package me.jellysquid.mods.sodium.client.gl.device;

import me.jellysquid.mods.sodium.client.gl.tessellation.GlIndexType;

public interface DrawCommandList extends AutoCloseable {
    /** DIRECT mode: a single glMultiDrawElementsBaseVertex over the whole batch. */
    void multiDrawElementsBaseVertex(MultiDrawBatch batch, GlIndexType indexType);

    /** INDIVIDUAL mode: one glDrawElementsBaseVertex per command in the batch. */
    void drawElementsIndividual(MultiDrawBatch batch, GlIndexType indexType);

    /** INDIRECT mode: glMultiDrawElementsIndirect over a driver-side command buffer (GL 4.3). */
    void multiDrawElementsIndirect(MultiDrawBatch batch, GlIndexType indexType);

    void endTessellating();

    void flush();

    @Override
    default void close() {
        this.flush();
    }
}
