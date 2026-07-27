package me.jellysquid.mods.sodium.mixin.features.chunk_rendering;

import me.jellysquid.mods.sodium.client.render.chunk.map.ChunkTracker;
import me.jellysquid.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import net.minecraft.client.multiplayer.WorldClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Attaches the Embeddium {@link ChunkTracker} to each {@link WorldClient}, exposed through {@link ChunkTrackerHolder}.
 * The tracker is fed chunk load/unload events by {@code MixinChunkProviderClient} and drained each frame by
 * {@code SodiumWorldRenderer#processChunkEvents}.
 */
@Mixin(WorldClient.class)
public abstract class MixinWorldClient implements ChunkTrackerHolder {
    @Unique
    private final ChunkTracker sodium$chunkTracker = new ChunkTracker();

    @Override
    public ChunkTracker sodium$getTracker() {
        return this.sodium$chunkTracker;
    }
}
