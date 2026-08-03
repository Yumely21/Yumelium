package com.yumelium.yumelium.shaders.pipeline;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Iris/Oculus port — M5 step 2. Transforms a shader pack's {@code gbuffers_terrain} (legacy OptiFine dialect:
 * {@code #version 120}, fixed-function builtins {@code gl_Vertex}/{@code gl_Color}/{@code gl_MultiTexCoord0/1}/
 * {@code gl_ModelViewProjectionMatrix}, {@code varying}, {@code gl_FragData}, {@code texture2D}) into a Sodium
 * {@code #version 330 core} chunk shader that runs on Sodium's existing compact terrain geometry.
 *
 * <p>This is the lightweight, targeted equivalent of Iris's {@code SodiumTerrainPipeline} transform (Iris uses the full
 * glsl-transformer). It reuses Sodium's whole draw path — {@code #import}ing {@code chunk_vertex.glsl} to decode the
 * packed integer attributes (so NO vertex-format change / re-mesh is needed) — and just remaps the builtins the pack
 * reads onto Sodium's decoded values + uniforms. Handles the common terrain-shader constructs; a pack using constructs
 * outside this set fails to compile and the caller falls back to a Sodium-native terrain shader.</p>
 */
public final class TerrainShaderTransformer {
    private TerrainShaderTransformer() {
    }

    /** Prepended to the transformed vertex shader: Sodium decode + the {@code iris_*} builtin bridges. */
    private static final String VERTEX_PREAMBLE =
            // COMPATIBILITY (not core): our GL context is a compat profile, and real packs (#version 130) use legacy
            // functions (texture2DLod) + the `attribute` keyword that CORE removed. Compatibility keeps them while still
            // supporting the modern constructs Sodium's decode uses. The built-in #120 pack is unaffected. #version 420:
            // image load/store (imageStore) is CORE — the shadow vertex voxelizes the world into voxel_img/wsr_img for
            // Colored Lighting + WSR when COLORED_LIGHTING > 0 (a superset of #330, so terrain/water are unaffected).
            "#version 420 compatibility\n" +
            "#import <sodium:include/chunk_vertex.glsl>\n" +
            "#import <sodium:include/chunk_matrices.glsl>\n" +
            "#import <sodium:include/chunk_material.glsl>\n" +
            "uniform vec3 u_RegionOffset;\n" +
            "\n" +
            "uvec3 _iris_rcc(uint p) { return uvec3(p) >> uvec3(5u, 0u, 2u) & uvec3(7u, 3u, 7u); }\n" +
            "vec3 _iris_draw_translation(uint p) { return _iris_rcc(p) * vec3(16.0); }\n" +
            "\n" +
            "vec4 iris_Vertex;\n" +
            "vec4 iris_Color;\n" +
            "vec4 iris_MultiTexCoord0;\n" +
            "vec4 iris_MultiTexCoord1;\n" +
            "mat4 iris_MVP;\n" +
            // Shadow pass: when iris_shadowPass=1 the terrain is drawn from the light's POV into the shadow depth map, and
            // the clip position must get the SAME radial warp + depth squash the pack applies when it LOOKS the map up
            // (lib/lighting/shadowSampling.glsl GetShadowPos: xy /= (dist·bias + (1-bias)); z *= 0.2). Without matching it,
            // the stored depths don't line up with the distorted lookup coords → no/garbage shadows. 0 in the camera pass.
            "uniform float iris_shadowPass;\n" +
            "uniform float iris_shadowMapBias;\n" +
            "out float iris_MipBias;\n" +
            "out float iris_AlphaCutoff;\n" + // per-material alpha cutoff {0.0, 0.1, 0.5, 1.0} — see the fragment discard rewrite
            "out float iris_Desaturate;\n" +
            "out vec3 iris_viewNormal;\n" +
            "out vec2 iris_dbgUV;\n" +
            "\n" +
            // OptiFine vertex attributes the pack's terrain/water shaders declare (mc_Entity, mc_midTexCoord, at_midBlock,
            // at_tangent). Sodium doesn't supply them, so we synthesize them from the decoded vertex + material bits. Both
            // the terrain and water programs include this preamble, so declare them ONCE here (their `attribute` decls are
            // stripped from the pack body) and fill them in _iris_terrain_init.
            "vec4 mc_Entity;\n" +
            "vec4 mc_midTexCoord;\n" +
            "vec4 at_midBlock;\n" +
            "vec4 at_tangent;\n" +
            // Foliage id from the material bits (see chunk_material.glsl _material_foliage): grounded plants → 10005,
            // leaves → 10009, upper double-plant → 10021. Complementary reads mc_Entity.x to wave + shade foliage natively.
            "int _iris_foliage_id(uint m) {\n" +
            "    uint f = _material_foliage(m);\n" +
            "    if (f == 1u) return 10005;\n" +
            "    if (f == 2u) return 10009;\n" +
            "    if (f == 3u) return 10021;\n" +
            "    return 0;\n" +
            "}\n" +
            "\n" +
            "void _iris_terrain_init() {\n" +
            "    _vert_init();\n" +
            "    iris_dbgUV = _vert_tex_diffuse_coord;\n" +
            "    vec3 _iris_pos = _vert_position + u_RegionOffset + _iris_draw_translation(_draw_id);\n" +
            "    iris_Vertex = vec4(_iris_pos, 1.0);\n" +
            "    iris_Color = _vert_color;\n" +
            "    iris_MultiTexCoord0 = vec4(_vert_tex_diffuse_coord, 0.0, 1.0);\n" +
            // Lightmap coord in the OptiFine/Iris convention (L+0.5)/16 the pack is calibrated for: Sodium's decoded coord
            // is L*16 (0..240 for light level 0..15), so /256 alone gives 0.9375 at full light — but the pack maps
            // [0.03125, 0.96875] → [0,1] (GetLightMapCoordinates), so full light landed at 0.967, JUST under thresholds like
            // the rain-puddle gate (lmCoord.y > 0.96875). Add the half-texel (+8) so full light = 248/256 = 0.96875 → 1.0.
            "    iris_MultiTexCoord1 = vec4((vec2(_vert_tex_light_coord) + 8.0) / 256.0, 0.0, 1.0);\n" +
            "    iris_MVP = u_ProjectionMatrix * u_ModelViewMatrix;\n" +
            "    iris_MipBias = _material_mip_bias(_material_params);\n" +
            "    iris_AlphaCutoff = _material_alpha_cutoff(_material_params);\n" +
            "    iris_Desaturate = _material_desaturate(_material_params) ? 1.0 : 0.0;\n" +
            "    iris_viewNormal = mat3(u_ModelViewMatrix) * _vert_normal;\n" +
            // Synthesize the OptiFine attributes the pack reads. mc_Entity.x lets Complementary identify grass/leaves;
            // mc_midTexCoord (the sprite centre) lets its foliage waving anchor the plant's base (top vertices sway,
            // bottom stay put); at_tangent from the normal supports the pack's optional normal-mapping; at_midBlock is
            // unused here (no POM) → 0.
            // mc_Entity.x = the block.properties id from the a_BlockId vertex attribute (foliage + light sources +
            // occluders). Fall back to the material-bit foliage id when a_BlockId is 0, so foliage waving is preserved even
            // for blocks the classifier didn't tag. The pack reads mc_Entity.x for waving AND colored-lighting voxelization.
            "    mc_Entity = vec4(_vert_block_id > 0.5 ? _vert_block_id : float(_iris_foliage_id(_material_params)), 0.0, 0.0, 0.0);\n" +
            "    mc_midTexCoord = vec4(_vert_mid_tex_coord, 0.0, 1.0);\n" +
            "    at_midBlock = vec4(_vert_mid_block, 0.0);\n" +
            // Real UV-aligned tangent from the mesh (a_Tangent), so the pack's TBN basis is correct for the nether-portal
            // parallax + PBR — the old cross(normal, up) synthesis gave the right axis but wrong sign/handedness.
            "    at_tangent = _vert_tangent;\n" +
            "}\n" +
            "\n" +
            "// ---- transformed pack gbuffers_terrain (vertex) ----\n";

