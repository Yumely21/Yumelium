package me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;

/**
 * The block occlusion cache is responsible for performing occlusion testing of neighboring block faces.
 *
 * <p>NOTE(yumelium): upstream (1.16.5) tested face occlusion using {@code VoxelShape}
 * ({@code getFaceOcclusionShape} + {@code Shapes.joinIsNotEmpty}) and cached the results. 1.12.2 has no
 * {@code VoxelShape}; vanilla's {@link IBlockState#shouldSideBeRendered(IBlockAccess, BlockPos, EnumFacing)}
 * already performs the neighbor occlusion test, so the whole VoxelShape path and its cache are unnecessary.
 */
public class BlockOcclusionCache {
    public BlockOcclusionCache() {
    }

    /**
     * @param selfState The state of the block in the world
     * @param view      The world view for this render context
     * @param pos       The position of the block
     * @param facing    The facing direction of the side to check
     * @return True if the block side facing {@param facing} is not occluded, otherwise false
     */
    public boolean shouldDrawSide(IBlockState selfState, IBlockAccess view, BlockPos pos, EnumFacing facing) {
        return selfState.shouldSideBeRendered(view, pos, facing);
    }
}
