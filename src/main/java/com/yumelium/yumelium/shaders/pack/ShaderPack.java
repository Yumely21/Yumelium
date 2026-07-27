package com.yumelium.yumelium.shaders.pack;

import java.util.ArrayList;
import java.util.List;

/**
 * Iris/Oculus port — M1. A loaded shader pack: its {@code shaders.properties} config + the discovered {@link ProgramSet}.
 * Loading only reads/parses; compilation, uniforms, render targets and the pipeline come in later milestones. The
 * {@link PackSource} is retained so later stages can lazily read includes/textures, and is closed via {@link #close()}.
 */
public final class ShaderPack implements AutoCloseable {
    /** Files probed (under {@code <prefix>shaders/}) to locate the pack's shaders directory. */
    private static final String[] PROBE_FILES = {
            "shaders.properties",
            "gbuffers_basic.fsh", "gbuffers_terrain.fsh", "gbuffers_textured.fsh",
            "final.fsh", "composite.fsh", "deferred.fsh",
    };

    private final PackSource source;
    private final String shadersRoot;
    private final String worldFolder;
    private final ShaderProperties properties;
    private final ProgramSet programSet;

    private ShaderPack(PackSource source, String shadersRoot, String worldFolder, ShaderProperties properties,
                       ProgramSet programSet) {
        this.source = source;
        this.shadersRoot = shadersRoot;
        this.worldFolder = worldFolder;
        this.properties = properties;
        this.programSet = programSet;
    }

    /** Loads a pack with the overworld ({@code world0/}) program set. */
    public static ShaderPack load(PackSource source) {
        return load(source, ProgramSet.OVERWORLD_FOLDER);
    }

    /**
     * Loads a pack from a source with the given dimension's program folder ({@code world0/}/{@code world-1/}/
     * {@code world1/}), or returns {@code null} if the source has no recognizable {@code shaders/} directory.
     */
    public static ShaderPack load(PackSource source, String worldFolder) {
        String root = detectShadersRoot(source);
        if (root == null) {
            return null;
        }
        ShaderProperties props = ShaderProperties.load(source, root + "shaders.properties");
        ProgramSet set = ProgramSet.scan(source, root, worldFolder);
        return new ShaderPack(source, root, worldFolder, props, set);
    }

    /** Finds the {@code shaders/} directory at the pack root or nested one level inside the archive; {@code null} if none. */
    private static String detectShadersRoot(PackSource source) {
        List<String> prefixes = new ArrayList<>();
        prefixes.add(""); // root-level shaders/
        prefixes.addAll(source.topLevelDirs()); // or nested one level (e.g. "PackName/")
        for (String prefix : prefixes) {
            String base = prefix + "shaders/";
            for (String probe : PROBE_FILES) {
                if (source.exists(base + probe)) {
                    return base;
                }
            }
        }
        return null;
    }

    public String name() {
        return this.source.name();
    }

    public String shadersRoot() {
        return this.shadersRoot;
    }

    /** The dimension folder this pack's program set was scanned from ({@code "world0/"}, {@code "world-1/"}, …). */
    public String worldFolder() {
        return this.worldFolder;
    }

    public ShaderProperties properties() {
        return this.properties;
    }

    public ProgramSet programSet() {
        return this.programSet;
    }

    public PackSource source() {
        return this.source;
    }

    @Override
    public void close() {
        this.source.close();
    }
}
