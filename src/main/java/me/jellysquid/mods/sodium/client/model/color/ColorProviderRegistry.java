package me.jellysquid.mods.sodium.client.model.color;

import it.unimi.dsi.fastutil.objects.Reference2ReferenceMap;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceSet;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.model.color.interop.BlockColorsExtended;
import me.jellysquid.mods.sodium.client.world.WorldSlice;
import net.minecraft.block.Block;
import net.minecraft.block.BlockDoublePlant;
import net.minecraft.block.BlockOldLeaf;
import net.minecraft.block.BlockPlanks;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.color.BlockColors;
import net.minecraft.client.renderer.color.IBlockColor;
import net.minecraft.init.Blocks;
import net.minecraft.world.biome.BiomeColorHelper;

/**
 * The color provider registry holds the map of {@link ColorProvider}s that are currently in use by the renderer. Most
 * are adapters for a vanilla/modded BlockColor implementation. However, certain vanilla BlockColors are detected
 * and replaced to enable per-vertex biome blending.
 *
 * <p>NOTE(yumelium): rewritten for 1.12.2 — flattened block constants (Blocks.GRASS_BLOCK/OAK_LEAVES/...) mapped to
 * 1.12.2 names (Blocks.GRASS/LEAVES/...); {@code BiomeColors::getAverageXColor}→{@code BiomeColorHelper::getXColorAtPos};
 * the 1.13+ fluid registry (Fluids.WATER etc.) is dropped — 1.12.2 fluids are blocks (Blocks.WATER/FLOWING_WATER).
 */
public class ColorProviderRegistry {
    // Single-biome colour resolvers (biome → colour, no blending). BiomeColorCache averages these over the biome-blend
    // radius, so routing the block colour through them (via WorldSlice.getBlockTint) makes the radius option actually
    // control the blend width — unlike BiomeColorHelper.getXColorAtPos, which does a fixed vanilla 3x3 blend. They are
    // static (stable identity) because BiomeColorCache is keyed by the resolver instance.
    private static final BiomeColorHelper.ColorResolver GRASS_RESOLVER = (biome, pos) -> biome.getGrassColorAtPos(pos);
    private static final BiomeColorHelper.ColorResolver FOLIAGE_RESOLVER = (biome, pos) -> biome.getFoliageColorAtPos(pos);
    private static final BiomeColorHelper.ColorResolver WATER_RESOLVER = (biome, pos) -> BiomeWaterColors.get(biome);

    private final Reference2ReferenceMap<Block, ColorProvider<IBlockState>> blocks = new Reference2ReferenceOpenHashMap<>();

    private final ReferenceSet<Block> overridenBlocks;

    /** Vanilla's own leaf colour handler — kept so the state-aware override can delegate the fixed-colour variants to it
     * instead of hardcoding their colours (which would ignore resource packs / Forge overrides). */
    private final IBlockColor vanillaLeaves;

    /** Vanilla's double-plant handler — the flower variants (sunflower/lilac/rose bush/peony) return -1 (untinted)
     * there; only GRASS and FERN take the biome colour. See {@link #isBiomeColoredDoublePlant}. */
    private final IBlockColor vanillaDoublePlant;

    public ColorProviderRegistry(BlockColors blockColors) {
        var providers = BlockColorsExtended.getProviders(blockColors);

        for (var entry : providers.reference2ReferenceEntrySet()) {
            this.blocks.put(entry.getKey(), DefaultColorProviders.adapt(entry.getValue()));
        }

        this.vanillaLeaves = providers.get(Blocks.LEAVES);
        this.vanillaDoublePlant = providers.get(Blocks.DOUBLE_PLANT);
        this.overridenBlocks = BlockColorsExtended.getOverridenVanillaBlocks(blockColors);

        this.installOverrides();
        probeLeafColors(this.vanillaLeaves);
    }

    /**
     * True when this leaf state takes the biome foliage colour. 1.12.2 stores oak/spruce/birch/jungle as metadata on the
     * single {@code Blocks.LEAVES} block, and vanilla gives spruce and birch FIXED colours (pine / birch) rather than the
     * biome's — which is why a spruce forest is dark green and birch is yellow-green in every biome. The blend must skip
     * those; see {@link DefaultColorProviders.StateFilteredBiomeColorAdapter}.
     */
    private static boolean isBiomeColoredLeaf(IBlockState state) {
        if (!state.getPropertyKeys().contains(BlockOldLeaf.VARIANT)) {
            return true; // not a BlockOldLeaf state (LEAVES2 / modded) — biome-coloured
        }
        BlockPlanks.EnumType variant = state.getValue(BlockOldLeaf.VARIANT);
        return variant != BlockPlanks.EnumType.SPRUCE && variant != BlockPlanks.EnumType.BIRCH;
    }

    /** True for the double-plant variants vanilla biome-tints (double tallgrass + large fern); the four flowers keep
     * their texture colours (vanilla returns -1 for them — tinting them green was the "green lilac" bug). */
    private static boolean isBiomeColoredDoublePlant(IBlockState state) {
        if (!state.getPropertyKeys().contains(BlockDoublePlant.VARIANT)) {
            return true; // not a vanilla double-plant state — keep the biome blend
        }
        BlockDoublePlant.EnumPlantType variant = state.getValue(BlockDoublePlant.VARIANT);
        return variant == BlockDoublePlant.EnumPlantType.GRASS || variant == BlockDoublePlant.EnumPlantType.FERN;
    }

