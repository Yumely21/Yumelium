package me.jellysquid.mods.sodium.client.render.chunk;

import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gui.SodiumGameOptions;
import me.jellysquid.mods.sodium.client.gl.attribute.GlVertexAttributeBinding;
import me.jellysquid.mods.sodium.client.gl.attribute.GlVertexFormat;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.gl.device.DrawCommandList;
import me.jellysquid.mods.sodium.client.gl.device.MultiDrawBatch;
import me.jellysquid.mods.sodium.client.gl.device.RenderDevice;
import me.jellysquid.mods.sodium.client.gl.tessellation.GlIndexType;
import me.jellysquid.mods.sodium.client.gl.tessellation.GlPrimitiveType;
import me.jellysquid.mods.sodium.client.gl.tessellation.GlTessellation;
import me.jellysquid.mods.sodium.client.gl.tessellation.TessellationBinding;
import me.jellysquid.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import me.jellysquid.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import me.jellysquid.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe;
import me.jellysquid.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import me.jellysquid.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegion;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderBindingPoints;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkMeshAttribute;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import me.jellysquid.mods.sodium.client.render.viewport.CameraTransform;
import me.jellysquid.mods.sodium.client.util.BitwiseMath;
import org.lwjgl.system.MemoryUtil;
import java.util.Iterator;

public class DefaultChunkRenderer extends ShaderChunkRenderer {
    private final MultiDrawBatch batch;

    private final SharedQuadIndexBuffer sharedIndexBuffer;

    private final GlVertexAttributeBinding[] vertexAttributeBindings;

    private boolean isIndexedPass;

    // Cached GL 4.3 (glMultiDrawElementsIndirect) availability, resolved lazily on first render.
    private Boolean indirectSupported;

    // The last effective draw mode we logged, so the active mode is reported once (and again on change).
    private SodiumGameOptions.MultiDrawMode lastLoggedMode;

    public DefaultChunkRenderer(RenderDevice device, ChunkVertexType vertexType) {
        super(device, vertexType);

        this.batch = new MultiDrawBatch((ModelQuadFacing.COUNT * RenderRegion.REGION_SIZE) + 1);
        this.sharedIndexBuffer = new SharedQuadIndexBuffer(device.createCommandList(), SharedQuadIndexBuffer.IndexType.INTEGER);

        this.vertexAttributeBindings = getBindingsForType();
    }

    @Override
    public void render(ChunkRenderMatrices matrices,
                       CommandList commandList,
                       ChunkRenderListIterable renderLists,
                       TerrainRenderPass renderPass,
                       CameraTransform camera) {
        super.begin(renderPass);

        // Iris shadow pass: cull relative to the CAMERA is wrong for a light-POV render (it would drop faces that face
        // away from the camera but toward the light, leaking shadows), so render all faces while the shadow pass is active.
        boolean shadowPass = com.yumelium.yumelium.shaders.pipeline.IrisPipeline.instance().isShadowPass();
        boolean useBlockFaceCulling = SodiumClientMod.options().advanced.useBlockFaceCulling && !shadowPass;
        // In the shadow pass, drop sections outside the shadow map's coverage (the pass reuses the camera-visible set, so
        // without this it re-draws the whole render distance into a map that only spans ~192 blocks — huge waste far out).
        boolean shadowCull = shadowPass;
        // Light-facing slice culling for the shadow terrain passes (SOLID+CUTOUT): a quad whose slice normal faces
        // away from the sun can never be the nearest-to-light depth over convex block geometry, so its slice is
        // skipped outside the voxel volume (see getLightFacingSlices). TRANSLUCENT stays full — it is the
        // caustics/shadowcolor + translucent-emitter voxelization pass, and it can be index-sorted.
        int shadowSliceMask = ModelQuadFacing.ALL;
        if (shadowPass && renderPass != me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses.TRANSLUCENT) {
            shadowSliceMask = getLightFacingSlices(
                    com.yumelium.yumelium.shaders.pipeline.IrisPipeline.instance().shadowLightDirection());
        }
        SodiumGameOptions.MultiDrawMode drawMode = this.resolveDrawMode();

        ChunkShaderInterface shader = this.activeProgram.getInterface();
        shader.setProjectionMatrix(matrices.projection());
        shader.setModelViewMatrix(matrices.modelView());

        Iterator<ChunkRenderList> iterator = renderLists.iterator(renderPass.isReverseOrder());

        this.isIndexedPass = renderPass.isSorted();

        while (iterator.hasNext()) {
            ChunkRenderList renderList = iterator.next();

            var region = renderList.getRegion();
            var storage = region.getStorage(renderPass);

            if (storage == null) {
                continue;
            }

            fillCommandBuffer(this.batch, region, storage, renderList, camera, renderPass, useBlockFaceCulling, shadowCull, shadowSliceMask);

            if (this.batch.isEmpty()) {
                continue;
            }



            if (!this.isIndexedPass) {
                this.sharedIndexBuffer.ensureCapacity(commandList, this.batch.getIndexBufferSize());
            }

            var tessellation = this.prepareTessellation(commandList, region);

            setModelMatrixUniforms(shader, region, camera);
            executeDrawBatch(commandList, tessellation, this.batch, drawMode);
        }

        if (shadowPass) {
            com.yumelium.yumelium.shaders.pipeline.IrisPipeline.instance().diagShadowCenterRow(
                    "after " + (renderPass == me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses.TRANSLUCENT
                            ? "TRANSLUCENT" : "OPAQUE") + " pass draw");
        }

        super.end(renderPass);
    }

