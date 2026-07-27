#version 120

uniform sampler2D colortex0;
uniform sampler2D depthtex0;
uniform sampler2D colortex1;
uniform sampler2D shadowtex0;
uniform mat4 gbufferProjection;
uniform mat4 gbufferProjectionInverse;
uniform mat4 gbufferModelViewInverse;
uniform mat4 shadowModelViewProjection;
uniform vec3 shadowLightPosition;
uniform vec3 skyColor;
uniform vec3 fogColor;
uniform float fogStart;
uniform float fogEnd;
uniform float sunBrightness;
uniform float viewWidth;
uniform float viewHeight;

const float SHADOW_RES = 2048.0;

// Configurable options (toggled from the "Shader Pack Settings" screen — a commented-out #define = off). Declared in
// shaders.properties' `screen` directive.
#define SHADOWS            // sun cast shadows
#define DIRECTIONAL_LIGHT  // gentle sunlit-side brightening
#define SSAO               // ambient occlusion (contact shadows)
#define BLOOM              // soft glow on bright areas

// DIAGNOSTIC: 0 = normal shading with cast shadows; 2 = show the shadow depth map FULLSCREEN (contrast-stretched) to
// verify the composite samples shadowtex0 correctly; 3 = grayscale shadow TEST on terrain (black=shadow, white=lit).
#define DEBUG_SHADOW 0

// DIAGNOSTIC: 1 = skip the SSAO + cast-shadow darkening; 2 = PURE PASSTHROUGH of colortex0 (no composite processing at
// all) to isolate whether the "ground too dim" is already in the captured scene (terrain shader) or added by the composite.
#define DIAG_NO_DARKENING 0

varying vec2 texcoord;

vec3 viewPosFromDepth(vec2 uv, float depth) {
    vec4 clip = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 v = gbufferProjectionInverse * clip;
    return v.xyz / v.w;
}

// Screen-space ambient occlusion: samples a small hemisphere (oriented by the geometric normal) and counts how much
// nearby geometry occludes each point, darkening crevices/contact areas. Range-checked so block edges don't halo.
float computeAO(vec3 P, vec3 N) {
    const int SAMPLES = 12;
    float radius = 0.9;
    float bias = 0.02;

    float rnd = fract(sin(dot(texcoord, vec2(12.9898, 78.233))) * 43758.5453);
    vec3 rvec = vec3(cos(rnd * 6.2831853), sin(rnd * 6.2831853), 0.0);
    vec3 T = normalize(rvec - N * dot(rvec, N));
    vec3 B = cross(N, T);

    float occlusion = 0.0;
    for (int i = 0; i < SAMPLES; i++) {
        float a = (float(i) + 0.5) / float(SAMPLES);
        float ang = float(i) * 2.399963 + rnd * 6.2831853;
        float rad = sqrt(1.0 - a * a);
        vec3 s = vec3(cos(ang) * rad, sin(ang) * rad, a);       // hemisphere sample (local)
        s *= radius * (0.25 + 0.75 * a * a);                     // bias toward the surface
        vec3 samplePos = P + (T * s.x + B * s.y + N * s.z);

        vec4 clip = gbufferProjection * vec4(samplePos, 1.0);
        vec2 sUV = (clip.xy / clip.w) * 0.5 + 0.5;
        if (sUV.x < 0.0 || sUV.x > 1.0 || sUV.y < 0.0 || sUV.y > 1.0) {
            continue;
        }
        float sd = texture2D(depthtex0, sUV).r;
        if (sd >= 1.0) {
            continue;
        }
        float sampleZ = viewPosFromDepth(sUV, sd).z;
        float rangeCheck = smoothstep(0.0, 1.0, radius / max(abs(P.z - sampleZ), 1e-4));
        occlusion += (sampleZ >= samplePos.z + bias ? 1.0 : 0.0) * rangeCheck;
    }
    return 1.0 - (occlusion / float(SAMPLES));
}

