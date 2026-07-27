package me.jellysquid.mods.sodium.client.render.chunk;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import me.jellysquid.mods.sodium.client.gl.attribute.GlVertexFormat;
import me.jellysquid.mods.sodium.client.gl.compat.FogHelper;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.gl.device.RenderDevice;
import me.jellysquid.mods.sodium.client.gl.shader.*;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.render.chunk.shader.*;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkMeshAttribute;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.minecraft.util.ResourceLocation;
import java.util.Map;

public abstract class ShaderChunkRenderer implements ChunkRenderer {
    private final Map<ChunkShaderOptions, GlProgram<ChunkShaderInterface>> programs = new Object2ObjectOpenHashMap<>();

    protected final ChunkVertexType vertexType;
    protected final GlVertexFormat<ChunkMeshAttribute> vertexFormat;

    protected final RenderDevice device;

    protected GlProgram<ChunkShaderInterface> activeProgram;

    // Iris shadow pass: a single lightweight program (the pack's own shadow vertex + a minimal alpha-test fragment),
    // compiled lazily on the first shadow-pass draw and reused for the solid + cutout shadow sub-passes. Bound instead of
    // the heavy gbuffers_terrain program so the depth-only shadow map skips the pack's per-fragment lighting. Null until
    // compiled; shadowProgramFailed latches when the pack has no shadow program or it fails to compile (→ terrain fallback).
    private GlProgram<ChunkShaderInterface> shadowProgram;
    private boolean shadowProgramFailed;

    public ShaderChunkRenderer(RenderDevice device, ChunkVertexType vertexType) {
        this.device = device;
        this.vertexType = vertexType;
        this.vertexFormat = vertexType.getVertexFormat();
    }

    protected GlProgram<ChunkShaderInterface> compileProgram(ChunkShaderOptions options) {
        GlProgram<ChunkShaderInterface> program = this.programs.get(options);

        if (program == null) {
            this.programs.put(options, program = this.createTerrainProgram(options));
        }

        return program;
    }

