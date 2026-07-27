package me.jellysquid.mods.sodium.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureMap;

/**
 * NOTE(yumelium): 1.16.5 fetched the light/block textures via {@code gameRenderer.lightTexture()} and
 * {@code TextureAtlas.LOCATION_BLOCKS}. On 1.12.2 the lightmap is {@code EntityRenderer.lightmapTexture}
 * (a DynamicTexture, exposed via yumelium_at.cfg) and the block atlas is {@code TextureMap.LOCATION_BLOCKS_TEXTURE}.
 */
public class TextureUtil {

    /**
     * NOTE: Must be called while a RenderLayer is active.
     */
    public static int getLightTextureId() {
        return Minecraft.getMinecraft().entityRenderer.lightmapTexture.getGlTextureId();
    }

    /**
     * NOTE: Must be called while a RenderLayer is active.
     */
    public static int getBlockTextureId() {
        return Minecraft.getMinecraft().getTextureManager().getTexture(TextureMap.LOCATION_BLOCKS_TEXTURE).getGlTextureId();
    }
}
