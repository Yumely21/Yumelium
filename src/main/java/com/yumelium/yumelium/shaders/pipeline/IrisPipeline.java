package com.yumelium.yumelium.shaders.pipeline;

import com.yumelium.yumelium.shaders.ShaderPackManager;
import com.yumelium.yumelium.shaders.gl.CustomImages;
import com.yumelium.yumelium.shaders.gl.GlslProgram;
import com.yumelium.yumelium.shaders.gl.RenderTargets;
import com.yumelium.yumelium.shaders.pack.GlslConditionals;
import com.yumelium.yumelium.shaders.pack.ProgramSource;
import com.yumelium.yumelium.shaders.pack.ShaderProperties;
import com.yumelium.yumelium.shaders.pack.ShaderOptionSet;
import com.yumelium.yumelium.shaders.pack.ShaderPack;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.MobEffects;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL42C;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Iris/Oculus port — M4. The deferred-pipeline orchestrator. World geometry is captured into {@code colortex0}
 * (M2/M3); this then runs the shader pack's fullscreen program chain in order — {@code deferred*} → {@code composite*}
 * → {@code final} — each pass reading the current {@code colortex0} and (for deferred/composite) ping-ponging the
 * result, with {@code final} writing to Minecraft's main framebuffer. Programs are compiled directly from the pack's
 * legacy {@code #version 120} GLSL (M0 compat-profile finding — no transformer). Opt-in via the shaders toggle key.
 */
public final class IrisPipeline {
    private static final IrisPipeline INSTANCE = new IrisPipeline();

    private static final int MAX_INDEX = 16;

    private static final String DEFAULT_FULLSCREEN_VSH =
            "#version 120\n" +
            "varying vec2 texcoord;\n" +
            "void main() {\n" +
            "    gl_Position = ftransform();\n" +
            "    texcoord = gl_MultiTexCoord0.st;\n" +
            "}\n";

    private volatile boolean enabled;
    private boolean activeThisFrame;
    private boolean pipelineInit;
    private RenderTargets targets;

    // The selected shader pack (built-in by default; the cycle key can switch to an external pack in shaderpacks/).
    private volatile String activePackName = ShaderPackManager.BUILTIN_NAME;

    // The dimension's program folder (pack dimension.properties: world0 = * catch-all, the_nether = world-1,
    // the_end = world1). Every pack (re)load scans THIS folder's programs; beginWorldRender watches the player's
    // dimension and swaps it (dropping the compiled pipeline) when it changes.
    private String worldFolder = com.yumelium.yumelium.shaders.pack.ProgramSet.OVERWORLD_FOLDER;

    private final List<Pass> preparePrograms = new ArrayList<>();
    private final List<Pass> deferredPrograms = new ArrayList<>();
    private final List<Pass> compositePrograms = new ArrayList<>();
    private GlslProgram finalProgram;
    /** Colortex indices the final program declares {@code colortexNMipmapEnabled = true} for (usually empty). */
    private int[] finalMipmaps = new int[0];

    /** A fullscreen deferred/composite pass: the compiled program + the colortex indices it writes (RENDERTARGETS), in
     * {@code gl_FragData} order. */
    private static final class Pass {
        final GlslProgram program;
        final int[] targets;
        final String name;
        /** colortex indices this pass declares {@code colortexNMipmapEnabled = true} → re-mipmap their front before it reads. */
        final int[] mipmaps;
        Pass(GlslProgram program, int[] targets, String name, int[] mipmaps) {
            this.program = program;
            this.targets = targets;
            this.name = name;
            this.mipmaps = mipmaps;
        }
    }

    // Sky (immediate-mode) programs, bound during RenderGlobal.renderSky: skybasic for the untextured sky (dome, glow,
    // stars, void), skytextured for the sun/moon. They read the fixed-function builtins directly (compat profile).
    private GlslProgram skybasicProgram;
    // A variant of gbuffers_skybasic that DISCARDs above-horizon pixels (VdotU > 0.15), used to paint the pack's real
    // GetSky onto the BELOW-horizon sky + horizon strip. MC 1.12.2 draws its "bottom sky" plane with a flat fixed-function
    // fog colour that our sky mixin can't route through the pack shader, so the below-horizon band read as the raw fog
    // colour (a hard grey strip at the horizon). This fullscreen fill (preCelestialFill — just BEFORE the sun/moon/star
    // quads, so it cannot erase them near the horizon) overwrites that band with the per-pixel GetSky the dome uses.
    private GlslProgram skybasicBelowProgram;
    private GlslProgram skytexturedProgram;
    private boolean skyActive;
    private boolean skyLogged;
    // Applied once per sky render: rotates MC's sun/moon/star quads by the pack's sunPathRotation so the textured disc
    // lands on the pack's atmosphere sun (its internal sunVec is tilted by that same baked rotation). Reset each beginSky.
    private boolean celestialTiltApplied;

    // First-person hand + held-item program (immediate mode), bound during ItemRenderer.renderItemInFirstPerson. Like the
    // sky, it reads the fixed-function builtins directly (compat profile); the built-in reproduces vanilla item lighting.
    private GlslProgram handProgram;
    private boolean handActive;
    private boolean handLogged;
    private boolean handEndLogged;

    // Regular-entity program (mobs, dropped items), bound around Sodium's entity render loop. Same immediate-mode path as
    // the hand; the built-in reproduces vanilla entity lighting with world-fixed light directions (see beginEntities).
    private GlslProgram entitiesProgram;
    private boolean entitiesActive;
    private boolean entitiesLogged;
    // Vanilla RenderHelper.enableStandardItemLighting world-space light directions (normalized).
    private static final Vector3f ITEM_LIGHT0 = new Vector3f(0.2F, 1.0F, -0.7F).normalize();
    private static final Vector3f ITEM_LIGHT1 = new Vector3f(-0.2F, 1.0F, 0.7F).normalize();

    // Block-entity / TESR program (chests, signs, banners, ...), bound around Sodium's TESR render. Same lighting model
    // as entities; falls back to the entities program if the pack has no gbuffers_block.
    private GlslProgram blockProgram;
    private boolean blockActive;
    private boolean blockLogged;
    // The pack's dedicated emissive beacon-beam program: onBlockEntityRender swaps it in PER TESR while a
    // TileEntityBeacon renders (and back for the next one), so the beam stops being material-dispatched like a chest.
    private GlslProgram beaconBeamProgram;
    /** The program currently bound for the block-entity pass (base or beacon) — onBlockEntityRender rebinds on change. */
    private GlslProgram activeBlockPassProgram;
    // Block-breaking crack overlay program, bound around RenderGlobal.drawBlockDamageTexture.
    private GlslProgram damagedBlockProgram;
    private boolean damagedActive;
    private boolean damagedLogged;
    // Overlay programs drawn INSIDE a live hand/entities/block pass (see beginArmorGlint/beginSpiderEyes):
    // the enchantment glint, the fullbright eye layers, and the glowing-entity per-entity swap.
    private GlslProgram armorGlintProgram;
    private GlslProgram spidereyesProgram;
    private GlslProgram entitiesGlowingProgram;
    private int[] armorGlintTargets;
    private int[] spidereyesTargets;
    private boolean glintActive;
    private boolean eyesActive;
    // gbuffers_basic: untextured POSITION_COLOR geometry — the leash line (inside the entities pass) and the block
    // selection outline (standalone, after the TESR/damage passes). gbuffers_line is deliberately NOT routed: its
    // vertex is the modern-Iris line expansion (vaPosition/vaNormal/gl_VertexID quad pairs) which 1.12.2's
    // fixed-function GL_LINES draws cannot feed — basic is the OptiFine-era program for exactly these draws.
    private GlslProgram basicProgram;
    private int[] basicTargets;
    private boolean basicActive;
    /** The program bound for the CURRENT entity (gbuffers_entities, or _glowing for isGlowing() ones) — what an
     * overlay sub-draw must restore, and where the per-entity/item uniforms go. */
    private GlslProgram activeEntityProgram;
    /** Exponentially smoothed held-item lightmap coords (raw 0..240 scale; -1 = unseeded). See gatherWorldState. */
    private float handLightSmoothX = -1.0F;
    private float handLightSmoothY = -1.0F;

    // Generic textured program (particles), bound around ParticleManager.renderParticles. No directional lighting
    // (billboards) — just texture × colour × lightmap.
    private GlslProgram texturedProgram;
    private boolean particlesActive;
    private boolean particlesLogged;

    // Weather program (falling rain/snow), bound around EntityRenderer.renderRainSnow. Immediate-mode like the sky: reads
    // the fixed-function builtins + the vanilla rain/snow texture MC binds on unit 0, and shades it the pack's way (tinted
    // by ambient/sun) instead of vanilla's flat streaks. IMPROVED_RAIN is 1.16.5+-only so on 1.12.2 it writes colour to
    // colortex0 directly (no deferred rain data pass), so a simple bind — no extra render targets — suffices.
    private GlslProgram weatherProgram;
    private boolean weatherActive;
    private boolean weatherLogged;

    // The active pack's raw gbuffers_terrain source {vertex, fragment}, loaded lazily; null if the pack has none.
    private String[] packTerrainSources;
    private boolean packTerrainLoaded;

    // The active pack's `shadow` program VERTEX source (options applied), loaded lazily; null if the pack has none. The
    // shadow pass uses this lean pack-authored shadow vertex (position + wave + shadow distortion, no full terrain
    // lighting) paired with a minimal alpha-test-only fragment, instead of running the heavy gbuffers_terrain shader for
    // depth. Only the vertex is needed — the pack's shadow fragment computes a shadow COLOUR we don't use (depth-only map).
    private String packShadowVertexSource;
    private boolean packShadowLoaded;

    // The active pack's gbuffers_water source {vertex, fragment} + the colortex indices its ACTIVE branch writes (parsed
    // from the branch-stripped fragment). Loaded lazily; null if the pack has no water program. Used for the TRANSLUCENT pass.
    private String[] packWaterSources;
    private int[] packWaterTargets;
    private boolean packWaterLoaded;

    // The colortex indices gbuffers_terrain's ACTIVE branch writes, parsed from the branch-stripped fragment exactly like
    // the water program's. Do NOT hardcode these: the pack ships several variants and they are NOT nested prefixes of one
    // another — with SS_BLOCKLIGHT the terrain writes `DRAWBUFFERS:0649` (gl_FragData[3] = lightAlbedo → colortex9), while
    // the Photonics variant writes `RENDERTARGETS:0,6,4,10,11,20` (gl_FragData[3] = phAlbedoOut → colortex10). Attaching
    // the Photonics list while the SSBL branch is live sent lightAlbedo into colortex10 — which is composite2's
    // SS-blocklight HISTORY buffer (never cleared, blended at 1% per frame). The history was therefore overwritten with
    // raw albedo every frame and could never converge: coloured ghosting/speckle around light sources.
    private int[] packTerrainTargets;

    // The colortex indices the immediate-mode entity + hand programs write (parsed from their branch-stripped fragments).
    // Attached during those draws so they overwrite the material buffer (colortex6.r = smoothnessD) + normal at their
    // pixels — else they write colour (colortex0) only and the STALE terrain material behind them (e.g. a wet, mirror-
    // smooth puddle) leaks into the pack's composite reflection, painting a reflection onto the entity/hand silhouette.
    private int[] entityTargets;
    private int[] handTargets;

    // Raw PNG bytes of the pack's custom textures (shaders.properties `texture.*=`), read once while the pack is open in
    // initPipeline and handed to RenderTargets. noise.png → noisetex; cloud-water.png → gaux4 (the water normal map whose
    // absence curved the reflection). Null if the pack doesn't ship them.
    private byte[] noisePngData;
    private byte[] waterNormalPngData;

    // The active pack's configurable options (M8), discovered lazily from its shaders.properties `screen` + #defines and
    // its saved values. Applied to every shader source before compilation; reset when the active pack changes.
    private ShaderOptionSet shaderOptions;
    private boolean shaderOptionsLoaded;

    // Custom images (Colored Lighting + World Space Reflections): the pack's `image.*`/`bufferObject.*` voxel/floodfill/
    // WSR textures + SSBOs (shaders.properties, all gated `#if COLORED_LIGHTING > 0`). Empty when COLORED_LIGHTING=0.
    // Allocated in initPipeline, cleared each frame, bound to every pass that samples/writes them, destroyed with the pack.
    private CustomImages customImages = new CustomImages();

    // GL sampler object bound to TEXTURE_ATLAS_UNIT (the WSR textureAtlas): forces the reflection's atlas reads to a
    // capped mip so a grazing-angle (huge lod) reflection can't sample a coarse mip whose texels average across sprite
    // boundaries — which on the 1.12.2 combined block+item atlas bleeds neighbouring item icons into block reflections.
    // Lazily created; 0 until first use. Overrides only unit 40's filtering, not the shared atlas texture's own params.
    private int atlasReflectSampler;

    // True once the transformed pack gbuffers_terrain has compiled — only then does the terrain shader write a valid
    // view-space normal to colortex1, so only then do we enable the second draw buffer during the terrain passes.
    private volatile boolean terrainWritesNormal;
    private boolean diagTerrainFboLogged;

    // True while Sodium's TRANSLUCENT terrain pass is the one being drawn, so applyTerrainUniforms can report the honest
    // renderStage. The pack's WSR voxelizer accepts only SOLID/CUTOUT stages and its puddle voxelizer only TRANSLUCENT;
    // a blanket TERRAIN_SOLID fed water into the WSR volume and starved the puddle map.
    private volatile boolean terrainTranslucentPass;

    // The G-buffer normal (colortex1) written by terrain: the terrain shader writes its view-space normal + its own
    // window depth (in alpha) into colortex1 during the terrain passes. The built-in composite consumes it through a
    // DEPTH-CONSISTENCY GUARD (M7): it uses the crisp per-face normal only where colortex1's stored depth matches the
    // scene depth, so geometry drawn over the terrain in immediate mode (hand/entities/particles — which leave colortex1
    // holding the terrain behind them) correctly falls back to the per-pixel screen-space normal. This fixed the earlier
    // "hand looks see-through" bug that forced this off, without needing every immediate-mode type to write normals.
    private static final boolean NORMAL_GBUFFER_ENABLED = true;

    // DIAGNOSTIC master switch for the periodic (every-100-frames) readback logging. This logging does full-framebuffer
    // glGetTexImage readbacks (~90 MB across the composite chain) + a 2048² shadow-depth glReadPixels, which STALL the GPU
    // pipeline for ~0.4 s. Worse, that stall makes wall-clock time jump ~0.4 s, so the very next frame the wind-driven
    // clouds + water waves leap forward by 0.4 s of animation in one step — TAA can't reproject that jump and rejects the
    // history → a hard flicker every 100 frames even with a still camera. OFF for normal play; flip on only when debugging.
    private static final boolean DIAG_PERIODIC_LOG = false;

    // DIAGNOSTIC (horizon band): logs the fog/sky uniforms (skyColor, fogColor, near/far, cameraY, sun) every 60 frames.
    // Cheap (no GPU readback, no stall). Used to find why the far horizon renders a grey band vs 1.20.1's smooth haze.
    private static final boolean HORIZON_DIAG = false;

    // DIAGNOSTIC (END beams): once a second in the END, logs the GL fixed-function fog state (what deferred1's
    // `gl_Fog.start / far > 0.5` ender-dragon check actually reads — vanilla setupFog sets 0.05*far while the dragon
    // boss bar makes world fog, 0.75*far otherwise), the boss-overlay verdict, camera XZ (the `maxCamPosXZ > 350`
    // escape), and the live vlFactor from the ct5 corner texel. vlFactor drives beam brightness: 0 = "dragon dead"
    // look (purple x2.5), 1 = "dragon alive" look (purple x1.5 + orange). One texel readback per second when on.
    private static final boolean DIAG_END_BEAM = false; // 2026-07-27: measured ratio=0.75 (dead, correct), vlFactor=0 (design value) — beam-streak gap closed by the rim fixes

    // DIAGNOSTIC (horizon band): clear the G-buffer colortex0 to MAGENTA instead of the fog colour. Any pixel that ends up
    // magenta was never covered by sky/terrain/water (it shows the raw clear) — pinpoints whether the grey below-horizon
    // band is uncovered clear-colour or a real (mis-shaded) sky. Set false for normal rendering.
    private static final boolean DIAG_MAGENTA_CLEAR = false;

    // DIAGNOSTIC: paint the below-horizon sky-fill BRIGHT RED to see exactly which pixels it covers (vs the grey band).
    private static final boolean DIAG_SKYFILL_RED = false;

    /** @return true only on a periodic-log frame AND when periodic logging is enabled (see {@link #DIAG_PERIODIC_LOG}). */
    private boolean isDiagFrame() {
        return DIAG_PERIODIC_LOG && this.frameCounter % 100 == 0;
    }

    // DIAGNOSTIC (water): probe glGetError at each step of the translucent water pass + dump the transformed water GLSL,
    // to find why shader water is wrong. Logs the first DIAG_WATER_MAX GL errors (with a step label) then goes quiet so a
    // persistent error doesn't spam. Turn off once water is fixed.
    private static final boolean DIAG_WATER = false;
    private static final int DIAG_WATER_MAX = 60;
    private int diagWaterErrLogs;

    /** Logs a GL error (if any) with a step label during the water pass; also clears the error. No-op unless DIAG_WATER. */
    private void diagWaterGlError(String step) {
        if (!DIAG_WATER) {
            return;
        }
        int e = GL11.glGetError();
        if (e != 0 && this.diagWaterErrLogs < DIAG_WATER_MAX) {
            this.diagWaterErrLogs++;
            log("[DIAG water] GL error 0x" + Integer.toHexString(e) + " at: " + step
                    + (this.diagWaterErrLogs == DIAG_WATER_MAX ? " (further water GL-error logs suppressed)" : ""));
        }
    }

    // Sodium/chunk-shader uniforms the water program legitimately gets from ChunkShaderInterface (not from our Iris scene
    // set) — excluded from the "unset" report so they aren't false positives.
    private static final java.util.Set<String> WATER_EXTRA_PROVIDED = new java.util.HashSet<>(java.util.Arrays.asList(
            "u_ModelViewMatrix", "u_ProjectionMatrix", "u_RegionOffset", "u_BlockTex", "u_LightTex",
            "tex", "gtexture", "lightmap", "iris_shadowPass", "iris_shadowMapBias"));
    private boolean waterUniformsLogged;

    /** ONE-TIME report of which uniforms the transformed gbuffers_water program actually uses that the pipeline never sets
     * (read as 0 → wrong reflections/refraction/fog). The strongest lead for "why is the water wrong". No-op unless DIAG_WATER. */
    public void logWaterProgramUniforms(GlslProgram water) {
        if (!DIAG_WATER || this.waterUniformsLogged || water == null) {
            return;
        }
        this.waterUniformsLogged = true;
        java.util.List<String> active = water.activeUniforms();
        java.util.List<String> misses = new java.util.ArrayList<>();
        for (String name : active) {
            String base = name.endsWith("[0]") ? name.substring(0, name.length() - 3) : name;
            if (base.startsWith("gl_") || PROVIDED_UNIFORMS.contains(base) || WATER_EXTRA_PROVIDED.contains(base)) {
                continue;
            }
            misses.add(base);
        }
        java.util.Collections.sort(misses);
        log("[DIAG water] gbuffers_water active-uniform count=" + active.size()
                + (misses.isEmpty() ? "; all provided" : "; UNSET (" + misses.size() + "): " + String.join(", ", misses)));
    }

    // DIAGNOSTIC: blit the raw colortex0 (gbuffer) to the screen, skipping the pack's deferred/composite/final chain, to
    // isolate where a broken (e.g. all-white) real-pack render originates. Set false for normal rendering.
    private static final boolean DIAG_SHOW_GBUFFER = false;

    // DIAGNOSTIC (composite chain bisection): run only the first N passes of prepare+deferred+composite, then show
    // colortex0. DIAG_CHAIN_PASSES=0 → raw gbuffer; raise it to find the pass that whites out. DIAG_BLIT_INSTEAD_OF_FINAL
    // skips the pack's final program and shows front(FINAL_COLORTEX) through the tonemap blit instead — separates
    // composite corruption from a final blow-up.
    private static final int DIAG_CHAIN_PASSES = 999;
    private static final boolean DIAG_BLIT_INSTEAD_OF_FINAL = false;

    // Complementary's full deferred composite chain diverges on this ad-hoc pipeline (its exposure/bloom/temporal passes
    // feed back to white/NaN without pixel-exact G-buffer encoding + ~100 uniforms). Since the pack does its main lighting
    // INSIDE gbuffers_terrain, we instead tonemap the (already-lit) colortex0 HDR scene directly — a stable, good-looking
    // result across all lighting. Set false to run the (currently broken) pack composite chain for further debugging.
    private static final boolean USE_BUILTIN_TONEMAP = false;

    // DIAGNOSTIC: every 120 frames, read back voxel_img + floodfill and log their non-zero texel counts, to localise where
    // the Colored Lighting chain breaks (voxel_img=0 → voxelization not running; floodfill=0 → compute not producing).
    private static final boolean DIAG_COLORED_LIGHTING = false;

    /** The colortex the composites leave the finished, tonemapped scene colour in (Complementary's final.glsl reads
     * colortex3). The pack's `final` program is the normal display path; this buffer feeds the tonemap-blit
     * FALLBACK (pack ships no final, or DIAG_BLIT_INSTEAD_OF_FINAL) and the display-path diagnostics. */
    private static final int FINAL_COLORTEX = 3;
    private GlslProgram tonemapProgram;

    // Copies the opaque scene depth (waterDepthTex, a depth texture on unit 1) into an R32F colour texture so the composite
    // can read it as depthtex1 on a high unit (depth textures read black off units 0/1). Makes z0 != z1 at water pixels →
    // the pack's getWSR (world-space reflections) fires instead of falling back to screen-space (which cut off at edges).
    private GlslProgram depthCopyProgram;

    // Whether copyDepthForWater() (which fills waterDepthTex = the SOURCE of the composite's depthtex1) actually ran this
    // frame. It is driven by onTerrainPassBegin(translucent=true), but Sodium SKIPS a render pass entirely when it has no
    // geometry — so in any view without visible water/glass/ice the copy never happens and waterDepthTex keeps a STALE
    // (or, on the first frames, uninitialised) depth. runOpaqueDepthCopy then builds depthtex1 out of that garbage, and
    // the pack's SSR — whose only hit gate in the composite is `refPos.z < 1.0` on depthtex1 — accepts a "hit" on sky
    // pixels and samples colortex0 (the screen's own image) into the reflection: block textures smeared over the wet
    // ground where real Iris shows a smooth sky. See runOpaqueDepthCopy for the per-frame fallback.
    private boolean waterDepthCopiedThisFrame;
    /** Diagnostic only: whether the translucent pass (not the fallback) supplied waterDepthTex this frame. */
    private boolean waterDepthCopiedByPass;

    private static final String DEPTH_COPY_FSH =
            "#version 120\n" +
            "uniform sampler2D depthtex0;\n" +      // opaque pre-water depth (waterDepthTex) on unit 1
            "uniform sampler2D depthtexLive;\n" +   // live scene depth incl. the first-person hand (depthTex) on unit 0
            "varying vec2 texcoord;\n" +
            "void main() {\n" +
            // Keep the opaque depth everywhere EXCEPT the first-person hand, whose compressed near depth (<= 0.1) is
            // spliced in from the live buffer. This IS real Iris semantics: Iris renders the SOLID HAND before its
            // pre-translucent depthtex1 snapshot, so the hand is present in Iris's depthtex1 — which is why the pack
            // has no z1-side hand gates (its VL rayEnd = min(lViewPos1, ...) simply stops at the hand). Removing this
            // splice let the volumetric light/fog march the full background distance THROUGH the hand (lights and
            // water glowing through it). NOTE: the water-material ripple once blamed on this splice was actually the
            // per-slot blend-off wipe (see MixinGlStateManagerIris) — with that fixed, the splice is purely correct.
            "    float oz = texture2D(depthtex0, texcoord).r;\n" +
            "    float lz = texture2D(depthtexLive, texcoord).r;\n" +
            "    gl_FragColor = vec4(lz <= 0.1 ? lz : oz);\n" +
            "}\n";

    // Forces the first-person hand's reflection fresnel (colortex4.a) back to 1.0. deferred1 overwrites the hand's
    // colortex4.a (which gbuffers_hand set to 1.0) with sqrt(fresnel*color.a) for EVERY pixel — including the wet hand,
    // whose IPBR-generated smoothness then makes composite1 blend a scene reflection onto it (the hand mirrors the sky at
    // grazing angles → a see-through, reflective hand). Pinning a=1.0 for the hand (z0<=0.1) makes composite1's reflection
    // gate `abs(pow2(a)-0.5)<0.5` reject it, so the hand stays matte. Runs once, right after deferred1. Pack is untouched.
    private GlslProgram handReflectFixProgram;
    private static final String HAND_REFLECT_FIX_FSH =
            "#version 120\n" +
            "uniform sampler2D colortex4;\n" +   // normal.rgb + reflection fresnel .a (just written by deferred1)
            "uniform sampler2D depthtex0;\n" +   // scene depth on unit 1 — the hand is the only geometry at z0<=0.1
            "uniform float handReflectMax;\n" +  // cap on the hand's reflection fresnel: 0=matte, ~0.3=subtle sheen, 1=full
            "varying vec2 texcoord;\n" +
            "void main() {\n" +
            "    vec4 c4 = texture2D(colortex4, texcoord);\n" +
            "    float z0 = texture2D(depthtex0, texcoord).r;\n" +
            // Cap the hand's reflection fresnel. At full strength deferred1 gives ~0.7, so composite1 mirrors sharp scene
            // objects (mobs/blocks) onto the hand → looks like you see through it. Capping to a small value keeps a faint
            // wet sheen while making the reflected objects nearly invisible. handReflectMax=0 makes composite1 skip it
            // entirely (fully matte).
            "    gl_FragColor = vec4(c4.rgb, z0 <= 0.1 ? min(c4.a, handReflectMax) : c4.a);\n" +
            "}\n";

    // The colored-lighting flood-fill compute program (pack `shadowcomp`), dispatched each frame after the shadow pass
    // voxelizes the world into voxel_img: it propagates block-light colour one voxel step through floodfill_img (ping-pong
    // with floodfill_img_copy). Null unless the pack has a shadowcomp AND COLORED_LIGHTING > 0 (→ custom images exist).
    private GlslProgram shadowCompProgram;

    // One-shot GL draw-state dumps around the first-person hand ([HAND STATE]/[HAND END]/[HAND DIAG]) — the
    // see-through-hand investigation's tooling. Off for clean logs; the hand fix itself does not depend on them.
    private static final boolean DIAG_HAND_STATE = false;
    // When true, the display tonemap colour-codes depthtex0 (this.depthTex, the buffer the composite reads as depthtex0)
    // over the near region so we can read the first-person hand's actual z0: RED z0<=0.1 (hand depth landed in depthtex0),
    // YELLOW 0.1..0.56, GREEN 0.56..0.7 (hand depth is the far background → composite treats it as sky/terrain → the
    // see-through hand). A DIAG viewer flag: it forces the tonemap quad to draw even when the pack's final ran.
    private static final boolean DIAG_HAND_DEPTH = false;
    // One-shot readback of the WSR face-data SSBO (frame 200) to diagnose the reflection item textures: stale (clear
    // failing) vs real voxelized faces with off-atlas UVs. See CustomImages#diagSsbo.
    // Verifies each terrain pass reports an honest renderStage to the pack (see applyTerrainUniforms).
    private static final boolean DIAG_RENDER_STAGE = false;
    private static final boolean DIAG_SSBO = false;
    // One-shot dump of the block atlas to run/client/atlas_dump.png (see dumpAtlas) — to inspect what the WSR samples.
    private static final boolean DIAG_DUMP_ATLAS = false;
    // DIAGNOSTIC (hand transparent face): probes a fixed hand-region pixel (display ~0.78W, 0.85H — on the held block's
    // dark face) ~1/second: scene depth (did the hand WRITE there?), colortex0 BEFORE the composite chain (what the
    // gbuffer holds), and the displayed colortex3 AFTER. Splits "the face never drew" (depth ≥ 0.1 / ct0 = background)
    // from "the composite painted background over it" (ct0 = pale hand, display = background).
    private static final boolean DIAG_HAND_PIXEL = false;
    // Per-Y-layer occupancy histogram of wsr_img (see CustomImages#diagVoxelYHistogram): is the WSR voxel grid ALIGNED
    // with the real blocks? Occupied air layers above the player's feet = phantom voxels, which make the reflection ray
    // hit a block immediately instead of escaping to the sky — the "block textures where 1.20.1 is smooth" symptom.
    private static final boolean DIAG_VOXEL_Y = false;
    /** DIAG (connected glass): one voxel-id histogram every 10s — stained glass must appear as ids 200-215. */
    private static final boolean DIAG_VOXEL_IDS = false; // CTM RESOLVED (glass voxelization confirmed working)
    // Paints the screen with the composite's colortex7 (= reflectOutput, the WSR reflection composite1 blends into water).
    // RED = alpha ~0 => getWSR returned vec4(0.0) (the voxel ray hit nothing), so the water can only use its own
    // screen-space reflection — which is exactly what cuts off diagonally at the frame edge.
    private static final boolean DIAG_COLORTEX7 = false;
    // Instruments composite.glsl's PBR_REFLECTIONS temporal accumulator (see injectRefTemporalDebug): is its ~28-frame
    // history being used, or rejected every frame? If rejected, the per-pixel roughness noise it exists to average is
    // never averaged — the speckle around light sources. Reports the dominant rejecting term. Self-contained: it binds
    // colortex7 and paints the tag itself, and it deliberately preserves colortex7.a (the buffer feeds its own history,
    // so writing an alpha of our own manufactures the very rejection we are testing for — it did, once).
    private static final boolean DIAG_REF_TEMPORAL = false;
    // Instruments the pack's WSR ray trace at compile time (see injectWsrTraceDebug) so getWSR reports WHICH of its four
    // exits it took, instead of an undiagnosable vec4(0.0). Pairs with DIAG_COLORTEX7 to show the tag on screen.
    private static final boolean DIAG_WSR_TRACE = false;
    // Paints the screen by colortex4.a = the pack's `fresnelM`, which gates composite.glsl's WHOLE reflection block
    // (`if (fresnelM > 0.0)`). RED (=0) means the pack never calls GetReflection there, so no reflection of any kind —
    // SSR or WSR — can exist at that pixel and colortex7 stays empty.
    private static final boolean DIAG_COLORTEX4A = false;
    // Logs the REAL numbers behind every reflection gate at the CROSSHAIR pixel (RenderTargets#diagCrosshair), once a
    // second. Aim at either side of a boundary and read values instead of interpreting hues: the colour diagnostics
    // above each compress several causes into one colour, and DIAG_COLORTEX4A's top/bottom split showed two DIFFERENT
    // buffers for two different parts of the scene, so a boundary could never be compared across it.
    private static final boolean DIAG_CROSSHAIR = false;
    // One-shot horizontal scan of the reflection gates across the WHOLE row (RenderTargets#diagScanRow). The crosshair
    // readback settled the last boundary in one round, but only because that boundary could be aimed at — a SCREEN-EDGE
    // boundary never reaches the centre, so it needs a scan instead.
    private static final boolean DIAG_SCAN_ROW = false;
    // Asks gbuffers_terrain what it decided about ore emission (see injectOreEmissionDebug): the real `mat`, the real
    // `emission`, and whether GLOWING_ORE_IRON survived the driver's preprocessing. Reported via the free colortex9 and
    // read at the crosshair, so aim at an ore block.
    private static final boolean DIAG_ORE_EMISSION = false;
    // Whole-buffer scan (no aiming) of colortex0 (emissive specks) + colortex3 (bloom tiles) + colortex0's mip4, once at
    // frame 200 — the ore glow is far weaker than 1.20.1 and the missing bloom halo points at the bloom pipeline. Splits
    // "specks not bright" from "bloom tiles empty" from "mipmap not generated". See RenderTargets#diagBloom.
    private static final boolean DIAG_BLOOM = false;
    // Logs both floodfill ping-pong buffers' totals per-frame over a window, to see the colored-light flicker directly.
    private static final boolean DIAG_FLOODFILL_FLICKER = false;
    // Compares the two floodfill ping-pong buffers per-voxel: a local difference read alternately by framemod2 flickers.
    private static final boolean DIAG_FLOODFILL_CMP = false;
    // One-shot compile-time check: does the TAA_SMOOTHING_OVERRIDE actually reach composite6 (the DoTAA pass)?
    private static final boolean DIAG_TAA_SMOOTHING = false;
    // A/B (compile-time source transform of the inlined taa.glsl; pack file on disk untouched): neutralize the TAA
    // neighbourhood variance clamp (ClipAABB). Confirmed (2026-07-16) the clamp-passthrough on the bright colored-light
    // boundary is the flicker mechanism → but the ROOT cause was the flood-fill volume being NEAREST-sampled (blocky
    // light = high variance); fixed at source in CustomImages (LINEAR floodfill). Keep the clamp ON (motion anti-ghost);
    // this stays false. Left in place only as the discriminator if a future variance-driven flicker needs re-testing.
    private static final boolean DIAG_TAA_NOCLAMP = false;
    // DIAGNOSTIC (source transform of the inlined volumetricLight.glsl; pack file on disk untouched): the light shafts do
    // not render at all, on land or underwater. The log already proves the code path EXISTS and compiles — composite1
    // reports shadowtex0 as an ACTIVE sampler2D, and GetVolumetricLight is the only thing in composite1 that touches
    // shadowtex0 (SampleShadow is #ifndef COMPOSITE1), so LIGHTSHAFTS_ACTIVE / OVERWORLD / COMPOSITE1 are all defined.
    // That leaves "the function runs but returns ~0". This forces its two kill-switches open to bisect WHERE the zero
    // comes from, rather than reading the pack statically (which has been wrong five times on this project):
    //   FORCE_LIT   — every ray sample counts as fully lit (bypasses the shadow-map read entirely)
    //   FORCE_MULT  — vlMult is pinned open (bypasses the whole atmospheric gate: sun angle, VdotL/VdotU, time of day)
    // With BOTH on, the screen should show heavy uniform light-shaft fog. If it does, the machinery works and the zero is
    // in the gate/shadow terms. If it does NOT, the sum never reaches the screen and the fault is downstream of the
    // function (colortex plumbing / the `color += volumetricEffect.rgb` add). Turn both off once located.
    private static final boolean DIAG_VL_FORCE_LIT = false;
    private static final boolean DIAG_VL_FORCE_MULT = false;
    // BRANCH-COLOR bisect (pair with DIAG_SHOW_VL): paints each VL ray sample by the branch the GPU actually took —
    // RED = texelFetch(shadowtex0) said LIT, GREEN = the translucent branch fired (shadow2D(shadowtex1)==1.0), BLACK =
    // both failed. The integrated on-screen hue then IS the GPU's answer: mostly-black = the raw shadowtex0 fetch reads
    // 0 (compare-flip broken for composite1), red-without-green = the ==1.0 equality on shadowtex1 never passes, green =
    // the branch fires and the zero is in shadowcolor1's content. CPU probes could not see this (they bypass samplers).
    private static final boolean DIAG_VL_BRANCH_COLORS = false;
    // RAYEND bisect: the raw-VL view shows geometry-terminated rays near-black vs bright sky rays, split at the floor's
    // visual horizon — as if the march is cut almost immediately when the ray ends on geometry. rayEnd = min(lViewPos1,
    // maxDistance) with lViewPos1 from OUR depthtex1; forcing the full march decides it: samples past the floor read
    // OPAQUE (density 0) anyway, so if lViewPos1 is CORRECT this changes ~nothing, and if it is TOO SMALL the
    // below-horizon region lights up.
    private static final boolean DIAG_VL_FORCE_RAYEND = false;
    // FORCE_DARK — the disambiguator. localDensity is initialised to vec3(1.0) BEFORE the shadow test and only assigned
    // inside `if (length(shadowPos.xy * 2.0 - 1.0) < 1.0)`. So "shadow read returns 1 everywhere" and "that if never runs"
    // produce the IDENTICAL picture (uniform fog, no structure) — which is exactly what the FORCE_LIT and shadow-read-live
    // runs both showed. Writing vec3(0.0) at the same spot separates them: if the near-field fog visibly thins/darkens the
    // branch DOES execute (so the shadow read is live and simply returning "lit"); if nothing changes at all the branch
    // never executes and GetShadowPos / the shadowPos range test is the fault. Overrides FORCE_LIT (same anchor).
    private static final boolean DIAG_VL_FORCE_DARK = false;
    // FORCE_RANGE — separates the last two candidates. FORCE_DARK writing vec3(0.0) inside the shadow branch changed
    // NOTHING, so either (a) the runtime `if (length(shadowPos.xy * 2.0 - 1.0) < 1.0)` never passes (GetShadowPos /
    // the shadow matrices are wrong for this program), or (b) the whole `#if SHADOW_QUALITY > -1 && defined COMPOSITE1`
    // block is not compiled at all (in which case the anchor is present in the source string — and reports HIT — but the
    // GLSL preprocessor drops it, which our Java-side anchor check cannot see). Forcing the range test open decides it:
    // if the fog changes, (a); if it still does not, (b).
    private static final boolean DIAG_VL_FORCE_RANGE = false;
    // Logs the CPU-evaluated GetShadowPos + the SALS/vlFactor probe (see diagShadowPos / diagSals) from inside the
    // composite chain, ~1/second. Off: it costs ~50 single-texel GPU readbacks a second. Turn on to re-check the light
    // shafts' vlFactor adaptation — but read vlFactor itself, not diagSals's salsCheck verdict, which runs ~10% under the
    // shader's (it fetches one exact texel where the shader LINEAR-filters, and samples at a different instant).
    private static final boolean DIAG_SHADOW_POS = false; // SALS verified working under BEHAVIOUR=1 (2026-07-27): open=skip, forest=4.6, flat=0 — thresholds behave like real 1.20.1
    // Logs the underwater VL shadow probe (diagUnderwaterShadow): GL-truth reads of shadowtex0/1 + shadowcolor0/1 at
    // camera-relative points while submerged, ~1/second — the measured branch decides the god-ray fix, not theory.
    private static final boolean DIAG_UW_SHADOW = false;
    // Logs how many draw batches reached the shadow map + the camera yaw/pitch, ~2/second. Costs nothing (the counter is
    // already maintained); on to confirm the camera-visible-set limitation behind the "shadows vanish when I turn" report.
    private static final boolean DIAG_SHADOW_CASTERS = false; // 2026-07-27: END shadow-corner bug RESOLVED (FBO colour re-attach + END sun angle) — see memory

    /** DIAGNOSTIC (END position-dependent shadows): entity shadow-caster loop stats — drawn/culled counts + class
     * histogram + camera pos, ~1/second (read by SodiumWorldRenderer). Correlate with [shadow casters] section counts:
     * if SECTIONS swing with position/yaw the terrain map itself is the problem (camera-visible-set reuse / underground
     * cull), independent of the new entity loop. Also gates the [enderman probe]/[footprint]/PNG-dump instruments. */
    public static final boolean DIAG_ENTITY_SHADOW = false; // 2026-07-27: mob shadows RESOLVED (leash-hook FBO scramble — see memory); [ENT-STATE] state-diff instrument stays available here
    // Optional compile-time TAA_SMOOTHING override (pack default 3 = Medium; 4 = High = blurrier but less general grain).
    // Kept at the pack default (3) to match 1.20.1 out of the box. The colored-light flicker this was first reached for is
    // now fixed at its ROOT (LINEAR flood-fill in CustomImages — see that file), so no smoothing bump is needed. Raise to 4
    // only if the user wants less overall temporal grain. A value != 3 rewrites `#define TAA_SMOOTHING` (pack file untouched).
    private static final int TAA_SMOOTHING_OVERRIDE = 3;
    // Splits "the WSR reflection is black" (crosshair: colortex7 rgb=0 with a=1 = a HIT) into its three possible zeroes
    // — the atlas sample, faceData.glColor, or the lighting term — and reports them as numbers via colortex7 for the
    // crosshair log to print. See injectWsrColorDebug.
    // ANSWERED — the whole getWSR colour path is healthy and stays ruled out: a hit at the boundary measured
    // born=0.1059 -> afterFadeout=0.1059 -> afterFog=0.1059 (atlas 0.35-0.53, glColor 0.7961, lighting 0.1137). getWSR
    // hands composite a LIVE colour; composite.glsl:152 `reflection.rgb *= reflectColor` was zeroing it, because the
    // water's materialMask had been alpha-blended into the seabed's (see parseBlendDirectives). Off: it also adds
    // colortex13 to composite's RENDERTARGETS, which is not free.
    private static final boolean DIAG_WSR_COLOR = false;
    // Reads the pack's wsr_lod_img / wsr_img voxel volumes through a real usampler3D in OUR tonemap, the same way
    // voxelRayTrace does. Separates "the voxel data exists" (which glGetTexImage already proved) from "a shader can
    // actually sample it" — the one link left unverified while getWSR keeps returning vec4(0.0).
    private static final boolean DIAG_WSR_READ = false;
    // One-shot GL-truth report of which texture unit every sampler of every deferred/composite pass resolves to. Unlike
    // the PROVIDED_UNIFORMS-based logUnsetUniforms (whose hand-written list wrongly claimed textureAtlas was fed), this
    // reads the actual uniform values back from GL, so an unassigned sampler shows up as the unit-0 default it really is.
    private static final boolean DIAG_SAMPLER_BINDINGS = false;
    // A/B TEST: wipe the WSR voxel volume right after the shadow pass voxelized it, so every WSR ray misses and the
    // pack's reflections fall back to SSR + sky. Splits the "block textures in the wet-ground reflection" symptom in
    // two: textures GONE => they came from WSR; textures REMAIN => they came from SSR (colortex0 via depthtex1).
    private static final boolean DIAG_KILL_WSR = false;
    // Logs isEyeInWater + the raw isInWater/isInsideOfMaterial checks + the eye block, to see whether the eye-in-water flag
    // actually reaches 1 while fully submerged (the underwater tint not showing could be a flag or a composite issue).
    private static final boolean DIAG_EYE_WATER = false;
    /**
     * Underwater god rays. The pack builds them from the DIFFERENCE between shadowtex0 (all casters) and shadowtex1
     * (opaque only): "blocked in 0 but clear in 1" means a translucent — the water surface — blocked the light, and the
     * shaft then takes its colour from shadowcolor1 (volumetricLight.glsl:204-219; shadowSampling.glsl does the same for
     * coloured shadows). This port had shadowtex1 ALIASED to shadowtex0 and skipped water depth writes in the shadow
     * pass, so the difference was always zero and localDensity stayed 0 for every underwater ray sample — bisected with
     * DIAG_VL_FORCE_MULT (no fog) vs +DIAG_VL_FORCE_LIT (fog). Turning this off restores the old behaviour.
     *
     * <p>ATTEMPT 2 (re-enabled with DIAG_UW_SHADOW): attempt 1 "failed" with a black seabed and no shaft, but for most of
     * that test the opaque-depth snapshot was silently FAILING with GL_INVALID_OPERATION (wrong copy API), leaving
     * shadowtex1 at depth 0 = "everything opaque-shadowed" — which by itself produces exactly the observed black seabed
     * and dead shafts. The blit fix landed only at the very end. So the verdict was contaminated; this run measures the
     * real branch values via diagUnderwaterShadow before drawing any new conclusion.</p>
     */
    private static final boolean UNDERWATER_SHAFTS = true;
    // Paints the screen with the composite's depthtex1 (our R32F opaque-depth copy): BLUE = 1.0 (far/sky, correct — the
    // pack's SSR rejects it), RED = 0.0 (broken — SSR would accept a "hit" on every sky pixel and sample colortex0, i.e.
    // the screen's own image, into the wet-floor reflection), GREEN = real geometry depth.
    private static final boolean DIAG_SHOW_DEPTHTEX1 = false;
    // One-shot numeric readback of depthTex -> waterDepthTex -> depthtex1 (see RenderTargets#diagDepthChain), to locate
    // which link of the chain breaks instead of inferring it from the colour-coded screen.
    private static final boolean DIAG_DEPTH_CHAIN = false;
    // Floor the mip level the WSR reflection samples the block atlas at (via a sampler on TEXTURE_ATLAS_UNIT with
    // GL_TEXTURE_MIN_LOD). Diagnostics proved the reflection only ever reads the atlas BLOCK region (never items) — so the
    // "item textures" were actually correctly-reflected block textures. This blurs those reflected textures into a softer
    // sheen so the tiled block detail is no longer distracting. 0 = off (sharp, mip from lod); ~1.5–2.5 = progressively
    // softer. Tunable.
    // Currently 0 (off) while diagnosing the root cause: the blur is a cosmetic patch over wrongly-hit geometry, and it
    // would mask the DIAG_KILL_WSR A/B result. Re-raise only if a genuine softening is wanted after the fix.
    private static final float ATLAS_REFLECT_MIN_LOD = 0.0F;
    // Caps the first-person hand's reflection fresnel (via runHandReflectFix, after deferred1). deferred1 gives the wet
    // hand ~0.7, which mirrors sharp scene objects (mobs/blocks) onto it (looks see-through). 0.0 = fully matte, ~0.3 =
    // faint wet sheen without recognisable reflections, 1.0 = full (no cap). Tunable.
    private static final float HAND_REFLECT_MAX = 0.0F;
    private static final String TONEMAP_FSH =
            // 330 compatibility (not 120): still gives us gl_FragColor/texture2D/varying, but adds usampler3D so the
            // diagnostic below can texelFetch the pack's integer voxel volumes exactly the way its ray trace does.
            "#version 330 compatibility\n" +
            "uniform usampler3D wsrLodDiag;\n" +   // wsr_lod_img — the COARSE volume voxelRayTrace's outer loop tests
            "uniform usampler3D wsrDiag;\n" +      // wsr_img — the FINE volume
            "uniform float diagWsrRead;\n" +
            "uniform sampler2D colortex0;\n" +   // bound to colortex3 = the composites' finished, display-ready colour
            "uniform sampler2D depthtex0;\n" +   // bound to this.depthTex on unit 1 (the composite's depthtex0)
            "uniform sampler2D colortex4diag;\n" + // bound to front(4) on unit 2 — normal.rgb + reflection fresnel .a
            "uniform sampler2D depthtex1diag;\n" + // bound to opaqueDepthColorTexture() on unit 3 — the composite's depthtex1
            "uniform sampler2D colortex7diag;\n" +  // bound to front(7) on unit 4 — the composite's WSR reflectOutput
            "uniform sampler2D colortex6diag;\n" +  // bound to front(6) on unit 5 — smoothnessD/materialMask/skyLightFactor
            "uniform float diagHandDepth;\n" +
            "uniform float diagDepthtex1;\n" +
            "uniform float diagColortex7;\n" +
            "uniform float diagRefTemporal;\n" +
            "uniform float diagColortex4a;\n" +
            "uniform float diagHandMcbl;\n" +
            "varying vec2 texcoord;\n" +
            "void main() {\n" +
            // The no-final fallback / DIAG viewer: sample the composites' finished colour (bound on unit 0) and output
            // it unchanged. The pack's own final (sharpen/vignette/dither) is the normal display path.
            "    vec3 c = texture2D(colortex0, texcoord).rgb;\n" +
            "    if (diagHandDepth > 0.5) {\n" +
            // Show the hand's colortex4.a (the reflection fresnel composite1 keys on). For a matte hand it should be ~0
            // (BLACK) or NaN (BLUE) so composite1 skips it. If it's in (0,1) (RED→GREEN gradient) the hand is wrongly
            // reflective. Only recolour the near hand region (z0<0.1).
            "        float z0 = texture2D(depthtex0, texcoord).r;\n" +
            "        if (z0 <= 0.1) {\n" +
            "            float a = texture2D(colortex4diag, texcoord).a;\n" +
            "            if (a != a) c = vec3(0.0, 0.0, 1.0);\n" +           // NaN → BLUE
            "            else if (a < 0.002) c = vec3(0.0, 0.0, 0.0);\n" +   // ~0 → BLACK (skipped by composite)
            "            else c = vec3(a, 1.0 - a, 0.0);\n" +               // (0,1] → RED(1)…GREEN(0) gradient
            "        }\n" +
            "    }\n" +
            // Show the composite's depthtex1 (our R32F opaque-depth copy) — the ONLY thing gating the pack's SSR. The SSR
            // accepts a hit when `refPos.z < 1.0`, where refPos.z is the depthtex1 value at the last ray-march step. So a
            // sky pixel MUST read 1.0; if it reads anything less, every reflected ray that escapes to the sky is treated as
            // hitting geometry → the wet floor samples colortex0 (the screen) → its own block texture in its reflection.
            "    if (diagDepthtex1 > 0.5) {\n" +
            "        float z1 = texture2D(depthtex1diag, texcoord).r;\n" +
            "        if (z1 >= 0.9999) c = vec3(0.0, 0.0, 1.0);\n" +        // BLUE  = 1.0 far/sky (correct → SSR rejects)
            "        else if (z1 <= 0.0001) c = vec3(1.0, 0.0, 0.0);\n" +   // RED   = 0.0 (BROKEN → SSR accepts everywhere)
            "        else c = vec3(0.0, 1.0, 0.0) * (1.0 - pow(z1, 64.0));\n" + // GREEN = real geometry depth
            "    }\n" +
            // Show the composite's WSR output (colortex7 = reflectOutput). Its ALPHA is what composite1 blends the
            // world-space reflection in with on the water path (`mix(ssrReflection.rgb, compositeReflection.rgb,
            // compositeReflection.a)`), and getWSR returns vec4(0.0) on a miss — so alpha ~0 means the voxel ray hit
            // nothing and the water falls back to its own screen-space reflection, which is what cuts off at the edge.
            // Show colortex4.a — the pack calls it fresnelM, and composite.glsl gates its ENTIRE reflection block on
            // `if (fresnelM > 0.0)`. At 0 the pack never even calls GetReflection, so reflectOutput stays vec4(0.0) and
            // colortex7 is empty everywhere (sky, terrain and water alike) — which is what the all-red colortex7 view
            // actually showed. Note colortex4 is RGBA8_SNORM and our runHandReflectFix rewrites it full-screen.
            // Split view of the two links that decide whether the composite computes ANY reflection:
            //   TOP    = colortex6.r  = smoothnessD, written by gbuffers_terrain's gl_FragData[1]
            //   BOTTOM = colortex4.a  = fresnelM, written by deferred1 as sqrt(fresnelM * color.a)
            // deferred1 does `fresnelM = fresnelM * sqrt1(smoothnessD) - dither*0.01`, and sqrt1(0)==0 — so a zero
            // smoothnessD drives fresnelM negative, sqrt() of it is NaN, colortex4.a lands at 0, and composite.glsl's
            // `if (fresnelM > 0.0)` then skips GetReflection entirely. RED on TOP explains RED on BOTTOM.
            "    if (diagColortex4a > 0.5) {\n" +
            "        if (texcoord.y > 0.5) {\n" +
            "            float s = texture2D(colortex6diag, texcoord).r;\n" +
            "            if (s < 0.001) c = vec3(1.0, 0.0, 0.0);\n" +       // RED = smoothnessD 0 (terrain never wrote it)
            "            else c = vec3(0.0, s, 1.0 - s);\n" +
            "        } else {\n" +
            "            float a = texture2D(colortex4diag, texcoord).a;\n" +
            "            if (a != a) c = vec3(0.0, 0.0, 1.0);\n" +          // BLUE  = NaN
            "            else if (a < 0.0) c = vec3(1.0, 0.0, 1.0);\n" +    // MAGENTA = negative
            "            else if (a < 0.001) c = vec3(1.0, 0.0, 0.0);\n" +  // RED = 0 => composite skips reflections
            "            else c = vec3(0.0, a, 1.0 - a);\n" +
            "        }\n" +
            "    }\n" +
            // Sample the pack's voxel volumes from OUR OWN shader, the same way voxelRayTrace does
            // (`texelFetch(wsr_lod_sampler, ivec3(...), 0).r > 0u`). A CustomImages readback says wsr_lod_img holds
            // thousands of non-zero texels — but glGetTexImage ignores both sampler bindings and texture completeness,
            // so it cannot tell whether a SHADER can read them. This can: it renders a horizontal slice of each volume.
            //   TOP    = wsr_lod_img (64x16x64) at its mid Y layer
            //   BOTTOM = wsr_img     (256x64x256) at its mid Y layer
            // Structure (a map of the terrain around the player) => shaders can read the volumes, so the ray trace's
            // misses are a logic/geometry problem. All dark => the shader reads zeros the readback disagrees with, and
            // getWSR could never hit anything no matter how good the voxel data is.
            "    if (diagWsrRead > 0.5) {\n" +
            "        if (texcoord.y > 0.5) {\n" +
            "            ivec3 p = ivec3(texcoord.x * 64.0, 8, (texcoord.y - 0.5) * 2.0 * 64.0);\n" +
            "            uint v = texelFetch(wsrLodDiag, p, 0).r;\n" +
            "            c = v > 0u ? vec3(0.0, 1.0, 0.0) : vec3(0.15, 0.0, 0.0);\n" +
            "        } else {\n" +
            "            ivec3 p = ivec3(texcoord.x * 256.0, 32, texcoord.y * 2.0 * 256.0);\n" +
            "            uint v = texelFetch(wsrDiag, p, 0).r;\n" +
            "            c = v > 0u ? vec3(0.0, 0.0, 1.0) : vec3(0.15, 0.0, 0.0);\n" +
            "        }\n" +
            "    }\n" +
            // colortex7 = the composite's reflectOutput. With DIAG_WSR_TRACE injected, getWSR writes a TAG here instead
            // of a reflection (see injectWsrTraceDebug), so this shows exactly which door the ray trace left through.
            "    if (diagColortex7 > 0.5) {\n" +
            "        vec4 r = texture2D(colortex7diag, texcoord);\n" +
            "        if (r.a < 0.001) c = vec3(0.12);\n" +               // GREY  = the composite skipped its reflection block
            "        else if (r.a < 0.75) c = r.rgb * 0.5 + vec3(0.0, 0.0, 0.35);\n" + // DARKENED+BLUE TINT = ray exited
            "        else c = r.rgb;\n" +                               // the raw tag colour (r=hit, g=faceRej, b=colRej)
            "    }\n" +
            // The temporal-accumulator tag lives in colortex7.RGB while .a keeps the pack's real value (that buffer feeds
            // its own history, so alpha must not be touched — see injectRefTemporalDebug). Show rgb raw, ignore alpha.
            "    if (diagRefTemporal > 0.5) c = texture2D(colortex7diag, texcoord).rgb;\n" +
            // Hand colored-blocklight diag: the hand wrote (c10sum*10, lmCoord.x, retintDelta*3) into colortex6; show
            // it RAW at hand pixels (z0 <= 0.1), bypassing the whole composite chain that crushed the v1 paint.
            "    if (diagHandMcbl > 0.5) {\n" +
            "        float z0h = texture2D(depthtex0, texcoord).r;\n" +
            "        if (z0h <= 0.1) c = texture2D(colortex6diag, texcoord).rgb;\n" +
            "    }\n" +
            "    gl_FragColor = vec4(c, 1.0);\n" +
            "}\n";

    // DIAGNOSTIC: temporarily disable the depth test during the pack terrain passes. If the terrain (constant-red) then
    // appears in colortex0, the terrain was previously being killed by the depth test (sky/clear depth ordering); if it
    // still doesn't appear, the terrain isn't drawing into the world FBO at all.
    private static final boolean DIAG_TERRAIN_NODEPTH = false;
    // Count of terrain-pass begins this frame (should be ~2-3: solid/cutout/translucent) — 0 means Sodium drew no terrain.
    private int diagTerrainPassCount;

    // Shader-pack uniforms, gathered once per frame. Matrices are captured during terrain draw (GL matrices are the
    // world's there, not the hand's); world state in beginWorldRender.
    private final Matrix4f gbufferProjection = new Matrix4f();
    private final Matrix4f projectionInverse = new Matrix4f();
    private final Matrix4f gbufferModelView = new Matrix4f();
    private final Matrix4f modelViewInverse = new Matrix4f();

    // Previous frame's camera matrices + position, fed as gbufferPrevious*/previousCameraPosition so the pack's temporal
    // reprojection (motion blur, TAA) works: it maps a world point through this frame's inverse then last frame's matrices
    // to find where it was on screen. Feeding the CURRENT matrices here (as we did) makes the motion vector always zero →
    // motion blur/TAA misbehave. Updated once per frame in captureMatrices/gatherWorldState before the current values change.
    private final Matrix4f prevProjection = new Matrix4f();
    private final Matrix4f prevModelView = new Matrix4f();
    private double prevCameraX, prevCameraY, prevCameraZ;

    private float fogColorR = 0.6F, fogColorG = 0.7F, fogColorB = 0.85F;
    private float skyColorR = 0.5F, skyColorG = 0.6F, skyColorB = 0.85F;
    private float fogStart = 64.0F, fogEnd = 256.0F;
    private float near = 0.05F, far = 256.0F;

    private double cameraX, cameraY, cameraZ;
    private float celestialAngle;
    private float sunBrightness = 1.0F;
    private int worldTime;
    private int frameCounter;
    private float rainStrength;
    private float thunderStrength;
    // inRainy: 1 when the camera's biome has RAIN precipitation (not snow/none), else 0. Complementary's `inRainy` is an
    // OptiFine custom uniform (smooth(if(in(biome_precipitation,1),1,0),...)) we must supply ourselves — the rain-puddle
    // formation gates on it (puddleMixer = puddleLightFactor * inRainy * puddleNormalFactor), so left unset (0) the ground
    // never gets wet/reflective in the rain. Combined with wetness (=rainStrength) it drives whether it's actually raining.
    private float inRainy;
    private int isEyeInWater;
    // waterAltitude: the Y of the water surface above the eye. The pack's DARKER_DEPTH_OCEANS reads it (waterDepthStart =
    // waterAltitude + 10) to gauge how far below the surface the camera is. Left unset it defaulted to 0 → the depth term
    // pegged and no deep-water darkening happened. 61.9 (sea level) is the pack's own fallback when not submerged.
    private float waterAltitude = 61.9F;

    /**
     * OptiFine/Iris temporal smoothing (exponential, half-life based) — the mechanism behind both the {@code wetness}
     * uniform (driven by the pack's {@code const float wetnessHalflife/drynessHalflife}) and shaders.properties'
     * {@code smooth(<id>, <value>, <fadeUpTime>, <fadeDownTime>)} custom uniforms. After one half-life the remaining gap
     * to the target has halved, so the value eases in/out instead of snapping.
     *
     * <p>Our port fed the RAW {@code rainStrength} to rainFactor/wetness/inRainy, so every weather change hit the
     * shader instantly and the wet-ground reflection popped on/off. Rising and falling get separate half-lives (rain
     * arrives faster than the ground dries).</p>
     */
    private static final class Smoothed {
        private float value;
        private boolean primed;

        /** @param halfLifeUp/@param halfLifeDown half-life toward a rising/falling target, in the SAME unit as {@code dt}. */
        float update(float target, float dt, float halfLifeUp, float halfLifeDown) {
            if (!this.primed) { // first frame: adopt the target rather than easing up from 0
                this.value = target;
                this.primed = true;
                return this.value;
            }
            float halfLife = target > this.value ? halfLifeUp : halfLifeDown;
            if (halfLife <= 1.0e-4F || dt <= 0.0F) {
                this.value = target;
                return this.value;
            }
            float keep = (float) Math.pow(0.5, dt / halfLife);
            this.value = this.value * keep + target * (1.0F - keep);
            return this.value;
        }

        float value() {
            return this.value;
        }
    }

    private final Smoothed wetnessSmoother = new Smoothed();
    private final Smoothed rainFactorSmoother = new Smoothed();
    private final Smoothed thunderFactorSmoother = new Smoothed();
    private final Smoothed inRainySmoother = new Smoothed();
    // inDry/inSnowy: the other two biome_precipitation flags (0=none, 2=snow), same smooth(20,10) as inRainy. Left
    // unset the pack saw every biome as temperate — the snow-biome atmosphere and the desert (dry) tweaks never applied.
    private final Smoothed inDrySmoother = new Smoothed();
    private final Smoothed inSnowySmoother = new Smoothed();
    // eyeBrightnessM2 = smooth(eyeBrightness.y > 239 ? 1 : 0, 2, 2) — an "eye in FULL skylight" flag, not a rescale of
    // eyeBrightnessM (which is the continuous y/240). The pack declares it in shaders.properties like the others.
    private final Smoothed eyeBrightM2Smoother = new Smoothed();
    // eyeBrightnessM = smooth(eyeBrightness.y / 240, 5, 5) — the CONTINUOUS skylight factor the pack's cave/interior
    // adaptation keys on (our compile takes the OptiFine-fallback branches that derive isEyeInCave etc. from it).
    // Fed RAW it stepped per block at cave mouths where real Iris eases it over the declared fade.
    private final Smoothed eyeBrightMSmoother = new Smoothed();
    // eyeBrightnessSmooth (ivec2, OptiFine standard uniform): eyeBrightness eased with eyeBrightnessHalflife
    // (10 ticks = 0.5 s default; this pack does not override the directive). Was fed the raw value.
    private final Smoothed eyeSmoothXSmoother = new Smoothed();
    private final Smoothed eyeSmoothYSmoother = new Smoothed();
    // Smoothed values fed to the shader, recomputed ONCE per frame in gatherWorldState (setSceneUniforms runs ~15×/frame,
    // so smoothing there would advance the decay once per PASS and make the fade rate depend on the pass count).
    private float wetness;
    private float rainFactor;
    private float thunderFactor;
    private float inRainySmooth;
    private float inDry;            // raw target: eye biome has NO precipitation
    private float inSnowy;          // raw target: eye biome snows
    private float inDrySmooth;
    private float inSnowySmooth;
    private float eyeBrightnessM2;  // smoothed "eye in full skylight" flag
    private float lastSmoothSeconds = -1.0F;
    // The pack's `const float wetnessHalflife/drynessHalflife` (lib/pipelineSettings.glsl), in TICKS — OptiFine's units.
    // Parsed from the compiled program source at init; these are the OptiFine defaults until then.
    private float wetnessHalflifeTicks = 600.0F;
    private float drynessHalflifeTicks = 200.0F;
    // shaders.properties `smooth(<id>, <value>, <fadeUp>, <fadeDown>)` fade times, in SECONDS, read from the PREPROCESSED
    // properties (parseWeatherTimings) so the live #if branch decides — NOT the RAIN_FADE_TIMER option, which only applies
    // under `#if IRIS_VERSION >= 10800` (a branch we deliberately don't take). These drive the ATMOSPHERE, not the ground.
    private float rainFadeUpSeconds = 3.0F;
    private float rainFadeDownSeconds = 3.0F;
    private float thunderFadeUpSeconds = 3.0F;
    private float thunderFadeDownSeconds = 3.0F;
    private float inRainyFadeUpSeconds = 20.0F;
    private float inRainyFadeDownSeconds = 10.0F;
    private float inDryFadeUpSeconds = 20.0F;
    private float inDryFadeDownSeconds = 10.0F;
    private float inSnowyFadeUpSeconds = 20.0F;
    private float inSnowyFadeDownSeconds = 10.0F;
    private float eyeBrightM2FadeUpSeconds = 2.0F;
    private float eyeBrightM2FadeDownSeconds = 2.0F;
    private float eyeBrightMFadeUpSeconds = 5.0F;   // pack: smooth(4, eyeBrightness.y/240, 5, 5)
    private float eyeBrightMFadeDownSeconds = 5.0F;
    private float eyeBrightnessMSmoothed;           // smoothed continuous skylight factor (0..1)
    private float eyeBrightnessSmoothX = -1.0F;     // smoothed eyeBrightness (raw 0..240 scale); -1 = unseeded
    private float eyeBrightnessSmoothY = -1.0F;
    // isRightHanded (pack `uniform bool`, initializer true): fed from the main-hand setting so left-handed players get
    // the mirrored hand effects. lightningBoltPosition (vec4): camera-relative position of a LIVE lightning bolt entity,
    // w=1 while one is rendered — drives the pack's lightning flash on clouds/terrain; unset it never flashed.
    private int isRightHanded = 1;
    private final float[] lightningBoltPos = new float[4];
    // frameTimeCounter: sampled ONCE per frame (in beginWorldRender), not per-pass. Sampling it inside setSceneUniforms
    // (called ~15×/frame) made every pass in a frame see a slightly different, monotonically-increasing wall-clock time —
    // the cloud/water/waving animation reference could differ between the world pass and the deferred cloud pass within a
    // single frame. One value per frame keeps the whole frame temporally coherent (as Iris does).
    private float frameTimeCounter;

    // frameTime / frameTimeSmooth: seconds the last frame took (Iris uniforms). frameTimeSmooth is an exponentially
    // smoothed frameTime, which is what packs use to make a per-frame rate frame-rate-independent.
    //
    // Left unset (= 0.0) this was NOT a benign zero: Complementary's scene-aware light shafts step vlFactor at a fixed
    // wall-clock rate with `frameCounter % int(0.06666 / frameTimeSmooth + 0.5) == 0` — with frameTimeSmooth 0 that is
    // 0.06666/0 = +Inf, int(Inf) is undefined, and the modulo by it is an integer divide-by-zero (undefined in GLSL). So
    // the vlFactor update never ran correctly and the shaft intensity stayed pinned. Seeded to a sane 1/60s so the very
    // first frames divide by something real.
    private float frameTime = 1.0F / 60.0F;
    private float frameTimeSmooth = 1.0F / 60.0F;
    private long lastFrameNanos;

    // Extra Iris scene uniforms the pack's sky/atmosphere reads (were unset → 0 → collapsed clouds/glare/cave fog). Gathered
    // per frame in gatherWorldState. eyeBrightness is 0..240 (vanilla scale: light level 0..15 × 16); moonPhase 0..7.
    private int eyeBrightnessBlock, eyeBrightnessSky;
    // Block-atlas dimensions (the Iris `atlasSize` uniform): the WSR scene voxelization + reflection sampling
    // (reflectionVoxelization/worldSpaceRef) use it to map sprite UVs to texels. Queried once from the block atlas texture.
    private int atlasWidth, atlasHeight;
    private int moonPhase;
    private int worldDay;
    private float screenBrightness = 1.0F;
    private float nightVision, blindness, darknessFactor;

    private final float[] sunPos = {0.0F, 1.0F, 0.0F};
    private final float[] moonPos = {0.0F, -1.0F, 0.0F};
    private final float[] upPos = {0.0F, 1.0F, 0.0F};
    private final float[] shadowLightPos = {0.0F, 1.0F, 0.0F};

    // Shadow pass: a second terrain traversal from the sun's point of view into a depth map (shadowtex0), sampled by the
    // composite to darken shadowed ground. v1 reuses Sodium's camera-visible section set (so it shadows visible terrain —
    // e.g. a hill onto the valley in front of you; geometry just out of view doesn't cast). Only active in shader mode.
    private static final boolean SHADOWS_ENABLED = true;
    // FALLBACK only. The live values come from the pack's `shadowDistance` option via refreshPackShadowGeometry();
    // these are what we use when the pack declares no such option or declares a degenerate one. 192 was the profile
    // the pipeline was originally developed against, and hardcoding it is what put every shadow in the wrong place
    // once the slider moved (fixed 2026-08-02).
    private static final float SHADOW_DISTANCE_FALLBACK = 192.0F; // orthographic half-extent (blocks) around the camera
    // Complementary's shadow distortion bias: const float shadowMapBias = 1.0 - 25.6 / shadowDistance. The shadow-pass
    // vertex warp (TerrainShaderTransformer) must use the SAME value as the terrain lookup's GetShadowPos.
    private static final float SHADOW_MAP_BIAS_FALLBACK = 1.0F - 25.6F / SHADOW_DISTANCE_FALLBACK;

    // shadowPlayer = true: the player (+ vehicle) rendered into the shadow map through MC's fixed-function entity
    // renderer with THIS program bound. It applies the pack's radial distortion + z-squash AFTER the ortho matrices —
    // the reason a plain fixed-function attempt could never work: with bias 0.8667 the distortion magnifies the map
    // centre (where the player always is) ~7.5x, so undistorted depths land on entirely wrong texels.
    private static final String PLAYER_SHADOW_VSH =
            "#version 120\n" +
            "uniform float shadowMapBias;\n" +
            "varying vec2 texcoord;\n" +
            "varying vec4 vcolor;\n" +
            "void main() {\n" +
            "    vec4 clip = gl_ProjectionMatrix * (gl_ModelViewMatrix * gl_Vertex);\n" +
            "    float distb = length(clip.xy);\n" +
            "    float distortFactor = (1.0 - shadowMapBias) + distb * shadowMapBias;\n" +
            "    clip.xy /= distortFactor;\n" +
            "    clip.z *= 0.2;\n" +
            "    gl_Position = clip;\n" +
            "    texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).st;\n" +
            "    vcolor = gl_Color;\n" +
            "}\n";

    private static final String PLAYER_SHADOW_FSH =
            "#version 120\n" +
            "varying vec2 texcoord;\n" +
            "varying vec4 vcolor;\n" +
            "void main() {\n" +
            // NO alpha discard — parity with the pack: shadow.glsl never discards for ANY caster (entities included),
            // so real Iris entity shadows are full-geometry. Our old `tex.a * vcolor.a < 0.1` discard made entity
            // shadows depend on the resource pack's entity-texture alpha + whatever colour state the previous
            // entity's layers left — which is exactly how the END endermen lost their shadows while the (opaque-
            // skinned, first-in-list) player always kept his.
            // Opaque-caster shadowcolor values, matching the lightweight terrain shadow fragment's opaque branch: no
            // colour tint + the pack's SALS height encoding at "caster at camera height".
            "    gl_FragData[0] = vec4(0.0, 0.0, 0.0, 1.0);\n" +
            "    gl_FragData[1] = vec4(vec3(0.3), 0.25);\n" +
            "}\n";

    private GlslProgram playerShadowProgram;

    /** A/B lever kept from the END held-item flicker investigation: the flicker persisted with the player shadow off
     * (AND with the whole flood-fill dead), which is what finally pointed at the endFlashPosition NaN. Leave false. */
    private static final boolean DIAG_SHADOW_PLAYER_OFF = false;

    /**
     * True while a pack with {@code oldLighting = false} is active: the vanilla diffuse face shade (down 0.5 /
     * north-south 0.8 / east-west 0.6) must NOT be baked into the chunk vertex data — the pack applies its OWN
     * directional shading (DoLighting's directionShade), and baking both DOUBLE-shaded down/side faces. Found via
     * the END island "white rim": pixel-compared against real 1.20.1 Iris, our island undersides were 31% darker
     * while the sky matched exactly. Consumed by the light pipelines during meshing (worker threads — reads two
     * booleans, safe); a renderer reload re-meshes when the pack/toggle changes.
     */
    public boolean stripVanillaFaceShade() {
        return this.enabled && !this.packOldLighting;
    }

    /** True when the pack asked for the player's own shadow ({@code shadowPlayer = true}) and the program compiled. */
    public boolean shadowPlayerEnabled() {
        return !DIAG_SHADOW_PLAYER_OFF && this.enabled && this.packShadowPlayer && this.playerShadowProgram != null;
    }

    /** True when the pack wants ALL entities as shadow casters ({@code shadowEntities} — Iris default true;
     * Complementary turns it on via the ENTITY_SHADOW option >= 1). They render through the same
     * distortion-matching program as the player ({@link #usePlayerShadowProgram}). */
    public boolean shadowEntitiesEnabled() {
        return !DIAG_SHADOW_PLAYER_OFF && this.enabled && this.packShadowEntities && this.playerShadowProgram != null;
    }

    /**
     * TESR block entities — chests, beds, skulls, banners, signs, shulker boxes, the spawner's mini mob — cast
     * shadows through the same distortion program as the entity casters, when the pack's {@code shadowBlockEntities}
     * asks for it. With Complementary that means ENTITY_SHADOW = 2 ("完全"); below that the pack emits false and
     * block entities do not cast.
     *
     * <p>This USED to ignore the directive and return true whenever the player-shadow program compiled, chasing
     * visual parity with a reference Iris 1.7.6 client that demonstrably kept drawing block-entity shadows at
     * ENTITY_SHADOW=-1 (measured 2026-07-27). That parity was not worth its cost: honouring a directive the pack
     * did not give means invoking arbitrary third-party TESR renderers from the light's point of view inside the
     * bound 4096² shadow framebuffer, in configurations the user never asked for. Bisected 2026-08-03 in the
     * Betweenlands, that loop alone made the screen flash black continuously until the client died — and six of that
     * mod's TESRs throw outright when rendered this way. Matching an observed quirk of another client is not worth
     * running a hazardous loop the pack explicitly declined.</p>
     */
    public boolean shadowBlockEntitiesEnabled() {
        return !DIAG_SHADOW_PLAYER_OFF && this.enabled && this.packShadowBlockEntities
                && this.playerShadowProgram != null;
    }

    /** The shadow ortho half-extent in blocks — the entity shadow caster cull radius must match the box. LIVE value
     * from the pack's shadowDistance slider (see {@link #refreshPackShadowGeometry()}), not a constant. */
    public static float shadowDistanceBlocks() {
        return instance().shadowDistanceThisFrame;
    }

    /** @return the shadow map edge length in texels (F3 debug), or 0 while the pipeline has no targets. */
    public int shadowMapSize() {
        return this.targets == null ? 0 : this.targets.shadowSize();
    }

    /** @return the pack's radial shadow distortion bias (F3 debug) — the LIVE value the entity shadow program uses,
     * so a mismatch against the pack is visible in F3 rather than only on screen. */
    public float shadowMapBias() {
        return this.shadowMapBiasThisFrame;
    }

    /** Re-establishes the shadow FBO's binding/attachments/drawBuffers/viewport mid-pass (no clear) — called by the
     * entity shadow-caster loop so one renderer scrambling the FBO cannot poison the rest of the pass. See
     * {@link RenderTargets#reassertShadowAttachments}. No-op outside the shadow pass. */
    public void reassertShadowFboState() {
        if (this.targets != null && this.shadowPass) {
            this.targets.reassertShadowAttachments();
        }
    }

    /** Binds the distortion-matching player-shadow program (diffuse on unit 0) for the fixed-function entity render.
     * The bias MUST come from the pack's live shadowDistance, not the built-in profile constant — see
     * {@link #refreshPackShadowGeometry()}; a mismatch here is what puts entity shadows in the wrong place at the
     * wrong size while terrain shadows stay correct. Sampled once per frame in beginWorldRender. */
    public void usePlayerShadowProgram() {
        this.playerShadowProgram.use();
        this.playerShadowProgram.setInt("tex", 0);
        this.playerShadowProgram.setFloat("shadowMapBias", this.shadowMapBiasThisFrame);
    }

    /** The pack's shadow geometry, sampled once per frame by {@link #refreshPackShadowGeometry()} — the consumers run
     * per draw, per caster and per section. Initialised to the built-in profile so anything that runs before the
     * first refresh (or with no pack options loaded) behaves exactly as the old hardcoded constants did. */
    private float shadowMapBiasThisFrame = SHADOW_MAP_BIAS_FALLBACK;
    private float shadowDistanceThisFrame = SHADOW_DISTANCE_FALLBACK;

    private final Matrix4f shadowProjection = new Matrix4f();
    private final Matrix4f shadowModelView = new Matrix4f();
    private final Matrix4f shadowModelViewProjection = new Matrix4f();
    // Inverses of the shadow view/projection, fed as the standard Iris shadowModelViewInverse/shadowProjectionInverse
    // uniforms. The pack's own shadow.vsh reconstructs the camera-relative world position via
    // shadowModelViewInverse * shadowProjectionInverse * ftransform() (ftransform = shadowProjection·shadowModelView·vertex
    // during the shadow pass), so both inverses must be set for the lightweight shadow shader's positions to be correct.
    // Only the shadow program reads these (composite/terrain use shadowModelView/Projection directly), so setting them is
    // additive — it doesn't change the existing (working) composite shadow lookup.
    private final Matrix4f shadowModelViewInverse = new Matrix4f();
    private final Matrix4f shadowProjectionInverse = new Matrix4f();
    private volatile boolean shadowPass;

    // Handheld light (the pack's own "dynamic lights"): Complementary's GetHeldLighting fades heldBlockLightValue by
    // distance from the player's eye and colours it by heldItemId, giving a COLOURED light that needs no chunk re-mesh.
    // All four uniforms were unset (they showed up in the [UNIFORM MISS] report for composite/composite5), so a held sea
    // lantern lit nothing at all.
    private com.yumelium.yumelium.shaders.pack.ItemIdMapper itemIdMapper;
    /** entity.properties → the {@code entityId} uniform (per-entity render hook); null if the pack ships none. */
    private com.yumelium.yumelium.shaders.pack.EntityIdMapper entityIdMapper;
    private int heldBlockLightValue;
    private int heldBlockLightValue2;
    private int heldItemId;
    private int heldItemId2;
    /** cameraPosition − eyePosition. See gatherHeldLight for why it is not zero in this port. */
    private final Vector3f relativeEyePosition = new Vector3f();

    /**
     * Reads the held items into the pack's handheld-light uniforms.
     *
     * <p>{@code relativeEyePosition} deserves a note. The pack computes the light's reach as
     * {@code playerPosLightM = playerPos + relativeEyePosition}, i.e. it wants the surface position measured from the
     * player's EYE. {@code playerPos} is relative to {@code cameraPosition}, and in real Iris those coincide, so the
     * uniform is ~0 there. This port deliberately keeps {@code cameraPosition.y} at FEET height (Sodium's geometry is
     * feet-relative — see the camera-position note above), so here the difference is real: feet − eye = (0, −eyeHeight, 0).
     * Feeding 0 instead would put the held light at the player's feet.</p>
     */
    private void gatherHeldLight(Entity view) {
        this.heldBlockLightValue = 0;
        this.heldBlockLightValue2 = 0;
        this.heldItemId = 0;
        this.heldItemId2 = 0;
        this.relativeEyePosition.set(0.0F, 0.0F, 0.0F);
        if (!(view instanceof EntityLivingBase)) {
            return;
        }
        EntityLivingBase living = (EntityLivingBase) view;
        net.minecraft.item.ItemStack main = living.getHeldItemMainhand();
        net.minecraft.item.ItemStack off = living.getHeldItemOffhand();
        this.heldBlockLightValue = com.yumelium.yumelium.shaders.pack.ItemIdMapper.heldLightValue(main);
        this.heldBlockLightValue2 = com.yumelium.yumelium.shaders.pack.ItemIdMapper.heldLightValue(off);
        if (this.itemIdMapper != null) {
            this.heldItemId = this.itemIdMapper.idFor(main);
            this.heldItemId2 = this.itemIdMapper.idFor(off);
        }
        this.relativeEyePosition.set(0.0F, -view.getEyeHeight(), 0.0F);
    }

    /** GPU wall-clock per phase — the only honest way to pick what to optimise. See GpuProfiler. */
    private final com.yumelium.yumelium.shaders.gl.GpuProfiler profiler = new com.yumelium.yumelium.shaders.gl.GpuProfiler();

    /**
     * Close the phase currently being timed and open {@code name}. The ONLY way to sub-divide a phase: GL_TIME_ELAPSED
     * queries cannot nest, and {@link com.yumelium.yumelium.shaders.gl.GpuProfiler#begin} silently ignores a nested
     * call — so a plain begin inside a live phase measures nothing and looks like the phase costs zero. Re-entering a
     * name later in the same frame is fine; the profiler sums repeated phases per frame.
     */
    public void profileSwitch(String name) {
        this.profiler.end();
        this.profiler.begin(name);
    }
    // Logs the GPU time breakdown (shadow / gbuffers / composite) once a second. Cheap: the queries are read back several
    // frames late, so nothing stalls. Off for clean logs; flip on when profiling where the frame time actually goes.
    // Was ON 2026-08-03 to attribute the entity cost; that question is answered (see the entity-performance notes in
    // CLAUDE.md), so back off for clean logs. The phases and the CPU counters below stay — flip this to re-measure.
    private static final boolean DIAG_GPU_TIME = false;

    // --- CPU-side entity timing -----------------------------------------------------------------------------------
    // GPU timer queries cannot see CPU cost, and the 2026-08-03 measurements showed the frame is often CPU-bound:
    // GPU total ~13 ms while cpuFrame hit 28 ms. The GPU side of the camera entity pass turned out to be fragment
    // cost (2x the entity count multiplied GPU time ~40x — superlinear, i.e. overdraw, not per-entity overhead), so
    // whatever is left to win is on the CPU and needs its own clock. Wall-clock nanoTime, summed per frame across
    // BOTH Forge render passes, reset in beginWorldRender.
    private long cpuCameraEntitiesNanos;
    private long cpuShadowEntitiesNanos;
    private int cpuCameraEntitiesCalls;

    public long entityCpuMark() {
        return System.nanoTime();
    }

    public void addCameraEntityCpu(long startNanos) {
        this.cpuCameraEntitiesNanos += System.nanoTime() - startNanos;
        this.cpuCameraEntitiesCalls++;
    }

    public void addShadowEntityCpu(long startNanos) {
        this.cpuShadowEntitiesNanos += System.nanoTime() - startNanos;
    }

    // Sub-breakdown of the shadow entity pass. It measured ~7 ms of CPU while drawing ONE entity and costing 0.00 ms
    // of GPU, and the figure did not move with the entity count (1182 vs 2300 entities: identical), so it is a fixed
    // per-frame cost, not a per-entity one — i.e. every shader user pays it always. These three split it into the
    // one-off state setup, the actual caster draws, and the block-entity sweep.
    private long shadowEntSetupNanos;
    private long shadowEntDrawNanos;
    private long shadowEntTesrNanos;

    public void addShadowEntSetup(long t) {
        this.shadowEntSetupNanos += System.nanoTime() - t;
    }

    public void addShadowEntDraw(long t) {
        this.shadowEntDrawNanos += System.nanoTime() - t;
    }

    public void addShadowEntTesr(long t) {
        this.shadowEntTesrNanos += System.nanoTime() - t;
    }

    /** Entity counts for the GPU TIME line: shadow casters actually drawn, and the leash-gate tallies. */
    private String entityDiagSuffix() {
        try {
            me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer r =
                    me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer.getInstanceNullable();
            if (r == null) {
                return "";
            }
            return " | shadowEntities=" + r.getShadowEntitiesDrawn() + " (culled=" + r.getShadowEntitiesCulled() + ")"
                    + " shadowTESR=" + r.getShadowTesrDrawn()
                    + " basicBrackets=" + this.basicBracketsRun + "/" + this.basicBracketsSkipped + " (run/skipped)"
                    + String.format(" | CPU camEnt=%.2fms (x%d passes) shadowEnt=%.2fms"
                                    + " [setup=%.2f draw=%.2f tesr=%.2f]",
                            this.cpuCameraEntitiesNanos / 1.0e6, this.cpuCameraEntitiesCalls,
                            this.cpuShadowEntitiesNanos / 1.0e6,
                            this.shadowEntSetupNanos / 1.0e6, this.shadowEntDrawNanos / 1.0e6,
                            this.shadowEntTesrNanos / 1.0e6);
        } catch (Throwable t) {
            return "";
        }
    }
    /** Sections the last shadow-list build selected / dropped as buried (reported next to the GPU timings). */
    private int lastShadowSections;
    private int lastShadowCulled;
    private int shadowDiagFrame;  // DIAGNOSTIC: shadow-pass frame counter for periodic readback logging
    private int shadowDiagLogs;   // DIAGNOSTIC: number of readback snapshots logged so far

    private long startNanos;

    private IrisPipeline() {
    }

    public static IrisPipeline instance() {
        return INSTANCE;
    }

    /** Pipeline INFO chatter — debug-gated (yumelium_plus → debug_logging). Warnings/errors in this file go through
     * {@code SodiumClientMod.logger().warn/error} directly and are NEVER gated. */
    private static void log(String s) {
        if (SodiumClientMod.debugLogs()) {
            SodiumClientMod.logger().info("[Iris] " + s);
        }
    }

    /** Every uniform name the pipeline actually feeds (setSceneUniforms + setCompositeSamplers + sky/hand/item setters).
     * Compared against a program's active uniforms to report exactly what a pack program wants but we leave unset. */
    private static final java.util.Set<String> PROVIDED_UNIFORMS = new java.util.HashSet<>();
    static {
        String[] names = {
            "gbufferProjection", "gbufferProjectionInverse", "gbufferModelView", "gbufferModelViewInverse",
            "gbufferPreviousProjection", "gbufferPreviousModelView",
            "shadowProjection", "shadowModelView", "shadowModelViewProjection",
            "shadowModelViewInverse", "shadowProjectionInverse",
            "cameraPosition", "previousCameraPosition", "sunPosition", "moonPosition", "upPosition", "shadowLightPosition",
            "sunAngle", "shadowAngle", "sunBrightness",
            "worldTime", "worldDay", "moonPhase", "frameCounter", "framemod8", "framemod2", "frameTimeCounter",
            "frameTime", "frameTimeSmooth", "centerDepthSmooth",
            "heldBlockLightValue", "heldBlockLightValue2", "heldItemId", "heldItemId2", "relativeEyePosition",
            "entityId", "entityColor", "blockEntityId", "currentRenderedItemId",
            "rainStrength", "rainFactor", "wetness", "inRainy", "inDry", "inSnowy", "thunderStrength", "thunderFactor",
            "inNetherWastes", "inCrimsonForest", "inWarpedForest", "inBasaltDeltas", "inSoulValley", "inVanillaEnd",
            "endFlashPosition", "endFlashIntensityM",
            "isEyeInWater", "waterAltitude", "lightningBoltPosition",
            "eyeBrightness", "eyeBrightnessSmooth", "eyeBrightnessM", "eyeBrightnessM2", "eyeAltitude", "bedrockLevel",
            "atlasSize", "cameraPositionFract", "cloudHeight", "darknessLightFactor", "isRightHanded",
            "screenBrightness", "nightVision", "blindness", "darknessFactor", "maxBlindnessDarkness",
            "viewWidth", "viewHeight", "aspectRatio", "near", "far",
            "fogColor", "skyColor", "fogStart", "fogEnd",
            "gcolor", "gnormal", "composite", "gaux1", "gaux2", "gaux3", "gaux4",
            "depthtex0", "depthtex1", "depthtex2", "depthtex", "gdepth",
            "shadowtex0", "shadowtex1", "shadow", "watershadow", "shadowcolor0", "shadowcolor1",
            "noisetex", "gtexture", "tex", "lightmap", "renderStage", "yl_ItemLightDir0", "yl_ItemLightDir1", "textureAtlas",
            "specular", "normals",
        };
        java.util.Collections.addAll(PROVIDED_UNIFORMS, names);
        for (int i = 0; i < RenderTargets.NUM_COLORTEX; i++) {
            PROVIDED_UNIFORMS.add("colortex" + i);
        }
    }

    // One-shot compile-time report of the uniforms each pack program uses that the pipeline never sets. Development aid
    // only — the misses it prints for the current pack are the known/benign ones — so it is off for clean logs; flip on
    // when bringing up a NEW pack or hunting a black/wrong-output program.
    private static final boolean DIAG_UNIFORM_REPORT = false;

    /** @return {@code "label (N unset): a, b, c"} when the program has active uniforms the pipeline never sets (they
     * read as 0 → wrong output), or {@code null} when everything is fed. Shared by the compile-time report and the
     * health report. */
    private static String unsetUniformLine(String label, GlslProgram p) {
        if (p == null) {
            return null;
        }
        java.util.List<String> misses = new java.util.ArrayList<>();
        for (String name : p.activeUniforms()) {
            String base = name.endsWith("[0]") ? name.substring(0, name.length() - 3) : name;
            if (base.startsWith("gl_") || PROVIDED_UNIFORMS.contains(base)) {
                continue; // fixed-function builtin (compat profile provides it) or already fed
            }
            misses.add(base);
        }
        if (misses.isEmpty()) {
            return null;
        }
        java.util.Collections.sort(misses);
        return label + " (" + misses.size() + " unset): " + String.join(", ", misses);
    }

    /** Logs the ACTIVE uniforms a compiled pack program uses that the pipeline never sets (they read as 0 → wrong output).
     * This is the rigorous "where our Iris port silently fails" report — especially for the sky/atmosphere programs. */
    private void logUnsetUniforms(String label, GlslProgram p) {
        if (!DIAG_UNIFORM_REPORT) {
            return;
        }
        String line = unsetUniformLine(label, p);
        if (line != null) {
            log("[UNIFORM MISS] " + line);
        }
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    /** @return true once the G-buffer render targets are allocated (so per-draw uniform/shadow feeding is safe). */
    public boolean hasTargets() {
        return this.targets != null;
    }

    /**
     * @return true while this frame's world geometry is being captured into the pipeline G-buffer (our FBO is bound,
     * between {@link #beginWorldRender} and {@link #endWorldRender}). The hand-depth-clear redirect uses this to keep the
     * opaque terrain/entity depth in {@code depthtex0} for the composite fog (MC clears depth before the first-person
     * hand; on our shared G-buffer that would wipe the scene depth the composite needs — leaving only the hand's).
     */
    public boolean isCapturingWorld() {
        return this.activeThisFrame;
    }

    public String activePackName() {
        return this.activePackName;
    }

    /** Opens the active pack scanned for the CURRENT dimension's program folder. Every pipeline-side pack (re)load
     * goes through here so a dimension switch (which changes {@link #worldFolder}) is honoured everywhere. */
    private ShaderPack loadActivePack() {
        return ShaderPackManager.instance().load(this.activePackName, this.worldFolder);
    }

    /** The pack program folder for the player's current dimension, per Complementary's {@code dimension.properties}:
     * nether (-1) → {@code world-1/}, end (1) → {@code world1/}, everything else → the {@code world0 = *} catch-all. */
    /**
     * Whether the pack's OWN clouds must be switched off for the current dimension.
     *
     * <p>The Betweenlands is an enclosed swamp dimension with its own sky and ceiling; a drifting cloud layer over it
     * is wrong. Suppressing the host cloud renderer is not enough, because with a shader pack active the clouds come
     * from the PACK — Complementary declares {@code clouds = off} precisely so it can draw its own.</p>
     *
     * <p>The pack cannot do this itself: its {@code dimension.properties} routes every unknown dimension to
     * {@code world0 = *}, so the Betweenlands gets the overworld programs verbatim. Matched on the provider's CLASS
     * NAME rather than a dimension id, which is user-configurable, and which also avoids loading the mod's types
     * (the Cleanroom classloader invariant — see BetweenlandsCompat).</p>
     */
    private static boolean suppressPackCloudsForDimension(Minecraft mc) {
        return mc.world != null && mc.world.provider != null
                && mc.world.provider.getClass().getName().startsWith("thebetweenlands.");
    }

    /** Whether the CURRENT compiled pipeline was built with the pack's clouds forced off. Part of the recompile key
     * alongside {@link #worldFolder}: the option is baked in as a {@code #define}, so it cannot change mid-pipeline. */
    private boolean packCloudsSuppressed;

    private static String dimensionFolder(Minecraft mc) {
        if (mc.world == null) {
            return com.yumelium.yumelium.shaders.pack.ProgramSet.OVERWORLD_FOLDER;
        }
        int dim = mc.world.provider.getDimension();
        if (dim == -1) {
            return "world-1/";
        }
        if (dim == 1) {
            return "world1/";
        }
        return com.yumelium.yumelium.shaders.pack.ProgramSet.OVERWORLD_FOLDER;
    }

    /**
     * Selects a shader pack (the built-in or an external one from {@code shaderpacks/}). Drops the compiled pipeline so
     * it recompiles from the new pack on the next frame; the caller should also reload the renderer so the terrain
     * program is rebuilt from the new pack.
     */
    public void setActivePack(String name) {
        if (name == null || name.equals(this.activePackName)) {
            return;
        }
        this.activePackName = name;
        this.shaderOptions = null; // the new pack has its own options
        this.shaderOptionsLoaded = false;
        destroy();
    }

    /**
     * @return the active pack's configurable options (discovered lazily + loaded from disk). Used by the "Shader Pack
     * Settings" screen and applied to every shader source before compilation.
     */
    public ShaderOptionSet shaderOptions() {
        if (!this.shaderOptionsLoaded) {
            this.shaderOptionsLoaded = true;
            ShaderOptionSet set = new ShaderOptionSet();
            try (ShaderPack pack = loadActivePack()) {
                if (pack != null) {
                    set = ShaderOptionSet.discover(pack, Minecraft.getMinecraft().gameSettings.language);
                    set.load(optionsFile(this.activePackName));
                }
            } catch (Throwable t) {
                SodiumClientMod.logger().error("[Iris] loading shader options failed", t);
            }
            this.shaderOptions = set;
        }
        return this.shaderOptions;
    }

    /** Persists the active pack's options + recompiles the pipeline so the new {@code #define} values take effect. */
    public void applyOptionChanges() {
        shaderOptions().save(optionsFile(this.activePackName)); // write to disk FIRST — destroy() below clears the in-memory
        destroy();                                              // options, so the recompile re-reads these values from the file.
    }

    private static java.nio.file.Path optionsFile(String packName) {
        // Match OptiFine/Iris exactly (the pack name + ".txt", verbatim) so we read AND write the SAME settings file the
        // pack already uses — a folder/zip pack name is always a valid filename, and the built-in "(built-in)" is too.
        // (Sanitizing the name broke this: it wrote to a mangled file and never loaded the pack's real saved settings.)
        return ShaderPackManager.instance().shaderpacksDir().resolve(packName + ".txt");
    }

    /**
     * The standard Iris preprocessor defines a real pack expects. Injecting {@code IS_IRIS} makes packs (Complementary +
     * Euphoria Patches) treat us as the Iris loader — which stops their many "this feature is not supported on OptiFine,
     * switch to Iris" full-screen error overlays (each Iris-gated feature errors otherwise). MC_VERSION + the MC_* values
     * feed version/quality gates. We deliberately do NOT define {@code IRIS_FEATURE_CUSTOM_IMAGES} (colored lighting's
     * voxel image load/store) since we don't implement it yet — so colored lighting stays internally off.
     */
    private static final String IRIS_DEFINES =
            "#define MC_VERSION 11202\n" +
            "#define IS_IRIS\n" +
            // Custom images (image load/store) are now implemented (CustomImages), so advertise the Iris feature flag that
            // gates the pack's Colored Lighting + World Space Reflections voxel subsystem. Harmless when COLORED_LIGHTING=0
            // (the pack keeps that code compiled out); it activates the voxel/floodfill/wsr paths once the option is > 0.
            "#define IRIS_FEATURE_CUSTOM_IMAGES\n" +
            "#define MC_OS_WINDOWS\n" +
            "#define MC_GL_VERSION 460\n" +
            "#define MC_GLSL_VERSION 330\n" +
            "#define MC_RENDER_QUALITY 1.0\n" +
            "#define MC_SHADOW_QUALITY 1.0\n" +
            "#define MC_HAND_DEPTH 0.125\n" +
            "#define MC_GL_VENDOR_NVIDIA\n" +
            "#define MC_GL_RENDERER_GEFORCE\n" +
            // Iris render-stage enum (the `renderStage` uniform is compared against these). Packs reference the constants
            // at compile time (e.g. gbuffers_skytextured checks MC_RENDER_STAGE_SUN/MOON), so they must all be defined.
            "#define MC_RENDER_STAGE_NONE 0\n" +
            "#define MC_RENDER_STAGE_SKY 1\n" +
            "#define MC_RENDER_STAGE_SUNSET 2\n" +
            "#define MC_RENDER_STAGE_CUSTOM_SKY 3\n" +
            "#define MC_RENDER_STAGE_SUN 4\n" +
            "#define MC_RENDER_STAGE_MOON 5\n" +
            "#define MC_RENDER_STAGE_STARS 6\n" +
            "#define MC_RENDER_STAGE_VOID 7\n" +
            "#define MC_RENDER_STAGE_TERRAIN_SOLID 8\n" +
            "#define MC_RENDER_STAGE_TERRAIN_CUTOUT_MIPPED 9\n" +
            "#define MC_RENDER_STAGE_TERRAIN_CUTOUT 10\n" +
            "#define MC_RENDER_STAGE_ENTITIES 11\n" +
            "#define MC_RENDER_STAGE_BLOCK_ENTITIES 12\n" +
            "#define MC_RENDER_STAGE_DESTROY 13\n" +
            "#define MC_RENDER_STAGE_OUTLINE 14\n" +
            "#define MC_RENDER_STAGE_DEBUG 15\n" +
            "#define MC_RENDER_STAGE_HAND_SOLID 16\n" +
            "#define MC_RENDER_STAGE_TERRAIN_TRANSLUCENT 17\n" +
            "#define MC_RENDER_STAGE_TRIPWIRE 18\n" +
            "#define MC_RENDER_STAGE_PARTICLES 19\n" +
            "#define MC_RENDER_STAGE_CLOUDS 20\n" +
            "#define MC_RENDER_STAGE_RAIN_SNOW 21\n" +
            "#define MC_RENDER_STAGE_WORLD_BORDER 22\n" +
            "#define MC_RENDER_STAGE_HAND_TRANSLUCENT 23\n";

    private static final java.util.regex.Pattern VERSION_LINE =
            java.util.regex.Pattern.compile("(?m)^\\s*#version[^\\n]*\\n");

    /** Inserts the Iris preprocessor defines right after the source's {@code #version} directive (defines must follow it). */
    private static String injectIrisDefines(String source) {
        if (source == null) {
            return null;
        }
        java.util.regex.Matcher m = VERSION_LINE.matcher(source);
        if (m.find()) {
            return source.substring(0, m.end()) + IRIS_DEFINES + source.substring(m.end());
        }
        return IRIS_DEFINES + source;
    }

    /** Matches the pack's cloud-style option define, whatever value the option screen last wrote into it. */
    private static final java.util.regex.Pattern CLOUD_STYLE_DEFINE =
            java.util.regex.Pattern.compile("#define\\s+CLOUD_STYLE_DEFINE\\s+-?\\d+");

    /**
     * Forces the pack's own clouds off in dimensions that must not have them (see
     * {@link #suppressPackCloudsForDimension}). Pure {@code #define} rewrite — the pack on disk is untouched, and the
     * option screen still shows/stores the player's real Cloud Style for every other dimension.
     *
     * <p>Complementary derives everything cloud-related from this one option:
     * {@code CLOUD_STYLE_DEFINE == -1} means "use the profile default", any other value is the style itself, and 0 is
     * the option screen's OFF entry ({@code value.CLOUD_STYLE_DEFINE.0=OFF}). With it at 0, {@code CLOUD_STYLE} is 0,
     * so {@code VL_CLOUDS_ACTIVE}, {@code CLOUDS_UNBOUND} and {@code CLOUDS_REIMAGINED} are all left undefined and the
     * sky/volumetric cloud code compiles out; {@code gbuffers_clouds} already discards for any value except 50.</p>
     */
    private String forcePackCloudsOff(String source) {
        if (source == null || !this.packCloudsSuppressed) {
            return source;
        }
        return CLOUD_STYLE_DEFINE.matcher(source).replaceAll("#define CLOUD_STYLE_DEFINE 0");
    }

    /** Applies the active pack's option {@code #define} overrides + the Iris preprocessor defines before compilation. */
    private String applyOptions(String source) {
        if (source == null) {
            return null;
        }
        String s = injectVlForceRayEnd(injectShaftFloorFix(injectShowVolumetricLight(injectVlBranchColors(injectHandDepthFix(injectVolumetricLightDebug(injectWsrColorOutput(
                injectWsrColorDebug(injectRefTemporalDebug(injectWsrTraceDebug(injectIrisDefines(shaderOptions().apply(source))))))))))));
        s = forcePackCloudsOff(s);
        // Raise the pack's Temporal Smoothing to reduce the variance-clamp flicker on bright colored light sources (see
        // TAA_SMOOTHING_OVERRIDE). Pure #define rewrite — the pack's common.glsl on disk is untouched.
        if (TAA_SMOOTHING_OVERRIDE != 3) {
            s = s.replaceAll("#define\\s+TAA_SMOOTHING\\s+\\d+", "#define TAA_SMOOTHING " + TAA_SMOOTHING_OVERRIDE);
        }
        // A/B DIAG: only matches the ClipAABB call inlined into composite6's taa.glsl. See DIAG_TAA_NOCLAMP.
        if (DIAG_TAA_NOCLAMP) {
            s = s.replace("tempColor = ClipAABB(tempColor, minclr, maxclr);", "/* [diag] TAA clamp disabled */");
        }
        return s;
    }

    /**
     * DIAGNOSTIC (source transform, pack files untouched): finds WHICH factor zeroes the WSR reflection's colour.
     *
     * <p>The crosshair readback showed every water pixel taking getWSR's early return with
     * {@code colortex7 = (0,0,0)} and {@code a} flipping between 0 and 1 — i.e. the voxel ray HITS (a=1) but
     * getShadedReflection produces black. composite1 then does
     * {@code combinedRef = mix(ssr.rgb, compositeReflection.rgb, compositeReflection.a)} after having already run
     * {@code color -= ssr.rgb}, so a black WSR with a=1 DELETES the reflection — the boundary is exactly the a=0↔1 line.</p>
     *
     * <p>getShadedReflection's colour is {@code color.rgb * lighting + maRecolor} where
     * {@code color = texture2DLod(textureAtlas, ...) * vec4(faceData.glColor, 1.0)}. Note the alpha survives a zeroed
     * glColor (it is multiplied by a literal 1.0), which is exactly the rgb=0/a=1 signature. Three candidates, so
     * report all three as NUMBERS through colortex7 and let the crosshair log name the zero:</p>
     * <ul>
     *   <li>{@code r} = luminance of the RAW atlas sample — 0 ⇒ textureAtlas is unbound/black ([[unbound-sampler]])</li>
     *   <li>{@code g} = luminance of {@code faceData.glColor} — 0 ⇒ the SSBO's per-face vertex colour is zero, i.e. our
     *       shadow-pass voxelizer never fed a real gl_Color</li>
     *   <li>{@code b} = luminance of {@code lighting} — 0 ⇒ the reflection's lighting term collapsed</li>
     * </ul>
     * The two post-processing steps in voxelRayTrace (the distance fadeout multiply and DoFog, which would scale and
     * tint these numbers) are skipped while this is on, so what the log prints is what the shader computed.
     */
    private static String injectWsrColorDebug(String source) {
        if (!DIAG_WSR_COLOR || source == null || !source.contains("vec4 getShadedReflection(")) {
            return source;
        }
        String[][] anchors = {
                {"globals", "vec4 getShadedReflection("},
                {"lightmap", "vec2 lmCoord = faceData.lightmap;"},
                {"born", "return vec4(color.rgb * lighting + maRecolor, alphaFade);"},
        };
        StringBuilder report = new StringBuilder("[WSR COLOR] anchors:");
        for (String[] a : anchors) {
            report.append(' ').append(a[0]).append('=').append(source.contains(a[1]) ? "HIT" : "*** MISS ***");
        }
        log(report.toString());

        String s = source;
        // Scratch that only this diagnostic writes. NOTHING the pack computes is altered — the previous version returned
        // its numbers THROUGH getWSR, which fed +100 into the reflection, blew out the pack's auto-exposure and made the
        // whole frame unusable to aim with. Report out of band instead.
        s = s.replace("vec4 getShadedReflection(", "vec4 yl_wsr = vec4(0.0);\nvec4 getShadedReflection(");
        // Report the VOXEL LIGHTMAP the WSR shades with. Everything else about the colour path already measured healthy
        // (atlas, glColor, DoFog, the fadeout) and the WSR agrees with the SSR on average — but `lighting` came back as
        // a CONSTANT 0.1137 across every sampled hit, which a per-face lightmap cannot be. faceData.lightmap is written
        // by the shadow-pass voxelizer from the pack's `GetLightMapCoordinates()` =
        // `(gl_TextureMatrix[1] * gl_MultiTexCoord1).xy`; our port synthesises gl_MultiTexCoord1 ALREADY NORMALISED
        // ((_vert_tex_light_coord + 8) / 256), so if gl_TextureMatrix[1] is not identity at shadow-draw time it scales
        // it a second time and the stored lightmap collapses to ~0 — which would make every WSR reflection uniformly
        // dark and its seam against the (correctly lit) SSR visible. 1.20.1 shows no seam, so a systematic difference
        // like this is exactly the shape of the remaining bug.
        //   x = faceData.lightmap.x (block light)   y = faceData.lightmap.y (sky light)
        //   z = luminance of `lighting`             w = 1 => a real hit
        // Sunlit terrain must read skyLight ≈ 1.0. Near-zero here IS the bug; a plausible per-face value kills the idea.
        s = s.replace("vec2 lmCoord = faceData.lightmap;",
                "vec2 lmCoord = faceData.lightmap;\nyl_wsr.x = faceData.lightmap.x; yl_wsr.y = faceData.lightmap.y;");
        s = s.replace("return vec4(color.rgb * lighting + maRecolor, alphaFade);",
                "yl_wsr.z = dot(lighting, vec3(0.3333));\nyl_wsr.w = 1.0;\n"
                        + "return vec4(color.rgb * lighting + maRecolor, alphaFade);");
        return s;
    }

    /**
     * DIAGNOSTIC: routes {@link #injectWsrColorDebug}'s numbers out of composite.glsl through an UNUSED colortex, so the
     * pack's own output is bit-for-bit untouched and the user can aim at a normal-looking frame.
     *
     * <p>composite's last directive is {@code /* DRAWBUFFERS:71 *}{@code /}; rewriting it to
     * {@code RENDERTARGETS:7,1,13} makes parseRenderTargets (which takes the last directive, and parses the comma form
     * for indices >9) attach colortex13 as a third output on its own. colortex13 is written by nothing in this pack.</p>
     */
    private static String injectWsrColorOutput(String source) {
        if (!DIAG_WSR_COLOR || source == null || !source.contains("virtualPrevRefPos")) {
            return source;
        }
        boolean anchor = source.contains("/* DRAWBUFFERS:71 */");
        log("[WSR COLOR] composite output anchor: DRAWBUFFERS:71=" + (anchor ? "HIT" : "*** MISS ***"));
        return source.replace("/* DRAWBUFFERS:71 */", "/* RENDERTARGETS:7,1,13 */")
                .replace("gl_FragData[1] = vec4(texture4.rgb, 1.0);",
                        "gl_FragData[1] = vec4(texture4.rgb, 1.0);\n    gl_FragData[2] = yl_wsr;");
    }

    /**
     * DIAGNOSTIC (source transform, pack files untouched): instruments composite.glsl's PBR_REFLECTIONS temporal
     * accumulator so it reports whether its history is being ACCEPTED, and if not, which term rejected it.
     *
     * <p>Why this matters: composite.glsl perturbs the reflection normal with per-pixel roughness noise
     * ({@code refNormal = normalM + roughNoise}) and relies on
     * {@code mix(prevRef.rgb, reflectOutput.rgb, min1(minBlendFactor / prevValid))} — with
     * {@code minBlendFactor ≈ 0.035}, a ~28-frame history — to average that noise away. If {@code prevValid} collapses
     * to ~0 every frame the blend saturates at 1.0, the history is never used, and the raw noise reaches the screen:
     * exactly the speckle the zoomed sea-lantern screenshot shows. Config confirms PBR_REFLECTIONS is on
     * (BLOCK_REFLECT_QUALITY=3, RP_MODE=1 — common.glsl:544).</p>
     *
     * <p>{@code prevValid} is {@code exp(-(t1+…+t6))}, so ANY one large term kills it. The terms are recomputed at the
     * mix site (every input is still in scope) and the dominant one is reported as a colour via colortex7 — pair with
     * DIAG_COLORTEX7, whose viewer passes an a=1.0 tag straight through:</p>
     * <ul>
     *   <li>GREY — the temporal block never ran here (water/{@code fresnelM==0}/sky; water takes getWSR's early return)</li>
     *   <li>RED — ran, but the reprojected position landed off-screen, so there is no history to use</li>
     *   <li>GREEN — ran and the history is HEALTHY (blend ≈ minBlendFactor): noise is being averaged, not the cause</li>
     *   <li>MAGENTA — the NORMAL term dominates: prevNormalM (colortex1, written by composite last frame) disagrees</li>
     *   <li>YELLOW — the DEPTH term dominates: prevRef.a (colortex7.a = last frame's linearZ1) disagrees</li>
     *   <li>BLUE — the screen-distance term dominates (reprojection drifting far from texCoord)</li>
     *   <li>WHITE — none of the three dominate: camera motion / pixelMovement / the off-screen heuristic</li>
     * </ul>
     */
    /**
     * DIAGNOSTIC (source transform, pack files untouched): forces GetVolumetricLight's kill-switches open to locate where
     * the light shafts become zero. See {@link #DIAG_VL_FORCE_LIT} / {@link #DIAG_VL_FORCE_MULT} for the reasoning.
     */
    /**
     * Rewrites gbuffers_hand's view-position reconstruction for this port's hand depth range. Source transform — the pack
     * file on disk is untouched.
     *
     * <p>The pack does {@code ScreenToView(vec3(gl_FragCoord.xy / view, gl_FragCoord.z + 0.38))}. That {@code + 0.38} is a
     * fudge calibrated against the depth slice IRIS renders the hand into; it is not derived from anything the shader can
     * see, because {@code glDepthRange} is a viewport transform and never appears in {@code gbufferProjectionInverse}.
     * This port compresses the hand into {@code [0, HAND_DEPTH_RANGE]} instead, so the pack's constant lands the
     * reconstructed position somewhere it never was — with {@code near = 0.05} the raw window depth of a hand fragment is
     * ~0.9, and +0.38 pushes it past the far plane, flipping the recovered view position BEHIND the camera.</p>
     *
     * <p>Ours is not a fudge: {@code glDepthRange(0, D)} maps {@code z_w = D * (0.5 * z_ndc + 0.5)}, so multiplying the
     * window depth by {@code 1/D} recovers exactly the value ScreenToView expects, at every depth rather than one.</p>
     *
     * <p>The symptom this fixes: a hard line across the held block. It only appeared once the handheld light started
     * reading the hand's viewPos ({@code dot(normalize(cameraHeldLightPos - viewPos), normalM)}) — the bad reconstruction
     * had been there all along with nothing consuming it.</p>
     */
    // DIAGNOSTIC (hand tinted by distant lights): paints the HAND with the three links of the colored-blocklight chain
    // instead of its normal colour — R = luminance of the raw colortex10 sample at the hand's pixel ×100 (is there
    // accumulated screen-space light THERE at all?), G = lmCoord.x (the hand's own blocklight lightmap — the gate the
    // retint scales by), B = how much ApplyMultiColoredBlocklight actually CHANGED blocklightCol ×3. A dark hand means
    // the orange enters through some other term entirely (DoLighting/specular); bright RED confirms the colortex10
    // content; GREEN shows the gate that lets it through. Hand-only (rides the injectHandDepthFix scope).
    // 2026-07-25 (END held-item flicker): RESOLVED — two causes, both fixed for real: (1) `specular`/`normals` were
    // never assigned → read the unit-0 block atlas → sprite mip-alpha became labPBR emission (fixed: default 1×1 PBR
    // textures on units 49/50); (2) block.properties qualifier fold-down gave every end_portal_frame state the Active
    // id 10556, whose material has a WORLD-POSITION ender-eye emission that strobes hand-held items while walking
    // (fixed: property-aware state matching in BlockIdMapper). The v6-v13 probe ladder stays below, gated off.
    private static final boolean DIAG_HAND_MCBL = false;

    private static String injectHandDepthFix(String source) {
        final String anchor = "vec3 screenPos = vec3(gl_FragCoord.xy / vec2(viewWidth, viewHeight), gl_FragCoord.z + 0.38);";
        if (source == null || !source.contains(anchor)) {
            return source; // not gbuffers_hand (or the pack changed the expression — see the log line below)
        }
        log("[hand depth] rewriting gbuffers_hand's viewPos reconstruction for depthRange(0, " + HAND_DEPTH_RANGE + ")");
        String s = source.replace(anchor,
                "vec3 screenPos = vec3(gl_FragCoord.xy / vec2(viewWidth, viewHeight), gl_FragCoord.z * "
                        + (float) (1.0 / HAND_DEPTH_RANGE) + "); // [yumelium] exact un-compression, replaces the pack's +0.38");
        if (DIAG_HAND_MCBL) {
            // v8 bisect: v6 (constant finalDiffuse) stopped the normal-item flicker → the pulse IS a finalDiffuse
            // input. Now capture DoLighting's internal terms into globals and export them through the hand's ct6
            // write (A=0.789 marks hand pixels); diagHandTerms() reads the region back BEFORE any composite runs,
            // so no display-chain contamination (the v3/v4 trap). R=sceneLighting, G=blockLighting+minLighting,
            // B=finalDiffuse — B pulsing with R/G steady would move the suspicion to directionShade/vanillaAO/emission.
            String fd = "vec3 finalDiffuse = pow2(directionShade * vanillaAO) * (blockLighting + pow2(sceneLighting) + minLighting) + pow2(emission);";
            log("[hand terms diag] finalDiffuse anchor=" + (s.contains(fd) ? "HIT" : "*** MISS ***"));
            // v13: emission CONFIRMED as the spiking term (v12: em 0→1.38→0 in lockstep with fd, blk=0). Now split
            // emission's inputs: R=albedo (the colour input every emissive irisIPBR branch derives from — pulsing
            // albedo indicts glColor/texture sampling), G=emission, B=finalDiffuse. Java logs the item id per draw.
            s = s.replace(fd, fd + "\n    yl_diagScene = color.rgb; yl_diagFD = finalDiffuse; yl_diagBlock = vec3(emission * 0.25);\n");
            String decl = "void DoLighting(";
            int di = s.indexOf(decl); // first occurrence only — a duplicate global would break the compile
            log("[hand terms diag] decl anchor=" + (di >= 0 ? "HIT" : "*** MISS ***"));
            if (di >= 0) {
                s = s.substring(0, di)
                        + "vec3 yl_diagScene = vec3(0.0); vec3 yl_diagBlock = vec3(0.0); vec3 yl_diagFD = vec3(0.0);\n"
                        + s.substring(di);
            }
            // v11: END_CENTER add measured zero (dragon alive → gate closed; acquitted). G now carries shadowLightMult —
            // the END directional term (line 461: shadowMult *= max(NdotLM*shadowTime,0), NOT overwritten to 1 unless
            // END_FLASH_SHADOW_INTERNAL). The Java side now logs MEANS per x-quarter so the small held item is not
            // drowned out by the big arm quads whose NdotL naturally sweeps with the walk bob.
            String ct6 = "gl_FragData[1] = vec4(smoothnessD, materialMask, skyLightFactor, purkinjeData);";
            log("[hand terms diag] ct6 anchor=" + (s.contains(ct6) ? "HIT" : "*** MISS ***"));
            s = s.replace(ct6, "gl_FragData[1] = vec4(dot(yl_diagScene, vec3(0.3333)), dot(yl_diagBlock, vec3(0.3333)), dot(yl_diagFD, vec3(0.3333)), 0.789);");
        }
        // The pack's +0.38 fudge had a SECOND job this replacement dropped: it kept the hand's screenPos.z UNDER the
        // 0.56 threshold ApplyMultiColoredBlocklight (coloredBlocklight.glsl:70) uses to decide whether to REPROJECT
        // its colortex10 read into the previous frame — i.e. on real Iris the HAND never reprojects, because it moves
        // with the camera and its own pixel is always current. Our exact un-compression pushes the hand's z to ~0.9,
        // so the hand started reprojecting through mixed-frame matrices; the colortex10 sample then wandered across
        // the screen with every camera turn and dragged DISTANT light-source colour onto the hand (measured: the held
        // block tinted orange by a far village's torches). Force the skip branch back — hand-only: this whole injection
        // runs solely on the source that contains the hand's screenPos anchor, and the gate text is unique pack-wide.
        String reproj = "if (screenPos.z > 0.56) {";
        if (s.contains(reproj)) {
            s = s.replace(reproj, "if (false) { // [yumelium] the hand never reprojects its colortex10 read (see above)");
        } else {
            log("[hand depth] *** reprojection-gate anchor MISS — distant light may smear onto the hand ***");
        }
        return s;
    }

    // DIAGNOSTIC (underwater god rays): show the RAW volumetric-light result underwater — composite1's screen add becomes
    // `color.rgb = volumetricEffect.rgb * 4.0` while submerged, so the screen displays exactly what the VL produced
    // (post the pack's own underwater crush). Beams visible → the VL is fine and the loss is how small the add is vs the
    // scene; black → the integral is zero and the loop terms need bisecting. Anchor unique to composite1 (verified).
    private static final boolean DIAG_SHOW_VL = false;

    private static String injectShowVolumetricLight(String source) {
        if (!DIAG_SHOW_VL || source == null || !source.contains("color += volumetricEffect.rgb;")) {
            return source;
        }
        log("[VL SHOW] composite1 will display the raw volumetricEffect while underwater (x4)");
        return source.replace("color += volumetricEffect.rgb;",
                "color += volumetricEffect.rgb;\n        if (isEyeInWater == 1) color.rgb = volumetricEffect.rgb * 4.0; // [yumelium diag] raw VL view");
    }

    /**
     * Underwater god rays: let the shaft reach the seabed. The pack gates the translucent-shaft branch on
     * {@code shadow2D(shadowtex1) == 1.0} — an EXACT equality on a hardware-PCF (LINEAR) compare, which returns a
     * fraction (0.25/0.5/0.75) wherever the 2×2 footprint mixes with the seabed's own texels. That kills the branch for
     * the last ~block above the floor, so the shafts visibly stop short of the seabed. Relaxing to {@code > 0.7} lets
     * "mostly clear" samples pass, and multiplying the density by the fraction fades the shaft smoothly into the floor
     * instead of a hard edge. No cave light-leak: a sample under an opaque roof fails ALL four taps (exactly 0.0), so
     * the relaxed test still excludes it.
     */
    private static final boolean UNDERWATER_SHAFT_FLOOR_FIX = true;

    private static String injectVlForceRayEnd(String source) {
        if (!DIAG_VL_FORCE_RAYEND || source == null || !source.contains("vec4 GetVolumetricLight(inout float vlFactor")) {
            return source;
        }
        String anchor = "float rayEnd = !sky ? min(lViewPos1, maxDistance) : maxDistance;";
        log("[VL RAYEND] anchor=" + (source.contains(anchor) ? "HIT" : "*** MISS ***"));
        return source.replace(anchor, "float rayEnd = maxDistance; // [diag] full march regardless of geometry");
    }

    // DIAGNOSTIC (opaque water): paint the WATER SURFACE with its own fresnel value. The pack drives both the water's
    // opacity (color.a = mix(color.a, 1.0, fresnel4)) and its reflection strength off fresnel = 1 + dot(normalM,
    // nViewPos): looking straight down it must be ~0 (BLACK = transparent water), grazing ~1 (WHITE = mirror). A flat or
    // stuck-high painting means normalM/nViewPos is broken in the transformed water program — which would explain both
    // the all-angle mirror AND the missing wave wobble in one stroke. Anchor is unique to water.glsl:310.
    private static final boolean DIAG_WATER_FRESNEL = false;

    private static String injectWaterFresnelView(String source) {
        if (!DIAG_WATER_FRESNEL || source == null) {
            return source;
        }
        // CALLED ONLY FROM ensurePackWaterLoaded (the water program's own load path) — v2 sat in applyOptions, which
        // feeds EVERY program, and hand/entities/terrain also inline water.glsl: there the preprocessor drops the
        // normalMap declaration (no GBUFFERS_WATER) while the injected reference survived → "undefined variable
        // normalMap" → those programs AND the water fell back to vanilla. Also normalMap is BLOCK-scoped inside the
        // per-material braces even in the water program; only normalM/normal/fresnel are main-scope at the final write.
        String anchor = "gl_FragData[0] = color;";
        if (!source.contains(anchor)) {
            log("[WATER FRESNEL] *** final-write anchor MISS ***");
            return source;
        }
        // v3 split: LEFT = normalM direction (smooth = healthy, rainbow noise = TBN broken); RIGHT = the TILT magnitude
        // |cross(normalM, normal)|×8 (dark grey = sane sub-degree wave tilt, white = the ~30° explosion the fresnel
        // floor implies). Both main-scope. The fresnel split already measured: flat fresnel CORRECT (0.2 steep),
        // normal-mapped FLOORS at ~0.5 steep → the wave normal is hugely over-tilted; this decides direction-vs-amplitude.
        // v4 — LENGTHS. v3 (direction + tilt) showed a smooth direction and tilts of only a few degrees — far too small
        // for the measured fresnel floor of 0.5 at steep view. But fresnel = 1 + dot(normalM, nViewPos) hits exactly
        // 1 - |normalM| looking straight down: a floor of 0.5 = |normalM| ≈ 0.5. v3's normalize() HID any length defect.
        // LEFT = |normal| (flat), RIGHT = |normalM|; white = 1.0 correct, mid-grey = the suspected 0.5.
        // v5 — the LIGHTMAP SCALE. Lengths are 1 (v4) and the direction is smooth (v3), but v3's ×8 tilt paint was
        // SATURATED: the white veins can be 15-30°, which at a 40° incidence produces exactly the measured 0.5 fresnel
        // floor. The amplitude chain ends in `normalMap.xy *= 0.03 * lmCoordM.y + 0.01` (≈×0.04 with the OptiFine
        // convention lmCoord ∈ 0..1) — if OUR water vertex feeds RAW lightmap coords (0..15 or 0..240) this becomes
        // ×0.5..×7.2 = a 12..180× amplitude explosion. Scale probe: LEFT = lmCoord.y*0.5 (grey 0.5 if normalized,
        // saturated white if raw), RIGHT = lmCoord.y/16 (near-black if normalized, ~0.9 if 0..15, white if 0..240).
        log("[WATER FRESNEL] split v5: LEFT=lmCoord.y*0.5, RIGHT=lmCoord.y/16 — grey/black=normalized OK, white/white=raw scale bug");
        return source.replace(anchor,
                "if (gl_FragCoord.x < 0.5 * viewWidth) color = vec4(vec3(clamp(lmCoord.y * 0.5, 0.0, 1.0)), 1.0);\n"
                        + "    else color = vec4(vec3(clamp(lmCoord.y / 16.0, 0.0, 1.0)), 1.0); // [yumelium diag] lightmap-scale view\n"
                        + "    " + anchor);
    }

    private String injectShaftFloorFix(String source) {
        if (!UNDERWATER_SHAFT_FLOOR_FIX || source == null
                || !source.contains("vec4 GetVolumetricLight(inout float vlFactor")) {
            return source;
        }
        String read = "float translucentShadowSample = shadow2D(shadowtex1, shadowPos.xyz).z;";
        String gate = "if (translucentShadowSample == 1.0) {";
        String density = "localDensity = pow2(shadowColorSample) * vlColorReducer;";
        // Anchor report only on a MISS: String.replace is silent, so a drifted anchor MUST stay loud (an uninstrumented
        // shader would just quietly lose the floor fix) — but the healthy all-HIT case no longer spams the log.
        if (!source.contains(read) || !source.contains(gate) || !source.contains(density)) {
            log("[SHAFT FLOOR] anchors: read=" + (source.contains(read) ? "HIT" : "*** MISS ***")
                    + " gate=" + (source.contains(gate) ? "HIT" : "*** MISS ***")
                    + " density=" + (source.contains(density) ? "HIT" : "*** MISS ***"));
            return source;
        }
        // UNDERWATER-ONLY z bias on the opaque-depth compare (~4 blocks; the shadow z step measured ≈0.0002/block).
        // The distortion makes far shadow texels span several world blocks, and the stored opaque depth is the texel's
        // HIGHEST terrain — so a floor-hugging ray sample reads "inside the ground" further out, and the fog band lifts
        // off the seabed with distance (matched-angle 1.20.1 comparison shows its fog hugging the floor everywhere).
        // Biasing the ref toward the light lets those samples clear the coarse-texel maximum. Gated on isEyeInWater so
        // land shadows (canopies etc.) keep the exact compare.
        String s = source.replace(read,
                "float translucentShadowSample = shadow2D(shadowtex1, vec3(shadowPos.xy, shadowPos.z - (isEyeInWater == 1 ? 0.0008 : 0.0))).z; // [yumelium] underwater floor bias");
        s = s.replace(gate,
                "if (translucentShadowSample > 0.7) { // [yumelium] PCF fractions near the seabed pass; roofs still read 0.0");
        s = s.replace(density,
                "localDensity = pow2(shadowColorSample) * vlColorReducer * translucentShadowSample; // [yumelium] smooth floor fade");
        return s;
    }

    private static String injectVlBranchColors(String source) {
        if (!DIAG_VL_BRANCH_COLORS || source == null || !source.contains("vec4 GetVolumetricLight(inout float vlFactor")) {
            return source;
        }
        String litAnchor = "localDensity = vec3(shadowSample);";
        String transAnchor = "float translucentShadowSample = shadow2D(shadowtex1, shadowPos.xyz).z;";
        log("[VL BRANCH] anchors: lit=" + (source.contains(litAnchor) ? "HIT" : "*** MISS ***")
                + " trans=" + (source.contains(transAnchor) ? "HIT" : "*** MISS ***"));
        if (!source.contains(litAnchor) || !source.contains(transAnchor)) {
            return source;
        }
        String s = source.replace(litAnchor,
                "localDensity = vec3(shadowSample) * vec3(1.0, 0.0, 0.0); // [diag] LIT samples show RED");
        // Paint the RAW shadow2D(shadowtex1) result, before the ==1.0 test: GREEN = compare passed (1.0), BLUE = 0.0,
        // cyan-ish = fractional PCF. This is the value the whole underwater-shaft branch hangs on.
        s = s.replace(transAnchor,
                "float translucentShadowSample = shadow2D(shadowtex1, shadowPos.xyz).z;\n"
                + "                            localDensity = vec3(0.0, translucentShadowSample, 1.0 - translucentShadowSample); // [diag] G=pass B=fail");
        return s;
    }

    private static String injectVolumetricLightDebug(String source) {
        // GetVolumetricLight is inlined into composite1 (and, for reflections, composite); guard on its signature so no
        // other program is touched.
        if ((!DIAG_VL_FORCE_LIT && !DIAG_VL_FORCE_MULT && !DIAG_VL_FORCE_DARK && !DIAG_VL_FORCE_RANGE) || source == null
                || !source.contains("vec4 GetVolumetricLight(inout float vlFactor")) {
            return source;
        }
        // Verify every anchor before relying on it: String.replace is SILENT on a miss, so an uninstrumented shader would
        // render exactly like the real finding "the effect is zero" — indistinguishable, and the diagnostic would lie.
        String[][] anchors = {
                {"gate", "if (vlMult < 0.0001) return vec4(0.0);"},
                {"shadow", "localDensity = vec3(shadowSample);"},
                {"accum", "volumetricLight += stepResult;"},
                {"range", "if (length(shadowPos.xy * 2.0 - 1.0) < 1.0) {"},
        };
        StringBuilder report = new StringBuilder("[VL FORCE] anchors:");
        for (String[] a : anchors) {
            report.append(' ').append(a[0]).append('=').append(source.contains(a[1]) ? "HIT" : "*** MISS ***");
        }
        report.append(" | forceLit=").append(DIAG_VL_FORCE_LIT).append(" forceMult=").append(DIAG_VL_FORCE_MULT)
                .append(" forceDark=").append(DIAG_VL_FORCE_DARK).append(" forceRange=").append(DIAG_VL_FORCE_RANGE);
        log(report.toString());

        String s = source;
        if (DIAG_VL_FORCE_MULT) {
            // Pin the atmospheric gate open INSTEAD of returning early. vlMult is also the final multiplier in Step 5
            // (`volumetricLight.rgb *= vlMult`), so pinning it here both defeats the early-out and guarantees the sum
            // survives to the return.
            s = s.replace("if (vlMult < 0.0001) return vec4(0.0);", "vlMult = 1.0; // [diag] gate forced open");
        }
        if (DIAG_VL_FORCE_RANGE) {
            // Ignore whether shadowPos landed inside the shadow map's distorted disc.
            s = s.replace("if (length(shadowPos.xy * 2.0 - 1.0) < 1.0) {", "if (true) { // [diag] range test forced open");
        }
        if (DIAG_VL_FORCE_DARK) {
            // Same anchor as FORCE_LIT, opposite value — proves whether the enclosing shadowPos range branch runs at all.
            s = s.replace("localDensity = vec3(shadowSample);", "localDensity = vec3(0.0); // [diag] shadow branch marker");
        } else if (DIAG_VL_FORCE_LIT) {
            // Bypass the shadow-map read: every near-field sample counts as fully lit.
            s = s.replace("localDensity = vec3(shadowSample);", "localDensity = vec3(1.0); // [diag] shadow read bypassed");
        }
        return s;
    }

    private static String injectRefTemporalDebug(String source) {
        // The reprojection is unique to composite.glsl; guard on it so composite1..9/deferred1 are left alone.
        if (!DIAG_REF_TEMPORAL || source == null || !source.contains("virtualPrevRefPos")) {
            return source;
        }
        // Verify every anchor: String.replace is SILENT on a miss, and an uninstrumented shader would paint an all-GREY
        // screen that is indistinguishable from the real finding "the block never runs". See the WSR trace note above.
        String[][] anchors = {
                {"globals", "vec2 view = vec2(viewWidth, viewHeight);"},
                {"reproj", "if (virtualPrevRefPos.xyz == clamp01(virtualPrevRefPos.xyz)) {"},
                {"mix", "reflectOutput.rgb = mix(prevRef.rgb, reflectOutput.rgb, min1(minBlendFactor / prevValid));"},
                {"output", "gl_FragData[0] = reflectOutput;"},
        };
        StringBuilder report = new StringBuilder("[REF TEMPORAL] anchors:");
        for (String[] a : anchors) {
            report.append(' ').append(a[0]).append('=').append(source.contains(a[1]) ? "HIT" : "*** MISS ***");
        }
        log(report.toString());

        String s = source;
        // Per-fragment scratch, declared at global scope right before the includes so main() and the reflection code see it.
        s = s.replace("vec2 view = vec2(viewWidth, viewHeight);",
                "vec2 view = vec2(viewWidth, viewHeight);\n"
                        + "float yl_tag = 0.0; vec4 yl_dbg = vec4(0.0);");
        // The reprojection landed on-screen — we at least have a history sample to judge.
        s = s.replace("if (virtualPrevRefPos.xyz == clamp01(virtualPrevRefPos.xyz)) {",
                "if (virtualPrevRefPos.xyz == clamp01(virtualPrevRefPos.xyz)) { yl_tag = 2.0;");
        // Recompute the three port-suspect terms of prevValid's exponent + the resulting blend. Same expressions as the
        // pack's, so a large value here IS what the pack is dividing by.
        s = s.replace("reflectOutput.rgb = mix(prevRef.rgb, reflectOutput.rgb, min1(minBlendFactor / prevValid));",
                "yl_tag = 3.0;\n"
                        + "yl_dbg = vec4(0.03 * length(view * (virtualPrevRefPos.xy - texCoord)),\n"
                        + "              12.0 * length(normalM - prevNormalM),\n"
                        + "              abs(prevRef.a - linearZ1) * far,\n"
                        + "              min1(minBlendFactor / prevValid));\n"
                        + "reflectOutput.rgb = mix(prevRef.rgb, reflectOutput.rgb, min1(minBlendFactor / prevValid));");
        // Report the tag in RGB — but keep reflectOutput.a EXACTLY as the pack wrote it.
        //
        // colortex7 is the accumulator's OWN history buffer, and prevValid's depth term is abs(prevRef.a - linearZ1)*far,
        // i.e. it depends on colortex7.a and nothing else. A first version of this diagnostic wrote vec4(tag, 1.0),
        // which fed a=1.0 back as next frame's prevRef.a → |1.0 - linearZ1| * far ≈ 246 → the depth term was guaranteed
        // to dominate and every opaque pixel reported YELLOW. The tool manufactured the exact finding it was testing for.
        // rgb is free to carry the tag (prevRef.rgb only feeds the mix's colour, never prevValid), so the accumulation
        // logic stays honest while the on-screen image is meaningless — which is fine, we are reading the tag.
        s = s.replace("gl_FragData[0] = reflectOutput;",
                "vec3 yl_c;\n"
                        + "if (yl_tag < 0.5) yl_c = vec3(0.12);\n"
                        + "else if (yl_tag < 2.5) yl_c = vec3(1.0, 0.0, 0.0);\n"
                        + "else if (yl_dbg.w < 0.15) yl_c = vec3(0.0, 1.0, 0.0);\n"
                        + "else {\n"
                        + "    float yl_m = max(yl_dbg.x, max(yl_dbg.y, yl_dbg.z));\n"
                        + "    if (yl_m < 0.5) yl_c = vec3(1.0);\n"
                        + "    else if (yl_m == yl_dbg.x) yl_c = vec3(0.0, 0.0, 1.0);\n"
                        + "    else if (yl_m == yl_dbg.y) yl_c = vec3(1.0, 0.0, 1.0);\n"
                        + "    else yl_c = vec3(1.0, 1.0, 0.0);\n"
                        + "}\n"
                        + "gl_FragData[0] = vec4(yl_c, reflectOutput.a);\n");
        try {
            java.nio.file.Path out = java.nio.file.Paths.get("ref_temporal_dump.glsl");
            java.nio.file.Files.write(out, s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            log("[REF TEMPORAL] dumped instrumented source to " + out.toAbsolutePath());
        } catch (Throwable t) {
            log("[REF TEMPORAL] source dump failed: " + t);
        }
        return s;
    }

    /**
     * DIAGNOSTIC (source transform, pack files untouched): instruments the pack's WSR ray trace so getWSR reports WHY it
     * missed instead of just returning vec4(0.0).
     *
     * <p>getWSR can come back empty through four different doors, and from the outside they are indistinguishable — which
     * is why every hypothesis so far (voxel alignment, LOD volume, samplers, depth, the fresnel gate) measured healthy
     * while reflections stayed dead. This tags each door and returns it as a colour:</p>
     * <ul>
     *   <li>BLACK — the ray marched out of the volume without hitting a single solid voxel</li>
     *   <li>RED — it DID hit solid voxels and nothing rejected them (so a reflection should exist: contradiction)</li>
     *   <li>GREEN — {@code faceData.textureBounds.z < 1e-6}: the SSBO holds no face for that (voxel, normal)</li>
     *   <li>BLUE — {@code color.a < 0.0041}: the atlas sample came back transparent</li>
     *   <li>WHITE — getWSR bailed at {@code CheckInsideSceneVoxelVolume} (the ray START is outside the volume)</li>
     * </ul>
     *
     * <p>Transforming the source at compile time is what an Iris loader does (TerrainShaderTransformer already rewrites
     * the terrain shaders this way); the pack's .glsl on disk stays byte-identical. Applies only to programs that
     * actually contain the ray trace, and only while DIAG_WSR_TRACE is on.</p>
     */
    private static String injectWsrTraceDebug(String source) {
        if (!DIAG_WSR_TRACE || source == null || !source.contains("vec4 voxelRayTrace(")) {
            return source;
        }
        String s = source;
        // Report whether each anchor actually matched. String.replace is SILENT on a miss, so an anchor that drifted
        // (whitespace, line endings, an option rewrite upstream) would leave the shader uninstrumented and the resulting
        // "no tags anywhere" would look exactly like a real finding. Verify, don't assume.
        // SINGLE-LINE anchors only: the multi-line ones silently missed (the text is visibly identical in the dumped
        // source, so something invisible differs), and a silent miss produces "no tags anywhere" — indistinguishable
        // from a real finding. Each of these is verified unique in the compiled source.
        String[][] anchors = {
                {"counters", "vec4 getShadedReflection("},
                {"faceReject", "if (faceData.textureBounds.z < 1e-6) return vec4(-1.0);"},
                {"colorReject", "if (color.a < 0.0041) return vec4(-1.0);"},
                {"hit", "if (mat > 0u) {"},
                {"miss", "traceLength = 999999.0;"},
                {"return", "return wsrResult;"},
        };
        StringBuilder report = new StringBuilder("[WSR TRACE] anchors:");
        for (String[] a : anchors) {
            report.append(' ').append(a[0]).append('=').append(source.contains(a[1]) ? "HIT" : "*** MISS ***");
        }
        log(report.toString());
        // Counters, declared just before the first function that uses them.
        s = s.replace("vec4 getShadedReflection(",
                "float yl_hits = 0.0; float yl_faceRej = 0.0; float yl_colRej = 0.0; float yl_exited = 0.0;\n"
                        + "vec4 yl_dbg = vec4(0.0);\n"
                        + "vec4 getShadedReflection(");
        // Door 1: the SSBO has no face data for this (voxel, normal).
        s = s.replace("if (faceData.textureBounds.z < 1e-6) return vec4(-1.0);",
                "if (faceData.textureBounds.z < 1e-6) { yl_faceRej += 1.0; return vec4(-1.0); }");
        // Door 2: the block atlas sampled transparent there — the dominant one. Capture the UV that failed and the face's
        // stored radius, so the screen shows WHY: a UV outside the block region, or one of the faces whose textureRad the
        // SSBO readback showed running up to 0.4531 (a sane 16px sprite on the 1024x512 atlas is 0.0078).
        s = s.replace("if (color.a < 0.0041) return vec4(-1.0);",
                "if (color.a < 0.0041) { yl_colRej += 1.0;"
                        + " yl_dbg = vec4(textureCoord, faceData.textureBounds.z > 0.05 ? 1.0 : 0.0, 1.0);"
                        + " return vec4(-1.0); }");
        // The ray reached a solid voxel at all (unique to voxelRayTrace's inner loop).
        s = s.replace("if (mat > 0u) {", "if (mat > 0u) { yl_hits += 1.0;");
        // The ray marched out of the volume without an accepted hit (unique to voxelRayTrace's tail).
        s = s.replace("traceLength = 999999.0;", "traceLength = 999999.0; yl_exited = 1.0;");
        // Report instead of the real result. Where the atlas reject fired (the dominant case) report the failing UV
        // itself; elsewhere fall back to the which-door counters.
        s = s.replace("return wsrResult;",
                "return yl_colRej > 0.0 ? yl_dbg"
                        + " : vec4(yl_hits > 0.0 ? 1.0 : 0.0, yl_faceRej > 0.0 ? 1.0 : 0.0, 0.0,"
                        + " yl_exited > 0.5 ? 0.5 : 1.0);");
        // Dump what the GPU will actually compile, so the instrumented region can be read directly instead of inferred.
        try {
            java.nio.file.Path out = java.nio.file.Paths.get("wsr_trace_dump.glsl");
            java.nio.file.Files.write(out, s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            log("[WSR TRACE] dumped instrumented source to " + out.toAbsolutePath());
        } catch (Throwable t) {
            log("[WSR TRACE] source dump failed: " + t);
        }
        return s;
    }

    /**
     * Builds the pack's custom images from {@code shaders.properties}. The {@code image.*}/{@code bufferObject.*}
     * directives live under {@code #if COLORED_LIGHTING > 0} (and size sub-branches), so the properties are preprocessed
     * with the current option values (+ the Iris defines) and inactive branches stripped before parsing — otherwise every
     * size variant would collapse to the last one. Returns an EMPTY set when COLORED_LIGHTING=0 (no images declared).
     */
    /**
     * Preprocesses {@code shaders.properties} with the current option values + our Iris defines, strips the inactive
     * {@code #if} branches, and substitutes option macros into the directive values — i.e. exactly the directives that
     * are ACTUALLY live for this build. Resolving the conditionals matters: e.g. rainFactor is declared twice, under
     * {@code #if IRIS_VERSION >= 10800} (RAIN_FADE_TIMER) and {@code #else} (a literal 3), and we deliberately do NOT
     * define IRIS_VERSION >= 10800 — so the 3-second branch is the live one. Empty map if unreadable.
     */
    private java.util.Map<String, String> resolvedProperties(ShaderPack pack) {
        try {
            String raw = pack.source().readText(pack.shadersRoot() + "shaders.properties");
            if (raw == null) {
                return java.util.Collections.emptyMap();
            }
            String preprocessed = GlslConditionals.stripInactive(optionDefinePreamble() + IRIS_DEFINES + raw);
            ShaderProperties props = ShaderProperties.parse(preprocessed);
            java.util.Map<String, String> optMacros = new java.util.HashMap<>();
            for (ShaderOptionSet.Option o : shaderOptions().allOptions()) {
                if (!o.bool && o.value != null) {
                    optMacros.put(o.name, o.value);
                }
            }
            java.util.Map<String, String> resolved = new java.util.LinkedHashMap<>();
            for (java.util.Map.Entry<String, String> e : props.asMap().entrySet()) {
                resolved.put(e.getKey(), substituteOptionMacros(e.getValue(), optMacros));
            }
            return resolved;
        } catch (Throwable t) {
            SodiumClientMod.logger().error("[Iris] reading shaders.properties failed", t);
            return java.util.Collections.emptyMap();
        }
    }

    private CustomImages buildCustomImages(ShaderPack pack) {
        try {
            String raw = pack.source().readText(pack.shadersRoot() + "shaders.properties");
            if (raw == null) {
                return new CustomImages();
            }
            String preprocessed = GlslConditionals.stripInactive(optionDefinePreamble() + IRIS_DEFINES + raw);
            ShaderProperties props = ShaderProperties.parse(preprocessed);
            // stripInactive resolves the #if BRANCHES but NOT macro references left in a directive's VALUE. The pack writes
            // `image.wsr_img = ... COLORED_LIGHTING 64 COLORED_LIGHTING` (dims are the COLORED_LIGHTING macro, not a literal),
            // so without substituting them the size parses as -1 → wsr_img/wsr_lod_img never allocate → the WSR scene voxel
            // map is empty → world-space reflections silently fall back to a (garbage) sky reflection. Substitute every
            // numeric pack-option macro (COLORED_LIGHTING=384, REFLECTION_RES, ...) into the directive values, whole-word.
            java.util.Map<String, String> optMacros = new java.util.HashMap<>();
            for (ShaderOptionSet.Option o : shaderOptions().allOptions()) {
                if (!o.bool && o.value != null) {
                    optMacros.put(o.name, o.value);
                }
            }
            java.util.Map<String, String> resolved = new java.util.LinkedHashMap<>();
            for (java.util.Map.Entry<String, String> e : props.asMap().entrySet()) {
                resolved.put(e.getKey(), substituteOptionMacros(e.getValue(), optMacros));
            }
            return CustomImages.parse(resolved);
        } catch (Throwable t) {
            SodiumClientMod.logger().error("[Iris image] building custom images failed", t);
            return new CustomImages();
        }
    }

    /** Substitutes numeric pack-option macros (e.g. {@code COLORED_LIGHTING}→{@code 384}) whole-word into a directive value,
     * so image/bufferObject dimensions written as a macro resolve to a number. Whole-word so {@code COLORED_LIGHTING} does
     * not match {@code COLORED_LIGHTING_INTERNAL} (the trailing boundary fails before the '_'). Pure-numeric values pass through. */
    private static String substituteOptionMacros(String value, java.util.Map<String, String> optMacros) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        boolean hasLetter = false;
        for (int i = 0; i < value.length(); i++) {
            if (Character.isLetter(value.charAt(i))) { hasLetter = true; break; }
        }
        if (!hasLetter) {
            return value;
        }
        for (java.util.Map.Entry<String, String> e : optMacros.entrySet()) {
            value = value.replaceAll("\\b" + java.util.regex.Pattern.quote(e.getKey()) + "\\b",
                    java.util.regex.Matcher.quoteReplacement(e.getValue()));
        }
        return value;
    }

    /** @return {@code #define NAME value} lines for every pack option, so a preprocessed shaders.properties evaluates its
     * {@code #if COLORED_LIGHTING == 256} guards against the user's current config. */
    /** True when the named BOOLEAN shader option is ON in the active config (e.g. CONNECTED_GLASS_EFFECT — the
     * shadow fragment mirrors the pack shadow vertex's #ifdef-gated varyings with it). False for unknown names. */
    public boolean isBooleanOptionOn(String name) {
        for (com.yumelium.yumelium.shaders.pack.ShaderOptionSet.Option o : shaderOptions().allOptions()) {
            if (o.bool && name.equals(o.name)) {
                return o.isOn();
            }
        }
        return false;
    }

    private String optionDefinePreamble() {
        StringBuilder sb = new StringBuilder();
        for (ShaderOptionSet.Option o : shaderOptions().allOptions()) {
            if (o.bool) {
                if (o.isOn()) {
                    sb.append("#define ").append(o.name).append('\n');
                }
            } else {
                sb.append("#define ").append(o.name).append(' ').append(o.value).append('\n');
            }
        }
        return sb.toString();
    }

    /** Reads a pack-relative resource (under {@code shaders/}) as raw bytes, e.g. a custom {@code .png} texture; null if absent. */
    private static byte[] readPackBytes(ShaderPack pack, String shadersRelativePath) {
        try {
            return pack.source().readBytes(pack.shadersRoot() + shadersRelativePath);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Per-block render-layer overrides from the pack's {@code layer.solid / layer.cutout / layer.cutout_mipped /
     * layer.translucent} directives (null = none). Read by the chunk meshing WORKER threads — volatile immutable
     * map, rebuilt per pack load. */
    private volatile java.util.Map<net.minecraft.block.Block, net.minecraft.util.BlockRenderLayer> layerOverrides;

    /** @return blocks forced into a specific render layer while shaders are on, or null for vanilla behaviour. */
    public java.util.Map<net.minecraft.block.Block, net.minecraft.util.BlockRenderLayer> layerOverrides() {
        return this.enabled ? this.layerOverrides : null;
    }

    /**
     * Parses the four {@code layer.<name> = <block> ...} directives from the PREPROCESSED block.properties.
     * Complementary moves {@code glass glass_pane beacon} into the translucent layer, which is what routes plain
     * glass through gbuffers_water on real Iris — its glass shading AND the shader-side connected glass
     * (DoConnectedGlass, mats 32008/32012) only exist there. Our 1.12.2 CUTOUT-layer plain glass went through
     * gbuffers_terrain, which has no glass code at all → no connected glass, wrong material. The pack's
     * solid/cutout/cutout_mipped lists currently name only modded blocks (createdeco:, improved_damage:, …), so
     * they resolve to nothing on this instance — but any future mod matching those entries (or another pack using
     * them on vanilla blocks) is now honoured through the same path. Only the vanilla namespace resolves here.
     */
    private void parseLayerOverrides(String preprocessed) {
        java.util.Map<String, net.minecraft.util.BlockRenderLayer> directives = new java.util.LinkedHashMap<>();
        directives.put("layer.solid", net.minecraft.util.BlockRenderLayer.SOLID);
        directives.put("layer.cutout", net.minecraft.util.BlockRenderLayer.CUTOUT);
        directives.put("layer.cutout_mipped", net.minecraft.util.BlockRenderLayer.CUTOUT_MIPPED);
        directives.put("layer.translucent", net.minecraft.util.BlockRenderLayer.TRANSLUCENT);
        java.util.Map<String, String> lastDecl = new java.util.HashMap<>(); // pack semantics: last declaration wins
        String joined = preprocessed.replace("\\\r\n", " ").replace("\\\n", " ").replace("\\\r", " ");
        for (String line : joined.split("\r?\n")) {
            String s = line.trim();
            for (String key : directives.keySet()) {
                if (s.startsWith(key)) {
                    // Exact-key match: "layer.cutout" must not swallow "layer.cutout_mipped" — after the key,
                    // only whitespace then '=' may follow.
                    String rest = s.substring(key.length()).trim();
                    if (rest.startsWith("=")) {
                        lastDecl.put(key, rest.substring(1).trim());
                    }
                }
            }
        }
        java.util.Map<net.minecraft.block.Block, net.minecraft.util.BlockRenderLayer> map = new java.util.HashMap<>();
        StringBuilder summary = new StringBuilder("layer overrides →");
        for (java.util.Map.Entry<String, net.minecraft.util.BlockRenderLayer> d : directives.entrySet()) {
            String list = lastDecl.get(d.getKey());
            int resolved = 0;
            if (list != null) {
                for (String tok : list.split("\\s+")) {
                    if (tok.isEmpty() || (tok.contains(":") && !tok.startsWith("minecraft:"))) {
                        continue; // modded namespace — not on this instance
                    }
                    String name = tok.startsWith("minecraft:") ? tok.substring("minecraft:".length()) : tok;
                    net.minecraft.block.Block b = net.minecraft.block.Block.getBlockFromName("minecraft:" + name);
                    if (b != null) {
                        map.put(b, d.getValue());
                        resolved++;
                    }
                }
            }
            summary.append(' ').append(d.getKey().substring("layer.".length())).append('=').append(resolved);
        }
        this.layerOverrides = map.isEmpty() ? null : java.util.Collections.unmodifiableMap(map);
        log(summary.append(" vanilla blocks").toString());
    }

    /**
     * Preprocesses a pack {@code *.properties} table so it reads exactly as real 1.20.1 Iris sees it. The pack ships
     * THREE MC-generation sections ({@code #if MC_VERSION >= 11300} flattened names / 1.8-1.12 legacy names /
     * 1.7.10), and parsing them ALL made cross-generation names collide: the legacy sections' {@code grass} is the
     * GRASS BLOCK (solid, even id 10132) while the modern section's {@code grass} is the PLANT (10005) — single
     * tallgrass took the block id, VOXELIZED AS A SOLID, starved the flood-fill in its own cell, and rendered with
     * the warm fallback blocklight colour next to coloured lights ("orange grass"). Same preamble as the shaders,
     * with MC_VERSION re-defined to 12001 AFTERWARDS (later #define wins) — the reference client whose parse this
     * port matches; the GLSL itself keeps 11202. BlockIdMapper's FLATTENED table expects the modern names anyway.
     */
    private String preprocessProperties(String raw) {
        if (raw == null) {
            return null;
        }
        return GlslConditionals.stripInactive(
                optionDefinePreamble() + IRIS_DEFINES + "#define MC_VERSION 12001\n" + raw);
    }

    /** Reads a pack-relative text resource (under {@code shaders/}); null if absent. */
    private static String readPackText(ShaderPack pack, String shadersRelativePath) {
        try {
            return pack.source().readText(pack.shadersRoot() + shadersRelativePath);
        } catch (Throwable t) {
            return null;
        }
    }

    public boolean toggle() {
        this.enabled = !this.enabled;
        if (!this.enabled) {
            this.activeThisFrame = false;
        }
        return this.enabled;
    }

    /**
     * Restores the persisted active pack + enabled state at startup (before any world/renderer exists), so a restart
     * keeps the user's shader selection. Only sets the fields — the renderer builds with these when the world loads;
     * no reload here (there's nothing to reload yet).
     */
    public void restore(String pack, boolean enabled) {
        if (pack != null) {
            this.activePackName = pack;
        }
        this.enabled = enabled;
    }

    /** Compiles all pipeline programs from the (built-in) pack once, on the render thread. */
    private void initPipeline() {
        if (this.pipelineInit) {
            return;
        }
        this.pipelineInit = true;
        this.startNanos = System.nanoTime();
        try (ShaderPack pack = loadActivePack()) {
            // 2026-07-27 audit (#8): reset pack-derived meshing state UNCONDITIONALLY first — BEFORE the pack null
            // check. A pack without block.properties used to inherit the PREVIOUS pack's layer.* forcing (glass stuck
            // in TRANSLUCENT), and a pack that fails to load entirely (pack == null: missing/corrupt zip) leaked both
            // the stale layer map and the stale block-id table until restart.
            this.layerOverrides = null;
            me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer.setBlockIdMapper(null);
            if (pack != null) {
                // Custom pack textures (read while the pack is open): the pack's noise.png + the cloud-water.png water
                // normal map (bound to gaux4). shaders.properties selects the water/cloud texture by CLOUD_TEXTURE; we use
                // the CLOUD_TEXTURE=0 default (cloud-water.png) — the user's config. Null-safe if a pack lacks them.
                // block.properties → the mc_Entity.x ids the pack identifies materials by. Without this every block but a
                // hardcoded handful arrived as id 0, so Complementary's IPBR gave them no material at all: no gloss on
                // obsidian/ice/metal, smoothnessD = 0, and therefore no deferred reflection on any solid.
                String blockProps = preprocessProperties(readPackText(pack, "block.properties"));
                if (blockProps != null) {
                    me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer.setBlockIdMapper(
                            com.yumelium.yumelium.shaders.pack.BlockIdMapper.build(
                                    com.yumelium.yumelium.shaders.pack.BlockProperties.parse(blockProps)));
                    parseLayerOverrides(blockProps);
                } else {
                    log("pack ships no block.properties — falling back to the built-in id table");
                }
                // item.properties → heldItemId/heldItemId2, which is what COLOURS the pack's handheld light.
                String itemProps = preprocessProperties(readPackText(pack, "item.properties"));
                this.itemIdMapper = itemProps == null ? null
                        : com.yumelium.yumelium.shaders.pack.ItemIdMapper.build(
                                com.yumelium.yumelium.shaders.pack.BlockProperties.parse(itemProps, "item."));
                // entity.properties → entityId (fed per entity by onEntityRender): boat lighting, the current_player
                // gate on the held-light halo, and entityIPBR's per-entity material dispatch all key on it.
                String entityProps = preprocessProperties(readPackText(pack, "entity.properties"));
                this.entityIdMapper = entityProps == null ? null
                        : com.yumelium.yumelium.shaders.pack.EntityIdMapper.build(
                                com.yumelium.yumelium.shaders.pack.BlockProperties.parse(entityProps, "entity."));
                // Which programs the current config disables + the misc host-behaviour directives (vignette/clouds/
                // alphaTest/...). BEFORE the texture reads: the gaux4 path comes from texture.gbuffers.gaux4.
                parseDisabledPrograms(pack);
                parseMiscDirectives(pack);
                this.noisePngData = readPackBytes(pack, "lib/textures/noise.png");
                // The gaux4/cloud texture the pack's directive names (CLOUD_TEXTURE picks between three files); the
                // old hardcoded cloud-water.png stays as the fallback if the directive's file is missing.
                this.waterNormalPngData = readPackBytes(pack, this.gaux4TexturePath);
                if (this.waterNormalPngData == null) {
                    this.waterNormalPngData = readPackBytes(pack, "lib/textures/cloud-water.png");
                }
                // Weather fade timings: the pack's wetness/dryness halflives (a `const float` in lib/pipelineSettings.glsl,
                // inlined into every program by the include resolver) + the RAIN_FADE_TIMER option.
                parseWeatherTimings(pack);
                // Which colortex the pack wants left alone each frame (default: cleared). See parseClearFlags.
                parseClearFlags(pack);
                // Which G-buffer writes must NOT be alpha-blended into what is behind them. See parseBlendDirectives.
                parseBlendDirectives(pack);
                // Resolve gbuffers_terrain's ACTIVE output list from the pack (see packTerrainTargets) rather than
                // trusting a hardcoded one — the variants are not prefixes of each other.
                this.packTerrainTargets = parseProgramTargets(pack, "gbuffers_terrain");
                log("gbuffers_terrain RENDERTARGETS=" + java.util.Arrays.toString(this.packTerrainTargets)
                        + (this.packTerrainTargets == null ? " (falling back to the hardcoded set)" : ""));
                // Custom images (Colored Lighting / WSR voxel + floodfill + wsr textures + SSBOs). Empty unless the pack's
                // COLORED_LIGHTING option is > 0; when present, allocate the GL textures/buffers up front.
                this.customImages = buildCustomImages(pack);
                this.customImages.allocate();
                // Register the pack's custom image/sampler names as provided, so the UNIFORM MISS report below doesn't
                // flag the ones customImages.bindTo feeds dynamically — and DOES flag anything genuinely unbound.
                for (CustomImages.Image img : this.customImages.images()) {
                    PROVIDED_UNIFORMS.add(img.name);
                    PROVIDED_UNIFORMS.add(img.samplerName);
                }
                // Colored-lighting flood-fill compute — only meaningful when the pack declared custom images (CL > 0),
                // and only when the pack has not disabled it for this config (program.world0/shadowcomp.enabled=false
                // at COLORED_LIGHTING == 0 — the empty-images check already covers that, but honour the directive too).
                if (!this.customImages.isEmpty() && !this.disabledPrograms.contains("shadowcomp")) {
                    ProgramSource scomp = pack.programSet().get("shadowcomp");
                    if (scomp != null && scomp.compute() != null) {
                        // Strip inactive #if branches so the ONE active `const ivec3 workGroups` survives — the source
                        // declares one per COLORED_LIGHTING size, and an unstripped parse picks the first (wrong) one, so
                        // we'd dispatch too few work groups and voxelize only a fraction of the volume (→ no colored light).
                        String scompSrc = GlslConditionals.stripInactive(applyOptions(scomp.compute()));
                        // Bounds-guard the flood-fill's volume-SHIFT reads. GetLightSample is a RAW texelFetch, and
                        // while the camera crosses a block the shifted read coordinate (previousPos) runs OUT OF
                        // RANGE at the volume's leading edge — undefined behaviour per spec (garbage on this NVIDIA
                        // driver), i.e. junk injected into the flood-fill exactly WHILE MOVING. The neighbour taps
                        // are clamped by the pack; only this direct-fetch path was not. Out-of-range = fresh cells
                        // scrolling in → the correct value is DARK (0), not clamped-edge smear.
                        String rawFetch = "return texelFetch(lightSampler, pos, 0);";
                        if (scompSrc.contains(rawFetch)) {
                            // textureSize(): self-contained bounds — voxelVolumeSize is NOT in scope at
                            // GetLightSample's position (the first attempt failed to compile on it).
                            scompSrc = scompSrc.replace(rawFetch,
                                    "if (any(lessThan(pos, ivec3(0))) || any(greaterThanEqual(pos, textureSize(lightSampler, 0))))"
                                    + " { return vec4(0.0); } return texelFetch(lightSampler, pos, 0);");
                        } else {
                            log("shadowcomp: GetLightSample fetch anchor MISS — shift reads stay unguarded");
                        }
                        this.shadowCompProgram = GlslProgram.compileCompute("shadowcomp", scompSrc);
                        log("shadowcomp compute " + (this.shadowCompProgram != null
                                ? "compiled (workGroups=" + java.util.Arrays.toString(this.shadowCompProgram.workGroups()) + ")"
                                : "FAILED to compile — colored lighting flood-fill disabled"));
                    }
                }
                this.tonemapProgram = GlslProgram.compile("iris_tonemap", DEFAULT_FULLSCREEN_VSH, TONEMAP_FSH);
                this.depthCopyProgram = GlslProgram.compile("iris_depthcopy", DEFAULT_FULLSCREEN_VSH, DEPTH_COPY_FSH);
                this.handReflectFixProgram = GlslProgram.compile("iris_handreflectfix", DEFAULT_FULLSCREEN_VSH, HAND_REFLECT_FIX_FSH);
                this.playerShadowProgram = GlslProgram.compile("iris_playershadow", PLAYER_SHADOW_VSH, PLAYER_SHADOW_FSH);
                compilePrepare(pack);
                compileChain(pack, "deferred", this.deferredPrograms);
                compileChain(pack, "composite", this.compositePrograms);
                ProgramSource finalSrc = pack.programSet().get("final");
                if (finalSrc != null && finalSrc.fragment() != null) {
                    this.finalProgram = compileFullscreen(finalSrc, "final");
                    // final reads the colortex bank exactly like a composite, so honor its colortexNMipmapEnabled
                    // consts — but its DRAWBUFFERS directive is deliberately NOT parsed: per Iris/OptiFine semantics
                    // final always renders to MC's framebuffer (Complementary declares DRAWBUFFERS:0 there; routing
                    // that through the ping-pong chain would clobber colortex0 and display nothing).
                    if (this.finalProgram != null) {
                        this.finalMipmaps = parseMipmaps(GlslConditionals.stripInactive(applyOptions(finalSrc.fragment())));
                    }
                }
                // DOF auto-focus gate: sample the centre depth per frame ONLY if some fullscreen program actually
                // links centerDepthSmooth (GL prunes it when the pack's config compiles the auto-focus branch out).
                this.wantsCenterDepth = false;
                java.util.List<Pass> allFullscreen = new java.util.ArrayList<>(this.preparePrograms);
                allFullscreen.addAll(this.deferredPrograms);
                allFullscreen.addAll(this.compositePrograms);
                for (Pass p : allFullscreen) {
                    if (org.lwjgl.opengl.GL20C.glGetUniformLocation(p.program.handle(), "centerDepthSmooth") >= 0) {
                        this.wantsCenterDepth = true;
                        break;
                    }
                }
                if (!this.wantsCenterDepth && this.finalProgram != null && org.lwjgl.opengl.GL20C
                        .glGetUniformLocation(this.finalProgram.handle(), "centerDepthSmooth") >= 0) {
                    this.wantsCenterDepth = true;
                }
                // Immediate-mode sky programs (fixed-function attrs/matrices, direct compile — no transform).
                this.skybasicProgram = compileWorldProgram(pack, "gbuffers_skybasic");
                this.skybasicBelowProgram = compileSkybasicBelow(pack);
                this.skytexturedProgram = compileWorldProgram(pack, "gbuffers_skytextured");
                // First-person hand program (immediate mode, same direct-compile path as the sky).
                this.handProgram = compileWorldProgram(pack, "gbuffers_hand");
                // DIAGNOSTIC: dump the EXACT hand fragment handed to GL (post options + injections), so claims about
                // what its gl_FragData writes look like can be read instead of inferred through the transform chain.
                if (DIAG_HAND_PIXEL) {
                    ProgramSource handSrc = pack.programSet().get("gbuffers_hand");
                    if (handSrc != null && handSrc.fragment() != null) {
                        TerrainShaderTransformer.dump("iris_hand_dump.fsh",
                                TerrainShaderTransformer.raiseVersion(applyOptions(handSrc.fragment())));
                    }
                }
                // Regular-entity program (mobs, dropped items), immediate mode.
                this.entitiesProgram = compileWorldProgram(pack, "gbuffers_entities");
                // Block-entity/TESR program + generic textured (particles) program, immediate mode.
                this.blockProgram = compileWorldProgram(pack, "gbuffers_block");
                this.texturedProgram = compileWorldProgram(pack, "gbuffers_textured");
                // Weather (rain/snow) program, immediate mode like the sky.
                this.weatherProgram = compileWorldProgram(pack, "gbuffers_weather");
                // Beacon beam (swapped in per TESR) + block-breaking crack overlay (around drawBlockDamageTexture).
                // Without these the beam was material-dispatched like a chest and the cracks drew fixed-function.
                this.beaconBeamProgram = compileWorldProgram(pack, "gbuffers_beaconbeam");
                this.damagedBlockProgram = compileWorldProgram(pack, "gbuffers_damagedblock");
                // Overlay programs drawn INSIDE a live pass: the enchantment glint (item + armor), the fullbright
                // eye layers (spider/enderman/dragon), and the glowing-entity variant (a per-entity swap like the
                // beacon's — same structure/targets as gbuffers_entities, compiled with GBUFFERS_ENTITIES_GLOWING).
                this.armorGlintProgram = compileWorldProgram(pack, "gbuffers_armor_glint");
                this.spidereyesProgram = compileWorldProgram(pack, "gbuffers_spidereyes");
                this.entitiesGlowingProgram = compileWorldProgram(pack, "gbuffers_entities_glowing");
                // Untextured POSITION_COLOR draws (leash, block selection outline). gbuffers_line stays unrouted by
                // design — see the basicProgram field note.
                this.basicProgram = compileWorldProgram(pack, "gbuffers_basic");
                // The G-buffer targets the entity/hand fragments write (so their draws overwrite the material/normal, not
                // just colour — see the entityTargets field note). Parsed from the ACTIVE (branch-stripped) fragment.
                this.entityTargets = parseProgramTargets(pack, "gbuffers_entities");
                this.handTargets = parseProgramTargets(pack, "gbuffers_hand");
                // Glint/eyes declare their own DRAWBUFFERS (:06 — slot1 = colortex6). They CANNOT inherit the enclosing
                // pass's attachment order (entities = [0,3,6,4,9] would land their material-preserve write in colortex3),
                // so their sub-draws re-attach these and the end hook restores the pass's set.
                this.armorGlintTargets = parseProgramTargets(pack, "gbuffers_armor_glint");
                this.spidereyesTargets = parseProgramTargets(pack, "gbuffers_spidereyes");
                this.basicTargets = parseProgramTargets(pack, "gbuffers_basic");
                // DIAGNOSTIC: the hand MUST write colortex4 (=4 in this list) with a=1.0, else its fresnelM stays stale
                // and composite1 applies a scene reflection to it (a see-through, reflective hand). If 4 is missing here,
                // parseProgramTargets picked the wrong DRAWBUFFERS branch (should be 064 for BLOCK_REFLECT_QUALITY>=2).
                if (DIAG_HAND_STATE) {
                    log("[HAND DIAG] handTargets=" + java.util.Arrays.toString(this.handTargets)
                            + " entityTargets=" + java.util.Arrays.toString(this.entityTargets));
                }
            }
        } catch (Throwable t) {
            SodiumClientMod.logger().error("[Iris] pipeline init failed", t);
        }
        log("pipeline programs: prepare=" + this.preparePrograms.size()
                + " deferred=" + this.deferredPrograms.size()
                + " composite=" + this.compositePrograms.size()
                + " final=" + (this.finalProgram != null ? "yes" : "no (blit fallback)")
                + " sky=" + (this.skybasicProgram != null ? "basic" : "-") + "/" + (this.skytexturedProgram != null ? "textured" : "-")
                + " hand=" + (this.handProgram != null ? "yes" : "-")
                + " entities=" + (this.entitiesProgram != null ? "yes" : "-")
                + " block=" + (this.blockProgram != null ? "yes" : "-")
                + " textured=" + (this.texturedProgram != null ? "yes" : "-"));

        // Rigorous "where the Iris port silently fails" report: which active uniforms each pack program wants but we
        // never set (read as 0 → wrong sky/atmosphere/etc.). Sky + the passes that build the atmosphere first.
        logUnsetUniforms("gbuffers_skybasic", this.skybasicProgram);
        logUnsetUniforms("gbuffers_skytextured", this.skytexturedProgram);
        for (Pass p : this.deferredPrograms) {
            logUnsetUniforms("deferred:" + p.name, p.program);
        }
        for (Pass p : this.compositePrograms) {
            logUnsetUniforms("composite:" + p.name, p.program);
        }
        if (this.finalProgram != null) {
            logUnsetUniforms("final", this.finalProgram);
        }
    }

    /**
     * @return false if the fragment writes no colour output (its {@code main} only {@code discard}s — e.g. Complementary's
     * {@code prepare} is a `void main(){ discard; }` stub). Such a pass writes NOTHING, so it must not be run in the
     * ping-pong chain: it "targets" colortex0 (the no-directive default) but never writes it, and flipping colortex0 after
     * it swaps the live gbuffer for the stale back buffer (an old frame's content) → the whole scene goes grey. Skipping
     * it is also what it means semantically (a no-op).
     */
    private static boolean writesColorOutput(String fragment) {
        return fragment.contains("gl_FragData") || fragment.contains("gl_FragColor");
    }

    /** Compiles the indexed family {@code base}, {@code base1}..{@code base16} (in order) that the pack provides, each
     * paired with the colortex indices its {@code RENDERTARGETS}/{@code DRAWBUFFERS} directive writes. */
    private void compileChain(ShaderPack pack, String base, List<Pass> out) {
        for (int i = 0; i <= MAX_INDEX; i++) {
            String name = i == 0 ? base : base + i;
            ProgramSource src = pack.programSet().get(name);
            if (src != null && src.fragment() != null) {
                // program.world0/<name>.enabled = false: the pack turns whole passes off per config (composite8 only
                // exists for RENKO_CUT, composite9 for LONG_EXPOSURE). Running them anyway is a divergence from Iris.
                if (this.disabledPrograms.contains(name)) {
                    log("[chain] " + name + " disabled by the pack for this config (program enabled=false)");
                    continue;
                }
                if (!writesColorOutput(src.fragment())) {
                    log("[chain] " + name + " skipped (no colour output — discard-only stub)");
                    continue;
                }
                GlslProgram program = compileFullscreen(src, name);
                if (program != null) {
                    // Parse the output layout from the ACTIVE source (options applied + false #if branches stripped) so a
                    // config-gated directive (e.g. composite4's DRAWBUFFERS:30 under #if MOTION_BLUR_EFFECT==1) doesn't
                    // over-attach + corrupt a buffer when its branch is off.
                    String active = GlslConditionals.stripInactive(applyOptions(src.fragment()));
                    // DIAG (one-shot, compile time): confirm the TAA_SMOOTHING override actually reached the composite that
                    // runs DoTAA. Reports the surviving `#define TAA_SMOOTHING N` line + which blendMinimum branch is active.
                    if (DIAG_TAA_SMOOTHING && name.equals("composite6")) {
                        java.util.regex.Matcher tm = java.util.regex.Pattern
                                .compile("#define\\s+TAA_SMOOTHING\\s+(\\d+)").matcher(active);
                        String sm = tm.find() ? tm.group(1) : "<none>";
                        boolean b4 = active.contains("blendMinimum = 0.5");
                        boolean b3 = active.contains("blendMinimum = 0.35");
                        log("[Iris TAA] composite6 effective TAA_SMOOTHING=" + sm
                                + " | blendMinimum branch: 0.5(High)=" + b4 + " 0.35(Med)=" + b3);
                    }
                    int[] rt = parseRenderTargets(active);
                    int[] mm = parseMipmaps(active);
                    out.add(new Pass(program, rt, name, mm));
                    log("[chain] " + name + " RENDERTARGETS=" + java.util.Arrays.toString(rt)
                            + (mm.length > 0 ? " mipmap=" + java.util.Arrays.toString(mm) : "")
                            // Which sampler kind this pass declared shadowtex0 as decides the shadow map's compare mode
                            // for the pass (see setCompositeSamplers). composite1 must report sampler2D/rawdepth or the
                            // volumetric light reads 0 everywhere and no light shafts render.
                            + (program.hasSampler("shadowtex0")
                                    ? " shadowtex0=" + (program.isShadowSampler("shadowtex0")
                                            ? "sampler2DShadow/compare" : "sampler2D/rawdepth")
                                    : ""));
                    // Report every uniform this pack pass actually uses that we never set. An unset sampler silently
                    // reads texture unit 0, which is how getWSR was dying (textureAtlas → shadowtex0), and how three
                    // earlier bugs happened (gaux4/noisetex/textureAtlas). Cheap, once per pass, at compile time.
                    logUnsetUniforms(name, program);
                }
            }
        }
    }

    /** Compiles the single {@code prepare} program (runs before the deferred chain). */
    private void compilePrepare(ShaderPack pack) {
        ProgramSource src = pack.programSet().get("prepare");
        if (src == null || src.fragment() == null) {
            return;
        }
        if (this.disabledPrograms.contains("prepare")) {
            log("[chain] prepare disabled by the pack for this config (program enabled=false)");
            return;
        }
        boolean colorOutput = writesColorOutput(src.fragment());
        GlslProgram program;
        if (colorOutput) {
            program = compileFullscreen(src, "prepare");
        } else {
            // Complementary's prepare FRAGMENT only discards, but its VERTEX does imageLoad/imageStore bookkeeping on
            // endcrystal_img (end-crystal vortex state decay + end-portal-beam aging), gated to one corner vertex — a
            // deliberate side-effect-only pass the pack expects to RUN (program.world0/prepare.enabled stays true with
            // END_PORTAL_BEAM on). The old unconditional "discard-only stub" skip silently dropped that work. It runs
            // with NO colour draw buffers and NO ping-pong flip (attaching colortex0 and flipping after a pass that
            // writes nothing would swap in the stale back buffer — the grey-scene bug the skip was added for).
            // raiseVersion: prepare is #version 130 with a layout() qualifier whose #extension comes too late — the
            // exact trap that silently broke gbuffers_block (see raiseVersion's javadoc).
            String vsh = src.vertex() != null ? src.vertex() : DEFAULT_FULLSCREEN_VSH;
            program = GlslProgram.compile("prepare",
                    TerrainShaderTransformer.raiseVersion(applyOptions(vsh)),
                    TerrainShaderTransformer.raiseVersion(applyOptions(src.fragment())));
        }
        if (program != null) {
            String active = GlslConditionals.stripInactive(applyOptions(src.fragment()));
            int[] rt = colorOutput ? parseRenderTargets(active) : new int[0];
            this.preparePrograms.add(new Pass(program, rt, "prepare", colorOutput ? parseMipmaps(active) : new int[0]));
            log("[chain] prepare " + (colorOutput ? "RENDERTARGETS=" + java.util.Arrays.toString(rt)
                    : "runs SIDE-EFFECT-ONLY (no colour writes; image ops live in its vertex)"));
        }
    }

    private static final Pattern DRAWBUFFERS_RE = Pattern.compile("/\\*\\s*DRAWBUFFERS\\s*:\\s*([0-9]+)\\s*\\*/");
    private static final Pattern RENDERTARGETS_RE = Pattern.compile("/\\*\\s*RENDERTARGETS\\s*:\\s*([0-9,\\s]+?)\\s*\\*/");
    private static final Pattern MIPMAP_RE =
            Pattern.compile("colortex(\\d+)MipmapEnabled\\s*=\\s*true");

    /** @return the colortex indices a fullscreen program declares {@code const bool colortexNMipmapEnabled = true} for —
     * the buffers it reads via {@code texture2DLod} and therefore needs a live mip pyramid for (else blocky garbage). */
    private static int[] parseMipmaps(String fragment) {
        java.util.LinkedHashSet<Integer> set = new java.util.LinkedHashSet<>();
        Matcher m = MIPMAP_RE.matcher(fragment);
        while (m.find()) {
            try {
                set.add(Integer.parseInt(m.group(1)));
            } catch (NumberFormatException ignored) {
            }
        }
        int[] out = new int[set.size()];
        int i = 0;
        for (int idx : set) {
            out[i++] = idx;
        }
        return out;
    }

    /**
     * Parses a fullscreen program's output layout from its (already branch-stripped) source. Takes the LAST
     * {@code DRAWBUFFERS}/{@code RENDERTARGETS} directive by source position — the one the GL loader's final
     * {@code glDrawBuffers} uses (matching the {@code gl_FragData[i]} writes that follow it). Because inactive {@code #if}
     * branches are already removed, the remaining directives are all real, so "last wins" is exact — and, unlike the old
     * "longest of {@code {0}} default vs matches", a single-target {@code DRAWBUFFERS:3} is correctly adopted (the old
     * strict length compare left it as {@code [0]}, so single-buffer passes wrote the wrong colortex).
     * {@code DRAWBUFFERS} digits are single colortex indices; {@code RENDERTARGETS} is a comma list (supports indices >9).
     * Defaults to {@code {0}} only when no directive is present.
     */
    private static int[] parseRenderTargets(String fragment) {
        int[] best = {0};
        int bestPos = -1;
        Matcher m = DRAWBUFFERS_RE.matcher(fragment);
        while (m.find()) {
            String digits = m.group(1);
            int[] list = new int[digits.length()];
            for (int i = 0; i < digits.length(); i++) {
                list[i] = digits.charAt(i) - '0';
            }
            if (m.start() > bestPos) { bestPos = m.start(); best = list; }
        }
        m = RENDERTARGETS_RE.matcher(fragment);
        while (m.find()) {
            String[] parts = m.group(1).split("\\s*,\\s*");
            int[] list = new int[parts.length];
            boolean ok = true;
            for (int i = 0; i < parts.length; i++) {
                try { list[i] = Integer.parseInt(parts[i].trim()); }
                catch (NumberFormatException e) { ok = false; break; }
            }
            if (ok && m.start() > bestPos) { bestPos = m.start(); best = list; }
        }
        return best;
    }

    private GlslProgram compileFullscreen(ProgramSource src, String name) {
        String vsh = src.vertex() != null ? src.vertex() : DEFAULT_FULLSCREEN_VSH;
        String fsh = applyOptions(src.fragment());
        if (DIAG_VL_ENCODE && "composite1".equals(name)) {
            fsh = injectVlEncode(fsh);
        }
        if (DIAG_D1_ENCODE && "deferred1".equals(name)) {
            fsh = injectD1Encode(fsh);
        }
        return GlslProgram.compile(name, applyOptions(vsh), fsh);
    }

    /** DIAGNOSTIC (END island 1px bright silhouette line): the line is BORN in deferred1 (1px scanline: boundary
     * pixel 0.19→0.46 while both neighbours stay put). Exports deferred1's terrain-branch internals through the
     * ct5 write: R = colour after SSAO, G = colour after DoFog, B = skyFade; -1 sentinels reveal a boundary pixel
     * that took the SKY branch instead (a gate bug would show as -1 at a terrain-depth column). ct5.a
     * (cloudLinearDepth) is preserved; waterRefColor is stomped for the diag run (water reflections off is fine). */
    private static final boolean DIAG_D1_ENCODE = false; // rim RESOLVED (white-sky fill + bokeh taps) — kept for future deferred1 numerics

    private String injectD1Encode(String fsh) {
        String decl = "float skyFade = 0.0;";
        log("[D1 encode] decl anchor=" + (fsh.contains(decl) ? "HIT" : "*** MISS ***"));
        fsh = fsh.replace(decl, decl + "\n    float yl_a = -1.0; float yl_b = -1.0; float yl_sf = -1.0;");
        String ssao = "color.rgb *= ssao;";
        log("[D1 encode] ssao anchor=" + (fsh.contains(ssao) ? "HIT" : "*** MISS ***"));
        fsh = fsh.replace(ssao, ssao + "\n        yl_a = dot(color.rgb, vec3(0.3333));");
        String fog = "DoFog(color, skyFade, lViewPos, playerPos, VdotU, VdotS, dither, false, 0.0);";
        log("[D1 encode] fog anchor=" + (fsh.contains(fog) ? "HIT" : "*** MISS ***"));
        fsh = fsh.replace(fog, fog + "\n        yl_b = dot(color.rgb, vec3(0.3333)); yl_sf = skyFade;");
        String out5 = "gl_FragData[1] = vec4(waterRefColor, cloudLinearDepth);";
        log("[D1 encode] ct5 anchor=" + (fsh.contains(out5) ? "HIT" : "*** MISS ***"));
        int oi = fsh.indexOf(out5); // first occurrence only — VOXY branches reuse the expression on other indices
        if (oi >= 0) {
            fsh = fsh.substring(0, oi) + "gl_FragData[1] = vec4(yl_a, yl_b, yl_sf, cloudLinearDepth);"
                    + fsh.substring(oi + out5.length());
        }
        return fsh;
    }

    /** DIAGNOSTIC (END island white rim): exports the ender-beam march's internals through composite1's ct5 write —
     * R = the island-melt fog factor, G = rayEnd/2000, B = lViewPos1/2000 (the pre-melt surface distance); ct5.a
     * (vlFactorM, the temporal feedback) is PRESERVED. DIAG_RIM_SCANLINE then profiles ct5 across the island edge:
     * if fog stays ~0 at the island columns, the melt never fires and the far-uniform-semantics suspicion
     * (RD*16 here vs RD*16*4 on modern MC) is confirmed — see memory end-island-white-rim. The anchors sit inside
     * `#if defined END` blocks; the GLSL preprocessor gates them, so this injects safely for every dimension. */
    private static final boolean DIAG_VL_ENCODE = false; // rim RESOLVED (config diff) — kept for future VL numerics

    private String injectVlEncode(String fsh) {
        String decl = "vec4 GetVolumetricLight(";
        int di = fsh.indexOf(decl);
        log("[VL encode] decl anchor=" + (di >= 0 ? "HIT" : "*** MISS ***"));
        if (di >= 0) {
            fsh = fsh.substring(0, di)
                    + "float yl_dbgFog = 0.0; float yl_dbgRayEnd = 0.0; float yl_dbgLV1 = 0.0;\n"
                    + fsh.substring(di);
        }
        String melt = "lViewPos0 = mix(lViewPos0, maxDistance, fog);";
        log("[VL encode] melt anchor=" + (fsh.contains(melt) ? "HIT" : "*** MISS ***"));
        fsh = fsh.replace(melt, melt + "\n        yl_dbgFog = fog; yl_dbgRayEnd = rayEnd; yl_dbgLV1 = lViewPos1;");
        String out5 = "gl_FragData[1] = vec4(lightFogLength, 0.0, 0.0, vlFactorM);";
        log("[VL encode] ct5 anchor=" + (fsh.contains(out5) ? "HIT" : "*** MISS ***"));
        fsh = fsh.replace(out5,
                "gl_FragData[1] = vec4(yl_dbgFog, yl_dbgRayEnd / 2000.0, yl_dbgLV1 / 2000.0, vlFactorM);");
        return fsh;
    }

    /** Compiles a world-geometry pack program (e.g. gbuffers_sky*) that reads the fixed-function builtins; needs both stages. */
    private GlslProgram compileWorldProgram(ShaderPack pack, String name) {
        ProgramSource src = pack.programSet().get(name);
        if (src != null && src.vertex() != null && src.fragment() != null) {
            String fragment = fixEndPortalEyeHeight(applyOptions(src.fragment()));
            if ("gbuffers_basic".equals(name)) {
                // 1.12.2's selection box is a LINE_STRIP whose edge-to-edge jumps are drawn with alpha = 0 — invisible
                // under vanilla's colour blend, but the pack's basic fragment hard-writes colortex6 (materialMask,
                // blend off), so those invisible DIAGONAL connector segments ERASED the material of whatever glossy
                // block was targeted (a matte diagonal line across obsidian / end-portal-frame faces while aiming at
                // them). Modern MC draws lines without connectors, so the pack never guards this. Discarding the
                // alpha-0 fragments removes the connectors entirely; the visible outline (alpha 0.4) is untouched.
                fragment = fragment.replace("vec4 color = glColor;",
                        "vec4 color = glColor; if (color.a < 0.005) discard;");
            }
            return GlslProgram.compile(name,
                    TerrainShaderTransformer.raiseVersion(applyOptions(src.vertex())),
                    TerrainShaderTransformer.raiseVersion(fragment));
        }
        return null;
    }

    /**
     * endPortalEffect.glsl parallax-height correction (a no-op for every program that doesn't inline it). The effect's
     * depth layers scale by {@code abs(playerPos.y)} = "how high the CAMERA is above the portal plane" — written for
     * real Iris, where playerPos is EYE-relative. This port's playerPos is FEET-relative (the camera-feet invariant
     * Sodium's geometry requires), so the height came out ~eyeHeight (1.62) too small: at point-blank range the layer
     * zoom was ~3x too sensitive and the pattern visibly changed as the player rose or jumped. Adding
     * {@code relativeEyePosition.y} (= −eyeHeight, fed per frame; the pack's own held-light code applies the exact
     * same correction) restores the eye-based height the formula was written for. XZ branches need no fix — the eye
     * offset is Y-only.
     */
    private static String fixEndPortalEyeHeight(String fragment) {
        if (DIAG_PORTAL_FEATURES_OFF) {
            return fragment;
        }
        return fragment
                // The ray reconstruction: `(gbufferModelViewInverse * vec4(viewPos * K, 1.0))` is AFFINE (w=1). On real
                // Iris the modelview has no translation (eye origin), so the K scale cancels in normalize() and this is
                // just the player-space view DIRECTION. Our feet-based modelview carries a +eyeHeight translation, so
                // the same expression tilted the direction by eyeHeight/K — per-LAYER, view-distance-dependent warping
                // (the "effect changes as the player rises" symptom). mat3() is the translation-free form both
                // conventions agree on.
                .replace("vec3 wpos = normalize((gbufferModelViewInverse * vec4(viewPos * (i * dismult + 1), 1.0)).xyz);",
                         "vec3 wpos = normalize(mat3(gbufferModelViewInverse) * viewPos);")
                .replace("wpos.xz *= 0.06 * sign(- playerPos.y);",
                         "wpos.xz *= 0.06 * sign(-(playerPos.y + relativeEyePosition.y));")
                .replace("wpos.xz *= abs(playerPos.y) + i * dismult + add;",
                         "wpos.xz *= abs(playerPos.y + relativeEyePosition.y) + i * dismult + add;");
    }

    /** @return the colortex indices a pack program's ACTIVE (options-applied, branch-stripped) fragment writes via its
     * {@code RENDERTARGETS}/{@code DRAWBUFFERS} directive (its {@code gl_FragData[i]} order); {@code null} if absent. */
    private int[] parseProgramTargets(ShaderPack pack, String name) {
        ProgramSource src = pack.programSet().get(name);
        if (src == null || src.fragment() == null) {
            return null;
        }
        return parseRenderTargets(GlslConditionals.stripInactive(applyOptions(src.fragment())));
    }

    /**
     * Compiles a below-horizon variant of {@code gbuffers_skybasic}: the pack fragment with an early {@code discard} for
     * above-horizon pixels injected right after it computes {@code VdotU} (view·up). Drawn fullscreen in {@link #endSky},
     * it paints the pack's real {@code GetSky} onto the below-horizon sky where MC 1.12.2 otherwise leaves a flat
     * fog-coloured plane. Returns null (fill skipped) if the pack has no skybasic or the {@code VdotU} anchor isn't found.
     */
    private GlslProgram compileSkybasicBelow(ShaderPack pack) {
        ProgramSource src = pack.programSet().get("gbuffers_skybasic");
        if (src == null || src.vertex() == null || src.fragment() == null) {
            return null;
        }
        String frag = src.fragment();
        // Anchor on the pack's own `float VdotU = dot(nViewPos, upVec);` and discard everything ABOVE the horizon fog band,
        // so the fill only repaints the near-horizon strip (the below-horizon sky-ground AND the thin above-horizon strip
        // where MC 1.12.2's fixed-function horizon-fog plane overwrites the dome with the flat fog colour). The cutoff
        // (VdotU > 0.15, ≈8.6°) clears the fog band with margin; the fill's GetSky is identical to the dome's above it, so
        // no seam. The sun sits far higher (VdotU≈1 at day) so it isn't touched. If the anchor isn't present, skip the fill.
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(float\\s+VdotU\\s*=\\s*dot\\s*\\(\\s*nViewPos\\s*,\\s*upVec\\s*\\)\\s*;)").matcher(frag);
        if (!m.find()) {
            log("skybasic-below: VdotU anchor not found — below-horizon sky fill disabled");
            return null;
        }
        frag = m.replaceFirst("$1 if (VdotU > 0.15) { discard; }");
        if (DIAG_SKYFILL_RED) {
            // Paint the below-horizon fill bright RED so we can see EXACTLY which pixels it lands on (vs the grey band).
            frag = frag.replaceFirst("(color\\.rgb\\s*=\\s*GetSky\\s*\\([^;]*\\)\\s*;)", "$1 color.rgb = vec3(1.0, 0.0, 0.0);");
        }
        try {
            return GlslProgram.compile("skybasic_below",
                    TerrainShaderTransformer.raiseVersion(applyOptions(src.vertex())),
                    TerrainShaderTransformer.raiseVersion(applyOptions(frag)));
        } catch (Throwable t) {
            log("skybasic-below compile failed — below-horizon sky fill disabled: " + t);
            return null;
        }
    }

    // --- sky (immediate-mode) hooks, called from MixinRenderGlobalSky around RenderGlobal.renderSky ---

    /**
     * Marks the sky phase active (does NOT bind eagerly): the first {@code disableTexture2D} in renderSky binds skybasic,
     * and nothing is drawn before it — this also avoids binding during the End dimension's {@code renderSkyEnd}, whose
     * texture toggles the mixin's redirects don't cover.
     */
    public void beginSky() {
        this.skyActive = this.enabled && this.activeThisFrame && this.skybasicProgram != null;
        this.celestialTiltApplied = false;
        this.skyMatricesCaptured = false; // re-capture this frame's camera matrices at the first sky bind
        if (this.skyActive && !this.skyLogged) {
            this.skyLogged = true;
            log("sky rendered by pack gbuffers_skybasic"
                    + (this.skytexturedProgram != null ? " + gbuffers_skytextured" : " (no skytextured; sun/moon vanilla)"));
        }
    }

    /** Untextured sky (dome, sunrise/sunset glow, stars, void) → skybasic. */
    public void skyBindBasic() {
        if (this.skyActive) {
            this.skybasicProgram.use();
            // gbuffers_skybasic divides gl_FragCoord.xy by viewWidth/viewHeight; unset (0) → Inf → NaN over the whole sky.
            // Feed the scene uniforms (which include viewWidth/viewHeight + matrices + sun) so it computes a valid sky.
            setSceneUniforms(this.skybasicProgram);
            applySkyMatrices(this.skybasicProgram); // MUST follow setSceneUniforms — overrides its 1-frame-old matrices
            // The procedural sun/moon (SUN_MOON_STYLE >= 2) + moon-crater noise + dithering sample noisetex; without it the
            // moon/stars pattern reads garbage. Bind the real noise texture on its own unit for this immediate-mode draw.
            bindNoiseForImmediate(this.skybasicProgram);
            applyAlphaOverride("gbuffers_skybasic"); // pack: GREATER 0.0001 — keep the sky's faint fragments
        }
    }

    // The CURRENT frame's camera matrices, read from GL at the first sky bind of the frame. The pipeline's regular
    // gbufferModelView/Projection are captured during the TERRAIN draw — which happens AFTER the sky — so during the
    // sky phase setSceneUniforms hands the pack LAST frame's matrices, and everything skybasic reconstructs from the
    // per-pixel view ray (the procedural starfield, the nebula, the atmosphere gradient) lagged one frame behind
    // camera rotation. Real Iris updates its camera uniforms at the start of the frame, so it never lags; capturing
    // the live GL matrices at sky time (the same trick applyHandProjection uses for the hand's projection) matches it.
    // The host cloud layer used to sit on top of the pack sky and masked the lag; clouds=off exposed it.
    private final Matrix4f skyModelView = new Matrix4f();
    private final Matrix4f skyModelViewInverse = new Matrix4f();
    private final Matrix4f skyProjection = new Matrix4f();
    private final Matrix4f skyProjectionInverse = new Matrix4f();
    private boolean skyMatricesCaptured;

    /**
     * Feeds a sky program THIS frame's camera matrices + the celestial directions recomputed with them. Captured once
     * per sky phase, at the FIRST sky bind — i.e. before MC pushes the celestial-tilt rotations, so the captured
     * modelview is the plain camera view (what gbufferModelView means), not the sun-quad transform. NOT called from
     * fillBelowHorizonSky's own capture path: that helper draws under pushed IDENTITY matrices, so it must reuse the
     * matrices captured here (guarded by {@link #skyMatricesCaptured}).
     */
    private void applySkyMatrices(GlslProgram p) {
        if (!this.skyMatricesCaptured) {
            this.skyMatricesCaptured = true;
            this.matrixReadback.clear();
            GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, this.matrixReadback);
            this.skyModelView.set(this.matrixReadback);
            this.skyModelView.invert(this.skyModelViewInverse);
            this.matrixReadback.clear();
            GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, this.matrixReadback);
            this.skyProjection.set(this.matrixReadback);
            this.skyProjection.invert(this.skyProjectionInverse);
        }
        p.setMatrix4("gbufferModelView", this.skyModelView);
        p.setMatrix4("gbufferModelViewInverse", this.skyModelViewInverse);
        p.setMatrix4("gbufferProjection", this.skyProjection);
        p.setMatrix4("gbufferProjectionInverse", this.skyProjectionInverse);
        // The view-space celestial directions must come from the SAME (current) modelview, or the sun/moon glow and the
        // shadow-light tint lag a frame behind the freshly-updated sky dome. Same math as captureMatrices.
        float a = this.celestialAngle * 6.2831855F;
        Vector3f sunView = this.skyModelView.transformDirection(
                new Vector3f(0.0F, (float) Math.cos(a), (float) Math.sin(a)));
        p.setVec3("sunPosition", sunView.x, sunView.y, sunView.z);
        p.setVec3("moonPosition", -sunView.x, -sunView.y, -sunView.z);
        Vector3f upView = this.skyModelView.transformDirection(new Vector3f(0.0F, 1.0F, 0.0F));
        p.setVec3("upPosition", upView.x, upView.y, upView.z);
        boolean sunUp = Math.cos(a) >= 0.0;
        p.setVec3("shadowLightPosition",
                sunUp ? sunView.x : -sunView.x, sunUp ? sunView.y : -sunView.y, sunUp ? sunView.z : -sunView.z);
    }

    /** Textured sky (sun, moon) → skytextured sampling the bound celestial texture at unit 0. */
    public void skyBindTextured() {
        if (!this.skyActive) {
            return;
        }
        if (this.skytexturedProgram != null) {
            this.skytexturedProgram.use();
            // The pack samples the sun/moon atlas via `tex` (NOT gtexture); MC has it bound on unit 0 here. Set both so
            // whichever the program actually declares resolves to unit 0. (With SUN_MOON_STYLE >= 2 the textured sun/moon
            // are discarded and drawn procedurally in skybasic; `tex` still matters for custom-sky quads.)
            this.skytexturedProgram.setInt("tex", 0);
            this.skytexturedProgram.setInt("gtexture", 0);
            // renderStage identifies the sun vs moon quad. Complementary needs it: with SUN_ANGLE=-1 + SHADER_STYLE=4 the
            // pack bakes sunPathRotation=-40°, so its internal sunVec is ~40° off MC's real sun quad → the geometric
            // VdotS>0.95 "is this the sun?" test fails and the sun quad is DISCARDED (custom-sky → discard on MC<1.13).
            // Feeding renderStage=SUN makes isSun true directly (like real Iris). Default to SUN; the bindTexture redirect
            // in MixinRenderGlobalSky flips it to MOON for the moon quad. MC_RENDER_STAGE_SUN=4.
            this.skytexturedProgram.setInt("renderStage", 4);
            setSceneUniforms(this.skytexturedProgram);
            applySkyMatrices(this.skytexturedProgram); // current-frame camera matrices (see applySkyMatrices)
            bindNoiseForImmediate(this.skytexturedProgram);
            applyAlphaOverride("gbuffers_skytextured"); // pack: GREATER 0.0001 — the sun/moon fade must not be cut at 0.1
        } else {
            GlslProgram.unuse(); // no textured sky program → sun/moon render vanilla
        }
    }

    /**
     * Updates the bound {@code gbuffers_skytextured} program's {@code renderStage} mid-phase, called from the sky mixin's
     * texture-bind redirect: MC binds sun.png then moon_phases.png between the (single) skytextured bind and each celestial
     * quad, so this is how we tag which body is being drawn. {@code stage} is an {@code MC_RENDER_STAGE_*} value.
     */
    public void setSkyRenderStage(int stage) {
        if (this.skyActive && this.skytexturedProgram != null) {
            this.skytexturedProgram.setInt("renderStage", stage);
        }
    }

    /**
     * Rotates MC's celestial frame (sun/moon/stars) by the pack's {@code sunPathRotation} so the textured sun DISC lands on
     * the pack's atmosphere sun. Called from the sky mixin at the sun's {@code bindTexture} — at that point MC's modelview
     * is {@code V·A·B} (A = {@code rotate(-90,0,1,0)}, B = {@code rotate(celestialAngle·360,1,0,0)}). We want the disc at
     * {@code V·RotX_world(-spr)·A·B}, i.e. a world-X rotation inserted before A. Since GL only post-multiplies, we apply the
     * equivalent local rotation {@code M = (A·B)^-1·RotX(-spr)·(A·B)} — same angle, axis {@code (A·B)^-1·(1,0,0) =
     * (0,-sinβ,-cosβ)}, β = {@code celestialAngle·2π}. Applied once (inside MC's celestial pushMatrix, so its popMatrix
     * cleans it up — no leak); the persisting rotation also tilts the moon + stars, matching the pack.
     */
    public void applyCelestialSunPathTilt() {
        if (!this.skyActive || this.celestialTiltApplied) {
            return;
        }
        this.celestialTiltApplied = true;
        float spr = sunPathRotationDegrees();
        if (spr == 0.0F) {
            return;
        }
        float beta = this.celestialAngle * 6.2831855F;
        float s = (float) Math.sin(beta);
        float c = (float) Math.cos(beta);
        GlStateManager.rotate(-spr, 0.0F, -s, -c); // angle = -spr (= RotX(-spr)); axis (A·B)^-1·x = (0,-sinβ,-cosβ)
    }

    /**
     * Reproduces the pack's overworld {@code sunPathRotation} (a compile-time const the shader derives from the SUN_ANGLE
     * option) so the host can tilt MC's celestial quads to match. Complementary: SUN_ANGLE == -1 → 0° for SHADER_STYLE 1,
     * −40° for SHADER_STYLE 4 (Unbound); any other SUN_ANGLE value IS the rotation in degrees.
     */
    private float sunPathRotationDegrees() {
        ShaderOptionSet opts = shaderOptions();
        int sunAngle = intOption(opts, "SUN_ANGLE", -1);
        if (sunAngle == -1) {
            return intOption(opts, "SHADER_STYLE", 4) == 1 ? 0.0F : -40.0F;
        }
        return sunAngle;
    }

    /**
     * Advances the temporally-smoothed weather uniforms once per frame. Complementary never reads the raw rainStrength
     * for its wet look — it reads {@code wetness} (which OptiFine/Iris smooth using the pack's
     * {@code const float wetnessHalflife/drynessHalflife}) and the {@code smooth(...)} custom uniforms
     * {@code rainFactor}/{@code thunderFactor}/{@code inRainy} declared in shaders.properties. Feeding those the raw
     * value made the wet-ground reflection appear/vanish the instant the weather changed instead of easing over the
     * pack's RAIN_FADE_TIMER (and the ground drying over the halflife).
     */
    private void updateWeatherSmoothing() {
        float now = (System.nanoTime() - this.startNanos) / 1.0e9F;
        float dt = this.lastSmoothSeconds < 0.0F ? 0.0F : now - this.lastSmoothSeconds;
        this.lastSmoothSeconds = now;
        // Clamp: a loading screen / lag spike / pause would otherwise hand us a huge dt and snap everything to target,
        // reintroducing exactly the popping this smoothing exists to remove.
        dt = Math.max(0.0F, Math.min(dt, 0.25F));

        // wetness: OptiFine semantics — halflives are in TICKS, so convert dt to ticks (20/s).
        this.wetness = this.wetnessSmoother.update(this.rainStrength, dt * 20.0F,
                this.wetnessHalflifeTicks, this.drynessHalflifeTicks);
        // smooth(...) custom uniforms: fade times in SECONDS, taken from the live shaders.properties directives. These are
        // the FAST ones (3 s) — they drive the atmosphere (clouds/sky/fog/rain), which should track the weather promptly.
        // Only `wetness` above is slow, and it alone controls how the wet-ground reflection eases in and out.
        this.rainFactor = this.rainFactorSmoother.update(this.rainStrength, dt,
                this.rainFadeUpSeconds, this.rainFadeDownSeconds);
        this.thunderFactor = this.thunderFactorSmoother.update(this.thunderStrength, dt,
                this.thunderFadeUpSeconds, this.thunderFadeDownSeconds);
        this.inRainySmooth = this.inRainySmoother.update(this.inRainy, dt,
                this.inRainyFadeUpSeconds, this.inRainyFadeDownSeconds);
        this.inDrySmooth = this.inDrySmoother.update(this.inDry, dt,
                this.inDryFadeUpSeconds, this.inDryFadeDownSeconds);
        this.inSnowySmooth = this.inSnowySmoother.update(this.inSnowy, dt,
                this.inSnowyFadeUpSeconds, this.inSnowyFadeDownSeconds);
        // eyeBrightnessSky is gathered earlier in gatherWorldState, so this frame's value is already current here.
        this.eyeBrightnessM2 = this.eyeBrightM2Smoother.update(this.eyeBrightnessSky > 239 ? 1.0F : 0.0F, dt,
                this.eyeBrightM2FadeUpSeconds, this.eyeBrightM2FadeDownSeconds);
        this.eyeBrightnessMSmoothed = this.eyeBrightMSmoother.update(this.eyeBrightnessSky / 240.0F, dt,
                this.eyeBrightMFadeUpSeconds, this.eyeBrightMFadeDownSeconds);
        // OptiFine's eyeBrightnessHalflife default = 10 ticks = 0.5 s, both directions (pack does not override it).
        this.eyeBrightnessSmoothX = this.eyeSmoothXSmoother.update(this.eyeBrightnessBlock, dt, 0.5F, 0.5F);
        this.eyeBrightnessSmoothY = this.eyeSmoothYSmoother.update(this.eyeBrightnessSky, dt, 0.5F, 0.5F);
    }

    private static final Pattern HALFLIFE_RE =
            Pattern.compile("const\\s+float\\s+(wetness|dryness)Halflife\\s*=\\s*([0-9.]+)");
    /** {@code smooth(<id>, <value>, <fadeUpSeconds>, <fadeDownSeconds>)} — captures the two fade times. */
    private static final Pattern SMOOTH_RE =
            Pattern.compile("smooth\\s*\\([^,]+,.+?,\\s*([0-9.]+)\\s*,\\s*([0-9.]+)\\s*\\)\\s*$");

    /** @return the fade up/down times of the ACTIVE {@code uniform.float.<name> = smooth(...)} directive, or the fallback. */
    private static float[] smoothFade(java.util.Map<String, String> props, String name, float defUp, float defDown) {
        String v = props.get("uniform.float." + name);
        if (v != null) {
            Matcher m = SMOOTH_RE.matcher(v.trim());
            if (m.find()) {
                try {
                    return new float[]{Float.parseFloat(m.group(1)), Float.parseFloat(m.group(2))};
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return new float[]{defUp, defDown};
    }

    /**
     * Reads the pack's real weather fade timings instead of assuming them: {@code const float
     * wetnessHalflife/drynessHalflife} from a compiled program source (lib/pipelineSettings.glsl is inlined into every
     * program by our include resolver), and the {@code smooth(...)} fade times from the PREPROCESSED shaders.properties.
     *
     * <p>Reading the properties (rather than the RAIN_FADE_TIMER option directly) is what makes this correct: the pack
     * declares rainFactor under {@code #if IRIS_VERSION >= 10800} → RAIN_FADE_TIMER, {@code #else} → a literal 3, and we
     * deliberately don't define IRIS_VERSION >= 10800 (it breaks the camera position). So RAIN_FADE_TIMER is NOT our fade
     * time — 3 s is — and the slow one is {@code wetness} (300-tick halflife = 15 s), which is exactly what drives the
     * wet-ground reflection: at RAIN_PUDDLES=2, {@code pFormNoise = wetnessM} → {@code puddleMixer *= wetness} →
     * {@code smoothnessD = mix(smoothnessD, 1.0, sqrt1(puddleMixer))}. rainFactor only drives the atmosphere.</p>
     */
    /** {@code blend.<program>.colortex<N> = off} → the colortex indices a program must NOT alpha-blend into. */
    private java.util.Map<String, java.util.Set<Integer>> blendOffByProgram = java.util.Collections.emptyMap();

    private static final Pattern BLEND_OFF_RE =
            Pattern.compile("^blend\\.([A-Za-z0-9_]+)\\.colortex(\\d+)$");

    /**
     * Parses the pack's {@code blend.<program>.colortex<N> = off} directives.
     *
     * <p>This is a real Iris feature the port simply never implemented, and its absence is what blackened the water's
     * world-space reflection: see {@link RenderTargets#beginTargets(int[], java.util.Set)} for the measured chain.
     * Complementary turns blending off for colortex6 (the material buffer) on essentially every gbuffers program, and
     * additionally for colortex4/colortex8 on gbuffers_water — precisely because a translucent pass would otherwise
     * blend its material/normal/SSR G-buffer writes into the opaque geometry behind it.</p>
     */
    private void parseBlendDirectives(ShaderPack pack) {
        java.util.Map<String, java.util.Set<Integer>> map = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, String> e : resolvedProperties(pack).entrySet()) {
            Matcher m = BLEND_OFF_RE.matcher(e.getKey().trim());
            if (!m.matches() || !"off".equalsIgnoreCase(e.getValue().trim())) {
                continue;
            }
            map.computeIfAbsent(m.group(1), k -> new java.util.HashSet<>()).add(Integer.parseInt(m.group(2)));
        }
        this.blendOffByProgram = map;
        if (map.isEmpty()) {
            log("WARNING: no `blend.<program>.colortexN = off` directives resolved — a translucent pass will alpha-blend"
                    + " its G-buffer material writes into the geometry behind it");
        } else {
            log("blend off: " + new java.util.TreeMap<>(map));
        }
    }

    /** The colortex indices {@code name} must not blend into (empty if the pack said nothing). */
    private java.util.Set<Integer> blendOffFor(String name) {
        return this.blendOffByProgram.getOrDefault(name, java.util.Collections.emptySet());
    }

    /** Program names the pack disables for the CURRENT config via {@code program.<world>/<name>.enabled = false},
     * scoped to the dimension folder the pack was scanned for (the pack repeats each directive per world). The gates
     * are real config logic (composite8 = RENKO_CUT, composite9 = LONG_EXPOSURE, shadowcomp = COLORED_LIGHTING);
     * before this was parsed, composite8+9 ran EVERY frame while Iris would skip them. */
    private java.util.Set<String> disabledPrograms = java.util.Collections.emptySet();

    private void parseDisabledPrograms(ShaderPack pack) {
        String prefix = "program." + pack.worldFolder(); // "program.world0/", "program.world-1/", ...
        java.util.Set<String> set = new java.util.HashSet<>();
        for (java.util.Map.Entry<String, String> e : resolvedProperties(pack).entrySet()) {
            String key = e.getKey();
            if (key.startsWith(prefix) && key.endsWith(".enabled")
                    && "false".equalsIgnoreCase(e.getValue().trim())) {
                set.add(key.substring(prefix.length(), key.length() - ".enabled".length()));
            }
        }
        this.disabledPrograms = set;
        if (!set.isEmpty()) {
            log("programs disabled by the pack for this config: " + new java.util.TreeSet<>(set));
        }
    }

    // --- misc pack directives (parsed once per pipeline init from the RESOLVED shaders.properties) -----------------

    /** {@code vignette = false}: the pack does its own vignette in post — the vanilla screen-edge darkening on top of
     * it double-darkens, so it must be suppressed while shaders are on. */
    private boolean packVignette = true;
    /** {@code underwaterOverlay = true}: the vanilla first-person water-texture overlay draws with shaders on ONLY
     * when a pack explicitly asks for it — absent means hidden, matching OptiFine (PropertyDefaultTrueFalse, explicit
     * true only) and Iris ({@code orElse(false)}). The old absent→show default let the built-in pack (no directive)
     * and the pre-init window draw the overlay over the pack's own underwater look (2026-07-31). */
    private boolean packUnderwaterOverlay = false;
    /** {@code rain.depth = false}: rain streaks must not write scene depth (they would poke holes into the composite's
     * depth-driven fog/DOF). */
    private boolean packRainDepth = true;
    /** {@code clouds = off}: the pack renders its own volumetric clouds — the host's cloud layer must not draw. */
    private boolean packVanillaClouds = true;
    /** centerDepthSmooth: the RAW (nonlinear) depthtex0 value at screen centre, exponentially smoothed — the DOF
     * auto-focus uniform. Complementary's composite3 (WORLD_BLUR=2 + WB_DOF_FOCUS=0) compares it DIRECTLY against
     * raw z1, no linearization (its fixed-focus branch converts blocks→depth-buffer space to match). Unset it read
     * 0.0 = focus pinned to the near plane → everything except the hand rendered at maximum blur (2026-07-31).
     * Half-life = {@code centerDepthHalflife} directive in TENTHS of a second (OptiFine/Iris default 1.0 = 0.1 s).
     * Starts at 1.0 (far plane) so the first frames blur-in from "focused at infinity", not from a blurred mess. */
    private float centerDepthSmooth = 1.0F;
    private float centerDepthHalflife = 1.0F;
    /** Whether any fullscreen pass links centerDepthSmooth — the per-frame 1-texel depth readback costs a GPU sync,
     * so packs without auto-focus DOF must not pay it. Computed at pipeline init. */
    private boolean wantsCenterDepth;
    /** {@code shadowPlayer = true}: the PLAYER (+ vehicle) still casts a shadow even though the pack turns entity
     * shadow casters off (shadowEntities = false). See {@link #shadowPlayerEnabled}. */
    private boolean packShadowPlayer;
    /** {@code shadowEntities} (Iris default TRUE): ALL entities cast shadows. Complementary emits
     * {@code shadowEntities = false} unless the ENTITY_SHADOW option is >= 1 (GUI: パフォーマンス設定 →
     * エンティティの影; profiles HIGH+ default it to 1, but the user's config may override to -1 = off).
     * See {@link #shadowEntitiesEnabled}. */
    private boolean packShadowEntities = true;
    /** {@code shadowBlockEntities} (Iris default TRUE): block entities (TESRs — chests, banners, beds …) cast
     * shadows; Complementary disables it below ENTITY_SHADOW == 2. See {@link #shadowBlockEntitiesEnabled}. */
    private boolean packShadowBlockEntities = true;
    /** {@code oldLighting = true}: the pack WANTS the vanilla diffuse face shade baked into vertex colors. Almost all
     * modern packs (incl. Complementary) set false and shade directionally themselves. See {@link #stripVanillaFaceShade}. */
    private boolean packOldLighting;
    /** {@code texture.deferred.colortex3} present: DEFERRED passes read the cloud noise texture through the colortex3
     * unit (reimaginedClouds' deferred branch) instead of the colortex3 buffer. */
    private boolean deferredColortex3Override;
    /** The pack path of the gbuffers gaux4 texture ({@code texture.gbuffers.gaux4}) — honours CLOUD_TEXTURE instead of
     * hardcoding cloud-water.png. */
    private String gaux4TexturePath = "lib/textures/cloud-water.png";
    /** {@code alphaTest.<program> = <FUNC> <ref>} → program name → {GL func, ref}. Only the FUNC/REF are applied (via
     * glAlphaFunc) — the test's own on/off state stays with the host draw, so a pass vanilla runs untested is untouched
     * while one vanilla tests at 0.1 (rain!) keeps the pack's much lower cutoff instead of losing faint fragments. */
    private java.util.Map<String, float[]> alphaTestOverrides = java.util.Collections.emptyMap();

    private static int alphaFuncOf(String name) {
        switch (name.toUpperCase(java.util.Locale.ROOT)) {
            case "NEVER": return GL11.GL_NEVER;
            case "LESS": return GL11.GL_LESS;
            case "EQUAL": return GL11.GL_EQUAL;
            case "LEQUAL": return GL11.GL_LEQUAL;
            case "NOTEQUAL": return GL11.GL_NOTEQUAL;
            case "GEQUAL": return GL11.GL_GEQUAL;
            case "ALWAYS": return GL11.GL_ALWAYS;
            case "GREATER":
            default: return GL11.GL_GREATER;
        }
    }

    private void parseMiscDirectives(ShaderPack pack) {
        java.util.Map<String, String> props = resolvedProperties(pack);
        this.packVignette = !"false".equalsIgnoreCase(props.getOrDefault("vignette", "true").trim());
        this.packUnderwaterOverlay = "true".equalsIgnoreCase(props.getOrDefault("underwaterOverlay", "false").trim());
        this.packRainDepth = !"false".equalsIgnoreCase(props.getOrDefault("rain.depth", "true").trim());
        this.packVanillaClouds = !"off".equalsIgnoreCase(props.getOrDefault("clouds", "on").trim());
        try {
            this.centerDepthHalflife = Float.parseFloat(props.getOrDefault("centerDepthHalflife", "1.0").trim());
        } catch (NumberFormatException e) {
            this.centerDepthHalflife = 1.0F;
        }
        this.packShadowPlayer = "true".equalsIgnoreCase(props.getOrDefault("shadowPlayer", "false").trim());
        this.packShadowEntities = !"false".equalsIgnoreCase(props.getOrDefault("shadowEntities", "true").trim());
        this.packShadowBlockEntities = !"false".equalsIgnoreCase(props.getOrDefault("shadowBlockEntities", "true").trim());
        this.packOldLighting = "true".equalsIgnoreCase(props.getOrDefault("oldLighting", "false").trim());
        log("oldLighting=" + this.packOldLighting + " → vanilla diffuse face shade "
                + (this.packOldLighting ? "KEPT in vertex data" : "STRIPPED (pack does its own directional shading)"));
        this.deferredColortex3Override = props.containsKey("texture.deferred.colortex3");
        String gaux4 = props.get("texture.gbuffers.gaux4");
        if (gaux4 != null && !gaux4.trim().isEmpty()) {
            String path = gaux4.trim();
            this.gaux4TexturePath = path.startsWith("/") ? path.substring(1) : path;
        }
        java.util.Map<String, float[]> alpha = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, String> e : props.entrySet()) {
            if (!e.getKey().startsWith("alphaTest.")) {
                continue;
            }
            String[] parts = e.getValue().trim().split("\\s+");
            if (parts.length == 2) {
                try {
                    alpha.put(e.getKey().substring("alphaTest.".length()),
                            new float[]{alphaFuncOf(parts[0]), Float.parseFloat(parts[1])});
                } catch (NumberFormatException ignored) {
                }
            }
        }
        this.alphaTestOverrides = alpha;
        log("misc directives: vignette=" + this.packVignette + " underwaterOverlay=" + this.packUnderwaterOverlay
                + " rain.depth=" + this.packRainDepth + " clouds=" + (this.packVanillaClouds ? "on" : "off")
                + " shadowPlayer=" + this.packShadowPlayer + " shadowEntities=" + this.packShadowEntities
                + " shadowBlockEntities=" + this.packShadowBlockEntities
                + " deferredColortex3Override=" + this.deferredColortex3Override
                + " gaux4=" + this.gaux4TexturePath + " alphaTest overrides=" + alpha.keySet());
    }

    /** Applies the pack's {@code alphaTest.<program>} FUNC/REF override for a routed program (no-op if none). */
    private void applyAlphaOverride(String programName) {
        float[] o = this.alphaTestOverrides.get(programName);
        if (o != null) {
            GlStateManager.alphaFunc((int) o[0], o[1]);
        }
    }

    /** Restores MC's global default alpha function after a pass that overrode it. */
    private void restoreAlphaFunc() {
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
    }

    /** @return true when the vanilla vignette must be skipped (shaders on + pack {@code vignette = false}). */
    public boolean suppressVignette() {
        return this.enabled && !this.packVignette;
    }

    /** @return true when the vanilla first-person underwater texture overlay must be skipped. */
    public boolean suppressUnderwaterOverlay() {
        return this.enabled && !this.packUnderwaterOverlay;
    }

    /** @return true when the host cloud layer (Yumelium/vanilla clouds) must not render ({@code clouds = off}). */
    public boolean suppressVanillaClouds() {
        return this.enabled && !this.packVanillaClouds;
    }

    private static final Pattern CLEAR_FLAG_RE =
            Pattern.compile("const\\s+bool\\s+colortex(\\d+)Clear\\s*=\\s*(true|false)\\s*;");

    /**
     * Resolves each colortex's per-frame clear flag from the pack's {@code const bool colortexNClear} declarations and
     * installs it into {@link RenderTargets}.
     *
     * <p>The default is CLEAR (OptiFine/Iris semantics): a pack opts a buffer OUT of the frame clear to make it
     * temporal. Only the declarations the preprocessor actually keeps count — Complementary declares colortex10Clear in
     * BOTH arms of {@code #ifdef PHOTONICS_LIGHTING} (true there, false in the SS_BLOCKLIGHT arm) — so the source is
     * branch-stripped first, exactly like buildCustomImages does for the image directives. Same lesson as the terrain
     * RENDERTARGETS: read what the pack says, don't hardcode a list from having read it once.</p>
     */
    private void parseClearFlags(ShaderPack pack) {
        // pipelineSettings.glsl is only pulled in by SOME programs (final/composite6, notably not composite) — probe the
        // ones that do rather than assuming, and take the first that actually yields declarations.
        for (String probe : new String[]{"final", "composite6", "composite"}) {
            ProgramSource ps = pack.programSet().get(probe);
            String src = ps != null ? ps.fragment() : null;
            if (src == null) {
                continue;
            }
            boolean[] flags = new boolean[RenderTargets.NUM_COLORTEX];
            java.util.Arrays.fill(flags, true);
            Matcher m = CLEAR_FLAG_RE.matcher(GlslConditionals.stripInactive(applyOptions(src)));
            java.util.List<String> optedOut = new java.util.ArrayList<>();
            boolean found = false;
            while (m.find()) {
                int idx = Integer.parseInt(m.group(1));
                if (idx < 0 || idx >= RenderTargets.NUM_COLORTEX) {
                    continue;
                }
                found = true;
                if (!Boolean.parseBoolean(m.group(2))) {
                    flags[idx] = false;
                    optedOut.add("colortex" + idx);
                }
            }
            if (found) {
                RenderTargets.setClearFlags(flags);
                log("clear flags from " + probe + ": all cleared each frame EXCEPT " + optedOut
                        + " (pack opted these out; every other colortex — incl. colortex8, the water SSR — is cleared)");
                return;
            }
        }
        log("WARNING: no `const bool colortexNClear` found in any probed program — every colortex will be cleared each"
                + " frame, which will break any buffer the pack means to be temporal (TAA/colortex2, SSBL/colortex10)");
    }

    private void parseWeatherTimings(ShaderPack pack) {
        // The halflives live in lib/pipelineSettings.glsl, which only SOME programs include (Complementary: final +
        // composite6 — notably NOT composite), so probe the programs that actually pull it in rather than assuming.
        boolean found = false;
        for (String probe : new String[]{"final", "composite6", "composite"}) {
            ProgramSource ps = pack.programSet().get(probe);
            String src = ps != null ? ps.fragment() : null;
            if (src == null) {
                continue;
            }
            Matcher m = HALFLIFE_RE.matcher(src);
            while (m.find()) {
                try {
                    float v = Float.parseFloat(m.group(2));
                    if ("wetness".equals(m.group(1))) {
                        this.wetnessHalflifeTicks = v;
                    } else {
                        this.drynessHalflifeTicks = v;
                    }
                    found = true;
                } catch (NumberFormatException ignored) {
                }
            }
            if (found) {
                break;
            }
        }
        if (!found) {
            log("WARNING: no `const float wetnessHalflife/drynessHalflife` found in any probed program — the wet-ground"
                    + " reflection will fade at OptiFine's defaults, not the pack's");
        }
        java.util.Map<String, String> props = resolvedProperties(pack);
        float[] rain = smoothFade(props, "rainFactor", 3.0F, 3.0F);
        this.rainFadeUpSeconds = rain[0];
        this.rainFadeDownSeconds = rain[1];
        float[] thunder = smoothFade(props, "thunderFactor", this.rainFadeUpSeconds, this.rainFadeDownSeconds);
        this.thunderFadeUpSeconds = thunder[0];
        this.thunderFadeDownSeconds = thunder[1];
        float[] rainy = smoothFade(props, "inRainy", 20.0F, 10.0F);
        this.inRainyFadeUpSeconds = rainy[0];
        this.inRainyFadeDownSeconds = rainy[1];
        float[] dry = smoothFade(props, "inDry", 20.0F, 10.0F);
        this.inDryFadeUpSeconds = dry[0];
        this.inDryFadeDownSeconds = dry[1];
        float[] snowy = smoothFade(props, "inSnowy", 20.0F, 10.0F);
        this.inSnowyFadeUpSeconds = snowy[0];
        this.inSnowyFadeDownSeconds = snowy[1];
        float[] eb2 = smoothFade(props, "eyeBrightnessM2", 2.0F, 2.0F);
        this.eyeBrightM2FadeUpSeconds = eb2[0];
        this.eyeBrightM2FadeDownSeconds = eb2[1];
        float[] ebm = smoothFade(props, "eyeBrightnessM", 5.0F, 5.0F);
        this.eyeBrightMFadeUpSeconds = ebm[0];
        this.eyeBrightMFadeDownSeconds = ebm[1];
        log(String.format("weather smoothing: wetness/dryness halflife=%.0f/%.0f ticks (%.1fs/%.1fs — drives the wet-ground"
                        + " reflection) | rainFactor smooth=%.1f/%.1fs (atmosphere) | thunderFactor=%.1f/%.1fs |"
                        + " inRainy=%.1f/%.1fs", this.wetnessHalflifeTicks, this.drynessHalflifeTicks,
                this.wetnessHalflifeTicks / 20.0F, this.drynessHalflifeTicks / 20.0F,
                this.rainFadeUpSeconds, this.rainFadeDownSeconds, this.thunderFadeUpSeconds, this.thunderFadeDownSeconds,
                this.inRainyFadeUpSeconds, this.inRainyFadeDownSeconds));
    }

    private static int intOption(ShaderOptionSet opts, String name, int fallback) {
        try {
            String v = opts.optionValue(name);
            return v == null ? fallback : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static float floatOption(ShaderOptionSet opts, String name, float fallback) {
        try {
            String v = opts.optionValue(name);
            return v == null ? fallback : Float.parseFloat(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Re-reads the pack's {@code shadowDistance} option and derives everything the shadow box needs from it:
     * the ortho half-extent ({@link #shadowDistanceThisFrame}) and the radial distortion bias
     * ({@link #shadowMapBiasThisFrame}, the pack's {@code const float shadowMapBias = 1.0 - 25.6 / shadowDistance}).
     *
     * <p>Read per frame rather than baked in: it is a user-facing slider in the shader options screen. Hardcoding it
     * to one profile's 192 is what put every shadow in the wrong place once the slider moved to 128 — the write
     * (our two bias uniforms) disagreed with the pack's own lookup, radially about the camera.</p>
     *
     * <p>Both derived values are sampled ONCE per frame here because their consumers run per draw, per caster and
     * per section. Sanity guard: {@code d} must exceed 25.6 (below that the bias is ≤ 0, i.e. no distortion or an
     * inverted one) and stay finite; anything else falls back to the built-in profile rather than producing a
     * degenerate shadow box.</p>
     */
    private void refreshPackShadowGeometry() {
        float d = floatOption(shaderOptions(), "shadowDistance", SHADOW_DISTANCE_FALLBACK);
        if (Float.isNaN(d) || !(d > 25.6F) || !(d <= 4096.0F)) {
            d = SHADOW_DISTANCE_FALLBACK;
        }
        this.shadowDistanceThisFrame = d;
        this.shadowMapBiasThisFrame = 1.0F - 25.6F / d;

        // Log on CHANGE, not on shadow-map allocation: the map is only reallocated when its RESOLUTION changes
        // (SHADOW_QUALITY / SHADOW_SMOOTHING), so moving the shadowDistance slider alone leaves no trace there — which
        // is exactly what happened during the 2026-08-02 verification session, where the value had to be reconstructed
        // from the options file's mtime. These four numbers are the whole shadow-box state, and every one of them is
        // derived rather than written down now, so this is also the cheapest check that the derivation is live.
        if (d != this.loggedShadowDistance) {
            this.loggedShadowDistance = d;
            log(String.format("shadow box: dist %.1fb, bias %.4f, cull extent %.1fb, eye %.1fb, list margin %.2fb",
                    d, this.shadowMapBiasThisFrame, shadowCullExtent(), shadowEyeDistance(),
                    me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager.shadowListMargin()));
        }
    }

    /** Last shadow distance written to the log — so the line above fires on change, not every frame. */
    private float loggedShadowDistance = Float.NaN;

    /**
     * Reproduces the pack's compile-time {@code const int shadowMapResolution} (common.glsl) from SHADOW_QUALITY +
     * SHADOW_SMOOTHING, so our shadow map is allocated at the EXACT size the shader assumes. The volumetric-light god rays
     * sample the shadow map by explicit texel index ({@code texelFetch(shadowtex0, ivec2(coord * shadowMapResolution))}),
     * so a mismatch reads out of bounds and paints hard shadow-edge bands across the sky.
     */
    private int packShadowMapResolution() {
        ShaderOptionSet opts = shaderOptions();
        int sq = intOption(opts, "SHADOW_QUALITY", 2);
        int ss = intOption(opts, "SHADOW_SMOOTHING", 4);
        if (sq >= 2) {
            return (sq >= 5 || ss < 3) ? 4096 : 2048;
        }
        return ss < 3 ? 2048 : 1024;
    }

    /** Binds the procedural noise texture on {@link RenderTargets#NOISETEX_UNIT} + points {@code noisetex} at it, for an
     * immediate-mode pack program (sky/hand/entities) that samples noise. Uses raw GL for the high unit, then restores the
     * active unit to 0 so MC's immediate-mode texturing (units 0/1) is unaffected. */
    private void bindNoiseForImmediate(GlslProgram p) {
        if (this.targets == null) {
            return;
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + RenderTargets.NOISETEX_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.targets.noiseTexture());
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        p.setInt("noisetex", RenderTargets.NOISETEX_UNIT);
    }

    public void endSky() {
        if (this.skyActive) {
            restoreAlphaFunc(); // drop the sky programs' alphaTest override before terrain draws
            GlslProgram.unuse();
            this.skyActive = false;
        }
    }

    /** True when the below-horizon GetSky fill runs this sky phase. The sky mixin skips vanilla's "bottom sky"
     * planes (sky2VBO/glSkyList2) exactly then: they draw AFTER the celestial quads, and since the fill moved
     * BEFORE those quads (so it no longer erases the rising moon/sun/stars), the planes would repaint the strip
     * with the flat colour the fill exists to replace. */
    public boolean skyBelowFillActive() {
        return this.skyActive && this.skybasicBelowProgram != null && this.targets != null
                && "world0/".equals(this.worldFolder);
    }

    /**
     * Runs the below-horizon GetSky fill just BEFORE the celestial quads — called from the sky mixin at the sun
     * texture bind — then re-binds the skytextured program/state those quads expect. Ordering is the point: at
     * renderSky RETURN the fill ERASED every already-drawn celestial pixel with VdotU &lt; 0.15, so the moon (and
     * the setting sun, and horizon stars) only appeared once ~8.6° above the horizon; drawn first, the additive
     * quads land on top of the fill and stay visible down through the horizon, like 1.20.1.
     */
    public void preCelestialFill() {
        if (!skyBelowFillActive()) {
            return;
        }
        fillBelowHorizonSky();
        skyBindTextured(); // the fill unbound the program; restore the celestial-quad bind (program + uniforms + alpha)
    }

    /**
     * Paints the pack's {@code GetSky} onto the BELOW-horizon sky, fixing MC 1.12.2's flat fog-coloured "bottom sky" plane
     * (which our sky mixin can't route through the pack shader → a hard grey strip at the horizon, ~= the raw fogColor).
     *
     * <p>Runs mid-{@code renderSky}, right before the sun/moon/star quads (see {@link #preCelestialFill}). A fullscreen
     * quad through {@link #skybasicBelowProgram} (skybasic + {@code discard} for VdotU &gt; 0.15) writes per-pixel
     * {@code GetSky} into colortex0 for the lower hemisphere + horizon strip; the upper sky is untouched (discarded) and
     * the celestial quads draw after, on top. Depth writes stay OFF so the terrain drawn later still occludes the
     * foreground — only the truly-uncovered below-horizon sky keeps the fill.</p>
     */
    private void fillBelowHorizonSky() {
        if (this.skybasicBelowProgram == null || this.targets == null) {
            return;
        }
        // OVERWORLD-only. The pack's NETHER/END skybasic is a glColor PASSTHROUGH (no per-pixel sky math), so this
        // fill flooded the whole END sky with the current immediate-mode colour — WHITE — into colortex0. The screen
        // never showed it (deferred1 repaints sky pixels with endSkyColor), but every NEIGHBOUR-sampling effect read
        // it: deferred1's DISTANT_LIGHT_BOKEH taps the 4 adjacent ct0 texels, so terrain pixels touching "sky" mixed
        // in the white placeholder → the 1px bright silhouette line around distant END islands (absent on real Iris,
        // whose gbuffer sky holds the real dark sky). The below-horizon fog plane this helper fixes is an
        // overworld-look issue anyway; NETHER/END skies are painted entirely by deferred1.
        if (!"world0/".equals(this.worldFolder)) {
            return;
        }
        // MC's sky render leaves the world FBO bound with colortex0 on attachment 0 (what we want to write). Draw state:
        // no depth test/write (every sky pixel is still at the cleared far depth here; terrain hasn't drawn yet), no blend.
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.disableBlend();
        GlStateManager.disableCull();
        GlStateManager.disableAlpha();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F); // gl_Color (skybasic's glColor) — the pack ignores it for GetSky
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();

        this.skybasicBelowProgram.use();
        setSceneUniforms(this.skybasicBelowProgram);
        // Reuse the matrices captured at the dome bind — this helper draws under pushed IDENTITY matrices, so a fresh
        // GL capture here would hand the pack identity and break the fill's view-ray reconstruction.
        if (this.skyMatricesCaptured) {
            applySkyMatrices(this.skybasicBelowProgram);
        }
        bindNoiseForImmediate(this.skybasicBelowProgram);
        drawFullscreenQuad(); // skybasic reconstructs the per-pixel view ray from gl_FragCoord + gbufferProjectionInverse

        GlslProgram.unuse();
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.popMatrix();
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.popMatrix();
        // Restore the state the CELESTIAL quads expect (we run mid-sky now, between the sunrise tri-fan and the sun
        // quad): depth TEST back on but depth WRITES stay OFF (the whole sky renders with depthMask false — a moon
        // quad writing depth would read as geometry to the composites), and vanilla's additive celestial blend
        // (SRC_ALPHA, ONE) re-established. Alpha test stays off — skyBindTextured re-applies the pack's override.
        GlStateManager.enableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.enableCull();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        // Vanilla set glColor to (1,1,1, 1-rainStrength) before the celestial quads — the sun/moon fade out in
        // rain through that vertex alpha. The fill stomped it to alpha 1 above; restore the exact value (captured
        // per-frame from world.getRainStrength) or the celestial bodies render full-bright during rain.
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F - this.rainStrength);
    }

    // --- first-person hand (immediate-mode) hooks, called from MixinItemRendererIris around renderItemInFirstPerson ---

    /**
     * Binds {@code gbuffers_hand} for the first-person hand + held item. The hand renders inside {@code renderWorld} into
     * the G-buffer (colortex0), so its shading feeds the composite like the rest of the world. MC has the item atlas on
     * unit 0 and the lightmap on unit 1 here, matching the samplers. No-op when shaders are off or the pack has no hand
     * program (then the hand renders with the fixed-function pipeline, as before).
     */
    public void beginHand() {
        this.handActive = this.enabled && this.activeThisFrame && this.handProgram != null;
        if (this.handActive) {
            this.handProgram.use();
            this.handProgram.setInt("gtexture", 0);
            this.handProgram.setInt("lightmap", 1);
            this.handProgram.setInt("currentRenderedItemId", 0); // the RenderItem hook sets it as each held model draws
            setSceneUniforms(this.handProgram); // viewWidth/viewHeight etc. — unset → NaN (rendered the hand black)
            applyHandProjection(this.handProgram); // MUST come after setSceneUniforms, which installs the WORLD projection
            bindGbuffersSceneSamplers(this.handProgram); // colortex10/18/shadowcolor0 → real buffers (else unit 0 = item atlas → speckle on the held item)
            // Bind the shadow map + noise for the hand, so it RECEIVES real sunlight like the third-person player does.
            // gbuffers_hand's DoLighting does `shadowMult *= GetShadow(shadowPos, ...)`, sampling shadowtex0 (a
            // sampler2DShadow). Left unbound, that sampler defaults to unit 0 (the item atlas) → the depth-compare returns
            // garbage → shadowMult collapses to ~0 → the hand gets NO direct sun (ambient-only → dark). The pack already
            // hard-caps the hand's shadowMult at vec3(0.5) ("Reduced shadowMult for held items to not get too bright",
            // gbuffers_hand main) — HALF the entity path — so binding the real shadow map lights the hand correctly and
            // dimmer than entities (which are confirmed-correct with the same bind), not blown out.
            bindShadowForImmediate(this.handProgram);
            bindNoiseForImmediate(this.handProgram);
            // Attach the hand program's full G-buffer targets so it writes its own material/normal (not just colour) — else
            // the stale wet-terrain material under the hand leaks into the composite reflection (a puddle mirror on the
            // first-person hand). See the entityTargets note. Restored in endHand.
            if (NORMAL_GBUFFER_ENABLED && this.targets != null && this.handTargets != null) {
                this.targets.beginTargets(this.handTargets, blendOffFor("gbuffers_hand"));
                // Re-attach the scene depth (the water pass swapped it) so the hand's depth writes land in depthTex — the
                // exact buffer the composite reads as depthtex0. Force depthMask ON too: without a written near depth the
                // composite reads the BACKGROUND depth behind the hand, treats the hand pixels as sky/terrain, and blends
                // clouds/sky/fog through them → the see-through first-person hand (worst against the sky, where clouds sit).
                this.targets.attachSceneDepth();
                GlStateManager.depthMask(true);
            }
            // Draw the hand ON TOP of nearby blocks WITHOUT clipping into them. Vanilla clears the depth before the hand;
            // our pipeline can't (the shared G-buffer depth is the composite's depthtex0, and clearing it would wipe the
            // scene depth the composite reads). The previous fix — depthFunc(GL_ALWAYS) — made the hand win against the
            // world but ALSO stopped its own faces from depth-sorting, so the arm rendered INSIDE-OUT (far interior faces
            // overwrote the near ones). Instead compress the hand into the FRONT slice of the depth range: every hand
            // fragment maps into window depth [0, 0.1], ahead of essentially all world geometry (under the near=0.05
            // projection even a block a few cm away has window depth > 0.1), so the hand always wins — while its faces
            // still depth-test against EACH OTHER with the normal LEQUAL over that compressed range, so the arm
            // self-occludes correctly. The compressed near depth also keeps the composite fog off the hand. Restored in
            // endHand. (depthFunc stays at MC's default LEQUAL — we no longer force ALWAYS.)
            GL11.glDepthRange(0.0, HAND_DEPTH_RANGE);
            if (DIAG_HAND_STATE && !this.handLogged) {
                this.handLogged = true;
                // DIAGNOSTIC: dump the exact GL draw state MC set up for the first-person hand. If blend is ON with an
                // over-blend func the opaque hand mixes with the terrain already in colortex0 → looks semi-transparent
                // (the fix would be to disable blend / force output alpha=1). If blend is OFF, the transparency must be a
                // composite pass instead. depthMask=false would mean the hand doesn't write depthtex0 → composite treats
                // it as z0==background. One-shot log.
                boolean blend = GL11.glGetBoolean(GL11.GL_BLEND);
                int blendSrc = GL11.glGetInteger(GL11.GL_BLEND_SRC);
                int blendDst = GL11.glGetInteger(GL11.GL_BLEND_DST);
                boolean depthTest = GL11.glGetBoolean(GL11.GL_DEPTH_TEST);
                boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
                boolean alphaTest = GL11.glGetBoolean(GL11.GL_ALPHA_TEST);
                boolean cull = GL11.glGetBoolean(GL11.GL_CULL_FACE);
                int fboBound = GL11.glGetInteger(org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER_BINDING);
                log(String.format("[HAND STATE] blend=%b src=0x%X dst=0x%X depthTest=%b depthMask=%b alphaTest=%b cull=%b"
                                + " | fboBound=%d pipelineFbo=%d%s",
                        blend, blendSrc, blendDst, depthTest, depthMask, alphaTest, cull,
                        fboBound, this.targets.fboId(),
                        fboBound == this.targets.fboId() ? "" : "  *** HAND TARGETS ATTACHED TO THE WRONG FBO ***"));
                // GL-truth of what each colour slot REALLY holds right after beginTargets(handTargets).
                this.targets.diagAttachments("hand pass", this.handTargets);
                // The two per-slot states diagAttachments does NOT cover: the actual glDrawBuffers selection and the
                // per-buffer colour write masks — the only remaining ways a correct FragData[1] write can vanish.
                StringBuilder db = new StringBuilder("[HAND DRAWBUF]");
                for (int i = 0; i < 4; i++) {
                    db.append(String.format(" DRAW_BUFFER%d=0x%X", i,
                            GL11.glGetInteger(org.lwjgl.opengl.GL20C.GL_DRAW_BUFFER0 + i)));
                }
                java.nio.ByteBuffer mask = org.lwjgl.BufferUtils.createByteBuffer(4);
                for (int i = 0; i < 4; i++) {
                    mask.clear();
                    org.lwjgl.opengl.GL30C.glGetBooleani_v(org.lwjgl.opengl.GL30C.GL_COLOR_WRITEMASK, i, mask);
                    db.append(String.format(" mask%d=%d%d%d%d", i, mask.get(0), mask.get(1), mask.get(2), mask.get(3)));
                }
                log(db.toString());
            }
        }
    }

    public void endHand() {
        if (this.handActive) {
            // ct6 the INSTANT the hand finished drawing — splits "the FragData[1] output never lands" (water mask
            // already here) from "something later overwrites it" (hand mask here, water at the pre-chain probe).
            if (DIAG_HAND_PIXEL && this.frameCounter % 60 == 0 && this.targets != null) {
                int hx = (int) (this.targets.width() * 0.78F);
                int hy = (int) (this.targets.height() * 0.15F);
                float[] c6 = this.targets.readColortexTexel(6, hx, hy);
                log(String.format("[HAND PIXEL] endHand ct6 front=%s @(%d,%d)=(sm=%.3f mask=%.0f sky=%.3f a=%.3f)",
                        this.targets.frontSide(6) == 0 ? "A" : "B", hx, hy,
                        c6[0], c6[1] * 255.0F, c6[2], c6[3]));
            }
            if (DIAG_HAND_STATE && !this.handEndLogged) {
                this.handEndLogged = true;
                // DIAGNOSTIC: capture the GL state AFTER MC drew the hand geometry. GL_DEPTH_RANGE tells us whether our
                // glDepthRange(0,0.1) survived MC's item render (if it's [0,1] MC reset it → the hand wrote a normal ~0.9
                // depth, so depthtex0/1 never got the near hand and every depth-based composite treats it as far). blend
                // ON here (vs the HEAD log's OFF) would mean MC enabled over-blend for the held item → alpha<1 lets the
                // world behind bleed through (see-through hand). depthMask false → the hand never wrote depth at all.
                java.nio.FloatBuffer dr = org.lwjgl.BufferUtils.createFloatBuffer(16);
                GL11.glGetFloat(GL11.GL_DEPTH_RANGE, dr);
                boolean blend = GL11.glGetBoolean(GL11.GL_BLEND);
                boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
                boolean depthTest = GL11.glGetBoolean(GL11.GL_DEPTH_TEST);
                log(String.format("[HAND END] depthRange=[%.4f,%.4f] blend=%b depthMask=%b depthTest=%b",
                        dr.get(0), dr.get(1), blend, depthMask, depthTest));
            }
            if (NORMAL_GBUFFER_ENABLED && this.targets != null && this.handTargets != null) {
                this.targets.endTerrainTargets(); // back to colortex0-only
            }
            GL11.glDepthRange(0.0, 1.0); // restore the full depth range for subsequent rendering
            GlslProgram.unuse();
            this.handActive = false;
        }
    }

    // --- regular entities (immediate-mode) hooks, called from MixinRenderGlobal around the entity render loop ---

    /**
     * Binds {@code gbuffers_entities} for the regular-entity render loop (mobs, dropped items). Runs inside
     * {@code renderWorld} into the G-buffer (colortex0). MC binds each entity's texture on unit 0 and its per-entity
     * lightmap on unit 1 as it draws, matching the samplers. The two item-light directions are uploaded here in EYE space
     * (world dirs × gbufferModelView, which vanilla's enableStandardItemLighting effectively bakes in once) so the shading
     * is fixed in world space. No-op when shaders are off or the pack has no entities program.
     */
    public void beginEntities() {
        this.entitiesActive = this.enabled && this.activeThisFrame && this.entitiesProgram != null;
        if (this.entitiesActive) {
            this.activeEntityProgram = this.entitiesProgram;
            useItemLitProgram(this.entitiesProgram);
            // Attach the entity program's full G-buffer targets so it writes its OWN material (colortex6.r = smoothnessD)
            // + normal, not just colour — otherwise the stale wet-terrain material behind the entity leaks into the pack's
            // composite reflection and paints a puddle reflection onto the entity silhouette. See the entityTargets note.
            if (NORMAL_GBUFFER_ENABLED && this.targets != null && this.entityTargets != null) {
                this.targets.beginTargets(this.entityTargets, blendOffFor("gbuffers_entities"));
            }
            if (!this.entitiesLogged) {
                this.entitiesLogged = true;
                log("entities rendered by pack gbuffers_entities (targets=" + java.util.Arrays.toString(this.entityTargets) + ")");
            }
        }
    }

    public void endEntities() {
        if (this.entitiesActive) {
            if (NORMAL_GBUFFER_ENABLED && this.targets != null && this.entityTargets != null) {
                this.targets.endTerrainTargets(); // back to colortex0-only for subsequent immediate-mode geometry
            }
            GlslProgram.unuse();
            this.entitiesActive = false;
            this.activeEntityProgram = null;
        }
    }

    /** True between {@link #beginEntities} and {@link #endEntities} while the pack's entity program owns the pass. */
    public boolean isEntityPassActive() {
        return this.entitiesActive;
    }

    // --- per-entity / per-item uniforms (mixin hooks; each no-ops unless its pass is live) -------------------------

    /**
     * Per-entity uniforms, called at the head of every entity render (mixin on {@code RenderManager.renderEntityStatic}):
     * {@code entityId} from entity.properties, {@code entityColor} = the hurt/death red flash — vanilla applies it
     * through a fixed-function texture combiner ({@code RenderLivingBase.setBrightness}: rgb(1,0,0) a=0.3) that a bound
     * shader bypasses entirely, so without this uniform entities never flashed red under shaders — and a reset of
     * {@code currentRenderedItemId} (the RenderItem hook below re-sets it for each item model the entity carries).
     */
    public void onEntityRender(Entity entity) {
        if (!this.entitiesActive || this.entitiesProgram == null) {
            return;
        }
        // gbuffers_entities_glowing: the entities program compiled with GBUFFERS_ENTITIES_GLOWING (same structure +
        // targets — the world0 stub just adds the define), swapped in per entity with the 1.9+ glowing flag, exactly
        // like the beacon's per-TESR swap. All per-entity uniforms go to whichever program is now bound.
        GlslProgram p = entity.isGlowing() && this.entitiesGlowingProgram != null
                ? this.entitiesGlowingProgram : this.entitiesProgram;
        if (p != this.activeEntityProgram) {
            useItemLitProgram(p);
            this.activeEntityProgram = p;
        }
        p.setInt("entityId", this.entityIdMapper == null ? 0 : this.entityIdMapper.idFor(entity));
        float red = 0.0F;
        float alpha = 0.0F;
        if (entity instanceof EntityLivingBase) {
            EntityLivingBase living = (EntityLivingBase) entity;
            if (living.hurtTime > 0 || living.deathTime > 0) {
                red = 1.0F;   // vanilla's exact overlay: brightnessBuffer = (1, 0, 0, 0.3)
                alpha = 0.3F;
            }
        }
        p.setVec4("entityColor", red, 0.0F, 0.0F, alpha);
        p.setInt("currentRenderedItemId", 0);
    }

    /**
     * {@code blockEntityId} for the TESR about to render (mixin on {@code TileEntityRendererDispatcher.render}): the
     * block.properties id of its block state, resolved through the SAME mapper terrain meshing uses — this is what
     * routes chests/signs/banners/conduits through the pack's blockEntityIPBR material dispatch instead of id 0.
     */
    /**
     * Whether the block-entity pass is currently dropped to FIXED FUNCTION for a stencil-mirror TESR — see
     * {@link #isStencilMirrorTesr}. Cleared by the next ordinary TESR's re-bind, or by {@link #endBlockEntities}.
     */
    private boolean stencilTesrFixedFn;

    /**
     * TESRs that must render with NO shader program bound (true fixed function, program 0).
     *
     * <p>The Betweenlands' mud-tower mirror ({@code RenderBeamOrigin}) builds a stencil mask by drawing its mirror
     * triangles with {@code disableTexture2D} + {@code colorMask(false×4)}, then draws the reflected world and the
     * crystal-face tint where the stencil matches. A bound {@code gbuffers_block} ruins the MASK WRITE: the program
     * ignores the fixed-function texture-off switch and samples the atlas at whatever UVs the triangle carries, and
     * its cutout {@code discard} kills fragments — a discarded fragment writes NO stencil, so the mask never forms,
     * the EQUAL test fails everywhere, and the mirror face renders as see-through nothing (2026-08-03, observed the
     * moment the world FBO gained a real stencil buffer). Program 0 is the compatibility-profile answer (the M0
     * founding decision guarantees fixed function works): no sampling, no GLSL discard, stencil writes land, and the
     * reflection draws with fixed-function texturing/lighting exactly as BL intended. The pixels' G-buffer
     * normal/material keep whatever the geometry behind wrote — acceptable shading for a glowing mirror — and the
     * draw-buffer list is narrowed to attachment 0 so fixed-function colour cannot smear into the material targets
     * (RenderTargets.beginFixedFunctionDrawBuffer).</p>
     *
     * <p>Matched by class NAME — no Betweenlands class is loaded (Cleanroom classloader invariant).</p>
     */
    private static boolean isStencilMirrorTesr(net.minecraft.tileentity.TileEntity te) {
        return te != null && "thebetweenlands.common.tile.TileEntityBeamOrigin".equals(te.getClass().getName());
    }

    public void onBlockEntityRender(net.minecraft.tileentity.TileEntity te) {
        if (!this.blockActive) {
            return;
        }
        if (isStencilMirrorTesr(te)) {
            if (!this.stencilTesrFixedFn) {
                this.stencilTesrFixedFn = true;
                GlslProgram.unuse();
                this.activeBlockPassProgram = null; // the next ordinary TESR re-binds with full uniform/sampler setup
                if (this.targets != null) {
                    this.targets.beginFixedFunctionDrawBuffer();
                }
            }
            return;
        }
        if (this.stencilTesrFixedFn) {
            this.stencilTesrFixedFn = false;
            if (this.targets != null) {
                this.targets.restoreActiveDrawBuffers();
            }
        }
        GlslProgram base = this.blockProgram != null ? this.blockProgram : this.entitiesProgram;
        boolean beacon = te instanceof net.minecraft.tileentity.TileEntityBeacon && this.beaconBeamProgram != null;
        GlslProgram p = beacon ? this.beaconBeamProgram : base;
        if (p == null) {
            return;
        }
        // Mid-pass program swap: the beacon's beam gets the pack's dedicated emissive gbuffers_beaconbeam (with its
        // alphaTest override), every other TESR the block program — full uniform/sampler setup on each swap, exactly
        // like the pass's own begin bind.
        if (p != this.activeBlockPassProgram) {
            useItemLitProgram(p);
            if (beacon) {
                applyAlphaOverride("gbuffers_beaconbeam");
            } else {
                restoreAlphaFunc();
            }
            this.activeBlockPassProgram = p;
        }
        int id = 0;
        try {
            com.yumelium.yumelium.shaders.pack.BlockIdMapper mapper =
                    me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer.getBlockIdMapper();
            if (mapper != null && te != null && te.getWorld() != null) {
                id = mapper.idFor(te.getWorld().getBlockState(te.getPos()));
            }
        } catch (Throwable ignored) {
        }
        p.setInt("blockEntityId", id);
        p.setInt("currentRenderedItemId", 0);
    }

    /**
     * {@code currentRenderedItemId} for the item model about to draw (mixin on {@code RenderItem.renderItem}): covers
     * the first-person held item, dropped items, item frames and mob-held/armor items — the pack's irisIPBR dispatches
     * every item material off it. Applies to whichever gbuffers program is live; a GUI item render (no pass active)
     * no-ops, so inventory drawing is untouched.
     */
    public void onItemRender(net.minecraft.item.ItemStack stack) {
        GlslProgram p = this.handActive ? this.handProgram
                : this.entitiesActive ? (this.activeEntityProgram != null ? this.activeEntityProgram : this.entitiesProgram)
                : this.blockActive ? (this.blockProgram != null ? this.blockProgram : this.entitiesProgram)
                : null;
        if (p == null) {
            return;
        }
        // BLOCK items resolve through block.properties (their block-state id), NOT item.properties. The pack's own
        // dispatch is the proof: irisIPBR routes currentRenderedItemId < 45000 into terrainIPBR, whose branches are
        // the BLOCK id space — a held sea lantern must arrive as block 10448 (emissive branch), while its
        // item.properties id (44018, the heldItemId light-COLOUR table) matches nothing there. Feeding 44018 zeroed
        // the held lantern's emission → lightAlbedo → the hand's SSBL colour source, so colortex10 at the hand kept
        // only reprojected background light and the held light took on DISTANT torch colour (the orange-hand bug).
        // item.properties stays the source for non-block items (tools/armor, the 45xxx ranges).
        int id = 0;
        if (stack != null && !stack.isEmpty() && stack.getItem() instanceof net.minecraft.item.ItemBlock) {
            try {
                com.yumelium.yumelium.shaders.pack.BlockIdMapper mapper =
                        me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer.getBlockIdMapper();
                if (mapper != null) {
                    net.minecraft.block.Block block = ((net.minecraft.item.ItemBlock) stack.getItem()).getBlock();
                    id = mapper.idFor(block.getStateFromMeta(stack.getMetadata()));
                }
            } catch (Throwable ignored) {
            }
        }
        if (id == 0 && this.itemIdMapper != null) {
            id = this.itemIdMapper.idFor(stack);
        }
        p.setInt("currentRenderedItemId", id);
        // v13 diag: name the id + stack at every hand draw in the meter window — if the id flip-flops frame to frame,
        // the emission bursts are a material-branch flip; if it is constant, the pulse is in the branch's colour input.
        if (DIAG_HAND_TERMS && this.handActive && "world1/".equals(this.worldFolder)) {
            int phase = this.frameCounter % 600;
            if (phase >= 300 && phase < 420) {
                log("[HAND ID] f=" + this.frameCounter + " id=" + id + " stack="
                        + (stack == null || stack.isEmpty() ? "empty"
                                : stack.getItem().getRegistryName() + "@" + stack.getMetadata()));
            }
        }
        // First-person only: replace the vanilla per-block-stepped hand brightness (set by ItemRenderer just before
        // this draw) with the SMOOTHED value — kills the 2-3 frame boundary blink the [ITEM METER] measured (worst in
        // the END, where no skylight masks blocklight steps). Third-person/entity items keep vanilla's per-entity value.
        if (this.handActive && this.handLightSmoothX >= 0.0F) {
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, this.handLightSmoothX, this.handLightSmoothY);
        }
    }

    // --- block entities / TESRs (immediate-mode), around Sodium's renderTileEntities ---

    /** Binds {@code gbuffers_block} (or the entities program as a fallback) for TESRs, with the same item lighting. */
    public void beginBlockEntities() {
        GlslProgram p = this.blockProgram != null ? this.blockProgram : this.entitiesProgram;
        this.blockActive = this.enabled && this.activeThisFrame && p != null;
        if (this.blockActive) {
            useItemLitProgram(p);
            this.activeBlockPassProgram = p;
            if (!this.blockLogged) {
                this.blockLogged = true;
                log("block entities rendered by pack " + (this.blockProgram != null ? "gbuffers_block" : "gbuffers_entities (fallback)"));
            }
        }
    }

    public void endBlockEntities() {
        if (this.blockActive) {
            if (this.stencilTesrFixedFn) {
                // A stencil-mirror TESR was the LAST one — restore the pass's draw-buffer list before closing.
                this.stencilTesrFixedFn = false;
                if (this.targets != null) {
                    this.targets.restoreActiveDrawBuffers();
                }
            }
            if (this.activeBlockPassProgram == this.beaconBeamProgram && this.beaconBeamProgram != null) {
                restoreAlphaFunc(); // a beacon was the last TESR — drop its alphaTest override
            }
            this.activeBlockPassProgram = null;
            GlslProgram.unuse();
            this.blockActive = false;
        }
    }

    // --- overlay sub-draws (enchantment glint, fullbright eye layers) inside a live hand/entities/block pass --------

    /**
     * Binds {@code gbuffers_armor_glint} around MC's enchantment-glint overlay (items via RenderItem.renderEffect,
     * armor via LayerArmorBase.renderEnchantedGlint). The pack's glint vertex is pure {@code ftransform()}, so the
     * enclosing draw's fixed-function matrices (including the hand's) carry over untouched. Its fragment declares
     * DRAWBUFFERS:06 and PRESERVES the material by re-writing a {@code texelFetch} of colortex6 (with
     * {@code blend.gbuffers_armor_glint.colortex6 = off}, so MC's additive glint blend cannot double it) — hence the
     * attachment swap to its own target list and the colortex6 sampler bind. GUI glint renders no-op here (no pass
     * active), keeping inventory drawing vanilla.
     */
    public void beginArmorGlint() {
        if (this.armorGlintProgram == null || !(this.handActive || this.entitiesActive || this.blockActive)) {
            return;
        }
        this.glintActive = true;
        useItemLitProgram(this.armorGlintProgram);
        bindOverlayColortex6(this.armorGlintProgram);
        if (NORMAL_GBUFFER_ENABLED && this.targets != null && this.armorGlintTargets != null) {
            this.targets.beginTargets(this.armorGlintTargets, blendOffFor("gbuffers_armor_glint"));
        }
    }

    public void endArmorGlint() {
        if (!this.glintActive) {
            return;
        }
        this.glintActive = false;
        restoreEnclosingPass();
    }

    /** A/B lever kept from the spider-flicker investigation (2026-07-23): bypassing ONLY the eyes routing did NOT
     * stop the whole-spider flicker on camera rotation → the flicker pre-dates the eyes feature (TAA thin-geometry
     * shimmer on the spider's many sub-pixel legs; endermen — thick shapes — are stable). Leave false. */
    private static final boolean DIAG_EYES_ROUTING_OFF = false;

    /** Binds {@code gbuffers_spidereyes} around the fullbright eye layers (spider/enderman/dragon) — the pack marks
     * them EMISSIVE (bloom + no shading) instead of the flat entity shading they got through gbuffers_entities. Same
     * attachment/material-preserve handling as the glint, plus the pack's alphaTest override (GREATER 0.0001). */
    public void beginSpiderEyes() {
        if (DIAG_EYES_ROUTING_OFF || this.spidereyesProgram == null || !this.entitiesActive) {
            return;
        }
        this.eyesActive = true;
        useItemLitProgram(this.spidereyesProgram);
        applyAlphaOverride("gbuffers_spidereyes");
        bindOverlayColortex6(this.spidereyesProgram);
        if (NORMAL_GBUFFER_ENABLED && this.targets != null && this.spidereyesTargets != null) {
            this.targets.beginTargets(this.spidereyesTargets, blendOffFor("gbuffers_spidereyes"));
        }
    }

    public void endSpiderEyes() {
        if (!this.eyesActive) {
            return;
        }
        this.eyesActive = false;
        restoreAlphaFunc();
        restoreEnclosingPass();
    }

    /**
     * Binds {@code gbuffers_basic} around an untextured POSITION_COLOR draw — the LEASH (inside the entities pass;
     * previously it went through gbuffers_entities, whose atlas sample at the leash's nonexistent texcoords tinted it
     * with whatever texel was left) and the BLOCK SELECTION OUTLINE (standalone, no pass active). The pack's basic is
     * the classic ftransform+glColor program, fully compatible with these fixed-function draws.
     */
    /** Kill switch for the leash-bracket gate (MixinRenderLivingLeashIris). Flip false to restore the old
     * unconditional bracket if a pack or mod ever turns out to depend on the per-entity program churn. */
    public static final boolean LEASH_GATE = true;

    /** Per-frame counters for the leash gate, surfaced on F3 so the saving is measurable in one build: with the gate
     * working, "skipped" tracks the living-entity count and "run" stays 0 until something is actually leashed. */
    private int basicBracketsRun;
    private int basicBracketsSkipped;

    public void countBasicBracketSkipped() {
        this.basicBracketsSkipped++;
    }

    public int basicBracketsRun() {
        return this.basicBracketsRun;
    }

    public int basicBracketsSkipped() {
        return this.basicBracketsSkipped;
    }

    public void beginBasic() {
        // The shadowPass guard is LOAD-BEARING: the leash mixin fires for EVERY EntityLiving (renderLeash itself
        // early-returns without a leash holder, but the HEAD/RETURN hooks run regardless), so during the entity
        // shadow-caster loop this used to run beginTargets/endTerrainTargets against the BOUND SHADOW FBO —
        // attaching a window-sized colortex to the 4096 shadow map. GL clips rasterization to the INTERSECTION of
        // attachment sizes, so every entity after the first mob was silently confined to a window-sized corner of
        // the map ("only the loop's first entity lands", and the original END terrain-shadow scramble). All other
        // begin* hooks reachable from an entity render are guarded by handActive/entitiesActive/blockActive —
        // basic is the one standalone-capable hook, so it needs the explicit guard.
        if (this.basicProgram == null || !this.enabled || !this.activeThisFrame || this.shadowPass) {
            return;
        }
        this.basicActive = true;
        this.basicBracketsRun++;
        useItemLitProgram(this.basicProgram);
        if (NORMAL_GBUFFER_ENABLED && this.targets != null && this.basicTargets != null) {
            this.targets.beginTargets(this.basicTargets, blendOffFor("gbuffers_basic"));
        }
    }

    public void endBasic() {
        if (!this.basicActive) {
            return;
        }
        this.basicActive = false;
        if (this.handActive || this.entitiesActive || this.blockActive) {
            restoreEnclosingPass(); // leash: back to the entity pass's program + targets
        } else {
            GlslProgram.unuse();    // selection outline: standalone — back to fixed-function + colortex0-only
            if (this.targets != null) {
                this.targets.endTerrainTargets();
            }
        }
    }

    private static final net.minecraft.util.ResourceLocation END_PORTAL_TEXTURE =
            new net.minecraft.util.ResourceLocation("textures/entity/end_portal.png");

    /**
     * Replaces the vanilla END PORTAL surface render while the block pass is live. Vanilla draws it as eye-linear
     * TEXGEN + up to 16 ADDITIVE passes — under a bound shader the texgen is dead, so every pass sampled garbage UVs
     * and the additive stack blew the portal out to white. The pack expects the MODERN single-draw form: its
     * endPortalEffect (blockEntityIPBR mat 5025, fed by the TESR dispatch's blockEntityId) ignores the vertex UVs and
     * procedurally taps the END PORTAL star texture bound as {@code tex} — so ONE fullbright quad with that texture
     * bound is exactly the input it was written for. @return true when handled (the vanilla TESR must be cancelled).
     */
    /** A/B lever kept from the 2026-07-24 crash investigation: a reproducible nvoglv64.dll access violation
     * (offset 0xc5aa8a, ~2s after overworld load) persisted with BOTH end-portal changes disabled — so the TESR
     * replacement and the fixEndPortalEyeHeight injection are RULED OUT as the cause (suspected GPU driver issue;
     * driver 32.0.16.1074 at the time). Leave false. */
    private static final boolean DIAG_PORTAL_FEATURES_OFF = false;

    public boolean renderEndPortalSurface(net.minecraft.tileentity.TileEntityEndPortal te, double x, double y, double z) {
        if (DIAG_PORTAL_FEATURES_OFF || !this.blockActive) {
            return false; // shaders off / outside the block pass — keep the vanilla multi-pass look
        }
        Minecraft.getMinecraft().getTextureManager().bindTexture(END_PORTAL_TEXTURE);
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240.0F, 240.0F); // vanilla: fullbright
        // Winding-proof: faces must show from either side (the gateway floats and is looked at from anywhere).
        GlStateManager.disableCull();
        net.minecraft.client.renderer.Tessellator tess = net.minecraft.client.renderer.Tessellator.getInstance();
        net.minecraft.client.renderer.BufferBuilder buf = tess.getBuffer();
        buf.begin(GL11.GL_QUADS, net.minecraft.client.renderer.vertex.DefaultVertexFormats.ITEM);
        // ALL SIX faces, gated by the TE's own face test — the semantics differ per type and both matter:
        //   end portal:  shouldRenderFace == (face == UP) only, surface at +0.75 (vanilla getOffset).
        //   end gateway: shouldRenderFace == Block.shouldSideBeRendered per face — the full-cube starfield of modern
        //                MC. Its top/bottom sit against the bedrock caps (UP/DOWN occluded = false!), so the VISIBLE
        //                faces are the SIDES — the original UP/DOWN-only version drew zero quads = invisible gateway.
        double top = y + (te instanceof net.minecraft.tileentity.TileEntityEndGateway ? 1.0D : 0.75D);
        if (te.shouldRenderFace(net.minecraft.util.EnumFacing.UP)) {
            buf.pos(x, top, z).color(255, 255, 255, 255).tex(0.0D, 0.0D).normal(0.0F, 1.0F, 0.0F).endVertex();
            buf.pos(x, top, z + 1.0D).color(255, 255, 255, 255).tex(0.0D, 1.0D).normal(0.0F, 1.0F, 0.0F).endVertex();
            buf.pos(x + 1.0D, top, z + 1.0D).color(255, 255, 255, 255).tex(1.0D, 1.0D).normal(0.0F, 1.0F, 0.0F).endVertex();
            buf.pos(x + 1.0D, top, z).color(255, 255, 255, 255).tex(1.0D, 0.0D).normal(0.0F, 1.0F, 0.0F).endVertex();
        }
        if (te.shouldRenderFace(net.minecraft.util.EnumFacing.DOWN)) {
            buf.pos(x, y, z).color(255, 255, 255, 255).tex(0.0D, 0.0D).normal(0.0F, -1.0F, 0.0F).endVertex();
            buf.pos(x + 1.0D, y, z).color(255, 255, 255, 255).tex(1.0D, 0.0D).normal(0.0F, -1.0F, 0.0F).endVertex();
            buf.pos(x + 1.0D, y, z + 1.0D).color(255, 255, 255, 255).tex(1.0D, 1.0D).normal(0.0F, -1.0F, 0.0F).endVertex();
            buf.pos(x, y, z + 1.0D).color(255, 255, 255, 255).tex(0.0D, 1.0D).normal(0.0F, -1.0F, 0.0F).endVertex();
        }
        if (te.shouldRenderFace(net.minecraft.util.EnumFacing.NORTH)) {
            buf.pos(x, y, z).color(255, 255, 255, 255).tex(0.0D, 0.0D).normal(0.0F, 0.0F, -1.0F).endVertex();
            buf.pos(x, top, z).color(255, 255, 255, 255).tex(0.0D, 1.0D).normal(0.0F, 0.0F, -1.0F).endVertex();
            buf.pos(x + 1.0D, top, z).color(255, 255, 255, 255).tex(1.0D, 1.0D).normal(0.0F, 0.0F, -1.0F).endVertex();
            buf.pos(x + 1.0D, y, z).color(255, 255, 255, 255).tex(1.0D, 0.0D).normal(0.0F, 0.0F, -1.0F).endVertex();
        }
        if (te.shouldRenderFace(net.minecraft.util.EnumFacing.SOUTH)) {
            buf.pos(x, y, z + 1.0D).color(255, 255, 255, 255).tex(0.0D, 0.0D).normal(0.0F, 0.0F, 1.0F).endVertex();
            buf.pos(x + 1.0D, y, z + 1.0D).color(255, 255, 255, 255).tex(1.0D, 0.0D).normal(0.0F, 0.0F, 1.0F).endVertex();
            buf.pos(x + 1.0D, top, z + 1.0D).color(255, 255, 255, 255).tex(1.0D, 1.0D).normal(0.0F, 0.0F, 1.0F).endVertex();
            buf.pos(x, top, z + 1.0D).color(255, 255, 255, 255).tex(0.0D, 1.0D).normal(0.0F, 0.0F, 1.0F).endVertex();
        }
        if (te.shouldRenderFace(net.minecraft.util.EnumFacing.WEST)) {
            buf.pos(x, y, z).color(255, 255, 255, 255).tex(0.0D, 0.0D).normal(-1.0F, 0.0F, 0.0F).endVertex();
            buf.pos(x, y, z + 1.0D).color(255, 255, 255, 255).tex(1.0D, 0.0D).normal(-1.0F, 0.0F, 0.0F).endVertex();
            buf.pos(x, top, z + 1.0D).color(255, 255, 255, 255).tex(1.0D, 1.0D).normal(-1.0F, 0.0F, 0.0F).endVertex();
            buf.pos(x, top, z).color(255, 255, 255, 255).tex(0.0D, 1.0D).normal(-1.0F, 0.0F, 0.0F).endVertex();
        }
        if (te.shouldRenderFace(net.minecraft.util.EnumFacing.EAST)) {
            buf.pos(x + 1.0D, y, z).color(255, 255, 255, 255).tex(0.0D, 0.0D).normal(1.0F, 0.0F, 0.0F).endVertex();
            buf.pos(x + 1.0D, top, z).color(255, 255, 255, 255).tex(0.0D, 1.0D).normal(1.0F, 0.0F, 0.0F).endVertex();
            buf.pos(x + 1.0D, top, z + 1.0D).color(255, 255, 255, 255).tex(1.0D, 1.0D).normal(1.0F, 0.0F, 0.0F).endVertex();
            buf.pos(x + 1.0D, y, z + 1.0D).color(255, 255, 255, 255).tex(1.0D, 0.0D).normal(1.0F, 0.0F, 0.0F).endVertex();
        }
        tess.draw();
        GlStateManager.enableCull();
        return true;
    }

    /** Points the overlay program's {@code colortex6} at the CURRENT front material buffer (for its texelFetch
     * preserve-write; reading the attached texel it re-writes is the same Iris semantics the pack was built for). */
    private void bindOverlayColortex6(GlslProgram p) {
        if (this.targets != null) {
            this.targets.bindOverlayColortex6();
            p.setInt("colortex6", RenderTargets.OVERLAY_COLORTEX6_UNIT);
        }
    }

    /** Re-binds the enclosing pass's program + colour attachments after an overlay sub-draw. Mirrors each pass's own
     * begin: hand = beginHand's program setup (incl. the hand projection), block = the current TESR program with
     * colortex0-only attachments (that pass never attaches G-buffer targets), entities = the current entity program
     * (plain or _glowing) with the entity target set. */
    private void restoreEnclosingPass() {
        if (this.handActive) {
            if (this.handProgram != null) {
                useItemLitProgram(this.handProgram);
                applyHandProjection(this.handProgram); // MUST follow setSceneUniforms (inside useItemLitProgram)
            }
            if (NORMAL_GBUFFER_ENABLED && this.targets != null && this.handTargets != null) {
                this.targets.beginTargets(this.handTargets, blendOffFor("gbuffers_hand"));
            }
        } else if (this.blockActive) {
            GlslProgram base = this.activeBlockPassProgram != null ? this.activeBlockPassProgram
                    : this.blockProgram != null ? this.blockProgram : this.entitiesProgram;
            if (base != null) {
                useItemLitProgram(base);
            }
            if (this.targets != null) {
                this.targets.endTerrainTargets(); // the block-entity pass draws with colortex0-only attachments
            }
        } else if (this.entitiesActive) {
            GlslProgram p = this.activeEntityProgram != null ? this.activeEntityProgram : this.entitiesProgram;
            if (p != null) {
                useItemLitProgram(p);
            }
            if (NORMAL_GBUFFER_ENABLED && this.targets != null && this.entityTargets != null) {
                this.targets.beginTargets(this.entityTargets, blendOffFor("gbuffers_entities"));
            }
        }
    }

    // --- particles (immediate-mode), around ParticleManager.renderParticles ---

    /** Binds {@code gbuffers_textured} for particles: texture × colour × lightmap, no directional lighting (billboards). */
    public void beginParticles() {
        this.particlesActive = this.enabled && this.activeThisFrame && this.texturedProgram != null;
        if (this.particlesActive) {
            this.texturedProgram.use();
            this.texturedProgram.setInt("gtexture", 0);
            this.texturedProgram.setInt("lightmap", 1);
            setSceneUniforms(this.texturedProgram);
            // The pack shadows particles like anything else: gbuffers_textured calls DoLighting, whose shadow path is NOT
            // gated out for GBUFFERS_TEXTURED, and it resolves shadowMult through GetShadow(shadowtex0/1). Left unbound
            // those samplers defaulted to unit 0 = the block atlas — and reading an ordinary RGBA texture through the
            // pack's `sampler2DShadow` is undefined (NVIDIA returns ~1.0 = fully lit), so particles stayed bright inside
            // shadows. Same reason useItemLitProgram binds it for entities.
            bindShadowForImmediate(this.texturedProgram);
            bindNoiseForImmediate(this.texturedProgram);   // DoLighting/atmosphere dithering samples noisetex
            bindGbuffersSceneSamplers(this.texturedProgram); // colortex10/18/shadowcolor0 → real buffers (else unit 0 = item atlas → speckle on block-break particles)
            if (!this.particlesLogged) {
                this.particlesLogged = true;
                log("particles rendered by pack gbuffers_textured");
                auditSamplerUnits("gbuffers_textured (particles)", this.texturedProgram);
            }
        }
    }

    public void endParticles() {
        if (this.particlesActive) {
            GlslProgram.unuse();
            this.particlesActive = false;
        }
    }

    // --- block-breaking crack overlay (immediate-mode), around RenderGlobal.drawBlockDamageTexture ---

    /** Binds {@code gbuffers_damagedblock} for the block-breaking crack overlay (drawn after translucent terrain with
     * polygon-offset blending over the block). Routes the cracks through the pack's shading + its
     * {@code alphaTest.gbuffers_damagedblock = GREATER 0.004} instead of fixed-function. No-op without the program. */
    public void beginDamagedBlock() {
        this.damagedActive = this.enabled && this.activeThisFrame && this.damagedBlockProgram != null;
        if (this.damagedActive) {
            this.damagedBlockProgram.use();
            this.damagedBlockProgram.setInt("tex", 0);      // MC binds the crack-sprite atlas on unit 0 here
            this.damagedBlockProgram.setInt("gtexture", 0);
            this.damagedBlockProgram.setInt("lightmap", 1);
            setSceneUniforms(this.damagedBlockProgram);
            bindShadowForImmediate(this.damagedBlockProgram);
            bindNoiseForImmediate(this.damagedBlockProgram);
            bindGbuffersSceneSamplers(this.damagedBlockProgram);
            applyAlphaOverride("gbuffers_damagedblock");
            if (!this.damagedLogged) {
                this.damagedLogged = true;
                log("block-breaking overlay rendered by pack gbuffers_damagedblock");
            }
        }
    }

    public void endDamagedBlock() {
        if (this.damagedActive) {
            restoreAlphaFunc();
            GlslProgram.unuse();
            this.damagedActive = false;
        }
    }

    // --- weather (rain/snow, immediate-mode), around EntityRenderer.renderRainSnow ---

    /**
     * Binds {@code gbuffers_weather} for the falling rain/snow. MC's {@code renderRainSnow} draws the weather as textured
     * quads (the rain/snow atlas on unit 0) with the fixed-function pipeline; binding the pack program routes them through
     * the pack's weather shading (ambient/sun tint, opacity) instead of vanilla's flat streaks. IMPROVED_RAIN is gated to
     * MC 1.16.5+ so on 1.12.2 the program writes colour straight to colortex0 — no extra targets needed. No-op when shaders
     * are off or the pack has no weather program (then rain renders vanilla).
     */
    public void beginWeather() {
        this.weatherActive = this.enabled && this.activeThisFrame && this.weatherProgram != null;
        if (this.weatherActive) {
            this.weatherProgram.use();
            this.weatherProgram.setInt("tex", 0);      // MC binds the rain/snow texture on unit 0
            this.weatherProgram.setInt("gtexture", 0); // alias, in case the pack samples gtexture
            setSceneUniforms(this.weatherProgram);
            bindShadowForImmediate(this.weatherProgram); // same unit-0 trap as particles — rain must darken in shadow too
            bindNoiseForImmediate(this.weatherProgram); // GLITTER_RAIN samples noisetex (off by default; harmless to bind)
            bindGbuffersSceneSamplers(this.weatherProgram); // colortex10/18/shadowcolor0 → real buffers (else unit 0 = item atlas)
            // alphaTest.gbuffers_weather = GREATER 0.0001: MC tests rain at 0.1, which kills the pack's faint streak
            // fragments (thinner rain than 1.20.1); rain.depth = false: streaks must not write scene depth (they'd
            // punch rain-shaped holes into the composite's depth-driven fog).
            applyAlphaOverride("gbuffers_weather");
            if (!this.packRainDepth) {
                GlStateManager.depthMask(false);
            }
            if (!this.weatherLogged) {
                this.weatherLogged = true;
                log("weather (rain/snow) rendered by pack gbuffers_weather");
                auditSamplerUnits("gbuffers_weather", this.weatherProgram);
            }
        }
    }

    public void endWeather() {
        if (this.weatherActive) {
            restoreAlphaFunc();
            if (!this.packRainDepth) {
                GlStateManager.depthMask(true);
            }
            GlslProgram.unuse();
            this.weatherActive = false;
        }
    }

    // The spare texture unit an immediate-mode pack program (hand/entities/block entities) samples the shadow map from —
    // units 0/1 are the atlas + lightmap during those draws, so the shadow map can't reuse them (matches the terrain path).
    private static final int IMMEDIATE_SHADOW_UNIT = 4;

    /**
     * Binds the shadow map + sets the shadow sampler uniforms for an immediate-mode pack program (hand/entities/block
     * entities). Without this, the program's {@code GetShadow} lookup samples an UNSET {@code sampler2DShadow shadowtex0}
     * (defaults to unit 0 = the block/entity atlas), so the depth compare always fails → every fragment reads as fully
     * shadowed → the player/entity renders dark even in direct sun. The shadow depth texture already carries the hardware
     * compare mode (COMPARE_REF_TO_TEXTURE) the {@code sampler2DShadow} needs. Uses raw GL for the high unit, then restores
     * the active unit to 0 so MC's immediate-mode atlas texturing (unit 0) is unaffected.
     */
    private void bindShadowForImmediate(GlslProgram p) {
        if (this.targets == null) {
            return;
        }
        int shadowTex = this.targets.shadowDepthTexture();
        if (shadowTex != 0) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + IMMEDIATE_SHADOW_UNIT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, shadowTex);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
        }
        p.setInt("shadowtex0", IMMEDIATE_SHADOW_UNIT);
        // shadowtex1 must be the OPAQUE-ONLY depth here too: with UNDERWATER_SHAFTS the water surface is IN shadowtex0,
        // so an aliased shadowtex1 makes the pack's "a translucent blocked the light" test (shadow0 < 1 but shadow1 ≈ 1)
        // always fail for anything below a water surface — the hand/entities/block entities/particles/weather then took
        // the opaque BLACK-shadow path instead of the caustic-tinted one, the same mechanism that blackened the seabed
        // before terrain moved to unit 47. The opaque copy already sits on TERRAIN_SHADOWTEX1_UNIT (bound once per frame
        // in beginWorldRender, compare mode permanently ON — matching these programs' shadow2D reads). With the feature
        // off nothing fills that copy, so keep the old alias exactly as before.
        p.setInt("shadowtex1", UNDERWATER_SHAFTS ? RenderTargets.TERRAIN_SHADOWTEX1_UNIT : IMMEDIATE_SHADOW_UNIT);
        p.setInt("shadow", IMMEDIATE_SHADOW_UNIT);
        p.setInt("watershadow", IMMEDIATE_SHADOW_UNIT);
    }

    /**
     * The window-depth slice the first-person hand is compressed into ({@code glDepthRange(0, HAND_DEPTH_RANGE)}), so it
     * always wins against world geometry while its own faces still depth-sort against each other.
     *
     * <p>{@link #injectHandDepthFix} depends on this value, so keep the two together: the pack un-projects the hand's
     * window depth to find its view position, and that only works if the compression is undone with the SAME constant.</p>
     */
    private static final double HAND_DEPTH_RANGE = 0.1;

    private final Matrix4f handProjection = new Matrix4f();
    private final Matrix4f handProjectionInverse = new Matrix4f();
    private final java.nio.FloatBuffer matrixReadback = org.lwjgl.BufferUtils.createFloatBuffer(16);

    /**
     * Overrides {@code gbufferProjection}/{@code gbufferProjectionInverse} with the HAND's projection for the hand pass.
     *
     * <p>Minecraft draws the first-person hand under its own projection: {@code EntityRenderer.renderHand} rebuilds it
     * with {@code getFOVModifier(partialTicks, false)} — the base FOV, deliberately WITHOUT the sprint/potion modifier the
     * world pass gets ({@code true}). The pipeline captures its matrices during the terrain layers, so those uniforms hold
     * the WORLD projection, and gbuffers_hand reconstructs its view position from the depth buffer:
     * {@code viewPos = ScreenToView(vec3(gl_FragCoord.xy / view, gl_FragCoord.z + 0.38))} — through
     * {@code gbufferProjectionInverse}. Feeding the world's matrix un-projects the hand's depth with the wrong FOV, so
     * viewPos lands somewhere it never was, and the error CHANGES with the sprint FOV modifier. That surfaced as a hard
     * line across the held block that moved when sprinting: the handheld light's directional term
     * ({@code dot(normalize(cameraHeldLightPos - viewPos), normalM)}) is the first thing in this port to depend on the
     * hand's viewPos being real.</p>
     *
     * <p>Read straight from GL because this runs at the head of {@code renderItemInFirstPerson}, i.e. after renderHand has
     * installed the hand projection. Only the projection differs — the model-view is the same camera orientation as the
     * world pass, so it is left alone.</p>
     */
    private void applyHandProjection(GlslProgram p) {
        this.matrixReadback.clear();
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, this.matrixReadback);
        this.handProjection.set(this.matrixReadback);
        this.handProjection.invert(this.handProjectionInverse);
        p.setMatrix4("gbufferProjection", this.handProjection);
        p.setMatrix4("gbufferProjectionInverse", this.handProjectionInverse);
    }

    /** Binds a program with the standard-item-lit uniforms (gtexture/lightmap samplers + the two world-fixed eye-space
     * light directions), shared by the entity + block-entity programs. */
    private void useItemLitProgram(GlslProgram p) {
        p.use();
        p.setInt("gtexture", 0);
        p.setInt("lightmap", 1);
        setSceneUniforms(p);
        bindShadowForImmediate(p); // real shadows → not dark in sun
        bindNoiseForImmediate(p);  // mainLighting/atmosphere dithering samples noisetex
        this.customImages.bindTo(p); // colored-light floodfill read + block-entity light voxelization writes
        bindGbuffersSceneSamplers(p); // colortex10/18/shadowcolor0 → real buffers (else unit 0 = item atlas → speckle)
        Vector3f l0 = this.gbufferModelView.transformDirection(new Vector3f(ITEM_LIGHT0));
        Vector3f l1 = this.gbufferModelView.transformDirection(new Vector3f(ITEM_LIGHT1));
        p.setVec3("yl_ItemLightDir0", l0.x, l0.y, l0.z);
        p.setVec3("yl_ItemLightDir1", l1.x, l1.y, l1.z);
    }

    /** Binds the three SCENE buffers the pack's IMMEDIATE-mode gbuffers world programs (hand, held item, particles,
     * entities, block entities, weather) sample — colortex10 (ApplyMultiColoredBlocklight's reprojected coloured
     * blocklight), colortex18 (Voxy SS-shadow), shadowcolor0 (stained-glass shadow) — onto their dedicated units, and
     * points those samplers there. Without it those samplers defaulted to unit 0 = the ITEM ATLAS, so the held item and
     * block-break particles wore the same tinted "item textures" speckle the terrain did (see
     * {@link RenderTargets#bindTerrainSceneTextures}). NEVER call this for composites — they bind their own colortex bank
     * on units 2+ and must not be repointed. No-op samplers the program doesn't declare (GlslProgram.setInt tolerant). */
    /**
     * DIAGNOSTIC (one-shot, per program): asks GL which texture unit every ACTIVE sampler of {@code program} resolves to,
     * and flags the ones sitting on unit 0.
     *
     * <p>This port's most repeated bug is a sampler nobody called {@code glUniform1i} for: it keeps its default value 0
     * and silently reads unit 0 — the block atlas during world rendering. It has now caused five separate bugs
     * (textureAtlas ×2, gaux4, noisetex, and shadowtex0 on particles). A hand-maintained "we provide these" list cannot
     * catch it — {@code PROVIDED_UNIFORMS} lists shadowtex0 while nothing bound it for the particle program, so the audit
     * reported it as fine. Ask GL instead. Call with the program bound, after its uniform setup.</p>
     *
     * <p>Unit 0 is legitimate for the diffuse atlas ({@code tex}/{@code gtexture}), so those are not flagged.</p>
     */
    private void auditSamplerUnits(String label, GlslProgram program) {
        if (program == null) {
            return;
        }
        java.util.List<String> suspect = new java.util.ArrayList<>();
        StringBuilder all = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> e : program.samplerBindings().entrySet()) {
            all.append(' ').append(e.getKey()).append('=').append(e.getValue());
            // Unit 0 is legitimate for the diffuse atlas...
            boolean atlasIsCorrect = e.getKey().equals("tex") || e.getKey().equals("gtexture")
                    || e.getKey().equals("texture") || e.getKey().equals("lightmap");
            // ...and for IMAGES, which are bound with glBindImageTexture into a SEPARATE unit namespace — image unit 0 has
            // nothing to do with texture unit 0/the block atlas. Flagging them produced a false positive (endcrystal_img).
            boolean isImage = program.samplerTypeOf(e.getKey()) >= 0x904C && program.samplerTypeOf(e.getKey()) <= 0x906C;
            if (e.getValue() == 0 && !atlasIsCorrect && !isImage) {
                suspect.add(e.getKey());
            }
        }
        // Silent when healthy: the audit's value is the canary, not the roll call — only a sampler stuck on unit 0
        // (this port's most repeated bug) is worth a log line, and THAT stays unconditional. Also recorded for the
        // health report, so a hit that scrolled out of the log is still discoverable later.
        if (!suspect.isEmpty()) {
            AUDIT_FAILURES.add(label + ": " + String.join(", ", suspect));
            log("[SAMPLER AUDIT] " + label + ":" + all
                    + "  | *** ON UNIT 0 (= block atlas, almost certainly unbound): " + String.join(", ", suspect) + " ***");
        }
    }

    private void bindGbuffersSceneSamplers(GlslProgram program) {
        if (this.targets == null) return;
        this.targets.bindTerrainSceneTextures();
        program.setInt("colortex10", RenderTargets.TERRAIN_COLORTEX10_UNIT);
        program.setInt("colortex18", RenderTargets.TERRAIN_COLORTEX18_UNIT);
        program.setInt("shadowcolor0", RenderTargets.TERRAIN_SHADOWCOLOR0_UNIT);
        // gaux2 = colortex5 = deferred1's volumetric-cloud depth. gbuffers_textured DISCARDS particles that fall behind it
        // (`if (pow2(cloudLinearDepth + …) * renderDistance < lViewPos) discard`), so with this on unit 0 the block atlas's
        // alpha at screen coords was being read as cloud depth — particles vanishing for no reason. Previous-frame content
        // is expected here: the pack notes particles sit before deferred1 in the pipeline, and colortex5 is clear-exempt.
        program.setInt("gaux2", RenderTargets.TERRAIN_GAUX2_UNIT);
        // gaux4 = the pack's cloud-water noise (cloud-water.png), not a colortex. The terrain program binds it for the same
        // reason; without it the wetness/puddle code reads unit 0 = the block atlas as "noise".
        int cloudWater = this.targets.waterNormalTexture();
        if (cloudWater != 0) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + RenderTargets.CLOUD_WATER_UNIT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, cloudWater);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            program.setInt("gaux4", RenderTargets.CLOUD_WATER_UNIT);
        }
        // The coloured-lighting volumes (floodfill_sampler / floodfill_sampler_copy / voxel_sampler). The sampler audit
        // caught all three sitting on unit 0 for particles — useItemLitProgram binds them for entities, this path did not.
        this.customImages.bindTo(program);
    }

    /**
     * @return the active pack's raw {@code gbuffers_terrain} source as {@code {vertex, fragment}} (loaded lazily), or
     * {@code null} if the pack provides no complete terrain program. Consumed by Sodium's {@code ShaderChunkRenderer},
     * which transforms it to Sodium's chunk-shader dialect (M5 step 2).
     */
    public String[] packTerrainSources() {
        if (!this.packTerrainLoaded) {
            this.packTerrainLoaded = true;
            try (ShaderPack pack = loadActivePack()) {
                if (pack != null) {
                    ProgramSource src = pack.programSet().get("gbuffers_terrain");
                    if (src != null && src.vertex() != null && src.fragment() != null) {
                        this.packTerrainSources = new String[]{applyOptions(src.vertex()), applyOptions(src.fragment())};
                    }
                }
            } catch (Throwable t) {
                SodiumClientMod.logger().error("[Iris] loading pack gbuffers_terrain failed", t);
            }
        }
        return this.packTerrainSources;
    }

    /**
     * @return the active pack's {@code shadow} program VERTEX source (options applied, loaded lazily), or {@code null} if
     * the pack has no shadow program. Consumed by {@code ShaderChunkRenderer} to build a lightweight shadow-pass program
     * (this pack shadow vertex + a minimal alpha-test fragment) so the shadow depth map isn't rendered with the full
     * gbuffers_terrain shader.
     */
    public String packShadowVertexSource() {
        if (!this.packShadowLoaded) {
            this.packShadowLoaded = true;
            try (ShaderPack pack = loadActivePack()) {
                if (pack != null) {
                    ProgramSource src = pack.programSet().get("shadow");
                    if (src != null && src.vertex() != null) {
                        this.packShadowVertexSource = applyOptions(src.vertex());
                    }
                }
            } catch (Throwable t) {
                SodiumClientMod.logger().error("[Iris shadow] loading pack shadow program failed", t);
            }
        }
        return this.packShadowVertexSource;
    }

    /**
     * @return the active pack's {@code gbuffers_water} source {vertex, fragment} (options applied, loaded lazily), or
     * {@code null} if none. Consumed by {@code ShaderChunkRenderer} for the TRANSLUCENT pass so water gets waves/
     * reflections/refraction instead of flat terrain shading.
     */
    public String[] packWaterSources() {
        ensurePackWaterLoaded();
        return this.packWaterSources;
    }

    /** @return the colortex indices {@code gbuffers_water}'s active branch writes (its {@code gl_FragData[i]} order), for
     * the translucent pass to attach; {@code null} if no water program. */
    public int[] packWaterTargets() {
        ensurePackWaterLoaded();
        return this.packWaterTargets;
    }

    /**
     * Swaps the water SSR's reflection COLOUR source from last frame's gaux2 snapshot to THIS frame's opaque scene.
     *
     * <p>gaux2 (colortex5 = deferred1's {@code waterRefColor}) cannot exist yet when the water draws in our pipeline —
     * deferred runs AFTER water here, where real Iris runs it BEFORE — so the port fed the water a 1-FRAME-OLD snapshot
     * ({@code snapshotWaterRefColor}). That is visible as the water reflection TRAILING the camera by one frame whenever
     * the view moves; it persisted with TAA fully off, which pinned it on this path. The water pass already has a
     * CURRENT-frame copy of the opaque scene bound as its colortex0 unit ({@code waterReflectTex}, taken by
     * {@code copyColor0ForWater} just before the water draws) — so sample THAT for the reflection colour instead. It is
     * stored linear where waterRefColor is sqrt-encoded, so the pack's {@code pow2(rgb*2.0)} decode is removed with it
     * (both decode sites belong to the two gaux2 samples — verified 2 occurrences pack-wide). Content difference vs the
     * real waterRefColor: deferred1's SSAO is missing from the mirrored image (subtle). gaux2 itself STAYS on the old
     * snapshot — its .a (volumetric-cloud depth) still feeds the water's cloud-occlusion discard exactly as before.</p>
     */
    private static final boolean WATER_REF_CURRENT_FRAME = true;

    private String injectCurrentFrameWaterReflection(String source) {
        if (!WATER_REF_CURRENT_FRAME || source == null) {
            return source;
        }
        // Verify every anchor before relying on it: String.replace is SILENT on a miss, and a partial injection (sampler
        // swapped but decode left in, or vice versa) would mis-tint every reflection. On any miss, fall back to the old
        // (laggy but correct) snapshot path untouched.
        String[][] anchors = {
                {"decl", "uniform sampler2D gaux2;"},
                {"far", "reflection = vec4(texture2D(gaux2, refPos.xy).rgb, 1.0);"},
                {"near", "reflection.rgb = texture2D(gaux2, screenPosR.xy).rgb;"},
                {"decode", "reflection.rgb = pow2(reflection.rgb * 2.0);"},
        };
        StringBuilder report = new StringBuilder("[WATER REF] current-frame reflection anchors:");
        boolean all = true;
        for (String[] a : anchors) {
            boolean hit = source.contains(a[1]);
            all &= hit;
            report.append(' ').append(a[0]).append('=').append(hit ? "HIT" : "*** MISS ***");
        }
        if (!all) {
            log(report.toString()); // a drifted anchor must stay loud — the silent fallback is the laggy snapshot path
            return source;
        }
        String s = source;
        s = s.replace("uniform sampler2D gaux2;",
                "uniform sampler2D gaux2;\nuniform sampler2D yumelium_reflectColor; // [yumelium] current-frame opaque scene");
        s = s.replace("reflection = vec4(texture2D(gaux2, refPos.xy).rgb, 1.0);",
                "reflection = vec4(texture2D(yumelium_reflectColor, refPos.xy).rgb, 1.0); // [yumelium] current frame, linear");
        s = s.replace("reflection.rgb = texture2D(gaux2, screenPosR.xy).rgb;",
                "reflection.rgb = texture2D(yumelium_reflectColor, screenPosR.xy).rgb; // [yumelium] current frame, linear");
        s = s.replace("reflection.rgb = pow2(reflection.rgb * 2.0);",
                "; // [yumelium] linear source, sqrt decode removed");
        return s;
    }

    private void ensurePackWaterLoaded() {
        if (this.packWaterLoaded) {
            return;
        }
        this.packWaterLoaded = true;
        try (ShaderPack pack = loadActivePack()) {
            if (pack != null) {
                ProgramSource src = pack.programSet().get("gbuffers_water");
                if (src != null && src.vertex() != null && src.fragment() != null) {
                    String frag = injectWaterFresnelView(injectCurrentFrameWaterReflection(applyOptions(src.fragment())));
                    this.packWaterSources = new String[]{applyOptions(src.vertex()), frag};
                    this.packWaterTargets = parseRenderTargets(GlslConditionals.stripInactive(frag));
                    log("gbuffers_water RENDERTARGETS=" + java.util.Arrays.toString(this.packWaterTargets));
                }
            }
        } catch (Throwable t) {
            SodiumClientMod.logger().error("[Iris] loading pack gbuffers_water failed", t);
        }
    }

    /**
     * Captures the world camera projection + model-view matrices, called from the terrain draw (where the GL matrices
     * hold the world's transform, not the hand's). Derives the inverses + the view-space sun/moon/up directions.
     */
    /** Iris G-buffer matrices, captured during terrain draw — also fed to the pack terrain program (ChunkShaderInterface)
     * so its vertex position math (which routes through gbufferModelView[Inverse]) and fragment lighting get real values,
     * not an all-zero unset uniform. */
    public Matrix4fc gbufferModelViewMatrix() {
        return this.gbufferModelView;
    }

    public Matrix4fc gbufferModelViewInverseMatrix() {
        return this.modelViewInverse;
    }

    public Matrix4fc gbufferProjectionMatrix() {
        return this.gbufferProjection;
    }

    public Matrix4fc gbufferProjectionInverseMatrix() {
        return this.projectionInverse;
    }

    public void captureMatrices(Matrix4fc projection, Matrix4fc modelView) {
        // NOTE: this runs once PER TERRAIN LAYER (solid/cutout/translucent), i.e. several times a frame — so the
        // previous-frame matrices are snapshotted once in beginWorldRender, NOT here (saving them here would overwrite
        // last frame's with THIS frame's on the 2nd layer → zero motion vectors → motion blur/TAA do nothing).
        this.gbufferProjection.set(projection);
        projection.invert(this.projectionInverse);
        this.gbufferModelView.set(modelView);
        modelView.invert(this.modelViewInverse);

        // The sun rides a circle rotated about the world X axis (as vanilla renders it): dir = (0, cos a, sin a).
        float a = this.celestialAngle * 6.2831855F;
        Vector3f sunView = this.gbufferModelView.transformDirection(new Vector3f(0.0F, (float) Math.cos(a), (float) Math.sin(a)));
        store(this.sunPos, sunView.x, sunView.y, sunView.z);
        store(this.moonPos, -sunView.x, -sunView.y, -sunView.z);
        boolean sunUp = Math.cos(a) >= 0.0;
        if (sunUp) {
            store(this.shadowLightPos, this.sunPos[0], this.sunPos[1], this.sunPos[2]);
        } else {
            store(this.shadowLightPos, this.moonPos[0], this.moonPos[1], this.moonPos[2]);
        }
        Vector3f upView = this.gbufferModelView.transformDirection(new Vector3f(0.0F, 1.0F, 0.0F));
        store(this.upPos, upView.x, upView.y, upView.z);

        if (isDiagFrame()) {
            log(String.format("[DIAG capture] MV diag=(%.3f,%.3f,%.3f,%.3f) trans=(%.2f,%.2f,%.2f) MVinv diag=(%.3f,%.3f,%.3f)",
                    this.gbufferModelView.m00(), this.gbufferModelView.m11(), this.gbufferModelView.m22(), this.gbufferModelView.m33(),
                    this.gbufferModelView.m30(), this.gbufferModelView.m31(), this.gbufferModelView.m32(),
                    this.modelViewInverse.m00(), this.modelViewInverse.m11(), this.modelViewInverse.m22()));
            // Reproduce Complementary's terrain sun math (GetSunVector + sunVisibility) from worldTime, to check whether
            // the pack sees this frame as day (sunVisibility~1) or night (~0) — the driver of lightColor/ambientColor.
            float timeAngle = this.worldTime / 24000.0f;
            float ang0 = timeAngle - 0.25f; ang0 -= (float) Math.floor(ang0);
            float ang = (float) ((ang0 + (Math.cos(ang0 * Math.PI) * -0.5 + 0.5 - ang0) / 3.0) * 6.2831853);
            float worldSunY = (float) Math.cos(ang);
            float sunVis = Math.max(0.0f, Math.min(0.125f, worldSunY + 0.0625f)) / 0.125f;
            // Replicate the pack's EXACT sunVisibility using the captured gbufferModelView (GLSL column-major semantics):
            // sunVec = normalize((MV * vec4(worldSunDir*2000,1)).xyz); upVec = normalize(MV[1].xyz); SdotU = dot(sunVec,upVec).
            Vector4f wsun = new Vector4f((float) -Math.sin(ang) * 2000f, worldSunY * 2000f, 0f, 1f);
            this.gbufferModelView.transform(wsun);
            Vector3f sunVec = new Vector3f(wsun.x, wsun.y, wsun.z).normalize();
            Vector3f upVec = new Vector3f(this.gbufferModelView.m10(), this.gbufferModelView.m11(), this.gbufferModelView.m12()).normalize();
            float sdotU = sunVec.dot(upVec);
            float sunVisShader = Math.max(0.0f, Math.min(0.125f, sdotU + 0.0625f)) / 0.125f;
            log(String.format("[DIAG sun] worldTime=%d timeAngle=%.3f worldSun.y=%.3f | MV col1=(%.3f,%.3f,%.3f) SdotU=%.3f sunVis(shader)=%.3f sunVis(simple)=%.3f",
                    this.worldTime, timeAngle, worldSunY,
                    this.gbufferModelView.m10(), this.gbufferModelView.m11(), this.gbufferModelView.m12(),
                    sdotU, sunVisShader, sunVis));
        }
    }

    private static void store(float[] dst, float x, float y, float z) {
        dst[0] = x;
        dst[1] = y;
        dst[2] = z;
    }

    /** GLSL {@code fract}: x − floor(x). */
    private static float frac(float x) {
        return x - (float) Math.floor(x);
    }

    /** True while the light-POV shadow pass is drawing terrain (so the chunk renderer skips camera block-face culling). */
    public boolean isShadowPass() {
        return this.shadowPass;
    }

    /** The camera position the shadow ortho box is centred on — the origin {@link #isSectionOutsideShadow} measures from. */
    public org.joml.Vector3d shadowCameraPos() {
        return new org.joml.Vector3d(this.cameraX, this.cameraY, this.cameraZ);
    }

    // Shadow-pass geometry cull margin: the shadow ortho covers exactly ±shadowDistanceThisFrame in the two light-view axes
    // perpendicular to the sun, so a section whose centre projects beyond that (plus a section-radius margin) can't cast
    // into the shadow map. Culling those stops the shadow pass from re-drawing the ENTIRE camera-visible terrain (out to
    // the render distance) when the map only needs ~192 blocks around the camera — the dominant cost at high render
    // distance (the shadow pass reuses the camera-visible section set, so without this it draws render-distance² geometry).
    /** Section-radius slack added to the box on every axis. A 16³ section's half-diagonal is 8√3 ≈ 13.86, so this
     * leaves ≈10.14 blocks of genuine headroom — the "~10 shadow box slack" term the shadow-list margin derivation in
     * RenderSectionManager relies on. Changing it changes that derivation. */
    public static final float SHADOW_BOX_SECTION_SLACK = 24.0F;

    private float shadowCullExtent() {
        return this.shadowDistanceThisFrame + SHADOW_BOX_SECTION_SLACK;
    }

    // Depth (along-sun) bounds of the shadow ortho in light-view space. computeShadowMatrices puts the light eye at
    // distance d = shadowDistanceThisFrame + 96 and uses ortho far = 2·d, so a point's view-space Z (negative in front of the eye)
    // is inside the box when −Z ∈ [~0, 2·d]; i.e. lz ∈ [−(2·d), 0]. The camera itself sits at lz = −d (box centre).
    /** Light-eye pull-back beyond the box half-extent, so the near plane clears it. */
    public static final float SHADOW_EYE_PULLBACK = 96.0F;

    private float shadowEyeDistance() {
        return this.shadowDistanceThisFrame + SHADOW_EYE_PULLBACK;
    }

    /**
     * @return true if a 16³ section centred at {@code (rx,ry,rz)} RELATIVE TO THE CAMERA falls outside the shadow ortho
     * box, so it need not be drawn in the shadow pass. Only meaningful while {@link #isShadowPass()} (the shadow matrices
     * are built camera-relative, camera at the origin). Full frustum test: the two axes perpendicular to the sun (±
     * shadowDistanceThisFrame) plus the along-sun depth range — so it never drops a caster the shadow map would actually sample.
     */
    public boolean isSectionOutsideShadow(float rx, float ry, float rz) {
        return isSectionOutsideShadow(rx, ry, rz, 0.0F);
    }

    /** {@code margin}: extra blocks of slack on every bound — used by the shadow-list CACHE build so the cached
     * list provably covers the camera/sun drift allowed below the rebuild thresholds (see
     * RenderSectionManager.shadowListMargin(), which derives it from this box's size). Encode-time culling passes 0. */
    public boolean isSectionOutsideShadow(float rx, float ry, float rz, float margin) {
        Matrix4f m = this.shadowModelView;
        float lx = m.m00() * rx + m.m10() * ry + m.m20() * rz + m.m30();
        float ly = m.m01() * rx + m.m11() * ry + m.m21() * rz + m.m31();
        float cullExtent = shadowCullExtent();
        if (Math.abs(lx) > cullExtent + margin || Math.abs(ly) > cullExtent + margin) {
            return true;
        }
        float lz = m.m02() * rx + m.m12() * ry + m.m22() * rz + m.m32();
        return lz > SHADOW_BOX_SECTION_SLACK + margin
                || lz < -(2.0F * shadowEyeDistance()) - SHADOW_BOX_SECTION_SLACK - margin;
    }

    /** Half-extents of the pack's camera-anchored colored-lighting voxel volume, or {@code null} when colored lighting
     * is off. Sections inside this box must never be culled from the shadow pass — the shadow vertex is what voxelizes
     * them (see RenderSectionManager.updateShadowRenderList). */
    public int[] voxelVolumeHalfExtents() {
        return this.customImages == null ? null : this.customImages.voxelHalfExtents();
    }

    /**
     * DIAGNOSTIC: replicates the pack's {@code GetShadowPos(scenePos)} — {@code PlayerToShadow} (spaceConversion.glsl)
     * then the distortion in shadowSampling.glsl — on the CPU, from the SAME matrix fields {@link #setSceneUniforms}
     * hands composite1. The volumetric light gates every shadow sample on
     * {@code length(shadowPos.xy * 2.0 - 1.0) < 1.0}, and forcing that test open measurably changed the picture, so it
     * was never passing. GetShadowPos is pure arithmetic over two matrices, so it can be evaluated here exactly —
     * no GPU readback and nothing for anyone to eyeball.
     *
     * <p>Sanity: at {@code scenePos ≈ 0} (the camera) the ortho box is centred on the camera, so shadowPos.xy should be
     * ≈ (0.5, 0.5) and {@code range} ≈ 0 → PASS. A range far above 1 at the camera means the matrices are not what the
     * pack's math expects; identity matrices in particular would send every point out of the disc.</p>
     */
    /**
     * DIAGNOSTIC (underwater god rays): replicates the pack's VL shadow branch (volumetricLight.glsl:204-219) on the CPU
     * at a few camera-relative points and reads the REAL textures — shadowtex0 (all casters), shadowtex1 (opaque-only),
     * shadowcolor0/1 — so the branch each point actually takes is measured, not guessed. The shaft exists only where a
     * point reads "blocked in tex0, clear in tex1" and shadowcolor1 carries the water tint; this logs every link:
     * water depth reaching shadowtex0, the opaque copy differing, the tint being written, and the resulting density.
     */
    private void diagUnderwaterShadow() {
        final float bias = this.shadowMapBiasThisFrame;
        int res = this.targets.shadowSize();
        // Locate the REAL seabed under the camera so each probe can be compared against it: the horizontal shaft cutoff
        // means the "opaque surface" the VL sees (z crossing tex1) sits somewhere — this tells us whether that height
        // matches the actual floor or floats above it.
        StringBuilder sb = new StringBuilder("[UW SHADOW] vertical column (player space):");
        try {
            net.minecraft.client.Minecraft mcD = Minecraft.getMinecraft();
            net.minecraft.entity.Entity viewD = mcD.getRenderViewEntity();
            if (viewD != null) {
                int floorY = -1;
                net.minecraft.util.math.BlockPos.MutableBlockPos fp = new net.minecraft.util.math.BlockPos.MutableBlockPos(
                        (int) Math.floor(viewD.posX), 0, (int) Math.floor(viewD.posZ));
                for (int y = (int) Math.floor(viewD.posY); y > (int) Math.floor(viewD.posY) - 32 && y > 0; y--) {
                    fp.setY(y);
                    if (mcD.world.getBlockState(fp).getMaterial() != net.minecraft.block.material.Material.WATER) {
                        floorY = y;
                        break;
                    }
                }
                sb.append(String.format(" cameraY=%.2f floorTopY=%d (floor %.1f blocks below camera)",
                        this.cameraY, floorY + 1, this.cameraY - (floorY + 1)));
            }
        } catch (Throwable ignored) {}
        // Near-vertical column + FAR floor-hugging points: the visible fog band floats above the seabed at DISTANCE, so
        // the question is whether far samples at floor height die in the shadow test (distortion coarsens the shadow
        // texel with distance → on a duned floor the stored opaque depth is the texel's HIGHEST point → a sample just
        // above the local floor reads "inside the ground").
        float[][] probes = {{0, -1, 0}, {0, -2, 0}, {0, -3, 0},
                {10, -2, 0}, {20, -2, 0}, {40, -2, 0}, {60, -2, 0}, {0, -2, 30}, {30, -2, 30}};
        for (float[] p : probes) {
            org.joml.Vector3f v = this.shadowModelView.transformPosition(new org.joml.Vector3f(p[0], p[1], p[2]));
            float x = this.shadowProjection.m00() * v.x + this.shadowProjection.m30();
            float y = this.shadowProjection.m11() * v.y + this.shadowProjection.m31();
            float z = this.shadowProjection.m22() * v.z + this.shadowProjection.m32();
            float distb = (float) Math.sqrt(x * x + y * y);
            float distortFactor = distb * bias + (1.0F - bias);
            x /= distortFactor;
            y /= distortFactor;
            z *= 0.2F;
            float range = (float) Math.sqrt(x * x + y * y);
            float sx = x * 0.5F + 0.5F, sy = y * 0.5F + 0.5F, sz = z * 0.5F + 0.5F;
            sb.append("\n    off=(").append(String.format("%.0f,%.0f,%.0f", p[0], p[1], p[2])).append(')');
            if (range >= 1.0F) {
                sb.append(" OUT OF DISC");
                continue;
            }
            int tx = (int) (sx * res), ty = (int) (sy * res);
            float d0 = this.targets.readShadowDepthTexel(tx, ty);
            float d1 = this.targets.readShadowDepthOpaqueTexel(tx, ty);
            float[] c0 = this.targets.readShadowColorTexel(false, tx, ty);
            float[] c1 = this.targets.readShadowColorTexel(true, tx, ty);
            // The pack's exact math: raw fetch on shadowtex0; hardware LEQUAL compare on shadowtex1 (1 = ref <= stored).
            float shadowSample = Math.max(0.0F, Math.min(1.0F, (d0 - sz) * 65536.0F));
            float trans = sz <= d1 ? 1.0F : 0.0F;
            String branch;
            if (shadowSample > 0.0F) {
                branch = "LIT (direct sun)";
            } else if (trans == 1.0F) {
                float lr = (float) Math.pow(c1[0] * 4.0F, 2), lg = (float) Math.pow(c1[1] * 4.0F, 2), lb = (float) Math.pow(c1[2] * 4.0F, 2);
                branch = String.format("TRANSLUCENT -> density=pow2(sc1*4)=(%.2f,%.2f,%.2f)", lr, lg, lb);
            } else {
                branch = "OPAQUE SHADOW (density=0, shaft dead)";
            }
            sb.append(String.format(" tex0=%.5f tex1=%.5f z=%.5f | sample=%.0f trans=%.0f | sc0=(%.2f,%.2f,%.2f) sc1=(%.2f,%.2f,%.2f,a=%.2f) | %s",
                    d0, d1, sz, shadowSample, trans, c0[0], c0[1], c0[2], c1[0], c1[1], c1[2], c1[3], branch));
        }
        sb.append("\n    tex0==tex1 everywhere would mean the water never wrote shadow depth (or the snapshot ran after it).");
        log(sb.toString());
    }

    private void diagShadowPos() {
        final float bias = this.shadowMapBiasThisFrame; // pack: shadowMapBias = 1.0 - 25.6 / shadowDistance
        StringBuilder sb = new StringBuilder("[SHADOWPOS] bias=" + String.format("%.4f", bias)
                + " smvTranslation=(" + String.format("%.2f,%.2f,%.2f",
                this.shadowModelView.m30(), this.shadowModelView.m31(), this.shadowModelView.m32())
                + ") sprojDiag=(" + String.format("%.5f,%.5f,%.5f",
                this.shadowProjection.m00(), this.shadowProjection.m11(), this.shadowProjection.m22())
                + ") sproj[3]=(" + String.format("%.4f,%.4f,%.4f",
                this.shadowProjection.m30(), this.shadowProjection.m31(), this.shadowProjection.m32()) + ")");
        // Player-space (camera-relative) probe points, the space GetVolumetricLight marches scenePos through.
        float[][] probes = {{0, 0, 0}, {0, 0, -4}, {0, 0, -16}, {0, 0, -64}, {0, 0, -150}, {64, 0, -64}};
        for (float[] p : probes) {
            // PlayerToShadow: mat3(shadowModelView) * pos + shadowModelView[3].xyz
            org.joml.Vector3f v = this.shadowModelView.transformPosition(new org.joml.Vector3f(p[0], p[1], p[2]));
            // projMAD(shadowProjection, v) = diagonal3(m) * v + m[3].xyz  (valid for the ortho projection)
            float x = this.shadowProjection.m00() * v.x + this.shadowProjection.m30();
            float y = this.shadowProjection.m11() * v.y + this.shadowProjection.m31();
            float z = this.shadowProjection.m22() * v.z + this.shadowProjection.m32();
            float distb = (float) Math.sqrt(x * x + y * y);
            float distortFactor = distb * bias + (1.0F - bias);
            x /= distortFactor;
            y /= distortFactor;
            z *= 0.2F;
            // GetShadowPos returns pos * 0.5 + 0.5; the pack then tests length(xy * 2.0 - 1.0), i.e. the distorted NDC.
            float range = (float) Math.sqrt(x * x + y * y);
            float sx = x * 0.5F + 0.5F;
            float sy = y * 0.5F + 0.5F;
            float sz = z * 0.5F + 0.5F;
            sb.append("\n    scenePos=(").append(String.format("%.0f,%.0f,%.0f", p[0], p[1], p[2]))
                    .append(") shadowPos=(").append(String.format("%.3f,%.3f,%.3f", sx, sy, sz))
                    .append(") range=").append(String.format("%.3f", range))
                    .append(range < 1.0F ? " PASS" : " *** OUT OF DISC ***");
            if (range < 1.0F) {
                // The exact read the pack does: texelFetch(shadowtex0, ivec2(shadowPos.xy * shadowMapResolutionM), 0).x,
                // then shadowSample = clamp((depth - shadowPos.z) * 65536.0, 0.0, 1.0). 0 = "in shadow" (kills the shaft
                // sample), 1 = "lit". An all-zero depth means the raw-depth read is broken, NOT that the world is shadowed.
                int res = this.targets.shadowSize();
                float depth = this.targets.readShadowDepthTexel((int) (sx * res), (int) (sy * res));
                float shadowSample = Math.max(0.0F, Math.min(1.0F, (depth - sz) * 65536.0F));
                sb.append(" | depth=").append(String.format("%.5f", depth))
                        .append(" vs z=").append(String.format("%.5f", sz))
                        .append(" -> shadowSample=").append(String.format("%.0f", shadowSample))
                        .append(shadowSample > 0.0F ? " (LIT)" : " (SHADOWED)");
            }
        }
        sb.append('\n').append(diagSals());
        log(sb.toString());
    }

    /**
     * DIAGNOSTIC: replicates the pack's scene-aware-light-shafts probe (volumetricLight.glsl Step 4) on the CPU and
     * reports it next to the live {@code vlFactor} it drives.
     *
     * <p>vlFactor is the term that decides whether the shafts are visible at all — at noon a 0 cuts vlMult to 0.125 and
     * triples the distance-falloff exponent. It lives in colortex5's bottom-right corner texel and is stepped by ±OSIEBCA
     * (1/255) a few times a second, so it is a SLOW ramp: reading the actual number beats inferring it from a screenshot.
     * The loop below is the pack's own, same constants, reading the same two textures.</p>
     */
    private String diagSals() {
        int res = this.targets.shadowSize();
        float salsSum = 0.0F;
        int salsCount = 0;
        float minA = 2.0F;
        float maxA = -1.0F;
        int litSkipped = 0;
        for (float i = 0.25F; i < 5.0F; i++) {
            for (float h = 0.45F; h < 5.0F; h++) {
                float cx = 0.3F + 0.4F * 0.2F * i; // pack: coord = 0.3 + 0.4 * (1.0/vec2(5,5)) * vec2(i,h)
                float cy = 0.3F + 0.4F * 0.2F * h;
                float salsSample = this.targets.readShadowDepthTexel((int) (cx * res), (int) (cy * res));
                if (salsSample >= 0.55F) {
                    litSkipped++; // pack skips these: nothing near enough to the light is casting here
                    continue;
                }
                float a = this.targets.readShadowColor1Alpha((int) (cx * res), (int) (cy * res));
                minA = Math.min(minA, a);
                maxA = Math.max(maxA, a);
                if (a > 0.0F) {
                    salsSum += Math.max(0.0F, a - 0.25F) / 0.05F; // consistencyMEJHRI7DG
                    salsCount++;
                }
            }
        }
        float salsCheck = salsCount > 0 ? salsSum / salsCount : Float.NaN;
        float vlFactor = this.targets.readVlFactor();
        return "    [SALS] vlFactor=" + String.format("%.4f", vlFactor)
                + " | salsCheck=" + (salsCount > 0 ? String.format("%.2f", salsCheck) : "NaN(no samples)")
                + " vs threshold=6.0 -> " + (salsCount > 0 && salsCheck > 6.0F ? "RISE" : "DECAY")
                + " | counted=" + salsCount + "/25 litSkipped=" + litSkipped
                + " shadowcolor1.a range=[" + (maxA < 0 ? "none" : String.format("%.3f..%.3f", minA, maxA)) + "]";
    }

    /**
     * Builds the shadow-map matrices for this frame: an orthographic box of half-extent {@code shadowDistanceThisFrame} centred
     * on the camera, viewed from the sun's world direction. Geometry is fed to the chunk shader in <b>camera-relative</b>
     * world space (the region offsets are camera-relative), so the shadow view/projection are built in that same space —
     * the camera sits at the origin.
     */
    private void computeShadowMatrices() {
        // Shadow light direction = Complementary's OWN world-space sun vector (GetSunVector before gbufferModelView), so the
        // shadow map is rendered + looked up from the exact direction the pack lights terrain with. This reproduces its
        // sunAngle→timeAngle remap (common.glsl, SHADOW_QUALITY != -1) + the baked sunPathRotation. Our old (0,cos,sin) was
        // in the wrong plane AND untilted → shadows fell the wrong way. Always the sun (night shadows are gated by the pack).
        float sunAngle = this.celestialAngle < 0.75F ? this.celestialAngle + 0.25F : this.celestialAngle - 0.75F;
        float tAmin = frac(sunAngle - 0.033333333F);
        float tAlin = tAmin < 0.433333333F ? tAmin * 1.15384615385F : tAmin * 0.882352941176F + 0.117647058824F;
        float hA = tAlin > 0.5F ? 1.0F : 0.0F;
        float tAfrc = frac(tAlin * 2.0F);
        float tAfrs = tAfrc * tAfrc * (3.0F - 2.0F * tAfrc);
        float tAmix = hA < 0.5F ? 0.3F : -0.1F;
        float timeAngle = (tAfrc * (1.0F - tAmix) + tAfrs * tAmix + hA) * 0.5F;
        float ang = frac(timeAngle - 0.25F);
        ang = (float) ((ang + (Math.cos(ang * Math.PI) * -0.5 + 0.5 - ang) / 3.0) * 6.28318530717959);
        Vector3f light;
        if ("world1/".equals(this.worldFolder)) {
            // END: the pack's GetSunVector END branch is NOT time-based — a FIXED sun at (0, cos α, −sin α)·2000 with
            // α = sunPathRotation, which common.glsl REDEFINES in the END from the END_SUN_ANGLE option (default 0 =
            // sun straight overhead → shadows fall straight down; user-verified against real 1.20.1 Iris). Using the
            // overworld timeAngle formula here pointed the map ~50° off — entity shadows landed blocks away from
            // their feet and read as "missing".
            float endRad = intOption(shaderOptions(), "END_SUN_ANGLE", 0) * 0.01745329251994F;
            light = new Vector3f(0.0F, (float) Math.cos(endRad), -(float) Math.sin(endRad));
        } else {
            float sprRad = sunPathRotationDegrees() * 0.01745329251994F;
            float srdX = (float) Math.cos(sprRad);
            float srdY = -(float) Math.sin(sprRad);
            light = new Vector3f(-(float) Math.sin(ang), (float) Math.cos(ang) * srdX, (float) Math.cos(ang) * srdY);
        }
        if (light.lengthSquared() < 1e-6F) {
            light.set(0.0F, 1.0F, 0.0F);
        }
        light.normalize();
        this.diagShadowLight.set(light);

        float d = shadowEyeDistance();                     // eye distance so the near plane clears the box
        Vector3f up = Math.abs(light.y) > 0.99F ? new Vector3f(0.0F, 0.0F, 1.0F) : new Vector3f(0.0F, 1.0F, 0.0F);
        this.shadowModelView.identity().lookAt(
                light.x * d, light.y * d, light.z * d,
                0.0F, 0.0F, 0.0F,
                up.x, up.y, up.z);
        float s = this.shadowDistanceThisFrame;
        this.shadowProjection.identity().ortho(-s, s, -s, s, 0.05F, 2.0F * d, false);
        this.shadowProjection.mul(this.shadowModelView, this.shadowModelViewProjection);
        // Inverses for the pack's shadow.vsh position round-trip (shadowModelViewInverse·shadowProjectionInverse·ftransform).
        this.shadowModelView.invert(this.shadowModelViewInverse);
        this.shadowProjection.invert(this.shadowProjectionInverse);
    }

    /**
     * Freezes the OPAQUE-only shadow depth into {@code shadowtex1}. Called from {@code drawShadowCasters} between the
     * opaque and translucent casters, UNCONDITIONALLY — a lazy "do it when the translucent pass starts" hook silently
     * never fires in views with no water (Sodium skips empty render passes), which would leave shadowtex1 stale.
     */
    public void snapshotOpaqueShadowDepth() {
        if (!UNDERWATER_SHAFTS || !this.activeThisFrame || !this.shadowPass || this.targets == null) {
            return;
        }
        this.targets.snapshotOpaqueShadowDepth();
    }

    /**
     * Renders opaque terrain (solid + cutout) from the light's POV into the shadow depth map. Called once per frame at the
     * start of the solid terrain layer. Uses a separate depth-only FBO, so the world G-buffer's attachments are untouched;
     * it just rebinds the world FBO + viewport afterward. A polygon-offset bias reduces self-shadowing acne.
     */
    public void renderShadowPass(SodiumWorldRenderer worldRenderer, double x, double y, double z) {
        if (!this.activeThisFrame || !SHADOWS_ENABLED || this.targets == null) {
            return;
        }
        try {
            computeShadowMatrices();
            // Depth state MUST be set BEFORE the clear: glClear(DEPTH) is masked out when depthMask is disabled, and the
            // sky/cloud passes before terrain can leave it disabled. Without this the shadow depth buffer stays at 0, then
            // GL_LEQUAL rejects every terrain fragment (0.5 <= 0 is false) → nothing renders → an all-black shadow map.
            GlStateManager.enableDepth();
            GlStateManager.depthMask(true);
            GlStateManager.depthFunc(GL11.GL_LEQUAL);
            GlStateManager.disableCull(); // render both sides so thin geometry casts shadows
            this.targets.beginShadowPass();
            diagShadowCenterRow("post-clear");
            GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
            GL11.glPolygonOffset(3.0F, 6.0F);
            this.shadowPass = true;
            me.jellysquid.mods.sodium.client.render.chunk.DefaultChunkRenderer.debugDrawBatches = 0;
            ChunkRenderMatrices matrices = new ChunkRenderMatrices(this.shadowProjection, this.shadowModelView);
            this.profiler.begin("shadow");
            worldRenderer.drawShadowCasters(matrices, x, y, z);
            this.profiler.end();
            diagShadowCenterRow("end-of-drawShadowCasters");
            this.lastShadowSections = worldRenderer.getShadowSectionCount();
            this.lastShadowCulled = worldRenderer.getShadowCulledUnderground();
            // DIAGNOSTIC: how much geometry actually reached the shadow map, against where the camera is looking.
            // drawShadowCasters reuses the CAMERA-visible section set, so a caster behind/outside the view frustum is
            // simply absent from the map and stops casting — which is the suspected cause of "shadows all vanish when I
            // turn". If this count swings with yaw/pitch, that is confirmed. Free: the counter is already maintained.
            if (DIAG_SHADOW_CASTERS && this.frameCounter % 30 == 0) {
                net.minecraft.entity.Entity view = Minecraft.getMinecraft().getRenderViewEntity();
                log("[shadow casters] batches=" + me.jellysquid.mods.sodium.client.render.chunk.DefaultChunkRenderer.debugDrawBatches
                        + " sections=" + this.lastShadowSections
                        + (view != null ? " yaw=" + String.format("%.0f", net.minecraft.util.math.MathHelper.wrapDegrees(view.rotationYaw))
                                + " pitch=" + String.format("%.0f", view.rotationPitch) : ""));
            }
            // End-to-end probes for the END position-dependent shadow report: project ground points below/around the
            // camera exactly like the terrain lookup and compare against the STORED map depth. ~1/second.
            if (DIAG_SHADOW_CASTERS && this.frameCounter % 60 == 0 && this.targets != null) {
                // Write-region suspects: a stale scissor rect or viewport would confine depth writes to a window-sized
                // corner of the shadow map — the receiving texel of the terrain moves with camera altitude, which would
                // make shadows exist only at some heights (the reported END symptom).
                java.nio.IntBuffer ib = org.lwjgl.BufferUtils.createIntBuffer(16);
                GL11.glGetInteger(GL11.GL_SCISSOR_BOX, ib);
                int sx = ib.get(0), sy = ib.get(1), sw = ib.get(2), sh = ib.get(3);
                ib.clear();
                GL11.glGetInteger(GL11.GL_VIEWPORT, ib);
                int vx = ib.get(0), vy = ib.get(1), vw = ib.get(2), vh = ib.get(3);
                log(String.format("[shadow probe] celestial=%.4f light=(%.3f, %.3f, %.3f) | scissorTest=%b box=(%d,%d %dx%d) viewport=(%d,%d %dx%d) shadowSize=%d culledUnderground=%d",
                        this.celestialAngle, this.diagShadowLight.x, this.diagShadowLight.y, this.diagShadowLight.z,
                        GL11.glGetBoolean(GL11.GL_SCISSOR_TEST), sx, sy, sw, sh, vx, vy, vw, vh,
                        this.targets.shadowSize(), this.lastShadowCulled));
            }
            // Full-map scan (~64MB readback stall — diagnostic runs only): shows the populated-region bounding box.
            // DIAGNOSTIC (periodic): confirm the shadow map is actually populated (nonCleared depth count > 0) and the
            // projection covers the scene — while the shadow FBO + its depth attachment are still bound for readback.
            if (isDiagFrame()) {
                logShadowDepthStats();
            }
            // Detach the shadow depth texture from its FBO so composites can sample it.
            this.targets.endShadowPass();
            // Per-texel probes AFTER the detach: glGetTextureSubImage on a texture still attached to the active draw
            // FBO returned stale CLEAR values (false "EMPTY" verdicts) while glReadPixels saw a 94%-populated map —
            // read the texture only once it is no longer an attachment (diagSals reads it this way and is reliable).
            if (DIAG_SHADOW_CASTERS && this.frameCounter % 60 == 0 && this.targets != null) {
                // The delta-map dump showed the PLAYER writing depth but the ENDERMEN not, despite identical draw
                // calls. Probe the nearest enderman's HEAD directly (post-detach texture reads are the reliable path):
                // "SHADOWED/surface" = its depth IS stored; "EMPTY" = its draw really writes nothing.
                net.minecraft.entity.Entity view2 = Minecraft.getMinecraft().getRenderViewEntity();
                if (view2 != null && Minecraft.getMinecraft().world != null) {
                    net.minecraft.entity.Entity nearest = null;
                    double bestSq = Double.MAX_VALUE;
                    for (net.minecraft.entity.Entity e : Minecraft.getMinecraft().world.loadedEntityList) {
                        if (e instanceof net.minecraft.entity.monster.EntityEnderman) {
                            double sq = e.getDistanceSq(view2);
                            if (sq < bestSq) {
                                bestSq = sq;
                                nearest = e;
                            }
                        }
                    }
                    if (nearest != null) {
                        double cx = view2.lastTickPosX + (view2.posX - view2.lastTickPosX) * Minecraft.getMinecraft().getRenderPartialTicks();
                        double cy = view2.lastTickPosY + (view2.posY - view2.lastTickPosY) * Minecraft.getMinecraft().getRenderPartialTicks()
                                + view2.getEyeHeight();
                        double cz = view2.lastTickPosZ + (view2.posZ - view2.lastTickPosZ) * Minecraft.getMinecraft().getRenderPartialTicks();
                        log(String.format("[enderman probe] nearest at dist=%.1f rel=(%.1f, %.1f, %.1f)", Math.sqrt(bestSq),
                                nearest.posX - cx, nearest.posY - cy, nearest.posZ - cz));
                        probeShadowLookup("ender-head", (float) (nearest.posX - cx), (float) (nearest.posY + 2.7 - cy), (float) (nearest.posZ - cz));
                        probeShadowLookup("ender-feet", (float) (nearest.posX - cx), (float) (nearest.posY - cy), (float) (nearest.posZ - cz));
                        // FOOTPRINT: how many texels around its feet actually hold the enderman (nearer than the
                        // ground)? A 0.6-block body at this distance should cover hundreds; single digits = the
                        // entity draw is degenerate; hundreds + still invisible = lookup-side suppression.
                        measureCasterFootprint((float) (nearest.posX - cx), (float) (nearest.posY - cy), (float) (nearest.posZ - cz));
                    }
                }
                probeShadowLookup("below16", 0, -16, 0);
                probeShadowLookup("below32", 0, -32, 0);
            }
            // PICTURE over numbers: dump the whole map as a PNG every ~5s (the numeric readbacks each lied in their
            // own way — see dumpShadowMapPng). Compare dumps with the entity loop ON vs OFF to SEE what it changes.
            if (DIAG_SHADOW_CASTERS && this.frameCounter % 300 == 0 && this.targets != null) {
                dumpShadowMapPng();
            }
        } catch (Throwable t) {
            SodiumClientMod.logger().error("[Iris shadow] shadow pass failed this frame", t);
        } finally {
            this.shadowPass = false;
            GL11.glPolygonOffset(0.0F, 0.0F);
            GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
            GlStateManager.enableCull();
            this.targets.bindWorldFramebufferNoClear();
        }
        // Colored-lighting flood-fill: propagate one voxel step NOW — voxel_img was just written by drawShadowCasters, and
        // the solid terrain gbuffers (which sample floodfill via GetLightVolume) render immediately after this returns. This
        // must precede that read so the terrain samples THIS frame's floodfill, anchored to THIS frame's floor(cameraPosition)
        // — otherwise the two-frame-stale buffer's anchor disagrees by a voxel at each block crossing (per-block flicker). The
        // compute is a self-contained dispatch (its own barriers, no FBO), so it's safe after the world FBO rebind above.
        runShadowComp();
        // Time the world geometry next: the shadow query has closed, and GL_TIME_ELAPSED cannot nest. The terrain hooks
        // split this into terrain_opaque / terrain_water / gb_other as the passes go by.
        this.profiler.begin("gb_other");
    }

    /** DIAGNOSTIC: reads back the whole shadow depth buffer (min/non-cleared count) + projects test points to NDC. */
    private void logShadowDepthStats() {
        try {
            int n = this.targets.shadowSize();
            java.nio.FloatBuffer buf = org.lwjgl.BufferUtils.createFloatBuffer(n * n);
            GL11.glReadPixels(0, 0, n, n, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, buf);
            float mn = Float.POSITIVE_INFINITY, mx = Float.NEGATIVE_INFINITY;
            long nonCleared = 0;
            // The UV bounding box of the POPULATED region: shows WHERE the drawn geometry actually landed in the map
            // (a corner-confined box = scissor/viewport clamp; an offset box = matrix disagreement; full-ish = healthy).
            int minPx = Integer.MAX_VALUE, maxPx = Integer.MIN_VALUE, minPy = Integer.MAX_VALUE, maxPy = Integer.MIN_VALUE;
            // Spatial 8x8 population grid + depth histogram: the pack's z*0.2 squash confines legit terrain depth to
            // ~[0.4, 0.6] — mass outside that band was written by something that BYPASSED the squash.
            final int cells = 8;
            long[] cellPop = new long[cells * cells];
            long[] hist = new long[6]; // <0.2, 0.2-0.4, 0.4-0.6 (legit band), 0.6-0.8, 0.8-0.999, >=0.999(clear)
            for (int i = 0; i < n * n; i++) {
                float v = buf.get(i);
                if (v < mn) mn = v;
                if (v > mx) mx = v;
                if (v < 0.999F) {
                    nonCleared++;
                    int px = i % n, py = i / n;
                    if (px < minPx) minPx = px;
                    if (px > maxPx) maxPx = px;
                    if (py < minPy) minPy = py;
                    if (py > maxPy) maxPy = py;
                    cellPop[(py * cells / n) * cells + (px * cells / n)]++;
                    if (v < 0.2F) hist[0]++;
                    else if (v < 0.4F) hist[1]++;
                    else if (v < 0.6F) hist[2]++;
                    else if (v < 0.8F) hist[3]++;
                    else hist[4]++;
                } else {
                    hist[5]++;
                }
            }
            String popBox = nonCleared == 0 ? "none"
                    : String.format("uv (%.3f..%.3f, %.3f..%.3f)",
                            minPx / (float) n, maxPx / (float) n, minPy / (float) n, maxPy / (float) n);
            log(String.format("shadow depth FULL %dx%d: min=%.4f max=%.4f nonCleared(<0.999)=%d/%d populatedBox=%s drawBatches=%d",
                    n, n, mn, mx, nonCleared, (long) n * n, popBox,
                    me.jellysquid.mods.sodium.client.render.chunk.DefaultChunkRenderer.debugDrawBatches));
            log(String.format("  depth histogram: <0.2=%d 0.2-0.4=%d 0.4-0.6(squash band)=%d 0.6-0.8=%d 0.8-0.999=%d clear=%d",
                    hist[0], hist[1], hist[2], hist[3], hist[4], hist[5]));
            long cellMax = (long) (n / cells) * (n / cells);
            for (int cy = cells - 1; cy >= 0; cy--) { // top row first (v=1 at top)
                StringBuilder row = new StringBuilder("  popGrid v=" + String.format("%.2f", (cy + 0.5F) / cells) + " |");
                for (int cx = 0; cx < cells; cx++) {
                    row.append(String.format(" %3d", (int) (100 * cellPop[cy * cells + cx] / cellMax)));
                }
                log(row.append(" |%").toString());
            }

            // Project some camera-relative test points (roughly at ground level, in front/behind/side) into shadow NDC.
            // If these land inside [-1,1]^3, the matrices are fine and the geometry SHOULD be in the map (→ a draw-submit
            // problem); if they land outside, the shadow projection is wrong.
            projectTest("origin", 0, 0, 0);
            projectTest("front16", 0, -2, 16);
            projectTest("back16", 0, -2, -16);
            projectTest("side16", 16, -2, 0);
            projectTest("down8", 0, -8, 0);
            log(String.format("shadow light dir/celestialAngle=%.3f", this.celestialAngle));
        } catch (Throwable t) {
            SodiumClientMod.logger().error("[Iris shadow] depth readback failed", t);
        }
    }

    /** DIAGNOSTIC: shadow light direction as last built by computeShadowMatrices (for the [shadow probe] log). */
    private final Vector3f diagShadowLight = new Vector3f();

    /** WORLD-space unit vector toward the shadow light — set by computeShadowMatrices at the top of every shadow
     * frame (including the degenerate-light (0,1,0) guard + normalize), so it is fresh anywhere inside the shadow
     * pass and at updateShadowRenderList time. Consumers: the shadow-list cache (sun-drift invalidation) and the
     * shadow light-facing slice cull. Read-only view — do not mutate. */
    public org.joml.Vector3fc shadowLightDirection() {
        return this.diagShadowLight;
    }

    /** Read-only view of this frame's shadow view matrix, built by computeShadowMatrices at the top of the shadow
     * pass. The shadow-list cache keys on its ROTATION part: light-space section coordinates are this matrix applied
     * to the camera-relative position, so the basis is what actually moves them — and the basis can rotate faster
     * than the light DIRECTION does (the up-vector cross product amplifies azimuthal motion near a vertical sun).
     * Do not mutate. */
    public org.joml.Matrix4fc shadowViewMatrix() {
        return this.shadowModelView;
    }

    /**
     * DIAGNOSTIC (mob shadows, "only the loop's first entity lands"): snapshot of every pipeline state a
     * fixed-function entity renderer could leak that the shadow caster draw depends on. STATE queries are the
     * trustworthy instrument on the shadow FBO (only PIXEL reads lie — see the instrument-lies list), so diffing
     * this map per entity pinpoints the leaked state without a single pixel read. Values are strings so the diff
     * is a plain map compare and the log line is self-describing.
     */
    public static java.util.LinkedHashMap<String, String> captureGlState() {
        java.util.LinkedHashMap<String, String> m = new java.util.LinkedHashMap<>();
        java.nio.IntBuffer i16 = org.lwjgl.BufferUtils.createIntBuffer(16);
        java.nio.FloatBuffer f16 = org.lwjgl.BufferUtils.createFloatBuffer(16);
        java.nio.ByteBuffer b16 = org.lwjgl.BufferUtils.createByteBuffer(16);
        m.put("program", Integer.toString(GL11.glGetInteger(0x8B8D)));   // GL_CURRENT_PROGRAM
        m.put("drawFbo", Integer.toString(GL11.glGetInteger(0x8CA6)));   // GL_DRAW_FRAMEBUFFER_BINDING
        m.put("readFbo", Integer.toString(GL11.glGetInteger(0x8CAA)));   // GL_READ_FRAMEBUFFER_BINDING
        GL11.glGetInteger(GL11.GL_VIEWPORT, i16);
        m.put("viewport", i16.get(0) + "," + i16.get(1) + "," + i16.get(2) + "," + i16.get(3));
        m.put("scissor", (GL11.glGetBoolean(GL11.GL_SCISSOR_TEST) ? "ON " : "off ") + boxToString(i16, GL11.GL_SCISSOR_BOX));
        m.put("depthMask", Boolean.toString(GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK)));
        m.put("depthTest", Boolean.toString(GL11.glGetBoolean(GL11.GL_DEPTH_TEST)));
        m.put("depthFunc", "0x" + Integer.toHexString(GL11.glGetInteger(GL11.GL_DEPTH_FUNC)));
        f16.clear();
        GL11.glGetFloat(GL11.GL_DEPTH_RANGE, f16);
        m.put("depthRange", f16.get(0) + ".." + f16.get(1));
        b16.clear();
        GL11.glGetBoolean(GL11.GL_COLOR_WRITEMASK, b16);
        m.put("colorMask", "" + b16.get(0) + b16.get(1) + b16.get(2) + b16.get(3));
        m.put("alphaTest", (GL11.glGetBoolean(GL11.GL_ALPHA_TEST) ? "ON" : "off")
                + "/0x" + Integer.toHexString(GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC))
                + "/" + GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF));
        m.put("blend", Boolean.toString(GL11.glGetBoolean(GL11.GL_BLEND)));
        m.put("cull", (GL11.glGetBoolean(GL11.GL_CULL_FACE) ? "ON" : "off")
                + "/0x" + Integer.toHexString(GL11.glGetInteger(GL11.GL_CULL_FACE_MODE))
                + "/0x" + Integer.toHexString(GL11.glGetInteger(GL11.GL_FRONT_FACE)));
        m.put("stencilTest", Boolean.toString(GL11.glGetBoolean(GL11.GL_STENCIL_TEST)));
        m.put("polyOffsetFill", Boolean.toString(GL11.glGetBoolean(0x8037))); // GL_POLYGON_OFFSET_FILL
        m.put("rasterDiscard", Boolean.toString(GL11.glGetBoolean(0x8C89))); // GL_RASTERIZER_DISCARD
        m.put("matrixMode", "0x" + Integer.toHexString(GL11.glGetInteger(GL11.GL_MATRIX_MODE)));
        m.put("mvStack", Integer.toString(GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH)));
        m.put("projStack", Integer.toString(GL11.glGetInteger(GL11.GL_PROJECTION_STACK_DEPTH)));
        m.put("attribStack", Integer.toString(GL11.glGetInteger(GL11.GL_ATTRIB_STACK_DEPTH)));
        m.put("activeTex", "0x" + Integer.toHexString(GL11.glGetInteger(0x84E0))); // GL_ACTIVE_TEXTURE
        m.put("arrayBuf", Integer.toString(GL11.glGetInteger(0x8894)));  // GL_ARRAY_BUFFER_BINDING
        m.put("elemBuf", Integer.toString(GL11.glGetInteger(0x8895)));   // GL_ELEMENT_ARRAY_BUFFER_BINDING
        m.put("vao", Integer.toString(GL11.glGetInteger(0x85B5)));       // GL_VERTEX_ARRAY_BINDING
        m.put("drawBuf0", "0x" + Integer.toHexString(GL11.glGetInteger(0x8825))); // GL_DRAW_BUFFER0
        m.put("drawBuf1", "0x" + Integer.toHexString(GL11.glGetInteger(0x8826))); // GL_DRAW_BUFFER1
        f16.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, f16);
        m.put("mvSum", String.format("%.3f", matSum(f16)));
        f16.clear();
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, f16);
        m.put("projSum", String.format("%.3f", matSum(f16)));
        return m;
    }

    private static String boxToString(java.nio.IntBuffer i16, int pname) {
        i16.clear();
        GL11.glGetInteger(pname, i16);
        return i16.get(0) + "," + i16.get(1) + "," + i16.get(2) + "," + i16.get(3);
    }

    private static float matSum(java.nio.FloatBuffer f16) {
        float s = 0.0F;
        for (int i = 0; i < 16; i++) {
            s += f16.get(i);
        }
        return s;
    }

    /** @return "key:base->now ..." for every entry that changed vs the baseline; empty when nothing did. */
    public static String diffGlState(java.util.Map<String, String> base, java.util.Map<String, String> now) {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : now.entrySet()) {
            String b = base.get(e.getKey());
            if (!e.getValue().equals(b)) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(e.getKey()).append(':').append(b).append("->").append(e.getValue());
            }
        }
        return sb.toString();
    }

    /**
     * DIAGNOSTIC (END shadow corner-clip): counts populated texels on the shadow map's CENTER ROW (v=0.5) via a 16KB
     * glReadPixels of the currently bound framebuffer, and logs with a phase label. The island must span most of this
     * row when correctly rendered; the broken map has it empty. Sampling after each phase of the shadow pass shows
     * whether the terrain draw never reaches the row or something later wipes it. Call only on diag frames while the
     * shadow FBO is bound.
     */
    public void diagShadowCenterRow(String label) {
        if (!DIAG_ENTITY_SHADOW || this.targets == null || this.frameCounter % 60 != 0) {
            return;
        }
        try {
            int n = this.targets.shadowSize();
            java.nio.FloatBuffer row = org.lwjgl.BufferUtils.createFloatBuffer(n);
            GL11.glReadPixels(0, n / 2, n, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, row);
            int populated = 0, minX = -1, maxX = -1;
            for (int x = 0; x < n; x++) {
                if (row.get(x) < 0.999F) {
                    populated++;
                    if (minX < 0) minX = x;
                    maxX = x;
                }
            }
            log(String.format("[shadow row v=0.5] %-22s populated=%d/%d span=u[%.3f..%.3f]",
                    label, populated, n, minX < 0 ? -1 : minX / (float) n, maxX < 0 ? -1 : maxX / (float) n));
        } catch (Throwable t) {
            SodiumClientMod.logger().error("[Iris shadow] center-row read failed", t);
        }
    }

    /** DIAGNOSTIC: 16x16-texel depth read from the CURRENTLY BOUND framebuffer at fractional coords, reported as
     * "min..max". Single-pixel glReadPixels returned a constant 0.0 while the full-map read of the same buffer showed
     * real content — block reads follow the (trustworthy) full-scan path. */
    private String readBoundDepth(float u, float v) {
        java.nio.IntBuffer vp = org.lwjgl.BufferUtils.createIntBuffer(16);
        GL11.glGetInteger(GL11.GL_VIEWPORT, vp);
        int n = Math.max(17, vp.get(2));
        java.nio.FloatBuffer fb = org.lwjgl.BufferUtils.createFloatBuffer(256);
        GL11.glReadPixels(Math.min(n - 17, (int) ((n - 1) * u)), Math.min(n - 17, (int) ((n - 1) * v)), 16, 16,
                GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, fb);
        float mn = 2.0F, mx = -1.0F;
        for (int i = 0; i < 256; i++) {
            float d = fb.get(i);
            mn = Math.min(mn, d);
            mx = Math.max(mx, d);
        }
        return String.format("%.4f..%.4f", mn, mx);
    }

    /**
     * DIAGNOSTIC (END position-dependent shadows): end-to-end shadow probe for one CAMERA-RELATIVE point — projects it
     * with the SAME math the terrain lookup uses (shadow MVP → radial distortion → z*0.2 → *0.5+0.5), reads the actual
     * stored depth at that texel, and prints the verdict. "SHADOWED" = a closer caster covers the point (a shadow WOULD
     * be drawn there); "surface/lit" = the point itself is the nearest depth; "EMPTY" = nothing rendered at that texel
     * (caster-side hole); "OUTSIDE MAP" = the projection leaves the distorted map (matrix/box problem).
     */
    private void probeShadowLookup(String name, float x, float y, float z) {
        Vector4f clip = this.shadowModelViewProjection.transform(new Vector4f(x, y, z, 1.0F));
        float w = clip.w == 0 ? 1e-6F : clip.w;
        float ndcX = clip.x / w, ndcY = clip.y / w, ndcZ = clip.z / w;
        float distb = (float) Math.sqrt(ndcX * ndcX + ndcY * ndcY);
        float factor = distb * this.shadowMapBiasThisFrame + (1.0F - this.shadowMapBiasThisFrame);
        float u = (ndcX / factor) * 0.5F + 0.5F;
        float v = (ndcY / factor) * 0.5F + 0.5F;
        float depth = (ndcZ * 0.2F) * 0.5F + 0.5F;
        int n = this.targets.shadowSize();
        float stored = (u < 0.0F || u > 1.0F || v < 0.0F || v > 1.0F) ? -2.0F
                : this.targets.readShadowDepthTexel((int) (u * (n - 1)), (int) (v * (n - 1)));
        String verdict;
        if (stored == -2.0F) {
            verdict = "OUTSIDE MAP";
        } else if (stored > 0.999F) {
            verdict = "EMPTY (no caster rendered at this texel)";
        } else if (stored < depth - 0.0005F) {
            verdict = "SHADOWED (caster above)";
        } else if (stored > depth + 0.0005F) {
            verdict = "stored DEEPER than point (?)";
        } else {
            verdict = "surface/lit";
        }
        log(String.format("  [probe %-8s] rel=(%4.0f,%4.0f,%4.0f) uv=(%.4f, %.4f) pointDepth=%.5f stored=%.5f -> %s",
                name, x, y, z, u, v, depth, stored, verdict));
    }

    /**
     * DIAGNOSTIC: dumps the shadow depth map to a grayscale PNG (run/client/shadow_dump_N.png) so it can be LOOKED at.
     * Every numeric readback path lied in its own way during the END shadow hunt (single-pixel glReadPixels returned
     * constant 0.0; full-map glReadPixels silently truncated at ~2M texels leaving zero-filled buffer tails that
     * mimicked a "depth-0 blanket"); strip reads of 256 rows stay under the transfer cap. Mapping: white=cleared(1.0),
     * black..gray = depth (the terrain squash band 0.4-0.6 lands mid-gray).
     */
    private int shadowDumpCounter;

    private void dumpShadowMapPng() {
        try {
            int n = this.targets.shadowSize();
            int scale = Math.max(1, n / 1024); // downsample to <=1024² for a manageable PNG
            int out = n / scale;
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(out, out, java.awt.image.BufferedImage.TYPE_INT_RGB);
            // Downsample with the MIN depth of each cell — a thin entity (few texels) must not be skipped by point
            // sampling; MIN keeps the nearest-to-light caster in the cell.
            float[] depth = new float[out * out];
            java.util.Arrays.fill(depth, 1.0F);
            java.nio.FloatBuffer strip = org.lwjgl.BufferUtils.createFloatBuffer(n * 256);
            for (int y0 = 0; y0 < n; y0 += 256) {
                strip.clear();
                org.lwjgl.opengl.GL45C.glGetTextureSubImage(this.targets.shadowDepthTexId(), 0,
                        0, y0, 0, n, Math.min(256, n - y0), 1,
                        GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, strip);
                int rows = Math.min(256, n - y0);
                for (int r = 0; r < rows; r++) {
                    int py = (y0 + r) / scale;
                    if (py >= out) break;
                    int rowBase = r * n;
                    int outBase = py * out;
                    for (int x = 0; x < n; x++) {
                        float d = strip.get(rowBase + x);
                        int ox = outBase + x / scale;
                        if (d < depth[ox]) depth[ox] = d;
                    }
                }
            }
            for (int py = 0; py < out; py++) {
                for (int px = 0; px < out; px++) {
                    int g = Math.max(0, Math.min(255, (int) (depth[py * out + px] * 255.0F)));
                    // PNG y flipped so the image matches uv orientation (v=0 at bottom of the map -> bottom of image)
                    img.setRGB(px, out - 1 - py, (g << 16) | (g << 8) | g);
                }
            }
            java.io.File f = new java.io.File(Minecraft.getMinecraft().gameDir,
                    "shadow_dump_" + (this.shadowDumpCounter % 8) + ".png");
            javax.imageio.ImageIO.write(img, "png", f);
            // DELTA image: each pixel = local 7x7 max depth (≈ the ground) minus this pixel's depth, amplified — a
            // 3-block-tall entity (Δdepth ≈ 0.001, invisible in the raw dump) becomes a bright dot. Counting dots vs
            // the [ENT] drawn count answers "are the endermen IN the map at all".
            java.awt.image.BufferedImage delta = new java.awt.image.BufferedImage(out, out, java.awt.image.BufferedImage.TYPE_INT_RGB);
            // Amplification sized to the geometry: 1 block along the light = 0.2/576 ≈ 0.000347 depth; a 3-block
            // enderman ≈ 0.00104 → x100000 ≈ gray 104. (The first attempt at x2000 mapped an enderman to gray 2 —
            // invisible; only 30+-block pillar tops showed.)
            int rad = 5;
            for (int py = 0; py < out; py++) {
                for (int px = 0; px < out; px++) {
                    float localMax = 0.0F;
                    for (int dy = -rad; dy <= rad; dy++) {
                        int yy = Math.max(0, Math.min(out - 1, py + dy));
                        for (int dx = -rad; dx <= rad; dx++) {
                            int xx = Math.max(0, Math.min(out - 1, px + dx));
                            float dd = depth[yy * out + xx];
                            if (dd < 0.999F && dd > localMax) localMax = dd; // ignore clear texels
                        }
                    }
                    float me = depth[py * out + px];
                    float dv = (me >= 0.999F || localMax <= 0.0F) ? 0.0F : (localMax - me) * 100000.0F;
                    int g = Math.max(0, Math.min(255, (int) dv));
                    delta.setRGB(px, out - 1 - py, (g << 16) | (g << 8) | g);
                }
            }
            java.io.File f2 = new java.io.File(Minecraft.getMinecraft().gameDir,
                    "shadow_delta_" + (this.shadowDumpCounter % 8) + ".png");
            javax.imageio.ImageIO.write(delta, "png", f2);
            this.shadowDumpCounter++;
            log("[shadow dump] wrote " + f.getName() + " + " + f2.getName());
        } catch (Throwable t) {
            SodiumClientMod.logger().error("[Iris shadow] dump failed", t);
        }
    }

    /**
     * DIAGNOSTIC: measures a caster's stored FOOTPRINT — reads a 64x64 texel block of the shadow depth texture centred
     * on the given camera-relative point (post-detach texture read = the reliable path) and counts texels nearer to
     * the light than the point's own depth minus half a block. Logs count + min depth.
     */
    private void measureCasterFootprint(float x, float y, float z) {
        Vector4f clip = this.shadowModelViewProjection.transform(new Vector4f(x, y, z, 1.0F));
        float w = clip.w == 0 ? 1e-6F : clip.w;
        float ndcX = clip.x / w, ndcY = clip.y / w, ndcZ = clip.z / w;
        float distb = (float) Math.sqrt(ndcX * ndcX + ndcY * ndcY);
        float factor = distb * this.shadowMapBiasThisFrame + (1.0F - this.shadowMapBiasThisFrame);
        float u = (ndcX / factor) * 0.5F + 0.5F;
        float v = (ndcY / factor) * 0.5F + 0.5F;
        float groundDepth = (ndcZ * 0.2F) * 0.5F + 0.5F;
        int n = this.targets.shadowSize();
        int cxT = Math.max(32, Math.min(n - 33, (int) (u * (n - 1))));
        int cyT = Math.max(32, Math.min(n - 33, (int) (v * (n - 1))));
        java.nio.FloatBuffer block = org.lwjgl.BufferUtils.createFloatBuffer(64 * 64);
        org.lwjgl.opengl.GL45C.glGetTextureSubImage(this.targets.shadowDepthTexId(), 0,
                cxT - 32, cyT - 32, 0, 64, 64, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, block);
        float halfBlock = 0.5F * 0.2F / (2.0F * shadowEyeDistance()); // 0.5 blocks along the light in window depth
        int above = 0;
        float mn = 1.0F;
        for (int i = 0; i < 64 * 64; i++) {
            float d = block.get(i);
            if (d < groundDepth - halfBlock) {
                above++;
            }
            mn = Math.min(mn, d);
        }
        log(String.format("  [footprint] 64x64 around uv(%.4f, %.4f): texels >0.5 blocks above ground = %d, minDepth=%.5f (ground=%.5f)",
                u, v, above, mn, groundDepth));
    }

    /** DIAGNOSTIC: level-0 width/height of a texture object (DSA state query — trustworthy). */
    private static int texW(int tex) {
        return tex == 0 ? -1 : org.lwjgl.opengl.GL45C.glGetTextureLevelParameteri(tex, 0, GL11.GL_TEXTURE_WIDTH);
    }

    private static int texH(int tex) {
        return tex == 0 ? -1 : org.lwjgl.opengl.GL45C.glGetTextureLevelParameteri(tex, 0, GL11.GL_TEXTURE_HEIGHT);
    }

    private void projectTest(String name, float x, float y, float z) {
        Vector3f p = new Vector3f(x, y, z);
        Vector4f clip = this.shadowModelViewProjection.transform(new Vector4f(p, 1.0F));
        float w = clip.w == 0 ? 1e-6F : clip.w;
        log(String.format("  proj[%s] (%.0f,%.0f,%.0f) -> ndc (%.3f, %.3f, %.3f)",
                name, x, y, z, clip.x / w, clip.y / w, clip.z / w));
    }

    /** Called by {@code ShaderChunkRenderer} once the transformed pack terrain compiles (it writes colortex1 normals). */
    public void setTerrainWritesNormal(boolean writes) {
        this.terrainWritesNormal = writes;
    }

    /**
     * Hooks around Sodium's terrain passes (from {@code ShaderChunkRenderer.begin/end}). While the transformed pack
     * terrain shader is active, attach the pack's full RENDERTARGETS ({@code colortex0,6,4,10,11,20}) so its
     * {@code gl_FragData[i]} G-buffer channels (colour/material/normal/…) land in the right buffers; non-terrain geometry
     * drawn between passes writes {@code colortex0} only (targets restored by {@link #onTerrainPassEnd}).
     */
    /**
     * Records which Sodium terrain pass is about to draw. MUST be called before the chunk program's {@code setupState()},
     * since that is what feeds the pack its {@code renderStage} (see applyTerrainUniforms) — including during the shadow
     * pass, which is exactly where the pack's voxelizers run.
     */
    public void setTerrainPass(boolean translucent) {
        this.terrainTranslucentPass = translucent;
    }

    public void onTerrainPassBegin(boolean translucent) {
        // During the shadow pass the shadow FBO is bound with depth-only draw buffers (GL_NONE). Attaching the world
        // colortex targets here (beginTerrainTargets) would re-point colortex0 at the shadow FBO + re-enable colour draw,
        // so the light-POV terrain would render INTO colortex0 — leaking an upside-down "different viewpoint" terrain into
        // the sky (where the camera terrain doesn't later overwrite it). renderShadowPass already set the depth state.
        if (this.shadowPass) {
            // Viewport truth at the exact moment a shadow terrain layer is about to draw. The END entity-shadow bug's
            // shadow-map dump showed the terrain rasterized into a WINDOW-sized corner of the 4096 map (island shape
            // intact, scaled by exactly windowW/4096 x windowH/4096) — i.e. SOMETHING re-set the window viewport after
            // beginShadowPass. Detect it here and FORCE the correct viewport: if the anomaly logs AND the shadows heal,
            // the cause and the causality are both pinned in one run.
            if (DIAG_ENTITY_SHADOW && this.targets != null) {
                java.nio.IntBuffer vb = org.lwjgl.BufferUtils.createIntBuffer(16);
                GL11.glGetInteger(GL11.GL_VIEWPORT, vb);
                int wantSize = this.targets.shadowSize();
                if (vb.get(2) != wantSize || vb.get(3) != wantSize) {
                    log("[shadow VP ANOMALY] viewport=(" + vb.get(0) + "," + vb.get(1) + " " + vb.get(2) + "x" + vb.get(3)
                            + ") at shadow " + (translucent ? "TRANSLUCENT" : "OPAQUE")
                            + " layer begin — expected " + wantSize + "x" + wantSize + "; forcing it");
                    GL11.glViewport(0, 0, wantSize, wantSize);
                }
                // The pack's shadow.vsh round-trips ftransform() (chunk matrices — correct by construction) through the
                // PACK's shadowProjectionInverse/shadowModelViewInverse and back through shadowProjection/shadowModelView
                // uniforms. Any inconsistency in that uniform quartet = a net scale/offset on the terrain — the squeezed
                // island. Read the values ACTUALLY on the bound shadow program and compare against the pipeline's own.
                if (!translucent && this.frameCounter % 60 == 0) {
                    int prog2 = GL11.glGetInteger(org.lwjgl.opengl.GL20C.GL_CURRENT_PROGRAM);
                    java.nio.FloatBuffer mb = org.lwjgl.BufferUtils.createFloatBuffer(16);
                    StringBuilder sb3 = new StringBuilder("[shadow uniforms] prog=" + prog2);
                    String[] names = {"shadowProjection", "shadowProjectionInverse", "shadowModelView", "shadowModelViewInverse"};
                    for (String nm : names) {
                        int loc = org.lwjgl.opengl.GL20C.glGetUniformLocation(prog2, nm);
                        if (loc < 0) {
                            sb3.append(" | ").append(nm).append("=ABSENT");
                        } else {
                            mb.clear();
                            org.lwjgl.opengl.GL20C.glGetUniformfv(prog2, loc, mb);
                            sb3.append(String.format(" | %s[0][0]=%.6f [1][1]=%.6f [3][3]=%.3f", nm, mb.get(0), mb.get(5), mb.get(15)));
                        }
                    }
                    sb3.append(String.format(" || expected P00=%.6f Pinv00=%.3f MV00=%.4f",
                            this.shadowProjection.m00(), this.shadowProjectionInverse.m00(), this.shadowModelView.m00()));
                    log(sb3.toString());
                    // Attachment REAL sizes (state queries — trustworthy, unlike pixel reads from this FBO). GL clips
                    // rendering to the INTERSECTION of all attached image sizes: one window-sized attachment on the
                    // 4096 shadow FBO would clip the terrain to exactly the observed window-rect corner.
                    int attD = org.lwjgl.opengl.GL30C.glGetFramebufferAttachmentParameteri(
                            org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER, org.lwjgl.opengl.GL30C.GL_DEPTH_ATTACHMENT,
                            org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
                    int att0 = org.lwjgl.opengl.GL30C.glGetFramebufferAttachmentParameteri(
                            org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER, org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0,
                            org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
                    int att1 = org.lwjgl.opengl.GL30C.glGetFramebufferAttachmentParameteri(
                            org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER, org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT1,
                            org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
                    log(String.format("[shadow attach sizes] fbo=%d depth=%d (%dx%d) color0=%d (%dx%d) color1=%d (%dx%d)",
                            GL11.glGetInteger(org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER_BINDING),
                            attD, texW(attD), texH(attD), att0, texW(att0), texH(att0), att1, texW(att1), texH(att1)));
                }
            }
            // Tell the shadow FRAGMENT which layer it is drawing, so the translucent one can write a water tint into
            // shadowcolor0/1 instead of black (black would turn every seabed pitch black). The chunk program is already
            // bound at this point (ShaderChunkRenderer binds before calling us), so ask GL for it rather than plumbing
            // the handle through Sodium.
            int prog = GL11.glGetInteger(org.lwjgl.opengl.GL20C.GL_CURRENT_PROGRAM);
            if (prog != 0) {
                int loc = org.lwjgl.opengl.GL20C.glGetUniformLocation(prog, "yumelium_shadowTranslucent");
                if (loc >= 0) {
                    // Only take the water branch when the feature is on; with it off the shadow fragment must behave
                    // exactly as it did before (opaque values for every caster, water included).
                    org.lwjgl.opengl.GL20C.glUniform1f(loc, (UNDERWATER_SHAFTS && translucent) ? 1.0F : 0.0F);
                }
            }
            if (translucent) {
                if (UNDERWATER_SHAFTS) {
                    // Let water write depth into shadowtex0. shadowtex1 already holds the opaque-only depth — snapshotted
                    // unconditionally after the opaque casters (see snapshotOpaqueShadowDepth), NOT lazily here: Sodium
                    // skips a render pass entirely when it has no geometry, so in any view without water this hook never
                    // fires and shadowtex1 would keep a stale depth (the same trap that bit copyDepthForWater).
                    GlStateManager.depthMask(true);
                } else {
                    // Legacy behaviour: translucent geometry is drawn in the shadow pass ONLY to voxelize it for coloured
                    // lighting (the imageStore is vertex-side, so it runs regardless of the fragment/depth), with depth
                    // writes off so water casts no shadow at all.
                    GlStateManager.depthMask(false);
                }
            }
            return;
        }
        // Sub-phase timing (see GpuProfiler): the camera terrain draws as separate solid/cutout/translucent passes, so the
        // profiler SUMS repeated begin/end pairs of the same name within a frame.
        this.profiler.end(); // close whatever gbuffers segment was open (entities/sky) — GL_TIME_ELAPSED cannot nest
        this.profiler.begin(translucent ? "terrain_water" : "terrain_opaque");
        if (this.activeThisFrame && this.targets != null) {
            if (translucent && this.packWaterTargets != null) {
                diagWaterGlError("water-begin (before copies)");
                // Snapshot the opaque scene (colortex0) for the water SSR BEFORE attaching colortex0 as a water render
                // target, so the reflection reads a stable copy rather than the buffer it's writing.
                this.targets.copyColor0ForWater();
                diagWaterGlError("copyColor0ForWater");
                // Snapshot the opaque DEPTH too: gbuffers_water samples depthtex1 (SSR/refraction/fog) but our single
                // depthTex is the FBO's live depth attachment the water writes to → feedback. Copy it here (still opaque:
                // water not yet drawn) so the water reads a stable opaque-depth texture instead.
                this.targets.copyDepthForWater();
                this.waterDepthCopiedThisFrame = true;
                diagWaterGlError("copyDepthForWater");
                // Water pass: attach gbuffers_water's own RENDERTARGETS so its gl_FragData[i] (colour/normal/material) land
                // in the buffers the composite reflections/refraction read.
                // blend.gbuffers_water.colortex{4,6,8}=off — without these the translucent pass's SRC_ALPHA blend mixes
                // the water's material/normal/SSR writes into the opaque terrain behind it (see beginTargets).
                this.targets.beginTargets(this.packWaterTargets, blendOffFor("gbuffers_water"));
                diagWaterGlError("beginTargets(water) " + java.util.Arrays.toString(this.packWaterTargets));
                applyAlphaOverride("gbuffers_water"); // pack: GREATER 0.0001 — don't cut the water's faintest fragments
            } else if (NORMAL_GBUFFER_ENABLED && this.terrainWritesNormal) {
                int[] tt = this.packTerrainTargets != null && this.packTerrainTargets.length > 0
                        ? this.packTerrainTargets : RenderTargets.TERRAIN_TARGETS;
                this.targets.beginTargets(tt, blendOffFor("gbuffers_terrain"));
            }
        }
        if (this.activeThisFrame) {
            this.diagTerrainPassCount++;
            if (DIAG_TERRAIN_NODEPTH) {
                GlStateManager.disableDepth();
            } else {
                // DIAG/fix: force depth test + WRITE on for the terrain pass — the scene depth (depthtex0) reads as the
                // far-plane clear for all terrain (only the hand wrote depth), which makes the composite fog treat every
                // terrain pixel as maximally distant → heavy grey haze. A prior pass (sky/cloud) may leave depthMask off.
                GlStateManager.enableDepth();
                GlStateManager.depthMask(true);
                GlStateManager.depthFunc(GL11.GL_LEQUAL);
            }
        }
    }

    public void onTerrainPassEnd(boolean translucent) {
        if (this.shadowPass) {
            if (translucent) {
                GlStateManager.depthMask(true); // restore for the next frame's opaque shadow depth
            }
            return; // shadow pass never attached world targets (see onTerrainPassBegin) — nothing to restore
        }
        this.profiler.end();               // close terrain_opaque / terrain_water
        this.profiler.begin("gb_other");   // sky + entities + block entities + hand, until the next terrain pass
        if (this.activeThisFrame && this.targets != null) {
            boolean waterAttached = translucent && this.packWaterTargets != null;
            if (waterAttached) {
                diagWaterGlError("water-end (after water draw, before restore)");
                restoreAlphaFunc(); // drop the gbuffers_water alphaTest override
            }
            if (waterAttached || (NORMAL_GBUFFER_ENABLED && this.terrainWritesNormal)) {
                this.targets.endTerrainTargets();
            }
            // Ladder start: what the water pass itself left at the crosshair (diag colour ≤(1.0,0.25,0)) + its material.
            if (DIAG_PORTAL_LADDER && waterAttached && this.frameCounter % 120 == 0) {
                diagLadderRead("water(end)", 0);
                diagLadderRead("water(end)", 6);
            }
        }
        if (this.activeThisFrame && DIAG_TERRAIN_NODEPTH) {
            GlStateManager.enableDepth();
        }
    }

    /** Binds the G-buffer so all world geometry renders into {@code colortex0}. */
    public void beginWorldRender(float partialTicks) {
        this.activeThisFrame = false;
        if (!this.enabled) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            Framebuffer main = mc.getFramebuffer();
            int w = main.framebufferWidth;
            int h = main.framebufferHeight;
            if (w <= 0 || h <= 0) {
                return;
            }
            // Per-dimension programs: when the player changes dimension, swap to that world's pack folder and drop the
            // compiled pipeline — initPipeline() below rebuilds everything (programs, disabled-program set, custom
            // images) from the new folder, and the lazy terrain/water/shadow sources (reset by destroy()) re-read it
            // before Sodium's reloaded chunk renderer compiles its programs.
            String dimFolder = dimensionFolder(mc);
            boolean noClouds = suppressPackCloudsForDimension(mc);
            if (!dimFolder.equals(this.worldFolder) || noClouds != this.packCloudsSuppressed) {
                log("dimension change: " + this.worldFolder + " -> " + dimFolder
                        + (noClouds != this.packCloudsSuppressed ? " (pack clouds " + (noClouds ? "OFF" : "on") + ")" : "")
                        + " — recompiling the pipeline");
                this.worldFolder = dimFolder;
                this.packCloudsSuppressed = noClouds;
                destroy();
            }
            initPipeline();
            if (this.targets == null) {
                this.targets = new RenderTargets();
            }
            // Hand the pack's custom-texture PNG bytes to the targets (loaded lazily into noisetex + the water normal map).
            this.targets.setCustomTextures(this.noisePngData, this.waterNormalPngData);
            // Match the shadow map size to the pack's shadowMapResolution const (god rays sample it by explicit texel index).
            this.targets.setShadowSize(packShadowMapResolution());
            // Same reason as the shadow-map resolution above: shadowDistance is a live slider in the shader options
            // screen, and it drives BOTH the ortho half-extent and the pack's shadow distortion bias. Sampled ONCE
            // per frame here, before anything that reads the shadow box (computeShadowMatrices, the section culls,
            // updateShadowRenderList, the entity caster radius) runs later in the same frame.
            refreshPackShadowGeometry();
            this.basicBracketsRun = 0;      // leash-gate counters are per FRAME (F3 diagnostics)
            this.basicBracketsSkipped = 0;
            this.cpuCameraEntitiesNanos = 0L;
            this.cpuShadowEntitiesNanos = 0L;
            this.cpuCameraEntitiesCalls = 0;
            this.shadowEntSetupNanos = 0L;
            this.shadowEntDrawNanos = 0L;
            this.shadowEntTesrNanos = 0L;
            this.targets.resize(w, h);

            EntityRenderer er = mc.entityRenderer;
            this.fogColorR = er.fogColorRed;
            this.fogColorG = er.fogColorGreen;
            this.fogColorB = er.fogColorBlue;

            this.near = 0.05F;
            this.far = Math.max(32.0F, mc.gameSettings.renderDistanceChunks * 16.0F);
            // Fog fades over the outer part of the render distance, toward the sky/fog colour (like vanilla).
            this.fogStart = this.far * 0.55F;
            this.fogEnd = this.far * 0.92F;

            // Snapshot the previous frame's camera matrices ONCE here (before this frame's terrain overwrites them in
            // captureMatrices) for temporal reprojection. gbufferProjection/gbufferModelView still hold last frame's values
            // at this point (they're only updated during the terrain draw, which comes after this).
            this.prevProjection.set(this.gbufferProjection);
            this.prevModelView.set(this.gbufferModelView);

            gatherWorldState(mc, partialTicks);
            this.frameCounter++;
            // Sample the animation clock once for the whole frame (see field note).
            long nowNanos = System.nanoTime();
            this.frameTimeCounter = (nowNanos - this.startNanos) / 1.0e9F;
            if (this.lastFrameNanos != 0L) {
                // Clamp away the pauses (world load, GUI, debugger) that would otherwise spike the smoothed value.
                this.frameTime = Math.min(Math.max((nowNanos - this.lastFrameNanos) / 1.0e9F, 1.0e-4F), 0.5F);
                // Iris smooths frameTime with a ~0.9 decay; the exact constant is not observable, only that it is stable.
                this.frameTimeSmooth += (this.frameTime - this.frameTimeSmooth) * 0.1F;
            }
            this.lastFrameNanos = nowNanos;
            // GL-truth compare-mode report on the sampled frame only (2 lines/frame otherwise).
            RenderTargets.diagCompareMode = DIAG_SHADOW_POS && this.frameCounter % 60 == 0;

            // DIAGNOSTIC (horizon band): dump the exact fog/sky uniforms our port feeds, so the atmospheric-fog result can
            // be compared against the (correct) 1.20.1 look. Cheap (no GPU readback), every 60 frames. Turn off once solved.
            if (DIAG_END_BEAM && "world1/".equals(this.worldFolder) && this.frameCounter % 60 == 0 && this.targets != null) {
                float glFogStart = GL11.glGetFloat(GL11.GL_FOG_START);
                float glFogEnd = GL11.glGetFloat(GL11.GL_FOG_END);
                boolean bossFog = mc.ingameGUI != null && mc.ingameGUI.getBossOverlay().shouldCreateFog();
                net.minecraft.entity.Entity view = mc.getRenderViewEntity();
                float maxCamXZ = view == null ? 0.0F
                        : (float) Math.max(Math.abs(view.posX), Math.abs(view.posZ));
                log(String.format(
                        "[END BEAM] glFogStart=%.1f glFogEnd=%.1f far=%.1f ratio=%.3f (dragonDead if >0.5) | bossOverlayFog=%b maxCamPosXZ=%.0f | vlFactor=%.4f",
                        glFogStart, glFogEnd, this.far, glFogStart / this.far, bossFog, maxCamXZ,
                        this.targets.readVlFactor()));
            }
            if (HORIZON_DIAG && this.frameCounter % 60 == 0) {
                float sunAngleDbg = this.celestialAngle < 0.75F ? this.celestialAngle + 0.25F : this.celestialAngle - 0.75F;
                // gbufferProjection[0][0]/[1][1] = the H/V clip scale = 1/tan(HFOV/2) & 1/tan(VFOV/2). If [1][1] is far
                // from the expected ~1/tan(vFov/2) (≈1.0-1.5 for a normal FOV), the sky's per-pixel VdotU is distorted.
                // NOTE: during the sky (before this frame's terrain) skybasic reads LAST frame's matrices — which is what
                // this.gbufferProjection still holds here (updated only in captureMatrices during terrain).
                log(String.format(
                        "[HORIZON DIAG] rdChunks=%d near=%.3f far=%.1f | cameraY=%.1f | skyColor=(%.3f,%.3f,%.3f) fogColor=(%.3f,%.3f,%.3f)"
                        + " | celestial=%.4f sunAngle=%.4f sunBright=%.3f | eyeBrSky=%d rain=%.2f | proj m00=%.4f m11=%.4f m22=%.5f m32=%.4f | vpW=%d vpH=%d aspect=%.4f",
                        Minecraft.getMinecraft().gameSettings.renderDistanceChunks, this.near, this.far, (float) this.cameraY,
                        this.skyColorR, this.skyColorG, this.skyColorB, this.fogColorR, this.fogColorG, this.fogColorB,
                        this.celestialAngle, sunAngleDbg, this.sunBrightness, this.eyeBrightnessSky, this.rainStrength,
                        this.gbufferProjection.m00(), this.gbufferProjection.m11(), this.gbufferProjection.m22(), this.gbufferProjection.m32(),
                        this.targets.width(), this.targets.height(), (float) this.targets.width() / Math.max(1, this.targets.height())));
            }

            // NOTE: do NOT reset the ping-pong flip each frame. Intra-frame consistency is already guaranteed (the world
            // pass writes front(i) and the composite chain reads front(i) first — same physical texture regardless of
            // parity). A per-frame reset instead DESTROYS the temporal buffers: TAA history / cloud accumulation / exposure
            // are buffers written an odd number of times per frame, so their front/back legitimately swaps across frames to
            // carry last frame's result forward; forcing front=A each frame made them read a stale copy every other frame →
            // the clouds + water reflections flickered (unresolved dither that TAA should have converged).
            if (DIAG_MAGENTA_CLEAR) {
                this.targets.beginWorldPass(1.0F, 0.0F, 1.0F); // magenta: exposes any pixel never covered by sky/geometry
            } else {
                this.targets.beginWorldPass(this.fogColorR, this.fogColorG, this.fogColorB);
            }
            // Custom images: zero the clear=true voxel/floodfill images + bind the SSBOs before any world geometry (the
            // shadow pass, later this frame, voxelizes into them). No-op when the pack declared none (COLORED_LIGHTING=0).
            this.customImages.clearForFrame();
            // Also zero the WSR face-data SSBO each frame: it's indexed by a camera-relative voxel pos, so a slot not
            // rewritten this frame holds a stale UV from a different world block. On our 1.12.2 combined block+item
            // atlas that stale UV shows up as stray item icons smeared over reflections. Zeroing → unwritten faces have
            // textureRad=0 → getShadedReflection rejects them → the ray marches to a real face or the sky. (See
            // CustomImages#clearSsbosForFrame for the full rationale.)
            this.customImages.clearSsbosForFrame();
            this.customImages.bindSsbos();
            // shadowtex1 (opaque-only shadow depth) for the gbuffers programs, once per frame like the atlas below.
            if (UNDERWATER_SHAFTS) {
                this.targets.bindOpaqueShadowForGbuffers();
            }
            // Bind the block atlas to its dedicated high unit for the pack's world-space reflections (WSR samples
            // textureAtlas to colour reflected voxels). Once per frame here — the binding persists through the water +
            // composite passes (nothing else touches that unit). Guarded on a valid atlas id.
            int atlasGl = Minecraft.getMinecraft().getTextureMapBlocks().getGlTextureId();
            if (atlasGl != 0) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + RenderTargets.TEXTURE_ATLAS_UNIT);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlasGl);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                // Attach a sampler object to this unit that caps the mip level. texture2DLod in getShadedReflection uses a
                // huge lod at grazing angles → coarse mips whose texels average across sprite boundaries → neighbouring
                // ITEM icons bleed into the block reflection (only visible on 1.12.2's combined block+item atlas). MAX_LOD
                // keeps sampling within the sprite's own texels. The sampler overrides only unit 40, not the shared atlas.
                if (ATLAS_REFLECT_MIN_LOD > 0.01F) {
                    if (this.atlasReflectSampler == 0) {
                        this.atlasReflectSampler = org.lwjgl.opengl.GL33C.glGenSamplers();
                        org.lwjgl.opengl.GL33C.glSamplerParameteri(this.atlasReflectSampler, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
                        org.lwjgl.opengl.GL33C.glSamplerParameteri(this.atlasReflectSampler, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
                        org.lwjgl.opengl.GL33C.glSamplerParameteri(this.atlasReflectSampler, GL11.GL_TEXTURE_WRAP_S, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
                        org.lwjgl.opengl.GL33C.glSamplerParameteri(this.atlasReflectSampler, GL11.GL_TEXTURE_WRAP_T, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
                        // Floor the mip: texture2DLod's explicit lod is clamped to >= MIN_LOD, so the reflection samples a
                        // coarser (blurred) atlas level → the reflected block textures soften into a sheen.
                        org.lwjgl.opengl.GL33C.glSamplerParameterf(this.atlasReflectSampler, org.lwjgl.opengl.GL12.GL_TEXTURE_MIN_LOD, ATLAS_REFLECT_MIN_LOD);
                    }
                    org.lwjgl.opengl.GL33C.glBindSampler(RenderTargets.TEXTURE_ATLAS_UNIT, this.atlasReflectSampler);
                }
            }
            // Bind cloud-water.png (the pack's gaux4 = cloud-shape noise) so gbuffers_terrain's wetness/puddle cloud
            // reflection reads real noise, not the block atlas (unit 0 default → "all random textures" in wet reflections).
            int cloudWaterGl = this.targets.waterNormalTexture();
            if (cloudWaterGl != 0) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + RenderTargets.CLOUD_WATER_UNIT);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, cloudWaterGl);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
            }
            // Bind noisetex for the OPAQUE TERRAIN program too. Sodium's chunk shader never bound it, so the pack's
            // gbuffers_terrain sampled unit 0 (the block atlas) as "noise" — and its rain-puddle code uses that noise for
            // the puddle SHAPE, so puddles came out shaped like atlas sprites/item icons lying flat on the ground. Bound
            // once per frame here (like the atlas + cloud-water above); the binding persists through the world pass.
            int noiseGl = this.targets.noiseTexture();
            if (noiseGl != 0) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + RenderTargets.TERRAIN_NOISETEX_UNIT);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, noiseGl);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
            }
            // Default labPBR specular/normals stand-ins (units 49/50): every program's `specular`/`normals` sampler is
            // pointed here by setSceneUniforms — unassigned they read the block atlas and its mip-averaged sprite alpha
            // became labPBR emission (the END held-item flicker). Once per frame, persists through the world pass.
            this.targets.bindDefaultPbrTextures();
            this.diagTerrainPassCount = 0;
            this.waterDepthCopiedThisFrame = false;
            this.activeThisFrame = true;
        } catch (Throwable t) {
            SodiumClientMod.logger().error("[Iris] beginWorldRender failed; disabling pipeline", t);
            this.enabled = false;
            this.activeThisFrame = false;
        }
    }

    private void gatherWorldState(Minecraft mc, float partialTicks) {
        World world = mc.world;
        Entity view = mc.getRenderViewEntity();
        if (world == null || view == null) {
            return;
        }
        // Save last frame's camera position (paired with prev matrices) before recomputing this frame's.
        this.prevCameraX = this.cameraX;
        this.prevCameraY = this.cameraY;
        this.prevCameraZ = this.cameraZ;
        // Smoothed held-item lightmap (see onItemRender): vanilla samples the HAND's brightness at the truncated EYE
        // BlockPos, which steps hard at cell boundaries — measured on the [ITEM METER] as 2-3 frame dips to ~45%
        // brightness at every crossing while walking. The pack's dark-dimension lighting curve (END especially: no
        // skylight to mask blocklight steps) turns that vanilla quirk into a visible held-item blink. ~0.15s halflife:
        // fast enough to track real light changes, long enough to erase the boundary spikes.
        try {
            net.minecraft.util.math.BlockPos eyePos = new net.minecraft.util.math.BlockPos(
                    view.posX, view.posY + view.getEyeHeight(), view.posZ);
            int combined = world.getCombinedLight(eyePos, 0);
            float lx = (float) (combined & 0xFFFF);
            float ly = (float) ((combined >> 16) & 0xFFFF);
            float rate = 1.0F - (float) Math.exp(-Math.max(this.frameTime, 1.0e-4F) / 0.15F * 0.6931F);
            if (this.handLightSmoothX < 0.0F) { // first frame: snap
                this.handLightSmoothX = lx;
                this.handLightSmoothY = ly;
            } else {
                this.handLightSmoothX += (lx - this.handLightSmoothX) * rate;
                this.handLightSmoothY += (ly - this.handLightSmoothY) * rate;
            }
        } catch (Throwable ignored) {
        }
        // cameraPosition MUST be the FEET position, because our world geometry is fed to the chunk shader FEET-relative
        // (Sodium's u_RegionOffset uses the view entity's feet Y — MixinRenderGlobal passes entity posY, no eye height —
        // and X/Z are already feet). The pack's core invariant is worldPos = cameraPosition + playerPos, and the pack also
        // derives cameraPositionBestFract = fract(cameraPosition) to align the colored-lighting VOXEL grid to block
        // boundaries. Adding eyeHeight to Y only (leaving X/Z at feet) made cameraPosition.y inconsistent with the geometry:
        // fract(eyeHeight)=fract(1.62)=0.62 shifted every voxel cell boundary 0.38 off the block boundary, so the bottom
        // 0.38 of each block sampled the cell below — visible as the nether-portal edge frame (top frame missing, bottom
        // frame too thick) while X/Z stayed correct. Keep Y at feet like X/Z; add eyeHeight locally only where the EYE
        // position is genuinely needed (cave/underwater light sampling below).
        this.cameraX = view.lastTickPosX + (view.posX - view.lastTickPosX) * partialTicks;
        this.cameraY = view.lastTickPosY + (view.posY - view.lastTickPosY) * partialTicks;
        this.cameraZ = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * partialTicks;
        this.celestialAngle = world.getCelestialAngle(partialTicks);
        this.sunBrightness = world.getSunBrightness(partialTicks);
        this.worldTime = (int) (world.getWorldTime() % 24000L);
        this.worldDay = (int) (world.getWorldTime() / 24000L);
        this.rainStrength = world.getRainStrength(partialTicks);
        this.thunderStrength = world.getThunderStrength(partialTicks);
        // isEyeInWater must key on the CAMERA EYE, not the body: view.isInWater() is true whenever the entity's bounding
        // box touches water (e.g. standing waist-deep with the eye ABOVE the surface), so it wrongly flagged the eye as
        // submerged during partial submersion and never distinguished "wading" from "fully underwater". isInsideOfMaterial
        // checks the block at posY + eyeHeight — the exact eye-in-fluid test OptiFine/Iris use. 1 = water, 2 = lava.
        this.isEyeInWater = view.isInsideOfMaterial(net.minecraft.block.material.Material.WATER) ? 1
                : (view.isInsideOfMaterial(net.minecraft.block.material.Material.LAVA) ? 2 : 0);
        // Find the water surface above the eye (first non-water block scanning up) for waterAltitude — see field note.
        if (this.isEyeInWater == 1) {
            int startY = (int) Math.floor(view.posY + view.getEyeHeight());
            float surface = 61.9F;
            net.minecraft.util.math.BlockPos.MutableBlockPos wp = new net.minecraft.util.math.BlockPos.MutableBlockPos(
                    (int) Math.floor(view.posX), startY, (int) Math.floor(view.posZ));
            for (int i = 0; i <= 96; i++) {
                wp.setY(startY + i);
                if (world.getBlockState(wp).getMaterial() != net.minecraft.block.material.Material.WATER) {
                    surface = startY + i;
                    break;
                }
            }
            this.waterAltitude = surface;
        } else {
            this.waterAltitude = 61.9F;
        }
        if (DIAG_EYE_WATER && this.frameCounter % 40 == 0) {
            double eyeY = view.posY + view.getEyeHeight();
            net.minecraft.util.math.BlockPos eyePos = new net.minecraft.util.math.BlockPos(view.posX, eyeY, view.posZ);
            // fogColor is what the pack turns into the UNDERWATER colour (WATERCOLOR_MODE>=2:
            // underwaterColorM1 = pow(fogColor, ...) → waterFogColor). If this is still the SKY colour while submerged,
            // the "water" fog mixes toward a near-white/grey haze → underwater looks like plain air. Vanilla's
            // updateFogColor sets a dark blue (~0.02,0.02,0.2) when the eye is in water.
            log(String.format("[EYE WATER] isEyeInWater=%d | isInWater=%b isInsideWater=%b eyeBlock=%s eyeY=%.2f | fogColor=(%.3f,%.3f,%.3f) skyColor=(%.3f,%.3f,%.3f)",
                    this.isEyeInWater, view.isInWater(),
                    view.isInsideOfMaterial(net.minecraft.block.material.Material.WATER),
                    world.getBlockState(eyePos).getBlock().getRegistryName(), eyeY,
                    this.fogColorR, this.fogColorG, this.fogColorB,
                    this.skyColorR, this.skyColorG, this.skyColorB));
        }
        this.moonPhase = world.getMoonPhase();
        this.screenBrightness = mc.gameSettings.gammaSetting;
        Vec3d sky = world.getSkyColor(view, partialTicks);
        this.skyColorR = (float) sky.x;
        this.skyColorG = (float) sky.y;
        this.skyColorB = (float) sky.z;

        // eyeBrightness (0..240 = light level 0..15 × 16): the pack uses .y (sky) for cave/eye-in-cave detection and .x
        // (block) for held/ambient block light. Sample the light at the EYE block (cameraY is feet now, so add eyeHeight).
        BlockPos eyePos = new BlockPos(this.cameraX, this.cameraY + view.getEyeHeight(), this.cameraZ);
        this.eyeBrightnessSky = world.getLightFor(EnumSkyBlock.SKY, eyePos) * 16;
        this.eyeBrightnessBlock = world.getLightFor(EnumSkyBlock.BLOCK, eyePos) * 16;
        // Biome precipitation type → the pack's three biome_precipitation flags (matching its custom uniforms):
        // inRainy (==1, rain — canRain() is false for deserts + snowy biomes, so rain puddles only form where it rains),
        // inSnowy (==2, snow biome), inDry (==0, neither — desert/mesa/nether-style atmosphere tweaks key on it).
        net.minecraft.world.biome.Biome eyeBiome = world.getBiome(eyePos);
        this.inRainy = eyeBiome.canRain() ? 1.0F : 0.0F;
        this.inSnowy = eyeBiome.isSnowyBiome() ? 1.0F : 0.0F;
        this.inDry = (this.inRainy < 0.5F && this.inSnowy < 0.5F) ? 1.0F : 0.0F;
        updateWeatherSmoothing();
        gatherHeldLight(view);
        this.isRightHanded = mc.gameSettings.mainHand == net.minecraft.util.EnumHandSide.RIGHT ? 1 : 0;

        // lightningBoltPosition (Iris): camera-relative position of a LIVE lightning bolt, w=1 while one is rendered.
        // 1.12.2 keeps bolts in World#weatherEffects (not the regular entity list); relative to our FEET cameraPosition,
        // matching the pack's `getLightningPos(playerPos, lightningBoltPosition.xyz, ...)` compare (playerPos is
        // feet-camera-relative in this port).
        this.lightningBoltPos[0] = this.lightningBoltPos[1] = this.lightningBoltPos[2] = this.lightningBoltPos[3] = 0.0F;
        for (int i = 0; i < world.weatherEffects.size(); i++) {
            Entity bolt = world.weatherEffects.get(i);
            if (bolt instanceof net.minecraft.entity.effect.EntityLightningBolt) {
                this.lightningBoltPos[0] = (float) (bolt.posX - this.cameraX);
                this.lightningBoltPos[1] = (float) (bolt.posY - this.cameraY);
                this.lightningBoltPos[2] = (float) (bolt.posZ - this.cameraZ);
                this.lightningBoltPos[3] = 1.0F;
                break;
            }
        }

        // Vision-affecting potion effects (drive nightVision brightening + blindness/darkness fog in the atmosphere).
        if (view instanceof EntityLivingBase) {
            EntityLivingBase living = (EntityLivingBase) view;
            this.nightVision = living.isPotionActive(MobEffects.NIGHT_VISION) ? 1.0F : 0.0F;
            this.blindness = living.isPotionActive(MobEffects.BLINDNESS) ? 1.0F : 0.0F;
        } else {
            this.nightVision = 0.0F;
            this.blindness = 0.0F;
        }
        this.darknessFactor = 0.0F; // no Darkness effect in 1.12.2

        // Block-atlas dimensions (for the WSR `atlasSize` uniform), queried once from the atlas GL texture. Bind → query
        // GL_TEXTURE_WIDTH/HEIGHT → the active unit stays 0 (MC rebinds its atlas before drawing). Falls back to 512² if
        // the query fails (e.g. atlas not yet uploaded).
        if (this.atlasWidth == 0) {
            try {
                int glId = mc.getTextureMapBlocks().getGlTextureId();
                if (glId != 0) {
                    GlStateManager.bindTexture(glId);
                    int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
                    int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
                    GlStateManager.bindTexture(0);
                    this.atlasWidth = w > 0 ? w : 512;
                    this.atlasHeight = h > 0 ? h : 512;
                }
            } catch (Throwable t) {
                this.atlasWidth = 512;
                this.atlasHeight = 512;
            }
        }
    }

    /** Runs the deferred → composite → final chain onto the main framebuffer. */
    public void endWorldRender() {
        if (!this.activeThisFrame) {
            return;
        }
        this.activeThisFrame = false;
        try {
            Framebuffer main = Minecraft.getMinecraft().getFramebuffer();

            // Colored-lighting flood-fill (shadowcomp) now runs at the END of renderShadowPass — i.e. right after the shadow
            // pass voxelizes voxel_img and BEFORE the solid terrain gbuffers sample floodfill — not here. Running it here (in
            // endWorldRender, AFTER the terrain) meant gbuffers_terrain read a floodfill written on the previous frame of the
            // SAME framemod2 parity (two frames stale), anchored to floor(cameraPosition) from two frames ago. GetLightVolume
            // samples with THIS frame's floor, so at every block the camera crossed the anchors disagreed by a voxel for one
            // frame → the "flicker every time you cross a block". The composite/WSR still read this same fresh floodfill.

            // FLICKER SPLIT: colortex0 here = the lit scene straight from the gbuffer (emission + floodfill + in-gbuffer
            // SSBL applied), BEFORE any composite/bloom/tonemap. If the flicker at ~(330,210) is already here, the source
            // is the gbuffer lighting; if colortex0 is steady but colortex3 (measured at display) flickers, it's the
            // composite chain (SSBL blur / bloom / tonemap).
            if (DIAG_FLOODFILL_FLICKER && this.frameCounter >= 200 && this.frameCounter <= 214) {
                this.targets.diagTemporalDelta(0, this.frameCounter);
            }

            // v8 hand-term readback MUST run here, before any deferred/composite pass can flip ct6.
            diagHandTerms();
            // Rim hunt: the GBUFFER-time profile, before any deferred/composite touches ct0.
            diagRimScanline("gbuffer", 0);

            beginFullscreenState();

            // Copy the opaque scene depth into the R32F colour texture the composite reads as depthtex1 (see the field note):
            // makes z0 (full, water surface) != z1 (opaque, sea floor) at water pixels → the pack's getWSR fires.
            runOpaqueDepthCopy();

            // DOF auto-focus (centerDepthSmooth): centre depth read back ASYNC (double-buffered PBO — the value is
            // one frame old, which the exponential smoothing swallows; the previous synchronous readDepthTexel here
            // drained the whole GPU pipeline every frame and measurably cost FPS). Smoothed per the
            // centerDepthHalflife directive (tenths of a second). See the field note.
            if (this.wantsCenterDepth) {
                float rawCenterDepth = this.targets.readCenterDepthAsync();
                if (rawCenterDepth >= 0.0F) {
                    float halfLifeSec = Math.max(this.centerDepthHalflife, 0.01F) * 0.1F;
                    float alpha = 1.0F - (float) Math.exp(-this.frameTime * 0.6931472F / halfLifeSec);
                    this.centerDepthSmooth += (rawCenterDepth - this.centerDepthSmooth) * alpha;
                }
            }

            // DIAGNOSTIC: skip the pack's deferred/composite/final chain and blit the RAW colortex0 (the gbuffer the
            // pack's gbuffers_terrain/sky/etc. just wrote) straight to the screen — to isolate whether a "white/broken"
            // result comes from the gbuffer capture or from the composite chain. Set false for normal rendering.
            if (DIAG_SHOW_GBUFFER) {
                if (isDiagFrame()) {
                    logColor0Readback();
                }
                main.bindFramebuffer(true);
                GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
                GlStateManager.bindTexture(this.targets.currentColor0());
                GlStateManager.enableTexture2D();
                GlslProgram.unuse();
                drawFullscreenQuad();
                endFullscreenState();
                return;
            }

            // Built-in tonemap path: skip the pack's divergent deferred chain and tonemap the already-lit colortex0.
            // Bind colortex0 on unit 0 with the same fixed-function texturing the proven DIAG blit uses (the raw unit-2..22
            // composite-read setup left GL state that kept the shader draw off-screen).
            if (USE_BUILTIN_TONEMAP && this.tonemapProgram != null) {
                main.bindFramebuffer(true);
                // depthtex0 on unit 1 (depth textures sample right on units 0/1 here), colortex0 on unit 0.
                GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
                GlStateManager.bindTexture(this.targets.depthTexture());
                GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
                GlStateManager.bindTexture(this.targets.currentColor0());
                GlStateManager.enableTexture2D();
                this.tonemapProgram.use();
                this.tonemapProgram.setInt("colortex0", 0);
                this.tonemapProgram.setInt("depthtex0", 1);
                this.tonemapProgram.setFloat("near", this.near);
                this.tonemapProgram.setFloat("far", this.far);
                this.tonemapProgram.setVec3("fogColor", this.fogColorR, this.fogColorG, this.fogColorB);
                this.tonemapProgram.setVec3("skyColor", this.skyColorR, this.skyColorG, this.skyColorB);
                drawFullscreenQuad();
                GlslProgram.unuse();
                endFullscreenState();
                return;
            }

            // DIAGNOSTIC: run only the first DIAG_CHAIN_PASSES of the combined prepare+deferred+composite chain, to bisect
            // which pass whites out the image. Pair with DIAG_BLIT_INSTEAD_OF_FINAL: with the pack's final running, a
            // truncated chain would still be repainted by final — the blit shows front(FINAL_COLORTEX) untouched.
            List<Pass> chain = new ArrayList<>();
            chain.addAll(this.preparePrograms);
            chain.addAll(this.deferredPrograms);
            chain.addAll(this.compositePrograms);
            int run = Math.min(DIAG_CHAIN_PASSES, chain.size());
            boolean logThisFrame = isDiagFrame();
            // Horizon-band localisation: sample the screen-centre column of colortex0 before the chain and after the fog
            // pass (deferred1), plus the displayed colortex3 after the chain — to see whether the grey horizon is baked in
            // by the world pass (sky/water) or introduced by the deferred/composite fog + tonemap.
            boolean horizonDiag = HORIZON_DIAG && this.frameCounter % 60 == 0;
            if (horizonDiag) {
                logHorizonColumn("PRE-chain (world pass)", 0);
            }
            if (logThisFrame) {
                checkErr("before chain (cleared)");
                // Trace colortex0 (the main scene buffer most passes read/write) from the raw gbuffer through every pass,
                // so we can pinpoint the pass that turns the colourful lit terrain into grey (over-strong fog etc.).
                logColortexAvg("PRE-chain", 0);
            }
            // UNDERWATER probe part 2: colortex3 exactly as composite1 will read it. The water pass stores
            // 1-translucentMult there (TM5723); if it reads back 0 → translucentMult == vec3(1.0), which
            // volumetricLight.glsl:235 treats as "no water on this ray" and ZEROES every lit sample underwater — that
            // alone hides the god rays even with healthy shadowcolor1 densities.
            if (DIAG_UW_SHADOW && this.isEyeInWater == 1 && this.frameCounter % 60 == 0) {
                int cw = this.targets.width(), ch = this.targets.height();
                float[] cc = this.targets.readColortexTexel(3, cw / 2, ch / 2);
                float[] cu = this.targets.readColortexTexel(3, cw / 2, ch * 5 / 6); // GL upper row = display top (up)
                log(String.format("[UW TM] colortex3 pre-chain center=(%.3f,%.3f,%.3f,a=%.2f) up=(%.3f,%.3f,%.3f,a=%.2f)"
                                + " -> translucentMult center=(%.3f,%.3f,%.3f)%s",
                        cc[0], cc[1], cc[2], cc[3], cu[0], cu[1], cu[2], cu[3],
                        1 - cc[0], 1 - cc[1], 1 - cc[2],
                        (cc[0] == 0.0F && cc[1] == 0.0F && cc[2] == 0.0F) ? "  *** ==vec3(1.0) → VL kills lit samples ***" : ""));
            }
            // Fixed-point probe on the held block's dark face (GL y flips: display 0.85H → GL 0.15H). PRE-chain.
            int diagHandX = 0, diagHandY = 0;
            if (DIAG_HAND_PIXEL && this.frameCounter % 60 == 0) {
                diagHandX = (int) (this.targets.width() * 0.78F);
                diagHandY = (int) (this.targets.height() * 0.15F);
                float hz = this.targets.readDepthTexel(diagHandX, diagHandY);
                float[] pre = this.targets.readColortexTexel(0, diagHandX, diagHandY);
                float[] c6a = this.targets.readColortexTexelSide(6, 0, diagHandX, diagHandY);
                float[] c6b = this.targets.readColortexTexelSide(6, 1, diagHandX, diagHandY);
                log(String.format("[HAND PIXEL] @(%d,%d) depth=%.4f%s | ct0 PRE-chain=(%.3f,%.3f,%.3f,a=%.2f)"
                                + " | ct6 front=%s | A=(sm=%.3f mask=%.0f sky=%.3f a=%.3f) B=(sm=%.3f mask=%.0f sky=%.3f a=%.3f)",
                        diagHandX, diagHandY, hz, hz <= 0.1F ? " (HAND depth ✓)" : " *** NOT hand depth ***",
                        pre[0], pre[1], pre[2], pre[3],
                        this.targets.frontSide(6) == 0 ? "A" : "B",
                        c6a[0], c6a[1] * 255.0F, c6a[2], c6a[3],
                        c6b[0], c6b[1] * 255.0F, c6b[2], c6b[3]));
            }
            this.profiler.end();            // close the last "gb_other" segment
            this.profiler.begin("composite");
            for (int i = 0; i < run; i++) {
                Pass pass = chain.get(i);
                runPass(pass);
                // composite4 has just built the bloom tiles into colortex3 (and runPass already flipped, so front(3) is
                // them). Measure before composite5+ overwrite colortex3. The composite chain runs through runPass, not
                // runChain — the earlier probe there never fired.
                if (DIAG_BLOOM && this.frameCounter == 200 && "composite4".equals(pass.name)) {
                    this.targets.diagBloom();
                }
                // Right after deferred1 writes colortex5 (= waterRefColor), snapshot it for NEXT frame's water gaux2 read —
                // composite1/composite5 (later in this chain) overwrite colortex5.rgb with 0, so a live read gives black.
                if ("deferred1".equals(pass.name)) {
                    this.targets.snapshotWaterRefColor();
                    // Cap the first-person hand's reflection fresnel (deferred1 set it to ~0.7 for the wet hand, which
                    // mirrors sharp scene objects onto it). Runs only if the cap actually reduces something (< 1.0). The
                    // depth fix stays regardless, so this only tames the reflection, not the earlier cloud/fog bleed.
                    if (HAND_REFLECT_MAX < 0.999F) runHandReflectFix();
                    if (horizonDiag) {
                        logHorizonColumn("after deferred1 (opaque fog)", 0);
                    }
                    // UNDERWATER PROBE: colortex0 right after deferred1 (which owns the opaque water fog via DoFog). If the
                    // terrain sample (terr) is already WARM here, deferred1 never fogged it (branch skipped / fog inert);
                    // if it is BLUE here but warm after all composites, a later composite un-blues it.
                    if (DIAG_EYE_WATER && this.isEyeInWater == 1 && this.frameCounter % 60 == 0) {
                        logColortexAvg("UW-after-deferred1", 0);
                    }
                }
                if (logThisFrame) {
                    checkErr("after pass " + i);
                    logColortexAvg(pass.name + " ct0", 0);
                    logColortexAvg(pass.name + " ct3", 3);   // colortex3 = the buffer we display
                }
            }

            this.profiler.end();

            // The same hand pixel AFTER the chain — background here + pale PRE means a composite pass replaced it.
            if (DIAG_HAND_PIXEL && this.frameCounter % 60 == 0) {
                float[] post = this.targets.readColortexTexel(FINAL_COLORTEX, diagHandX, diagHandY);
                log(String.format("[HAND PIXEL] @(%d,%d) ct%d POST-chain (displayed)=(%.3f,%.3f,%.3f)",
                        diagHandX, diagHandY, FINAL_COLORTEX, post[0], post[1], post[2]));
            }

            if (horizonDiag) {
                logHorizonColumn("POST-chain (displayed ct3)", FINAL_COLORTEX);
            }

            // Detach the atlas reflection sampler now the composite chain (the only textureAtlas reader) is done, so unit
            // 40 goes back to default filtering for anything else.
            org.lwjgl.opengl.GL33C.glBindSampler(RenderTargets.TEXTURE_ATLAS_UNIT, 0);

            // DIAGNOSTIC: ground-truth of the buffers final reads (colortex3 = final colour, colortex2 = exposure/taa).
            if (isDiagFrame()) {
                logColortex(0);
                logColortex(3);
                logColortex(2);
            }

            // The composite passes leave texture units 2..22 enabled (bindCompositeReadTextures) — disable them BEFORE
            // DIAGNOSTIC: the crosshair pixel's real gate values, right after the chain ran (so colortex7 holds THIS
            // frame's reflectOutput). ~1/second so a still camera gives a readable stream, not a wall of text.
            // Report the shadow matrices AS THE COMPOSITE CHAIN SEES THEM (not as computeShadowMatrices left them), so a
            // stale/never-computed matrix at composite time cannot hide. ~1/second.
            if (DIAG_SHADOW_POS && this.frameCounter % 60 == 0) {
                diagShadowPos();
            }
            if (DIAG_UW_SHADOW && this.isEyeInWater == 1 && this.frameCounter % 60 == 0) {
                diagUnderwaterShadow();
            }
            if (DIAG_CROSSHAIR && this.frameCounter % 60 == 0) {
                RenderTargets.wsrColorDebug = DIAG_WSR_COLOR;
                RenderTargets.oreDebug = DIAG_ORE_EMISSION;
                this.targets.diagCrosshair();
            }
            // A screen-EDGE boundary can never be put under the crosshair, so scan the whole row instead. Once, so the
            // log stays readable — the camera just has to be pointed at it when the frame lands.
            if (DIAG_SCAN_ROW && this.frameCounter == 200) {
                RenderTargets.wsrColorDebug = DIAG_WSR_COLOR;
                this.targets.diagScanRow();
            }

            // Objective flicker meter for the END held-item investigation (no-op unless DIAG_ITEM_METER + world1).
            diagItemMeter();
            diagFloodfillCenter();
            // nvoglv64 cache-crash reproducer: by frame 200 the lazy chunk programs (the historical crash site) have
            // compiled; round-trip every program binary through the driver's serializer ONCE. See ProgramBinaryAudit.
            if (this.frameCounter == 200) {
                com.yumelium.yumelium.shaders.gl.ProgramBinaryAudit.runOnce();
            }

            // Drop any per-draw-slot GL_BLEND overrides the G-buffer passes set (blend.<program>.colortexN = off). They
            // are GLOBAL GL state, not FBO state, so a slot left disabled here would follow MC straight into its GUI/HUD
            // rendering — which is exactly what it did.
            this.targets.endBlendOverrides();

            // Run the pack's `final` program to MC's framebuffer — the pack's last post-fx (Complementary: sharpen,
            // vignette, dither, underwater wobble). It must run HERE, BEFORE the compare-mode/unbind restores below
            // (setCompositeSamplers re-applies final's own shadowtex0 compare choice; the restores then run after, in
            // their proven order). bindCompositeReadTextures MUST be re-run first: runPass binds the fronts BEFORE its
            // draw and flips AFTER without rebinding, so the last pass's freshly written buffers (composite7's FXAA'd
            // colortex3 here) sit in the new front while the unit still holds that pass's stale INPUT — sampling
            // through the leftover binds displayed the pre-FXAA frame. Iris semantics: output is the main framebuffer,
            // final's DRAWBUFFERS is ignored, and the ping-pong parity does NOT advance (no flipTargets — flipping an
            // unwritten target swaps in a stale frame, see writesColorOutput). The tonemap blit below stays as the
            // no-final fallback and DIAG viewer; its redundant state-reset + framebuffer rebind after this is harmless.
            boolean ranFinal = false;
            if (this.finalProgram != null && !DIAG_BLIT_INSTEAD_OF_FINAL) {
                endFullscreenState();
                beginFullscreenState();
                main.bindFramebuffer(true);
                this.targets.bindCompositeReadTextures();
                for (int idx : this.finalMipmaps) {
                    this.targets.enableMipmapRead(idx);
                }
                this.finalProgram.use();
                setSceneUniforms(this.finalProgram);
                setCompositeSamplers(this.finalProgram);
                this.customImages.bindTo(this.finalProgram);
                drawFullscreenQuad();
                GlslProgram.unuse();
                for (int idx : this.finalMipmaps) {
                    this.targets.disableMipmapRead(idx);
                }
                ranFinal = true;
            }

            // composite1 flips the shadow map to raw-depth reads (see setCompositeSamplers); put hardware compare back
            // while unit 0 is still OURS (bindCompositeReadTextures left shadowtex0 bound there), so re-binding it costs
            // nothing and cannot desync GlStateManager's cache. Next frame's terrain does shadow2D and needs compare on.
            this.targets.setShadowCompareMode(true);

            // touching the main framebuffer, or MC's later GUI/menu rendering samples those units and renders black.
            this.targets.unbindCompositeReadTextures();

            // Fully reset the fullscreen GL state the composite chain left, then re-establish a fresh one — the composites
            // leave GL such that a subsequent fullscreen-quad draw to the main framebuffer draws off-screen. This is the
            // same clean state the (working) built-in tonemap ran in.
            endFullscreenState();
            beginFullscreenState();

            // Fallback/diagnostic display (draw gated when the pack's final ran above): colortex3 (the buffer
            // Complementary's composites write the finished, already-tonemapped colour into — final.glsl reads it
            // too), shown with the proven fixed-function unit-0 + shader draw. The DIAG viewer flags force this quad
            // even after final ran (see diagViewer below); otherwise only the binds execute (harmless, no draw).
            main.bindFramebuffer(true);
            GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
            GlStateManager.bindTexture(this.targets.depthTexture());
            if (DIAG_HAND_DEPTH || DIAG_COLORTEX4A) { // colortex4 (front) on unit 2 — normal.rgb + the fresnel .a
                GL13.glActiveTexture(GL13.GL_TEXTURE2);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.targets.front(4));
            }
            // NOTE: bind diagnostic textures with RAW GL, not GlStateManager. bindCompositeReadTextures() binds units 2+
            // with raw glBindTexture, which does NOT update GlStateManager's per-unit cache — so a GlStateManager.bindTexture
            // here can decide the texture is "already bound" and skip the call, leaving the composite's binding in place.
            // That silently made an earlier depthtex1 diagnostic read colortex1 (unit 3 = COLORTEX_UNIT0+1) and sent me
            // chasing a depth bug that did not exist.
            if (DIAG_SHOW_DEPTHTEX1) { // our R32F opaque-depth copy on unit 3 — what the pack's SSR gates on
                GL13.glActiveTexture(GL13.GL_TEXTURE3);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.targets.opaqueDepthColorTexture());
            }
            if (DIAG_COLORTEX7 || DIAG_REF_TEMPORAL) { // the composite's WSR reflectOutput on unit 4
                GL13.glActiveTexture(GL13.GL_TEXTURE4);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.targets.front(7));
            }
            if (DIAG_COLORTEX4A || DIAG_HAND_MCBL) { // colortex6 (front) on unit 5 — smoothnessD in .r / hand diag rgb
                GL13.glActiveTexture(GL13.GL_TEXTURE5);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.targets.front(6));
            }
            if (DIAG_WSR_READ) { // the pack's voxel volumes, on units 6/7, to read them back through a real sampler
                GL13.glActiveTexture(GL13.GL_TEXTURE6);
                GL11.glBindTexture(org.lwjgl.opengl.GL12.GL_TEXTURE_3D, this.customImages.textureOf("wsr_lod_img"));
                GL13.glActiveTexture(GL13.GL_TEXTURE7);
                GL11.glBindTexture(org.lwjgl.opengl.GL12.GL_TEXTURE_3D, this.customImages.textureOf("wsr_img"));
            }
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GlStateManager.bindTexture(this.targets.front(FINAL_COLORTEX));
            GlStateManager.enableTexture2D();
            // The FINAL displayed buffer (colortex3, post whole composite chain). colortex0 mid-chain was 5.477 (bright
            // emissive specks) — this says what survived tonemapping to the screen. If the speck is ~1.0 here it is
            // displayed bright and the gap to 1.20.1 is smaller than the screenshot suggests; if it is crushed to ~0.3
            // the composite tonemap/exposure is the culprit.
            if (DIAG_BLOOM && this.frameCounter == 201) {
                this.targets.diagFinal(FINAL_COLORTEX);
            }
            // FLICKER PROBE part 2: the SSBL (screen-space block light) buffers, per-frame over a window. colortex9 =
            // raw SS blocklight albedo (terrain writes it), colortex10 = the temporally-accumulated/blurred result
            // (colortex10Clear=false). MCBL_MAIN_DEFINE=3 makes SS_BLOCKLIGHT active. If these alternate/jump each frame
            // while the floodfill stays steady, the flicker is the screen-space accumulator, not the voxel floodfill.
            if (DIAG_FLOODFILL_FLICKER && this.frameCounter >= 200 && this.frameCounter <= 214) {
                // colortex10 = the SS_BLOCKLIGHT accumulator (temporal, reprojected). If it flickers at the same fixed
                // spot as colortex0's gbuffer flicker (~330,273), the screen-space block light is the source — and its
                // input colortex9 read NaN, which is the likely mechanism.
                this.targets.diagTemporalDelta(10, this.frameCounter);
                this.targets.diagTemporalDelta(FINAL_COLORTEX, this.frameCounter);
            }
            // DIAGNOSTIC: underwater, composite1 must have darkened+blued colortex0 (color *= ~(0.68,0.74,0.82)) and
            // replaced sky pixels with waterFogColor (~dark blue). Read the real buffers back: if colortex0 IS tinted but
            // the final display buffer is not, the composite output is being lost on the way to the screen.
            if (DIAG_EYE_WATER && this.isEyeInWater == 1 && this.frameCounter % 60 == 0) {
                logColortexAvg("UW-ct0(after composites)", 0);
                logColortexAvg("UW-final(displayed)", FINAL_COLORTEX);
            }
            // These DIAG viewers display THROUGH the tonemap quad — when one is on, draw it even though final already
            // ran (the viewer deliberately paints its visualization over the pack's output).
            boolean diagViewer = DIAG_HAND_DEPTH || DIAG_SHOW_DEPTHTEX1 || DIAG_COLORTEX7
                    || DIAG_REF_TEMPORAL || DIAG_COLORTEX4A || DIAG_WSR_READ;
            if (ranFinal && !diagViewer) {
                // The pack's final already drew the frame — the state resets/restores above still ran; only the
                // overwriting tonemap draw is skipped (it would paint front(FINAL_COLORTEX) over final's output).
            } else if (this.tonemapProgram != null) {
                this.tonemapProgram.use();
                this.tonemapProgram.setInt("colortex0", 0);
                this.tonemapProgram.setInt("depthtex0", 1);
                this.tonemapProgram.setInt("colortex4diag", 2);
                this.tonemapProgram.setInt("depthtex1diag", 3);
                this.tonemapProgram.setInt("colortex7diag", 4);
                this.tonemapProgram.setInt("colortex6diag", 5);
                this.tonemapProgram.setInt("wsrLodDiag", 6);
                this.tonemapProgram.setInt("wsrDiag", 7);
                this.tonemapProgram.setFloat("diagWsrRead", DIAG_WSR_READ ? 1.0F : 0.0F);
                this.tonemapProgram.setMatrix4("gbufferProjectionInverse", this.projectionInverse);
                this.tonemapProgram.setFloat("exposure", 1.0F);
                this.tonemapProgram.setFloat("diagHandDepth", DIAG_HAND_DEPTH ? 1.0F : 0.0F);
                this.tonemapProgram.setFloat("diagDepthtex1", DIAG_SHOW_DEPTHTEX1 ? 1.0F : 0.0F);
                this.tonemapProgram.setFloat("diagColortex7", DIAG_COLORTEX7 ? 1.0F : 0.0F);
                this.tonemapProgram.setFloat("diagRefTemporal", DIAG_REF_TEMPORAL ? 1.0F : 0.0F);
                this.tonemapProgram.setFloat("diagColortex4a", DIAG_COLORTEX4A ? 1.0F : 0.0F);
                // v6 bisect must show the hand through the NORMAL chain (constant finalDiffuse only) — the ct6 raw
                // display below was the v5 encode's viewer; with the encode gone it just painted the hand's material
                // data (the "item turned green" report). Keep the branch in TONEMAP_FSH for future encodes, gate off.
                this.tonemapProgram.setFloat("diagHandMcbl", 0.0F);
                drawFullscreenQuad();
                GlslProgram.unuse();
            } else {
                GlslProgram.unuse();
                drawFullscreenQuad();
            }
            endFullscreenState();
            // FIX (dark GUI): vanilla disables the lightmap texture unit after the world render, but our pipeline draws
            // terrain via Sodium (which enables the lightmap unit and never restores it here), so the fixed-function 2D
            // GUI — F3 text, hotbar, and the title-screen panorama after quitting — inherited an ENABLED lightmap unit and
            // was modulated by the world's lightmap texel. At night that texel is dark → dark GUI, while the shader-lit 3D
            // scene (which ignores the lightmap) stayed correct; the darkness persisted into the title panorama. Restore
            // the vanilla post-world state so 2D rendering is untinted. MC re-enables the lightmap next frame's world pass.
            // NOTE: entityRenderer.disableLightmap() goes through GlStateManager, whose cached lightmap-unit tex2d flag is
            // DESYNCED from real GL here — the diagnostic showed GL has GL_TEXTURE_2D ENABLED on the lightmap unit while
            // GlStateManager believes it is off (something raw-enabled it), so that call is a silent no-op. Force it off in
            // RAW GL so the actual state matches the cache's belief, then leave the active unit back on 0. NOTE:
            // OpenGlHelper.lightmapTexUnit is already the GL enum GL_TEXTURE1 (not an index), so pass it straight to
            // glActiveTexture — adding GL_TEXTURE0 to it would select an invalid unit (the call no-ops, staying on unit 0,
            // and the glDisable would then kill unit 0's block-atlas texturing → an all-white screen).
            GL13.glActiveTexture(OpenGlHelper.lightmapTexUnit);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL13.glActiveTexture(OpenGlHelper.defaultTexUnit);

            // Shader health report (video settings → Yumelium Plus): one shot after a COMPLETED frame, so lazily
            // compiled programs (the pack terrain compiles during the first frame's terrain pass) are all accounted for.
            boolean wantHealth = SodiumClientMod.options().yumeliumPlus.shaderHealthReport;
            if (wantHealth && !this.healthReported) {
                this.healthReported = true;
                logHealthReport();
            } else if (!wantHealth) {
                this.healthReported = false; // re-arm: toggling OFF→ON prints a fresh report
            }

            // Harvest the GPU timers issued a few frames back and report the breakdown ~1/second.
            this.profiler.frameEnd();
            if (DIAG_GPU_TIME && this.frameCounter % 60 == 0) {
                String report = this.profiler.report();
                if (report != null) {
                    // frameTime is the CPU-side frame wall clock. When GPU-bound the two nearly match, so the gap between
                    // it and the measured total is what these phases do NOT cover (vanilla GUI, MC's own work, or a CPU
                    // bottleneck). Printing it stops the breakdown from being read as if it were the whole frame.
                    log("[GPU TIME] " + report + String.format(" | cpuFrame=%.2fms (%.0f fps)", this.frameTime * 1000.0F, 1.0F / this.frameTime)
                            + " | shadowSections=" + this.lastShadowSections
                            + " (buried culled=" + this.lastShadowCulled + ")"
                            + " renderDist=" + Minecraft.getMinecraft().gameSettings.renderDistanceChunks
                            + " shadowMap=" + this.targets.shadowSize() + "² res=" + this.targets.width() + "x" + this.targets.height()
                            // Entity counts alongside the timings, so cost-per-entity is readable straight off the
                            // line instead of needing an F3 screenshot taken at the same moment.
                            + entityDiagSuffix());
                }
            }
        } catch (Throwable t) {
            SodiumClientMod.logger().error("[Iris] endWorldRender failed; disabling pipeline", t);
            this.enabled = false;
            try {
                Minecraft.getMinecraft().getFramebuffer().bindFramebuffer(true);
            } catch (Throwable ignored) {
            }
        }
    }

    private void checkErr(String where) {
        int e = GL11.glGetError();
        if (e != 0) log("GL error 0x" + Integer.toHexString(e) + " " + where);
    }

    /** DIAGNOSTIC: dumps the block atlas (what the WSR samples as textureAtlas) to run/client/atlas_dump.png so we can see
     *  whether the region the reflection samples (SSBO origins ~[0.06..0.49]) holds real block textures or item icons. */
    /**
     * DIAGNOSTIC: dumps the block atlas's MIP CHAIN (levels 0..N) to run/client/atlas_mip<N>.png, plus a per-level
     * report of how dark its non-transparent texels are.
     *
     * <p>"Foliage goes black from certain angles" was already traced once to atlas MIP BLEED — the fix was to force
     * {@code iris_MipBias} (−4 on cutout ≈ level 0) onto the terrain fragment's atlas reads. That fix cannot help two
     * places, and both are exactly what is still broken: the LEAVES layer is solid/mipped so its bias is 0 (it keeps its
     * mips on purpose), and the WSR reflection samples the atlas through its OWN
     * {@code texture2DLod(textureAtlas, textureCoord, lod)} in getShadedReflection, where iris_MipBias does not exist at
     * all. If the higher mips really are black where foliage lives, that single fact explains both reports; if they are
     * not, the shared-cause theory dies here instead of after another round of guessing.</p>
     */
    private void dumpAtlas() {
        try {
            int atlasGl = Minecraft.getMinecraft().getTextureMapBlocks().getGlTextureId();
            if (atlasGl == 0) return;
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlasGl);
            int maxLevel = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, org.lwjgl.opengl.GL12.GL_TEXTURE_MAX_LEVEL);
            log("[ATLAS DUMP] GL_TEXTURE_MAX_LEVEL=" + maxLevel);
            for (int level = 0; level <= Math.min(maxLevel, 4); level++) {
                int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, level, GL11.GL_TEXTURE_WIDTH);
                int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, level, GL11.GL_TEXTURE_HEIGHT);
                if (w <= 0 || h <= 0) {
                    log("[ATLAS DUMP] level " + level + ": MISSING (no mip uploaded)");
                    continue;
                }
                java.nio.ByteBuffer pix = org.lwjgl.BufferUtils.createByteBuffer(w * h * 4);
                GL11.glGetTexImage(GL11.GL_TEXTURE_2D, level, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pix);
                java.awt.image.BufferedImage img =
                        new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                // "Dark but visible" is the tell: a texel the shader will KEEP (alpha passes the cutout test) whose rgb
                // has been dragged toward black by averaging against transparent neighbours.
                long darkOpaque = 0, opaque = 0;
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int i = (y * w + x) * 4;
                        int r = pix.get(i) & 0xFF, g = pix.get(i + 1) & 0xFF, b = pix.get(i + 2) & 0xFF,
                                a = pix.get(i + 3) & 0xFF;
                        img.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
                        if (a > 128) {
                            opaque++;
                            if (r + g + b < 24) {
                                darkOpaque++;
                            }
                        }
                    }
                }
                java.io.File f = new java.io.File("atlas_mip" + level + ".png");
                javax.imageio.ImageIO.write(img, "PNG", f);
                log(String.format("[ATLAS DUMP] level %d: %dx%d -> %s | near-black yet opaque texels: %d / %d (%.2f%%)",
                        level, w, h, f.getName(), darkOpaque, opaque,
                        opaque == 0 ? 0.0 : 100.0 * darkOpaque / opaque));
            }
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        } catch (Throwable t) {
            log("[ATLAS DUMP] failed: " + t);
        }
    }

    /**
     * Dispatches the colored-lighting flood-fill compute ({@code shadowcomp}): it reads {@code voxel_img} (the world
     * voxelized by this frame's shadow pass) + the previous frame's floodfill and writes the propagated colour into the
     * floodfill images (ping-pong by {@code framemod2}). A barrier first makes the shadow-pass {@code imageStore}s visible
     * to the compute; {@link GlslProgram#dispatch()} barriers after so the deferred chain sees the result. No-op if the
     * pack has no shadowcomp / no custom images.
     */
    /**
     * DIAGNOSTIC: the WSR ray-start alignment invariant.
     *
     * <p>The pack's WSR casts its ray from {@code playerPos}, which composite.glsl reconstructs from the depth via
     * {@code ViewToPlayer} = {@code gbufferModelViewInverse}. It then aligns the ray start to the voxel grid with
     * {@code worldSpaceRef.glsl:292}: {@code normalOffsetDist = 1.0 - fract(dot(playerPos + cameraPositionBestFract, n))}
     * — which only lands the start in the AIR cell above a surface if {@code playerPos + fract(cameraPosition)} equals
     * the world position up to an INTEGER. That holds only when playerPos is measured from the same origin as our
     * {@code cameraPosition} uniform, which we deliberately set to the player's FEET (see the camera-feet invariant:
     * Sodium's geometry is feet-relative via u_RegionOffset).
     *
     * <p>So {@code gbufferModelViewInverse[3].xyz} — the view origin (the eye) expressed in player space — MUST be
     * ~(0, eyeHeight, 0). If it is ~(0,0,0), playerPos is EYE-relative while cameraPosition is FEET → every WSR ray
     * start is off by fract(eyeHeight)=0.62 of a block, so the ray begins inside the floor's OWN voxel and the wet floor
     * reflects its own block texture instead of escaping to the sky.
     */
    private void diagWsrAlignment(float camFractY) {
        Matrix4fc mvi = this.modelViewInverse;
        float ox = mvi.m30(), oy = mvi.m31(), oz = mvi.m32();
        float eyeHeight = Minecraft.getMinecraft().getRenderViewEntity() != null
                ? Minecraft.getMinecraft().getRenderViewEntity().getEyeHeight() : Float.NaN;
        // What the pack actually feeds its fract(): the eye's own world Y, offset by an integer, must stay integral.
        float probe = oy + camFractY;
        String verdict;
        if (Math.abs(oy) < 0.05f) {
            verdict = "EYE-RELATIVE playerPos + FEET cameraPosition => MISMATCH of fract(eyeHeight)="
                    + String.format("%.3f", eyeHeight - Math.floor(eyeHeight)) + " *** WSR RAY STARTS INSIDE THE FLOOR ***";
        } else if (Math.abs(oy - eyeHeight) < 0.05f) {
            verdict = "feet-relative playerPos (matches our feet cameraPosition) => alignment OK";
        } else {
            verdict = "UNEXPECTED origin — playerPos is neither eye- nor feet-relative";
        }
        log(String.format("[Iris WSR align DIAG] gbufferModelViewInverse[3]=(%.3f, %.3f, %.3f) eyeHeight=%.3f"
                        + " cameraY(feet)=%.3f fract=%.3f | probe=fract(mvi.y+camFractY)=%.3f | %s",
                ox, oy, oz, eyeHeight, this.cameraY, camFractY, probe - Math.floor(probe), verdict));
    }

    private void runShadowComp() {
        if (this.shadowCompProgram == null || this.customImages.isEmpty()) {
            return;
        }
        GL42C.glMemoryBarrier(GL42C.GL_ALL_BARRIER_BITS); // flush the shadow-pass voxelization writes
        // A/B TEST: discard the WSR voxelization so every WSR ray misses (see DIAG_KILL_WSR).
        if (DIAG_KILL_WSR) {
            this.customImages.clearImagesNow("wsr_img", "wsr_lod_img");
            if (this.frameCounter == 200) {
                log("[Iris WSR A/B] DIAG_KILL_WSR active — wsr_img/wsr_lod_img wiped after voxelization;"
                        + " any remaining reflection is SSR + sky, NOT WSR");
            }
        }
        // DIAGNOSTIC: dump the WSR face-data SSBO once (frame 200) to tell stale (clear failing → item textures) from
        // real voxelized faces (clear OK → item textures come from those faces' UVs). Expensive, so just once.
        if (DIAG_SSBO && this.frameCounter > 0 && this.frameCounter % 300 == 0) {
            // Pair the SSBO's face radii with what BlockRenderer actually produced. If the SSBO still holds radii up to
            // 0.45 while BlockRenderer's own max is ~0.03, the bad faces come from somewhere other than block meshing —
            // and the quad count proves the check ran at all rather than being skipped on cached chunk meshes.
            log(String.format("[midTex DIAG] BlockRenderer quads examined=%d maxRad=%.4f (a 16px sprite = 0.0078)",
                    me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer.DIAG_MIDTEX_QUADS.get(),
                    me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer.DIAG_MIDTEX_MAXRAD.get()
                            / 10000.0f));
            // Pair the face count with the VOLUME occupancy: the ray trace now reports "exited without hitting anything",
            // so the question is whether wsr_img still holds the terrain. The pack's per-material exclusion only started
            // working once block.properties gave it real ids, and it drops every odd (non-solid) id — plus our
            // drawShadowCasters only submits BlockRenderLayer.SOLID, so CUTOUT (leaves/grass/flowers) is never voxelized
            // at all. If occupancy collapsed alongside the face count, too much is being excluded.
            this.customImages.diagCountNonZero("wsr_img");
            this.customImages.diagCountNonZero("wsr_lod_img");
            this.customImages.diagSsbo();
        }
        if (DIAG_DUMP_ATLAS && this.frameCounter == 100) {
            dumpAtlas();
        }
        // DIAGNOSTIC (connected glass): are stained-glass blocks in the colored-lighting voxel map with their
        // 200-215 ids? DoConnectedGlass's neighbour compare needs them; borders staying visible = the ids missing.
        if (DIAG_VOXEL_IDS && this.frameCounter % 600 == 200) {
            this.customImages.diagVoxelIdHistogram("voxel_img");
        }
        // DIAGNOSTIC: is the WSR voxel grid aligned with the real blocks? (One-shot; the readback is expensive.)
        if (DIAG_VOXEL_Y && this.frameCounter == 200) {
            float camFractY = (float) (this.cameraY - Math.floor(this.cameraY));
            diagWsrAlignment(camFractY);
            this.customImages.diagVoxelYHistogram("wsr_img", camFractY);
            // wsr_lod_img is the COARSE volume voxelRayTrace's OUTER loop tests
            // (`if (texelFetch(wsr_lod_sampler, ivec3(voxelPosRT1), 0).r > 0u)`). If it is empty — or allocated at a size
            // that disagrees with the shader's `sceneVoxelVolumeSize / 4`, making the imageStore land out of bounds — the
            // ray NEVER descends into the fine volume, so every WSR trace misses and the water reflection falls back to
            // the screen-space one that cuts off at the frame edge. Must be ~64x16x64 and non-empty at COLORED_LIGHTING=256.
            this.customImages.diagCountNonZero("wsr_lod_img");
        }
        // DIAGNOSTIC: every 120 frames, dump voxel_img (did voxelization run?) BEFORE the compute, and floodfill AFTER.
        boolean diag = DIAG_COLORED_LIGHTING && this.frameCounter % 120 == 0;
        if (diag) {
            this.customImages.diagCountNonZero("voxel_img");
        }
        this.shadowCompProgram.use();
        setSceneUniforms(this.shadowCompProgram);
        this.customImages.bindTo(this.shadowCompProgram);
        this.customImages.bindSsbos();
        this.shadowCompProgram.dispatch(); // glDispatchCompute(workGroups) + full memory barrier
        GlslProgram.unuse();
        // FLICKER PROBE: log both ping-pong floodfill totals + framemod2 for a window of CONSECUTIVE frames, right after
        // the compute wrote this frame's buffer. A static light should show both totals steady; if they alternate
        // high/low each frame, the ping-pong is the flicker (the terrain reads whichever framemod2 selects).
        if (DIAG_FLOODFILL_FLICKER && this.frameCounter >= 200 && this.frameCounter <= 212) {
            log(String.format("[Iris FLOODFILL] frame=%d framemod2=%d | floodfill_img total=%.1f | floodfill_img_copy total=%.1f",
                    this.frameCounter, this.frameCounter % 2,
                    this.customImages.diagTotalLum("floodfill_img"),
                    this.customImages.diagTotalLum("floodfill_img_copy")));
        }
        // Do the two ping-pong buffers agree PER-VOXEL? Totals matching doesn't prove it, and the terrain reads one or
        // the other by framemod2 — a local difference near a light is read alternately = flicker.
        if (DIAG_FLOODFILL_CMP && this.frameCounter >= 200 && this.frameCounter <= 210) {
            this.customImages.diagCompareFloodfill();
        }
        if (diag) {
            this.customImages.diagCountNonZero("floodfill_img");
            this.customImages.diagCountNonZero("floodfill_img_copy");
        }
    }

    /**
     * Fills the opaque-depth R32F colour texture (the composite's depthtex1) by rendering the pre-water depth copy
     * ({@code waterDepthTex}) through the depth-copy shader. Runs inside the fullscreen GL state, before the composite
     * chain. Without this, depthtex0==depthtex1 → z0==z1 → the pack never detects water → getWSR (WSR) never runs.
     */
    private void runOpaqueDepthCopy() {
        if (this.depthCopyProgram == null || this.targets == null) {
            return;
        }
        // waterDepthTex is normally snapshotted at the start of the TRANSLUCENT terrain pass — but Sodium skips that pass
        // outright when nothing translucent is in view, leaving a stale/uninitialised depth that would become depthtex1.
        // If it never ran this frame there is no water, so the current depth IS the opaque depth: snapshot it now.
        this.waterDepthCopiedByPass = this.waterDepthCopiedThisFrame; // for DIAG_DEPTH_CHAIN
        if (!this.waterDepthCopiedThisFrame) {
            this.targets.copyDepthForWater();
            this.waterDepthCopiedThisFrame = true;
        }
        this.targets.beginOpaqueDepthCopy();               // bind opaqueDepthFbo + waterDepthTex(unit1) + live depthTex(unit0)
        this.depthCopyProgram.use();
        this.depthCopyProgram.setInt("depthtex0", RenderTargets.DEPTHTEX_UNIT);
        this.depthCopyProgram.setInt("depthtexLive", 0); // live scene depth (incl. hand) → splice the hand into depthtex1
        drawFullscreenQuad();
        GlslProgram.unuse();
        // DIAGNOSTIC: dump the real values of the depthTex → waterDepthTex → depthtex1 chain, once.
        if (DIAG_DEPTH_CHAIN && this.frameCounter == 200) {
            log("[Iris depthChain DIAG] waterDepthCopiedThisFrame(before fallback)=" + this.waterDepthCopiedByPass
                    + " (false => Sodium skipped the translucent pass; the fallback in runOpaqueDepthCopy supplied it)");
            this.targets.diagDepthChain();
        }
    }

    /**
     * Restores the first-person hand's reflection fresnel (colortex4.a) to 1.0 after deferred1 stomped it, so the
     * composite reflection chain rejects the hand and leaves it matte (no scene/sky mirrored onto it → no see-through).
     * Reads colortex4 (front) + depthtex0, writes colortex4 (back) with a=1.0 where z0<=0.1, then flips it. No-op if the
     * program failed to compile. Keeps the shader pack untouched — the exclusion lives entirely in the Iris port.
     */
    private void runHandReflectFix() {
        if (this.handReflectFixProgram == null || this.targets == null) {
            return;
        }
        this.targets.bindCompositeReadTextures();           // colortex4 (front) on its unit + depthtex0 on unit 1
        this.handReflectFixProgram.use();
        setCompositeSamplers(this.handReflectFixProgram);   // points colortex4 / depthtex0 at the right units
        this.handReflectFixProgram.setFloat("handReflectMax", HAND_REFLECT_MAX);
        this.targets.bindCompositeTargets(new int[]{4});    // write colortex4 (back side)
        drawFullscreenQuad();
        this.targets.flipTargets(new int[]{4});             // make the fixed colortex4 the new front
        GlslProgram.unuse();
    }

    /** Runs one deferred/composite pass: reads all colortex (front) + depth/shadow, writes its RENDERTARGETS (back sides),
     * then flips those buffers so the next pass reads its output. */
    private void runPass(Pass pass) {
        this.targets.bindCompositeReadTextures();
        // texture.deferred.colortex3: for the DEFERRED stage only, the pack replaces the colortex3 SAMPLER with its
        // cloud noise texture (the same file as gbuffers gaux4 — reimaginedClouds' deferred branch reads its cloud
        // shapes through colortex3). Inert under CLOUD_STYLE 3 (unbound uses noisetex) but correct for every style.
        // No restore needed: the next pass's bindCompositeReadTextures rebinds the real colortex3.
        if (this.deferredColortex3Override && pass.name.startsWith("deferred")) {
            int cloudTex = this.targets.waterNormalTexture();
            if (cloudTex != 0) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + RenderTargets.COLORTEX_UNIT0 + 3);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, cloudTex);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
            }
        }
        // Enable mipmapped reads (colortexNMipmapEnabled) only for the duration of this pass — refresh the pyramid + switch
        // to a mipmap filter now, restore the crisp base filter right after the draw (see enableMipmapRead's note).
        for (int idx : pass.mipmaps) {
            this.targets.enableMipmapRead(idx);
        }
        pass.program.use();
        setSceneUniforms(pass.program);
        if (DIAG_EYE_WATER && this.isEyeInWater == 1 && this.frameCounter % 40 == 0) {
            int loc = org.lwjgl.opengl.GL20C.glGetUniformLocation(pass.program.handle(), "isEyeInWater");
            int val = -999;
            if (loc >= 0) {
                java.nio.IntBuffer vb = org.lwjgl.BufferUtils.createIntBuffer(1);
                org.lwjgl.opengl.GL20C.glGetUniformiv(pass.program.handle(), loc, vb);
                val = vb.get(0);
            }
            log("[EYE WATER PASS] " + pass.name + " isEyeInWater loc=" + loc + " val=" + val);
        }
        setCompositeSamplers(pass.program);
        this.customImages.bindTo(pass.program); // voxel/floodfill/wsr images (colored lighting reads them here)
        // DIAGNOSTIC: ask GL which texture unit every sampler of this pass actually points at, once. Unit 0 on anything
        // other than a deliberate unit-0 sampler means the pipeline never assigned it → it is silently reading whatever
        // sits on unit 0 (shadowtex0 here). See GlslProgram#samplerBindings.
        if (DIAG_SAMPLER_BINDINGS && this.frameCounter == 200) {
            StringBuilder sb = new StringBuilder("[Iris SAMPLER] " + pass.name + ":");
            for (java.util.Map.Entry<String, Integer> e : pass.program.samplerBindings().entrySet()) {
                sb.append(String.format(" %s=%d", e.getKey(), e.getValue()));
            }
            log(sb.toString());
        }
        this.targets.bindCompositeTargets(pass.targets);
        drawFullscreenQuad();
        for (int idx : pass.mipmaps) {
            this.targets.disableMipmapRead(idx);
        }
        this.targets.flipTargets(pass.targets);
        if (DIAG_PORTAL_LADDER && this.frameCounter % 120 == 0) {
            for (int t : pass.targets) {
                diagLadderRead(pass.name, t);
            }
        }
        if (DIAG_RIM_SCANLINE) {
            for (int t : pass.targets) {
                if (t == 0 || t == 3 || t == 5) { // 5 = the VL buffer deferred1 writes and composite1 adds to ct0
                    diagRimScanline(pass.name, t);
                }
            }
        }
    }

    /** DIAGNOSTIC (nether portal investigation): with the crosshair ON a surface, log the centre texel of every buffer
     * each pass writes, from the water pass onward — names the pass that changes the value. Off in normal builds. */
    private static final boolean DIAG_PORTAL_LADDER = false; // (rim hunt moved to DIAG_RIM_SCANLINE — aiming a 1-3px rim by hand was hopeless)

    /** DIAGNOSTIC (END island white rim): dumps a horizontal LUMINANCE SCANLINE (center ±120px, every 5px) of ct0/ct3
     * after every pass that writes them, every 4s. The user only has to put the island's edge ANYWHERE inside the
     * center band; the rim then shows as a bump in the profile and the first pass whose profile has the bump is the
     * culprit. Off in normal builds. */
    private static final boolean DIAG_RIM_SCANLINE = false; // RESOLVED 2026-07-27 (user-verified all dims) — see memory end-island-white-rim

    private void diagRimScanline(String label, int idx) {
        if (!DIAG_RIM_SCANLINE || this.targets == null || this.frameCounter % 240 != 0) {
            return;
        }
        // 1px resolution over ±40px: the artifact is a SINGLE-pixel bright line at the silhouette (the 5px stride
        // used for the broad rim hunt skipped right over it).
        int cx = this.targets.width() / 2, cy = this.targets.height() / 2;
        int x0 = Math.max(0, cx - 40);
        java.nio.FloatBuffer buf = this.targets.readColortexRegion(idx, x0, cy, 80, 1);
        if (buf == null) {
            return;
        }
        if (idx == 5) {
            // ct5 carries the VL-internals encode (R=melt fog, G=rayEnd/2000, B=lViewPos1/2000, A=vlFactorM) —
            // print each channel separately, with the far value the melt divides by.
            // Channel meaning depends on the active encode: DIAG_VL_ENCODE (composite1) = fog/rayEnd/lV1/vlFactorM;
            // DIAG_D1_ENCODE (deferred1) = postSSAO/postDoFog/skyFade/cloudDepth (-1 = sky-branch sentinel).
            String[] ch = {"R", "G", "B", "A"};
            for (int c = 0; c < 4; c++) {
                StringBuilder sb = new StringBuilder(String.format("[RIM] f=%d %-12s ct5 %-11s (far=%.0f):",
                        this.frameCounter, label, ch[c], this.far));
                for (int i = 0; i < 80; i++) {
                    sb.append(String.format(" %.3f", buf.get(i * 4 + c)));
                }
                log(sb.toString());
            }
            return;
        }
        StringBuilder sb = new StringBuilder(String.format("[RIM] f=%d %-12s ct%d:", this.frameCounter, label, idx));
        for (int i = 0; i < 80; i++) {
            sb.append(String.format(" %.3f", (buf.get(i * 4) + buf.get(i * 4 + 1) + buf.get(i * 4 + 2)) / 3.0F));
        }
        log(sb.toString());
        if ("gbuffer".equals(label)) {
            // The END sky measured 1.000 in the gbuffer = nothing drew it, so ct0 holds the CLEAR colour — which
            // should be the (dark) END fog colour. Print what we actually cleared with, to catch a white fogColor.
            log(String.format("[RIM] clear colour this frame: fog=(%.3f, %.3f, %.3f) sky=(%.3f, %.3f, %.3f)",
                    this.fogColorR, this.fogColorG, this.fogColorB, this.skyColorR, this.skyColorG, this.skyColorB));
        }
    }

    /** DIAGNOSTIC (END held-item flicker): objective per-frame METER — reads the final display buffer (ct3) at the
     * held item's screen position for a 6s window every 30s while in the END, so the oscillation is measured in
     * NUMBERS instead of perception. Walk continuously; remove with the investigation. */
    private static final boolean DIAG_ITEM_METER = false; // END flicker RESOLVED 2026-07-25 — see DIAG_HAND_MCBL note

    private void diagItemMeter() {
        if (!DIAG_ITEM_METER || !"world1/".equals(this.worldFolder) || this.targets == null) {
            return;
        }
        int phase = this.frameCounter % 600;
        if (phase < 300 || phase >= 420) {
            return;
        }
        int x = (int) (this.targets.width() * 0.78F);
        int y = (int) (this.targets.height() * 0.82F);
        float[] v = this.targets.readColortexTexel(3, x, y);
        log(String.format("[ITEM METER] f=%d ct3=(%.4f, %.4f, %.4f)", this.frameCounter, v[0], v[1], v[2]));
    }

    /** DIAGNOSTIC (END held-item flicker, probe v8): reads the hand's ct6 term export back BEFORE any composite runs
     * (front(6) at the top of endWorldRender = exactly what gbuffers_hand wrote — the v3/v4 probes read it at display
     * time, AFTER composites had flipped ct6, and measured a composite output instead). Hand pixels are marked with
     * A=0.789 by the injection; sway-proof because the whole lower-right region is scanned, not one fixed texel. */
    private static final boolean DIAG_HAND_TERMS = false; // END flicker RESOLVED 2026-07-25 — see DIAG_HAND_MCBL note

    private void diagHandTerms() {
        if (!DIAG_HAND_TERMS || !"world1/".equals(this.worldFolder) || this.targets == null) {
            return;
        }
        int phase = this.frameCounter % 600;
        if (phase < 300 || phase >= 420) {
            return;
        }
        int w = this.targets.width(), h = this.targets.height();
        // Texel y-origin is BOTTOM-left in GL, so the on-screen lower-right item lives in the LOW-y band. Log the MEAN
        // of each exported term per x-quarter of the band: the held item and the arm live in different x-slices, and
        // means match the "whole item pulses" percept where min/max only tracked single extreme pixels.
        int x0 = (int) (w * 0.55F);
        int rw = w - x0, rh = (int) (h * 0.45F);
        java.nio.FloatBuffer buf = this.targets.readColortexRegion(6, x0, 0, rw, rh);
        if (buf == null) {
            return;
        }
        int[] qn = new int[4];
        double[] qr = new double[4], qg = new double[4], qb = new double[4];
        for (int i = 0; i < rw * rh; i++) {
            float a = buf.get(i * 4 + 3);
            if (a < 0.784F || a > 0.794F) {
                continue; // not a hand pixel
            }
            int q = Math.min(3, (i % rw) * 4 / rw);
            qn[q]++;
            qr[q] += buf.get(i * 4);
            qg[q] += buf.get(i * 4 + 1);
            qb[q] += buf.get(i * 4 + 2);
        }
        double dx = this.cameraX - this.prevCameraX;
        double dy = this.cameraY - this.prevCameraY;
        double dz = this.cameraZ - this.prevCameraZ;
        StringBuilder sb = new StringBuilder(String.format("[HAND TERMS] f=%d camD=%.3f",
                this.frameCounter, Math.sqrt(dx * dx + dy * dy + dz * dz)));
        for (int q = 0; q < 4; q++) {
            if (qn[q] == 0) {
                sb.append(String.format(" q%d(n=0)", q + 1));
            } else {
                sb.append(String.format(" q%d(n=%d alb=%.4f em=%.4f fd=%.4f)",
                        q + 1, qn[q], qr[q] / qn[q], qg[q] / qn[q] * 4.0, qb[q] / qn[q]));
            }
        }
        log(sb.toString());
    }

    /** DIAGNOSTIC (END held-item flicker, probe v7): per-frame CONTENT of the camera-cell region of BOTH floodfill
     * buffers, plus the framemod2 parity and camera speed. The hand's pulsing lightVolume.a is READ from exactly this
     * region (SceneToVoxel(vec3(0.0)) = volume center), and GetLightVolume ping-pongs the two buffers by framemod2.
     * maxA pulsing in the CONTENT (correlated with camD>0) → the data is dirty, find the writer; content all-zero
     * while the item still pulses → the defect is the shader-side READ (unit/parity), not the data. */
    private static final boolean DIAG_FF_CENTER = false; // ANSWERED 2026-07-25: both buffers ZERO at the camera cell while moving

    private void diagFloodfillCenter() {
        if (!DIAG_FF_CENTER || !"world1/".equals(this.worldFolder) || this.customImages == null) {
            return;
        }
        int phase = this.frameCounter % 600;
        if (phase < 300 || phase >= 420) {
            return;
        }
        double dx = this.cameraX - this.prevCameraX;
        double dy = this.cameraY - this.prevCameraY;
        double dz = this.cameraZ - this.prevCameraZ;
        log(String.format("[FF CENTER] f=%d fm2=%d camD=%.3f | %s | %s",
                this.frameCounter, this.frameCounter % 2, Math.sqrt(dx * dx + dy * dy + dz * dz),
                this.customImages.diagCenterProbe("floodfill_img", 2),
                this.customImages.diagCenterProbe("floodfill_img_copy", 2)));
    }

    private void diagLadderRead(String label, int colortexIdx) {
        int cx = this.targets.width() / 2;
        int cy = this.targets.height() / 2;
        float[] v = this.targets.readColortexTexel(colortexIdx, cx, cy);
        log(String.format("[PORTAL LADDER] %-14s wrote ct%-2d @center = (%.3f, %.3f, %.3f, a=%.3f)",
                label, colortexIdx, v[0], v[1], v[2], v[3]));
    }

    /** Runs a deferred/composite chain: each pass reads all colortex (front) + depth/shadow, writes its RENDERTARGETS
     * (back sides), then flips those buffers so the next pass reads its output. */
    private void runChain(List<Pass> passes) {
        for (Pass pass : passes) {
            this.targets.bindCompositeReadTextures();
            for (int idx : pass.mipmaps) {
                this.targets.enableMipmapRead(idx);
            }
            pass.program.use();
            setSceneUniforms(pass.program);
            setCompositeSamplers(pass.program);
            this.targets.bindCompositeTargets(pass.targets);
            drawFullscreenQuad();
            for (int idx : pass.mipmaps) {
                this.targets.disableMipmapRead(idx);
            }
            this.targets.flipTargets(pass.targets);
        }
    }

    /** Sets the colortex/depthtex/shadowtex sampler-unit uniforms for a composite pass (see RenderTargets unit layout). */
    private void setCompositeSamplers(GlslProgram p) {
        for (int i = 0; i < RenderTargets.NUM_COLORTEX; i++) {
            p.setInt("colortex" + i, RenderTargets.COLORTEX_UNIT0 + i);
        }
        // Legacy colortex aliases (gcolor/gnormal/composite/gaux1-4 = colortex0-7).
        p.setInt("gcolor", RenderTargets.COLORTEX_UNIT0);
        p.setInt("gnormal", RenderTargets.COLORTEX_UNIT0 + 2);
        p.setInt("composite", RenderTargets.COLORTEX_UNIT0 + 3);
        p.setInt("gaux1", RenderTargets.COLORTEX_UNIT0 + 4);
        p.setInt("gaux2", RenderTargets.COLORTEX_UNIT0 + 5);
        p.setInt("gaux3", RenderTargets.COLORTEX_UNIT0 + 6);
        p.setInt("gaux4", RenderTargets.COLORTEX_UNIT0 + 7);
        // Depth maps (units 0/1 only, compat quirk) + their aliases.
        p.setInt("depthtex0", RenderTargets.DEPTHTEX_UNIT);
        // depthtex1/depthtex2 = the OPAQUE-only depth (R32F colour on a high unit) so z0 != z1 at water pixels → the pack
        // detects water and runs getWSR (world-space reflections) instead of the screen-space fallback that cut off at edges.
        p.setInt("depthtex1", RenderTargets.OPAQUE_DEPTH_UNIT);
        // depthtex2 strictly means "no translucents AND no hand" (ours has the hand spliced in, like depthtex1) — but
        // MEASURED 2026-07-22: Complementary never samples scene depth through depthtex2 at all. Its only uses are the
        // PixelCraft palette LUT (`texture.composite.depthtex2 = palettes/paletteN.png` + texelFetch in pixelCraft.glsl),
        // i.e. a repurposed sampler slot for a feature (custom composite texture overrides) this port doesn't implement.
        // So the alias below is inert for this pack; build a real no-hand copy only if a pack ever reads depth from it.
        p.setInt("depthtex2", RenderTargets.OPAQUE_DEPTH_UNIT);
        p.setInt("depthtex", RenderTargets.DEPTHTEX_UNIT);
        p.setInt("gdepth", RenderTargets.DEPTHTEX_UNIT);
        p.setInt("shadowtex0", RenderTargets.SHADOWTEX_UNIT);
        // shadowtex1 = the OPAQUE-ONLY depth on its own texture object. It used to alias shadowtex0, which made the pack's
        // "blocked in tex0 but clear in tex1 ⇒ a translucent blocked the light" test always false — killing both coloured
        // shadows and the underwater god rays — and left its shadow2D a mismatched read whenever composite1 flipped
        // shadowtex0 to raw-depth mode. See RenderTargets.ensureShadow / snapshotOpaqueShadowDepth.
        // With UNDERWATER_SHAFTS off nothing ever fills the opaque-only copy, and an all-far shadowtex1 would make the
        // pack treat EVERY shadow as a coloured/translucent one — so alias it back onto shadowtex0, exactly as before.
        // UNIT 47 (the same one the gbuffers use), NOT a unit in the composite-read range: the first home for this was
        // SHADOWTEX1_UNIT = OPAQUE_DEPTH+3 = 27, which COLLIDES with the custom-images sampler range — runPass binds the
        // composite read textures first and customImages.bindTo() then re-bound unit 27 to endcrystal_sampler's image, so
        // composite1's shadow2D(shadowtex1) sampled the END CRYSTAL TEXTURE through a depth-compare sampler (undefined →
        // 0 on NVIDIA) and the underwater god-ray branch never fired. Caught by the GL sampler audit:
        // "endcrystal_sampler=27 shadowtex1=27". Unit 47 is bound once per frame in beginWorldRender and nothing in the
        // composite chain touches the 40..47 range.
        p.setInt("shadowtex1", UNDERWATER_SHAFTS ? RenderTargets.TERRAIN_SHADOWTEX1_UNIT : RenderTargets.SHADOWTEX_UNIT);
        p.setInt("shadow", RenderTargets.SHADOWTEX_UNIT);
        p.setInt("watershadow", RenderTargets.SHADOWTEX_UNIT);
        // Match the shadow map's hardware-compare mode to how THIS program declared shadowtex0. Complementary declares it
        // `sampler2D` in composite1 (the volumetric light reads raw depth with texelFetch) and `sampler2DShadow`
        // everywhere else (shadow2D) — see lib/uniforms.glsl. Reading a depth texture through the sampler kind that does
        // not match its GL_TEXTURE_COMPARE_MODE is undefined and yields 0 on NVIDIA, which is why the light shafts were
        // missing entirely: every texelFetch'd shadow sample came back 0 → "in shadow" → the ray march summed to zero.
        // Programs that declare no shadowtex0 leave the mode alone (compare on, the state terrain lighting needs).
        if (this.targets != null && p.hasSampler("shadowtex0")) {
            this.targets.setShadowCompareMode(p.isShadowSampler("shadowtex0"));
        }
        // The shadow COLOUR buffers, not the shadow depth texture. Both are declared `sampler2D` by the pack, so aiming
        // them at a depth texture (which additionally sat in hardware-compare mode) was an undefined read. shadowcolor1's
        // ALPHA is the caster height the scene-aware light shafts decode to grow vlFactor — starving it pinned vlFactor at
        // 0 and left the shafts at under 1/8 intensity. shadowcolor0 carries the stained-glass shadow tint.
        p.setInt("shadowcolor0", RenderTargets.SHADOWCOLOR0_UNIT);
        p.setInt("shadowcolor1", RenderTargets.SHADOWCOLOR1_UNIT);
        // Real procedural noise texture (RenderTargets.NOISETEX_UNIT) — clouds, the sun-glare halo, dithering + water all
        // sample noisetex; feeding a colour buffer instead (the old fallback) collapsed the atmosphere (no clouds/glare).
        p.setInt("noisetex", RenderTargets.NOISETEX_UNIT);
        // The block atlas, for the pack's world-space reflections. getWSR is called FROM the composite (reflections.glsl:85
        // for translucents, reflectionBackground.glsl:121 for solids), and its getShadedReflection colours every reflected
        // voxel with `texture2DLod(textureAtlas, ...)` + sizes its UVs with `textureSize(textureAtlas, 0)`. So the composite
        // needs this binding exactly as much as the water/terrain programs do — it was the one program that missed it.
        //
        // Left unset the sampler defaulted to unit 0, which bindCompositeReadTextures fills with shadowtex0: a DEPTH
        // texture in compare mode, so reading it through a plain sampler2D yields 0 → `if (color.a < 0.0041) return
        // vec4(-1.0)` rejected EVERY voxel the ray hit → the ray marched out of the volume and getWSR returned vec4(0.0)
        // ("miss") for every pixel on screen. WSR therefore contributed nothing anywhere, and water reflections were left
        // with only their screen-space part — the diagonal cutoff at the frame edge that WSR exists to fill in.
        p.setInt("textureAtlas", RenderTargets.TEXTURE_ATLAS_UNIT);
    }

    // --- fullscreen pass plumbing ---

    private void beginFullscreenState() {
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.disableBlend();
        GlStateManager.disableCull();
        GlStateManager.disableAlpha();
        GlStateManager.disableLighting();
        GlStateManager.disableFog();
        GlStateManager.enableTexture2D();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        // Identity matrices so the pack's ftransform() maps the NDC quad straight through.
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
    }

    private void endFullscreenState() {
        // Composite read units (2..) are disabled by unbindCompositeReadTextures; here just make sure the active unit is 0
        // with RAW GL (GlStateManager's cache still believes unit 0 is active after our raw switches, so its
        // setActiveTexture(0) would be a no-op and leave GL stuck on a higher unit, desyncing MC's later texturing).
        GL13.glActiveTexture(GL13.GL_TEXTURE0);

        GlslProgram.unuse();
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.popMatrix();
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.popMatrix();
        GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
    }

    /**
     * Feeds a pack program's non-sampler scene uniforms (matrices, camera, celestial, time/weather, viewport/planes/
     * colours). Shared by {@link #setCommonUniforms} (composite/deferred passes) and {@link #applyTerrainUniforms} (the
     * transformed pack terrain program), which each set their own sampler-unit layout separately.
     */
    public void setSceneUniforms(GlslProgram program) {
        // Matrices
        program.setMatrix4("gbufferProjection", this.gbufferProjection);
        program.setMatrix4("gbufferProjectionInverse", this.projectionInverse);
        program.setMatrix4("gbufferModelView", this.gbufferModelView);
        program.setMatrix4("gbufferModelViewInverse", this.modelViewInverse);
        // Previous frame's matrices (real, tracked per frame) so temporal reprojection (motion blur, TAA) is correct.
        program.setMatrix4("gbufferPreviousProjection", this.prevProjection);
        program.setMatrix4("gbufferPreviousModelView", this.prevModelView);
        // Shadow matrices (camera-relative world → shadow clip); the composite folds in gbufferModelViewInverse.
        program.setMatrix4("shadowProjection", this.shadowProjection);
        program.setMatrix4("shadowModelView", this.shadowModelView);
        program.setMatrix4("shadowModelViewProjection", this.shadowModelViewProjection);
        // Inverses — read only by the pack's lightweight shadow.vsh (position round-trip). Inert no-ops on other programs.
        program.setMatrix4("shadowModelViewInverse", this.shadowModelViewInverse);
        program.setMatrix4("shadowProjectionInverse", this.shadowProjectionInverse);

        // labPBR resource-pack textures this port never has: point them at the 1×1 defaults (bound once per frame in
        // beginWorldRender) instead of the unit-0 block atlas. Unassigned, customEmission.glsl read the sprite's own
        // mip-averaged alpha as labPBR emission → held items pulsed ~10× emissive in the dark END while moving (the
        // END hand flicker; v11 measured pow2(emission) spiking with scene/shadow/blocklight all frozen).
        program.setInt("specular", RenderTargets.DEFAULT_SPECULAR_UNIT);
        program.setInt("normals", RenderTargets.DEFAULT_NORMALS_UNIT);

        // Camera + celestial
        program.setVec3("cameraPosition", (float) this.cameraX, (float) this.cameraY, (float) this.cameraZ);
        program.setVec3("previousCameraPosition", (float) this.prevCameraX, (float) this.prevCameraY, (float) this.prevCameraZ);
        program.setVec3("sunPosition", this.sunPos[0], this.sunPos[1], this.sunPos[2]);
        program.setVec3("moonPosition", this.moonPos[0], this.moonPos[1], this.moonPos[2]);
        program.setVec3("upPosition", this.upPos[0], this.upPos[1], this.upPos[2]);
        program.setVec3("shadowLightPosition", this.shadowLightPos[0], this.shadowLightPos[1], this.shadowLightPos[2]);
        // Iris `sunAngle` is NOT MC's celestialAngle: it is frac(celestialAngle + 0.25) (celestialAngle=0 at noon →
        // sunAngle=0.25). Complementary derives its whole day/night `timeAngle` (and thus the sun vector + sunVisibility
        // that gate ALL sun/ambient light) from sunAngle, so feeding raw celestialAngle put the sun below the horizon at
        // midday → sunVisibility=0 → the terrain rendered night-dark in broad daylight. `shadowAngle` folds night onto
        // day (the moon casts the other half), staying in [0,0.5).
        float sunAngle = this.celestialAngle < 0.75F ? this.celestialAngle + 0.25F : this.celestialAngle - 0.75F;
        float shadowAngle = sunAngle < 0.5F ? sunAngle : sunAngle - 0.5F;
        program.setFloat("sunAngle", sunAngle);
        program.setFloat("shadowAngle", shadowAngle);
        program.setFloat("sunBrightness", this.sunBrightness);

        // Time + weather
        program.setInt("worldTime", this.worldTime);
        program.setInt("worldDay", this.worldDay);
        program.setInt("moonPhase", this.moonPhase);
        program.setInt("frameCounter", this.frameCounter);
        // framemod8 (frameCounter % 8) drives the pack's TAA jitter sample pattern + the composite TAA un-jitter/accumulate.
        // Left unset (0) the jitter was frozen AND TAA couldn't converge → the animated cloud/water dither flickered.
        program.setFloat("framemod8", (float) (this.frameCounter % 8));
        // framemod2 (frameCounter % 2): the colored-lighting flood-fill compute (shadowcomp) ping-pongs the two floodfill
        // images by this parity — even frames read floodfill/write floodfill_copy, odd frames the reverse.
        program.setFloat("framemod2", (float) (this.frameCounter % 2));
        program.setFloat("frameTimeCounter", this.frameTimeCounter);
        program.setFloat("frameTime", this.frameTime);
        program.setFloat("centerDepthSmooth", this.centerDepthSmooth);
        program.setFloat("frameTimeSmooth", this.frameTimeSmooth);
        // Handheld light — see gatherHeldLight. heldBlockLightValue is the held block's own light level (0..15); the pack
        // fades it by distance itself. heldItemId picks the light's colour out of item.properties.
        // ALL FOUR ARE `uniform int` in the pack (lib/uniforms.glsl:26-29) — glUniform1f on an int uniform is
        // GL_INVALID_OPERATION, which silently leaves the uniform unset (the light simply never appeared).
        program.setInt("heldBlockLightValue", this.heldBlockLightValue);
        program.setInt("heldBlockLightValue2", this.heldBlockLightValue2);
        program.setInt("heldItemId", this.heldItemId);
        program.setInt("heldItemId2", this.heldItemId2);
        program.setVec3("relativeEyePosition", this.relativeEyePosition.x, this.relativeEyePosition.y, this.relativeEyePosition.z);
        program.setFloat("rainStrength", this.rainStrength);
        // rainFactor / wetness / inRainy / thunderFactor are TEMPORALLY SMOOTHED (updateWeatherSmoothing, once per frame):
        // wetness by the pack's const wetnessHalflife/drynessHalflife (OptiFine's mechanism), the rest by the
        // shaders.properties `smooth(<id>, <value>, <fadeUp>, <fadeDown>)` custom uniforms. Feeding the raw rainStrength
        // here (as we used to) made the wet-ground reflection pop on/off the instant the weather changed, instead of the
        // puddles forming over RAIN_FADE_TIMER and the ground drying out over the halflife.
        program.setFloat("rainFactor", this.rainFactor);
        program.setFloat("wetness", this.wetness);
        program.setFloat("inRainy", this.inRainySmooth); // rain-biome flag; gates rain-puddle formation
        program.setFloat("inDry", this.inDrySmooth);     // no-precipitation biome flag (desert atmosphere tweaks)
        program.setFloat("inSnowy", this.inSnowySmooth); // snow-biome flag (snowy atmosphere tweaks)
        // EP nether-biome mixers (netherColor's NETHER_COLOR_MODE == 3 blend). 1.12.2 has ONE nether biome (Hell), the
        // equivalent of nether_wastes — so it is 1 in the nether and every 1.16+ biome stays 0. The pack's own uniform
        // initializers already default to exactly this; set them anyway so the driver-side state is explicit.
        boolean nether = "world-1/".equals(this.worldFolder);
        program.setFloat("inNetherWastes", nether ? 1.0F : 0.0F);
        program.setFloat("inCrimsonForest", 0.0F);
        program.setFloat("inWarpedForest", 0.0F);
        program.setFloat("inBasaltDeltas", 0.0F);
        program.setFloat("inSoulValley", 0.0F);
        // EP end-biome mixer (shaders.properties:598: smoothed "eye is in a VANILLA end biome" — modded end biomes
        // blend endSkyColor toward the world fog colour via END_SKY_FOG_INFLUENCE). 1.12.2's End has the single
        // vanilla biome, so this is exactly 1 in the End and 0 elsewhere; matches the pack's own initializer (1.0)
        // and silences the last unset-uniform line in the END health report.
        program.setFloat("inVanillaEnd", "world1/".equals(this.worldFolder) ? 1.0F : 0.0F);
        // EuphoriaPatches' END FLASH driver uniforms (fed by the EP companion mod on real Iris; the event system is
        // not ported). endFlashPosition MUST be a non-zero vector: the pack normalizes it, and the unset (0,0,0)
        // turned into NaN that poisoned ambientColorM → sceneLighting in the END — measured as the held-item lighting
        // flickering while moving (NaN through TAA/undefined blending). The author's own fallback direction
        // (lib/uniforms.glsl:138) with intensity 0 = no flash events, no NaN, stable ambient.
        program.setVec3("endFlashPosition", 0.0F, 0.0F, -100.0F);
        program.setFloat("endFlashIntensityM", 0.0F);
        program.setFloat("thunderStrength", this.thunderStrength);
        program.setFloat("thunderFactor", this.thunderFactor);
        program.setInt("isEyeInWater", this.isEyeInWater);
        // Camera-relative position of a live lightning bolt (w=1 while one exists) — the pack's lightning flash.
        program.setVec4("lightningBoltPosition",
                this.lightningBoltPos[0], this.lightningBoltPos[1], this.lightningBoltPos[2], this.lightningBoltPos[3]);

        // Eye brightness + altitude (cave/eye-in-cave detection, ambient block light), vision effects, screen brightness.
        program.setIVec2("atlasSize", this.atlasWidth > 0 ? this.atlasWidth : 512, this.atlasHeight > 0 ? this.atlasHeight : 512);
        // Point the pack's world-space-reflection texture sampler at the block atlas unit (bound once per frame in
        // beginWorldRender). Left unset it defaulted to unit 0 → WSR reflections sampled item/GUI textures (garbage).
        program.setInt("textureAtlas", RenderTargets.TEXTURE_ATLAS_UNIT);
        program.setIVec2("eyeBrightness", this.eyeBrightnessBlock, this.eyeBrightnessSky);
        // eyeBrightnessSmooth: OptiFine semantics — eased with eyeBrightnessHalflife (default 10 ticks). Was fed
        // the RAW value, so everything keyed on it snapped at cave mouths instead of adapting like real Iris.
        program.setIVec2("eyeBrightnessSmooth",
                Math.round(this.eyeBrightnessSmoothX < 0.0F ? this.eyeBrightnessBlock : this.eyeBrightnessSmoothX),
                Math.round(this.eyeBrightnessSmoothY < 0.0F ? this.eyeBrightnessSky : this.eyeBrightnessSmoothY));
        // eyeBrightnessM: the pack's smooth(eyeBrightness.y/240, 5, 5) custom uniform — now actually smoothed.
        program.setFloat("eyeBrightnessM", this.eyeBrightnessMSmoothed);
        program.setFloat("eyeBrightnessM2", this.eyeBrightnessM2); // smoothed "eye in FULL skylight" flag (2s/2s)
        program.setFloat("eyeAltitude", (float) this.cameraY);
        // cameraPositionFract: the pack's `cameraPositionBestFract = cameraPositionFract` (common.glsl:878) — the voxel
        // anchor the end-portal beam + end-crystal voxelization align with. Unset (0) their alignment was off by the
        // camera's sub-block offset. Fract of the SAME feet-based cameraPosition fed above (camera-feet invariant).
        program.setVec3("cameraPositionFract",
                (float) (this.cameraX - Math.floor(this.cameraX)),
                (float) (this.cameraY - Math.floor(this.cameraY)),
                (float) (this.cameraZ - Math.floor(this.cameraZ)));
        // cloudHeight: the pack shifts every cloud altitude by (cloudHeight - 192); 192 is the 1.18+ vanilla cloud
        // height its defaults (and our 1.20.1 parity reference) assume — NOT 1.12.2's 128, which would sink the clouds.
        program.setFloat("cloudHeight", 192.0F);
        program.setFloat("darknessLightFactor", 0.0F); // the 1.19+ Darkness effect does not exist on 1.12.2
        program.setInt("isRightHanded", this.isRightHanded); // pack `uniform bool`; glUniform1i is the legal setter
        program.setFloat("waterAltitude", this.waterAltitude); // water-surface Y for DARKER_DEPTH_OCEANS depth gauge
        program.setInt("bedrockLevel", 0);
        program.setFloat("screenBrightness", this.screenBrightness);
        program.setFloat("nightVision", this.nightVision);
        program.setFloat("blindness", this.blindness);
        program.setFloat("darknessFactor", this.darknessFactor);
        program.setFloat("maxBlindnessDarkness", Math.max(this.blindness, this.darknessFactor));

        // Viewport + planes + colours
        program.setFloat("viewWidth", this.targets.width());
        program.setFloat("viewHeight", this.targets.height());
        program.setFloat("aspectRatio", (float) this.targets.width() / Math.max(1, this.targets.height()));
        program.setFloat("near", this.near);
        program.setFloat("far", this.far);
        program.setVec3("fogColor", this.fogColorR, this.fogColorG, this.fogColorB);
        program.setVec3("skyColor", this.skyColorR, this.skyColorG, this.skyColorB);
        program.setFloat("fogStart", this.fogStart);
        program.setFloat("fogEnd", this.fogEnd);
    }

    /** @return the light-POV shadow depth texture id, for the terrain program to bind + sample (real shadows). */
    public int shadowDepthTextureId() {
        return this.targets == null ? 0 : this.targets.shadowDepthTexture();
    }

    /**
     * Feeds the transformed pack terrain program (Sodium's {@code ChunkShaderInterface}, wrapped as a {@link GlslProgram})
     * the same scene uniforms the composites get, so the pack's in-gbuffer lighting (Complementary lights terrain in
     * {@code gbuffers_terrain} itself, not purely deferred) has real sun/light/time values instead of zeros → not black.
     * Sampler layout differs from the composite passes: the block atlas stays on unit 0 and the lightmap on unit 1 (set by
     * ChunkShaderInterface), and the shadow map is bound to {@code shadowUnit} by the caller.
     */
    /**
     * Binds + points the translucent {@code gbuffers_water} program's scene samplers (colortex0..N, depthtex, noisetex) at
     * the live scene buffers, so its SSR reflections / refraction / water fog read the rendered world instead of an unset
     * sampler (unit 0 = the block atlas → the "icon grid" garbage). Called only for the TRANSLUCENT/water pass.
     */
    public void applyWaterSceneSamplers(GlslProgram program) {
        if (this.targets == null) {
            return;
        }
        diagWaterGlError("water samplers (before bind)");
        this.targets.bindWaterSceneTextures();
        diagWaterGlError("bindWaterSceneTextures");
        for (int i = 0; i < RenderTargets.NUM_COLORTEX; i++) {
            program.setInt("colortex" + i, RenderTargets.WATER_COLORTEX_UNIT0 + i);
        }
        program.setInt("gcolor", RenderTargets.WATER_COLORTEX_UNIT0);
        // The injected current-frame reflection source (see injectCurrentFrameWaterReflection): the same unit as the
        // water's colortex0, where bindWaterSceneTextures puts waterReflectTex = THIS frame's opaque scene copy.
        program.setInt("yumelium_reflectColor", RenderTargets.WATER_COLORTEX_UNIT0);
        program.setInt("gaux1", RenderTargets.WATER_COLORTEX_UNIT0 + 4);
        program.setInt("gaux2", RenderTargets.WATER_COLORTEX_UNIT0 + 5);
        program.setInt("gaux3", RenderTargets.WATER_COLORTEX_UNIT0 + 6);
        program.setInt("gaux4", RenderTargets.WATER_COLORTEX_UNIT0 + 7);
        program.setInt("depthtex0", RenderTargets.WATER_DEPTHTEX_UNIT);
        program.setInt("depthtex1", RenderTargets.WATER_DEPTHTEX_UNIT);
        program.setInt("depthtex", RenderTargets.WATER_DEPTHTEX_UNIT);
        program.setInt("gdepth", RenderTargets.WATER_DEPTHTEX_UNIT);
        program.setInt("noisetex", RenderTargets.WATER_NOISETEX_UNIT);
        // WSR reflection colour source: the block atlas (bound on its dedicated unit in beginWorldRender). Without this the
        // water reflection sampled unit 0 → random item/GUI textures.
        program.setInt("textureAtlas", RenderTargets.TEXTURE_ATLAS_UNIT);
        // Custom images: the water reflection reads the WSR voxel map (getWSR samples wsr_img/wsr_sampler).
        this.customImages.bindTo(program);
    }

    public void applyTerrainUniforms(GlslProgram program, int shadowUnit) {
        setSceneUniforms(program);
        program.setInt("shadowtex0", shadowUnit);
        // shadowtex1 must be the OPAQUE-ONLY depth for the gbuffers too, not just the composites: shadowSampling.glsl's
        // coloured-shadow branch is `if (shadow0 < 1.0) { if (shadow2D(shadowtex1,...) > 0.9999) shadowcol=shadowcolor0 }`.
        // Aliased to shadowtex0 (which now contains the WATER surface), the seabed's shadow1 compared against the water
        // depth above it → always 0 → branch dead → shadowcol=0 → `return shadowcol*(1-shadow0)+shadow0` = 0 = pitch-black
        // seabed. With the opaque-only copy the self-compare passes exactly as it did before water became a caster.
        program.setInt("shadowtex1", UNDERWATER_SHAFTS ? RenderTargets.TERRAIN_SHADOWTEX1_UNIT : shadowUnit);
        program.setInt("shadow", shadowUnit);
        program.setInt("watershadow", shadowUnit);
        // Shadow-pass vertex distortion controls (see TerrainShaderTransformer): warp on only while rendering the shadow
        // map, with the pack's exact bias so the stored depths match the terrain lookup's distorted coords.
        program.setFloat("iris_shadowPass", this.shadowPass ? 1.0F : 0.0F);
        program.setFloat("iris_shadowMapBias", this.shadowMapBiasThisFrame);
        // renderStage: the pack's voxelizers gate on it. UpdateVoxelMap (colored lighting) wants any TERRAIN stage — left
        // unset (0 = NONE) nothing voxelized at all. But UpdateSceneVoxelMap (the WSR volume) accepts ONLY
        // SOLID/CUTOUT/CUTOUT_MIPPED, and UpdatePuddleVoxelMap accepts ONLY TRANSLUCENT — so the stage must be HONEST per
        // pass, not a blanket 8.
        //
        // Reporting TERRAIN_SOLID for the translucent pass made the pack voxelize WATER into the WSR volume, which real
        // Iris never does. Water quads also carry no mc_midTexCoord/at_midBlock (FluidRenderer doesn't write the Iris
        // vertex attributes), so those voxels landed off-centre with a garbage textureRad — and getShadedReflection then
        // sampled the atlas at ~(0,0), got a transparent texel, and rejected the hit. Every reflection ray leaving the
        // water hit that garbage first and rejected its way out of the volume, so getWSR returned "miss" everywhere and
        // water reflections were left with only their screen-space part (the diagonal cutoff at the frame edge).
        int stage = this.terrainTranslucentPass ? 17 : 8; // TERRAIN_TRANSLUCENT : TERRAIN_SOLID
        program.setInt("renderStage", stage);
        // Verify the stage is honest per pass, and specifically during the shadow pass (where the voxelizers run). This
        // caught the ordering bug: setupState() reads the flag, so it has to be set before the bind, not after.
        if (DIAG_RENDER_STAGE && this.frameCounter == 200) {
            log("[renderStage DIAG] shadowPass=" + this.shadowPass + " translucent=" + this.terrainTranslucentPass
                    + " -> renderStage=" + stage + " (WSR voxelizer wants 8/9/10; puddle voxelizer wants 17)");
        }
        // gaux4 = cloud-water.png (bound in beginWorldRender): the pack's `texture.gbuffers.gaux4 = cloud-water.png`. The
        // terrain's wetness/puddle cloud REFLECTION reads gaux4.b as cloud-shape noise; unset it defaulted to unit 0 = the
        // block atlas → the reflection showed "all the random textures". (The translucent water program sets its own gaux4.)
        program.setInt("gaux4", RenderTargets.CLOUD_WATER_UNIT);
        // noisetex (bound in beginWorldRender). Sodium's ChunkShaderInterface binds only u_BlockTex/u_LightTex + the
        // tex/gtexture/lightmap aliases, so this sampler was left at its default 0 = the BLOCK ATLAS. gbuffers_terrain's
        // RAIN_PUDDLES code derives the puddle SHAPE from it (pFormNoise → puddleMixer) and the ripple normal, so the
        // puddles took the shape of the atlas's block sprites + item icons, lying flat in the ground plane — the "item
        // textures on the wet ground" (never a reflection: they were never mirrored). Same class of bug as gaux4 above.
        program.setInt("noisetex", RenderTargets.TERRAIN_NOISETEX_UNIT);
        // Scene buffers the terrain FRAGMENT samples (colortex10/colortex18/shadowcolor0). Bound only for the water pass
        // before, so on the solid pass they defaulted to unit 0 = the block atlas: ApplyMultiColoredBlocklight read
        // colortex10 = the atlas at screen coords and mixed it into the surface's blocklight, so lit blocks near a light
        // source wore tinted "item textures" and the atlas RGB (added as light) blew out auto-exposure. Same unit-0 trap
        // as gaux4/noisetex above; bind the real buffers on their dedicated high units.
        this.targets.bindTerrainSceneTextures();
        program.setInt("colortex10", RenderTargets.TERRAIN_COLORTEX10_UNIT);
        program.setInt("colortex18", RenderTargets.TERRAIN_COLORTEX18_UNIT);
        program.setInt("shadowcolor0", RenderTargets.TERRAIN_SHADOWCOLOR0_UNIT);
        // Custom images: the terrain fragment reads colored light (floodfill), and the shadow VERTEX voxelizes into
        // voxel_img/wsr_img (UpdateVoxelMap/UpdateSceneVoxelMap). No-op when the pack declared none.
        this.customImages.bindTo(program);
        // Terrain covers most of the screen, so a sampler left on unit 0 here is the most damaging version of this port's
        // most repeated bug. Audited once per program, from GL's own state — the same check that immediately found five
        // more unbound samplers on the particle program.
        // Latched per pass kind: the shadow program runs first and would otherwise consume the one-shot, hiding the CAMERA
        // terrain program — the one that actually covers the screen.
        if (this.shadowPass ? this.shadowTerrainAuditPending : this.cameraTerrainAuditPending) {
            if (this.shadowPass) {
                this.shadowTerrainAuditPending = false;
            } else {
                this.cameraTerrainAuditPending = false;
            }
            auditSamplerUnits("gbuffers_terrain" + (this.shadowPass ? " (shadow pass)" : " (camera pass)"), program);
        }
    }

    /** One-shot latches for the terrain sampler audit (see applyTerrainUniforms). */
    private boolean shadowTerrainAuditPending = true;
    private boolean cameraTerrainAuditPending = true;

    private void logColor0Readback() {
        logColortex(0);
    }

    /** DIAGNOSTIC (horizon band): logs the linear RGB of colortex{idx}'s FRONT down the screen-centre column at 30%..70%
     * of height, so we can see where the far horizon turns grey (raw world pass vs after the composite chain). One full
     * glGetTexImage; only called on a HORIZON_DIAG frame. */
    private void logHorizonColumn(String label, int idx) {
        try {
            int w = this.targets.width(), h = this.targets.height();
            if (w <= 0 || h <= 0) {
                return;
            }
            java.nio.FloatBuffer buf = org.lwjgl.BufferUtils.createFloatBuffer(w * h * 4);
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GlStateManager.bindTexture(this.targets.front(idx));
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_FLOAT, buf);
            int x = w / 2;
            // Also read the scene depth down the same column, so we can tell whether a given band is terrain/water
            // (depth < 1.0) or sky (depth == 1.0) — the grey horizon region's nature decides the fix.
            java.nio.FloatBuffer dbuf = org.lwjgl.BufferUtils.createFloatBuffer(w * h);
            GlStateManager.bindTexture(this.targets.depthTexture());
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, dbuf);
            StringBuilder sb = new StringBuilder("[HORIZON DIAG] " + label + " ct" + idx + " centre-col (top→bottom): ");
            for (int frac = 40; frac <= 62; frac += 2) {
                int yTop = h * frac / 100;
                int yTex = h - 1 - yTop;          // glGetTexImage row 0 = bottom of the image
                int p = yTex * w + x;
                sb.append(String.format("%d%%=(%.2f,%.2f,%.2f)z%.4f ", frac,
                        buf.get(p * 4), buf.get(p * 4 + 1), buf.get(p * 4 + 2), dbuf.get(p)));
            }
            log(sb.toString());
        } catch (Throwable ignored) {
        }
    }

    /** DIAGNOSTIC: logs just the avg+max of colortexN (front) with a label — for tracing where a value diverges across the
     * composite chain in a single frame. */
    private void logColortexAvg(String label, int idx) {
        try {
            int w = this.targets.width(), h = this.targets.height();
            if (w <= 0 || h <= 0) return;
            java.nio.FloatBuffer buf = org.lwjgl.BufferUtils.createFloatBuffer(w * h * 4);
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GlStateManager.bindTexture(this.targets.front(idx));
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_FLOAT, buf);
            double sum = 0; float mx = 0; long nan = 0; int total = w * h;
            for (int i = 0; i < total; i++) {
                for (int c = 0; c < 3; c++) {
                    float v = buf.get(i * 4 + c);
                    if (Float.isNaN(v) || Float.isInfinite(v)) { nan++; continue; }
                    sum += v; if (v > mx) mx = v;
                }
            }
            int ci = (h / 2) * w + (w / 2);        // center pixel (≈ horizon)
            int sky = (h * 5 / 6) * w + (w / 2);   // GL upper row = DISPLAY SKY (glGetTexImage is bottom-up, no y-flip)
            int terr = (h / 6) * w + (w / 2);      // GL lower row = DISPLAY FOREGROUND TERRAIN
            log(String.format("[DIAG chain] colortex%d %-14s avg=%.3f max=%.3f NaN=%d center=(%.3f,%.3f,%.3f) sky=(%.3f,%.3f,%.3f) terr=(%.3f,%.3f,%.3f)",
                    idx, label, sum / (total * 3), mx, nan,
                    buf.get(ci * 4), buf.get(ci * 4 + 1), buf.get(ci * 4 + 2),
                    buf.get(sky * 4), buf.get(sky * 4 + 1), buf.get(sky * 4 + 2),
                    buf.get(terr * 4), buf.get(terr * 4 + 1), buf.get(terr * 4 + 2)));
        } catch (Throwable ignored) {}
    }

    /** DIAGNOSTIC: reads back colortexN (its current front) and logs ground-truth stats (center pixel, min/max/avg, NaN
     * count) — so we can tell what a stage ACTUALLY produced, independent of how it's displayed. */
    private void logColortex(int idx) {
        try {
            int w = this.targets.width();
            int h = this.targets.height();
            if (w <= 0 || h <= 0) return;
            java.nio.FloatBuffer buf = org.lwjgl.BufferUtils.createFloatBuffer(w * h * 4);
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GlStateManager.bindTexture(this.targets.front(idx));
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_FLOAT, buf);
            long nan = 0;
            float mn = Float.POSITIVE_INFINITY, mx = Float.NEGATIVE_INFINITY;
            double sum = 0;
            int total = w * h;
            for (int i = 0; i < total; i++) {
                for (int c = 0; c < 3; c++) {
                    float v = buf.get(i * 4 + c);
                    if (Float.isNaN(v) || Float.isInfinite(v)) { nan++; continue; }
                    if (v < mn) mn = v;
                    if (v > mx) mx = v;
                    sum += v;
                }
            }
            int ci = (h / 2) * w + (w / 2);
            log(String.format("[DIAG colortex%d] center=(%.3f,%.3f,%.3f) rgb[min=%.3f max=%.3f avg=%.3f] NaN/Inf=%d",
                    idx, buf.get(ci * 4), buf.get(ci * 4 + 1), buf.get(ci * 4 + 2), mn, mx, sum / (total * 3), nan));
        } catch (Throwable t) {
            SodiumClientMod.logger().error("[DIAG colortex] readback failed", t);
        }
    }

    private static void drawFullscreenQuad() {
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        buffer.pos(-1.0D, -1.0D, 0.0D).tex(0.0D, 0.0D).endVertex();
        buffer.pos(1.0D, -1.0D, 0.0D).tex(1.0D, 0.0D).endVertex();
        buffer.pos(1.0D, 1.0D, 0.0D).tex(1.0D, 1.0D).endVertex();
        buffer.pos(-1.0D, 1.0D, 0.0D).tex(0.0D, 1.0D).endVertex();
        tessellator.draw();
    }

    // ---- shader health report (video settings → Yumelium Plus → Shader Health Report) -----------------------------

    /** One-shot latch: re-armed while the option is off, fires once after the next completed frame when on. */
    private boolean healthReported;

    /** Sampler-audit failures recorded by {@link #auditSamplerUnits} (deduplicated), echoed by the health report. */
    private static final java.util.Set<String> AUDIT_FAILURES =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());

    /** shaders.properties key prefixes the port implements (or whose behaviour it verifiably matches); every other
     * ACTIVE directive is reported as unimplemented. */
    private static final String[] HANDLED_PROPERTY_PREFIXES = {
            "image.", "bufferObject.",                        // custom images + SSBOs (CustomImages)
            "blend.",                                         // blend.<program>.colortexN = off (custom funcs = listed gap)
            "texture.noise", "texture.gbuffers.gaux4",        // custom textures (gaux4 path honours CLOUD_TEXTURE now)
            "texture.deferred.colortex3",                     // deferred-stage colortex3 override (cloud noise; runPass)
            "uniform.float.rainFactor", "uniform.float.thunderFactor", "uniform.float.inRainy",
            "uniform.float.inDry", "uniform.float.inSnowy", "uniform.float.eyeBrightnessM2", // smoothed custom uniforms
            "screen", "sliders", "profile.",                  // the options GUI (ShaderOptionSet)
            "program.",                                       // program.<dim>/<name>.enabled=false (parseDisabledPrograms)
            "alphaTest.",                                     // per-program alpha FUNC/REF overrides (applyAlphaOverride)
            "vignette", "underwaterOverlay", "clouds",        // host-overlay suppression (parseMiscDirectives + mixins)
            "rain.depth",                                     // weather depth-write gate (beginWeather)
            "beacon.beam.depth",                              // true = beam writes depth; the TESR default already does
            "separateAo", "oldLighting",                      // the vertex-colour legacy format implements exactly these
            "oldHandLight",                                   // pack-side: both held-light uniforms are fed separately
            "particles.ordering",                             // "mixed": our particles draw before deferred, as expected
            "shadow.culling", "shadowEntities", "shadowBlockEntities", // shadowEntities honoured (all-entity casters); TESR casters not rendered
            "shadowPlayer",                                   // =true: player + vehicle drawn into the shadow map
            "oldLighting",                                    // =false: vanilla face shade stripped from vertex data
            "breaksAnisotropy", "supportsColorCorrection", "iris.features.optional", // informational, no behaviour
            "voxelizeLightBlocks",                            // light-block voxelization runs in our shadow pass
            "photonics.", "profile2.",                        // EP metadata / GUI profile presets — informational
    };

    private static boolean isHandledProperty(String key) {
        for (String prefix : HANDLED_PROPERTY_PREFIXES) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Logs ONE full report of everything the Iris port is NOT doing for the active pack (programs with no host hook,
     * shaders.properties directives it does not implement, the deliberate design gaps) and everything that FAILED
     * (shader compiles, sampler audits, unset uniforms, framebuffer completeness). Log-only — rendering is untouched.
     * Runs after a COMPLETED frame so lazily-compiled programs (the pack terrain compiles during the first frame's
     * terrain pass) are all accounted for; toggle the option off and back on to print it again.
     */
    private void logHealthReport() {
        StringBuilder sb = new StringBuilder("==== SHADER HEALTH REPORT — pack: ").append(this.activePackName)
                .append(" — dimension folder: ").append(this.worldFolder).append(" ====\n");
        try (ShaderPack pack = loadActivePack()) {
            if (pack == null) {
                log("[HEALTH] could not reopen the active pack — report unavailable");
                return;
            }
            // Force the lazy program sources so "not loaded yet" cannot masquerade as "not routed".
            packTerrainSources();
            packWaterSources();
            packShadowVertexSource();

            // -- A. program routing: which of the pack's programs actually reach a host hook --
            java.util.Set<String> routed = new java.util.HashSet<>();
            for (Pass p : this.preparePrograms) routed.add(p.name);
            for (Pass p : this.deferredPrograms) routed.add(p.name);
            for (Pass p : this.compositePrograms) routed.add(p.name);
            if (this.skybasicProgram != null) routed.add("gbuffers_skybasic");
            if (this.skytexturedProgram != null) routed.add("gbuffers_skytextured");
            if (this.handProgram != null) routed.add("gbuffers_hand");
            if (this.entitiesProgram != null) routed.add("gbuffers_entities");
            if (this.blockProgram != null) routed.add("gbuffers_block");
            if (this.texturedProgram != null) routed.add("gbuffers_textured");
            if (this.weatherProgram != null) routed.add("gbuffers_weather");
            if (this.beaconBeamProgram != null) routed.add("gbuffers_beaconbeam");
            if (this.damagedBlockProgram != null) routed.add("gbuffers_damagedblock");
            if (this.armorGlintProgram != null) routed.add("gbuffers_armor_glint");
            if (this.spidereyesProgram != null) routed.add("gbuffers_spidereyes");
            if (this.entitiesGlowingProgram != null) routed.add("gbuffers_entities_glowing");
            if (this.basicProgram != null) routed.add("gbuffers_basic");
            if (this.packTerrainSources != null) routed.add("gbuffers_terrain");
            if (this.packWaterSources != null) routed.add("gbuffers_water");
            if (this.packShadowVertexSource != null) routed.add("shadow");
            if (this.shadowCompProgram != null) routed.add("shadowcomp");
            sb.append("-- programs: ").append(routed.size()).append(" routed of ")
                    .append(pack.programSet().count()).append(" the pack provides --\n");
            sb.append(this.finalProgram != null && !DIAG_BLIT_INSTEAD_OF_FINAL
                    ? "   final: RUN to MC's framebuffer (pack post-fx active: sharpen/vignette/dither)\n"
                    : "   final: pack ships none (or DIAG_BLIT_INSTEAD_OF_FINAL) — colortex" + FINAL_COLORTEX
                            + " tonemap-blit fallback\n");
            sb.append("   gbuffers_line: NOT ROUTED by design — its vertex is the modern-Iris line expansion"
                    + " (vaPosition/vaNormal/gl_VertexID); 1.12.2's GL_LINES draws route through gbuffers_basic\n");
            for (java.util.Map.Entry<String, ProgramSource> e : pack.programSet().programs().entrySet()) {
                String name = e.getKey();
                if (routed.contains(name) || "final".equals(name) || "gbuffers_line".equals(name)) {
                    continue;
                }
                String frag = e.getValue().fragment();
                String reason;
                if (this.disabledPrograms.contains(name)) {
                    reason = " (disabled by the pack for this config — program.enabled = false, honoured)";
                } else if ("gbuffers_clouds".equals(name)) {
                    reason = " (clouds = off — the pack suppresses the vanilla cloud layer this program would shade;"
                            + " its own clouds render in deferred1)";
                } else if (frag != null && !writesColorOutput(frag)) {
                    reason = " (discard-only stub — skipping is correct)";
                } else {
                    reason = " (no host hook — that geometry draws via a neighbouring program or fixed-function)";
                }
                sb.append("   NOT ROUTED: ").append(name).append(reason).append('\n');
            }

            // -- B. failures --
            sb.append("-- failures --\n");
            java.util.List<String> compileFails = GlslProgram.compileFailures();
            sb.append(compileFails.isEmpty() ? "   shader compiles: no failures recorded this session\n"
                    : "   shader compile/link FAILURES: " + compileFails + "  (numbered source dumps in shader_dumps/)\n");
            if (this.packTerrainSources != null && !this.terrainWritesNormal) {
                sb.append("   gbuffers_terrain: transformed pack terrain NOT active — Sodium fallback shading is in use"
                        + " (grep the log for 'gbuffers_terrain failed')\n");
            }
            synchronized (AUDIT_FAILURES) {
                sb.append(AUDIT_FAILURES.isEmpty() ? "   sampler audit: no unbound (unit-0) samplers recorded\n"
                        : "   sampler audit FAILURES: " + AUDIT_FAILURES + '\n');
            }
            sb.append("   world FBO: ").append(this.targets != null && this.targets.isCreated()
                    ? "complete" : "*** NOT COMPLETE / absent ***").append('\n');
            int glErr = GL11.glGetError();
            sb.append("   glGetError at report time: 0x").append(Integer.toHexString(glErr))
                    .append(glErr == 0 ? " (clean)\n" : " *** a GL error was pending ***\n");

            // -- C. unset uniforms (each program's active uniforms the pipeline never sets; they read as 0) --
            sb.append("-- unset uniforms --\n");
            java.util.List<Object[]> namedPrograms = new java.util.ArrayList<>();
            for (Pass p : this.preparePrograms) namedPrograms.add(new Object[]{p.name, p.program});
            for (Pass p : this.deferredPrograms) namedPrograms.add(new Object[]{p.name, p.program});
            for (Pass p : this.compositePrograms) namedPrograms.add(new Object[]{p.name, p.program});
            namedPrograms.add(new Object[]{"gbuffers_skybasic", this.skybasicProgram});
            namedPrograms.add(new Object[]{"gbuffers_skytextured", this.skytexturedProgram});
            namedPrograms.add(new Object[]{"gbuffers_hand", this.handProgram});
            namedPrograms.add(new Object[]{"gbuffers_entities", this.entitiesProgram});
            namedPrograms.add(new Object[]{"gbuffers_block", this.blockProgram});
            namedPrograms.add(new Object[]{"gbuffers_textured", this.texturedProgram});
            namedPrograms.add(new Object[]{"gbuffers_weather", this.weatherProgram});
            namedPrograms.add(new Object[]{"shadowcomp", this.shadowCompProgram});
            boolean anyMiss = false;
            for (Object[] entry : namedPrograms) {
                String line = unsetUniformLine((String) entry[0], (GlslProgram) entry[1]);
                if (line != null) {
                    sb.append("   ").append(line).append('\n');
                    anyMiss = true;
                }
            }
            if (!anyMiss) {
                sb.append("   none — every active uniform of every compiled program is fed\n");
            }
            sb.append("   (terrain/water/shadow run through Sodium's chunk-shader path; applyTerrainUniforms feeds them"
                    + " and they are not re-checked here)\n");

            // -- D. shaders.properties directives with no implementation (only the branches ACTIVE for this config) --
            sb.append("-- shaders.properties directives the port does not implement --\n");
            java.util.Map<String, java.util.List<String>> unhandled = new java.util.TreeMap<>();
            for (String key : resolvedProperties(pack).keySet()) {
                if (isHandledProperty(key)) {
                    continue;
                }
                int dot = key.indexOf('.');
                String family = dot > 0 ? key.substring(0, dot) : key;
                unhandled.computeIfAbsent(family, k -> new java.util.ArrayList<>()).add(key);
            }
            if (unhandled.isEmpty()) {
                sb.append("   none\n");
            } else {
                for (java.util.Map.Entry<String, java.util.List<String>> e : unhandled.entrySet()) {
                    java.util.List<String> keys = e.getValue();
                    sb.append("   ").append(e.getKey()).append(" (").append(keys.size()).append("): ")
                            .append(String.join(", ", keys.subList(0, Math.min(4, keys.size()))))
                            .append(keys.size() > 4 ? ", …" : "").append('\n');
                }
            }

            // -- E. known design gaps (deliberate; each was a measured decision, not an oversight) --
            sb.append("-- known design gaps (deliberate) --\n");
            sb.append("   * shadow FRAGMENT is a ported subset: water caustics + shaft noise + SALS heights ARE ported;"
                    + " stained-glass COLOURED shadows are not (opaque casters write a neutral tint)\n");
            sb.append("   * entity shadow casters follow the pack's shadowEntities/shadowPlayer/shadowBlockEntities"
                    + " directives (the ENTITY_SHADOW option): all in-range entities via the distortion-matching"
                    + " program when shadowEntities, else player + vehicle only; TESR casters only at"
                    + " shadowBlockEntities (Complementary: ENTITY_SHADOW=2)\n");
            sb.append("   * IRIS_VERSION >= 10800 pack branches are deliberately not taken (defining it broke cameraPosition)\n");
            sb.append("   * per-stage custom texture overrides (texture.<stage>.<sampler>) are unimplemented beyond"
                    + " noise + gbuffers gaux4\n");
            sb.append("   * blend.<program>.<buffer> custom blend FUNCTIONS are unimplemented (only `off` is honoured —"
                    + " the only form this pack uses)\n");
            sb.append("   * depthtex2 aliases depthtex1 (measured: this pack never reads scene depth through depthtex2)\n");
            sb.append("   * hand reflection fresnel is capped to ").append(HAND_REFLECT_MAX)
                    .append(" (full strength mirrored scene objects onto the wet hand)\n");
        } catch (Throwable t) {
            SodiumClientMod.logger().error("[Iris] health report failed", t);
            return;
        }
        sb.append("==== end of health report ====");
        log("[HEALTH] " + sb);
    }

    /** Releases GPU resources (e.g. on world unload). */
    public void destroy() {
        if (this.targets != null) {
            this.targets.destroy();
            this.targets = null;
        }
        if (this.atlasReflectSampler != 0) {
            // A sampler OBJECT (not per-texture state); glDeleteSamplers also detaches it from any unit it is bound to.
            org.lwjgl.opengl.GL33C.glDeleteSamplers(this.atlasReflectSampler);
            this.atlasReflectSampler = 0;
        }
        this.customImages.destroy();
        this.customImages = new CustomImages();
        for (Pass p : this.preparePrograms) {
            p.program.delete();
        }
        this.preparePrograms.clear();
        for (Pass p : this.deferredPrograms) {
            p.program.delete();
        }
        this.deferredPrograms.clear();
        for (Pass p : this.compositePrograms) {
            p.program.delete();
        }
        this.compositePrograms.clear();
        if (this.finalProgram != null) {
            this.finalProgram.delete();
            this.finalProgram = null;
            this.finalMipmaps = new int[0];
        }
        if (this.tonemapProgram != null) {
            this.tonemapProgram.delete();
            this.tonemapProgram = null;
        }
        if (this.depthCopyProgram != null) {
            this.depthCopyProgram.delete();
            this.depthCopyProgram = null;
        }
        if (this.handReflectFixProgram != null) {
            this.handReflectFixProgram.delete();
            this.handReflectFixProgram = null;
        }
        if (this.playerShadowProgram != null) {
            this.playerShadowProgram.delete();
            this.playerShadowProgram = null;
        }
        if (this.shadowCompProgram != null) {
            this.shadowCompProgram.delete();
            this.shadowCompProgram = null;
        }
        if (this.skybasicProgram != null) {
            this.skybasicProgram.delete();
            this.skybasicProgram = null;
        }
        if (this.skybasicBelowProgram != null) {
            this.skybasicBelowProgram.delete();
            this.skybasicBelowProgram = null;
        }
        if (this.skytexturedProgram != null) {
            this.skytexturedProgram.delete();
            this.skytexturedProgram = null;
        }
        if (this.handProgram != null) {
            this.handProgram.delete();
            this.handProgram = null;
        }
        if (this.entitiesProgram != null) {
            this.entitiesProgram.delete();
            this.entitiesProgram = null;
        }
        if (this.blockProgram != null) {
            this.blockProgram.delete();
            this.blockProgram = null;
        }
        if (this.texturedProgram != null) {
            this.texturedProgram.delete();
            this.texturedProgram = null;
        }
        if (this.weatherProgram != null) {
            this.weatherProgram.delete();
            this.weatherProgram = null;
        }
        if (this.beaconBeamProgram != null) {
            this.beaconBeamProgram.delete();
            this.beaconBeamProgram = null;
        }
        if (this.damagedBlockProgram != null) {
            this.damagedBlockProgram.delete();
            this.damagedBlockProgram = null;
        }
        if (this.armorGlintProgram != null) {
            this.armorGlintProgram.delete();
            this.armorGlintProgram = null;
        }
        if (this.spidereyesProgram != null) {
            this.spidereyesProgram.delete();
            this.spidereyesProgram = null;
        }
        if (this.entitiesGlowingProgram != null) {
            this.entitiesGlowingProgram.delete();
            this.entitiesGlowingProgram = null;
        }
        if (this.basicProgram != null) {
            this.basicProgram.delete();
            this.basicProgram = null;
        }
        this.armorGlintTargets = null;
        this.spidereyesTargets = null;
        this.basicTargets = null;
        this.glintActive = false;
        this.eyesActive = false;
        this.basicActive = false;
        this.activeEntityProgram = null;
        this.activeBlockPassProgram = null;
        this.damagedActive = false;
        this.damagedLogged = false;
        this.skyActive = false;
        this.skyLogged = false;
        this.handActive = false;
        this.handLogged = false;
        this.entitiesActive = false;
        this.entitiesLogged = false;
        this.blockActive = false;
        this.blockLogged = false;
        this.particlesActive = false;
        this.particlesLogged = false;
        this.weatherActive = false;
        this.weatherLogged = false;
        this.packTerrainSources = null;
        this.packTerrainLoaded = false;
        this.packShadowVertexSource = null;
        this.packShadowLoaded = false;
        this.packWaterSources = null;
        this.packWaterTargets = null;
        this.packWaterLoaded = false;
        this.entityTargets = null;
        this.handTargets = null;
        this.terrainWritesNormal = false;
        this.shadowPass = false;
        this.shadowDiagFrame = 0;
        this.shadowDiagLogs = 0;
        this.pipelineInit = false;
        this.activeThisFrame = false;
        this.healthReported = false; // a pack switch/reload re-reports if the option is still on
        // Re-read the pack's option file on the next recompile. Previously the discovered/loaded options were cached for
        // the whole session and only re-read on a full game restart, so a manual edit to shaderpacks/<pack>.txt (e.g.
        // toggling BORDER_FOG) had NO effect until a restart — which made config-driven fixes look like they "did nothing".
        // Clearing this here means any pipeline recompile (shader-pack reselect, the settings-GUI apply, an explicit
        // reload) picks up the current on-disk config. The GUI's applyOptionChanges() still saves BEFORE destroy(), so its
        // in-memory changes survive the reload (they are simply re-read from the file they were just written to).
        this.shaderOptions = null;
        this.shaderOptionsLoaded = false;
    }
}
