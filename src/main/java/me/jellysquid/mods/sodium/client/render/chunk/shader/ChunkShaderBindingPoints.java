package me.jellysquid.mods.sodium.client.render.chunk.shader;

public class ChunkShaderBindingPoints {
    public static final int ATTRIBUTE_POSITION_ID = 0;
    public static final int ATTRIBUTE_COLOR = 1;
    public static final int ATTRIBUTE_BLOCK_TEXTURE = 2;
    public static final int ATTRIBUTE_LIGHT_TEXTURE = 3;
    // Yumelium/Iris: packed face normal (a_Normal), only bound for the normal-extended compact format in shader mode.
    public static final int ATTRIBUTE_NORMAL = 4;
    // Yumelium/Iris: sprite-mid tex coord (a_MidTexCoord) for foliage waving, only bound for the normal-extended format.
    public static final int ATTRIBUTE_MID_TEX_COORD = 5;
    // Yumelium/Iris: block.properties id (a_BlockId → mc_Entity.x) for foliage + light-source identification (colored
    // lighting voxelization). Only bound for the normal-extended format.
    public static final int ATTRIBUTE_BLOCK_ID = 6;
    // Yumelium/Iris: packed UV tangent (a_Tangent → at_tangent) for the TBN basis (portal parallax / PBR). Normal-extended only.
    public static final int ATTRIBUTE_TANGENT = 7;
    // Yumelium/Iris: vertex→block-centre offset (a_MidBlock → at_midBlock) for voxel centring. Normal-extended only.
    public static final int ATTRIBUTE_MID_BLOCK = 8;

    public static final int FRAG_COLOR = 0;
    // Yumelium/Iris: second fragment output (view-space normal → colortex1) written by the transformed terrain shader.
    public static final int FRAG_NORMAL = 1;
}
