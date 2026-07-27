package com.yumelium.yumelium.shaders.pack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Iris/Oculus port — M1. The set of programs a shader pack provides for one dimension, discovered by probing the
 * known OptiFine/Iris program names under the pack's {@code shaders/} directory. A program is "present" when at least
 * one of its stage files ({@code .vsh}/{@code .gsh}/{@code .fsh}/{@code .csh}) exists; all present stages are loaded.
 *
 * <p>The naming taxonomy (the fixed "world" programs + the indexed families begin/prepare/shadowcomp/deferred/
 * composite) is the OptiFine shader-pack format specification — the same names every pack uses; it is not copied code.
 * Dimension folders ({@code world0}/{@code world-1}/{@code world1}) are a later refinement; M1 scans the base set.</p>
 */
public final class ProgramSet {
    /** Fixed, singular program names (gbuffers "world geometry" + sky + shadow + final). */
    private static final String[] NAMED_PROGRAMS = {
            "gbuffers_basic",
            "gbuffers_line",
            "gbuffers_textured",
            "gbuffers_textured_lit",
            "gbuffers_skybasic",
            "gbuffers_skytextured",
            "gbuffers_clouds",
            "gbuffers_terrain",
            "gbuffers_terrain_solid",
            "gbuffers_terrain_cutout",
            "gbuffers_damagedblock",
            "gbuffers_block",
            "gbuffers_beaconbeam",
            "gbuffers_item",
            "gbuffers_entities",
            "gbuffers_entities_glowing",
            "gbuffers_armor_glint",
            "gbuffers_spidereyes",
            "gbuffers_hand",
            "gbuffers_hand_water",
            "gbuffers_weather",
            "gbuffers_water",
            "shadow",
            "shadow_solid",
            "shadow_cutout",
            "final",
    };

    /** Indexed program families: base name (index 0, no digit) then {@code name1}..{@code name<max>}. */
    private static final String[] INDEXED_FAMILIES = {"begin", "prepare", "shadowcomp", "deferred", "composite"};
    private static final int MAX_INDEX = 16;

    private static final String[] EXTENSIONS = {".vsh", ".gsh", ".fsh", ".csh"};

    /** The default dimension folder (the overworld; also {@code dimension.properties}' {@code world0 = *} catch-all). */
    public static final String OVERWORLD_FOLDER = "world0/";

    private final Map<String, ProgramSource> programs; // insertion-ordered by discovery

    private ProgramSet(Map<String, ProgramSource> programs) {
        this.programs = programs;
    }

    public Map<String, ProgramSource> programs() {
        return this.programs;
    }

    public int count() {
        return this.programs.size();
    }

    public ProgramSource get(String name) {
        return this.programs.get(name);
    }

    public boolean has(String name) {
        return this.programs.containsKey(name);
    }

    /** Enumerates every candidate program name in the pack's taxonomy (present or not), in discovery order. */
    public static List<String> candidateNames() {
        List<String> names = new ArrayList<>();
        for (String family : INDEXED_FAMILIES) {
            names.add(family);
            for (int i = 1; i <= MAX_INDEX; i++) {
                names.add(family + i);
            }
        }
        for (String named : NAMED_PROGRAMS) {
            names.add(named);
        }
        return names;
    }

    /** Overworld scan (the {@code world0/} folder, falling back to the {@code shaders/} root). */
    public static ProgramSet scan(PackSource src, String shadersRoot) {
        return scan(src, shadersRoot, OVERWORLD_FOLDER);
    }

    /**
     * Probes {@code src} under {@code shadersRoot} (e.g. {@code "shaders/"}) for every candidate program and loads the
     * stages of each present one. Real packs (Complementary, BSL) put the compilable entry points in per-dimension
     * folders ({@code world0/}/{@code world-1/}/{@code world1/}) that {@code #include} a shared {@code program/*.glsl};
     * {@code worldFolder} selects the dimension's folder, with the {@code shaders/} root ({@code ""}) as the fallback for
     * simple packs (our built-in) that keep everything at the root.
     */
    public static ProgramSet scan(PackSource src, String shadersRoot, String worldFolder) {
        Map<String, ProgramSource> found = new LinkedHashMap<>();
        for (String name : candidateNames()) {
            String vsh = readStage(src, shadersRoot, worldFolder, name, ".vsh");
            String gsh = readStage(src, shadersRoot, worldFolder, name, ".gsh");
            String fsh = readStage(src, shadersRoot, worldFolder, name, ".fsh");
            String csh = readStage(src, shadersRoot, worldFolder, name, ".csh");
            if (vsh != null || gsh != null || fsh != null || csh != null) {
                found.put(name, new ProgramSource(name, vsh, gsh, fsh, csh));
            }
        }
        return new ProgramSet(found);
    }

    /** Finds a program's stage file (dimension folder → root) and inlines its includes. */
    private static String readStage(PackSource src, String shadersRoot, String worldFolder, String name, String ext) {
        for (String subdir : new String[]{worldFolder, ""}) {
            String path = shadersRoot + subdir + name + ext;
            String text = src.readText(path);
            if (text != null) {
                return ShaderPreprocessor.process(src, shadersRoot, path, text);
            }
        }
        return null;
    }
}
