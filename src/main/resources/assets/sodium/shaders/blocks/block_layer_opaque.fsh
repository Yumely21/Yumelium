#version 330 core

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
    vec4 diffuseColor = texture(u_BlockTex, v_TexCoord, v_MaterialMipBias);

#ifdef USE_FRAGMENT_DISCARD
    if (diffuseColor.a < v_MaterialAlphaCutoff) {
        discard;
    }
#endif

    // yumelium: for flagged materials (water), collapse the texture to grayscale so the biome vertex color fully
    // determines the hue — the newer-Minecraft way (grayscale water texture x biome tint), giving per-biome water
    // colours 1.12.2's blue texture otherwise can't produce.
    //
    // NOTE: 1.12.2's water_still/water_flow have a CONSTANT blue channel (~244) — all the ripple detail is in the
    // R/G channels, so max(r,g,b) would give a flat, detail-less gray. We use luminance (captures the ripples) and
    // stretch its narrow dark band (~0.29..0.47 for vanilla water) up to a bright range so the tint reads as a clean
    // colour like newer MC's bright grayscale texture. Constants are tuned to the vanilla water texture.
    if (v_MaterialDesaturate > 0.5) {
        float luma = dot(diffuseColor.rgb, vec3(0.299, 0.587, 0.114));
        float t = clamp((luma - 0.29) / 0.18, 0.0, 1.0);
        diffuseColor.rgb = vec3(0.65 + t * 0.35);
    }

#ifdef USE_VANILLA_COLOR_FORMAT
    // Apply per-vertex color. AO shade is applied ahead of time on the CPU.
    diffuseColor *= v_Color;
#else
    // Apply per-vertex color
    diffuseColor.rgb *= v_Color.rgb;

    // Apply ambient occlusion "shade"
    diffuseColor.rgb *= v_Color.a;
#endif

    // yumelium: gate fog by USE_FOG. With fog disabled (ChunkFogMode.NONE — no fog defines), neither the SMOOTH nor
    // EXP2 uniforms exist, so the linear path below must not be compiled; just output the unfogged colour.
#ifdef USE_FOG
#ifdef USE_FOG_EXP2
    fragColor = _exp2Fog(diffuseColor, v_FragDistance, u_FogColor, u_FogDensity);
#else
    fragColor = _linearFog(diffuseColor, v_FragDistance, u_FogColor, u_FogStart, u_FogEnd);
#endif
#else
    fragColor = diffuseColor;
#endif
}