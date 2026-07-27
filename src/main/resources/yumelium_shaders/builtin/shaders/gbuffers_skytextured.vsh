#version 120

// Yumelium built-in test pack — textured sky (sun, moon). Fixed-function immediate mode (compatibility profile).
void main() {
    gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
    gl_TexCoord[0] = gl_TextureMatrix[0] * gl_MultiTexCoord0;
    gl_FrontColor = gl_Color;
}