    private SodiumGameOptions.MultiDrawMode resolveDrawMode() {
        SodiumGameOptions.MultiDrawMode requested = SodiumClientMod.options().advanced.multiDrawMode;
        SodiumGameOptions.MultiDrawMode effective = requested;

        // INDIRECT needs GL 4.3; transparently fall back to DIRECT when unsupported.
        if (requested == SodiumGameOptions.MultiDrawMode.INDIRECT && !this.isIndirectSupported()) {
            effective = SodiumGameOptions.MultiDrawMode.DIRECT;
        }

        // Report the active mode once, and whenever it changes, so it can be verified from the log.
        if (effective != this.lastLoggedMode) {
            this.lastLoggedMode = effective;
            if (requested != effective) {
                SodiumClientMod.logger().info("Chunk multi-draw mode: {} (requested {}, but GL 4.3 is unavailable so it fell back)", effective, requested);
            } else {
                SodiumClientMod.logger().info("Chunk multi-draw mode: {}", effective);
            }
        }

        return effective;
    }

    private boolean isIndirectSupported() {
        if (this.indirectSupported == null) {
            boolean ok;
            try {
                ok = org.lwjgl.opengl.GL.getCapabilities().OpenGL43;
            } catch (Throwable t) {
                ok = false;
            }
            this.indirectSupported = ok;
        }
        return this.indirectSupported;
    }

