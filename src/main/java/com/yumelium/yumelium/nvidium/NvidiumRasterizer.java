package com.yumelium.yumelium.nvidium;

import com.yumelium.yumelium.nvidium.gl.MeshRasterProgram;
import com.yumelium.yumelium.nvidium.gl.NvidiumGl;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gl.compat.FogHelper;
import me.jellysquid.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkFogMode;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.NVMeshShader;
import org.lwjgl.opengl.NVShaderBufferLoad;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

/**
 * Nvidium port — Milestone M3b. Draws ALL terrain (solid → cutout → translucent) with the NV mesh shader, replacing
 * vanilla terrain rendering entirely. Each pass is a separate ordered mesh-shader draw over its own work-item range
 * (see {@link NvidiumBackend}) with the right GL state: solid = opaque; cutout = alpha-test; translucent = blend +
 * depth-write off. Fragment samples the block atlas (unit 0) + lightmap (unit 1), both already bound by the vanilla
 * renderBlockLayer setup.
 *
 * <p>M4a (2026-08-01): optional GPU culling — a task shader (32 items per task workgroup) frustum-tests each work
 * item's section AABB and launches mesh workgroups only for survivors, with ORDER-PRESERVING compaction so the
 * CPU's far→near translucent section order survives (NV_mesh_shader rasterizes task WGs in dispatch order and
 * children in emission order). The AABB is origin + (-8..+24): the 16-bit vertex decode is {@code pos/2048 - 8}, so
 * that box is a strict superset of decodable geometry — the GPU cull can only drop items whose geometry cannot be
 * visible, correct by construction. The task stage is built in its own try/catch: if the lwjglx shim or driver
 * rejects it, the proven mesh-only program keeps running byte-identically (this slice doubles as the task-stage
 * go/no-go probe, M0 philosophy). Side effect: dispatch count becomes ceil(items/32), multiplying the
 * GL_MAX_DRAW_MESH_TASKS_COUNT_NV headroom by 32.</p>
 */
public final class NvidiumRasterizer {
    private static final NvidiumRasterizer INSTANCE = new NvidiumRasterizer();

    /** Uniform locations of one program variant (mesh-only or task+mesh). Missing uniforms resolve to -1 and the
     * matching glUniform* calls are silently ignored — the mesh-only variant simply has no frustum/count slots. */
    private static final class Uniforms {
        final int uProj, uModelView, uCameraBlock, uCameraFrac, uWorkItems, uGeometry, uBlockTex, uLightTex;
        final int uWorkItemBase, uAlphaTest, uTranslucent, uSortIndex, uUseSortIndex;
        final int uFogMode, uFogColor, uFogStart, uFogEnd, uFogDensity;
        final int uWorkItemCount, uFrustumPlanes;

        Uniforms(int id) {
            this.uProj = GL20C.glGetUniformLocation(id, "u_Proj");
            this.uModelView = GL20C.glGetUniformLocation(id, "u_ModelView");
            this.uCameraBlock = GL20C.glGetUniformLocation(id, "u_CameraBlock");
            this.uCameraFrac = GL20C.glGetUniformLocation(id, "u_CameraFrac");
            this.uWorkItems = GL20C.glGetUniformLocation(id, "u_WorkItems");
            this.uGeometry = GL20C.glGetUniformLocation(id, "u_Geometry");
            this.uBlockTex = GL20C.glGetUniformLocation(id, "u_BlockTex");
            this.uLightTex = GL20C.glGetUniformLocation(id, "u_LightTex");
            this.uWorkItemBase = GL20C.glGetUniformLocation(id, "u_WorkItemBase");
            this.uAlphaTest = GL20C.glGetUniformLocation(id, "u_AlphaTest");
            this.uTranslucent = GL20C.glGetUniformLocation(id, "u_Translucent");
            this.uSortIndex = GL20C.glGetUniformLocation(id, "u_SortIndex");
            this.uUseSortIndex = GL20C.glGetUniformLocation(id, "u_UseSortIndex");
            this.uFogMode = GL20C.glGetUniformLocation(id, "u_FogMode");
            this.uFogColor = GL20C.glGetUniformLocation(id, "u_FogColor");
            this.uFogStart = GL20C.glGetUniformLocation(id, "u_FogStart");
            this.uFogEnd = GL20C.glGetUniformLocation(id, "u_FogEnd");
            this.uFogDensity = GL20C.glGetUniformLocation(id, "u_FogDensity");
            this.uWorkItemCount = GL20C.glGetUniformLocation(id, "u_WorkItemCount");
            this.uFrustumPlanes = GL20C.glGetUniformLocation(id, "u_FrustumPlanes");
        }
    }

