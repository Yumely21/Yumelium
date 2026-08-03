package me.jellysquid.mods.sodium.client.render.chunk;

import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import it.unimi.dsi.fastutil.longs.Long2ReferenceMaps;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.*;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gl.compat.FogHelper;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.util.math.RayTraceResult;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.gl.device.RenderDevice;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import me.jellysquid.mods.sodium.client.render.chunk.compile.executor.ChunkBuilder;
import me.jellysquid.mods.sodium.client.render.chunk.compile.executor.ChunkJobResult;
import me.jellysquid.mods.sodium.client.render.chunk.compile.executor.ChunkJobCollector;
import me.jellysquid.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import me.jellysquid.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderSortTask;
import me.jellysquid.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderTask;
import me.jellysquid.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import me.jellysquid.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import me.jellysquid.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import me.jellysquid.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import me.jellysquid.mods.sodium.client.render.chunk.lists.VisibleChunkCollector;
import me.jellysquid.mods.sodium.client.render.chunk.occlusion.GraphDirection;
import me.jellysquid.mods.sodium.client.render.chunk.occlusion.OcclusionCuller;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegion;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegionManager;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import me.jellysquid.mods.sodium.client.render.texture.SpriteUtil;
import me.jellysquid.mods.sodium.client.render.viewport.CameraTransform;
import me.jellysquid.mods.sodium.client.render.viewport.Viewport;
import me.jellysquid.mods.sodium.client.util.MathUtil;
import me.jellysquid.mods.sodium.client.util.iterator.ByteIterator;
import me.jellysquid.mods.sodium.client.world.WorldSlice;
import me.jellysquid.mods.sodium.client.world.cloned.ChunkRenderContext;
import me.jellysquid.mods.sodium.client.world.cloned.ClonedChunkSectionCache;
import net.minecraft.entity.Entity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.math.BlockPos;
import me.jellysquid.mods.sodium.client.util.math.ChunkSectionPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.util.math.Vec3d;
import org.apache.commons.lang3.ArrayUtils;
import org.embeddedt.embeddium.render.ShaderModBridge;
import org.embeddedt.embeddium.render.chunk.sorting.TranslucentQuadAnalyzer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

public class RenderSectionManager {
    private final ChunkBuilder builder;

    private final Thread renderThread = Thread.currentThread();

    private final RenderRegionManager regions;
    private final ClonedChunkSectionCache sectionCache;

    private final Long2ReferenceMap<RenderSection> sectionByPosition = new Long2ReferenceOpenHashMap<>();

    private final ConcurrentLinkedDeque<ChunkJobResult<ChunkBuildOutput>> buildResults = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<Runnable> asyncSubmittedTasks = new ConcurrentLinkedDeque<>();

    private final ChunkRenderer chunkRenderer;

    private final WorldClient world;

    private final ReferenceSet<RenderSection> sectionsWithGlobalEntities = new ReferenceOpenHashSet<>();

    private final OcclusionCuller occlusionCuller;

    private final int renderDistance;

    private final ChunkVertexType vertexType;

    @NotNull
    private SortedRenderLists renderLists;

    /** Iris shadows: the light-POV section list — see {@link #updateShadowRenderList}. */
    private SortedRenderLists shadowRenderLists = SortedRenderLists.empty();
    private int shadowSectionCount;

    /**
     * Frame counter for the shadow list build, SEPARATE from the camera's {@link #lastUpdatedFrame}.
     *
     * <p>It must have its own, because {@code ChunkRenderList} only clears itself when handed a frame number it has not
     * seen ({@code if (getLastVisibleFrame() != frame) reset(frame)}), and the shadow list is rebuilt independently of
     * the camera traversal. Reusing the camera counter meant the reset stopped firing on every frame that skipped a
     * camera update, so sections were appended to the previous frame's list until it overflowed —
     * {@code ArrayIndexOutOfBoundsException: Render list is full} — which aborted the shadow pass and left the shadow
     * map half-drawn, i.e. the shadows vanished. Since the frame-to-frame cache landed, the counter advances once per
     * REBUILD (not per frame) — the reset argument is unchanged because it keys on inequality, not on +1.</p>
     */
    private int shadowFrame;

    // --- shadow-list frame-to-frame cache -------------------------------------------------------------------------
    // The 67k-section walk in updateShadowRenderList cost 2-3 ms CPU EVERY frame at rd32. The cached list is only a
    // candidate SET — draw positions and the encode-time cull always use the CURRENT camera and matrices
    // (DefaultChunkRenderer), so over-inclusion is harmless and only OMISSION matters. The build therefore culls
    // with shadowListMargin() of extra slack, and the list is rebuilt before the allowed drift can consume it.
    /** Topology/geometry changed since the last build (set by {@link #updateSectionInfo}). */
    private boolean shadowListDirty = true;
    private double shadowListCameraX, shadowListCameraY, shadowListCameraZ;
    private final org.joml.Vector3f shadowListLightDir = new org.joml.Vector3f();
    /** computeShadowMatrices' up-vector branch selector (|light.y| > 0.99) at build time: when the sun crosses that
     * threshold the light-space X/Y basis ROLLS discontinuously, which the direction-drift test cannot see — the
     * square |lx|,|ly| cull is basis-dependent, so a roll invalidates the cached list even with an unchanged light
     * direction (only reachable with user SUN_ANGLE settings that take the sun near vertical). */
    private boolean shadowListUpFlipped;
    private int[] shadowListVoxHalf;
    /** The pack shadow distance the cached list was culled against; NaN forces the first build. */
    private float shadowListShadowDistance = Float.NaN;
    /** Rotation part of the shadow view matrix at build time, row-major (m00,m10,m20, m01,m11,m21, m02,m12,m22).
     * Paired with {@link #SHADOW_LIST_BASIS_EPS}. */
    private final float[] shadowListBasis = new float[9];
    private int shadowListAge;

