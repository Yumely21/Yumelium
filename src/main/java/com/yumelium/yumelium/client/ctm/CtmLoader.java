package com.yumelium.yumelium.client.ctm;

import me.jellysquid.mods.sodium.client.SodiumClientMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.AbstractResourcePack;
import net.minecraft.client.resources.IResourcePack;
import net.minecraft.client.resources.ResourcePackRepository;
import net.minecraft.util.ResourceLocation;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Discovers OptiFine/MCPatcher connected-texture definition files ({@code optifine/ctm/**.properties} and the legacy
 * {@code mcpatcher/ctm/**.properties}) across the active resource packs. 1.12.2's {@code IResourceManager} cannot list a
 * directory, so we walk each pack's backing file (zip or folder) directly to enumerate the {@code .properties} paths;
 * the actual bytes are then loaded through the resource manager (which respects pack priority) when parsing.
 *
 * <p>Nvidium/BetterGrass-style feature port; CTM concept from Continuity (LGPL-3.0), reimplemented for our mesher.</p>
 */
public final class CtmLoader {
    private static final String[] CTM_ROOTS = {"optifine/ctm", "mcpatcher/ctm"};

    private CtmLoader() {
    }

    private static void log(String s) {
        SodiumClientMod.logger().info("[Yumelium CTM] " + s);
    }

    /** Enumerate every CTM {@code .properties} resource location provided by the active resource packs. */
    public static Set<ResourceLocation> discoverPropertyFiles() {
        Set<ResourceLocation> found = new LinkedHashSet<>();
        try {
            for (IResourcePack pack : activePacks()) {
                if (!(pack instanceof AbstractResourcePack)) {
                    continue; // default/virtual packs expose no backing file to walk
                }
                File file = ((AbstractResourcePack) pack).getResourcePackFile();
                if (file == null) {
                    continue;
                }
                if (file.isDirectory()) {
                    scanFolder(file, found);
                } else if (file.isFile()) {
                    scanZip(file, found);
                }
            }
        } catch (Throwable t) {
            log("discovery error: " + t);
        }
        return found;
    }

    private static List<IResourcePack> activePacks() {
        List<IResourcePack> packs = new ArrayList<>();
        ResourcePackRepository repo = Minecraft.getMinecraft().getResourcePackRepository();
        if (repo != null) {
            for (ResourcePackRepository.Entry entry : repo.getRepositoryEntries()) {
                packs.add(entry.getResourcePack());
            }
        }
        return packs;
    }

    private static void scanFolder(File root, Set<ResourceLocation> out) {
        File assets = new File(root, "assets");
        File[] domains = assets.listFiles(File::isDirectory);
        if (domains == null) {
            return;
        }
        for (File domainDir : domains) {
            String domain = domainDir.getName();
            for (String ctmRoot : CTM_ROOTS) {
                File ctmDir = new File(domainDir, ctmRoot);
                if (!ctmDir.isDirectory()) {
                    continue;
                }
                Path base = domainDir.toPath();
                try (Stream<Path> walk = Files.walk(ctmDir.toPath())) {
                    walk.filter(p -> p.toString().endsWith(".properties"))
                            .forEach(p -> out.add(new ResourceLocation(domain,
                                    base.relativize(p).toString().replace('\\', '/'))));
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void scanZip(File zipFile, Set<ResourceLocation> out) {
        try (ZipFile zip = new ZipFile(zipFile)) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()) {
                    continue;
                }
                String name = e.getName(); // e.g. assets/minecraft/optifine/ctm/glass.properties
                if (!name.endsWith(".properties") || !name.startsWith("assets/")) {
                    continue;
                }
                int domainEnd = name.indexOf('/', "assets/".length());
                if (domainEnd < 0) {
                    continue;
                }
                String domain = name.substring("assets/".length(), domainEnd);
                String path = name.substring(domainEnd + 1);
                if (isCtmPath(path)) {
                    out.add(new ResourceLocation(domain, path));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isCtmPath(String path) {
        for (String root : CTM_ROOTS) {
            if (path.startsWith(root + "/")) {
                return true;
            }
        }
        return false;
    }
}
