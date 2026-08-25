package ai.resourcepack.engine.core.pack;

import ai.resourcepack.engine.api.Bundle;
import ai.resourcepack.engine.api.BuildReport;
import ai.resourcepack.engine.api.BuiltPack;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.api.PackMeta;
import ai.resourcepack.engine.core.content.ContentFolderLoader;
import ai.resourcepack.engine.core.registry.ContentRegistryImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackBuilderTest {

    @TempDir
    Path root;

    private Path content;
    private Path out;

    @BeforeEach
    void setUp() throws IOException {
        content = root.resolve("content");
        out = root.resolve("out");
        Files.createDirectories(content);
    }

    private void write(String path, String text) throws IOException {
        Path file = content.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private LoadReport load() {
        return new ContentFolderLoader(new ContentRegistryImpl()).load(content, ContentSource.AUTHORED);
    }

    private BuildReport build() {
        return new PackBuilder().build(content, out, load());
    }

    /** Every entry in the zip, in the order it was written. */
    private static Map<String, byte[]> read(Path zip) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (InputStream in = Files.newInputStream(zip);
             ZipInputStream zin = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                entries.put(entry.getName(), zin.readAllBytes());
            }
        }
        return entries;
    }

    private static String text(Map<String, byte[]> entries, String path) {
        return new String(entries.get(path), StandardCharsets.UTF_8);
    }

    // ---- routing -------------------------------------------------------

    @Test
    void assetsAreNamespacedAndOverridesAreNot() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/assets/textures/item/ruby.png", "RUBY");
        write("mypack/overrides/textures/block/stone.png", "STONE");

        BuildReport report = build();
        BuiltPack pack = report.pack("main").orElseThrow();
        Map<String, byte[]> entries = read(pack.file());

        assertEquals("RUBY", text(entries, "assets/mypack/textures/item/ruby.png"));
        assertEquals("STONE", text(entries, "assets/minecraft/textures/block/stone.png"));
        assertFalse(report.hasErrors());
    }

    @Test
    void everyPackGetsAMcmeta() throws IOException {
        write("mypack/pack.yml", "{}\n");

        Map<String, byte[]> entries = read(build().pack("main").orElseThrow().file());

        String mcmeta = text(entries, "pack.mcmeta");
        assertTrue(mcmeta.contains("\"pack_format\": " + PackBuilder.PACK_FORMAT), mcmeta);
        assertTrue(mcmeta.contains("RP Engine - main"), mcmeta);
    }

    @Test
    void aBundleGathersEveryNamespaceThatNamedIt() throws IOException {
        write("alpha/pack.yml", "bundles: [lobby, dungeon]\n");
        write("alpha/assets/textures/a.png", "A");
        write("beta/pack.yml", "bundles: [dungeon]\n");
        write("beta/assets/textures/b.png", "B");

        BuildReport report = build();

        assertEquals(List.of("dungeon", "lobby"),
                report.packs().stream().map(BuiltPack::bundle).toList());

        Map<String, byte[]> dungeon = read(report.pack("dungeon").orElseThrow().file());
        assertTrue(dungeon.containsKey("assets/alpha/textures/a.png"));
        assertTrue(dungeon.containsKey("assets/beta/textures/b.png"));

        Map<String, byte[]> lobby = read(report.pack("lobby").orElseThrow().file());
        assertTrue(lobby.containsKey("assets/alpha/textures/a.png"));
        assertFalse(lobby.containsKey("assets/beta/textures/b.png"),
                "beta never named lobby, so it must not be in it");
    }

    @Test
    void namespacedAssetsCannotCollide() throws IOException {
        write("alpha/pack.yml", "{}\n");
        write("alpha/assets/textures/same.png", "A");
        write("beta/pack.yml", "{}\n");
        write("beta/assets/textures/same.png", "B");

        BuildReport report = build();
        Map<String, byte[]> entries = read(report.pack("main").orElseThrow().file());

        assertEquals("A", text(entries, "assets/alpha/textures/same.png"));
        assertEquals("B", text(entries, "assets/beta/textures/same.png"));
        assertTrue(report.diagnostics().isEmpty(), "the whole point of namespacing them");
    }

    @Test
    void overridesCollideAndSayWho() throws IOException {
        write("alpha/pack.yml", "{}\n");
        write("alpha/overrides/textures/block/stone.png", "A");
        write("beta/pack.yml", "{}\n");
        write("beta/overrides/textures/block/stone.png", "B");

        BuildReport report = build();
        Map<String, byte[]> entries = read(report.pack("main").orElseThrow().file());

        // Later namespace alphabetically wins. Arbitrary, but the same on
        // every machine, which is what the hash needs.
        assertEquals("B", text(entries, "assets/minecraft/textures/block/stone.png"));
        List<Diagnostic> warnings = report.diagnostics(Diagnostic.Severity.WARNING);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).message().contains("alpha"), warnings.get(0).message());
        assertTrue(warnings.get(0).message().contains("beta"), warnings.get(0).message());
    }

    @Test
    void aBundleTakesOneIconAndSaysWhoseWentUnused() throws IOException {
        write("alpha/pack.yml", "{}\n");
        write("alpha/pack.png", "ALPHA_ICON");
        write("beta/pack.yml", "{}\n");
        write("beta/pack.png", "BETA_ICON");

        BuildReport report = build();
        Map<String, byte[]> entries = read(report.pack("main").orElseThrow().file());

        assertEquals("ALPHA_ICON", text(entries, "pack.png"));
        assertEquals(1, report.diagnostics(Diagnostic.Severity.WARNING).size());
    }

    @Test
    void definitionsAndYamlNeverReachTheZip() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/items/gems.yml", "ruby: {}\n");
        write("mypack/assets/textures/item/ruby.png", "RUBY");

        Map<String, byte[]> entries = read(build().pack("main").orElseThrow().file());

        assertEquals(List.of("assets/mypack/textures/item/ruby.png", "pack.mcmeta"),
                entries.keySet().stream().sorted().toList());
    }

    // ---- determinism ---------------------------------------------------

    @Test
    void rebuildingUnchangedContentProducesTheSameHash() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/assets/textures/a.png", "A");
        write("mypack/assets/textures/b.png", "B");

        String first = build().pack("main").orElseThrow().sha1();
        byte[] firstBytes = Files.readAllBytes(out.resolve("main.zip"));

        // A fresh registry and a fresh builder, exactly as a restart would be.
        String second = build().pack("main").orElseThrow().sha1();

        assertEquals(first, second, "an unchanged pack must not cost every player a redownload");
        assertArrayEquals(firstBytes, Files.readAllBytes(out.resolve("main.zip")));
    }

    @Test
    void changingContentChangesTheHash() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/assets/textures/a.png", "A");
        String before = build().pack("main").orElseThrow().sha1();

        write("mypack/assets/textures/a.png", "CHANGED");
        String after = build().pack("main").orElseThrow().sha1();

        assertNotEquals(before, after);
    }

    @Test
    void fileTimestampsNeverReachTheZip() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/assets/textures/a.png", "A");
        build();
        String before = new PackBuilder().build(content, out, load()).pack("main").orElseThrow().sha1();

        // Rewrite the same bytes, which moves the file's mtime. A zip that
        // carried it would hash differently and every player would redownload
        // for nothing.
        Files.writeString(content.resolve("mypack/assets/textures/a.png"), "A");
        Files.setLastModifiedTime(content.resolve("mypack/assets/textures/a.png"),
                java.nio.file.attribute.FileTime.fromMillis(1_700_000_000_000L));

        assertEquals(before, new PackBuilder().build(content, out, load()).pack("main").orElseThrow().sha1());
    }

    @Test
    void entriesAreSortedRegardlessOfWhatTheFilesystemSays() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/assets/textures/z.png", "Z");
        write("mypack/assets/textures/a.png", "A");
        write("mypack/assets/sounds/m.ogg", "M");

        List<String> names = new ArrayList<>(read(build().pack("main").orElseThrow().file()).keySet());

        assertEquals(names.stream().sorted().toList(), names);
    }

    // ---- the pack identity ---------------------------------------------

    @Test
    void theUuidFollowsTheNameAndNotTheContents() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/assets/textures/a.png", "A");
        BuiltPack before = build().pack("main").orElseThrow();

        write("mypack/assets/textures/a.png", "CHANGED");
        BuiltPack after = build().pack("main").orElseThrow();

        // A rebuild is the same pack with new bytes. A UUID that moved would
        // leave the old one applied on every client.
        assertEquals(before.uuid(), after.uuid());
        assertNotEquals(before.sha1(), after.sha1());
        assertEquals(BuiltPack.uuidFor("main"), after.uuid());
        assertNotEquals(BuiltPack.uuidFor("main"), BuiltPack.uuidFor("lobby"));
    }

    @Test
    void theHashIsOfTheFileOnDisk() throws IOException {
        write("mypack/pack.yml", "{}\n");
        write("mypack/assets/textures/a.png", "A");

        BuiltPack pack = build().pack("main").orElseThrow();

        assertEquals(Files.size(pack.file()), pack.size());
        assertEquals(sha1Of(Files.readAllBytes(pack.file())), pack.sha1());
    }

    private static String sha1Of(byte[] bytes) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-1").digest(bytes);
            StringBuilder out = new StringBuilder();
            for (byte b : digest) {
                out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- bundle resolution ---------------------------------------------

    @Test
    void bundlesResolveSortedBothWays() {
        List<Bundle> bundles = Bundles.resolve(List.of(
                PackMeta.of("zebra", ContentSource.AUTHORED, null, null, null, List.of("lobby")),
                PackMeta.of("alpha", ContentSource.AUTHORED, null, null, null, List.of("lobby", "arena"))));

        assertEquals(List.of("arena", "lobby"), bundles.stream().map(Bundle::name).toList());
        assertEquals(List.of("alpha", "zebra"), bundles.get(1).namespaces());
    }

    @Test
    void nothingLoadedMeansNothingBuilt() {
        BuildReport report = build();

        assertTrue(report.packs().isEmpty());
        assertTrue(report.diagnostics().isEmpty());
    }

    @Test
    void nullArgumentsAnswerEmpty() {
        PackBuilder builder = new PackBuilder();

        assertTrue(builder.build(null, out, LoadReport.empty()).packs().isEmpty());
        assertTrue(builder.build(content, null, LoadReport.empty()).packs().isEmpty());
        assertTrue(builder.build(content, out, null).packs().isEmpty());
    }
}
