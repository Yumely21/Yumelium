#version 120

uniform sampler2D gtexture;  // unit 0: the entity texture bound by its renderer
uniform sampler2D lightmap;  // unit 1: block+sky lightmap at the entity's position

varying vec2 lmcoord;
varying float itemLight;

/* DRAWBUFFERS:0 */
void main() {
    vec4 albedo = texture2D(gtexture, gl_TexCoord[0].st) * gl_Color;
    // Cutout entity edges (e.g. hair, capes, plants); opaque bodies keep alpha ~1.
    if (albedo.a < 0.1) {
        discard;
    }
    albedo.rgb *= itemLight;                // vanilla two-light directional entity shading
    albedo *= texture2D(lightmap, lmcoord); // block+sky light at the entity's position
    gl_FragData[0] = albedo;
}
