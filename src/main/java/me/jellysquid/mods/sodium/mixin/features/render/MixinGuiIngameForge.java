package me.jellysquid.mods.sodium.mixin.features.render;

import net.minecraft.client.gui.FontRenderer;
import net.minecraftforge.client.GuiIngameForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Draws the "Yumelium Renderer" F3 debug line in Yumelium's light blue-purple accent instead of the default grey.
 * (The F3 debug overlay only honours the 16 formatting-code colours, so a custom RGB requires recolouring the draw call.)
 */
@Mixin(GuiIngameForge.class)
public abstract class MixinGuiIngameForge {
    @Redirect(method = "renderHUDText", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/FontRenderer;drawString(Ljava/lang/String;III)I"))
    private int yumelium$colorDebugLine(FontRenderer font, String text, int x, int y, int color) {
        if (text != null && text.contains("Yumelium Renderer")) {
            color = 0xFFB4A8F0;
        }
        return font.drawString(text, x, y, color);
    }
}
