package me.jellysquid.mods.sodium.mixin.features.shaders;

import com.yumelium.yumelium.shaders.pipeline.IrisPipeline;
import net.minecraft.client.renderer.ItemRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Yumelium/Iris: routes the first-person hand + held item through the shader pack's {@code gbuffers_hand} program when
 * shaders are active.
 *
 * <p>{@code renderItemInFirstPerson} draws the arm and the held item/block in fixed-function immediate mode; it runs
 * inside {@code EntityRenderer.renderWorld}, so the geometry lands in the pipeline's G-buffer (colortex0). We bind the
 * pack program at HEAD and unbind at RETURN, exactly like the sky. Because 1.12.2 is a compatibility profile, the pack's
 * {@code #version 120} hand shader reads the fixed-function builtins ({@code gl_Vertex}, {@code gl_Color},
 * {@code gl_MultiTexCoord0/1}, {@code gl_ModelViewProjectionMatrix}, {@code gl_NormalMatrix}, {@code gl_Normal}) that the
 * immediate-mode hand feeds it — no transform needed. The bound program bypasses fixed-function {@code GL_LIGHTING}, so
 * the pack shader must recompute the hand's item lighting itself (the built-in does). All no-ops when shaders are off.</p>
 */
@Mixin(ItemRenderer.class)
public class MixinItemRendererIris {
    @Inject(method = "renderItemInFirstPerson(F)V", at = @At("HEAD"))
    private void yumelium$handBegin(float partialTicks, CallbackInfo ci) {
        IrisPipeline.instance().beginHand();
    }

    @Inject(method = "renderItemInFirstPerson(F)V", at = @At("RETURN"))
    private void yumelium$handEnd(float partialTicks, CallbackInfo ci) {
        IrisPipeline.instance().endHand();
    }

    /**
     * Suppresses the vanilla first-person WATER-TEXTURE overlay while a pack declaring {@code underwaterOverlay = false}
     * is active — the pack's own underwater fog/colour grading replaces it, and the flat water tile drawn over the
     * camera fights that look (real Iris skips it the same way).
     */
    @Inject(method = "renderWaterOverlayTexture(F)V", at = @At("HEAD"), cancellable = true)
    private void yumelium$suppressWaterOverlay(float partialTicks, CallbackInfo ci) {
        if (IrisPipeline.instance().suppressUnderwaterOverlay()) {
            ci.cancel();
        }
    }
}
