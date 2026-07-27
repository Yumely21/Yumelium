package me.jellysquid.mods.lithium.common.util.math;

import me.jellysquid.mods.lithium.mixin.math.sine_lut.MathHelperAccessor;
import net.minecraft.util.math.MathHelper;

/**
 * A replacement for the sine angle lookup table used in {@link MathHelper}, both reducing the size of LUT and improving
 * the access patterns for common paired sin/cos operations.
 *
 *  sin(-x) = -sin(x)
 *    ... to eliminate negative angles from the LUT.
 *
 *  sin(x) = sin(pi/2 - x)
 *    ... to eliminate supplementary angles from the LUT.
 *
 * Using these identities allows us to reduce the LUT from 64K entries (256 KB) to just 16K entries (64 KB), enabling
 * it to better fit into the CPU's caches at the expense of some cycles on the fast path.
 *
 * Unlike other "fast math" implementations, the values returned by this class are *bit-for-bit identical* with those
 * from {@link MathHelper} — validated at class-initialization against every entry of the vanilla table (a mismatch
 * throws, so a silently-different sine can never ship).
 *
 * Ported from Lithium 1.16.x (LGPLv3, CaffeineMC/lithium-fabric) to 1.12.2: the vanilla table here is
 * {@code SIN_TABLE} (reached through {@link MathHelperAccessor} instead of an access widener) and the angle-to-index
 * constants are 1.12.2's exact float expressions ({@code (int)(f * 10430.378F)}), NOT Lithium's 1.16 double-context
 * ones — bit-parity is against THIS version's vanilla.
 *
 * @author coderbot16   Author of the original (and very clever) implementation in Rust:
 *  https://gitlab.com/coderbot16/i73/-/tree/master/i73-trig/src
 * @author jellysquid3  Additional optimizations, port to Java
 */
public class CompactSineLUT {
    private static final int[] SINE_TABLE_INT = new int[16384 + 1];
    private static final float SINE_TABLE_MIDPOINT;

    static {
        float[] sinTable = MathHelperAccessor.getSinTable();

        // Copy the sine table, converting to raw int bits
        for (int i = 0; i < SINE_TABLE_INT.length; i++) {
            SINE_TABLE_INT[i] = Float.floatToRawIntBits(sinTable[i]);
        }

        SINE_TABLE_MIDPOINT = sinTable[sinTable.length / 2];

        // Test that the lookup table is correct during runtime
        for (int i = 0; i < sinTable.length; i++) {
            float expected = sinTable[i];
            float value = lookup(i);

            if (expected != value) {
                throw new IllegalArgumentException(String.format("LUT error at index %d (expected: %s, found: %s)", i, expected, value));
            }
        }
    }

    // [VanillaCopy] MathHelper#sin(float) — 1.12.2 constants
    public static float sin(float f) {
        return lookup((int) (f * 10430.378F) & 0xFFFF);
    }

    // [VanillaCopy] MathHelper#cos(float) — 1.12.2 constants
    public static float cos(float f) {
        return lookup((int) (f * 10430.378F + 16384.0F) & 0xFFFF);
    }

    private static float lookup(int index) {
        // A special case... Is there some way to eliminate this?
        if (index == 32768) {
            return SINE_TABLE_MIDPOINT;
        }

        // Trigonometric identity: sin(-x) = -sin(x)
        // Given a domain of 0 <= x <= 2*pi, just negate the value if x > pi.
        // This allows the sin table size to be halved.
        int neg = (index & 0x8000) << 16;

        // All bits set if (pi/2 <= x), none set otherwise
        // Extracts the 15th bit from 'half'
        int mask = (index << 17) >> 31;

        // Trigonometric identity: sin(x) = sin(pi/2 - x)
        int pos = (0x8001 & mask) + (index ^ mask);

        // Wrap the position in the table. Moving this down to immediately before the array access
        // seems to help the Hotspot compiler optimize the bit math better.
        pos &= 0x7fff;

        // Fetch the corresponding value from the LUT and invert the sign bit as needed
        // This directly manipulates the sign bit on the float bits to simplify logic
        return Float.intBitsToFloat(SINE_TABLE_INT[pos] ^ neg);
    }
}
