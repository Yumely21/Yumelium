#version 120

// Yumelium built-in test pack — block entities / TESRs (chests, signs, banners, beds, ...). Fixed-function immediate
// mode; same standard-item-lighting model as regular entities (two world-fixed directional lights + 0.4 ambient), so
// the two light directions arrive in EYE space from the pipeline (world dirs × gbufferModelView). See gbuffers_entities.
uniform vec3 yl_ItemLightDir0;
uniform vec3 yl_ItemLightDir1;

varying vec2 lmcoord;
varying float itemLight;

void main() {
    gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
    gl_TexCoord[0] = gl_TextureMatrix[0] * gl_MultiTexCoord0;
    gl_FrontColor = gl_Color;
    lmcoord = (gl_TextureMatrix[1] * gl_MultiTexCoord1).st;

    vec3 N = normalize(gl_NormalMatrix * gl_Normal);
    itemLight = min(0.4 + 0.6 * max(dot(N, yl_ItemLightDir0), 0.0) + 0.6 * max(dot(N, yl_ItemLightDir1), 0.0), 1.0);
}