    private MeshRasterProgram program;         // mesh-only — the proven M3b path and the permanent fallback
    private MeshRasterProgram programCulled;   // task+mesh — null when the task stage failed to build (M4a)
    private Uniforms uniforms;
    private Uniforms uniformsCulled;
    private Uniforms active;
    private boolean gpuCullActive;
    private FloatBuffer matrixBuffer;
    private FloatBuffer frustumBuffer;
    private final Matrix4f mvpScratch = new Matrix4f();
    private final Vector4f planeScratch = new Vector4f();
    private boolean failed;

    private NvidiumRasterizer() {
    }

    public static NvidiumRasterizer instance() {
        return INSTANCE;
    }

    private static void log(String s) {
        SodiumClientMod.logger().info("[Nvidium M3] " + s);
    }

    /** @return whether the LAST beginDraw ran with GPU culling (F3 diagnostics). */
    public boolean gpuCullingActive() {
        return this.gpuCullActive;
    }

    private static boolean optionGpuCulling() {
        try {
            return SodiumClientMod.options().yumeliumPlus.nvidiumGpuCulling;
        } catch (Throwable t) {
            return false;
        }
    }

    private void ensureInit() {
        if (this.program != null || this.failed) {
            return;
        }
        try {
            this.program = new MeshRasterProgram(null, meshSrc(false), FRAG_SRC);
            this.uniforms = new Uniforms(this.program.id());
            this.matrixBuffer = MemoryUtil.memAllocFloat(16);
            this.frustumBuffer = MemoryUtil.memAllocFloat(24);
            log("full terrain rasterizer initialized (uBlockTex=" + this.uniforms.uBlockTex
                    + " uWorkItemBase=" + this.uniforms.uWorkItemBase + ")");
        } catch (Throwable t) {
            this.failed = true;
            log("rasterizer init failed (falling back to vanilla): " + t);
            NvidiumBackend.RENDER_TERRAIN = false;
            return;
        }
        // The task+mesh variant fails SOFT: mesh-only rendering is proven, the task stage is not — M4a doubles as
        // its go/no-go probe on the lwjglx shim. Never sets `failed`.
        try {
            this.programCulled = new MeshRasterProgram(TASK_SRC, meshSrc(true), FRAG_SRC);
            this.uniformsCulled = new Uniforms(this.programCulled.id());
            log("[M4a] task-shader GPU culling available");
        } catch (Throwable t) {
            this.programCulled = null;
            log("[M4a] task stage unavailable — GPU culling disabled (mesh-only path unaffected): " + t);
        }
    }

