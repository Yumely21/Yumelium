// The position of the vertex around the model origin
vec3 _vert_position;

// The block texture coordinate of the vertex
vec2 _vert_tex_diffuse_coord;

// The light texture coordinate of the vertex
ivec2 _vert_tex_light_coord;

// The color of the vertex
vec4 _vert_color;

// The index of the draw command which this vertex belongs to
uint _draw_id;

// The material bits for the primitive
uint _material_params;

// The geometric face normal of the vertex (world/model space). Valid only when USE_VERTEX_NORMAL is defined (Iris
// shader mode meshes with the normal-extended compact format); otherwise it defaults to up so shaders that read it
// still get a sane value.
vec3 _vert_normal;

// The sprite-centre (mid) texture coordinate of the quad this vertex belongs to. Valid only with USE_VERTEX_NORMAL
// (Iris mode); otherwise defaults to the vertex's own tex coord. The transformed terrain shader exposes it as
// mc_midTexCoord so foliage waving can separate a plant's top (swaying) vertices from its anchored bottom ones.
vec2 _vert_mid_tex_coord;

// The Complementary block.properties id of this vertex's block (mc_Entity.x): identifies foliage (waving) + light
// sources (colored-lighting voxelization). Valid only with USE_VERTEX_NORMAL (Iris mode); 0 otherwise.
float _vert_block_id;

// The UV-aligned tangent (xyz) + handedness (w = ±1) of the quad this vertex belongs to (at_tangent). Valid only with
// USE_VERTEX_NORMAL (Iris mode); defaults to (1,0,0,1) otherwise.
vec4 _vert_tangent;

// The offset from this vertex to its block centre, ×64 (at_midBlock). Valid only with USE_VERTEX_NORMAL (Iris mode);
// 0 otherwise (→ voxelization falls back to the vertex position).
vec3 _vert_mid_block;

#ifdef USE_VERTEX_NORMAL
in vec4 a_Normal; // normalized signed bytes (NormI8): xyz in [-1, 1]
in vec2 a_MidTexCoord; // raw unsigned shorts (same TEXTURE scale as a_TexCoord)
in float a_BlockId; // raw unsigned short → float (the block.properties id)
in vec4 a_Tangent; // normalized signed bytes (NormI8): xyz tangent in [-1, 1], w = handedness ±1
in vec4 a_MidBlock; // raw signed bytes: xyz = (blockCentre - vertex) × 64
#endif

#ifdef USE_VERTEX_COMPRESSION
in uvec4 a_PosId;
in vec4 a_Color;
in vec2 a_TexCoord;
in ivec2 a_LightCoord;

#if !defined(VERT_POS_SCALE)
#error "VERT_POS_SCALE not defined"
#elif !defined(VERT_POS_OFFSET)
#error "VERT_POS_OFFSET not defined"
#elif !defined(VERT_TEX_SCALE)
#error "VERT_TEX_SCALE not defined"
#endif

void _vert_init() {
    _vert_position = (vec3(a_PosId.xyz) * VERT_POS_SCALE + VERT_POS_OFFSET);
    _vert_tex_diffuse_coord = (a_TexCoord * VERT_TEX_SCALE);
    _vert_tex_light_coord = a_LightCoord;
    _vert_color = a_Color;

    _draw_id = (a_PosId.w >> 8u) & 0xFFu;
    _material_params = (a_PosId.w >> 0u) & 0xFFu;

#ifdef USE_VERTEX_NORMAL
    _vert_normal = a_Normal.xyz;
    _vert_mid_tex_coord = a_MidTexCoord * VERT_TEX_SCALE;
    _vert_block_id = a_BlockId;
    _vert_tangent = vec4(a_Tangent.xyz, a_Tangent.w >= 0.0 ? 1.0 : -1.0);
    _vert_mid_block = a_MidBlock.xyz;
#else
    _vert_normal = vec3(0.0, 1.0, 0.0);
    _vert_mid_tex_coord = _vert_tex_diffuse_coord;
    _vert_block_id = 0.0;
    _vert_tangent = vec4(1.0, 0.0, 0.0, 1.0);
    _vert_mid_block = vec3(0.0);
#endif
}

#else

in vec3 a_PosId;
in vec4 a_Color;
in vec2 a_TexCoord;
in uint a_LightCoord;

void _vert_init() {
    _vert_position = a_PosId;
    _vert_tex_diffuse_coord = a_TexCoord;
    _vert_color = a_Color;

    uint packed_draw_params = (a_LightCoord & 0xFFFFu);
    // Vertex Material
    _material_params = (packed_draw_params) & 0xFFu;

    // Vertex Mesh ID
    _draw_id  = (packed_draw_params >> 8) & 0xFFu;

    // Vertex Light
    _vert_tex_light_coord = ivec2((uvec2((a_LightCoord >> 16) & 0xFFFFu) >> uvec2(0, 8)) & uvec2(0xFFu));

    // The uncompressed (vanilla-like) path carries no packed normal; default it to up.
    _vert_normal = vec3(0.0, 1.0, 0.0);
    _vert_mid_tex_coord = _vert_tex_diffuse_coord;
    _vert_block_id = 0.0;
    _vert_tangent = vec4(1.0, 0.0, 0.0, 1.0);
    _vert_mid_block = vec3(0.0);
}
#endif
