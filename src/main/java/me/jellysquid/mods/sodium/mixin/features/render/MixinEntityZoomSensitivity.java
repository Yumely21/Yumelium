package me.jellysquid.mods.sodium.mixin.features.render;

import com.yumelium.yumelium.client.zoom.ZoomHandler;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Scales the local player's mouse-look deltas by the zoom sensitivity factor (see {@link ZoomHandler}), so that a
 * deeper zoom turns the camera proportionally slower for finer aiming. {@code Entity.turn(yaw, pitch)} is the choke
 * point for look rotation; the factor is 1.0 for any entity other than the local player, and when not zoomed.
 */
@Mixin(Entity.class)
public class MixinEntityZoomSensitivity {
    @ModifyVariable(method = "turn", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private float yumelium$scaleYaw(float yaw) {
        return yaw * ZoomHandler.getSensitivityFactor(this);
    }

    @ModifyVariable(method = "turn", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private float yumelium$scalePitch(float pitch) {
        return pitch * ZoomHandler.getSensitivityFactor(this);
    }
}
