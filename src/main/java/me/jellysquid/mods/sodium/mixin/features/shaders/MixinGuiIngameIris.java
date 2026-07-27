package me.jellysquid.mods.sodium.mixin.features.shaders;

import com.yumelium.yumelium.shaders.pipeline.IrisPipeline;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Yumelium/Iris: suppresses the vanilla screen-edge VIGNETTE while a shader pack that declares {@code vignette = false}
 * is active (Complementary does — its own post chain handles exposure/edges, and the vanilla darkening on top of it
 * double-darkens the frame vs real Iris).
 *
 * <p><b>The cancel must reproduce the method's STATE CONTRACT.</b> renderVignette is not just a draw: the HUD code
 * after it RELIES on its cleanup — it re-enables the depth test, re-enables alpha, resets the draw colour and restores
 * the standard alpha blend func (Forge's own no-vignette branch still calls {@code enableDepth()}). A bare HEAD cancel
 * skipped all of that, so the hotbar's 3D block icons rendered under leftover state roulette — the "cube icons look
 * glass-like with shaders on" bug. Skip only the darkening quad; perform the same state transitions vanilla would.</p>
 */
@Mixin(GuiIngame.class)
public class MixinGuiIngameIris {
    @Inject(method = "renderVignette", at = @At("HEAD"), cancellable = true)
    private void yumelium$suppressVignette(float lightLevel, ScaledResolution scaledRes, CallbackInfo ci) {
        if (IrisPipeline.instance().suppressVignette()) {
            // The exact tail of vanilla renderVignette, minus the vignette quad itself.
            GlStateManager.depthMask(true);
            GlStateManager.enableDepth();
            GlStateManager.enableAlpha();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.tryBlendFuncSeparate(
                    GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                    GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
            ci.cancel();
        }
    }
}
