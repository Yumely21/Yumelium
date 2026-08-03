package me.jellysquid.mods.sodium.mixin.features.chunk_rendering;

import com.llamalad7.mixinextras.sugar.Local;
import com.yumelium.yumelium.shaders.pipeline.IrisPipeline;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gl.device.RenderDevice;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.DestroyBlockProgress;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.client.MinecraftForgeClient;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;

/**
 * Drives {@link SodiumWorldRenderer} from vanilla's {@link RenderGlobal}, replacing the vanilla terrain renderer.
 *
 * <p>NOTE(yumelium): adapted from Vintagium's {@code MixinWorldRenderer} hook points (1.12.2 RenderGlobal), but wired to
 * the Embeddium 0.5.x renderer API ({@code setupTerrain}/{@code drawChunkLayer}). The heavy passes ({@code setupTerrain},
 * {@code renderBlockLayer}) are {@link Overwrite}s so vanilla's chunk renderer never runs; lifecycle events are injected.
 * Block entities (TESRs) render through {@link #sodium$renderTileEntities} and regular entities (mobs) through
 * {@link #sodium$renderEntities}, both using Sodium's render lists / section graph in place of the now-empty
 * {@code renderInfos} that vanilla's loops rely on.
 */
@Mixin(RenderGlobal.class)
public abstract class MixinRenderGlobal {
    @Shadow
    @Final
    private Minecraft mc;

    @Shadow
    @Final
    private Map<Integer, DestroyBlockProgress> damagedBlocks;

    @Shadow
    @Final
    private RenderManager renderManager;

    @Shadow
    private WorldClient world;

    @Shadow
    private int countEntitiesRendered;

    @Shadow
    protected abstract boolean isOutlineActive(Entity entityIn, Entity viewer, ICamera camera);

    @Unique
    private SodiumWorldRenderer sodium$renderer;

    /** TEMP DIAG rate limiter — see the boss-gate probe in {@link #sodium$renderEntities}. */
    @Unique
    private static int yumelium$diagBossCounter;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void sodium$init(Minecraft minecraft, CallbackInfo ci) {
        this.sodium$renderer = SodiumWorldRenderer.create();
    }

    @Inject(method = "setWorldAndLoadRenderers", at = @At("RETURN"))
    private void sodium$onWorldChanged(WorldClient world, CallbackInfo ci) {
        RenderDevice.enterManagedCode();

        try {
            this.sodium$renderer.setWorld(world);
        } finally {
            RenderDevice.exitManagedCode();
        }
    }

    @Inject(method = "loadRenderers", at = @At("RETURN"))
    private void sodium$onReload(CallbackInfo ci) {
        RenderDevice.enterManagedCode();

        try {
            this.sodium$renderer.reload();
        } finally {
            RenderDevice.exitManagedCode();
        }
    }

    /**
     * Perf: stop vanilla from sizing its {@link net.minecraft.client.renderer.ViewFrustum} to the render distance —
     * Sodium's {@code RenderSectionManager} owns terrain, so vanilla's RenderChunk grid + its GL buffers are pure waste.
     * Redirecting the ViewFrustum-sizing read of {@code renderDistanceChunks} (ordinal 1) to 0 collapses that grid to a
     * negligible stub, freeing render-distance²×16 RenderChunks worth of heap (→ more chunk-builder threads, less GC).
     */
    @Redirect(method = "loadRenderers", at = @At(value = "FIELD", target = "Lnet/minecraft/client/settings/GameSettings;renderDistanceChunks:I", ordinal = 1))
    private int sodium$nullifyBuiltChunkStorage(GameSettings settings) {
        return 0;
    }

    @Inject(method = "setDisplayListEntitiesDirty", at = @At("RETURN"))
    private void sodium$onTerrainUpdateScheduled(CallbackInfo ci) {
        this.sodium$renderer.scheduleTerrainUpdate();
    }

