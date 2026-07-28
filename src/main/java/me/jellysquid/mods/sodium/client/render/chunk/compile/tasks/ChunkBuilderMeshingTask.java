package me.jellysquid.mods.sodium.client.render.chunk.compile.tasks;

import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBufferSorter;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildBuffers;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderCache;
import me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderContext;
import me.jellysquid.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import me.jellysquid.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.util.task.CancellationToken;
import me.jellysquid.mods.sodium.client.world.WorldSlice;
import me.jellysquid.mods.sodium.client.world.cloned.ChunkRenderContext;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BlockModelShapes;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.chunk.VisGraph;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.crash.CrashReport;
import net.minecraft.crash.CrashReportCategory;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.ReportedException;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Map;
import java.util.Objects;

/**
 * Rebuilds all the meshes of a chunk for each given render pass with non-occluded blocks. The result is then uploaded
 * to graphics memory on the main thread.
 *
 * <p>NOTE(yumelium): rewritten for 1.12.2 — stripped Embeddium/Forge-1.14+ features (IModelData/ModelDataSnapshotter,
 * ChunkDataBuiltEvent, MeshAppenderRenderer, UnwrappableBakedModel, EmbeddiumRenderLayerCache). Adapted MC APIs:
 * getRenderShape→getRenderType(EnumBlockRenderType), BakedModel→IBakedModel via BlockModelShapes#getModelForState,
 * render layers via Block#canRenderInLayer, FluidState→fluids-as-blocks (Material#isLiquid), BlockEntity→TileEntity,
 * VisGraph#computeVisibility, and net.minecraft.crash.* crash reports.
 */
public class ChunkBuilderMeshingTask extends ChunkBuilderTask<ChunkBuildOutput> {
    private final RenderSection render;
    private final ChunkRenderContext renderContext;

    private final int buildTime;

    private Vec3d camera = Vec3d.ZERO;

    public ChunkBuilderMeshingTask(RenderSection render, ChunkRenderContext renderContext, int time) {
        this.render = render;
        this.renderContext = renderContext;
        this.buildTime = time;
    }

    public ChunkBuilderMeshingTask withCameraPosition(Vec3d camera) {
        this.camera = camera;
        return this;
    }

