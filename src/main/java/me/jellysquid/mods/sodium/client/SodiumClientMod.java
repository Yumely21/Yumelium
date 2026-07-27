package me.jellysquid.mods.sodium.client;

import com.yumelium.yumelium.Reference;
import me.jellysquid.mods.sodium.client.gui.SodiumGameOptions;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Holder for the ported Sodium/Embeddium engine globals (options + logger).
 *
 * <p>Unlike upstream, this is NOT the {@code @Mod} entry point — Yumelium's entry is
 * {@link com.yumelium.yumelium.YumeliumMod}. The engine only needs these static accessors, so we keep
 * the class name/API (many engine files call {@code SodiumClientMod.options()}/{@code logger()}) while
 * sourcing the mod id/name/version from {@link Reference}.
 */
public class SodiumClientMod {

    public static final String MODID = Reference.MOD_ID;
    public static final String MODNAME = Reference.MOD_NAME;

    private static SodiumGameOptions CONFIG;
    public static Logger LOGGER = LogManager.getLogger(MODNAME);

    public static SodiumGameOptions options() {
        if (CONFIG == null) {
            CONFIG = loadConfig();
        }

        return CONFIG;
    }

    public static Logger logger() {
        if (LOGGER == null) {
            LOGGER = LogManager.getLogger(MODNAME);
        }

        return LOGGER;
    }

    private static SodiumGameOptions loadConfig() {
        return SodiumGameOptions.load(Minecraft.getMinecraft().gameDir.toPath().resolve("config").resolve(MODID + "-options.json"));
    }

    public static String getVersion() {
        return Reference.VERSION;
    }

    public static boolean isDirectMemoryAccessEnabled() {
        return options().advanced.allowDirectMemoryAccess;
    }

    public static boolean canApplyTranslucencySorting() {
        return options().advanced.translucencySorting;
    }

    /**
     * Whether the vanilla (non-compact) terrain vertex format may be used. Compact format is preferred unless disabled.
     */
    public static boolean canUseVanillaVertices() {
        return !options().advanced.useCompactVertexFormat;
    }
}
