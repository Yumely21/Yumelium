package com.yumelium.yumelium.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Soft-dependency bridge to tr7zw's EntityCulling (1.12.2). Its occlusion culler cancels the RenderManager /
 * TESR-dispatcher render funnels for camera-occluded casters ({@code Cullable.isCulled} checked in
 * {@code WorldRendererMixin}/{@code BlockEntityRenderDispatcherMixin}; verified against entityculling-1.12.2-1.6.3
 * bytecode — {@code isOutOfCamera} is NOT consulted there) — the SAME funnels our shadow pass renders through.
 * Unhandled, anything hidden from the CAMERA would stop casting shadows, but the sun's view is not the camera's:
 * a mob behind a pillar still casts onto visible ground. Modern (1.16+) EntityCulling special-cases the Iris
 * shadow pass for exactly this reason; the 1.12.2 build predates Iris, so we replicate the semantic from our
 * side — clear the culled flag for the shadow draw, restore it afterwards. Camera-pass culling stays fully
 * effective (the flag is only lifted around the shadow-pass call).
 *
 * <p>All-reflective: no compile or runtime dependency — completely inert when EntityCulling is absent.</p>
 */
public final class EntityCullingCompat {
    private static final Class<?> CULLABLE;
    private static final MethodHandle IS_CULLED;
    private static final MethodHandle SET_CULLED;

    static {
        Class<?> cullable = null;
        MethodHandle isCulled = null;
        MethodHandle setCulled = null;
        try {
            cullable = Class.forName("dev.tr7zw.entityculling.access.Cullable");
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            isCulled = lookup.findVirtual(cullable, "isCulled", MethodType.methodType(boolean.class));
            setCulled = lookup.findVirtual(cullable, "setCulled", MethodType.methodType(void.class, boolean.class));
        } catch (Throwable absent) {
            cullable = null; // EntityCulling not installed — stay inert
            isCulled = null;
            setCulled = null;
        }
        CULLABLE = cullable;
        IS_CULLED = isCulled;
        SET_CULLED = setCulled;
    }

    private EntityCullingCompat() {
    }

    /**
     * Lifts EntityCulling's culled flag from a shadow caster about to draw.
     *
     * @return true when the flag was lifted and {@link #endShadowDraw} must restore it
     */
    public static boolean beginShadowDraw(Object caster) {
        if (CULLABLE == null || !CULLABLE.isInstance(caster)) {
            return false;
        }
        try {
            if ((boolean) IS_CULLED.invoke(caster)) {
                SET_CULLED.invoke(caster, false);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Restores the culled flag lifted by {@link #beginShadowDraw} so camera culling continues unaffected. */
    public static void endShadowDraw(Object caster) {
        try {
            SET_CULLED.invoke(caster, true);
        } catch (Throwable ignored) {
        }
    }

    // --- F3 stats proxy -------------------------------------------------------------------------------------------
    // EntityCulling's own DebugHudMixin injects into the VANILLA GuiOverlayDebug.call(), but Forge 1.12.2 renders F3
    // through its GuiOverlayDebugForge subclass which overrides renderDebugInfoLeft() and never calls call() — the
    // mod's stats line can never appear on Forge. Its counters are public fields though, so we surface them in the
    // Yumelium F3 block instead.

    private static final java.lang.reflect.Field EC_INSTANCE;
    private static final java.lang.reflect.Field EC_RENDERED_E;
    private static final java.lang.reflect.Field EC_SKIPPED_E;
    private static final java.lang.reflect.Field EC_RENDERED_BE;
    private static final java.lang.reflect.Field EC_SKIPPED_BE;

    static {
        java.lang.reflect.Field inst = null, re = null, se = null, rbe = null, sbe = null;
        try {
            Class<?> modBase = Class.forName("dev.tr7zw.entityculling.EntityCullingModBase");
            inst = modBase.getField("instance");
            re = modBase.getField("renderedEntities");
            se = modBase.getField("skippedEntities");
            rbe = modBase.getField("renderedBlockEntities");
            sbe = modBase.getField("skippedBlockEntities");
        } catch (Throwable absent) {
            inst = null;
        }
        EC_INSTANCE = inst;
        EC_RENDERED_E = inst == null ? null : re;
        EC_SKIPPED_E = inst == null ? null : se;
        EC_RENDERED_BE = inst == null ? null : rbe;
        EC_SKIPPED_BE = inst == null ? null : sbe;
    }

    /** @return EntityCulling's frame stats for the F3 screen, or null when the mod is absent/not initialized. */
    public static String debugLine() {
        if (EC_INSTANCE == null) {
            return null;
        }
        try {
            Object inst = EC_INSTANCE.get(null);
            if (inst == null) {
                return null;
            }
            return "E: " + EC_RENDERED_E.getInt(inst) + " rendered / " + EC_SKIPPED_E.getInt(inst) + " culled"
                    + " | BE: " + EC_RENDERED_BE.getInt(inst) + " rendered / " + EC_SKIPPED_BE.getInt(inst) + " culled";
        } catch (Throwable ignored) {
            return null;
        }
    }
}
