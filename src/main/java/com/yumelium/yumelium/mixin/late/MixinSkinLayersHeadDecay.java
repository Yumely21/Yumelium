package com.yumelium.yumelium.mixin.late;

import com.yumelium.yumelium.compat.SkinLayersDecayCompat;
import net.minecraft.client.entity.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * LATE mixin: the head half of {@link MixinSkinLayersBodyDecay} — the hat layer is built by its own
 * {@code setupModel} inside this method, so it needs the same treatment as the body.
 */
@Mixin(targets = "dev.tr7zw.skinlayers.renderlayers.HeadLayerFeatureRenderer")
public class MixinSkinLayersHeadDecay {

    @Inject(method = "doRenderLayer(Lnet/minecraft/client/entity/AbstractClientPlayer;FFFFFFF)V",
            at = @At("RETURN"), require = 1)
    private void yumelium$headDecay(AbstractClientPlayer player, float limbSwing, float limbSwingAmount,
                                    float partialTicks, float ageInTicks, float netHeadYaw, float headPitch,
                                    float scale, CallbackInfo ci) {
        SkinLayersDecayCompat.drawHeadDecay(player, scale);
    }

}
