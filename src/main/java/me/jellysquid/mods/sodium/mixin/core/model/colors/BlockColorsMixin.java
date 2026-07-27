package me.jellysquid.mods.sodium.mixin.core.model.colors;

import it.unimi.dsi.fastutil.objects.Reference2ReferenceMap;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceMaps;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import it.unimi.dsi.fastutil.objects.ReferenceSet;
import it.unimi.dsi.fastutil.objects.ReferenceSets;
import me.jellysquid.mods.sodium.client.model.color.interop.BlockColorsExtended;
import net.minecraft.block.Block;
import net.minecraft.client.renderer.color.BlockColors;
import net.minecraft.client.renderer.color.IBlockColor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tracks a parallel copy of the block -> color-provider map (keyed by Block for iteration) so
 * {@link me.jellysquid.mods.sodium.client.model.color.ColorProviderRegistry} can read it, and records which vanilla
 * blocks a mod overrode (so per-vertex biome blending is skipped for those). Adapted to 1.12.2's
 * {@code registerBlockColorHandler(IBlockColor, Block...)}.
 */
@Mixin(BlockColors.class)
public class BlockColorsMixin implements BlockColorsExtended {
    @Unique
    private final Reference2ReferenceMap<Block, IBlockColor> blocksToColor = new Reference2ReferenceOpenHashMap<>();

    @Unique
    private final ReferenceSet<Block> overridenBlocks = new ReferenceOpenHashSet<>();

    @Inject(method = "registerBlockColorHandler", at = @At("HEAD"))
    private void preRegisterColorProvider(IBlockColor provider, Block[] blocks, CallbackInfo ci) {
        if (provider != null) {
            synchronized (this.blocksToColor) {
                for (Block block : blocks) {
                    // A vanilla provider is already registered for vanilla blocks; if we are replacing it,
                    // a mod is using custom logic and we must disable per-vertex coloring for that block.
                    if (this.blocksToColor.put(block, provider) != null) {
                        this.overridenBlocks.add(block);
                    }
                }
            }
        }
    }

    @Override
    public Reference2ReferenceMap<Block, IBlockColor> sodium$getProviders() {
        return Reference2ReferenceMaps.unmodifiable(this.blocksToColor);
    }

    @Override
    public ReferenceSet<Block> embeddium$getOverridenVanillaBlocks() {
        return ReferenceSets.unmodifiable(this.overridenBlocks);
    }
}
