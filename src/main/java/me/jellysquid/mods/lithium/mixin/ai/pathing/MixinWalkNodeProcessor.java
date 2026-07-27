package me.jellysquid.mods.lithium.mixin.ai.pathing;

import me.jellysquid.mods.lithium.common.ai.pathing.BlockStatePathNodeCache;
import net.minecraft.block.state.IBlockState;
import net.minecraft.pathfinding.PathNodeType;
import net.minecraft.pathfinding.WalkNodeProcessor;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lithium ai.pathing (ported to 1.12.2 after PathNodeCache): short-circuits
 * {@link WalkNodeProcessor#getPathNodeTypeRaw} — evaluated for EVERY path node and for each of its 26
 * neighbour-danger cells, per mob, per repath — with the {@link PathNodeType} cached on the block state flyweight.
 * A cache hit costs one ChunkCache array read + a field read; the vanilla material/instanceof/Forge-hook chain runs
 * once per distinct state. HEAD cancel skips the vanilla body entirely; on a miss the RETURN hook stores the
 * freshly computed value (a cancelled HEAD never reaches the RETURN hook, so the stash field stays balanced —
 * node processors are confined to their owning entity's thread, making the instance fields safe).
 */
@Mixin(WalkNodeProcessor.class)
public abstract class MixinWalkNodeProcessor {
    private final BlockPos.MutableBlockPos lithium$rawPos = new BlockPos.MutableBlockPos();
    private IBlockState lithium$rawQueryState;

    @Inject(method = "getPathNodeTypeRaw", at = @At("HEAD"), cancellable = true)
    private void lithium$cachedRawType(IBlockAccess access, int x, int y, int z, CallbackInfoReturnable<PathNodeType> cir) {
        IBlockState state = access.getBlockState(this.lithium$rawPos.setPos(x, y, z));
        // instanceof guard: an exotic modded IBlockState implementation without our mixin just skips caching.
        if (state instanceof BlockStatePathNodeCache) {
            PathNodeType cached = ((BlockStatePathNodeCache) state).lithium$getPathNodeType();
            if (cached != null) {
                cir.setReturnValue(cached);
            } else {
                this.lithium$rawQueryState = state;
            }
        }
    }

    @Inject(method = "getPathNodeTypeRaw", at = @At("RETURN"))
    private void lithium$storeRawType(IBlockAccess access, int x, int y, int z, CallbackInfoReturnable<PathNodeType> cir) {
        IBlockState state = this.lithium$rawQueryState;
        if (state != null) {
            this.lithium$rawQueryState = null;
            PathNodeType type = cir.getReturnValue();
            if (type != null) {
                ((BlockStatePathNodeCache) state).lithium$setPathNodeType(type);
            }
        }
    }
}