    /** Prepended to the transformed fragment shader: the colour output Sodium binds, the mip-bias + normal varyings, and
     * the second output (colortex1 view-space normal) that the wrapper {@code main} always writes. */
    private static final String FRAGMENT_PREAMBLE =
            // COMPATIBILITY (not core): our GL context is a compat profile, and real packs (#version 130) use legacy
            // functions (texture2DLod) + the `attribute` keyword that #330 CORE removed. Compatibility keeps them while
            // still supporting the #330 constructs Sodium's decode uses. The built-in #120 pack is unaffected.
            "#version 330 compatibility\n" +
            "out vec4 fragColor;\n" +
            "out vec4 iris_FragNormal;\n" +
            "in float iris_MipBias;\n" +
            "in float iris_Desaturate;\n" +
            "in vec3 iris_viewNormal;\n" +
            "\n" +
            "// ---- transformed pack gbuffers_terrain (fragment) ----\n";

    /** Appended after the pack's (renamed) fragment main: run it, then write the geometric normal to colortex1. If the
     * pack {@code discard}ed, this is never reached, so transparent texels write neither colour nor normal. */
    private static final String FRAGMENT_MAIN_WRAPPER =
            "\n" +
            "void main() {\n" +
            "    iris_pack_main();\n" +
            "    float _iris_nl = length(iris_viewNormal);\n" +
            "    vec3 _iris_n = _iris_nl > 1e-5 ? iris_viewNormal / _iris_nl : vec3(0.0, 0.0, 1.0);\n" +
            // Alpha carries THIS terrain fragment's own window depth, so the composite can tell whether the colortex1 it
            // samples belongs to the surface actually shown at that pixel (terrain) or is stale terrain behind something
            // drawn over it in immediate mode (hand/entities/particles). See composite.fsh's depth-consistency guard.
            "    iris_FragNormal = vec4(_iris_n * 0.5 + 0.5, gl_FragCoord.z);\n" +
            "}\n";

    /** Appended after the pack's (renamed) vertex main: run the Sodium decode init first, then the pack's entry point. */
    private static final String VERTEX_MAIN_WRAPPER =
            // The pack computes gl_Position via a gbufferModelView·gbufferModelViewInverse round-trip that algebraically
            // reduces to proj·modelview·vertex — but only if those two uniforms are exact inverses at runtime. To remove
            // that fragile dependency for the clip position, override gl_Position after the pack main with the known-good
            // transform Sodium's built-in path uses (iris_MVP = u_ProjectionMatrix·u_ModelViewMatrix, iris_Vertex = the
            // decoded region-relative position). The pack's gbufferModelView uniforms still feed its fragment lighting.
            "\nvoid main() {\n" +
            "    _iris_terrain_init();\n" +
            "    iris_pack_main();\n" +
            "    vec4 _iris_clip = iris_MVP * iris_Vertex;\n" +
            // Foliage/leaf waving: the pack waves a local player-space `position` and sets its own gl_Position, but our
            // robust override drops that. Re-run the pack's DoWave here on this vertex's camera-relative world position
            // (iris_Vertex.xyz — pass-independent, so it is correct in the shadow pass too), take just the displacement it
            // produced, and add it to the clip position as a delta through iris_MVP (the current pass's MVP). Terrain
            // positioning stays on the exact-inverse-free iris_MVP path; only grass/leaves (mc_Entity != 0) actually move.
            "#if defined WAVING_ANYTHING_TERRAIN\n" +
            // Only foliage (mc_Entity != 0) waves — gate the whole block so the ~99% of terrain vertices that are non-foliage
            // skip DoWave AND the extra clip-space matrix multiply entirely (keeps the per-vertex cost off regular terrain).
            "    if (mc_Entity.x > 0.5) {\n" +
            "        int _iris_mat = int(mc_Entity.x + 0.5);\n" +
            "        vec3 _iris_pp = iris_Vertex.xyz;\n" +
            "        DoWave(_iris_pp, _iris_mat);\n" +
            "        _iris_clip += iris_MVP * vec4(_iris_pp - iris_Vertex.xyz, 0.0);\n" +
            "    }\n" +
            "#endif\n" +
            "    if (iris_shadowPass > 0.5) {\n" +
            "        float _iris_distb = sqrt(_iris_clip.x * _iris_clip.x + _iris_clip.y * _iris_clip.y);\n" +
            "        float _iris_df = _iris_distb * iris_shadowMapBias + (1.0 - iris_shadowMapBias);\n" +
            "        _iris_clip.xy /= _iris_df;\n" +
            "        _iris_clip.z *= 0.2;\n" +
            "    }\n" +
            // TAA jitter: the pack's own gbuffers_terrain jitters gl_Position (so the composite TAA can accumulate sub-pixel
            // samples), but our gl_Position override dropped it — leaving terrain UN-jittered while water IS jittered → the
            // scene mis-aligns each frame and TAA can't converge (flicker). Re-apply the pack's exact TAAJitter (jitter.glsl
            // is included by the terrain vertex, so the function is in scope) in the camera pass only, never in the shadow pass.
            "#ifdef TAA\n" +
            "    else { _iris_clip.xy = TAAJitter(_iris_clip.xy, _iris_clip.w); }\n" +
            "#endif\n" +
            "    gl_Position = _iris_clip;\n" +
            "}\n";

