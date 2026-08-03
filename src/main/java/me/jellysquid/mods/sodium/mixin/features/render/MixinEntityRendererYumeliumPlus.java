package me.jellysquid.mods.sodium.mixin.features.render;

import me.jellysquid.mods.sodium.client.SodiumClientMod;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.EnumParticleTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Yumelium Plus: skips rain/snow rendering ("Weather" toggle), disables GL fog ("Fog" toggle), suppresses the
 * ground rain-splash particles ("Rain Splash" particle toggle) while leaving the rain ambience sound intact,
 * and widens the projection far clip plane so terrain is never visibly cut off when fog is disabled.
 */
@Mixin(EntityRenderer.class)
public class MixinEntityRendererYumeliumPlus {
    @Shadow private float farPlaneDistance;

    /**
     * Floor for the PROJECTION far plane. Vanilla clips at {@code farPlaneDistance * SQRT_2} = rd·16·√2 — at rd2
     * that is ~45 blocks, which slices through the farthest loaded chunk ring (horizontal corner 32√2 ≈ 45, and any
     * tall terrain sooner). Fog is designed to hide that cut, so with the Yumelium Plus fog toggle OFF it is plainly
     * visible (user report 2026-08-03, "since the beginning, Sodium and Nvidium alike"). 288 covers the full loaded
     * volume at small render distances (√(48² + 48² + 256²) ≈ 265); the floor is inert from rd 13 up
     * (rd·16·√2 ≥ 288 ⟺ rd ≥ 12.7), so normal render distances keep vanilla numbers exactly.
     */
    private static final float YUMELIUM$MIN_PROJECTION_FAR = 288.0F;

    /**
     * Applies the floor by redirecting the {@code MathHelper.SQRT_2} read in the three places that build/restore the
     * WORLD projection with {@code farPlaneDistance * SQRT_2}: {@code setupCameraTransform} (the projection itself)
     * and the restores in {@code renderWorldPass}/{@code renderCloudsCheck} after the cloud pass — all three must
     * agree or the projection changes mid-frame. Deliberately NOT the {@code farPlaneDistance} field: fog placement
     * derives from it, and pushing fog out past the loaded world would re-expose the very cut this hides.
     * {@code renderHand}'s non-√2 projection (near geometry only) is untouched.
     */
    @Redirect(method = {"setupCameraTransform", "renderWorldPass", "renderCloudsCheck"},
            at = @At(value = "FIELD", target = "Lnet/minecraft/util/math/MathHelper;SQRT_2:F",
                    opcode = org.objectweb.asm.Opcodes.GETSTATIC),
            require = 3)
    private float yumelium$floorProjectionFar() {
        return Math.max(net.minecraft.util.math.MathHelper.SQRT_2,
                YUMELIUM$MIN_PROJECTION_FAR / Math.max(this.farPlaneDistance, 1.0F));
    }

    /**
     * The sky and cloud sections build their OWN projections from plain multiples of {@code farPlaneDistance}
     * (sky ×2 in {@code renderWorldPass}, clouds ×4 in {@code renderCloudsCheck}) — and the sun/moon quads sit at
     * distance 100, so below rd4 (sky far = rd·16·2 < 100 ⟺ rd < 3.2) the celestials far-clip out of their own
     * pass. That is WHY vanilla's rd4 sky gate exists. Floor the ordinal-0 field read in each method (the projection
     * builds; the later ordinal-1 reads are the √2 restores, already consistent via the SQRT_2 redirect above):
     * 72 → sky far ≥ 144 > 100, clouds far ≥ 288 = the main projection floor. Inert from rd5 up for the sky's
     * purpose and rd13+ exactly like the main floor.
     */
    @Redirect(method = {"renderWorldPass", "renderCloudsCheck"},
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/EntityRenderer;farPlaneDistance:F",
                    opcode = org.objectweb.asm.Opcodes.GETFIELD, ordinal = 0),
            require = 2)
    private float yumelium$floorSkyProjectionFar(EntityRenderer self) {
        return Math.max(this.farPlaneDistance, YUMELIUM$MIN_PROJECTION_FAR / 4.0F);
    }

    /**
     * Vanilla skips the ENTIRE sky section (sun, moon, stars, sky disc) below render distance 4 —
     * {@code renderWorldPass} bc 297-301 in the Forge-patched bytecode: {@code renderDistanceChunks < 4 → jump past
     * "sky"}. With the far-plane floors above, the sky renders fine at any distance, so lift the gate: this ordinal-0
     * read feeds only that comparison (javap-verified — consumed by the if_icmplt and nothing else).
     */
    @Redirect(method = "renderWorldPass",
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/settings/GameSettings;renderDistanceChunks:I",
                    ordinal = 0),
            require = 1)
    private int yumelium$alwaysRenderSky(net.minecraft.client.settings.GameSettings settings) {
        return Math.max(settings.renderDistanceChunks, 4);
    }

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
