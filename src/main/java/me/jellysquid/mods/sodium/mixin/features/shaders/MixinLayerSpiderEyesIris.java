package me.jellysquid.mods.sodium.mixin.features.shaders;

import com.yumelium.yumelium.shaders.pipeline.IrisPipeline;
import net.minecraft.client.renderer.entity.layers.LayerSpiderEyes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Yumelium/Iris: routes the spider's fullbright eye layer through the pack's {@code gbuffers_spidereyes} (emissive —
 * bloom + no shading) instead of the plain entity shading it got via gbuffers_entities. The explicit descriptor skips
 * the generic bridge method. See {@code IrisPipeline.beginSpiderEyes} for the attachment/material handling.
 */
@Mixin(LayerSpiderEyes.class)
public class MixinLayerSpiderEyesIris {
    @Inject(method = "doRenderLayer(Lnet/minecraft/entity/monster/EntitySpider;FFFFFFF)V", at = @At("HEAD"))
    private void yumelium$beginEyes(CallbackInfo ci) {
        IrisPipeline.instance().beginSpiderEyes();
    }

    @Inject(method = "doRenderLayer(Lnet/minecraft/entity/monster/EntitySpider;FFFFFFF)V", at = @At("RETURN"))
    private void yumelium$endEyes(CallbackInfo ci) {
        IrisPipeline.instance().endSpiderEyes();
    }
}