    /** gbuffers_water vertex extras: the OptiFine attributes the pack declares ({@code mc_Entity}, {@code at_tangent},
     * {@code mc_midTexCoord}, {@code at_midBlock}) which Sodium doesn't supply — synthesized from the decoded vertex.
     * {@code mc_Entity.x = 32000} (Complementary's water id, so DoWave/fragment treat it as water) when the geometry uses
     * Sodium's desaturate (WATER) material, else 31000 (glass) so glass/ice don't wave. */
    private static final String WATER_VERTEX_EXTRA =
            // mc_Entity/mc_midTexCoord/at_midBlock/at_tangent are declared + filled by the shared terrain preamble/
            // _iris_terrain_init (which _iris_water_init calls first). For translucent geometry we prefer the REAL block id
            // from a_BlockId (e.g. nether portal = 30020, so the pack applies its portal shader effect instead of treating
            // it as plain glass); only when there's no classified id (water, glass, ice, …) do we fall back to the material
            // desaturate bit → 32000 (water, so DoWave + the fragment treat it as water) else 31000 (glass; glass/ice don't wave).
            "void _iris_water_init() {\n" +
            "    _iris_terrain_init();\n" +
            "    mc_Entity = vec4(_vert_block_id > 0.5 ? _vert_block_id\n" +
            "                     : (_material_desaturate(_material_params) ? 32000.0 : 31000.0), 0.0, 0.0, 0.0);\n" +
            "}\n";

    /** Water wrapper: run the Sodium decode + water-attribute synthesis, then the pack main — WITHOUT overriding
     * gl_Position (the pack applies vertex waves to its `position` and sets gl_Position itself; overriding would drop them). */
    private static final String WATER_VERTEX_MAIN_WRAPPER =
            "\nvoid main() {\n    _iris_water_init();\n    iris_pack_main();\n}\n";

    /** Transforms {@code gbuffers_water}'s vertex: same builtin substitution as terrain, but keeps the pack's wave-carrying
     * gl_Position and synthesizes the OptiFine attributes (stripping their {@code attribute} decls). */
    public static String transformWaterVertex(String packVertex) {
        String body = stripVersion(packVertex);
        body = replaceWord(body, "varying", "out");
        body = substituteVertexBuiltins(body);
        body = body.replaceAll("(?m)^\\s*attribute\\s+vec4\\s+(mc_Entity|at_midBlock|mc_midTexCoord|at_tangent)\\s*;.*$", "");
        body = body.replaceAll("\\bvoid\\s+main\\s*\\(", "void iris_pack_main(");
        return VERTEX_PREAMBLE + WATER_VERTEX_EXTRA + body + WATER_VERTEX_MAIN_WRAPPER;
    }

    /** {@code gbuffers_water}'s fragment reuses the legacy path (keeps its multi-buffer {@code gl_FragData[n]} + samplers). */
    public static String transformWaterFragment(String packFragment) {
        return transformFragment(packFragment, "water");
    }

    /** Shadow-pass vertex wrapper: run the Sodium decode + OptiFine-attribute synthesis, then the pack's own shadow main
     * — WITHOUT overriding gl_Position. The pack shadow vertex already computes {@code position} (camera-relative world,
     * recovered from {@code ftransform()} via shadowModelViewInverse·shadowProjectionInverse — hence those uniforms must be
     * set), applies its {@code DoWave} to it, and sets {@code gl_Position = shadowProjection·shadowModelView·position}
     * with the pack's radial shadow-map distortion + z-squash. So the terrain wrapper's gl_Position override / distortion
     * must NOT run here (that would double-distort). */
    private static final String SHADOW_VERTEX_MAIN_WRAPPER =
            "\nvoid main() {\n    _iris_terrain_init();\n    iris_pack_main();\n}\n";

