package me.jellysquid.mods.sodium.mixin.features.shaders;

import com.yumelium.yumelium.shaders.gl.RenderTargets;
import net.minecraft.client.renderer.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Yumelium/Iris: keeps the pack's {@code blend.<program>.colortexN = off} overrides alive across the host's own blend
 * toggles. The non-indexed {@code glEnable(GL_BLEND)} — which MC issues mid-pass for the held item, entity layers and
 * more — sets EVERY draw buffer's blend bit, silently undoing the {@code glDisablei} overrides beginTargets installed.
 * The hand's material write (colortex6) then alpha-blended with an alpha of ~0 and the WATER's materialMask underneath
 * survived — the composite ran its water path on the first-person hand (refraction ripple + stray brightness).
 * Re-asserting after every enableBlend is a no-op outside a pass with overrides (a single static mask check).
 */
@Mixin(GlStateManager.class)
public class MixinGlStateManagerIris {
    @Inject(method = "enableBlend", at = @At("TAIL"))
    private static void yumelium$reassertBlendOffs(CallbackInfo ci) {
        RenderTargets.reassertBlendOffs();
    }
}
