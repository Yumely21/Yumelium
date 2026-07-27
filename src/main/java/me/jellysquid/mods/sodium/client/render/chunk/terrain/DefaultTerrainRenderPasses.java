package me.jellysquid.mods.sodium.client.render.chunk.terrain;

import net.minecraft.util.BlockRenderLayer;
import org.jetbrains.annotations.ApiStatus;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class DefaultTerrainRenderPasses {
    public static final TerrainRenderPass SOLID = new TerrainRenderPass(BlockRenderLayer.SOLID, false, false);
    public static final TerrainRenderPass CUTOUT = new TerrainRenderPass(BlockRenderLayer.CUTOUT_MIPPED, false, true);
    public static final TerrainRenderPass TRANSLUCENT = new TerrainRenderPass(BlockRenderLayer.TRANSLUCENT, true, false);

    @Deprecated
    public static final TerrainRenderPass[] ALL = new TerrainRenderPass[] { SOLID, CUTOUT, TRANSLUCENT };

    @ApiStatus.Experimental
    public static final Map<BlockRenderLayer, List<TerrainRenderPass>> RENDER_PASS_MAPPINGS = Map.of(
            BlockRenderLayer.SOLID, List.of(SOLID, CUTOUT),
            BlockRenderLayer.TRANSLUCENT, List.of(TRANSLUCENT)
    );

    static {
        if(!RENDER_PASS_MAPPINGS.values().stream().flatMap(Collection::stream).collect(Collectors.toSet()).containsAll(Arrays.asList(ALL))) {
            throw new IllegalStateException("Render pass mappings are not complete");
        }
    }
}
