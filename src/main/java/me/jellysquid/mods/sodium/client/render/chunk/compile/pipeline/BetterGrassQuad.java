package me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline;

import me.jellysquid.mods.sodium.client.model.quad.BakedQuadView;
import me.jellysquid.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.EnumFacing;

/**
 * A {@link BakedQuadView} that re-textures a grass/mycelium/podzol/snow side quad with the block's TOP sprite (and its
 * tint), implementing "better grass". Concept ported from BetterGrassify (Apache-2.0,
 * https://github.com/UltimatChamp/BetterGrassify) — no source is copied; only the OptiFine-style behaviour is
 * reproduced. All geometry/lighting is delegated to the original quad; only the sprite, its texture coordinates
 * (remapped from the original sprite's atlas region into the top sprite's), and the tint index are overridden.
 */
final class BetterGrassQuad implements BakedQuadView {
    private final BakedQuadView delegate;
    private final TextureAtlasSprite topSprite;
    private final int tintIndex;

    private final float srcMinU;
    private final float srcSpanU;
    private final float srcMinV;
    private final float srcSpanV;

    BetterGrassQuad(BakedQuadView delegate, TextureAtlasSprite topSprite, int tintIndex) {
        this.delegate = delegate;
        this.topSprite = topSprite;
        this.tintIndex = tintIndex;

        TextureAtlasSprite src = delegate.getSprite();
        this.srcMinU = src.getMinU();
        this.srcSpanU = src.getMaxU() - src.getMinU();
        this.srcMinV = src.getMinV();
        this.srcSpanV = src.getMaxV() - src.getMinV();
    }

    @Override
    public float getTexU(int idx) {
        float rel = this.srcSpanU == 0.0F ? 0.0F : (this.delegate.getTexU(idx) - this.srcMinU) / this.srcSpanU;
        return this.topSprite.getMinU() + rel * (this.topSprite.getMaxU() - this.topSprite.getMinU());
    }

    @Override
    public float getTexV(int idx) {
        float rel = this.srcSpanV == 0.0F ? 0.0F : (this.delegate.getTexV(idx) - this.srcMinV) / this.srcSpanV;
        return this.topSprite.getMinV() + rel * (this.topSprite.getMaxV() - this.topSprite.getMinV());
    }

    @Override
    public TextureAtlasSprite getSprite() {
        return this.topSprite;
    }

    @Override
    public int getColorIndex() {
        return this.tintIndex;
    }

    // --- everything else delegates to the original quad ---

    @Override
    public float getX(int idx) {
        return this.delegate.getX(idx);
    }

    @Override
    public float getY(int idx) {
        return this.delegate.getY(idx);
    }

    @Override
    public float getZ(int idx) {
        return this.delegate.getZ(idx);
    }

    @Override
    public int getColor(int idx) {
        return this.delegate.getColor(idx);
    }

    @Override
    public int getLight(int idx) {
        return this.delegate.getLight(idx);
    }

    @Override
    public int getFlags() {
        return this.delegate.getFlags();
    }

    @Override
    public EnumFacing getLightFace() {
        return this.delegate.getLightFace();
    }

    @Override
    public int getForgeNormal(int idx) {
        return this.delegate.getForgeNormal(idx);
    }

    @Override
    public int getComputedFaceNormal() {
        return this.delegate.getComputedFaceNormal();
    }

    @Override
    public ModelQuadFacing getNormalFace() {
        return this.delegate.getNormalFace();
    }

    @Override
    public boolean hasShade() {
        return this.delegate.hasShade();
    }

    @Override
    public void setFlags(int flags) {
        this.delegate.setFlags(flags);
    }

    @Override
    public boolean hasAmbientOcclusion() {
        return this.delegate.hasAmbientOcclusion();
    }
}
