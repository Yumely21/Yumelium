package me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline;

import me.jellysquid.mods.sodium.client.model.color.ColorProvider;
import me.jellysquid.mods.sodium.client.model.color.ColorProviderRegistry;
import me.jellysquid.mods.sodium.client.model.light.LightMode;
import me.jellysquid.mods.sodium.client.model.light.LightPipeline;
import me.jellysquid.mods.sodium.client.model.light.LightPipelineProvider;
import me.jellysquid.mods.sodium.client.model.light.data.QuadLightData;
import me.jellysquid.mods.sodium.client.model.quad.ModelQuad;
import me.jellysquid.mods.sodium.client.model.quad.ModelQuadView;
import me.jellysquid.mods.sodium.client.model.quad.ModelQuadViewMutable;
import me.jellysquid.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import me.jellysquid.mods.sodium.client.model.quad.properties.ModelQuadFlags;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildBuffers;
import me.jellysquid.mods.sodium.client.render.chunk.compile.buffers.ChunkModelBuilder;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.DefaultMaterials;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.Material;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import me.jellysquid.mods.sodium.client.util.DirectionUtil;
import me.jellysquid.mods.sodium.client.util.WorldUtil;
import me.jellysquid.mods.sodium.client.world.WorldSlice;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.fluids.Fluid;
import org.embeddedt.embeddium.render.chunk.ChunkColorWriter;
import org.embeddedt.embeddium.render.fluid.EmbeddiumFluidSpriteCache;
import org.joml.Vector3d;

import java.util.Arrays;

/**
 * The complement of {@link BlockRenderer} for emitting fluid geometry.
 *
 * <p>NOTE(yumelium): synthesized from Vintagium's 1.12.2 fluid mesher (fluids-as-{@link BlockLiquid}, corner heights
 * from {@code BlockLiquid#LEVEL}, still/flowing/overlay sprites via {@link EmbeddiumFluidSpriteCache}, flow-direction
 * UVs) but writing through the Embeddium 0.5.x path — a {@link ChunkModelBuilder} obtained from {@link ChunkBuildBuffers},
 * {@link ColorProvider} biome tint, {@link LightPipeline} lighting and {@link ChunkColorWriter} encoding, matching
 * {@link BlockRenderer}. 1.12.2 fluid data access lives in {@link WorldUtil} (needs {@code MixinBlockLiquid}).
 */
public class FluidRenderer {
    private static final float EPSILON = 0.001f;

    private final BlockPos.MutableBlockPos scratchPos = new BlockPos.MutableBlockPos();

    private final ModelQuadViewMutable quad = new ModelQuad();

    private final LightPipelineProvider lighters;
    private final ColorProviderRegistry colorProviderRegistry;

    private final QuadLightData quadLightData = new QuadLightData();
    private final int[] quadColors = new int[4];

    private final ChunkVertexEncoder.Vertex[] vertices = ChunkVertexEncoder.Vertex.uninitializedQuad();

    /** The current fluid's block.properties id (mc_Entity), resolved once per {@link #render} and written to every
     * vertex — water = 32000, lava = 10068/10070. See the writeQuad note. */
    private int currentBlockId;

    private final EmbeddiumFluidSpriteCache fluidSpriteCache = new EmbeddiumFluidSpriteCache();

    private final ChunkColorWriter colorEncoder = ChunkColorWriter.get();

    public FluidRenderer(ColorProviderRegistry colorProviderRegistry, LightPipelineProvider lighters) {
        this.quad.setLightFace(EnumFacing.UP);

        this.lighters = lighters;
        this.colorProviderRegistry = colorProviderRegistry;
    }

    private boolean isFluidOccluded(IBlockAccess world, int x, int y, int z, EnumFacing dir, Fluid fluid) {
        BlockPos pos = this.scratchPos.setPos(x, y, z);
        IBlockState blockState = world.getBlockState(pos);
        BlockPos adjPos = this.scratchPos.setPos(x + dir.getXOffset(), y + dir.getYOffset(), z + dir.getZOffset());
        Fluid adjFluid = WorldUtil.getFluid(world.getBlockState(adjPos));

        if (blockState.getMaterial().isOpaque()) {
            // Fluidlogged or next to water: occlude sides that are solid or the same liquid
            return fluid == adjFluid || blockState.isSideSolid(world, pos, dir);
        }
        return fluid == adjFluid;
    }

