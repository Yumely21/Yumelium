package com.yumelium.yumelium.compat;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Auto-fixes KNOWN-INCOMPATIBLE third-party mod config values so users never have to hand-edit them
 * (2026-07-28, user request after the VintageFix flat-entity-shading hunt).
 *
 * <p>Runs from the coremod's {@code injectData} — before mixin configs are applied and before any mod init — so
 * VintageFix's mixin plugin (which reads its properties at mixin-application time) sees the corrected value in the
 * SAME launch. FermiumASM loads its config in its own coremod, whose order relative to ours is undefined: the file
 * edit is guaranteed for the NEXT launch, and a best-effort reflective in-memory flip covers the current one when
 * their config object already exists.</p>
 *
 * <p>Rules (each documented at the definition):</p>
 * <ul>
 * <li>NEVER creates a config file — only corrects existing wrong values (a missing file means the mod hasn't run
 *     yet; its first run generates it and the next launch gets enforced).</li>
 * <li>Only the named keys are touched; every other byte of the file is preserved.</li>
 * </ul>
 */
public final class ThirdPartyConfigEnforcer {
    /** key=expected pairs per file; the match is on the exact {@code key=} prefix of a trimmed line. */
    private static final String[][] VINTAGEFIX_RULES = {
            // Its entity-disappearing bugfix resurrects the VANILLA entity render loop, which this renderer relies
            // on no-op'ing (empty renderInfos) — entities then draw a second time OUTSIDE the shader pass and look
            // flat. The bugfix is redundant here anyway: our own loop iterates the full entity list.
            {"mixin.bugfix.entity_disappearing", "false"},
    };

    private static final String[][] NORMALASM_RULES = {
            // The "loader surgery" family raw-patches FML/LaunchClassLoader internals assuming STOCK Forge
            // bytecode; Cleanroom's forked loader diverges and the partial patches crash (NoSuchFieldError
            // ModCandidate.packages observed). They are Java-8-era RAM hacks with ~zero benefit on Java 21+.
            {"B:packageStringCanonicalization", "false"},
            {"B:asmDataStringCanonicalization", "false"},
            {"B:filePermissionsCacheCanonicalization", "false"},
            {"B:cleanupLaunchClassLoaderLate", "false"},
            {"B:cleanupLaunchClassLoaderEarly", "false"},
            {"B:disablePackageManifestMap", "false"},
            {"B:weakClassCache", "false"},
            {"B:weakResourceCache", "false"},
    };

    /** Field names on mirror.normalasm.config.NormalConfig mirrored by the in-memory best-effort flip. */
    private static final String[] NORMALASM_MEMORY_FIELDS = {
            "packageStringCanonicalization", "asmDataStringCanonicalization",
            "filePermissionsCacheCanonicalization", "cleanupLaunchClassLoaderLate",
            "cleanupLaunchClassLoaderEarly", "disablePackageManifestMap",
            "weakClassCache", "weakResourceCache",
    };

    private ThirdPartyConfigEnforcer() {
    }

    /** @param gameDir the .minecraft directory (coremod injectData's mcLocation), or null for the working dir. */
    public static void enforceAll(File gameDir) {
        File config = new File(gameDir == null ? new File(".") : gameDir, "config");
        enforceFile(new File(config, "vintagefix.properties"), VINTAGEFIX_RULES, "=");
        enforceFile(new File(config, "normalasm.cfg"), NORMALASM_RULES, "=");
        flipNormalAsmInMemory();
    }

    private static void enforceFile(File file, String[][] rules, String sep) {
        if (!file.isFile()) {
            return; // mod absent or not yet run — its first run generates the file; next launch gets enforced
        }
        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            List<String> out = new ArrayList<>(lines.size());
            List<String> changed = new ArrayList<>(2);
            for (String line : lines) {
                String replaced = line;
                String trimmed = line.trim();
                for (String[] rule : rules) {
                    String prefix = rule[0] + sep;
                    if (trimmed.startsWith(prefix) && !trimmed.equals(prefix + rule[1])) {
                        replaced = line.substring(0, line.indexOf(trimmed)) + prefix + rule[1];
                        changed.add(rule[0] + " -> " + rule[1]);
                        break;
                    }
                }
                out.add(replaced);
            }
            if (!changed.isEmpty()) {
                Files.write(file.toPath(), out, StandardCharsets.UTF_8);
                log("corrected " + file.getName() + " for Yumelium compatibility: " + changed);
            }
        } catch (Throwable t) {
            log("could not enforce " + file.getName() + ": " + t);
        }
    }

    /** Best-effort same-launch coverage for FermiumASM: its coremod may have already loaded the old values. */
    private static void flipNormalAsmInMemory() {
        try {
            Class<?> cfg = Class.forName("mirror.normalasm.config.NormalConfig");
            Object instance = cfg.getField("instance").get(null);
            if (instance == null) {
                return;
            }
            for (String name : NORMALASM_MEMORY_FIELDS) {
                try {
                    java.lang.reflect.Field f = cfg.getField(name);
                    if (f.getBoolean(instance)) {
                        f.setBoolean(instance, false);
                        log("flipped in-memory NormalConfig." + name + " = false (same-launch coverage)");
                    }
                } catch (Throwable ignored) {
                    // field renamed in a future fork version — the file rule still covers the next launch
                }
            }
        } catch (Throwable absent) {
            // FermiumASM not installed / config class not loadable yet — file rules cover it
        }
    }

    private static void log(String s) {
        System.out.println("[Yumelium/ThirdPartyConfigEnforcer] " + s);
    }
}
