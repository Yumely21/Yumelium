#version 120

// Yumelium built-in test pack — final pass to the screen.
varying vec2 texcoord;

void main() {
    gl_Position = ftransform();
    texcoord = gl_MultiTexCoord0.st;
}
