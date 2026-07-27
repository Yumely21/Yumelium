package com.yumelium.yumelium.client.shaders;

import me.jellysquid.mods.sodium.client.SodiumClientMod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL32C;

/**
 * Iris/Oculus port — Milestone M0 (go/no-go gate). Runs once, on the render thread, and reports whether this stack
 * (Cleanroom 1.12.2 + lwjglx shim + real GL context) can support the two things the whole shader-pack pipeline hinges
 * on, both proven with cheap, non-disruptive GL probes:
 *
 * <ol>
 *   <li><b>Legacy shader-pack GLSL direct compile.</b> Shader packs are written in old {@code #version 120} GLSL that
 *       uses the fixed-function builtins ({@code gl_Vertex}, {@code gl_ModelViewProjectionMatrix},
 *       {@code gl_MultiTexCoord0}, {@code gl_Color}, {@code gl_TextureMatrix}, {@code gl_FragData[]} MRT output). On a
 *       modern <i>core</i>-profile context those are removed, which is why Iris on 1.16.5+ needs the heavy
 *       glsl-transformer (ANTLR) to rewrite them. But 1.12.2 renders vanilla with the fixed-function pipeline, so its
 *       GL context is almost certainly a <b>compatibility profile</b> where that legacy GLSL compiles <i>directly</i>.
 *       If this probe compiles+links such a program, we can render shader packs OptiFine/ShadersMod-style and largely
 *       <b>skip the glsl-transformer</b> — the single biggest de-risking of the port.</li>
 *   <li><b>Multi-target G-buffer FBO + blit.</b> The deferred pipeline needs a framebuffer with several colour
 *       attachments (colortex0..N) + a depth attachment, {@code glDrawBuffers} MRT, and {@code glBlitFramebuffer}
 *       between framebuffers. Proven here entirely offscreen (no draw to the screen) so the running frame is
 *       untouched.</li>
 * </ol>
 *
 * <p>If the VERDICT is GO, the shader-pack pipeline (M1+) is feasible on this stack. Purely diagnostic — restores every
 * GL binding it touches and draws nothing visible.</p>
 */