    /**
     * Lightweight shadow-pass FRAGMENT: the cutout alpha test, plus the ONE piece of the pack's shadow-colour output that
     * is actually consumed downstream — the light-shaft height in {@code shadowcolor1.a}.
     *
     * <p>Samples the block atlas ({@code tex}, bound to unit 0 by ChunkShaderInterface) at the diffuse UV the vertex
     * preamble exposes as {@code iris_dbgUV}, with the per-material mip bias, and discards transparent texels so
     * leaves/grass cast correctly-shaped (not solid-box) shadows.</p>
     *
     * <p><b>Why the height channel is not optional.</b> This fragment used to write depth only, on the reasoning that "the
     * shadow map we sample is depth-only". But Complementary's scene-aware light shafts (SALS) read
     * {@code texture2D(shadowcolor1, coord).a} in volumetricLight.glsl Step 4 and decode it back to a height — that is
     * what grows {@code vlFactor}. With shadowcolor1 absent, vlFactor stayed pinned at 0, and vlFactor is a STRENGTH term,
     * not a switch: at noon it cuts vlMult to 0.125 and triples the distance-falloff exponent, so the light shafts
     * rendered at under 1/8 intensity and read as "missing entirely". The encoding is the pack's own, tagged
     * {@code consistencyMEJHRI7DG} at both ends (shadow.glsl:221 writes, volumetricLight.glsl:312 reads):
     * {@code a = 0.25 + max0(positionYM * 0.05)}, where {@code positionYM} is {@code position.y} — the CAMERA-RELATIVE
     * height of the shadow caster. SALS then asks "is the average caster around me more than 6 blocks up?" (canopy /
     * buildings → strong shafts; open field → weak).</p>
     *
     * <p>{@code position} is the pack shadow VERTEX's own varying (declared unconditionally, camera-relative, after its
     * DoWave) — we already run that vertex verbatim, so the height was being computed and then thrown away right here.</p>
     *
     * <p>2026-07-27: STAINED-GLASS COLOURED SHADOWS ported. The translucent layer now dispatches on {@code mat}
     * (the pack's own {@code flat out int mat = mc_Entity.x} from its shadow vertex, which we run verbatim):
     * water keeps the caustics path; ice gets its dedicated branch (shadow.glsl:177-184); plain glass/pane/beacon
     * the faint-neutral branch (:189-199); everything else — stained/tinted glass included — goes through the
     * pack's {@code DoNaturalShadowCalculation} (:39-46), whose alpha-aware mix is what turns a translucent
     * caster's sprite colour into the coloured shadow (opaque a=1 collapses to black — which is why the opaque
     * path's plain black was already correct). {@code shadowcolor1.rgb} gets the pack's normalized hue ×0.5 so
     * light shafts through glass are tinted too; the glass family writes SALS height 0 (86AHGA).</p>
     */
    /** @param connectedGlass whether the pack option CONNECTED_GLASS_EFFECT is ON — the shadow VERTEX only outputs
     * the {@code signMidCoordPos}/{@code absMidCoordPos} varyings under that #ifdef, so the fragment must mirror it
     * (declaring inputs the vertex never writes is link-fragile). When on, stained/tinted glass resamples its sprite
     * near the CENTRE (the pack's DoSimpleConnectedGlass) so the frame/border texels don't stripe the coloured shadow. */
    public static String shadowFragment(boolean connectedGlass) {
        String decls = connectedGlass
                ? "in vec2 signMidCoordPos;\nflat in vec2 absMidCoordPos;\n"
                : "";
        String glassResample = connectedGlass
                ? "        if (mat == 30008 || mat >= 31000) {\n" +
                  // DoSimpleConnectedGlass (connectedGlass.glsl:26-34): midCoord = uv - sign*abs (the sprite centre
                  // from mc_midTexCoord), nudged -0.125*halfSize toward the interior, sampled at LOD 0 → borderless.
                  "            vec2 yl_mid = iris_dbgUV - signMidCoordPos * absMidCoordPos - 0.00001;\n" +
                  "            texSample = texture2DLod(tex, yl_mid - 0.125 * absMidCoordPos, 0.0);\n" +
                  "        }\n"
                : "";
        String plainResample = connectedGlass
                ? "        if (mat == 32008 || mat == 32012) {\n" +
                  // shadow.glsl:190-196: plain glass/pane also resample the sprite centre — the border texels would
                  // otherwise cast a thin black grid (border a=1 → the opaque branch below) where real Iris casts
                  // almost nothing (interior a≈0 → the faint branch).
                  "            vec2 yl_midP = iris_dbgUV - signMidCoordPos * absMidCoordPos - 0.00001;\n" +
                  "            texSample = texture2DLod(tex, yl_midP - 0.125 * absMidCoordPos, 0.0);\n" +
                  "        }\n"
                : "";
        return SHADOW_FRAGMENT
                .replace("//YL_CONNECTED_GLASS_DECLS\n", decls)
                .replace("//YL_CONNECTED_GLASS_RESAMPLE\n", glassResample)
                .replace("//YL_CONNECTED_GLASS_RESAMPLE_PLAIN\n", plainResample);
    }

