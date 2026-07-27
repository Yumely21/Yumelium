const uint MATERIAL_USE_MIP_OFFSET = 0u;
const uint MATERIAL_ALPHA_CUTOFF_OFFSET = 1u;
// yumelium: bit 3 = desaturate the sampled texture before the vertex color is applied (used for water)
const uint MATERIAL_DESATURATE_OFFSET = 3u;
// yumelium: bits 4-5 = foliage category, so the Iris terrain shader can synthesize mc_Entity and the shader pack can
// identify + wave grass/leaves (0 = none, 1 = grounded foliage, 2 = leaves, 3 = upper foliage). See _material_foliage.
const uint MATERIAL_FOLIAGE_OFFSET = 4u;

const float[4] ALPHA_CUTOFF = float[4](0.0, 0.1, 0.5, 1.0);

float _material_mip_bias(uint material) {
    return ((material >> MATERIAL_USE_MIP_OFFSET) & 1u) != 0u ? 0.0 : -4.0;
}

float _material_alpha_cutoff(uint material) {
    return ALPHA_CUTOFF[(material >> MATERIAL_ALPHA_CUTOFF_OFFSET) & 3u];
}

bool _material_desaturate(uint material) {
    return ((material >> MATERIAL_DESATURATE_OFFSET) & 1u) != 0u;
}

uint _material_foliage(uint material) {
    return (material >> MATERIAL_FOLIAGE_OFFSET) & 3u;
}