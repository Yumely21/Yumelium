package me.jellysquid.mods.sodium.client.world.biome;

public enum BiomeColorSource {
    GRASS,
    FOLIAGE,
    WATER;

    public static final BiomeColorSource[] VALUES = BiomeColorSource.values();
    public static final int COUNT = VALUES.length;

    // TODO(yumelium): restore from(BiomeColorHelper.ColorResolver) mapping when color mixins/registry are ported.
}
