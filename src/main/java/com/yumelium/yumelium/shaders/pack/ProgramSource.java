package com.yumelium.yumelium.shaders.pack;

/**
 * Iris/Oculus port — M1. The loaded GLSL source of one shader-pack program (e.g. {@code gbuffers_terrain},
 * {@code composite2}, {@code final}). A program may provide any subset of the four stages; a raster program has at
 * least a vertex or fragment stage, a compute-only program (newer packs) has just a compute stage. The actual
 * compilation + {@code #include}/directive handling happens in a later milestone (M3+) — M1 only loads the text.
 */
public final class ProgramSource {
    private final String name;
    private final String vertex;
    private final String geometry;
    private final String fragment;
    private final String compute;

    public ProgramSource(String name, String vertex, String geometry, String fragment, String compute) {
        this.name = name;
        this.vertex = vertex;
        this.geometry = geometry;
        this.fragment = fragment;
        this.compute = compute;
    }

    public String name() {
        return this.name;
    }

    public String vertex() {
        return this.vertex;
    }

    public String geometry() {
        return this.geometry;
    }

    public String fragment() {
        return this.fragment;
    }

    public String compute() {
        return this.compute;
    }

    public boolean hasVertex() {
        return this.vertex != null;
    }

    public boolean hasGeometry() {
        return this.geometry != null;
    }

    public boolean hasFragment() {
        return this.fragment != null;
    }

    public boolean hasCompute() {
        return this.compute != null;
    }

    /** @return true if this is a raster (vertex/fragment) program rather than a compute-only one. */
    public boolean isRaster() {
        return this.vertex != null || this.fragment != null;
    }

    /** @return a compact {@code v/g/f/c} presence tag for logging, e.g. {@code "vf"} or {@code "vgf"}. */
    public String stageTag() {
        StringBuilder sb = new StringBuilder();
        if (hasVertex()) sb.append('v');
        if (hasGeometry()) sb.append('g');
        if (hasFragment()) sb.append('f');
        if (hasCompute()) sb.append('c');
        return sb.toString();
    }
}
