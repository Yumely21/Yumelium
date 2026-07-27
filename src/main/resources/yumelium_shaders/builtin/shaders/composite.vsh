#version 120

// Yumelium built-in test pack — full-screen composite pass.
varying vec2 texcoord;

void main() {
    gl_Position = ftransform();
    texcoord = gl_MultiTexCoord0.st;
}
