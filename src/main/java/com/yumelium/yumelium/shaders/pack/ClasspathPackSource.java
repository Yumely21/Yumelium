package com.yumelium.yumelium.shaders.pack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * {@link PackSource} backed by classpath resources under a fixed root (the mod's embedded built-in test pack at
 * {@code /yumelium_shaders/builtin/}). Lets the M1 loader always be exercised even when the user has no external pack.
 */
public final class ClasspathPackSource implements PackSource {
    private final String resourceRoot; // e.g. "/yumelium_shaders/builtin/"
    private final String name;

    public ClasspathPackSource(String resourceRoot, String name) {
        this.resourceRoot = resourceRoot.endsWith("/") ? resourceRoot : resourceRoot + "/";
        this.name = name;
    }

    @Override
    public String name() {
        return this.name;
    }

    @Override
    public boolean exists(String path) {
        return ClasspathPackSource.class.getResource(this.resourceRoot + path) != null;
    }

    @Override
    public String readText(String path) {
        try (InputStream in = ClasspathPackSource.class.getResourceAsStream(this.resourceRoot + path)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public List<String> topLevelDirs() {
        // The built-in layout is fixed and root-level ("shaders/"), so no nesting detection is needed.
        return Collections.emptyList();
    }
}
