package com.yumelium.yumelium.client.ctm;

import me.jellysquid.mods.sodium.client.model.quad.BakedQuadView;
import me.jellysquid.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.EnumFacing;

/**
 * A connected-texture quad wrapper for glass PANES. Unlike {@link CtmQuad} (which remaps the existing UV), a pane's thin
 * arm faces only map HALF the glass sprite with an orientation-dependent UV, so remapping puts the tile frame in the
 * wrong place. Instead this maps the chosen 47-tile onto the quad by the vertex's LOCAL block position — the along-wall
 * axis (z for E/W faces, x for N/S faces) → U and height (y) → V — so each block shows a whole tile locally: connected
 * middle panes use the frameless tile (seamless), end panes keep the frame at the wall end / top / bottom.
 */
public final class PaneCtmQuad implements BakedQuadView {
    private final BakedQuadView delegate;
    private final TextureAtlasSprite tile;
    private final int axis;    // 0 = along-wall is Z (E/W faces), 1 = along-wall is X (N/S faces)
    private final boolean flip; // flip the U direction for W/N faces so left/right match the tile

    public PaneCtmQuad(BakedQuadView delegate, TextureAtlasSprite tile, EnumFacing face) {
        this.delegate = delegate;
        this.tile = tile;
        switch (face) {
            case EAST:  this.axis = 0; this.flip = false; break;
            case WEST:  this.axis = 0; this.flip = true;  break;
            case SOUTH: this.axis = 1; this.flip = false; break;
            case NORTH: this.axis = 1; this.flip = true;  break;
            default:    this.axis = 0; this.flip = false; break;
        }
    }

    @Override
    public float getTexU(int idx) {
        // getX/Y/Z are block-relative (0..1), not 0..16.
        float h = this.axis == 0 ? this.delegate.getZ(idx) : this.delegate.getX(idx);
        float rel = this.flip ? 1.0F - h : h;
        return remap(rel, this.tile.getMinU(), this.tile.getMaxU());
    }

    @Override
    public float getTexV(int idx) {
        float rel = 1.0F - this.delegate.getY(idx); // block top (y=1) -> texture top (minV)
        return remap(rel, this.tile.getMinV(), this.tile.getMaxV());
    }

    /** Half-texel-inset remap — see {@link CtmQuad#remap} for why (stops the atlas-adjacent tile's frame bleeding in at
     * block boundaries). Duplicated rather than shared to keep the two quad wrappers independent. */
    private static float remap(float rel, float min, float max) {
        float clamped = rel < 0.0F ? 0.0F : rel > 1.0F ? 1.0F : rel;
        float half = (max - min) / 32.0F; // half-texel (sample texel centres) — see CtmQuad.remap
        return (min + half) + clamped * ((max - half) - (min + half));
    }

    @Override
    public TextureAtlasSprite getSprite() {
        return this.tile;
    }

    // --- everything else delegates ---

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
