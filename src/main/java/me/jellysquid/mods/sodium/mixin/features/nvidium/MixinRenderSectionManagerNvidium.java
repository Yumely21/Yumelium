package me.jellysquid.mods.sodium.mixin.features.nvidium;

import com.yumelium.yumelium.nvidium.NvidiumBackend;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;

/**
 * Nvidium M2 — mirrors built section geometry into the GPU-resident arena and frees it on unload. Passive: vanilla
 * Sodium rendering is unchanged; this only shadows the data. Active only when {@link NvidiumBackend#MIRROR}.
 */
@Mixin(RenderSectionManager.class)
public class MixinRenderSectionManagerNvidium {
    @Inject(method = "processChunkBuildResults", at = @At("RETURN"))
    private void yumelium$mirrorBuildResults(ArrayList<ChunkBuildOutput> results, CallbackInfo ci) {
        if (!NvidiumBackend.MIRROR) {
            return;
        }
        for (int i = 0, n = results.size(); i < n; i++) {
            ChunkBuildOutput result = results.get(i);
            // 2026-07-27 audit: mirror ONLY what Sodium itself keeps. The raw list contains stale/superseded outputs
            // (filterChunkBuildResults drops disposed sections and older-than-resident builds — at RETURN time
            // lastBuiltFrame is already set for kept outputs, so the same test reproduces its intra-batch dedup) and
            // SORT outputs (index-only, no vertex data — mirroring one would freeSection() the resident geometry
            // and upload nothing, evicting the section from the arena).
            if (result.render.isDisposed()
                    || result.render.getLastBuiltFrame() > result.buildTime
                    || result.isIndexOnlyUpload()) {
                continue;
            }
            NvidiumBackend.instance().mirror(result.render, result.meshes);
        }
    }

    @Inject(method = "onSectionRemoved", at = @At("HEAD"))
    private void yumelium$freeSection(int x, int y, int z, CallbackInfo ci) {
        if (NvidiumBackend.MIRROR) {
            NvidiumBackend.instance().remove(x, y, z);
        }
    }
}
