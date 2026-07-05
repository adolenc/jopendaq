package com.opendaq.lowlevel;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Locates and loads the native openDAQ libraries.
 *
 * <p>The loader looks for the libraries in this order:
 * <ol>
 *   <li>the directory named by the {@code OPENDAQ_JAVA_NATIVE_DIR} environment
 *       variable (or the {@code opendaq.native.dir} system property) — for dev
 *       builds and packagers pointing at a {@code bin/<platform>/} directory;</li>
 *   <li>the {@code natives-<platform>} classifier jar on the classpath, whose
 *       libraries are extracted once into a versioned per-user cache.</li>
 * </ol>
 *
 * <p>There is no network access: the natives ship as ordinary Maven artifacts
 * (a {@code natives-<platform>} classifier jar published alongside the main
 * jar).  Add the one for your platform to the classpath, or set
 * {@code OPENDAQ_JAVA_NATIVE_DIR} to a directory of loose libraries.
 */
public final class NativeLoader {

    private NativeLoader() {}

    static final String NATIVE_DIR_ENV_VAR = "OPENDAQ_JAVA_NATIVE_DIR";
    static final String NATIVE_DIR_PROPERTY = "opendaq.native.dir";
    static final String MODULES_ENV_VAR = "OPENDAQ_MODULES_PATH";

    /** Classpath resource root under which each platform's natives jar packs its libraries. */
    static final String NATIVES_RESOURCE_ROOT = "com/opendaq/natives/";

    private static Path loadedNativeDirectory;
    private static List<Path> loadedLibraryPaths;
    private static SymbolLookup lookup;
    private static RuntimeException loadError;

    private enum Os { LINUX, MACOS, WINDOWS }

    private static Os currentOs() {
        String name = System.getProperty("os.name", "").toLowerCase();
        if (name.contains("linux")) return Os.LINUX;
        if (name.contains("mac") || name.contains("darwin")) return Os.MACOS;
        if (name.contains("windows")) return Os.WINDOWS;
        throw new IllegalStateException("Unsupported OS for openDAQ native libraries: " + name);
    }

    /**
     * Name of the platform directory holding this host's natives, e.g.
     * "linux-x86_64".  The naming matches Netty's {@code os-maven-plugin}
     * classifier ({@code ${os.detected.classifier}}) so the natives jar can be
     * pulled in as {@code natives-${os.detected.classifier}} with no mapping.
     */
    public static String currentPlatformName() {
        String os = switch (currentOs()) {
            case LINUX -> "linux";
            case MACOS -> "osx";
            case WINDOWS -> "windows";
        };
        String archName = System.getProperty("os.arch", "").toLowerCase();
        String arch = switch (archName) {
            case "amd64", "x86_64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch_64";
            default -> throw new IllegalStateException(
                "Unsupported architecture for openDAQ native libraries: " + archName);
        };
        return os + "-" + arch;
    }

    /** Exact library file names shipped in bin/&lt;platform&gt;/, in dependency load order. */
    public static List<String> libraryFileNames() {
        return switch (currentOs()) {
            case LINUX -> List.of(
                "libdaqcoretypes-64-3.so",
                "libdaqcoreobjects-64-3.so",
                "libopendaq-64-3.so",
                "libcopendaq.so");
            case MACOS -> List.of(
                "libdaqcoretypes-64-3.dylib",
                "libdaqcoreobjects-64-3.dylib",
                "libopendaq-64-3.dylib",
                "libcopendaq.dylib");
            case WINDOWS -> List.of(
                "daqcoretypes-64-3.dll",
                "daqcoreobjects-64-3.dll",
                "opendaq-64-3.dll",
                "copendaq.dll");
        };
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return (value != null && !value.isEmpty()) ? value : null;
    }

