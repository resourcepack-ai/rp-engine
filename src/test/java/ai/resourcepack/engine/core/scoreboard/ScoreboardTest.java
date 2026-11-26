package ai.resourcepack.engine.core.scoreboard;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.api.ScoreboardInfo;
import ai.resourcepack.engine.core.content.ContentFolderLoader;
import ai.resourcepack.engine.core.font.GlyphAllocator;
import ai.resourcepack.engine.core.font.TextWidth;
import ai.resourcepack.engine.core.registry.ContentRegistryImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScoreboardTest {

    @TempDir
    Path root;

    private static ContentId id(String text) {
        return ContentId.parse(text).orElseThrow();
    }

    private LoadReport load(String yaml) throws IOException {
        Path content = root.resolve("content");
        Files.createDirectories(content.resolve("p/scoreboards"));
        Files.writeString(content.resolve("p/pack.yml"), "{}\n", StandardCharsets.UTF_8);
        Files.writeString(content.resolve("p/scoreboards/a.yml"), yaml, StandardCharsets.UTF_8);
        return new ContentFolderLoader(new ContentRegistryImpl()).load(content, ContentSource.AUTHORED);
    }

    // ---- the panel -------------------------------------------------------

    /** What a pixel over a background comes out as, a channel at a time. */
    private static double over(double background, int argb, int shift) {
        double alpha = (argb >>> 24) / 255.0;
        return ((argb >> shift) & 0xFF) * alpha + background * (1 - alpha);
    }

    @Test
    void aPixelOverTheGamesRectangleComesOutAsItWouldAlone() {
        int wanted = 0xC8203040;
        for (double box : new double[] {ScoreboardPanel.TITLE_BOX, ScoreboardPanel.BODY_BOX}) {
            int shipped = ScoreboardPanel.over(wanted, box);
            for (double world : new double[] {0, 90, 255}) {
                for (int shift : new int[] {16, 8, 0}) {
                    double alone = over(world, wanted, shift);
                    double together = over(world * (1 - box), shipped, shift);
                    assertEquals(alone, together, 2.0, "over " + world + " behind a box of " + box);
                }
            }
        }
    }

    @Test
    void aPixelThinnerThanTheRectangleIsLeftOut() {
        assertEquals(0, ScoreboardPanel.over(0x40FFFFFF, ScoreboardPanel.TITLE_BOX));
        // And a solid one is itself: nothing shows through it to allow for.
        assertEquals(0xFF123456, ScoreboardPanel.over(0xFF123456, ScoreboardPanel.BODY_BOX));
    }

    @Test
    void aPanelIsAsTallAsItsLinesAndEveryPieceMeasuresItsWidth() {
        ScoreboardPanel panel = ScoreboardPanel.builder().lines(4).padding(5, 5, 5, 5).radius(3).build();
        // Four above the game's rectangle, its ten title rows, nine a line, three below.
        assertEquals(4 + 10 + 36 + 3, panel.height());
        assertEquals(12, panel.ascent());
        BufferedImage left = panel.left(null);
        assertEquals(panel.height(), left.getHeight());
        assertEquals(panel.leftWidth(), left.getWidth());
        // The corner is cut, and the piece still has something in its last column.
        assertEquals(0, left.getRGB(0, 0) >>> 24);
        for (int index = 0; index < ScoreboardPanel.MIDDLES; index++) {
            BufferedImage middle = panel.middle(index, null);
            assertEquals(1 << index, middle.getWidth());
            assertTrue(lastColumnHasInk(middle));
        }
        assertTrue(lastColumnHasInk(panel.right(null)));
    }

    @Test
    void aPanelCutFromAPictureKeepsItsCorners() {
        BufferedImage source = new BufferedImage(12, 12, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 12; x++) {
            for (int y = 0; y < 12; y++) {
                source.setRGB(x, y, 0xFF000000 | (x * 20) << 16 | (y * 20));
            }
        }
        ScoreboardPanel panel = ScoreboardPanel.builder().lines(3).padding(6, 6, 6, 6)
                .texture("board", 4, 4, 4, 4).build();
        BufferedImage left = panel.left(source);
        // Outside the game's rectangle a pixel ships exactly as the picture has it.
        assertEquals(source.getRGB(0, 0), left.getRGB(0, 0));
        assertEquals(source.getRGB(0, 11), left.getRGB(0, panel.height() - 1));
        BufferedImage right = panel.right(source);
        assertEquals(panel.rightWidth(), right.getWidth());
    }

    private static boolean lastColumnHasInk(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            if ((image.getRGB(image.getWidth() - 1, y) >>> 24) != 0) {
                return true;
            }
        }
        return false;
    }

    // ---- the layout ------------------------------------------------------

    @Test
    void theTitleIsExactlyAsWideAsTheSidebarAndThePiecesSpanThePanel() {
        ScoreboardPanel panel = ScoreboardPanel.builder().lines(3).padding(5, 5, 5, 5).radius(3).build();
        int codepoint = 0xE100;
        TextWidth.glyph(codepoint + ScoreboardPanel.LEFT, panel.leftWidth());
        TextWidth.glyph(codepoint + ScoreboardPanel.RIGHT, panel.rightWidth());
        for (int index = 0; index < ScoreboardPanel.MIDDLES; index++) {
            TextWidth.glyph(codepoint + ScoreboardPanel.FIRST_MIDDLE + index, 1 << index);
        }
        for (String title : List.of("", "§b§lMY SERVER", "A", "A title a good deal wider than any of the lines")) {
            List<String> lines = List.of("§7Rank: §fOwner", "", "§fCoins: §e1,250");
            ScoreboardLayout.Composed composed = ScoreboardLayout.compose(panel, codepoint, title, lines,
                    List.of(), 0, false);
            assertEquals(composed.width(), TextWidth.of(composed.title()), "the title's width for: " + title);
            assertTrue(composed.width() >= TextWidth.of(lines.get(0)) + panel.extraRight());

            // The pieces, taken alone, are as wide as the panel.
            int drawn = 0;
            for (int c : composed.title().codePoints().toArray()) {
                if (c == codepoint + ScoreboardPanel.LEFT) {
                    drawn += panel.leftWidth();
                } else if (c == codepoint + ScoreboardPanel.RIGHT) {
                    drawn += panel.rightWidth();
                } else if (c >= codepoint + ScoreboardPanel.FIRST_MIDDLE && c < codepoint + ScoreboardPanel.GLYPHS) {
                    drawn += 1 << (c - codepoint - ScoreboardPanel.FIRST_MIDDLE);
                }
            }
            assertEquals(panel.widthFor(composed.width()), drawn, "the panel's width for: " + title);
        }
    }

    @Test
    void aLineIsSetInTheMiddleOrOnTheRightOfTheRoom() {
        List<String> lines = List.of("A much longer line than the others", "mid", "end");
        ScoreboardLayout.Composed composed = ScoreboardLayout.compose(null, 0, "Title", lines,
                List.of(ScoreboardLayout.Align.LEFT, ScoreboardLayout.Align.CENTER, ScoreboardLayout.Align.RIGHT),
                0, false);
        int room = TextWidth.of(lines.get(0));
        assertEquals(lines.get(0), composed.lines().get(0));
        assertEquals((room - TextWidth.of("mid")) / 2 + TextWidth.of("mid"), TextWidth.of(composed.lines().get(1)));
        assertEquals(room, TextWidth.of(composed.lines().get(2)));
        // With no panel the title is sent as written: the game centres it.
        assertEquals("Title", composed.title());
    }

    @Test
    void theRedNumbersAreAllowedForWhereTheGameStillDrawsThem() {
        List<String> lines = List.of("one", "two");
        int hidden = ScoreboardLayout.compose(null, 0, "", lines, List.of(), 0, false).width();
        int shown = ScoreboardLayout.compose(null, 0, "", lines, List.of(), 0, true).width();
        assertEquals(hidden + TextWidth.of(": 1"), shown);
    }

    // ---- definitions -----------------------------------------------------

    @Test
    void readsAScoreboard() throws IOException {
        ScoreboardDefinitions.Result result = ScoreboardDefinitions.parse(load(
                "main:\n"
                        + "  title: \"&b&lMY SERVER\"\n"
                        + "  lines:\n"
                        + "    - \"&7Rank {rank}\"\n"
                        + "    - \"\"\n"
                        + "    - { text: \"play.example.com\", align: center }\n"
                        + "  background:\n"
                        + "    color: \"#10121a\"\n"
                        + "    opacity: 80\n"
                        + "    padding: [6, 5, 4, 3]\n"
                        + "    border: \"#3b82f6\"\n"
                        + "  update: 10\n"
                        + "  worlds: [lobby]\n"
                        + "  permission: my.board\n"
                        + "  priority: 3\n"));
        assertTrue(result.diagnostics().isEmpty(), () -> result.diagnostics().toString());
        ScoreboardDefinitions.Board board = result.boards().get(id("p:main"));
        ScoreboardInfo info = board.info();
        assertEquals("&b&lMY SERVER", info.title());
        assertEquals(List.of("&7Rank {rank}", "", "play.example.com"), info.lines());
        assertEquals(ScoreboardLayout.Align.CENTER, board.aligns().get(2));
        assertTrue(info.background());
        assertTrue(info.automatic());
        assertEquals(10, info.update());
        assertEquals(List.of("lobby"), info.worlds());
        assertEquals("my.board", info.permission().orElseThrow());
        assertEquals(3, info.priority());
        assertNotNull(board.panel());
        assertEquals(5 + 10 + 27 + 2, board.panel().height());
        assertEquals(1, board.panel().outLeft());
        assertTrue(board.codepoint() >= GlyphAllocator.FIRST_CODEPOINT);
    }

    @Test
    void aScoreboardWithNoBackgroundKeepsTheGamesBox() throws IOException {
        ScoreboardDefinitions.Result result = ScoreboardDefinitions.parse(load(
                "plain:\n  lines: [one, two]\n  auto: false\n"));
        ScoreboardDefinitions.Board board = result.boards().get(id("p:plain"));
        assertNull(board.panel());
        assertFalse(board.info().background());
        assertFalse(board.info().automatic());
        assertEquals("", board.info().title());
    }

    @Test
    void aScoreboardWithNoLinesIsAnErrorAndTooManyAreCut() throws IOException {
        StringBuilder many = new StringBuilder("empty:\n  title: x\nlong:\n  lines:\n");
        for (int i = 0; i < 18; i++) {
            many.append("    - line ").append(i).append('\n');
        }
        ScoreboardDefinitions.Result result = ScoreboardDefinitions.parse(load(many.toString()));
        assertNull(result.boards().get(id("p:empty")));
        assertEquals(ScoreboardDefinitions.MAX_LINES, result.boards().get(id("p:long")).info().lines().size());
        assertEquals(2, result.diagnostics().size(), () -> result.diagnostics().toString());
    }

    @Test
    void aScoreboardTakesItsOwnRunOfGlyphs() throws IOException {
        LoadReport loaded = load("a:\n  lines: [x]\n  background: true\nb:\n  lines: [y]\n  background: \"#000000\"\n");
        Map<ContentId, Integer> codepoints = GlyphAllocator.allocate(loaded);
        assertEquals(ScoreboardPanel.GLYPHS, GlyphAllocator.SCOREBOARD_GLYPHS);
        assertEquals(GlyphAllocator.SCOREBOARD_GLYPHS,
                Math.abs(codepoints.get(id("p:a")) - codepoints.get(id("p:b"))));
    }
}
