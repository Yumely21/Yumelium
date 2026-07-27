package me.jellysquid.mods.sodium.mixin.core;

import me.jellysquid.mods.sodium.client.world.VanillaFluidBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraftforge.fluids.IFluidBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Makes vanilla {@link BlockLiquid} (water/lava) implement {@link VanillaFluidBlock} so the fluid mesher can resolve
 * the Forge {@link net.minecraftforge.fluids.Fluid} for a liquid block (via {@code WorldUtil.getFluid}).
 */
@Mixin(BlockLiquid.class)
public abstract class MixinBlockLiquid implements VanillaFluidBlock {
    @Unique
    private final IFluidBlock sodium$fluidBlock = new VanillaFluidBlock.Implementation((Block) (Object) this);

    @Override
    public IFluidBlock getFakeFluidBlock() {
        return this.sodium$fluidBlock;
    }
}