    private boolean beginDraw(SortedRenderLists lists, Matrix4fc projection, Matrix4fc modelView,
                              double camX, double camY, double camZ) {
        if (!NvidiumBackend.RENDER_TERRAIN || !NvidiumGl.SUPPORTED) {
            return false;
        }
        NvidiumBackend backend = NvidiumBackend.instance();
        if (!backend.isReady()) {
            return false;
        }
        ensureInit();
        if (this.failed) {
            return false;
        }

        this.gpuCullActive = this.programCulled != null && optionGpuCulling();
        MeshRasterProgram prog = this.gpuCullActive ? this.programCulled : this.program;
        this.active = this.gpuCullActive ? this.uniformsCulled : this.uniforms;
        prog.bind();

        projection.get(this.matrixBuffer);
        GL20C.glUniformMatrix4fv(this.active.uProj, false, this.matrixBuffer);
        modelView.get(this.matrixBuffer);
        GL20C.glUniformMatrix4fv(this.active.uModelView, false, this.matrixBuffer);

        // Build the work-item table from Sodium's occlusion-culled visible list (no redundant frustum scan here).
        backend.syncMetadataForDraw(lists, camX, camY, camZ);

        int cbx = (int) Math.floor(camX);
        int cby = (int) Math.floor(camY);
        int cbz = (int) Math.floor(camZ);
        GL20C.glUniform3i(this.active.uCameraBlock, cbx, cby, cbz);
        GL20C.glUniform3f(this.active.uCameraFrac, (float) (camX - cbx), (float) (camY - cby), (float) (camZ - cbz));

        GL20C.glUniform1i(this.active.uBlockTex, 0);
        GL20C.glUniform1i(this.active.uLightTex, 1);
        NVShaderBufferLoad.glUniformui64NV(this.active.uWorkItems, backend.metadataAddress());
        NVShaderBufferLoad.glUniformui64NV(this.active.uGeometry, backend.geometryAddress());
        NVShaderBufferLoad.glUniformui64NV(this.active.uSortIndex, backend.sortIndexAddress());
        GL20C.glUniform1i(this.active.uUseSortIndex, 0); // opaque passes never use the sort index; translucent may enable it

        // Fixed-function GL fog cannot touch a GLSL-460 mesh-shader program, so Nvidium terrain rendered with ZERO
        // fog — no atmospheric distance fog, and no underwater/lava EXP fog (the world looked like clear air from
        // under the water surface, 2026-07-31). Emulate it exactly like Sodium's ChunkShaderFogComponent: read the
        // current fog state through FogHelper each pass (setupFog ran just before renderBlockLayer, so this honors
        // both the Yumelium Fog toggle and its water/lava exemption for free) and apply fog.glsl's formulas in the
        // fragment. GL_EXP is approximated as EXP2, same as the Sodium path.
        ChunkFogMode fogMode = FogHelper.getFogMode();
        GL20C.glUniform1i(this.active.uFogMode, fogMode == ChunkFogMode.EXP2 ? 1 : fogMode == ChunkFogMode.SMOOTH ? 2 : 0);
        if (fogMode != ChunkFogMode.NONE) {
            float[] fogColor = FogHelper.getFogColor();
            GL20C.glUniform4f(this.active.uFogColor, fogColor[0], fogColor[1], fogColor[2], fogColor[3]);
            GL20C.glUniform1f(this.active.uFogStart, FogHelper.getFogStart());
            GL20C.glUniform1f(this.active.uFogEnd, FogHelper.getFogEnd());
            GL20C.glUniform1f(this.active.uFogDensity, FogHelper.getFogDensity());
        }

        if (this.gpuCullActive) {
            // Frustum planes of proj*modelView act on CAMERA-RELATIVE coordinates — exactly the space the mesh
            // shader's sectionRel (and the task shader's AABB) lives in, so no extra transform is needed. JOML's
            // frustumPlane yields inward-pointing plane normals: a box is visible iff its positive vertex is on or
            // above every plane. Unnormalized planes are fine for the sign test.
            this.mvpScratch.set(projection).mul(modelView);
            this.frustumBuffer.clear();
            for (int p = 0; p < 6; p++) {
                this.mvpScratch.frustumPlane(p, this.planeScratch);
                this.frustumBuffer.put(this.planeScratch.x).put(this.planeScratch.y)
                        .put(this.planeScratch.z).put(this.planeScratch.w);
            }
            this.frustumBuffer.flip();
            GL20C.glUniform4fv(this.active.uFrustumPlanes, this.frustumBuffer);
        }
        return true;
    }

