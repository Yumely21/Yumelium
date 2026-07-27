package com.yumelium.yumelium.nvidium.gl;

import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL44C;
import org.lwjgl.opengl.NVShaderBufferLoad;

/**
 * A device-local (GPU-only) immutable buffer that is made <em>resident</em> and exposes its raw GPU virtual address via
 * {@code GL_NV_shader_buffer_load}, so mesh/task shaders can read its contents through a bindless pointer. Data is
 * written by a GPU-side copy from a staging buffer, not by CPU mapping.
 *
 * <p>Uses the classic (bind + {@code glBufferStorage}) path rather than DSA, matching the engine's proven GL usage on
 * the lwjglx shim. Nvidium/Alphadium port (LGPL-3.0) — see {@link NvidiumGl}.</p>
 */
public class AddressableDeviceBuffer {
    private final int id;
    private final long size;
    private final long gpuAddress;

    public AddressableDeviceBuffer(long size) {
        this.size = size;
        this.id = GL15C.glGenBuffers();

        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, this.id);
        GL44C.glBufferStorage(GL15C.GL_ARRAY_BUFFER, size, 0); // immutable, device-local; updated via server-side copy
        NVShaderBufferLoad.glMakeBufferResidentNV(GL15C.GL_ARRAY_BUFFER, GL15C.GL_READ_ONLY);
        this.gpuAddress = NVShaderBufferLoad.glGetBufferParameterui64NV(GL15C.GL_ARRAY_BUFFER,
                NVShaderBufferLoad.GL_BUFFER_GPU_ADDRESS_NV);
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0);
    }

    public int id() {
        return this.id;
    }

    public long size() {
        return this.size;
    }

    /** Raw GPU virtual address usable as a bindless pointer inside shaders. */
    public long gpuAddress() {
        return this.gpuAddress;
    }

    public void delete() {
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, this.id);
        NVShaderBufferLoad.glMakeBufferNonResidentNV(GL15C.GL_ARRAY_BUFFER);
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0);
        GL15C.glDeleteBuffers(this.id);
    }
}