// Sun shadow: transform the fragment (view space) into the light's orthographic clip space and PCF-compare against the
// shadow depth map. Returns 1.0 = fully lit, 0.0 = fully shadowed. Fragments beyond the shadow frustum are treated as lit.
float computeShadow(vec3 viewP) {
    vec3 worldP = (gbufferModelViewInverse * vec4(viewP, 1.0)).xyz;
    vec4 sclip = shadowModelViewProjection * vec4(worldP, 1.0);
    vec3 suv = (sclip.xyz / sclip.w) * 0.5 + 0.5;
    if (suv.x < 0.0 || suv.x > 1.0 || suv.y < 0.0 || suv.y > 1.0 || suv.z > 1.0) {
        return 1.0; // outside the shadow-distance box → no shadow
    }
    // Slope-scaled depth bias: at grazing sun angles (e.g. morning/evening), the shadow-map depth changes fast across a
    // surface, so a fixed bias can't separate a flat lit surface from its own occluder → the whole ground self-shadows
    // ("acne"), looking uniformly dim. Grow the bias with how fast suv.z changes per screen-pixel (clamped so genuine
    // shadows don't detach/peter-pan).
    float slope = max(abs(dFdx(suv.z)), abs(dFdy(suv.z)));
    float bias = 0.0010 + clamp(slope * 2.5, 0.0, 0.006);
    float texel = 1.0 / SHADOW_RES;
    float lit = 0.0;
    for (int xo = -1; xo <= 1; xo++) {
        for (int yo = -1; yo <= 1; yo++) {
            float occluder = texture2D(shadowtex0, suv.xy + vec2(float(xo), float(yo)) * texel).r;
            lit += (suv.z - bias <= occluder) ? 1.0 : 0.0;
        }
    }
    return lit / 9.0;
}