    private void drawPass(int pass, int alphaTest, int translucent) {
        NvidiumBackend backend = NvidiumBackend.instance();
        int count = backend.passItemCount(pass);
        if (count <= 0) {
            return;
        }
        GL20C.glUniform1i(this.active.uWorkItemBase, backend.passItemBase(pass));
        GL20C.glUniform1i(this.active.uAlphaTest, alphaTest);
        GL20C.glUniform1i(this.active.uTranslucent, translucent);
        GL20C.glUniform1i(this.active.uWorkItemCount, count);
        // GPU culling: one TASK workgroup covers 32 work items and launches only the survivors as mesh children.
        NVMeshShader.glDrawMeshTasksNV(0, this.gpuCullActive ? (count + 31) / 32 : count);
    }

    /** Draws the solid + cutout passes (opaque). @return true if it handled the pass (skip vanilla). */
    public boolean drawSolidAndCutout(SortedRenderLists lists, Matrix4fc projection, Matrix4fc modelView,
                                      double camX, double camY, double camZ) {
        if (!beginDraw(lists, projection, modelView, camX, camY, camZ)) {
            return false;
        }
        try {
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(true);
            GL11.glDisable(GL11.GL_BLEND);
            // Back-face cull: Sodium's quads are wound like vanilla's GL_QUADS (CCW front), which vanilla renders with
            // cull enabled, so GL_BACK/GL_CCW (the default winding) is correct and halves rasterised solid triangles.
            GL11.glEnable(GL11.GL_CULL_FACE);
            GL11.glCullFace(GL11.GL_BACK);

            drawPass(NvidiumBackend.PASS_SOLID, 0, 0);
            drawPass(NvidiumBackend.PASS_CUTOUT, 1, 0);

            GL20C.glUseProgram(0);
            return true;
        } catch (Throwable t) {
            log("solid/cutout draw error (disabling): " + t);
            NvidiumBackend.RENDER_TERRAIN = false;
            return false;
        }
    }

    /** Draws the translucent pass (blended, depth-write off). @return true if it handled the pass (skip vanilla). */
    public boolean drawTranslucent(SortedRenderLists lists, Matrix4fc projection, Matrix4fc modelView,
                                   double camX, double camY, double camZ) {
        if (!beginDraw(lists, projection, modelView, camX, camY, camZ)) {
            return false;
        }
        try {
            // 2026-07-27 audit: state routed through GlStateManager, and the pass EXITS with the blend-on /
            // depth-write-off state vanilla established for the translucent window. The old raw-GL exit
            // (glDepthMask(true) + glDisable(GL_BLEND)) desynced GlStateManager's caches exactly across Forge's
            // pass-1 entity render window; vanilla's own renderWorldPass cleanup performs the flip afterwards
            // with the caches in sync.
            net.minecraft.client.renderer.GlStateManager.enableDepth();
            net.minecraft.client.renderer.GlStateManager.depthMask(false); // translucent surfaces don't occlude each other
            net.minecraft.client.renderer.GlStateManager.enableBlend();
            net.minecraft.client.renderer.GlStateManager.tryBlendFuncSeparate(
                    net.minecraft.client.renderer.GlStateManager.SourceFactor.SRC_ALPHA,
                    net.minecraft.client.renderer.GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                    net.minecraft.client.renderer.GlStateManager.SourceFactor.ONE,
                    net.minecraft.client.renderer.GlStateManager.DestFactor.ZERO);
            // Back-face cull like vanilla — otherwise thin double-sided translucent geometry (glass panes) draws its far
            // face through the near one (doubled tint). GL_BACK is the cull-face default.
            net.minecraft.client.renderer.GlStateManager.enableCull();

            // FULL sort mode: the mesh shader reads each quad through the per-quad sort-index buffer (back-to-front).
            GL20C.glUniform1i(this.active.uUseSortIndex, NvidiumBackend.instance().sortActive() ? 1 : 0);
            drawPass(NvidiumBackend.PASS_TRANSLUCENT, 0, 1);

            GL20C.glUseProgram(0);
            return true;
        } catch (Throwable t) {
            log("translucent draw error (disabling): " + t);
            NvidiumBackend.RENDER_TERRAIN = false;
            return false;
        }
    }

