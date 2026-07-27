package me.jellysquid.mods.sodium.client.render.chunk.terrain;

import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.Material;
import net.minecraft.util.BlockRenderLayer;

/**
 * A terrain render pass corresponds to a draw call to render some subset of terrain geometry. Passes are generally
 * used for fixed configuration that will not change from quad to quad and allow for optimizations to be made
 * within the terrain shader code at compile time (e.g. omitting the fragment discard conditional entirely on the solid pass).
 * <p>
 * Geometry that shares the same terrain render pass may still be able to specify some more dynamic properties. See {@link Material}
 * for more information.
 * <p>
 * NOTE(yumelium): delombok'd (upstream used @Builder/@AllArgsConstructor/@Accessors) to avoid a Lombok+Java25
 * dependency. The 1.16.5 {@code RenderType} layer is replaced with 1.12.2 {@link BlockRenderLayer}.
 */
public class TerrainRenderPass {
    /**
     * The vanilla render layer this pass corresponds to (used for GPU pipeline state setup).
     */
    private final BlockRenderLayer layer;

    /**
     * Whether sections on this render pass should be rendered farthest-to-nearest, rather than nearest-to-farthest.
     */
    private final boolean useReverseOrder;
    /**
     * Whether fragment alpha testing should be enabled for this render pass.
     */
    private final boolean fragmentDiscard;
    /**
     * Whether this render pass wants to opt in to translucency sorting if enabled.
     */
    private final boolean useTranslucencySorting;

    // delombok: package-private all-args constructor (@AllArgsConstructor(access = PACKAGE))
    TerrainRenderPass(BlockRenderLayer layer, boolean useReverseOrder, boolean fragmentDiscard, boolean useTranslucencySorting) {
        this.layer = layer;
        this.useReverseOrder = useReverseOrder;
        this.fragmentDiscard = fragmentDiscard;
        this.useTranslucencySorting = useTranslucencySorting;
    }

    @Deprecated
    public TerrainRenderPass(BlockRenderLayer layer, boolean useReverseOrder, boolean allowFragmentDiscard) {
        this(layer, useReverseOrder, allowFragmentDiscard, useReverseOrder);
    }

    public BlockRenderLayer layer() {
        return this.layer;
    }

    public boolean isReverseOrder() {
        return this.useReverseOrder;
    }

    public boolean isSorted() {
        return this.useTranslucencySorting && SodiumClientMod.canApplyTranslucencySorting();
    }

    @Deprecated
    public void startDrawing() {
        // TODO(yumelium): 1.12.2 BlockRenderLayer has no setupRenderState(); reimplement terrain render state
        // (alpha test for CUTOUT, blend for TRANSLUCENT, via GlStateManager) when ShaderChunkRenderer is ported.
    }

    @Deprecated
    public void endDrawing() {
        // TODO(yumelium): see startDrawing().
    }

    public boolean supportsFragmentDiscard() {
        return this.fragmentDiscard;
    }
}
