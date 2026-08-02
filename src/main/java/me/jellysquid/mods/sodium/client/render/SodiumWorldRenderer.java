package me.jellysquid.mods.sodium.client.render;

import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gl.compat.FogHelper;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.gl.device.RenderDevice;
import me.jellysquid.mods.sodium.client.model.quad.blender.BlendedColorProvider;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import me.jellysquid.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import me.jellysquid.mods.sodium.client.render.chunk.map.ChunkStatus;
import me.jellysquid.mods.sodium.client.render.chunk.map.ChunkTracker;
import me.jellysquid.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegion;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.render.viewport.Viewport;
import me.jellysquid.mods.sodium.client.render.viewport.frustum.Frustum;
import me.jellysquid.mods.sodium.client.render.viewport.frustum.SimpleFrustum;
import me.jellysquid.mods.sodium.client.util.NativeBuffer;
import me.jellysquid.mods.sodium.client.util.iterator.ByteIterator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.DestroyBlockProgress;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.entity.Entity;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.client.MinecraftForgeClient;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Provides an extension to vanilla's {@link net.minecraft.client.renderer.RenderGlobal}.
 *
 * <p>NOTE(yumelium): ported from Embeddium 0.5.x's SodiumWorldRenderer (which owns the 0.5.x
 * {@link RenderSectionManager}) but rewritten for the 1.12.2 render loop using Vintagium's proven idioms —
 * blaze3d {@code Camera}/{@code PoseStack}/{@code RenderType}/{@code MultiBufferSource} are absent, so we drive the
 * manager from the 1.12.2 {@code RenderGlobal} hooks: the frustum {@link Viewport} is reconstructed from the fixed
 * function GL projection/model-view matrices (JOML {@link FrustumIntersection}), block entities render through the
 * legacy {@link TileEntityRendererDispatcher}, and layers map via {@link DefaultTerrainRenderPasses#RENDER_PASS_MAPPINGS}.
 */
public class SodiumWorldRenderer {
    private static SodiumWorldRenderer instance;

    private final Minecraft client;

    private WorldClient world;
    private int renderDistance;

    private double lastCameraX, lastCameraY, lastCameraZ;
    private double lastCameraPitch, lastCameraYaw;
    private float lastFogDistance;
    private float lastProjFov; // projection FOV term (m11); changes when FOV changes (e.g. zoom) so the graph re-culls

    private boolean useEntityCulling;

    private Viewport currentViewport;

    private RenderSectionManager renderSectionManager;

    // Scratch buffer for reading the fixed-function GL matrices on the render thread.
    private static final FloatBuffer MATRIX_BUFFER = BufferUtils.createFloatBuffer(16);

    /**
     * Instantiates Sodium's world renderer. This should be called at the time of the world renderer initialization.
     */
    public static SodiumWorldRenderer create() {
        if (instance == null) {
            instance = new SodiumWorldRenderer(Minecraft.getMinecraft());
        }

        return instance;
    }

    /**
     * @throws IllegalStateException If the renderer has not yet been created
     * @return The current instance of this type
     */
    public static SodiumWorldRenderer getInstance() {
        if (instance == null) {
            throw new IllegalStateException("Renderer not initialized");
        }

        return instance;
    }

    /**
     * @return The current instance of the Sodium terrain renderer, or null if the renderer is not active
     */
    public static SodiumWorldRenderer getInstanceNullable() {
        return instance;
    }

    private SodiumWorldRenderer(Minecraft client) {
        this.client = client;
    }

    public void setWorld(WorldClient world) {
        // Check that the world is actually changing
        if (this.world == world) {
            return;
        }

        // If we have a world is already loaded, unload the renderer
        if (this.world != null) {
            this.unloadWorld();
        }

        // If we're loading a new world, load the renderer
        if (world != null) {
            this.loadWorld(world);
        }
    }

    private void loadWorld(WorldClient world) {
        this.world = world;

        try (CommandList commandList = RenderDevice.INSTANCE.createCommandList()) {
            this.initRenderer(commandList);
        }
    }

    private void unloadWorld() {
        if (this.renderSectionManager != null) {
            this.renderSectionManager.destroy();
            this.renderSectionManager = null;
        }

        this.world = null;
    }

    /**
     * @return The number of chunk renders which are visible in the current camera's frustum
     */
    public int getVisibleChunkCount() {
        return this.renderSectionManager != null ? this.renderSectionManager.getVisibleChunkCount() : 0;
    }

    /**
     * Notifies the chunk renderer that the graph scene has changed and should be re-computed.
     */
    public void scheduleTerrainUpdate() {
        // BUG: seems to be called before init
        if (this.renderSectionManager != null) {
            this.renderSectionManager.markGraphDirty();
        }
    }

    /**
     * @return True if no chunks are pending rebuilds
     */
    public boolean isTerrainRenderComplete() {
        return this.renderSectionManager == null || this.renderSectionManager.getBuilder().isBuildQueueEmpty();
    }

    /**
     * Called prior to any chunk rendering in order to update necessary state.
     */
    public void setupTerrain(Entity viewEntity,
                             double partialTicks,
                             int frame,
                             boolean spectator,
                             boolean updateChunksImmediately) {
        if (this.renderSectionManager == null) {
            return;
        }

        NativeBuffer.reclaim(false);

        this.processChunkEvents();

        this.useEntityCulling = SodiumClientMod.options().advanced.useEntityCulling;

        if (this.client.gameSettings.renderDistanceChunks != this.renderDistance) {
            this.reload();
        }

        Profiler profiler = this.client.profiler;
        profiler.startSection("camera_setup");

        if (viewEntity == null) {
            throw new IllegalStateException("Client instance has no active render entity");
        }

        double x = viewEntity.lastTickPosX + (viewEntity.posX - viewEntity.lastTickPosX) * partialTicks;
        double y = viewEntity.lastTickPosY + (viewEntity.posY - viewEntity.lastTickPosY) * partialTicks + (double) viewEntity.getEyeHeight();
        double z = viewEntity.lastTickPosZ + (viewEntity.posZ - viewEntity.lastTickPosZ) * partialTicks;

        float pitch = viewEntity.rotationPitch;
        float yaw = viewEntity.rotationYaw;
        float fogDistance = FogHelper.getFogCutoff();
        // The FOV (e.g. from zoom) affects the culling frustum but not the camera position/rotation, so it must be part
        // of the dirty test — otherwise the graph keeps a stale (narrow-FOV) visible set until the camera moves.
        float projFov = readGlMatrix(GL11.GL_PROJECTION_MATRIX).m11();

        boolean dirty = x != this.lastCameraX || y != this.lastCameraY || z != this.lastCameraZ ||
                pitch != this.lastCameraPitch || yaw != this.lastCameraYaw || fogDistance != this.lastFogDistance ||
                projFov != this.lastProjFov;

        if (dirty) {
            this.renderSectionManager.markGraphDirty();
        }

        Viewport viewport = createViewport(x, y, z);
        this.currentViewport = viewport;

        this.lastCameraX = x;
        this.lastCameraY = y;
        this.lastCameraZ = z;
        this.lastCameraPitch = pitch;
        this.lastCameraYaw = yaw;
        this.lastFogDistance = fogDistance;
        this.lastProjFov = projFov;

        this.renderSectionManager.runAsyncTasks();

        profiler.endStartSection("chunk_update");

        this.renderSectionManager.updateChunks(updateChunksImmediately);

        profiler.endStartSection("chunk_upload");

        this.renderSectionManager.uploadChunks();

        if (this.renderSectionManager.needsUpdate()) {
            profiler.endStartSection("chunk_render_lists");

            this.renderSectionManager.update(viewEntity, viewport, frame, spectator);
        }

        if (updateChunksImmediately) {
            profiler.endStartSection("chunk_upload_immediately");

            this.renderSectionManager.uploadChunks();
        }

        profiler.endStartSection("chunk_render_tick");

        this.renderSectionManager.tickVisibleRenders();

        profiler.endSection();

        Entity.setRenderDistanceWeight(MathHelper.clamp((double) this.client.gameSettings.renderDistanceChunks / 8.0D, 1.0D, 2.5D)
                * SodiumClientMod.options().quality.entityDistanceScaling);
    }

    private void processChunkEvents() {
        ChunkTracker tracker = ChunkTrackerHolder.get(this.world);
        tracker.forEachEvent(this.renderSectionManager::onChunkAdded, this.renderSectionManager::onChunkRemoved);
    }

    /**
     * Performs a render pass for the given {@link BlockRenderLayer} and draws all visible chunks for it.
     */
    public void drawChunkLayer(BlockRenderLayer renderLayer, double x, double y, double z) {
        if (this.renderSectionManager == null) {
            return;
        }

        List<TerrainRenderPass> passes = DefaultTerrainRenderPasses.RENDER_PASS_MAPPINGS.get(renderLayer);

        if (passes != null) {
            ChunkRenderMatrices matrices = createRenderMatrices();

            // Iris/Oculus (M6): capture the world projection + model-view here (the GL matrices are the world's during
            // terrain draw, not the hand's) so composite passes get the pack's matrix/camera/celestial uniforms.
            if (com.yumelium.yumelium.shaders.pipeline.IrisPipeline.instance().isEnabled()) {
                com.yumelium.yumelium.shaders.pipeline.IrisPipeline.instance().captureMatrices(matrices.projection(), matrices.modelView());

                // Iris shadows: render opaque terrain from the light's POV into the shadow map, once per frame, before the
                // solid layer draws (so the shadow map is ready when the composite samples it). Rebinds the world FBO after.
                if (renderLayer == BlockRenderLayer.SOLID) {
                    com.yumelium.yumelium.shaders.pipeline.IrisPipeline.instance().renderShadowPass(this, x, y, z);
                }
            }

            // Nvidium M3b: the mesh-shader rasterizer draws ALL terrain — solid+cutout on the solid layer, translucent
            // on the translucent layer. When it handles the layer, vanilla is skipped entirely; otherwise fall back.
            boolean handledByNvidium = false;
            if (com.yumelium.yumelium.nvidium.NvidiumBackend.RENDER_TERRAIN) {
                var rasterizer = com.yumelium.yumelium.nvidium.NvidiumRasterizer.instance();
                // Feed Nvidium Sodium's already frustum+occlusion-culled visible set so it draws only visible sections.
                var lists = this.renderSectionManager.getRenderLists();
                if (renderLayer == BlockRenderLayer.SOLID) {
                    handledByNvidium = rasterizer.drawSolidAndCutout(lists, matrices.projection(), matrices.modelView(), x, y, z);
                } else if (renderLayer == BlockRenderLayer.TRANSLUCENT) {
                    handledByNvidium = rasterizer.drawTranslucent(lists, matrices.projection(), matrices.modelView(), x, y, z);
                }
            }

            if (!handledByNvidium) {
                //noinspection ForLoopReplaceableByForEach
                for (int i = 0; i < passes.size(); i++) {
                    this.renderSectionManager.renderLayer(matrices, passes.get(i), x, y, z);
                }
            }
        }

        GlStateManager.resetColor();
    }

    /**
     * Iris shadows: draws the opaque terrain passes (solid + cutout) into the currently-bound shadow depth map using the
     * supplied light-POV matrices. Called by {@link com.yumelium.yumelium.shaders.pipeline.IrisPipeline#renderShadowPass},
     * which owns the FBO/state setup.
     *
     * <p>Draws the SHADOW section list ({@link RenderSectionManager#updateShadowRenderList}) — every loaded section in the
     * shadow ortho box — not the camera-visible one. It used to reuse the camera's list, which meant a caster stopped
     * casting as soon as it left the view frustum; since shadows are cast by geometry between a surface and the sun,
     * typically behind the camera, turning around made the shadows disappear wholesale.</p>
     */
    /** @return how many sections the last shadow-list build selected (diagnostic; 0 if the renderer is not up). */
    public int getShadowSectionCount() {
        return this.renderSectionManager == null ? 0 : this.renderSectionManager.getShadowSectionCount();
    }

    /** @return frames the current shadow list has been served from cache (0 = rebuilt this frame; diagnostic). */
    public int getShadowListAge() {
        return this.renderSectionManager == null ? 0 : this.renderSectionManager.getShadowListAge();
    }

    /** @return how many sections the last shadow-list build dropped as buried under the terrain (diagnostic). */
    public int getShadowCulledUnderground() {
        return this.renderSectionManager == null ? 0 : this.renderSectionManager.getShadowCulledUnderground();
    }

    // Per-frame shadow caster statistics for the F3 debug screen (always counted — increments are negligible).
    private int iris$shadowEntitiesDrawn, iris$shadowEntitiesCulled, iris$shadowTesrDrawn, iris$shadowTesrCulled, iris$shadowTesrFailed;

    public int getShadowEntitiesDrawn() {
        return this.iris$shadowEntitiesDrawn;
    }

    public int getShadowEntitiesCulled() {
        return this.iris$shadowEntitiesCulled;
    }

    public int getShadowTesrDrawn() {
        return this.iris$shadowTesrDrawn;
    }

    public int getShadowTesrCulled() {
        return this.iris$shadowTesrCulled;
    }

    public int getShadowTesrFailed() {
        return this.iris$shadowTesrFailed;
    }

    /** @return camera-pass visible section count (F3 culling line). */
    public int getVisibleSectionCount() {
        return this.renderSectionManager == null ? 0 : this.renderSectionManager.getVisibleChunkCount();
    }

    /** @return total loaded render sections (F3 culling line). */
    public int getTotalSectionCount() {
        return this.renderSectionManager == null ? 0 : this.renderSectionManager.getTotalSections();
    }

    public void drawShadowCasters(ChunkRenderMatrices matrices, double x, double y, double z) {
        if (this.renderSectionManager == null) {
            return;
        }
        // F3 stats reflect THIS frame's shadow pass; zeroed here so a disabled caster path reads 0, not stale.
        this.iris$shadowEntitiesDrawn = 0;
        this.iris$shadowEntitiesCulled = 0;
        this.iris$shadowTesrDrawn = 0;
        this.iris$shadowTesrCulled = 0;
        this.iris$shadowTesrFailed = 0;
        List<TerrainRenderPass> passes = DefaultTerrainRenderPasses.RENDER_PASS_MAPPINGS.get(BlockRenderLayer.SOLID);
        if (passes == null) {
            return;
        }
        this.renderSectionManager.updateShadowRenderList();
        //noinspection ForLoopReplaceableByForEach
        for (int i = 0; i < passes.size(); i++) {
            this.renderSectionManager.renderShadowLayer(matrices, passes.get(i), x, y, z);
        }

        // shadowPlayer = true: the PLAYER (+ vehicle) casts a shadow even though the pack turns entity shadow casters
        // off. Drawn BEFORE the opaque-depth snapshot below so the player's depth lands in shadowtex1 too — an OPAQUE
        // caster; after the snapshot it would read as "blocked in tex0, clear in tex1" = the coloured/translucent
        // shadow path. The old all-entity fixed-function attempt could never have worked: the pack's radial shadow
        // distortion magnifies the map centre (where the player always is) ~7.5x, so undistorted depths land on the
        // wrong texels — IrisPipeline's dedicated program applies the exact distortion instead.
        // shadowEntities (pack option ENTITY_SHADOW >= 1): ALL in-range entities cast, the view entity included.
        // Otherwise shadowPlayer draws just the view entity + vehicle. Either way this must stay BEFORE the
        // opaque-depth snapshot below — entities are OPAQUE casters and must land in shadowtex1 too.
        boolean iris$allEntities = com.yumelium.yumelium.shaders.pipeline.IrisPipeline.instance().shadowEntitiesEnabled();
        boolean iris$entityCasters = iris$allEntities
                || com.yumelium.yumelium.shaders.pipeline.IrisPipeline.instance().shadowPlayerEnabled();
        // Block entities cast INDEPENDENTLY of the entity/player flags (reference-quirk parity: Iris 1.7.6 renders
        // them at every ENTITY_SHADOW value) — at ENTITY_SHADOW=-1 + PLAYER_SHADOW=-1 the pack emits both entity
        // flags false, and gating only on them silently killed the TESR loop nested inside (2026-07-27 audit).
        if (iris$entityCasters || com.yumelium.yumelium.shaders.pipeline.IrisPipeline.instance().shadowBlockEntitiesEnabled()) {
            // Split the entity casters out of the enclosing "shadow" phase, which spans terrain + entities + TESRs —
            // without this, entity caster cost is not separable from terrain cost and any estimate of an entity-side
            // optimisation is a guess. It must be a SWITCH, not a nested begin: GL_TIME_ELAPSED queries cannot nest
            // and GpuProfiler.begin() silently ignores a nested call, so a plain begin here would measure nothing at
            // all. Re-entering "shadow" afterwards is fine — the profiler sums repeated phases within a frame.
            com.yumelium.yumelium.shaders.pipeline.IrisPipeline.instance().profileSwitch("shadow_entities");
            final long iris$cpuStart = com.yumelium.yumelium.shaders.pipeline.IrisPipeline.instance().entityCpuMark();
            try {
                drawEntityShadowCasters(matrices, x, y, z, iris$allEntities, iris$entityCasters);
            } catch (Throwable t) {
                SodiumClientMod.logger().warn("[Iris shadow] entity shadow pass failed this frame", t);
            } finally {
                com.yumelium.yumelium.shaders.pipeline.IrisPipeline.instance().addShadowEntityCpu(iris$cpuStart);
                // Back to the enclosing phase for the snapshot + translucent casters below.
                com.yumelium.yumelium.shaders.pipeline.IrisPipeline.instance().profileSwitch("shadow");
                // The snapshot + translucent layer below need the shadow FBO healthy no matter what an entity
                // renderer (or a hook) did to its binding/attachments mid-loop.
                com.yumelium.yumelium.shaders.pipeline.IrisPipeline.instance().reassertShadowFboState();
            }
        }

        com.yumelium.yumelium.shaders.pipeline.IrisPipeline.instance().diagShadowCenterRow("post-entity-loop");
        // Freeze the opaque-only depth into shadowtex1 BEFORE any translucent caster writes. Must happen here, not inside
        // the translucent hook: Sodium skips a render pass with no geometry, so a view without water would never take the
        // snapshot and shadowtex1 would keep a stale depth (wrong shadows everywhere).
        com.yumelium.yumelium.shaders.pipeline.IrisPipeline.instance().snapshotOpaqueShadowDepth();

        // Also draw the TRANSLUCENT geometry from the light POV. With UNDERWATER_SHAFTS it now WRITES depth (into
        // shadowtex0 only), which is what makes water a translucent caster — the shadowtex0-vs-shadowtex1 difference the
        // pack turns into coloured shadows and underwater god rays. It also VOXELIZES it for colored lighting; without
        // this pass the nether portal (and other translucent light emitters) never gets voxelized.
        List<TerrainRenderPass> translucentPasses =
                DefaultTerrainRenderPasses.RENDER_PASS_MAPPINGS.get(BlockRenderLayer.TRANSLUCENT);
        if (translucentPasses != null) {
            //noinspection ForLoopReplaceableByForEach
            for (int i = 0; i < translucentPasses.size(); i++) {
                this.renderSectionManager.renderShadowLayer(matrices, translucentPasses.get(i), x, y, z);
            }
        }

    }

    private final FloatBuffer iris$shadowMatrixBuf = BufferUtils.createFloatBuffer(16);
    /** Rate limiter for the [Iris shadow][ENT] diagnostic (~1 line/second at 60fps). */
    private int iris$entShadowLogTick;

    /** (Re)loads the light-POV projection + model-view into the fixed-function stacks and leaves matrixMode at
     * MODELVIEW — called before EVERY entity shadow caster so one renderer's matrix/mode leak cannot corrupt the
     * next entity's transform (proven by the alternating-order experiment: only the loop's first entity ever cast). */
    private void iris$loadShadowMatrices(ChunkRenderMatrices matrices) {
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.loadIdentity();
        this.iris$shadowMatrixBuf.clear();
        matrices.projection().get(this.iris$shadowMatrixBuf);
        GlStateManager.multMatrix(this.iris$shadowMatrixBuf);
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.loadIdentity();
        this.iris$shadowMatrixBuf.clear();
        matrices.modelView().get(this.iris$shadowMatrixBuf);
        GlStateManager.multMatrix(this.iris$shadowMatrixBuf);
    }
    private final FloatBuffer iris$sentinelBuf = BufferUtils.createFloatBuffer(1);

    /** DIAGNOSTIC: MIN of a 16x16 depth block at uv (0.9, 0.9) from the currently-bound shadow FBO
     * (single-pixel glReadPixels returned a constant 0.0 while full-map reads of the same buffer showed content —
     * block reads follow the trustworthy path). */
    private float iris$readShadowSentinel() {
        java.nio.IntBuffer vp = BufferUtils.createIntBuffer(16);
        GL11.glGetInteger(GL11.GL_VIEWPORT, vp);
        int n = Math.max(17, vp.get(2));
        java.nio.FloatBuffer fb = BufferUtils.createFloatBuffer(256);
        GL11.glReadPixels(Math.min(n - 17, (int) (n * 0.9F)), Math.min(n - 17, (int) (n * 0.9F)), 16, 16,
                GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, fb);
        float mn = 2.0F;
        for (int i = 0; i < 256; i++) {
            mn = Math.min(mn, fb.get(i));
        }
        return mn;
    }

    /**
     * Renders entity shadow casters into the shadow depth map through {@code IrisPipeline}'s distortion-matching
     * program: with {@code allEntities} (the pack's {@code shadowEntities}, ENTITY_SHADOW option >= 1) every loaded
     * entity within the shadow ortho box — the view entity included; otherwise only the view entity + vehicle
     * (the pack's {@code shadowPlayer = true}). The bound program warps the depth exactly like the pack's own shadow
     * vertex — a plain fixed-function render could never work: with bias 0.8667 the distortion magnifies the map
     * centre ~7.5x, so undistorted depths land on entirely wrong texels. State is fully bracketed: the fixed-function
     * entity render has leaked state into later passes before (the GUI-dark lightmap lesson), so the lightmap unit is
     * forced OFF with raw GL and the terrain-pass defaults are re-established afterwards. {@code renderManager}'s
     * viewer info is cached with THIS frame's camera so each entity lands at its camera-relative world position,
     * which the shadow model-view then maps to light space.
     */
    private void drawEntityShadowCasters(ChunkRenderMatrices matrices, double x, double y, double z, boolean allEntities, boolean entityCasters) {
        Minecraft mc = this.client;
        Entity view = mc.getRenderViewEntity();
        if (view == null || this.world == null) {
            return;
        }
        float partialTicks = mc.getRenderPartialTicks();
        RenderManager renderManager = mc.getRenderManager();
        // DIAGNOSTIC: split this pass's CPU cost into setup / draws / TESR sweep — it measured ~7 ms while drawing a
        // single entity for 0.00 ms of GPU, and did not move with the entity count.
        final long iris$tSetup = com.yumelium.yumelium.shaders.pipeline.IrisPipeline.instance().entityCpuMark();

        // Light-POV fixed-function matrices; the distortion program warps on top of them.
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        this.iris$shadowMatrixBuf.clear();
        matrices.projection().get(this.iris$shadowMatrixBuf);
        GlStateManager.multMatrix(this.iris$shadowMatrixBuf);
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        this.iris$shadowMatrixBuf.clear();
        matrices.modelView().get(this.iris$shadowMatrixBuf);
        GlStateManager.multMatrix(this.iris$shadowMatrixBuf);

        // Camera-relative placement with THIS frame's camera: renderPos still holds LAST frame's value here (the main
        // entity render sets it later in the frame), which would lag the shadow by one frame of camera motion.
        // Vanilla model rendering RECORDS DISPLAY LISTS through CLIENT vertex arrays (ModelRenderer.compileDisplayList
        // → WorldVertexBufferUploader → glVertexPointer(client memory) + glDrawArrays). Sodium's chunk shadow draws
        // have just run and can leave a region VBO (and VAO) bound: with a VBO bound, the client-memory pointer is
        // reinterpreted as an OFFSET into that VBO and nvoglv64 access-violates inside the display-list recorder
        // (fixed offset 0xc5aa8a — the "load-time crash" that masqueraded as driver/shader-cache breakage for two
        // days). It only fired when THIS pass was the player model's FIRST-EVER render (the lists compile once,
        // then glCallList is safe), which is why it looked probabilistic. Unbind everything vertex-related; the
        // chunk draws that follow rebind whatever they need.
        org.lwjgl.opengl.GL30C.glBindVertexArray(0);
        org.lwjgl.opengl.GL15C.glBindBuffer(org.lwjgl.opengl.GL15C.GL_ARRAY_BUFFER, 0);
        org.lwjgl.opengl.GL15C.glBindBuffer(org.lwjgl.opengl.GL15C.GL_ELEMENT_ARRAY_BUFFER, 0);

        renderManager.cacheActiveRenderInfo(mc.world, mc.fontRenderer, view, mc.pointedEntity, mc.gameSettings, partialTicks);
        renderManager.setRenderPosition(x, y, z);
        renderManager.setRenderShadow(false); // no vanilla blob shadow in the depth map
        net.minecraft.client.renderer.RenderHelper.enableStandardItemLighting();
        GlStateManager.enableAlpha();

        com.yumelium.yumelium.shaders.pipeline.IrisPipeline.instance().usePlayerShadowProgram();
        com.yumelium.yumelium.shaders.pipeline.IrisPipeline.instance().addShadowEntSetup(iris$tSetup);
        final long iris$tDraw = com.yumelium.yumelium.shaders.pipeline.IrisPipeline.instance().entityCpuMark();
        boolean logNow = com.yumelium.yumelium.shaders.pipeline.IrisPipeline.DIAG_ENTITY_SHADOW
                && ++this.iris$entShadowLogTick % 60 == 0;
        try {
            if (!entityCasters) {
                // BE-only mode (ENTITY_SHADOW=-1 + PLAYER_SHADOW=-1): skip straight to the TESR loop below.
            } else if (!allEntities) {
                boolean iris$recullView = com.yumelium.yumelium.compat.EntityCullingCompat.beginShadowDraw(view);
                try {
                    renderManager.renderEntityStatic(view, partialTicks, false);
                } finally {
                    if (iris$recullView) {
                        com.yumelium.yumelium.compat.EntityCullingCompat.endShadowDraw(view);
                    }
                }
                Entity vehicle = view.getRidingEntity();
                if (vehicle != null) {
                    boolean iris$recullVehicle = com.yumelium.yumelium.compat.EntityCullingCompat.beginShadowDraw(vehicle);
                    try {
                        renderManager.renderEntityStatic(vehicle, partialTicks, false);
                    } finally {
                        if (iris$recullVehicle) {
                            com.yumelium.yumelium.compat.EntityCullingCompat.endShadowDraw(vehicle);
                        }
                    }
                }
                this.iris$shadowEntitiesDrawn = vehicle != null ? 2 : 1;
            } else {
                // Everything within reach of the shadow ortho box, view entity included (renderEntityStatic has no
                // first-person skip — that filter lives in RenderGlobal's camera pass). Lightning is excluded: the
                // pack itself discards its shadow (shadow.glsl, entityId 50004), and a flash-frame full-model shadow
                // is wrong anyway. Per-entity try: one misbehaving (modded) renderer must not abort the shadow pass.
                float r = com.yumelium.yumelium.shaders.pipeline.IrisPipeline.shadowDistanceBlocks() + 16.0F;
                double cullSq = (double) r * r;
                int drawn = 0, culled = 0;
                java.util.Map<String, Integer> classes = logNow ? new java.util.TreeMap<>() : null;
                // Culprit hunt for the END "depth<0.2 blanket": the blanket cannot come from the distortion program
                // (its z*0.2 squash confines depth to ~[0.4,0.6]), so SOME entity's renderer draws with broken
                // matrices/state. On diagnostic frames read a sentinel texel (uv 0.9,0.9 — inside the blanket, and a
                // region real terrain rarely reaches with depth < 0.35) after every entity: the draw that trips it is
                // the culprit. One-pixel glReadPixels per entity, diag frames only (the shadow FBO is bound here).
                int failed = 0;
                String failMsg = null;
                // State-diff instrument (mob-shadow hunt): baseline = the healthy setup right before the first
                // entity. After each entity render, diff the FULL fixed-function+pipeline state against it — the
                // leak that makes every subsequent entity's draw miss the map must show up here (state queries are
                // the trustworthy instrument on the shadow FBO; pixel reads are the ones that lie).
                java.util.LinkedHashMap<String, String> stateBase = null;
                if (logNow) {
                    stateBase = com.yumelium.yumelium.shaders.pipeline.IrisPipeline.captureGlState();
                    SodiumClientMod.logger().info("[Iris shadow][ENT-STATE] baseline " + stateBase);
                }
                for (Entity entity : this.world.loadedEntityList) {
                    if (entity == null || entity instanceof net.minecraft.entity.effect.EntityLightningBolt) {
                        continue;
                    }
                    double dx = entity.posX - x, dy = entity.posY - y, dz = entity.posZ - z;
                    if (dx * dx + dy * dy + dz * dz > cullSq) {
                        culled++;
                        continue;
                    }
                    try {
                        // Re-assert the distortion program PER ENTITY: any renderer/layer/hook that binds another
                        // program (or unbinds ours) mid-loop would leave every entity after it rendering fixed-function
                        // — undistorted, un-squashed, i.e. absent from the map the lookup samples.
                        com.yumelium.yumelium.shaders.pipeline.IrisPipeline.instance().usePlayerShadowProgram();
                        // Re-assert the shadow FBO's binding/attachments/drawBuffers/viewport PER ENTITY: the leash
                        // hook used to attach a WINDOW-sized colortex to the bound shadow FBO mid-loop, and GL clips
                        // rasterization to the attachment-size INTERSECTION — every later entity was confined to a
                        // window-sized corner of the 4096 map ("only the first entity lands"). That hook is now
                        // guarded, but any (modded) renderer touching FBO state gets the same immunization here.
                        com.yumelium.yumelium.shaders.pipeline.IrisPipeline.instance().reassertShadowFboState();
                        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
                        GlStateManager.depthMask(true);
                        // FULL matrix re-load per entity. The alternating-order experiment proved only the FIRST
                        // entity of the loop ever lands in the map (the player's shadow blinked when the order
                        // flipped): a renderer/layer leaves the matrix stacks (or matrix MODE) dirty, so every
                        // subsequent entity transforms against garbage. Re-establishing both stacks per entity is a
                        // few GL calls and makes each draw independent of its predecessor's leaks.
                        iris$loadShadowMatrices(matrices);
                        // pre-draw diff: what is STILL broken at draw time despite every re-assert above —
                        // whatever appears here for entity 2+ (but not entity 1) is the smoking gun.
                        if (stateBase != null) {
                            String d = com.yumelium.yumelium.shaders.pipeline.IrisPipeline.diffGlState(
                                    stateBase, com.yumelium.yumelium.shaders.pipeline.IrisPipeline.captureGlState());
                            SodiumClientMod.logger().info("[Iris shadow][ENT-STATE] pre  #" + drawn + " "
                                    + entity.getClass().getSimpleName() + (d.isEmpty() ? " clean" : " " + d));
                        }
                        // EntityCulling compat: lift a camera-occlusion cull for the SHADOW draw (the sun's view is
                        // not the camera's), restore right after — see EntityCullingCompat.
                        boolean iris$recull = com.yumelium.yumelium.compat.EntityCullingCompat.beginShadowDraw(entity);
                        try {
                            renderManager.renderEntityStatic(entity, partialTicks, false);
                        } finally {
                            if (iris$recull) {
                                com.yumelium.yumelium.compat.EntityCullingCompat.endShadowDraw(entity);
                            }
                        }
                        // post-draw diff: everything this entity's renderer leaked.
                        if (stateBase != null) {
                            String d = com.yumelium.yumelium.shaders.pipeline.IrisPipeline.diffGlState(
                                    stateBase, com.yumelium.yumelium.shaders.pipeline.IrisPipeline.captureGlState());
                            SodiumClientMod.logger().info("[Iris shadow][ENT-STATE] post #" + drawn + " "
                                    + entity.getClass().getSimpleName() + (d.isEmpty() ? " clean" : " " + d));
                        }
                        drawn++;
                        if (classes != null) {
                            classes.merge(entity.getClass().getSimpleName(), 1, Integer::sum);
                        }
                    } catch (Throwable t) {
                        // One misbehaving renderer must not abort the pass — but a SILENT swallow hid real failures
                        // during the END entity-shadow hunt (an entity that throws here simply has no shadow).
                        failed++;
                        if (failMsg == null) {
                            failMsg = entity.getClass().getSimpleName() + ": " + t;
                        }
                    }
                }
                if (logNow) {
                    SodiumClientMod.logger().info("[Iris shadow][ENT] drawn=" + drawn + " culledByDistance=" + culled
                            + " failed=" + failed + (failMsg != null ? " (first: " + failMsg + ")" : "")
                            + String.format(" cam=(%.0f, %.0f, %.0f)", x, y, z) + " classes=" + classes);
                }
                this.iris$shadowEntitiesDrawn = drawn;
                this.iris$shadowEntitiesCulled = culled;
            }
            // shadowBlockEntities (ENTITY_SHADOW=2 "すべて"): TESR block entities cast too — chests, beds, skulls,
            // banners, signs, shulker boxes, the enchant-table book, piston moving blocks, the spawner's mini mob.
            // Same distortion program + per-caster re-asserts as the entities above; drawn BEFORE the opaque-depth
            // snapshot so they land in shadowtex1 (opaque casters). Skipped: the beacon (its TESR is ONLY the
            // emissive beam — the light source must not cast a 256-block hard shadow column) and the end
            // portal/gateway (surface flush with the ground casts nothing visible, and the vanilla fallback is a
            // TEXGEN multi-pass that manhandles the texture matrix mid-shadow-pass). Forge fast-only TESRs draw
            // nothing through the plain render() path and simply don't cast.
            com.yumelium.yumelium.shaders.pipeline.IrisPipeline.instance().addShadowEntDraw(iris$tDraw);
            final long iris$tTesr = com.yumelium.yumelium.shaders.pipeline.IrisPipeline.instance().entityCpuMark();
            if (com.yumelium.yumelium.shaders.pipeline.IrisPipeline.instance().shadowBlockEntitiesEnabled()
                    && net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher.instance.world != null) {
                net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher dispatcher =
                        net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher.instance;
                float teR = com.yumelium.yumelium.shaders.pipeline.IrisPipeline.shadowDistanceBlocks() + 16.0F;
                double teCullSq = (double) teR * teR;
                int drawnTe = 0, failedTe = 0;
                String failTeMsg = null;
                for (net.minecraft.tileentity.TileEntity te : this.world.loadedTileEntityList) {
                    if (te == null || te.isInvalid()
                            || te instanceof net.minecraft.tileentity.TileEntityBeacon
                            || te instanceof net.minecraft.tileentity.TileEntityEndPortal) { // gateway extends portal
                        continue;
                    }
                    net.minecraft.util.math.BlockPos pos = te.getPos();
                    double dx = pos.getX() + 0.5 - x, dy = pos.getY() + 0.5 - y, dz = pos.getZ() + 0.5 - z;
                    if (dx * dx + dy * dy + dz * dz > teCullSq) {
                        this.iris$shadowTesrCulled++;
                        continue;
                    }
                    if (dispatcher.getRenderer(te) == null) {
                        continue;
                    }
                    try {
                        com.yumelium.yumelium.shaders.pipeline.IrisPipeline.instance().usePlayerShadowProgram();
                        com.yumelium.yumelium.shaders.pipeline.IrisPipeline.instance().reassertShadowFboState();
                        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
                        GlStateManager.depthMask(true);
                        iris$loadShadowMatrices(matrices);
                        // Explicit-offset overload: skips the dispatcher's stale-camera distance check and its
                        // world.getCombinedLight lookup; camera-relative offsets are exactly what the shadow
                        // model-view maps to light space. EntityCulling compat as in the entity loop above.
                        boolean iris$recullTe = com.yumelium.yumelium.compat.EntityCullingCompat.beginShadowDraw(te);
                        try {
                            dispatcher.render(te, pos.getX() - x, pos.getY() - y, pos.getZ() - z, partialTicks);
                        } finally {
                            if (iris$recullTe) {
                                com.yumelium.yumelium.compat.EntityCullingCompat.endShadowDraw(te);
                            }
                        }
                        drawnTe++;
                    } catch (Throwable t) {
                        failedTe++;
                        if (failTeMsg == null) {
                            failTeMsg = te.getClass().getSimpleName() + ": " + t;
                        }
                    }
                }
                if (logNow) {
                    SodiumClientMod.logger().info("[Iris shadow][TESR] drawn=" + drawnTe + " failed=" + failedTe
                            + (failTeMsg != null ? " (first: " + failTeMsg + ")" : ""));
                }
                this.iris$shadowTesrDrawn = drawnTe;
                this.iris$shadowTesrFailed = failedTe;
            }
            com.yumelium.yumelium.shaders.pipeline.IrisPipeline.instance().addShadowEntTesr(iris$tTesr);
        } finally {
            com.yumelium.yumelium.shaders.gl.GlslProgram.unuse();
            net.minecraft.client.renderer.RenderHelper.disableStandardItemLighting();
            renderManager.setRenderShadow(true);
            // Force the lightmap unit OFF with raw GL (GlStateManager's cache can desync — the GUI-dark lesson) and
            // restore the defaults the terrain passes that follow expect.
            GlStateManager.setActiveTexture(net.minecraft.client.renderer.OpenGlHelper.lightmapTexUnit);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GlStateManager.setActiveTexture(net.minecraft.client.renderer.OpenGlHelper.defaultTexUnit);
            GlStateManager.enableTexture2D();
            // Re-bind the BLOCK ATLAS on unit 0: vanilla binds it BEFORE the solid layer, but this player render just
            // bound the player skin / armor textures there — the camera terrain pass that follows samples unit 0, so
            // without this every solid block sampled the SKIN (flat vertex-tint-only world, textures "gone").
            GlStateManager.bindTexture(mc.getTextureMapBlocks().getGlTextureId());
            GlStateManager.depthMask(true);
            GlStateManager.disableBlend();
            GlStateManager.enableCull();
            GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
            GlStateManager.matrixMode(GL11.GL_PROJECTION);
            GlStateManager.popMatrix();
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
            GlStateManager.popMatrix();
            // The raw VAO/VBO unbinds above (the display-list crash guard) and everything the entity renderers bound
            // went BEHIND Sodium's GlStateTracker. Its cache would still claim the pre-loop VAO/buffers are bound, so
            // the next chunk draw (the translucent shadow layer / next frame's terrain) would SKIP the real
            // glBindVertexArray and rasterize the region mesh through whatever vertex state the entity render left —
            // the END "terrain squeezed into a window-sized corner of the shadow map" corruption. Make the tracker
            // forget so every following bind is re-issued for real.
            me.jellysquid.mods.sodium.client.gl.device.RenderDevice.INSTANCE.notifyExternalStateReset();
        }
    }

    public void reload() {
        if (this.world == null) {
            return;
        }

        try (CommandList commandList = RenderDevice.INSTANCE.createCommandList()) {
            this.initRenderer(commandList);
        }
    }

    private void initRenderer(CommandList commandList) {
        // Apply the Nvidium user settings on (re)load: recompute enabled state and drop resident geometry so it is
        // re-mirrored at the (possibly new) buffer size. Chunks rebuild below, so any change takes effect cleanly.
        com.yumelium.yumelium.nvidium.NvidiumBackend.applyConfig();
        com.yumelium.yumelium.nvidium.NvidiumBackend.instance().reset();

        if (this.renderSectionManager != null) {
            this.renderSectionManager.destroy();
            this.renderSectionManager = null;
        }

        this.renderDistance = this.client.gameSettings.renderDistanceChunks;

        this.renderSectionManager = new RenderSectionManager(this.world, this.renderDistance, commandList);

        ChunkTracker tracker = ChunkTrackerHolder.get(this.world);
        ChunkTracker.forEachChunk(tracker.getReadyChunks(), this.renderSectionManager::onChunkAdded);

        BlendedColorProvider.checkBlendingEnabled();
    }

    // --- Frustum / matrix reconstruction from the fixed-function GL state -------------------------------------------

    private static Matrix4f readGlMatrix(int glMatrixName) {
        // lwjglx compat shim exposes the LWJGL2-style glGetFloat(int, FloatBuffer) (writes without advancing position).
        MATRIX_BUFFER.clear();
        GL11.glGetFloat(glMatrixName, MATRIX_BUFFER);
        return new Matrix4f(MATRIX_BUFFER);
    }

    private static ChunkRenderMatrices createRenderMatrices() {
        Matrix4f projection = readGlMatrix(GL11.GL_PROJECTION_MATRIX);
        Matrix4f modelView = readGlMatrix(GL11.GL_MODELVIEW_MATRIX);
        return new ChunkRenderMatrices(projection, modelView);
    }

    private static Viewport createViewport(double x, double y, double z) {
        Matrix4f projection = readGlMatrix(GL11.GL_PROJECTION_MATRIX);
        Matrix4f modelView = readGlMatrix(GL11.GL_MODELVIEW_MATRIX);

        // Clip = projection * modelView; the model-view at terrain-setup time holds only the camera rotation
        // (the world is drawn camera-relative), so the resulting frustum matches the Viewport's camera-relative coords.
        Matrix4f clip = new Matrix4f(projection).mul(modelView);
        Frustum frustum = new SimpleFrustum(new FrustumIntersection(clip));

        return new Viewport(frustum, new Vector3d(x, y, z));
    }

    // --- Block entities (legacy 1.12.2 TESR immediate-mode path) ---------------------------------------------------

    public void forEachVisibleBlockEntity(Consumer<TileEntity> consumer) {
        SortedRenderLists renderLists = this.renderSectionManager.getRenderLists();

        for (java.util.Iterator<ChunkRenderList> it = renderLists.iterator(); it.hasNext(); ) {
            ChunkRenderList renderList = it.next();

            RenderRegion renderRegion = renderList.getRegion();
            ByteIterator sectionIterator = renderList.sectionsWithEntitiesIterator();

            if (sectionIterator == null) {
                continue;
            }

            while (sectionIterator.hasNext()) {
                RenderSection renderSection = renderRegion.getSection(sectionIterator.nextByteAsInt());

                if (renderSection == null) {
                    continue;
                }

                TileEntity[] blockEntities = renderSection.getCulledBlockEntities();

                if (blockEntities == null) {
                    continue;
                }

                for (TileEntity blockEntity : blockEntities) {
                    consumer.accept(blockEntity);
                }
            }
        }

        for (RenderSection renderSection : this.renderSectionManager.getSectionsWithGlobalEntities()) {
            TileEntity[] blockEntities = renderSection.getGlobalBlockEntities();

            if (blockEntities == null) {
                continue;
            }

            for (TileEntity blockEntity : blockEntities) {
                consumer.accept(blockEntity);
            }
        }
    }

    public void renderTileEntities(float partialTicks, Map<Integer, DestroyBlockProgress> damagedBlocks) {
        if (this.renderSectionManager == null) {
            return;
        }

        int pass = MinecraftForgeClient.getRenderPass();

        forEachVisibleBlockEntity(blockEntity -> renderTileEntity(blockEntity, pass, partialTicks));
    }

    private void renderTileEntity(TileEntity tileEntity, int pass, float partialTicks) {
        if (tileEntity == null || !tileEntity.shouldRenderInPass(pass)) {
            return;
        }

        // The end portal is exempt from the per-TE frustum test: vanilla gives it an effectively infinite render
        // bounding box (never culled), and its Forge fallback box is unreliable (BlockEndPortal has no collision box).
        if (this.currentViewport != null && !(tileEntity instanceof net.minecraft.tileentity.TileEntityEndPortal)
                && !this.currentViewport.isBoxVisible(tileEntity.getRenderBoundingBox())) {
            return;
        }

        try {
            TileEntityRendererDispatcher.instance.render(tileEntity, partialTicks, -1);
        } catch (RuntimeException e) {
            if (tileEntity.isInvalid()) {
                SodiumClientMod.logger().error("Suppressing crash from invalid tile entity", e);
            } else {
                throw e;
            }
        }
    }

    // --- Entity culling --------------------------------------------------------------------------------------------

    private static boolean isInfiniteExtentsBox(AxisAlignedBB box) {
        return Double.isInfinite(box.minX) || Double.isInfinite(box.minY) || Double.isInfinite(box.minZ)
                || Double.isInfinite(box.maxX) || Double.isInfinite(box.maxY) || Double.isInfinite(box.maxZ);
    }

    /**
     * Returns whether the entity intersects with any visible chunks in the graph.
     * @return True if the entity is visible, otherwise false
     */
    public boolean isEntityVisible(Entity entity) {
        if (!this.useEntityCulling || this.renderSectionManager == null) {
            return true;
        }

        // Ensure entities with outlines or nametags are always visible
        if (entity.isGlowing() || entity.getAlwaysRenderNameTagForRender()) {
            return true;
        }

        AxisAlignedBB box = entity.getRenderBoundingBox();

        // Entities outside the valid world height will never map to a rendered chunk
        if (box.maxY < 0.5D || box.minY > 255.5D) {
            return true;
        }

        if (isInfiniteExtentsBox(box)) {
            return true;
        }

        return this.isBoxVisible(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    public boolean isBoxVisible(double x1, double y1, double z1, double x2, double y2, double z2) {
        int minX = MathHelper.floor(x1 - 0.5D) >> 4;
        int minY = MathHelper.floor(y1 - 0.5D) >> 4;
        int minZ = MathHelper.floor(z1 - 0.5D) >> 4;

        int maxX = MathHelper.floor(x2 + 0.5D) >> 4;
        int maxY = MathHelper.floor(y2 + 0.5D) >> 4;
        int maxZ = MathHelper.floor(z2 + 0.5D) >> 4;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    if (this.renderSectionManager.isSectionVisible(x, y, z)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    public String getChunksDebugString() {
        if (this.renderSectionManager == null) {
            return "C: 0/0 D: 0";
        }
        return String.format("C: %d/%d D: %d", this.renderSectionManager.getVisibleChunkCount(),
                this.renderSectionManager.getTotalSections(), this.renderDistance);
    }

    /**
     * {@return the multi-line block of debug info shown under the "Yumelium Renderer" header on the F3 screen}
     */
    public java.util.List<String> getDebugStrings() {
        java.util.List<String> list = new java.util.ArrayList<>();
        list.add("");
        // The line text is recoloured to Yumelium's light blue-purple by MixinGuiIngameForge (F3 can't use custom RGB
        // via formatting codes); keep it bold and free of a colour code so that recolour takes effect.
        list.add("§lYumelium Renderer");

        if (this.renderSectionManager != null) {
            list.add("§f" + this.getChunksDebugString());
            list.addAll(this.renderSectionManager.getDebugStrings());
        } else {
            list.add("§7(no active world)");
        }

        return list;
    }

    /**
     * Schedules chunk rebuilds for all chunks in the specified block region.
     */
    public void scheduleRebuildForBlockArea(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, boolean important) {
        this.scheduleRebuildForChunks(minX >> 4, minY >> 4, minZ >> 4, maxX >> 4, maxY >> 4, maxZ >> 4, important);
    }

    /**
     * Schedules chunk rebuilds for all chunks in the specified chunk region.
     */
    public void scheduleRebuildForChunks(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, boolean important) {
        if (this.renderSectionManager == null) {
            return;
        }
        for (int chunkX = minX; chunkX <= maxX; chunkX++) {
            for (int chunkY = minY; chunkY <= maxY; chunkY++) {
                for (int chunkZ = minZ; chunkZ <= maxZ; chunkZ++) {
                    this.scheduleRebuildForChunk(chunkX, chunkY, chunkZ, important);
                }
            }
        }
    }

    /**
     * Schedules a chunk rebuild for the render belonging to the given chunk section position.
     */
    public void scheduleRebuildForChunk(int x, int y, int z, boolean important) {
        this.renderSectionManager.scheduleRebuild(x, y, z, important);
    }

    public boolean isSectionReady(int x, int y, int z) {
        return this.renderSectionManager != null && this.renderSectionManager.isSectionBuilt(x, y, z);
    }

    // Legacy compatibility (chunk-status events routed through the tracker)
    @Deprecated
    public void onChunkAdded(int x, int z) {
        ChunkTracker tracker = ChunkTrackerHolder.get(this.world);
        tracker.onChunkStatusAdded(x, z, ChunkStatus.FLAG_HAS_BLOCK_DATA);
    }

    @Deprecated
    public void onChunkLightAdded(int x, int z) {
        ChunkTracker tracker = ChunkTrackerHolder.get(this.world);
        tracker.onChunkStatusAdded(x, z, ChunkStatus.FLAG_HAS_LIGHT_DATA);
    }

    @Deprecated
    public void onChunkRemoved(int x, int z) {
        ChunkTracker tracker = ChunkTrackerHolder.get(this.world);
        tracker.onChunkStatusRemoved(x, z, ChunkStatus.FLAG_ALL);
    }
}
