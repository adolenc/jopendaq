package org.opendaq.lowlevel;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;

/**
 * Locates and loads the native openDAQ libraries.
 *
 * <p>The loader looks for the libraries in this order:
 * <ol>
 *   <li>the directory named by the {@code OPENDAQ_JAVA_NATIVE_DIR} environment
 *       variable (or the {@code opendaq.native.dir} system property),</li>
 *   <li>the per-user download cache, downloading the release archive pinned in
 *       {@code native-binaries.properties} into it when necessary.</li>
 * </ol>
 *
 * <p>Set {@code OPENDAQ_NO_DOWNLOAD} to forbid the automatic download (e.g. in
 * offline or CI environments); you can still trigger it explicitly with
 * {@link #installNativeLibraries()}.  Set {@code OPENDAQ_NATIVE_ARCHIVE_URL} to
 * fetch the archive from a mirror instead (checksum verification is skipped
 * for mirrors).
 */
public final class NativeLoader {

    private NativeLoader() {}

    static final String NATIVE_DIR_ENV_VAR = "OPENDAQ_JAVA_NATIVE_DIR";
    static final String NATIVE_DIR_PROPERTY = "opendaq.native.dir";
    static final String NO_DOWNLOAD_ENV_VAR = "OPENDAQ_NO_DOWNLOAD";
    static final String ARCHIVE_URL_ENV_VAR = "OPENDAQ_NATIVE_ARCHIVE_URL";
    static final String MODULES_ENV_VAR = "OPENDAQ_MODULES_PATH";

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

    /** Name of the platform directory holding this host's natives, e.g. "linux-x64". */
    public static String currentPlatformName() {
        String os = switch (currentOs()) {
            case LINUX -> "linux";
            case MACOS -> "darwin";
            case WINDOWS -> "windows";
        };
        String archName = System.getProperty("os.arch", "").toLowerCase();
        String arch = switch (archName) {
            case "amd64", "x86_64" -> "x64";
            case "aarch64", "arm64" -> "arm64";
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
    // Release archive manifest and download cache
    // ------------------------------------------------------------------

    private static Properties manifest;

    private static synchronized Properties manifest() {
        if (manifest == null) {
            manifest = new Properties();
            try (InputStream in = NativeLoader.class.getResourceAsStream(
                    "/org/opendaq/native-binaries.properties")) {
                if (in != null) manifest.load(in);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return manifest;
    }

    private static Path cacheRoot() {
        String tag = manifest().getProperty("tag", "untagged");
        if (currentOs() == Os.WINDOWS) {
            String base = env("LOCALAPPDATA");
            if (base == null) base = System.getProperty("user.home");
            return Path.of(base, "cache", "java-opendaq", tag);
        }
        String xdg = env("XDG_CACHE_HOME");
        Path base = (xdg != null) ? Path.of(xdg) : Path.of(System.getProperty("user.home"), ".cache");
        return base.resolve("java-opendaq").resolve(tag);
    }

    private static Path cachedNativeDirectory() {
        Path dir = cacheRoot().resolve(currentPlatformName());
        return Files.isDirectory(dir) ? dir : null;
    }

    private static boolean downloadsDisabled() {
        return env(NO_DOWNLOAD_ENV_VAR) != null;
    }

    /**
     * Download the pinned native-binaries release archive for this platform
     * into the per-user cache, verify its SHA-256 checksum, extract it, and
     * return the extracted directory.
     */
    public static synchronized Path installNativeLibraries() {
        Properties m = manifest();
        String platform = currentPlatformName();
        String archiveName = m.getProperty("archive." + platform);
        String expectedSha = m.getProperty("sha256." + platform);
        if (archiveName == null) {
            throw new IllegalStateException(
                "No pinned native-binaries archive for platform " + platform + ".");
        }
        String url = env(ARCHIVE_URL_ENV_VAR);
        boolean verify = (url == null);
        if (url == null) url = m.getProperty("base-url") + archiveName;

        Path target = cacheRoot().resolve(platform);
        Path archive = cacheRoot().resolve(archiveName);
        try {
            Files.createDirectories(cacheRoot());
            System.err.println("[java-opendaq] downloading native openDAQ libraries from " + url);
            HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();
            HttpResponse<Path> response = client.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofFile(archive));
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " fetching " + url);
            }
            if (verify) {
                String actual = sha256(archive);
                if (!actual.equalsIgnoreCase(expectedSha)) {
                    Files.deleteIfExists(archive);
                    throw new IOException("SHA-256 mismatch for " + archiveName
                        + ": expected " + expectedSha + ", got " + actual);
                }
            }
            Files.createDirectories(target);
            Process tar = new ProcessBuilder("tar", "-xzf", archive.toString(), "-C", target.toString())
                .inheritIO().start();
            int status = tar.waitFor();
            if (status != 0) throw new IOException("tar exited with status " + status);
            Files.deleteIfExists(archive);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to install openDAQ native libraries", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while installing openDAQ native libraries", e);
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[1 << 16];
                int n;
                while ((n = in.read(buffer)) > 0) digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------
    // Locating and loading
    // ------------------------------------------------------------------

    /**
     * The directory the native openDAQ libraries live in, resolving the search
     * order documented on this class (and downloading them when necessary).
     */
    public static synchronized Path nativeLibraryDirectory() {
        Path fromEnv = environmentNativeDirectory();
        if (fromEnv != null) return fromEnv;
        Path cached = cachedNativeDirectory();
        if (cached != null) return cached;
        if (!manifest().isEmpty() && !downloadsDisabled()) {
            return installNativeLibraries();
        }
        throw new IllegalStateException(
            "Unable to locate openDAQ native libraries. Set " + NATIVE_DIR_ENV_VAR
            + " (or -D" + NATIVE_DIR_PROPERTY + ") to the directory containing them"
            + (downloadsDisabled()
               ? "; automatic download is disabled because " + NO_DOWNLOAD_ENV_VAR
                 + " is set (unset it, or call NativeLoader.installNativeLibraries() yourself)."
               : ", or allow the automatic download."));
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
        Path cached = cachedNativeDirectory();
        out.println("  candidate download-cache: "
            + cacheRoot().resolve(currentPlatformName())
            + (cached != null ? " (exists)" : " (missing)"));
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
