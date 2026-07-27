package me.jellysquid.mods.sodium.client.render.chunk.terrain.material;

import me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.parameters.AlphaCutoffParameter;
import net.minecraft.util.BlockRenderLayer;

public class DefaultMaterials {
    public static final Material SOLID = new Material(DefaultTerrainRenderPasses.SOLID, AlphaCutoffParameter.ZERO, true);
    public static final Material CUTOUT = new Material(DefaultTerrainRenderPasses.CUTOUT, AlphaCutoffParameter.ONE_TENTH, false);
    public static final Material CUTOUT_MIPPED = new Material(DefaultTerrainRenderPasses.CUTOUT, AlphaCutoffParameter.ONE_TENTH, true);
    public static final Material TRANSLUCENT = new Material(DefaultTerrainRenderPasses.TRANSLUCENT, AlphaCutoffParameter.ZERO, true);

    // Yumelium: water uses the translucent pass like normal, but with the desaturate flag so its blue texture is turned
    // grayscale in the shader before the biome tint is applied — the 1.13 way, enabling per-biome (e.g. swamp) water.
    public static final Material WATER = new Material(DefaultTerrainRenderPasses.TRANSLUCENT, AlphaCutoffParameter.ZERO, true, true);

    public static Material forRenderLayer(BlockRenderLayer layer) {
        switch (layer) {
            case SOLID:
                return SOLID;
            case CUTOUT:
                return CUTOUT;
            case CUTOUT_MIPPED:
                return CUTOUT_MIPPED;
            case TRANSLUCENT:
                return TRANSLUCENT;
            default:
                throw new IllegalArgumentException("No material mapping exists for " + layer);
        }
    }

    // Foliage categories (must match chunk_material.glsl MATERIAL_FOLIAGE_OFFSET / the transformer's _iris_foliage_id).
    public static final int FOLIAGE_NONE = 0;
    public static final int FOLIAGE_GROUNDED = 1; // grass, ferns, saplings, crops, dead bush, single + double-plant-lower
    public static final int FOLIAGE_LEAVES = 2;
    public static final int FOLIAGE_UPPER = 3;    // double-plant upper half

    // Precomputed foliage variants of each base material (same render pass/alpha/mip, foliage bits set), so tagging a
    // block's quads as foliage is a cached lookup rather than a per-quad allocation. Keyed by the base Material identity.
    private static final java.util.Map<Material, Material[]> FOLIAGE_VARIANTS = new java.util.IdentityHashMap<>();
    static {
        for (Material base : new Material[]{SOLID, CUTOUT, CUTOUT_MIPPED, TRANSLUCENT}) {
            Material[] variants = new Material[4];
            variants[FOLIAGE_NONE] = base;
            for (int f = 1; f <= 3; f++) {
                variants[f] = new Material(base.pass, base.alphaCutoff, base.mipped, base.desaturate, f);
            }
            FOLIAGE_VARIANTS.put(base, variants);
        }
    }

    /** @return {@code base} tagged with the given foliage category (same render pass), or {@code base} unchanged for
     * {@link #FOLIAGE_NONE} / an unknown base. The variant shares {@code base.pass}, so it uses the same mesh builder. */
    public static Material forFoliage(Material base, int foliage) {
        if (foliage == FOLIAGE_NONE) {
            return base;
        }
        Material[] variants = FOLIAGE_VARIANTS.get(base);
        return (variants != null && foliage >= 0 && foliage < variants.length) ? variants[foliage] : base;
    }
}
