package me.jellysquid.mods.lithium.common.ai.pathing;

import net.minecraft.pathfinding.PathNodeType;

/**
 * Duck interface implemented onto {@code BlockStateContainer.StateImplementation} (and thereby Forge's extended
 * states): holds the lazily computed {@link PathNodeType} for this exact block state, so the pathfinder's
 * per-node/per-neighbour material-and-instanceof chain runs ONCE per state instead of once per query.
 *
 * <p>KNOWN LIMITATION (same spirit as Lithium's PathNodeCache): the cache assumes the raw node type of a state is
 * position-independent. That holds for all of vanilla (doors/gates/rails encode their variation in the STATE), but a
 * mod overriding Forge's {@code Block.getAiPathNodeType(state, world, pos)} or {@code isPassable} with genuinely
 * position-dependent logic would get its first answer cached. Acceptable on this (essentially vanilla) instance.</p>
 */
public interface BlockStatePathNodeCache {
    PathNodeType lithium$getPathNodeType();

    void lithium$setPathNodeType(PathNodeType type);
}
