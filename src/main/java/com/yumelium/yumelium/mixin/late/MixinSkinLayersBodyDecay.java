package com.yumelium.yumelium.mixin.late;

import com.yumelium.yumelium.compat.SkinLayersDecayCompat;
import net.minecraft.client.entity.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * LATE mixin: draws the Betweenlands decay overlay over 3dSkinLayers' extruded BODY geometry.
 *
 * <p>Why here and not inside Betweenlands' own decay layer: 3dSkinLayers builds its geometry lazily in
 * {@code setupModel}, which only runs inside this very method. Measured in game (2026-08-05),
 * {@code PlayerSettings.getSkinLayers()} is still {@code null} while Betweenlands' {@code LayerDecay} draws, so
 * an overlay issued from there has nothing to paint on. At RETURN of this method the parts exist and are in
 * their final pose.</p>
 *
 * <p>The alpha is not recomputed: {@link SkinLayersDecayCompat} snapshots the colour Betweenlands set for its
 * own pass earlier in the same frame, so the layers pulse in exact lockstep with the body.</p>
 */
@Mixin(targets = "dev.tr7zw.skinlayers.renderlayers.BodyLayerFeatureRenderer")
public class MixinSkinLayersBodyDecay {

    @Inject(method = "doRenderLayer(Lnet/minecraft/client/entity/AbstractClientPlayer;FFFFFFF)V",
            at = @At("RETURN"), require = 1)
    private void yumelium$bodyDecay(AbstractClientPlayer player, float limbSwing, float limbSwingAmount,
                                    float partialTicks, float ageInTicks, float netHeadYaw, float headPitch,
                                    float scale, CallbackInfo ci) {
        SkinLayersDecayCompat.drawBodyDecay(player, scale);
    }

}
