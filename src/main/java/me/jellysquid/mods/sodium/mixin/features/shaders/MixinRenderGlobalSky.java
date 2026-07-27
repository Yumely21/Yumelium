package me.jellysquid.mods.sodium.mixin.features.shaders;

import com.yumelium.yumelium.shaders.pipeline.IrisPipeline;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Yumelium/Iris: routes the vanilla immediate-mode sky through the shader pack's sky programs when shaders are active.
 *
 * <p>{@code renderSky} draws the sky in phases separated by texture-enable state: untextured (sky dome, sunrise/sunset
 * glow, stars, void) and textured (sun, moon). We redirect those {@code GlStateManager.disableTexture2D()} /
 * {@code enableTexture2D()} calls to also bind the matching pack program — {@code gbuffers_skybasic} for untextured,
 * {@code gbuffers_skytextured} for textured. Because 1.12.2 is a compatibility profile, the pack's {@code #version 120}
 * sky shaders read the fixed-function builtins ({@code gl_Vertex}, {@code gl_Color}, {@code gl_MultiTexCoord0},
 * {@code gl_ModelViewProjectionMatrix}) that the immediate-mode sky feeds them — no transform needed. The programs are
 * unbound on return so terrain (which binds its own program) is unaffected. All no-ops when shaders are off.</p>
 */
@Mixin(RenderGlobal.class)
public class MixinRenderGlobalSky {
    @Shadow
    private VertexBuffer sky2VBO;
    @Shadow
    private int glSkyList2;

    @Inject(method = "renderSky(FI)V", at = @At("HEAD"))
    private void yumelium$skyBegin(float partialTicks, int pass, CallbackInfo ci) {
        IrisPipeline.instance().beginSky();
    }

    /**
     * Skips vanilla's below-horizon "bottom sky" planes (sky2VBO / glSkyList2) when the pack's GetSky fill is active:
     * the fill now runs BEFORE the celestial quads (see the sun branch of the bindTexture redirect), and these planes
     * draw AFTER them — they would repaint the below-horizon strip with the flat colour the fill exists to replace.
     * With the fill off (shaders off, nether/END, program missing) they draw vanilla.
     */
    @Redirect(
            method = "renderSky(FI)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/vertex/VertexBuffer;drawArrays(I)V")
    )
    private void yumelium$skyDrawArrays(VertexBuffer buffer, int mode) {
        if (buffer == this.sky2VBO && IrisPipeline.instance().skyBelowFillActive()) {
            return;
        }
        buffer.drawArrays(mode);
    }

    @Redirect(
            method = "renderSky(FI)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlStateManager;callList(I)V")
    )
    private void yumelium$skyCallList(int list) {
        if (list == this.glSkyList2 && IrisPipeline.instance().skyBelowFillActive()) {
            return;
        }
        GlStateManager.callList(list);
    }

    @Redirect(
            method = "renderSky(FI)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlStateManager;disableTexture2D()V")
    )
    private void yumelium$skyDisableTexture() {
        GlStateManager.disableTexture2D();
        IrisPipeline.instance().skyBindBasic();
    }

    @Redirect(
            method = "renderSky(FI)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlStateManager;enableTexture2D()V")
    )
    private void yumelium$skyEnableTexture() {
        GlStateManager.enableTexture2D();
        IrisPipeline.instance().skyBindTextured();
    }

    /**
     * MC binds {@code sun.png} then {@code moon_phases.png} between the (single) {@code enableTexture2D} that binds
     * skytextured and each celestial quad. We tag the bound program's {@code renderStage} accordingly (SUN=4, MOON=5) so
     * the pack identifies the sun/moon by stage instead of its own geometric test — which fails here because the pack
     * bakes a -40° sunPathRotation, offsetting its internal sunVec from MC's real sun quad. Other bindTexture calls in
     * renderSky (if any) are passed through untouched.
     */
    @Redirect(
            method = "renderSky(FI)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/texture/TextureManager;bindTexture(Lnet/minecraft/util/ResourceLocation;)V")
    )
    private void yumelium$skyBindTexture(TextureManager textureManager, ResourceLocation location) {
        String path = location.toString();
        if (path.contains("sun")) {
            // BEFORE the celestial quads: paint the pack's GetSky over the horizon strip + below-horizon sky. This
            // used to run at renderSky RETURN, where it ERASED the freshly drawn sun/moon/stars below VdotU=0.15
            // (~8.6°): the moon "popped in" only once above the horizon. Drawing the fill first keeps the celestial
            // bodies visible down to (and through) the horizon, like 1.20.1. Vanilla's below-horizon sky2 planes —
            // which draw AFTER the celestial section — are skipped above so they cannot repaint the strip either.
            IrisPipeline.instance().preCelestialFill();
        }
        textureManager.bindTexture(location);
        if (path.contains("sun")) {
            IrisPipeline.instance().setSkyRenderStage(4);  // MC_RENDER_STAGE_SUN
            // At the sun bind MC's modelview is V·A·B (before any quad draws) — tilt the celestial frame here so the disc
            // (and moon/stars, which follow inside the same pushMatrix) align with the pack's sunPathRotation atmosphere.
            IrisPipeline.instance().applyCelestialSunPathTilt();
        } else if (path.contains("moon")) {
            IrisPipeline.instance().setSkyRenderStage(5);  // MC_RENDER_STAGE_MOON
        }
    }

    @Inject(method = "renderSky(FI)V", at = @At("RETURN"))
    private void yumelium$skyEnd(float partialTicks, int pass, CallbackInfo ci) {
        IrisPipeline.instance().endSky();
    }
}
