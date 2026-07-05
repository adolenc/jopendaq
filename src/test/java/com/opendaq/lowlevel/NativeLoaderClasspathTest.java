package com.opendaq.lowlevel;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit coverage for the classpath -> per-user-cache extraction that replaces
 * the old release download.  Drives {@link NativeLoader#extractFromClasspath}
 * with a synthetic natives "jar" (a directory on a URLClassLoader) and fake
 * library files, so it needs no real openDAQ binaries and is platform-neutral.
 */
class NativeLoaderClasspathTest {

    /** Lay out com/opendaq/natives/<platform>/ with an index, tag, and two fake libs under {@code root}. */
    private static void writeNativesResource(Path root, String platform, String tag) throws Exception {
        Path dir = root.resolve("com/opendaq/natives/" + platform);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("index.txt"), "liba.so\nlibb.so\n");
        Files.writeString(dir.resolve("tag.txt"), tag + "\n");
        Files.write(dir.resolve("liba.so"), new byte[] {1, 2, 3});
        Files.write(dir.resolve("libb.so"), new byte[] {4, 5});
    }

    @Test
    void extractsIntoTagKeyedCacheAndIsIdempotent(@TempDir Path tmp) throws Exception {
        Path resources = tmp.resolve("classpath");
        writeNativesResource(resources, "testplat-x64", "tag-abc123");
        Path cacheBase = tmp.resolve("cache");

        try (URLClassLoader loader = new URLClassLoader(new URL[] {resources.toUri().toURL()}, null)) {
            Path extracted = NativeLoader.extractFromClasspath(loader, "testplat-x64", cacheBase);

            // Lands in cacheBase/<tag>/<platform>/ with every indexed file plus the marker.
            assertEquals(cacheBase.resolve("tag-abc123").resolve("testplat-x64"), extracted);
            assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(extracted.resolve("liba.so")));
            assertArrayEquals(new byte[] {4, 5}, Files.readAllBytes(extracted.resolve("libb.so")));
            assertTrue(Files.isRegularFile(extracted.resolve(".complete")), "a .complete marker is written");

            // A second call reuses the completed directory rather than re-extracting.
            byte[] before = Files.readAllBytes(extracted.resolve("liba.so"));
            Files.write(extracted.resolve("liba.so"), new byte[] {9});   // tamper to prove no re-copy
            Path again = NativeLoader.extractFromClasspath(loader, "testplat-x64", cacheBase);
            assertEquals(extracted, again);
            assertArrayEquals(new byte[] {9}, Files.readAllBytes(again.resolve("liba.so")),
                "an already-complete cache is reused untouched");
            assertNotNull(before);
        }
    }

    @Test
    void returnsNullWhenNoNativesJarForPlatform(@TempDir Path tmp) throws Exception {
        Path resources = tmp.resolve("classpath");
        writeNativesResource(resources, "testplat-x64", "tag-abc123");
        Path cacheBase = tmp.resolve("cache");

        try (URLClassLoader loader = new URLClassLoader(new URL[] {resources.toUri().toURL()}, null)) {
            // No resources packaged for this platform -> loader must report absence, not throw.
            assertNull(NativeLoader.extractFromClasspath(loader, "otherplat-arm64", cacheBase));
        }
    }
}
