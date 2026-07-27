package me.jellysquid.mods.sodium.client.render.chunk.terrain.material.parameters;

public class MaterialParameters {
    public static final int OFFSET_USE_MIP = 0;
    public static final int OFFSET_ALPHA_CUTOFF = 1; // 2 bits (1..2)
    // NOTE(yumelium): bit 3 flags "desaturate the sampled texture before applying the vertex color". Used for water so
    // biome tint works the 1.13 way (grayscale texture x biome color), reproducing per-biome water colours 1.12.2 lacks.
    public static final int OFFSET_DESATURATE = 3;
    // NOTE(yumelium): bits 4-5 carry a foliage category (0 none, 1 grounded, 2 leaves, 3 upper) so the transformed Iris
    // terrain shader can synthesize mc_Entity and Complementary can identify + wave grass/leaves natively.
    public static final int OFFSET_FOLIAGE = 4; // 2 bits (4..5)

    public static int pack(AlphaCutoffParameter alphaCutoff, boolean useMipmaps) {
        return pack(alphaCutoff, useMipmaps, false, 0);
    }

    public static int pack(AlphaCutoffParameter alphaCutoff, boolean useMipmaps, boolean desaturate) {
        return pack(alphaCutoff, useMipmaps, desaturate, 0);
    }

    public static int pack(AlphaCutoffParameter alphaCutoff, boolean useMipmaps, boolean desaturate, int foliage) {
        return (((useMipmaps ? 1 : 0) << OFFSET_USE_MIP) |
                ((alphaCutoff.ordinal()) << OFFSET_ALPHA_CUTOFF) |
                ((desaturate ? 1 : 0) << OFFSET_DESATURATE) |
                ((foliage & 3) << OFFSET_FOLIAGE));
    }
}
