package me.jellysquid.mods.sodium.mixin.features.options;

import me.jellysquid.mods.sodium.client.gui.SodiumOptionsGUI;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiOptions;
import net.minecraft.client.gui.GuiScreen;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces the vanilla "Video Settings..." button (id 101) in the Options screen with Yumelium's Sodium options GUI.
 */
@Mixin(GuiOptions.class)
public abstract class MixinOptionsScreen extends GuiScreen {
    @Dynamic
    @Inject(method = "actionPerformed", at = @At("HEAD"), cancellable = true)
    private void sodium$openSodiumOptions(GuiButton button, CallbackInfo ci) {
        if (button.enabled && button.id == 101) {
            this.mc.displayGuiScreen(new SodiumOptionsGUI(this));
            ci.cancel();
        }
    }
}
