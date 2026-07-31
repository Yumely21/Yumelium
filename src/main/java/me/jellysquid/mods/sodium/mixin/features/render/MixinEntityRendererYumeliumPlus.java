package me.jellysquid.mods.sodium.mixin.features.render;

import me.jellysquid.mods.sodium.client.SodiumClientMod;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.EnumParticleTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Yumelium Plus: skips rain/snow rendering ("Weather" toggle), disables GL fog ("Fog" toggle), and suppresses the
 * ground rain-splash particles ("Rain Splash" particle toggle) while leaving the rain ambience sound intact.
 */
@Mixin(EntityRenderer.class)
public class MixinEntityRendererYumeliumPlus {
    @Inject(method = "renderRainSnow", at = @At("HEAD"), cancellable = true)
    private void yumelium$toggleWeather(float partialTicks, CallbackInfo ci) {
        if (!SodiumClientMod.options().yumeliumPlus.renderWeather) {
            ci.cancel();
        }
    }

    // Skip the damage camera tilt/shake when disabled.
    @Inject(method = "hurtCameraEffect", at = @At("HEAD"), cancellable = true)
    private void yumelium$toggleScreenShake(float partialTicks, CallbackInfo ci) {
        if (!SodiumClientMod.options().yumeliumPlus.screenShake) {
            ci.cancel();
        }
    }

    // Turn GL fog back off right after vanilla configures it, so nothing renders fog this frame — EXCEPT fluid fog.
    // Like OptiFine's "Fog: OFF", the toggle kills only ATMOSPHERIC distance fog: the dense EXP fog vanilla sets when
    // the CAMERA is inside water/lava IS the underwater/in-lava look (with shaders off there is nothing else — the
    // first-person water overlay masked its absence, but in third person the world read as clear air, 2026-07-31).
    // Same decision source as setupFog itself (getBlockStateAtEntityViewpoint = camera block incl. third-person
    // offset), so first/third-person semantics match vanilla exactly.
    @Inject(method = "setupFog", at = @At("RETURN"))
    private void yumelium$toggleFog(int startCoords, float partialTicks, CallbackInfo ci) {
        if (SodiumClientMod.options().yumeliumPlus.renderFog) {
            return;
        }
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        net.minecraft.entity.Entity view = mc.getRenderViewEntity();
        if (view != null && mc.world != null) {
            net.minecraft.block.material.Material m = net.minecraft.client.renderer.ActiveRenderInfo
                    .getBlockStateAtEntityViewpoint(mc.world, view, partialTicks).getMaterial();
            if (m == net.minecraft.block.material.Material.WATER || m == net.minecraft.block.material.Material.LAVA) {
                return;
            }
        }
        GlStateManager.disableFog();
    }

    // Skip only the splash-particle spawns inside addRainParticles; the rain ambience sound (also emitted here) is kept.
    @Redirect(method = "addRainParticles", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/WorldClient;spawnParticle(Lnet/minecraft/util/EnumParticleTypes;DDDDDD[I)V"))
    private void yumelium$rainSplash(WorldClient world, EnumParticleTypes type, double x, double y, double z,
                                     double vx, double vy, double vz, int[] params) {
        if (SodiumClientMod.options().yumeliumPlus.particleRainSplash) {
            world.spawnParticle(type, x, y, z, vx, vy, vz, params);
        }
    }
}
