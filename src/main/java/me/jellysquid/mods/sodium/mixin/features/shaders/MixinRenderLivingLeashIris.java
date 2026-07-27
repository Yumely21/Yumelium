package me.jellysquid.mods.sodium.mixin.features.shaders;

import com.yumelium.yumelium.shaders.pipeline.IrisPipeline;
import net.minecraft.client.renderer.entity.RenderLiving;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Yumelium/Iris: routes the LEASH line (untextured POSITION_COLOR strips, drawn per leashed mob inside the entities
 * pass) through the pack's {@code gbuffers_basic}. Through gbuffers_entities it sampled the block atlas at the
 * leash's nonexistent texcoords — whatever texel was left tinted the rope. endBasic() restores the entity pass's
 * program + targets. The explicit descriptor skips the generic bridge method.
 */
@Mixin(RenderLiving.class)
public class MixinRenderLivingLeashIris {
    @Inject(method = "renderLeash(Lnet/minecraft/entity/EntityLiving;DDDFF)V", at = @At("HEAD"))
    private void yumelium$leashBegin(CallbackInfo ci) {
        IrisPipeline.instance().beginBasic();
    }

    @Inject(method = "renderLeash(Lnet/minecraft/entity/EntityLiving;DDDFF)V", at = @At("RETURN"))
    private void yumelium$leashEnd(CallbackInfo ci) {
        IrisPipeline.instance().endBasic();
    }
}
