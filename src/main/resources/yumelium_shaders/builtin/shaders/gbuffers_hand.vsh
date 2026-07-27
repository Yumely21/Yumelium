#version 120

// Yumelium built-in test pack — first-person hand + held item (immediate mode, compatibility profile).
// Reproduces vanilla "standard item lighting" (two eye-space directional lights + 0.4 ambient) so the held item keeps
// its directional face shading: a bound GLSL program bypasses the fixed-function GL_LIGHTING that vanilla uses for the
// hand, so we must recompute that lighting here or held blocks look flat.
varying vec2 lmcoord;
varying float itemLight;

void main() {
    gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
    gl_TexCoord[0] = gl_TextureMatrix[0] * gl_MultiTexCoord0; // texture matrix carries the enchantment-glint scroll
    gl_FrontColor = gl_Color;
    // The lightmap coord uses unit 1's texture matrix (MC's enableLightmap sets scale 1/256 + translate 8).
    lmcoord = (gl_TextureMatrix[1] * gl_MultiTexCoord1).st;

    // Vanilla RenderHelper.enableStandardItemLighting: two directional lights (diffuse 0.6 each) + 0.4 global ambient,
    // color-material AMBIENT_AND_DIFFUSE. Lights are eye-space directions; gl_NormalMatrix * gl_Normal is the eye normal.
    const vec3 L0 = vec3(0.26726124, 0.80178368, -0.53452247); // normalize(0.2, 1.0, -0.7)
    const vec3 L1 = vec3(-0.26726124, 0.80178368, 0.53452247); // normalize(-0.2, 1.0, 0.7)
    vec3 N = normalize(gl_NormalMatrix * gl_Normal);
    itemLight = min(0.4 + 0.6 * max(dot(N, L0), 0.0) + 0.6 * max(dot(N, L1), 0.0), 1.0);
}