    /** Max per-axis camera drift (blocks) tolerated before rebuild. Paired with the build margin — see
     * {@link #shadowListMargin()}, which DERIVES the margin from this and from the pack's live shadow distance so the
     * pair can no longer drift apart by hand. */
    private static final double SHADOW_LIST_REBUILD_DISTANCE = 8.0;
    /**
     * Rebuild threshold for the shadow-map BASIS, as the Frobenius norm of the change in its rotation part.
     *
     * <p>This deliberately does not measure an angle. What displaces a section in light space is
     * {@code l = M·p}, so the drift to bound is {@code |(M_now − M_build)·p| ≤ ‖ΔM‖_F · |p|} — a matrix-norm
     * statement that is true for ANY basis change, with no trigonometry and therefore no geometry left to model
     * wrongly. The same constant then appears in {@link #shadowListMargin()} multiplied by the box radius, so the
     * cache key and the margin are two halves of one inequality by construction.</p>
     *
     * <p>Why not the light DIRECTION (which SHADOW_LIST_LIGHT_DOT_MIN keys on, and which is kept as a cheap
     * additional trigger): the basis comes from {@code lookAt(light, origin, up)}, and the cross product with a
     * fixed up-vector AMPLIFIES azimuthal light motion as the sun approaches vertical. A half-degree of light
     * movement can roll the basis by several degrees, which a direction-only key cannot see. That was harmless
     * while the ortho was pinned at ±192; once the box follows the pack's shadowDistance the box radius — and with
     * it the displacement per degree — grows, so the gap became reachable at large shadow distances with a
     * non-default SUN_ANGLE.</p>
     *
     * <p>Value: the Frobenius norm of a rotation by α is {@code 2√2·sin(α/2)}, so 0.5° ⇒ 0.012340. Chosen to keep
     * the rebuild cadence the previous 0.5° direction threshold produced.</p>
     */
    private static final float SHADOW_LIST_BASIS_EPS = 0.01234F;

    /**
     * Extra cull slack (blocks) applied ONLY at list build, so the cached list provably stays a superset of what the
     * exact per-frame encode cull will accept for any drift below the rebuild thresholds.
     *
     * <p>Two constraints, both of which must hold; the margin is their max:</p>
     * <ul>
     *   <li><b>A (shadow box):</b> {@code √3·drift + rot·R ≤ MARGIN + boxSlack}, where {@code R} is the shadow box's
     *       max radius in light space and {@code boxSlack} is the part of the pipeline's per-axis section slack not
     *       already consumed by a section's own half-diagonal (8√3). {@code R} grows with the shadow distance, which
     *       is why this must be computed and not written down: the old constant was derived at shadowDistance=192.</li>
     *   <li><b>B (voxel box):</b> {@code drift ≤ MARGIN − 8}, per axis.</li>
     * </ul>
     *
     * <p>Sanity check against the value this replaces: at shadowDistance 192, R = √(2·216² + 600²) ≈ 673, so
     * A needs √3·8 + 0.00873·673 − 10.14 ≈ 9.6 and B needs 8 + 8 = 16 → 16.0, exactly the constant that was verified
     * in-game. B dominates for every realistic shadow distance; A only overtakes it past ~500 blocks.</p>
     */
    public static float shadowListMargin() {
        float shadowDistance = com.yumelium.yumelium.shaders.pipeline.IrisPipeline.shadowDistanceBlocks();
        float sectionSlack = com.yumelium.yumelium.shaders.pipeline.IrisPipeline.SHADOW_BOX_SECTION_SLACK;
        float eyePullback = com.yumelium.yumelium.shaders.pipeline.IrisPipeline.SHADOW_EYE_PULLBACK;

        // Light-space half-extents of the box isSectionOutsideShadow tests against.
        float cull = shadowDistance + sectionSlack;                                  // |lx|, |ly|
        float depth = 2.0F * (shadowDistance + eyePullback) + sectionSlack;          // lz span
        float maxRadius = (float) Math.sqrt(2.0 * cull * cull + (double) depth * depth);

        // The section half-diagonal already eats part of the pipeline's per-axis slack; only the rest is headroom.
        float boxSlack = sectionSlack - 8.0F * (float) Math.sqrt(3.0);
        float a = (float) (Math.sqrt(3.0) * SHADOW_LIST_REBUILD_DISTANCE)
                + SHADOW_LIST_BASIS_EPS * maxRadius - boxSlack;
        float b = (float) SHADOW_LIST_REBUILD_DISTANCE + 8.0F;
        return Math.max(a, b);
    }
    /** cos(0.5°): rebuild when the shadow light has rotated more than half a degree since the build. Do not tighten
     * below ~cos(0.05°) — it would drown in normalize() float noise and rebuild every frame. */
    private static final float SHADOW_LIST_LIGHT_DOT_MIN = 0.99996192F;
    /** Belt-and-braces TTL: force a rebuild at least every N frames (~10 s at 60 fps). */
    private static final int SHADOW_LIST_MAX_AGE = 600;

    /** Drop shadow-pass sections that are buried under the terrain — see {@link #isFullyUnderground}.
     * NOTE (2026-08-03): bisected OFF against the BL decay-pit phantom light shafts — streaks unchanged, so this
     * cull is EXONERATED for that bug (despite the shaft-light hole in the javadoc's soundness argument, which
     * remains real in principle: sun through a tall open shaft reaches buried sections of neighbouring chunks
     * crossing only air). Re-enabled. */
    private static final boolean SHADOW_CULL_UNDERGROUND = true;
    /** Blocks of slack below the chunk's lowest exposed column before a section counts as buried. One section's worth. */
    private static final int SHADOW_UNDERGROUND_MARGIN = 16;
    /** Per-build cache of each chunk's minimum heightmap, so the 16 sections of a column share one lookup. */
    private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap chunkLowestHeight = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
    private int shadowCulledUnderground;

    {
        this.chunkLowestHeight.defaultReturnValue(Integer.MIN_VALUE);
    }

    @NotNull
    private Map<ChunkUpdateType, ArrayDeque<RenderSection>> rebuildLists;

    private int lastUpdatedFrame;

    private boolean needsUpdate;

    private @Nullable BlockPos lastCameraPosition;
    private Vec3d cameraPosition = Vec3d.ZERO;

    private final boolean translucencySorting;

