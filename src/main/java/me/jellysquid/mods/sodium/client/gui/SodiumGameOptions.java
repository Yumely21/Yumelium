package me.jellysquid.mods.sodium.client.gui;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gui.options.FormattedTextProvider;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class SodiumGameOptions {
    public final QualitySettings quality = new QualitySettings();
    public final AdvancedSettings advanced = new AdvancedSettings();
    public final PerformanceSettings performance = new PerformanceSettings();
    public final NotificationSettings notifications = new NotificationSettings();
    public final YumeliumPlusSettings yumeliumPlus = new YumeliumPlusSettings();

    private Path configPath;

    /**
     * "Yumelium Plus" — Sodium-Extra-style toggles for optional world rendering (for performance or preference).
     */
    public static class YumeliumPlusSettings {
        // Nvidium (NVIDIA mesh-shader terrain renderer) — only active on capable hardware (RTX + GL_NV_mesh_shader)
        public boolean nvidiumEnabled = true;
        public NvidiumBufferSize nvidiumBufferSize = NvidiumBufferSize.GB_2;
        public NvidiumSortMode nvidiumTranslucentSort = NvidiumSortMode.SECTION;
        // M4a: cull work items on the GPU in a task shader (frustum test against the section AABB) before the mesh
        // stage runs. Read per frame — no renderer reload needed; silently inert when the task stage failed to build.
        public boolean nvidiumGpuCulling = true;

        // Sky / environment
        public boolean renderSky = true;
        public boolean renderSunMoon = true;
        public boolean renderStars = true;
        public boolean renderSkyColors = true;
        public boolean renderWeather = true;
        public boolean renderFog = true;

        // Animations (master + per-category)
        public boolean animateBlockTextures = true; // master: "All Animations"
        public boolean animateWater = true;
        public boolean animateLava = true;
        public boolean animateFire = true;
        public boolean animatePortal = true;
        public boolean animateOther = true;

        // Particles (master + per-category)
        public boolean renderParticles = true; // master: "All Particles"
        public boolean particleRainSplash = true;
        public boolean particleBlockBreak = true;
        public boolean particleBlockBreaking = true;

        // Detail
        public boolean biomeColors = true;

        // Shader engine health report: while on, the Iris port logs ONE full report (unrouted programs, unimplemented
        // pack directives, compile failures, sampler audits, unset uniforms) after the next completed frame. Toggle
        // off and back on to print it again. Log-only — rendering is untouched.
        public boolean shaderHealthReport = false;

        // (textureSet / oreTextureType removed — the mod no longer embeds Minecraft's textures; see the companion pack)

        // Camera
        public boolean screenShake = true;

        // Visual
        public BetterGrassMode betterGrass = BetterGrassMode.OFF;
        public DynamicLightsMode dynamicLights = DynamicLightsMode.OFF;
        // Connected textures. The engine is ours; the tiles come from the Yumelium companion resource pack, so this is a
        // plain on/off — the old Legacy/New choice only existed to pick between two tile sets embedded in the jar, and
        // the jar no longer embeds any. With it on but no pack installed, glass just renders normally.
        public boolean connectedTextures = true;

        // Zoom (hold the Zoom key — see Controls)
        public boolean zoomEnabled = true;
        public boolean zoomSmooth = true;
        public boolean zoomScroll = true;
        public boolean zoomReduceSensitivity = true;

        // HUD extras
        public boolean showFps = false;
        public boolean showCoordinates = false;
        public HudPosition hudPosition = HudPosition.TOP_LEFT;
        public boolean hudShadow = true;
    }

    public enum HudPosition implements FormattedTextProvider {
        TOP_LEFT(new TextComponentTranslation("yumelium.options.hud_position.top_left")),
        TOP_RIGHT(new TextComponentTranslation("yumelium.options.hud_position.top_right")),
        BOTTOM_LEFT(new TextComponentTranslation("yumelium.options.hud_position.bottom_left")),
        BOTTOM_RIGHT(new TextComponentTranslation("yumelium.options.hud_position.bottom_right"));

        private final ITextComponent name;

        HudPosition(ITextComponent name) {
            this.name = name;
        }

        @Override
        public ITextComponent getLocalizedName() {
            return this.name;
        }

        public boolean isTop() {
            return this == TOP_LEFT || this == TOP_RIGHT;
        }

        public boolean isLeft() {
            return this == TOP_LEFT || this == BOTTOM_LEFT;
        }
    }

    /**
     * Chunk draw-submission path (all use Vertex Array Objects). DIRECT = one glMultiDrawElementsBaseVertex per region
     * (default). INDIVIDUAL = one glDrawElementsBaseVertex per section (most compatible, most CPU). INDIRECT =
     * glMultiDrawElementsIndirect per region (GL 4.3; least driver overhead, falls back to DIRECT if unsupported).
     */
    public enum MultiDrawMode implements FormattedTextProvider {
        INDIRECT(new TextComponentTranslation("yumelium.options.multidraw_mode.indirect")),
        INDIVIDUAL(new TextComponentTranslation("yumelium.options.multidraw_mode.individual")),
        DIRECT(new TextComponentTranslation("yumelium.options.multidraw_mode.direct"));

        private final ITextComponent name;

        MultiDrawMode(ITextComponent name) {
            this.name = name;
        }

        @Override
        public ITextComponent getLocalizedName() {
            return this.name;
        }
    }

    /**
     * VRAM budget for the Nvidium GPU-resident terrain geometry arena. The arena starts small and grows on demand up to
     * this cap; larger caps allow higher render distances before any section is dropped.
     */
    public enum NvidiumBufferSize implements FormattedTextProvider {
        MB_512(new TextComponentTranslation("yumelium.options.nvidium_buffer_size.512mb"), 512L * 1024 * 1024),
        GB_1(new TextComponentTranslation("yumelium.options.nvidium_buffer_size.1gb"), 1024L * 1024 * 1024),
        GB_2(new TextComponentTranslation("yumelium.options.nvidium_buffer_size.2gb"), 2048L * 1024 * 1024),
        GB_4(new TextComponentTranslation("yumelium.options.nvidium_buffer_size.4gb"), 4096L * 1024 * 1024),
        // "Unlimited" = capped at the GPU's own dedicated VRAM, queried from the driver at first use (needs a live
        // GL context, so it cannot be a fixed constant here). Sections then never drop for budget reasons alone.
        UNLIMITED(new TextComponentTranslation("yumelium.options.nvidium_buffer_size.unlimited"), -1L);

        private final ITextComponent name;
        private final long bytes;

        NvidiumBufferSize(ITextComponent name, long bytes) {
            this.name = name;
            this.bytes = bytes;
        }

        public long bytes() {
            return this.bytes < 0
                    ? com.yumelium.yumelium.nvidium.NvidiumBackend.dedicatedVramBytes()
                    : this.bytes;
        }

        @Override
        public ITextComponent getLocalizedName() {
            return this.name;
        }
    }

    /**
     * How Nvidium orders translucent (water/glass) geometry for correct back-to-front blending. OFF = unsorted (fastest,
     * may mis-blend overlapping transparency); SECTION = sort visible sections back-to-front (default); FULL = SECTION
     * plus per-quad sorting within each section (most correct, extra CPU cost).
     */
    public enum NvidiumSortMode implements FormattedTextProvider {
        OFF(new TextComponentTranslation("yumelium.options.nvidium_translucent_sort.off")),
        SECTION(new TextComponentTranslation("yumelium.options.nvidium_translucent_sort.section")),
        FULL(new TextComponentTranslation("yumelium.options.nvidium_translucent_sort.full"));

        private final ITextComponent name;

        NvidiumSortMode(ITextComponent name) {
            this.name = name;
        }

        @Override
        public ITextComponent getLocalizedName() {
            return this.name;
        }
    }

    /**
     * "Better grass" (ported concept from BetterGrassify, Apache-2.0). OFF = vanilla; FAST = grass texture on all
     * visible grass/mycelium/podzol/snow side faces; FANCY = only where the block continues (adjacent or one step down).
     */
    public enum BetterGrassMode implements FormattedTextProvider {
        OFF(new TextComponentTranslation("options.off")),
        FAST(new TextComponentTranslation("options.clouds.fast")),
        FANCY(new TextComponentTranslation("options.clouds.fancy"));

        private final ITextComponent name;

        BetterGrassMode(ITextComponent name) {
            this.name = name;
        }

        @Override
        public ITextComponent getLocalizedName() {
            return this.name;
        }
    }

    /**
     * Dynamic lights — light-emitting held/dropped items and burning entities illuminate the world. OFF = none;
     * FAST = updated every few ticks (fewer chunk rebuilds); FANCY = updated every tick (smoothest, more rebuilds).
     */
    public enum DynamicLightsMode implements FormattedTextProvider {
        OFF(new TextComponentTranslation("options.off")),
        FAST(new TextComponentTranslation("options.clouds.fast")),
        FANCY(new TextComponentTranslation("options.clouds.fancy"));

        private final ITextComponent name;

        DynamicLightsMode(ITextComponent name) {
            this.name = name;
        }

        @Override
        public ITextComponent getLocalizedName() {
            return this.name;
        }
    }

    public static class AdvancedSettings {
        public boolean useVertexArrayObjects = true;
        public boolean useChunkMultidraw = true;
        public MultiDrawMode multiDrawMode = MultiDrawMode.DIRECT;

        public boolean animateOnlyVisibleTextures = true;
        public boolean useEntityCulling = true;
        public boolean useParticleCulling = true;
        public boolean useFogOcclusion = true;
        public boolean useCompactVertexFormat = true;
        public boolean useBlockFaceCulling = true;
        public boolean allowDirectMemoryAccess = true;
        public boolean useAdvancedStagingBuffers = true;
        public boolean ignoreDriverBlacklist = false;
        // Default ON = vanilla parity (1.12.2 re-sorts translucency as the camera moves). OFF renders translucent
        // quads in mesh-build order, which collapses whenever several translucent surfaces share a section — barely
        // visible in the overworld's single flat water sheet, but The Betweenlands (stacked water levels + plants
        // that embed fluid quads in the same cell) made water behind plants vanish entirely (2026-07-28). This flag
        // also feeds the per-quad sort data Nvidium's FULL translucent mode consumes.
        public boolean translucencySorting = true;
        public boolean disableIncompatibleModWarnings = false;
    }

    public static class PerformanceSettings {
        public int chunkBuilderThreads = 0;
        public boolean alwaysDeferChunkUpdates = false;
    }

    public static class QualitySettings {
        public GraphicsQuality cloudQuality = GraphicsQuality.DEFAULT;
        public GraphicsQuality weatherQuality = GraphicsQuality.DEFAULT;
        public GraphicsQuality leavesQuality = GraphicsQuality.DEFAULT;

        public int biomeBlendRadius = 2;
        public float entityDistanceScaling = 1.0F;

        public boolean enableVignette = true;
        public boolean enableClouds = true;

        public LightingQuality smoothLighting = LightingQuality.HIGH;
    }

    public static class NotificationSettings {
        public boolean hideDonationButton = false;
    }

    public enum GraphicsQuality implements FormattedTextProvider {
        DEFAULT(new TextComponentTranslation("generator.default")),
        FANCY(new TextComponentTranslation("options.clouds.fancy")),
        FAST(new TextComponentTranslation("options.clouds.fast"));

        private final ITextComponent name;

        GraphicsQuality(ITextComponent name) {
            this.name = name;
        }

        @Override
        public ITextComponent getLocalizedName() {
            return this.name;
        }

        public boolean isFancy(boolean fancy) {
            return (this == FANCY) || (this == DEFAULT && fancy);
        }
    }

    public enum LightingQuality implements FormattedTextProvider {
        OFF(new TextComponentTranslation("options.ao.off")),
        LOW(new TextComponentTranslation("options.ao.min")),
        HIGH(new TextComponentTranslation("options.ao.max"));

        private final ITextComponent name;

        LightingQuality(ITextComponent name) {
            this.name = name;
        }

        @Override
        public ITextComponent getLocalizedName() {
            return this.name;
        }
    }

    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting()
            .excludeFieldsWithModifiers(Modifier.PRIVATE)
            .create();

    public static SodiumGameOptions load(Path path) {
        SodiumGameOptions config;
        boolean resaveConfig = true;

        if (Files.exists(path)) {
            try (FileReader reader = new FileReader(path.toFile())) {
                config = GSON.fromJson(reader, SodiumGameOptions.class);
            } catch (IOException e) {
                throw new RuntimeException("Could not parse config", e);
            } catch (JsonSyntaxException e) {
                SodiumClientMod.logger().error("Could not parse config, will fallback to default settings", e);
                config = new SodiumGameOptions();
                resaveConfig = false;
            }
        } else {
            config = new SodiumGameOptions();
        }

        config.configPath = path;

        try {
            if(resaveConfig)
                config.writeChanges();
        } catch (IOException e) {
            throw new RuntimeException("Couldn't update config file", e);
        }

        return config;
    }

    public void writeChanges() throws IOException {
        Path dir = this.configPath.getParent();

        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        } else if (!Files.isDirectory(dir)) {
            throw new IOException("Not a directory: " + dir);
        }

        Files.write(this.configPath, GSON.toJson(this)
                .getBytes(StandardCharsets.UTF_8));
    }
}