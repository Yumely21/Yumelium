package com.yumelium.yumelium.client.soulfire;

import me.jellysquid.mods.sodium.client.SodiumClientMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.util.ResourceLocation;

import java.io.IOException;

/**
 * The soul-fire sprites, stitched into the block atlas when a resource pack provides them.
 *
 * <p>1.12.2 has no soul fire — it arrived in 1.16 — so fire burning on soul sand is plain {@code minecraft:fire} and
 * looks identical to any other fire. Yumelium's mesher can tell the two apart (it knows the position, so it can look at
 * the block below) and re-textures those quads with these sprites, the same way connected textures re-texture glass.</p>
 *
 * <p><b>The artwork is not shipped with the mod.</b> A distributed mod jar must not carry Minecraft's assets, so the
 * textures come from the separate Yumelium companion resource pack — or any pack that puts them at the paths below.
 * Nothing here is registered unless a pack actually provides them: {@code registerSprite} on a missing path does not
 * fail, it stitches Minecraft's magenta "missing texture" placeholder, which would turn every campfire into a
 * checkerboard. Probe first, and stay off if the answer is no.</p>
 *
 * <p>Recolouring the ordinary orange flame instead was tried and rejected: real soul fire is different artwork, not a
 * hue shift — its flame tongues and its white-hot base do not exist in the orange texture — so a tint only ever
 * approximates it.</p>
 */
public final class SoulFireTextures {
    /** 1.12.2 puts block textures under {@code textures/blocks/} (plural); 1.16 uses {@code textures/block/}. */
    private static final String FIRE_0 = "blocks/soul_fire_0";
    private static final String FIRE_1 = "blocks/soul_fire_1";

    /**
     * The sprites to replace. 1.12.2's fire is {@code fire_layer_0}/{@code fire_layer_1} — the 1.13 flattening renamed
     * them to {@code fire_0}/{@code fire_1}, which is what the 1.16 assets the soul-fire art comes from use. Matching on
     * the 1.16 names found nothing, so every quad fell through un-swapped and the flame stayed orange while the shader
     * pack (keyed off the block id, not the sprite) still lit it blue.
     */
    private static final String VANILLA_FIRE_0 = "minecraft:blocks/fire_layer_0";
    private static final String VANILLA_FIRE_1 = "minecraft:blocks/fire_layer_1";

    // Written on the main thread at stitch time, read by the chunk-build WORKER threads (BlockRenderer.isSoulFire) —
    // volatile so a worker cannot mesh against a stale "available" and produce fire that is lit as soul fire but
    // textured as ordinary fire, or the reverse.
    private static volatile TextureAtlasSprite soulFire0;
    private static volatile TextureAtlasSprite soulFire1;
    private static volatile boolean available;

    private SoulFireTextures() {
    }

    /** @return true once a pack has supplied the sprites and they are stitched into the atlas. */
    public static boolean isAvailable() {
        return available;
    }

    public static void clear() {
        soulFire0 = null;
        soulFire1 = null;
        available = false;
        swapped = false;
        unexpectedSprite = null;
    }

    /**
     * Stitches the sprites if a loaded resource pack provides them. Call from {@code TextureStitchEvent.Pre} on the
     * block atlas.
     */
    public static void register(TextureMap map) {
        clear();
        if (!packProvides(FIRE_0) || !packProvides(FIRE_1)) {
            SodiumClientMod.logger().info("[Yumelium soul fire] no soul-fire textures in any loaded resource pack —"
                    + " fire on soul sand stays ordinary fire. Install the Yumelium companion texture pack to enable it.");
            return;
        }
        soulFire0 = map.registerSprite(new ResourceLocation("minecraft", FIRE_0));
        soulFire1 = map.registerSprite(new ResourceLocation("minecraft", FIRE_1));
        available = soulFire0 != null && soulFire1 != null;
        if (available) {
            SodiumClientMod.logger().info("[Yumelium soul fire] soul-fire textures registered — fire on soul sand will"
                    + " render blue");
        }
    }

    /**
     * Maps an ordinary fire sprite to its soul-fire counterpart.
     *
     * <p>Fire renders as two overlaid layers with different animations ({@code fire_0} and {@code fire_1}), and soul fire
     * ships a matching pair, so the swap is per-layer — feeding both layers the same sprite would visibly flatten the
     * animation.</p>
     *
     * @return the replacement sprite, or null if this quad is not a fire layer (or no pack supplied the textures).
     */
    public static TextureAtlasSprite replacementFor(String iconName) {
        if (!available || iconName == null) {
            return null;
        }
        if (iconName.equals(VANILLA_FIRE_0)) {
            swapped = true;
            return soulFire0;
        }
        if (iconName.equals(VANILLA_FIRE_1)) {
            swapped = true;
            return soulFire1;
        }
        // Reached only for a fire quad whose sprite is neither layer — i.e. VANILLA_FIRE_* no longer names what fire is
        // actually textured with. Report it ONCE with the real name: a silent no-match is what made the first attempt
        // look like "the pack isn't loading" when the pack was fine and only the expected name was wrong.
        if (unexpectedSprite == null) {
            unexpectedSprite = iconName;
            SodiumClientMod.logger().warn("[Yumelium soul fire] fire quad uses sprite '" + iconName + "', which is"
                    + " neither " + VANILLA_FIRE_0 + " nor " + VANILLA_FIRE_1 + " — cannot re-texture it");
        }
        return null;
    }

    /** Diagnostics for the swap itself: whether it ever fired, and the first sprite name we failed to recognise. */
    private static volatile boolean swapped;
    private static volatile String unexpectedSprite;

    /** @return true if at least one fire quad has actually been re-textured since the last resource reload. */
    public static boolean hasSwapped() {
        return swapped;
    }

    /** @return true if some resource pack actually has this texture (the mod itself never does). */
    private static boolean packProvides(String path) {
        try {
            Minecraft.getMinecraft().getResourceManager()
                    .getResource(new ResourceLocation("minecraft", "textures/" + path + ".png"));
            return true;
        } catch (IOException e) {
            return false; // pack not installed — the normal case, not an error
        } catch (Throwable t) {
            return false;
        }
    }
}
