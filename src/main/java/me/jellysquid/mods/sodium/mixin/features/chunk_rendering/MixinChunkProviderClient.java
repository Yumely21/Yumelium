package me.jellysquid.mods.sodium.mixin.features.chunk_rendering;

import me.jellysquid.mods.sodium.client.render.chunk.map.ChunkStatus;
import me.jellysquid.mods.sodium.client.render.chunk.map.ChunkTracker;
import me.jellysquid.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import net.minecraft.client.multiplayer.ChunkProviderClient;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Feeds client chunk load/unload events into the {@link ChunkTracker} on the owning {@link net.minecraft.client.multiplayer.WorldClient}.
 * A chunk (and its 3x3 neighbourhood) becoming {@code FLAG_ALL} is what queues it for terrain meshing.
 */
@Mixin(ChunkProviderClient.class)
public abstract class MixinChunkProviderClient {
    @Shadow
    @Final
    private World world;

    @Inject(method = "loadChunk", at = @At("RETURN"))
    private void sodium$afterLoadChunk(int x, int z, CallbackInfoReturnable<Chunk> cir) {
        ChunkTracker tracker = ((ChunkTrackerHolder) this.world).sodium$getTracker();
        tracker.onChunkStatusAdded(x, z, ChunkStatus.FLAG_ALL);
    }

    @Inject(method = "unloadChunk", at = @At("RETURN"))
    private void sodium$afterUnloadChunk(int x, int z, CallbackInfo ci) {
        ChunkTracker tracker = ((ChunkTrackerHolder) this.world).sodium$getTracker();
        tracker.onChunkStatusRemoved(x, z, ChunkStatus.FLAG_ALL);
    }
}
