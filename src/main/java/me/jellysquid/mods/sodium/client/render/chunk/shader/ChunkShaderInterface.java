package me.jellysquid.mods.sodium.client.render.chunk.shader;

import com.yumelium.yumelium.shaders.gl.GlslProgram;
import com.yumelium.yumelium.shaders.pipeline.IrisPipeline;
import me.jellysquid.mods.sodium.client.gl.GlObject;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformFloat3v;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformInt;
import me.jellysquid.mods.sodium.client.gl.shader.uniform.GlUniformMatrix4f;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import java.util.EnumMap;
import java.util.Map;

/**
 * A forward-rendering shader program for chunks.
 */
public class ChunkShaderInterface {
    // Spare texture unit the transformed pack terrain program samples the shadow map from (units 0/1 are the block atlas
    // and lightmap during the terrain draw, so the shadow map can't reuse them).
    private static final int SHADOW_UNIT = 4;

    private final Map<ChunkShaderTextureSlot, GlUniformInt> uniformTextures;

    private final GlUniformMatrix4f uniformModelViewMatrix;
    private final GlUniformMatrix4f uniformProjectionMatrix;
    private final GlUniformFloat3v uniformRegionOffset;

    // Real-pack sampler aliases: shader packs name the block atlas `tex`/`gtexture` and the lightmap `lightmap`, and rely
    // on Iris/OptiFine binding them to units 0/1. Sodium only binds u_BlockTex/u_LightTex, so a pack that samples `tex`
    // gets an unset sampler (undefined unit on some drivers → wrong/gray). Bind these aliases to 0/1 too. Tolerant (see
    // GlProgram.bindUniform): inert if the program doesn't declare them (e.g. Sodium's own / the built-in shaders).
    private final GlUniformInt uniformTexAlias;
    private final GlUniformInt uniformGtextureAlias;
    private final GlUniformInt uniformLightmapAlias;

    // The transformed pack terrain program wrapped so IrisPipeline can feed it the pack's scene uniforms (matrices,
    // camera, sun/light, time, fog/sky) — the same set the composites get. Complementary lights terrain inside
    // gbuffers_terrain (not purely deferred), so without these its lighting computes to ~black. Non-owning wrapper;
    // glGetUniformLocation just returns -1 (a silent no-op) for the names Sodium's own shaders don't declare.
    private final GlslProgram packUniforms;

    // The fog shader component used by this program in order to setup the appropriate GL state
    private final ChunkShaderFogComponent fogShader;

    // True for the TRANSLUCENT pass → this program is the transformed pack gbuffers_water, which reads the scene buffers
    // (colortex/depthtex/noisetex) for SSR reflections/refraction/water fog; bind those in setupState.
    private final boolean isWaterPass;

    public ChunkShaderInterface(ShaderBindingContext context, ChunkShaderOptions options) {
        this.isWaterPass = options.pass() == me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses.TRANSLUCENT;
        this.uniformModelViewMatrix = context.bindUniform("u_ModelViewMatrix", GlUniformMatrix4f::new);
        this.uniformProjectionMatrix = context.bindUniform("u_ProjectionMatrix", GlUniformMatrix4f::new);
        this.uniformRegionOffset = context.bindUniform("u_RegionOffset", GlUniformFloat3v::new);

        this.uniformTextures = new EnumMap<>(ChunkShaderTextureSlot.class);
        this.uniformTextures.put(ChunkShaderTextureSlot.BLOCK, context.bindUniform("u_BlockTex", GlUniformInt::new));
        this.uniformTextures.put(ChunkShaderTextureSlot.LIGHT, context.bindUniform("u_LightTex", GlUniformInt::new));

        this.uniformTexAlias = context.bindUniform("tex", GlUniformInt::new);
        this.uniformGtextureAlias = context.bindUniform("gtexture", GlUniformInt::new);
        this.uniformLightmapAlias = context.bindUniform("lightmap", GlUniformInt::new);

        // context is the GlProgram being linked (implements ShaderBindingContext, extends GlObject); its handle is set by
        // the time this constructor runs (bindUniform above already used it), so we can wrap it for uniform feeding.
        this.packUniforms = GlslProgram.wrap("iris_pack_terrain", ((GlObject) context).handle());

        this.fogShader = options.fog().getFactory().apply(context);
    }

    @Deprecated // the shader interface should not modify pipeline state
    public void setupState() {
        // NOTE(yumelium): 1.16.5 bound the lightmap to texture unit 2, but 1.12.2's EntityRenderer#enableLightmap
        // binds it to OpenGlHelper.lightmapTexUnit == GL_TEXTURE1 (unit index 1). Sampling unit 2 (empty) made all
        // terrain pitch black. The block atlas stays on unit 0 (OpenGlHelper.defaultTexUnit).
        this.bindTexture(ChunkShaderTextureSlot.BLOCK, 0);
        this.bindTexture(ChunkShaderTextureSlot.LIGHT, 1);

        // Pack sampler aliases → the same units (inert no-ops if the program doesn't use these names).
        this.uniformTexAlias.setInt(0);
        this.uniformGtextureAlias.setInt(0);
        this.uniformLightmapAlias.setInt(1);

        // Feed the pack terrain program its scene uniforms + shadow map (only in shader mode with live targets; harmless
        // no-ops on Sodium's own shaders since they don't declare these names). The program is already bound here, so the
        // wrapper's glUniform* calls land on it.
        IrisPipeline pipeline = IrisPipeline.instance();
        if (pipeline.isEnabled() && pipeline.hasTargets()) {
            int shadowTex = pipeline.shadowDepthTextureId();
            if (shadowTex != 0) {
                // Bind the shadow map to the spare unit with RAW GL, then switch the active unit back to 0 (also RAW —
                // GlStateManager's cache still believes unit 0 is active, so its setActiveTexture(0) would be a no-op and
                // leave GL stuck on the spare unit, desyncing the terrain draw's atlas sampling).
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + SHADOW_UNIT);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, shadowTex);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
            }
            pipeline.applyTerrainUniforms(this.packUniforms, SHADOW_UNIT);
            // Water (translucent) also samples the scene buffers for reflections/refraction/water fog.
            if (this.isWaterPass) {
                pipeline.applyWaterSceneSamplers(this.packUniforms);
                pipeline.logWaterProgramUniforms(this.packUniforms); // one-time: which uniforms the water wants but we miss
            }
        }

        this.fogShader.setup();
    }

    @Deprecated(forRemoval = true) // should be handled properly in GFX instead.
    private void bindTexture(ChunkShaderTextureSlot slot, int textureId) {
        var uniform = this.uniformTextures.get(slot);
        uniform.setInt(textureId);
    }

    public void setProjectionMatrix(Matrix4fc matrix) {
        this.uniformProjectionMatrix.set(matrix);
    }

    public void setModelViewMatrix(Matrix4fc matrix) {
        this.uniformModelViewMatrix.set(matrix);
    }

    public void setRegionOffset(float x, float y, float z) {
        this.uniformRegionOffset.set(x, y, z);
    }
}
