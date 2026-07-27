package com.yumelium.yumelium.mixin;

import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Third-person camera: ignore blocks WITHOUT a collision box (tall grass, flowers, saplings, torches, …) when
 * clipping the camera distance. Vanilla 1.12.2's {@code orientCamera} traces its 8 obstruction rays with
 * {@code ignoreBlockWithoutBoundingBox = false}, so standing in tall grass slams the third-person camera into
 * near-zero distance. Modern Minecraft (the 1.20.1 reference) only clips the camera against real collision
 * shapes — this redirect switches the overload to match that behaviour ({@code stopOnLiquid=false,
 * ignoreBlockWithoutBoundingBox=true, returnLastUncollidableBlock=false}). Only the camera rays are affected.
 */
@Mixin(EntityRenderer.class)
public class MixinEntityRendererCameraClip {
    @Redirect(
            method = "orientCamera(F)V",
            at = @At(
                    value = "INVOKE",
                    // NOTE the call-site receiver type is WorldClient (this.mc.world) — targeting World here silently
                    // matches nothing.
                    target = "Lnet/minecraft/client/multiplayer/WorldClient;rayTraceBlocks(Lnet/minecraft/util/math/Vec3d;Lnet/minecraft/util/math/Vec3d;)Lnet/minecraft/util/math/RayTraceResult;"
            )
    )
    private RayTraceResult yumelium$cameraClipIgnoresNonSolids(WorldClient world, Vec3d start, Vec3d end) {
        return world.rayTraceBlocks(start, end, false, true, false);
    }
}
