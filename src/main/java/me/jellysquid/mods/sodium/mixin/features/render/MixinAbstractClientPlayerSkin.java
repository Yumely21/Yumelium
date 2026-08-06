package me.jellysquid.mods.sodium.mixin.features.render;

import me.jellysquid.mods.sodium.client.SodiumClientMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Yumelium Plus "Yumely21's Skin": renders the LOCAL player with the bundled skin (slim/Alex model), regardless of
 * the logged-in account or skin servers — so dev runs (username "Developer") and offline sessions show the author's
 * real skin. Deliberately narrow: only {@code Minecraft.player} is overridden; other players keep their own skins.
 *
 * <p>Both overrides sit on {@link AbstractClientPlayer}, the single source every consumer reads: vanilla
 * {@code RenderPlayer}/{@code ModelPlayer} pick the arm model via {@code getSkinType()}, the skin texture binds via
 * {@code getLocationSkin()}, and 3dSkinLayers builds its extruded geometry from the same two calls — so the layers
 * and the Betweenlands decay-overlay compat follow the override automatically, no special-casing anywhere.</p>
 */
@Mixin(AbstractClientPlayer.class)
public class MixinAbstractClientPlayerSkin {

    @Unique
    private static final ResourceLocation YUMELIUM$SKIN =
            new ResourceLocation("yumelium", "textures/entity/yumely21_skin.png");

    @Unique
    private boolean yumelium$isLocalPlayer() {
        return SodiumClientMod.options().yumeliumPlus.yumely21Skin
                && (Object) this == Minecraft.getMinecraft().player;
    }

    @Inject(method = "getLocationSkin()Lnet/minecraft/util/ResourceLocation;", at = @At("HEAD"),
            cancellable = true, require = 1)
    private void yumelium$overrideSkin(CallbackInfoReturnable<ResourceLocation> cir) {
        if (yumelium$isLocalPlayer()) {
            cir.setReturnValue(YUMELIUM$SKIN);
        }
    }

    @Inject(method = "getSkinType()Ljava/lang/String;", at = @At("HEAD"), cancellable = true, require = 1)
    private void yumelium$overrideSkinType(CallbackInfoReturnable<String> cir) {
        if (yumelium$isLocalPlayer()) {
            cir.setReturnValue("slim"); // the bundled skin uses the Alex (3px-arm) layout
        }
    }
}
