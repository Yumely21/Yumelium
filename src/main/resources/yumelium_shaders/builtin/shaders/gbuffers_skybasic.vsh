#version 120

// Yumelium built-in test pack — untextured sky (sky dome, sunrise/sunset glow, stars, void). Runs in Minecraft's
// fixed-function immediate mode (compatibility profile), so it reads the fixed-function builtins directly.
void main() {
    gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
    gl_FrontColor = gl_Color;
}
