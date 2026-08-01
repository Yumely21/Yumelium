package com.yumelium.yumelium.nvidium.gl;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20C;

/**
 * A classic vertex+fragment program — the Nvidium M4b occlusion pass rasterizes section bounding boxes with it
 * (depth-tested, colour and depth writes masked off) so a fragment surviving the depth test can stamp the section's
 * visibility entry. Deliberately separate from {@link MeshRasterProgram}: the mesh pipeline has no vertex stage.
 *
 * <p>Nvidium/Alphadium port (LGPL-3.0) — see {@link NvidiumGl}.</p>
 */
public class BoxRasterProgram {
    private final int program;

    public BoxRasterProgram(String vertexSource, String fragmentSource) {
        int vertex = 0, fragment = 0;
        try {
            vertex = compile(GL20C.GL_VERTEX_SHADER, vertexSource, "vertex");
            fragment = compile(GL20C.GL_FRAGMENT_SHADER, fragmentSource, "fragment");

            this.program = GL20C.glCreateProgram();
            GL20C.glAttachShader(this.program, vertex);
            GL20C.glAttachShader(this.program, fragment);
            GL20C.glLinkProgram(this.program);

            if (GL20C.glGetProgrami(this.program, GL20C.GL_LINK_STATUS) == GL11.GL_FALSE) {
                String linkLog = GL20C.glGetProgramInfoLog(this.program).trim();
                GL20C.glDeleteProgram(this.program);
                throw new RuntimeException("[Nvidium] box raster program link failed: " + linkLog);
            }
        } finally {
            // Unconditional: after a successful link the shader objects are redundant, and on any failure path this
            // stops an already-compiled stage from leaking.
            if (vertex != 0) {
                GL20C.glDeleteShader(vertex);
            }
            if (fragment != 0) {
                GL20C.glDeleteShader(fragment);
            }
        }
    }

    private static int compile(int type, String source, String name) {
        int shader = GL20C.glCreateShader(type);
        if (shader == 0) {
            throw new RuntimeException("[Nvidium] glCreateShader returned 0 for the box " + name + " shader");
        }
        GL20C.glShaderSource(shader, source);
        GL20C.glCompileShader(shader);
        if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20C.glGetShaderInfoLog(shader).trim();
            GL20C.glDeleteShader(shader);
            throw new RuntimeException("[Nvidium] box " + name + " shader compile failed: " + log);
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
