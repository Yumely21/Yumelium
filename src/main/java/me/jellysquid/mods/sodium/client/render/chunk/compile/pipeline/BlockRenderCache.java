package me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline;

import me.jellysquid.mods.sodium.client.model.color.ColorProviderRegistry;
import me.jellysquid.mods.sodium.client.model.light.LightPipelineProvider;
import me.jellysquid.mods.sodium.client.model.light.cache.ArrayLightDataCache;
import me.jellysquid.mods.sodium.client.world.WorldSlice;
import me.jellysquid.mods.sodium.client.world.cloned.ChunkRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.BlockModelShapes;

/**
 * Holds important caches and working data structures for a single chunk meshing thread. All objects within
 * this class do not need to be thread-safe or lightweight to construct, as a separate instance is allocated per thread
 * and reused for the lifetime of that thread.
 *
 * <p>NOTE(yumelium): delombok'd; ClientLevel→WorldClient; light cache is the Vintagium-based
 * {@code model.light.cache.ArrayLightDataCache}; {@code BlockModelShaper}→1.12.2 {@code BlockModelShapes} (via
 * {@code getBlockRendererDispatcher}); Embeddium's {@code EmbeddiumRenderLayerCache} dropped (render layers are
 * computed directly in 1.12.2).
 */
public class BlockRenderCache {
    private final ArrayLightDataCache lightDataCache;

    private final BlockRenderer blockRenderer;
    private final FluidRenderer fluidRenderer;
    private final LightPipelineProvider lightPipelineProvider;

    private final BlockModelShapes blockModels;
    private final WorldSlice worldSlice;

    public BlockRenderCache(Minecraft client, WorldClient world) {
        this.worldSlice = new WorldSlice(world);
        this.lightDataCache = new ArrayLightDataCache(this.worldSlice);

        LightPipelineProvider lightPipelineProvider = new LightPipelineProvider(this.lightDataCache);

        var colorRegistry = new ColorProviderRegistry(client.getBlockColors());

        this.blockRenderer = new BlockRenderer(colorRegistry, lightPipelineProvider);
        this.fluidRenderer = new FluidRenderer(colorRegistry, lightPipelineProvider);
        this.lightPipelineProvider = lightPipelineProvider;

        this.blockModels = client.getBlockRendererDispatcher().getBlockModelShapes();
    }

    public BlockModelShapes getBlockModels() {
        return this.blockModels;
    }

    public BlockRenderer getBlockRenderer() {
        return this.blockRenderer;
    }

    public FluidRenderer getFluidRenderer() {
        return this.fluidRenderer;
    }

    /**
     * Initialize the render cache for a new chunk.
     */
    public void init(ChunkRenderContext context) {
        this.lightDataCache.reset(context.getOrigin());
        this.lightPipelineProvider.reset();
        this.worldSlice.copyData(context);
    }

    public WorldSlice getWorldSlice() {
        return this.worldSlice;
    }

    public void cleanup() {
        this.worldSlice.reset();
    }
}