    private static void fillCommandBuffer(MultiDrawBatch batch,
                                          RenderRegion renderRegion,
                                          SectionRenderDataStorage renderDataStorage,
                                          ChunkRenderList renderList,
                                          CameraTransform camera,
                                          TerrainRenderPass pass,
                                          boolean useBlockFaceCulling,
                                          boolean shadowCull,
                                          int shadowSliceMask) {
        batch.clear();

        var iterator = renderList.sectionsWithGeometryIterator(pass.isReverseOrder());

        if (iterator == null) {
            return;
        }

        int originX = renderRegion.getChunkX();
        int originY = renderRegion.getChunkY();
        int originZ = renderRegion.getChunkZ();

        int indexPointerMask = pass.isSorted() ? 0xFFFFFFFF : 0;

        var pipeline = com.yumelium.yumelium.shaders.pipeline.IrisPipeline.instance();
        // Colored lighting: sections inside the camera-anchored voxel volume must bypass this cull too, mirroring
        // updateShadowRenderList's exemption — the list build let them through, but this encode-time re-cull was
        // missing the exemption and silently dropped voxel-box CORNER sections under diagonal sun azimuths (corner
        // radius 208·√2 ≈ 294 > the ±216 across-sun extent), so their light sources never voxelized and the
        // flood-fill lost them (2026-08-01). null when the pack/config has no colored lighting.
        int[] shadowVoxHalf = shadowCull ? pipeline.voxelVolumeHalfExtents() : null;

        while (iterator.hasNext()) {
            int sectionIndex = iterator.nextByteAsInt();

            int chunkX = originX + LocalSectionIndex.unpackX(sectionIndex);
            int chunkY = originY + LocalSectionIndex.unpackY(sectionIndex);
            int chunkZ = originZ + LocalSectionIndex.unpackZ(sectionIndex);

            // Shadow pass: skip sections outside the shadow map's coverage (see IrisPipeline.isSectionOutsideShadow).
            boolean inVoxelVolume = false;
            if (shadowCull) {
                int rx = (chunkX << 4) + 8 - camera.intX;
                int ry = (chunkY << 4) + 8 - camera.intY;
                int rz = (chunkZ << 4) + 8 - camera.intZ;
                // +16: section extent plus the voxelizer's sub-block offsets — same margin as the list build.
                inVoxelVolume = shadowVoxHalf != null
                        && Math.abs(rx) <= shadowVoxHalf[0] + 16
                        && Math.abs(ry) <= shadowVoxHalf[1] + 16
                        && Math.abs(rz) <= shadowVoxHalf[2] + 16;
                if (!inVoxelVolume && pipeline.isSectionOutsideShadow(rx, ry, rz)) {
                    continue;
                }
            }

            var pMeshData = renderDataStorage.getDataPointer(sectionIndex);

            int slices;

            if (useBlockFaceCulling && !pass.isSorted()) {
                slices = getVisibleFaces(camera.intX, camera.intY, camera.intZ, chunkX, chunkY, chunkZ);
            } else if (shadowCull && !inVoxelVolume) {
                // Light-facing slice cull. ALL inside the voxel volume — the shadow VERTEX shader is the voxelizer
                // (imageStore), so every quad of a light source must still run its vertices there. ALL also for
                // near-camera BURIED sections (RenderSection.shadowKeepAllSlices): buried geometry is single-sided —
                // its sun-facing counterpart is unmeshed solid ground — so the slice cull's "the front face occludes
                // anyway" premise fails and an underground room's walls would cast nothing (BL decay-pit phantom
                // beams, 2026-08-03).
                var section = renderRegion.getSection(sectionIndex);
                slices = (section != null && section.shadowKeepAllSlices())
                        ? ModelQuadFacing.ALL
                        : shadowSliceMask; // == ALL on the TRANSLUCENT shadow sub-pass
            } else {
                slices = ModelQuadFacing.ALL;
            }

            slices &= SectionRenderDataUnsafe.getSliceMask(pMeshData);

            if (slices != 0) {
                addDrawCommands(batch, pMeshData, slices, indexPointerMask);
            }
        }
    }

    @SuppressWarnings("IntegerMultiplicationImplicitCastToLong")
    private static void addDrawCommands(MultiDrawBatch batch, long pMeshData, int mask, int indexPointerMask) {
        final var pBaseVertex = batch.pBaseVertex;
        final var pElementCount = batch.pElementCount;
        final var pElementPointer = batch.pElementPointer;

        int size = batch.size;

        for (int facing = 0; facing < ModelQuadFacing.COUNT; facing++) {
            MemoryUtil.memPutInt(pBaseVertex + (size << 2), SectionRenderDataUnsafe.getVertexOffset(pMeshData, facing));
            MemoryUtil.memPutInt(pElementCount + (size << 2), SectionRenderDataUnsafe.getElementCount(pMeshData, facing));
            MemoryUtil.memPutAddress(pElementPointer + (size << 3), SectionRenderDataUnsafe.getIndexOffset(pMeshData, facing) & indexPointerMask);

            size += (mask >> facing) & 1;
        }

        batch.size = size;
    }

    private static final int MODEL_UNASSIGNED = ModelQuadFacing.UNASSIGNED.ordinal();
    private static final int MODEL_POS_X      = ModelQuadFacing.POS_X.ordinal();
    private static final int MODEL_POS_Y      = ModelQuadFacing.POS_Y.ordinal();
    private static final int MODEL_POS_Z      = ModelQuadFacing.POS_Z.ordinal();

    private static final int MODEL_NEG_X      = ModelQuadFacing.NEG_X.ordinal();
    private static final int MODEL_NEG_Y      = ModelQuadFacing.NEG_Y.ordinal();
    private static final int MODEL_NEG_Z      = ModelQuadFacing.NEG_Z.ordinal();

    // Epsilon band for light-facing slice culling in the shadow pass: keeps slices that are near edge-on to the
    // light. Must cover the pack shadow vertex's DoWave leaf tilt (a few degrees) and the sunPathRotation=0 case
    // where the light's Z component is exactly 0 all day.
    private static final float SHADOW_SLICE_EPS = 0.1F;