    private static final String SHADOW_FRAGMENT =
            "#version 330 compatibility\n" +
            "uniform sampler2D tex;\n" +
            "uniform sampler2D noisetex;\n" +      // TERRAIN_NOISETEX_UNIT via applyTerrainUniforms (bound per frame)
            "uniform sampler2D gaux4;\n" +          // cloud-water.png on CLOUD_WATER_UNIT via applyTerrainUniforms
            "uniform vec3 cameraPosition;\n" +
            "uniform float frameTimeCounter;\n" +
            "uniform float sunBrightness;\n" +      // stand-in for the pack's sunVisibility (1 day / 0 night)
            "uniform float rainFactor;\n" +
            // 1 only while the shadow pass draws the TRANSLUCENT layer (water). Those casters write depth into
            // shadowtex0 but NOT into shadowtex1 (the opaque-only copy), which is exactly the difference the pack keys
            // on: shadowSampling sees "blocked in tex0, clear in tex1" and uses shadowcolor0 as a COLOURED shadow
            // instead of a black one, and the volumetric light turns shadowcolor1.rgb into the underwater god ray.
            // Writing black here would make the seabed pitch black under every water surface.
            "uniform float yumelium_shadowTranslucent;\n" +
            "in vec2 iris_dbgUV;\n" +
            "in float iris_MipBias;\n" +
            "in vec4 position;\n" +
            // The pack's own shadow-vertex outputs (we run that vertex verbatim): the caster's block id + vertex tint.
            "flat in int mat;\n" +
            "flat in vec4 glColor;\n" +
            "//YL_CONNECTED_GLASS_DECLS\n" +
            "void main() {\n" +
            "    vec4 texSample = texture2D(tex, iris_dbgUV, iris_MipBias);\n" +
            // The alpha cutout belongs to the OPAQUE/CUTOUT layers only (leaves must not cast full-square shadows).
            // The pack's shadow.glsl never discards: on the translucent layer a fully-transparent texel still writes
            // depth + its faint store — that IS the plain glass shadow (interior a=0 in most textures; branch below
            // stores 0.075 regardless of alpha). Discarding here erased the whole glass shadow, frames included.
            "    if (yumelium_shadowTranslucent < 0.5 && texSample.a < 0.1) discard;\n" +
            "    if (yumelium_shadowTranslucent > 0.5 && mat == 32000) {\n" +
            // WATER — the pack's own shadow.glsl water path, its MC_VERSION < 11300 branch (this port's target), ported
            // verbatim so the seabed + god rays match 1.20.1:
            //   shadowcolor0 = CAUSTICS, the WATER_CAUSTIC_STYLE == 3 branch (shadow.glsl:116-137) — the UNBOUND style.
            //   Unbound is SHADER_STYLE 4 → WATER_STYLE_DEFAULT 3, and WATER_CAUSTIC_STYLE_DEFINE=-1 resolves to it; the
            //   first attempt ported the <3 (sprite-luminance) branch and the user immediately recognised the wrong,
            //   REIMAGINED-style pattern. This one derives the caustic from the drifting gradient of cloud-water.png
            //   (gaux4 — applyTerrainUniforms already points it at CLOUD_WATER_UNIT for every chunk program, this one
            //   included). 0.0385 = WATER_SPEED_MULT(1.10) * 0.035; WATER_CAUSTIC_STRENGTH=1.0 makes its mix() a no-op.
            //   Stored at 1/10 scale (tag 423HDSS) — the terrain sampler multiplies by colorMult=2.5..10 and can exceed
            //   1.0, which is what keeps a deep seabed BLUE instead of black and makes shallow caustics sparkle.
            "        vec3 worldPos = position.xyz + cameraPosition;\n" +
            "        vec2 causticWind = vec2(0.0, frameTimeCounter * 0.0385);\n" +
            "        vec2 cPos1 = worldPos.xz * 0.08 + causticWind;\n" +
            "        vec2 cPos2 = worldPos.xz * 0.06 - causticWind;\n" +
            "        float caustic = dot(texture2D(gaux4, cPos1 + vec2(0.001, 0.0)).rg, vec2(14.0))\n" +
            "                      - dot(texture2D(gaux4, cPos1 - vec2(0.001, 0.0)).rg, vec2(14.0))\n" +
            "                      + dot(texture2D(gaux4, cPos2 + vec2(0.0, 0.001)).rg, vec2(14.0))\n" +
            "                      - dot(texture2D(gaux4, cPos2 - vec2(0.0, 0.001)).rg, vec2(14.0));\n" +
            "        vec3 wc = vec3(clamp(caustic * 0.8 + 0.35, 0.0, 1.0) * 0.65 + 0.35);\n" +
            "        wc *= vec3(0.3, 0.45, 0.9);\n" +      // MC_VERSION < 11300 tint (shadow.glsl:133)
            "        wc *= vec3(0.6, 0.8, 1.1);\n" +
            "        wc = pow(wc, vec3(0.75)) * 0.5;\n" +
            //   shadowcolor1 = the UNDERWATER LIGHT SHAFT pattern (shadow.glsl:158-170): two noisetex octaves drifting
            //   over world XZ. THE NOISE IS THE SHAFT — a constant here gives uniform haze, never visible rays (the
            //   previous constant (0.13,0.22,0.29) proved exactly that). frameTimeCounter stands in for syncedTime, the
            //   same substitution the pack's own DAYLIGHT_CYCLE_COMPAT makes.
            "        vec2 waterWind = vec2(frameTimeCounter * 0.01, 0.0);\n" +
            "        float waterNoise = texture2DLod(noisetex, worldPos.xz * 0.012 - waterWind, 0.0).g;\n" +
            "        waterNoise += texture2DLod(noisetex, worldPos.xz * 0.05 + waterWind, 0.0).g;\n" +
            "        float factorW = max(2.5 - 0.025 * length(position.xz), 0.8333) * 1.3;\n" +
            "        waterNoise = pow(waterNoise * 0.5, factorW) * factorW * 1.3;\n" +
            "        vec3 c2 = vec3(0.08, 0.12, 0.195) * waterNoise * (1.0 + sunBrightness - rainFactor);\n" +
            //   SALS caster height: the pack raises water by +3.5 (shadow.glsl:103) so shafts get extreme near water.
            "        gl_FragData[0] = vec4(wc, 1.0);\n" +
            "        gl_FragData[1] = vec4(c2, 0.25 + max((position.y + 3.5) * 0.05, 0.0));\n" +
            "    } else if (yumelium_shadowTranslucent > 0.5 && mat == 32004) {\n" +
            // ICE (shadow.glsl:177-184): double-squared sprite colour, alpha-aware mix, ×0.14 store.
            "        vec4 c1 = texSample;\n" +
            "        c1.rgb *= c1.rgb; c1.rgb *= c1.rgb;\n" +
            "        c1.rgb = mix(vec3(1.0), c1.rgb, pow(c1.a, (1.0 - c1.a) * 0.5) * 1.05);\n" +
            "        c1.rgb *= 1.0 - pow(c1.a, 64.0);\n" +
            "        c1.rgb *= 0.14;\n" +
            "        gl_FragData[0] = vec4(c1.rgb, c1.a);\n" +
            "        gl_FragData[1] = vec4(normalize(pow(c1.rgb, vec3(0.25)) + 1e-5) * 0.5, 0.25 + max(position.y * 0.05, 0.0));\n" +
            "    } else if (yumelium_shadowTranslucent > 0.5 && (mat == 32008 || mat == 32012 || mat == 32016)) {\n" +
            // PLAIN glass / pane / beacon (shadow.glsl:189-199): faint neutral; 0.075 = 0.1*(1-GLASS_OPACITY 0.25).
            // SALS height 0 for the glass family (86AHGA: big glass planes must not read as roofs).
            "//YL_CONNECTED_GLASS_RESAMPLE_PLAIN\n" +
            "        vec4 c1 = texSample.a > 0.5 ? vec4(0.0, 0.0, 0.0, 1.0) : vec4(vec3(0.075), texSample.a);\n" +
            "        gl_FragData[0] = c1;\n" +
            "        gl_FragData[1] = vec4(vec3(0.3), 0.25);\n" +
            "    } else if (yumelium_shadowTranslucent > 0.5) {\n" +
            // STAINED/TINTED GLASS + every other translucent caster: the pack's DoNaturalShadowCalculation
            // (shadow.glsl:39-46) — the alpha-aware mix that turns the sprite colour into a COLOURED shadow
            // (a→1 collapses to black, so opaque-ish translucents behave like solids). ×0.1 store (423HDSS);
            // shadowcolor1.rgb = normalized hue ×0.5 so light shafts through the glass are tinted too.
            "//YL_CONNECTED_GLASS_RESAMPLE\n" +
            "        vec4 c1 = vec4(texSample.rgb * glColor.rgb, texSample.a);\n" +
            "        c1.rgb = mix(vec3(1.0), c1.rgb, pow(c1.a, (1.0 - c1.a) * 0.5) * 1.05);\n" +
            "        c1.rgb *= 1.0 - pow(c1.a, 64.0);\n" +
            "        c1.rgb *= 0.1;\n" +
            "        float salsA = (mat == 30008 || mat >= 31000) ? 0.25 : 0.25 + max(position.y * 0.05, 0.0);\n" +
            "        gl_FragData[0] = vec4(c1.rgb, c1.a);\n" +
            "        gl_FragData[1] = vec4(normalize(c1.rgb + 1e-5) * 0.5, salsA);\n" +
            "    } else {\n" +
            // .a of gl_FragData[1] is the SALS caster height (pack encoding, see the light-shaft work) — keep it on
            // both paths or scene-aware light shafts break.
            "        gl_FragData[0] = vec4(0.0, 0.0, 0.0, 1.0);\n" +
            "        gl_FragData[1] = vec4(vec3(0.3), 0.25 + max(position.y * 0.05, 0.0));\n" +
            "    }\n" +
            "}\n";

