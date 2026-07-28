package com.yumelium.yumelium.shaders;

import com.yumelium.yumelium.shaders.gui.YumeliumShaderScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import org.lwjgl.input.Keyboard;

import java.util.List;

/**
 * Iris/Oculus port — the standard Iris keybinds, rebindable from Options → Controls under the "Iris Keybinds"
 * category, using Iris's own translation keys and defaults: {@code K} toggles shaders, {@code R} reloads the active
 * pack from disk, {@code O} opens the shader pack selection screen. {@code N} (cycle pack) is a Yumelium extra Iris
 * doesn't have, kept for convenience. All delegate to {@link ShaderController}, the same code path the Shaders GUI
 * (reachable from Video Settings) uses. Status messages reuse Iris's translation keys too (en_us + ja_jp shipped).
 */
public final class IrisKeyHandler {
    private static final String CATEGORY = "iris.keybinds";

    public static final KeyBinding TOGGLE_KEY =
            new KeyBinding("iris.keybind.toggleShaders", Keyboard.KEY_K, CATEGORY);
    public static final KeyBinding RELOAD_KEY =
            new KeyBinding("iris.keybind.reload", Keyboard.KEY_R, CATEGORY);
    public static final KeyBinding PACK_SCREEN_KEY =
            new KeyBinding("iris.keybind.shaderPackSelection", Keyboard.KEY_O, CATEGORY);
    public static final KeyBinding CYCLE_PACK_KEY =
            new KeyBinding("key.yumelium.shaders_cycle_pack", Keyboard.KEY_N, CATEGORY);

    private static final IrisKeyHandler INSTANCE = new IrisKeyHandler();

    private IrisKeyHandler() {
    }

    public static IrisKeyHandler instance() {
        return INSTANCE;
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        while (TOGGLE_KEY.isPressed()) {
            ShaderController.setEnabled(!ShaderController.isEnabled());
            status(ShaderController.isEnabled()
                    ? I18n.format("iris.shaders.toggled", ShaderController.activePack())
                    : I18n.format("iris.shaders.disabled"));
        }

        while (RELOAD_KEY.isPressed()) {
            ShaderController.reloadShaders();
            status(I18n.format("iris.shaders.reloaded"));
        }

        while (PACK_SCREEN_KEY.isPressed()) {
            Minecraft mc = Minecraft.getMinecraft();
            mc.displayGuiScreen(new YumeliumShaderScreen(mc.currentScreen));
        }

        while (CYCLE_PACK_KEY.isPressed()) {
            cyclePack();
        }
    }

    /** Switches the active shader pack to the next available one (built-in + external {@code shaderpacks/} entries). */
    private void cyclePack() {
        List<String> available = ShaderController.availablePacks();
        if (available.isEmpty()) {
            return;
        }
        String current = ShaderController.activePack();
        int index = available.indexOf(current);
        String next = available.get((index + 1) % available.size());

        ShaderController.setPack(next);
        status("Shader pack: " + next + (ShaderController.isEnabled()
                ? "" : " (press " + TOGGLE_KEY.getDisplayName() + " to enable)"));
    }

    private static void status(String message) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player != null) {
            mc.player.sendStatusMessage(new TextComponentString(message), true);
        }
    }
}
