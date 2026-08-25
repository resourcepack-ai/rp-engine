package ai.resourcepack.engine.core.font;

import ai.resourcepack.engine.api.BuildReport;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.api.OverlayInfo;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GUI backgrounds and HUD overlays: one enormous glyph, nudged into place.
 *
 * <p>Everything except actually opening an inventory is here; that needs a
 * server and is deliberately three lines.
 */
class OverlaysTest {

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

    private static OverlayInfo one(OverlayDefinitions.Result result, String id) {
        return result.overlays().get(ContentId.parse(id).orElseThrow());
    }

    private Map<String, String> zip() throws IOException {
        BuildReport report = new PackBuilder().with(new FontAssets()).build(content, out, load());
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

    // ---- definitions ---------------------------------------------------

    @Test
    void readsAScreen() throws IOException {
        write("mypack/screens/a.yml",
                "shop:\n  container: chest_9x3\n  height: 200\n  ascent: 20\n  offset: 12\n");

        OverlayInfo shop = one(OverlayDefinitions.screens(load()), "mypack:shop");

        assertEquals("chest_9x3", shop.container());
        assertEquals(200, shop.height());
        assertEquals(20, shop.ascent());
        assertEquals(12, shop.offset());
        assertEquals("shop", shop.file());
    }

    @Test
    void aScreenIsPositionedFromItsWindowGeometry() throws IOException {
        write("mypack/screens/a.yml", "shop: {}\n");

        OverlayInfo shop = one(OverlayDefinitions.screens(load()), "mypack:shop");

        // A six-row chest window is 176x222 centred on a 256 sheet, so the art
        // is inset 40 across and 17 down: ascent = 13 + 17, offset = 8 + 40.
        // Guessed numbers give a picture that is NEARLY right, which is worse
        // than one obviously wrong — it reads as a rounding bug rather than a
        // missing formula. See GuiWindows.
        assertEquals("chest_9x6", shop.container());
        assertEquals(GuiWindows.SHEET_SIZE, shop.height());
        assertEquals(30, shop.ascent());
        assertEquals(48, shop.offset());
    }

    @Test
    void aShorterChestSitsLowerOnTheScreen() throws IOException {
        write("mypack/screens/a.yml", "small:\n  container: chest_9x1\n");

        OverlayInfo small = one(OverlayDefinitions.screens(load()), "mypack:small");

        // 114 + 18 = 132 tall, so inset (256-132)/2 = 62 down.
        assertEquals(13 + 62, small.ascent());
        assertEquals(48, small.offset(), "every chest is the same width, so the shift does not move");
    }

    @Test
    void aWiderWindowIsShiftedLess() throws IOException {
        write("mypack/screens/a.yml", "beacon:\n  container: beacon\n");

        // A beacon window is 230 wide, so it is inset only 13 across.
        assertEquals(8 + 13, one(OverlayDefinitions.screens(load()), "mypack:beacon").offset());
    }

    @Test
    void aPackCanStillStateItsOwnPlacement() throws IOException {
        write("mypack/screens/a.yml", "shop:\n  ascent: 20\n  offset: 100\n");

        OverlayInfo shop = one(OverlayDefinitions.screens(load()), "mypack:shop");

        // For art that is not laid out the usual way.
        assertEquals(20, shop.ascent());
        assertEquals(100, shop.offset());
    }

    @Test
    void aContainerTheGameDoesNotHaveIsRefusedWithTheList() throws IOException {
        write("mypack/screens/a.yml", "shop:\n  container: chest_9x9\n");

        OverlayDefinitions.Result result = OverlayDefinitions.screens(load());

        // A GUI is a real container wearing a picture, so it has to be one
        // that exists. The alternative is a command that does nothing.
        assertTrue(result.overlays().isEmpty());
        assertTrue(result.diagnostics().get(0).message().contains("chest_9x6"));
    }

    @Test
    void readsAHud() throws IOException {
        write("mypack/huds/a.yml", "mana:\n  slot: boss_bar\n  height: 40\n  ascent: 30\n");

        OverlayInfo mana = one(OverlayDefinitions.huds(load()), "mypack:mana");

        assertEquals(OverlayInfo.Slot.BOSS_BAR, mana.slot());
        assertEquals(40, mana.height());
        assertTrue(mana.container().isEmpty());
    }

    @Test
    void anUnknownSlotWarnsAndUsesTheActionBar() throws IOException {
        write("mypack/huds/a.yml", "mana:\n  slot: sidebar\n");

        OverlayDefinitions.Result result = OverlayDefinitions.huds(load());

        assertEquals(OverlayInfo.Slot.ACTION_BAR, one(result, "mypack:mana").slot());
        assertEquals(Diagnostic.Severity.WARNING, result.diagnostics().get(0).severity());
    }

    @Test
    void anAscentAboveTheHeightIsRefusedBecauseTheGameDrawsNothing() throws IOException {
        write("mypack/screens/a.yml", "shop:\n  height: 100\n  ascent: 200\n");

        OverlayDefinitions.Result result = OverlayDefinitions.screens(load());

        assertEquals(100, one(result, "mypack:shop").ascent());
        assertEquals(Diagnostic.Severity.WARNING, result.diagnostics().get(0).severity());
    }

    // ---- one number line -----------------------------------------------

    @Test
    void iconsScreensAndHudsNeverShareACodepoint() throws IOException {
        write("mypack/fonts/a.yml", "sword: {}\n");
        write("mypack/screens/a.yml", "shop: {}\n");
        write("mypack/huds/a.yml", "mana: {}\n");

        LoadReport loaded = load();
        int icon = IconDefinitions.parse(loaded).icons()
                .get(ContentId.parse("mypack:sword").orElseThrow()).codepoint();
        int screen = one(OverlayDefinitions.screens(loaded), "mypack:shop").codepoint();
        int hud = one(OverlayDefinitions.huds(loaded), "mypack:mana").codepoint();

        // Allocating per kind would hand the same codepoint to an icon and a
        // screen, and the failure is a chat message drawing a full-screen GUI
        // across somebody's view.
        assertNotEquals(icon, screen);
        assertNotEquals(screen, hud);
        assertNotEquals(icon, hud);
    }

    @Test
    void aBrokenDefinitionDoesNotShiftTheGlyphsAfterIt() throws IOException {
        write("mypack/screens/a.yml", "broken:\n  container: nope\nshop: {}\n");

        int withBroken = one(OverlayDefinitions.screens(load()), "mypack:shop").codepoint();

        write("mypack/screens/a.yml", "broken: {}\nshop: {}\n");
        int withFixed = one(OverlayDefinitions.screens(load()), "mypack:shop").codepoint();

        // Costs a codepoint out of six thousand, and buys that fixing one typo
        // does not change what every other glyph resolves to.
        assertEquals(withBroken, withFixed);
    }

    // ---- the font file -------------------------------------------------

    @Test
    void everyGlyphKindLandsInOneFontFile() throws IOException {
        write("mypack/fonts/a.yml", "sword: {}\n");
        write("mypack/screens/a.yml", "shop: {}\n");
        write("mypack/huds/a.yml", "mana: {}\n");
        write("mypack/assets/textures/font/sword.png", "PNG");
        write("mypack/assets/textures/gui/shop.png", "PNG");
        write("mypack/assets/textures/gui/mana.png", "PNG");

        String font = zip().get("assets/minecraft/font/default.json");

        assertTrue(font.contains("mypack:font/sword.png"), font);
        assertTrue(font.contains("mypack:gui/shop.png"), font);
        assertTrue(font.contains("mypack:gui/mana.png"), font);
    }

    @Test
    void theFontCarriesTheNegativeSpaceProvider() throws IOException {
        write("mypack/screens/a.yml", "shop: {}\n");
        write("mypack/assets/textures/gui/shop.png", "PNG");

        String font = zip().get("assets/minecraft/font/default.json");

        // Without it a GUI backdrop starts where the title text starts, which
        // is not where the window is.
        assertTrue(font.contains("\"type\": \"space\""), font);
        assertTrue(font.contains("-1"), font);
        assertTrue(font.contains("-512"), font);
        assertTrue(font.chars().allMatch(c -> c < 0x80), "the font file must be plain ASCII");
    }

    @Test
    void aScreenWithNoImageIsAnError() throws IOException {
        write("mypack/screens/a.yml", "shop: {}\n");

        BuildReport report = new PackBuilder().with(new FontAssets()).build(content, out, load());

        assertTrue(report.hasErrors());
        assertTrue(report.diagnostics(Diagnostic.Severity.ERROR).get(0).message()
                .contains("assets/mypack/textures/gui/shop.png"));
    }

    @Test
    void noGlyphsMeansNoFontFile() throws IOException {
        write("mypack/items/a.yml", "ruby:\n  material: DIAMOND\n");

        assertFalse(zip().containsKey("assets/minecraft/font/default.json"));
    }

    // ---- positioning ---------------------------------------------------

    @Test
    void aShiftIsBuiltOutOfPowersOfTwo() {
        // 100 = 64 + 32 + 4, so three characters rather than a hundred.
        assertEquals(3, Overlays.shift(100).codePointCount(0, Overlays.shift(100).length()));
        assertEquals(1, Overlays.shift(8).codePointCount(0, Overlays.shift(8).length()));
        assertEquals("", Overlays.shift(0));
        assertEquals("", Overlays.shift(-5));
    }

    @Test
    void everyShiftCharacterIsAboveTheGlyphRange() {
        String shift = Overlays.shift(511);

        // Space codepoints sit above the content range so they can never
        // collide with an icon.
        assertTrue(shift.codePoints().allMatch(c -> c >= FontAssets.FIRST_SPACE_CODEPOINT));
        assertTrue(shift.codePoints().allMatch(c -> c > GlyphAllocator.LAST_CODEPOINT));
    }
}
