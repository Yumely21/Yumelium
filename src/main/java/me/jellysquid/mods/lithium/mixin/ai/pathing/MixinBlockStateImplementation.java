package me.jellysquid.mods.lithium.mixin.ai.pathing;

import me.jellysquid.mods.lithium.common.ai.pathing.BlockStatePathNodeCache;
import net.minecraft.pathfinding.PathNodeType;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Attaches the per-state path-node-type cache field to the block state flyweight. Forge's
 * {@code ExtendedStateImplementation} extends this class, so extended states inherit it. The benign data race on the
 * field (integrated server: client + server threads share the flyweights) is harmless — both writers store the same
 * idempotent value, and {@link PathNodeType} enum constants are safely published by class init.
 */
@Mixin(targets = "net.minecraft.block.state.BlockStateContainer$StateImplementation")
public class MixinBlockStateImplementation implements BlockStatePathNodeCache {
    private PathNodeType lithium$pathNodeType;

    @Override
    public PathNodeType lithium$getPathNodeType() {
        return this.lithium$pathNodeType;
    }

    @Override
    public void lithium$setPathNodeType(PathNodeType type) {
        this.lithium$pathNodeType = type;
    }
}
