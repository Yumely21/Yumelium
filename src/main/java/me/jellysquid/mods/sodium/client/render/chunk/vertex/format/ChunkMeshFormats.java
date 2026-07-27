package me.jellysquid.mods.sodium.client.render.chunk.vertex.format;

import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.impl.CompactChunkVertex;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.impl.VanillaLikeChunkVertex;

public class ChunkMeshFormats {
    public static final ChunkVertexType COMPACT = new CompactChunkVertex(false);
    public static final ChunkVertexType VANILLA_LIKE = new VanillaLikeChunkVertex();

    /**
     * Yumelium/Iris: the compact layout plus a packed per-vertex face normal ({@code a_Normal}, 24-byte stride). Used
     * for terrain meshing while the Iris shader pipeline is active so pack {@code gbuffers_terrain} shaders (and the
     * built-in deferred lighting) get a real geometric normal. Nvidium is disabled in that mode, so its dependency on
     * the 20-byte {@link #COMPACT} layout is never violated. Switching in/out requires a renderer reload (which the
     * shader toggle already performs) so meshes are rebuilt at the matching stride.
     */
    public static final ChunkVertexType COMPACT_NORMAL = new CompactChunkVertex(true);

    /** @return true for either compact variant (with or without the appended normal). */
    public static boolean isCompact(ChunkVertexType type) {
        return type == COMPACT || type == COMPACT_NORMAL;
    }

    /**
     * @return the terrain vertex type to mesh with right now: the normal-extended compact format when the Iris shader
     * pipeline is enabled, otherwise the plain compact format. (The vanilla-like format is chosen separately, upstream.)
     */
    public static ChunkVertexType activeCompact() {
        return com.yumelium.yumelium.shaders.pipeline.IrisPipeline.instance().isEnabled() ? COMPACT_NORMAL : COMPACT;
    }
}
