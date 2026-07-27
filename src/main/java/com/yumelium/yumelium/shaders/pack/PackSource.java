package com.yumelium.yumelium.shaders.pack;

import java.util.List;

/**
 * Iris/Oculus port — M1. A read-only, path-addressed view over the backing store of one shader pack, so that the pack
 * loader is agnostic to whether the pack is an unzipped folder, a {@code .zip} file, or an embedded classpath resource
 * set (our built-in test pack). Paths are pack-root-relative and always use {@code /} separators.
 *
 * <p>We probe for known program file names ({@code shaders/gbuffers_terrain.fsh}, ...) rather than listing directories,
 * because 1.12.2 has no reliable directory listing for classpath/zip anyway (the same reason the CTM loader had to walk
 * pack files by hand).</p>
 */
public interface PackSource extends AutoCloseable {
    /** @return a human-readable name for this pack (folder or zip file name; {@code "(built-in)"} for the embedded one). */
    String name();

    /** @return true if a regular file exists at {@code path} (pack-root-relative, {@code /}-separated). */
    boolean exists(String path);

    /** @return the UTF-8 text of the file at {@code path}, or {@code null} if it does not exist / cannot be read. */
    String readText(String path);

    /** @return the raw bytes of the file at {@code path} (for binary pack resources like custom {@code .png} textures),
     * or {@code null} if it does not exist / cannot be read. */
    default byte[] readBytes(String path) {
        return null;
    }

    /**
     * @return the immediate child directory names at the pack root, each with a trailing {@code /} (used only to detect
     * a pack whose {@code shaders/} directory is nested one level inside the archive). Empty if unknown/none.
     */
    List<String> topLevelDirs();

    /**
     * @return every regular file path (pack-root-relative, {@code /}-separated) recursively under {@code dir}, or an
     * empty list if the backing store can't be listed (e.g. an embedded classpath pack). Used to scan a real pack's
     * shader sources — programs live in {@code program/}/{@code world*}/ subfolders and options in {@code lib/} includes,
     * so the option scanner can't rely on the fixed top-level program names.
     */
    default List<String> listFiles(String dir) {
        return java.util.Collections.emptyList();
    }

    @Override
    default void close() {
    }
}
