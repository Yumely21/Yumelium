package me.jellysquid.mods.sodium.mixin.features.render;

import com.yumelium.yumelium.client.zoom.ZoomHandler;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Applies the hold-to-zoom FOV multiplier to the world FOV (only when {@code useFovSetting} is true, so the held-item
 * FOV is unaffected). See {@link ZoomHandler}.
 */
@Mixin(EntityRenderer.class)
public class MixinEntityRendererZoom {
    @Inject(method = "getFOVModifier", at = @At("RETURN"), cancellable = true)
    private void yumelium$applyZoom(float partialTicks, boolean useFovSetting, CallbackInfoReturnable<Float> cir) {
        if (useFovSetting) {
            float mult = ZoomHandler.getFovMultiplier();
            if (mult != 1.0F) {
                cir.setReturnValue(cir.getReturnValueF() * mult);
            }
        }
    }
}