    @Override
    public ChunkBuildOutput execute(ChunkBuildContext buildContext, CancellationToken cancellationToken) {
        BuiltSectionInfo.Builder renderData = new BuiltSectionInfo.Builder();
        VisGraph occluder = new VisGraph();

        ChunkBuildBuffers buffers = buildContext.buffers;
        buffers.init(renderData, this.render.getSectionIndex());

        BlockRenderCache cache = buildContext.cache;
        cache.init(this.renderContext);

        WorldSlice slice = cache.getWorldSlice();

        int minX = this.render.getOriginX();
        int minY = this.render.getOriginY();
        int minZ = this.render.getOriginZ();

        int maxX = minX + 16;
        int maxY = minY + 16;
        int maxZ = minZ + 16;

        // Initialise with minX/minY/minZ so initial getBlockState crash context is correct
        BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos(minX, minY, minZ);
        BlockPos.MutableBlockPos modelOffset = new BlockPos.MutableBlockPos();

        BlockRenderContext context = new BlockRenderContext(slice);

        try {
            for (int y = minY; y < maxY; y++) {
                if (cancellationToken.isCancelled()) {
                    return null;
                }

                for (int z = minZ; z < maxZ; z++) {
                    for (int x = minX; x < maxX; x++) {
                        IBlockState blockState = slice.getBlockState(x, y, z);

                        // Fast path - skip invisible blocks that don't have any custom logic
                        if (blockState.getRenderType() == EnumBlockRenderType.INVISIBLE && !blockState.getBlock().hasTileEntity(blockState)) {
                            continue;
                        }

                        blockPos.setPos(x, y, z);
                        modelOffset.setPos(x & 15, y & 15, z & 15);

                        // Forge fluid blocks must have exactly ONE renderer per mode, or the two surfaces z-fight —
                        // and the winner varies per section, which reads as "water cut off at chunk boundaries".
                        // Shaders ON: our FluidRenderer owns every fluid surface (shader water attributes); the pure
                        // fluid model (ModelFluid) is skipped below, while fluid-EMBEDDING blocks (Betweenlands
                        // underwater plants: real model + water in the same cell) keep their model and get their
                        // water from FluidRenderer. Shaders OFF: the mod renders itself exactly as it would without
                        // us — models own everything and FluidRenderer skips ALL IFluidBlock blocks (drawing an
                        // embedded-fluid cell here put an opaque murk-tinted cube over plant cells). Vanilla
                        // BlockLiquid is not an IFluidBlock, so vanilla water always uses FluidRenderer.
                        boolean shadersOn = com.yumelium.yumelium.shaders.pipeline.IrisPipeline.instance().isEnabled();

                        if (blockState.getRenderType() == EnumBlockRenderType.MODEL) {
                            // 1.12.2: connection/variant state (panes, fences, walls, stairs, redstone, doors, ...) lives
                            // in getActualState, and the model is chosen from it — like vanilla BlockRendererDispatcher.
                            // The render state additionally goes through getExtendedState (Forge IExtendedBlockState).
                            IBlockState actualState;
                            IBlockState renderState;
                            try {
                                actualState = blockState.getActualState(slice, blockPos);
                                renderState = blockState.getBlock().getExtendedState(actualState, slice, blockPos);
                            } catch (Throwable t) {
                                actualState = blockState;
                                renderState = blockState;
                                // The fallback hides whatever getActualState/getExtendedState needed to provide
                                // (connection variants, Forge extended data like fluid corner heights) — surface it
                                // once per block class instead of failing silently (lesson from the BTL water hunt).
                                if (EXT_STATE_FAILED.add(blockState.getBlock().getClass().getName())) {
                                    me.jellysquid.mods.sodium.client.SodiumClientMod.logger().warn(
                                            "getActualState/getExtendedState failed for "
                                                    + blockState.getBlock().getClass().getName()
                                                    + " — rendering with the plain state", t);
                                }
                            }

                            IBakedModel model = cache.getBlockModels().getModelForState(actualState);

                            // Blocks that merely EMBED a fluid (real model) never match — only the pure fluid model
                            // is skipped, and only while our FluidRenderer draws that surface instead (shaders ON).
                            // Toggling shaders rebuilds the renderer, so meshes always match the current ownership.
                            boolean forgeFluidModel = shadersOn && model != null
                                    && model.getClass().getName().startsWith("net.minecraftforge.client.model.ModelFluid$");

                            long seed = MathHelper.getPositionRandom(blockPos);

                            // Iris `layer.*` (block.properties): the pack forces specific blocks into another render
                            // layer — e.g. plain glass/pane/beacon into TRANSLUCENT so they render through
                            // gbuffers_water (glass shading + shader-side connected glass live only there); mods can
                            // match its solid/cutout/cutout_mipped lists too. Matches real Iris; null when shaders off.
                            java.util.Map<net.minecraft.block.Block, BlockRenderLayer> layerOverrides =
                                    com.yumelium.yumelium.shaders.pipeline.IrisPipeline.instance().layerOverrides();
                            BlockRenderLayer forcedLayer =
                                    layerOverrides == null ? null : layerOverrides.get(blockState.getBlock());
                            for (BlockRenderLayer layer : BlockRenderLayer.values()) {
                                boolean draw = !forgeFluidModel && (forcedLayer != null
                                        ? layer == forcedLayer
                                        : blockState.getBlock().canRenderInLayer(blockState, layer));
                                if (draw) {
                                    context.update(blockPos, modelOffset, renderState, model, seed, layer);
                                    cache.getBlockRenderer().renderModel(context, buffers);
                                }
                            }
                        }

                        // 1.12.2 fluids are blocks; render liquid geometry from the fluid block state. With shaders
                        // OFF, Forge fluid blocks (IFluidBlock — pure fluids AND fluid-embedding blocks) are entirely
                        // the mod's own models' responsibility (see the ownership note above).
                        boolean fluidRan = blockState.getMaterial().isLiquid()
                                && (shadersOn || !(blockState.getBlock() instanceof net.minecraftforge.fluids.IFluidBlock));
                        if (fluidRan) {
                            cache.getFluidRenderer().render(slice, blockState, blockPos, modelOffset, buffers);
                        }

                        if (blockState.getBlock().hasTileEntity(blockState)) {
                            TileEntity entity = slice.getTileEntity(blockPos);

                            if (entity != null) {
                                TileEntitySpecialRenderer<TileEntity> renderer = TileEntityRendererDispatcher.instance.getRenderer(entity);

                                if (renderer != null) {
                                    // 1.12.2's equivalent of upstream's shouldRenderOffScreen is
                                    // TileEntitySpecialRenderer.isGlobalRenderer (beacon/structure block override it):
                                    // global TESRs render regardless of section visibility. The END PORTAL renderer
                                    // does not override it, but vanilla exempts the portal from frustum culling via an
                                    // infinite render bounding box — classifying it CULLED made the whole portal
                                    // surface pop out whenever its section left the (zoomed) frustum.
                                    boolean global = renderer.isGlobalRenderer(entity)
                                            || entity instanceof net.minecraft.tileentity.TileEntityEndPortal;
                                    renderData.addBlockEntity(entity, !global);
                                }
                            }
                        }

                        if (blockState.isOpaqueCube()) {
                            occluder.setOpaqueCube(blockPos);
                        }
                    }
                }
            }
        } catch (ReportedException ex) {
            // Propagate existing crashes (add context)
            throw fillCrashInfo(ex.getCrashReport(), slice, blockPos);
        } catch (Throwable ex) {
            // Create a new crash report for other exceptions (e.g. thrown in getQuads)
            throw fillCrashInfo(CrashReport.makeCrashReport(ex, "Encountered exception while building chunk meshes"), slice, blockPos);
        }

        Map<TerrainRenderPass, BuiltSectionMeshParts> meshes = new Reference2ReferenceOpenHashMap<>();

        for (TerrainRenderPass pass : DefaultTerrainRenderPasses.ALL) {
            BuiltSectionMeshParts mesh = buffers.createMesh(pass);

            if (mesh != null) {
                if (pass.isSorted()) {
                    Objects.requireNonNull(mesh.getIndexData());
                    ChunkBufferSorter.sort(
                            mesh.getIndexData(),
                            mesh.getSortState(),
                            (float) camera.x - minX,
                            (float) camera.y - minY,
                            (float) camera.z - minZ
                    );
                }
                meshes.put(pass, mesh);
                renderData.addRenderPass(pass);
            }
        }

        renderData.setOcclusionData(occluder.computeVisibility());

        return new ChunkBuildOutput(this.render, renderData.build(), meshes, this.buildTime);
    }

    /** Once-per-block-class marker for the getActualState/getExtendedState failure warning above. */
    private static final java.util.Set<String> EXT_STATE_FAILED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private ReportedException fillCrashInfo(CrashReport report, WorldSlice slice, BlockPos pos) {
        CrashReportCategory crashReportSection = report.makeCategory("Block being rendered");

        IBlockState state = null;
        try {
            state = slice.getBlockState(pos);
        } catch (Exception ignored) {}
        CrashReportCategory.addBlockInfo(crashReportSection, pos, state);

        crashReportSection.addCrashSection("Chunk section", this.render);
        if (this.renderContext != null) {
            crashReportSection.addCrashSection("Render context volume", this.renderContext.getVolume());
        }

        return new ReportedException(report);
    }
}
