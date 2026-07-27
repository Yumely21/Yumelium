package me.jellysquid.mods.sodium.mixin.features.shaders;

import com.yumelium.yumelium.shaders.pipeline.IrisPipeline;
import net.minecraft.client.renderer.tileentity.TileEntityEndPortalRenderer;
import net.minecraft.tileentity.TileEntityEndPortal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Yumelium/Iris: while the shader block pass is live, replaces the vanilla end-portal surface (eye-linear TEXGEN +
 * up to 16 ADDITIVE passes — garbage UVs and a white blowout under a bound shader) with a single fullbright quad
 * carrying the end-portal star texture, which is exactly the input the pack's endPortalEffect procedurally taps.
 * The end-gateway renderer defers to this method for its surface, so gateways are covered too (its beam is its own
 * draw and untouched). Vanilla look is kept when shaders are off.
 */
@Mixin(TileEntityEndPortalRenderer.class)
public class MixinTileEntityEndPortalRendererIris {
    @Inject(method = "render(Lnet/minecraft/tileentity/TileEntityEndPortal;DDDFIF)V",
            at = @At("HEAD"), cancellable = true)
    private void yumelium$portalSurface(TileEntityEndPortal te, double x, double y, double z,
                                        float partialTicks, int destroyStage, float alpha, CallbackInfo ci) {
        if (IrisPipeline.instance().renderEndPortalSurface(te, x, y, z)) {
            ci.cancel();
        }
    }
}