    public RenderSectionManager(WorldClient world, int renderDistance, CommandList commandList) {
        // Iris: while shaders are active, mesh terrain into the normal-extended compact format (a_Normal) so pack
        // gbuffers_terrain shaders + the built-in deferred lighting get a real geometric normal. The shader toggle
        // reloads the renderer, so this is re-evaluated and the whole world is re-meshed at the matching stride.
        ChunkVertexType vertexType = SodiumClientMod.canUseVanillaVertices() ? ChunkMeshFormats.VANILLA_LIKE : ChunkMeshFormats.activeCompact();

        this.chunkRenderer = new DefaultChunkRenderer(RenderDevice.INSTANCE, vertexType);

        this.vertexType = vertexType;

        this.world = world;
        this.builder = new ChunkBuilder(world, vertexType);

        this.needsUpdate = true;
        this.renderDistance = renderDistance;

        this.regions = new RenderRegionManager(commandList);
        this.sectionCache = new ClonedChunkSectionCache(this.world);

        this.renderLists = SortedRenderLists.empty();
        this.occlusionCuller = new OcclusionCuller(Long2ReferenceMaps.unmodifiable(this.sectionByPosition), this.world);

        this.rebuildLists = new EnumMap<>(ChunkUpdateType.class);

        for (var type : ChunkUpdateType.values()) {
            this.rebuildLists.put(type, new ArrayDeque<>());
        }

        this.translucencySorting = SodiumClientMod.canApplyTranslucencySorting();
    }

    public void runAsyncTasks() {
        Runnable task;

        while ((task = this.asyncSubmittedTasks.poll()) != null) {
            task.run();
        }
    }

    public void update(Entity camera, Viewport viewport, int frame, boolean spectator) {
        this.lastCameraPosition = camera.getPosition();
        this.cameraPosition = camera.getPositionVector();

        this.createTerrainRenderList(camera, viewport, frame, spectator);

        this.needsUpdate = false;
        this.lastUpdatedFrame = frame;
    }

    private void checkTranslucencyChange() {
        if(!this.translucencySorting || lastCameraPosition == null)
            return;

        int camSectionX = ChunkSectionPos.getSectionCoord(MathHelper.floor(cameraPosition.x));
        int camSectionY = ChunkSectionPos.getSectionCoord(MathHelper.floor(cameraPosition.y));
        int camSectionZ = ChunkSectionPos.getSectionCoord(MathHelper.floor(cameraPosition.z));

        this.scheduleTranslucencyUpdates(camSectionX, camSectionY, camSectionZ);
    }

    private void scheduleTranslucencyUpdates(int camSectionX, int camSectionY, int camSectionZ) {
        var sortRebuildList = this.rebuildLists.get(ChunkUpdateType.SORT);
        var importantSortRebuildList = this.rebuildLists.get(ChunkUpdateType.IMPORTANT_SORT);
        var allowImportant = allowImportantRebuilds();
        for (Iterator<ChunkRenderList> it = this.renderLists.iterator(); it.hasNext(); ) {
            ChunkRenderList entry = it.next();
            var region = entry.getRegion();
            ByteIterator sectionIterator = entry.sectionsWithGeometryIterator(false);
            if (sectionIterator == null) {
                continue;
            }
            while (sectionIterator.hasNext()) {
                var section = region.getSection(sectionIterator.nextByteAsInt());

                if (section == null || !section.isBuilt()) {
                    // Nonexistent/unbuilt sections are not relevant
                    continue;
                }

                boolean hasTranslucentData = section.isNeedsDynamicTranslucencySorting();

                if (!hasTranslucentData) {
                    // Sections without sortable translucent data are not relevant
                    continue;
                }

                ChunkUpdateType update = ChunkUpdateType.getPromotionUpdateType(section.getPendingUpdate(), (allowImportant && this.shouldPrioritizeRebuild(section)) ? ChunkUpdateType.IMPORTANT_SORT : ChunkUpdateType.SORT);

                if (update == null) {
                    // We wouldn't be able to resort this section anyway
                    continue;
                }

                double dx = cameraPosition.x - section.lastCameraX;
                double dy = cameraPosition.y - section.lastCameraY;
                double dz = cameraPosition.z - section.lastCameraZ;
                double camDelta = (dx * dx) + (dy * dy) + (dz * dz);

                if (camDelta < 1) {
                    // Didn't move enough, ignore
                    continue;
                }

                boolean cameraChangedSection = camSectionX != ChunkSectionPos.getSectionCoord(MathHelper.floor(section.lastCameraX)) ||
                        camSectionY != ChunkSectionPos.getSectionCoord(MathHelper.floor(section.lastCameraY)) ||
                        camSectionZ != ChunkSectionPos.getSectionCoord(MathHelper.floor(section.lastCameraZ));

                if (cameraChangedSection || section.isAlignedWithSectionOnGrid(camSectionX, camSectionY, camSectionZ)) {
                    section.setPendingUpdate(update);
                    // Inject it into the rebuild lists
                    (update == ChunkUpdateType.IMPORTANT_SORT ? importantSortRebuildList : sortRebuildList).add(section);

                    section.lastCameraX = cameraPosition.x;
                    section.lastCameraY = cameraPosition.y;
                    section.lastCameraZ = cameraPosition.z;
                }
            }
        }
    }

    private void createTerrainRenderList(Entity camera, Viewport viewport, int frame, boolean spectator) {
        this.resetRenderLists();

        final var searchDistance = this.getSearchDistance();
        final var useOcclusionCulling = this.shouldUseOcclusionCulling(camera, spectator);

        var visitor = new VisibleChunkCollector(frame);

        this.occlusionCuller.findVisible(visitor, viewport, searchDistance, useOcclusionCulling, frame);

        this.renderLists = visitor.createRenderLists();
        this.rebuildLists = visitor.getRebuildLists();

        this.checkTranslucencyChange();
    }

    private float getSearchDistance() {
        float distance;

        // TODO: does *every* shaderpack really disable fog?
        if (SodiumClientMod.options().advanced.useFogOcclusion && !ShaderModBridge.areShadersEnabled()) {
            distance = this.getEffectiveRenderDistance();
        } else {
            distance = this.getRenderDistance();
        }

        return distance;
    }

    private boolean shouldUseOcclusionCulling(Entity camera, boolean spectator) {
        final boolean useOcclusionCulling;
        BlockPos origin = camera.getPosition();

        if (spectator && this.world.getBlockState(origin)
                .isOpaqueCube())
        {
            useOcclusionCulling = false;
        } else {
            useOcclusionCulling = true;
        }
        return useOcclusionCulling;
    }

    private void resetRenderLists() {
        this.renderLists = SortedRenderLists.empty();

        for (var list : this.rebuildLists.values()) {
            list.clear();
        }
    }

