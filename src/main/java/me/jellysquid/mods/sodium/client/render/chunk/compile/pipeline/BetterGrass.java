package me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline;

import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gui.SodiumGameOptions;
import net.minecraft.block.Block;
import net.minecraft.block.BlockDirt;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;

import java.util.List;

/**
 * "Better grass" logic — reproduces OptiFine's Fancy/Fast better-grass behaviour (concept ported from BetterGrassify,
 * Apache-2.0, https://github.com/UltimatChamp/BetterGrassify; no source copied). Decides when a grass-like block's
 * horizontal side face should be re-textured with its own top sprite, and supplies that sprite + tint.
 */
final class BetterGrass {
    private BetterGrass() {
    }

    static SodiumGameOptions.BetterGrassMode mode() {
        return SodiumClientMod.options().yumeliumPlus.betterGrass;
    }

    static boolean isGrassLike(IBlockState state) {
        Block b = state.getBlock();
        if (b == Blocks.GRASS || b == Blocks.MYCELIUM || b == Blocks.GRASS_PATH
                || b == Blocks.SNOW || b == Blocks.SNOW_LAYER) {
            return true;
        }
        if (b == Blocks.DIRT) {
            try {
                return state.getValue(BlockDirt.VARIANT) == BlockDirt.DirtType.PODZOL;
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }

    /** The block's top sprite + tint index, taken from its UP-face quad, or {@code null} if it has none. */
    static TopFace topFace(IBakedModel model, IBlockState state, long seed) {
        List<BakedQuad> up = model.getQuads(state, EnumFacing.UP, seed);
        if (up.isEmpty()) {
            return null;
        }
        BakedQuad q = up.get(0);
        return new TopFace(q.getSprite(), q.getTintIndex());
    }

    /**
     * Whether the horizontal side face pointing {@param dir} should be re-grassed. FAST: always. FANCY: only where the
     * same block continues — directly adjacent (flat borders) or one block down in that direction (slopes/steps).
     */
    static boolean shouldReplaceSide(SodiumGameOptions.BetterGrassMode mode, IBlockAccess world, IBlockState self,
                                     BlockPos.MutableBlockPos scratch, int x, int y, int z, EnumFacing dir) {
        if (mode == SodiumGameOptions.BetterGrassMode.FAST) {
            return true;
        }

        Block block = self.getBlock();
        int nx = x + dir.getXOffset();
        int nz = z + dir.getZOffset();

        scratch.setPos(nx, y, nz);
        if (world.getBlockState(scratch).getBlock() == block) {
            return true;
        }
        scratch.setPos(nx, y - 1, nz);
        return world.getBlockState(scratch).getBlock() == block;
    }

    static final class TopFace {
        final TextureAtlasSprite sprite;
        final int tintIndex;

        TopFace(TextureAtlasSprite sprite, int tintIndex) {
            this.sprite = sprite;
            this.tintIndex = tintIndex;
        }
    }
}
