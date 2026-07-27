package com.yumelium.yumelium.shaders;

import com.yumelium.yumelium.shaders.pack.ClasspathPackSource;
import com.yumelium.yumelium.shaders.pack.FolderPackSource;
import com.yumelium.yumelium.shaders.pack.PackSource;
import com.yumelium.yumelium.shaders.pack.ProgramSource;
import com.yumelium.yumelium.shaders.pack.ShaderPack;
import com.yumelium.yumelium.shaders.pack.ZipPackSource;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Iris/Oculus port — M1. Discovers available shader packs (external folders/zips in {@code <gameDir>/shaderpacks/} plus
 * the mod's embedded built-in test pack) and loads one on demand. M1 scope = discovery + load + config parsing +
 * program-set enumeration, all logged for validation; no rendering. The rest of the pipeline (M2+) consumes the loaded
 * {@link ShaderPack}.
 */
public final class ShaderPackManager {
    /** The synthetic name of the embedded built-in test pack (always available). */
    public static final String BUILTIN_NAME = "(built-in)";
    private static final String BUILTIN_RESOURCE_ROOT = "/yumelium_shaders/builtin/";

    private static final ShaderPackManager INSTANCE = new ShaderPackManager();

    private ShaderPackManager() {
    }

    public static ShaderPackManager instance() {
        return INSTANCE;
    }

    private static void log(String s) {
        SodiumClientMod.logger().info("[Iris] " + s);
    }

    /** @return the {@code shaderpacks/} directory in the game folder, creating it if absent. */
    public Path shaderpacksDir() {
        Path dir = Minecraft.getMinecraft().gameDir.toPath().resolve("shaderpacks");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            SodiumClientMod.logger().warn("[Iris] could not create shaderpacks dir " + dir, e);
        }
        return dir;
    }

    /**
     * @return the names of all available packs: {@link #BUILTIN_NAME} first, then external folders/zips in
     * {@code shaderpacks/} (sorted, case-insensitive).
     */
    public List<String> listAvailable() {
        List<String> names = new ArrayList<>();
        names.add(BUILTIN_NAME);

        Path dir = shaderpacksDir();
        List<String> external = new ArrayList<>();
        try (Stream<Path> entries = Files.list(dir)) {
            entries.forEach(p -> {
                String fileName = p.getFileName().toString();
                if (Files.isDirectory(p)) {
                    external.add(fileName);
                } else if (fileName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    external.add(fileName);
                }
            });
        } catch (IOException e) {
            SodiumClientMod.logger().warn("[Iris] could not list shaderpacks dir " + dir, e);
        }
        external.sort(String.CASE_INSENSITIVE_ORDER);
        names.addAll(external);
        return names;
    }

    /**
     * Opens a {@link PackSource} for the named pack ({@link #BUILTIN_NAME} → embedded classpath; otherwise a folder or
     * zip in {@code shaderpacks/}). Returns {@code null} if the name is not found.
     */
    public PackSource openSource(String name) {
        if (BUILTIN_NAME.equals(name)) {
            return new ClasspathPackSource(BUILTIN_RESOURCE_ROOT, BUILTIN_NAME);
        }
        Path path = shaderpacksDir().resolve(name);
        if (Files.isDirectory(path)) {
            return new FolderPackSource(path);
        }
        if (Files.isRegularFile(path) && name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            try {
                return new ZipPackSource(path);
            } catch (IOException e) {
                SodiumClientMod.logger().error("[Iris] failed to open zip pack " + name, e);
                return null;
            }
        }
        return null;
    }

    /** Loads the named pack with the overworld ({@code world0/}) program set. */
    public ShaderPack load(String name) {
        return load(name, com.yumelium.yumelium.shaders.pack.ProgramSet.OVERWORLD_FOLDER);
    }

    /** Loads the named pack with the given dimension folder's program set, or {@code null} if it can't be opened /
     * has no {@code shaders/} directory. */
    public ShaderPack load(String name, String worldFolder) {
        PackSource src = openSource(name);
        if (src == null) {
            return null;
        }
        ShaderPack pack = ShaderPack.load(src, worldFolder);
        if (pack == null) {
            src.close();
        }
        return pack;
    }

    /**
     * M1 self-test: discover all packs, log them, then load + log the program set of the built-in pack and the first
     * external pack (if any). Proves the loader end-to-end. Called once from client init.
     */
    public void discoverAndLog() {
        try {
            log("======== shader-pack loader (M1) ========");
            Path dir = shaderpacksDir();
            log("shaderpacks dir = " + dir);

            List<String> available = listAvailable();
            log("available packs (" + available.size() + "): " + String.join(", ", available));

            // Always load + report the built-in test pack.
            logPack(BUILTIN_NAME);

            // Also load the first external pack, if the user has dropped one in.
            for (String name : available) {
                if (!BUILTIN_NAME.equals(name)) {
                    logPack(name);
                    break;
                }
            }
            log("======== shader-pack loader OK ========");
        } catch (Throwable t) {
            SodiumClientMod.logger().error("[Iris] shader-pack discovery threw", t);
        }
    }

    private void logPack(String name) {
        try (ShaderPack pack = load(name)) {
            if (pack == null) {
                log("pack '" + name + "': NOT LOADABLE (no shaders/ directory)");
                return;
            }
            log("pack '" + pack.name() + "': shadersRoot='" + pack.shadersRoot()
                    + "', properties=" + pack.properties().size() + " key(s), programs=" + pack.programSet().count());

            // Summarize discovered programs grouped by category prefix.
            Map<String, List<String>> byCategory = new TreeMap<>();
            for (ProgramSource ps : pack.programSet().programs().values()) {
                String cat = categoryOf(ps.name());
                byCategory.computeIfAbsent(cat, k -> new ArrayList<>()).add(ps.name() + "[" + ps.stageTag() + "]");
            }
            for (Map.Entry<String, List<String>> e : byCategory.entrySet()) {
                log("   " + e.getKey() + ": " + String.join(", ", e.getValue()));
            }
        }
    }

    private static String categoryOf(String programName) {
        if (programName.startsWith("gbuffers_")) return "gbuffers";
        if (programName.startsWith("shadowcomp")) return "shadowcomp";
        if (programName.startsWith("shadow")) return "shadow";
        if (programName.startsWith("begin")) return "begin";
        if (programName.startsWith("prepare")) return "prepare";
        if (programName.startsWith("deferred")) return "deferred";
        if (programName.startsWith("composite")) return "composite";
        if (programName.equals("final")) return "final";
        return "other";
    }
}
