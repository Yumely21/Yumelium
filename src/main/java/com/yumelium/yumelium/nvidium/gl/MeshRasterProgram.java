package com.yumelium.yumelium.nvidium.gl;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.NVMeshShader;

/**
 * A rasterization program built from an optional task shader, a mesh shader, and a fragment shader — the pipeline the
 * Nvidium terrain path draws with (via {@code glDrawMeshTasksNV}).
 *
 * <p>Nvidium/Alphadium port (LGPL-3.0) — see {@link NvidiumGl}.</p>
 */
public class MeshRasterProgram {
    private final int program;

    public MeshRasterProgram(String taskSource, String meshSource, String fragmentSource) {
        int task = 0, mesh = 0, fragment = 0;
        try {
            task = taskSource != null ? compile(NVMeshShader.GL_TASK_SHADER_NV, taskSource, "task") : 0;
            mesh = compile(NVMeshShader.GL_MESH_SHADER_NV, meshSource, "mesh");
            fragment = compile(GL20C.GL_FRAGMENT_SHADER, fragmentSource, "fragment");

            this.program = GL20C.glCreateProgram();
            if (task != 0) {
                GL20C.glAttachShader(this.program, task);
            }
            GL20C.glAttachShader(this.program, mesh);
            GL20C.glAttachShader(this.program, fragment);
            GL20C.glLinkProgram(this.program);

            boolean linked = GL20C.glGetProgrami(this.program, GL20C.GL_LINK_STATUS) != GL11.GL_FALSE;
            if (!linked) {
                String linkLog = GL20C.glGetProgramInfoLog(this.program).trim();
                GL20C.glDeleteProgram(this.program);
                throw new RuntimeException("[Nvidium] mesh raster program link failed: " + linkLog);
            }
        } finally {
            // Delete the shader objects unconditionally — after a successful link they're no longer needed, and on ANY
            // failure path (a later stage failing to compile, or the link failing) this stops the earlier-compiled
            // shaders from leaking.
            if (task != 0) {
                GL20C.glDeleteShader(task);
            }
            if (mesh != 0) {
                GL20C.glDeleteShader(mesh);
            }
            if (fragment != 0) {
                GL20C.glDeleteShader(fragment);
            }
        }
    }

    private static int compile(int type, String source, String name) {
        int shader = GL20C.glCreateShader(type);
        if (shader == 0) {
            throw new RuntimeException("[Nvidium] glCreateShader returned 0 for " + name + " shader");
        }
        GL20C.glShaderSource(shader, source);
        GL20C.glCompileShader(shader);
        if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20C.glGetShaderInfoLog(shader).trim();
            GL20C.glDeleteShader(shader);
            throw new RuntimeException("[Nvidium] " + name + " shader compile failed: " + log);
        }
        return shader;
    }

    public void bind() {
        GL20C.glUseProgram(this.program);
    }

    public int id() {
        return this.program;
    }

    public void delete() {
        GL20C.glDeleteProgram(this.program);
    }
}
