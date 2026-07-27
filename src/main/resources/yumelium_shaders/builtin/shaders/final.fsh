#version 120

uniform sampler2D colortex0;
varying vec2 texcoord;

// Yumelium built-in test pack — plain pass-through: the final pass copies colortex0 to the screen unchanged.
void main() {
    gl_FragColor = texture2D(colortex0, texcoord);
}
