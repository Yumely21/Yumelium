package com.yumelium.yumelium.client;

import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gui.SodiumGameOptions;

/**
 * Classifies an animated atlas sprite (by its icon name) into a Yumelium Plus animation category and reports whether
 * that category is currently enabled. Used by {@code MixinTextureMap} to selectively freeze water/lava/fire/portal/other
 * texture animations. The master "All Animations" toggle gates every category.
 */
public final class YumeliumAnimationFilter {
    private YumeliumAnimationFilter() {
    }

    public static boolean shouldAnimate(String iconName) {
        SodiumGameOptions.YumeliumPlusSettings o = SodiumClientMod.options().yumeliumPlus;

        // Master switch: off freezes everything.
        if (!o.animateBlockTextures) {
            return false;
        }

        if (iconName == null) {
            return o.animateOther;
        }

        if (iconName.contains("water_still") || iconName.contains("water_flow")) {
            return o.animateWater;
        }
        if (iconName.contains("lava_still") || iconName.contains("lava_flow")) {
            return o.animateLava;
        }
        if (iconName.contains("fire_layer")) {
            return o.animateFire;
        }
        // Vanilla nether portal sprite is "minecraft:blocks/portal".
        if (iconName.endsWith("/portal") || iconName.contains("nether_portal")) {
            return o.animatePortal;
        }

        return o.animateOther;
    }
}