    // M4a task shader: 32 work items per task workgroup; frustum-tests each item's section AABB and emits only the
    // survivors, in ascending item order (order-preserving compaction — MANDATORY for the translucent pass, whose
    // far→near section order must survive; NV_mesh_shader rasterizes children in emission order).
    private static final String TASK_SRC =
            "#version 460\n" +
            "#extension GL_NV_mesh_shader : require\n" +
            "#extension GL_NV_gpu_shader5 : require\n" +
            "#extension GL_NV_shader_buffer_load : require\n" +
            "layout(local_size_x = 32) in;\n" +
            "uniform int* u_WorkItems;\n" +
            "uniform int u_WorkItemBase;\n" +
            "uniform int u_WorkItemCount;\n" +
            "uniform ivec3 u_CameraBlock;\n" +
            "uniform vec3 u_CameraFrac;\n" +
            "uniform vec4 u_FrustumPlanes[6];\n" +
            "taskNV out Task { uint itemIdx[32]; } OUT;\n" +
            "shared uint s_vis;\n" +
            "void main() {\n" +
            "    uint lid = gl_LocalInvocationID.x;\n" +
            "    if (lid == 0u) s_vis = 0u;\n" +
            "    barrier();\n" +
            "    uint gi = gl_WorkGroupID.x * 32u + lid;\n" +
            "    if (gi < uint(u_WorkItemCount)) {\n" +
            "        int wi = (u_WorkItemBase + int(gi)) * 8;\n" +
            // Section AABB = origin + (-8..+24): the 16-bit vertex decode is pos/2048-8, so geometry may legally
            // span that whole box — a strict superset, hence conservative (never culls potentially-visible items).
            "        vec3 lo = vec3(ivec3(u_WorkItems[wi+2], u_WorkItems[wi+3], u_WorkItems[wi+4]) - u_CameraBlock)\n" +
            "                  - u_CameraFrac - vec3(8.0);\n" +
            "        vec3 hi = lo + vec3(32.0);\n" +
            "        bool visible = true;\n" +
            "        for (int p = 0; p < 6 && visible; p++) {\n" +
            "            vec4 pl = u_FrustumPlanes[p];\n" +
            "            vec3 v = mix(lo, hi, greaterThan(pl.xyz, vec3(0.0)));\n" + // positive vertex
            "            visible = dot(pl.xyz, v) + pl.w >= 0.0;\n" +
            "        }\n" +
            "        if (visible) atomicOr(s_vis, 1u << lid);\n" +
            "    }\n" +
            "    barrier();\n" +
            "    if (lid == 0u) {\n" +
            "        uint mask = s_vis, n = 0u, base = gl_WorkGroupID.x * 32u;\n" +
            "        while (mask != 0u) { uint i = findLSB(mask); mask &= mask - 1u; OUT.itemIdx[n++] = base + i; }\n" +
            "        gl_TaskCountNV = n;\n" +
            "    }\n" +
            "}\n";

