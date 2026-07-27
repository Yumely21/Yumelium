package com.yumelium.yumelium.client.ctm;

import me.jellysquid.mods.sodium.client.model.quad.BakedQuadView;
import me.jellysquid.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.EnumFacing;

/**
 * A {@link BakedQuadView} that re-textures a quad with a connected-texture (CTM) tile, remapping the quad's texture
 * coordinates from the original sprite's atlas region into the chosen tile sprite's region. Everything else (geometry,
 * lighting, colour/tint) delegates to the original quad — so tinted blocks keep their biome/block colour.
 *
 * <p>Same technique as {@code BetterGrassQuad}; this is the connected-textures port of the OptiFine/Continuity CTM
 * concept (Continuity is LGPL-3.0; this is an independent reimplementation against our mesher, not a code copy).</p>
 */
public final class CtmQuad implements BakedQuadView {
    private final BakedQuadView delegate;
    private final TextureAtlasSprite tile;

    private final float srcMinU;
    private final float srcSpanU;
    private final float srcMinV;
    private final float srcSpanV;

    public CtmQuad(BakedQuadView delegate, TextureAtlasSprite tile) {
        this.delegate = delegate;
        this.tile = tile;

        TextureAtlasSprite src = delegate.getSprite();
        this.srcMinU = src.getMinU();
        this.srcSpanU = src.getMaxU() - src.getMinU();
        this.srcMinV = src.getMinV();
        this.srcSpanV = src.getMaxV() - src.getMinV();
    }

    @Override
    public float getTexU(int idx) {
        float rel = this.srcSpanU == 0.0F ? 0.0F : (this.delegate.getTexU(idx) - this.srcMinU) / this.srcSpanU;
        return remap(rel, this.tile.getMinU(), this.tile.getMaxU());
    }

    @Override
    public float getTexV(int idx) {
        float rel = this.srcSpanV == 0.0F ? 0.0F : (this.delegate.getTexV(idx) - this.srcMinV) / this.srcSpanV;
        return remap(rel, this.tile.getMinV(), this.tile.getMaxV());
    }

    /**
     * Maps a 0..1 position within the source glass sprite onto the chosen tile's atlas region, INSET by half a texel on
     * each edge.
     *
     * <p>The inset is what actually fixes the connected-glass "seams". A block face samples the tile from its left edge
     * ({@code min}) to its right edge ({@code max}) exactly. Bilinear filtering at the very edge blends the tile's edge
     * texel with the ATLAS-ADJACENT sprite — and the 47 CTM tiles sit next to bordered tiles, so that neighbour's frame
     * bleeds in as a thin line at every block boundary. It shows on only ONE of a pair of opposite faces because the
     * face's texture direction is mirrored: one face samples the tile's right edge at the boundary (bleeds from the
     * right neighbour), the other samples the left edge (a different, transparent neighbour). Pulling both sample bounds
     * half a texel inward keeps every sample strictly inside the tile, so no neighbour can bleed. The tiles are 16&times;16,
     * so half a texel is 1/32 of the region; on the frameless middle tile (transparent edges) the inset is invisible.</p>
     */
    private static float remap(float rel, float min, float max) {
        float clamped = rel < 0.0F ? 0.0F : rel > 1.0F ? 1.0F : rel;
        // Sample texel CENTERS (half-texel inset): rel 0 → texel-0 centre, rel 1 → texel-15 centre. This kills plain
        // bilinear bleed at mip 0 while still sampling the outermost texel, where the bordered tiles keep their frame — a
        // full-texel inset skipped that texel and erased every frame. A faint seam can survive at coarser mip levels (the
        // atlas mipmap footprint reaches past one texel); fully removing THAT needs the tileset itself to carry a
        // transparent margin, not a UV trick.
        float half = (max - min) / 32.0F;
        return (min + half) + clamped * ((max - half) - (min + half));
    }

    @Override
    public TextureAtlasSprite getSprite() {
        return this.tile;
    }

    // --- everything else delegates to the original quad ---

    @Override
    public int getColorIndex() {
        return this.delegate.getColorIndex();
    }

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
