package me.jellysquid.mods.sodium.client.model.color;

import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.init.Biomes;
import net.minecraft.world.biome.Biome;

/**
 * Yumelium enhancement: backports 1.13+ per-biome water colors to 1.12.2, which has none — every vanilla biome uses
 * {@code waterColor == 0xFFFFFF} (no tint), so {@link net.minecraft.world.biome.BiomeColorHelper#getWaterColorAtPos}
 * returns a constant. These are ABSOLUTE colors: the water material desaturates the blue texture to grayscale in the
 * shader before this tint is applied (the real 1.13 method), so any color — including a true brown swamp — is reproducible.
 * {@link #DEFAULT} is the 1.13 water blue used by all unlisted biomes.
 */
public final class BiomeWaterColors {
    private static final int DEFAULT = 0x3F76E4; // 1.13 default water blue

    private static final Reference2IntMap<Biome> COLORS = build();

    private BiomeWaterColors() {
    }

    private static Reference2IntMap<Biome> build() {
        Reference2IntOpenHashMap<Biome> map = new Reference2IntOpenHashMap<>();
        map.defaultReturnValue(DEFAULT);

        // Exact official 1.13+ per-biome water colors (only the biomes that exist in 1.12.2 and differ from default).
        // Swamp / Swamp Hills — the iconic murky green
        put(map, Biomes.SWAMPLAND, 0x617B64);
        put(map, Biomes.MUTATED_SWAMPLAND, 0x617B64);

        // Frozen Ocean / Frozen River
        put(map, Biomes.FROZEN_OCEAN, 0x3938C9);
        put(map, Biomes.FROZEN_RIVER, 0x3938C9);

        // Mushroom Fields / Mushroom Field Shore
        put(map, Biomes.MUSHROOM_ISLAND, 0x8A8997);
        put(map, Biomes.MUSHROOM_ISLAND_SHORE, 0x8A8997);

        return map;
    }

    private static void put(Reference2IntOpenHashMap<Biome> map, Biome biome, int color) {
        if (biome != null) {
            map.put(biome, color);
        }
    }

    public static int get(Biome biome) {
        return biome == null ? DEFAULT : COLORS.getInt(biome);
    }
}
