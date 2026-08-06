package com.yumelium.yumelium.mixin.late;

import com.yumelium.yumelium.compat.SkinLayersDecayCompat;
import net.minecraft.client.renderer.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * LATE mixin (Betweenlands present only — see YumeliumLateMixins): snapshots the decay pass's alpha so the
 * 3dSkinLayers overlay can pulse in exact lockstep with the body.
 *
 * <p>{@code DecayRenderHandler$LayerDecay.doRenderLayer} sets {@code color(1, 1, 1, alpha)} before drawing the
 * vanilla model and {@code color(1, 1, 1, 1)} after. Redirecting both lets us read the real alpha — derived
 * from the decay level and {@code ticksExisted} — without reimplementing that formula, and the reset call
 * naturally clears the snapshot.</p>
 *
 * <p>The actual overlay is NOT drawn here: 3dSkinLayers builds its geometry lazily and it is still null at this
 * point in the frame. See {@link MixinSkinLayersBodyDecay}.</p>
 */
@Mixin(targets = "thebetweenlands.client.handler.DecayRenderHandler$LayerDecay")
public class MixinLayerDecaySkinLayers {

    /** Per-player snapshot reset — see {@link SkinLayersDecayCompat#clearDecayAlpha}. Runs before the decay pass
     * decides whether to draw, so a decay-free player never inherits the previous player's alpha. */
    @Inject(method = "doRenderLayer(Lnet/minecraft/client/entity/AbstractClientPlayer;FFFFFFF)V",
            at = @At("HEAD"), require = 1)
    private void yumelium$clearDecayAlpha(net.minecraft.client.entity.AbstractClientPlayer player, float limbSwing,
                                          float limbSwingAmount, float partialTicks, float ageInTicks,
                                          float netHeadYaw, float headPitch, float scale, CallbackInfo ci) {
        SkinLayersDecayCompat.clearDecayAlpha();
    }

    @Redirect(method = "doRenderLayer(Lnet/minecraft/client/entity/AbstractClientPlayer;FFFFFFF)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GlStateManager;color(FFFF)V"),
            require = 2)
    private void yumelium$captureDecayAlpha(float red, float green, float blue, float alpha) {
        GlStateManager.color(red, green, blue, alpha);
        SkinLayersDecayCompat.captureDecayAlpha(alpha);
    }

}
