package com.yumelium.yumelium.shaders;

import com.yumelium.yumelium.nvidium.NvidiumBackend;
import com.yumelium.yumelium.shaders.pack.ShaderOptionSet;
import com.yumelium.yumelium.shaders.pipeline.IrisPipeline;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gl.device.RenderDevice;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Central control for the Yumelium shader pipeline: enable/disable + active-pack selection, shared by the test keybinds
 * (K/N) and the Shaders GUI (M8). Applies the Nvidium mutual-exclusion (shaders and Nvidium can't both drive terrain)
 * and reloads Sodium's renderer so the terrain program is rebuilt from the active pack.
 */
public final class ShaderController {
    private ShaderController() {
    }

    public static boolean isEnabled() {
        return IrisPipeline.instance().isEnabled();
    }

    public static String activePack() {
        return IrisPipeline.instance().activePackName();
    }

    public static List<String> availablePacks() {
        return ShaderPackManager.instance().listAvailable();
    }

    /** @return the active pack's configurable options (for the "Shader Pack Settings" screen). */
    public static ShaderOptionSet shaderOptions() {
        return IrisPipeline.instance().shaderOptions();
    }

    /** Persists a shader-option change and recompiles the pipeline + renderer so it takes effect. */
    public static void applyOptionChanges() {
        IrisPipeline.instance().applyOptionChanges();
        reloadRenderer();
    }

    /** Enables or disables the shader pipeline (idempotent — a no-op if already in that state). */
    public static void setEnabled(boolean on) {
        if (IrisPipeline.instance().isEnabled() == on) {
            return;
        }
        IrisPipeline.instance().toggle(); // flips to `on`
        NvidiumBackend.shadersActive = on;
        NvidiumBackend.applyConfig();
        reloadRenderer();
        saveState();
        SodiumClientMod.logger().info("[Iris] shaders " + (on ? "ON" : "OFF")
                + " (Nvidium " + (on ? "disabled" : "restored") + ", pack '" + activePack() + "')");
    }

    /**
     * Reloads the active shader pack from disk (Iris's R keybind): drops the compiled pipeline so every program,
     * properties file and saved option value is re-read on the next frame, then rebuilds the renderer so the terrain
     * program is recompiled too. Safe while disabled (destroy() is idempotent; the fresh state is simply picked up
     * on the next enable) — same as Iris, where R reloads regardless of the enabled state.
     */
    public static void reloadShaders() {
        IrisPipeline.instance().destroy();
        if (isEnabled()) {
            reloadRenderer();
        }
        SodiumClientMod.logger().info("[Iris] shaders reloaded from disk (pack '" + activePack() + "')");
    }

    /** Selects the active shader pack, rebuilding the pipeline + renderer. A no-op if the name is null or unchanged. */
    public static void setPack(String name) {
        if (name == null || name.equals(IrisPipeline.instance().activePackName())) {
            return;
        }
        IrisPipeline.instance().setActivePack(name);
        NvidiumBackend.applyConfig();
        reloadRenderer();
        saveState();
        SodiumClientMod.logger().info("[Iris] active shader pack → '" + name + "'");
    }

    // --- persistence of the enabled state + active pack across restarts (shaderpacks/yumelium-config.txt) -----------

    private static Path stateFile() {
        return ShaderPackManager.instance().shaderpacksDir().resolve("yumelium-config.txt");
    }

    /** Writes the current enabled state + active pack so a restart restores them. */
    private static void saveState() {
        try {
            String text = "enabled=" + isEnabled() + "\npack=" + activePack() + "\n";
            Files.write(stateFile(), text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            SodiumClientMod.logger().warn("[Iris] could not save shader state", e);
        }
    }

    /**
     * Restores the saved enabled state + active pack at startup (called from client init, before any world). Only sets
     * the pipeline fields + the Nvidium mutual-exclusion flag — the renderer picks them up when the world first loads.
     * A saved pack that no longer exists falls back to the built-in.
     */
    public static void restoreState() {
        Path file = stateFile();
        try {
            if (!Files.exists(file)) {
                return;
            }
            boolean enabled = false;
            String pack = null;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                if (key.equals("enabled")) {
                    enabled = Boolean.parseBoolean(value);
                } else if (key.equals("pack")) {
                    pack = value;
                }
            }
            if (pack != null && !availablePacks().contains(pack)) {
                pack = null; // the saved pack was removed → fall back to the built-in
            }
            IrisPipeline.instance().restore(pack, enabled);
            NvidiumBackend.shadersActive = enabled;
            SodiumClientMod.logger().info("[Iris] restored shader state: " + (enabled ? "ON" : "OFF")
                    + ", pack '" + activePack() + "'");
        } catch (Exception e) {
            SodiumClientMod.logger().warn("[Iris] could not restore shader state", e);
        }
    }

    /** Reloads Sodium's renderer so the terrain program (built from the active pack) is recompiled. */
    public static void reloadRenderer() {
        SodiumWorldRenderer renderer = SodiumWorldRenderer.getInstanceNullable();
        if (renderer == null) {
            return;
        }
        RenderDevice.enterManagedCode();
        try {
            renderer.reload();
        } catch (Throwable t) {
            SodiumClientMod.logger().error("[Iris] renderer reload failed", t);
        } finally {
            RenderDevice.exitManagedCode();
        }
    }
}
