package com.yumelium.yumelium.shaders.gl;

import me.jellysquid.mods.sodium.client.SodiumClientMod;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL33C;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GPU wall-clock per render phase, measured with {@code GL_TIME_ELAPSED} queries.
 *
 * <p>Exists so "what should we optimise?" is answered by measurement rather than by which phase looks expensive in the
 * source. CPU-side timing cannot answer it at all: GL calls return long before the GPU runs the work, so timing
 * {@code renderShadowPass} on the CPU mostly measures command submission. Results are read back {@link #RING} frames
 * later, so no query ever stalls the pipeline waiting on the GPU.</p>
 *
 * <p>{@code GL_TIME_ELAPSED} queries cannot nest — only one may be active at a time — so phases must be sequential.
 * {@link #begin} silently ignores a nested call rather than raising a GL error.</p>
 */
public final class GpuProfiler {
    /** How many frames to let a query age before reading it. Long enough that the GPU is done; short enough to stay live. */
    private static final int RING = 4;

    /** One issued query: which phase it belongs to and which frame issued it. */
    private static final class Sample {
        final String name;
        final int query;

        Sample(String name, int query) {
            this.name = name;
            this.query = query;
        }
    }

    private static final class Phase {
        double emaMs = -1.0;
    }

    private final Map<String, Phase> phases = new LinkedHashMap<>();
    /** Queries issued per frame slot. A phase may be timed SEVERAL times in one frame (the terrain renders as separate
     * solid/cutout passes), so results are summed per name rather than kept one-per-phase. */
    @SuppressWarnings("unchecked")
    private final java.util.List<Sample>[] issued = new java.util.List[RING];
    private final java.util.ArrayDeque<Integer> freeQueries = new java.util.ArrayDeque<>();
    private int frame;
    private boolean active;
    private boolean supported = true;

    public GpuProfiler() {
        for (int i = 0; i < RING; i++) {
            this.issued[i] = new java.util.ArrayList<>();
        }
    }

    /** Starts timing {@code name}. No-op if another phase is already being timed (GL_TIME_ELAPSED cannot nest). */
    public void begin(String name) {
        if (!this.supported || this.active) {
            return;
        }
        try {
            this.phases.computeIfAbsent(name, n -> new Phase());
            int query = this.freeQueries.isEmpty() ? GL33C.glGenQueries() : this.freeQueries.poll();
            GL15C.glBeginQuery(GL33C.GL_TIME_ELAPSED, query);
            this.issued[this.frame % RING].add(new Sample(name, query));
            this.active = true;
        } catch (Throwable t) {
            this.supported = false;
        }
    }

    public void end() {
        if (!this.supported || !this.active) {
            return;
        }
        try {
            GL15C.glEndQuery(GL33C.GL_TIME_ELAPSED);
        } catch (Throwable ignored) {
            // fall through: the slot stays pending and is skipped by collect()
        }
        this.active = false;
    }

    /**
     * Ends the frame: harvests the results issued {@code RING} frames ago (by then the GPU has finished them, so
     * {@code glGetQueryObject} returns without blocking) and folds them into a smoothed per-phase average.
     */
    public void frameEnd() {
        if (!this.supported) {
            return;
        }
        this.frame++;
        int slot = this.frame % RING; // the oldest slot — about to be reused next frame, so harvest it now
        java.util.List<Sample> samples = this.issued[slot];
        if (samples.isEmpty()) {
            return;
        }
        try {
            java.util.Map<String, Double> sums = new LinkedHashMap<>();
            for (Sample s : samples) {
                if (GL15C.glGetQueryObjecti(s.query, GL15C.GL_QUERY_RESULT_AVAILABLE) == 0) {
                    return; // the GPU is still behind — leave the whole slot for next frame rather than half-count it
                }
                sums.merge(s.name, GL33C.glGetQueryObjectui64(s.query, GL15C.GL_QUERY_RESULT) / 1.0e6, Double::sum);
            }
            for (Map.Entry<String, Double> e : sums.entrySet()) {
                Phase phase = this.phases.computeIfAbsent(e.getKey(), n -> new Phase());
                phase.emaMs = phase.emaMs < 0.0 ? e.getValue() : phase.emaMs + (e.getValue() - phase.emaMs) * 0.1;
            }
            for (Sample s : samples) {
                this.freeQueries.add(s.query);
            }
            samples.clear();
        } catch (Throwable t) {
            this.supported = false;
        }
    }

    /** @return e.g. {@code "shadow=3.21ms composite=8.44ms | total=11.65ms (of 20.0ms @50fps)"}, or null if unsupported. */
    public String report() {
        if (!this.supported || this.phases.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        double total = 0.0;
        for (Map.Entry<String, Phase> e : this.phases.entrySet()) {
            double ms = e.getValue().emaMs;
            if (ms < 0.0) {
                continue;
            }
            total += ms;
            sb.append(e.getKey()).append('=').append(String.format("%.2f", ms)).append("ms ");
        }
        if (total <= 0.0) {
            return null;
        }
        sb.append("| measured total=").append(String.format("%.2f", total)).append("ms");
        return sb.toString();
    }

    public void delete() {
        try {
            for (java.util.List<Sample> slot : this.issued) {
                for (Sample s : slot) {
                    GL33C.glDeleteQueries(s.query);
                }
                slot.clear();
            }
            for (int q : this.freeQueries) {
                GL33C.glDeleteQueries(q);
            }
        } catch (Throwable ignored) {
        }
        this.freeQueries.clear();
        this.phases.clear();
        SodiumClientMod.logger().info("[Iris profiler] GPU timer queries released");
    }
}