    /**
     * Reimplements vanilla's regular-entity render loop. Vanilla iterates {@code renderInfos} (the visible render
     * chunks) to decide which entities are visible, but Sodium leaves that list empty, so vanilla would render no
     * mobs. We instead iterate the loaded entity list and cull each entity against Sodium's section graph via
     * {@link SodiumWorldRenderer#isEntityVisible}. Injected right before the first {@code renderInfos} access; vanilla's
     * subsequent loop then no-ops on the empty list. Adapted from Vintagium's {@code MixinRenderGlobal#renderEntities}.
     */
    @Inject(
            method = "renderEntities",
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/RenderGlobal;renderInfos:Ljava/util/List;", ordinal = 0)
    )
    private void sodium$renderEntities(Entity renderViewEntity, ICamera camera, float partialTicks, CallbackInfo ci,
                                       @Local(ordinal = 0) List<Entity> loadedEntityList,
                                       @Local(ordinal = 1) List<Entity> outlineEntityList,
                                       @Local(ordinal = 2) List<Entity> multipassEntityList,
                                       @Local(ordinal = 0) double renderViewX,
                                       @Local(ordinal = 1) double renderViewY,
                                       @Local(ordinal = 2) double renderViewZ) {
        int pass = MinecraftForgeClient.getRenderPass();
        EntityPlayerSP player = this.mc.player;
        BlockPos.MutableBlockPos entityBlockPos = new BlockPos.MutableBlockPos();

        // Apply entity distance scaling
        Entity.setRenderDistanceWeight(MathHelper.clamp((double) this.mc.gameSettings.renderDistanceChunks / 8.0D, 1.0D, 2.5D)
                * SodiumClientMod.options().quality.entityDistanceScaling);

        // Route the whole entity loop through the shader pack's gbuffers_entities (no-op when shaders are off). One bind
        // for the pass matches vanilla, which sets up standard item lighting once before the loop; each entity's texture
        // (unit 0) and per-entity lightmap (unit 1) are picked up per-draw. try/finally so the program can't leak.
        IrisPipeline.instance().beginEntities();
        // Own GPU phase so the CAMERA-pass entity cost is separable from the SHADOW-pass one ("shadow_entities").
        // A switch, not a nested begin — GL_TIME_ELAPSED cannot nest (see IrisPipeline.profileSwitch).
        IrisPipeline.instance().profileSwitch("camera_entities");
        // CPU clock too: the GPU phase measures fragment work, which turned out to be the pack's own shading. The
        // loop's own CPU cost (walking every loaded entity, shouldRender's per-entity AABB allocation, the id
        // resolve) is invisible to a GL timer and is what the frame is actually waiting on.
        final long yumelium$cpuStart = IrisPipeline.instance().entityCpuMark();
        // Loop-invariant work hoisted out of a body that runs once per LOADED entity per render pass (2300 entities
        // x 2 passes on the benchmark). Vanilla recomputes both inside the loop; neither depends on `entity`.
        final SodiumWorldRenderer yumelium$renderer = SodiumWorldRenderer.getInstance();
        // (TEMP DIAG counter — see the boss-gate probe in the loop.)
        // declared below as a mixin field
        final boolean yumelium$isSleeping = renderViewEntity instanceof EntityLivingBase
                && ((EntityLivingBase) renderViewEntity).isPlayerSleeping();
        final boolean yumelium$thirdPerson = this.mc.gameSettings.thirdPersonView != 0;
        try {
        for (Entity entity : loadedEntityList) {
            // TEMP DIAG (2026-08-03, debug-gated + rate-limited): which camera-loop gate rejects the Sludge Menace
            // with shaders on? The hull probe proved the SHADOW pass reaches its renderer every frame while the
            // camera pass never does.
            final boolean yumelium$diagBoss = me.jellysquid.mods.sodium.client.SodiumClientMod.debugLogs()
                    && entity.getClass().getName().contains("SludgeMenace")
                    && ((yumelium$diagBossCounter++ & 0x1F) == 0);

            // Skip entities that shouldn't render in this pass
            if (!entity.shouldRenderInPass(pass)) {
                if (yumelium$diagBoss) {
                    me.jellysquid.mods.sodium.client.SodiumClientMod.logger().info(
                            "[bossGate DIAG] REJECTED by shouldRenderInPass(pass=" + pass + ")");
                }
                continue;
            }

            // Do regular vanilla checks for visibility
            if (!this.renderManager.shouldRender(entity, camera, renderViewX, renderViewY, renderViewZ) && !entity.isRidingOrBeingRiddenBy(player)) {
                if (yumelium$diagBoss) {
                    me.jellysquid.mods.sodium.client.SodiumClientMod.logger().info(
                            "[bossGate DIAG] REJECTED by renderManager.shouldRender (box=" + entity.getRenderBoundingBox() + ")");
                }
                continue;
            }

            // Check if any corners of the bounding box are in a visible subchunk
            if (!yumelium$renderer.isEntityVisible(entity)) {
                if (yumelium$diagBoss) {
                    me.jellysquid.mods.sodium.client.SodiumClientMod.logger().info(
                            "[bossGate DIAG] REJECTED by isEntityVisible (box=" + entity.getRenderBoundingBox() + ")");
                }
                continue;
            }
            if (yumelium$diagBoss) {
                me.jellysquid.mods.sodium.client.SodiumClientMod.logger().info("[bossGate DIAG] PASSED all gates -> renderEntityStatic");
            }

            if ((entity != renderViewEntity || yumelium$thirdPerson || yumelium$isSleeping)
                    && (entity.posY < 0.0D || entity.posY >= 256.0D || this.world.isBlockLoaded(entityBlockPos.setPos(entity)))) {
                ++this.countEntitiesRendered;
                this.renderManager.renderEntityStatic(entity, partialTicks, false);

                if (this.isOutlineActive(entity, renderViewEntity, camera)) {
                    outlineEntityList.add(entity);
                }

                if (this.renderManager.isRenderMultipass(entity)) {
                    multipassEntityList.add(entity);
                }
            }
        }
        } finally {
            IrisPipeline.instance().addCameraEntityCpu(yumelium$cpuStart);
            IrisPipeline.instance().profileSwitch("gb_other"); // back to the enclosing gbuffers phase
            // endEntities() deliberately DOES NOT happen here (2026-08-03): vanilla's renderEntities continues after
            // this injection with its MULTIPASS block — Render.isMultipass() renderers get a second render call
            // there, and the Betweenlands' multipart bosses (Sludge Menace) draw their ENTIRE visible body in it.
            // Closing the entity pass here left that block running with NO program bound (probe-measured prog=0):
            // the boss rendered fixed-function into the pipeline and vanished from the shaded image. The pass now
            // ends at the head of sodium$renderTileEntities, which vanilla reaches right after the multipass block.
        }
    }

