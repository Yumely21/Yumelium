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
}