    private boolean isSideExposed(IBlockAccess world, int x, int y, int z, EnumFacing dir) {
        BlockPos pos = this.scratchPos.setPos(x + dir.getXOffset(), y + dir.getYOffset(), z + dir.getZOffset());
        IBlockState blockState = world.getBlockState(pos);

        // Forge-idiomatic culling: the neighbour's face touching this fluid fully covers it → cull. This keys on
        // the block's RENDER shape (doesSideBlockRendering → isOpaqueCube by default), NOT its Material: modded
        // terrain often pairs a full opaque render cube with a custom Material whose isOpaque() is false
        // (Betweenlands mud/silt) — the old material gate skipped the shape check entirely there and emitted water
        // side faces flush against every such wall, which the shader pack then rendered as a floating reflective
        // "water surface" film on underwater walls (2026-07-29).
        if (blockState.doesSideBlockRendering(world, pos, dir.getOpposite())) {
            // The top face is always inset, so a full cube above can't possibly occlude it
            return dir == EnumFacing.UP;
        }

        return true;
    }

    public void render(WorldSlice world, IBlockState fluidState, BlockPos pos, BlockPos offset, ChunkBuildBuffers buffers) {
        Fluid fluid = WorldUtil.getFluid(fluidState);

        if (fluid == null) {
            return;
        }

        int posX = pos.getX();
        int posY = pos.getY();
        int posZ = pos.getZ();

        boolean sfUp = this.isFluidOccluded(world, posX, posY, posZ, EnumFacing.UP, fluid);
        boolean sfDown = this.isFluidOccluded(world, posX, posY, posZ, EnumFacing.DOWN, fluid) ||
                !this.isSideExposed(world, posX, posY, posZ, EnumFacing.DOWN);
        boolean sfNorth = this.isFluidOccluded(world, posX, posY, posZ, EnumFacing.NORTH, fluid);
        boolean sfSouth = this.isFluidOccluded(world, posX, posY, posZ, EnumFacing.SOUTH, fluid);
        boolean sfWest = this.isFluidOccluded(world, posX, posY, posZ, EnumFacing.WEST, fluid);
        boolean sfEast = this.isFluidOccluded(world, posX, posY, posZ, EnumFacing.EAST, fluid);

        if (sfUp && sfDown && sfEast && sfWest && sfNorth && sfSouth) {
            return;
        }

        // Treat any non-lava fluid as colored; lava has no biome tint
        boolean hc = fluidState.getBlock() != Blocks.LAVA && fluidState.getBlock() != Blocks.FLOWING_LAVA;

        // Iris: the fluid's block.properties id for mc_Entity (a_BlockId), resolved per fluid block just like
        // BlockRenderer does for solids. Without it every fluid vertex carried 0: water was rescued by the
        // desaturate-material fallback (→ 32000, the exact id block.properties gives it anyway), but LAVA reached
        // gbuffers_terrain with mc_Entity = 0 — so terrainIPBR's lava branch (10068 still / 10070 flowing: the
        // animated glow) never ran and the shadow-pass voxelizer never registered lava as a colored light source.
        // That was the visibly flat, dim lava in the nether.
        var idMapper = BlockRenderer.getBlockIdMapper();
        this.currentBlockId = idMapper == null ? 0 : idMapper.idFor(fluidState);

        // Vanilla water uses the desaturate material so its blue texture becomes grayscale in the shader and the biome
        // tint yields the real per-biome colour (1.13 style). MODDED water is routed the same way (2026-07-28,
        // Betweenlands phase): a fluid whose block material is WATER and whose registered fluid name is water-like
        // ("swamp_water", "stagnant_water", ...) gets the pack's full water treatment — translucent water pass, waves,
        // reflections — instead of flat textured SOLID geometry. The name check keeps thick water-material liquids a
        // mod deliberately styles otherwise (Betweenlands tar/rubber) on their own texture + layer: desaturation would
        // grey them out, and tar shouldn't ripple. Their block tint (e.g. the swamp murk gradient) still applies via
        // the colorProvider below, so this doesn't bleach modded water to vanilla blue.
        boolean isWater = fluidState.getBlock() == Blocks.WATER || fluidState.getBlock() == Blocks.FLOWING_WATER
                || (com.yumelium.yumelium.shaders.pipeline.IrisPipeline.instance().isEnabled()
                        && fluidState.getMaterial() == net.minecraft.block.material.Material.WATER
                        && fluid.getName() != null
                        && fluid.getName().toLowerCase(java.util.Locale.ROOT).contains("water"));
        if (isWater && this.currentBlockId == 0 && idMapper != null) {
            // No block.properties entry of its own (packs don't know modded fluids) — inherit vanilla water's id so
            // the shader's water branch (mc_Entity 32000) recognises it.
            this.currentBlockId = idMapper.idFor(Blocks.WATER.getDefaultState());
        }
        Material material = isWater ? DefaultMaterials.WATER : DefaultMaterials.forRenderLayer(fluidState.getBlock().getRenderLayer());
        ChunkModelBuilder meshBuilder = buffers.get(material);

        TextureAtlasSprite[] sprites = this.fluidSpriteCache.getSprites(fluid);
        ColorProvider<IBlockState> colorProvider = hc ? this.getColorProvider(fluidState) : null;

        float h1 = this.getCornerHeight(world, posX, posY, posZ, fluid);
        float h2 = this.getCornerHeight(world, posX, posY, posZ + 1, fluid);
        float h3 = this.getCornerHeight(world, posX + 1, posY, posZ + 1, fluid);
        float h4 = this.getCornerHeight(world, posX + 1, posY, posZ, fluid);

        float yOffset = sfDown ? 0.0F : EPSILON;

        final ModelQuadViewMutable quad = this.quad;

        LightMode lightMode = hc && Minecraft.isAmbientOcclusionEnabled() ? LightMode.SMOOTH : LightMode.FLAT;
        LightPipeline lighter = this.lighters.getLighter(lightMode);

        quad.setFlags(0);

        if (!sfUp && this.isSideExposed(world, posX, posY, posZ, EnumFacing.UP)) {
            h1 -= EPSILON;
            h2 -= EPSILON;
            h3 -= EPSILON;
            h4 -= EPSILON;

            Vector3d velocity = WorldUtil.getVelocity(world, pos, fluidState);

            TextureAtlasSprite sprite;
            ModelQuadFacing facing;
            float u1, u2, u3, u4;
            float v1, v2, v3, v4;

            if (velocity.x == 0.0D && velocity.z == 0.0D) {
                sprite = sprites[0];
                facing = ModelQuadFacing.POS_Y;
                u1 = sprite.getInterpolatedU(0.0D);
                v1 = sprite.getInterpolatedV(0.0D);
                u2 = u1;
                v2 = sprite.getInterpolatedV(16.0D);
                u3 = sprite.getInterpolatedU(16.0D);
                v3 = v2;
                u4 = u3;
                v4 = v1;
            } else {
                sprite = sprites[1];
                facing = ModelQuadFacing.UNASSIGNED;
                float dir = (float) MathHelper.atan2(velocity.z, velocity.x) - (1.5707964f);
                float sin = MathHelper.sin(dir) * 0.25F;
                float cos = MathHelper.cos(dir) * 0.25F;
                u1 = sprite.getInterpolatedU(8.0F + (-cos - sin) * 16.0F);
                v1 = sprite.getInterpolatedV(8.0F + (-cos + sin) * 16.0F);
                u2 = sprite.getInterpolatedU(8.0F + (-cos + sin) * 16.0F);
                v2 = sprite.getInterpolatedV(8.0F + (cos + sin) * 16.0F);
                u3 = sprite.getInterpolatedU(8.0F + (cos + sin) * 16.0F);
                v3 = sprite.getInterpolatedV(8.0F + (cos - sin) * 16.0F);
                u4 = sprite.getInterpolatedU(8.0F + (cos - sin) * 16.0F);
                v4 = sprite.getInterpolatedV(8.0F + (-cos - sin) * 16.0F);
            }

            float uAvg = (u1 + u2 + u3 + u4) / 4.0F;
            float vAvg = (v1 + v2 + v3 + v4) / 4.0F;
            float s1 = (float) sprites[0].getIconWidth() / (sprites[0].getMaxU() - sprites[0].getMinU());
            float s2 = (float) sprites[0].getIconHeight() / (sprites[0].getMaxV() - sprites[0].getMinV());
            float s3 = 4.0F / Math.max(s2, s1);

            u1 = lerp(s3, u1, uAvg);
            u2 = lerp(s3, u2, uAvg);
            u3 = lerp(s3, u3, uAvg);
            u4 = lerp(s3, u4, uAvg);
            v1 = lerp(s3, v1, vAvg);
            v2 = lerp(s3, v2, vAvg);
            v3 = lerp(s3, v3, vAvg);
            v4 = lerp(s3, v4, vAvg);

            quad.setSprite(sprite);

            setVertex(quad, 0, 0.0f, h1, 0.0f, u1, v1);
            setVertex(quad, 1, 0.0f, h2, 1.0F, u2, v2);
            setVertex(quad, 2, 1.0F, h3, 1.0F, u3, v3);
            setVertex(quad, 3, 1.0F, h4, 0.0f, u4, v4);

            this.updateQuad(quad, world, pos, lighter, EnumFacing.UP, 1.0F, colorProvider, fluidState);
            this.writeQuad(meshBuilder, material, offset, quad, facing, false);

            if (WorldUtil.method_15756(world, this.scratchPos.setPos(posX, posY + 1, posZ), fluid)) {
                setVertex(quad, 3, 0.0f, h1, 0.0f, u1, v1);
                setVertex(quad, 2, 0.0f, h2, 1.0F, u2, v2);
                setVertex(quad, 1, 1.0F, h3, 1.0F, u3, v3);
                setVertex(quad, 0, 1.0F, h4, 0.0f, u4, v4);

                this.writeQuad(meshBuilder, material, offset, quad, ModelQuadFacing.NEG_Y, true);
            }
        }

        if (!sfDown) {
            TextureAtlasSprite sprite = sprites[0];

            float minU = sprite.getMinU();
            float maxU = sprite.getMaxU();
            float minV = sprite.getMinV();
            float maxV = sprite.getMaxV();
            quad.setSprite(sprite);

            setVertex(quad, 0, 0.0f, yOffset, 1.0F, minU, maxV);
            setVertex(quad, 1, 0.0f, yOffset, 0.0f, minU, minV);
            setVertex(quad, 2, 1.0F, yOffset, 0.0f, maxU, minV);
            setVertex(quad, 3, 1.0F, yOffset, 1.0F, maxU, maxV);

            this.updateQuad(quad, world, pos, lighter, EnumFacing.DOWN, 1.0F, colorProvider, fluidState);
            this.writeQuad(meshBuilder, material, offset, quad, ModelQuadFacing.NEG_Y, false);
        }

        quad.setFlags(ModelQuadFlags.IS_PARALLEL | ModelQuadFlags.IS_ALIGNED);

        for (EnumFacing dir : DirectionUtil.HORIZONTAL_DIRECTIONS) {
            float c1;
            float c2;
            float x1;
            float z1;
            float x2;
            float z2;

            switch (dir) {
                case NORTH:
                    if (sfNorth) {
                        continue;
                    }
                    c1 = h1;
                    c2 = h4;
                    x1 = 0.0f;
                    x2 = 1.0F;
                    z1 = EPSILON;
                    z2 = z1;
                    break;
                case SOUTH:
                    if (sfSouth) {
                        continue;
                    }
                    c1 = h3;
                    c2 = h2;
                    x1 = 1.0F;
                    x2 = 0.0f;
                    z1 = 1.0f - EPSILON;
                    z2 = z1;
                    break;
                case WEST:
                    if (sfWest) {
                        continue;
                    }
                    c1 = h2;
                    c2 = h1;
                    x1 = EPSILON;
                    x2 = x1;
                    z1 = 1.0F;
                    z2 = 0.0f;
                    break;
                case EAST:
                    if (sfEast) {
                        continue;
                    }
                    c1 = h4;
                    c2 = h3;
                    x1 = 1.0f - EPSILON;
                    x2 = x1;
                    z1 = 0.0f;
                    z2 = 1.0F;
                    break;
                default:
                    continue;
            }

            if (this.isSideExposed(world, posX, posY, posZ, dir)) {
                int adjX = posX + dir.getXOffset();
                int adjY = posY + dir.getYOffset();
                int adjZ = posZ + dir.getZOffset();

                TextureAtlasSprite sprite = sprites[1];
                TextureAtlasSprite oSprite = sprites[2];

                if (oSprite != null) {
                    BlockPos adjPos = this.scratchPos.setPos(adjX, adjY, adjZ);
                    IBlockState adjBlock = world.getBlockState(adjPos);

                    if (adjBlock.getBlockFaceShape(world, adjPos, dir.getOpposite()) == BlockFaceShape.SOLID) {
                        sprite = oSprite;
                    }
                }

                float u1 = sprite.getInterpolatedU(0.0D);
                float u2 = sprite.getInterpolatedU(8.0D);
                float v1 = sprite.getInterpolatedV((1.0F - c1) * 16.0F * 0.5F);
                float v2 = sprite.getInterpolatedV((1.0F - c2) * 16.0F * 0.5F);
                float v3 = sprite.getInterpolatedV(8.0D);

                quad.setSprite(sprite);

                setVertex(quad, 0, x2, c2, z2, u2, v2);
                setVertex(quad, 1, x2, yOffset, z2, u2, v3);
                setVertex(quad, 2, x1, yOffset, z1, u1, v3);
                setVertex(quad, 3, x1, c1, z1, u1, v1);

                float br = dir.getAxis() == EnumFacing.Axis.Z ? 0.8F : 0.6F;

                ModelQuadFacing facing = ModelQuadFacing.fromDirection(dir);

                this.updateQuad(quad, world, pos, lighter, dir, br, colorProvider, fluidState);
                this.writeQuad(meshBuilder, material, offset, quad, facing, false);

                if (sprite != oSprite) {
                    setVertex(quad, 0, x1, c1, z1, u1, v1);
                    setVertex(quad, 1, x1, yOffset, z1, u1, v3);
                    setVertex(quad, 2, x2, yOffset, z2, u2, v3);
                    setVertex(quad, 3, x2, c2, z2, u2, v2);

                    this.writeQuad(meshBuilder, material, offset, quad, facing.getOpposite(), true);
                }
            }
        }
    }

