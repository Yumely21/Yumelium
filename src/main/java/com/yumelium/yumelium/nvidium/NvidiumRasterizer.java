package com.yumelium.yumelium.nvidium;

import com.yumelium.yumelium.nvidium.gl.BoxRasterProgram;
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
import org.lwjgl.opengl.GL30C;
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
        final int uFrame, uOcclusionEnabled, uNearRadiusSq, uCullEnabled, uDiagTerms;

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
            this.uFrame = GL20C.glGetUniformLocation(id, "u_Frame");
            this.uOcclusionEnabled = GL20C.glGetUniformLocation(id, "u_OcclusionEnabled");
            this.uNearRadiusSq = GL20C.glGetUniformLocation(id, "u_NearRadiusSq");
            this.uCullEnabled = GL20C.glGetUniformLocation(id, "u_CullEnabled");
            this.uDiagTerms = GL20C.glGetUniformLocation(id, "u_DiagTerms");
        }
    }

    /** SSBO binding points shared by the task shader (reads stamps) and the box pass (writes stamps / reads boxes). */
    private static final int SSBO_VISIBILITY = 0;
    private static final int SSBO_BOX_INSTANCES = 1;
    /** Sections whose centre is within this many blocks are never occlusion-culled: with the camera inside (or right
     * against) a section the box test is unreliable — its far wall can sit behind the very geometry we are standing
     * in — and close-range popping is the most visible failure there is. */
    private static final float NEAR_ALWAYS_VISIBLE = 48.0F;

    /** TEMP DIAGNOSTIC (2026-08-01, black distant leaves): false-colour which factor of the shaded product is
     * degenerate — RED lightmap, BLUE atlas sample, GREEN vertex colour, YELLOW vertex alpha (AO), WHITE all fine
     * (so the darkening is the fog blend). Answered 2026-08-01: BLUE — the atlas sample itself, in BOTH backends,
     * which pointed at MC's mipmap generation (see MixinTextureUtilMipmap). Left in for the next such hunt. */
    private static final boolean DIAG_TERMS = false;

    private MeshRasterProgram program;         // mesh-only — the proven M3b path and the permanent fallback
    private MeshRasterProgram programCulled;   // task+mesh — null when the task stage failed to build (M4a)
    private Uniforms uniforms;
    private Uniforms uniformsCulled;
    private Uniforms active;
    private boolean gpuCullActive;
    /** Whether the cull runs in the TASK stage (vs inside the mesh shader) — drives the dispatch shape and F3. */
    private boolean taskCullActive;
    // M4b: the box-raster occlusion pass. Its own program (vertex+fragment), its own empty VAO (the boxes are
    // generated from gl_VertexID/gl_InstanceID — binding an empty VAO stops whatever attribute arrays the previous
    // draw left enabled from being fetched), and a frame clock the stamps are compared against.
    private BoxRasterProgram boxProgram;
    private int boxUProj, boxUModelView, boxUCameraBlock, boxUCameraFrac, boxUFrame;
    private int boxVao;
    private final java.nio.ByteBuffer colorMaskScratch = org.lwjgl.BufferUtils.createByteBuffer(4);
    // Never 0 — that value is the "never tested" stamp sentinel. Skipping 0 on wrap makes exactly one frame's
    // comparison off by one, ~248 days into a continuous session; deliberately not worth handling.
    private int frameCounter = 1;
    private boolean occlusionActive;
    private boolean lastOcclusionActive;
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

    /** @return whether the LAST beginDraw also applied the box-raster occlusion test (F3 diagnostics). */
    public boolean occlusionCullingActive() {
        return this.occlusionActive;
    }

    /** @return whether culling ran in the task stage rather than inside the mesh shader (F3 diagnostics). */
    public boolean taskStageCulling() {
        return this.taskCullActive;
    }

    private static boolean optionGpuCulling() {
        try {
            return SodiumClientMod.options().yumeliumPlus.nvidiumGpuCulling;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean optionOcclusionCulling() {
        try {
            return SodiumClientMod.options().yumeliumPlus.nvidiumOcclusionCulling;
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
        // M4b occlusion pass — also fails soft: without it the stamps stay at the sentinel and the task shader's
        // occlusion term is simply never enabled.
        try {
            this.boxProgram = new BoxRasterProgram(BOX_VERT_SRC, BOX_FRAG_SRC);
            int id = this.boxProgram.id();
            this.boxUProj = GL20C.glGetUniformLocation(id, "u_Proj");
            this.boxUModelView = GL20C.glGetUniformLocation(id, "u_ModelView");
            this.boxUCameraBlock = GL20C.glGetUniformLocation(id, "u_CameraBlock");
            this.boxUCameraFrac = GL20C.glGetUniformLocation(id, "u_CameraFrac");
            this.boxUFrame = GL20C.glGetUniformLocation(id, "u_Frame");
            this.boxVao = GL30C.glGenVertexArrays();
            log("[M4b] box-raster occlusion available");
        } catch (Throwable t) {
            this.boxProgram = null;
            log("[M4b] occlusion pass unavailable — occlusion culling disabled: " + t);
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

        // GPU culling runs either in the task stage (one dispatch per 32 items, survivors launched as mesh children)
        // or, when the driver rejects the task shader, inside the mesh shader itself (culled workgroups emit zero
        // primitives). Same test, same AABB — only the dispatch shape and the saved workgroup launch differ.
        this.gpuCullActive = optionGpuCulling();
        this.taskCullActive = this.gpuCullActive && this.programCulled != null;
        MeshRasterProgram prog = this.taskCullActive ? this.programCulled : this.program;
        this.active = this.taskCullActive ? this.uniformsCulled : this.uniforms;
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
        GL20C.glUniform1i(this.active.uDiagTerms, DIAG_TERMS ? 1 : 0);
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
        // Only the mesh-side variant declares this; on the tasked program the location is -1 and the call no-ops.
        GL20C.glUniform1i(this.active.uCullEnabled, this.gpuCullActive && !this.taskCullActive ? 1 : 0);

        // M4b occlusion: the task shader compares each section's stamp against the current frame. The stamps come
        // from the box pass at the end of the opaque draw, so the SOLID/CUTOUT passes read frame-1 data (hysteresis
        // covers it) and TRANSLUCENT reads this frame's. Keyed on the OPTION, not on this frame's instance count —
        // a momentarily empty visible set must not read as "switched off" and burn a full clearVisibility on the
        // way back (drawVisibilityBoxes skips an empty list on its own).
        this.occlusionActive = this.gpuCullActive && optionOcclusionCulling()
                && this.boxProgram != null && backend.visBufferId() != 0;
        if (this.occlusionActive != this.lastOcclusionActive) {
            if (this.occlusionActive) {
                // Stamps left over from before the feature was switched off would read as "long occluded" and blank
                // the world for a frame; the sentinel means "never tested → visible".
                backend.clearVisibility();
            }
            this.lastOcclusionActive = this.occlusionActive;
        }
        if (this.gpuCullActive) {
            org.lwjgl.opengl.GL30C.glUniform1ui(this.active.uFrame, this.frameCounter);
            GL20C.glUniform1i(this.active.uOcclusionEnabled, this.occlusionActive ? 1 : 0);
            GL20C.glUniform1f(this.active.uNearRadiusSq, NEAR_ALWAYS_VISIBLE * NEAR_ALWAYS_VISIBLE);
            if (backend.visBufferId() != 0) {
                // Bound whenever the culled program runs — its task stage declares the block even when the
                // occlusion term is switched off, and an SSBO block should always have a live binding.
                org.lwjgl.opengl.GL30C.glBindBufferBase(org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER,
                        SSBO_VISIBILITY, backend.visBufferId());
            }
        }
        return true;
    }

    /**
     * M4b: rasterize every visible section's AABB against the depth buffer the opaque terrain just filled, with
     * colour and depth writes masked off. A box that produces a depth-passing fragment stamps the current frame
     * into its section's visibility entry (the fragment shader forces {@code early_fragment_tests}, without which
     * the SSBO write would disable early-Z and stamp occluded boxes too). Sections whose stamp goes stale are then
     * culled by the task shader — this is what removes hill-behind-hill and canopy overdraw that Sodium's
     * graph-based occlusion cannot see.
     */
    private void drawVisibilityBoxes(Matrix4fc projection, Matrix4fc modelView, double camX, double camY, double camZ) {
        NvidiumBackend backend = NvidiumBackend.instance();
        int instances = backend.boxInstanceCount();
        int instanceBuffer = backend.boxInstanceBufferId();
        if (!this.occlusionActive || instances <= 0 || instanceBuffer == 0) {
            return;
        }

        this.boxProgram.bind();
        projection.get(this.matrixBuffer);
        GL20C.glUniformMatrix4fv(this.boxUProj, false, this.matrixBuffer);
        modelView.get(this.matrixBuffer);
        GL20C.glUniformMatrix4fv(this.boxUModelView, false, this.matrixBuffer);
        int cbx = (int) Math.floor(camX);
        int cby = (int) Math.floor(camY);
        int cbz = (int) Math.floor(camZ);
        GL20C.glUniform3i(this.boxUCameraBlock, cbx, cby, cbz);
        GL20C.glUniform3f(this.boxUCameraFrac, (float) (camX - cbx), (float) (camY - cby), (float) (camZ - cbz));
        org.lwjgl.opengl.GL30C.glUniform1ui(this.boxUFrame, this.frameCounter);
        org.lwjgl.opengl.GL30C.glBindBufferRange(org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER,
                SSBO_BOX_INSTANCES, instanceBuffer, backend.boxInstanceOffset(), backend.boxInstanceRangeBytes());

        // Raw GL, restored to exactly the values drawSolidAndCutout established a few lines above. A raw set + raw
        // restore to the same value cannot desync GlStateManager's caches (the 2026-07-27 audit lesson), whereas
        // GlStateManager calls here could: its cull/mask caches know nothing about the raw state this runs inside,
        // so a cached "already disabled" would silently skip the disable and leave back faces culled — fatal for a
        // box the camera sits inside.
        int prevVao = org.lwjgl.opengl.GL11C.glGetInteger(org.lwjgl.opengl.GL30C.GL_VERTEX_ARRAY_BINDING);
        // The colour mask is NOT all-true in every frame — vanilla's anaglyph 3D renders each eye with a channel
        // mask around the whole world pass — and GlStateManager.colorMask is a skip-on-match cache, so restoring a
        // hardcoded all-true would both corrupt the eye pass and desync that cache permanently. Read it back.
        this.colorMaskScratch.clear();
        org.lwjgl.opengl.GL11C.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, this.colorMaskScratch);
        boolean prevR = this.colorMaskScratch.get(0) != 0;
        boolean prevG = this.colorMaskScratch.get(1) != 0;
        boolean prevB = this.colorMaskScratch.get(2) != 0;
        boolean prevA = this.colorMaskScratch.get(3) != 0;
        GL11.glColorMask(false, false, false, false);
        GL11.glDepthMask(false);
        GL11.glDisable(GL11.GL_CULL_FACE);
        // An empty VAO: the boxes are generated from gl_VertexID/gl_InstanceID + an SSBO, so any attribute arrays a
        // previous draw left enabled must not be fetched.
        org.lwjgl.opengl.GL30C.glBindVertexArray(this.boxVao);
        org.lwjgl.opengl.GL31C.glDrawArraysInstanced(GL11.GL_TRIANGLES, 0, 36, instances);
        org.lwjgl.opengl.GL30C.glBindVertexArray(prevVao);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glDepthMask(true);
        GL11.glColorMask(prevR, prevG, prevB, prevA);

        // The stamps are incoherent SSBO stores: make them visible to the task shader (this frame's translucent
        // pass, and every pass next frame).
        org.lwjgl.opengl.GL42C.glMemoryBarrier(org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
        org.lwjgl.opengl.GL30C.glBindBufferBase(org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER,
                SSBO_BOX_INSTANCES, 0);
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
        // With the task stage: one TASK workgroup per 32 work items, survivors launched as mesh children. Without
        // it: one mesh workgroup per work item as always (the mesh shader culls itself).
        NVMeshShader.glDrawMeshTasksNV(0, this.taskCullActive ? (count + 31) / 32 : count);
    }

    /** Draws the solid + cutout passes (opaque). @return true if it handled the pass (skip vanilla). */
    public boolean drawSolidAndCutout(SortedRenderLists lists, Matrix4fc projection, Matrix4fc modelView,
                                      double camX, double camY, double camZ) {
        // The frame clock the occlusion stamps are compared against — advanced once per frame, here, because this
        // is the first Nvidium draw of the frame. drawTranslucent reuses the same value.
        if (++this.frameCounter == 0) {
            this.frameCounter = 1; // 0 is the "never tested" sentinel
        }
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

            // Opaque terrain has filled the depth buffer — test this frame's section boxes against it.
            drawVisibilityBoxes(projection, modelView, camX, camY, camZ);

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
            // M4b occlusion: one stamp per section visibility index, written by the box-raster pass.
            "layout(std430, binding = 0) readonly buffer Visibility { uint stamps[]; };\n" +
            "uniform uint u_Frame;\n" +
            "uniform int u_OcclusionEnabled;\n" +
            "uniform float u_NearRadiusSq;\n" +
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
            // Occlusion term. Skipped for sections that are NEW to the CPU visible set (work item 7): their box has
            // not been rasterized yet, so their stamp says nothing — without this, spinning the camera would blank
            // every newly-entering section for a frame. Also skipped close to the camera, where the box test is
            // unreliable (the camera can be inside the box). Stamp 0 = never tested = visible, which is what makes a
            // never-arriving stamp degrade to "no culling" instead of to missing terrain.
            "        if (visible && u_OcclusionEnabled == 1 && u_WorkItems[wi + 7] == 0) {\n" +
            "            vec3 c = lo + vec3(16.0);\n" +
            "            if (dot(c, c) > u_NearRadiusSq) {\n" +
            "                uint stamp = stamps[u_WorkItems[wi + 6]];\n" +
            "                visible = (stamp == 0u) || (u_Frame - stamp <= 2u);\n" + // hysteresis: 2 frames
            "            }\n" +
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
            (tasked ? "taskNV in Task { uint itemIdx[32]; } IN;\n"
                    // Mesh-side culling: the same frustum + occlusion test the task shader does, run per WORKGROUP
                    // instead of per task lane. Used whenever the task stage is unavailable — NVIDIA's shader
                    // compiler rejects our task shader on some drivers with an internal assembly error, and without
                    // this the whole GPU-culling feature would be dormant there. A culled workgroup emits zero
                    // primitives, so everything after the test (128 vertex decodes + rasterization) is skipped; only
                    // the workgroup launch itself remains, which is what the task stage would have saved on top.
                    : "uniform vec4 u_FrustumPlanes[6];\n"
                    + "uniform int u_CullEnabled;\n"
                    + "layout(std430, binding = 0) readonly buffer Visibility { uint stamps[]; };\n"
                    + "uniform uint u_Frame;\n"
                    + "uniform int u_OcclusionEnabled;\n"
                    + "uniform float u_NearRadiusSq;\n") +
            // Same table as sodium's include/chunk_material.glsl — the fragment must reproduce Sodium's per-material
            // sampling exactly or the two backends disagree on foliage (see the mipBias note below).
            "const float ALPHA_CUTOFF[4] = float[4](0.0, 0.1, 0.5, 1.0);\n" +
            "uniform sampler2D u_LightTex;\n" +
            "layout(location = 0) out VertexData { vec4 color; vec2 uv; vec3 light; float desaturate;"
                    + " float fragDist; float mipBias; float alphaCutoff; } v_out[];\n" +
            "void main() {\n" +
            (tasked
                ? "    int wi = (u_WorkItemBase + int(IN.itemIdx[gl_WorkGroupID.x])) * 8;\n"
                : "    int wi = (u_WorkItemBase + int(gl_WorkGroupID.x)) * 8;\n") +
            "    int byteOffset = u_WorkItems[wi + 0];\n" +
            "    int numQuads = u_WorkItems[wi + 1];\n" +
            "    ivec3 origin = ivec3(u_WorkItems[wi + 2], u_WorkItems[wi + 3], u_WorkItems[wi + 4]);\n" +
            "    int sortOffset = u_WorkItems[wi + 5];\n" +
            "    vec3 sectionRel = vec3(origin - u_CameraBlock) - u_CameraFrac;\n" +
            (tasked ? ""
                    : "    if (u_CullEnabled == 1) {\n"
                    + "        vec3 lo = sectionRel - vec3(8.0);\n"   // decode range is -8..+24 around the origin
                    + "        vec3 hi = lo + vec3(32.0);\n"
                    + "        bool visible = true;\n"
                    + "        for (int p = 0; p < 6 && visible; p++) {\n"
                    + "            vec4 pl = u_FrustumPlanes[p];\n"
                    + "            vec3 v = mix(lo, hi, greaterThan(pl.xyz, vec3(0.0)));\n" // positive vertex
                    + "            visible = dot(pl.xyz, v) + pl.w >= 0.0;\n"
                    + "        }\n"
                    + "        if (visible && u_OcclusionEnabled == 1 && u_WorkItems[wi + 7] == 0) {\n"
                    + "            vec3 c = sectionRel + vec3(8.0);\n" // section centre, camera-relative
                    + "            if (dot(c, c) > u_NearRadiusSq) {\n"
                    + "                uint stamp = stamps[u_WorkItems[wi + 6]];\n"
                    + "                visible = (stamp == 0u) || (u_Frame - stamp <= 2u);\n"
                    + "            }\n"
                    + "        }\n"
                    + "        if (!visible) {\n"
                    + "            if (gl_LocalInvocationID.x == 0u) { gl_PrimitiveCountNV = 0u; }\n"
                    + "            return;\n"
                    + "        }\n"
                    + "    }\n") +
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
            // Atlas LOD bias — sodium's _material_mip_bias, bit for bit. Non-mipped materials (the CUTOUT layer:
            // grass, crops, plants) sample far down the mip chain because MC's atlas mips average the transparent
            // BLACK texels surrounding a cutout sprite into the visible ones; without this they darken at grazing
            // angles (2026-08-01). NOTE the bias is ADDED to the derivative LOD — it is not a level clamp.
            "            uint cutoffIdx = (material >> 1u) & 3u;\n" +
            "            float mipBias = ((material >> 0u) & 1u) != 0u ? 0.0 : -4.0;\n" +
            // bits 1-2 = alpha cutoff index; CUTOUT is 0.1, not the 0.5 this shader used to hardcode.
            "            float alphaCut = ALPHA_CUTOFF[cutoffIdx];\n" +
            "            uint o = q * 4u + v;\n" +
            "            gl_MeshVerticesNV[o].gl_Position = mvp * vec4(pos, 1.0);\n" +
            "            v_out[o].color = col;\n" +
            "            v_out[o].uv = uv;\n" +
            // Sample the lightmap HERE, per vertex, and interpolate the resulting colour — exactly what sodium's
            // block_layer_opaque.vsh does. Interpolating the COORDINATE and fetching per fragment (what this shader
            // used to do) is not equivalent: MC binds the lightmap with legacy GL_CLAMP, whose border is BLACK, and
            // this clamp range is the outermost texel CENTRES, i.e. zero margin. Outdoor terrain sits exactly on the
            // lower bound (blocklight 0), so any interpolation undershoot — which distant foliage, a mass of thin
            // sub-pixel triangles, is precisely the geometry to produce — blends that black border straight into the
            // light term. Leaves going black with distance while Sodium stayed correct (2026-08-01).
            "            v_out[o].light = texture(u_LightTex, clamp(lc / 256.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0))).rgb;\n" +
            "            v_out[o].desaturate = desat;\n" +
            "            v_out[o].mipBias = mipBias;\n" +
            "            v_out[o].alphaCutoff = alphaCut;\n" +
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

    // M4b box-raster occlusion program. The cube is generated from gl_VertexID (12 explicit triangles — winding is
    // irrelevant with culling off, and an explicit table cannot silently degenerate the way a hand-written triangle
    // strip can) and positioned from the per-section instance data, using the SAME AABB the task shader culls with.
    private static final String BOX_VERT_SRC =
            "#version 430\n" +
            "layout(std430, binding = 1) readonly buffer BoxInstances { ivec4 boxes[]; };\n" +
            "uniform mat4 u_Proj;\n" +
            "uniform mat4 u_ModelView;\n" +
            "uniform ivec3 u_CameraBlock;\n" +
            "uniform vec3 u_CameraFrac;\n" +
            "flat out uint v_VisIdx;\n" +
            // Corner bits: x = c & 1, y = (c >> 1) & 1, z = (c >> 2) & 1.
            "const int CUBE[36] = int[36](\n" +
            "    0,1,3, 0,3,2,   4,5,7, 4,7,6,\n" +
            "    0,2,6, 0,6,4,   1,3,7, 1,7,5,\n" +
            "    0,1,5, 0,5,4,   2,3,7, 2,7,6);\n" +
            "void main() {\n" +
            "    ivec4 box = boxes[gl_InstanceID];\n" +
            "    v_VisIdx = uint(box.x);\n" +
            "    int c = CUBE[gl_VertexID];\n" +
            "    vec3 corner = vec3(float(c & 1), float((c >> 1) & 1), float((c >> 2) & 1));\n" +
            "    vec3 lo = vec3(box.yzw - u_CameraBlock) - u_CameraFrac - vec3(8.0);\n" +
            "    gl_Position = u_Proj * u_ModelView * vec4(lo + corner * 32.0, 1.0);\n" +
            "}\n";

    private static final String BOX_FRAG_SRC =
            "#version 430\n" +
            // MANDATORY: an SSBO write makes this shader "have side effects", which by default DISABLES the early
            // depth test — the stamp would then be written for occluded boxes too and nothing would ever be culled.
            "layout(early_fragment_tests) in;\n" +
            "layout(std430, binding = 0) writeonly buffer Visibility { uint stamps[]; };\n" +
            "uniform uint u_Frame;\n" +
            "flat in uint v_VisIdx;\n" +
            "void main() {\n" +
            "    stamps[v_VisIdx] = u_Frame;\n" + // every writer stores the same value — no atomics needed
            "}\n";

    private static final String FRAG_SRC =
            "#version 460\n" +
            "layout(location = 0) in VertexData { vec4 color; vec2 uv; vec3 light; float desaturate;"
                    + " float fragDist; float mipBias; float alphaCutoff; } v_in;\n" +
            "uniform sampler2D u_BlockTex;\n" +
            "uniform int u_AlphaTest;\n" +
            "uniform int u_Translucent;\n" +
            "uniform int u_FogMode;\n" + // 0 = none, 1 = EXP2 (water/lava), 2 = LINEAR (atmospheric)
            "uniform vec4 u_FogColor;\n" +
            "uniform float u_FogStart;\n" +
            "uniform float u_FogEnd;\n" +
            "uniform float u_FogDensity;\n" +
            "uniform int u_DiagTerms;\n" +
            "out vec4 fragColor;\n" +
            "void main() {\n" +
            // Alpha-tested foliage samples the BASE level explicitly. textureLod is a hard level select; the mip
            // BIAS used before is only added to the derivative LOD, so it never actually pinned the level — distant
            // leaves still landed several mips down, where the atlas's transparent surroundings blend into the
            // sprite and turn it black. Solid/translucent keep normal mipmapping (no transparent neighbours to
            // bleed). Trade-off: distant foliage aliases like "Mipmap Levels: OFF" does (2026-08-01).
            "    vec4 tex = (v_in.alphaCutoff > 0.0) ? textureLod(u_BlockTex, v_in.uv, 0.0)\n" +
            "                                       : texture(u_BlockTex, v_in.uv, v_in.mipBias);\n" +
            "    if (u_AlphaTest == 1 && tex.a < v_in.alphaCutoff) { discard; }\n" +
            // Water desaturate (match Sodium's block_layer_opaque): grayscale the texture so the biome vertex colour
            // sets the hue → the calmer per-biome water instead of the raw vivid-blue texture.
            "    if (v_in.desaturate > 0.5) {\n" +
            "        float luma = dot(tex.rgb, vec3(0.299, 0.587, 0.114));\n" +
            "        float t = clamp((luma - 0.29) / 0.18, 0.0, 1.0);\n" +
            "        tex.rgb = vec3(0.65 + t * 0.35);\n" +
            "    }\n" +
            "    vec3 rgb = tex.rgb * v_in.color.rgb * v_in.light * v_in.color.a;\n" +
            // DIAGNOSTIC (u_DiagTerms=1): false-colour which FACTOR of the product above is degenerate, so a single
            // look at the affected geometry names the culprit instead of another round of guessing. Runs before fog
            // and returns, so WHITE means the shaded colour is fine and the darkening must come from the fog blend.
            "    if (u_DiagTerms == 1) {\n" +
            "        vec3 dbg = vec3(0.0);\n" +
            "        if (max(max(v_in.light.r, v_in.light.g), v_in.light.b) < 0.05) dbg.r = 1.0;\n" +      // RED   = lightmap
            "        if (max(max(tex.r, tex.g), tex.b) < 0.05) dbg.b = 1.0;\n" +                          // BLUE  = atlas sample
            "        if (max(max(v_in.color.r, v_in.color.g), v_in.color.b) < 0.05) dbg.g = 1.0;\n" +     // GREEN = vertex colour
            "        if (v_in.color.a < 0.05) dbg = vec3(1.0, 1.0, 0.0);\n" +                             // YELLOW= vertex alpha (AO)
            "        if (dbg == vec3(0.0)) dbg = vec3(1.0);\n" +                                          // WHITE = all fine -> fog
            "        fragColor = vec4(dbg, 1.0);\n" +
            "        return;\n" +
            "    }\n" +
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
            // Output the REAL texture alpha, as sodium's block_layer_opaque.fsh does. (This is cosmetic parity, not
            // a filter: vanilla runs disableAlpha() around the SOLID layer call that both backends draw solid AND
            // cutout inside, so the fixed-function alpha test is OFF here — the shader discard above is the only
            // cutout gate. Blending is off too, so the framebuffer alpha is otherwise unused.)
            "    fragColor = vec4(rgb, tex.a);\n" +
            "}\n";
}
