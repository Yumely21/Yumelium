package com.yumelium.yumelium.mixin;

import net.minecraft.client.renderer.texture.TextureUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Alpha-weighted atlas mipmaps: stops cutout foliage from going BLACK at a distance.
 *
 * <p>Vanilla's {@code blendColors} builds each mip texel from its four parents but accumulates colour only from the
 * parents that are NOT fully transparent, then divides by 4 unconditionally (verified in the Forge-patched
 * bytecode: the {@code (c >> 24) != 0} guard at bci 50-127, then four {@code fdiv 4.0} at 135-161). A leaf texel
 * whose 2×2 block contains three transparent neighbours therefore keeps only a QUARTER of its colour while its
 * alpha lands at ~0.25 — high enough to clear the 0.1 cutoff and the {@code alpha < 96 → 0} snap that follows, so
 * the darkened texel is actually drawn. The error compounds with every mip level, which is why leaves (and grass at
 * grazing angles) fade toward black with distance while the sprite itself is perfectly fine.
 *
 * <p>The fix is the standard one: average the COLOUR over the contributing texels only, and leave ALPHA divided by
 * four — alpha is coverage and genuinely should fall as the sprite shrinks, whereas the colour of a leaf does not
 * become darker just because its neighbours are empty. Everything else (the gamma round-trip through
 * {@code getColorGamma} + the 1/2.2 pow, the alpha snap, the packing) is vanilla's, unchanged.
 *
 * <p>This is a shared-atlas fix, so it corrects every consumer at once — the Sodium chunk renderer, the Nvidium
 * mesh-shader backend and the shader pipeline alike (2026-08-01: diagnosed by false-colouring the Nvidium
 * fragment's factors, which showed the ATLAS SAMPLE itself returning black, in both backends).</p>
 */
@Mixin(TextureUtil.class)
public class MixinTextureUtilMipmap {
    @Shadow
    private static float getColorGamma(int color) {
        throw new AssertionError("shadow");
    }

    @Inject(method = "blendColors(IIIIZ)I", at = @At("HEAD"), cancellable = true, require = 1)
    private static void yumelium$alphaWeightedBlend(int c0, int c1, int c2, int c3, boolean hasTransparency,
                                                    CallbackInfoReturnable<Integer> cir) {
        if (!hasTransparency) {
            return; // fully opaque sprite: vanilla's plain per-channel average is already correct
        }
        float a = 0.0F, r = 0.0F, g = 0.0F, b = 0.0F;
        int contributors = 0;
        for (int i = 0; i < 4; i++) {
            int c = i == 0 ? c0 : i == 1 ? c1 : i == 2 ? c2 : c3;
            if ((c >> 24) != 0) { // same "not fully transparent" test vanilla uses
                a += getColorGamma(c >> 24);
                r += getColorGamma(c >> 16);
                g += getColorGamma(c >> 8);
                b += getColorGamma(c);
                contributors++;
            }
        }
        if (contributors == 0) {
            cir.setReturnValue(0); // every parent transparent → transparent, as vanilla produces
            return;
        }
        a /= 4.0F;              // coverage: keep vanilla's unconditional /4
        r /= contributors;      // colour: average only the texels that HAVE colour
        g /= contributors;
        b /= contributors;

        int alpha = (int) (Math.pow(a, 0.45454545454545453D) * 255.0D);
        int red = (int) (Math.pow(r, 0.45454545454545453D) * 255.0D);
        int green = (int) (Math.pow(g, 0.45454545454545453D) * 255.0D);
        int blue = (int) (Math.pow(b, 0.45454545454545453D) * 255.0D);
        if (alpha < 96) {
            alpha = 0; // vanilla's snap — keeps the cutout silhouette from growing a halo
        }
        cir.setReturnValue(alpha << 24 | red << 16 | green << 8 | blue);
    }

    /** Alpha at or below this (out of 255) is floored to exactly 0. See the blast-radius audit in the javadoc below
     * before changing: 8 catches every fake-transparent texel found in the scanned corpus (max was 8), while 9+
     * starts eating energy_barrier.png's real 9–32 gradient band. */
    private static final int ALPHA_FLOOR_MAX = 8;

    /**
     * Block-atlas alpha floor (2026-08-04): texels shipped at alpha 1–8/255 — Betweenlands' "transparent"
     * plant/algae texels; algae_N.png contains NO true alpha-0 pixels at all — are floored to alpha 0 at sprite
     * load, BEFORE mip generation. Vanilla's fixed-function alpha test (0.1) never drew them in ANY layer, but the
     * pack's terrain discard (a &lt;= 0.00001) and gbuffers_water alphaTest (GREATER 0.0001) pass them, rendering a
     * near-invisible film whose DEPTH writes punch holes in late depth-tested draws (BL gas clouds); with
     * ANISOTROPIC_FILTER the sqrt boost even lifts them over the hardened 0.1 cutoff (sqrt(8/255) = 0.177).
     * Flooring the DATA fixes every consumer at once: terrain, water, AF (sqrt(0) = 0), Nvidium, vanilla.
     *
     * <p>{@code generateMipmapData} aliases {@code result[0] = data[0]} (same array reference), so this in-place
     * mutation reaches both the uploaded level-0 texels and every mip input; it runs BEFORE the method's own
     * hasTransparency scan, so the alpha-weighted blend above engages and floored texels stop contributing RGB to
     * mip colours. RGB is KEPT (mask 0x00FFFFFF): every contributor test keys on alpha alone, and the artist's
     * under-colour is better for GL_LINEAR edge filtering than transparent black. Thread-safe by construction —
     * VintageFix runs this on ForkJoinPool workers (one volatile read + pure per-call array mutation).</p>
     *
     * <p>Scoped to the block atlas via {@code YumeliumMod.BLOCK_ATLAS_LOADING} (MixinTextureMapAtlasScope brackets
     * {@code TextureMap.loadSprites} — deliberately the CALLER of loadTextureAtlas, where VintageFix injects its
     * preload, so the flag is set strictly before VF's workers start and cleared after VF's blocking wait ends).
     * Kill switch: {@code YumeliumMod.ATLAS_ALPHA_FLOOR}.</p>
     */
    @Inject(method = "generateMipmapData(II[[I)[[I", at = @At("HEAD"), require = 1)
    private static void yumelium$floorNearZeroAlpha(int mipmapLevels, int width, int[][] data,
                                                    CallbackInfoReturnable<int[][]> cir) {
        if (!com.yumelium.yumelium.YumeliumMod.ATLAS_ALPHA_FLOOR
                || !com.yumelium.yumelium.YumeliumMod.BLOCK_ATLAS_LOADING) {
            return;
        }
        int[] level0 = data[0];
        if (level0 == null) {
            return;
        }
        for (int i = 0; i < level0.length; i++) {
            int a = level0[i] >>> 24;
            if (a != 0 && a <= ALPHA_FLOOR_MAX) {
                level0[i] &= 0x00FFFFFF; // alpha -> 0, RGB kept
            }
        }
    }
}