    private static Path environmentNativeDirectory() {
        String value = env(NATIVE_DIR_ENV_VAR);
        if (value == null) value = System.getProperty(NATIVE_DIR_PROPERTY);
        if (value == null || value.isEmpty()) return null;
        Path dir = Path.of(value);
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException(
                "The " + NATIVE_DIR_ENV_VAR + " override points to a missing directory: " + value);
        }
        return dir;
    }

    // ------------------------------------------------------------------
    // Classpath natives jar -> per-user extraction cache
    // ------------------------------------------------------------------

    /** Base of the per-user cache the natives jars extract into (before the tag/platform). */
    private static Path cacheBaseDir() {
        if (currentOs() == Os.WINDOWS) {
            String base = env("LOCALAPPDATA");
            if (base == null) base = System.getProperty("user.home");
            return Path.of(base, "cache", "java-opendaq");
        }
        String xdg = env("XDG_CACHE_HOME");
        Path base = (xdg != null) ? Path.of(xdg) : Path.of(System.getProperty("user.home"), ".cache");
        return base.resolve("java-opendaq");
    }

    private static Path classpathNativeDirectory() {
        return extractFromClasspath(NativeLoader.class.getClassLoader(),
                                    currentPlatformName(), cacheBaseDir());
    }

    /**
     * Extract the libraries packaged under {@code com/opendaq/natives/<platform>/}
     * on {@code loader} into {@code cacheBase/<tag>/<platform>/} and return that
     * directory, or {@code null} when no such natives resource is on the
     * classpath.  Idempotent: a directory already carrying a {@code .complete}
     * marker is reused untouched, so concurrent callers converge on one copy.
     *
     * <p>Package-private and parameterised so a unit test can drive it with a
     * synthetic natives jar and a temporary cache, without real libraries.
     */
    static Path extractFromClasspath(ClassLoader loader, String platform, Path cacheBase) {
        String root = NATIVES_RESOURCE_ROOT + platform + "/";
        List<String> entries = readIndex(loader, root + "index.txt");
        if (entries == null) return null;            // no natives jar for this platform on the classpath
        String tag = readResourceLine(loader, root + "tag.txt", "untagged");
        Path target = cacheBase.resolve(tag).resolve(platform);
        Path complete = target.resolve(".complete");
        if (Files.isRegularFile(complete)) return target;
        try {
            Files.createDirectories(cacheBase);
            Path staging = Files.createTempDirectory(cacheBase, platform + "-");
            for (String name : entries) {
                try (InputStream in = loader.getResourceAsStream(root + name)) {
                    if (in == null) {
                        throw new IOException(
                            "natives jar is missing " + root + name + " (listed in index.txt)");
                    }
                    Files.copy(in, staging.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Files.writeString(staging.resolve(".complete"), tag + "\n");
            Files.createDirectories(target.getParent());
            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException | UnsupportedOperationException raced) {
                // Another JVM populated the target first, or the filesystem
                // cannot atomically move onto an existing directory.
                if (Files.isRegularFile(complete)) {
                    deleteRecursively(staging);
                } else {
                    deleteRecursively(target);
                    Files.move(staging, target);
                }
            }
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(
                "Failed to extract openDAQ native libraries from the classpath", e);
        }
    }

    private static List<String> readIndex(ClassLoader loader, String resource) {
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) return null;
            List<String> names = new ArrayList<>();
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : text.split("\n")) {
                String name = line.strip();
                if (!name.isEmpty()) names.add(name);
            }
            return names;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String readResourceLine(ClassLoader loader, String resource, String fallback) {
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) return fallback;
            String value = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
            return value.isEmpty() ? fallback : value;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    // ------------------------------------------------------------------
    // Locating and loading
    // ------------------------------------------------------------------

    /**
     * The directory the native openDAQ libraries live in, resolving the search
     * order documented on this class (extracting a classpath natives jar into
     * the per-user cache when necessary).
     */
    public static synchronized Path nativeLibraryDirectory() {
        Path fromEnv = environmentNativeDirectory();
        if (fromEnv != null) return fromEnv;
        Path fromClasspath = classpathNativeDirectory();
        if (fromClasspath != null) return fromClasspath;
        throw new IllegalStateException(
            "Unable to locate the native openDAQ libraries. Add the "
            + "com.opendaq:jopendaq:<version>:natives-" + currentPlatformName()
            + " jar to your classpath, or set " + NATIVE_DIR_ENV_VAR
            + " (or -D" + NATIVE_DIR_PROPERTY + ") to a directory containing them.");
    }

    /**
     * Load the openDAQ native libraries (idempotent) and return the combined
     * symbol lookup over them.
     */
    public static synchronized SymbolLookup ensureLoaded() {
        if (lookup != null) return lookup;
        if (loadError != null) throw loadError;
        try {
            Path directory = nativeLibraryDirectory();
            List<Path> paths = new ArrayList<>();
            SymbolLookup combined = null;
            for (String fileName : libraryFileNames()) {
                Path libraryPath = directory.resolve(fileName);
                if (!Files.exists(libraryPath)) {
                    throw new IllegalStateException(
                        "Could not find " + fileName + " in " + directory + "."
                        + (fileName.contains("copendaq")
                           ? " Build openDAQ with OPENDAQ_GENERATE_C_BINDINGS=ON so the C wrapper library is produced."
                           : ""));
                }
                SymbolLookup one = SymbolLookup.libraryLookup(libraryPath, Arena.global());
                combined = (combined == null) ? one : combined.or(one);
                paths.add(libraryPath);
            }
            loadedNativeDirectory = directory;
            loadedLibraryPaths = paths;
            lookup = combined;
            return lookup;
        } catch (RuntimeException e) {
            loadError = e;
            throw e;
        }
    }

    /** The directory the natives were loaded from, or null when not yet loaded. */
    public static synchronized Path loadedNativeDirectory() {
        return loadedNativeDirectory;
    }

    /**
     * Print a diagnostic report of where the loader looked for the native
     * libraries and what it found, to help debug load failures.
     */
    public static synchronized void healthcheck(PrintStream out) {
        out.println("openDAQ healthcheck");
        out.println("  status: " + (lookup != null ? "loaded"
            : loadError != null ? "load-failed" : "not-loaded"));
        if (loadedNativeDirectory != null) {
            out.println("  loaded native directory: " + loadedNativeDirectory);
            for (Path p : loadedLibraryPaths) out.println("  library: " + p);
        }
        String modulePath = env(MODULES_ENV_VAR);
        if (modulePath != null) out.println("  " + MODULES_ENV_VAR + ": " + modulePath);
        try {
            Path fromEnv = environmentNativeDirectory();
            out.println("  candidate environment: "
                + (fromEnv != null ? fromEnv + " (exists)" : "(not set)"));
        } catch (RuntimeException e) {
            out.println("  candidate environment: ERROR: " + e.getMessage());
        }
        String root = NATIVES_RESOURCE_ROOT + currentPlatformName() + "/";
        boolean onClasspath =
            NativeLoader.class.getClassLoader().getResource(root + "index.txt") != null;
        out.println("  candidate classpath natives jar: "
            + (onClasspath ? "present (" + root + ")" : "absent — add the natives-"
               + currentPlatformName() + " jar"));
        if (loadError != null) out.println("  load error: " + loadError.getMessage());
        Path resolved = loadedNativeDirectory;
        if (resolved == null) {
            try {
                resolved = nativeLibraryDirectory();
            } catch (RuntimeException e) {
                out.println("  resolve error: " + e.getMessage());
            }
        }
        if (resolved != null) {
            for (String fileName : libraryFileNames()) {
                Path libraryPath = resolved.resolve(fileName);
                out.println("  library " + fileName + ": "
                    + (Files.exists(libraryPath) ? libraryPath.toString() : "MISSING"));
            }
        }
    }
}
