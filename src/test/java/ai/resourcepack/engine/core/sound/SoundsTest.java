package ai.resourcepack.engine.core.sound;

import ai.resourcepack.engine.api.BuildReport;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.api.SoundInfo;
import ai.resourcepack.engine.core.content.ContentFolderLoader;
import ai.resourcepack.engine.core.pack.PackBuilder;
import ai.resourcepack.engine.core.registry.ContentRegistryImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoundsTest {

    @TempDir
    Path root;

    private Path content;
    private Path out;

    @BeforeEach
    void setUp() throws IOException {
        content = root.resolve("content");
        out = root.resolve("out");
        Files.createDirectories(content);
        write("mypack/pack.yml", "{}\n");
    }

    private void write(String path, String text) throws IOException {
        Path file = content.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private LoadReport load() {
        return new ContentFolderLoader(new ContentRegistryImpl()).load(content, ContentSource.AUTHORED);
    }

    private SoundDefinitions.Result parse() {
        return SoundDefinitions.parse(load());
    }

    private static SoundInfo one(SoundDefinitions.Result result, String id) {
        return result.sounds().get(ContentId.parse(id).orElseThrow());
    }

    private BuildReport build() {
        return new PackBuilder().with(new SoundAssets()).build(content, out, load());
    }

    private Map<String, String> zip(BuildReport report) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (InputStream in = Files.newInputStream(report.pack("main").orElseThrow().file());
             ZipInputStream zin = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zin.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    // ---- parsing -------------------------------------------------------

    @Test
    void readsTheFieldsAPackWrites() throws IOException {
        write("mypack/sounds/a.yml",
                "chime:\n  file: bells/chime\n  category: ambient\n"
                        + "  subtitle: \"A chime rings\"\n  volume: 0.8\n  pitch: 1.2\n  stream: true\n");

        SoundInfo chime = one(parse(), "mypack:chime");

        assertEquals("bells/chime", chime.file());
        assertEquals("ambient", chime.category());
        assertEquals("A chime rings", chime.subtitle().orElseThrow());
        assertEquals(0.8f, chime.volume());
        assertEquals(1.2f, chime.pitch());
        assertTrue(chime.stream());
    }

    @Test
    void theFileDefaultsToTheIdsOwnPath() throws IOException {
        write("mypack/sounds/a.yml", "bells/chime: {}\n");

        // A pack that shipped chime.ogg and called it chime has said it once.
        assertEquals("bells/chime", one(parse(), "mypack:bells/chime").file());
    }

    @Test
    void anUnknownCategoryWarnsAndFallsBackToMaster() throws IOException {
        write("mypack/sounds/a.yml", "chime:\n  category: sfx\n");

        SoundDefinitions.Result result = parse();

        // Getting this wrong means a player who turned music down still hears
        // it, which gets a server muted rather than reported.
        assertEquals("master", one(result, "mypack:chime").category());
        assertEquals(Diagnostic.Severity.WARNING, result.diagnostics().get(0).severity());
    }

    @Test
    void everyVanillaCategoryIsAccepted() throws IOException {
        for (String category : SoundInfo.CATEGORIES) {
            write("mypack/sounds/a.yml", "chime:\n  category: " + category + "\n");
            assertEquals(category, one(parse(), "mypack:chime").category());
        }
    }

    @Test
    void anAbsurdVolumeIsClampedWithAWarning() throws IOException {
        write("mypack/sounds/a.yml", "chime:\n  volume: 40\n  pitch: 9\n");

        SoundDefinitions.Result result = parse();

        assertEquals(10f, one(result, "mypack:chime").volume());
        assertEquals(2f, one(result, "mypack:chime").pitch());
        assertEquals(2, result.diagnostics().size());
    }

    @Test
    void theDefaultsAreAPlainMasterSound() throws IOException {
        write("mypack/sounds/a.yml", "chime: {}\n");

        SoundInfo chime = one(parse(), "mypack:chime");

        assertEquals("master", chime.category());
        assertEquals(1f, chime.volume());
        assertEquals(1f, chime.pitch());
        assertFalse(chime.stream());
        assertTrue(chime.subtitle().isEmpty());
    }

    // ---- building ------------------------------------------------------

    @Test
    void writesOneSoundsJsonForTheWholeNamespace() throws IOException {
        write("mypack/sounds/a.yml", "chime:\n  category: ambient\n");
        write("mypack/sounds/b.yml", "gong: {}\n");
        write("mypack/assets/sounds/chime.ogg", "OGG");
        write("mypack/assets/sounds/gong.ogg", "OGG");

        String json = zip(build()).get("assets/mypack/sounds.json");

        // One file holds every sound in the namespace, so a second file would
        // replace the first rather than add to it.
        assertTrue(json.contains("\"chime\""), json);
        assertTrue(json.contains("\"gong\""), json);
        assertTrue(json.contains("\"category\": \"ambient\""), json);
        assertTrue(json.contains("\"name\": \"mypack:chime\""), json);
    }

    @Test
    void aSubtitleBecomesALanguageEntry() throws IOException {
        write("mypack/sounds/a.yml", "bells/chime:\n  subtitle: \"A chime rings\"\n");
        write("mypack/assets/sounds/bells/chime.ogg", "OGG");

        Map<String, String> entries = zip(build());

        assertTrue(entries.get("assets/mypack/sounds.json")
                .contains("\"subtitle\": \"subtitles.mypack.bells.chime\""));
        assertTrue(entries.get("assets/mypack/lang/en_us.json")
                .contains("\"subtitles.mypack.bells.chime\": \"A chime rings\""));
    }

    @Test
    void noSubtitlesMeansNoLanguageFile() throws IOException {
        write("mypack/sounds/a.yml", "chime: {}\n");
        write("mypack/assets/sounds/chime.ogg", "OGG");

        // An empty locale file is a wall of blank subtitles, which is worse
        // than the client falling back to its own.
        assertFalse(zip(build()).containsKey("assets/mypack/lang/en_us.json"));
    }

    @Test
    void aSoundWithNoAudioIsAnErrorThatNamesTheFormat() throws IOException {
        write("mypack/sounds/a.yml", "chime: {}\n");

        BuildReport report = build();

        assertTrue(report.hasErrors());
        String message = report.diagnostics(Diagnostic.Severity.ERROR).get(0).message();
        assertTrue(message.contains("assets/mypack/sounds/chime.ogg"), message);
        // The mistake worth pre-empting: an mp3 renamed to .ogg is silence,
        // and nothing in game says so.
        assertTrue(message.contains("Ogg Vorbis"), message);
    }

    @Test
    void aSoundOnlyReachesTheBundleItShipsIn() throws IOException {
        write("mypack/pack.yml", "bundles: [lobby]\n");
        write("mypack/sounds/a.yml", "chime: {}\n");
        write("mypack/assets/sounds/chime.ogg", "OGG");
        write("other/pack.yml", "bundles: [arena]\n");
        write("other/sounds/a.yml", "horn: {}\n");
        write("other/assets/sounds/horn.ogg", "OGG");

        BuildReport report = build();
        Map<String, String> lobby = new LinkedHashMap<>();
        try (InputStream in = Files.newInputStream(report.pack("lobby").orElseThrow().file());
             ZipInputStream zin = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                lobby.put(entry.getName(), new String(zin.readAllBytes(), StandardCharsets.UTF_8));
            }
        }

        assertTrue(lobby.containsKey("assets/mypack/sounds.json"));
        assertFalse(lobby.containsKey("assets/other/sounds.json"));
    }

    @Test
    void aSoundBuildIsReproducible() throws IOException {
        write("mypack/sounds/a.yml", "chime: {}\ngong: {}\n");
        write("mypack/assets/sounds/chime.ogg", "OGG");
        write("mypack/assets/sounds/gong.ogg", "OGG");

        assertEquals(build().pack("main").orElseThrow().sha1(),
                build().pack("main").orElseThrow().sha1());
    }
}
