package com.yumelium.yumelium.shaders.gl;

import me.jellysquid.mods.sodium.client.SodiumClientMod;
import org.joml.Matrix4fc;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Iris/Oculus port — M3. A compiled GLSL program built directly from shader-pack source strings. Because 1.12.2's GL
 * context is a compatibility profile (proven in M0), the pack's legacy {@code #version 120} GLSL — with its
 * fixed-function builtins ({@code gl_Vertex}, {@code ftransform()}, {@code gl_MultiTexCoord0}, {@code gl_FragData[]}) —
 * compiles as-is with no source transformation. This wrapper compiles/links + caches uniform locations, and is reused
 * for every pipeline program (composite/deferred/final now; terrain gbuffers later).
 */
public final class GlslProgram {
    /** Every program+stage that failed to compile or link this session, deduplicated — the health report echoes this so
     * a failure that scrolled out of the log is still discoverable. (The failure is also logged in full at the moment it
     * happens, with a numbered source dump.) */
    private static final java.util.Set<String> COMPILE_FAILURES =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());

    /** @return a snapshot of every compile/link failure recorded this session (name + stage), oldest first. */
    public static java.util.List<String> compileFailures() {
        synchronized (COMPILE_FAILURES) {
            return new java.util.ArrayList<>(COMPILE_FAILURES);
        }
    }

    private final String name;
    private final int program;
    private final Map<String, Integer> uniformCache = new HashMap<>();

    private GlslProgram(String name, int program) {
        this.name = name;
        this.program = program;
        ProgramBinaryAudit.register(name, program); // nvoglv64 cache-crash reproducer — see that class
    }

    /** Compiles + links a vertex+fragment program from source, or returns {@code null} on any compile/link error (logged). */
    public static GlslProgram compile(String name, String vertexSrc, String fragmentSrc) {
        int vsh = compileStage(name, GL20C.GL_VERTEX_SHADER, vertexSrc);
        if (vsh == 0) {
            return null;
        }
        int fsh = compileStage(name, GL20C.GL_FRAGMENT_SHADER, fragmentSrc);
        if (fsh == 0) {
            GL20C.glDeleteShader(vsh);
            return null;
        }

        int program = GL20C.glCreateProgram();
        GL20C.glAttachShader(program, vsh);
        GL20C.glAttachShader(program, fsh);
        GL20C.glLinkProgram(program);
        GL20C.glDetachShader(program, vsh);
        GL20C.glDetachShader(program, fsh);
        GL20C.glDeleteShader(vsh);
        GL20C.glDeleteShader(fsh);

        if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == GL11.GL_FALSE) {
            SodiumClientMod.logger().error("[Iris] program '" + name + "' failed to link: "
                    + GL20C.glGetProgramInfoLog(program).trim());
            COMPILE_FAILURES.add(name + " (link)");
            GL20C.glDeleteProgram(program);
            return null;
        }
        return new GlslProgram(name, program);
    }

    /**
     * Wraps an EXISTING GL program handle (e.g. Sodium's transformed pack terrain program) so the pipeline's uniform
     * setters ({@code setInt/setFloat/setVec3/setMatrix4}, and thus {@code IrisPipeline}'s shared uniform code) can feed
     * it the pack's scene uniforms too. Non-owning: {@link #delete()} must NOT be called on a wrapper (Sodium owns the
     * program). Uniform locations are cached per wrapper, so keep the instance and reuse it across frames.
     */
    public static GlslProgram wrap(String name, int existingHandle) {
        return new GlslProgram(name, existingHandle);
    }

    /** The compute-shader dispatch size, in work groups (parsed from the pack's {@code const ivec3 workGroups}); null for
     * a non-compute program. glDispatchCompute is called with these counts (× the shader's local_size = the volume). */
    private int[] workGroups;

    /** Compiles + links a COMPUTE program from source, or returns {@code null} on error. The Iris {@code const ivec3
     * workGroups = ivec3(x,y,z);} declaration (parsed here) drives {@link #dispatch()}'s glDispatchCompute counts. */
    public static GlslProgram compileCompute(String name, String computeSrc) {
        int csh = compileStage(name, GL43C.GL_COMPUTE_SHADER, computeSrc);
        if (csh == 0) {
            return null;
        }
        int program = GL20C.glCreateProgram();
        GL20C.glAttachShader(program, csh);
        GL20C.glLinkProgram(program);
        GL20C.glDetachShader(program, csh);
        GL20C.glDeleteShader(csh);
        if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == GL11.GL_FALSE) {
            SodiumClientMod.logger().error("[Iris] compute program '" + name + "' failed to link: "
                    + GL20C.glGetProgramInfoLog(program).trim());
            COMPILE_FAILURES.add(name + " (compute link)");
            GL20C.glDeleteProgram(program);
            return null;
        }
        GlslProgram p = new GlslProgram(name, program);
        p.workGroups = parseWorkGroups(computeSrc);
        return p;
    }

    private static final java.util.regex.Pattern WORKGROUPS = java.util.regex.Pattern.compile(
            "const\\s+ivec3\\s+workGroups\\s*=\\s*ivec3\\s*\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)");

    /** @return the {@code const ivec3 workGroups = ivec3(x,y,z)} counts from the (option-applied, branch-stripped) compute
     * source, or {@code null} if the shader has none (then the caller must supply a dispatch size). */
    private static int[] parseWorkGroups(String computeSrc) {
        java.util.regex.Matcher m = WORKGROUPS.matcher(computeSrc);
        if (m.find()) {
            return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
        }
        return null;
    }

    /** @return the parsed {@code const ivec3 workGroups} dispatch counts, or {@code null}. */
    public int[] workGroups() {
        return this.workGroups;
    }

    /** Binds this compute program and dispatches it at its parsed {@code workGroups} size, then a full memory barrier so
     * later image/SSBO/texture reads see the writes. No-op if the work-group size is unknown. */
    public void dispatch() {
        if (this.workGroups == null) {
            return;
        }
        dispatch(this.workGroups[0], this.workGroups[1], this.workGroups[2]);
    }

    /** Binds this compute program and dispatches {@code x×y×z} work groups + a memory barrier. */
    public void dispatch(int x, int y, int z) {
        GL20C.glUseProgram(this.program);
        GL43C.glDispatchCompute(x, y, z);
        GL42C.glMemoryBarrier(GL42C.GL_ALL_BARRIER_BITS);
    }

    private static int compileStage(String name, int type, String src) {
        int shader = GL20C.glCreateShader(type);
        GL20C.glShaderSource(shader, src);
        GL20C.glCompileShader(shader);
        if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String kind = type == GL20C.GL_VERTEX_SHADER ? "vertex"
                    : type == GL43C.GL_COMPUTE_SHADER ? "compute" : "fragment";
            SodiumClientMod.logger().error("[Iris] " + kind + " shader '" + name + "' failed to compile: "
                    + GL20C.glGetShaderInfoLog(shader).trim());
            COMPILE_FAILURES.add(name + " (" + kind + ")");
            dumpFailedSource(name, kind, src);
            GL20C.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    /**
     * Writes the exact source we handed to GL, one line per line and numbered from 1, so the line numbers in the driver's
     * error log ({@code 0(10172): error ...}) index straight into the file. Without this a failure in a ~11k-line
     * generated shader is unactionable — the pack source alone can't be lined up with it after our transforms.
     */
    private static void dumpFailedSource(String name, String kind, String src) {
        try {
            java.io.File dir = new java.io.File("shader_dumps");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return;
            }
            java.io.File out = new java.io.File(dir, name + "." + (kind.equals("vertex") ? "vsh" : "fsh"));
            StringBuilder sb = new StringBuilder(src.length() + src.length() / 8);
            String[] lines = src.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                sb.append(String.format("%6d| %s%n", i + 1, lines[i]));
            }
            java.nio.file.Files.write(out.toPath(), sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            SodiumClientMod.logger().error("[Iris] ...numbered source dumped to " + out.getAbsolutePath());
        } catch (Throwable t) {
            SodiumClientMod.logger().error("[Iris] could not dump failed shader source: " + t);
        }
    }

    public String name() {
        return this.name;
    }

    public int handle() {
        return this.program;
    }

    public void use() {
        GL20C.glUseProgram(this.program);
    }

    public static void unuse() {
        GL20C.glUseProgram(0);
    }

    public int uniform(String uniformName) {
        return this.uniformCache.computeIfAbsent(uniformName, n -> GL20C.glGetUniformLocation(this.program, n));
    }

    /**
     * @return the names of every ACTIVE uniform the linked program actually uses (driver-reported, so dead-code-eliminated
     * ones are excluded). Array uniforms come back as {@code name[0]}. Diagnostic aid: comparing this against the set the
     * pipeline feeds reveals exactly which uniforms a pack program wants that we leave unset (→ read as 0 → wrong output).
     */
    public java.util.List<String> activeUniforms() {
        java.util.List<String> out = new java.util.ArrayList<>();
        int count = GL20C.glGetProgrami(this.program, GL20C.GL_ACTIVE_UNIFORMS);
        java.nio.IntBuffer size = BufferUtils.createIntBuffer(1);
        java.nio.IntBuffer type = BufferUtils.createIntBuffer(1);
        for (int i = 0; i < count; i++) {
            out.add(GL20C.glGetActiveUniform(this.program, i, size, type));
        }
        return out;
    }

    /**
     * DIAGNOSTIC: every ACTIVE SAMPLER uniform of the linked program, mapped to the texture unit it is CURRENTLY set to
     * (read back from GL with glGetUniformiv, not from any bookkeeping of ours).
     *
     * <p>This is the only trustworthy way to catch the port's recurring failure mode: a sampler the pipeline never
     * assigns keeps its default value 0 and therefore silently reads texture unit 0 — the block atlas during world
     * rendering, shadowtex0 during the composite. It has caused four separate bugs (textureAtlas twice, gaux4, noisetex).
     * A hand-maintained "we provide these" list cannot catch it: textureAtlas was ON that list while nothing actually
     * called setInt for it in the composite, so the audit reported it as fine. Ask GL instead.</p>
     *
     * <p>Call while the program is bound and AFTER the pipeline's uniform setup for the pass has run.</p>
     *
     * @return name → texture unit, in declaration order.
     */
    public java.util.LinkedHashMap<String, Integer> samplerBindings() {
        java.util.LinkedHashMap<String, Integer> out = new java.util.LinkedHashMap<>();
        int count = GL20C.glGetProgrami(this.program, GL20C.GL_ACTIVE_UNIFORMS);
        java.nio.IntBuffer size = BufferUtils.createIntBuffer(1);
        java.nio.IntBuffer type = BufferUtils.createIntBuffer(1);
        java.nio.IntBuffer value = BufferUtils.createIntBuffer(1);
        for (int i = 0; i < count; i++) {
            String name = GL20C.glGetActiveUniform(this.program, i, size, type);
            if (!isSamplerType(type.get(0))) {
                continue;
            }
            int loc = GL20C.glGetUniformLocation(this.program, name);
            if (loc < 0) {
                continue;
            }
            value.clear();
            GL20C.glGetUniformiv(this.program, loc, value);
            out.put(name, value.get(0));
        }
        return out;
    }

    /** GL_SAMPLER_2D_SHADOW — the type a {@code uniform sampler2DShadow} declaration reports. */
    private static final int GL_SAMPLER_2D_SHADOW = 0x8B62;

    private java.util.Map<String, Integer> samplerTypeCache;

    /**
     * @return the declared GLSL type of {@code uniformName} (e.g. {@code GL_SAMPLER_2D} vs {@code GL_SAMPLER_2D_SHADOW}),
     * or 0 if the program has no such active uniform.
     */
    public int samplerTypeOf(String uniformName) {
        if (this.samplerTypeCache == null) {
            this.samplerTypeCache = new java.util.HashMap<>();
            int count = GL20C.glGetProgrami(this.program, GL20C.GL_ACTIVE_UNIFORMS);
            java.nio.IntBuffer size = BufferUtils.createIntBuffer(1);
            java.nio.IntBuffer type = BufferUtils.createIntBuffer(1);
            for (int i = 0; i < count; i++) {
                String name = GL20C.glGetActiveUniform(this.program, i, size, type);
                this.samplerTypeCache.put(name, type.get(0));
            }
        }
        return this.samplerTypeCache.getOrDefault(uniformName, 0);
    }

    /**
     * Whether {@code uniformName} is declared as a HARDWARE-COMPARE sampler ({@code sampler2DShadow}) rather than a plain
     * {@code sampler2D}. The distinction is not cosmetic: a depth texture may only be read through the sampler kind that
     * matches its {@code GL_TEXTURE_COMPARE_MODE} — the other combination is undefined and returns 0 on NVIDIA.
     *
     * <p>Complementary declares {@code shadowtex0} both ways from the SAME header (lib/uniforms.glsl): {@code sampler2D}
     * inside {@code #ifdef COMPOSITE1} (where the volumetric light reads raw depth with {@code texelFetch}) and
     * {@code sampler2DShadow} everywhere else (where terrain lighting uses {@code shadow2D}). So the compare mode has to
     * follow the PROGRAM, and asking GL what the program actually declared is the only reliable way to know.</p>
     */
    public boolean isShadowSampler(String uniformName) {
        return samplerTypeOf(uniformName) == GL_SAMPLER_2D_SHADOW;
    }

    /** @return true if the program declares {@code uniformName} as any active sampler at all. */
    public boolean hasSampler(String uniformName) {
        return isSamplerType(samplerTypeOf(uniformName));
    }

    /** @return true for every GLSL sampler/image type (float, int and unsigned variants, 1D/2D/3D/cube/array/shadow). */
    private static boolean isSamplerType(int glType) {
        // The sampler + image enum blocks are contiguous ranges in the GL enum space; test them by range rather than
        // listing ~90 constants (and missing one, which is exactly the class of mistake this method exists to catch).
        return (glType >= 0x8B5D && glType <= 0x8B68)   // GL_SAMPLER_1D .. GL_SAMPLER_2D_RECT_SHADOW
                || (glType >= 0x8DC0 && glType <= 0x8DD8) // unsigned/integer + array samplers
                || (glType >= 0x900C && glType <= 0x900F) // cube map array samplers
                || (glType >= 0x904C && glType <= 0x906C) // image types
                || (glType >= 0x9109 && glType <= 0x910D); // buffer/multisample samplers
    }

    private static final int GL_FLOAT = 0x1406;
    private static final int GL_INT = 0x1404;
    private static final int GL_BOOL = 0x8B56;
    private java.util.Set<String> typeWarned;

    /**
     * Warns once when a uniform is being set through the wrong glUniform* overload.
     *
     * <p>This is a silent failure worth catching. {@code glUniform1f} on an {@code int} uniform (or vice versa) raises
     * GL_INVALID_OPERATION and **leaves the uniform at its previous value** — the shader then runs with a stale/zero
     * input and simply does nothing visible. It cost a full debug round on the pack's handheld light: all four of
     * {@code heldBlockLightValue/2} and {@code heldItemId/2} are declared {@code uniform int}, and feeding the light
     * level as a float meant a held sea lantern lit nothing while the only clue was a generic "1282: Invalid operation"
     * at Post render. Same family as the unbound-sampler trap: GL objects, then the effect quietly goes missing.</p>
     */
    private void checkUniformType(String uniformName, int expected) {
        int actual = samplerTypeOf(uniformName);
        if (actual == 0 || actual == expected) {
            return; // not an active uniform (nothing happens anyway), or the types agree
        }
        if (expected == GL_INT && isSamplerType(actual)) {
            return; // glUniform1i is ALSO how a sampler/image is pointed at its unit — that is correct, not a mismatch
        }
        if (actual == GL_BOOL && expected == GL_INT) {
            return; // the spec allows bool uniforms to be set through glUniform1i (0 = false, non-0 = true)
        }
        if (this.typeWarned == null) {
            this.typeWarned = new java.util.HashSet<>();
        }
        if (this.typeWarned.add(uniformName)) {
            SodiumClientMod.logger().warn("[Iris] program '" + this.name + "': uniform " + uniformName
                    + " is declared as GL type 0x" + Integer.toHexString(actual) + " but is being set as 0x"
                    + Integer.toHexString(expected) + " — glUniform will raise GL_INVALID_OPERATION and the uniform will"
                    + " KEEP ITS OLD VALUE (the effect that reads it silently does nothing)");
        }
    }

    public void setInt(String uniformName, int value) {
        int loc = uniform(uniformName);
        if (loc >= 0) {
            checkUniformType(uniformName, GL_INT);
            GL20C.glUniform1i(loc, value);
        }
    }

    public void setFloat(String uniformName, float value) {
        int loc = uniform(uniformName);
        if (loc >= 0) {
            checkUniformType(uniformName, GL_FLOAT);
            GL20C.glUniform1f(loc, value);
        }
    }

    public void setVec2(String uniformName, float x, float y) {
        int loc = uniform(uniformName);
        if (loc >= 0) {
            GL20C.glUniform2f(loc, x, y);
        }
    }

    public void setIVec2(String uniformName, int x, int y) {
        int loc = uniform(uniformName);
        if (loc >= 0) {
            GL20C.glUniform2i(loc, x, y);
        }
    }

    public void setVec3(String uniformName, float x, float y, float z) {
        int loc = uniform(uniformName);
        if (loc >= 0) {
            GL20C.glUniform3f(loc, x, y, z);
        }
    }

    public void setVec4(String uniformName, float x, float y, float z, float w) {
        int loc = uniform(uniformName);
        if (loc >= 0) {
            GL20C.glUniform4f(loc, x, y, z, w);
        }
    }

    private final FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);

    public void setMatrix4(String uniformName, Matrix4fc matrix) {
        int loc = uniform(uniformName);
        if (loc >= 0) {
            this.matrixBuffer.clear();
            matrix.get(this.matrixBuffer);
            GL20C.glUniformMatrix4fv(loc, false, this.matrixBuffer);
        }
    }

    public void delete() {
        GL20C.glDeleteProgram(this.program);
        this.uniformCache.clear();
    }
}
