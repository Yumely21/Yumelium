package com.yumelium.yumelium.shaders.gl;

import me.jellysquid.mods.sodium.client.SodiumClientMod;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL41C;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * DIAGNOSTIC for the nvoglv64.dll cache-poisoning crash (offset 0xc5aa8a, on the shader DISK-cache HIT path): the
 * driver's disk cache stores its compiled-program blobs through the same serializer the ARB_get_program_binary path
 * uses, so round-tripping every one of OUR programs through {@code glGetProgramBinary → glProgramBinary} inside a
 * SINGLE run exercises the suspect load path deterministically — no crash-per-launch bisecting. The culprit theory:
 * 1.12.2 gives a COMPATIBILITY-profile context, and this port links GL 4.2+ features (compute, imageStore, SSBO)
 * into it — a combination real Iris never produces (its compute path exists only on 1.16+ core contexts) and a
 * plausibly untested corner of NVIDIA's binary serializer.
 *
 * <p>Every program funnel registers here ({@code GlslProgram} ctor + Sodium's {@code GlProgram.Builder#link}); the
 * audit logs each program's name WITH A FLUSH before loading its binary, so a hard driver crash names the culprit
 * as the last line in the log.</p>
 */
public final class ProgramBinaryAudit {

    /** One-shot lever: round-trip every registered program's binary at frame ~200.
     * RESULT 2026-07-25: 38/38 programs round-trip CLEAN → our GL stream is well-formed and the disk-cache crash
     * lives in the driver's SEPARATE source-hash cache serializer, not the ARB binary path. Off in normal builds;
     * the in-process env guard (NvidiaShaderCacheGuard, PROVEN: GLCache stays empty) prevents the crash. */
    public static final boolean ENABLED = false;

    private static final List<Object[]> PROGRAMS = new ArrayList<>(); // {String name, Integer glId}
    private static boolean ran;

    private ProgramBinaryAudit() {
    }

    public static synchronized void register(String name, int program) {
        PROGRAMS.add(new Object[]{name, program});
    }

    public static synchronized void runOnce() {
        if (!ENABLED || ran) {
            return;
        }
        ran = true;
        say("[BINARY AUDIT] round-tripping " + PROGRAMS.size() + " programs through glGetProgramBinary/glProgramBinary"
                + " (a hard crash here names the culprit as the LAST logged program)");
        int ok = 0, failed = 0, skipped = 0;
        for (Object[] entry : PROGRAMS) {
            String name = (String) entry[0];
            int id = (Integer) entry[1];
            if (!GL20C.glIsProgram(id)) {
                skipped++; // destroyed since registration (renderer reload)
                continue;
            }
            int len = GL20C.glGetProgrami(id, GL41C.GL_PROGRAM_BINARY_LENGTH);
            int err = GL11C.glGetError();
            if (len <= 0 || err != 0) {
                say("[BINARY AUDIT] " + name + ": no retrievable binary (len=" + len + " err=0x"
                        + Integer.toHexString(err) + ") — skipped");
                skipped++;
                continue;
            }
            ByteBuffer data = BufferUtils.createByteBuffer(len);
            IntBuffer fmt = BufferUtils.createIntBuffer(1);
            IntBuffer outLen = BufferUtils.createIntBuffer(1);
            GL41C.glGetProgramBinary(id, outLen, fmt, data);
            err = GL11C.glGetError();
            if (err != 0) {
                say("[BINARY AUDIT] " + name + ": glGetProgramBinary error 0x" + Integer.toHexString(err) + " — skipped");
                skipped++;
                continue;
            }
            say("[BINARY AUDIT] loading " + name + " (len=" + len + " fmt=0x" + Integer.toHexString(fmt.get(0)) + ") ...");
            int scratch = GL20C.glCreateProgram();
            data.limit(outLen.get(0));
            GL41C.glProgramBinary(scratch, fmt.get(0), data);
            int linked = GL20C.glGetProgrami(scratch, GL20C.GL_LINK_STATUS);
            err = GL11C.glGetError();
            GL20C.glDeleteProgram(scratch);
            if (linked == GL11C.GL_TRUE && err == 0) {
                ok++;
            } else {
                failed++;
                say("[BINARY AUDIT] " + name + ": *** LOAD FAILED (link=" + linked + " err=0x"
                        + Integer.toHexString(err) + ") ***");
            }
        }
        say("[BINARY AUDIT] DONE: " + ok + " ok, " + failed + " load-failed, " + skipped + " skipped — "
                + "surviving this line means the ARB round-trip did not reproduce the driver crash");
    }

    /** Logs through BOTH log4j and stdout with a flush — a driver crash kills the process before async appenders drain. */
    private static void say(String msg) {
        SodiumClientMod.logger().info(msg);
        System.out.println(msg);
        System.out.flush();
    }
}
