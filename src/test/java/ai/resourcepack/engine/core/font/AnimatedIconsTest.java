package ai.resourcepack.engine.core.font;

import ai.resourcepack.engine.api.BuildReport;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.IconInfo;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.core.content.ContentFolderLoader;
import ai.resourcepack.engine.core.pack.PackBuilder;
import ai.resourcepack.engine.core.pack.PackFiles;
import ai.resourcepack.engine.core.registry.ContentRegistryImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Icons with more than one frame: a strip, a GIF, and the server choosing which
 * frame to send.
 *
 * <p>There is no shader here to test, by design — every frame is a glyph and
 * the engine picks one. So what is worth asserting is the arithmetic (which
 * frame, when), the allocation (one codepoint per frame, and nothing else
 * moved by it), and the build (the strip is cut into exactly those glyphs).
 */
class AnimatedIconsTest {

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

    private void write(String path, byte[] bytes) throws IOException {
        Path file = content.resolve(path);
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    private LoadReport load() {
        return new ContentFolderLoader(new ContentRegistryImpl()).load(content, ContentSource.AUTHORED);
    }

    private IconDefinitions.Result parse() {
        return IconDefinitions.parse(load(), PackFiles.folder(content));
    }

    private static IconInfo one(IconDefinitions.Result result, String id) {
        return result.icons().get(ContentId.parse(id).orElseThrow());
    }

    private static ContentId id(String id) {
        return ContentId.parse(id).orElseThrow();
    }

    private Map<String, byte[]> zip(BuildReport report) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (InputStream in = Files.newInputStream(report.pack("main").orElseThrow().file());
             ZipInputStream zin = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                entries.put(entry.getName(), zin.readAllBytes());
            }
        }
        return entries;
    }

    private static IconInfo strip(int frames, int fps, boolean loops) {
        return IconInfo.of(id("mypack:spin"), "spin", 8, 7, 0xE000)
                .withCell(frames, 1, 1)
                .withAnimation(frames, fps, loops);
    }

    // ---- which frame, when -------------------------------------------------

    @Test
    void aLoopingIconMovesOnEveryTenthOfASecondAtTenFpsAndWraps() {
        IconInfo spin = strip(4, 10, true);

        assertEquals(0, spin.frameAt(0));
        assertEquals(0, spin.frameAt(99));
        assertEquals(1, spin.frameAt(100));
        assertEquals(3, spin.frameAt(399));
        assertEquals(0, spin.frameAt(400));
        assertEquals(2, spin.frameAt(1_000_000_000_200L));
    }

    @Test
    void aNonLoopingIconStopsOnItsLastFrame() {
        IconInfo once = strip(4, 20, false);

        assertEquals(1, once.frameAt(50));
        assertEquals(3, once.frameAt(150));
        assertEquals(3, once.frameAt(60_000));
    }

    @Test
    void aStillIconIsAlwaysFrameZeroAndTimeBeforeTheStartIsTheFirstFrame() {
        IconInfo still = IconInfo.of(id("mypack:sword"), "sword", 8, 7, 0xE000);

        assertEquals(0, still.frameAt(123_456));
        assertEquals(0, strip(4, 10, true).frameAt(-500));
        assertFalse(still.animated());
    }

    @Test
    void eachFrameIsTheCodepointAfterThePrevious() {
        IconInfo spin = strip(4, 10, true);

        assertEquals(0xE002, spin.codepoint(2));
        // Clamped rather than wandering into whatever icon comes next.
        assertEquals(0xE003, spin.codepoint(9));
        assertEquals(0xE000, spin.codepoint(-1));
    }

    @Test
    void fpsIsHeldToOnceATick() {
        assertEquals(IconInfo.MAX_FPS, strip(4, 60, true).fps());
        assertEquals(1, strip(4, 0, true).fps());
    }

    // ---- the clock ------------------------------------------------------------

    @Test
    void characterIsTheFirstFrameAndCharacterNowFollowsTheClock() {
        AtomicLong clock = new AtomicLong(0);
        IconsImpl icons = new IconsImpl(clock::get);
        icons.replace(Map.of(id("mypack:spin"), strip(4, 10, true)));

        clock.set(250);

        assertEquals(new String(Character.toChars(0xE000)), icons.character(id("mypack:spin")).orElseThrow());
        assertEquals(new String(Character.toChars(0xE002)), icons.characterNow(id("mypack:spin")).orElseThrow());
    }

    @Test
    void formatIsTheFirstFrameAndFormatNowIsTheCurrentOne() {
        AtomicLong clock = new AtomicLong(0);
        IconsImpl icons = new IconsImpl(clock::get);
        icons.replace(Map.of(id("mypack:spin"), strip(4, 10, true)));
        clock.set(100);

        // A message sent once is the first frame, so saved text stays as it was.
        assertEquals("a " + new String(Character.toChars(0xE000)), icons.format("a :mypack:spin:"));
        assertEquals("a " + new String(Character.toChars(0xE001)), icons.formatNow("a :mypack:spin:"));
        assertEquals("a :mypack:nope:", icons.formatNow("a :mypack:nope:"));
    }

    @Test
    void aNonLoopingIconIsTimedFromWhenTheServerStarted() {
        AtomicLong clock = new AtomicLong(1_700_000_000_000L);
        IconsImpl icons = new IconsImpl(clock::get);
        icons.replace(Map.of(id("mypack:once"), strip(4, 10, false)));

        // From the epoch it would have finished decades ago; from the start
        // it is still on its first frame.
        assertEquals(new String(Character.toChars(0xE000)), icons.characterNow(id("mypack:once")).orElseThrow());
        clock.addAndGet(200);
        assertEquals(new String(Character.toChars(0xE002)), icons.characterNow(id("mypack:once")).orElseThrow());
        clock.addAndGet(10_000);
        assertEquals(new String(Character.toChars(0xE003)), icons.characterNow(id("mypack:once")).orElseThrow());
    }

    // ---- a strip --------------------------------------------------------------

    @Test
    void aStripTakesOneCodepointPerFrameAndTheNextIconStartsAfterThem() throws IOException {
        write("mypack/fonts/a.yml", """
                loading:
                  file: loading
                  animation: { frames: 8, fps: 12, loop: false }
                spinner_after: {}
                """);

        IconDefinitions.Result result = parse();
        IconInfo loading = one(result, "mypack:loading");

        assertEquals(8, loading.frames());
        assertEquals(8, loading.rows());
        assertEquals(1, loading.columns());
        assertEquals(12, loading.fps());
        assertFalse(loading.loops());
        assertEquals(GlyphAllocator.FIRST_CODEPOINT, loading.codepoint());
        assertEquals(GlyphAllocator.FIRST_CODEPOINT + 8, one(result, "mypack:spinner_after").codepoint());
    }

    @Test
    void theDefaultsAreTenFpsAndLooping() throws IOException {
        write("mypack/fonts/a.yml", "loading:\n  animation: { frames: 3 }\n");

        IconInfo loading = one(parse(), "mypack:loading");

        assertEquals(IconInfo.DEFAULT_FPS, loading.fps());
        assertTrue(loading.loops());
    }

    @Test
    void theStripIsCutIntoOneGlyphPerFrameByOneProvider() throws IOException {
        write("mypack/fonts/a.yml", "loading:\n  animation: { frames: 3 }\n");
        write("mypack/assets/textures/font/loading.png", "PNG");

        BuildReport report = new PackBuilder().with(new FontAssets()).build(content, out, load());
        String font = new String(zip(report).get("assets/minecraft/font/default.json"), StandardCharsets.UTF_8);

        assertTrue(font.contains("\"chars\": [\"\\uE000\", \"\\uE001\", \"\\uE002\"]"), font);
    }

    @Test
    void tooFastIsClampedAndSaysSo() throws IOException {
        write("mypack/fonts/a.yml", "loading:\n  animation: { frames: 3, fps: 60 }\n");

        IconDefinitions.Result result = parse();

        assertEquals(IconInfo.MAX_FPS, one(result, "mypack:loading").fps());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("animation.fps: 60")));
    }

    @Test
    void tooManyFramesIsTheFirstFrameStillAndCostsOneCodepoint() throws IOException {
        write("mypack/fonts/a.yml", "long:\n  animation: { frames: 100 }\nnext: {}\n");

        IconDefinitions.Result result = parse();
        IconInfo still = one(result, "mypack:long");

        // Still cut into its real rows, so the first frame draws right.
        assertEquals(100, still.rows());
        assertEquals(1, still.frames());
        assertEquals(still.codepoint() + 1, one(result, "mypack:next").codepoint());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("more than the 64")));
    }

    @Test
    void anAnimationOnAGridIsAStillCellAndSaysSo() throws IOException {
        write("mypack/fonts/a.yml",
                "face:\n  file: faces\n  grid: { rows: 2, columns: 2, cell: 2 }\n  animation: { frames: 4 }\n");

        IconDefinitions.Result result = parse();

        assertEquals(1, one(result, "mypack:face").frames());
        assertEquals(2, one(result, "mypack:face").cell());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("grid: sheet")));
    }

    @Test
    void anAnimatedIconMovesNoScreenOrHud() throws IOException {
        write("mypack/screens/a.yml", "shop: {}\n");
        write("mypack/huds/a.yml", "mana: {}\n");
        write("mypack/fonts/a.yml", "loading:\n  animation: { frames: 8 }\n");
        int screen = OverlayDefinitions.screens(load()).overlays().get(id("mypack:shop")).codepoint();

        write("mypack/fonts/a.yml", "loading:\n  animation: { frames: 2 }\n");
        LoadReport loaded = load();

        // Screens and HUDs are allocated before icons, so the overlay loader,
        // which never reads a GIF, cannot disagree with the icon loader.
        assertEquals(screen, OverlayDefinitions.screens(loaded).overlays().get(id("mypack:shop")).codepoint());
        IconInfo loading = one(IconDefinitions.parse(loaded, PackFiles.folder(content)), "mypack:loading");
        int hud = OverlayDefinitions.huds(loaded).overlays().get(id("mypack:mana")).codepoint();
        assertTrue(loading.codepoint() > hud && loading.codepoint() > screen);
    }

    // ---- a GIF ----------------------------------------------------------------

    @Test
    void aGifIsCompositedOntoItsLogicalScreenAndDisposedAsItSays() {
        int red = 0xFF0000;
        int blue = 0x0000FF;
        int green = 0x00FF00;
        byte[] gif = Gifs.write(List.of(
                new Gifs.Frame(Gifs.solid(4, 4, red), 0, 0, "none", 10),
                // A 2x2 patch in the bottom-right corner, cleared afterwards.
                new Gifs.Frame(Gifs.solid(2, 2, blue), 2, 2, "restoreToBackgroundColor", 10),
                // One pixel, top-left, then the canvas put back as it was.
                new Gifs.Frame(Gifs.solid(1, 1, green), 0, 0, "restoreToPrevious", 10),
                new Gifs.Frame(Gifs.solid(1, 1, blue), 3, 0, "none", 10)));

        GifFrames frames = GifFrames.decode(gif, 64).orElseThrow();
        List<BufferedImage> drawn = frames.frames();

        assertEquals(4, frames.count());
        assertEquals(4, drawn.get(1).getWidth());
        assertEquals(4, drawn.get(1).getHeight());
        // Frame 2 is the patch on top of frame 1, not the patch on its own.
        assertEquals(red, drawn.get(1).getRGB(0, 0) & 0xFFFFFF);
        assertEquals(blue, drawn.get(1).getRGB(3, 3) & 0xFFFFFF);
        // Its area was cleared before frame 3: transparent, not blue.
        assertEquals(0, drawn.get(2).getRGB(3, 3) >>> 24);
        assertEquals(green, drawn.get(2).getRGB(0, 0) & 0xFFFFFF);
        // restoreToPrevious took the green pixel back off before frame 4.
        assertEquals(red, drawn.get(3).getRGB(0, 0) & 0xFFFFFF);
        assertEquals(blue, drawn.get(3).getRGB(3, 0) & 0xFFFFFF);
    }

    @Test
    void aGifsFpsIsItsAverageDelay() {
        // 20 hundredths is 200 ms a frame, five a second.
        assertEquals(5, GifFrames.measure(Gifs.frames(3, 2, 2, 20), 64).orElseThrow().fps(IconInfo.MAX_FPS));
        // 0 is "as fast as you can", which browsers play at 100 ms.
        assertEquals(10, GifFrames.measure(Gifs.frames(3, 2, 2, 0), 64).orElseThrow().fps(IconInfo.MAX_FPS));
        // 2 hundredths is 50 fps, held to once a tick.
        assertEquals(20, GifFrames.measure(Gifs.frames(3, 2, 2, 2), 64).orElseThrow().fps(IconInfo.MAX_FPS));
    }

    @Test
    void aGifIconIsCountedFromTheFileAndBuiltIntoAStrip() throws IOException {
        write("mypack/fonts/a.yml", "dance:\n  gif: dance.gif\n  height: 11\n  ascent: 9\nzzz: {}\n");
        write("mypack/assets/textures/font/dance.gif", Gifs.frames(5, 6, 4, 20));
        write("mypack/assets/textures/font/zzz.png", "PNG");

        IconDefinitions.Result result = parse();
        IconInfo dance = one(result, "mypack:dance");
        assertEquals(5, dance.frames());
        assertEquals(5, dance.fps());
        assertEquals("dance.gif", dance.file());
        assertEquals(dance.codepoint() + 5, one(result, "mypack:zzz").codepoint());

        BuildReport report = new PackBuilder().with(new FontAssets()).build(content, out, load());
        Map<String, byte[]> zip = zip(report);
        BufferedImage strip = ImageIO.read(new ByteArrayInputStream(zip.get("assets/mypack/textures/font/dance.gif.png")));
        String font = new String(zip.get("assets/minecraft/font/default.json"), StandardCharsets.UTF_8);

        assertEquals(6, strip.getWidth());
        assertEquals(4 * 5, strip.getHeight());
        assertTrue(font.contains("\"file\": \"mypack:font/dance.gif.png\""), font);
        assertTrue(font.contains("\\uE004\"]"), font);
        // The GIF itself is source: the client reads the strip.
        assertFalse(zip.containsKey("assets/mypack/textures/font/dance.gif"));
        assertFalse(report.hasErrors(), report.diagnostics().toString());
    }

    @Test
    void aGifCanBeToldToUseFewerFramesAndAnotherRate() throws IOException {
        write("mypack/fonts/a.yml", "dance:\n  gif: dance\n  animation: { frames: 2, fps: 4, loop: false }\n");
        write("mypack/assets/textures/font/dance.gif", Gifs.frames(5, 2, 2, 20));

        IconInfo dance = one(parse(), "mypack:dance");

        assertEquals(2, dance.frames());
        assertEquals(4, dance.fps());
        assertFalse(dance.loops());
        assertEquals("dance.gif", dance.file());
    }

    @Test
    void aLongGifKeepsItsFirstSixtyFourFramesAndSaysSo() throws IOException {
        write("mypack/fonts/a.yml", "long:\n  gif: long.gif\n");
        write("mypack/assets/textures/font/long.gif", Gifs.frames(70, 1, 1, 10));

        IconDefinitions.Result result = parse();

        assertEquals(IconInfo.MAX_FRAMES, one(result, "mypack:long").frames());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("has 70 frames")));
    }

    @Test
    void aGifLargerThanAGlyphIsScaledDownAndSaysSo() throws IOException {
        write("mypack/fonts/a.yml", "big:\n  gif: big.gif\n");
        write("mypack/assets/textures/font/big.gif", Gifs.frames(2, 300, 30, 10));

        BuildReport report = new PackBuilder().with(new FontAssets()).build(content, out, load());
        BufferedImage strip = ImageIO.read(new ByteArrayInputStream(
                zip(report).get("assets/mypack/textures/font/big.gif.png")));

        assertEquals(256, strip.getWidth());
        assertEquals(2 * 26, strip.getHeight());
        assertTrue(report.diagnostics().stream().anyMatch(d -> d.message().contains("scaled down")));
    }

    @Test
    void aMissingGifIsAnErrorAndStillHoldsItsPlace() throws IOException {
        write("mypack/fonts/a.yml", "ghost:\n  gif: ghost.gif\nzzz: {}\n");

        IconDefinitions.Result result = parse();

        assertFalse(result.icons().containsKey(id("mypack:ghost")));
        assertEquals(GlyphAllocator.FIRST_CODEPOINT + 1, one(result, "mypack:zzz").codepoint());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.severity() == Diagnostic.Severity.ERROR
                && d.message().contains("assets/mypack/textures/font/ghost.gif")));
    }

    @Test
    void aGifInTheItemsAdderLayoutIsFoundToo() throws IOException {
        // textures/ at the pack root is copied as assets/<ns>/textures/, so
        // counting the frames has to look there as well.
        write("mypack/fonts/a.yml", "dance:\n  gif: dance.gif\n");
        write("mypack/textures/font/dance.gif", Gifs.frames(3, 2, 2, 10));

        assertEquals(3, one(parse(), "mypack:dance").frames());
    }
}
