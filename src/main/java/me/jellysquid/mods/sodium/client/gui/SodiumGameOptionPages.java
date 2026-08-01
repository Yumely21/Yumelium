package me.jellysquid.mods.sodium.client.gui;

import com.google.common.collect.ImmutableList;
import me.jellysquid.mods.sodium.client.gui.options.*;
import me.jellysquid.mods.sodium.client.gui.options.control.ControlValueFormatter;
import me.jellysquid.mods.sodium.client.gui.options.control.CyclingControl;
import me.jellysquid.mods.sodium.client.gui.options.control.SliderControl;
import me.jellysquid.mods.sodium.client.gui.options.control.TickBoxControl;
import me.jellysquid.mods.sodium.client.gui.options.named.AttackIndicator;
import me.jellysquid.mods.sodium.client.gui.options.named.GraphicsMode;
import me.jellysquid.mods.sodium.client.gui.options.named.ParticleMode;
import me.jellysquid.mods.sodium.client.gui.options.storage.MinecraftOptionsStorage;
import me.jellysquid.mods.sodium.client.gui.options.storage.SodiumOptionsStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.client.GuiIngameForge;

import org.lwjgl.opengl.Display;

import java.util.ArrayList;
import java.util.List;

public class SodiumGameOptionPages {
    private static final SodiumOptionsStorage sodiumOpts = new SodiumOptionsStorage();
    private static final MinecraftOptionsStorage vanillaOpts = new MinecraftOptionsStorage();

