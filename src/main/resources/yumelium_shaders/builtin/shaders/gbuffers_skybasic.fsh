#version 120

/* DRAWBUFFERS:0 */
void main() {
    // Neutral pass-through: the built-in reference pack reproduces the vanilla sky exactly (the proof-of-life warm tint
    // was confirmed via the log + is removed now). Real packs bring their own gbuffers_skybasic to recolour the sky.
    gl_FragData[0] = gl_Color;
}