    /** Builds the mesh shader source; {@code tasked} adds the task payload input and indexes work items through it
     * (child i of a task workgroup renders survivor i) — the ONLY two differences from the proven mesh-only source,
     * assembled from one template so the variants can never drift apart. */
    private static String meshSrc(boolean tasked) {
        return "#version 460\n" +
            "#extension GL_NV_mesh_shader : require\n" +
            "#extension GL_NV_gpu_shader5 : require\n" +
            "#extension GL_NV_shader_buffer_load : require\n" +
            "layout(local_size_x = 32) in;\n" +
            "layout(triangles, max_vertices = 128, max_primitives = 64) out;\n" +
            "uniform int* u_WorkItems;\n" +
            "uniform uint* u_Geometry;\n" +
            "uniform int* u_SortIndex;\n" +
            "uniform int u_UseSortIndex;\n" +
            "uniform mat4 u_Proj;\n" +
            "uniform mat4 u_ModelView;\n" +
            "uniform ivec3 u_CameraBlock;\n" +
            "uniform vec3 u_CameraFrac;\n" +
            "uniform int u_WorkItemBase;\n" +
            (tasked ? "taskNV in Task { uint itemIdx[32]; } IN;\n" : "") +
            "layout(location = 0) out VertexData { vec4 color; vec2 uv; vec2 lightUV; float desaturate; float fragDist; } v_out[];\n" +
            "void main() {\n" +
            (tasked
                ? "    int wi = (u_WorkItemBase + int(IN.itemIdx[gl_WorkGroupID.x])) * 8;\n"
                : "    int wi = (u_WorkItemBase + int(gl_WorkGroupID.x)) * 8;\n") +
            "    int byteOffset = u_WorkItems[wi + 0];\n" +
            "    int numQuads = u_WorkItems[wi + 1];\n" +
            "    ivec3 origin = ivec3(u_WorkItems[wi + 2], u_WorkItems[wi + 3], u_WorkItems[wi + 4]);\n" +
            "    int sortOffset = u_WorkItems[wi + 5];\n" +
            "    vec3 sectionRel = vec3(origin - u_CameraBlock) - u_CameraFrac;\n" +
            "    mat4 mvp = u_Proj * u_ModelView;\n" +
            "    uint q = gl_LocalInvocationID.x;\n" +
            "    if (q < uint(numQuads)) {\n" +
            "        int quadIdx = (u_UseSortIndex == 1) ? u_SortIndex[sortOffset + int(q)] : int(q);\n" +
            "        for (uint v = 0u; v < 4u; v++) {\n" +
            "            uint ub = (uint(byteOffset) + uint(quadIdx * 80 + int(v) * 20)) >> 2u;\n" +
            "            uint w0 = u_Geometry[ub + 0u];\n" +
            "            uint w1 = u_Geometry[ub + 1u];\n" +
            "            uint w2 = u_Geometry[ub + 2u];\n" +
            "            uint w3 = u_Geometry[ub + 3u];\n" +
            "            uint w4 = u_Geometry[ub + 4u];\n" +
            "            float px = float(w0 & 0xFFFFu) * (1.0 / 2048.0) - 8.0;\n" +
            "            float py = float(w0 >> 16) * (1.0 / 2048.0) - 8.0;\n" +
            "            float pz = float(w1 & 0xFFFFu) * (1.0 / 2048.0) - 8.0;\n" +
            "            vec3 pos = sectionRel + vec3(px, py, pz);\n" +
            "            vec4 col = vec4(float(w2 & 0xFFu), float((w2 >> 8) & 0xFFu), float((w2 >> 16) & 0xFFu), float((w2 >> 24) & 0xFFu)) * (1.0 / 255.0);\n" +
            "            vec2 uv = vec2(float(w3 & 0xFFFFu), float(w3 >> 16)) * (1.0 / 32768.0);\n" +
            "            vec2 lc = vec2(float(w4 & 0xFFFFu), float(w4 >> 16));\n" +
            "            uint material = (w1 >> 16) & 0xFFu;\n" + // byte 6 = material flags
            "            float desat = ((material >> 3u) & 1u) != 0u ? 1.0 : 0.0;\n" + // bit 3 = desaturate (water)
            "            uint o = q * 4u + v;\n" +
            "            gl_MeshVerticesNV[o].gl_Position = mvp * vec4(pos, 1.0);\n" +
            "            v_out[o].color = col;\n" +
            "            v_out[o].uv = uv;\n" +
            "            v_out[o].lightUV = clamp(lc / 256.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0));\n" +
            "            v_out[o].desaturate = desat;\n" +
            // pos is already camera-relative → spherical frag distance, same as Sodium's getFragDistance(SPHERICAL).
            "            v_out[o].fragDist = length(pos);\n" +
            "        }\n" +
            "        uint pb = q * 6u;\n" +
            "        uint vb = q * 4u;\n" +
            "        gl_PrimitiveIndicesNV[pb + 0u] = vb + 0u;\n" +
            "        gl_PrimitiveIndicesNV[pb + 1u] = vb + 1u;\n" +
            "        gl_PrimitiveIndicesNV[pb + 2u] = vb + 2u;\n" +
            "        gl_PrimitiveIndicesNV[pb + 3u] = vb + 0u;\n" +
            "        gl_PrimitiveIndicesNV[pb + 4u] = vb + 2u;\n" +
            "        gl_PrimitiveIndicesNV[pb + 5u] = vb + 3u;\n" +
            "    }\n" +
            "    if (gl_LocalInvocationID.x == 0u) {\n" +
            "        gl_PrimitiveCountNV = uint(numQuads) * 2u;\n" +
            "    }\n" +
            "}\n";
    }

