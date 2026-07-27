package com.yumelium.yumelium.shaders.pack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** {@link PackSource} backed by an unzipped shader-pack folder under {@code shaderpacks/}. */
public final class FolderPackSource implements PackSource {
    private final Path root;
    private final String name;

    public FolderPackSource(Path root) {
        this.root = root;
        this.name = root.getFileName().toString();
    }

    @Override
    public String name() {
        return this.name;
    }

    @Override
    public boolean exists(String path) {
        Path p = this.root.resolve(path);
        return Files.isRegularFile(p);
    }

    @Override
    public String readText(String path) {
        Path p = this.root.resolve(path);
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public byte[] readBytes(String path) {
        Path p = this.root.resolve(path);
        try {
            return Files.readAllBytes(p);
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public List<String> topLevelDirs() {
        List<String> dirs = new ArrayList<>();
        try (Stream<Path> children = Files.list(this.root)) {
            children.filter(Files::isDirectory).forEach(p -> dirs.add(p.getFileName().toString() + "/"));
        } catch (IOException ignored) {
        }
        return dirs;
    }

    @Override
    public List<String> listFiles(String dir) {
        List<String> out = new ArrayList<>();
        Path base = this.root.resolve(dir);
        if (!Files.isDirectory(base)) {
            return out;
        }
        try (Stream<Path> walk = Files.walk(base)) {
            walk.filter(Files::isRegularFile)
                    .forEach(p -> out.add(this.root.relativize(p).toString().replace('\\', '/')));
        } catch (IOException ignored) {
        }
        return out;
    }
}