    /**
     * Iris/Oculus port (M5): when the shader pipeline is active, terrain is drawn with the shader pack's
     * {@code gbuffers_terrain} (transformed to Sodium's chunk-shader dialect) instead of Sodium's own chunk shader.
     * Falls back to a shipped Sodium-dialect terrain shader, then to Sodium's default, on any failure — so terrain
     * never breaks. A renderer reload on toggling shaders gives this a fresh program cache so the choice is picked up.
     */
    private GlProgram<ChunkShaderInterface> createTerrainProgram(ChunkShaderOptions options) {
        if (com.yumelium.yumelium.shaders.pipeline.IrisPipeline.instance().isEnabled()) {
            // TRANSLUCENT pass → the pack's gbuffers_water (waves/reflections/refraction) instead of gbuffers_terrain.
            boolean translucent = options.pass() == me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses.TRANSLUCENT;
            if (translucent) {
                String[] waterSrc = com.yumelium.yumelium.shaders.pipeline.IrisPipeline.instance().packWaterSources();
                if (waterSrc != null) {
                    String vsh = com.yumelium.yumelium.shaders.pipeline.TerrainShaderTransformer.transformWaterVertex(waterSrc[0]);
                    String fsh = com.yumelium.yumelium.shaders.pipeline.TerrainShaderTransformer.transformWaterFragment(waterSrc[1]);
                    try {
                        ChunkShaderOptions noFog = new ChunkShaderOptions(ChunkFogMode.NONE, options.pass(), options.vertexType());
                        GlProgram<ChunkShaderInterface> program = this.createShaderFromSource("iris_pack_water", vsh, fsh, noFog);
                        me.jellysquid.mods.sodium.client.SodiumClientMod.logger().info("[Iris] translucent water rendered by transformed pack gbuffers_water");
                        return program;
                    } catch (Throwable t) {
                        // Dump the FINAL transformed water GLSL ON FAILURE ONLY (2026-07-27 audit: this used to run
                        // unconditionally — ~1.4MB x 2 written to run/client on every water program compile).
                        try {
                            java.nio.file.Files.write(java.nio.file.Paths.get("iris_water_dump.vsh"), vsh.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            java.nio.file.Files.write(java.nio.file.Paths.get("iris_water_dump.fsh"), fsh.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        } catch (Throwable dumpErr) {
                            me.jellysquid.mods.sodium.client.SodiumClientMod.logger().warn("[Iris] failed to dump water GLSL", dumpErr);
                        }
                        // Log the actual GLSL compile/link error (Sodium's GlShader logs the info log at WARN; make the
                        // failure unmistakable here and point at the dumped source).
                        me.jellysquid.mods.sodium.client.SodiumClientMod.logger().error(
                                "[Iris] pack gbuffers_water FAILED to compile/link (see the 'Shader compilation log' WARN above + run/client/iris_water_dump.*); falling back to gbuffers_terrain for translucent", t);
                    }
                }
            }
            String[] packSrc = com.yumelium.yumelium.shaders.pipeline.IrisPipeline.instance().packTerrainSources();
            if (packSrc != null) {
                try {
                    String vsh = com.yumelium.yumelium.shaders.pipeline.TerrainShaderTransformer.transformVertex(packSrc[0]);
                    String fsh = com.yumelium.yumelium.shaders.pipeline.TerrainShaderTransformer.transformFragment(packSrc[1]);
                    // The pack terrain shader doesn't use Sodium's fog (the shader pipeline's composite applies fog), so
                    // compile it with fog mode NONE — otherwise ChunkShaderInterface's fog component tries to bind
                    // u_FogColor (absent in the pack shader) and throws "No uniform exists".
                    ChunkShaderOptions noFog = new ChunkShaderOptions(ChunkFogMode.NONE, options.pass(), options.vertexType());
                    GlProgram<ChunkShaderInterface> program = this.createShaderFromSource("iris_pack_terrain", vsh, fsh, noFog);
                    // The transformed terrain shader can write a view-space normal to colortex1 (currently gated off in
                    // IrisPipeline until non-terrain geometry also writes normals — see NORMAL_GBUFFER_ENABLED).
                    com.yumelium.yumelium.shaders.pipeline.IrisPipeline.instance().setTerrainWritesNormal(true);
                    me.jellysquid.mods.sodium.client.SodiumClientMod.logger().info("[Iris] terrain rendered by transformed pack gbuffers_terrain");
                    return program;
                } catch (Throwable t) {
                    com.yumelium.yumelium.shaders.pipeline.IrisPipeline.instance().setTerrainWritesNormal(false);
                    me.jellysquid.mods.sodium.client.SodiumClientMod.logger().warn("[Iris] pack gbuffers_terrain failed to compile; using iris_terrain fallback", t);
                }
            }
            try {
                return this.createShader("blocks/iris_terrain", options);
            } catch (Throwable t) {
                me.jellysquid.mods.sodium.client.SodiumClientMod.logger().warn("[Iris] iris_terrain fallback failed; using Sodium terrain shader", t);
            }
        }
        return this.createShader("blocks/block_layer_opaque", options);
    }

    private GlProgram<ChunkShaderInterface> createShader(String path, ChunkShaderOptions options) {
        ShaderConstants constants = options.constants();

        GlShader vertShader = ShaderLoader.loadShader(ShaderType.VERTEX,
                new ResourceLocation("sodium", path + ".vsh"), constants);

        GlShader fragShader = ShaderLoader.loadShader(ShaderType.FRAGMENT,
                new ResourceLocation("sodium", path + ".fsh"), constants);

        return this.linkChunkProgram(vertShader, fragShader, options);
    }

    private GlProgram<ChunkShaderInterface> createShaderFromSource(String name, String vshSource, String fshSource, ChunkShaderOptions options) {
        ShaderConstants constants = options.constants();

        GlShader vertShader = ShaderLoader.loadShaderFromSource(ShaderType.VERTEX,
                new ResourceLocation("sodium", name + ".vsh"), vshSource, constants);

        GlShader fragShader = ShaderLoader.loadShaderFromSource(ShaderType.FRAGMENT,
                new ResourceLocation("sodium", name + ".fsh"), fshSource, constants);

        return this.linkChunkProgram(vertShader, fragShader, options);
    }

    private GlProgram<ChunkShaderInterface> linkChunkProgram(GlShader vertShader, GlShader fragShader, ChunkShaderOptions options) {
        try {
            return GlProgram.builder(new ResourceLocation("sodium", "chunk_shader"))
                    .attachShader(vertShader)
                    .attachShader(fragShader)
                    .bindAttribute("a_PosId", ChunkShaderBindingPoints.ATTRIBUTE_POSITION_ID)
                    .bindAttribute("a_Color", ChunkShaderBindingPoints.ATTRIBUTE_COLOR)
                    .bindAttribute("a_TexCoord", ChunkShaderBindingPoints.ATTRIBUTE_BLOCK_TEXTURE)
                    .bindAttribute("a_LightCoord", ChunkShaderBindingPoints.ATTRIBUTE_LIGHT_TEXTURE)
                    // Iris shader mode: packed normal attribute + sprite-mid tex coord (foliage waving) + a second fragment
                    // output for the view-space normal (colortex1). All harmless no-ops for Sodium-native shaders that
                    // don't declare them (an unused bound attribute location is simply not read).
                    .bindAttribute("a_Normal", ChunkShaderBindingPoints.ATTRIBUTE_NORMAL)
                    .bindAttribute("a_MidTexCoord", ChunkShaderBindingPoints.ATTRIBUTE_MID_TEX_COORD)
                    .bindAttribute("a_BlockId", ChunkShaderBindingPoints.ATTRIBUTE_BLOCK_ID)
                    .bindAttribute("a_Tangent", ChunkShaderBindingPoints.ATTRIBUTE_TANGENT)
                    .bindAttribute("a_MidBlock", ChunkShaderBindingPoints.ATTRIBUTE_MID_BLOCK)
                    .bindFragmentData("fragColor", ChunkShaderBindingPoints.FRAG_COLOR)
                    .bindFragmentData("iris_FragNormal", ChunkShaderBindingPoints.FRAG_NORMAL)
                    .link((shader) -> new ChunkShaderInterface(shader, options));
        } finally {
            vertShader.delete();
            fragShader.delete();
        }
    }

    protected void begin(TerrainRenderPass pass) {
        pass.startDrawing();

        ChunkShaderOptions options = new ChunkShaderOptions(FogHelper.getFogMode(), pass, this.vertexType);

        // Iris shadow pass: bind the lightweight pack shadow shader (position + wave + alpha-tested depth) instead of the
        // full gbuffers_terrain, so the depth-only shadow map isn't rendered with the heavy per-fragment lighting. Falls
        // back to the terrain program if the pack has no shadow program or it failed to compile.
        var pipeline = com.yumelium.yumelium.shaders.pipeline.IrisPipeline.instance();
        GlProgram<ChunkShaderInterface> program = null;
        if (pipeline.isEnabled() && pipeline.isShadowPass()) {
            program = this.getShadowProgram(options);
        }
        if (program == null) {
            program = this.compileProgram(options);
        }

        boolean translucent = pass == me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses.TRANSLUCENT;
        // Tell the pipeline WHICH pass this is BEFORE setupState(), because setupState() → applyTerrainUniforms() feeds the
        // pack its `renderStage`, and the pack's voxelizers gate on it (the WSR volume takes only SOLID/CUTOUT stages, the
        // puddle map only TRANSLUCENT). Setting it after the bind — as onTerrainPassBegin below does — left every pass
        // reporting the PREVIOUS pass's stage: solid terrain claimed TRANSLUCENT and water claimed SOLID, i.e. exactly
        // inverted, so water kept polluting the WSR volume while real geometry was excluded from it.
        pipeline.setTerrainPass(translucent);

        this.activeProgram = program;
        this.activeProgram.bind();
        this.activeProgram.getInterface()
                .setupState();

        // Iris: capture the terrain shader's view-space normal into colortex1 for this pass (no-op when shaders are off).
        pipeline.onTerrainPassBegin(translucent);
    }

    /**
     * Iris/Oculus port: lazily compiles the lightweight shadow-pass program — the pack's own {@code shadow} vertex
     * (transformed to Sodium's chunk-shader dialect: position + {@code DoWave} + the pack's shadow-map distortion) paired
     * with a minimal alpha-test-only fragment (the shadow map is depth-only, so the pack's shadow-colour fragment work is
     * dropped). Cached and reused for the solid + cutout shadow sub-passes. Returns {@code null} — caller uses the terrain
     * program — if the pack has no shadow program or it fails to compile/link (latched in {@code shadowProgramFailed}).
     */
    private GlProgram<ChunkShaderInterface> getShadowProgram(ChunkShaderOptions options) {
        if (this.shadowProgram == null && !this.shadowProgramFailed) {
            String shadowVsh = com.yumelium.yumelium.shaders.pipeline.IrisPipeline.instance().packShadowVertexSource();
            if (shadowVsh == null) {
                this.shadowProgramFailed = true; // pack ships no shadow program → always use the terrain fallback
            } else {
                try {
                    String vsh = com.yumelium.yumelium.shaders.pipeline.TerrainShaderTransformer.transformShadowVertex(shadowVsh);
                    // Fog NONE (depth-only map, no fog) + a fixed SOLID pass so ChunkShaderInterface never treats it as the
                    // water pass; one program serves both the solid and cutout shadow sub-passes.
                    ChunkShaderOptions shadowOptions = new ChunkShaderOptions(ChunkFogMode.NONE,
                            me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses.SOLID, this.vertexType);
                    this.shadowProgram = this.createShaderFromSource("iris_pack_shadow", vsh,
                            com.yumelium.yumelium.shaders.pipeline.TerrainShaderTransformer.shadowFragment(
                                    com.yumelium.yumelium.shaders.pipeline.IrisPipeline.instance()
                                            .isBooleanOptionOn("CONNECTED_GLASS_EFFECT")), shadowOptions);
                    me.jellysquid.mods.sodium.client.SodiumClientMod.logger().info(
                            "[Iris shadow] shadow pass uses the lightweight pack shadow shader");
                } catch (Throwable t) {
                    this.shadowProgramFailed = true;
                    me.jellysquid.mods.sodium.client.SodiumClientMod.logger().warn(
                            "[Iris shadow] pack shadow shader failed to compile; shadow pass falls back to gbuffers_terrain", t);
                }
            }
        }
        return this.shadowProgram;
    }

    protected void end(TerrainRenderPass pass) {
        boolean translucent = pass == me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses.TRANSLUCENT;
        com.yumelium.yumelium.shaders.pipeline.IrisPipeline.instance().onTerrainPassEnd(translucent);

        this.activeProgram.unbind();
        this.activeProgram = null;

        pass.endDrawing();
    }

    @Override
    public void delete(CommandList commandList) {
        this.programs.values()
                .forEach(GlProgram::delete);
        // Iris shadow pass program (compiled outside the options-keyed cache).
        if (this.shadowProgram != null) {
            this.shadowProgram.delete();
            this.shadowProgram = null;
        }
        this.shadowProgramFailed = false;
    }

    @Override
    public ChunkVertexType getVertexType() {
        return this.vertexType;
    }
}