    /** Slices that can contain the nearest-to-light depth: keep a slice iff its normal is within the epsilon band
     * of facing the light ({@code dot(sliceNormal, dirToLight) > -EPS}); UNASSIGNED (non-axis-aligned quads) always
     * drawn. Constant per pass — a directional light with an ortho projection has no per-section variance. A
     * diagonal sun drops 3 of the 6 axis slices; a sun lying in a coordinate plane drops 2, exactly vertical 1. */
    private static int getLightFacingSlices(org.joml.Vector3fc l) {
        int planes = (1 << MODEL_UNASSIGNED);
        if (l.x() > -SHADOW_SLICE_EPS) planes |= 1 << MODEL_POS_X;
        if (l.x() <  SHADOW_SLICE_EPS) planes |= 1 << MODEL_NEG_X;
        if (l.y() > -SHADOW_SLICE_EPS) planes |= 1 << MODEL_POS_Y;
        if (l.y() <  SHADOW_SLICE_EPS) planes |= 1 << MODEL_NEG_Y;
        if (l.z() > -SHADOW_SLICE_EPS) planes |= 1 << MODEL_POS_Z;
        if (l.z() <  SHADOW_SLICE_EPS) planes |= 1 << MODEL_NEG_Z;
        return planes;
    }

    private static int getVisibleFaces(int originX, int originY, int originZ, int chunkX, int chunkY, int chunkZ) {
        // This is carefully written so that we can keep everything branch-less.
        //
        // Normally, this would be a ridiculous way to handle the problem. But the Hotspot VM's
        // heuristic for generating SETcc/CMOV instructions is broken, and it will always create a
        // branch even when a trivial ternary is encountered.
        //
        // For example, the following will never be transformed into a SETcc:
        //   (a > b) ? 1 : 0
        //
        // So we have to instead rely on sign-bit extension and masking (which generates a ton
        // of unnecessary instructions) to get this to be branch-less.
        //
        // To do this, we can transform the previous expression into the following.
        //   (b - a) >> 31
        //
        // This works because if (a > b) then (b - a) will always create a negative number. We then shift the sign bit
        // into the least significant bit's position (which also discards any bits following the sign bit) to get the
        // output we are looking for.
        //
        // If you look at the output which LLVM produces for a series of ternaries, you will instantly become distraught,
        // because it manages to a) correctly evaluate the cost of instructions, and b) go so far
        // as to actually produce vector code.  (https://godbolt.org/z/GaaEx39T9)

        int boundsMinX = (chunkX << 4), boundsMaxX = boundsMinX + 16;
        int boundsMinY = (chunkY << 4), boundsMaxY = boundsMinY + 16;
        int boundsMinZ = (chunkZ << 4), boundsMaxZ = boundsMinZ + 16;

        // the "unassigned" plane is always front-facing, since we can't check it
        int planes = (1 << MODEL_UNASSIGNED);

        planes |= BitwiseMath.greaterThan(originX, (boundsMinX - 3)) << MODEL_POS_X;
        planes |= BitwiseMath.greaterThan(originY, (boundsMinY - 3)) << MODEL_POS_Y;
        planes |= BitwiseMath.greaterThan(originZ, (boundsMinZ - 3)) << MODEL_POS_Z;

        planes |=    BitwiseMath.lessThan(originX, (boundsMaxX + 3)) << MODEL_NEG_X;
        planes |=    BitwiseMath.lessThan(originY, (boundsMaxY + 3)) << MODEL_NEG_Y;
        planes |=    BitwiseMath.lessThan(originZ, (boundsMaxZ + 3)) << MODEL_NEG_Z;

        return planes;
    }

    private static void setModelMatrixUniforms(ChunkShaderInterface shader, RenderRegion region, CameraTransform camera) {
        float x = getCameraTranslation(region.getOriginX(), camera.intX, camera.fracX);
        float y = getCameraTranslation(region.getOriginY(), camera.intY, camera.fracY);
        float z = getCameraTranslation(region.getOriginZ(), camera.intZ, camera.fracZ);

        shader.setRegionOffset(x, y, z);
    }

    private static float getCameraTranslation(int chunkBlockPos, int cameraBlockPos, float cameraPos) {
        return (chunkBlockPos - cameraBlockPos) - cameraPos;
    }