    private static final String FRAG_SRC =
            "#version 460\n" +
            "layout(location = 0) in VertexData { vec4 color; vec2 uv; vec2 lightUV; float desaturate; float fragDist; } v_in;\n" +
            "uniform sampler2D u_BlockTex;\n" +
            "uniform sampler2D u_LightTex;\n" +
            "uniform int u_AlphaTest;\n" +
            "uniform int u_Translucent;\n" +
            "uniform int u_FogMode;\n" + // 0 = none, 1 = EXP2 (water/lava), 2 = LINEAR (atmospheric)
            "uniform vec4 u_FogColor;\n" +
            "uniform float u_FogStart;\n" +
            "uniform float u_FogEnd;\n" +
            "uniform float u_FogDensity;\n" +
            "out vec4 fragColor;\n" +
            "void main() {\n" +
            "    vec4 tex = texture(u_BlockTex, v_in.uv);\n" +
            "    if (u_AlphaTest == 1 && tex.a < 0.5) { discard; }\n" +
            // Water desaturate (match Sodium's block_layer_opaque): grayscale the texture so the biome vertex colour
            // sets the hue → the calmer per-biome water instead of the raw vivid-blue texture.
            "    if (v_in.desaturate > 0.5) {\n" +
            "        float luma = dot(tex.rgb, vec3(0.299, 0.587, 0.114));\n" +
            "        float t = clamp((luma - 0.29) / 0.18, 0.0, 1.0);\n" +
            "        tex.rgb = vec3(0.65 + t * 0.35);\n" +
            "    }\n" +
            "    vec3 light = texture(u_LightTex, v_in.lightUV).rgb;\n" +
            "    vec3 rgb = tex.rgb * v_in.color.rgb * light * v_in.color.a;\n" +
            // Fog emulation — formulas copied from assets/sodium/shaders/include/fog.glsl (_exp2Fog / _linearFog),
            // arg order and all: EXP2 mixes (fog → frag), LINEAR mixes (frag → fog). Fragment alpha stays untouched.
            "    if (u_FogMode == 1) {\n" +
            "        float d = v_in.fragDist * u_FogDensity;\n" +
            "        float f = clamp(1.0 / exp2(d * d), 0.0, 1.0);\n" +
            "        rgb = mix(u_FogColor.rgb, rgb, f * u_FogColor.a);\n" +
            "    } else if (u_FogMode == 2 && v_in.fragDist > u_FogStart) {\n" +
            "        float f = v_in.fragDist < u_FogEnd ? smoothstep(u_FogStart, u_FogEnd, v_in.fragDist) : 1.0;\n" +
            "        rgb = mix(rgb, u_FogColor.rgb, f * u_FogColor.a);\n" +
            "    }\n" +
            "    float a = (u_Translucent == 1) ? tex.a : 1.0;\n" +
            "    fragColor = vec4(rgb, a);\n" +
            "}\n";
}
