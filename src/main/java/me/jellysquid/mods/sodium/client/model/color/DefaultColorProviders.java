package me.jellysquid.mods.sodium.client.model.color;

import me.jellysquid.mods.sodium.client.model.quad.ModelQuadView;
import me.jellysquid.mods.sodium.client.model.quad.blender.BlendedColorProvider;
import me.jellysquid.mods.sodium.client.world.biome.BiomeColorSource;
import me.jellysquid.mods.sodium.client.world.WorldSlice;
import net.caffeinemc.mods.sodium.api.util.ColorARGB;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.color.IBlockColor;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;

import java.util.Arrays;

public class DefaultColorProviders {
    public static ColorProvider<IBlockState> adapt(IBlockColor provider) {
        return new VanillaAdapter(provider);
    }

    // TODO(yumelium): fluid color providers (getFluidProvider / ForgeFluidAdapter) are deferred to the fluid
    // rendering port. 1.12.2 has no FluidState; fluids are blocks handled via VanillaFluidBlock.

    @Deprecated(forRemoval = true)
    public static class GrassColorProvider<T> extends BlendedColorProvider<T> {
        public static final ColorProvider<IBlockState> BLOCKS = new GrassColorProvider<>();

        private GrassColorProvider() {
        }

        @Override
        protected int getColor(WorldSlice world, int x, int y, int z) {
            return world.getColor(BiomeColorSource.GRASS, x, y, z);
        }
    }

    @Deprecated(forRemoval = true)
    public static class FoliageColorProvider<T> extends BlendedColorProvider<T> {
        public static final ColorProvider<IBlockState> BLOCKS = new FoliageColorProvider<>();

        private FoliageColorProvider() {
        }

        @Override
        protected int getColor(WorldSlice world, int x, int y, int z) {
            return world.getColor(BiomeColorSource.FOLIAGE, x, y, z);
        }
    }

    @Deprecated(forRemoval = true)
    public static class WaterColorProvider<T> extends BlendedColorProvider<T> {
        public static final ColorProvider<IBlockState> BLOCKS = new WaterColorProvider<>();

        private WaterColorProvider() {
        }

        @Override
        protected int getColor(WorldSlice world, int x, int y, int z) {
            return world.getColor(BiomeColorSource.WATER, x, y, z);
        }
    }

    /**
     * Adapter for {@link BlendedColorProvider} that accepts a biome color getter (e.g. a
     * {@code BiomeColorHelper::getGrassColorAtPos} method reference) and uses it to perform per-vertex biome blending.
     */
    public static class VertexBlendedBiomeColorAdapter<T> extends BlendedColorProvider<T> {
        private final VanillaBiomeColor vanillaGetter;

        /**
         * Interface whose signature matches 1.12.2's {@code BiomeColorHelper.getXColorAtPos(IBlockAccess, BlockPos)}
         * methods, allowing method references to be passed to the constructor.
         */
        @FunctionalInterface
        public interface VanillaBiomeColor {
            int getAverageColor(IBlockAccess getter, BlockPos pos);
        }

        public VertexBlendedBiomeColorAdapter(VanillaBiomeColor vanillaGetter) {
            this.vanillaGetter = vanillaGetter;
        }

        @Override
        protected int getColor(WorldSlice world, BlockPos pos) {
            return vanillaGetter.getAverageColor(world, pos);
        }
    }

    /**
     * A {@link VertexBlendedBiomeColorAdapter} that first asks whether a given block STATE is biome-coloured at all, and
     * hands the ones that are not to the vanilla provider untouched.
     *
     * <p>Needed because {@link BlendedColorProvider} resolves its colour from a POSITION only — the state never reaches
     * {@code getColor}. That is fine on 1.13+, where the flattening gave every leaf type its own block, so Sodium can
     * register the blended provider on exactly the biome-coloured ones. On 1.12.2 a whole family shares one Block and
     * differs by metadata: {@code Blocks.LEAVES} (BlockOldLeaf) is oak/spruce/birch/jungle, and only oak and jungle take
     * the biome foliage colour — spruce and birch have FIXED colours (that is why spruce stays dark green and birch stays
     * yellow-green in every biome). Registering the state-blind blended provider on the whole Block painted the biome
     * foliage colour over all four, so every tree's leaves came out the same colour.</p>
     *
     * <p>The fixed colours are not reproduced here: the vanilla {@link IBlockColor} already implements the rule, so we
     * delegate to it and stay correct for resource packs and Forge overrides.</p>
     */
    public static class StateFilteredBiomeColorAdapter extends VertexBlendedBiomeColorAdapter<IBlockState> {
        private final IBlockColor vanilla;
        private final java.util.function.Predicate<IBlockState> biomeColored;

        /**
         * @param vanilla      the vanilla provider for this block; if null, everything falls through to blending.
         * @param biomeColored true when the state should take the blended biome colour.
         */
        public StateFilteredBiomeColorAdapter(VanillaBiomeColor blendedGetter, IBlockColor vanilla,
                                              java.util.function.Predicate<IBlockState> biomeColored) {
            super(blendedGetter);
            this.vanilla = vanilla;
            this.biomeColored = biomeColored;
        }

        @Override
        public void getColors(WorldSlice view, BlockPos pos, IBlockState state, ModelQuadView quad, int[] output) {
            if (this.vanilla != null && !this.biomeColored.test(state)) {
                // A constant colour — blending four neighbours would only smear it into the neighbouring biome's tint.
                Arrays.fill(output, ColorARGB.toABGR(this.vanilla.colorMultiplier(state, view, pos, quad.getColorIndex())));
                return;
            }
            super.getColors(view, pos, state, quad, output);
        }
    }

    private static class VanillaAdapter implements ColorProvider<IBlockState> {
        private final IBlockColor provider;

        private VanillaAdapter(IBlockColor provider) {
            this.provider = provider;
        }

        @Override
        public void getColors(WorldSlice view, BlockPos pos, IBlockState state, ModelQuadView quad, int[] output) {
            Arrays.fill(output, ColorARGB.toABGR(this.provider.colorMultiplier(state, view, pos, quad.getColorIndex())));
        }
    }
}