    private GlTessellation prepareTessellation(CommandList commandList, RenderRegion region) {
        var resources = region.getResources();
        var tessellation = this.isIndexedPass ? resources.getIndexedTessellation() : resources.getTessellation();

        if (tessellation == null) {
            tessellation = this.createRegionTessellation(commandList, resources);
            if (this.isIndexedPass) {
                resources.updateIndexedTessellation(commandList, tessellation);
            } else {
                resources.updateTessellation(commandList, tessellation);
            }
        }

        return tessellation;
    }

    private GlVertexAttributeBinding[] getBindingsForType() {
        if(ChunkMeshFormats.isCompact(this.vertexType)) {
            GlVertexAttributeBinding[] base = new GlVertexAttributeBinding[] {
                    new GlVertexAttributeBinding(ChunkShaderBindingPoints.ATTRIBUTE_POSITION_ID,
                            this.vertexFormat.getAttribute(ChunkMeshAttribute.POSITION_MATERIAL_MESH)),
                    new GlVertexAttributeBinding(ChunkShaderBindingPoints.ATTRIBUTE_COLOR,
                            this.vertexFormat.getAttribute(ChunkMeshAttribute.COLOR_SHADE)),
                    new GlVertexAttributeBinding(ChunkShaderBindingPoints.ATTRIBUTE_BLOCK_TEXTURE,
                            this.vertexFormat.getAttribute(ChunkMeshAttribute.BLOCK_TEXTURE)),
                    new GlVertexAttributeBinding(ChunkShaderBindingPoints.ATTRIBUTE_LIGHT_TEXTURE,
                            this.vertexFormat.getAttribute(ChunkMeshAttribute.LIGHT_TEXTURE))
            };
            // Iris shader mode: the compact-normal format appends a packed normal (4 signed bytes) at NORMAL_OFFSET as
            // trailing padding the GlVertexFormat doesn't track, so bind it manually as a normalized GL_BYTE vec4.
            if (this.vertexType == ChunkMeshFormats.COMPACT_NORMAL) {
                int stride = this.vertexFormat.getStride();
                GlVertexAttributeBinding normal = new GlVertexAttributeBinding(ChunkShaderBindingPoints.ATTRIBUTE_NORMAL,
                        new me.jellysquid.mods.sodium.client.gl.attribute.GlVertexAttribute(
                                me.jellysquid.mods.sodium.client.gl.attribute.GlVertexAttributeFormat.BYTE, 4, true,
                                me.jellysquid.mods.sodium.client.render.chunk.vertex.format.impl.CompactChunkVertex.NORMAL_OFFSET,
                                stride, false));
                // Sprite-mid tex coord (2 unsigned shorts) → a_MidTexCoord, read un-normalized (raw ushort → float, scaled
                // by VERT_TEX_SCALE in the shader like a_TexCoord). Also trailing padding the GlVertexFormat doesn't track.
                GlVertexAttributeBinding midTex = new GlVertexAttributeBinding(ChunkShaderBindingPoints.ATTRIBUTE_MID_TEX_COORD,
                        new me.jellysquid.mods.sodium.client.gl.attribute.GlVertexAttribute(
                                me.jellysquid.mods.sodium.client.gl.attribute.GlVertexAttributeFormat.UNSIGNED_SHORT, 2, false,
                                me.jellysquid.mods.sodium.client.render.chunk.vertex.format.impl.CompactChunkVertex.MID_TEX_OFFSET,
                                stride, false));
                // Block-properties id (1 unsigned short) → a_BlockId, read un-normalized (raw ushort → float = the id).
                GlVertexAttributeBinding blockId = new GlVertexAttributeBinding(ChunkShaderBindingPoints.ATTRIBUTE_BLOCK_ID,
                        new me.jellysquid.mods.sodium.client.gl.attribute.GlVertexAttribute(
                                me.jellysquid.mods.sodium.client.gl.attribute.GlVertexAttributeFormat.UNSIGNED_SHORT, 1, false,
                                me.jellysquid.mods.sodium.client.render.chunk.vertex.format.impl.CompactChunkVertex.BLOCK_ID_OFFSET,
                                stride, false));
                // Packed UV tangent (4 signed bytes, NormI8) → a_Tangent, read as a normalized GL_BYTE vec4.
                GlVertexAttributeBinding tangent = new GlVertexAttributeBinding(ChunkShaderBindingPoints.ATTRIBUTE_TANGENT,
                        new me.jellysquid.mods.sodium.client.gl.attribute.GlVertexAttribute(
                                me.jellysquid.mods.sodium.client.gl.attribute.GlVertexAttributeFormat.BYTE, 4, true,
                                me.jellysquid.mods.sodium.client.render.chunk.vertex.format.impl.CompactChunkVertex.TANGENT_OFFSET,
                                stride, false));
                // Vertex→block-centre offset (3 signed bytes ×64) → a_MidBlock, read UN-normalized (raw byte → float; the
                // pack divides by 64). 4 components (3 used + pad).
                GlVertexAttributeBinding midBlock = new GlVertexAttributeBinding(ChunkShaderBindingPoints.ATTRIBUTE_MID_BLOCK,
                        new me.jellysquid.mods.sodium.client.gl.attribute.GlVertexAttribute(
                                me.jellysquid.mods.sodium.client.gl.attribute.GlVertexAttributeFormat.BYTE, 4, false,
                                me.jellysquid.mods.sodium.client.render.chunk.vertex.format.impl.CompactChunkVertex.MID_BLOCK_OFFSET,
                                stride, false));
                GlVertexAttributeBinding[] withNormal = new GlVertexAttributeBinding[base.length + 5];
                System.arraycopy(base, 0, withNormal, 0, base.length);
                withNormal[base.length] = normal;
                withNormal[base.length + 1] = midTex;
                withNormal[base.length + 2] = blockId;
                withNormal[base.length + 3] = tangent;
                withNormal[base.length + 4] = midBlock;
                return withNormal;
            }
            return base;
        } else if(this.vertexType == ChunkMeshFormats.VANILLA_LIKE) {
            GlVertexFormat<ChunkMeshAttribute> vanillaFormat = this.vertexFormat;
            return new GlVertexAttributeBinding[] {
                    new GlVertexAttributeBinding(ChunkShaderBindingPoints.ATTRIBUTE_POSITION_ID,
                            vanillaFormat.getAttribute(ChunkMeshAttribute.POSITION_MATERIAL_MESH)),
                    new GlVertexAttributeBinding(ChunkShaderBindingPoints.ATTRIBUTE_COLOR,
                            vanillaFormat.getAttribute(ChunkMeshAttribute.COLOR_SHADE)),
                    new GlVertexAttributeBinding(ChunkShaderBindingPoints.ATTRIBUTE_BLOCK_TEXTURE,
                            vanillaFormat.getAttribute(ChunkMeshAttribute.BLOCK_TEXTURE)),
                    new GlVertexAttributeBinding(ChunkShaderBindingPoints.ATTRIBUTE_LIGHT_TEXTURE,
                            vanillaFormat.getAttribute(ChunkMeshAttribute.LIGHT_TEXTURE)),
            };
        } else
            return null; // assume Oculus/Iris will take over
    }