    private ColorProvider<IBlockState> getColorProvider(IBlockState fluidState) {
        return this.colorProviderRegistry.getColorProvider(fluidState.getBlock());
    }

    private void updateQuad(ModelQuadView quad, WorldSlice world, BlockPos pos, LightPipeline lighter, EnumFacing dir,
                            float brightness, ColorProvider<IBlockState> colorProvider, IBlockState fluidState) {
        QuadLightData light = this.quadLightData;
        lighter.calculate(quad, pos, light, null, dir, false);

        if (colorProvider != null) {
            colorProvider.getColors(world, pos, fluidState, quad, this.quadColors);

            // Force full alpha on all colors — like BlockRenderer. Biome tint from BiomeColorHelper arrives as 0x00RRGGBB
            // (alpha 0); the EMBEDDIUM color writer preserves that alpha and the shader multiplies rgb by it, so an alpha
            // of 0 turns the fluid black/invisible. Translucency comes from the sprite + the TRANSLUCENT pass, not here.
            for (int i = 0; i < this.quadColors.length; i++) {
                this.quadColors[i] |= 0xFF000000;
            }
        } else {
            Arrays.fill(this.quadColors, 0xFFFFFFFF);
        }

        // Multiply the per-vertex color against the combined brightness (per-vertex brightness * the face's shade)
        for (int i = 0; i < 4; i++) {
            this.quadColors[i] = this.colorEncoder.writeColor(this.quadColors[i], light.br[i] * brightness);
        }
    }