    public void onSectionAdded(int x, int y, int z) {
        long key = ChunkSectionPos.asLong(x, y, z);

        if (this.sectionByPosition.containsKey(key)) {
            return;
        }

        RenderRegion region = this.regions.createForChunk(x, y, z);

        RenderSection renderSection = new RenderSection(region, x, y, z);
        region.addSection(renderSection);

        this.sectionByPosition.put(key, renderSection);

        Chunk chunk = this.world.getChunk(x, z);
        var sectionArray = chunk.getBlockStorageArray();
        ExtendedBlockStorage section = y >= 0 && y < sectionArray.length ? sectionArray[y] : Chunk.NULL_BLOCK_STORAGE;

        // NOTE(yumelium): dropped Embeddium's ChunkMeshEvent (mod mesh-appender API); a section is empty if it has no blocks.
        boolean isEmpty = (section == Chunk.NULL_BLOCK_STORAGE || section.isEmpty());
        if (isEmpty) {
            this.updateSectionInfo(renderSection, BuiltSectionInfo.EMPTY);
        } else {
            renderSection.setPendingUpdate(ChunkUpdateType.INITIAL_BUILD);
        }

        this.connectNeighborNodes(renderSection);

        this.needsUpdate = true;
    }

    public void onSectionRemoved(int x, int y, int z) {
        RenderSection section = this.sectionByPosition.remove(ChunkSectionPos.asLong(x, y, z));

        if (section == null) {
            return;
        }

        RenderRegion region = section.getRegion();

        if (region != null) {
            region.removeSection(section);
        }

        this.disconnectNeighborNodes(section);
        this.updateSectionInfo(section, null);

        section.delete();

        this.needsUpdate = true;
    }

    public void renderLayer(ChunkRenderMatrices matrices, TerrainRenderPass pass, double x, double y, double z) {
        RenderDevice device = RenderDevice.INSTANCE;
        CommandList commandList = device.createCommandList();

        this.chunkRenderer.render(matrices, commandList, this.renderLists, pass, new CameraTransform(x, y, z));

        commandList.flush();
    }

    /**
     * Iris shadows: renders a pass from the SHADOW section list built by {@link #updateShadowRenderList}, not the
     * camera-visible one.
     */
    public void renderShadowLayer(ChunkRenderMatrices matrices, TerrainRenderPass pass, double x, double y, double z) {
        RenderDevice device = RenderDevice.INSTANCE;
        CommandList commandList = device.createCommandList();

        this.chunkRenderer.render(matrices, commandList, this.shadowRenderLists, pass, new CameraTransform(x, y, z));

        commandList.flush();
    }

    /**
     * Iris shadows: builds the section list for the light's point of view — every loaded section inside the shadow ortho
     * box, regardless of where the camera is looking.
     *
     * <p>The shadow pass used to reuse the camera-visible list, which meant a shadow caster stopped casting the moment it
     * left the view frustum. Since a shadow is cast by whatever sits between a surface and the SUN — usually behind or
     * beside the camera — turning around could drop most casters at once and the shadows would vanish wholesale.</p>
     *
     * <p>This deliberately does NOT reuse {@link OcclusionCuller}: its graph walk is a camera-visibility concept (and
     * refuses to revisit a section already marked with the current frame, so it cannot run twice per frame anyway).
     * Shadows need a BOX, so this filters the loaded sections directly with the pipeline's own shadow-ortho test — the
     * same one {@code DefaultChunkRenderer} applies per section, so nothing that could cast into the map is dropped.</p>
     *
     * <p>Sections are collected into each region's separate shadow list, so the camera's list — built before this and
     * consumed after it — is untouched. The collector's rebuild queues are discarded: chunk rebuild scheduling stays
     * driven by camera visibility.</p>
     */
    public void updateShadowRenderList() {
        var pipeline = com.yumelium.yumelium.shaders.pipeline.IrisPipeline.instance();
        var camera = pipeline.shadowCameraPos();
        var light = pipeline.shadowLightDirection();
        int[] cacheVoxHalf = pipeline.voxelVolumeHalfExtents();

        // Frame-to-frame cache: reuse the retained shadowRenderLists while the world and the shadow box are close
        // enough to the build-time state. Only the candidate SET is cached — draw positions and the encode-time cull
        // (DefaultChunkRenderer) always use the CURRENT camera/matrices, so a stale list can only over-include
        // (harmless) once the build margins cover the allowed drift. Stale entries cannot crash: a removed section
        // draws nothing (zeroed mesh data) and a deleted region is skipped (null storage).
        boolean cameraMoved = Math.abs(camera.x - this.shadowListCameraX) > SHADOW_LIST_REBUILD_DISTANCE
                || Math.abs(camera.y - this.shadowListCameraY) > SHADOW_LIST_REBUILD_DISTANCE
                || Math.abs(camera.z - this.shadowListCameraZ) > SHADOW_LIST_REBUILD_DISTANCE;
        boolean lightMoved = this.shadowListLightDir.dot(light.x(), light.y(), light.z()) < SHADOW_LIST_LIGHT_DOT_MIN;
        // The basis is what actually moves a section in light space, and it can rotate faster than the light
        // direction does — see SHADOW_LIST_BASIS_EPS. This is the trigger the margin's rotation term is paired with;
        // lightMoved above is kept as a cheaper early trigger, and upFlipped as belt-and-braces for the discontinuous
        // up-vector switch (which this norm would also catch, since the basis rolls).
        boolean basisMoved = shadowBasisDrift(pipeline.shadowViewMatrix()) > SHADOW_LIST_BASIS_EPS;
        boolean upFlipped = Math.abs(light.y()) > 0.99F;
        boolean voxChanged = !java.util.Arrays.equals(cacheVoxHalf, this.shadowListVoxHalf);
        // The shadow distance is a live shader-option slider: moving it resizes the very box this list was culled
        // against, so a cached list built for the old box may be missing sections the new one needs. Exact compare —
        // the option only changes when the user changes it.
        float shadowDistance = com.yumelium.yumelium.shaders.pipeline.IrisPipeline.shadowDistanceBlocks();
        boolean shadowBoxChanged = shadowDistance != this.shadowListShadowDistance;
        if (!this.shadowListDirty && !cameraMoved && !lightMoved && !basisMoved
                && upFlipped == this.shadowListUpFlipped && !voxChanged
                && !shadowBoxChanged && this.shadowListAge < SHADOW_LIST_MAX_AGE) {
            this.shadowListAge++;
            return; // reuse this.shadowRenderLists; shadowFrame deliberately NOT incremented (no reset needed)
        }
        final float margin = shadowListMargin();

        // Its OWN counter — see the shadowFrame field. Using the camera's lastUpdatedFrame overflowed the lists.
        var collector = new VisibleChunkCollector(++this.shadowFrame, true);

        this.chunkLowestHeight.clear();
        int visited = 0;
        int culledUnderground = 0;

        // Colored lighting: the light-source voxelization is an imageStore in the shadow VERTEX, so a section that
        // never reaches the shadow-pass draw is never voxelized — its light sources are simply absent from the
        // flood-fill. Every section intersecting the camera-anchored voxel volume must therefore bypass BOTH shadow
        // culls. The underground cull made this fatal in the NETHER: the bedrock roof is the heightmap, so the whole
        // world counted as "buried", nothing was voxelized, and e.g. the nether portal lost its purple self-light
        // (replaced by a runaway screen-space-blocklight feedback = the blazing portal). Also fixes torch/portal
        // colored light in overworld caves. null when the pack/config has no colored lighting → culls run as before.
        int[] voxHalf = cacheVoxHalf;

        for (var section : this.sectionByPosition.values()) {
            // Section centre, relative to the camera — the space the shadow matrices are built in.
            float rx = (float) ((section.getOriginX() + 8) - camera.x);
            float ry = (float) ((section.getOriginY() + 8) - camera.y);
            float rz = (float) ((section.getOriginZ() + 8) - camera.z);

            // +16: the section's own extent (±8 around its centre) plus the voxelizer's sub-block offsets.
            // +margin (shadowListMargin()): build-only slack so the CACHED list stays a superset of the encode-time
            // exemption for any camera drift below the rebuild threshold (colored light must never pop).
            boolean inVoxelVolume = voxHalf != null
                    && Math.abs(rx) <= voxHalf[0] + 16.0F + margin
                    && Math.abs(ry) <= voxHalf[1] + 16.0F + margin
                    && Math.abs(rz) <= voxHalf[2] + 16.0F + margin;
            if (!inVoxelVolume) {
                if (pipeline.isSectionOutsideShadow(rx, ry, rz, margin)) {
                    continue;
                }
                if (SHADOW_CULL_UNDERGROUND && isFullyUnderground(section)) {
                    culledUnderground++;
                    continue;
                }
            }

            collector.visit(section, true);
            visited++;
        }

        this.shadowRenderLists = collector.createRenderLists();
        this.shadowSectionCount = visited;
        this.shadowCulledUnderground = culledUnderground;

        // Record the cache key of this build.
        this.shadowListDirty = false;
        this.shadowListAge = 0;
        this.shadowListCameraX = camera.x;
        this.shadowListCameraY = camera.y;
        this.shadowListCameraZ = camera.z;
        this.shadowListLightDir.set(light.x(), light.y(), light.z());
        this.shadowListUpFlipped = Math.abs(light.y()) > 0.99F;
        this.shadowListVoxHalf = cacheVoxHalf == null ? null : cacheVoxHalf.clone();
        this.shadowListShadowDistance = shadowDistance;
        storeShadowBasis(pipeline.shadowViewMatrix());
    }

