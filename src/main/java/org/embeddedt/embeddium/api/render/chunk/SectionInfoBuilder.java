package org.embeddedt.embeddium.api.render.chunk;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.tileentity.TileEntity;
import org.jetbrains.annotations.ApiStatus;

import java.util.function.Predicate;

@ApiStatus.NonExtendable
public interface SectionInfoBuilder {
    void addSprite(TextureAtlasSprite sprite);

    void addBlockEntity(TileEntity entity, boolean cull);

    void removeBlockEntitiesIf(Predicate<TileEntity> filter);
}