    /** Transforms the pack's {@code shadow} program VERTEX into a Sodium chunk-shader-dialect shadow vertex: same builtin
     * substitution + OptiFine-attribute synthesis as terrain, but with {@link #SHADOW_VERTEX_MAIN_WRAPPER} (the pack sets
     * its own distorted gl_Position). Paired with {@link #SHADOW_FRAGMENT}. */
    public static String transformShadowVertex(String packVertex) {
        String body = stripVersion(packVertex);
        body = replaceWord(body, "varying", "out");
        body = substituteVertexBuiltins(body);
        body = body.replaceAll("(?m)^\\s*attribute\\s+vec4\\s+(mc_Entity|at_midBlock|mc_midTexCoord|at_tangent)\\s*;.*$", "");
        body = body.replaceAll("\\bvoid\\s+main\\s*\\(", "void iris_pack_main(");
        return VERTEX_PREAMBLE + body + SHADOW_VERTEX_MAIN_WRAPPER;
    }

    public static String transformVertex(String packVertex) {
        String body = stripVersion(packVertex);
        body = replaceWord(body, "varying", "out");
        body = substituteVertexBuiltins(body);
        // Rename the pack's entry point(s) → iris_pack_main and call it from a wrapper main that runs _iris_terrain_init()
        // (the Sodium decode) first. A wrapper (not injecting INTO main) is robust for real packs whose combined .glsl has
        // TWO `void main`s — a vertex one and a fragment one in mutually-exclusive #ifdef VERTEX_SHADER/FRAGMENT_SHADER
        // blocks. Only the active (vertex) one survives preprocessing, so exactly one iris_pack_main exists to call; the
        // old inject-into-the-first-main approach could land the init inside the #ifdef'd-out fragment main → init never
        // ran → the Sodium uniforms (u_RegionOffset, ...) were dead-code-eliminated → ChunkShaderInterface's bindUniform
        // threw "No uniform exists".
        // Strip the pack's OptiFine attribute decls — we declare + synthesize these in the shared preamble instead (their
        // unbound `attribute` form read as 0, so mc_Entity was always 0 → no foliage id, no waving).
        body = body.replaceAll("(?m)^\\s*attribute\\s+vec4\\s+(mc_Entity|at_midBlock|mc_midTexCoord|at_tangent)\\s*;.*$", "");
        body = body.replaceAll("\\bvoid\\s+main\\s*\\(", "void iris_pack_main(");
        return VERTEX_PREAMBLE + body + VERTEX_MAIN_WRAPPER;
    }

    /**
     * A real pack (#version 130+, OptiFine dialect: {@code attribute}, {@code texture2DLod}, multi-buffer
     * {@code gl_FragData[n]}). These need the light-touch path: keep the legacy builtins/functions (our GL is a
     * compatibility profile) instead of the built-in pack's fuller #120→#330 rewrite (which would force a single
     * {@code out fragColor}, colliding with the pack's {@code gl_FragData[n]} writes).
     */
    private static boolean isLegacy(String source) {
        Matcher m = Pattern.compile("#version\\s+(\\d+)").matcher(source);
        return m.find() && Integer.parseInt(m.group(1)) >= 130;
    }

    public static String transformFragment(String packFragment) {
        return transformFragment(packFragment, "terrain");
    }

    /** @param what only names the dump file — gbuffers_water reaches transformFragmentLegacy through this same call,
     *  and both writing to one filename made the dump silently show whichever compiled last. */
    public static String transformFragment(String packFragment, String what) {
        if (isLegacy(packFragment)) {
            return transformFragmentLegacy(packFragment, what);
        }
        String body = stripVersion(packFragment);
        body = replaceWord(body, "varying", "in");
        // Sampler names → the samplers Sodium's ChunkShaderInterface binds (block atlas = unit 0, lightmap = unit 1).
        body = replaceWord(body, "gtexture", "u_BlockTex");
        body = replaceWord(body, "lightmap", "u_LightTex");
        // texture2D(...) → texture(...) for the core profile (do this AFTER sampler renames so we don't touch names).
        body = body.replace("texture2D(", "texture(");
        // Apply Sodium's per-material mip bias to block-atlas samples (−4.0 on cutout ≈ disables mipmapping) so
        // grass/foliage edges don't mip-blend the black transparent texels into visible black fringes. Only the
        // block-texture sampler is biased (not the lightmap). Matches block_layer_opaque's texture(..., mipBias).
        body = body.replaceAll("texture\\(\\s*u_BlockTex\\s*,\\s*([^,()]*)\\)", "texture(u_BlockTex, $1, iris_MipBias)");
        // Fragment outputs → the bound `fragColor`.
        body = body.replace("gl_FragData[0]", "fragColor");
        body = replaceWord(body, "gl_FragColor", "fragColor");
        // Rename the pack's entry point so our wrapper main can run it and then always emit the view-space normal to
        // colortex1 (unless the pack discarded). This makes the geometric normal G-buffer pack-agnostic.
        body = body.replaceFirst("void\\s+main", "void iris_pack_main");
        return FRAGMENT_PREAMBLE + body + FRAGMENT_MAIN_WRAPPER;
    }

    /**
     * Light-touch fragment transform for real packs (#130+): just the compat version header + map the pack's block/
     * lightmap samplers to Sodium's. The pack keeps its own {@code gl_FragData[n]} multi-buffer writes, {@code texture2D}/
     * {@code texture2DLod}, and its own G-buffer outputs (no wrapper, no forced {@code out fragColor}).
     */
    /** DIAGNOSTIC: override the terrain fragment to output the RAW atlas sample (bypassing the pack's color processing),
     * to isolate whether a "gray terrain" is the texture/UV itself or the pack's IPBR/material processing. */
    private static final boolean DEBUG_RAW_TEX = false;

    /** DIAGNOSTIC: override the terrain fragment output with the packed lighting inputs (R=vertex colour tint*AO
     * luminance, G=lmCoord.y skylight, B=sunVisibility) so the colortex0 readback shows which term is dark and causes
     * the "daytime but night-dark" terrain. Pair with IrisPipeline.DIAG_SHOW_GBUFFER=true to display colortex0 raw. */
    private static final boolean DEBUG_LIGHTING_VIZ = false;

