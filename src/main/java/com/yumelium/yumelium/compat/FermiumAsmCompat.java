package com.yumelium.yumelium.compat;

import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Soft-dependency workaround for FermiumASM (NormalASM/LoliASM lineage) in DEV environments.
 *
 * <p>Its {@code mirror.normalasm.api.NormalStringPool.onDebugList} — a static {@code RenderGameOverlayEvent.Text}
 * handler that appends string-pool stats to F3 — calls {@code Minecraft.func_71410_x()} by its SRG name directly.
 * The class sits in a coremod transformer-EXCLUDED package, so the dev runtime's SRG→MCP remapper never touches
 * it: in a dev (MCP-named) run the method doesn't exist and every F3 frame dies with NoSuchMethodError. In
 * production the runtime IS SRG-named and the handler works fine.</p>
 *
 * <p>TIMING (learned the hard way, 2026-07-27): NormalASM registers the handler in its {@code loadComplete}
 * (CommonProxy.loadComplete, gated on {@code NormalStringPool.getSize() > 0}) — AFTER every mod's postInit, so a
 * postInit-time unregister is a no-op on a not-yet-registered handler. The removal therefore runs on the FIRST
 * CLIENT TICK (strictly after all loadComplete phases, strictly before any F3 render is possible), via a one-shot
 * tick listener that then removes itself. Production keeps FermiumASM's F3 line (SRG present → we never install
 * the listener); the string pool itself is untouched either way. Inert when FermiumASM is absent.</p>
 */
public final class FermiumAsmCompat {
    private FermiumAsmCompat() {
    }

    /** Call from postInit (client side): installs the one-shot fixer only when needed. */
    public static void install() {
        try {
            Class.forName("mirror.normalasm.api.NormalStringPool", false, FermiumAsmCompat.class.getClassLoader());
        } catch (Throwable absent) {
            return; // FermiumASM not installed
        }
        try {
            Minecraft.class.getDeclaredMethod("func_71410_x");
            return; // SRG runtime (production) — the handler works; leave everything alone
        } catch (NoSuchMethodException devRuntime) {
            // MCP-named dev runtime — the handler would crash on its first invocation
        }
        MinecraftForge.EVENT_BUS.register(new FirstTickFixer());
    }

    /** One-shot: on the first client tick (post-loadComplete), pull NormalStringPool off the bus, then leave. */
    public static final class FirstTickFixer {
        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            try {
                MinecraftForge.EVENT_BUS.unregister(Class.forName("mirror.normalasm.api.NormalStringPool"));
                com.yumelium.yumelium.YumeliumMod.LOGGER.info(
                        "[compat] dev runtime: unregistered FermiumASM's SRG-hardcoded F3 stats handler"
                                + " (NormalStringPool) after loadComplete — production is unaffected");
            } catch (Throwable t) {
                com.yumelium.yumelium.YumeliumMod.LOGGER.warn("[compat] FermiumASM F3 handler removal failed", t);
            } finally {
                MinecraftForge.EVENT_BUS.unregister(this);
            }
        }
    }
}
