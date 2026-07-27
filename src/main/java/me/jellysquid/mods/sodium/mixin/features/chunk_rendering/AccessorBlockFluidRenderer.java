package me.jellysquid.mods.sodium.mixin.features.chunk_rendering;

import net.minecraft.client.renderer.BlockFluidRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BlockFluidRenderer.class)
public interface AccessorBlockFluidRenderer {
    @Accessor("atlasSpritesWater")
    TextureAtlasSprite[] getAtlasSpritesWater();

    @Accessor("atlasSpritesLava")
    TextureAtlasSprite[] getAtlasSpritesLava();

    @Accessor("atlasSpriteWaterOverlay")
    TextureAtlasSprite getAtlasSpriteWaterOverlay();
}
