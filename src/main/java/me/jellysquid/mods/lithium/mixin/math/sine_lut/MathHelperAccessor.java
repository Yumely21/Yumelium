package me.jellysquid.mods.lithium.mixin.math.sine_lut;

import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read access to the private vanilla sine table so {@code CompactSineLUT} can build (and validate against) it —
 * an accessor mixin instead of an access-transformer entry keeps the whole subsystem self-contained. */
@Mixin(MathHelper.class)
public interface MathHelperAccessor {
    @Accessor("SIN_TABLE")
    static float[] getSinTable() {
        throw new AssertionError();
    }
}
