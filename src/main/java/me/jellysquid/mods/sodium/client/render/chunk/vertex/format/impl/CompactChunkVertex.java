package me.jellysquid.mods.sodium.client.render.chunk.vertex.format.impl;

import me.jellysquid.mods.sodium.client.gl.attribute.GlVertexAttributeFormat;
import me.jellysquid.mods.sodium.client.gl.attribute.GlVertexFormat;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.Material;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkMeshAttribute;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import org.lwjgl.system.MemoryUtil;

public class CompactChunkVertex implements ChunkVertexType {
    /** Byte offset of the packed normal in the normal-extended variant (appended after the base 20-byte layout). */
    public static final int NORMAL_OFFSET = 20;
    /** Byte offset of the sprite-mid texture coordinate (2 unsigned shorts), appended after the normal. */
    public static final int MID_TEX_OFFSET = 24;
    /** Byte offset of the block-properties id (1 unsigned short), appended after the mid-tex coord. */
    public static final int BLOCK_ID_OFFSET = 28;
    /** Byte offset of the packed UV tangent (4 signed bytes, NormI8), appended after the block id (+2 pad). */
    public static final int TANGENT_OFFSET = 32;
    /** Byte offset of the vertex→block-centre offset (3 signed bytes ×64 + 1 pad), appended after the tangent. */
    public static final int MID_BLOCK_OFFSET = 36;

    private static final int BASE_STRIDE = 20;
    /** Base layout (20) + packed normal (4) + sprite-mid tex coord (2×ushort) + block id (ushort, +2 pad) + packed tangent
     * (4) + vertex→block-centre offset (3 bytes + 1 pad) = 40 bytes when the Iris shader pipeline is active. The pack needs
     * the mid-tex coord for waving, the block id for identification, the tangent for the TBN basis, and the mid-block for
     * voxel centring. */
    private static final int NORMAL_STRIDE = 40;

    public static final int STRIDE = BASE_STRIDE;

    private static final int POSITION_MAX_VALUE = 65536;
    private static final int TEXTURE_MAX_VALUE = 32768;

    private static final float MODEL_ORIGIN = 8.0f;
    private static final float MODEL_RANGE = 32.0f;
    private static final float MODEL_SCALE = MODEL_RANGE / POSITION_MAX_VALUE;
    private static final float MODEL_SCALE_INV = POSITION_MAX_VALUE / MODEL_RANGE;

    private static final float TEXTURE_SCALE = (1.0f / TEXTURE_MAX_VALUE);

    /**
     * When true, this format appends a packed face normal ({@code a_Normal}) and uses a 24-byte stride — used only when
     * the Iris shader pipeline is active (a renderer reload re-meshes into this format). When false, it is byte-for-byte
     * the original 20-byte compact layout, which Nvidium's raster path depends on. The extra 4 bytes are trailing
     * padding as far as {@link GlVertexFormat} is concerned; the normal is bound manually in DefaultChunkRenderer.
     */
    private final boolean withNormal;
    private final int stride;
    private final GlVertexFormat<ChunkMeshAttribute> vertexFormat;

    public CompactChunkVertex() {
        this(false);
    }

    public CompactChunkVertex(boolean withNormal) {
        this.withNormal = withNormal;
        this.stride = withNormal ? NORMAL_STRIDE : BASE_STRIDE;
        this.vertexFormat = GlVertexFormat.builder(ChunkMeshAttribute.class, this.stride)
                .addElement(ChunkMeshAttribute.POSITION_MATERIAL_MESH, 0, GlVertexAttributeFormat.UNSIGNED_SHORT, 4, false, true)
                .addElement(ChunkMeshAttribute.COLOR_SHADE, 8, GlVertexAttributeFormat.UNSIGNED_BYTE, 4, true, false)
                .addElement(ChunkMeshAttribute.BLOCK_TEXTURE, 12, GlVertexAttributeFormat.UNSIGNED_SHORT, 2, false, false)
                .addElement(ChunkMeshAttribute.LIGHT_TEXTURE, 16, GlVertexAttributeFormat.UNSIGNED_SHORT, 2, false, true)
                .build();
    }

    public boolean hasNormal() {
        return this.withNormal;
    }

    @Override
    public float getTextureScale() {
        return TEXTURE_SCALE;
    }

    @Override
    public float getPositionScale() {
        return MODEL_SCALE;
    }

    @Override
    public float getPositionOffset() {
        return -MODEL_ORIGIN;
    }

    @Override
    public GlVertexFormat<ChunkMeshAttribute> getVertexFormat() {
        return this.vertexFormat;
    }

    @Override
    public ChunkVertexEncoder getEncoder() {
        if (this.withNormal) {
            final int stride = this.stride;
            return (ptr, material, vertex, sectionIndex) -> {
                writeBase(ptr, material, vertex, sectionIndex);
                // Packed face normal (NormI8 layout = 4 signed bytes) read as a normalized GL_BYTE vec4 → a_Normal.
                MemoryUtil.memPutInt(ptr + NORMAL_OFFSET, vertex.normal);
                // Sprite-mid tex coord (2 unsigned shorts, same TEXTURE_MAX_VALUE scale as the block texture) → a_MidTexCoord.
                MemoryUtil.memPutShort(ptr + MID_TEX_OFFSET, encodeTexture(vertex.midU));
                MemoryUtil.memPutShort(ptr + MID_TEX_OFFSET + 2, encodeTexture(vertex.midV));
                // Block-properties id (1 unsigned short) → a_BlockId (mc_Entity.x). IDs fit in 16 bits (max ~32000).
                MemoryUtil.memPutShort(ptr + BLOCK_ID_OFFSET, (short) (vertex.blockId & 0xFFFF));
                // Packed UV tangent (NormI8) → a_Tangent (at_tangent).
                MemoryUtil.memPutInt(ptr + TANGENT_OFFSET, vertex.tangent);
                // Vertex→block-centre offset (3 signed bytes ×64 + pad) → a_MidBlock (at_midBlock).
                MemoryUtil.memPutInt(ptr + MID_BLOCK_OFFSET, vertex.midBlock);
                return ptr + stride;
            };
        }
        return (ptr, material, vertex, sectionIndex) -> {
            writeBase(ptr, material, vertex, sectionIndex);
            return ptr + BASE_STRIDE;
        };
    }

    private static void writeBase(long ptr, Material material, ChunkVertexEncoder.Vertex vertex, int sectionIndex) {
        MemoryUtil.memPutShort(ptr + 0, encodePosition(vertex.x));
        MemoryUtil.memPutShort(ptr + 2, encodePosition(vertex.y));
        MemoryUtil.memPutShort(ptr + 4, encodePosition(vertex.z));

        MemoryUtil.memPutByte(ptr + 6, (byte) (material.bits() & 0xFF));
        MemoryUtil.memPutByte(ptr + 7, (byte) (sectionIndex & 0xFF));

        MemoryUtil.memPutInt(ptr + 8, vertex.color);

        MemoryUtil.memPutShort(ptr + 12, encodeTexture(vertex.u));
        MemoryUtil.memPutShort(ptr + 14, encodeTexture(vertex.v));

        MemoryUtil.memPutInt(ptr + 16, vertex.light);
    }

    private static short encodePosition(float value) {
        return (short) ((MODEL_ORIGIN + value) * MODEL_SCALE_INV);
    }

    public static float decodePosition(short value) {
        return (((float)Short.toUnsignedInt(value)) / MODEL_SCALE_INV) - MODEL_ORIGIN;
    }

    private static short encodeTexture(float value) {
        return (short) (Math.round(value * TEXTURE_MAX_VALUE) & 0xFFFF);
    }
}
