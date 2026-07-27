package org.embeddedt.embeddium.impl.render.chunk.compile;

import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import org.jetbrains.annotations.Nullable;

public final class GlobalChunkBuildContext {
    private static ChunkBuildContext mainThreadContext;
    // NOTE(yumelium): 1.12.2 has no MinecraftAccessor game-thread getter; this class is first loaded on the
    // main (render) thread via ChunkBuilder#setMainThread, so capturing the current thread here is correct.
    private static final Thread mainThread = Thread.currentThread();

    private GlobalChunkBuildContext() {}

    public static void setMainThread() {
        if (mainThread != Thread.currentThread()) {
            throw new IllegalStateException("Global chunk build context captured wrong thread");
        }
    }

    @Nullable
    public static ChunkBuildContext get() {
        var thread = Thread.currentThread();
        if (thread == mainThread) {
            return mainThreadContext;
        } else if (thread instanceof Holder holder) {
            return holder.embeddium$getGlobalContext();
        } else {
            return null;
        }
    }

    public static void bindMainThread(ChunkBuildContext context) {
        mainThreadContext = context;
    }

    public interface Holder {
        ChunkBuildContext embeddium$getGlobalContext();
    }
}
