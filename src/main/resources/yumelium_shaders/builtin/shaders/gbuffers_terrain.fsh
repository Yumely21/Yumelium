#version 120

uniform sampler2D gtexture;
uniform sampler2D lightmap;

varying vec2 texcoord;
varying vec2 lmcoord;
varying vec4 color;

/* DRAWBUFFERS:0 */
void main() {
    vec4 albedo = texture2D(gtexture, texcoord);

    // Cutout alpha test on the raw texture (leaves/grass gaps); a no-op for fully opaque solid blocks, and keeps
    // partially-transparent (water/glass) fragments. Done before applying vertex colour so baked AO doesn't affect it.
    if (albedo.a < 0.1) {
        discard;
    }

    // Water (and other desaturated materials): collapse the texture to grayscale so the biome vertex colour sets the hue,
    // matching Sodium's block_layer_opaque — this gives the calmer per-biome water instead of the raw vivid-blue texture.
    // iris_Desaturate is supplied by the transformer from Sodium's _material_desaturate flag.
    if (iris_Desaturate > 0.5) {
        float luma = dot(albedo.rgb, vec3(0.299, 0.587, 0.114));
        float t = clamp((luma - 0.29) / 0.18, 0.0, 1.0);
        albedo.rgb = vec3(0.65 + t * 0.35);
    }

    albedo *= color;                          // vertex colour (biome tint + baked AO shade)
    // Block + sky light. Clamp the coord to texel centres [0.5/16, 15.5/16] EXACTLY like Sodium's _sample_lightmap —
    // without it the full-brightness sky coord lands on a texel boundary and LINEAR-blends toward the darker neighbour,
    // dimming the whole terrain (this is why the pack terrain looked ~40% darker than Sodium's own block_layer_opaque).
    albedo *= texture2D(lightmap, clamp(lmcoord, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
    gl_FragData[0] = albedo;
}
