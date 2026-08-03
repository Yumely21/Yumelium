package com.yumelium.yumelium.shaders.gl;

import me.jellysquid.mods.sodium.client.SodiumClientMod;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL44C;
import org.lwjgl.opengl.GL45C;

/**
 * Iris/Oculus port — MRT deferred bank. Real packs (Complementary) use up to {@code colortex20} with per-index internal
 * formats and a prepare→deferred→composite→final chain where each pass writes only the buffers named in its
 * {@code /* RENDERTARGETS: a,b,c *}{@code /} directive and reads any {@code colortexN}. To let a pass read {@code colortexN}
 * and write {@code colortexN} without a feedback loop, every buffer is <b>double-buffered</b> (ping-pong): reads sample
 * the FRONT texture, the pass writes the BACK, then the written buffers flip. Buffers a pass doesn't write keep their
 * value (persistent temporal buffers).
 */
public final class RenderTargets {
    /** Highest colortex index a real pack (Complementary) references (+1 for the array size). */
    public static final int NUM_COLORTEX = 21;

    /** Default shadow map resolution; the ACTIVE size ({@link #shadowSize}) is set to match the pack's compile-time
     * {@code const int shadowMapResolution} (which depends on SHADOW_QUALITY/SHADOW_SMOOTHING). It MUST match: the pack's
     * volumetric-light god rays sample the shadow map with {@code texelFetch(shadowtex0, ivec2(coord * shadowMapResolution))}
     * — an explicit texel index — so a smaller map reads out of bounds (→ 0 → false shadow) and draws hard shadow-edge
     * bands across the sky. (Terrain shadows use normalized {@code shadow2D} and are unaffected, which is why only the god
     * rays broke.) */
    public static final int DEFAULT_SHADOW_SIZE = 2048;
    private int shadowSize = DEFAULT_SHADOW_SIZE;

    /** Composite read-sampler texture units: depth textures only sample right on units 0/1 on this compat stack, so
     * shadowtex0→0, depthtex0→1, and the colour buffers colortexN→unit {@code COLORTEX_UNIT0 + N}. */
    public static final int SHADOWTEX_UNIT = 0;
    public static final int DEPTHTEX_UNIT = 1;
    public static final int COLORTEX_UNIT0 = 2;
    /** The unit the procedural noise texture ({@code noisetex}) binds to — just past the colortex bank (units 2..22). */
    public static final int NOISETEX_UNIT = COLORTEX_UNIT0 + NUM_COLORTEX;
    /** The unit the opaque-depth R32F colour texture (composite {@code depthtex1}) binds to. A COLOUR texture, so it samples
     * fine on this high unit (unlike a true depth texture, which the compat stack reads black off units 0/1). */
    public static final int OPAQUE_DEPTH_UNIT = NOISETEX_UNIT + 1;
    /** The shadow COLOUR buffers. Ordinary colour textures, so they sample fine on these high units (unlike the shadow
     * DEPTH texture, which this compat stack only reads correctly on units 0/1). shadowcolor0/1 previously both pointed at
     * the shadow DEPTH texture — reading a depth texture through a plain sampler2D, which is the same undefined-read trap
     * the shadowtex0 compare mode had, and it starved the light shafts' SALS of its height input. */
    public static final int SHADOWCOLOR0_UNIT = OPAQUE_DEPTH_UNIT + 1;
    public static final int SHADOWCOLOR1_UNIT = OPAQUE_DEPTH_UNIT + 2;
    // (shadowtex1's unit used to be OPAQUE_DEPTH_UNIT + 3 = 27 — REMOVED: it collided with the custom-images sampler
    // range (endcrystal_sampler=27) which re-bound the unit after the composite read binds. shadowtex1 now lives on
    // TERRAIN_SHADOWTEX1_UNIT for every consumer.)

    /** {@code noisetex} resolution. Complementary declares no {@code noiseTextureResolution}, so Iris' default (256) is
     * used and Iris generates a procedural RGBA random-noise texture at that size; we do the same. */
    public static final int NOISE_SIZE = 256;

    // Sampler units the TRANSLUCENT gbuffers_water program reads the scene from (its SSR reflections / refraction / water
    // fog sample colortex0/5/6/19, depthtex0/1, noisetex). Colortex bank starts at 5 (clear of 0=atlas, 4=shadow).
    // depthtex reuses unit 1: on this compat stack a depth texture only samples correctly on units 0/1, and the pack
    // computes lighting from the vertex lmCoord (never samples the lightmap texture) so overriding unit 1 is safe —
    // otherwise the SSR ray-march reads garbage depth → mis-projected reflections (terrain "floating" in the sky).
    public static final int WATER_COLORTEX_UNIT0 = 5;
    public static final int WATER_DEPTHTEX_UNIT = DEPTHTEX_UNIT;                        // 1 (over the unused lightmap)
    public static final int WATER_NOISETEX_UNIT = WATER_COLORTEX_UNIT0 + NUM_COLORTEX;  // 26

    /** The unit the block TEXTURE ATLAS binds to for the pack's world-space reflections. WSR (getShadedReflection) samples
     * {@code textureAtlas} to colour each reflected voxel by its real block texture. Must sit clear of the composite/water
     * colortex banks (≤26) AND the custom-image sampler units (27+), so it lives high; RTX-class GL exposes ≥80 combined
     * units. Left UNBOUND (as it was), {@code textureAtlas} defaulted to unit 0 → the reflection sampled whatever texture
     * happened to be on unit 0 (item/GUI atlas) → "random item textures" smeared across water/solid reflections. */
    public static final int TEXTURE_ATLAS_UNIT = 40;

    /** The unit {@code cloud-water.png} binds to for the pack's {@code gbuffers} {@code gaux4} (the pack directive is
     * {@code texture.gbuffers.gaux4 = cloud-water.png}). The cloud code reads {@code gaux4.b} as the cloud-shape NOISE
     * (reimaginedClouds/unboundClouds GetCloudNoise, the non-DEFERRED1 branch), so the WETNESS/puddle cloud REFLECTION on
     * opaque terrain needs it. Left unset, gbuffers_terrain's gaux4 defaulted to unit 0 = the block atlas → the reflection
     * sampled the whole atlas as "noise" → "all the random textures" smeared across wet-ground/water reflections. The
     * translucent water program already binds cloud-water.png on its own gaux4 unit; this covers the opaque terrain. */
    public static final int CLOUD_WATER_UNIT = 41;

    /** The unit {@code noisetex} binds to for the OPAQUE TERRAIN program (Sodium's chunk shader, where the transformed
     * gbuffers_terrain runs). Sodium's ChunkShaderInterface only binds u_BlockTex(0)/u_LightTex(1) and the tex/gtexture/
     * lightmap aliases — it never bound {@code noisetex}, so the pack's terrain sampler defaulted to unit 0 = THE BLOCK
     * ATLAS. gbuffers_terrain's rain-puddle code reads noisetex for both the puddle SHAPE
     * ({@code pFormNoise = texture2DLod(noisetex, puddlePosForm, 0.0).b …}, which gates {@code puddleMixer}) and the
     * ripple NORMAL — so the puddles formed in the shape of the atlas's sprites and item icons, laid flat in the ground
     * plane. That is the "item textures on the wet ground": not a reflection at all (they were never mirrored), just
     * atlas-shaped puddles. Same class of bug as {@link #TEXTURE_ATLAS_UNIT} and {@link #CLOUD_WATER_UNIT}; the
     * composite/water/sky programs already set noisetex on their own units, only terrain was missed. */
    public static final int TERRAIN_NOISETEX_UNIT = 42;

    /** Units the OPAQUE TERRAIN program binds the few SCENE buffers its own fragment samples to. Sodium's chunk shader
     * only binds the atlas/lightmap/aliases + (via applyTerrainUniforms) shadow/gaux4/noisetex/custom-images, and the
     * full colortex/depthtex bank is bound ONLY for the water pass ({@link #bindWaterSceneTextures}). So every scene
     * sampler gbuffers_terrain reads was left at its default 0 = THE BLOCK ATLAS. The transformed terrain fragment reads
     * exactly three: {@code colortex10} (ApplyMultiColoredBlocklight reads the reprojected coloured-blocklight buffer —
     * this is the one that showed as tinted "item textures" smeared over lit surfaces near light sources, and blew out
     * auto-exposure because the atlas RGB got added as blocklight), {@code colortex18} (Voxy screen-space shadow), and
     * {@code shadowcolor0} (stained-glass coloured shadow). Same class of bug as {@link #TEXTURE_ATLAS_UNIT} /
     * {@link #CLOUD_WATER_UNIT} / {@link #TERRAIN_NOISETEX_UNIT}. High units, clear of everything else terrain uses. */
    public static final int TERRAIN_COLORTEX10_UNIT = 43;
    public static final int TERRAIN_COLORTEX18_UNIT = 44;
    public static final int TERRAIN_SHADOWCOLOR0_UNIT = 45;
    /** colortex5 for the immediate-mode gbuffers programs, which the pack aliases as {@code gaux2}. gbuffers_textured
     * reads {@code texelFetch(gaux2, texelCoord, 0).a} as the volumetric-cloud depth and DISCARDS particles behind it —
     * so left on unit 0 it read the block atlas's alpha at screen coords and discarded particles at random. */
    public static final int TERRAIN_GAUX2_UNIT = 46;
    /** {@code shadowtex1} for the GBUFFERS programs (terrain/water/entities): the opaque-only shadow depth on its own
     * high unit, bound once per frame in beginWorldRender (persists; nothing else touches it). Without this the gbuffers'
     * shadowtex1 aliased shadowtex0 — underwater, the seabed's {@code shadow1 > 0.9999} test then compared against the
     * WATER surface depth and always failed → the coloured-shadow branch never fired → pitch-black seabed. */
    public static final int TERRAIN_SHADOWTEX1_UNIT = 47;
    /** {@code colortex6} for the OVERLAY programs (armor glint / spider eyes): their DRAWBUFFERS:06 fragment PRESERVES
     * the material buffer by re-writing a {@code texelFetch} of it — reading the very texture that is attached, which is
     * the Iris semantics the pack was written against (same-texel read+write; blend on that slot is off). */
    public static final int OVERLAY_COLORTEX6_UNIT = 48;

    /** 1×1 stand-ins for the resource-pack labPBR textures ({@code specular}/{@code normals}) that this port never has:
     * UNASSIGNED those samplers read unit 0 = the BLOCK ATLAS, and customEmission.glsl then treats the sprite's own
     * mip-averaged alpha (0&lt;a&lt;1 on edge texels) as labPBR emission — held items burst 10× emissive in the dark END
     * while moving (the END hand flicker; v11 measured pow2(emission) spiking with every other term frozen). Real Iris
     * binds an all-zero specular (a=0 → customEmission early-outs) and a flat normal; these reproduce that. */
    public static final int DEFAULT_SPECULAR_UNIT = 49;
    public static final int DEFAULT_NORMALS_UNIT = 50;
    private int defaultSpecularTex, defaultNormalsTex;