    /**
     * @return {@code ‖M_now − M_build‖_F} over the rotation part of the shadow view matrix. Bounds the light-space
     * displacement of any section since the build by {@code (this) × |p|}, which is exactly the quantity
     * {@link #shadowListMargin()}'s rotation term budgets for.
     */
    private float shadowBasisDrift(org.joml.Matrix4fc m) {
        float[] b = this.shadowListBasis;
        float d0 = m.m00() - b[0], d1 = m.m10() - b[1], d2 = m.m20() - b[2];
        float d3 = m.m01() - b[3], d4 = m.m11() - b[4], d5 = m.m21() - b[5];
        float d6 = m.m02() - b[6], d7 = m.m12() - b[7], d8 = m.m22() - b[8];
        return (float) Math.sqrt(d0 * d0 + d1 * d1 + d2 * d2 + d3 * d3 + d4 * d4
                + d5 * d5 + d6 * d6 + d7 * d7 + d8 * d8);
    }

    private void storeShadowBasis(org.joml.Matrix4fc m) {
        float[] b = this.shadowListBasis;
        b[0] = m.m00(); b[1] = m.m10(); b[2] = m.m20();
        b[3] = m.m01(); b[4] = m.m11(); b[5] = m.m21();
        b[6] = m.m02(); b[7] = m.m12(); b[8] = m.m22();
    }

    /**
     * Drop sections buried under the terrain from the shadow pass.
     *
     * <p>A shadow map stores the depth of whatever is NEAREST the sun. A section with solid ground above every one of its
     * columns can never be that nearest thing — the ground above it always is — so drawing it only costs vertices and
     * changes no pixel. The shadow box spans the full world height (its along-sun depth is ~576 blocks), so without this
     * the pass redraws bedrock-to-surface for all ~730 chunks around the camera every frame.</p>
     *
     * <p>The test uses the chunk's minimum heightmap, so a section is only dropped when EVERY column in its 16×16
     * footprint is covered — not merely the one above its centre. {@link #SHADOW_UNDERGROUND_MARGIN} adds slack for the
     * one case the heightmap cannot speak to: a low sun sends near-horizontal rays, which can in principle reach a
     * vertically-covered section through an opening in a neighbouring chunk. Anything reaching it that way would have to
     * pass geometry that is itself in the shadow map (and thus nearer the sun), so the margin is belt-and-braces.</p>
     */
    private boolean isFullyUnderground(RenderSection section) {
        long key = net.minecraft.util.math.ChunkPos.asLong(section.getChunkX(), section.getChunkZ());
        int lowest = this.chunkLowestHeight.get(key);
        if (lowest == Integer.MIN_VALUE) {
            net.minecraft.world.chunk.Chunk chunk = this.world.getChunk(section.getChunkX(), section.getChunkZ());
            lowest = chunk == null ? 0 : chunk.getLowestHeight();
            this.chunkLowestHeight.put(key, lowest);
        }
        return section.getOriginY() + 16 + SHADOW_UNDERGROUND_MARGIN <= lowest;
    }

    /** @return how many sections the last shadow-list build dropped as buried (diagnostic). */
    public int getShadowCulledUnderground() {
        return this.shadowCulledUnderground;
    }

    /** @return how many sections the last {@link #updateShadowRenderList} put in the shadow list (diagnostic). */
    public int getShadowSectionCount() {
        return this.shadowSectionCount;
    }

    /** @return frames the current shadow list has been served from cache (0 = rebuilt this frame; diagnostic). */
    public int getShadowListAge() {
        return this.shadowListAge;
    }