    private GlTessellation createRegionTessellation(CommandList commandList, RenderRegion.DeviceResources resources) {
        return commandList.createTessellation(GlPrimitiveType.TRIANGLES, new TessellationBinding[] {
                TessellationBinding.forVertexBuffer(resources.getVertexBuffer(), this.vertexAttributeBindings),
                TessellationBinding.forElementBuffer(this.isIndexedPass ? resources.getIndexBuffer() : this.sharedIndexBuffer.getBufferObject())
        });
    }

    /** DIAGNOSTIC: counts draw batches issued (used to verify the Iris shadow pass actually submits geometry). */
    public static volatile int debugDrawBatches;

    private static void executeDrawBatch(CommandList commandList, GlTessellation tessellation, MultiDrawBatch batch,
                                         SodiumGameOptions.MultiDrawMode mode) {
        debugDrawBatches++;
        try (DrawCommandList drawCommandList = commandList.beginTessellating(tessellation)) {
            switch (mode) {
                case INDIRECT:
                    drawCommandList.multiDrawElementsIndirect(batch, GlIndexType.UNSIGNED_INT);
                    break;
                case INDIVIDUAL:
                    drawCommandList.drawElementsIndividual(batch, GlIndexType.UNSIGNED_INT);
                    break;
                case DIRECT:
                default:
                    drawCommandList.multiDrawElementsBaseVertex(batch, GlIndexType.UNSIGNED_INT);
                    break;
            }
        }
    }

    @Override
    public void delete(CommandList commandList) {
        super.delete(commandList);

        this.sharedIndexBuffer.delete(commandList);
        this.batch.delete();
    }
}