    private void writeQuad(ChunkModelBuilder builder, Material material, BlockPos offset, ModelQuadView quad,
                           ModelQuadFacing facing, boolean flip) {
        int vertexIdx = flip ? 3 : 0;
        int lightOrder = flip ? -1 : 1;

        var vertices = this.vertices;

        // Iris shader mode: packed geometric normal for the fluid quad (ignored by the compact/vanilla encoders).
        int packedNormal = me.jellysquid.mods.sodium.client.util.ModelQuadUtil.calculateNormal(quad);

        // mc_midTexCoord for fluids. The Vertex objects are REUSED across quads, so leaving this unwritten made every
        // water/lava vertex inherit whatever BlockRenderer last wrote for a solid block. gbuffers_water computes
        // `absMidCoordPos = abs(texCoord - mc_midTexCoord)` unconditionally, and water.glsl turns it into
        // `blockRes = absMidCoordPos.x * atlasSize.x * 2.0` → `waterPos = floor(waterPos * blockRes) / blockRes`, i.e. the
        // sprite resolution it quantises the ripple-normal lookup to (16 for a 16px sprite). A stale value made blockRes
        // arbitrary — and zero makes it 0/0 = NaN — so the water's normalMap collapsed and the surface reflected like a
        // flat mirror while the vertex-side waves kept moving.
        //
        // blockId = the fluid's real block.properties id (resolved once per fluid block in render()). Water resolves to
        // 32000 — the same value the desaturate-material fallback produced when this was left 0 — and lava now reaches
        // the shader as 10068/10070 so its IPBR glow + light voxelization work. at_midBlock is deliberately left alone
        // (it feeds gbuffers_water's `blockUV = 0.5 - at_midBlock.xyz/64.0`, and writing it was what put visible noise
        // on the water in an earlier attempt).
        float midU = 0.0f, midV = 0.0f;
        for (int i = 0; i < 4; i++) {
            midU += quad.getTexU(i);
            midV += quad.getTexV(i);
        }
        midU *= 0.25f;
        midV *= 0.25f;

        // at_tangent — the one that makes water flat. gbuffers_water builds its TBN from
        // `cross(at_tangent.xyz, gl_Normal.xyz) * at_tangent.w` (line 501) and derives viewVector from it; water.glsl then
        // does `parallaxMult = -0.01 * viewVector.xy / viewVector.z` and walks the gaux4 ripple-normal lookup with it. A
        // stale tangent (inherited from the last solid block, since the Vertex objects are reused) collapses that basis —
        // a zero handedness makes viewVector 0 and the division 0/0 — so normalMap died and the surface became a perfect
        // mirror while the vertex-side waves kept moving.
        //
        // It must be the REAL per-quad tangent: a neutral (1,0,0,1) is parallel to the normal on ±X faces, so
        // cross(T, N) = 0 collapses the basis there — that was the noise the earlier attempt produced.
        int packedTangent = BlockRenderer.computeTangent(quad);

        for (int i = 0; i < 4; i++) {
            var out = vertices[i];
            out.x = offset.getX() + quad.getX(i);
            out.y = offset.getY() + quad.getY(i);
            out.z = offset.getZ() + quad.getZ(i);

            out.color = this.quadColors[vertexIdx];
            out.u = quad.getTexU(i);
            out.v = quad.getTexV(i);
            out.light = this.quadLightData.lm[vertexIdx];
            out.normal = packedNormal;
            out.midU = midU;
            out.midV = midV;
            out.tangent = packedTangent;
            out.blockId = this.currentBlockId;

            vertexIdx += lightOrder;
        }

        TextureAtlasSprite sprite = quad.getSprite();

        if (sprite != null) {
            builder.addSprite(sprite);
        }

        builder.getVertexBuffer(facing)
                .push(vertices, material);
    }

