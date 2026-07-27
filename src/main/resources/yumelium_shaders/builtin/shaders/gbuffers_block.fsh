#version 120

uniform sampler2D gtexture;  // unit 0: the block-entity texture bound by its renderer
uniform sampler2D lightmap;  // unit 1: block+sky lightmap at the block entity's position

varying vec2 lmcoord;
varying float itemLight;

/* DRAWBUFFERS:0 */
void main() {
    vec4 albedo = texture2D(gtexture, gl_TexCoord[0].st) * gl_Color;
    if (albedo.a < 0.1) {
        discard;
    }
    albedo.rgb *= itemLight;                // vanilla two-light directional shading
    albedo *= texture2D(lightmap, lmcoord); // block+sky light at the block entity's position
    gl_FragData[0] = albedo;
}
