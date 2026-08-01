package com.yumelium.yumelium.nvidium;

import com.yumelium.yumelium.nvidium.gl.AddressableDeviceBuffer;
import com.yumelium.yumelium.nvidium.gl.BufferArena;
import com.yumelium.yumelium.nvidium.gl.NvidiumGl;
import com.yumelium.yumelium.nvidium.gl.PersistentMappedBuffer;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import org.embeddedt.embeddium.render.chunk.sorting.TranslucentQuadAnalyzer;
import me.jellysquid.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import me.jellysquid.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegion;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.util.iterator.ByteIterator;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.system.MemoryUtil;

import java.util.Iterator;
import java.util.Map;

/**
 * Nvidium port — Milestones M2/M3. Keeps every built section's compact vertex geometry GPU-resident in a bindless
 * arena, split by render pass (SOLID / CUTOUT / TRANSLUCENT), and builds per-pass <b>work-item</b> tables (each item =
 * one mesh-shader workgroup handling up to {@link #QUADS_PER_WG} quads) in a bindless metadata buffer that
 * {@link NvidiumRasterizer} draws — replacing vanilla terrain entirely.
 */
public final class NvidiumBackend {
    private static final NvidiumBackend INSTANCE = new NvidiumBackend();

    public static volatile boolean MIRROR = false;
    public static volatile boolean RENDER_TERRAIN = false;
    /** Set once the capability probe + self-test pass; Nvidium may run only if this AND the user setting are true. */
    public static volatile boolean available = false;

    public static final int QUADS_PER_WG = 32;
    public static final int PASS_SOLID = 0;
    public static final int PASS_CUTOUT = 1;
    public static final int PASS_TRANSLUCENT = 2;
    private static final TerrainRenderPass[] PASSES = {
            DefaultTerrainRenderPasses.SOLID, DefaultTerrainRenderPasses.CUTOUT, DefaultTerrainRenderPasses.TRANSLUCENT
    };

    private static final int BYTES_PER_QUAD = 4 * 20;
    private static final long DEFAULT_GEOMETRY_BYTES = 2048L * 1024 * 1024; // fallback max if the option can't be read
    private static final long INITIAL_GEOMETRY_BYTES = 512L * 1024 * 1024;  // arena starts here and grows on demand
    private static final long STAGING_BYTES = 16L * 1024 * 1024;
    private static final long METADATA_BYTES = 16L * 1024 * 1024;
    private static final long SORT_INDEX_BYTES = 24L * 1024 * 1024; // per-frame translucent per-quad sort indices (FULL)
    private static final int META_INTS = 8; // {byteOffset, numQuads, ox, oy, oz, sortOffset, _, _}
    private static final int META_BYTES = META_INTS * 4;

    private boolean initialized;
    private AddressableDeviceBuffer geometry;
    private AddressableDeviceBuffer metadata;
    private BufferArena arena;
    private PersistentMappedBuffer staging;
    private PersistentMappedBuffer metaStaging;
    private long stagingCursor;
    private long geometryBytes;      // current allocated geometry size (grows on demand)
    private long maxGeometryBytes;   // configured cap (Yumelium Plus → Nvidium buffer size)
    // Work-item / sort tables also grow on demand (kept across reset() so a dimension change doesn't re-grow).
    // The old FIXED sizes silently truncated the translucent pass — emitted last — once the visible set got big
    // enough: in the flat, open Betweenlands swamp all water/glass flickered out per-frame (2026-07-28).
    private long metadataBytes = METADATA_BYTES;
    private long sortIndexBytes = SORT_INDEX_BYTES;

    // key -> {arenaOffset, totalSize, ox, oy, oz, solidQuads, cutoutQuads, translucentQuads}
    private final Long2ObjectLinkedOpenHashMap<int[]> sections = new Long2ObjectLinkedOpenHashMap<>();
    private boolean metaDirty;
    private final int[] passItemBase = new int[3];
    private final int[] passItemCount = new int[3];

    // Visible-set / per-frame rebuild bookkeeping. We consume Sodium's occlusion-culled render list; it is a fresh
    // object each time Sodium recomputes the visible set (camera moved / occlusion changed), so identity == unchanged.
    private SortedRenderLists lastLists;
    private boolean synced;
    private boolean lastSortSections;
    private boolean lastFullSort;
    private int stagingSlot;
    private long syncLogCounter;

