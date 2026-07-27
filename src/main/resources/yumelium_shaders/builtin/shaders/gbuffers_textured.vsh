#version 120

// Yumelium built-in test pack — generic textured geometry with NO directional lighting (particles, ...). Fixed-function
// immediate mode. Particles are camera-facing billboards carrying their own per-vertex lightmap coord, so there is no
// meaningful surface normal to shade — just texture × vertex colour × lightmap, reproducing vanilla's fixed-function.
varying vec2 lmcoord;

void main() {
    gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
    gl_TexCoord[0] = gl_TextureMatrix[0] * gl_MultiTexCoord0;
    gl_FrontColor = gl_Color;
    lmcoord = (gl_TextureMatrix[1] * gl_MultiTexCoord1).st;
}
