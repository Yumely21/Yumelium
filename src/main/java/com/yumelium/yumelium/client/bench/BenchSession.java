package com.yumelium.yumelium.client.bench;

import com.yumelium.yumelium.client.entity.ProceduralGeometryCache;
import com.yumelium.yumelium.shaders.pipeline.IrisPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.Arrays;

/**
 * The measuring half of {@code /ylbench}: collects per-frame wall-clock deltas + the pipeline's per-frame CPU
 * entity counters over a fixed window and prints one compact chat summary.
 *
 * <p>Sampling happens at {@link TickEvent.RenderTickEvent} phase END — the ONLY point where IrisPipeline's
 * per-frame CPU fields are fully accumulated but not yet reset (they reset in the NEXT frame's beginWorldRender).
 * GPU numbers come from GpuProfiler.report(), which runs unconditionally (DIAG_GPU_TIME gates only the log line)
 * but is an EMA (alpha 0.1, ~30 frames to settle) — hence the 10 s default window. Registered on the Forge bus in
 * ClientProxy; idle cost is one boolean check per render tick.</p>
 */
public final class BenchSession {

    public static final BenchSession INSTANCE = new BenchSession();

    private boolean active;
    private long startNano;
    private long endNano;
    private long lastFrameNano;
    private long[] deltas = new long[4096];
    private int deltaCount;
    private long camEntNanos;
    private long camEntPasses;
    private long shadowEntNanos;
    private long shadowSetupNanos;
    private long shadowDrawNanos;
    private long shadowTesrNanos;
    private int pipelineFrames;

    private BenchSession() {
    }

    public boolean isActive() {
        return this.active;
    }

    /** Client-thread only (the command hops via addScheduledTask). */
    public void start(int seconds) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || mc.player == null) {
            return;
        }
        if (mc.gameSettings.enableVsync || mc.gameSettings.limitFramerate < 260) {
            chat(TextFormatting.YELLOW, "WARNING: vsync/frame cap active — numbers will be flattened"
                    + " (vsync=" + mc.gameSettings.enableVsync + " cap=" + mc.gameSettings.limitFramerate + ")");
        }
        this.deltaCount = 0;
        this.camEntNanos = this.camEntPasses = 0;
        this.shadowEntNanos = this.shadowSetupNanos = this.shadowDrawNanos = this.shadowTesrNanos = 0;
        this.pipelineFrames = 0;
        ProceduralGeometryCache.benchResetCounters();
        this.lastFrameNano = 0;
        this.startNano = System.nanoTime();
        this.endNano = this.startNano + seconds * 1_000_000_000L;
        this.active = true;
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !this.active) {
            return;
        }
        long now = System.nanoTime();
        if (this.lastFrameNano != 0) {
            if (this.deltaCount == this.deltas.length) {
                this.deltas = Arrays.copyOf(this.deltas, this.deltas.length * 2);
            }
            this.deltas[this.deltaCount++] = now - this.lastFrameNano;
        }
        this.lastFrameNano = now;
        IrisPipeline p = IrisPipeline.instance();
        if (p.isEnabled()) {
            this.camEntNanos += p.benchCameraEntityCpuNanos();
            this.camEntPasses += p.benchCameraEntityPasses();
            this.shadowEntNanos += p.benchShadowEntityCpuNanos();
            this.shadowSetupNanos += p.benchShadowEntSetupNanos();
            this.shadowDrawNanos += p.benchShadowEntDrawNanos();
            this.shadowTesrNanos += p.benchShadowEntTesrNanos();
            this.pipelineFrames++;
        }
        if (now >= this.endNano) {
            this.active = false;
            finish(now);
        }
    }

    private void finish(long now) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || mc.player == null || this.deltaCount == 0) {
            return;
        }
        double windowSec = (now - this.startNano) / 1.0e9;
        int frames = this.deltaCount;
        double avgFps = frames / windowSec;
        long[] sorted = Arrays.copyOf(this.deltas, frames);
        Arrays.sort(sorted);
        double p50 = sorted[(int) (frames * 0.50)] / 1.0e6;
        double p95 = sorted[Math.min(frames - 1, (int) (frames * 0.95))] / 1.0e6;
        double p99 = sorted[Math.min(frames - 1, (int) (frames * 0.99))] / 1.0e6;
        double max = sorted[frames - 1] / 1.0e6;

        int[] counts = YlBenchCommand.countBenchEntities(mc.world);
        int[] procGeo = ProceduralGeometryCache.benchSnapshot();

        chat(TextFormatting.AQUA, String.format("%.1fs | %d menace (+%d dummy) | %d frames avg %.1f fps",
                windowSec, counts[0], counts[1], frames, avgFps));
        chat(TextFormatting.GRAY, String.format("frame ms: p50 %.1f  p95 %.1f  p99 %.1f  max %.1f", p50, p95, p99, max));
        if (this.pipelineFrames > 0) {
            double f = this.pipelineFrames;
            chat(TextFormatting.GRAY, String.format(
                    "CPU/frame: camEnt %.2fms (x%.1f passes)  shadowEnt %.2fms [setup %.2f / draw %.2f / tesr %.2f]",
                    this.camEntNanos / f / 1.0e6, this.camEntPasses / f,
                    this.shadowEntNanos / f / 1.0e6, this.shadowSetupNanos / f / 1.0e6,
                    this.shadowDrawNanos / f / 1.0e6, this.shadowTesrNanos / f / 1.0e6));
            String gpu = IrisPipeline.instance().benchGpuReport();
            chat(TextFormatting.GRAY, "GPU (EMA): " + (gpu != null ? gpu : "n/a"));
        } else {
            chat(TextFormatting.GRAY, "CPU/GPU pipeline stats: n/a (shaders off)");
        }
        String mspt = "n/a";
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server != null) {
            long sum = 0;
            for (long t : server.tickTimeArray) {
                sum += t;
            }
            mspt = String.format("%.1fms", sum / (double) server.tickTimeArray.length / 1.0e6);
        }
        chat(TextFormatting.GRAY, String.format("procGeo: %d builds / %d replays | server MSPT %s",
                procGeo[0], procGeo[1], mspt));
    }

    private static void chat(TextFormatting color, String msg) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player != null) {
            TextComponentString c = new TextComponentString("[ylbench] " + msg);
            c.getStyle().setColor(color);
            mc.player.sendMessage(c);
        }
    }
}
