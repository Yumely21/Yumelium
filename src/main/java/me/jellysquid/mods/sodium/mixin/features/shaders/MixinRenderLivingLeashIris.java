package me.jellysquid.mods.sodium.mixin.features.shaders;

import com.yumelium.yumelium.shaders.pipeline.IrisPipeline;
import net.minecraft.client.renderer.entity.RenderLiving;
import net.minecraft.entity.EntityLiving;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Yumelium/Iris: routes the LEASH line (untextured POSITION_COLOR strips, drawn per leashed mob inside the entities
 * pass) through the pack's {@code gbuffers_basic}. Through gbuffers_entities it sampled the block atlas at the
 * leash's nonexistent texcoords — whatever texel was left tinted the rope. endBasic() restores the entity pass's
 * program + targets. The explicit descriptor skips the generic bridge method.
 *
 * <p><b>The HEAD hook is gated on the mob actually being leashed</b>, because {@code RenderLiving.doRender} calls
 * {@code renderLeash} for EVERY EntityLiving and {@code renderLeash} itself just returns when there is no holder
 * (verified: bci 0-11 is {@code getLeashHolder(); ifnonnull; return}). Bracketing that no-op cost a full program
 * switch each way per mob — {@code useItemLitProgram} (82 uniform uploads in setSceneUniforms + ~9 sampler binds)
 * twice, plus two {@code beginTargets} FBO attachment rebuilds. With 1000 cows on screen that is ~10^5 GL calls per
 * frame spent on rope that does not exist; measured 2026-08-03 as roughly 42 us per entity per frame, i.e. the
 * difference between ~130 FPS with shaders off and ~20 FPS with them on. Gating reproduces vanilla's own condition
 * exactly, so a leashed mob still gets the identical bracket it always did.</p>
 */
@Mixin(RenderLiving.class)
public class MixinRenderLivingLeashIris {
    @Inject(method = "renderLeash(Lnet/minecraft/entity/EntityLiving;DDDFF)V", at = @At("HEAD"), require = 1)
    private void yumelium$leashBegin(EntityLiving entity, double x, double y, double z, float entityYaw,
                                     float partialTicks, CallbackInfo ci) {
        if (IrisPipeline.LEASH_GATE && (entity == null || entity.getLeashHolder() == null)) {
            IrisPipeline.instance().countBasicBracketSkipped();
            // The bracket we are skipping used to end in beginTargets -> resyncBlendSlots(), which incidentally
            // repaired any per-draw-buffer blend state a modded renderer had clobbered with raw GL before the NEXT
            // entity drew (MixinGlStateManagerIris only sees GlStateManager.enableBlend, not GL11.glEnable). Keep
            // that protection: reassertBlendOffs is a single int test and returns immediately when nothing is off.
            com.yumelium.yumelium.shaders.gl.RenderTargets.reassertBlendOffs();
            return;
        }
        IrisPipeline.instance().beginBasic();
    }

    /** Unconditional: endBasic() already no-ops unless beginBasic actually armed the bracket, and it must keep
     * firing on renderLeash's early return for the leashed-then-unleashed-mid-frame case. */
    @Inject(method = "renderLeash(Lnet/minecraft/entity/EntityLiving;DDDFF)V", at = @At("RETURN"), require = 1)
    private void yumelium$leashEnd(EntityLiving entity, double x, double y, double z, float entityYaw,
                                   float partialTicks, CallbackInfo ci) {
        IrisPipeline.instance().endBasic();
    }
}
