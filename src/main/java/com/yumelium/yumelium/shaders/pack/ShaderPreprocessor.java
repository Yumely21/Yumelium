package com.yumelium.yumelium.shaders.pack;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Iris/Oculus port (R1) — resolves OptiFine/Iris {@code #include} directives so a real pack's program can be assembled
 * into a single compilable source. Real packs (Complementary, BSL, …) split shaders across {@code lib/} includes and a
 * shared {@code program/*.glsl} that each dimension's {@code .vsh}/{@code .fsh} entry point pulls in (after defining
 * {@code VERTEX_SHADER}/{@code FRAGMENT_SHADER}); nothing compiles without inlining those includes first.
 *
 * <p>{@code #include "/abs/path"} is resolved relative to the pack's {@code shaders/} root; {@code #include "rel"} is
 * relative to the including file's directory. Recursion is depth-capped. Missing includes become a comment marker so a
 * later compile error points at the gap rather than failing opaquely.</p>
 */
public final class ShaderPreprocessor {
    private static final int MAX_DEPTH = 100;
    private static final Pattern INCLUDE = Pattern.compile("^\\s*#include\\s+[\"<]([^\">]+)[\">]");

    private ShaderPreprocessor() {
    }

    /**
     * @param entryPath the pack-root-relative path of {@code source} (so relative includes resolve against its folder).
     * @return {@code source} with every {@code #include} recursively inlined.
     */
    public static String process(PackSource src, String shadersRoot, String entryPath, String source) {
        StringBuilder out = new StringBuilder();
        resolve(src, shadersRoot, entryPath, source, out, 0);
        return out.toString();
    }

    private static void resolve(PackSource src, String shadersRoot, String path, String source, StringBuilder out, int depth) {
        if (source == null) {
            out.append("// [yumelium: missing include ").append(path).append("]\n");
            return;
        }
        if (depth > MAX_DEPTH) {
            out.append("// [yumelium: include depth exceeded at ").append(path).append("]\n");
            return;
        }
        String dir = path.contains("/") ? path.substring(0, path.lastIndexOf('/') + 1) : "";
        for (String line : source.split("\n", -1)) {
            Matcher m = INCLUDE.matcher(line);
            if (m.find()) {
                String inc = m.group(1);
                String resolved = normalize(inc.startsWith("/") ? shadersRoot + inc.substring(1) : dir + inc);
                resolve(src, shadersRoot, resolved, src.readText(resolved), out, depth + 1);
            } else {
                out.append(line).append('\n');
            }
        }
    }

    /** Collapses {@code .} / {@code ..} / empty segments in a {@code /}-separated path. */
    private static String normalize(String path) {
        Deque<String> stack = new ArrayDeque<>();
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                stack.pollLast();
            } else {
                stack.addLast(part);
            }
        }
        return String.join("/", stack);
    }
}
