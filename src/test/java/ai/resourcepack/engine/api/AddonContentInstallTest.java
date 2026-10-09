package ai.resourcepack.engine.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An addon's jar is a file somebody else built, so nothing in it may be written
 * outside the content folder it is installed into.
 */
class AddonContentInstallTest {

    @TempDir
    Path dir;

    @Test
    void anEntryNamingAnAbsolutePathIsSkipped() throws IOException {
        Path outside = Files.createDirectories(dir.resolve("outside"));
        Path into = dir.resolve("content").resolve("ns");
        // `content/ns/` followed by an absolute path: `content/ns//tmp/...` on
        // Linux, `content/ns/C:/...` on Windows. Either resolves past `into`.
        String absolute = outside.toAbsolutePath().toString().replace('\\', '/');
        Path jar = jar("content/ns/items/ok.yml",
                "content/ns/" + absolute + "/escaped.yml",
                "content/ns/../escaped-dots.yml");

        AddonContent.install(jar.toFile(), "content/ns/", into, "1.0");

        assertTrue(Files.isRegularFile(into.resolve("items/ok.yml")), "the ordinary entry is installed");
        assertFalse(Files.exists(outside.resolve("escaped.yml")), "an absolute path is not followed");
        assertFalse(Files.exists(dir.resolve("content").resolve("escaped-dots.yml")));
    }

    private Path jar(String... entries) throws IOException {
        Path jar = dir.resolve("addon.jar");
        try (OutputStream file = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(file)) {
            for (String name : entries) {
                out.putNextEntry(new JarEntry(name));
                out.write("x: 1\n".getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return jar;
    }
}
