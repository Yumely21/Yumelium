package com.yumelium.yumelium.client.nvidium;

import me.jellysquid.mods.sodium.client.SodiumClientMod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.NVMeshShader;

/**
 * Nvidium port — Milestone M0 (go/no-go gate). Runs once, on the render thread, and reports whether this stack
 * (RTX GPU + lwjglx shim + real GL 4.6 context) can actually use NVIDIA mesh shaders and bindless buffers, and whether
 * a trivial mesh-shader program compiles, links, and dispatches ({@code glDrawMeshTasksNV}) without a GL error.
 *
 * <p>If the VERDICT logged here is GO, the full Nvidium mesh-shader terrain backend (M1+) is feasible; if NO-GO, the
 * port is not viable on this LWJGL/shim stack. Purely diagnostic — draws nothing visible (0 primitives) and restores
 * all state it touches.</p>
 */
public class NvidiumCapabilityProbe {
    private boolean hasRun = false;

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (this.hasRun || event.phase != TickEvent.Phase.END) {
            return;
        }
        this.hasRun = true;
        try {
            run();
        } catch (Throwable t) {
            SodiumClientMod.logger().error("[Nvidium M0] capability probe threw", t);
        }
    }

    private static void log(String s) {
        SodiumClientMod.logger().info("[Nvidium M0] " + s);
    }

    private static void run() {
        GLCapabilities caps = GL.getCapabilities();

        log("======== Nvidium mesh-shader capability probe ========");
        log("GL_VENDOR   = " + GL11.glGetString(GL11.GL_VENDOR));
        log("GL_RENDERER = " + GL11.glGetString(GL11.GL_RENDERER));
        log("GL_VERSION  = " + GL11.glGetString(GL11.GL_VERSION));

        boolean gl46 = caps.OpenGL46;
        boolean extMesh = caps.GL_NV_mesh_shader;
        boolean extBindlessTex = caps.GL_ARB_bindless_texture || caps.GL_NV_bindless_texture;
        boolean extShaderBufLoad = caps.GL_NV_shader_buffer_load;
        boolean extCmdList = caps.GL_NV_command_list;

        // Function-pointer presence — proves the (shim-)loader actually resolved the entry points we would call.
        boolean fnMesh = caps.glDrawMeshTasksNV != 0L;
        boolean fnBindlessTex = caps.glGetTextureHandleARB != 0L || caps.glMakeTextureHandleResidentARB != 0L;
        boolean fnBufResident = caps.glMakeBufferResidentNV != 0L;
        boolean fnBindlessMdi = caps.glMultiDrawElementsIndirectBindlessNV != 0L;

        log("OpenGL 4.6                     = " + gl46);
        log("ext GL_NV_mesh_shader          = " + extMesh + "   (fn glDrawMeshTasksNV loaded = " + fnMesh + ")");
        log("ext ARB/NV_bindless_texture    = " + extBindlessTex + "   (fn loaded = " + fnBindlessTex + ")");
        log("ext GL_NV_shader_buffer_load   = " + extShaderBufLoad + "   (fn glMakeBufferResidentNV loaded = " + fnBufResident + ")");
        log("ext GL_NV_command_list         = " + extCmdList);
        log("fn  bindless multidraw-indirect= " + fnBindlessMdi);

        String drawResult;
        boolean drawOk = false;
        if (extMesh && fnMesh) {
            drawResult = tryTrivialMeshDraw();
            drawOk = drawResult.startsWith("OK");
        } else {
            drawResult = "skipped (mesh-shader extension/function not available)";
        }
        log("mesh-shader compile+link+dispatch test = " + drawResult);

        boolean go = extMesh && fnMesh && extBindlessTex && fnBindlessTex && extShaderBufLoad && fnBufResident && drawOk;
        log("======== VERDICT: " + (go
                ? "GO — the Nvidium mesh-shader backend is FEASIBLE on this stack (proceed to M1)"
                : "NO-GO — a required capability or dispatch is missing/failed (see lines above)") + " ========");

        // If M0 passed, run the M1 GL-layer self-test in the same frame; if it passes too, arm the M2 geometry mirror.
        if (go) {
            boolean m1 = com.yumelium.yumelium.nvidium.NvidiumM1SelfTest.run(NvidiumCapabilityProbe::log);
            if (m1) {
                com.yumelium.yumelium.nvidium.NvidiumBackend.available = true;
                com.yumelium.yumelium.nvidium.NvidiumBackend.applyConfig();
                log("[M3b] mesh-shader terrain AVAILABLE — controlled by the Yumelium Plus → Nvidium toggle (currently "
                        + (com.yumelium.yumelium.nvidium.NvidiumBackend.RENDER_TERRAIN ? "ON" : "OFF") + ").");
            }
        }
    }

    /** Compiles a minimal mesh+fragment program that emits 0 primitives and dispatches it once; returns OK or a reason. */
    private static String tryTrivialMeshDraw() {
        int meshShader = 0;
        int fragShader = 0;
        int program = 0;
        int prevProgram = GL11.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        try {
            String meshSrc =
                    "#version 460\n" +
                    "#extension GL_NV_mesh_shader : require\n" +
                    "layout(local_size_x = 1) in;\n" +
                    "layout(triangles, max_vertices = 3, max_primitives = 1) out;\n" +
                    "void main() { gl_PrimitiveCountNV = 0u; }\n";
            String fragSrc =
                    "#version 460\n" +
                    "out vec4 fragColor;\n" +
                    "void main() { fragColor = vec4(1.0); }\n";

            meshShader = GL20C.glCreateShader(NVMeshShader.GL_MESH_SHADER_NV);
            if (meshShader == 0) {
                return "FAIL: glCreateShader(GL_MESH_SHADER_NV) returned 0";
            }
            GL20C.glShaderSource(meshShader, meshSrc);
            GL20C.glCompileShader(meshShader);
            if (GL20C.glGetShaderi(meshShader, GL20C.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
                return "FAIL: mesh shader did not compile: " + GL20C.glGetShaderInfoLog(meshShader).trim();
            }

            fragShader = GL20C.glCreateShader(GL20C.GL_FRAGMENT_SHADER);
            GL20C.glShaderSource(fragShader, fragSrc);
            GL20C.glCompileShader(fragShader);
            if (GL20C.glGetShaderi(fragShader, GL20C.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
                return "FAIL: fragment shader did not compile: " + GL20C.glGetShaderInfoLog(fragShader).trim();
            }

            program = GL20C.glCreateProgram();
            GL20C.glAttachShader(program, meshShader);
            GL20C.glAttachShader(program, fragShader);
            GL20C.glLinkProgram(program);
            if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == GL11.GL_FALSE) {
                return "FAIL: program did not link: " + GL20C.glGetProgramInfoLog(program).trim();
            }

            GL11.glGetError(); // clear any prior error
            GL20C.glUseProgram(program);
            NVMeshShader.glDrawMeshTasksNV(0, 1);
            int err = GL11.glGetError();
            if (err != GL11.GL_NO_ERROR) {
                return "FAIL: glDrawMeshTasksNV raised GL error 0x" + Integer.toHexString(err);
            }
            return "OK (compiled, linked, dispatched with no GL error)";
        } catch (Throwable t) {
            return "FAIL: exception " + t;
        } finally {
            GL20C.glUseProgram(prevProgram);
            if (program != 0) {
                GL20C.glDeleteProgram(program);
            }
            if (meshShader != 0) {
                GL20C.glDeleteShader(meshShader);
            }
            if (fragShader != 0) {
                GL20C.glDeleteShader(fragShader);
            }
        }
    }
}