    /** Binds the default labPBR stand-ins on their units (creating them on first use); once per frame is enough —
     * nothing else touches units 49/50. */
    public void bindDefaultPbrTextures() {
        if (this.defaultSpecularTex == 0) {
            this.defaultSpecularTex = makePixelTexture(0, 0, 0, 0);
            this.defaultNormalsTex = makePixelTexture(127, 127, 255, 255);
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + DEFAULT_SPECULAR_UNIT);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, this.defaultSpecularTex);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + DEFAULT_NORMALS_UNIT);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, this.defaultNormalsTex);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    private static int makePixelTexture(int r, int g, int b, int a) {
        int tex = GL11C.glGenTextures();
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, tex);
        java.nio.ByteBuffer px = org.lwjgl.BufferUtils.createByteBuffer(4);
        px.put((byte) r).put((byte) g).put((byte) b).put((byte) a).flip();
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8, 1, 1, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, px);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
        return tex;
    }

    /** Binds the current FRONT colortex6 on {@link #OVERLAY_COLORTEX6_UNIT} for an overlay program's material read. */
    public void bindOverlayColortex6() {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + OVERLAY_COLORTEX6_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, front(6));
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    /**
     * The colortex indices Complementary's overworld {@code gbuffers_terrain} writes, in {@code gl_FragData} order
     * ({@code RENDERTARGETS:0,6,4,10,11,20}). Simpler config branches ({@code DRAWBUFFERS:06} / {@code 064}) are prefixes
     * of this list, so attaching all six is consistent with whichever branch the preprocessor actually keeps.
     */
    public static final int[] TERRAIN_TARGETS = {0, 6, 4, 10, 11, 20};

    /**
     * Per-index clear flag, resolved from the pack's {@code const bool colortexNClear} declarations by
     * {@link #setClearFlags}.
     *
     * <p><b>The default is CLEAR</b> — OptiFine/Iris wipe every colortex each frame and a pack opts OUT with
     * {@code const bool colortexNClear = false;}. This array used to be hardcoded the other way round ("default: don't
     * clear; clear the ones Complementary marks = true"), which silently left every buffer the pack does not mention
     * uncleared. The one that mattered was <b>colortex8</b> ("SSR results for WSR"): only gbuffers_water writes it, and
     * composite1:219 reads it for every pixel that passes the fresnel gate, so on any reflective surface with no water
     * drawn this frame the read returned an OLD frame's water reflection — and composite1's
     * {@code mix(compositeReflection, ssrReflection, float(ssrReflection.a > 0.999))} lets a stale alpha REPLACE the WSR
     * reflection outright. That is the reflection afterimage around light sources (bright SSR = visible stale value).
     */
    private static final boolean[] CLEAR = new boolean[NUM_COLORTEX];
    static {
        java.util.Arrays.fill(CLEAR, true);
    }

    /** Applies the pack's resolved {@code colortexNClear = false} opt-outs; every other buffer is cleared each frame. */
    public static void setClearFlags(boolean[] resolved) {
        java.util.Arrays.fill(CLEAR, true);
        if (resolved == null) {
            return;
        }
        System.arraycopy(resolved, 0, CLEAR, 0, Math.min(resolved.length, NUM_COLORTEX));
    }

    /** Per-index mipmap flag: a colortex a pass declares {@code colortexNMipmapEnabled = true} needs a real mip pyramid so
     * the pack's {@code texture2DLod(colortexN, uv, lod)} reads (bloom / atmospheric downsampling) sample a proper mip
     * instead of garbage → the blocky noise around bright areas (the sun). Complementary requests it for colortex0
     * (deferred1/composite3/composite4) and colortex3 (composite6). Such textures get a mip-capable filter at creation and
     * are re-mipmapped ({@link #generateMipmap}) right before a pass that reads them mipmapped. */
    private static final boolean[] MIPMAPPED = new boolean[NUM_COLORTEX];
    static {
        MIPMAPPED[0] = true;
        MIPMAPPED[3] = true;
    }

    /** Per-index LINEAR-filter flag. Iris' default colortex filter is LINEAR; we use NEAREST, which is fine for the
     * texelFetch/1:1 reads but WRONG for a buffer a pass samples at FRACTIONAL coords.
     *  - colortex2 (TAA history) is read via {@code textureCatmullRom} (fractional taps for the reprojection) — NEAREST
     *    made the reprojected history blocky → TAA couldn't align/converge → residual cloud/water flicker.
     *  - colortex3 holds the BLOOM TILES: composite4 writes the scene downsampled into small tile regions of colortex3,
     *    then composite5's DoBloom UPSAMPLES them back with {@code texture2D(colortex3, bloomCoord/rescale)} (a fractional
     *    read of a much smaller tile). With NEAREST that upsample showed each tile's coarse texels → blocky nested squares
     *    of the sun-glare halo. LINEAR bilinearly interpolates the tiles → a smooth glow (composite5 clamps the coord
     *    inside each tile, which only makes sense with bilinear). colortex3 is also mipmap-managed (composite6) + is the
     *    final display buffer, both of which are fine with a LINEAR base filter. */
    private static final boolean[] LINEAR = new boolean[NUM_COLORTEX];
    static {
        LINEAR[2] = true;
        LINEAR[3] = true;
    }

    private final int[] colortexA = new int[NUM_COLORTEX];
    private final int[] colortexB = new int[NUM_COLORTEX];
    private final boolean[] flip = new boolean[NUM_COLORTEX];
    private int depthTex;
    private int fbo;
    // Number of colour-attachment slots currently populated on `fbo` (slots 0..count-1). The stale-attachment detach
    // loops used to stop at a fixed TERRAIN_TARGETS.length (6): if any pass ever attached MORE than 6 targets, slot 6+
    // would stay attached forever — and a texture left attached to the draw framebuffer while a later pass SAMPLES it
    // is a spec feedback hazard even when glDrawBuffers doesn't list the slot. Tracking the real high-water mark makes
    // the detach exact at any width. All attach sites operate on this single `fbo`, so one counter is enough.
    private int colorAttachHigh = 1;

    // Procedural noise texture (noisetex) — size-independent, created lazily, torn down in destroy(). Many pack effects
    // (clouds, the sun-glare halo, dithering, water) sample it; without a real noise texture they read the scene colour
    // (our old fallback) → clouds/atmosphere collapse. REPEAT wrap + LINEAR filter so tiled fract() coords sample smoothly.
    private int noiseTex;

    // Custom pack textures (OptiFine `texture.*` bindings) supplied by IrisPipeline as raw PNG bytes:
    //  - noisePngData      → the pack's own `lib/textures/noise.png` (shaders.properties `texture.noise=`), used for
    //                        noisetex instead of our random noise (packs are TUNED to their noise texture's statistics).
    //  - waterNormalPngData→ `lib/textures/cloud-water.png` bound to `gaux4` (`texture.gbuffers.gaux4=`): the WATER NORMAL
    //                        MAP gbuffers_water samples for ripples. Missing it (reading an empty scene buffer) gave a
    //                        garbage water normal → the reflection curved. Bound on the water's gaux4 (colortex7) unit.
    private byte[] noisePngData;
    private byte[] waterNormalPngData;
    private int waterNormalTex;

    // A copy of colortex0 (the opaque scene) taken just before the translucent water pass, so gbuffers_water's SSR
    // reflections read a STABLE scene instead of colortex0 while it's also the pass's render target (reading a live render
    // target is undefined → dark / self-reflecting water). Same format as colortex0 (R11F_G11F_B10F), viewport-sized.
    private int waterReflectTex;

    // A snapshot of colortex5 (= deferred1's `waterRefColor`, sqrt-encoded scene colour + cloudLinearDepth in .a) taken
    // right after deferred1 runs. gbuffers_water's SSR reads gaux2 (==colortex5) for the reflected scene colour, but in
    // our pipeline the water is drawn BEFORE the deferred/composite chain (Iris draws it AFTER deferred), so a live read
    // gets LAST frame's colortex5 — which composite1/composite5 overwrite with 0 → the terrain reflection is black. This
    // snapshot preserves the real waterRefColor for the water to read (1-frame-old, fine for SSR). Same RGBA8 as colortex5.
    private int waterRefTex;

    // An OPAQUE-only depth copy (depthtex1), snapshotted just before the translucent water pass. gbuffers_water passes
    // depthtex1 to its SSR ray march + refraction/fog, but our single depthTex is ALSO the water FBO's live depth
    // attachment (depthMask is on during the water draw) — sampling it there is a read/write feedback loop (undefined →
    // garbage depth → wrong reflections). Real Iris reads a pre-translucent copy. This copy = opaque scene depth (water
    // not yet drawn), bound on the water's depth unit instead of the live attachment.
    private int waterDepthTex;

    // The opaque scene depth as an R32F COLOUR texture, for the COMPOSITE's depthtex1. The compat stack samples true depth
    // textures correctly only on units 0/1 (units 0/1 = shadowtex/depthtex0 are already taken), so the composite couldn't
    // read a second (opaque) depth — leaving depthtex0==depthtex1 → z0==z1 → the pack never detects water → getWSR (world-
    // space reflections) never ran. A colour texture samples on ANY unit; a copy pass writes waterDepthTex's depth into
    // this R32F, and the composite reads it as depthtex1 → z0!=z1 at water pixels → getWSR fires.
    private int opaqueDepthColorTex;
    private int opaqueDepthFbo;

    private int width = -1;
    private int height = -1;
    private boolean created;

    // Shadow map — size-independent (fixed SHADOW_SIZE), created lazily, torn down in destroy().
    private int shadowFbo;
    private int shadowDepthTex;
    private int shadowColorTex;
    private int shadowColorTex1;
    /** shadowtex1 — the OPAQUE-ONLY shadow depth (snapshot taken before the translucent shadow draw). See ensureShadow. */
    private int shadowDepthTexOpaque;
    /** Blit target for {@link #snapshotOpaqueShadowDepth} — depth copies need a framebuffer blit, not a texture copy. */
    private int shadowOpaqueFbo;
    private boolean shadowCreated;

    private static void log(String s) {
        SodiumClientMod.logger().info("[Iris] " + s);
    }

    public int width() {
        return this.width;
    }

    /** @return true once the world FBO allocated AND passed the completeness check (the health report echoes this). */
    public boolean isCreated() {
        return this.created;
    }

    /** @return the world/composite FBO's GL id (diagnostics: compare against GL_FRAMEBUFFER_BINDING). */
    public int fboId() {
        return this.fbo;
    }

    public int height() {
        return this.height;
    }

    public int depthTexture() {
        return this.depthTex;
    }

    /** The texture currently holding colortexN's contents (what reads should sample). */
    public int front(int i) {
        return this.flip[i] ? this.colortexB[i] : this.colortexA[i];
    }

    /** The ping-pong partner colortexN writes into (then {@link #flipTargets} swaps it to the front). */
    private int back(int i) {
        return this.flip[i] ? this.colortexA[i] : this.colortexB[i];
    }

    public int normalTexture() {
        return front(4);
    }

    public int shadowDepthTexture() {
        return this.shadowDepthTex;
    }

    /** @return the active shadow map resolution (matches the pack's {@code shadowMapResolution} const). */
    public int shadowSize() {
        return this.shadowSize;
    }

    /** Sets the shadow map resolution to match the pack's {@code shadowMapResolution} const. Recreates the shadow map
     * lazily at the new size if it changed (the god-ray texel-index sampling requires an exact match — see field note). */
    public void setShadowSize(int size) {
        if (size <= 0 || size == this.shadowSize) {
            return;
        }
        this.shadowSize = size;
        destroyShadow(); // ensureShadow() rebuilds at the new size on next use
    }

    /** @return the procedural noise texture id (lazily generated), for {@code noisetex}. */
    public int noiseTexture() {
        ensureNoise();
        return this.noiseTex;
    }

    /** Supplies the raw PNG bytes of the pack's custom textures (read by IrisPipeline while the pack is open). Stored so
     * the (lazily created) noise + water-normal GL textures load from the pack instead of a random/empty fallback. */
    public void setCustomTextures(byte[] noisePng, byte[] waterNormalPng) {
        this.noisePngData = noisePng;
        this.waterNormalPngData = waterNormalPng;
    }

    /** Decodes a PNG (via ImageIO) into a REPEAT+LINEAR RGBA8 GL texture (top row = image row 0, matching OptiFine's custom
     * texture loader so the pack's assumed orientation holds). @return the texture id, or 0 on failure. */
    private static int loadPngRepeatLinear(byte[] png) {
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
            if (img == null) {
                return 0;
            }
            int w = img.getWidth(), h = img.getHeight();
            java.nio.ByteBuffer buf = org.lwjgl.BufferUtils.createByteBuffer(w * h * 4);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = img.getRGB(x, y);
                    buf.put((byte) ((argb >> 16) & 0xFF)); // R
                    buf.put((byte) ((argb >> 8) & 0xFF));  // G
                    buf.put((byte) (argb & 0xFF));         // B
                    buf.put((byte) ((argb >> 24) & 0xFF)); // A
                }
            }
            buf.flip();
            int tex = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8, w, h, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
            return tex;
        } catch (Throwable t) {
            log("PNG decode failed: " + t);
            return 0;
        }
    }

    /** Loads {@code noisetex} — the pack's own {@code noise.png} if supplied (packs are tuned to it), else a deterministic
     * random fallback. REPEAT wrap + LINEAR filter matches how packs tile/sample it. */
    private void ensureNoise() {
        if (this.noiseTex != 0) {
            return;
        }
        if (this.noisePngData != null) {
            int tex = loadPngRepeatLinear(this.noisePngData);
            if (tex != 0) {
                this.noiseTex = tex;
                log("loaded noisetex from pack noise.png (id=" + tex + ")");
                return;
            }
        }
        java.nio.ByteBuffer pixels = org.lwjgl.BufferUtils.createByteBuffer(NOISE_SIZE * NOISE_SIZE * 4);
        java.util.Random rand = new java.util.Random(0L);
        byte[] row = new byte[NOISE_SIZE * 4];
        for (int y = 0; y < NOISE_SIZE; y++) {
            rand.nextBytes(row);
            pixels.put(row);
        }
        pixels.flip();
        int tex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8, NOISE_SIZE, NOISE_SIZE, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        this.noiseTex = tex;
        log("generated noisetex " + NOISE_SIZE + "x" + NOISE_SIZE + " (RGBA random fallback, id=" + tex + ")");
    }

    /** Loads the water normal map ({@code gaux4} = {@code cloud-water.png}) once, if the pack supplied it. */
    private void ensureWaterNormal() {
        if (this.waterNormalTex != 0 || this.waterNormalPngData == null) {
            return;
        }
        int tex = loadPngRepeatLinear(this.waterNormalPngData);
        if (tex != 0) {
            this.waterNormalTex = tex;
            log("loaded water normal map (gaux4/colortex7) from pack cloud-water.png (id=" + tex + ")");
        }
    }

    /** @return the GL id of the pack's {@code cloud-water.png} ({@code gaux4} = cloud-shape noise + water normal), loading
     * it lazily; 0 if the pack supplied none. Bound to {@link #CLOUD_WATER_UNIT} for gbuffers_terrain's cloud reflection. */
    public int waterNormalTexture() {
        ensureWaterNormal();
        return this.waterNormalTex;
    }

    public int currentColor0() {
        return front(0);
    }

    public void resize(int w, int h) {
        if (this.created && w == this.width && h == this.height) {
            return;
        }
        destroy();
        create(w, h);
    }

    private static int internalFormat(int i) {
        switch (i) {
            case 0:  return GL30C.GL_R11F_G11F_B10F;
            case 1:
            case 4:  return GL31C.GL_RGBA8_SNORM;
            case 2:
            case 7:
            case 8:
            case 9:
            case 10:
            case 11:
            case 20: return GL30C.GL_RGBA16F;
            case 18: return GL30C.GL_R8;
            default: return GL11C.GL_RGBA8;
        }
    }

    private void create(int w, int h) {
        this.width = w;
        this.height = h;

        for (int i = 0; i < NUM_COLORTEX; i++) {
            int fmt = internalFormat(i);
            this.colortexA[i] = newColorTexture(w, h, fmt);
            this.colortexB[i] = newColorTexture(w, h, fmt);
            this.flip[i] = false;
            // Zero both ping-pong textures so an uninitialised float buffer (R11F/RGBA16F) can't seed NaN into a temporal
            // composite buffer (colortex2 exposure/TAA etc.) that reads itself before anything writes it.
            GL44C.glClearTexImage(this.colortexA[i], 0, GL11.GL_RGBA, GL11.GL_FLOAT, (float[]) null);
            GL44C.glClearTexImage(this.colortexB[i], 0, GL11.GL_RGBA, GL11.GL_FLOAT, (float[]) null);
            if (LINEAR[i]) {
                setLinearFilter(this.colortexA[i]);
                setLinearFilter(this.colortexB[i]);
            }
        }
        this.depthTex = newSceneDepthTexture(w, h);
        // Scratch copy of colortex0 for the water SSR (same internal format so glCopyImageSubData is legal). LINEAR filter
        // so the SSR's sub-pixel reflected-coord reads interpolate smoothly instead of showing blocky nearest texels.
        this.waterReflectTex = newColorTexture(w, h, GL30C.GL_R11F_G11F_B10F);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.waterReflectTex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        // Opaque-depth copy for the water's depthtex1 reads (SSR/refraction/fog). Same DEPTH24_STENCIL8 format as depthTex
        // so glCopyImageSubData is legal; NEAREST (from newTexture) so depth isn't interpolated across discontinuities.
        this.waterDepthTex = newSceneDepthTexture(w, h);
        // waterRefColor snapshot (gaux2 for the water). Same RGBA8 as colortex5; LINEAR so the SSR's fractional reads interpolate.
        this.waterRefTex = newColorTexture(w, h, GL11C.GL_RGBA8);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.waterRefTex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        // Opaque depth as an R32F colour texture (the composite's depthtex1) + its own FBO for the copy pass. NEAREST so
        // the depth compare (z0 vs z1) isn't blurred across geometry edges.
        this.opaqueDepthColorTex = newColorTexture(w, h, GL30C.GL_R32F);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.opaqueDepthColorTex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        this.opaqueDepthFbo = GL30C.glGenFramebuffers();
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, this.opaqueDepthFbo);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, this.opaqueDepthColorTex, 0);
        GL20C.glDrawBuffers(new int[]{GL30C.GL_COLOR_ATTACHMENT0});

        this.fbo = GL30C.glGenFramebuffers();
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, this.fbo);
        // DEPTH_STENCIL attachment, not DEPTH: depthTex is packed depth24+stencil8 (see newSceneDepthTexture) and the
        // stencil half must actually be attached or stencil-based TESRs stay broken. ALL world-fbo depth attach/detach
        // sites must use this same attachment point — mixing them leaves a stale stencil attachment behind.
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_STENCIL_ATTACHMENT,
                GL11.GL_TEXTURE_2D, this.depthTex, 0);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, this.colortexA[0], 0);
        this.colorAttachHigh = 1; // a fresh FBO has exactly attachment 0 populated (destroy/create can recycle mid-session)

        int status = GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER);
        this.created = status == GL30C.GL_FRAMEBUFFER_COMPLETE;
        if (!this.created) {
            log("WARNING: framebuffer incomplete, status=0x" + Integer.toHexString(status));
        } else {
            log("allocated targets " + w + "x" + h + " (colortex0.." + (NUM_COLORTEX - 1) + " ping-pong + depth, fbo=" + this.fbo + ")");
        }
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
    }

    private static int newColorTexture(int w, int h, int internalFormat) {
        int format, type;
        switch (internalFormat) {
            case GL30C.GL_R11F_G11F_B10F: format = GL11.GL_RGB;  type = GL11.GL_FLOAT;         break;
            case GL30C.GL_RGBA16F:        format = GL11.GL_RGBA; type = GL11.GL_FLOAT;         break;
            case GL31C.GL_RGBA8_SNORM:    format = GL11.GL_RGBA; type = GL11.GL_BYTE;          break;
            case GL30C.GL_R8:             format = GL11.GL_RED;  type = GL11.GL_UNSIGNED_BYTE; break;
            case GL30C.GL_R32F:           format = GL11.GL_RED;  type = GL11.GL_FLOAT;         break;
            default:                      format = GL11.GL_RGBA; type = GL11.GL_UNSIGNED_BYTE; break;
        }
        return newTexture(w, h, internalFormat, format, type);
    }

    private static int newDepthTexture(int w, int h) {
        return newTexture(w, h, GL14C.GL_DEPTH_COMPONENT24, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT);
    }

    /**
     * The SCENE depth texture: packed depth24 + stencil8 instead of plain depth24 — same 24-bit depth precision, plus
     * a real stencil buffer on the world FBO.
     *
     * <p>Why the scene needs stencil at all (2026-08-03): with shaders on, block-entity/entity renderers draw into
     * OUR fbo, and stencil-based renderers — the Betweenlands' mirror ({@code RenderBeamOrigin}) and hole illusions
     * ({@code RenderWallHole}, {@code RenderCCGroundSpawner}) — validate stencil availability against MC's OWN
     * {@code Framebuffer} object ({@code Stencil.reserve} → {@code MinecraftForgeClient.reserveStencilBit} +
     * {@code mcFbo.enableStencil()}), never against the framebuffer actually bound. On a stencil-less FBO the GL spec
     * makes the stencil test ALWAYS PASS and stencil writes no-ops, so the mask those renderers build simply doesn't
     * exist: the mud-tower mirror rendered as a giant unmasked translucent triangle across the whole screen. Giving
     * the world FBO a real stencil buffer makes their glStencilFunc/Op act on actual bits again — vanilla behaviour.
     * Nothing in the pack or the pipeline touches stencil, so the bits belong entirely to such mods.</p>
     *
     * <p>Depth reads are unaffected: sampling a DEPTH24_STENCIL8 texture returns the depth component (default
     * {@code DEPTH_STENCIL_TEXTURE_MODE}), and {@code glGetTexImage}/{@code glGetTextureSubImage} with
     * {@code GL_DEPTH_COMPONENT} stay legal. {@code waterDepthTex} must keep the IDENTICAL internal format —
     * {@code copyDepthForWater} uses {@code glCopyImageSubData}, which requires format compatibility. The SHADOW
     * depth textures deliberately stay plain DEPTH_COMPONENT24 (nothing stencil-tests the light's view, and their
     * snapshot blit only needs the two of THEM to match).</p>
     */
    private static int newSceneDepthTexture(int w, int h) {
        return newTexture(w, h, GL30C.GL_DEPTH24_STENCIL8, GL30C.GL_DEPTH_STENCIL, GL30C.GL_UNSIGNED_INT_24_8);
    }

    /** Sets a colortex to LINEAR min/mag so fractional-coord reads (e.g. TAA's textureCatmullRom on colortex2) interpolate. */
    private static void setLinearFilter(int tex) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
    }

    /**
     * Enables mipmapped reading of colortexN (front) for the DURATION of one pass that declares {@code
     * colortexNMipmapEnabled = true}: refreshes its mip pyramid from the current contents and switches its min filter to
     * {@code LINEAR_MIPMAP_LINEAR} so the pack's {@code texture2DLod(colortexN, uv, lod)} reads select the right level.
     *
     * <p>MUST be paired with {@link #disableMipmapRead} right after the draw. The switch is temporary because leaving a
     * mipmap min filter on permanently makes every other pass's plain {@code texture2D(colortexN, uv)} read pick a
     * fractional auto-LOD (&gt;0), and across the ~10-pass composite chain that compounds into a heavily blurred scene.</p>
     */
    /** Binds the scene buffers the translucent gbuffers_water program samples (colortexN → units {@code
     * WATER_COLORTEX_UNIT0+N}, depthtex → {@code WATER_DEPTHTEX_UNIT}, noisetex → {@code WATER_NOISETEX_UNIT}), then resets
     * the active unit to 0 so the water draw's atlas sampling (unit 0) is unaffected. colortexN are the FRONT (live) scene;
     * colortex0/6 are also render targets of this pass (Iris does the same — the SSR reads already-rendered opaque texels). */
    /** Copies colortex0 (the opaque scene) into the water reflection scratch, called once just before the water pass draws
     * so its SSR reads a stable copy (no read-while-render-target feedback). GPU-side copy (no FBO/shader). */
    public void copyColor0ForWater() {
        if (this.waterReflectTex == 0) {
            return;
        }
        org.lwjgl.opengl.GL43C.glCopyImageSubData(front(0), GL11.GL_TEXTURE_2D, 0, 0, 0, 0,
                this.waterReflectTex, GL11.GL_TEXTURE_2D, 0, 0, 0, 0, this.width, this.height, 1);
    }

    /** Snapshots the current (opaque) scene depth into {@link #waterDepthTex}, called once just before the translucent
     * water draws so gbuffers_water's depthtex1 reads (SSR/refraction/fog) sample a stable OPAQUE-depth copy instead of
     * the live depth attachment it's simultaneously writing (feedback → garbage). GPU-side copy (no FBO/shader). */
    public void copyDepthForWater() {
        if (this.waterDepthTex == 0) {
            return;
        }
        org.lwjgl.opengl.GL43C.glCopyImageSubData(this.depthTex, GL11.GL_TEXTURE_2D, 0, 0, 0, 0,
                this.waterDepthTex, GL11.GL_TEXTURE_2D, 0, 0, 0, 0, this.width, this.height, 1);
    }

    /** Snapshots colortex5 (deferred1's {@code waterRefColor} + cloudLinearDepth) into {@link #waterRefTex}. Called from
     * the composite chain right after the deferred1 pass, BEFORE composite1/composite5 overwrite colortex5.rgb with 0. The
     * water (drawn earlier, in the world pass) then reads this as gaux2 for a correct terrain reflection (see field note). */
    public void snapshotWaterRefColor() {
        if (this.waterRefTex == 0) {
            return;
        }
        org.lwjgl.opengl.GL43C.glCopyImageSubData(front(5), GL11.GL_TEXTURE_2D, 0, 0, 0, 0,
                this.waterRefTex, GL11.GL_TEXTURE_2D, 0, 0, 0, 0, this.width, this.height, 1);
    }

    /** Binds the opaque-depth copy FBO + the source opaque depth (waterDepthTex) on the depth unit, for the pipeline's
     * depth→R32F copy pass (fills {@link #opaqueDepthColorTex} = the composite's depthtex1). Caller draws a fullscreen quad
     * with a shader sampling {@code depthtex0} (unit 1). */
    public void beginOpaqueDepthCopy() {
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, this.opaqueDepthFbo);
        GlStateManager.viewport(0, 0, this.width, this.height);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + DEPTHTEX_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.waterDepthTex);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        // The LIVE scene depth (incl. the first-person hand) on unit 0, so the copy can splice the hand's near depth into
        // depthtex1 (see DEPTH_COPY_FSH). Depth textures only sample correctly on units 0/1 on this compat stack.
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.depthTex);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    /** @return the opaque scene depth as an R32F colour texture (the composite's depthtex1). */
    public int opaqueDepthColorTexture() {
        return this.opaqueDepthColorTex;
    }

    /**
     * DIAGNOSTIC: logs the REAL numbers behind the composite's reflection gate at the crosshair pixel, so the user can
     * aim at either side of a boundary and we read values instead of guessing at colours.
     *
     * <p>Every colour-coded reflection diagnostic so far has been ambiguous: the tag encodings compress several
     * different causes into one hue, the top/bottom split showed two DIFFERENT buffers for two different parts of the
     * scene (so a boundary could not be compared across it), and one of them silently fed its own tag back into the
     * buffer it was measuring. These are the raw inputs to every gate in the chain:</p>
     * <ul>
     *   <li>{@code z0} (depthTex) vs {@code z1} (opaqueDepthColorTex = our depthtex1) — {@code z0 != z1} is the pack's
     *       "this pixel is translucent" test, which routes it to getWSR's early return in reflections.glsl:84</li>
     *   <li>{@code colortex4.a} = fresnelM — gates composite.glsl:116 {@code if (fresnelM > 0.0)}</li>
     *   <li>{@code colortex6.r} = smoothnessD, {@code .g*255} = materialMask</li>
     *   <li>{@code colortex7} = reflectOutput — what composite1 actually gets to blend in</li>
     * </ul>
     * Single-texel {@code glGetTextureSubImage} reads (GL 4.5), not whole-texture {@code glGetTexImage} — cheap enough
     * to run periodically without the stall that forced DIAG_PERIODIC_LOG off.
     */
    public void diagCrosshair() {
        if (this.width <= 0) {
            return;
        }
        int cx = this.width / 2;
        int cy = this.height / 2;
        java.nio.FloatBuffer buf = org.lwjgl.BufferUtils.createFloatBuffer(4);
        float[] c4 = texel(buf, front(4), cx, cy, GL11C.GL_RGBA);
        float[] c6 = texel(buf, front(6), cx, cy, GL11C.GL_RGBA);
        float[] c7 = texel(buf, front(7), cx, cy, GL11C.GL_RGBA);
        float[] z0 = texel(buf, this.depthTex, cx, cy, GL11C.GL_DEPTH_COMPONENT);
        float[] z1 = texel(buf, this.opaqueDepthColorTex, cx, cy, GL11C.GL_RED);
        boolean translucent = z0 != null && z1 != null && Math.abs(z0[0] - z1[0]) > 1e-7f;
        SodiumClientMod.logger().info(String.format(
                "[Iris CROSSHAIR] z0=%.6f z1=%.6f %s | fresnelM(colortex4.a)=%.4f %s | smoothnessD=%.4f matMask=%.0f"
                        + " | colortex7=(%.3f,%.3f,%.3f) a=%.4f",
                z0 == null ? -1f : z0[0], z1 == null ? -1f : z1[0],
                translucent ? "TRANSLUCENT(z0!=z1 -> getWSR early return)" : "OPAQUE(z0==z1 -> SSR+background)",
                c4 == null ? -9f : c4[3],
                c4 != null && c4[3] > 0.0f ? "-> composite RUNS reflections" : "-> composite SKIPS reflections (gate 116)",
                c6 == null ? -9f : c6[0], c6 == null ? -9f : c6[1] * 255.0f,
                c7 == null ? -9f : c7[0], c7 == null ? -9f : c7[1], c7 == null ? -9f : c7[2],
                c7 == null ? -9f : c7[3]));
        if (oreDebug) {
            // Read colortex6 directly — no injection. gbuffers_terrain packs ore glow into gl_FragData[1].a =
            // colortex6.a = lmCoord.x + clamp01(purkinjeOverwrite) + clamp01(emission). So colortex6.a on an ore SPECK
            // should read distinctly HIGHER than on the surrounding stone (which has the same lmCoord.x but no
            // emission). colortex9 was the wrong buffer the whole time — the pack uses it for SSBL albedo (MCBL=3).
            // Aim at the coloured ore speck, then at the grey stone right next to it, and compare the a= values.
            float a6 = c6 == null ? -9f : c6[3];
            SodiumClientMod.logger().info(String.format(
                    "[Iris CROSSHAIR]   ^ ORE: colortex6 = (smoothnessD=%.3f, matMask=%.0f, skyLight=%.3f, packed.a=%.4f)"
                            + "   <- packed.a carries emission; compare ore speck vs adjacent stone",
                    c6 == null ? -9f : c6[0], c6 == null ? -9f : c6[1] * 255.0f,
                    c6 == null ? -9f : c6[2], a6));
        }
        if (wsrColorDebug) {
            // colortex13 = (preFogLum, postFogLum, glColorLum, hitFlag), written out of band so the frame itself is
            // untouched. w==1 is the only thing that means "getShadedReflection actually ran here": without an explicit
            // flag, a hit whose colour is zero is byte-identical to voxelRayTrace's honest MISS return (vec4(0.0)), and
            // the previous version of this readback duly reported every miss as three broken factors at once.
            float[] d = texel(buf, front(13), cx, cy, GL11C.GL_RGBA);
            if (d == null || d[3] < 0.5f) {
                SodiumClientMod.logger().info(
                        "[Iris CROSSHAIR]   ^ WSR: ray MISSED (no voxel hit — correct for a reflection escaping to sky)");
            } else {
                String culprit;
                if (d[0] < 0.0005f) {
                    culprit = "born BLACK (getShadedReflection: atlas/glColor/lighting)";
                } else if (d[1] < 0.0005f) {
                    culprit = "<== the FADEOUT multiply killed it (voxelRayTrace: minOf(fadeout) => sceneVoxelVolumeSize)";
                } else if (d[2] < 0.0005f) {
                    culprit = "<== DoFog killed it";
                } else {
                    culprit = "getWSR returned a LIVE colour -> if colortex7 is still 0, composite.glsl zeroed it after"
                            + " GetReflection (reflection.rgb *= reflectColor)";
                }
                SodiumClientMod.logger().info(String.format(
                        "[Iris CROSSHAIR]   ^ WSR HIT: born=%.4f -> afterFadeout=%.4f -> afterFog=%.4f   %s",
                        d[0], d[1], d[2], culprit));
            }
        }
    }

    /** Set by IrisPipeline when DIAG_WSR_COLOR rewrote colortex7 into (atlasSample, glColor, lighting, alphaFade). */
    public static boolean wsrColorDebug;

    /** Set by IrisPipeline when DIAG_ORE_EMISSION made gbuffers_terrain report (mat, emission, glowFlag, ran) in colortex9. */
    public static boolean oreDebug;

    /**
     * DIAGNOSTIC: same numbers as {@link #diagCrosshair}, but sampled along a horizontal ROW across the whole screen.
     *
     * <p>The crosshair readback settled the last boundary in one round — but only because that boundary could be aimed
     * at. A boundary at the SCREEN EDGE never reaches the centre of the screen, so there is nothing to aim at and no
     * amount of squinting at a screenshot will say which quantity steps across it. This scans the row the crosshair sits
     * on and prints each sample, so the step shows up as a column in the log:</p>
     * <ul>
     *   <li>{@code wsrA} = colortex7.a — 1 ⇒ the voxel trace hit and composite1 uses the WSR, 0 ⇒ it missed and
     *       composite1 falls back to gbuffers_water's own screen-space reflection. A step here IS the SSR/WSR seam.</li>
     *   <li>{@code fresM} = colortex4.a, {@code mat} = colortex6.g*255, {@code T} = translucent (z0 != z1)</li>
     * </ul>
     */
    public void diagScanRow() {
        if (this.width <= 0) {
            return;
        }
        final int cols = 76;
        final int rows = 30;
        java.nio.FloatBuffer buf = org.lwjgl.BufferUtils.createFloatBuffer(4);
        StringBuilder sb = new StringBuilder(
                "[Iris SCAN] reflection map, screen orientation (top row = top of screen)\n"
                        + "   W = translucent, WSR HIT   -> composite1 shows the voxel reflection\n"
                        + "   S = translucent, WSR MISS  -> composite1 falls back to gbuffers_water's screen-space one\n"
                        + "   . = opaque   ' ' = sky\n"
                        + "   Every W/S border is an SSR<->WSR seam; a hard visible line means the two disagree there.\n");
        StringBuilder samples = new StringBuilder();
        double wsrSum = 0, ssrSum = 0;
        int nHit = 0;
        for (int r = 0; r < rows; r++) {
            // GL texture space has y=0 at the BOTTOM, so walk rows backwards to print the map the way the screen looks.
            int y = (rows - 1 - r) * (this.height - 1) / (rows - 1);
            sb.append("   ");
            for (int c = 0; c < cols; c++) {
                int x = c * (this.width - 1) / (cols - 1);
                float[] z0 = texel(buf, this.depthTex, x, y, GL11C.GL_DEPTH_COMPONENT);
                float[] z1 = texel(buf, this.opaqueDepthColorTex, x, y, GL11C.GL_RED);
                float[] c7 = texel(buf, front(7), x, y, GL11C.GL_RGBA);
                char ch;
                if (z0 == null || z0[0] >= 1.0f) {
                    ch = ' ';
                } else if (z1 == null || Math.abs(z0[0] - z1[0]) <= 1e-7f) {
                    ch = '.';
                } else {
                    ch = (c7 != null && c7[3] > 0.5f) ? 'W' : 'S';
                }
                sb.append(ch);
                if (ch == 'W') {
                    // At a WSR-HIT pixel the SSR was computed too, so BOTH candidate contributions exist right here —
                    // no need to find a neighbouring S pixel and hope it is comparable. composite1 picks between exactly
                    // these two (`combinedRef = mix(ssr.rgb, compositeReflection.rgb, compositeReflection.a)` after
                    // `compositeReflection.rgb *= fresnelM`), so if they disagree at the same pixel, the W/S border is a
                    // visible line — and by how much says which side is wrong.
                    float[] c4 = texel(buf, front(4), x, y, GL11C.GL_RGBA);
                    float[] c8 = texel(buf, front(8), x, y, GL11C.GL_RGBA);
                    float[] d13 = wsrColorDebug ? texel(buf, front(13), x, y, GL11C.GL_RGBA) : null;
                    if (c4 != null && c7 != null && c8 != null) {
                        float fres = c4[3] * c4[3]; // composite1 uses pow2(colortex4.a)
                        float wsr = (c7[0] + c7[1] + c7[2]) / 3.0f * fres;
                        float ssr = (c8[0] + c8[1] + c8[2]) / 3.0f;
                        wsrSum += wsr;
                        ssrSum += ssr;
                        nHit++;
                        if (samples.length() < 1600) {
                            samples.append(String.format("     (%4d,%4d) WSR*fres=%.4f vs SSR=%.4f ratio=%.2f"
                                            + " [ssr.a=%.3f]", x, y, wsr, ssr,
                                    ssr > 1e-5f ? wsr / ssr : -1f, c8[3]));
                            if (d13 != null && d13[3] > 0.5f) {
                                samples.append(String.format("  voxelLightmap block=%.3f sky=%.3f lighting=%.3f",
                                        d13[0], d13[1], d13[2]));
                            }
                            samples.append('\n');
                        }
                    }
                }
            }
            sb.append('\n');
        }
        if (nHit > 0) {
            sb.append(String.format("   At WSR-HIT pixels, what composite1 SHOWS vs what it would have shown instead:%n"
                            + "   mean WSR*fresnelM = %.4f | mean SSR = %.4f | ratio = %.2f  (≈1.00 => the seam is"
                            + " invisible; far from 1 => that IS the line)%n",
                    wsrSum / nHit, ssrSum / nHit, ssrSum > 1e-5f ? wsrSum / ssrSum : -1f));
            sb.append(samples);
        }
        SodiumClientMod.logger().info(sb.toString());
    }

    /**
     * DIAGNOSTIC: colortex9 at the crosshair the moment the SOLID terrain pass ends — the other half of the pincer with
     * {@link #diagCrosshair}'s post-composite read. Reports BOTH ping-pong sides: {@code front(9)} is what the terrain
     * wrote into, and if the value turns up in {@code back(9)} instead then something flipped colortex9 in between and
     * the reader is simply looking at the wrong texture.
     */
    public void diagOreRightAfterTerrain() {
        if (this.width <= 0) {
            return;
        }
        // Scan the WHOLE buffer, not the crosshair pixel. Every probe so far read only the centre and reported "not
        // terrain here" — which is the correct answer for a centre that happens to be sky, and is indistinguishable
        // from the write never landing. This cannot be fooled by where the camera points: if terrain wrote colortex9
        // ANYWHERE, `written` is large; if it is 0, gl_FragData[3] reaches nothing and the plumbing is the bug.
        int n = this.width * this.height;
        java.nio.FloatBuffer px = org.lwjgl.BufferUtils.createFloatBuffer(n * 4);
        // First: do the OTHER terrain targets receive anything? "slot 3 is the only broken one" was an assumption I
        // never tested — if colortex4/6 are empty too, the terrain writes gl_FragData[0] alone and the whole picture of
        // which slot works is wrong. Count non-zero texels per slot; slot 0 (colortex0) has the sky in it either way.
        StringBuilder slots = new StringBuilder("[Iris ORE@terrain-end] per-slot non-zero after the SOLID pass:");
        for (int c : new int[]{6, 4, 9}) {
            px.clear();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, front(c));
            GL11C.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11C.GL_RGBA, GL11C.GL_FLOAT, px);
            long nz = 0;
            for (int i = 0; i < n; i++) {
                if (px.get(i * 4) != 0f || px.get(i * 4 + 1) != 0f || px.get(i * 4 + 2) != 0f || px.get(i * 4 + 3) != 0f) {
                    nz++;
                }
            }
            slots.append(String.format("  colortex%d(slot%d)=%d", c, c == 6 ? 1 : c == 4 ? 2 : 3, nz));
        }
        SodiumClientMod.logger().info(slots.toString());

        px.clear();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, front(9));
        GL11C.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11C.GL_RGBA, GL11C.GL_FLOAT, px);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        long written = 0, glowing = 0;
        float matMin = Float.MAX_VALUE, matMax = -Float.MAX_VALUE, emMax = 0;
        int glowFlag = -1;
        // Also grab the single brightest-r texel regardless of alpha — the per-slot count says colortex9 is NOT empty
        // (hundreds of thousands non-zero) yet nothing has alpha>0.5, so SOMETHING writes it and it isn't our vec4 whose
        // .w is a literal 1.0. Show that texel raw so we can see whose data it is.
        float bestR = -1; float[] bestPx = new float[4];
        for (int i = 0; i < n; i++) {
            float m = px.get(i * 4), em = px.get(i * 4 + 1), gf = px.get(i * 4 + 2), a = px.get(i * 4 + 3);
            if (m > bestR) { bestR = m; bestPx[0] = m; bestPx[1] = em; bestPx[2] = gf; bestPx[3] = a; }
            if (a > 0.5f) {
                written++;
                if (m < matMin) matMin = m;
                if (m > matMax) matMax = m;
                if (em > emMax) emMax = em;
                if (glowFlag < 0) glowFlag = gf > 0.5f ? 1 : 0;
                if (em > 0.001f) glowing++;
            }
        }
        SodiumClientMod.logger().info(String.format(
                "[Iris ORE@terrain-end] colortex9 brightest-r texel (any alpha) = (%.3f, %.3f, %.3f, a=%.3f)",
                bestPx[0], bestPx[1], bestPx[2], bestPx[3]));
        SodiumClientMod.logger().info(String.format(
                "[Iris ORE@terrain-end] front(9)=tex%d | terrain wrote %d / %d px%s | mat range %.0f..%.0f"
                        + " | pixels with emission>0: %d (max %.4f) | GLOWING_ORE_IRON=%s",
                front(9), written, n, written == 0 ? "  <== NOTHING: gl_FragData[3] never reaches colortex9" : "",
                written == 0 ? -1 : matMin, written == 0 ? -1 : matMax, glowing, emMax,
                glowFlag < 0 ? "?" : (glowFlag == 1 ? "defined" : "*** NOT DEFINED ***")));
    }

    /**
     * DIAGNOSTIC: whole-buffer brightness of colortex0 (scene, where emissive ore specks live) and colortex3 (the bloom
     * tile atlas composite4 builds and composite5 reads). No aiming — the ore glow is dramatically weaker than 1.20.1
     * and the difference is the bloom halo, so split "the emissive specks aren't bright in colortex0" from "colortex0 is
     * bright but the bloom tiles are empty" (the mipmap the bloom samples not being generated). Also samples colortex0's
     * MIP LEVEL 4 max: if level 0 is bright but level 4 is black, glGenerateMipmap isn't feeding the bloom.
     */
    public void diagBloom() {
        if (this.width <= 0) {
            return;
        }
        int n = this.width * this.height;
        java.nio.FloatBuffer px = org.lwjgl.BufferUtils.createFloatBuffer(n * 4);
        // Count how many colortex0 pixels are actually EMITTING (lum > 2.0 — well above albedo+skylight). This splits the
        // two remaining explanations for the weak ore glow: FEW emitting pixels => the per-pixel emission condition
        // (`color.g-color.b > 0.15`) rarely holds on the 1.12.2 gold-ore texture (pack/texture, not our bug); MANY
        // emitting pixels but a dim final => the composite tonemap/exposure is crushing them (our bug).
        px.clear();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, front(0));
        GL11C.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11C.GL_RGBA, GL11C.GL_FLOAT, px);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        float c0max = 0; long emitting = 0;
        for (int i = 0; i < n; i++) {
            float lum = px.get(i * 4) * 0.3f + px.get(i * 4 + 1) * 0.59f + px.get(i * 4 + 2) * 0.11f;
            if (lum > c0max) c0max = lum;
            if (lum > 2.0f) emitting++;
        }
        float c0mip4 = texMaxLum(px, front(0), 4, this.width * this.height / 256);
        float c3max = texMaxLum(px, front(3), 0, n);
        SodiumClientMod.logger().info(String.format(
                "[Iris BLOOM] colortex0 max=%.3f | EMITTING px (lum>2)=%d (%.3f%%) | MIP4 max=%.3f%s | bloom tiles max=%.3f%s",
                c0max, emitting, 100.0 * emitting / n,
                c0mip4, c0mip4 < 0.01f && c0max > 0.5f ? " <==MIP EMPTY" : "",
                c3max, c3max < 0.02f && c0max > 0.5f ? " <==TILES EMPTY" : ""));
    }

    private final java.util.Map<Integer, java.nio.FloatBuffer> diagPrevFrames = new java.util.HashMap<>();

    /**
     * DIAGNOSTIC: the largest per-pixel change in colortexN since the last call FOR THAT INDEX, and WHERE it is. A
     * whole-screen total hides a local flicker (one bright area alternating while the total stays flat); this catches
     * it. Keyed prev-buffers per index, so several colortex can be tracked in the same frame to find where the flicker
     * first appears (colortex0 = pre-composite lit scene vs colortex3 = final).
     */
    public void diagTemporalDelta(int idx, int frame) {
        if (this.width <= 0) {
            return;
        }
        int n = this.width * this.height;
        java.nio.FloatBuffer cur = org.lwjgl.BufferUtils.createFloatBuffer(n * 4);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, front(idx));
        GL11C.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11C.GL_RGBA, GL11C.GL_FLOAT, cur);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        java.nio.FloatBuffer prev = this.diagPrevFrames.get(idx);
        if (prev != null) {
            float maxD = 0; int maxAt = -1; long changed = 0;
            for (int i = 0; i < n; i++) {
                float d = Math.abs(cur.get(i * 4) - prev.get(i * 4))
                        + Math.abs(cur.get(i * 4 + 1) - prev.get(i * 4 + 1))
                        + Math.abs(cur.get(i * 4 + 2) - prev.get(i * 4 + 2));
                if (d > maxD) { maxD = d; maxAt = i; }
                if (d > 0.1f) changed++;
            }
            int mx = maxAt < 0 ? -1 : maxAt % this.width, my = maxAt < 0 ? -1 : maxAt / this.width;
            SodiumClientMod.logger().info(String.format(
                    "[Iris FLICKER] frame=%d colortex%d: max change=%.3f at (%d,%d) | changed >0.1 = %d (%.3f%%)",
                    frame, idx, maxD, mx, my, changed, 100.0 * changed / n));
        }
        this.diagPrevFrames.put(idx, cur);
    }

    /** Total luminance of colortexN (front), for per-frame logging to catch a temporal (SSBL/TAA) flicker. */
    public double diagColortexTotal(int idx) {
        if (this.width <= 0) {
            return -1;
        }
        int n = this.width * this.height;
        java.nio.FloatBuffer px = org.lwjgl.BufferUtils.createFloatBuffer(n * 4);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, front(idx));
        GL11C.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11C.GL_RGBA, GL11C.GL_FLOAT, px);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += px.get(i * 4) + px.get(i * 4 + 1) + px.get(i * 4 + 2);
        }
        return sum;
    }

    /** Max + mean luminance of the final displayed buffer, plus how many pixels are near-saturated (bright glow). */
    public void diagFinal(int idx) {
        if (this.width <= 0) {
            return;
        }
        int n = this.width * this.height;
        java.nio.FloatBuffer px = org.lwjgl.BufferUtils.createFloatBuffer(n * 4);
        px.clear();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, front(idx));
        GL11C.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11C.GL_RGBA, GL11C.GL_FLOAT, px);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        float max = 0; double sum = 0; long bright = 0;
        for (int i = 0; i < n; i++) {
            float lum = px.get(i * 4) * 0.3f + px.get(i * 4 + 1) * 0.59f + px.get(i * 4 + 2) * 0.11f;
            if (lum > max) max = lum;
            sum += lum;
            if (lum > 0.7f) bright++;
        }
        SodiumClientMod.logger().info(String.format(
                "[Iris FINAL] colortex%d (displayed) max lum = %.3f | mean = %.3f | pixels > 0.7 (glowy) = %d / %d (%.2f%%)",
                idx, max, sum / n, bright, n, 100.0 * bright / n));
    }

    private static float texMaxLum(java.nio.FloatBuffer px, int tex, int level, int cap) {
        if (tex == 0) {
            return -1f;
        }
        px.clear();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11C.glGetTexImage(GL11.GL_TEXTURE_2D, level, GL11C.GL_RGBA, GL11C.GL_FLOAT, px);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        float max = 0;
        int lim = Math.min(cap, px.capacity() / 4);
        for (int i = 0; i < lim; i++) {
            float lum = px.get(i * 4) * 0.3f + px.get(i * 4 + 1) * 0.59f + px.get(i * 4 + 2) * 0.11f;
            if (lum > max) max = lum;
        }
        return max;
    }

    // --- centerDepthSmooth async readback (double-buffered pixel-pack buffers, 1-frame latency) --------------------
    private final int[] centerDepthPbos = new int[2];
    private final boolean[] centerDepthQueued = new boolean[2];
    private int centerDepthWriteIdx;
    private final java.nio.FloatBuffer centerDepthScratch = org.lwjgl.BufferUtils.createFloatBuffer(1);

    /** Centre depth of depthtex0, read back ASYNCHRONOUSLY: queues a GPU→PBO copy of this frame's centre texel and
     * returns the value queued on the PREVIOUS call (1 frame old — the caller's temporal smoothing makes the latency
     * unobservable). The synchronous DSA path ({@link #readDepthTexel}) stalls the CPU until the GPU drains the whole
     * world render — measurable FPS loss when called per frame (2026-07-31, DOF auto-focus) — while this never blocks:
     * by the time a PBO is read, its transfer finished a frame ago. Returns -1 until the first result lands. */
    public float readCenterDepthAsync() {
        if (this.depthTex == 0 || this.width <= 0) {
            return -1.0F;
        }
        if (this.centerDepthPbos[0] == 0) {
            for (int i = 0; i < 2; i++) {
                this.centerDepthPbos[i] = GL15C.glGenBuffers();
                GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, this.centerDepthPbos[i]);
                GL15C.glBufferData(GL21C.GL_PIXEL_PACK_BUFFER, 4, GL15C.GL_STREAM_READ);
                this.centerDepthQueued[i] = false;
            }
        }
        int write = this.centerDepthWriteIdx;
        int read = write ^ 1;
        // Queue this frame's centre texel into the write PBO — with a PACK buffer bound the pixels argument is a
        // byte offset, and the copy runs GL-server-side without a client sync.
        GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, this.centerDepthPbos[write]);
        GL45C.nglGetTextureSubImage(this.depthTex, 0, this.width / 2, this.height / 2, 0, 1, 1, 1,
                GL11C.GL_DEPTH_COMPONENT, GL11C.GL_FLOAT, 4, 0L);
        float result = -1.0F;
        if (this.centerDepthQueued[read]) {
            GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, this.centerDepthPbos[read]);
            this.centerDepthScratch.clear();
            GL15C.glGetBufferSubData(GL21C.GL_PIXEL_PACK_BUFFER, 0, this.centerDepthScratch);
            result = this.centerDepthScratch.get(0);
        }
        GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, 0);
        this.centerDepthQueued[write] = true;
        this.centerDepthWriteIdx = read;
        return result;
    }

    /** DIAGNOSTIC: the scene depth (depthtex0) at texel (x, y) — e.g. to check whether the HAND's depth landed there. */
    public float readDepthTexel(int x, int y) {
        if (this.depthTex == 0 || this.width <= 0) {
            return -1.0F;
        }
        java.nio.FloatBuffer buf = org.lwjgl.BufferUtils.createFloatBuffer(4);
        float[] r = texel(buf, this.depthTex,
                Math.max(0, Math.min(this.width - 1, x)), Math.max(0, Math.min(this.height - 1, y)),
                GL11C.GL_DEPTH_COMPONENT);
        return r == null ? -1.0F : r[0];
    }

    /** DIAGNOSTIC: one RGBA texel of a SPECIFIC ping-pong side of colortexN (side 0 = A, 1 = B), bypassing the flip
     * bookkeeping entirely — to catch a write and a read disagreeing about which side is "front". */
    public float[] readColortexTexelSide(int idx, int side, int x, int y) {
        java.nio.FloatBuffer buf = org.lwjgl.BufferUtils.createFloatBuffer(4);
        int tex = side == 0 ? this.colortexA[idx] : this.colortexB[idx];
        float[] r = texel(buf, tex,
                Math.max(0, Math.min(this.width - 1, x)), Math.max(0, Math.min(this.height - 1, y)), GL11C.GL_RGBA);
        return r != null ? r : new float[]{-1, -1, -1, -1};
    }

    /** @return which side is front for colortexN right now (0 = A, 1 = B) — pairs with readColortexTexelSide. */
    public int frontSide(int idx) {
        return this.flip[idx] ? 1 : 0;
    }

    /** DIAGNOSTIC: a w×h RGBA region of colortexN's FRONT in one sub-image download (row-major, x fastest),
     * or null when the target is absent. Caller clamps the rectangle into range. */
    public java.nio.FloatBuffer readColortexRegion(int idx, int x, int y, int w, int h) {
        int tex = front(idx);
        if (tex == 0 || w <= 0 || h <= 0) {
            return null;
        }
        java.nio.FloatBuffer buf = org.lwjgl.BufferUtils.createFloatBuffer(w * h * 4);
        GL45C.glGetTextureSubImage(tex, 0, x, y, 0, w, h, 1, GL11C.GL_RGBA, GL11C.GL_FLOAT, buf);
        return buf;
    }

    /** DIAGNOSTIC: one RGBA texel of colortexN's FRONT (the side the next pass reads), clamped into range. */
    public float[] readColortexTexel(int idx, int x, int y) {
        java.nio.FloatBuffer buf = org.lwjgl.BufferUtils.createFloatBuffer(4);
        float[] r = texel(buf, front(idx),
                Math.max(0, Math.min(this.width - 1, x)), Math.max(0, Math.min(this.height - 1, y)), GL11C.GL_RGBA);
        return r != null ? r : new float[]{-1, -1, -1, -1};
    }

    /** Reads a single texel with glGetTextureSubImage (GL 4.5) — no whole-texture download. */
    private static float[] texel(java.nio.FloatBuffer buf, int tex, int x, int y, int format) {
        if (tex == 0) {
            return null;
        }
        buf.clear();
        GL45C.glGetTextureSubImage(tex, 0, x, y, 0, 1, 1, 1, format, GL11C.GL_FLOAT, buf);
        return new float[]{buf.get(0), buf.limit() > 1 ? buf.get(1) : 0f,
                buf.limit() > 2 ? buf.get(2) : 0f, buf.limit() > 3 ? buf.get(3) : 0f};
    }

    /**
     * DIAGNOSTIC: reads back the three links of the depthtex1 chain and logs their ACTUAL values at a few pixels, so the
     * break can be located instead of inferred from a colour-coded screen.
     *
     * <p>Chain: {@code depthTex} (live scene depth, the FBO attachment) → {@code waterDepthTex} (glCopyImageSubData
     * snapshot) → {@code opaqueDepthColorTex} (R32F, written by the DEPTH_COPY_FSH pass) = the composite's depthtex1.
     * A sky pixel must read 1.0 at every link; the pack's SSR gate is {@code refPos.z < 1.0} on the last one, so
     * anything below 1.0 there makes every sky-bound reflection ray count as a geometry hit.</p>
     */
    public void diagDepthChain() {
        if (this.depthTex == 0 || this.width <= 0) {
            return;
        }
        int n = this.width * this.height;
        java.nio.FloatBuffer buf = org.lwjgl.BufferUtils.createFloatBuffer(n);
        // Sample points (GL texture space: y=0 is the BOTTOM of the screen).
        int cx = this.width / 2;
        int[] px = {cx, cx, cx};
        int[] py = {(int) (this.height * 0.92f), this.height / 2, (int) (this.height * 0.08f)};
        String[] label = {"top(sky?)", "centre", "bottom(ground?)"};

        float[][] vals = new float[3][3];
        float[] mins = new float[3];
        float[] maxs = new float[3];
        int[] texes = {this.depthTex, this.waterDepthTex, this.opaqueDepthColorTex};
        String[] names = {"depthTex(live)", "waterDepthTex(copy)", "opaqueDepthColorTex(=depthtex1)"};
        for (int t = 0; t < 3; t++) {
            if (texes[t] == 0) {
                continue;
            }
            buf.clear();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texes[t]);
            // R32F is a colour texture (read GL_RED); the other two are true depth textures (read GL_DEPTH_COMPONENT).
            int fmt = (t == 2) ? GL11C.GL_RED : GL11C.GL_DEPTH_COMPONENT;
            GL11C.glGetTexImage(GL11.GL_TEXTURE_2D, 0, fmt, GL11C.GL_FLOAT, buf);
            float mn = Float.MAX_VALUE, mx = -Float.MAX_VALUE;
            for (int i = 0; i < n; i += 97) { // stride-sample for min/max (full scan is needlessly slow)
                float v = buf.get(i);
                if (v < mn) mn = v;
                if (v > mx) mx = v;
            }
            mins[t] = mn;
            maxs[t] = mx;
            for (int p = 0; p < 3; p++) {
                vals[t][p] = buf.get(py[p] * this.width + px[p]);
            }
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        StringBuilder sb = new StringBuilder("[Iris depthChain DIAG] " + this.width + "x" + this.height + "\n");
        for (int t = 0; t < 3; t++) {
            sb.append(String.format("  %-32s min=%.6f max=%.6f | ", names[t], mins[t], maxs[t]));
            for (int p = 0; p < 3; p++) {
                sb.append(String.format("%s=%.6f  ", label[p], vals[t][p]));
            }
            sb.append('\n');
        }
        sb.append("  EXPECT: a sky pixel = 1.000000 at every link. depthtex1 < 1.0 on sky => the pack's SSR accepts a"
                + " hit there and samples colortex0 (the screen) into the reflection.\n");

        // z0 vs z1: the pack's ONLY test for "this pixel is translucent" (reflections.glsl:84 `if (z0 != z1)`), which is
        // what routes a water pixel to getWSR instead of the screen-space fallback that cuts off at the screen edge.
        // Water writing no depth into depthtex0, or depthtex1 accidentally containing the water surface, both collapse
        // z0 == z1 → the pack never sees water → SSR only → the reflection cuts off at the frame edge.
        java.nio.FloatBuffer z0buf = org.lwjgl.BufferUtils.createFloatBuffer(n);
        java.nio.FloatBuffer z1buf = org.lwjgl.BufferUtils.createFloatBuffer(n);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.depthTex);
        GL11C.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11C.GL_DEPTH_COMPONENT, GL11C.GL_FLOAT, z0buf);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.opaqueDepthColorTex);
        GL11C.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11C.GL_RED, GL11C.GL_FLOAT, z1buf);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        long differ = 0, sky = 0;
        float maxGap = 0.0f;
        for (int i = 0; i < n; i++) {
            float z0 = z0buf.get(i);
            float z1 = z1buf.get(i);
            if (z0 >= 1.0f) {
                sky++;
            } else if (z0 != z1) {
                differ++;
                maxGap = Math.max(maxGap, Math.abs(z1 - z0));
            }
        }
        long world = n - sky;
        sb.append(String.format("  z0 != z1 (the pack's \"is translucent\" test): %d px = %.2f%% of the non-sky image"
                        + " (maxGap=%.6f); sky=%.1f%%\n", differ, 100.0 * differ / Math.max(1, world), maxGap,
                100.0 * sky / Math.max(1, n)));
        sb.append("  EXPECT: roughly the fraction of the screen covered by water/glass. ~0% while water is visible =>"
                + " the pack never routes water to getWSR, so its reflection is screen-space only and cuts off at the edge.");
        SodiumClientMod.logger().info(sb.toString());
    }

    public void bindWaterSceneTextures() {
        ensureNoise();
        ensureWaterNormal();
        for (int i = 0; i < NUM_COLORTEX; i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + WATER_COLORTEX_UNIT0 + i);
            int tex;
            if (i == 0) {
                // colortex0's unit gets the pre-pass scene COPY (the water writes colortex0, so reading the live one is a
                // feedback loop → dark/self-reflecting water).
                tex = this.waterReflectTex;
            } else if (i == 7 && this.waterNormalTex != 0) {
                // gaux4 == colortex7 == the pack's custom water NORMAL MAP (cloud-water.png), not a scene buffer. Reading a
                // scene buffer here gave a garbage water normal → curved reflection.
                tex = this.waterNormalTex;
            } else if (i == 5 && this.waterRefTex != 0) {
                // gaux2 == colortex5 == deferred1's waterRefColor (reflected scene colour). Our live colortex5 is last
                // frame's, already zeroed by composite1/5 → black terrain reflection. Use the post-deferred1 snapshot.
                tex = this.waterRefTex;
            } else {
                tex = front(i);
            }
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + WATER_DEPTHTEX_UNIT);
        // The OPAQUE-depth COPY (not the live depthTex, which is the water FBO's depth attachment — sampling that while the
        // water writes depth is a feedback loop). copyDepthForWater() must run just before the water draws.
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.waterDepthTex);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + WATER_NOISETEX_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.noiseTex);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    /** Binds the three SCENE buffers the OPAQUE gbuffers_terrain fragment samples (colortex10 for coloured blocklight,
     * colortex18 for the Voxy SS-shadow, shadowcolor0 for stained-glass shadow) onto their dedicated high units, so those
     * samplers stop defaulting to unit 0 = the block atlas. colortex10 is also a terrain draw-buffer attachment but the
     * transformed fragment never writes that slot (it emits only fragColor + iris_FragNormal), so this is the same
     * read-the-reprojected-buffer pattern real Iris runs in gbuffers_terrain — not a live feedback loop. Resets the
     * active unit to 0 so the follow-on atlas sampling on unit 0 is unaffected (GlStateManager's cache still thinks 0 is
     * active). */
    /** Binds the opaque-only shadow depth ({@code shadowtex1}) on {@link #TERRAIN_SHADOWTEX1_UNIT} for the gbuffers
     * programs. Compare mode is permanently ON on this texture, matching their {@code shadow2D} reads. */
    public void bindOpaqueShadowForGbuffers() {
        ensureShadow();
        if (this.shadowDepthTexOpaque == 0) {
            return;
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + TERRAIN_SHADOWTEX1_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.shadowDepthTexOpaque);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    public void bindTerrainSceneTextures() {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + TERRAIN_COLORTEX10_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, front(10));
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + TERRAIN_COLORTEX18_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, front(18));
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + TERRAIN_SHADOWCOLOR0_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.shadowColorTex);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + TERRAIN_GAUX2_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, front(5));
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    public void enableMipmapRead(int i) {
        if (!MIPMAPPED[i]) {
            return;
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + COLORTEX_UNIT0 + i);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, front(i));
        GL30C.glGenerateMipmap(GL11.GL_TEXTURE_2D);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    /** Restores colortexN (front)'s min filter to its BASE filter after a mipmapped pass, so subsequent plain
     * {@code texture2D} reads (and the final display) sample level 0. The base is {@code GL_LINEAR} for a LINEAR-flagged
     * buffer (e.g. colortex3, whose bloom tiles must stay bilinear for the next frame's upsample) else {@code GL_NEAREST}. */
    public void disableMipmapRead(int i) {
        if (!MIPMAPPED[i]) {
            return;
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + COLORTEX_UNIT0 + i);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, front(i));
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, LINEAR[i] ? GL11.GL_LINEAR : GL11.GL_NEAREST);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    private static int newTexture(int w, int h, int internalFormat, int format, int type) {
        int tex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internalFormat, w, h, 0, format, type, 0L);
        return tex;
    }

    /**
     * Binds the world FBO for the world pass: colortex0 (front) the only draw buffer; the buffers Complementary marks
     * {@code colortexNClear} are cleared (colortex0 to the fog colour, the rest to 0). Depth cleared. Non-terrain geometry
     * writes colortex0; the terrain pass switches to the full RENDERTARGETS via {@link #beginTerrainTargets()}.
     */
    public void beginWorldPass(float clearR, float clearG, float clearB) {
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, this.fbo);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_STENCIL_ATTACHMENT,
                GL11.GL_TEXTURE_2D, this.depthTex, 0);
        GlStateManager.viewport(0, 0, this.width, this.height);

        // Clear each colortexNClear buffer (its front side) to 0 with glClearTexImage — a state-INDEPENDENT clear that
        // ignores scissor/colorMask/drawBuffers. The old FBO-attach + glClear(COLOR) path went through GlStateManager,
        // so a stale scissor box or disabled colour mask left over from MC's render could silently skip it → the temporal
        // buffer kept LAST frame's contents. That's fatal for colortex6 (the TAA materialMask): stale terrain material IDs
        // on now-sky/water pixels push DoTAA into its skip path (mask 254 / ~149) → those pixels lose temporal resolve and
        // the cloud/water dither flickers. glClearTexImage(..., null) writes 0 to the whole level unconditionally.
        for (int i = 0; i < NUM_COLORTEX; i++) {
            if (CLEAR[i] && i != 0) {
                GL44C.glClearTexImage(front(i), 0, GL11.GL_RGBA, GL11.GL_FLOAT, (float[]) null);
            }
        }
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, front(0), 0);
        // Start the frame from a single-attachment FBO: drop whatever slots the last frame's final pass left attached.
        for (int i = 1; i < this.colorAttachHigh; i++) {
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0 + i, GL11.GL_TEXTURE_2D, 0, 0);
        }
        this.colorAttachHigh = 1;
        GL20C.glDrawBuffers(new int[]{GL30C.GL_COLOR_ATTACHMENT0});
        GlStateManager.clearColor(clearR, clearG, clearB, 1.0F);
        // Stencil cleared with depth: the scene depth is packed depth24+stencil8 now (stencil-based TESRs — see
        // newSceneDepthTexture), and last frame's mirror/hole masks must not leak into this frame's tests.
        GlStateManager.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);
    }

    /**
     * Attaches the terrain G-buffer targets ({@link #TERRAIN_TARGETS}, front sides) to colour attachments 0..N, so the
     * transformed pack {@code gbuffers_terrain}'s {@code gl_FragData[i]} lands in {@code colortex[TERRAIN_TARGETS[i]]}.
     */
    public void beginTerrainTargets() {
        beginTargets(TERRAIN_TARGETS);
    }

    /** Attaches the terrain targets the PACK actually declares (resolved from its active branch), falling back to the
     * hardcoded set only if parsing failed. See IrisPipeline#packTerrainTargets for why the hardcoded list is unsafe. */
    public void beginTerrainTargets(int[] resolved) {
        beginTargets(resolved != null && resolved.length > 0 ? resolved : TERRAIN_TARGETS);
    }

    /** Attaches an arbitrary G-buffer target set (front sides) to colour attachments 0..N so a program's {@code
     * gl_FragData[i]} lands in {@code colortex[indices[i]]} — used for the terrain set and the water set (translucent pass). */
    public void beginTargets(int[] indices) {
        beginTargets(indices, null);
    }

    /**
     * As {@link #beginTargets(int[])}, plus the pack's {@code blend.<program>.colortexN = off} opt-outs.
     *
     * <p><b>GL blending applies to EVERY attached draw buffer, not just colour.</b> The translucent (water) pass draws
     * with {@code SRC_ALPHA, ONE_MINUS_SRC_ALPHA}, so without this the water's G-buffer writes are alpha-blended into
     * whatever the terrain left underneath: gbuffers_water's {@code gl_FragData[2] = vec4(1.0, materialMask, ...)} came
     * out as a MIX of the water's material and the seabed's. Measured at the crosshair: smoothnessD 0.5961 (should be
     * exactly 1.0) and materialMask 144 (should be 0) — varying per pixel, with a constant .g/.r ratio, which is the
     * signature of one blend factor applied to both. 144 lands inside deferredIPBR.glsl's ENTITY range
     * ({@code abs(materialMaskInt - 149.5) < 50} → 100..199), so the pack decided the water was an entity, and
     * composite.glsl:129 {@code if (!opaqueSurface) reflectColor = vec3(0.0)} + :152
     * {@code reflection.rgb *= reflectColor} blacked out the reflection's rgb while leaving its alpha at 1 — which
     * composite1 then blends in as {@code mix(ssr, black, 1.0)}, deleting the reflection entirely. That is the boundary.
     *
     * <p>Per-draw-buffer enables ({@code glDisablei}) are GL 3.0. Blending is only ever turned OFF here, never on:
     * {@code glEnablei(GL_BLEND, i)} would force blending on for a buffer whose pass wants none (GL has no separate
     * "global" enable — {@code glEnable(GL_BLEND)} just sets every buffer's bit), so the callers' own blend state stays
     * authoritative and {@link #endBlendOverrides} restores the bits we cleared.
     */
    public void beginTargets(int[] indices, java.util.Set<Integer> blendOff) {
        int[] draw = new int[indices.length];
        for (int i = 0; i < indices.length; i++) {
            int attach = GL30C.GL_COLOR_ATTACHMENT0 + i;
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, attach, GL11.GL_TEXTURE_2D, front(indices[i]), 0);
            draw[i] = attach;
        }
        // Detach any attachments beyond this set left over from a previous (wider) set, so no stale buffer is written —
        // up to the tracked high-water mark, not a fixed constant (see colorAttachHigh).
        for (int i = indices.length; i < this.colorAttachHigh; i++) {
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0 + i, GL11.GL_TEXTURE_2D, 0, 0);
        }
        this.colorAttachHigh = indices.length;
        GL20C.glDrawBuffers(draw);

        // Always resynchronise FIRST: per-slot GL_BLEND bits are global GL state that survives any FBO/target change, so
        // a slot an EARLIER pass disabled would otherwise stay disabled forever once a later pass's off-set no longer
        // mentions it. (It did: tracking only the current call's mask leaked slots 3 and 4 from the water pass through
        // every later draw, and MC's own GUI/HUD rendering inherited them.)
        resyncBlendSlots();
        if (blendOff != null && !blendOff.isEmpty()) {
            int mask = 0;
            for (int i = 0; i < indices.length; i++) {
                if (blendOff.contains(indices[i])) {
                    GL30C.glDisablei(GL11.GL_BLEND, i);
                    mask |= 1 << i;
                }
            }
            // The offs must SURVIVE the host's own blend toggles: a non-indexed glEnable(GL_BLEND) — which MC issues
            // mid-pass for the held item, entity layers etc. — sets EVERY slot's bit, silently re-enabling blending on
            // the material slots. The hand's colortex6 write then blends with srcA = purkinjeData ≈ 0 in darkness, so
            // the WATER's materialMask (241) underneath survived and the composite ran its water path on the hand
            // (ripple + brightness). MixinGlStateManagerIris re-applies this mask after every enableBlend.
            activeBlendOffSlots = mask;
        }
    }

    /** Slots whose GL_BLEND must stay OFF for the duration of the current G-buffer pass (bitmask). Re-applied by the
     * GlStateManager.enableBlend hook, because the non-indexed enable wipes every per-slot bit. */
    private static int activeBlendOffSlots;

    /** Re-disables the pass's blend-off slots after the host re-enabled blending globally. Called from the
     * GlStateManager.enableBlend mixin; no-op (and cheap) outside a pass with overrides. */
    public static void reassertBlendOffs() {
        int mask = activeBlendOffSlots;
        if (mask == 0) {
            return;
        }
        for (int i = 0; i < 8; i++) {
            if ((mask & (1 << i)) != 0) {
                GL30C.glDisablei(GL11.GL_BLEND, i);
            }
        }
    }

    /**
     * Puts every draw slot's GL_BLEND bit back to whatever slot 0 says, i.e. the state the caller (Sodium / MC /
     * GlStateManager) believes is in force.
     *
     * <p>Slot 0 is the authority precisely because it is the one nobody overrides — {@code glEnable/glDisable(GL_BLEND)}
     * and {@code glIsEnabled(GL_BLEND)} are all defined as operating on slot 0, and GlStateManager only ever issues the
     * non-indexed calls. Reading it back and mirroring it is therefore an exact restore, and it cannot force blending ON
     * for a pass that wants none — which a blind {@code glEnablei} would (GL has no separate "global" enable; the
     * non-indexed call just writes every slot's bit).</p>
     */
    private void resyncBlendSlots() {
        activeBlendOffSlots = 0; // the overrides end with the pass; the enableBlend hook goes back to no-op
        boolean on = GL11.glIsEnabled(GL11.GL_BLEND);
        for (int i = 0; i < 8; i++) {
            if (on) {
                GL30C.glEnablei(GL11.GL_BLEND, i);
            } else {
                GL30C.glDisablei(GL11.GL_BLEND, i);
            }
        }
    }

    /** Drops every per-slot GL_BLEND override, restoring the caller's uniform blend state. Call when a pass ends. */
    public void endBlendOverrides() {
        resyncBlendSlots();
    }

    /**
     * DIAGNOSTIC: asks GL which texture each colour attachment REALLY holds right now, and whether the FBO is complete.
     *
     * <p>The "[chain] X RENDERTARGETS=[...]" lines only report what our PARSER decided; they say nothing about what
     * {@code glFramebufferTexture2D} actually bound. Those two have already disagreed once today — our GlslConditionals
     * resolves SS_BLOCKLIGHT differently from the driver, so the port attaches gbuffers_terrain's {@code 0649} variant
     * (4 targets) while the driver compiles the {@code 064} one (3). Print the truth.</p>
     */
    public void diagAttachments(String label, int[] expected) {
        StringBuilder sb = new StringBuilder("[Iris FBO] " + label + " expected=" + java.util.Arrays.toString(expected)
                + " status=0x" + Integer.toHexString(GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER)) + "\n");
        for (int i = 0; i < 6; i++) {
            int tex = GL30C.glGetFramebufferAttachmentParameteri(GL30C.GL_FRAMEBUFFER,
                    GL30C.GL_COLOR_ATTACHMENT0 + i, GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            String which = "(none)";
            for (int c = 0; c < NUM_COLORTEX; c++) {
                if (tex != 0 && (this.colortexA[c] == tex || this.colortexB[c] == tex)) {
                    which = "colortex" + c + (this.colortexA[c] == tex ? "A" : "B");
                    break;
                }
            }
            sb.append(String.format("   slot %d -> tex %d  %s%s%n", i, tex, which,
                    i < expected.length ? "   (expected colortex" + expected[i] + " = tex " + front(expected[i]) + ")" : ""));
        }
        SodiumClientMod.logger().info(sb.toString());
    }

    /** Re-attaches the main scene depth texture ({@code depthTex}) as the current FBO's depth attachment. The water pass
     * swaps the depth attachment for its feedback-safe copy; the first-person hand (drawn after water) must write its depth
     * back into {@code depthTex} — the very buffer the composite samples as {@code depthtex0} — or the composite reads the
     * background depth behind the hand and blends sky/clouds/fog through it (a see-through hand). */
    public void attachSceneDepth() {
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_STENCIL_ATTACHMENT, GL11.GL_TEXTURE_2D, this.depthTex, 0);
    }

    /** Detaches the extra terrain targets and returns to colortex0-only drawing (sky/entities/particles). */
    public void endTerrainTargets() {
        // Per-draw-buffer GL_BLEND bits are global GL state, not FBO state — leaving one cleared would silently disable
        // blending for whatever draws into that slot next.
        endBlendOverrides();
        for (int i = 1; i < this.colorAttachHigh; i++) {
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0 + i,
                    GL11.GL_TEXTURE_2D, 0, 0);
        }
        this.colorAttachHigh = 1;
        GL20C.glDrawBuffers(new int[]{GL30C.GL_COLOR_ATTACHMENT0});
    }

    // --- deferred/composite chain ----------------------------------------------------------------------------------

    /** Binds the read textures for a composite pass: shadowtex0→unit0, depthtex0→unit1, colortex0..20 (front)→units 2..22.
     * Depth textures must stay on units 0/1 (compat quirk: they sample black elsewhere); colour buffers sample anywhere. */
    public void bindCompositeReadTextures() {
        for (int i = 0; i < NUM_COLORTEX; i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + COLORTEX_UNIT0 + i);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, front(i));
            GL11.glEnable(GL11.GL_TEXTURE_2D);
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + DEPTHTEX_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.depthTex);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + SHADOWTEX_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.shadowDepthTex);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        // noisetex (clouds / sun-glare / dithering) on its own unit past the colortex bank.
        ensureNoise();
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + NOISETEX_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.noiseTex);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        // Opaque depth (R32F colour) → the composite's depthtex1, so the pack detects water (z0 != z1) → getWSR fires.
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + OPAQUE_DEPTH_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.opaqueDepthColorTex);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        // The shadow colour buffers (stained-glass tint + the light-shaft caster height in shadowcolor1.a).
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + SHADOWCOLOR0_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.shadowColorTex);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + SHADOWCOLOR1_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.shadowColorTex1);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        // NOTE: shadowtex1 (the opaque-only depth) is NOT bound here. Its first home, SHADOWTEX1_UNIT = 27, collided
        // with the custom-images sampler range — customImages.bindTo() re-bound unit 27 to endcrystal_sampler right
        // after this method ran, so composite1's shadow2D(shadowtex1) read the end-crystal IMAGE through a depth-compare
        // sampler (undefined → 0) and the underwater god rays never fired. It now lives on TERRAIN_SHADOWTEX1_UNIT (47),
        // bound once per frame in bindOpaqueShadowForGbuffers, shared by gbuffers and composites alike.
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    /**
     * Snapshots the shadow depth map into {@link #shadowDepthTexOpaque} ({@code shadowtex1}). Called in the shadow pass
     * AFTER the opaque casters and BEFORE the translucent (water) ones, so shadowtex1 holds opaque-only depth while
     * shadowtex0 goes on to include the water surface. The pack's "a translucent blocked this light" test is exactly
     * that difference — without it, coloured shadows and the underwater god rays can never fire.
     */
    public void snapshotOpaqueShadowDepth() {
        ensureShadow();
        if (this.shadowDepthTex == 0 || this.shadowDepthTexOpaque == 0 || this.shadowOpaqueFbo == 0 || this.shadowFbo == 0) {
            return;
        }
        // Depth must be copied with a FRAMEBUFFER BLIT. Two earlier attempts were both invalid and cost a full regression:
        // glCopyImageSubData raised GL_INVALID_OPERATION every frame (its source was the depth attachment of the
        // currently-bound FBO), and glCopyTexSubImage2D only ever reads a COLOUR buffer, so filling a DEPTH_COMPONENT
        // texture with it is illegal too — and it also had to touch texture unit 0 mid-pass, which is its own hazard here.
        // Either failure leaves shadowtex1 at its uninitialised depth 0, which under GL_LEQUAL reads as "everything
        // shadowed": the pack's `shadow1 > 0.9999` never passes, shadowcol stays black, and the whole world goes black.
        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, this.shadowFbo);
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, this.shadowOpaqueFbo);
        GL30C.glBlitFramebuffer(0, 0, this.shadowSize, this.shadowSize,
                0, 0, this.shadowSize, this.shadowSize,
                GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        // Put the shadow FBO back as the active read+draw target — the pass still has translucent casters to draw.
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, this.shadowFbo);
    }

    /**
     * Disables the EXTRA colour sampler units (2..) enabled by {@link #bindCompositeReadTextures}, and leaves unit 0 with
     * {@code GL_TEXTURE_2D} ENABLED with the active unit back at 0.
     *
     * <p>Critically it must NOT leave unit 0's texturing disabled: MC blits the main framebuffer to the screen with a
     * fixed-function TEXTURED fullscreen quad on unit 0, so if unit-0 texturing is off that blit draws the flat white
     * clear colour instead of the frame — which is exactly why the whole screen went white after the composite chain.</p>
     */
    public void unbindCompositeReadTextures() {
        for (int i = 0; i < NUM_COLORTEX; i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + COLORTEX_UNIT0 + i);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + NOISETEX_UNIT);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + OPAQUE_DEPTH_UNIT);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + SHADOWCOLOR0_UNIT);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + SHADOWCOLOR1_UNIT);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        // Leave units 0/1 (shadowtex/depthtex) texturing enabled — MC manages+needs unit-0 texturing for its FB blit.
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    /** Attaches a composite pass's RENDERTARGETS (back sides) to attachments 0..N; depth detached (composites don't write
     * it, and it's being sampled as depthtex0). {@code gl_FragData[i]} → {@code colortex[indices[i]]}. */
    public void bindCompositeTargets(int[] indices) {
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, this.fbo);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_STENCIL_ATTACHMENT, GL11.GL_TEXTURE_2D, 0, 0);
        if (indices.length == 0) {
            // SIDE-EFFECT-ONLY pass (prepare: its work is image load/store in the vertex; the fragment discards).
            // Keep ONE colour attachment so the FBO stays complete, but select no draw buffers at all — nothing may
            // touch the colortex bank, and the caller's flipTargets(empty) correctly flips nothing.
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, front(0), 0);
            for (int i = 1; i < this.colorAttachHigh; i++) {
                GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0 + i, GL11.GL_TEXTURE_2D, 0, 0);
            }
            this.colorAttachHigh = 1;
            GL20C.glDrawBuffers(new int[]{GL11.GL_NONE});
            GlStateManager.viewport(0, 0, this.width, this.height);
            return;
        }
        int[] draw = new int[indices.length];
        for (int i = 0; i < indices.length; i++) {
            int attach = GL30C.GL_COLOR_ATTACHMENT0 + i;
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, attach, GL11.GL_TEXTURE_2D, back(indices[i]), 0);
            draw[i] = attach;
        }
        // Detach any attachments beyond this pass's count left over from a previous (wider) pass — up to the tracked
        // high-water mark, not a fixed constant (see colorAttachHigh).
        for (int i = indices.length; i < this.colorAttachHigh; i++) {
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0 + i, GL11.GL_TEXTURE_2D, 0, 0);
        }
        this.colorAttachHigh = indices.length;
        GL20C.glDrawBuffers(draw);
        GlStateManager.viewport(0, 0, this.width, this.height);
    }

    /**
     * Copies colortexN (its current front) straight to a destination framebuffer with {@code glBlitFramebuffer} — a GPU
     * copy that bypasses shaders / fullscreen-quad / GL texture-unit state entirely (the composite chain leaves that state
     * in a way that keeps a fullscreen-quad draw from reaching the main framebuffer). Used to show the composited result.
     */
    public void blitColortexToScreen(int idx, int destFbo, int destW, int destH) {
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, this.fbo);
        // Leave ONLY colortexN on attachment 0 (detach depth + the extra colour attachments a composite pass left behind),
        // so the read framebuffer is single-attachment + complete for the blit — a stray/mismatched attachment makes
        // glBlitFramebuffer raise GL_INVALID_OPERATION (1282) and silently do nothing (→ white screen).
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_STENCIL_ATTACHMENT, GL11.GL_TEXTURE_2D, 0, 0);
        for (int i = 1; i < this.colorAttachHigh; i++) {
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0 + i, GL11.GL_TEXTURE_2D, 0, 0);
        }
        this.colorAttachHigh = 1;
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, front(idx), 0);
        GL20C.glDrawBuffers(new int[]{GL30C.GL_COLOR_ATTACHMENT0});
        int status = GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER);
        if (status != GL30C.GL_FRAMEBUFFER_COMPLETE) {
            log("blit: read FBO incomplete 0x" + Integer.toHexString(status));
        }
        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, this.fbo);
        GL30C.glReadBuffer(GL30C.GL_COLOR_ATTACHMENT0);
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, destFbo);
        GL30C.glBlitFramebuffer(0, 0, this.width, this.height, 0, 0, destW, destH,
                GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        int err = GL11.glGetError();
        if (err != 0) {
            log("blit: glGetError after glBlitFramebuffer = 0x" + Integer.toHexString(err));
        }
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, destFbo);
    }

    /** Flips the buffers a pass wrote so their freshly-written back side becomes the front for subsequent reads. */
    public void flipTargets(int[] indices) {
        for (int idx : indices) {
            this.flip[idx] = !this.flip[idx];
        }
    }

    /** Resets ping-pong to the front=A state at the start of a frame (so the gbuffer writes the same physical textures the
     * composites will first read). */
    public void resetFlip() {
        java.util.Arrays.fill(this.flip, false);
    }

    // --- shadow map (depth-only, light POV) ------------------------------------------------------------------------

    private void ensureShadow() {
        if (this.shadowCreated) {
            return;
        }
        this.shadowDepthTex = newDepthTexture(this.shadowSize, this.shadowSize);
        // The pack samples shadowtex0/1 as sampler2DShadow (shadow2D) in the terrain program → the depth texture needs
        // hardware compare mode (ref <= stored → lit). LINEAR filter enables hardware 2×2 PCF for softer edges (the pack's
        // GetShadow adds its own multi-tap PCF on top).
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.shadowDepthTex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14C.GL_TEXTURE_COMPARE_MODE, GL30C.GL_COMPARE_REF_TO_TEXTURE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14C.GL_TEXTURE_COMPARE_FUNC, GL11.GL_LEQUAL);
        // A fresh texture object starts with compare ON (just set), so setShadowCompareMode's cache must agree — otherwise
        // a recreation (resize / world reload; this fires more than once per session) that happens while the cache says
        // "off" makes the next request for "off" a no-op and pins compare ON for good.
        this.shadowCompareOn = true;
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        // shadowcolor0 = the pack's shadow COLOUR (stained glass); shadowcolor1 = its "Light Shaft Color", whose ALPHA
        // carries the caster height the scene-aware light shafts decode to grow vlFactor. Both are real buffers now: the
        // pass used to be depth-only (glDrawBuffers(GL_NONE)), which pinned vlFactor at 0 and left the light shafts at
        // under 1/8 intensity. See TerrainShaderTransformer.SHADOW_FRAGMENT for the encoding.
        this.shadowColorTex = newColorTexture(this.shadowSize, this.shadowSize, GL11C.GL_RGBA8);
        this.shadowColorTex1 = newColorTexture(this.shadowSize, this.shadowSize, GL11C.GL_RGBA8);
        // shadowtex1 = the OPAQUE-ONLY shadow depth, its own texture object (was aliased to shadowDepthTex). Two reasons
        // it must be separate: (1) the pack keys "a translucent blocked the light" on shadowtex0 differing from
        // shadowtex1 — aliased, that difference is always zero, so coloured shadows and the underwater god rays could
        // never fire; (2) shadowtex1 stays sampler2DShadow even inside composite1, where shadowtex0's compare mode is
        // flipped OFF for its raw texelFetch — a shared object would make that shadow2D a mismatched read (undefined,
        // 0 on NVIDIA). So this one keeps compare ON permanently and setShadowCompareMode never touches it.
        this.shadowDepthTexOpaque = newDepthTexture(this.shadowSize, this.shadowSize);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.shadowDepthTexOpaque);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14C.GL_TEXTURE_COMPARE_MODE, GL30C.GL_COMPARE_REF_TO_TEXTURE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14C.GL_TEXTURE_COMPARE_FUNC, GL11.GL_LEQUAL);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        // Its own FBO, purely as the blit destination for the opaque-depth snapshot. Cleared to depth 1.0 straight away:
        // an uninitialised depth of 0 reads as "everything shadowed" under GL_LEQUAL, which blackens the entire world, so
        // the safe default has to be "nothing shadowed" for any frame where the snapshot has not run yet.
        this.shadowOpaqueFbo = GL30C.glGenFramebuffers();
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, this.shadowOpaqueFbo);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT,
                GL11.GL_TEXTURE_2D, this.shadowDepthTexOpaque, 0);
        GL20C.glDrawBuffers(new int[]{GL11.GL_NONE});
        GL11.glDepthMask(true);
        GL11.glClearDepth(1.0);
        GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
        this.shadowFbo = GL30C.glGenFramebuffers();
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, this.shadowFbo);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, this.shadowColorTex, 0);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT1,
                GL11.GL_TEXTURE_2D, this.shadowColorTex1, 0);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT,
                GL11.GL_TEXTURE_2D, this.shadowDepthTex, 0);
        GL20C.glDrawBuffers(new int[]{GL30C.GL_COLOR_ATTACHMENT0, GL30C.GL_COLOR_ATTACHMENT1});
        int status = GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER);
        this.shadowCreated = status == GL30C.GL_FRAMEBUFFER_COMPLETE;
        if (!this.shadowCreated) {
            log("WARNING: shadow framebuffer incomplete, status=0x" + Integer.toHexString(status));
        } else {
            log("allocated shadow map " + this.shadowSize + "x" + this.shadowSize + " (depth, fbo=" + this.shadowFbo + ")");
        }
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
    }

    /** Tracks {@link #setShadowCompareMode}'s last applied state so the common case (no change) costs no GL call. */
    private boolean shadowCompareOn = true;
    /** DIAGNOSTIC: GL-truth report of every compare-mode transition (see setShadowCompareMode). */
    public static boolean diagCompareMode = false;

    /**
     * Switches the shadow depth map between HARDWARE-COMPARE ({@code shadow2D} / {@code sampler2DShadow}) and RAW-DEPTH
     * ({@code texelFetch} / {@code sampler2D}) reads.
     *
     * <p>A depth texture may only be sampled through the sampler kind matching its {@code GL_TEXTURE_COMPARE_MODE}; the
     * mismatched combination is undefined in the GL spec and returns 0 on NVIDIA. Complementary needs BOTH: terrain
     * lighting does {@code shadow2D(shadowtex0, …)} (compare on), while the volumetric light in composite1 reads raw
     * depth with {@code texelFetch(shadowtex0, …)} (compare off) — the pack declares the two types from one header via
     * {@code #ifdef COMPOSITE1}. Compare mode is per-TEXTURE-OBJECT state, not per-unit, so with a single shadow map the
     * only way to serve both is to flip it per program. Leaving it permanently on (as this port did) made composite1's
     * {@code shadowSample} read 0 for every ray step → every sample counted as shadowed → the volumetric light summed to
     * zero → NO light shafts anywhere, on land or underwater, while terrain shadows stayed correct.</p>
     *
     * <p>{@code shadowtex1} is {@code sampler2DShadow} even inside composite1, so during a raw-depth pass its
     * {@code shadow2D} is the mismatched combination. That path only runs for samples ALREADY found to be in shadow, and
     * is gated on an exact {@code == 1.0} test that a garbage read fails — so it degrades to "no coloured translucent
     * shafts" rather than corrupting the shafts themselves. Giving shadowtex1 its own texture object is the real fix and
     * is only worth it once the shadow pass writes shadowcolor0/1 at all (it is depth-only today).</p>
     */
    public void setShadowCompareMode(boolean compare) {
        if (this.shadowDepthTex == 0 || this.shadowCompareOn == compare) {
            return;
        }
        this.shadowCompareOn = compare;
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + SHADOWTEX_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.shadowDepthTex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14C.GL_TEXTURE_COMPARE_MODE,
                compare ? GL30C.GL_COMPARE_REF_TO_TEXTURE : GL11.GL_NONE);
        // Ask GL what it actually stored, rather than trusting that the call landed. This port's recurring failure mode is
        // exactly a state change that silently no-ops (GlStateManager cache desync, wrong active unit, wrong texture
        // bound) while our own bookkeeping happily reports success. Logged only on a real transition, so ~2 lines/frame.
        if (diagCompareMode) {
            int actual = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL14C.GL_TEXTURE_COMPARE_MODE);
            int boundTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            log("[SHADOW CMP] requested=" + (compare ? "COMPARE" : "NONE")
                    + " glReports=" + (actual == GL30C.GL_COMPARE_REF_TO_TEXTURE ? "COMPARE"
                            : actual == GL11.GL_NONE ? "NONE" : "0x" + Integer.toHexString(actual))
                    + " boundTex=" + boundTex + " shadowDepthTex=" + this.shadowDepthTex
                    + (boundTex != this.shadowDepthTex ? "  *** WRONG TEXTURE BOUND ***" : "")
                    + " glErr=0x" + Integer.toHexString(GL11.glGetError()));
        }
        // texelFetch ignores filtering, but a LINEAR depth texture read through a plain sampler2D elsewhere would
        // interpolate depth values; NEAREST keeps the raw-depth reads exact. Restore LINEAR for the 2x2 hardware PCF.
        int filter = compare ? GL11.GL_LINEAR : GL11.GL_NEAREST;
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    /**
     * DIAGNOSTIC: the raw depth stored in the shadow map at texel {@code (x, y)}, read straight from GL — the exact value
     * the pack's {@code texelFetch(shadowtex0, ivec2(shadowPos.xy * shadowMapResolutionM), 0).x} should see. Reads a
     * single texel via glGetTextureSubImage (GL 4.5), so it does not pull the whole 4096² map across the bus.
     */
    public float readShadowDepthTexel(int x, int y) {
        if (this.shadowDepthTex == 0) {
            return -1.0F;
        }
        java.nio.FloatBuffer buf = org.lwjgl.BufferUtils.createFloatBuffer(1);
        GL45C.glGetTextureSubImage(this.shadowDepthTex, 0,
                Math.max(0, Math.min(this.shadowSize - 1, x)), Math.max(0, Math.min(this.shadowSize - 1, y)), 0,
                1, 1, 1, GL11C.GL_DEPTH_COMPONENT, GL11C.GL_FLOAT, buf);
        return buf.get(0);
    }

    /** DIAGNOSTIC: the OPAQUE-ONLY shadow depth ({@code shadowtex1}) at texel {@code (x, y)} — see readShadowDepthTexel. */
    public float readShadowDepthOpaqueTexel(int x, int y) {
        if (this.shadowDepthTexOpaque == 0) {
            return -1.0F;
        }
        java.nio.FloatBuffer buf = org.lwjgl.BufferUtils.createFloatBuffer(1);
        GL45C.glGetTextureSubImage(this.shadowDepthTexOpaque, 0,
                Math.max(0, Math.min(this.shadowSize - 1, x)), Math.max(0, Math.min(this.shadowSize - 1, y)), 0,
                1, 1, 1, GL11C.GL_DEPTH_COMPONENT, GL11C.GL_FLOAT, buf);
        return buf.get(0);
    }

    /** DIAGNOSTIC: the full RGBA of shadowcolor0 ({@code color1=false}) or shadowcolor1 ({@code true}) at texel (x, y). */
    public float[] readShadowColorTexel(boolean color1, int x, int y) {
        int tex = color1 ? this.shadowColorTex1 : this.shadowColorTex;
        if (tex == 0) {
            return new float[]{-1, -1, -1, -1};
        }
        java.nio.FloatBuffer buf = org.lwjgl.BufferUtils.createFloatBuffer(4);
        GL45C.glGetTextureSubImage(tex, 0,
                Math.max(0, Math.min(this.shadowSize - 1, x)), Math.max(0, Math.min(this.shadowSize - 1, y)), 0,
                1, 1, 1, GL11C.GL_RGBA, GL11C.GL_FLOAT, buf);
        return new float[]{buf.get(0), buf.get(1), buf.get(2), buf.get(3)};
    }

    /** DIAGNOSTIC: shadowcolor1's alpha at texel {@code (x, y)} — the light-shaft caster height the SALS decodes. */
    public float readShadowColor1Alpha(int x, int y) {
        if (this.shadowColorTex1 == 0) {
            return -1.0F;
        }
        java.nio.FloatBuffer buf = org.lwjgl.BufferUtils.createFloatBuffer(4);
        GL45C.glGetTextureSubImage(this.shadowColorTex1, 0,
                Math.max(0, Math.min(this.shadowSize - 1, x)), Math.max(0, Math.min(this.shadowSize - 1, y)), 0,
                1, 1, 1, GL11C.GL_RGBA, GL11C.GL_FLOAT, buf);
        return buf.get(3);
    }

    /** DIAGNOSTIC: colortex5's bottom-right corner texel alpha — where the pack parks the temporal {@code vlFactor}. */
    public float readVlFactor() {
        if (this.width <= 0 || this.height <= 0) {
            return -1.0F;
        }
        java.nio.FloatBuffer buf = org.lwjgl.BufferUtils.createFloatBuffer(4);
        GL45C.glGetTextureSubImage(front(5), 0, this.width - 1, this.height - 1, 0,
                1, 1, 1, GL11C.GL_RGBA, GL11C.GL_FLOAT, buf);
        return buf.get(3);
    }

    public void beginShadowPass() {
        ensureShadow();
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, this.shadowFbo);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT,
                GL11.GL_TEXTURE_2D, this.shadowDepthTex, 0);
        // Re-attach the COLOUR targets every frame too — never assume they survived from ensureShadow. Found the hard
        // way (END "entity shadows break the terrain shadows"): COLOR_ATTACHMENT0 had been swapped for a WINDOW-sized
        // colortex (and attachment1 detached) by outside FBO traffic, and since GL clips rendering to the INTERSECTION
        // of all attachment sizes, the whole terrain shadow draw was silently cropped to a 1920x1057 corner of the
        // 4096 map — shadows then existed only where that corner happened to cover the lookup, i.e. they came and went
        // with the camera position. Re-establishing the pass's own attachments makes it immune to whoever scrambles
        // the FBO between frames.
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, this.shadowColorTex, 0);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT1,
                GL11.GL_TEXTURE_2D, this.shadowColorTex1, 0);
        if (this.shadowFbo == this.fbo) {
            // Should be impossible (distinct glGenFramebuffers) — but a destroy/recreate cycle that reuses freed GL ids
            // combined with a stale field would make the composites scramble the shadow FBO every frame. Loud if ever.
            log("ERROR: shadow FBO id aliases the world FBO id (" + this.fbo + ") — attachment scrambling guaranteed");
        }
        GL20C.glDrawBuffers(new int[]{GL30C.GL_COLOR_ATTACHMENT0, GL30C.GL_COLOR_ATTACHMENT1});
        GlStateManager.viewport(0, 0, this.shadowSize, this.shadowSize);
        // Clear the colour buffers to ZERO alpha, not black-opaque: the light shafts' SALS skips a texel with
        // `if (sampledHeight > 0.0)`, so alpha 0 is precisely "no caster here, don't count me". A cleared alpha of 1 would
        // decode to a bogus 15-block-tall caster everywhere and peg vlFactor open.
        GlStateManager.clearColor(0.0F, 0.0F, 0.0F, 0.0F);
        GlStateManager.clearDepth(1.0);
        GlStateManager.clear(GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_COLOR_BUFFER_BIT);
    }

    /**
     * Mid-frame immunization for the entity shadow-caster loop: re-binds the shadow FBO and re-establishes its OWN
     * attachments + draw buffers + viewport, WITHOUT clearing (the frame's casters so far must survive). The loop
     * hands control to arbitrary entity renderers and their mixin hooks; one of them retargeting the bound FBO
     * (the leash-hook lesson: a window-sized colortex on the 4096 map clips ALL later rasterization to the
     * attachment-size INTERSECTION) must not poison the remaining entities or the translucent/snapshot phases.
     */
    public void reassertShadowAttachments() {
        if (!this.shadowCreated) {
            return;
        }
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, this.shadowFbo);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, this.shadowColorTex, 0);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT1,
                GL11.GL_TEXTURE_2D, this.shadowColorTex1, 0);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT,
                GL11.GL_TEXTURE_2D, this.shadowDepthTex, 0);
        GL20C.glDrawBuffers(new int[]{GL30C.GL_COLOR_ATTACHMENT0, GL30C.GL_COLOR_ATTACHMENT1});
        GlStateManager.viewport(0, 0, this.shadowSize, this.shadowSize);
    }

    /** DIAGNOSTIC: the shadow FBO's GL id (identity check vs GL_FRAMEBUFFER_BINDING). */
    public int shadowFboId() {
        return this.shadowFbo;
    }

    /** DIAGNOSTIC: the shadowtex0 depth texture's GL id (identity check vs the FBO's actual depth attachment). */
    public int shadowDepthTexId() {
        return this.shadowDepthTex;
    }

    public void endShadowPass() {
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, this.shadowFbo);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT,
                GL11.GL_TEXTURE_2D, 0, 0);
    }

    public void bindWorldFramebufferNoClear() {
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, this.fbo);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT,
                GL11.GL_TEXTURE_2D, this.depthTex, 0);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, front(0), 0);
        GL20C.glDrawBuffers(new int[]{GL30C.GL_COLOR_ATTACHMENT0});
        GlStateManager.viewport(0, 0, this.width, this.height);
    }

    public void destroy() {
        destroyShadow();
        if (this.centerDepthPbos[0] != 0) {
            GL15C.glDeleteBuffers(this.centerDepthPbos[0]);
            GL15C.glDeleteBuffers(this.centerDepthPbos[1]);
            this.centerDepthPbos[0] = 0;
            this.centerDepthPbos[1] = 0;
            this.centerDepthQueued[0] = false;
            this.centerDepthQueued[1] = false;
        }
        if (this.defaultSpecularTex != 0) {
            deleteTexture(this.defaultSpecularTex);
            deleteTexture(this.defaultNormalsTex);
            this.defaultSpecularTex = 0;
            this.defaultNormalsTex = 0;
        }
        if (this.noiseTex != 0) {
            deleteTexture(this.noiseTex);
            this.noiseTex = 0;
        }
        if (this.waterNormalTex != 0) {
            deleteTexture(this.waterNormalTex);
            this.waterNormalTex = 0;
        }
        if (!this.created) {
            return;
        }
        for (int i = 0; i < NUM_COLORTEX; i++) {
            deleteTexture(this.colortexA[i]);
            deleteTexture(this.colortexB[i]);
            this.colortexA[i] = this.colortexB[i] = 0;
        }
        deleteTexture(this.depthTex);
        this.depthTex = 0;
        deleteTexture(this.waterReflectTex);
        this.waterReflectTex = 0;
        deleteTexture(this.waterDepthTex);
        this.waterDepthTex = 0;
        deleteTexture(this.opaqueDepthColorTex);
        this.opaqueDepthColorTex = 0;
        if (this.opaqueDepthFbo != 0) {
            GL30C.glDeleteFramebuffers(this.opaqueDepthFbo);
            this.opaqueDepthFbo = 0;
        }
        deleteTexture(this.waterRefTex);
        this.waterRefTex = 0;
        if (this.fbo != 0) {
            GL30C.glDeleteFramebuffers(this.fbo);
            this.fbo = 0;
        }
        this.created = false;
        this.width = -1;
        this.height = -1;
    }

    private void destroyShadow() {
        if (!this.shadowCreated) {
            return;
        }
        deleteTexture(this.shadowDepthTex);
        deleteTexture(this.shadowColorTex);
        deleteTexture(this.shadowColorTex1);
        deleteTexture(this.shadowDepthTexOpaque);
        this.shadowDepthTex = 0;
        this.shadowColorTex = 0;
        this.shadowColorTex1 = 0;
        this.shadowDepthTexOpaque = 0;
        if (this.shadowOpaqueFbo != 0) {
            GL30C.glDeleteFramebuffers(this.shadowOpaqueFbo);
            this.shadowOpaqueFbo = 0;
        }
        if (this.shadowFbo != 0) {
            GL30C.glDeleteFramebuffers(this.shadowFbo);
            this.shadowFbo = 0;
        }
        this.shadowCreated = false;
    }

    private static void deleteTexture(int tex) {
        if (tex != 0) {
            GL11.glDeleteTextures(tex);
        }
    }
}
