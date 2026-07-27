package com.yumelium.yumelium.shaders;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import org.lwjgl.input.Keyboard;

import java.util.List;

/**
 * Iris/Oculus port — shortcut controls. {@code K} toggles the deferred shader pipeline on/off; {@code N} cycles the
 * active shader pack (the built-in test pack + any external packs in {@code shaderpacks/}). These delegate to
 * {@link ShaderController}, the same code path the Shaders GUI (M8, reachable from Video Settings) uses.
 */
public final class IrisKeyHandler {
    public static final KeyBinding TOGGLE_KEY =
            new KeyBinding("key.yumelium.shaders_toggle", Keyboard.KEY_K, "key.categories.yumelium");
    public static final KeyBinding CYCLE_PACK_KEY =
            new KeyBinding("key.yumelium.shaders_cycle_pack", Keyboard.KEY_N, "key.categories.yumelium");

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
            status("Yumelium shaders: "
                    + (ShaderController.isEnabled() ? "ON — " + ShaderController.activePack() : "OFF"));
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
        status("Shader pack: " + next + (ShaderController.isEnabled() ? "" : " (press K to enable)"));
    }

    private static void status(String message) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player != null) {
            mc.player.sendStatusMessage(new TextComponentString(message), true);
        }
    }
}