/* DRAWBUFFERS:0 */
void main() {
    vec3 color = texture2D(colortex0, texcoord).rgb;
    float depth = texture2D(depthtex0, texcoord).r;

#if DIAG_NO_DARKENING == 2
    // Pure passthrough: output the captured scene (colortex0) with zero composite processing. If the terrain still looks
    // dimmer than shaders-OFF here, the darkening is in the terrain shader / capture, not the composite lighting.
    gl_FragData[0] = vec4(color, 1.0);
    return;
#endif

#if DEBUG_SHADOW == 2
    // Four-quadrant sampler test — one screenshot shows which texture units the composite can actually sample:
    //   TOP-LEFT  = colortex0 (unit 0, scene)      TOP-RIGHT   = depthtex0 (unit 1, scene depth)
    //   BOT-LEFT  = colortex1 (unit 2, normal)     BOT-RIGHT   = shadowtex0 (unit 3, shadow depth, contrast-stretched)
    // colortex0/depthtex0 are known-good; if the bottom-right is black while others show content, the unit-3 bind/sample
    // is the problem. If bottom-left is also black, units 2 AND 3 are broken.
    {
        vec2 q = fract(texcoord * 2.0);
        vec3 c;
        if (texcoord.y >= 0.5 && texcoord.x < 0.5)      c = texture2D(colortex0, q).rgb;          // top-left
        else if (texcoord.y >= 0.5)                     c = vec3(texture2D(depthtex0, q).r);      // top-right
        else if (texcoord.x < 0.5)                      c = texture2D(colortex1, q).rgb;          // bottom-left
        else {                                                                                     // bottom-right
            float sd = texture2D(shadowtex0, q).r;
            c = vec3(clamp((sd - 0.3) / 0.7, 0.0, 1.0));
        }
        gl_FragData[0] = vec4(c, 1.0);
        return;
    }
#endif

    // Deferred lighting on world geometry (depth < 1); the sky (depth == 1) is left as vanilla. We prefer the crisp
    // per-face G-buffer normal (colortex1) the terrain shader writes, but that buffer is TERRAIN-ONLY: the hand, entities
    // and particles are drawn over the terrain in immediate mode and do NOT write it, so at those pixels colortex1 still
    // holds the normal of the terrain BEHIND them. A depth-consistency guard rejects that stale data (colortex1.a carries
    // the terrain fragment's own window depth; if it disagrees with the scene depth here, the normal is not ours) and
    // falls back to the per-pixel screen-space normal, which is derived from THIS pixel's own depth and always valid.
    // This is what lets the G-buffer normal be consumed without the hand looking see-through.
    if (depth < 1.0) {
        vec3 P = viewPosFromDepth(texcoord, depth);

        vec3 dPdx = dFdx(P);
        vec3 dPdy = dFdy(P);
        vec3 crossP = cross(dPdx, dPdy);
        float clen = length(crossP);

        vec4 gbuf = texture2D(colortex1, texcoord);
        bool useGNormal = dot(gbuf.rgb, gbuf.rgb) > 0.01 && abs(gbuf.a - depth) < 0.02;

        // Directional sun/moon shading + SSAO. The G-buffer normal is crisp (no edge suppression); the screen-space
        // fallback needs the degenerate-cross guard (thin/edge-on geometry → NaN) + edge suppression (the derivative
        // normal is unreliable at depth discontinuities).
        float directional = 1.0;
        float ao = 1.0;
        vec3 N;
        float edge = 0.0;
        bool haveN = false;
        if (useGNormal) {
            N = normalize(gbuf.rgb * 2.0 - 1.0);   // real per-face terrain normal
            haveN = true;
        } else if (clen > 1e-5) {
            N = crossP / clen;                      // screen-space normal (hand/entities/particles + degenerate terrain)
            edge = clamp((length(dPdx) + length(dPdy)) * 1.5, 0.0, 1.0);
            haveN = true;
        }
        if (haveN) {
#ifdef DIRECTIONAL_LIGHT
            float ndl = max(dot(N, normalize(shadowLightPosition)), 0.0);
            // Only BRIGHTEN sun-facing surfaces; never darken below neutral (1.0). The terrain already carries Sodium's
            // baked sky/block light + AO, so a directional term that dropped to 0.85 for non-sun-facing surfaces made the
            // whole ground look dim at low sun (flat ground has a small ndl then) — "daytime ground dark, only bright near
            // the sun". Additive-only keeps the base at the baked brightness with a gentle sunlit highlight.
            directional = mix(1.0, 1.0 + 0.18 * ndl, sunBrightness * (1.0 - edge));
#endif
#ifdef SSAO
            // AO is NOT edge-suppressed — crevices/edges are exactly where it should darken; the range check inside
            // computeAO limits haloing at big depth discontinuities instead.
            ao = computeAO(P, N);
#endif
        }
        color *= directional;
#ifdef SSAO
#if DIAG_NO_DARKENING == 0
        color *= mix(1.0, ao, 0.85);                 // ambient occlusion (contact shadows)
#endif
#endif

#ifdef SHADOWS
        // Cast shadows from the sun's shadow map. Robust at screen-space-normal edges (it uses world position, not the
        // derivative normal), and gated by sunBrightness so night has no hard shadows. Shadowed ground darkens by ~32%.
        float shadowFactor = computeShadow(P);
#if DEBUG_SHADOW == 3
        // Grayscale shadow test on terrain: black = shadowed, white = lit. Reveals whether the map content + aligns.
        gl_FragData[0] = vec4(vec3(shadowFactor), 1.0);
        return;
#endif
#if DIAG_NO_DARKENING == 0
        color *= mix(1.0, 0.40, (1.0 - shadowFactor) * clamp(sunBrightness * 2.0, 0.0, 1.0));
#endif
#endif

        // Sky-colour ambient lift in shadowed areas.
        float lum = dot(color, vec3(0.2126, 0.7152, 0.0722));
        color += skyColor * (1.0 - smoothstep(0.0, 0.30, lum)) * 0.05;

        // Vanilla-style distance fog toward the sky/fog colour.
        float dist = length(P);
        float fog = clamp((dist - fogStart) / max(fogEnd - fogStart, 1e-4), 0.0, 1.0);
        color = mix(color, fogColor, fog);
    }

#ifdef BLOOM
    // Cheap single-pass bloom: gather a small ring of the scene, keep only the parts brighter than a threshold, and add
    // a soft glow. Makes bright things (sun, snow, glowstone/torches, water sparkle) bloom — a recognizable shader look
    // without needing a separate blur buffer. The sample ring is ROTATED by a per-pixel hash + the two radii are jittered
    // so the discrete samples don't form regular dashed/moiré lines along high-contrast edges (e.g. the cloud/sky edge).
    vec2 px = vec2(1.0 / viewWidth, 1.0 / viewHeight);
    float jitter = fract(sin(dot(texcoord, vec2(12.9898, 78.233))) * 43758.5453);
    float baseAng = jitter * 6.2831853;
    vec3 bloom = vec3(0.0);
    for (int i = 0; i < 12; i++) {
        float ang = float(i) * 0.5235988 + baseAng; // 2*PI/12, rotated per-pixel
        vec2 dir = vec2(cos(ang), sin(ang));
        bloom += max(texture2D(colortex0, texcoord + dir * px * (2.5 + jitter)).rgb - 0.80, 0.0);
        bloom += max(texture2D(colortex0, texcoord + dir * px * (6.0 + 2.0 * jitter)).rgb - 0.80, 0.0);
    }
    bloom /= 24.0;
    color += bloom * 0.9;
#endif

    gl_FragData[0] = vec4(color, 1.0);
}
