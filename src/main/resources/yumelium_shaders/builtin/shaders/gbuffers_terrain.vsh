#version 120

// Yumelium built-in test pack — terrain geometry.
varying vec2 texcoord;
varying vec2 lmcoord;
varying vec4 color;

void main() {
    gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
    texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).st;
    lmcoord = (gl_TextureMatrix[1] * gl_MultiTexCoord1).st;
    color = gl_Color;
}
