package ai.resourcepack.engine.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An addon's jar is a file somebody else built, so nothing in it may be written
 * outside the content folder it is installed into; and the folder is the server
 * owner's, so an update must not throw away what they changed in it.
 */
class AddonContentInstallTest {

    @TempDir
    Path dir;

    private final List<String> warnings = new ArrayList<>();

    private final Logger log = logger();

    private Logger logger() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                warnings.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return logger;
    }

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

    @Test
    void anUpdateKeepsWhatTheOwnerChanged() throws IOException {
        Path into = dir.resolve("content").resolve("ns");
        assertTrue(install(into, "1.0", Map.of("items/a.yml", "a: 1\n", "items/b.yml", "b: 1\n")));
        assertTrue(Files.isRegularFile(into.resolve(AddonContent.MANIFEST)));

        // The owner fixes b; the addon changes both and adds c.
        Files.writeString(into.resolve("items/b.yml"), "b: owner\n");
        assertTrue(install(into, "2.0",
                Map.of("items/a.yml", "a: 2\n", "items/b.yml", "b: 2\n", "items/c.yml", "c: 2\n")));

        assertEquals("a: 2\n", read(into, "items/a.yml"), "an untouched file is updated");
        assertEquals("b: owner\n", read(into, "items/b.yml"), "an edited file is kept");
        assertEquals("c: 2\n", read(into, "items/c.yml"), "a new file is added");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("items/b.yml") && w.contains("delete")), warnings.toString());

        // Still the owner's on the update after, though nothing was written to it.
        warnings.clear();
        install(into, "3.0", Map.of("items/a.yml", "a: 2\n", "items/b.yml", "b: 3\n", "items/c.yml", "c: 2\n"));
        assertEquals("b: owner\n", read(into, "items/b.yml"));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("items/b.yml")), warnings.toString());

        // Deleted, it is installed again.
        Files.delete(into.resolve("items/b.yml"));
        install(into, "4.0", Map.of("items/a.yml", "a: 2\n", "items/b.yml", "b: 4\n", "items/c.yml", "c: 2\n"));
        assertEquals("b: 4\n", read(into, "items/b.yml"));
        // And from then on it is the addon's again.
        install(into, "5.0", Map.of("items/a.yml", "a: 2\n", "items/b.yml", "b: 5\n", "items/c.yml", "c: 2\n"));
        assertEquals("b: 5\n", read(into, "items/b.yml"));
    }

    @Test
    void aFolderFromBeforeTheRecordUpdatesAsItAlwaysDidThenIsProtected() throws IOException {
        Path into = Files.createDirectories(dir.resolve("content").resolve("ns").resolve("items")).getParent();
        // As an older engine left it: the files and a stamp, no hashes.
        Files.writeString(into.resolve("items/same.yml"), "same\n");
        Files.writeString(into.resolve("items/differs.yml"), "old or edited\n");
        Files.writeString(into.resolve(AddonContent.STAMP), "1.0\n");

        boolean wrote = install(into, "2.0", Map.of("items/same.yml", "same\n", "items/differs.yml", "new\n"));

        assertTrue(wrote);
        assertEquals("new\n", read(into, "items/differs.yml"), "no record: written over, as before");
        assertTrue(warnings.isEmpty(), warnings.toString());
        Map<String, String> record = AddonContent.readManifest(into.resolve(AddonContent.MANIFEST));
        assertTrue(record.containsKey("items/same.yml") && record.containsKey("items/differs.yml"), record.toString());

        // From the next update on, an edit is kept.
        Files.writeString(into.resolve("items/differs.yml"), "owner\n");
        install(into, "3.0", Map.of("items/same.yml", "same\n", "items/differs.yml", "newer\n"));
        assertEquals("owner\n", read(into, "items/differs.yml"));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("items/differs.yml")), warnings.toString());
    }

    @Test
    void theSameBuildTouchesNothing() throws IOException {
        Path into = dir.resolve("content").resolve("ns");
        install(into, "1.0", Map.of("items/a.yml", "a: 1\n"));
        Files.writeString(into.resolve("items/a.yml"), "a: owner\n");

        assertFalse(install(into, "1.0", Map.of("items/a.yml", "a: 1\n")));
        assertEquals("a: owner\n", read(into, "items/a.yml"));
    }

    private boolean install(Path into, String version, Map<String, String> files) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        files.forEach((path, text) -> entries.put("content/ns/" + path, text));
        Path jar = dir.resolve("addon-" + version + ".jar");
        try (OutputStream file = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(file)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return AddonContent.install(jar.toFile(), "content/ns/", into, version, log);
    }

    private static String read(Path into, String path) throws IOException {
        return Files.readString(into.resolve(path), StandardCharsets.UTF_8);
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
