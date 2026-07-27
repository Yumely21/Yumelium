package com.yumelium.yumelium.client.zoom;

import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gui.SodiumGameOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

/**
 * A hold-to-zoom feature (an independent reimplementation of the well-known zoom concept — the zoom behaviour itself is
 * not copyrightable; no code from Zoomify (GPL-3.0) is used, so Yumelium's licensing is unaffected). Holding the Zoom
 * key smoothly narrows the world FOV; the mouse wheel adjusts the zoom depth while held. The actual FOV multiply is
 * applied in {@code MixinEntityRendererZoom}.
 */
public final class ZoomHandler {
    public static final KeyBinding ZOOM_KEY =
            new KeyBinding("key.yumelium.zoom", Keyboard.KEY_C, "key.categories.yumelium");

    private static final ZoomHandler INSTANCE = new ZoomHandler();

    private static final float MIN_MULT = 0.05F; // deepest zoom (~20x)
    private static final float MAX_MULT = 0.60F; // shallowest zoom
    private static final float DEFAULT_MULT = 0.50F; // initial 2x

    private float currentMult = 1.0F;
    private float zoomMult = DEFAULT_MULT;
    private long lastNanos = System.nanoTime();

    private ZoomHandler() {
    }

    public static ZoomHandler instance() {
        return INSTANCE;
    }

    /** Read by the FOV mixin. 1.0 = no zoom; smaller = zoomed in. */
    public static float getFovMultiplier() {
        return INSTANCE.currentMult;
    }

    /**
     * Mouse-look sensitivity scale for {@param entity}. While the local player is zoomed (and the option is on), look
     * sensitivity is scaled by the zoom FOV multiplier, so a deeper zoom aims proportionally finer. 1.0 otherwise.
     */
    public static float getSensitivityFactor(Object entity) {
        SodiumGameOptions.YumeliumPlusSettings o = options();
        if (!o.zoomEnabled || !o.zoomReduceSensitivity) {
            return 1.0F;
        }
        if (entity != Minecraft.getMinecraft().player) {
            return 1.0F;
        }
        float mult = INSTANCE.currentMult;
        return mult < 1.0F ? mult : 1.0F;
    }

    private static SodiumGameOptions.YumeliumPlusSettings options() {
        return SodiumClientMod.options().yumeliumPlus;
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        SodiumGameOptions.YumeliumPlusSettings o = options();

        boolean active = o.zoomEnabled && ZOOM_KEY.isKeyDown() && mc.world != null && mc.currentScreen == null;

        // Releasing the zoom key resets the scroll-adjusted depth, so each zoom starts at the initial 2x.
        if (!active) {
            this.zoomMult = DEFAULT_MULT;
        }

        float target = active ? this.zoomMult : 1.0F;

        long now = System.nanoTime();
        float dt = (now - this.lastNanos) / 1.0e9F;
        this.lastNanos = now;
        if (dt < 0.0F || dt > 0.5F) {
            dt = 1.0F / 60.0F;
        }

        if (o.zoomSmooth) {
            float t = Math.min(1.0F, dt * 12.0F); // transition speed
            this.currentMult += (target - this.currentMult) * t;
            if (Math.abs(this.currentMult - target) < 0.001F) {
                this.currentMult = target;
            }
        } else {
            this.currentMult = target;
        }
    }

    @SubscribeEvent
    public void onMouse(MouseEvent event) {
        SodiumGameOptions.YumeliumPlusSettings o = options();
        if (!o.zoomEnabled || !o.zoomScroll) {
            return;
        }
        if (!ZOOM_KEY.isKeyDown() || Minecraft.getMinecraft().currentScreen != null) {
            return;
        }

        int dwheel = event.getDwheel();
        if (dwheel == 0) {
            return;
        }

        // Scroll up zooms in further (smaller multiplier), scroll down zooms out.
        if (dwheel > 0) {
            this.zoomMult *= 0.85F;
        } else {
            this.zoomMult /= 0.85F;
        }
        this.zoomMult = Math.max(MIN_MULT, Math.min(MAX_MULT, this.zoomMult));

        // Consume the scroll so it doesn't also switch the hotbar slot.
        event.setCanceled(true);
    }
}
