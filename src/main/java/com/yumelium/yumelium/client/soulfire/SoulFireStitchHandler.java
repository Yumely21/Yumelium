package com.yumelium.yumelium.client.soulfire;

import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Stitches the soul-fire sprites into the block atlas on every resource reload. See {@link SoulFireTextures} for why the
 * artwork lives in a separate resource pack rather than in the mod.
 */
public class SoulFireStitchHandler {

    @SubscribeEvent
    public void onTextureStitchPre(TextureStitchEvent.Pre event) {
        if (event.getMap() != Minecraft.getMinecraft().getTextureMapBlocks()) {
            return; // block textures only
        }
        SoulFireTextures.register(event.getMap());
    }
}