    // Reusable scratch: the visible resident sections this frame, and (for translucent) their back-to-front sort keys
    // (squared distance in high 32 bits, scratch index in low 32 bits).
    private int[][] visibleScratch = new int[512][];
    private float[][] visibleCenters = new float[512][];   // parallel: translucent quad centers, or null
    private int[][] tlSections = new int[256][];
    private float[][] tlCenters = new float[256][];         // parallel to tlSections
    private long[] tlKeys = new long[256];

    // FULL translucent sort (per-quad, back-to-front): Sodium-computed section-local quad centers kept per section,
    // + a per-frame GPU buffer of sorted quad indices the mesh shader dereferences. Only built in sort mode FULL.
    private AddressableDeviceBuffer sortIndexBuffer;
    private PersistentMappedBuffer sortStaging;
    private final Long2ObjectOpenHashMap<float[]> translucentCenters = new Long2ObjectOpenHashMap<>();
    private long[] quadKeys = new long[1024];
    private int sortStagingSlot;
    private boolean sortActive; // whether this frame's sync wrote per-quad sort indices (FULL)

    private long cumulativeBytes;
    private long mirrorCalls;
    private boolean arenaFullLogged;

    private NvidiumBackend() {
    }

    public static NvidiumBackend instance() {
        return INSTANCE;
    }

    /**
     * F3 debug lines (no prefix/colour — the overlay adds those). Render-thread only, like every other entry point
     * of this class. Cheap: plain field/collection-size reads, no GL queries.
     */
    public java.util.List<String> debugStrings() {
        java.util.List<String> list = new java.util.ArrayList<>(4);
        if (!this.initialized) {
            list.add("state: " + (available ? "available, not yet initialized" : "unavailable (no mesh-shader support)"));
            return list;
        }
        list.add("mode: " + (RENDER_TERRAIN ? "mesh-shader terrain" : MIRROR ? "mirror only" : "off")
                + (shadersActive ? " (suspended: shaders active)" : "")
                + (RENDER_TERRAIN ? ", cull: " + (NvidiumRasterizer.instance().gpuCullingActive() ? "gpu-frustum" : "cpu") : ""));
        list.add(String.format("geometry: %dMB used / %dMB alloc (cap %dMB), resident sections: %d",
                this.arena == null ? 0 : this.arena.used() >> 20, this.geometryBytes >> 20,
                this.maxGeometryBytes >> 20, this.sections.size()));
        list.add(String.format("frame items S/C/T: %d/%d/%d, sort: %s",
                this.passItemCount[PASS_SOLID], this.passItemCount[PASS_CUTOUT], this.passItemCount[PASS_TRANSLUCENT],
                this.lastFullSort ? "full" : this.lastSortSections ? "sections" : "off"));
        list.add(String.format("mirrored: %d uploads, %.2fGB cumulative", this.mirrorCalls,
                this.cumulativeBytes / 1073741824.0D));
        return list;
    }

    private static void log(String s) {
        SodiumClientMod.logger().info("[Nvidium M2] " + s);
    }

    private void ensureInit() {
        if (this.initialized) {
            return;
        }
        this.maxGeometryBytes = configuredMaxGeometryBytes();
        this.geometryBytes = Math.min(INITIAL_GEOMETRY_BYTES, this.maxGeometryBytes);
        this.geometry = new AddressableDeviceBuffer(this.geometryBytes);
        this.metadata = new AddressableDeviceBuffer(this.metadataBytes);
        this.arena = new BufferArena(this.geometryBytes);
        this.staging = new PersistentMappedBuffer(STAGING_BYTES);
        this.metaStaging = new PersistentMappedBuffer(this.metadataBytes);
        this.sortIndexBuffer = new AddressableDeviceBuffer(this.sortIndexBytes);
        this.sortStaging = new PersistentMappedBuffer(this.sortIndexBytes);
        this.initialized = true;
        log("initialized: geometry=" + (this.geometryBytes >> 20) + "MB (max " + (this.maxGeometryBytes >> 20)
                + "MB) gpuAddress=0x" + Long.toHexString(this.geometry.gpuAddress()) + " metadata gpuAddress=0x"
                + Long.toHexString(this.metadata.gpuAddress()));
    }

    private static long configuredMaxGeometryBytes() {
        try {
            return SodiumClientMod.options().yumeliumPlus.nvidiumBufferSize.bytes();
        } catch (Throwable t) {
            return DEFAULT_GEOMETRY_BYTES;
        }
    }

    /** GL_NVX_gpu_memory_info total-dedicated-VRAM constant (value is reported in KiB). */
    private static final int GPU_MEMORY_INFO_DEDICATED_VIDMEM_NVX = 0x9047;
    private static long cachedDedicatedVramBytes;