    public void tickVisibleRenders() {
        // NOTE(yumelium): upstream marks the animated sprites of visible sections active so off-screen animations pause.
        // On 1.12.2 vanilla ticks ALL atlas sprites itself, so SpriteUtil.markSpriteActive is a no-op — walking every
        // visible section + its sprite list each frame just to call a no-op is pure waste. Skip it entirely. (If the
        // "animate only visible textures" optimization is ever implemented in SpriteUtil, drop this early return.)
        if (true) {
            return;
        }

        Iterator<ChunkRenderList> it = this.renderLists.iterator();

        while (it.hasNext()) {
            ChunkRenderList renderList = it.next();

            var region = renderList.getRegion();
            var iterator = renderList.sectionsWithSpritesIterator();

            if (iterator == null) {
                continue;
            }

            while (iterator.hasNext()) {
                var section = region.getSection(iterator.nextByteAsInt());

                if (section == null) {
                    continue;
                }

                var sprites = section.getAnimatedSprites();

                if (sprites == null) {
                    continue;
                }

                for (TextureAtlasSprite sprite : sprites) {
                    SpriteUtil.markSpriteActive(sprite);
                }
            }
        }
    }

    public boolean isSectionVisible(int x, int y, int z) {
        RenderSection render = this.getRenderSection(x, y, z);

        if (render == null) {
            return false;
        }

        return render.getLastVisibleFrame() == this.lastUpdatedFrame;
    }

    public void updateChunks(boolean updateImmediately) {
        this.sectionCache.cleanup();
        this.regions.update();

        var blockingRebuilds = new ChunkJobCollector(Integer.MAX_VALUE, this.buildResults::add);
        var deferredRebuilds = new ChunkJobCollector(this.builder.getSchedulingBudget(), this.buildResults::add);

        this.submitRebuildTasks(blockingRebuilds, ChunkUpdateType.IMPORTANT_REBUILD);
        this.submitRebuildTasks(blockingRebuilds, ChunkUpdateType.IMPORTANT_SORT);
        this.submitRebuildTasks(updateImmediately ? blockingRebuilds : deferredRebuilds, ChunkUpdateType.REBUILD);
        this.submitRebuildTasks(updateImmediately ? blockingRebuilds : deferredRebuilds, ChunkUpdateType.INITIAL_BUILD);

        // Count sort tasks as requiring a quarter of the resources of a mesh task
        var deferredSorts = new ChunkJobCollector(Math.max(4, this.builder.getSchedulingBudget() * 4), this.buildResults::add);
        this.submitRebuildTasks(updateImmediately ? blockingRebuilds : deferredSorts, ChunkUpdateType.SORT);

        blockingRebuilds.awaitCompletion(this.builder);
    }

    public void uploadChunks() {
        var results = this.collectChunkBuildResults();

        if (results.isEmpty()) {
            return;
        }

        this.processChunkBuildResults(results);

        for (var result : results) {
            result.delete();
        }

        this.needsUpdate = true;
    }

    private void processChunkBuildResults(ArrayList<ChunkBuildOutput> results) {
        var filtered = filterChunkBuildResults(results);

        this.regions.uploadMeshes(RenderDevice.INSTANCE.createCommandList(), filtered);

        for (var result : filtered) {
            if(result.info != null) {
                this.updateSectionInfo(result.render, result.info);
                if (this.translucencySorting) {
                    // We only change the translucency info on full rebuilds, as sorts can keep using the same data
                    this.updateTranslucencyInfo(result.render, result.meshes);
                }
            }

            var job = result.render.getBuildCancellationToken();

            if (job != null && result.buildTime >= result.render.getLastSubmittedFrame()) {
                result.render.setBuildCancellationToken(null);
            }

            result.render.setLastBuiltFrame(result.buildTime);
        }
    }

    private void updateTranslucencyInfo(RenderSection render, Map<TerrainRenderPass, BuiltSectionMeshParts> meshes) {
        Map<TerrainRenderPass, TranslucentQuadAnalyzer.SortState> sortStates = new Reference2ObjectArrayMap<>();
        for(var entry : meshes.entrySet()) {
            if(entry.getKey().isSorted()) {
                sortStates.put(entry.getKey(), entry.getValue().getSortState().compactForStorage());
            }
        }
        render.setTranslucencySortStates(sortStates.isEmpty() ? Collections.emptyMap() : sortStates);
    }

    private void updateSectionInfo(RenderSection render, BuiltSectionInfo info) {
        // Shadow-list cache: membership/flags/heightmap may have changed. This single site covers section add
        // (empty branch + built results), section remove (null info), and every mesh rebuild — the underground cull
        // depends on heightmap changes carried by rebuilds whose flags do NOT change (digging exposes a different,
        // still-culled section below), so do not narrow this to flag transitions.
        this.shadowListDirty = true;
        render.setInfo(info);

        if (info == null || ArrayUtils.isEmpty(info.globalBlockEntities)) {
            this.sectionsWithGlobalEntities.remove(render);
        } else {
            this.sectionsWithGlobalEntities.add(render);
        }
    }

    private static List<ChunkBuildOutput> filterChunkBuildResults(ArrayList<ChunkBuildOutput> outputs) {
        var map = new Reference2ReferenceLinkedOpenHashMap<RenderSection, ChunkBuildOutput>();

        for (var output : outputs) {
            if (output.render.isDisposed() || output.render.getLastBuiltFrame() > output.buildTime) {
                continue;
            }

            var render = output.render;
            var previous = map.get(render);

            if (previous == null || previous.buildTime < output.buildTime) {
                map.put(render, output);
            }
        }

        return new ArrayList<>(map.values());
    }

    private ArrayList<ChunkBuildOutput> collectChunkBuildResults() {
        ArrayList<ChunkBuildOutput> results = new ArrayList<>();
        ChunkJobResult<ChunkBuildOutput> result;

        while ((result = this.buildResults.poll()) != null) {
            results.add(result.unwrap());
        }

        return results;
    }

