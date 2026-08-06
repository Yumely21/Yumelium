package com.yumelium.yumelium.mixin.late;

import com.yumelium.yumelium.compat.SkinLayersDecayCompat;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.EnumHandSide;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * LATE mixin (Betweenlands present only — see YumeliumLateMixins): the FIRST-PERSON half of the decay-overlay
 * compat. Betweenlands' {@code renderArmFirstPersonWithDecay} paints decay on the vanilla first-person arm and
 * its flat sleeve — but 3dSkinLayers hides all six vanilla wear parts and draws an extruded voxel sleeve instead
 * (inside {@code renderRightArm}/{@code renderLeftArm}), which Betweenlands' pass never touches.
 *
 * <p>The colour redirect captures the pulse alpha from the method's own colour calls (three sites: the two
 * per-side alpha writes + the shared opaque reset — the capture keeps sets below 1 and ignores the reset); TAIL
 * consumes it and re-draws the voxel sleeve with the synthesised decay texture. At TAIL the caller's matrix
 * bracket is still open, so the arm transform Betweenlands posed is exactly what the overlay renders under.
 * The first port's texture-bind swap on this method was a no-op (the flat sleeve it painted is hidden by
 * 3dSkinLayers) and changed stock Betweenlands' authored look when 3dSkinLayers was absent — this hook draws
 * only when the voxel sleeve actually exists.</p>
 */
@Mixin(targets = "thebetweenlands.client.handler.DecayRenderHandler")
public class MixinDecayRenderHandlerFirstPerson {

    @Redirect(method = "renderArmFirstPersonWithDecay(FFLnet/minecraft/util/EnumHandSide;I)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GlStateManager;color(FFFF)V"),
            require = 3)
    private static void yumelium$captureFpDecayAlpha(float red, float green, float blue, float alpha) {
        GlStateManager.color(red, green, blue, alpha);
        SkinLayersDecayCompat.captureDecayAlpha(alpha);
    }

    @Inject(method = "renderArmFirstPersonWithDecay(FFLnet/minecraft/util/EnumHandSide;I)V",
            at = @At("TAIL"), require = 1)
    private static void yumelium$drawFpSleeveOverlay(float equipProgress, float swingProgress, EnumHandSide side,
                                                     int packedLight, CallbackInfo ci) {
        SkinLayersDecayCompat.drawFirstPersonSleeve(side);
    }
}
