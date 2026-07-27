package com.yumelium.yumelium.shaders.pack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** {@link PackSource} backed by a zipped shader pack ({@code shaderpacks/MyPack.zip}). */
public final class ZipPackSource implements PackSource {
    private final ZipFile zip;
    private final String name;

    public ZipPackSource(Path zipPath) throws IOException {
        this.zip = new ZipFile(zipPath.toFile());
        this.name = zipPath.getFileName().toString();
    }

    @Override
    public String name() {
        return this.name;
    }

    @Override
    public boolean exists(String path) {
        ZipEntry e = this.zip.getEntry(path);
        return e != null && !e.isDirectory();
    }

    @Override
    public String readText(String path) {
        ZipEntry e = this.zip.getEntry(path);
        if (e == null || e.isDirectory()) {
            return null;
        }
        try (InputStream in = this.zip.getInputStream(e)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return null;
        }
    }

    @Override
    public byte[] readBytes(String path) {
        ZipEntry e = this.zip.getEntry(path);
        if (e == null || e.isDirectory()) {
            return null;
        }
        try (InputStream in = this.zip.getInputStream(e)) {
            return in.readAllBytes();
        } catch (IOException ex) {
            return null;
        }
    }

    @Override
    public List<String> topLevelDirs() {
        Set<String> dirs = new LinkedHashSet<>();
        var entries = this.zip.entries();
        while (entries.hasMoreElements()) {
            String n = entries.nextElement().getName();
            int slash = n.indexOf('/');
            if (slash > 0) {
                // record the first path segment as a top-level directory
                dirs.add(n.substring(0, slash + 1));
            }
        }
        return List.copyOf(dirs);
    }

    @Override
    public List<String> listFiles(String dir) {
        List<String> out = new java.util.ArrayList<>();
        var entries = this.zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            if (!e.isDirectory() && e.getName().startsWith(dir)) {
                out.add(e.getName());
            }
        }
        return out;
    }

    @Override
    public void close() {
        try {
            this.zip.close();
        } catch (IOException ignored) {
        }
    }
}