    private void submitRebuildTasks(ChunkJobCollector collector, ChunkUpdateType type) {
        var queue = this.rebuildLists.get(type);

        while (!queue.isEmpty() && collector.canOffer()) {
            RenderSection section = queue.remove();

            if (section.isDisposed()) {
                continue;
            }

            // Because Sodium creates the update queue on the frame before it's processed,
            // the update type might no longer match. Filter out such a scenario.
            if (section.getPendingUpdate() != type) {
                continue;
            }

            int frame = this.lastUpdatedFrame;
            ChunkBuilderTask<ChunkBuildOutput> task = type.isSort() ? this.createSortTask(section, frame) : this.createRebuildTask(section, frame);

            if (task == null && type.isSort()) {
                // Ignore sorts that became invalid
                section.setPendingUpdate(null);
                continue;
            }

            if (task != null) {
                var job = this.builder.scheduleTask(task, type.isImportant(), collector::onJobFinished);
                collector.addSubmittedJob(job);

                section.setBuildCancellationToken(job);

                if (!type.isSort()) {
                    // Prevent further sorts from being performed on this section
                    section.setTranslucencySortStates(Collections.emptyMap());
                }
            } else {
                var result = ChunkJobResult.successfully(new ChunkBuildOutput(section, BuiltSectionInfo.EMPTY, Collections.emptyMap(), frame));
                this.buildResults.add(result);

                section.setBuildCancellationToken(null);
            }

            section.setLastSubmittedFrame(frame);
            section.setPendingUpdate(null);
        }
    }

    public @Nullable ChunkBuilderMeshingTask createRebuildTask(RenderSection render, int frame) {
        ChunkRenderContext context = WorldSlice.prepare(this.world, render.getPosition(), this.sectionCache);

        if (context == null) {
            return null;
        }

        return new ChunkBuilderMeshingTask(render, context, frame).withCameraPosition(this.cameraPosition);
    }

    public ChunkBuilderSortTask createSortTask(RenderSection render, int frame) {
        Map<TerrainRenderPass, TranslucentQuadAnalyzer.SortState> sortStates = render.getTranslucencySortStates();
        if(sortStates.isEmpty() || sortStates.values().stream().noneMatch(TranslucentQuadAnalyzer.SortState::requiresDynamicSorting))
            return null;
        return new ChunkBuilderSortTask(render, (float)cameraPosition.x, (float)cameraPosition.y, (float)cameraPosition.z, frame, sortStates);
    }

    public void markGraphDirty() {
        this.needsUpdate = true;
    }

    public boolean needsUpdate() {
        return this.needsUpdate;
    }

    public ChunkBuilder getBuilder() {
        return this.builder;
    }

    public void destroy() {
        this.builder.shutdown(); // stop all the workers, and cancel any tasks

        for (var result : this.collectChunkBuildResults()) {
            result.delete(); // delete resources for any pending tasks (including those that were cancelled)
        }

        this.sectionsWithGlobalEntities.clear();
        this.resetRenderLists();

        try (CommandList commandList = RenderDevice.INSTANCE.createCommandList()) {
            this.regions.delete(commandList);
            this.chunkRenderer.delete(commandList);
        }
    }

    public int getTotalSections() {
        return this.sectionByPosition.size();
    }

    public int getVisibleChunkCount() {
        var sections = 0;
        var iterator = this.renderLists.iterator();

        while (iterator.hasNext()) {
            var renderList = iterator.next();
            sections += renderList.getSectionsWithGeometryCount();
        }

        return sections;
    }

    private void scheduleRebuildOffThread(int x, int y, int z, boolean important) {
        asyncSubmittedTasks.add(() -> this.scheduleRebuild(x, y, z, important));
    }

    public void scheduleRebuild(int x, int y, int z, boolean important) {
        if (Thread.currentThread() != renderThread) {
            this.scheduleRebuildOffThread(x, y, z, important);
            return;
        }

        this.sectionCache.invalidate(x, y, z);

        RenderSection section = this.sectionByPosition.get(ChunkSectionPos.asLong(x, y, z));

        if (section != null) {
            ChunkUpdateType pendingUpdate;

            if (allowImportantRebuilds() && (important || this.shouldPrioritizeRebuild(section))) {
                pendingUpdate = ChunkUpdateType.IMPORTANT_REBUILD;
            } else {
                pendingUpdate = ChunkUpdateType.REBUILD;
            }

            pendingUpdate = ChunkUpdateType.getPromotionUpdateType(section.getPendingUpdate(), pendingUpdate);
            if (pendingUpdate != null) {
                section.setPendingUpdate(pendingUpdate);

                this.needsUpdate = true;
            }
        }
    }

    private static final float NEARBY_REBUILD_DISTANCE = (16.0f * 16.0f);

    private boolean shouldPrioritizeRebuild(RenderSection section) {
        return this.lastCameraPosition != null && section.getSquaredDistance(this.lastCameraPosition) < NEARBY_REBUILD_DISTANCE;
    }

    private static boolean allowImportantRebuilds() {
        return !SodiumClientMod.options().performance.alwaysDeferChunkUpdates;
    }

    private float getEffectiveRenderDistance() {
        var renderDistance = this.getRenderDistance();

        // Eye-in-fluid fog (water/lava) is dense but NOT a wall: the scene above/behind the surface stays clearly
        // visible through it (especially looking out of shallow water), and 1.12.2 fog has no alpha channel to
        // prove opacity — FogHelper's 1.0 is synthetic. Culling by the underwater EXP-fog cutoff shrank the world
        // to ~2 chunks (The Betweenlands, 2026-07-29). Fog occlusion stays for ordinary atmospheric fog only.
        var view = net.minecraft.client.Minecraft.getMinecraft().getRenderViewEntity();
        if (view != null && (view.isInsideOfMaterial(net.minecraft.block.material.Material.WATER)
                || view.isInsideOfMaterial(net.minecraft.block.material.Material.LAVA))) {
            return renderDistance;
        }

        // The cull must key on the SAME state the fog rendering keys on. FogHelper.getFogCutoff() reads only
        // fogState.mode/end/density; it never looks at fogState.fog.currentState, so GL fog that has been switched
        // OFF still yields its last cutoff distance. The Yumelium Plus "Fog" toggle disables fog exactly that way
        // (GlStateManager.disableFog() clears the enable bit and leaves mode/end alone), so with the toggle off the
        // terrain shader compiled ChunkFogMode.NONE — no fog drawn — while sections past the stale fog end were
        // still never visited: a hard terrain edge against the fog-coloured clear, with nothing to hide it. Worst in
        // the Nether and the Betweenlands, whose FogHandler writes a short fog end from inside setupFog.
        // getFogMode() is the oracle ChunkShaderOptions itself uses, so this can never cull tighter than what draws.
        if (FogHelper.getFogMode() == me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkFogMode.NONE) {
            return renderDistance;
        }

        var color = FogHelper.getFogColor();
        var distance = FogHelper.getFogCutoff();

        // The fog must be fully opaque in order to skip rendering of chunks behind it
        if (!(Math.abs(color[3] - 1.0f) < 1.0E-5F)) {
            return renderDistance;
        }

        return Math.min(renderDistance, distance + 0.5f);
    }