    /** DIAGNOSTIC (grass "black stripes"): run the pack fragment normally, then for FOLIAGE pixels replace the lit colour
     * with the RAW atlas albedo (no lighting). Compare grass with this ON vs OFF: if the dark stripes VANISH (grass turns
     * a flat green) → the darkening is added by LIGHTING (DoLighting / composite); if they PERSIST → it's the texture/UV
     * itself. {@code mat} is the flat foliage id from mc_Entity; {@code tex}/{@code texCoord} are the pack's atlas + UV.
     * RESULT: the stripes PERSISTED in the raw albedo → they are atlas-mipmap black fringes (fixed via iris_MipBias). */
    private static final boolean DEBUG_FOLIAGE_ALBEDO = false;

    /** DIAGNOSTIC: write the exact transformed terrain/water fragment handed to GL to iris_&lt;what&gt;_dump.fsh in the
     * run directory on every successful compile (~1.4MB water + ~0.8MB terrain each time). 2026-07-27 audit #7: this
     * write used to be unconditional — gated per the DIAG_* convention. Failure diagnostics are unaffected:
     * ShaderChunkRenderer writes its own iris_water_dump pair in its catch block when a compile actually fails. */
    private static final boolean DIAG_DUMP_TRANSFORMED = false;

    private static String transformFragmentLegacy(String packFragment, String what) {
        String body = stripVersion(packFragment);
        body = replaceWord(body, "gtexture", "u_BlockTex");
        body = replaceWord(body, "lightmap", "u_LightTex");
        // Force the per-material mip bias onto the atlas albedo samples (as the non-legacy path does). Without it the real
        // pack's legacy fragment samples the block atlas at the AUTO mip level, where a cutout sprite's transparent BLACK
        // border texels bleed into the grass/foliage through mipmapping → the checkered black fringes on grass. iris_MipBias
        // is −4 for cutout (≈ forces level 0, no bleed) and 0 for solid/mipped (leaves keep their mipmaps), so it is correct
        // per-material. Only plain texture2D(...) atlas reads are biased; texture2DLod (explicit LOD) reads are left alone.
        body = body.replaceAll("texture2D\\(\\s*(tex|u_BlockTex)\\s*,\\s*([^,()]*)\\)", "texture2D($1, $2, iris_MipBias)");
        // Per-material alpha cutoff on the pack's albedo discard (TERRAIN only; water is the translucent layer, whose
        // materials carry cutoff 0.0 — its transparent texels must BLEND, not discard). Complementary's own threshold is
        // 0.00001, tuned for textures whose transparent texels are EXACTLY alpha 0 — mods ship textures where "transparent"
        // is alpha 1/255 (≈0.004, e.g. Betweenlands pale grass): those fragments survived, wrote DEPTH over the whole
        // cross-quad while drawing nearly-invisible colour, and depth-tested effects drawn later (BL's RenderWorldLast gas
        // clouds) were erased in quad-shaped holes. Vanilla, OptiFine and our own shaders-off path all kill such texels
        // with the per-layer alpha test (0.1 cutout / 0.5 mipped) — iris_AlphaCutoff is exactly that value per material
        // (chunk_material.glsl), so ON now matches OFF. Anchored on the pack's marker comment; a pack update that drops
        // the anchor loses only this hardening, and the miss is logged loudly below.
        if ("terrain".equals(what)) {
            String anchor = "if (color.a <= 0.00001) discard; // 6WIR4HT23";
            String replaced = body.replace(anchor,
                    "if (color.a <= max(0.00001, iris_AlphaCutoff)) discard; // 6WIR4HT23 [+ yumelium per-material cutoff]");
            if (replaced.equals(body)) {
                me.jellysquid.mods.sodium.client.SodiumClientMod.logger().warn(
                        "[Yumelium] terrain alpha-cutoff anchor MISSING (pack updated?) — low-alpha 'transparent' texels"
                        + " of modded cutout blocks will write depth and punch holes in late-drawn effects");
            } else {
                body = replaced;
            }
        }
        String tail = "";
        if (DEBUG_LIGHTING_VIZ) {
            body = body.replaceAll("\\bvoid\\s+main\\s*\\(", "void iris_pack_main(");
            // R = vertex colour (Sodium's tint*AO, multiplied into albedo at `color.rgb *= glColor.rgb`) luminance,
            // G = lmCoord.y (skylight, gates sun + ambient), B = sunVisibility. All three should be high (~1) outdoors
            // at day; whichever is low is the term killing the terrain lighting.
            // R = gbufferModelView[1].y uniform (view-space world-up.y; ~0.997 if the uniform is set, 0.5 if it's an
            // unset all-zero mat4), G = flat-in upVec.y from the vertex (0.997 if the vertex computed it), B =
            // sunVisibility. Remapped *0.5+0.5 since colortex0 (R11F_G11F_B10F) can't store negatives.
            tail = "\nvoid main() {\n" +
                   "    iris_pack_main();\n" +
                   "    gl_FragData[0] = vec4(gbufferModelView[1].y * 0.5 + 0.5, upVec.y * 0.5 + 0.5, sunVisibility, 1.0);\n" +
                   "}\n";
        } else if (DEBUG_RAW_TEX) {
            // Rename the pack's entry, run it, then overwrite gl_FragData[0] with the raw atlas sample (Complementary's
            // atlas sampler is `tex`, its diffuse varying is `texCoord`). Textured → the pack processing grays it; still
            // gray → the texture/UV binding is wrong.
            body = body.replaceAll("\\bvoid\\s+main\\s*\\(", "void iris_pack_main(");
            // RAW ATLAS SAMPLE: now that the pack terrain vertex gets real gbufferModelView[Inverse] uniforms (so
            // gl_Position is valid and geometry rasterizes) and its texCoord flows from Sodium's decoded UV, sample the
            // block atlas directly (Complementary's atlas sampler is `tex`, its diffuse varying `texCoord`). Bypass the
            // pack's own fragment (iris_pack_main) for now — it reads ~100 more uniforms we don't set yet. If real block
            // textures appear here, the pack terrain SHADER pipeline (vertex decode + atlas) is working end-to-end.
            tail = "\nvoid main() { gl_FragData[0] = texture2D(tex, texCoord); }\n";
        } else if (DEBUG_FOLIAGE_ALBEDO) {
            body = body.replaceAll("\\bvoid\\s+main\\s*\\(", "void iris_pack_main(");
            // Normal pack fragment, but foliage pixels show the raw albedo (no lighting) — see DEBUG_FOLIAGE_ALBEDO.
            tail = "\nvoid main() {\n" +
                   "    iris_pack_main();\n" +
                   "    if (mat == 10003 || mat == 10005 || mat == 10021 || mat == 10023 || mat == 10029) {\n" +
                   "        gl_FragData[0] = vec4(texture2D(tex, texCoord).rgb, 1.0);\n" +
                   "    }\n" +
                   "}\n";
        }
        String out = "#version 330 compatibility\n"
                + "// ---- transformed pack gbuffers_terrain (fragment, real-pack legacy path) ----\n"
                + "in vec2 iris_dbgUV;\n"
                + "in float iris_MipBias;\n" // per-material atlas mip bias (see the texture2D bias above)
                + "in float iris_AlphaCutoff;\n" // per-material alpha cutoff (see the terrain discard rewrite above)
                + body + tail;
        if (DIAG_DUMP_TRANSFORMED) {
            dump("iris_" + what + "_dump.fsh", out);
        }
        return out;
    }

