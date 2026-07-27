#version 120

// Yumelium built-in test pack — regular entities (mobs, dropped items, ...). Fixed-function immediate mode.
// A bound GLSL program bypasses the fixed-function GL_LIGHTING that vanilla uses for entity shading (RenderHelper's
// two directional lights + 0.4 ambient), so we recompute it. The two light directions are supplied in EYE space by the
// pipeline (world dirs transformed by gbufferModelView) so the shading stays fixed in WORLD space as vanilla does — i.e.
// circling a mob keeps the same side lit (unlike the hand, whose lights are camera-relative).
uniform vec3 yl_ItemLightDir0;
uniform vec3 yl_ItemLightDir1;

varying vec2 lmcoord;
varying float itemLight;

void main() {
    gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
    gl_TexCoord[0] = gl_TextureMatrix[0] * gl_MultiTexCoord0;
    gl_FrontColor = gl_Color;
    // Per-entity lightmap coord (block+sky light at the entity's position); unit-1 texture matrix scales it (1/256 + 8).
    lmcoord = (gl_TextureMatrix[1] * gl_MultiTexCoord1).st;

    vec3 N = normalize(gl_NormalMatrix * gl_Normal);
    itemLight = min(0.4 + 0.6 * max(dot(N, yl_ItemLightDir0), 0.0) + 0.6 * max(dot(N, yl_ItemLightDir1), 0.0), 1.0);
}
