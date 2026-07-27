package me.jellysquid.mods.sodium.client.render.chunk.vertex.format;

import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.Material;

public interface ChunkVertexEncoder {
    long write(long ptr, Material material, Vertex vertex, int sectionIndex);

    class Vertex {
        public float x;
        public float y;
        public float z;
        public int color;
        public float u;
        public float v;
        public int light;
        // Packed face normal (NormI8: signed bytes X|Y|Z, world/model space). Only written to the vertex buffer by the
        // normal-extended chunk format used when the Iris shader pipeline is active; ignored by the compact/vanilla formats.
        public int normal;
        // Sprite-centre (mid) texture coordinate for this quad, same value on all 4 vertices. Only written by the
        // normal-extended (Iris) chunk format; the transformed terrain shader exposes it as mc_midTexCoord so the pack's
        // foliage waving can tell a plant's top vertices (which sway) from its anchored bottom ones.
        public float midU;
        public float midV;
        // Complementary block.properties id for this block (torch=10496, sea lantern=10448, grass=10005, …), same on all 4
        // vertices. Only written by the normal-extended (Iris) format; the terrain shader exposes it as mc_Entity.x so the
        // pack identifies foliage (waving) + light sources (colored-lighting voxelization). 0 = unidentified/plain block.
        public int blockId;
        // Packed UV-aligned tangent (NormI8: signed bytes X|Y|Z|W, W = handedness ±1), same for all 4 quad vertices. Only
        // written by the normal-extended (Iris) format; exposed as at_tangent so the pack builds a correct TBN basis for
        // parallax (nether portal depth effect), generated normals, POM, and custom PBR.
        public int tangent;
        // Offset from THIS vertex to its block centre, ×64 (signed bytes X|Y|Z, RAW/non-normalized), per vertex. Only
        // written by the normal-extended (Iris) format; exposed as at_midBlock so the pack voxelizes each block at its
        // CENTRE (colored-lighting + WSR voxel alignment; e.g. the nether-portal edge glow) instead of at a vertex corner.
        public int midBlock;

        public static Vertex[] uninitializedQuad() {
            Vertex[] vertices = new Vertex[4];

            for (int i = 0; i < 4; i++) {
                vertices[i] = new Vertex();
            }

            return vertices;
        }
    }
}
