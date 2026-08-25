package ai.resourcepack.engine.core.font;

import ai.resourcepack.engine.api.BuildReport;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.IconInfo;
import ai.resourcepack.engine.api.LoadReport;
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
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IconsTest {

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

    private IconDefinitions.Result parse() {
        return IconDefinitions.parse(load());
    }

    private static IconInfo one(IconDefinitions.Result result, String id) {
        return result.icons().get(ContentId.parse(id).orElseThrow());
    }

    private Map<String, String> zip() throws IOException {
        BuildReport report = new PackBuilder().with(new IconAssets()).build(content, out, load());
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

    // ---- allocation ----------------------------------------------------

    @Test
    void codepointsComeOutOfThePrivateUseAreaInIdOrder() throws IOException {
        write("mypack/fonts/a.yml", "sword: {}\napple: {}\ncoin: {}\n");

        IconDefinitions.Result result = parse();

        // Sorted, so the same content allocates identically on every machine.
        assertEquals(IconDefinitions.FIRST_CODEPOINT, one(result, "mypack:apple").codepoint());
        assertEquals(IconDefinitions.FIRST_CODEPOINT + 1, one(result, "mypack:coin").codepoint());
        assertEquals(IconDefinitions.FIRST_CODEPOINT + 2, one(result, "mypack:sword").codepoint());
    }

    @Test
    void allocationIsTheSameEveryTime() throws IOException {
        write("mypack/fonts/a.yml", "sword: {}\napple: {}\n");

        assertEquals(one(parse(), "mypack:sword").codepoint(), one(parse(), "mypack:sword").codepoint());
    }

    @Test
    void addingAnEarlierIdShiftsTheOnesAfterIt() throws IOException {
        write("mypack/fonts/a.yml", "sword: {}\n");
        int before = one(parse(), "mypack:sword").codepoint();

        write("mypack/fonts/a.yml", "apple: {}\nsword: {}\n");
        int after = one(parse(), "mypack:sword").codepoint();

        // The documented trade. Stable codepoints would mean a file mapping id
        // to number that must never be lost or reordered, which is exactly the
        // problem the item scheme was designed to delete. The rule that makes
        // it safe is elsewhere: resolve the id when you write the text.
        assertNotEquals(before, after);
    }

    @Test
    void twoNamespacesShareOneAllocation() throws IOException {
        write("other/pack.yml", "{}\n");
        write("mypack/fonts/a.yml", "sword: {}\n");
        write("other/fonts/a.yml", "apple: {}\n");

        IconDefinitions.Result result = parse();

        // One font file, so one number line. Two packs allocating separately
        // would hand out the same codepoint twice.
        assertNotEquals(one(result, "mypack:sword").codepoint(), one(result, "other:apple").codepoint());
    }

    @Test
    void anAscentAboveTheHeightIsRefusedBecauseTheGameDrawsNothing() throws IOException {
        write("mypack/fonts/a.yml", "sword:\n  height: 8\n  ascent: 20\n");

        IconDefinitions.Result result = parse();

        assertEquals(8, one(result, "mypack:sword").ascent());
        assertEquals(Diagnostic.Severity.WARNING, result.diagnostics().get(0).severity());
    }

    @Test
    void theDefaultsAreALineOfTextTall() throws IOException {
        write("mypack/fonts/a.yml", "sword: {}\n");

        IconInfo sword = one(parse(), "mypack:sword");

        assertEquals(8, sword.height());
        assertEquals(8, sword.ascent());
        assertEquals("sword", sword.file());
    }

    // ---- the font file -------------------------------------------------

    @Test
    void iconsAreDeclaredInTheDefaultFont() throws IOException {
        write("mypack/fonts/a.yml", "sword:\n  height: 12\n  ascent: 10\n");
        write("mypack/assets/textures/font/sword.png", "PNG");

        String font = zip().get("assets/minecraft/font/default.json");

        // The default font, so an icon renders in an anvil-typed item name, a
        // sign and a scoreboard, not only where a plugin can set a font.
        assertTrue(font.contains("\"file\": \"mypack:font/sword.png\""), font);
        assertTrue(font.contains("\"height\": 12"), font);
        assertTrue(font.contains("\"ascent\": 10"), font);
        assertTrue(font.contains("\\uE000"), font);
    }

    @Test
    void theCodepointIsWrittenAsAnEscape() throws IOException {
        write("mypack/fonts/a.yml", "sword: {}\n");
        write("mypack/assets/textures/font/sword.png", "PNG");

        // Plain ASCII: a Private Use Area character survives some editors and
        // is mangled by the rest, and the failure looks like the icon simply
        // not existing.
        String font = zip().get("assets/minecraft/font/default.json");
        assertTrue(font.chars().allMatch(c -> c < 0x80), "the font file must be plain ASCII");
    }

    @Test
    void anIconWithNoImageIsAnError() throws IOException {
        write("mypack/fonts/a.yml", "sword: {}\n");

        BuildReport report = new PackBuilder().with(new IconAssets()).build(content, out, load());

        assertTrue(report.hasErrors());
        assertTrue(report.diagnostics(Diagnostic.Severity.ERROR).get(0).message()
                .contains("assets/mypack/textures/font/sword.png"));
    }

    @Test
    void aPackOverridingTheDefaultFontIsRefusedLoudly() throws IOException {
        write("mypack/fonts/a.yml", "sword: {}\n");
        write("mypack/assets/textures/font/sword.png", "PNG");
        write("mypack/overrides/font/default.json", "{\"providers\":[]}");

        BuildReport report = new PackBuilder().with(new IconAssets()).build(content, out, load());

        // It would replace ours and delete every icon in the bundle, while the
        // pack that did it looked fine.
        assertTrue(report.hasErrors());
        assertTrue(report.diagnostics(Diagnostic.Severity.ERROR).get(0).message()
                .contains("overrides the default font"));
    }

    @Test
    void noIconsMeansNoFontFile() throws IOException {
        write("mypack/items/a.yml", "ruby:\n  material: DIAMOND\n");

        assertFalse(zip().containsKey("assets/minecraft/font/default.json"));
    }

    // ---- putting one into text -----------------------------------------

    @Test
    void aPlaceholderBecomesTheCharacter() throws IOException {
        write("mypack/fonts/a.yml", "sword: {}\n");
        IconsImpl icons = new IconsImpl();
        icons.replace(parse().icons());

        String sword = one(parse(), "mypack:sword").character();

        assertEquals("You hold a " + sword + " now", icons.format("You hold a :mypack:sword: now"));
    }

    @Test
    void anIdThatNamesNothingIsLeftExactlyAsWritten() {
        IconsImpl icons = new IconsImpl();

        // Text that silently loses a chunk of itself is much harder to
        // diagnose than text that still visibly says what was asked for.
        assertEquals("a :mypack:nope: b", icons.format("a :mypack:nope: b"));
    }

    @Test
    void ordinaryTextIsUntouched() {
        IconsImpl icons = new IconsImpl();

        assertEquals("no colons here", icons.format("no colons here"));
        assertEquals("12:30 and 14:00", icons.format("12:30 and 14:00"));
        assertEquals("", icons.format(null));
    }

    @Test
    void aPlaceholderMustNameItsNamespace() throws IOException {
        write("mypack/fonts/a.yml", "sword: {}\n");
        IconsImpl icons = new IconsImpl();
        icons.replace(parse().icons());

        // :sword: would mean guessing which pack, and that guess changes
        // answer the day a second pack is installed.
        assertEquals(":sword:", icons.format(":sword:"));
    }

    @Test
    void severalPlaceholdersInOneLine() throws IOException {
        write("mypack/fonts/a.yml", "sword: {}\napple: {}\n");
        IconsImpl icons = new IconsImpl();
        IconDefinitions.Result parsed = parse();
        icons.replace(parsed.icons());

        assertEquals(one(parsed, "mypack:apple").character() + one(parsed, "mypack:sword").character(),
                icons.format(":mypack:apple::mypack:sword:"));
        assertEquals(List.of(ContentId.parse("mypack:apple").orElseThrow(),
                        ContentId.parse("mypack:sword").orElseThrow()),
                List.copyOf(icons.ids()));
    }
}