    /**
     * The GPU's total dedicated VRAM, queried once from the driver — the cap behind the "Unlimited" buffer-size
     * option, so the arena may grow to the physical memory but never past it. Needs a live GL context (only called
     * from backend init on the render thread). Nvidium requires NVIDIA, where the NVX extension is always present;
     * the 4 GB fallback covers a hypothetical driver that hides it.
     */
    public static long dedicatedVramBytes() {
        if (cachedDedicatedVramBytes == 0) {
            long bytes = 0;
            try {
                if (org.lwjgl.opengl.GL.getCapabilities().GL_NVX_gpu_memory_info) {
                    bytes = (long) org.lwjgl.opengl.GL11C.glGetInteger(GPU_MEMORY_INFO_DEDICATED_VIDMEM_NVX) * 1024L;
                }
            } catch (Throwable ignored) {
                // no context yet / exotic driver — fall through to the default below
            }
            cachedDedicatedVramBytes = bytes > 0 ? bytes : 4096L * 1024 * 1024;
            log("dedicated VRAM = " + (cachedDedicatedVramBytes >> 20) + "MB"
                    + (bytes > 0 ? " (NVX query)" : " (query unavailable — assuming 4GB)"));
        }
        return cachedDedicatedVramBytes;
    }

    /** @return true if translucent sections should be ordered back-to-front (sort mode SECTION or FULL, not OFF). */
    private static boolean translucentSortEnabled() {
        try {
            return SodiumClientMod.options().yumeliumPlus.nvidiumTranslucentSort
                    != me.jellysquid.mods.sodium.client.gui.SodiumGameOptions.NvidiumSortMode.OFF;
        } catch (Throwable t) {
            return true;
        }
    }

