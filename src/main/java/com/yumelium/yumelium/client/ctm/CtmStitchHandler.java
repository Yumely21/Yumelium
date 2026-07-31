package com.yumelium.yumelium.client.ctm;

import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gui.SodiumGameOptions;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Registers the built-in connected-texture tile sprites into the block atlas at stitch time (which fires on every
 * resource reload). The tile PNGs come from the separate Yumelium companion resource pack (a distributed jar must
 * not carry Minecraft-derived assets); {@link CtmRegistry#registerBuiltins} probes for them and stays inactive
 * when no loaded pack provides them.
 */
public class CtmStitchHandler {

    private static void log(String s) {
        SodiumClientMod.logger().info("[Yumelium CTM] " + s);
    }

    private static boolean enabled() {
        try {
            return SodiumClientMod.options().yumeliumPlus.connectedTextures;
        } catch (Throwable t) {
            return false;
        }
    }

    @SubscribeEvent
    public void onTextureStitchPre(TextureStitchEvent.Pre event) {
        if (event.getMap() != Minecraft.getMinecraft().getTextureMapBlocks()) {
            return; // CTM tiles live only on the block atlas
        }

        if (!enabled()) {
            CtmRegistry.instance().clear();
            return;
        }

        // The tiles ship in the separate Yumelium companion resource pack, not in the mod (a distributed jar must not
        // carry Minecraft's assets). Registering resolves against whatever packs the player has loaded.
        try {
            if (CtmRegistry.instance().registerBuiltins(event.getMap())) {
                log("connected textures registered");
            } else {
                log("no connected-texture tiles in any loaded resource pack — inactive."
                        + " Install the Yumelium companion texture pack to enable them.");
            }
        } catch (Throwable t) {
            CtmRegistry.instance().clear();
            log("registration failed (disabling): " + t);
        }
    }
}
