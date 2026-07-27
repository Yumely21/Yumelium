package me.jellysquid.mods.sodium.mixin.features.render;

import com.yumelium.yumelium.client.YumeliumAnimationFilter;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Yumelium Plus: freezes animated block/item textures per-category (water, lava, fire, portal, other) when the matching
 * animation toggle is off. Instead of cancelling the whole animation tick, each sprite's per-frame update is filtered by
 * its icon name, so categories can be toggled independently.
 */
@Mixin(TextureMap.class)
public class MixinTextureMap {
    @Redirect(method = "updateAnimations", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;updateAnimation()V"))
    private void yumelium$filterAnimation(TextureAtlasSprite sprite) {
        if (YumeliumAnimationFilter.shouldAnimate(sprite.getIconName())) {
            sprite.updateAnimation();
        }
    }
}