    /**
     * Logs what vanilla's own handler returns per leaf variant, so the rule {@link #isBiomeColoredLeaf} encodes is a
     * MEASURED fact rather than a reading of the vanilla source. Distinct colours for SPRUCE/BIRCH vs OAK confirm it.
     * Probed with a null world/pos, which is exactly the case where vanilla cannot consult a biome — so a variant that
     * still returns its own colour there is one that never depended on the biome.
     */
    private static boolean leafProbeLogged;

    private static void probeLeafColors(IBlockColor vanilla) {
        if (leafProbeLogged) {
            return; // the registry is rebuilt many times per session (once per renderer reload); one report is enough
        }
        leafProbeLogged = true;
        if (vanilla == null) {
            SodiumClientMod.logger().warn("[Yumelium leaves] no vanilla colour handler for Blocks.LEAVES — leaf tints may be wrong");
            return;
        }
        StringBuilder sb = new StringBuilder("[Yumelium leaves] vanilla colorMultiplier per variant (null world):");
        for (var variant : BlockOldLeaf.VARIANT.getAllowedValues()) {
            String value;
            try {
                IBlockState state = Blocks.LEAVES.getDefaultState().withProperty(BlockOldLeaf.VARIANT, variant);
                value = String.format("%06X", vanilla.colorMultiplier(state, null, null, 0) & 0xFFFFFF);
            } catch (Throwable t) {
                value = "threw " + t.getClass().getSimpleName();
            }
            sb.append(' ').append(variant.getName()).append('=').append(value)
                    .append(isBiomeColoredLeaf(Blocks.LEAVES.getDefaultState().withProperty(BlockOldLeaf.VARIANT, variant))
                            ? "(biome)" : "(FIXED)");
        }
        SodiumClientMod.logger().info(sb.toString());
    }

    private void installOverrides() {
        // Yumelium Plus "Biome Colors" toggle: when off, grass/foliage/water use a fixed temperate default instead of
        // the per-biome color (baked into chunk meshes, so the option is flagged REQUIRES_RENDERER_RELOAD).
        DefaultColorProviders.VertexBlendedBiomeColorAdapter.VanillaBiomeColor grassGetter =
                (getter, pos) -> SodiumClientMod.options().yumeliumPlus.biomeColors ? ((WorldSlice) getter).getBlockTint(pos, GRASS_RESOLVER) : 0x91BD59;

        this.registerBlocks(new DefaultColorProviders.VertexBlendedBiomeColorAdapter<>(grassGetter),
                Blocks.GRASS, Blocks.TALLGRASS, Blocks.REEDS);

        // Blocks.DOUBLE_PLANT packs sunflower/lilac/double-grass/double-fern/rose-bush/peony into ONE block by
        // metadata, and vanilla biome-tints ONLY the grass and fern variants — every flower returns -1 (untinted).
        // The plain blended provider resolves from position alone, so it painted biome green over the flowers too
        // (green lilacs/rose bushes/peonies). Same state-aware split as Blocks.LEAVES; the meshing task passes the
        // getActualState-resolved state, so the UPPER half carries the real variant as well.
        this.registerBlocks(new DefaultColorProviders.StateFilteredBiomeColorAdapter(
                        grassGetter, this.vanillaDoublePlant, ColorProviderRegistry::isBiomeColoredDoublePlant),
                Blocks.DOUBLE_PLANT);

        DefaultColorProviders.VertexBlendedBiomeColorAdapter.VanillaBiomeColor foliageGetter =
                (getter, pos) -> SodiumClientMod.options().yumeliumPlus.biomeColors ? ((WorldSlice) getter).getBlockTint(pos, FOLIAGE_RESOLVER) : 0x77AB2F;

        // Blocks.LEAVES (BlockOldLeaf) is oak/spruce/birch/jungle by METADATA — only oak and jungle are biome-coloured, so
        // it needs the state-aware provider. See StateFilteredBiomeColorAdapter. LEAVES2 (acacia/dark oak) and VINE are
        // biome-coloured for every state, so the plain blended provider is correct there.
        this.registerBlocks(new DefaultColorProviders.StateFilteredBiomeColorAdapter(
                        foliageGetter, this.vanillaLeaves, ColorProviderRegistry::isBiomeColoredLeaf),
                Blocks.LEAVES);
        this.registerBlocks(new DefaultColorProviders.VertexBlendedBiomeColorAdapter<>(foliageGetter),
                Blocks.LEAVES2, Blocks.VINE);

        // Yumelium: 1.12.2 vanilla has no per-biome water color (all biomes use 0xFFFFFF); back-port 1.13+ colors via
        // BiomeWaterColors. Routed through the biome-blend cache (WATER_RESOLVER) so it honours the blend radius too.
        this.registerBlocks(new DefaultColorProviders.VertexBlendedBiomeColorAdapter<>(
                        (getter, pos) -> SodiumClientMod.options().yumeliumPlus.biomeColors ? ((WorldSlice) getter).getBlockTint(pos, WATER_RESOLVER) : 0x3F76E4),
                Blocks.WATER, Blocks.FLOWING_WATER);
    }

    private void registerBlocks(ColorProvider<IBlockState> resolver, Block... blocks) {
        for (var block : blocks) {
            // Do not register our resolver if the block is overriden
            if (this.overridenBlocks.contains(block))
                continue;
            this.blocks.put(block, resolver);
        }
    }

    public ColorProvider<IBlockState> getColorProvider(Block block) {
        return this.blocks.get(block);
    }
}
