#version 120

uniform sampler2D gtexture;

/* DRAWBUFFERS:0 */
void main() {
    // Sun / moon: sample the bound celestial texture and modulate by the vertex colour (which carries the day/night
    // fade alpha). Kept neutral so the sun and moon look correct — their correct rendering proves skytextured runs.
    gl_FragData[0] = texture2D(gtexture, gl_TexCoord[0].st) * gl_Color;
}
