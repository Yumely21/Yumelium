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

    // Turn GL fog back off right after vanilla configures it, so nothing renders fog this frame — EXCEPT fog that is
    // not "atmosphere". Like OptiFine's "Fog: OFF", the toggle kills only ATMOSPHERIC distance fog. Two exemptions:
    //
    // 1. FLUID. The dense EXP fog vanilla sets when the CAMERA is inside water/lava IS the underwater/in-lava look
    //    (with shaders off there is nothing else — the first-person water overlay masked its absence, but in third
    //    person the world read as clear air, 2026-07-31). Same decision source as setupFog itself
    //    (getBlockStateAtEntityViewpoint = camera block incl. third-person offset), so first/third-person semantics
    //    match vanilla exactly.
    //
    // 2. BLINDNESS. Vanilla renders the potion effect ITSELF as fog: setupFog's blindness branch (verified in the
    //    Forge-patched bytecode at bci 70-87) switches to LINEAR with a far plane that collapses to ~5 blocks
    //    (ramping in over the last 20 ticks). Disabling it would not remove an atmospheric preference, it would
    //    remove the effect — a gameplay change, and one that also silently defeats every mod that applies blindness.
    //
    // Forge's ForgeHooksClient.getFogDensity hook (bci 42-67) runs FIRST and skips the rest of setupFog when a mod
    // returns a density >= 0; that path is how content mods supply their own atmospheric fog, and it is exactly what
    // the toggle exists to turn off — so it gets no exemption of its own. Note the two exemptions above are tested
    // against the WORLD, not against which branch of setupFog ran, so a blind player (or one submerged) keeps
    // whatever fog is configured even when a mod supplied it. That is the intended precedence: while blindness is
    // active, removing fog would hand the player vision the effect is supposed to deny, whichever code set it.
    @Inject(method = "setupFog", at = @At("RETURN"))
    private void yumelium$toggleFog(int startCoords, float partialTicks, CallbackInfo ci) {
        if (SodiumClientMod.options().yumeliumPlus.renderFog) {
            return;
        }
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        net.minecraft.entity.Entity view = mc.getRenderViewEntity();
        if (view instanceof net.minecraft.entity.EntityLivingBase
                && ((net.minecraft.entity.EntityLivingBase) view)
                        .isPotionActive(net.minecraft.init.MobEffects.BLINDNESS)) {
            return;
        }
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
