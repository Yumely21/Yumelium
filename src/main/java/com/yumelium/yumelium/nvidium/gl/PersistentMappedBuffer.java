package com.yumelium.yumelium.nvidium.gl;

import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL44C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * A persistently-mapped, coherent, write-only staging buffer. The CPU writes section geometry directly into the mapped
 * region and it is streamed to device-local buffers via GPU copies. Mapped once for its whole lifetime.
 *
 * <p>Classic (bind + {@code glBufferStorage} + {@code glMapBufferRange}) path, matching the engine's proven GL usage.
 * Nvidium/Alphadium port (LGPL-3.0) — see {@link NvidiumGl}.</p>
 */
public class PersistentMappedBuffer {
    private static final int FLAGS =
            GL30C.GL_MAP_WRITE_BIT | GL44C.GL_MAP_PERSISTENT_BIT | GL44C.GL_MAP_COHERENT_BIT;

    private final int id;
    private final long size;
    private final ByteBuffer mapped;
    private final long address;

    public PersistentMappedBuffer(long size) {
        this.size = size;
        this.id = GL15C.glGenBuffers();

        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, this.id);
        GL44C.glBufferStorage(GL15C.GL_ARRAY_BUFFER, size, FLAGS);
        ByteBuffer buf = GL30C.glMapBufferRange(GL15C.GL_ARRAY_BUFFER, 0L, size, FLAGS);
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0);

        if (buf == null) {
            throw new RuntimeException("[Nvidium] failed to persistently map staging buffer");
        }
        this.mapped = buf;
        this.address = MemoryUtil.memAddress(buf);
    }

    public int id() {
        return this.id;
    }

    public long size() {
        return this.size;
    }

    /** The persistently-mapped, coherent region — write section data here, then GPU-copy to a device buffer. */
    public ByteBuffer mapped() {
        return this.mapped;
    }

    /** Native address of the mapped region (for MemoryUtil-style direct writes). */
    public long address() {
        return this.address;
    }

    public void delete() {
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, this.id);
        GL30C.glUnmapBuffer(GL15C.GL_ARRAY_BUFFER);
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0);
        GL15C.glDeleteBuffers(this.id);
    }
}
