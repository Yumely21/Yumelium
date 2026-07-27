package com.yumelium.yumelium.nvidium.gl;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLCapabilities;

/**
 * Yumelium — Nvidium backend port (Milestone M1). Shared capability flags + small GL helpers for the mesh-shader
 * terrain backend.
 *
 * <p>Ported concepts from Alphadium / Nvidium (LGPL-3.0, © MCRcortex and the Alphadium authors,
 * https://github.com/R7CE4/alphadium). This is an independent adaptation to Yumelium's 1.12.2 / LWJGL3-via-lwjglx
 * stack; it does not copy upstream sources verbatim.</p>
 */
public final class NvidiumGl {
    public static final boolean MESH_SHADER;
    public static final boolean BINDLESS_TEXTURE;
    public static final boolean SHADER_BUFFER_LOAD;
    public static final boolean SPARSE_BUFFER;
    public static final boolean SUPPORTED;

    static {
        boolean mesh = false, bindlessTex = false, bufLoad = false, sparse = false;
        try {
            GLCapabilities caps = GL.getCapabilities();
            mesh = caps.GL_NV_mesh_shader && caps.glDrawMeshTasksNV != 0L;
            bindlessTex = (caps.GL_ARB_bindless_texture || caps.GL_NV_bindless_texture);
            bufLoad = caps.GL_NV_shader_buffer_load && caps.glMakeBufferResidentNV != 0L;
            sparse = caps.GL_ARB_sparse_buffer;
        } catch (Throwable ignored) {
        }
        MESH_SHADER = mesh;
        BINDLESS_TEXTURE = bindlessTex;
        SHADER_BUFFER_LOAD = bufLoad;
        SPARSE_BUFFER = sparse;
        SUPPORTED = mesh && bufLoad; // the minimum needed for the mesh-shader geometry path
    }

    private NvidiumGl() {
    }

    /** Throws if a GL error is pending, tagging it with {@param where}. Use around setup, not per-frame. */
    public static void checkError(String where) {
        int err = GL11.glGetError();
        if (err != GL11.GL_NO_ERROR) {
            throw new RuntimeException("[Nvidium] GL error 0x" + Integer.toHexString(err) + " at " + where);
        }
    }
}