    /** @return true for sort mode FULL (per-quad within-section back-to-front sorting via the sort-index buffer). */
    private static boolean fullSortEnabled() {
        try {
            return SodiumClientMod.options().yumeliumPlus.nvidiumTranslucentSort
                    == me.jellysquid.mods.sodium.client.gui.SodiumGameOptions.NvidiumSortMode.FULL;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * When the Iris/Oculus shader pipeline is active, Nvidium is disabled — the two terrain backends are mutually
     * exclusive, and shaders render terrain through Sodium's chunk shader (which the pack's gbuffers_terrain replaces).
     */
    public static volatile boolean shadersActive = false;

    /** Recompute {@link #MIRROR}/{@link #RENDER_TERRAIN} from capability ({@link #available}) AND the user setting. */
    public static void applyConfig() {
        boolean enabled;
        try {
            enabled = available && SodiumClientMod.options().yumeliumPlus.nvidiumEnabled;
        } catch (Throwable t) {
            enabled = available;
        }
        if (shadersActive) {
            enabled = false; // Iris/Oculus shaders own the terrain path; Nvidium and shaders never run together.
        }
        // 2026-07-27 audit: the mesh-shader path decodes the 20-byte COMPACT vertex layout; with the user-facing
        // "Use Compact Vertex Format" option OFF, Sodium meshes in the 28-byte vanilla-like float format — mirroring
        // those buffers would be total geometry corruption. Disable Nvidium outright in that mode (applyConfig runs
        // on every renderer reload + option change, followed by reset(), so toggling is clean).
        try {
            if (SodiumClientMod.canUseVanillaVertices()) {
                enabled = false;
            }
        } catch (Throwable t) {
            // options unavailable this early — keep enabled as computed
        }
        MIRROR = enabled;
        RENDER_TERRAIN = enabled;
    }

    /** Tear down all GPU buffers + resident geometry so the next mirror re-initialises with the current settings. */
    public void reset() {
        if (!this.initialized) {
            return;
        }
        try {
            if (this.geometry != null) this.geometry.delete();
            if (this.metadata != null) this.metadata.delete();
            if (this.staging != null) this.staging.delete();
            if (this.metaStaging != null) this.metaStaging.delete();
            if (this.sortIndexBuffer != null) this.sortIndexBuffer.delete();
            if (this.sortStaging != null) this.sortStaging.delete();
        } catch (Throwable t) {
            log("reset: buffer delete error: " + t);
        }
        this.geometry = null;
        this.metadata = null;
        this.arena = null;
        this.staging = null;
        this.metaStaging = null;
        this.sortIndexBuffer = null;
        this.sortStaging = null;
        this.translucentCenters.clear();
        this.sections.clear();
        this.stagingCursor = 0L;
        this.metaDirty = false;
        this.synced = false;
        this.sortActive = false;
        this.lastLists = null;
        this.arenaFullLogged = false;
        this.initialized = false;
    }

    /** Grow the geometry buffer (up to {@link #maxGeometryBytes}) so a new section can fit. @return true if it grew. */
    private boolean growGeometry() {
        if (this.geometryBytes >= this.maxGeometryBytes) {
            return false;
        }
        long newSize = Math.min(this.geometryBytes * 2L, this.maxGeometryBytes);
        if (newSize <= this.geometryBytes) {
            return false;
        }
        AddressableDeviceBuffer newGeometry = new AddressableDeviceBuffer(newSize);
        // Copy the existing geometry into the new buffer (arena offsets are preserved), then swap.
        GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, this.geometry.id());
        GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, newGeometry.id());
        GL31C.glCopyBufferSubData(GL31C.GL_COPY_READ_BUFFER, GL31C.GL_COPY_WRITE_BUFFER, 0L, 0L, this.geometryBytes);
        GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, 0);
        GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, 0);
        this.geometry.delete();
        this.geometry = newGeometry;
        this.arena.grow(newSize);
        log("geometry arena grew " + (this.geometryBytes >> 20) + "MB -> " + (newSize >> 20) + "MB");
        this.geometryBytes = newSize;
        return true;
    }

    public boolean isReady() {
        return this.initialized;
    }

    public long geometryAddress() {
        return this.geometry.gpuAddress();
    }

    public long metadataAddress() {
        return this.metadata.gpuAddress();
    }

    public long sortIndexAddress() {
        return this.sortIndexBuffer != null ? this.sortIndexBuffer.gpuAddress() : 0L;
    }

    /** @return true if the last sync wrote per-quad translucent sort indices (FULL mode) for the mesh shader to use. */
    public boolean sortActive() {
        return this.sortActive;
    }

    public int passItemBase(int pass) {
        return this.passItemBase[pass];
    }

    public int passItemCount(int pass) {
        return this.passItemCount[pass];
    }

    private static int lengthOf(BuiltSectionMeshParts mp) {
        return (mp != null && mp.getVertexData() != null) ? mp.getVertexData().getLength() : 0;
    }

    public void mirror(RenderSection section, Map<TerrainRenderPass, BuiltSectionMeshParts> meshes) {
        if (!MIRROR || !NvidiumGl.SUPPORTED || section == null || meshes == null || meshes.isEmpty()) {
            return;
        }
        try {
            ensureInit();

            int sx = section.getChunkX();
            int sy = section.getChunkY();
            int sz = section.getChunkZ();
            long key = pack(sx, sy, sz);
            freeSection(key);

            int solidBytes = lengthOf(meshes.get(PASSES[PASS_SOLID]));
            int cutoutBytes = lengthOf(meshes.get(PASSES[PASS_CUTOUT]));
            int translucentBytes = lengthOf(meshes.get(PASSES[PASS_TRANSLUCENT]));
            int total = solidBytes + cutoutBytes + translucentBytes;
            if (total == 0 || total > this.staging.size()) {
                return;
            }

            long offset = this.arena.alloc(total);
            while (offset < 0L && growGeometry()) {
                offset = this.arena.alloc(total);
            }
            if (offset < 0L) {
                if (!this.arenaFullLogged) {
                    log("geometry arena full at max " + (this.maxGeometryBytes >> 20) + "MB — a few distant sections may "
                            + "not draw; raise the Nvidium buffer size in Yumelium Plus if needed");
                    this.arenaFullLogged = true;
                }
                return;
            }

            if (this.stagingCursor + total > this.staging.size()) {
                // 2026-07-27 audit: glFlush only SUBMITS the queued glCopyBufferSubData commands — it does not wait
                // for them, so rewinding the persistently-mapped staging ring could overwrite memory the GPU hasn't
                // consumed yet. Drain with a real fence (wrap is rare, so the stall is acceptable).
                long fence = org.lwjgl.opengl.GL32C.glFenceSync(org.lwjgl.opengl.GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
                org.lwjgl.opengl.GL32C.glClientWaitSync(fence, org.lwjgl.opengl.GL32C.GL_SYNC_FLUSH_COMMANDS_BIT, Long.MAX_VALUE);
                org.lwjgl.opengl.GL32C.glDeleteSync(fence);
                this.stagingCursor = 0L;
            }
            long stageOffset = this.stagingCursor;

            // Copy the passes in order (solid, cutout, translucent) so each pass is a contiguous range.
            long cursor = this.staging.address() + stageOffset;
            cursor = copyPass(meshes.get(PASSES[PASS_SOLID]), cursor);
            cursor = copyPass(meshes.get(PASSES[PASS_CUTOUT]), cursor);
            copyPass(meshes.get(PASSES[PASS_TRANSLUCENT]), cursor);
            this.stagingCursor += total;
            copyToDevice(this.staging.id(), this.geometry, stageOffset, offset, total);

            this.sections.put(key, new int[]{
                    (int) offset, total, sx * 16, sy * 16, sz * 16,
                    solidBytes / BYTES_PER_QUAD, cutoutBytes / BYTES_PER_QUAD, translucentBytes / BYTES_PER_QUAD
            });

            // Keep Sodium's per-quad centers (section-local) for FULL within-section translucent sorting. NONE-level
            // sections are coplanar (no sort needed) → no centers stored; drawn in identity order under FULL.
            BuiltSectionMeshParts tp = meshes.get(PASSES[PASS_TRANSLUCENT]);
            if (tp != null && tp.getSortState() != null
                    && tp.getSortState().level() != TranslucentQuadAnalyzer.Level.NONE
                    && tp.getSortState().centers() != null) {
                this.translucentCenters.put(key, tp.getSortState().centers());
            }

            this.metaDirty = true;
            this.cumulativeBytes += total;

            if ((++this.mirrorCalls & 511L) == 0L) {
                log("resident sections=" + this.sections.size() + " arenaUsed=" + (this.arena.used() >> 20)
                        + "MB cumulativeUploaded=" + (this.cumulativeBytes >> 20) + "MB");
            }
        } catch (Throwable t) {
            log("mirror error (disabling): " + t);
            MIRROR = false;
            RENDER_TERRAIN = false;
        }
    }

    private static long copyPass(BuiltSectionMeshParts mp, long dstAddr) {
        if (mp == null || mp.getVertexData() == null) {
            return dstAddr;
        }
        int len = mp.getVertexData().getLength();
        MemoryUtil.memCopy(MemoryUtil.memAddress(mp.getVertexData().getDirectBuffer()), dstAddr, len);
        return dstAddr + len;
    }

    public void remove(int x, int y, int z) {
        if (!this.initialized) {
            return;
        }
        freeSection(pack(x, y, z));
    }

    private void freeSection(long key) {
        int[] a = this.sections.remove(key);
        if (a != null) {
            this.arena.free(a[0], a[1]);
            this.translucentCenters.remove(key);
            this.metaDirty = true;
        }
    }

    /**
     * Rebuild the per-pass work-item tables (contiguous: solid items, then cutout, then translucent) from Sodium's
     * already frustum+occlusion-culled visible render list, and upload them for the draw. Rebuilds only when Sodium
     * recomputed the visible set (its {@link SortedRenderLists} is a fresh object each recompute → identity compare) or
     * our geometry changed ({@link #metaDirty}) — so the two per-frame draw calls (solid & translucent) share one build,
     * a static camera costs nothing, and we inherit Sodium's occlusion culling for free (no redundant frustum scan of
     * all resident sections). Staging is triple-buffered to avoid overwriting a copy still in flight.
     */
    public void syncMetadataForDraw(SortedRenderLists lists, double camX, double camY, double camZ) {
        if (!this.initialized) {
            return;
        }
        boolean sortSections = translucentSortEnabled();
        boolean fullSort = fullSortEnabled();
        // Skip the rebuild when nothing that affects the work-item table changed (same visible set + geometry + sort
        // mode). A static camera keeps Sodium's list object, so this makes standing still free — but a live sort-mode
        // change still forces a rebuild so it takes effect immediately.
        if (this.synced && !this.metaDirty && lists == this.lastLists
                && sortSections == this.lastSortSections && fullSort == this.lastFullSort) {
            return;
        }
        this.lastSortSections = sortSections;
        this.lastFullSort = fullSort;

        // Collect this frame's visible resident sections once (Sodium already frustum+occlusion culled them).
        int vis = collectVisibleSections(lists);

        // Demand-size the tables BEFORE emitting: translucent is emitted last, so an overflow silently truncates
        // exactly the translucent pass. Growth is a rare one-off per world/dimension scale-up.
        long requiredItems = 0;
        long requiredSortInts = 0;
        for (int i = 0; i < vis; i++) {
            int[] s = this.visibleScratch[i];
            for (int pass = PASS_SOLID; pass <= PASS_TRANSLUCENT; pass++) {
                int quads = s[5 + pass];
                if (quads != 0) {
                    requiredItems += (quads + QUADS_PER_WG - 1) / QUADS_PER_WG;
                }
            }
            requiredSortInts += s[5 + PASS_TRANSLUCENT];
        }
        ensureMetadataCapacity(requiredItems);
        if (fullSort) {
            ensureSortIndexCapacity(requiredSortInts);
        }

        long slotBytes = this.metadataBytes / 3;
        long base = this.metaStaging.address() + (long) this.stagingSlot * slotBytes;
        int maxItems = (int) (slotBytes / META_BYTES);
        int item = 0;

        // Solid then cutout: opaque, order-independent.
        for (int pass = PASS_SOLID; pass <= PASS_CUTOUT; pass++) {
            this.passItemBase[pass] = item;
            for (int i = 0; i < vis; i++) {
                int[] s = this.visibleScratch[i];
                if (s[5 + pass] != 0) {
                    item = emitSectionItems(base, maxItems, item, s, pass);
                }
            }
            this.passItemCount[pass] = item - this.passItemBase[pass];
        }

        // Translucent: blend far-to-near — gather visible translucent sections (+ their per-quad centers), sort SECTIONS
        // by DESCENDING distance (SECTION/FULL modes). In FULL mode each section's quads are ALSO sorted back-to-front and
        // the mesh shader dereferences a per-quad sort-index buffer (byteOffset = section translucent start, work-item[5]
        // = sort offset). In SECTION/OFF, quads are emitted in buffer order (byteOffset = batch start).
        this.passItemBase[PASS_TRANSLUCENT] = item;
        int n = 0;
        for (int i = 0; i < vis; i++) {
            int[] s = this.visibleScratch[i];
            if (s[5 + PASS_TRANSLUCENT] == 0) {
                continue;
            }
            if (n >= this.tlSections.length) {
                growScratch(false, n);
            }
            this.tlSections[n] = s;
            this.tlCenters[n] = this.visibleCenters[i];
            float cx = (float) (s[2] + 8 - camX);
            float cy = (float) (s[3] + 8 - camY);
            float cz = (float) (s[4] + 8 - camZ);
            float distSq = cx * cx + cy * cy + cz * cz;
            this.tlKeys[n] = ((long) Float.floatToRawIntBits(distSq) << 32) | (n & 0xFFFFFFFFL);
            n++;
        }
        if (sortSections) {
            java.util.Arrays.sort(this.tlKeys, 0, n); // ascending distance; iterate reversed for far→near
        }

        this.sortActive = false;
        if (fullSort) {
            long sortSlotBytes = this.sortIndexBytes / 3;
            long sortBase = this.sortStaging.address() + (long) this.sortStagingSlot * sortSlotBytes;
            int maxSortInts = (int) (sortSlotBytes / 4);
            int sortCount = 0;
            for (int order = 0; order < n && item < maxItems; order++) {
                int idx = sortSections ? (int) (this.tlKeys[n - 1 - order] & 0xFFFFFFFFL) : order;
                int[] s = this.tlSections[idx];
                if (sortCount + s[7] > maxSortInts) {
                    break; // sort buffer full (astronomically unlikely) — stop adding translucent this frame
                }
                int secSortBase = sortCount;
                sortCount = writeQuadSortIndices(sortBase, sortCount, s, this.tlCenters[idx], camX, camY, camZ);
                item = emitTranslucentFullItems(base, maxItems, item, s, secSortBase);
            }
            if (sortCount > 0) {
                copyToDevice(this.sortStaging.id(), this.sortIndexBuffer,
                        (long) this.sortStagingSlot * sortSlotBytes, 0L, (long) sortCount * 4);
                this.sortStagingSlot = (this.sortStagingSlot + 1) % 3;
                this.sortActive = true;
            }
        } else {
            for (int order = 0; order < n && item < maxItems; order++) {
                int idx = sortSections ? (int) (this.tlKeys[n - 1 - order] & 0xFFFFFFFFL) : order;
                item = emitSectionItems(base, maxItems, item, this.tlSections[idx], PASS_TRANSLUCENT);
            }
        }
        this.passItemCount[PASS_TRANSLUCENT] = item - this.passItemBase[PASS_TRANSLUCENT];

        long bytes = (long) item * META_BYTES;
        if (bytes > 0) {
            copyToDevice(this.metaStaging.id(), this.metadata, (long) this.stagingSlot * slotBytes, 0L, bytes);
        }
        this.stagingSlot = (this.stagingSlot + 1) % 3;

        this.lastLists = lists;
        this.metaDirty = false;
        this.synced = true;

        if ((++this.syncLogCounter & 255L) == 0L) {
            log("occlusion cull (from Sodium): visible=" + vis + "/" + this.sections.size() + " sections, workItems="
                    + item + " (solid=" + this.passItemCount[PASS_SOLID] + " cutout=" + this.passItemCount[PASS_CUTOUT]
                    + " translucent=" + this.passItemCount[PASS_TRANSLUCENT] + ")");
        }
    }

    /**
     * Grows the triple-buffered work-item table when the visible set needs more items than a slot holds.
     * glFinish first: queued prior frames still dereference the OLD table by raw GPU address (bindless), so the
     * delete must not happen while they are in flight. The hitch is acceptable — growth is a rare one-off.
     */
    private void ensureMetadataCapacity(long items) {
        long needed = items * META_BYTES * 3L;
        if (needed <= this.metadataBytes) {
            return;
        }
        long newBytes = this.metadataBytes;
        while (newBytes < needed) {
            newBytes <<= 1;
        }
        org.lwjgl.opengl.GL11C.glFinish();
        this.metadata.delete();
        this.metaStaging.delete();
        this.metadataBytes = newBytes;
        this.metadata = new AddressableDeviceBuffer(newBytes);
        this.metaStaging = new PersistentMappedBuffer(newBytes);
        this.stagingSlot = 0;
        log("work-item table grown to " + (newBytes >> 20) + "MB ("
                + ((newBytes / 3) / META_BYTES) + " items/frame)");
    }

    /** Same on-demand growth for the FULL-sort per-quad index table (one int per visible translucent quad). */
    private void ensureSortIndexCapacity(long ints) {
        long needed = ints * 4L * 3L;
        if (needed <= this.sortIndexBytes) {
            return;
        }
        long newBytes = this.sortIndexBytes;
        while (newBytes < needed) {
            newBytes <<= 1;
        }
        org.lwjgl.opengl.GL11C.glFinish();
        this.sortIndexBuffer.delete();
        this.sortStaging.delete();
        this.sortIndexBytes = newBytes;
        this.sortIndexBuffer = new AddressableDeviceBuffer(newBytes);
        this.sortStaging = new PersistentMappedBuffer(newBytes);
        this.sortStagingSlot = 0;
        log("translucent sort table grown to " + (newBytes >> 20) + "MB");
    }

    /** Gather the resident sections in Sodium's visible render list into {@link #visibleScratch}; returns the count. */
    private int collectVisibleSections(SortedRenderLists lists) {
        int vis = 0;
        for (Iterator<ChunkRenderList> it = lists.iterator(); it.hasNext(); ) {
            ChunkRenderList entry = it.next();
            RenderRegion region = entry.getRegion();
            ByteIterator sectionIterator = entry.sectionsWithGeometryIterator(false);
            if (sectionIterator == null) {
                continue;
            }
            while (sectionIterator.hasNext()) {
                RenderSection rs = region.getSection(sectionIterator.nextByteAsInt());
                if (rs == null) {
                    continue;
                }
                long key = pack(rs.getChunkX(), rs.getChunkY(), rs.getChunkZ());
                int[] s = this.sections.get(key);
                if (s == null) {
                    continue; // visible but not (yet) resident in the Nvidium arena
                }
                if (vis >= this.visibleScratch.length) {
                    growScratch(true, vis);
                }
                this.visibleScratch[vis] = s;
                this.visibleCenters[vis] = this.translucentCenters.get(key); // null when no per-quad sort data
                vis++;
            }
        }
        return vis;
    }

    /** Emit the work-items (one per {@link #QUADS_PER_WG}-quad batch) for one section's pass into the staging buffer. */
    private int emitSectionItems(long base, int maxItems, int item, int[] s, int pass) {
        int passQuads = s[5 + pass];
        int passByteStart = s[0]
                + (pass >= 1 ? s[5] * BYTES_PER_QUAD : 0)
                + (pass >= 2 ? s[6] * BYTES_PER_QUAD : 0);
        int batches = (passQuads + QUADS_PER_WG - 1) / QUADS_PER_WG;
        for (int b = 0; b < batches && item < maxItems; b++) {
            int q0 = b * QUADS_PER_WG;
            int num = Math.min(QUADS_PER_WG, passQuads - q0);
            long p = base + (long) item * META_BYTES;
            MemoryUtil.memPutInt(p, passByteStart + q0 * BYTES_PER_QUAD);
            MemoryUtil.memPutInt(p + 4, num);
            MemoryUtil.memPutInt(p + 8, s[2]);
            MemoryUtil.memPutInt(p + 12, s[3]);
            MemoryUtil.memPutInt(p + 16, s[4]);
            MemoryUtil.memPutInt(p + 20, 0);
            MemoryUtil.memPutInt(p + 24, 0);
            MemoryUtil.memPutInt(p + 28, 0);
            item++;
        }
        return item;
    }

    /**
     * Write one section's translucent quad indices, sorted back-to-front by camera distance, into the sort-index staging
     * at [sortCount, sortCount+nq). Uses Sodium's section-local per-quad centers; when absent (coplanar section) writes
     * identity order. @return the new sortCount.
     */
    private int writeQuadSortIndices(long sortBase, int sortCount, int[] s, float[] centers,
                                     double camX, double camY, double camZ) {
        int nq = s[7];
        long dst = sortBase + (long) sortCount * 4L;
        if (centers == null || centers.length < nq * 3) {
            for (int q = 0; q < nq; q++) {
                MemoryUtil.memPutInt(dst + (long) q * 4L, q);
            }
            return sortCount + nq;
        }
        if (this.quadKeys.length < nq) {
            this.quadKeys = new long[Integer.highestOneBit(nq) << 1];
        }
        float clx = (float) (camX - s[2]); // camera in section-local space (centers are section-local)
        float cly = (float) (camY - s[3]);
        float clz = (float) (camZ - s[4]);
        for (int q = 0; q < nq; q++) {
            float dx = centers[q * 3] - clx;
            float dy = centers[q * 3 + 1] - cly;
            float dz = centers[q * 3 + 2] - clz;
            float distSq = dx * dx + dy * dy + dz * dz;
            this.quadKeys[q] = ((long) Float.floatToRawIntBits(distSq) << 32) | (q & 0xFFFFFFFFL);
        }
        java.util.Arrays.sort(this.quadKeys, 0, nq);
        for (int k = 0; k < nq; k++) { // reversed → far→near
            int q = (int) (this.quadKeys[nq - 1 - k] & 0xFFFFFFFFL);
            MemoryUtil.memPutInt(dst + (long) k * 4L, q);
        }
        return sortCount + nq;
    }

    /** FULL-mode translucent work-items: byteOffset = section translucent start, work-item[5] = per-batch sort offset. */
    private int emitTranslucentFullItems(long base, int maxItems, int item, int[] s, int secSortBase) {
        int nq = s[7];
        int translucentByteStart = s[0] + s[5] * BYTES_PER_QUAD + s[6] * BYTES_PER_QUAD;
        int batches = (nq + QUADS_PER_WG - 1) / QUADS_PER_WG;
        for (int b = 0; b < batches && item < maxItems; b++) {
            int q0 = b * QUADS_PER_WG;
            int num = Math.min(QUADS_PER_WG, nq - q0);
            long p = base + (long) item * META_BYTES;
            MemoryUtil.memPutInt(p, translucentByteStart);
            MemoryUtil.memPutInt(p + 4, num);
            MemoryUtil.memPutInt(p + 8, s[2]);
            MemoryUtil.memPutInt(p + 12, s[3]);
            MemoryUtil.memPutInt(p + 16, s[4]);
            MemoryUtil.memPutInt(p + 20, secSortBase + q0);
            MemoryUtil.memPutInt(p + 24, 0);
            MemoryUtil.memPutInt(p + 28, 0);
            item++;
        }
        return item;
    }

    private void growScratch(boolean visible, int need) {
        if (visible) {
            int cap = this.visibleScratch.length;
            while (cap <= need) {
                cap <<= 1;
            }
            this.visibleScratch = java.util.Arrays.copyOf(this.visibleScratch, cap);
            this.visibleCenters = java.util.Arrays.copyOf(this.visibleCenters, cap);
        } else {
            int cap = this.tlSections.length;
            while (cap <= need) {
                cap <<= 1;
            }
            this.tlSections = java.util.Arrays.copyOf(this.tlSections, cap);
            this.tlCenters = java.util.Arrays.copyOf(this.tlCenters, cap);
            this.tlKeys = java.util.Arrays.copyOf(this.tlKeys, cap);
        }
    }

    private void copyToDevice(int srcId, AddressableDeviceBuffer dst, long srcOffset, long dstOffset, long bytes) {
        GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, srcId);
        GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, dst.id());
        GL31C.glCopyBufferSubData(GL31C.GL_COPY_READ_BUFFER, GL31C.GL_COPY_WRITE_BUFFER, srcOffset, dstOffset, bytes);
        GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, 0);
        GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, 0);
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
    }
}
