package com.yumelium.yumelium.mixin.late;

import com.yumelium.yumelium.compat.SkinLayersDecayCompat;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * LATE mixin (Betweenlands present only — see YumeliumLateMixins): the THIRD-PERSON half of the decay-overlay
 * compat, entirely on Betweenlands' own decay layer (zero mixins into 3dSkinLayers classes).
 *
 * <p>Lifecycle per rendered player: HEAD clears the alpha snapshot; the colour redirect captures the exact
 * pulsing alpha Betweenlands computes (its opaque resets are ignored — clearing on them was the original
 * consumed-too-early bug); TAIL consumes it and paints the 3D skin layers. TAIL is the single shared return, so
 * it also fires on the no-decay early-outs — the consume-side alpha gate (≤0 → skip) is what tells those apart.
 * At TAIL the voxel geometry is guaranteed built and posed: 3dSkinLayers draws at renderModel TAIL, which runs
 * BEFORE the vanilla layer list containing this decay layer (bytecode-verified order — the first port assumed
 * the opposite and consumed a stale alpha).</p>
 */
@Mixin(targets = "thebetweenlands.client.handler.DecayRenderHandler$LayerDecay")
public class MixinLayerDecaySkinLayers {

    @Inject(method = "doRenderLayer(Lnet/minecraft/client/entity/AbstractClientPlayer;FFFFFFF)V",
            at = @At("HEAD"), require = 1)
    private void yumelium$clearDecayAlpha(AbstractClientPlayer player, float limbSwing, float limbSwingAmount,
                                          float partialTicks, float ageInTicks, float netHeadYaw, float headPitch,
                                          float scale, CallbackInfo ci) {
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

    @Inject(method = "doRenderLayer(Lnet/minecraft/client/entity/AbstractClientPlayer;FFFFFFF)V",
            at = @At("TAIL"), require = 1)
    private void yumelium$drawLayersOverlay(AbstractClientPlayer player, float limbSwing, float limbSwingAmount,
                                            float partialTicks, float ageInTicks, float netHeadYaw, float headPitch,
                                            float scale, CallbackInfo ci) {
        SkinLayersDecayCompat.drawLayersOverlay(player);
    }
}
