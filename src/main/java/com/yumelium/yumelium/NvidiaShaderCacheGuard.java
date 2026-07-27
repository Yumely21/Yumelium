package com.yumelium.yumelium;

import java.io.File;

/**
 * Works around an NVIDIA driver defect: the GL shader DISK cache ({@code %LOCALAPPDATA%\NVIDIA\GLCache}) gets
 * poisoned by this workload's heavy shader recompilation, and the next launch then access-violates INSIDE
 * {@code nvoglv64.dll} (offset 0xc5aa8a, ~2s after world load, on the cache-HIT load path) — no Java crash report,
 * only a Windows Event Log entry. The {@code __GL_SHADER_DISK_CACHE=0} env var in the gradle run config did NOT
 * reach the driver (a fresh 15MB cache entry appeared during a crashing run), so this guard acts from INSIDE the
 * process instead, and must run BEFORE the GL context exists (the driver reads its env and opens the cache at
 * context creation) — hence the coremod-constructor call site, not mod init (the 1.12.2 splash screen already owns
 * a GL context by preInit).
 *
 * <p>Two layers, each sufficient on its own when it works:</p>
 * <ol>
 * <li><b>Wipe the stale cache</b> (pure Java): with no pre-existing entries the poisoned-HIT path cannot execute;
 *     the driver recompiles cold and rewrites fresh entries, which the NEXT launch wipes again. Locked files (another
 *     GL app running) are skipped silently — the .toc/.bin pair for THIS app is only locked while the game runs.</li>
 * <li><b>Native process env</b> (best-effort, via the JNA that ships with vanilla 1.12.2): disable the cache and
 *     point its path at a throwaway directory, so even a partially-wiped cache is never read. Set with kernel32's
 *     {@code SetEnvironmentVariableW} — Java's {@code System.getenv} copy is irrelevant, the driver reads the WIN32
 *     process block.</li>
 * </ol>
 */
public final class NvidiaShaderCacheGuard {

    private NvidiaShaderCacheGuard() {
    }

    public static void install() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return;
        }
        try {
            setNativeEnv();
        } catch (Throwable t) {
            System.out.println("[Yumelium] NVIDIA cache guard: env-var layer unavailable (" + t + ")");
        }
        try {
            wipeStaleCache();
        } catch (Throwable t) {
            System.out.println("[Yumelium] NVIDIA cache guard: cache wipe failed (" + t + ")");
        }
    }

    /** Deletes every file under %LOCALAPPDATA%\NVIDIA\GLCache (recursively); locked/undeletable files are skipped. */
    private static void wipeStaleCache() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null || localAppData.isEmpty()) {
            return;
        }
        File cache = new File(localAppData, "NVIDIA" + File.separator + "GLCache");
        if (!cache.isDirectory()) {
            return;
        }
        int[] deleted = {0};
        deleteFilesUnder(cache, deleted);
        System.out.println("[Yumelium] NVIDIA cache guard: wiped " + deleted[0]
                + " stale GLCache file(s) before GL context creation (driver cache-poisoning workaround)");
    }

    private static void deleteFilesUnder(File dir, int[] deleted) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File f : children) {
            if (f.isDirectory()) {
                deleteFilesUnder(f, deleted);
            } else if (f.delete()) {
                deleted[0]++;
            }
        }
    }

    /** Sets the NVIDIA workaround env vars in the WIN32 process environment via JNA — reflection-free direct use;
     * vanilla 1.12.2 ships JNA (text2speech), so it is on the launch classpath at coremod time.
     *
     * <p>{@code __GL_THREADED_OPTIMIZATIONS=0} is the load-bearing one: the 0xc5aa8a crash recurred with the disk
     * cache CONFIRMED off and empty, killing the cache theory — the survivor is the driver's background shader
     * re-optimizer thread racing our compile-then-use-immediately workload (a classic nvoglv64 fixed-offset crash).
     * NVIDIA's application profiles tame it for KNOWN Minecraft launchers (javaw), but this dev workload runs bare
     * {@code java.exe} from ~/.jdks, matching no profile — which is exactly why "real Iris" users never see it.
     * The disk-cache vars stay as belt-and-braces (they are proven honoured in-process and cost only a few seconds
     * of cold compiles).</p> */
    private static void setNativeEnv() {
        Kernel32 k32 = (Kernel32) com.sun.jna.Native.loadLibrary("kernel32", Kernel32.class,
                com.sun.jna.win32.W32APIOptions.DEFAULT_OPTIONS);
        File throwaway = new File(System.getProperty("java.io.tmpdir"), "yumelium-glcache");
        //noinspection ResultOfMethodCallIgnored
        throwaway.mkdirs();
        boolean a = k32.SetEnvironmentVariableW(new com.sun.jna.WString("__GL_SHADER_DISK_CACHE"),
                new com.sun.jna.WString("0"));
        boolean b = k32.SetEnvironmentVariableW(new com.sun.jna.WString("__GL_SHADER_DISK_CACHE_PATH"),
                new com.sun.jna.WString(throwaway.getAbsolutePath()));
        boolean c = k32.SetEnvironmentVariableW(new com.sun.jna.WString("__GL_THREADED_OPTIMIZATIONS"),
                new com.sun.jna.WString("0"));
        System.out.println("[Yumelium] NVIDIA cache guard: process env set disk_cache=0(" + a
                + ") path->" + throwaway + "(" + b + ") threaded_opts=0(" + c + ")");
    }

    /** Minimal kernel32 binding — jna core only, no jna-platform dependency. */
    public interface Kernel32 extends com.sun.jna.win32.StdCallLibrary {
        @SuppressWarnings("UnusedReturnValue")
        boolean SetEnvironmentVariableW(com.sun.jna.WString name, com.sun.jna.WString value);
    }
}
