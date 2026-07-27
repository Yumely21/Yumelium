package me.jellysquid.mods.sodium.mixin.features.shaders;

import com.yumelium.yumelium.shaders.pipeline.IrisPipeline;
import net.minecraft.client.renderer.entity.layers.LayerEndermanEyes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Yumelium/Iris: enderman eye layer → {@code gbuffers_spidereyes} (see {@link MixinLayerSpiderEyesIris}). */
@Mixin(LayerEndermanEyes.class)
public class MixinLayerEndermanEyesIris {
    @Inject(method = "doRenderLayer(Lnet/minecraft/entity/monster/EntityEnderman;FFFFFFF)V", at = @At("HEAD"))
    private void yumelium$beginEyes(CallbackInfo ci) {
        IrisPipeline.instance().beginSpiderEyes();
    }

    @Inject(method = "doRenderLayer(Lnet/minecraft/entity/monster/EntityEnderman;FFFFFFF)V", at = @At("RETURN"))
    private void yumelium$endEyes(CallbackInfo ci) {
        IrisPipeline.instance().endSpiderEyes();
    }
}
