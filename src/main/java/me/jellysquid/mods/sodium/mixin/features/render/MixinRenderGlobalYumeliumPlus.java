package me.jellysquid.mods.sodium.mixin.features.render;

import me.jellysquid.mods.sodium.client.SodiumClientMod;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Yumelium Plus sky toggles. The whole "Sky" toggle cancels the public {@code renderSky(float,int)}; the finer
 * toggles (stars, sun &amp; moon, sky colours) hook call sites inside that same public overload.
 */
@Mixin(RenderGlobal.class)
public abstract class MixinRenderGlobalYumeliumPlus {
    @Unique
    // 2026-07-27 audit: these redirects previously targeted the private renderSky(BufferBuilder,F,Z) overload, but
    // every redirected call site (getSkyColor, getStarBrightness, the sun/moon Tessellator.draw slices) lives in the
    // PUBLIC renderSky(FI)V — all four sky-element toggles were silent no-ops until retargeted here. require = 1 on
    // every injector so a future retarget regression fails loudly instead of reverting to silent no-ops.
    private static final String PUBLIC_RENDER_SKY = "renderSky(FI)V";

    // --- Whole sky ---------------------------------------------------------------------------------------------------
    @Inject(method = "renderSky(FI)V", at = @At("HEAD"), cancellable = true, require = 1)
    private void yumelium$toggleSky(float partialTicks, int pass, CallbackInfo ci) {
        if (!SodiumClientMod.options().yumeliumPlus.renderSky) {
            ci.cancel();
        }
    }

    // --- Sky colour: return a neutral colour instead of the biome/time sky colour --------------------------------------
    @Redirect(method = PUBLIC_RENDER_SKY, require = 1, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/WorldClient;getSkyColor(Lnet/minecraft/entity/Entity;F)Lnet/minecraft/util/math/Vec3d;"))
    private Vec3d yumelium$skyColor(WorldClient world, Entity entity, float partialTicks) {
        if (!SodiumClientMod.options().yumeliumPlus.renderSkyColors) {
            return new Vec3d(0.5D, 0.6D, 0.75D);
        }
        return world.getSkyColor(entity, partialTicks);
    }

    // --- Stars: report zero star brightness so the star VBO isn't drawn ----------------------------------------------
    @Redirect(method = PUBLIC_RENDER_SKY, require = 1, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/WorldClient;getStarBrightness(F)F"))
    private float yumelium$stars(WorldClient world, float partialTicks) {
        if (!SodiumClientMod.options().yumeliumPlus.renderStars) {
            return 0.0F;
        }
        return world.getStarBrightness(partialTicks);
    }

    // --- Sun (the Tessellator.draw between the SUN and MOON texture binds) --------------------------------------------
    @Redirect(method = PUBLIC_RENDER_SKY, require = 1,
            slice = @Slice(
                    from = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/RenderGlobal;SUN_TEXTURES:Lnet/minecraft/util/ResourceLocation;"),
                    to = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/RenderGlobal;MOON_PHASES_TEXTURES:Lnet/minecraft/util/ResourceLocation;")),
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/Tessellator;draw()V"))
    private void yumelium$sunDraw(Tessellator tessellator) {
        yumelium$drawCelestial(tessellator);
    }

    // --- Moon (the Tessellator.draw between the MOON texture bind and the star-brightness read) -----------------------
    @Redirect(method = PUBLIC_RENDER_SKY, require = 1,
            slice = @Slice(
                    from = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/RenderGlobal;MOON_PHASES_TEXTURES:Lnet/minecraft/util/ResourceLocation;"),
                    to = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/WorldClient;getStarBrightness(F)F")),
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/Tessellator;draw()V"))
    private void yumelium$moonDraw(Tessellator tessellator) {
        yumelium$drawCelestial(tessellator);
    }

    @Unique
    private static void yumelium$drawCelestial(Tessellator tessellator) {
        if (SodiumClientMod.options().yumeliumPlus.renderSunMoon) {
            tessellator.draw();
        } else {
            // The buffer was already filled with the quad; end its building state and discard it without uploading,
            // otherwise the next buffer.begin() would fail with "Already building".
            tessellator.getBuffer().finishDrawing();
            tessellator.getBuffer().reset();
        }
    }
}
