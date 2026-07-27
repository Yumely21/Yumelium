package me.jellysquid.mods.sodium.client.render.texture;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * NOTE(yumelium): upstream SpriteUtil tracked on-demand sprite animation via GlobalChunkBuildContext (Embeddium)
 * or loliasm/normalasm (Vintagium). On 1.12.2, vanilla ticks all atlas sprites, so markSpriteActive is a no-op.
 */
public class SpriteUtil {
    public static boolean hasAnimation(TextureAtlasSprite sprite) {
        return sprite.hasAnimationMetadata();
    }

    public static void markSpriteActive(TextureAtlasSprite sprite) {
        // TODO(yumelium): if the "animate only visible textures" optimization is desired, hook the sprite ticker.
    }
}
