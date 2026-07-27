package com.yumelium.yumelium.shaders.gl;

import me.jellysquid.mods.sodium.client.SodiumClientMod;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL44C;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Iris/Oculus port — custom images (Colored Lighting + World Space Reflections). Real packs (Complementary) declare
 * {@code image.<name> = ...} and {@code bufferObject.<i> = <bytes>} directives in {@code shaders.properties}; Iris
 * allocates a GL texture / SSBO for each and binds it both as an image (load/store) and as a sampler (texture read) to
 * every program that declares the corresponding uniform. These back the pack's voxelization (voxel_img), flood-fill
 * colored light (floodfill_img), and WSR scene voxels (wsr_img). Our port previously omitted them (no
 * {@code IRIS_FEATURE_CUSTOM_IMAGES}); this is the infrastructure that enables them.
 *
 * <p>Directive syntax: {@code image.<name> = <samplerName> <format> <internalFormat> <pixelType> <clear> <relative>
 * <dims...>} where dims are {@code w h} (2D) or {@code w h d} (3D). {@code clear}=true images are zeroed each frame. The
 * directives live under {@code #if COLORED_LIGHTING > 0} in shaders.properties, so the properties MUST be preprocessed
 * with the option values before parsing (else all size variants collapse).</p>
 */
public final class CustomImages {
    // Sampler texture units for custom-image texture() reads — start above every unit RenderTargets uses (composite
    // colortex 2..22, noise 23; water 5..26). GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS on modern GL is ≥48 (usually far more).
    private static final int SAMPLER_UNIT_BASE = 27;

    /** One custom image: a GL texture bound both as an image unit (load/store) and a sampler unit (texture read). */
    public static final class Image {
        public final String name;          // image uniform (e.g. voxel_img)
        public final String samplerName;   // sampler uniform (e.g. voxel_sampler)
        final int internalFormat;          // GL_R16UI, GL_RGBA16F, ...
        final int pixelFormat;             // GL_RED_INTEGER, GL_RGBA, ... (for glClearTexImage)
        final int pixelType;               // GL_UNSIGNED_INT, GL_HALF_FLOAT, ... (for glClearTexImage)
        final boolean clear;               // zero it each frame
        final int width, height, depth;    // depth == 0 → 2D, else 3D
        final int target;                  // GL_TEXTURE_2D or GL_TEXTURE_3D
        final int imageUnit;               // assigned image unit (glBindImageTexture)
        final int textureUnit;             // assigned sampler unit (glActiveTexture)
        int texture;                       // GL texture id (0 until allocated)

        Image(String name, String samplerName, int internalFormat, int pixelFormat, int pixelType, boolean clear,
              int width, int height, int depth, int imageUnit, int textureUnit) {
            this.name = name;
            this.samplerName = samplerName;
            this.internalFormat = internalFormat;
            this.pixelFormat = pixelFormat;
            this.pixelType = pixelType;
            this.clear = clear;
            this.width = width;
            this.height = height;
            this.depth = depth;
            this.target = depth == 0 ? GL11.GL_TEXTURE_2D : GL12.GL_TEXTURE_3D;
            this.imageUnit = imageUnit;
            this.textureUnit = textureUnit;
        }
    }

    /** One shader-storage buffer object ({@code bufferObject.<index> = <bytes>}), bound to its index as an SSBO. */
    public static final class Ssbo {
        final int index;   // binding index (bufferObject.<index>)
        final long bytes;  // size in bytes
        int buffer;        // GL buffer id

        Ssbo(int index, long bytes) {
            this.index = index;
            this.bytes = bytes;
        }
    }

    private final List<Image> images = new ArrayList<>();
    private final Map<String, Image> byName = new LinkedHashMap<>();
    private final List<Ssbo> ssbos = new ArrayList<>();
    private boolean allocated;

    /** @return true if the active pack declared any custom images (→ IRIS_FEATURE_CUSTOM_IMAGES worth defining). */
    public boolean isEmpty() {
        return this.images.isEmpty() && this.ssbos.isEmpty();
    }

    public List<Image> images() {
        return this.images;
    }

    /** Half-extents (x,y,z, in blocks) of the camera-anchored voxel volume — the LARGEST 3D image (the voxel /
     * flood-fill volumes); {@code null} when the pack declares none. "Largest", not "first": the END's image set puts
     * the tiny {@code endcrystal_img} (40x10x1) first, which silently shrank the shadow-cull bypass box to ±(20,5,0)
     * and made the nether/cave voxelization fix work only within arm's reach. The shadow-pass section cull keeps
     * every section inside this box drawable, because the shadow vertex voxelizes what it draws. */
    public int[] voxelHalfExtents() {
        Image best = null;
        long bestVolume = 0;
        for (Image img : this.images) {
            if (img.depth > 0) {
                long volume = (long) img.width * img.height * img.depth;
                if (volume > bestVolume) {
                    bestVolume = volume;
                    best = img;
                }
            }
        }
        return best == null ? null : new int[]{best.width / 2, best.height / 2, best.depth / 2};
    }

    /** @return the GL texture id of the named image, or 0 if absent (diagnostics: lets an Iris-side shader sample the
     * very texture the pack's ray trace reads, to separate "the data is there" from "the shader can actually read it" —
     * glGetTexImage ignores texture completeness and sampler binding, so a readback can succeed where a shader cannot). */
    public int textureOf(String name) {
        Image img = this.byName.get(name);
        return img == null ? 0 : img.texture;
    }

    /**
     * Parses {@code image.*} + {@code bufferObject.*} directives from an ALREADY-PREPROCESSED shaders.properties map
     * (its {@code #if} branches resolved with the current option values, so only the active size variant remains).
     */
    public static CustomImages parse(Map<String, String> props) {
        CustomImages ci = new CustomImages();
        int imageUnit = 0;
        for (Map.Entry<String, String> e : props.entrySet()) {
            String key = e.getKey();
            if (key.startsWith("image.")) {
                Image img = parseImage(key.substring("image.".length()).trim(), e.getValue().trim(),
                        imageUnit, SAMPLER_UNIT_BASE + imageUnit);
                if (img != null) {
                    ci.images.add(img);
                    ci.byName.put(img.name, img);
                    imageUnit++;
                }
            } else if (key.startsWith("bufferObject.")) {
                try {
                    int index = Integer.parseInt(key.substring("bufferObject.".length()).trim());
                    long bytes = Long.parseLong(e.getValue().trim());
                    ci.ssbos.add(new Ssbo(index, bytes));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return ci;
    }

    private static Image parseImage(String name, String value, int imageUnit, int textureUnit) {
        String[] t = value.split("\\s+");
        // <sampler> <format> <internalFormat> <pixelType> <clear> <relative> <dims...>
        if (t.length < 8) {
            SodiumClientMod.logger().warn("[Iris image] malformed image." + name + " = " + value);
            return null;
        }
        String samplerName = t[0];
        int pixelFormat = pixelFormat(t[1]);
        int internalFormat = internalFormat(t[2]);
        int pixelType = pixelType(t[3]);
        boolean clear = Boolean.parseBoolean(t[4]);
        // t[5] = relative (absolute sizes only in this pack — dims are literal texel counts).
        int width = parseIntSafe(t[6]);
        int height = parseIntSafe(t[7]);
        int depth = t.length >= 9 ? parseIntSafe(t[8]) : 0;
        if (internalFormat == 0 || pixelFormat == 0 || pixelType == 0 || width <= 0 || height <= 0) {
            SodiumClientMod.logger().warn("[Iris image] unsupported/incomplete image." + name + " = " + value);
            return null;
        }
        return new Image(name, samplerName, internalFormat, pixelFormat, pixelType, clear, width, height, depth,
                imageUnit, textureUnit);
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int internalFormat(String s) {
        switch (s.toLowerCase()) {
            case "r8ui": return GL30C.GL_R8UI;
            case "r16ui": return GL30C.GL_R16UI;
            case "r32ui": return GL30C.GL_R32UI;
            case "r8i": return GL30C.GL_R8I;
            case "r16i": return GL30C.GL_R16I;
            case "r32i": return GL30C.GL_R32I;
            case "r16f": return GL30C.GL_R16F;
            case "r32f": return GL30C.GL_R32F;
            case "rgba8": return GL11C.GL_RGBA8;
            case "rgba16f": return GL30C.GL_RGBA16F;
            case "rgba32f": return GL30C.GL_RGBA32F;
            case "rgba16": return GL11C.GL_RGBA16;
            default: return 0;
        }
    }

    /** True for float internal formats that support GL_LINEAR texture filtering (the flood-fill light volumes). Integer
     * formats (…UI/…I) are NOT filterable and would make a LINEAR sampler incomplete → must stay NEAREST. */
    private static boolean isFilterableFloat(int internalFormat) {
        return internalFormat == GL30C.GL_RGBA16F || internalFormat == GL30C.GL_RGBA32F
                || internalFormat == GL30C.GL_R16F || internalFormat == GL30C.GL_R32F;
    }

    private static int pixelFormat(String s) {
        switch (s.toLowerCase()) {
            case "red": return GL11C.GL_RED;
            case "red_integer": return GL30C.GL_RED_INTEGER;
            case "rg": return GL30C.GL_RG;
            case "rg_integer": return GL30C.GL_RG_INTEGER;
            case "rgb": return GL11C.GL_RGB;
            case "rgba": return GL11C.GL_RGBA;
            case "rgba_integer": return GL30C.GL_RGBA_INTEGER;
            default: return 0;
        }
    }

    private static int pixelType(String s) {
        switch (s.toLowerCase()) {
            case "unsigned_byte": return GL11C.GL_UNSIGNED_BYTE;
            case "byte": return GL11C.GL_BYTE;
            case "unsigned_short": return GL11C.GL_UNSIGNED_SHORT;
            case "short": return GL11C.GL_SHORT;
            case "unsigned_int": return GL11C.GL_UNSIGNED_INT;
            case "int": return GL11C.GL_INT;
            case "half_float": return GL30C.GL_HALF_FLOAT;
            case "float": return GL11C.GL_FLOAT;
            default: return 0;
        }
    }

    /** Allocates the GL textures (immutable storage) + SSBOs. Idempotent; call once after {@link #parse}. */
    public void allocate() {
        if (this.allocated || isEmpty()) {
            return;
        }
        this.allocated = true;
        int maxImageUnits = GL11C.glGetInteger(GL42C.GL_MAX_IMAGE_UNITS);
        for (Image img : this.images) {
            if (img.imageUnit >= maxImageUnits) {
                SodiumClientMod.logger().warn("[Iris image] " + img.name + " image unit " + img.imageUnit
                        + " >= GL_MAX_IMAGE_UNITS " + maxImageUnits + " — colored lighting/WSR may misbehave");
            }
            img.texture = GL11C.glGenTextures();
            GL11C.glBindTexture(img.target, img.texture);
            // The 3D float flood-fill light volumes (floodfill_img/_copy, rgba16f) are sampled by the pack's terrain via
            // texture(floodfill_sampler, pos) at FRACTIONAL coords — the hardware-trilinear equivalent of GetComplexLightVolume's
            // manual weighted blend. With NEAREST the colored light came out per-voxel BLOCKY (hard light cubes), so its high
            // screen-space variance tripped composite6's TAA neighbourhood clamp (ClipAABB) under the projection jitter →
            // the light cast on surfaces flickered. LINEAR smooths the volume like real Iris, killing the flicker at the source.
            // Integer volumes (voxel_img/wsr_img/leaves_img, *ui) are NOT filterable and must stay NEAREST; image load/store
            // ignores this filter, so the compute writes are unaffected.
            boolean linear = img.depth > 0 && isFilterableFloat(img.internalFormat);
            int filter = linear ? GL11C.GL_LINEAR : GL11C.GL_NEAREST;
            GL11C.glTexParameteri(img.target, GL11C.GL_TEXTURE_MIN_FILTER, filter);
            GL11C.glTexParameteri(img.target, GL11C.GL_TEXTURE_MAG_FILTER, filter);
            GL11C.glTexParameteri(img.target, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);
            GL11C.glTexParameteri(img.target, GL11C.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11C.glTexParameteri(img.target, GL11C.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            if (img.depth == 0) {
                GL42C.glTexStorage2D(img.target, 1, img.internalFormat, img.width, img.height);
            } else {
                GL42C.glTexStorage3D(img.target, 1, img.internalFormat, img.width, img.height, img.depth);
            }
            GL11C.glBindTexture(img.target, 0);
            clearImage(img);
        }
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);

        for (Ssbo s : this.ssbos) {
            s.buffer = GL15C.glGenBuffers();
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, s.buffer);
            GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, s.bytes, GL15C.GL_DYNAMIC_DRAW);
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
        }
        StringBuilder sb = new StringBuilder("[Iris image] allocated " + this.images.size() + " custom images + "
                + this.ssbos.size() + " SSBOs (GL_MAX_IMAGE_UNITS=" + maxImageUnits + ")\n");
        // Log each image's resolved size: the pack declares one size variant per COLORED_LIGHTING under #if/#elif
        // branches, so a mis-resolved branch silently allocates the WRONG dimensions while the shader still computes its
        // indices from sceneVoxelVolumeSize — imageStore then lands out of bounds and the volume stays empty.
        for (Image img : this.images) {
            sb.append(String.format("    %-20s -> %-20s %dx%dx%d imageUnit=%d texUnit=%d clear=%b%n",
                    img.name, img.samplerName, img.width, img.height, Math.max(1, img.depth),
                    img.imageUnit, img.textureUnit, img.clear));
        }
        for (Ssbo s : this.ssbos) {
            sb.append(String.format("    bufferObject.%d -> %d bytes (%d uvec4)", s.index, s.bytes, s.bytes / 16L));
        }
        SodiumClientMod.logger().info(sb.toString());
    }

    /** Zeroes every {@code clear=true} image. Call once per frame before the passes that voxelize into them. */
    public void clearForFrame() {
        for (Image img : this.images) {
            if (img.clear) {
                clearImage(img);
            }
        }
    }

    /**
     * Zeroes every SSBO each frame (call once, alongside {@link #clearForFrame}, before the shadow pass writes them).
     *
     * <p>Why this is needed on our 1.12.2 port but not upstream: the WSR face-data SSBO ({@code blockDataSSBO}) is
     * indexed by a CAMERA-RELATIVE voxel position ({@code playerToSceneVoxel} adds {@code cameraPositionBestFract}), so
     * as the camera moves the same SSBO slot maps to a different world block every frame. {@code wsr_img} (occupancy) is
     * {@code clear=true} so it only ever reflects THIS frame's voxelization; but the SSBO is never cleared upstream, so a
     * face the ray hits that was NOT written this frame reads a STALE UV from some other world block. Upstream (1.20.1)
     * samples a BLOCK-ONLY atlas, so a stale UV just yields some other block texture — invisible. Our port samples the
     * 1.12.2 COMBINED block+item atlas, so a stale UV lands in the item-icon region → stray "item textures" smeared over
     * reflections. Zeroing the SSBO makes unwritten faces read {@code textureRad==0}, which getShadedReflection rejects
     * ({@code textureBounds.z < 1e-6 → vec4(-1)}) so the ray marches past them to a real face or the sky. Costs one
     * buffer clear per frame; the only upstream behaviour lost is the ~1% temporal lightmap smoothing in storeToAllFaces
     * (lava/fire), which is negligible.</p>
     */
    public void clearSsbosForFrame() {
        if (this.ssbos.isEmpty()) {
            return;
        }
        for (Ssbo s : this.ssbos) {
            if (s.buffer != 0) {
                GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, s.buffer);
                // GL_R32UI + null data → fills the whole store with 32-bit zeros (buffer size is a multiple of 16).
                GL43C.glClearBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, GL30C.GL_R32UI, GL30C.GL_RED_INTEGER,
                        GL11C.GL_UNSIGNED_INT, (java.nio.ByteBuffer) null);
            }
        }
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
    }

    /**
     * DIAGNOSTIC/AB-TEST: zeroes the named images NOW (e.g. after the shadow pass has voxelized into them), discarding
     * this frame's voxelization. Clearing {@code wsr_img} + {@code wsr_lod_img} makes every WSR ray miss, so the pack's
     * reflections fall back to SSR + sky — which isolates whether a reflection artefact comes from WSR or from SSR.
     */
    public void clearImagesNow(String... names) {
        for (String n : names) {
            Image img = this.byName.get(n);
            if (img != null) {
                clearImage(img);
            }
        }
    }

    /** DIAGNOSTIC: reads the whole integer voxel volume back and logs a histogram of its ids (top 12 by count,
     * air/0 and solid/1 reported separately) — names exactly WHICH ids fill the volume as light sources. */
    public void diagVoxelIdHistogram(String name) {
        Image img = this.byName.get(name);
        if (img == null || img.texture == 0 || img.depth == 0) {
            SodiumClientMod.logger().info("[Iris voxelHist] " + name + " unavailable");
            return;
        }
        int count = img.width * img.height * img.depth;
        java.nio.ShortBuffer data = org.lwjgl.BufferUtils.createShortBuffer(count);
        GL11C.glBindTexture(img.target, img.texture);
        GL11C.glGetTexImage(img.target, 0, GL30C.GL_RED_INTEGER, GL11C.GL_UNSIGNED_SHORT, data);
        GL11C.glBindTexture(img.target, 0);
        long air = 0, solid = 0;
        java.util.Map<Integer, Long> freq = new java.util.HashMap<>();
        for (int i = 0; i < count; i++) {
            int v = data.get(i) & 0xFFFF;
            if (v == 0) { air++; continue; }
            if (v == 1) { solid++; continue; }
            freq.merge(v, 1L, Long::sum);
        }
        StringBuilder sb = new StringBuilder("[Iris voxelHist] " + name + " " + img.width + "x" + img.height + "x"
                + img.depth + ": air=" + air + " solid=" + solid + " others=" + freq.size() + " distinct ids:");
        freq.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(12)
                .forEach(e -> sb.append(String.format(" id=%d(x%d, low15=%d, cw=%d)",
                        e.getKey(), e.getValue(), e.getKey() & 32767, (e.getKey() >> 15) & 1)));
        SodiumClientMod.logger().info(sb.toString());
    }

    /** Zeroes one image to 0 (glClearTexImage with null data → zero-filled). */
    private static void clearImage(Image img) {
        if (img.texture == 0) {
            return;
        }
        // glClearTexImage (GL 4.4) is state-independent and works on integer + float formats alike.
        GL44C.glClearTexImage(img.texture, 0, img.pixelFormat, img.pixelType, (java.nio.ByteBuffer) null);
    }

    /** Binds every SSBO to its {@code bufferObject.<index>} binding point (global, program-independent). */
    public void bindSsbos() {
        for (Ssbo s : this.ssbos) {
            if (s.buffer != 0) {
                GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, s.index, s.buffer);
            }
        }
    }

    /**
     * Binds every image to its image unit (load/store) + its sampler unit (texture read), and points the program's
     * {@code <name>}/{@code <samplerName>} uniforms at those units. Inert for uniforms the program doesn't declare
     * ({@link GlslProgram#setInt} no-ops on location -1). Restores the active texture unit to 0 for MC's texturing.
     * Call while {@code program} is bound, for every pass (shadow/prepare/shadowcomp/deferred/composite/gbuffers).
     */
    public void bindTo(GlslProgram program) {
        for (Image img : this.images) {
            if (img.texture == 0) {
                continue;
            }
            // Image binding (imageLoad/imageStore). layered=true for 3D so the whole volume is addressable.
            GL42C.glBindImageTexture(img.imageUnit, img.texture, 0, img.depth != 0, 0,
                    GL15C.GL_READ_WRITE, img.internalFormat);
            program.setInt(img.name, img.imageUnit);
            // Sampler binding (texture()/texelFetch reads).
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + img.textureUnit);
            GL11C.glBindTexture(img.target, img.texture);
            program.setInt(img.samplerName, img.textureUnit);
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    /**
     * DIAGNOSTIC: reads back the named image and logs how many texels are non-zero (+ a sample max), to pinpoint where
     * the colored-lighting chain breaks: voxel_img non-zero → voxelization runs; floodfill non-zero → the compute runs.
     * Integer images (r*ui) are read as UNSIGNED_INT, float images (rgba16f) as FLOAT. Expensive (full readback) — call
     * rarely. No-op if the image isn't present.
     */
    /**
     * DIAGNOSTIC: total luminance of a floodfill image, for logging over CONSECUTIVE frames to see a flicker directly.
     * A static colored light should converge to a steady total; if the two ping-pong buffers alternate high/low each
     * frame (and the terrain reads whichever framemod2 points at), that alternation IS the flicker.
     */
    public double diagTotalLum(String name) {
        Image img = this.byName.get(name);
        if (img == null || img.texture == 0) {
            return -1;
        }
        int texels = img.width * img.height * (img.depth == 0 ? 1 : img.depth);
        java.nio.FloatBuffer buf = org.lwjgl.BufferUtils.createFloatBuffer(texels * 4);
        GL11C.glBindTexture(img.target, img.texture);
        GL11C.glGetTexImage(img.target, 0, GL11C.GL_RGBA, GL11C.GL_FLOAT, buf);
        GL11C.glBindTexture(img.target, 0);
        double sum = 0;
        for (int i = 0; i < texels; i++) {
            sum += buf.get(i * 4) + buf.get(i * 4 + 1) + buf.get(i * 4 + 2);
        }
        return sum;
    }

    /**
     * DIAGNOSTIC: max per-voxel difference between the two ping-pong floodfill images. Their TOTALS match, but the
     * terrain reads one or the other by framemod2 each frame — if they differ LOCALLY (near a light, where the two
     * leapfrogging propagation fronts are a step apart), that per-voxel difference is read alternately and the block
     * light flickers at half framerate. Reports max diff + how many voxels differ by >0.05.
     */
    public void diagCompareFloodfill() {
        Image a = this.byName.get("floodfill_img");
        Image b = this.byName.get("floodfill_img_copy");
        if (a == null || b == null || a.texture == 0 || b.texture == 0) {
            SodiumClientMod.logger().info("[Iris FLOODFILL CMP] (images not present)");
            return;
        }
        int texels = a.width * a.height * (a.depth == 0 ? 1 : a.depth);
        java.nio.FloatBuffer ba = org.lwjgl.BufferUtils.createFloatBuffer(texels * 4);
        java.nio.FloatBuffer bb = org.lwjgl.BufferUtils.createFloatBuffer(texels * 4);
        GL11C.glBindTexture(a.target, a.texture);
        GL11C.glGetTexImage(a.target, 0, GL11C.GL_RGBA, GL11C.GL_FLOAT, ba);
        GL11C.glBindTexture(b.target, b.texture);
        GL11C.glGetTexImage(b.target, 0, GL11C.GL_RGBA, GL11C.GL_FLOAT, bb);
        GL11C.glBindTexture(a.target, 0);
        float maxD = 0; long differ = 0;
        for (int i = 0; i < texels; i++) {
            float d = Math.abs(ba.get(i * 4) - bb.get(i * 4))
                    + Math.abs(ba.get(i * 4 + 1) - bb.get(i * 4 + 1))
                    + Math.abs(ba.get(i * 4 + 2) - bb.get(i * 4 + 2));
            if (d > maxD) maxD = d;
            if (d > 0.05f) differ++;
        }
        SodiumClientMod.logger().info(String.format(
                "[Iris FLOODFILL CMP] floodfill_img vs _copy: max per-voxel diff=%.3f | voxels differing >0.05 = %d / %d (%.3f%%)",
                maxD, differ, texels, 100.0 * differ / texels));
    }

    public void diagCountNonZero(String name) {
        Image img = this.byName.get(name);
        if (img == null || img.texture == 0) {
            SodiumClientMod.logger().info("[Iris image DIAG] " + name + ": (not present)");
            return;
        }
        int texels = img.width * img.height * (img.depth == 0 ? 1 : img.depth);
        boolean integer = img.pixelFormat == GL30C.GL_RED_INTEGER || img.pixelFormat == GL30C.GL_RG_INTEGER
                || img.pixelFormat == GL30C.GL_RGBA_INTEGER;
        GL11C.glBindTexture(img.target, img.texture);
        long nonZero = 0;
        double maxV = 0;
        if (integer) {
            java.nio.IntBuffer buf = org.lwjgl.BufferUtils.createIntBuffer(texels);
            GL11C.glGetTexImage(img.target, 0, GL30C.GL_RED_INTEGER, GL11C.GL_UNSIGNED_INT, buf);
            for (int i = 0; i < texels; i++) {
                int v = buf.get(i);
                if (v != 0) {
                    nonZero++;
                    if (v > maxV) maxV = v;
                }
            }
        } else {
            java.nio.FloatBuffer buf = org.lwjgl.BufferUtils.createFloatBuffer(texels * 4);
            GL11C.glGetTexImage(img.target, 0, GL11C.GL_RGBA, GL11C.GL_FLOAT, buf);
            for (int i = 0; i < texels; i++) {
                float r = buf.get(i * 4), g = buf.get(i * 4 + 1), b = buf.get(i * 4 + 2);
                if (r != 0 || g != 0 || b != 0) {
                    nonZero++;
                    double m = Math.max(r, Math.max(g, b));
                    if (m > maxV) maxV = m;
                }
            }
        }
        GL11C.glBindTexture(img.target, 0);
        SodiumClientMod.logger().info(String.format("[Iris image DIAG] %s (%dx%dx%d): nonZero=%d/%d max=%.3f",
                name, img.width, img.height, Math.max(1, img.depth), nonZero, texels, maxV));
    }

    /**
     * DIAGNOSTIC (END hand flicker): reads a (2*half)³ region CENTERED on the volume's middle texel of a 3D float
     * image — the hand's colored-light sample comes from exactly there (SceneToVoxel(vec3(0.0)) = fract + volume/2),
     * so this is the content the pulsing lightVolume.a was read from. Sub-image readback (GL 4.5) because the full
     * volume is ~134MB/frame; returns a compact string for a combined per-frame log line. ALPHA is reported —
     * diagCountNonZero ignores it, and alpha (the artificial-light boost) is the flickering term.
     */
    public String diagCenterProbe(String name, int half) {
        Image img = this.byName.get(name);
        if (img == null || img.texture == 0 || img.depth == 0) {
            return name + "=absent";
        }
        int sz = half * 2;
        int x0 = Math.max(0, img.width / 2 - half);
        int y0 = Math.max(0, img.height / 2 - half);
        int z0 = Math.max(0, img.depth / 2 - half);
        java.nio.FloatBuffer buf = org.lwjgl.BufferUtils.createFloatBuffer(sz * sz * sz * 4);
        org.lwjgl.opengl.GL45C.glGetTextureSubImage(img.texture, 0, x0, y0, z0, sz, sz, sz,
                GL11C.GL_RGBA, GL11C.GL_FLOAT, buf);
        float maxRgb = 0.0F, maxA = 0.0F;
        for (int i = 0; i < sz * sz * sz; i++) {
            float m = Math.max(buf.get(i * 4), Math.max(buf.get(i * 4 + 1), buf.get(i * 4 + 2)));
            if (m > maxRgb) maxRgb = m;
            float a = buf.get(i * 4 + 3);
            if (a > maxA) maxA = a;
        }
        return String.format("%s maxRGB=%.5f maxA=%.5f", name, maxRgb, maxA);
    }

    /**
     * DIAGNOSTIC: reads back the first SSBO (blockDataSSBO — the WSR face data) and reports how many uvec4 slots are
     * non-zero and how many getShadedReflection would ACCEPT (textureRad = data.y/65536 >= ~1e-6, i.e. data.y >= 1), plus a
     * few sample origin UVs + textureRads. If nonZero ≈ total slots the per-frame clear is failing (stale garbage → item
     * textures); if it's a small fraction the clear works and the accepted faces are the real voxelized terrain (so any
     * item textures come from those faces' UVs). Expensive (full ~450MB readback) — call once behind a flag.
     */
    public void diagSsbo() {
        if (this.ssbos.isEmpty()) {
            return;
        }
        Ssbo s = this.ssbos.get(0);
        if (s.buffer == 0) {
            return;
        }
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, s.buffer);
        long totalUvec4 = s.bytes / 16L;
        final int CHUNK = 1 << 22; // 4M uvec4 = 64 MB per readback
        java.nio.IntBuffer buf = org.lwjgl.BufferUtils.createIntBuffer(CHUNK * 4);
        long nonZero = 0, accept = 0;
        float minOx = 2, maxOx = -1, minOy = 2, maxOy = -1, minR = 2, maxR = -1;
        // distinct-ish origin count via a coarse 64x64 UV histogram (how many atlas cells the origins spread across)
        boolean[] cells = new boolean[64 * 64];
        int distinctCells = 0;
        for (long off = 0; off < totalUvec4; off += CHUNK) {
            int n = (int) Math.min((long) CHUNK, totalUvec4 - off);
            buf.clear();
            buf.limit(n * 4);
            GL15C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, off * 16L, buf);
            for (int i = 0; i < n; i++) {
                int d0 = buf.get(i * 4), d1 = buf.get(i * 4 + 1), d2 = buf.get(i * 4 + 2), d3 = buf.get(i * 4 + 3);
                if (d0 != 0 || d1 != 0 || d2 != 0 || d3 != 0) {
                    nonZero++;
                    if (d1 >= 1) { // textureRad = d1/65536 >= ~1.5e-5 → getShadedReflection does NOT reject (>= 1e-6)
                        accept++;
                        float ox = ((d0 >>> 16) & 0xFFFF) / 65536f;
                        float oy = (d0 & 0xFFFF) / 65536f;
                        float rad = (d1 & 0xFFFF) / 65536f;
                        minOx = Math.min(minOx, ox); maxOx = Math.max(maxOx, ox);
                        minOy = Math.min(minOy, oy); maxOy = Math.max(maxOy, oy);
                        minR = Math.min(minR, rad); maxR = Math.max(maxR, rad);
                        int cx = Math.min(63, (int) (ox * 64)), cy = Math.min(63, (int) (oy * 64));
                        if (!cells[cy * 64 + cx]) { cells[cy * 64 + cx] = true; distinctCells++; }
                    }
                }
            }
        }
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
        SodiumClientMod.logger().info(String.format(
                "[Iris SSBO DIAG] blockDataSSBO: nonZero=%d accept=%d / %d (%.2f%%) | originX[%.3f..%.3f] originY[%.3f..%.3f]"
                + " texRad[%.4f..%.4f] | distinct atlas cells(64x64)=%d",
                nonZero, accept, totalUvec4, 100.0 * nonZero / Math.max(1, totalUvec4),
                minOx, maxOx, minOy, maxOy, minR, maxR, distinctCells));
    }

    /**
     * DIAGNOSTIC: per-Y-layer occupancy histogram of a 3D voxel image (wsr_img). The volume is camera-centred
     * ({@code playerToSceneVoxel} = scenePos + cameraPositionBestFract + 0.5*size), so with the camera's FEET at
     * {@code cameraPositionBestFract.y} the player stands at layer {@code 32 + fract}: layers below are the ground,
     * layers ABOVE should be near-empty when standing outdoors under open sky.
     *
     * <p>This isolates voxel-grid MISALIGNMENT from every UV/atlas theory: if the air layers above the floor are
     * occupied, the WSR ray leaving a wet floor hits a phantom voxel on its very first steps and returns that block's
     * texture instead of the sky — exactly the "block textures where 1.20.1 is smooth" symptom. A correct grid shows a
     * sharp cliff: occupied below the stand layer, ~0 above it.</p>
     *
     * <p>Expensive (full readback; 512x64x512 R16UI = 64MB as UNSIGNED_INT) — call once behind a flag.</p>
     *
     * @param camFractY {@code fract(cameraPosition.y)} — the sub-block offset the pack adds, so the log can name the
     *                  layer the camera's feet occupy.
     */
    public void diagVoxelYHistogram(String name, float camFractY) {
        Image img = this.byName.get(name);
        if (img == null || img.texture == 0 || img.depth == 0) {
            SodiumClientMod.logger().info("[Iris voxelY DIAG] " + name + ": (not present / not 3D)");
            return;
        }
        int w = img.width, h = img.height, d = img.depth;
        int texels = w * h * d;
        java.nio.IntBuffer buf = org.lwjgl.BufferUtils.createIntBuffer(texels);
        GL11C.glBindTexture(img.target, img.texture);
        GL11C.glGetTexImage(img.target, 0, GL30C.GL_RED_INTEGER, GL11C.GL_UNSIGNED_INT, buf);
        GL11C.glBindTexture(img.target, 0);
        // glGetTexImage 3D ordering: x fastest, then y, then z → index = x + y*w + z*w*h.
        long[] perY = new long[h];
        long total = 0;
        for (int z = 0; z < d; z++) {
            int zBase = z * w * h;
            for (int y = 0; y < h; y++) {
                int yBase = zBase + y * w;
                long c = 0;
                for (int x = 0; x < w; x++) {
                    if (buf.get(yBase + x) != 0) c++;
                }
                perY[y] += c;
                total += c;
            }
        }
        int standLayer = (int) Math.floor(h * 0.5 + camFractY);
        long perLayerCells = (long) w * d;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[Iris voxelY DIAG] %s (%dx%dx%d) total=%d (%.3f%%) camFractY=%.3f standLayer=%d%n",
                name, w, h, d, total, 100.0 * total / texels, camFractY, standLayer));
        long above = 0;
        for (int y = 0; y < h; y++) {
            if (y > standLayer) above += perY[y];
            sb.append(String.format("  y=%2d %s %6.2f%%  %d%s%n", y,
                    y == standLayer ? "<-feet" : (y == standLayer - 1 ? "<-floor" : "      "),
                    100.0 * perY[y] / perLayerCells, perY[y],
                    perY[y] > 0 && y > standLayer ? "   *** AIR LAYER OCCUPIED ***" : ""));
        }
        sb.append(String.format("  SUMMARY: occupied cells strictly ABOVE the feet layer = %d (%.3f%% of that region)",
                above, 100.0 * above / Math.max(1, perLayerCells * (h - 1 - standLayer))));
        SodiumClientMod.logger().info(sb.toString());
    }

    /** Releases all GL textures + SSBOs. */
    public void destroy() {
        for (Image img : this.images) {
            if (img.texture != 0) {
                GL11C.glDeleteTextures(img.texture);
                img.texture = 0;
            }
        }
        for (Ssbo s : this.ssbos) {
            if (s.buffer != 0) {
                GL15C.glDeleteBuffers(s.buffer);
                s.buffer = 0;
            }
        }
        this.images.clear();
        this.byName.clear();
        this.ssbos.clear();
        this.allocated = false;
    }
}
