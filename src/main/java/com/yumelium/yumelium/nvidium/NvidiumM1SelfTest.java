package com.yumelium.yumelium.nvidium;

import com.yumelium.yumelium.nvidium.gl.AddressableDeviceBuffer;
import com.yumelium.yumelium.nvidium.gl.BufferArena;
import com.yumelium.yumelium.nvidium.gl.MeshRasterProgram;
import com.yumelium.yumelium.nvidium.gl.NvidiumGl;
import com.yumelium.yumelium.nvidium.gl.PersistentMappedBuffer;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL31C;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

/**
 * Nvidium port — Milestone M1 runtime self-test. Exercises the ported GL layer end-to-end (bindless addressable device
 * buffer, persistent-mapped staging + GPU copy, the buffer arena allocator, and a task+mesh+fragment program) and logs
 * a PASS/FAIL verdict. Diagnostic only — nothing is rendered; all resources are freed.
 */
public final class NvidiumM1SelfTest {
    private NvidiumM1SelfTest() {
    }

    private static final String TASK_SRC =
            "#version 460\n" +
            "#extension GL_NV_mesh_shader : require\n" +
            "layout(local_size_x = 1) in;\n" +
            "void main() { gl_TaskCountNV = 1u; }\n";

    private static final String MESH_SRC =
            "#version 460\n" +
            "#extension GL_NV_mesh_shader : require\n" +
            "layout(local_size_x = 1) in;\n" +
            "layout(triangles, max_vertices = 3, max_primitives = 1) out;\n" +
            "void main() { gl_PrimitiveCountNV = 0u; }\n";

    private static final String FRAG_SRC =
            "#version 460\n" +
            "out vec4 fragColor;\n" +
            "void main() { fragColor = vec4(1.0); }\n";

    public static boolean run(Consumer<String> log) {
        log.accept("======== Nvidium M1 GL-layer self-test ========");
        if (!NvidiumGl.SUPPORTED) {
            log.accept("SKIP: mesh-shader / bindless buffer capabilities not available");
            return false;
        }
        log.accept("caps: mesh=" + NvidiumGl.MESH_SHADER + " shaderBufferLoad=" + NvidiumGl.SHADER_BUFFER_LOAD
                + " bindlessTex=" + NvidiumGl.BINDLESS_TEXTURE + " sparseBuffer=" + NvidiumGl.SPARSE_BUFFER);

        boolean ok = true;
        AddressableDeviceBuffer device = null;
        PersistentMappedBuffer staging = null;
        MeshRasterProgram program = null;

        try {
            // 1) bindless addressable device buffer — must expose a non-zero GPU address
            device = new AddressableDeviceBuffer(64 * 1024);
            long addr = device.gpuAddress();
            boolean addrOk = addr != 0L;
            ok &= addrOk;
            log.accept("1) addressable device buffer: id=" + device.id() + " gpuAddress=0x" + Long.toHexString(addr)
                    + " -> " + (addrOk ? "OK" : "FAIL (address is 0)"));

            // 2) persistent-mapped staging write + GPU copy into the device buffer
            staging = new PersistentMappedBuffer(64 * 1024);
            ByteBuffer m = staging.mapped();
            for (int i = 0; i < 256; i++) {
                m.put(i, (byte) i);
            }
            GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, staging.id());
            GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, device.id());
            GL31C.glCopyBufferSubData(GL31C.GL_COPY_READ_BUFFER, GL31C.GL_COPY_WRITE_BUFFER, 0L, 0L, 256L);
            GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, 0);
            GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, 0);
            NvidiumGl.checkError("staging->device copy");
            log.accept("2) persistent-mapped staging + GPU copy: OK");

            // 3) buffer arena alloc/free/coalesce
            BufferArena arena = new BufferArena(64 * 1024);
            long a = arena.alloc(1000);
            long b = arena.alloc(2000);
            arena.free(a, 1000);
            long c = arena.alloc(700);
            boolean arenaOk = a >= 0 && b >= 0 && c >= 0;
            ok &= arenaOk;
            log.accept("3) buffer arena: a=" + a + " b=" + b + " c=" + c + " used=" + arena.used()
                    + " -> " + (arenaOk ? "OK" : "FAIL"));

            // 4) task + mesh + fragment program compiles and links
            program = new MeshRasterProgram(TASK_SRC, MESH_SRC, FRAG_SRC);
            log.accept("4) task+mesh+fragment program: id=" + program.id() + " -> OK");

            NvidiumGl.checkError("m1 self-test end");
        } catch (Throwable t) {
            ok = false;
            log.accept("M1 self-test EXCEPTION: " + t);
        } finally {
            if (program != null) {
                program.delete();
            }
            if (staging != null) {
                staging.delete();
            }
            if (device != null) {
                device.delete();
            }
        }

        log.accept("======== M1 VERDICT: " + (ok
                ? "PASS — the ported GL layer (bindless buffers + arena + mesh program) works on this stack (proceed to M2)"
                : "FAIL — see the failing step above") + " ========");
        return ok;
    }
}
