package me.jellysquid.mods.lithium.mixin.math.sine_lut;

import me.jellysquid.mods.lithium.common.util.math.CompactSineLUT;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/** Lithium math.sine_lut (ported to 1.12.2): routes {@link MathHelper#sin}/{@link MathHelper#cos} through the
 * cache-friendly 64KB compact LUT. Bit-for-bit identical to vanilla — see {@link CompactSineLUT}'s startup validation. */
@Mixin(MathHelper.class)
public class MixinMathHelper {
    /**
     * @author jellysquid3
     * @reason use an optimized implementation
     */
    @Overwrite
    public static float sin(float value) {
        return CompactSineLUT.sin(value);
    }

    /**
     * @author jellysquid3
     * @reason use an optimized implementation
     */
    @Overwrite
    public static float cos(float value) {
        return CompactSineLUT.cos(value);
    }
}
