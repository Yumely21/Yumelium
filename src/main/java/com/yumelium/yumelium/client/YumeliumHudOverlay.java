package com.yumelium.yumelium.client;

import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gui.SodiumGameOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Yumelium Plus HUD extras: renders an FPS counter and/or player coordinates in one of the four screen corners (when
 * enabled and the F3 debug screen isn't open), optionally with a text shadow.
 */
public class YumeliumHudOverlay {
    private static final int COLOR = 0xFFFFFFFF;

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();

        // The F3 debug screen already shows this information; don't draw over it.
        if (mc.gameSettings.showDebugInfo) {
            return;
        }

        SodiumGameOptions.YumeliumPlusSettings opts = SodiumClientMod.options().yumeliumPlus;

        List<String> lines = new ArrayList<>();
        if (opts.showFps) {
            lines.add("FPS: " + Minecraft.getDebugFPS());
        }
        if (opts.showCoordinates && mc.player != null) {
            lines.add(String.format("XYZ: %.1f / %.1f / %.1f", mc.player.posX, mc.player.posY, mc.player.posZ));
        }
        if (lines.isEmpty()) {
            return;
        }

        FontRenderer font = mc.fontRenderer;
        ScaledResolution sr = new ScaledResolution(mc);
        int screenW = sr.getScaledWidth();
        int screenH = sr.getScaledHeight();

        SodiumGameOptions.HudPosition pos = opts.hudPosition;
        int lineHeight = font.FONT_HEIGHT + 1;
        int y = pos.isTop() ? 2 : screenH - (lines.size() * lineHeight) - 2;

        for (String line : lines) {
            int x = pos.isLeft() ? 2 : screenW - font.getStringWidth(line) - 2;

            if (opts.hudShadow) {
                font.drawStringWithShadow(line, x, y, COLOR);
            } else {
                font.drawString(line, x, y, COLOR);
            }

            y += lineHeight;
        }
    }
}