    /**
     * Writes the EXACT source handed to GL, so what the terrain shader really compiles can be read instead of inferred
     * from the pack file plus a chain of transforms — the pack's gbuffers_terrain reaches GL through applyOptions
     * (options + every DIAG_* source injection) and then transformFragment's legacy branch, and a claim about what
     * survives all that is worth exactly nothing unverified. Call sites gate this behind DIAG_* flags
     * (DIAG_DUMP_TRANSFORMED here, DIAG_HAND_PIXEL in IrisPipeline); on-failure dumps stay unconditional.
     */
    static void dump(String name, String src) {
        try {
            java.nio.file.Files.write(java.nio.file.Paths.get(name),
                    src.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }
    }

    // --- helpers ---

    /** Removes the leading {@code #version ...} directive (we emit our own #330 header). */
    private static String stripVersion(String src) {
        return src.replaceFirst("(?m)^\\s*#version[^\\n]*\\n?", "");
    }

    /**
     * Rewrites a pack program's own {@code #version} to {@code 420 compatibility} for the immediate-mode programs we
     * compile untransformed (sky/hand/entities/block/textured/weather).
     *
     * <p>Complementary declares every program {@code #version 130}, where the {@code layout(...)} qualifier does not
     * exist — it arrives only with GLSL 1.40, or earlier via {@code #extension GL_ARB_shader_image_load_store}. The
     * composites get away with it because their extension directive sits on line 1 of worldSpaceRef.glsl; but
     * gbuffers_block's is buried at program/gbuffers_block.glsl:361 (inside {@code #ifdef END_PORTAL_BEAM_INTERNAL}),
     * too late to be honoured, so its next line — {@code layout(r32i) uniform iimage2D endcrystal_img;} — was a syntax
     * error and the WHOLE program silently fell back to gbuffers_entities. At 420 image load/store is core, so no
     * {@code #extension} is needed at any position. Compatibility keeps the legacy builtins the pack relies on
     * ({@code attribute}, {@code gl_FragData}, {@code ftransform}, {@code texture2D}); terrain/water/shadow already
     * compile this same pack code at 420/330 compatibility.
     */
    public static String raiseVersion(String packSrc) {
        return packSrc.replaceFirst("(?m)^\\s*#version[^\\n]*", "#version 420 compatibility");
    }

    /** Substitutes the fixed-function vertex builtins with the Sodium-decoded {@code iris_*} values / uniforms. */
    private static String substituteVertexBuiltins(String src) {
        // NOTE(real packs): the pack terrain vertex builds its clip position through the Iris G-buffer matrices
        // (gbufferModelView / gbufferModelViewInverse), which are Iris *uniforms*. We now bind + set those on Sodium's
        // terrain program (ChunkShaderInterface) from the same ChunkRenderMatrices Sodium draws with, so they hold real
        // values instead of an unset all-zero mat4 (which made gl_Position collapse to 0 → every vertex clipped → NO
        // terrain rendered). Do NOT string-substitute them here: that also mangles the `uniform mat4 gbufferModelView;`
        // declaration into `uniform mat4 mat4(1.0);` → a syntax error → pack fails → iris_terrain fallback.
        // Order matters: longer / superstring tokens first (e.g. NormalMatrix before Normal, MVP before ModelView).
        src = src.replace("gl_ModelViewProjectionMatrix", "iris_MVP");
        src = src.replace("gl_ModelViewMatrix", "u_ModelViewMatrix");
        src = src.replace("gl_ProjectionMatrix", "u_ProjectionMatrix");
        // The chunk model-view has no non-uniform scale, so its upper-left 3x3 IS the normal matrix (world → view).
        src = src.replace("gl_NormalMatrix", "mat3(u_ModelViewMatrix)");
        src = src.replace("gl_TextureMatrix[0]", "mat4(1.0)");
        src = src.replace("gl_TextureMatrix[1]", "mat4(1.0)");
        src = src.replace("gl_MultiTexCoord0", "iris_MultiTexCoord0");
        src = src.replace("gl_MultiTexCoord1", "iris_MultiTexCoord1");
        src = src.replace("ftransform()", "(iris_MVP * iris_Vertex)");
        // Whole-word: a plain replace of "gl_Vertex" also corrupts "gl_VertexID" → "iris_VertexID" (undefined). The
        // shadow vertex's voxelization uses gl_VertexID (a valid #version 420 builtin, left untouched) → only standalone
        // gl_Vertex becomes iris_Vertex. (\b after the 'x' fails inside gl_VertexID since 'x' and 'I' are both word chars.)
        src = replaceWord(src, "gl_Vertex", "iris_Vertex");
        src = src.replace("gl_Color", "iris_Color");
        // Real per-vertex geometric normal (world/model space) decoded by chunk_vertex.glsl, instead of the old stub.
        src = src.replace("gl_Normal", "_vert_normal");
        return src;
    }

    /** Replaces whole-word occurrences of {@code word} (so it won't clip inside a longer identifier). */
    private static String replaceWord(String src, String word, String replacement) {
        return Pattern.compile("\\b" + Pattern.quote(word) + "\\b")
                .matcher(src)
                .replaceAll(Matcher.quoteReplacement(replacement));
    }
}
