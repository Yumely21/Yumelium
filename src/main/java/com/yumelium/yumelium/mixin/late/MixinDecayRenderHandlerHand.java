package com.yumelium.yumelium.mixin.late;

import com.yumelium.yumelium.compat.SkinLayersDecayCompat;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * LATE mixin (Betweenlands present only — see YumeliumLateMixins): gives the FIRST-PERSON decay arm the
 * synthesised second-layer decay texture.
 *
 * <p>{@code DecayRenderHandler.renderArmFirstPersonWithDecay} draws the first-person arm and its decay overlay,
 * binding the raw {@code player_decay.png} — whose second-layer (sleeve) regions are EMPTY, the same asset gap
 * that left the 3D skin layers clean in third person. The method issues THREE texture binds (the player's skin
 * for the arm base, then the decay texture for the overlay pieces), so ALL binds are redirected and
 * {@link SkinLayersDecayCompat#handDecayTexture} swaps only the ones whose argument IS the decay texture —
 * matched by resource-location value, never by loading a Betweenlands class.</p>
 */
@Mixin(targets = "thebetweenlands.client.handler.DecayRenderHandler")
public class MixinDecayRenderHandlerHand {

    @Redirect(method = "renderArmFirstPersonWithDecay(FFLnet/minecraft/util/EnumHandSide;I)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/texture/TextureManager;bindTexture(Lnet/minecraft/util/ResourceLocation;)V"),
            require = 3)
    private static void yumelium$bindLayeredDecay(TextureManager textureManager, ResourceLocation texture) {
        textureManager.bindTexture(SkinLayersDecayCompat.handDecayTexture(texture));
    }
}
