package com.yumelium.yumelium.client.entity;

import me.jellysquid.mods.sodium.client.SodiumClientMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.WorldVertexBufferUploader;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import net.minecraft.entity.Entity;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Capture-and-replay cache for EXPENSIVE PROCEDURAL entity geometry (Betweenlands' Sludge Menace body hull first).
 *
 * <p>Some third-party renderers rebuild large meshes into the shared Tessellator EVERY time they run —
 * {@code RenderWallLivingRoot.renderBodyHull} walks its segment chain, runs two hull-contraction solves per step and
 * re-emits the whole tube, and with the pack's ENTITY_SHADOW on it does all of that TWICE per frame (the shadow
 * caster pass runs before the camera pass). The geometry is camera-independent (verified: the method reads only the
 * entity, math helpers and the buffer), so the second build of a frame is pure waste, and successive frames differ
 * only by the animation step.</p>
 *
 * <p>The cycle: the renderer's mixin calls {@link #replayIfFresh} at the head of the hull method — a hit draws the
 * retained VBO under the current matrices and cancels the original body (skipping ALL of its CPU); a miss arms
 * {@link #beginCapture}, the method runs unmodified, and its final {@code Tessellator.draw()} is redirected into
 * {@link #captureAndDraw}, which uploads the built vertices into the entity's VBO and draws them via the vanilla
 * uploader (the pass that BUILDS also SEES the result). Freshness: geometry built THIS frame is always fresh (the
 * shadow→camera same-frame reuse — zero visual change), and beyond that an update interval scales with camera
 * distance ({@link #updateInterval}) so far-away hulls animate at a reduced rate instead of re-solving every
 * frame.</p>
 *
 * <p>Replay draws with fixed-function client arrays from the VBO — the same GL surface the vanilla uploader uses, so
 * both shader mode (a gbuffers program samples the same attribute streams) and plain mode behave identically. The
 * format is handled generically over its elements; lightmap coords use the OpenGlHelper client-texture switch. VBOs
 * are evicted when unused for a few seconds or on world unload. Registered on the Forge event bus by ClientProxy
 * (frame counter + eviction sweep only — no per-frame work beyond an int increment).</p>
 */
public final class ProceduralGeometryCache {
    public static final ProceduralGeometryCache INSTANCE = new ProceduralGeometryCache();

    /** Update-rate schedule by squared camera distance: near hulls rebuild every frame (visual parity), mid every
     * 2nd, far every 4th. The same-frame pass reuse applies at EVERY distance on top of this. */
    private static final double NEAR_SQ = 8.0 * 8.0;
    private static final double MID_SQ = 24.0 * 24.0;

    /** Entries idle longer than this are evicted (entity died / left view). */
    private static final long IDLE_EVICT_MS = 5000L;

    private static final class Entry {
        int vbo;
        int vertexCount;
        int drawMode;
        VertexFormat format;
        int builtFrame = Integer.MIN_VALUE;
        long lastUsedMs;
    }

    /** DIAG (debug_logging-gated): logs capture uploads and replays with first-vertex readbacks, so a corrupted
     * replay can be told apart from a corrupted capture. Rate-capped per session. */
    private static final int DIAG_MAX_LINES = 60;
    private static int diagLines;

    private final Map<Integer, Entry> entries = new HashMap<>();
    private final WorldVertexBufferUploader uploader = new WorldVertexBufferUploader();

    private int frame;
    private Entry capturing;

    // --- current-entity tracking (fed by MixinRenderManagerIris around renderEntityStatic; a stack because
    // passengers recurse). Lets late mixins into third-party renderers identify their entity without capturing the
    // mod's own types. Cleared every frame as crash-safety (a throwing renderer skips the RETURN pop).
    private static final java.util.ArrayDeque<Entity> CURRENT = new java.util.ArrayDeque<>();

    public static void pushCurrentEntity(Entity entity) {
        CURRENT.push(entity);
    }

    public static void popCurrentEntity() {
        if (!CURRENT.isEmpty()) {
            CURRENT.pop();
        }
    }

    /** The entity whose renderer is currently executing, or null outside renderEntityStatic. */
    public static Entity currentEntity() {
        return CURRENT.peek();
    }

    /** DIAG: the hull method was reached (fires on every path, cache on or off; debug-gated + capped). Logs the GL
     * truth of the moment — modelview translation, depth/blend/program — so a sane draw can be told from a doomed one. */
    public static void diagHullEntered() {
        if (SodiumClientMod.debugLogs() && diagLines < DIAG_MAX_LINES) {
            diagLines++;
            java.nio.FloatBuffer mv = java.nio.ByteBuffer.allocateDirect(64).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
            GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, mv);
            SodiumClientMod.logger().info(String.format(
                    "[procGeo DIAG] hull ENTER frame=%d entity=%s cacheEnabled=%s mvT=(%.2f,%.2f,%.2f) mvScale=%.3f depthTest=%s depthMask=%s blend=%s prog=%d",
                    INSTANCE.frame,
                    currentEntity() == null ? "null" : String.valueOf(currentEntity().getEntityId()),
                    enabled(),
                    mv.get(12), mv.get(13), mv.get(14), mv.get(0),
                    GL11.glIsEnabled(GL11.GL_DEPTH_TEST), GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK),
                    GL11.glIsEnabled(GL11.GL_BLEND),
                    GL11.glGetInteger(org.lwjgl.opengl.GL20.GL_CURRENT_PROGRAM)));
        }
    }

    /** DIAG: the uncached vanilla draw path ran, with the vertex count it is about to draw (pre-finish). */
    public static void diagVanillaDraw(BufferBuilder buffer) {
        if (SodiumClientMod.debugLogs() && diagLines < DIAG_MAX_LINES) {
            diagLines++;
            SodiumClientMod.logger().info("[procGeo DIAG] vanilla draw frame=" + INSTANCE.frame
                    + " count=" + buffer.getVertexCount() + " mode=" + buffer.getDrawMode());
        }
    }

    private ProceduralGeometryCache() {
    }

    private static boolean enabled() {
        try {
            return SodiumClientMod.options().yumeliumPlus.modRendererCache;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Frames between rebuilds at this camera distance; 1 = every frame (same-frame reuse still applies). */
    private static int updateInterval(Entity entity) {
        Entity view = Minecraft.getMinecraft().getRenderViewEntity();
        if (view == null) {
            return 1;
        }
        double d = view.getDistanceSq(entity);
        return d < NEAR_SQ ? 1 : (d < MID_SQ ? 2 : 4);
    }

    /**
     * Replays the entity's cached geometry if it is still fresh. @return true when the caller must SKIP its build
     * (the geometry was drawn from the cache); false arms a capture — the caller must run its build, whose
     * tessellator draw the mixin routes into {@link #captureAndDraw}.
     */
    public boolean replayIfFresh(Entity entity) {
        if (!enabled()) {
            return false;
        }
        Entry e = this.entries.get(entity.getEntityId());
        long now = System.currentTimeMillis();
        if (e != null && e.vertexCount > 0 && (this.frame - e.builtFrame) < updateInterval(entity)) {
            e.lastUsedMs = now;
            if (SodiumClientMod.debugLogs() && diagLines < DIAG_MAX_LINES) {
                diagLines++;
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, e.vbo);
                java.nio.ByteBuffer check = java.nio.ByteBuffer.allocateDirect(12).order(java.nio.ByteOrder.nativeOrder());
                GL15.glGetBufferSubData(GL15.GL_ARRAY_BUFFER, 0, check);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
                SodiumClientMod.logger().info(String.format(
                        "[procGeo DIAG] replay frame=%d built=%d vbo=%d count=%d vbo0=(%.3f,%.3f,%.3f)",
                        this.frame, e.builtFrame, e.vbo, e.vertexCount,
                        check.getFloat(0), check.getFloat(4), check.getFloat(8)));
            }
            draw(e);
            return true;
        }
        if (e == null) {
            e = new Entry();
            this.entries.put(entity.getEntityId(), e);
        }
        e.lastUsedMs = now;
        this.capturing = e;
        return false;
    }

    /** @return whether a capture is armed (the mixin's draw redirect checks before diverting). */
    public boolean captureActive() {
        return this.capturing != null;
    }

    /**
     * Consumes the armed capture: finishes the builder, snapshots its vertices into the entry's VBO, then draws them
     * through the vanilla uploader so the building pass still renders. Never throws into the renderer — a failure
     * falls back to the plain draw.
     */
    public void captureAndDraw(BufferBuilder buffer) {
        Entry e = this.capturing;
        this.capturing = null;
        if (e == null) {
            // Not armed (shouldn't happen — the redirect checks) — behave exactly like the vanilla call.
            buffer.finishDrawing();
            this.uploader.draw(buffer);
            return;
        }
        try {
            buffer.finishDrawing();
            int count = buffer.getVertexCount();
            if (count > 0) {
                VertexFormat format = buffer.getVertexFormat();
                ByteBuffer data = buffer.getByteBuffer();
                data.position(0);
                data.limit(count * format.getSize());
                if (e.vbo == 0) {
                    e.vbo = GL15.glGenBuffers();
                }
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, e.vbo);
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_DYNAMIC_DRAW);
                if (SodiumClientMod.debugLogs() && diagLines < DIAG_MAX_LINES) {
                    diagLines++;
                    // Read the first vertex back OUT of the VBO so an upload/readback mismatch is visible in the log.
                    java.nio.ByteBuffer check = java.nio.ByteBuffer.allocateDirect(12).order(java.nio.ByteOrder.nativeOrder());
                    GL15.glGetBufferSubData(GL15.GL_ARRAY_BUFFER, 0, check);
                    SodiumClientMod.logger().info(String.format(
                            "[procGeo DIAG] capture frame=%d vbo=%d count=%d mode=%d stride=%d src0=(%.3f,%.3f,%.3f) vbo0=(%.3f,%.3f,%.3f)",
                            this.frame, e.vbo, count, buffer.getDrawMode(), format.getSize(),
                            data.getFloat(0), data.getFloat(4), data.getFloat(8),
                            check.getFloat(0), check.getFloat(4), check.getFloat(8)));
                }
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
                me.jellysquid.mods.sodium.client.gl.device.RenderDevice.INSTANCE.notifyExternalStateReset();
                data.limit(data.capacity());
                e.vertexCount = count;
                e.drawMode = buffer.getDrawMode();
                e.format = format;
                e.builtFrame = this.frame;
            } else {
                e.vertexCount = 0;
            }
        } catch (Throwable t) {
            e.vertexCount = 0; // never cache a suspect snapshot
            SodiumClientMod.logger().warn("[Yumelium] procedural geometry capture failed — falling back to live builds", t);
        }
        // The building pass must still see its own geometry on screen; the uploader also reset()s the builder,
        // which is the state the redirected Tessellator.draw() is contractually expected to leave behind.
        this.uploader.draw(buffer);
    }

    /** Fixed-function client-array draw from the entry's VBO — the same GL surface the vanilla uploader uses. */
    private static void draw(Entry e) {
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, e.vbo);
        int stride = e.format.getSize();
        int offset = 0;
        for (VertexFormatElement el : e.format.getElements()) {
            switch (el.getUsage()) {
                case POSITION:
                    GL11.glVertexPointer(el.getElementCount(), el.getType().getGlConstant(), stride, offset);
                    GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
                    break;
                case UV:
                    OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit + el.getIndex());
                    GL11.glTexCoordPointer(el.getElementCount(), el.getType().getGlConstant(), stride, offset);
                    GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
                    OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
                    break;
                case COLOR:
                    GL11.glColorPointer(el.getElementCount(), el.getType().getGlConstant(), stride, offset);
                    GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
                    break;
                case NORMAL:
                    GL11.glNormalPointer(el.getType().getGlConstant(), stride, offset);
                    GL11.glEnableClientState(GL11.GL_NORMAL_ARRAY);
                    break;
                default:
                    break;
            }
            offset += el.getSize();
        }
        GL11.glDrawArrays(e.drawMode, 0, e.vertexCount);
        for (VertexFormatElement el : e.format.getElements()) {
            switch (el.getUsage()) {
                case POSITION:
                    GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
                    break;
                case UV:
                    OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit + el.getIndex());
                    GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
                    OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
                    break;
                case COLOR:
                    GL11.glDisableClientState(GL11.GL_COLOR_ARRAY);
                    // Vanilla's uploader does the same reset after drawing a colored buffer: a stale per-vertex
                    // color otherwise leaks into subsequent fixed-function draws.
                    GlStateManager.resetColor();
                    break;
                case NORMAL:
                    GL11.glDisableClientState(GL11.GL_NORMAL_ARRAY);
                    break;
                default:
                    break;
            }
        }
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        // Raw ARRAY_BUFFER binds bypass Sodium's GlStateTracker; make it forget so the next tracked bind is re-issued
        // for real (same discipline as the shadow caster pass — see RenderDevice.notifyExternalStateReset's javadoc).
        me.jellysquid.mods.sodium.client.gl.device.RenderDevice.INSTANCE.notifyExternalStateReset();
    }

    private void delete(Entry e) {
        if (e.vbo != 0) {
            GL15.glDeleteBuffers(e.vbo);
            e.vbo = 0;
        }
        e.vertexCount = 0;
    }

    // --- lifecycle (registered on the Forge event bus by ClientProxy) -----------------------------------------------

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            this.frame++;
            this.capturing = null; // a capture armed but never consumed must not leak across frames
            CURRENT.clear();       // crash-safety: a throwing renderer skips the RETURN pop
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || (this.frame & 0x3F) != 0 || this.entries.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Integer, Entry>> it = this.entries.entrySet().iterator();
        while (it.hasNext()) {
            Entry e = it.next().getValue();
            if (now - e.lastUsedMs > IDLE_EVICT_MS) {
                delete(e);
                it.remove();
            }
        }
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.getWorld().isRemote) {
            for (Entry e : this.entries.values()) {
                delete(e);
            }
            this.entries.clear();
            this.capturing = null;
        }
    }
}
