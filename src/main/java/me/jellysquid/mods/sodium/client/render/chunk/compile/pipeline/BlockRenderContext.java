package me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline;

import me.jellysquid.mods.sodium.client.world.WorldSlice;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.client.ForgeHooksClient;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Holds the context for the current block being rendered in a chunk section. This container is reused rather than
 * being freshly constructed for each block to avoid allocations.
 *
 * <p>NOTE(yumelium): 1.16.5 fields dropped for 1.12.2 — PoseStack/CachingPoseStack (no matrix stack in the 1.12.2
 * block mesher), IModelData (Forge 1.14+; absent in 1.12.2), and WorldSliceLocalGenerator (WorldSlice already
 * implements IBlockAccess, so it serves as the local slice directly). BakedModel→IBakedModel, RenderType→BlockRenderLayer.
 */
public class BlockRenderContext {
    private final WorldSlice world;
    private final IBlockAccess localSlice;

    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    private final Vector3f origin = new Vector3f();

    private IBlockState state;
    private IBakedModel model;

    private long seed;

    private BlockRenderLayer renderLayer;

    public BlockRenderContext(WorldSlice world) {
        this.world = world;
        this.localSlice = world;
    }

    public void update(BlockPos pos, BlockPos origin, IBlockState state, IBakedModel model, long seed, BlockRenderLayer renderLayer) {
        this.pos.setPos(pos.getX(), pos.getY(), pos.getZ());
        this.origin.set(origin.getX(), origin.getY(), origin.getZ());

        this.state = state;
        this.model = model;

        this.seed = seed;

        this.renderLayer = renderLayer;

        ForgeHooksClient.setRenderLayer(renderLayer);
    }

    public BlockPos pos() {
        return this.pos;
    }

    public WorldSlice world() {
        return this.world;
    }

    public IBlockAccess localSlice() {
        return this.localSlice;
    }

    public IBlockState state() {
        return this.state;
    }

    public IBakedModel model() {
        return this.model;
    }

    public Vector3fc origin() {
        return this.origin;
    }

    public long seed() {
        return this.seed;
    }

    public BlockRenderLayer renderLayer() {
        return this.renderLayer;
    }
}
