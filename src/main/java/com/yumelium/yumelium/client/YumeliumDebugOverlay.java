package com.yumelium.yumelium.client;

import com.yumelium.yumelium.Reference;
import com.yumelium.yumelium.shaders.pipeline.IrisPipeline;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Adds the "Yumelium Renderer" block (chunk counts + GPU buffer stats) and the Iris-style shader status block to the
 * left column of the F3 debug screen.
 */
public class YumeliumDebugOverlay {
    /** DIAGNOSTIC (broken 3D block icons in the hotbar while shaders are on): dumps the fixed-function GL state the
     * GUI item renderer depends on, ~1/second, AT HUD TIME — the leak the world pipeline leaves behind shows up here
     * as the state that differs from vanilla defaults (texture matrix non-identity, wrong active unit, etc.). */
    private static final boolean DIAG_HUD_GL_STATE = false;
    private static long lastStateDump;
    private static long lastPreDump;

    /** The shader engine's display name on the F3 screen (the equivalent of Iris's "[Iris]" tag). */
    private static final String SHADER_BRAND = "YumeliumShader(RainShader)";
    /** Aqua (formatting code b) — every shader status line is drawn in this colour. */
    private static final String COLOR = "§b";

    @SubscribeEvent
    public void onRenderDebugText(RenderGameOverlayEvent.Text event) {
        // The Text overlay event can fire even when the F3 debug screen is closed, so gate on it explicitly.
        if (!Minecraft.getMinecraft().gameSettings.showDebugInfo) {
            return;
        }

        SodiumWorldRenderer renderer = SodiumWorldRenderer.getInstanceNullable();

        if (renderer != null) {
            event.getLeft().addAll(renderer.getDebugStrings());
        }

        if (DIAG_HUD_GL_STATE && System.currentTimeMillis() - lastStateDump > 1000L) {
            lastStateDump = System.currentTimeMillis();
            dumpHudGlState("text-time");
        }

        // Iris-style shader status block (what real Iris adds to F3): the engine version always, plus the active
        // shader pack while shaders are on. Formatting codes are per-line (each F3 line is drawn separately), so no §r.
        IrisPipeline pipeline = IrisPipeline.instance();
        event.getLeft().add("");
        event.getLeft().add(COLOR + "[" + SHADER_BRAND + "] Version: " + Reference.VERSION);
        if (pipeline.isEnabled()) {
            event.getLeft().add(COLOR + "[" + SHADER_BRAND + "] Shaderpack: " + pipeline.activePackName());
            // Shadow pass details (the counterpart of real Iris's F3 shadow block). All counts are THIS frame's.
            int size = pipeline.shadowMapSize();
            event.getLeft().add(String.format("%s[%s] Shadow Maps: %dx%d depth + 2 color (+opaque snapshot), dist %db, bias %.4f",
                    COLOR, SHADER_BRAND, size, size, (int) IrisPipeline.shadowDistanceBlocks(), pipeline.shadowMapBias()));
            if (renderer != null) {
                event.getLeft().add(String.format("%s[%s] Shadow Terrain: C: %d (underground culled: %d, list age: %df)",
                        COLOR, SHADER_BRAND, renderer.getShadowSectionCount(), renderer.getShadowCulledUnderground(),
                        renderer.getShadowListAge()));
                String casters = pipeline.shadowEntitiesEnabled() ? "player+mobs"
                        : pipeline.shadowPlayerEnabled() ? "player" : "off";
                if (pipeline.shadowBlockEntitiesEnabled()) {
                    casters += "+BE";
                }
                String failed = renderer.getShadowTesrFailed() > 0 ? (", failed: " + renderer.getShadowTesrFailed()) : "";
                event.getLeft().add(String.format("%s[%s] Shadow Entities: E: %d (culled: %d) | BE: %d (culled: %d%s) [%s, r=%db]",
                        COLOR, SHADER_BRAND, renderer.getShadowEntitiesDrawn(), renderer.getShadowEntitiesCulled(),
                        renderer.getShadowTesrDrawn(), renderer.getShadowTesrCulled(), failed,
                        casters, (int) IrisPipeline.shadowDistanceBlocks() + 16));
                event.getLeft().add(String.format("%s[%s] Culling: camera %d/%d sections | shadow: camera-visible set + shadow box + underground (voxel volume exempt)",
                        COLOR, SHADER_BRAND, renderer.getVisibleSectionCount(), renderer.getTotalSectionCount()));
            }
        } else {
            event.getLeft().add(COLOR + "[" + SHADER_BRAND + "] Shaders: Disabled");
        }

        // EntityCulling stats proxy: the mod's own F3 line targets vanilla GuiOverlayDebug.call(), which Forge's
        // GuiOverlayDebugForge override never invokes — so we surface its public counters here instead.
        String ecStats = com.yumelium.yumelium.compat.EntityCullingCompat.debugLine();
        if (ecStats != null) {
            event.getLeft().add("§e[EntityCulling] " + ecStats);
        }

        // Nvidium block (green, like the original mod's F3 lines) — only while the backend is actually active.
        // Under shaders it is suspended by design (applyConfig), so this block disappears with the pipeline on.
        if (com.yumelium.yumelium.nvidium.NvidiumBackend.MIRROR
                || com.yumelium.yumelium.nvidium.NvidiumBackend.RENDER_TERRAIN) {
            for (String line : com.yumelium.yumelium.nvidium.NvidiumBackend.instance().debugStrings()) {
                event.getLeft().add("§a[Nvidium] " + line);
            }
        }
    }

