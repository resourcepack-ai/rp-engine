package ai.resourcepack.engine.core.distribution;

import ai.resourcepack.engine.core.pack.PlainPack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudioPackMirrorTest {

    @TempDir
    Path root;

    private Path content;
    private Path data;

    @BeforeEach
    void setUp() throws IOException {
        data = root.resolve("RPEngine");
        content = data.resolve("content");
        Files.createDirectories(content);
    }

    private Path zip(String name, Map<String, String> files) throws IOException {
        Path zip = root.resolve(name);
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                z.putNextEntry(new ZipEntry(file.getKey()));
                z.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return zip;
    }

    private Path release(String name, String texture) throws IOException {
        return zip(name, Map.of(
                "pack.mcmeta", "{\"pack\":{\"pack_format\":46,\"description\":\"x\"}}",
                "assets/minecraft/textures/block/stone.png", texture));
    }

    private static long backups(Path data) throws IOException {
        Path dir = data.resolve(StudioPackMirror.BACKUPS);
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).count();
        }
    }

    @Test
    void installsTheReleaseAsAPlainPackFolder() throws IOException {
        StudioPackMirror mirror = new StudioPackMirror(content, data);
        StudioPackMirror.Installed done = mirror.install(release("a.zip", "one"), "bks8wbem", "r1", "{\"huds\":[]}");

        Path folder = content.resolve("studio-bks8wbem");
        assertEquals("studio-bks8wbem", done.folder());
        assertTrue(PlainPack.is(folder));
        assertEquals("one", Files.readString(folder.resolve("assets/minecraft/textures/block/stone.png")));
        assertTrue(Files.isRegularFile(folder.resolve(StudioPackMirror.CONTENT_FILE)));
        assertTrue(Files.isRegularFile(folder.resolve(StudioPackMirror.README)));
        assertEquals("r1", mirror.releaseId());
        assertEquals(0, done.keptEdits());
        // The content manifest is found where a dropped-in export's is.
        assertTrue(PlainPack.studioManifests(content, StudioPackMirror.CONTENT_FILE).containsKey("studio-bks8wbem"));
    }

    @Test
    void stateSurvivesARestart() throws IOException {
        new StudioPackMirror(content, data).install(release("a.zip", "one"), "p", "r1", null);
        StudioPackMirror again = new StudioPackMirror(content, data);
        assertEquals("r1", again.releaseId());
        assertEquals("studio-p", again.folder());
    }

    @Test
    void aNewReleaseReplacesTheFolderAndBacksUpHandEdits() throws IOException {
        StudioPackMirror mirror = new StudioPackMirror(content, data);
        mirror.install(release("a.zip", "one"), "p", "r1", null);
        Path folder = content.resolve("studio-p");
        Files.writeString(folder.resolve("assets/minecraft/textures/block/stone.png"), "mine");
        Files.writeString(folder.resolve("assets/minecraft/extra.txt"), "added");

        StudioPackMirror.Installed done = mirror.install(release("b.zip", "two"), "p", "r2", null);

        assertEquals("two", Files.readString(folder.resolve("assets/minecraft/textures/block/stone.png")));
        assertFalse(Files.exists(folder.resolve("assets/minecraft/extra.txt")));
        assertEquals(2, done.keptEdits());
        assertEquals(2, backups(data));
        assertEquals("mine", Files.readString(done.backup().resolve("assets/minecraft/textures/block/stone.png")));
        assertEquals("r2", mirror.releaseId());
    }

    @Test
    void anUntouchedFolderNeedsNoBackup() throws IOException {
        StudioPackMirror mirror = new StudioPackMirror(content, data);
        mirror.install(release("a.zip", "one"), "p", "r1", "{}");
        StudioPackMirror.Installed done = mirror.install(release("b.zip", "two"), "p", "r2", "{}");
        assertEquals(0, done.keptEdits());
        assertNull(done.backup());
        assertEquals(0, backups(data));
    }

    @Test
    void aFolderItNeverWroteIsBackedUpWhole() throws IOException {
        Path theirs = content.resolve("studio-p");
        Files.createDirectories(theirs);
        Files.writeString(theirs.resolve("notes.txt"), "keep me");

        StudioPackMirror.Installed done = new StudioPackMirror(content, data)
                .install(release("a.zip", "one"), "p", "r1", null);

        assertEquals(1, done.keptEdits());
        assertEquals("keep me", Files.readString(done.backup().resolve("notes.txt")));
    }

    @Test
    void aBadDownloadLeavesTheLiveFolderAlone() throws IOException {
        StudioPackMirror mirror = new StudioPackMirror(content, data);
        mirror.install(release("a.zip", "one"), "p", "r1", null);
        Path notAPack = zip("bad.zip", Map.of("readme.txt", "hello"));

        assertThrows(IOException.class, () -> mirror.install(notAPack, "p", "r2", null));
        assertEquals("one", Files.readString(content.resolve("studio-p/assets/minecraft/textures/block/stone.png")));
        assertEquals("r1", mirror.releaseId());
    }

    @Test
    void anotherPackTakesTheOldFolderAway() throws IOException {
        StudioPackMirror mirror = new StudioPackMirror(content, data);
        mirror.install(release("a.zip", "one"), "first", "r1", null);
        mirror.install(release("b.zip", "two"), "second", "r2", null);
        assertFalse(Files.exists(content.resolve("studio-first")));
        assertTrue(Files.isDirectory(content.resolve("studio-second")));
    }

    @Test
    void unbindingRemovesTheFolderOrLeavesItAsTheOwners() throws IOException {
        StudioPackMirror mirror = new StudioPackMirror(content, data);
        mirror.install(release("a.zip", "one"), "p", "r1", null);
        assertTrue(mirror.remove(false));
        assertFalse(Files.exists(content.resolve("studio-p")));
        assertFalse(mirror.remove(false));

        mirror.install(release("b.zip", "two"), "p", "r2", null);
        assertTrue(mirror.remove(true));
        assertTrue(PlainPack.is(content.resolve("studio-p")));
        assertFalse(Files.exists(content.resolve("studio-p").resolve(StudioPackMirror.README)));
        assertEquals("", new StudioPackMirror(content, data).releaseId());
    }

    @Test
    void folderNamesAreNamespaces() {
        assertEquals("studio-bks8wbem", StudioPackMirror.folderFor("bks8wbem"));
        assertEquals("studio-my_pack", StudioPackMirror.folderFor("My Pack"));
        assertEquals("studio-pack", StudioPackMirror.folderFor(""));
        assertEquals("studio-pack", StudioPackMirror.folderFor(null));
    }

    @Test
    void versionsCompareNumerically() {
        assertTrue(DistributionManager.compareVersions(
                DistributionManager.versionParts("1.21.10"), DistributionManager.versionParts("1.21.4")) > 0);
        assertEquals(0, DistributionManager.compareVersions(
                DistributionManager.versionParts("1.21"), DistributionManager.versionParts("1.21.0")));
        assertTrue(DistributionManager.compareVersions(
                DistributionManager.versionParts("26.2"), DistributionManager.versionParts("1.21.11")) > 0);
        assertNull(DistributionManager.versionParts("bedrock"));
    }
}