    public static OptionPage general() {
        List<OptionGroup> groups = new ArrayList<>();

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setName(new TextComponentTranslation("options.renderDistance"))
                        .setTooltip(new TextComponentTranslation("sodium.options.view_distance.tooltip"))
                        .setControl(option -> new SliderControl(option, 2, 32, 1, ControlValueFormatter.quantity("options.chunks")))
                        .setBinding((options, value) -> options.renderDistanceChunks = value, options -> options.renderDistanceChunks)
                        .setImpact(OptionImpact.HIGH)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setName(new TextComponentTranslation("options.gamma"))
                        .setTooltip(new TextComponentTranslation("sodium.options.brightness.tooltip"))
                        .setControl(opt -> new SliderControl(opt, 0, 100, 1, ControlValueFormatter.brightness()))
                        .setBinding((opts, value) -> opts.gammaSetting = value * 0.01F, (opts) -> (int) (opts.gammaSetting / 0.01F))
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(new TextComponentTranslation("sodium.options.clouds.name"))
                        .setTooltip(new TextComponentTranslation("sodium.options.clouds.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.quality.enableClouds = value, (opts) -> opts.quality.enableClouds)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setName(new TextComponentTranslation("options.guiScale"))
                        .setTooltip(new TextComponentTranslation("sodium.options.gui_scale.tooltip"))
                        .setControl(option -> new SliderControl(option, 0, 3, 1, ControlValueFormatter.guiScale()))
                        .setBinding((opts, value) -> {
                            opts.guiScale = value;

                            // Resizing our window
                            if(Minecraft.getMinecraft().currentScreen instanceof SodiumOptionsGUI) {
                                Minecraft.getMinecraft().displayGuiScreen(new SodiumOptionsGUI(((SodiumOptionsGUI) Minecraft.getMinecraft().currentScreen).prevScreen));
                            }
                        }, opts -> opts.guiScale)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, vanillaOpts)
                        .setName(new TextComponentTranslation("options.fullscreen"))
                        .setTooltip(new TextComponentTranslation("sodium.options.fullscreen.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> {
                            opts.fullScreen = value;

                            Minecraft client = Minecraft.getMinecraft();

                            if (client.isFullScreen() != opts.fullScreen) {
                                client.toggleFullscreen();

                                // The client might not be able to enter full-screen mode
                                opts.fullScreen = client.isFullScreen();
                            }
                        }, (opts) -> opts.fullScreen)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, vanillaOpts)
                        .setName(new TextComponentTranslation("options.vsync"))
                        .setTooltip(new TextComponentTranslation("sodium.options.v_sync.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> {
                            opts.enableVsync = value;
                            Display.setVSyncEnabled(opts.enableVsync);
                        }, opts -> opts.enableVsync)
                        .setImpact(OptionImpact.VARIES)
                        .build())
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setName(new TextComponentTranslation("options.framerateLimit"))
                        .setTooltip(new TextComponentTranslation("sodium.options.fps_limit.tooltip"))
                        .setControl(option -> new SliderControl(option, 5, 260, 5, ControlValueFormatter.fpsLimit()))
                        .setBinding((opts, value) -> opts.limitFramerate = value, opts -> opts.limitFramerate)
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, vanillaOpts)
                        .setName(new TextComponentTranslation("options.viewBobbing"))
                        .setTooltip(new TextComponentTranslation("sodium.options.view_bobbing.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.viewBobbing = value, opts -> opts.viewBobbing)
                        .build())
                .add(OptionImpl.createBuilder(AttackIndicator.class, vanillaOpts)
                        .setName(new TextComponentTranslation("options.attackIndicator"))
                        .setTooltip(new TextComponentTranslation("sodium.options.attack_indicator.tooltip"))
                        .setControl(opts -> new CyclingControl<>(opts, AttackIndicator.class, new ITextComponent[] { new TextComponentTranslation(AttackIndicator.OFF.getTranslationKey()), new TextComponentTranslation(AttackIndicator.CROSSHAIR.getTranslationKey()), new TextComponentTranslation(AttackIndicator.HOTBAR.getTranslationKey()) }))
                        .setBinding((opts, value) -> opts.attackIndicator = value.getId(), (opts) -> AttackIndicator.byId(opts.attackIndicator))
                        .build())
                .build());

        return new OptionPage(new TextComponentTranslation("stat.generalButton"), ImmutableList.copyOf(groups));
    }

    public static OptionPage quality() {
        List<OptionGroup> groups = new ArrayList<>();

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(GraphicsMode.class, vanillaOpts)
                        .setName(new TextComponentTranslation("options.graphics"))
                        .setTooltip(new TextComponentTranslation("sodium.options.graphics_quality.tooltip"))
                        .setControl(option -> new CyclingControl<>(option, GraphicsMode.class))
                        .setBinding(
                                (opts, value) -> opts.fancyGraphics = value.isFancy(),
                                opts -> GraphicsMode.fromBoolean(opts.fancyGraphics))
                        .setImpact(OptionImpact.HIGH)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(SodiumGameOptions.GraphicsQuality.class, sodiumOpts)
                        .setName(new TextComponentTranslation("options.renderClouds"))
                        .setTooltip(new TextComponentTranslation("sodium.options.clouds_quality.tooltip"))
                        .setControl(option -> new CyclingControl<>(option, SodiumGameOptions.GraphicsQuality.class))
                        .setBinding((opts, value) -> opts.quality.cloudQuality = value, opts -> opts.quality.cloudQuality)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .add(OptionImpl.createBuilder(SodiumGameOptions.GraphicsQuality.class, sodiumOpts)
                        .setName(new TextComponentTranslation("soundCategory.weather"))
                        .setTooltip(new TextComponentTranslation("sodium.options.weather_quality.tooltip"))
                        .setControl(option -> new CyclingControl<>(option, SodiumGameOptions.GraphicsQuality.class))
                        .setBinding((opts, value) -> opts.quality.weatherQuality = value, opts -> opts.quality.weatherQuality)
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .add(OptionImpl.createBuilder(SodiumGameOptions.GraphicsQuality.class, sodiumOpts)
                        .setName(new TextComponentTranslation("sodium.options.leaves_quality.name"))
                        .setTooltip(new TextComponentTranslation("sodium.options.leaves_quality.tooltip"))
                        .setControl(option -> new CyclingControl<>(option, SodiumGameOptions.GraphicsQuality.class))
                        .setBinding((opts, value) -> opts.quality.leavesQuality = value, opts -> opts.quality.leavesQuality)
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(ParticleMode.class, vanillaOpts)
                        .setName(new TextComponentTranslation("options.particles"))
                        .setTooltip(new TextComponentTranslation("sodium.options.particle_quality.tooltip"))
                        .setControl(opt -> new CyclingControl<>(opt, ParticleMode.class))
                        .setBinding((opts, value) -> opts.particleSetting = value.ordinal(), (opts) -> ParticleMode.fromOrdinal(opts.particleSetting))
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .add(OptionImpl.createBuilder(SodiumGameOptions.LightingQuality.class, sodiumOpts)
                        .setName(new TextComponentTranslation("options.ao"))
                        .setTooltip(new TextComponentTranslation("sodium.options.smooth_lighting.tooltip"))
                        .setControl(option -> new CyclingControl<>(option, SodiumGameOptions.LightingQuality.class))
                        .setBinding((opts, value) -> opts.quality.smoothLighting = value, opts -> opts.quality.smoothLighting)
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                // TODO
                .add(OptionImpl.createBuilder(int.class, sodiumOpts)
                        .setName(new TextComponentTranslation("sodium.options.biome_blend.name"))
                        .setTooltip(new TextComponentTranslation("sodium.options.biome_blend.tooltip"))
                        .setControl(option -> new SliderControl(option, 0, 14, 1, ControlValueFormatter.quantityOrDisabled("sodium.options.biome_blend.value", "gui.none")))
                        .setBinding((opts, value) -> opts.quality.biomeBlendRadius = value, opts -> opts.quality.biomeBlendRadius)
                        .setImpact(OptionImpact.LOW)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(int.class, sodiumOpts)
                        .setName(new TextComponentTranslation("sodium.options.entity_distance.name"))
                        .setTooltip(new TextComponentTranslation("sodium.options.entity_distance.tooltip"))
                        .setControl(option -> new SliderControl(option, 50, 500, 25, ControlValueFormatter.percentage()))
                        .setBinding((opts, value) -> opts.quality.entityDistanceScaling = value / 100.0F, opts -> Math.round(opts.quality.entityDistanceScaling * 100.0F))
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, vanillaOpts)
                        .setName(new TextComponentTranslation("options.entityShadows"))
                        .setTooltip(new TextComponentTranslation("sodium.options.entity_shadows.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.entityShadows = value, opts -> opts.entityShadows)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(new TextComponentTranslation("sodium.options.vignette.name"))
                        .setTooltip(new TextComponentTranslation("sodium.options.vignette.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.quality.enableVignette = value, opts -> opts.quality.enableVignette)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .build());


        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setName(new TextComponentTranslation("options.mipmapLevels"))
                        .setTooltip(new TextComponentTranslation("sodium.options.mipmap_levels.tooltip"))
                        .setControl(option -> new SliderControl(option, 0, 4, 1, ControlValueFormatter.multiplier()))
                        .setBinding((opts, value) -> opts.mipmapLevels = value, opts -> opts.mipmapLevels)
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD)
                        .build())
                .build());


        return new OptionPage(new TextComponentTranslation("sodium.options.pages.quality"), ImmutableList.copyOf(groups));
    }

    public static OptionPage advanced() {
        List<OptionGroup> groups = new ArrayList<>();

        // NOTE(yumelium): the "use_chunk_multidraw" option (Vintagium 0.2.x MultidrawChunkRenderBackend) is dropped —
        // the Embeddium 0.5.x engine has a single modern backend and doesn't switch on this flag.
        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(new TextComponentTranslation("sodium.options.use_vertex_objects.name"))
                        .setTooltip(new TextComponentTranslation("sodium.options.use_vertex_objects.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.advanced.useVertexArrayObjects = value, opts -> opts.advanced.useVertexArrayObjects)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .add(OptionImpl.createBuilder(SodiumGameOptions.MultiDrawMode.class, sodiumOpts)
                        .setName(new TextComponentTranslation("yumelium.options.multidraw_mode.name"))
                        .setTooltip(new TextComponentTranslation("yumelium.options.multidraw_mode.tooltip"))
                        .setControl(option -> new CyclingControl<>(option, SodiumGameOptions.MultiDrawMode.class))
                        .setBinding((opts, value) -> opts.advanced.multiDrawMode = value, opts -> opts.advanced.multiDrawMode)
                        .setImpact(OptionImpact.VARIES)
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(new TextComponentTranslation("sodium.options.use_block_face_culling.name"))
                        .setTooltip(new TextComponentTranslation("sodium.options.use_block_face_culling.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.MEDIUM)
                        .setBinding((opts, value) -> opts.advanced.useBlockFaceCulling = value, opts -> opts.advanced.useBlockFaceCulling)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build()
                )
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(new TextComponentTranslation("sodium.options.use_compact_vertex_format.name"))
                        .setTooltip(new TextComponentTranslation("sodium.options.use_compact_vertex_format.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.MEDIUM)
                        .setBinding((opts, value) -> opts.advanced.useCompactVertexFormat = value, opts -> opts.advanced.useCompactVertexFormat)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build()
                )
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(new TextComponentTranslation("sodium.options.use_fog_occlusion.name"))
                        .setTooltip(new TextComponentTranslation("sodium.options.use_fog_occlusion.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.advanced.useFogOcclusion = value, opts -> opts.advanced.useFogOcclusion)
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build()
                )
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(new TextComponentTranslation("sodium.options.translucency_sorting.name"))
                        .setTooltip(new TextComponentTranslation("sodium.options.translucency_sorting.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.advanced.translucencySorting = value, opts -> opts.advanced.translucencySorting)
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build()
                )
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(new TextComponentTranslation("sodium.options.use_entity_culling.name"))
                        .setTooltip(new TextComponentTranslation("sodium.options.use_entity_culling.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.MEDIUM)
                        .setBinding((opts, value) -> opts.advanced.useEntityCulling = value, opts -> opts.advanced.useEntityCulling)
                        .build()
                )
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(new TextComponentTranslation("sodium.options.use_particle_culling.name"))
                        .setTooltip(new TextComponentTranslation("sodium.options.use_particle_culling.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.LOW)
                        .setBinding((opts, value) -> opts.advanced.useParticleCulling = value, opts -> opts.advanced.useParticleCulling)
                        .build()
                )
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(new TextComponentTranslation("sodium.options.animate_only_visible_textures.name"))
                        .setTooltip(new TextComponentTranslation("sodium.options.animate_only_visible_textures.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.HIGH)
                        .setBinding((opts, value) -> opts.advanced.animateOnlyVisibleTextures = value, opts -> opts.advanced.animateOnlyVisibleTextures)
                        .build()
                )
                .build());

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(new TextComponentTranslation("sodium.options.allow_direct_memory_access.name"))
                        .setTooltip(new TextComponentTranslation("sodium.options.allow_direct_memory_access.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.HIGH)
                        .setBinding((opts, value) -> opts.advanced.allowDirectMemoryAccess = value, opts -> opts.advanced.allowDirectMemoryAccess)
                        .build()
                )
                .build());

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(new TextComponentTranslation("sodium.options.ignore_driver_blacklist.name"))
                        .setTooltip(new TextComponentTranslation("sodium.options.ignore_driver_blacklist.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.advanced.ignoreDriverBlacklist = value, opts -> opts.advanced.ignoreDriverBlacklist)
                        .build()
                )
                .build());

        return new OptionPage(new TextComponentTranslation("sodium.options.pages.advanced"), ImmutableList.copyOf(groups));
    }

    public static OptionPage performance() {
        List<OptionGroup> groups = new ArrayList<>();

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(int.class, sodiumOpts)
                        .setName(new TextComponentTranslation("sodium.options.chunk_update_threads.name"))
                        .setTooltip(new TextComponentTranslation("sodium.options.chunk_update_threads.tooltip"))
                        .setControl(o -> new SliderControl(o, 0, Runtime.getRuntime().availableProcessors(), 1, ControlValueFormatter.quantityOrDisabled("sodium.options.threads.value", "sodium.options.default")))
                        .setImpact(OptionImpact.HIGH)
                        .setBinding((opts, value) -> opts.performance.chunkBuilderThreads = value, opts -> opts.performance.chunkBuilderThreads)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build()
                )
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(new TextComponentTranslation("sodium.options.always_defer_chunk_updates.name"))
                        .setTooltip(new TextComponentTranslation("sodium.options.always_defer_chunk_updates.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.HIGH)
                        .setBinding((opts, value) -> opts.performance.alwaysDeferChunkUpdates = value, opts -> opts.performance.alwaysDeferChunkUpdates)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .build());

        return new OptionPage(new TextComponentTranslation("sodium.options.pages.performance"), ImmutableList.copyOf(groups));
    }

    public static OptionPage nvidium() {
        List<OptionGroup> groups = new ArrayList<>();

        // Nvidium — NVIDIA mesh-shader terrain renderer (only active on capable hardware: RTX + GL_NV_mesh_shader).
        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(new TextComponentTranslation("yumelium.options.nvidium_enabled.name"))
                        .setTooltip(new TextComponentTranslation("yumelium.options.nvidium_enabled.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.yumeliumPlus.nvidiumEnabled = value, opts -> opts.yumeliumPlus.nvidiumEnabled)
                        .setImpact(OptionImpact.HIGH)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(SodiumGameOptions.NvidiumBufferSize.class, sodiumOpts)
                        .setName(new TextComponentTranslation("yumelium.options.nvidium_buffer_size.name"))
                        .setTooltip(new TextComponentTranslation("yumelium.options.nvidium_buffer_size.tooltip"))
                        .setControl(option -> new CyclingControl<>(option, SodiumGameOptions.NvidiumBufferSize.class))
                        .setBinding((opts, value) -> opts.yumeliumPlus.nvidiumBufferSize = value, opts -> opts.yumeliumPlus.nvidiumBufferSize)
                        .setImpact(OptionImpact.HIGH)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(SodiumGameOptions.NvidiumSortMode.class, sodiumOpts)
                        .setName(new TextComponentTranslation("yumelium.options.nvidium_translucent_sort.name"))
                        .setTooltip(new TextComponentTranslation("yumelium.options.nvidium_translucent_sort.tooltip"))
                        .setControl(option -> new CyclingControl<>(option, SodiumGameOptions.NvidiumSortMode.class))
                        .setBinding((opts, value) -> opts.yumeliumPlus.nvidiumTranslucentSort = value, opts -> opts.yumeliumPlus.nvidiumTranslucentSort)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(new TextComponentTranslation("yumelium.options.nvidium_gpu_culling.name"))
                        .setTooltip(new TextComponentTranslation("yumelium.options.nvidium_gpu_culling.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.yumeliumPlus.nvidiumGpuCulling = value, opts -> opts.yumeliumPlus.nvidiumGpuCulling)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .build());

        return new OptionPage(new TextComponentTranslation("yumelium.options.pages.nvidium"), ImmutableList.copyOf(groups));
    }

    public static OptionPage yumeliumPlus() {
        List<OptionGroup> groups = new ArrayList<>();

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(new TextComponentTranslation("yumelium.options.render_sky.name"))
                        .setTooltip(new TextComponentTranslation("yumelium.options.render_sky.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.yumeliumPlus.renderSky = value, opts -> opts.yumeliumPlus.renderSky)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(new TextComponentTranslation("yumelium.options.render_sun_moon.name"))
                        .setTooltip(new TextComponentTranslation("yumelium.options.render_sun_moon.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.yumeliumPlus.renderSunMoon = value, opts -> opts.yumeliumPlus.renderSunMoon)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(new TextComponentTranslation("yumelium.options.render_stars.name"))
                        .setTooltip(new TextComponentTranslation("yumelium.options.render_stars.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.yumeliumPlus.renderStars = value, opts -> opts.yumeliumPlus.renderStars)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(new TextComponentTranslation("yumelium.options.render_sky_colors.name"))
                        .setTooltip(new TextComponentTranslation("yumelium.options.render_sky_colors.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.yumeliumPlus.renderSkyColors = value, opts -> opts.yumeliumPlus.renderSkyColors)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(new TextComponentTranslation("yumelium.options.render_weather.name"))
                        .setTooltip(new TextComponentTranslation("yumelium.options.render_weather.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.yumeliumPlus.renderWeather = value, opts -> opts.yumeliumPlus.renderWeather)
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(new TextComponentTranslation("yumelium.options.render_fog.name"))
                        .setTooltip(new TextComponentTranslation("yumelium.options.render_fog.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.yumeliumPlus.renderFog = value, opts -> opts.yumeliumPlus.renderFog)
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(new TextComponentTranslation("yumelium.options.screen_shake.name"))
                        .setTooltip(new TextComponentTranslation("yumelium.options.screen_shake.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.yumeliumPlus.screenShake = value, opts -> opts.yumeliumPlus.screenShake)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .add(OptionImpl.createBuilder(SodiumGameOptions.BetterGrassMode.class, sodiumOpts)
                        .setName(new TextComponentTranslation("yumelium.options.better_grass.name"))
                        .setTooltip(new TextComponentTranslation("yumelium.options.better_grass.tooltip"))
                        .setControl(option -> new CyclingControl<>(option, SodiumGameOptions.BetterGrassMode.class))
                        .setBinding((opts, value) -> opts.yumeliumPlus.betterGrass = value, opts -> opts.yumeliumPlus.betterGrass)
                        .setImpact(OptionImpact.LOW)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(SodiumGameOptions.DynamicLightsMode.class, sodiumOpts)
                        .setName(new TextComponentTranslation("yumelium.options.dynamic_lights.name"))
                        .setTooltip(new TextComponentTranslation("yumelium.options.dynamic_lights.tooltip"))
                        .setControl(option -> new CyclingControl<>(option, SodiumGameOptions.DynamicLightsMode.class))
                        .setBinding((opts, value) -> opts.yumeliumPlus.dynamicLights = value, opts -> opts.yumeliumPlus.dynamicLights)
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                // The "Connected Textures" toggle is removed while the feature is disabled (see ClientProxy) — leaving a
                // switch that does nothing would be worse than no switch. Restore this block when CTM comes back.
                .build());

        // Zoom (hold the Zoom key, set in Controls)
        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(new TextComponentTranslation("yumelium.options.zoom_enabled.name"))
                        .setTooltip(new TextComponentTranslation("yumelium.options.zoom_enabled.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.yumeliumPlus.zoomEnabled = value, opts -> opts.yumeliumPlus.zoomEnabled)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(new TextComponentTranslation("yumelium.options.zoom_smooth.name"))
                        .setTooltip(new TextComponentTranslation("yumelium.options.zoom_smooth.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.yumeliumPlus.zoomSmooth = value, opts -> opts.yumeliumPlus.zoomSmooth)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(new TextComponentTranslation("yumelium.options.zoom_scroll.name"))
                        .setTooltip(new TextComponentTranslation("yumelium.options.zoom_scroll.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.yumeliumPlus.zoomScroll = value, opts -> opts.yumeliumPlus.zoomScroll)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(new TextComponentTranslation("yumelium.options.zoom_reduce_sensitivity.name"))
                        .setTooltip(new TextComponentTranslation("yumelium.options.zoom_reduce_sensitivity.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.yumeliumPlus.zoomReduceSensitivity = value, opts -> opts.yumeliumPlus.zoomReduceSensitivity)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .build());

        // Animations (master "All Animations" + per-category)
        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(new TextComponentTranslation("yumelium.options.animate_block_textures.name"))
                        .setTooltip(new TextComponentTranslation("yumelium.options.animate_block_textures.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.yumeliumPlus.animateBlockTextures = value, opts -> opts.yumeliumPlus.animateBlockTextures)
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(new TextComponentTranslation("yumelium.options.animate_water.name"))
                        .setTooltip(new TextComponentTranslation("yumelium.options.animate_water.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.yumeliumPlus.animateWater = value, opts -> opts.yumeliumPlus.animateWater)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(new TextComponentTranslation("yumelium.options.animate_lava.name"))
                        .setTooltip(new TextComponentTranslation("yumelium.options.animate_lava.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.yumeliumPlus.animateLava = value, opts -> opts.yumeliumPlus.animateLava)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(new TextComponentTranslation("yumelium.options.animate_fire.name"))
                        .setTooltip(new TextComponentTranslation("yumelium.options.animate_fire.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.yumeliumPlus.animateFire = value, opts -> opts.yumeliumPlus.animateFire)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(new TextComponentTranslation("yumelium.options.animate_portal.name"))
                        .setTooltip(new TextComponentTranslation("yumelium.options.animate_portal.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.yumeliumPlus.animatePortal = value, opts -> opts.yumeliumPlus.animatePortal)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(new TextComponentTranslation("yumelium.options.animate_other.name"))
                        .setTooltip(new TextComponentTranslation("yumelium.options.animate_other.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.yumeliumPlus.animateOther = value, opts -> opts.yumeliumPlus.animateOther)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .build());

        // Particles (master "All Particles" + per-category)
        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(new TextComponentTranslation("yumelium.options.render_particles.name"))
                        .setTooltip(new TextComponentTranslation("yumelium.options.render_particles.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.yumeliumPlus.renderParticles = value, opts -> opts.yumeliumPlus.renderParticles)
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(new TextComponentTranslation("yumelium.options.particle_rain_splash.name"))
                        .setTooltip(new TextComponentTranslation("yumelium.options.particle_rain_splash.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.yumeliumPlus.particleRainSplash = value, opts -> opts.yumeliumPlus.particleRainSplash)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(new TextComponentTranslation("yumelium.options.particle_block_break.name"))
                        .setTooltip(new TextComponentTranslation("yumelium.options.particle_block_break.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.yumeliumPlus.particleBlockBreak = value, opts -> opts.yumeliumPlus.particleBlockBreak)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(new TextComponentTranslation("yumelium.options.particle_block_breaking.name"))
                        .setTooltip(new TextComponentTranslation("yumelium.options.particle_block_breaking.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.yumeliumPlus.particleBlockBreaking = value, opts -> opts.yumeliumPlus.particleBlockBreaking)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .build());

        // Detail
        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(new TextComponentTranslation("yumelium.options.biome_colors.name"))
                        .setTooltip(new TextComponentTranslation("yumelium.options.biome_colors.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.yumeliumPlus.biomeColors = value, opts -> opts.yumeliumPlus.biomeColors)
                        .setImpact(OptionImpact.LOW)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .build());

        // Shader engine health report (log-only; see SodiumGameOptions.YumeliumPlusSettings#shaderHealthReport)
        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(new TextComponentTranslation("yumelium.options.shader_health_report.name"))
                        .setTooltip(new TextComponentTranslation("yumelium.options.shader_health_report.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.yumeliumPlus.shaderHealthReport = value, opts -> opts.yumeliumPlus.shaderHealthReport)
                        .build())
                .build());

        // NOTE: the "Texture" / "Ore Texture Type" options are gone. They switched between newer-version Minecraft
        // textures embedded in the mod jar, and a distributed mod must not ship Mojang's assets. The textures now live in
        // the separate Yumelium companion resource pack, which — being an ordinary resource pack — simply applies when
        // installed, so an in-mod switch for them has nothing left to switch. Connected textures survive (that feature is
        // our own code; only the tiles moved out), and light up when the pack provides them.

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(new TextComponentTranslation("yumelium.options.show_fps.name"))
                        .setTooltip(new TextComponentTranslation("yumelium.options.show_fps.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.yumeliumPlus.showFps = value, opts -> opts.yumeliumPlus.showFps)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(new TextComponentTranslation("yumelium.options.show_coordinates.name"))
                        .setTooltip(new TextComponentTranslation("yumelium.options.show_coordinates.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.yumeliumPlus.showCoordinates = value, opts -> opts.yumeliumPlus.showCoordinates)
                        .build())
                .add(OptionImpl.createBuilder(SodiumGameOptions.HudPosition.class, sodiumOpts)
                        .setName(new TextComponentTranslation("yumelium.options.hud_position.name"))
                        .setTooltip(new TextComponentTranslation("yumelium.options.hud_position.tooltip"))
                        .setControl(option -> new CyclingControl<>(option, SodiumGameOptions.HudPosition.class))
                        .setBinding((opts, value) -> opts.yumeliumPlus.hudPosition = value, opts -> opts.yumeliumPlus.hudPosition)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setName(new TextComponentTranslation("yumelium.options.hud_shadow.name"))
                        .setTooltip(new TextComponentTranslation("yumelium.options.hud_shadow.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.yumeliumPlus.hudShadow = value, opts -> opts.yumeliumPlus.hudShadow)
                        .build())
                .build());

        return new OptionPage(new TextComponentTranslation("yumelium.options.pages.plus"), ImmutableList.copyOf(groups));
    }
}