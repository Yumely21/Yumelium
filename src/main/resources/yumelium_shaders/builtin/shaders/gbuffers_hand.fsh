#version 120

uniform sampler2D gtexture;  // unit 0: the item/arm/block atlas texture bound by MC for the hand
uniform sampler2D lightmap;  // unit 1: block+sky lightmap at the player's position

varying vec2 lmcoord;
varying float itemLight;

/* DRAWBUFFERS:0 */
void main() {
    vec4 albedo = texture2D(gtexture, gl_TexCoord[0].st) * gl_Color;
    // Cutout item edges (e.g. tools, plants held in hand); the opaque arm/blocks keep alpha ~1.
    if (albedo.a < 0.1) {
        discard;
    }
    albedo.rgb *= itemLight;               // vanilla two-light directional item shading
    albedo *= texture2D(lightmap, lmcoord); // block+sky light where the player stands
    gl_FragData[0] = albedo;
}