    /**
     * Renders block entities (TESRs: chests, signs, banners, ...) through Sodium's render lists, replacing vanilla's
     * block-entity loop which iterates the now-empty {@code renderInfos}. Injected right after the second
     * {@code RenderHelper.enableStandardItemLighting()} (so item lighting + lightmap are already set up), then cancels
     * the rest of {@code renderEntities} and restores the state vanilla would have (disableLightmap + close the profiler
     * section). Regular entities still render via vanilla's earlier loop.
     */
    @Inject(
            method = "renderEntities",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/RenderHelper;enableStandardItemLighting()V", shift = At.Shift.AFTER, ordinal = 1),
            cancellable = true
    )
    private void sodium$renderTileEntities(Entity renderViewEntity, ICamera camera, float partialTicks, CallbackInfo ci) {
        // Close the ENTITY pass here, not at the end of sodium$renderEntities: vanilla's MULTIPASS block (the second
        // render of Render.isMultipass() renderers — the Betweenlands' multipart bosses draw their whole body there)
        // runs between the two injection points and must stay inside the gbuffers_entities bracket, or it renders
        // with no program bound and the boss disappears from the shaded image (2026-08-03). No-ops when already ended.
        IrisPipeline.instance().endEntities();
        // Route TESRs through the pack's gbuffers_block (no-op when shaders are off). Standard item lighting is already
        // set up (this inject is right after the 2nd enableStandardItemLighting); try/finally so the program can't leak.
        IrisPipeline.instance().beginBlockEntities();
        try {
            this.sodium$renderer.renderTileEntities(partialTicks, this.damagedBlocks);
        } finally {
            IrisPipeline.instance().endBlockEntities();
        }

        this.mc.entityRenderer.disableLightmap();
        this.mc.profiler.endSection();

        ci.cancel();
    }

    /**
     * @reason Redirect the terrain setup phase to Sodium's renderer
     * @author Yumelium
     */
    @Overwrite
    public void setupTerrain(Entity entity, double partialTicks, ICamera camera, int frame, boolean spectator) {
        RenderDevice.enterManagedCode();

        try {
            this.sodium$renderer.setupTerrain(entity, partialTicks, frame, spectator, false);
        } finally {
            RenderDevice.exitManagedCode();
        }
    }

    /**
     * @reason Redirect the chunk layer render passes to Sodium's renderer
     * @author Yumelium
     */
    @Overwrite
    public int renderBlockLayer(BlockRenderLayer blockLayerIn, double partialTicks, int pass, Entity entityIn) {
        RenderDevice.enterManagedCode();

        RenderHelper.disableStandardItemLighting();

        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.bindTexture(this.mc.getTextureMapBlocks().getGlTextureId());
        GlStateManager.enableTexture2D();

        this.mc.entityRenderer.enableLightmap();

        double x = entityIn.lastTickPosX + (entityIn.posX - entityIn.lastTickPosX) * partialTicks;
        double y = entityIn.lastTickPosY + (entityIn.posY - entityIn.lastTickPosY) * partialTicks;
        double z = entityIn.lastTickPosZ + (entityIn.posZ - entityIn.lastTickPosZ) * partialTicks;

        try {
            this.sodium$renderer.drawChunkLayer(blockLayerIn, x, y, z);
        } finally {
            RenderDevice.exitManagedCode();
        }

        this.mc.entityRenderer.disableLightmap();

        return 1;
    }

    /**
     * @reason Redirect to Sodium's renderer
     * @author Yumelium
     */
    @Overwrite
    protected int getRenderedChunks() {
        return this.sodium$renderer.getVisibleChunkCount();
    }

    /**
     * @reason Redirect the check to Sodium's renderer
     * @author Yumelium
     */
    @Overwrite
    public boolean hasNoChunkUpdates() {
        return this.sodium$renderer.isTerrainRenderComplete();
    }

    /**
     * @reason Redirect chunk updates to Sodium's renderer
     * @author Yumelium
     */
    @Overwrite
    private void markBlocksForUpdate(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, boolean important) {
        this.sodium$renderer.scheduleRebuildForBlockArea(minX, minY, minZ, maxX, maxY, maxZ, important);
    }

    /**
     * @reason Replace the debug string
     * @author Yumelium
     */
    @Overwrite
    public String getDebugInfoRenders() {
        return this.sodium$renderer.getChunksDebugString();
    }
}
