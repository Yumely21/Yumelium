#version 120

// Yumelium built-in test pack — minimal untextured geometry (leash, hitboxes, ...).
void main() {
    gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
    gl_FrontColor = gl_Color;
}
