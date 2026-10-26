package ai.resourcepack.engine.core.pack;

import ai.resourcepack.engine.api.BuildReport;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.core.content.ContentFolderLoader;
import ai.resourcepack.engine.core.registry.ContentRegistryImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An ordinary resource pack, as a folder or a zip, dropped into the content folder. */
class PlainPackTest {

    @TempDir
    Path root;

    private Path content;
    private Path out;
    private Path unpacked;

    private static final String OVERLAY_MCMETA = """
            {"pack": {"pack_format": 46, "description": "Shaders"},
             "overlays": {"entries": [{"formats": [46, 64], "directory": "shaders_new"}]}}
            """;

    @BeforeEach
    void setUp() throws IOException {
        content = root.resolve("content");
        out = root.resolve("out");
        unpacked = root.resolve(PlainPack.UNPACKED);
        Files.createDirectories(content);
    }

    private void write(String path, String text) throws IOException {
        Path file = content.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private void zip(String name, Map<String, String> files) throws IOException {
        try (OutputStream file = Files.newOutputStream(content.resolve(name));
             ZipOutputStream zip = new ZipOutputStream(file)) {
            for (Map.Entry<String, String> entry : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

    private LoadReport load() {
        return new ContentFolderLoader(new ContentRegistryImpl()).load(content, ContentSource.AUTHORED);
    }

    private static Map<String, String> read(Path zip) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (InputStream in = Files.newInputStream(zip);
             ZipInputStream zin = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zin.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    private static String errors(List<Diagnostic> diagnostics) {
        return diagnostics.stream().map(Diagnostic::toString).toList().toString();
    }

    @Test
    void aFolderIsCopiedAsWrittenWithItsOverlays() throws IOException {
        write("shaders/pack.mcmeta", OVERLAY_MCMETA);
        write("shaders/pack.png", "ICON");
        write("shaders/assets/minecraft/shaders/core/rendertype_text.fsh", "BASE");
        write("shaders/assets/other/textures/thing.png", "THING");
        write("shaders/shaders_new/assets/minecraft/shaders/core/rendertype_text.fsh", "NEW");

        LoadReport loaded = load();
        assertTrue(loaded.diagnostics().isEmpty(), errors(loaded.diagnostics()));
        BuildReport report = new PackBuilder().build(content, out, loaded);
        assertFalse(report.hasErrors(), errors(report.diagnostics()));
        Map<String, String> entries = read(report.pack("main").orElseThrow().file());

        assertEquals("BASE", entries.get("assets/minecraft/shaders/core/rendertype_text.fsh"));
        assertEquals("THING", entries.get("assets/other/textures/thing.png"));
        assertEquals("NEW", entries.get("shaders_new/assets/minecraft/shaders/core/rendertype_text.fsh"));
        assertEquals("ICON", entries.get("pack.png"));
        assertTrue(entries.get("pack.mcmeta").contains("shaders_new"), entries.get("pack.mcmeta"));
        // Not under its own namespace, which is what assets/ means in a content pack.
        assertFalse(entries.keySet().stream().anyMatch(path -> path.startsWith("assets/shaders/")),
                entries.keySet().toString());
    }

    @Test
    void keepsItsFilterAndLanguages() throws IOException {
        write("alpha/pack.mcmeta", """
                {"pack": {"pack_format": 46, "description": "A"},
                 "filter": {"block": [{"namespace": "minecraft", "path": "textures/block/dirt.png"}]},
                 "language": {"pirate_xx": {"name": "Pirate", "region": "Seas", "bidirectional": false}}}
                """);
        write("alpha/assets/minecraft/lang/pirate_xx.json", "{}");
        write("beta/pack.mcmeta", """
                {"pack": {"pack_format": 46, "description": "B"},
                 "filter": {"block": [{"namespace": "other"}]},
                 "language": {"pirate_xx": {"name": "Arr", "region": "Seas", "bidirectional": false}}}
                """);
        write("beta/assets/other/textures/x.png", "X");

        BuildReport report = new PackBuilder().build(content, out, load());
        String mcmeta = read(report.pack("main").orElseThrow().file()).get("pack.mcmeta");
        com.google.gson.JsonObject meta = com.google.gson.JsonParser.parseString(mcmeta).getAsJsonObject();

        assertEquals(2, meta.getAsJsonObject("filter").getAsJsonArray("block").size(), mcmeta);
        assertEquals("Pirate", meta.getAsJsonObject("language").getAsJsonObject("pirate_xx")
                .get("name").getAsString(), mcmeta);
        assertTrue(report.diagnostics().stream().anyMatch(d -> d.toString().contains("pirate_xx")),
                errors(report.diagnostics()));
    }

    @Test
    void anOrdinaryBuildHasNeither() throws IOException {
        write("mypack/pack.yml", "{}\n");

        String mcmeta = read(new PackBuilder().build(content, out, load()).pack("main").orElseThrow().file())
                .get("pack.mcmeta");

        assertFalse(mcmeta.contains("filter"), mcmeta);
        assertFalse(mcmeta.contains("language"), mcmeta);
    }

    @Test
    void sitsBesideAContentPack() throws IOException {
        write("shaders/pack.mcmeta", OVERLAY_MCMETA);
        write("shaders/assets/minecraft/textures/block/stone.png", "STONE");
        write("mypack/pack.yml", "{}\n");
        write("mypack/assets/textures/item/ruby.png", "RUBY");

        Map<String, String> entries = read(new PackBuilder().build(content, out, load())
                .pack("main").orElseThrow().file());

        assertEquals("STONE", entries.get("assets/minecraft/textures/block/stone.png"));
        assertEquals("RUBY", entries.get("assets/mypack/textures/item/ruby.png"));
    }

    @Test
    void aFolderWithPackYmlIsStillAContentPack() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/pack.mcmeta", OVERLAY_MCMETA);
        write("mypack/assets/textures/item/ruby.png", "RUBY");

        Map<String, String> entries = read(new PackBuilder().build(content, out, load())
                .pack("main").orElseThrow().file());

        assertEquals("RUBY", entries.get("assets/mypack/textures/item/ruby.png"));
    }

    @Test
    void aZipIsUnpackedIntoAFolderAndMovedAside() throws IOException {
        zip("My Shaders v2.zip", Map.of(
                "pack.mcmeta", OVERLAY_MCMETA,
                "assets/minecraft/shaders/core/rendertype_text.fsh", "BASE"));

        PlainPack.Unpacked result = PlainPack.unpackZips(content, unpacked);

        assertTrue(result.diagnostics().isEmpty(), errors(result.diagnostics()));
        assertEquals(List.of("My Shaders v2.zip -> my_shaders_v2/"), result.unpacked());
        assertTrue(PlainPack.is(content.resolve("my_shaders_v2")));
        assertFalse(Files.exists(content.resolve("My Shaders v2.zip")));
        assertTrue(Files.exists(unpacked.resolve("My Shaders v2.zip")));

        Map<String, String> entries = read(new PackBuilder().build(content, out, load())
                .pack("main").orElseThrow().file());
        assertEquals("BASE", entries.get("assets/minecraft/shaders/core/rendertype_text.fsh"));
    }

    @Test
    void aZipWithEverythingInOneFolderIsUnwrapped() throws IOException {
        zip("pack.zip", Map.of(
                "Pack/pack.mcmeta", OVERLAY_MCMETA,
                "Pack/assets/minecraft/textures/block/stone.png", "STONE"));

        PlainPack.unpackZips(content, unpacked);

        assertEquals("STONE", Files.readString(
                content.resolve("pack/assets/minecraft/textures/block/stone.png")));
    }

    @Test
    void neverUnpacksOverAFolder() throws IOException {
        write("pack/pack.mcmeta", OVERLAY_MCMETA);
        write("pack/assets/minecraft/textures/block/stone.png", "MINE");
        zip("pack.zip", Map.of(
                "pack.mcmeta", OVERLAY_MCMETA,
                "assets/minecraft/textures/block/stone.png", "THEIRS"));

        PlainPack.Unpacked result = PlainPack.unpackZips(content, unpacked);

        assertTrue(result.unpacked().isEmpty());
        assertEquals(1, result.diagnostics().size());
        assertEquals("MINE", Files.readString(content.resolve("pack/assets/minecraft/textures/block/stone.png")));
        assertTrue(Files.exists(content.resolve("pack.zip")));
    }

    @Test
    void leavesAZipThatIsNotAResourcePack() throws IOException {
        zip("backup.zip", Map.of("world/level.dat", "LEVEL"));

        PlainPack.Unpacked result = PlainPack.unpackZips(content, unpacked);

        assertTrue(result.unpacked().isEmpty());
        assertEquals(1, result.diagnostics().size());
        assertTrue(Files.exists(content.resolve("backup.zip")));
        assertFalse(Files.exists(content.resolve("backup")));
    }

    @Test
    void anEntryCannotEscapeItsFolder() throws IOException {
        zip("evil.zip", Map.of(
                "pack.mcmeta", OVERLAY_MCMETA,
                "assets/minecraft/textures/block/stone.png", "STONE",
                "../../outside.txt", "NO"));

        PlainPack.unpackZips(content, unpacked);

        assertFalse(Files.exists(root.resolve("outside.txt")));
        assertFalse(Files.exists(content.resolve("outside.txt")));
        assertTrue(Files.exists(content.resolve("evil/assets/minecraft/textures/block/stone.png")));
    }

    @Test
    void namesAZipLikeANamespace() {
        assertEquals("faithful_32x_-_1.21", PlainPack.namespaceFor("Faithful 32x - 1.21.zip"));
        assertEquals("my_shaders", PlainPack.namespaceFor("  My Shaders!!.zip"));
    }
}