    private static void setVertex(ModelQuadViewMutable quad, int i, float x, float y, float z, float u, float v) {
        quad.setX(i, x);
        quad.setY(i, y);
        quad.setZ(i, z);
        quad.setTexU(i, u);
        quad.setTexV(i, v);
    }

    private float getCornerHeight(IBlockAccess world, int x, int y, int z, Fluid fluid) {
        int samples = 0;
        float totalHeight = 0.0F;

        for (int i = 0; i < 4; ++i) {
            int x2 = x - (i & 1);
            int z2 = z - (i >> 1 & 1);

            if (WorldUtil.getFluidOfBlock(world.getBlockState(this.scratchPos.setPos(x2, y + 1, z2)).getBlock()) == fluid) {
                return 1.0F;
            }

            BlockPos pos = this.scratchPos.setPos(x2, y, z2);

            IBlockState blockState = world.getBlockState(pos);
            Fluid fluid2 = WorldUtil.getFluidOfBlock(blockState.getBlock());

            if (fluid == fluid2) {
                float height = WorldUtil.getFluidHeight(fluid2, blockState.getValue(BlockLiquid.LEVEL));

                if (height >= 0.8F) {
                    totalHeight += height * 10.0F;
                    samples += 10;
                } else {
                    totalHeight += height;
                    ++samples;
                }
            } else if (!blockState.getMaterial().isSolid()) {
                ++samples;
            }
        }

        if (samples == 0) {
            return 0.0F;
        }

        return totalHeight / (float) samples;
    }

    private static float lerp(float t, float a, float b) {
        return a + t * (b - a);
    }
}
