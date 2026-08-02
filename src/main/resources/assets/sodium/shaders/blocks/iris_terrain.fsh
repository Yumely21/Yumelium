#version 330 core

// Iris/Oculus port (M5) — the swapped terrain fragment shader used when the shader pipeline is active. A copy of
// block_layer_opaque.fsh plus a PROOF-OF-LIFE marker tint at the end, so it is visually obvious that terrain is drawn
// by the swapped (pack) terrain program rather than Sodium's own. The pack's real gbuffers_terrain replaces this later.

#import <sodium:include/fog.glsl>

in vec4 v_Color; // The interpolated vertex color
in vec2 v_TexCoord; // The interpolated block texture coordinates
#ifdef USE_FOG
in float v_FragDistance; // The fragment's distance from the camera
#endif

in float v_MaterialMipBias;
in float v_MaterialDesaturate;
in float v_MaterialAlphaCutoff;

uniform sampler2D u_BlockTex; // The block texture

uniform vec4 u_FogColor; // The color of the shader fog

#ifdef USE_FOG_SMOOTH
uniform float u_FogStart; // The starting position of the shader fog
uniform float u_FogEnd; // The ending position of the shader fog
#endif

#ifdef USE_FOG_EXP2
uniform float u_FogDensity; // The density of the shader fog
#endif

out vec4 fragColor; // The output fragment for the color framebuffer

void main() {
    // Keep this sampling in step with block_layer_opaque.fsh: the alpha-tested pass samples the BASE level explicitly.
    // A mip BIAS is only ADDED to the derivative-computed LOD, so it cannot pin the level — distant foliage otherwise
    // resolves several mips down, where MC's atlas blends a sprite with the transparent BLACK around it and the leaf
    // goes black. This file is the fallback the pipeline falls back to when the pack's gbuffers_terrain transform
    // throws, so it must not reintroduce a bug that is fixed in the primary path.
#ifdef USE_FRAGMENT_DISCARD
    vec4 diffuseColor = textureLod(u_BlockTex, v_TexCoord, 0.0);
#else
    vec4 diffuseColor = texture(u_BlockTex, v_TexCoord, v_MaterialMipBias);
#endif

#ifdef USE_FRAGMENT_DISCARD
    if (diffuseColor.a < v_MaterialAlphaCutoff) {
        discard;
    }
#endif

    if (v_MaterialDesaturate > 0.5) {
        float luma = dot(diffuseColor.rgb, vec3(0.299, 0.587, 0.114));
        float t = clamp((luma - 0.29) / 0.18, 0.0, 1.0);
        diffuseColor.rgb = vec3(0.65 + t * 0.35);
    }

#ifdef USE_VANILLA_COLOR_FORMAT
    diffuseColor *= v_Color;
#else
    diffuseColor.rgb *= v_Color.rgb;
    diffuseColor.rgb *= v_Color.a;
#endif

#ifdef USE_FOG
#ifdef USE_FOG_EXP2
    fragColor = _exp2Fog(diffuseColor, v_FragDistance, u_FogColor, u_FogDensity);
#else
    fragColor = _linearFog(diffuseColor, v_FragDistance, u_FogColor, u_FogStart, u_FogEnd);
#endif
#else
    fragColor = diffuseColor;
#endif

    // Yumelium M5 proof-of-life marker: a warm tint so it is obvious the swapped (iris) terrain program is drawing.
    fragColor.rgb *= vec3(1.15, 1.02, 0.80);
}