    /** Same dump, but immediately BEFORE the hotbar renders — the corrupted state may be consumed/reset by the hotbar
     * drawing itself, which is why the text-time dump (which fires AFTER the hotbar) can look clean. */
    @SubscribeEvent
    public void onOverlayPre(RenderGameOverlayEvent.Pre event) {
        if (DIAG_HUD_GL_STATE && event.getType() == RenderGameOverlayEvent.ElementType.HOTBAR
                && System.currentTimeMillis() - lastPreDump > 1000L) {
            lastPreDump = System.currentTimeMillis();
            dumpHudGlState("PRE-hotbar");
        }
    }

    /** See {@link #DIAG_HUD_GL_STATE}. Raw GL reads only — no state is changed by the dump itself. */
    private static void dumpHudGlState(String when) {
        try {
            int realActive = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE)
                    - org.lwjgl.opengl.GL13.GL_TEXTURE0;
            int currentProgram = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL20.GL_CURRENT_PROGRAM);
            java.nio.FloatBuffer m = org.lwjgl.BufferUtils.createFloatBuffer(16);
            StringBuilder sb = new StringBuilder("[HUD GL] ").append(when)
                    .append(" program=").append(currentProgram).append(currentProgram != 0 ? " *** SHADER STILL BOUND ***" : "")
                    .append(" activeUnit=").append(realActive);
            for (int unit = 0; unit <= 1; unit++) {
                org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0 + unit);
                boolean tex2d = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_TEXTURE_2D);
                m.clear();
                org.lwjgl.opengl.GL11.glGetFloat(org.lwjgl.opengl.GL11.GL_TEXTURE_MATRIX, m);
                boolean identity = Math.abs(m.get(0) - 1.0F) < 1e-4F && Math.abs(m.get(5) - 1.0F) < 1e-4F
                        && Math.abs(m.get(12)) < 1e-4F && Math.abs(m.get(13)) < 1e-4F;
                sb.append(String.format(" | unit%d: tex2D=%b texMatrix=%s(m00=%.4f m30=%.4f)",
                        unit, tex2d, identity ? "identity" : "*** NON-IDENTITY ***", m.get(0), m.get(12)));
            }
            org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0 + realActive);
            int boundTex = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
            sb.append(" | blend=").append(org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_BLEND))
                    .append(String.format(" src=0x%X dst=0x%X",
                            org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_BLEND_SRC),
                            org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_BLEND_DST)))
                    .append(String.format(" | alpha=%b func=0x%X ref=%.4f",
                            org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_ALPHA_TEST),
                            org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_ALPHA_TEST_FUNC),
                            org.lwjgl.opengl.GL11.glGetFloat(org.lwjgl.opengl.GL11.GL_ALPHA_TEST_REF)))
                    .append(" | lighting=").append(org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_LIGHTING))
                    .append(" | cullMode=0x").append(Integer.toHexString(
                            org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_CULL_FACE_MODE)))
                    .append(" | depthRange=");
            java.nio.FloatBuffer dr = org.lwjgl.BufferUtils.createFloatBuffer(16);
            org.lwjgl.opengl.GL11.glGetFloat(org.lwjgl.opengl.GL11.GL_DEPTH_RANGE, dr);
            sb.append(String.format("[%.3f,%.3f]", dr.get(0), dr.get(1)))
                    .append(" | boundTex0=").append(boundTex);
            me.jellysquid.mods.sodium.client.SodiumClientMod.logger().info(sb.toString());
        } catch (Throwable ignored) {
        }
    }
}
