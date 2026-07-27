#version 120

uniform sampler2D gtexture;  // unit 0: the particle atlas (or other textured geometry)
uniform sampler2D lightmap;  // unit 1: block+sky lightmap

varying vec2 lmcoord;

/* DRAWBUFFERS:0 */
void main() {
    vec4 albedo = texture2D(gtexture, gl_TexCoord[0].st) * gl_Color;
    // Particles are alpha-blended; only drop fully-transparent texels so soft edges survive the blend.
    if (albedo.a < 0.01) {
        discard;
    }
    albedo *= texture2D(lightmap, lmcoord); // block+sky light (particles carry their own lightmap coord)
    gl_FragData[0] = albedo;
}