public class IrisCapabilityProbe {
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
            SodiumClientMod.logger().error("[Iris] capability probe threw", t);
        }
    }

    private static void log(String s) {
        SodiumClientMod.logger().info("[Iris] " + s);
    }

    private static void run() {
        log("======== Iris/Oculus shader-pipeline feasibility probe ========");
        log("GL_VENDOR   = " + GL11.glGetString(GL11.GL_VENDOR));
        log("GL_RENDERER = " + GL11.glGetString(GL11.GL_RENDERER));
        log("GL_VERSION  = " + GL11.glGetString(GL11.GL_VERSION));
        log("GLSL        = " + GL11.glGetString(GL20C.GL_SHADING_LANGUAGE_VERSION));

        // Context profile — decides whether legacy fixed-function GLSL builtins exist without transformation.
        String profile;
        try {
            GL11.glGetError();
            int mask = GL11.glGetInteger(GL32C.GL_CONTEXT_PROFILE_MASK);
            if (GL11.glGetError() != GL11.GL_NO_ERROR) {
                profile = "unknown (query unsupported)";
            } else {
                boolean core = (mask & GL32C.GL_CONTEXT_CORE_PROFILE_BIT) != 0;
                boolean compat = (mask & GL32C.GL_CONTEXT_COMPATIBILITY_PROFILE_BIT) != 0;
                profile = "mask=0x" + Integer.toHexString(mask)
                        + (compat ? " COMPATIBILITY" : "") + (core ? " CORE" : "");
            }
        } catch (Throwable t) {
            profile = "unknown (" + t + ")";
        }
        log("context profile = " + profile);
        log("GL_MAX_DRAW_BUFFERS      = " + safeGetInt(GL20C.GL_MAX_DRAW_BUFFERS));
        log("GL_MAX_COLOR_ATTACHMENTS = " + safeGetInt(GL30C.GL_MAX_COLOR_ATTACHMENTS));

        // Probe A — the make-or-break test: does legacy shader-pack GLSL compile directly?
        String glslResult = tryLegacyShaderCompile();
        boolean glslOk = glslResult.startsWith("OK");
        log("legacy #version 120 shader-pack GLSL compile+link = " + glslResult);

        // Probe B — the deferred pipeline's framebuffer machinery.
        String fboResult = tryMultiTargetFboBlit();
        boolean fboOk = fboResult.startsWith("OK");
        log("multi-target G-buffer FBO + blit                  = " + fboResult);

        boolean go = glslOk && fboOk;
        log("======== VERDICT: " + (go
                ? "GO — the Iris/Oculus shader pipeline is FEASIBLE on this stack (proceed to M1)"
                : "NO-GO — a required capability failed (see lines above)") + " ========");
        if (glslOk) {
            log("NOTE: legacy fixed-function GLSL compiles directly → the compatibility-profile, OptiFine/ShadersMod-"
                    + "style direct-compile path is viable; the heavy glsl-transformer (ANTLR) is largely UNNECESSARY.");
        } else {
            log("NOTE: legacy GLSL did NOT compile → this looks like a CORE-profile context; a GLSL transformer would "
                    + "be required to rewrite pack shaders (much larger scope). Re-check the failure log above.");
        }
    }

    private static int safeGetInt(int pname) {
        GL11.glGetError();
        int v = GL11.glGetInteger(pname);
        return GL11.glGetError() == GL11.GL_NO_ERROR ? v : -1;
    }

    /**
     * Compiles + links a representative shader-pack vertex+fragment program written in old {@code #version 120} GLSL
     * that exercises the fixed-function builtins a real {@code gbuffers_terrain} program uses, plus {@code gl_FragData}
     * multi-render-target output. Returns {@code OK} or a failure reason with the compiler info log.
     */
    private static String tryLegacyShaderCompile() {
        int vsh = 0, fsh = 0, program = 0;
        try {
            String vertSrc =
                    "#version 120\n" +
                    "varying vec2 texcoord;\n" +
                    "varying vec4 color;\n" +
                    "varying vec3 normal;\n" +
                    "void main() {\n" +
                    "    gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;\n" +
                    "    texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).st;\n" +
                    "    color = gl_Color;\n" +
                    "    normal = gl_NormalMatrix * gl_Normal;\n" +
                    "}\n";
            String fragSrc =
                    "#version 120\n" +
                    "uniform sampler2D gtexture;\n" +
                    "uniform sampler2D lightmap;\n" +
                    "varying vec2 texcoord;\n" +
                    "varying vec4 color;\n" +
                    "varying vec3 normal;\n" +
                    "/* DRAWBUFFERS:012 */\n" +
                    "void main() {\n" +
                    "    vec4 albedo = texture2D(gtexture, texcoord) * color;\n" +
                    "    gl_FragData[0] = albedo;\n" +
                    "    gl_FragData[1] = vec4(normal * 0.5 + 0.5, 1.0);\n" +
                    "    gl_FragData[2] = vec4(0.0, 0.0, 0.0, 1.0);\n" +
                    "}\n";

            vsh = GL20C.glCreateShader(GL20C.GL_VERTEX_SHADER);
            GL20C.glShaderSource(vsh, vertSrc);
            GL20C.glCompileShader(vsh);
            if (GL20C.glGetShaderi(vsh, GL20C.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
                return "FAIL: vertex shader did not compile: " + GL20C.glGetShaderInfoLog(vsh).trim();
            }

            fsh = GL20C.glCreateShader(GL20C.GL_FRAGMENT_SHADER);
            GL20C.glShaderSource(fsh, fragSrc);
            GL20C.glCompileShader(fsh);
            if (GL20C.glGetShaderi(fsh, GL20C.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
                return "FAIL: fragment shader did not compile: " + GL20C.glGetShaderInfoLog(fsh).trim();
            }

            program = GL20C.glCreateProgram();
            GL20C.glAttachShader(program, vsh);
            GL20C.glAttachShader(program, fsh);
            GL20C.glLinkProgram(program);
            if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == GL11.GL_FALSE) {
                return "FAIL: program did not link: " + GL20C.glGetProgramInfoLog(program).trim();
            }

            String warn = GL20C.glGetProgramInfoLog(program).trim();
            return warn.isEmpty() ? "OK (compiled + linked)" : "OK (compiled + linked; info log: " + warn + ")";
        } catch (Throwable t) {
            return "FAIL: exception " + t;
        } finally {
            if (program != 0) GL20C.glDeleteProgram(program);
            if (vsh != 0) GL20C.glDeleteShader(vsh);
            if (fsh != 0) GL20C.glDeleteShader(fsh);
        }
    }

    /**
     * Builds an offscreen framebuffer with 3 colour textures + a depth texture (a minimal G-buffer), sets MRT draw
     * buffers, checks completeness, clears, and {@code glBlitFramebuffer}s colour 0 into a second offscreen framebuffer.
     * All offscreen — the on-screen frame is never touched. Saves + restores the framebuffer/texture bindings.
     */
    private static String tryMultiTargetFboBlit() {
        final int W = 16, H = 16;
        final int NUM_COLOR = 3;

        int prevDraw = GL11.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        int prevRead = GL11.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

        int fbo = 0, blitFbo = 0, depthTex = 0, blitTex = 0;
        int[] colorTex = new int[NUM_COLOR];
        try {
            GL11.glGetError(); // clear

            fbo = GL30C.glGenFramebuffers();
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo);

            for (int i = 0; i < NUM_COLOR; i++) {
                colorTex[i] = newColorTexture(W, H);
                GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0 + i,
                        GL11.GL_TEXTURE_2D, colorTex[i], 0);
            }
            depthTex = newDepthTexture(W, H);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT,
                    GL11.GL_TEXTURE_2D, depthTex, 0);

            int[] bufs = new int[NUM_COLOR];
            for (int i = 0; i < NUM_COLOR; i++) {
                bufs[i] = GL30C.GL_COLOR_ATTACHMENT0 + i;
            }
            GL20C.glDrawBuffers(bufs);

            int status = GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER);
            if (status != GL30C.GL_FRAMEBUFFER_COMPLETE) {
                return "FAIL: G-buffer FBO incomplete, status=0x" + Integer.toHexString(status);
            }

            GL11.glClearColor(0.1f, 0.2f, 0.3f, 1.0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

            // Blit colour attachment 0 into a second offscreen framebuffer (proves glBlitFramebuffer works).
            blitFbo = GL30C.glGenFramebuffers();
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, blitFbo);
            blitTex = newColorTexture(W, H);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, blitTex, 0);
            int blitStatus = GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER);
            if (blitStatus != GL30C.GL_FRAMEBUFFER_COMPLETE) {
                return "FAIL: blit target FBO incomplete, status=0x" + Integer.toHexString(blitStatus);
            }

            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, fbo);
            GL11.glReadBuffer(GL30C.GL_COLOR_ATTACHMENT0);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, blitFbo);
            GL30C.glBlitFramebuffer(0, 0, W, H, 0, 0, W, H, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);

            int err = GL11.glGetError();
            if (err != GL11.GL_NO_ERROR) {
                return "FAIL: GL error during FBO/blit 0x" + Integer.toHexString(err);
            }
            return "OK (" + NUM_COLOR + " colour attachments + depth, MRT draw buffers, complete, blit clean)";
        } catch (Throwable t) {
            return "FAIL: exception " + t;
        } finally {
            for (int t : colorTex) if (t != 0) GL11.glDeleteTextures(t);
            if (depthTex != 0) GL11.glDeleteTextures(depthTex);
            if (blitTex != 0) GL11.glDeleteTextures(blitTex);
            if (fbo != 0) GL30C.glDeleteFramebuffers(fbo);
            if (blitFbo != 0) GL30C.glDeleteFramebuffers(blitFbo);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, prevDraw);
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, prevRead);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        }
    }

    private static int newColorTexture(int w, int h) {
        int tex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
        return tex;
    }

    private static int newDepthTexture(int w, int h) {
        int tex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL14C.GL_DEPTH_COMPONENT24, w, h, 0,
                GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, 0L);
        return tex;
    }
}
