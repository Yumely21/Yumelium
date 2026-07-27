package com.yumelium.yumelium.shaders.pack;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Iris/Oculus port — M1. A parsed {@code shaders.properties} (and the same format for {@code block.properties} etc.).
 * The format is a Java-properties-like {@code key=value} file with {@code #} line comments and {@code \} line
 * continuations. For M1 we keep the raw key→value map plus a couple of typed accessors; the full directive/profile/
 * option semantics are layered on in later milestones as the pipeline needs them.
 */
public final class ShaderProperties {
    private final Map<String, String> values;

    private ShaderProperties(Map<String, String> values) {
        this.values = values;
    }

    public static ShaderProperties empty() {
        return new ShaderProperties(Collections.emptyMap());
    }

    /** Loads + parses the properties file at {@code path} from {@code src}, or returns empty if absent. */
    public static ShaderProperties load(PackSource src, String path) {
        String text = src.readText(path);
        if (text == null) {
            return empty();
        }
        return parse(text);
    }

    public static ShaderProperties parse(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        StringBuilder logical = new StringBuilder();
        for (String rawLine : text.split("\n")) {
            String line = rawLine.replace("\r", "");
            // Join continued lines (trailing backslash).
            if (line.endsWith("\\")) {
                logical.append(line, 0, line.length() - 1);
                continue;
            }
            logical.append(line);
            String full = logical.toString();
            logical.setLength(0);

            String trimmed = full.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int eq = indexOfSeparator(trimmed);
            if (eq < 0) {
                continue;
            }
            String key = trimmed.substring(0, eq).trim();
            String value = trimmed.substring(eq + 1).trim();
            if (!key.isEmpty()) {
                values.put(key, value);
            }
        }
        return new ShaderProperties(values);
    }

    /** Properties use {@code =} (and, less commonly, {@code :}) as the key/value separator. */
    private static int indexOfSeparator(String line) {
        int eq = line.indexOf('=');
        int colon = line.indexOf(':');
        if (eq < 0) {
            return colon;
        }
        if (colon < 0) {
            return eq;
        }
        return Math.min(eq, colon);
    }

    public Map<String, String> asMap() {
        return Collections.unmodifiableMap(this.values);
    }

    public String get(String key) {
        return this.values.get(key);
    }

    public String get(String key, String def) {
        return this.values.getOrDefault(key, def);
    }

    public boolean isEmpty() {
        return this.values.isEmpty();
    }

    public int size() {
        return this.values.size();
    }
}