    private float getRenderDistance() {
        return this.renderDistance * 16.0f;
    }

    private void connectNeighborNodes(RenderSection render) {
        for (int direction = 0; direction < GraphDirection.COUNT; direction++) {
            RenderSection adj = this.getRenderSection(render.getChunkX() + GraphDirection.x(direction),
                    render.getChunkY() + GraphDirection.y(direction),
                    render.getChunkZ() + GraphDirection.z(direction));

            if (adj != null) {
                adj.setAdjacentNode(GraphDirection.opposite(direction), render);
                render.setAdjacentNode(direction, adj);
            }
        }
    }

    private void disconnectNeighborNodes(RenderSection render) {
        for (int direction = 0; direction < GraphDirection.COUNT; direction++) {
            RenderSection adj = render.getAdjacent(direction);

            if (adj != null) {
                adj.setAdjacentNode(GraphDirection.opposite(direction), null);
                render.setAdjacentNode(direction, null);
            }
        }
    }

    private RenderSection getRenderSection(int x, int y, int z) {
        return this.sectionByPosition.get(ChunkSectionPos.asLong(x, y, z));
    }

    private Collection<String> getSortingStrings() {
        List<String> list = new ArrayList<>();

        int[] sectionCounts = new int[TranslucentQuadAnalyzer.Level.VALUES.length];

        for (Iterator<ChunkRenderList> it = this.renderLists.iterator(); it.hasNext(); ) {
            var renderList = it.next();
            var region = renderList.getRegion();
            var listIter = renderList.sectionsWithGeometryIterator(false);
            if(listIter != null) {
                while(listIter.hasNext()) {
                    RenderSection section = region.getSection(listIter.nextByteAsInt());
                    // Do not count sections without translucent data
                    if(section == null || section.getTranslucencySortStates().isEmpty()) {
                        continue;
                    }

                    sectionCounts[section.getHighestSortingLevel().ordinal()]++;
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Sorting: ");
        TranslucentQuadAnalyzer.Level[] values = TranslucentQuadAnalyzer.Level.VALUES;
        for (int i = 0; i < values.length; i++) {
            TranslucentQuadAnalyzer.Level level = values[i];
            sb.append(level.name());
            sb.append('=');
            sb.append(sectionCounts[level.ordinal()]);
            if((i + 1) < values.length) {
                sb.append(", ");
            }
        }

        list.add(sb.toString());

        var cameraEntity = Minecraft.getMinecraft().getRenderViewEntity();
        if(cameraEntity != null) {
            var hitResult = cameraEntity.rayTrace(20.0D, 0.0F);
            if(hitResult != null && hitResult.typeOfHit == RayTraceResult.Type.BLOCK) {
                var pos = ((RayTraceResult)hitResult).getBlockPos();
                var self = this.getRenderSection(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
                if(self != null && !self.getTranslucencySortStates().isEmpty() && self.getTranslucencySortStates().keySet().stream().anyMatch(TerrainRenderPass::isSorted)) {
                    list.add("Targeted Section: " + self.getHighestSortingLevel().name());
                }
            }
        }

        return list;
    }

    public Collection<String> getDebugStrings() {
        List<String> list = new ArrayList<>();

        int count = 0, indexCount = 0;

        long deviceUsed = 0;
        long deviceAllocated = 0;

        long indexUsed = 0, indexAllocated = 0;

        for (var region : this.regions.getLoadedRegions()) {
            var resources = region.getResources();

            if (resources == null) {
                continue;
            }

            var buffer = resources.getGeometryArena();

            deviceUsed += buffer.getDeviceUsedMemoryL();
            deviceAllocated += buffer.getDeviceAllocatedMemoryL();

            var indexBuffer = resources.getIndexArena();

            if (indexBuffer != null) {
                indexUsed += indexBuffer.getDeviceUsedMemoryL();
                indexAllocated += indexBuffer.getDeviceAllocatedMemoryL();
                indexCount++;
            }

            count++;
        }

        list.add(String.format("Geometry Pool: %d/%d MiB (%d buffers)", MathUtil.toMib(deviceUsed), MathUtil.toMib(deviceAllocated), count));
        if (indexUsed > 0) {
            list.add(String.format("Index Pool: %d/%d MiB (%d buffers)", MathUtil.toMib(indexUsed), MathUtil.toMib(indexAllocated), indexCount));
        }
        list.add(String.format("Transfer Queue: %s", this.regions.getStagingBuffer().toString()));

        list.add(String.format("Chunk Builder: Permits=%02d | Busy=%02d | Total=%02d",
                this.builder.getScheduledJobCount(), this.builder.getBusyThreadCount(), this.builder.getTotalThreadCount())
        );

        list.add(String.format("Chunk Queues: U=%02d (P0=%03d | P1=%03d | P2=%03d)",
                this.buildResults.size(),
                this.rebuildLists.get(ChunkUpdateType.IMPORTANT_REBUILD).size(),
                this.rebuildLists.get(ChunkUpdateType.REBUILD).size(),
                this.rebuildLists.get(ChunkUpdateType.INITIAL_BUILD).size())
        );

        if(this.translucencySorting) {
            list.addAll(getSortingStrings());
        }

        return list;
    }

    public @NotNull SortedRenderLists getRenderLists() {
        return this.renderLists;
    }

    public boolean isSectionBuilt(int x, int y, int z) {
        var section = this.getRenderSection(x, y, z);
        return section != null && section.isBuilt();
    }

    public void onChunkAdded(int x, int z) {
        for (int y = 0; y < 16; y++) {
            this.onSectionAdded(x, y, z);
        }
    }

    public void onChunkRemoved(int x, int z) {
        for (int y = 0; y < 16; y++) {
            this.onSectionRemoved(x, y, z);
        }
    }

    public Collection<RenderSection> getSectionsWithGlobalEntities() {
        return ReferenceSets.unmodifiable(this.sectionsWithGlobalEntities);
    }

    public ChunkVertexType getVertexType() {
        return this.vertexType;
    }
}
