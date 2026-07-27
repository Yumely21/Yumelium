package me.jellysquid.mods.sodium.mixin.features.render;

import me.jellysquid.mods.sodium.client.SodiumClientMod;
import net.minecraft.client.particle.ParticleManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Yumelium Plus per-category particle toggles:
 *  - "All Particles" (master) skips rendering every queued particle.
 *  - "Block Break" skips the block-destroy poof at spawn time (also saves the spawn cost).
 *  - "Block Breaking" skips the mining/hit particles at spawn time (both addBlockHitEffects overloads).
 * (Rain-splash particles are handled in MixinEntityRendererYumeliumPlus so the rain sound is preserved.)
 */
@Mixin(ParticleManager.class)
public class MixinParticleManager {
    @Inject(method = "renderParticles", at = @At("HEAD"), cancellable = true)
    private void yumelium$toggleParticles(CallbackInfo ci) {
        if (!SodiumClientMod.options().yumeliumPlus.renderParticles) {
            ci.cancel();
        }
    }

    @Inject(method = "renderLitParticles", at = @At("HEAD"), cancellable = true)
    private void yumelium$toggleLitParticles(CallbackInfo ci) {
        if (!SodiumClientMod.options().yumeliumPlus.renderParticles) {
            ci.cancel();
        }
    }

    @Inject(method = "addBlockDestroyEffects", at = @At("HEAD"), cancellable = true)
    private void yumelium$toggleBlockBreak(CallbackInfo ci) {
        if (!SodiumClientMod.options().yumeliumPlus.particleBlockBreak) {
            ci.cancel();
        }
    }

    @Inject(method = {
            "addBlockHitEffects(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/RayTraceResult;)V",
            "addBlockHitEffects(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/EnumFacing;)V"
    }, at = @At("HEAD"), cancellable = true)
    private void yumelium$toggleBlockBreaking(CallbackInfo ci) {
        if (!SodiumClientMod.options().yumeliumPlus.particleBlockBreaking) {
            ci.cancel();
        }
    }
}
