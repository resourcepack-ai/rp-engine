package ai.resourcepack.engine.core.font;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.OverlayInfo;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A head is a run whose glyph is a picture, and these are the two things about
 * it that nothing else can catch.
 *
 * <p>The first is the ADVANCE. Every run after a head is placed by walking a
 * cursor across the ones before it, and the cursor is moved by measuring what
 * each run drew — against a table of vanilla's own glyph widths. A head's glyph
 * is one the pack invented, so that table answers with its default, and a
 * 32-pixel head measured as six puts the next label twenty-seven pixels left of
 * where the author put it. The run states its own width for exactly this, and
 * this asserts the composer reads it.
 *
 * <p>The second is WHOSE face it is. A push bakes one glyph per recipient and
 * the run says which character is whose, so the same overlay drawn for two
 * players has to come out with two different characters in it — and for anybody
 * the push never named, with the default one rather than nothing.
 */
class ShaderHeadRunTest {

    private static final String PLUS = "abcdefghij";
    private static final String MINUS = "ABCDEFGHIJ";

    /**
     * The head glyphs, as studio allocates them: consecutive codepoints out of
     * the reserved block at the top of the Private Use Area, index 0 being the
     * fallback everybody unnamed draws.
     */
    private static final String STEVE = "";
    private static final String ALICE_FACE = "";
    private static final String BOB_FACE = "";

    /** How wide the head was declared. Its glyph height plus the inter-glyph pixel. */
    private static final int HEAD_ADVANCE = 33;

    private static final UUID ALICE = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID BOB = UUID.fromString("99999999-8888-7777-6666-555555555555");

    /** The key studio writes: lowercase, undashed. */
    private static String key(UUID uuid) {
        return uuid.toString().toLowerCase(Locale.ROOT).replace("-", "");
    }

    private OverlayInfo overlay(OverlayInfo.Slot slot) {
        return OverlayInfo.pushed(ContentId.parse("test:hud").orElseThrow(), "canvas", "", slot,
                        "#fe0001", "", "", List.of(),
                        List.of(
                                new OverlayInfo.OverlayRun("", 0, STEVE, "", "#fd0002", HEAD_ADVANCE,
                                        Map.of(key(ALICE), ALICE_FACE, key(BOB), BOB_FACE)),
                                new OverlayInfo.OverlayRun("", 40, "{player}", "", "#fd0003")))
                .withCursor(257, PLUS, MINUS);
    }

    private static int shiftWidth(String text) {
        return text.chars()
                .map(c -> PLUS.indexOf(c) >= 0 ? 1 << PLUS.indexOf(c) : -(1 << MINUS.indexOf(c)))
                .sum();
    }

    @Test void theHeadsOwnWidthPlacesTheLabelAfterIt() {
        BaseComponent[] parts = Overlays.compose(overlay(OverlayInfo.Slot.ACTION_BAR),
                Map.of("player", "Alice"), null);
        int cursor = 257;
        for (int i = 1; i < parts.length; i++) {
            TextComponent part = (TextComponent) parts[i];
            if (part.getColorRaw() == null) {
                cursor += shiftWidth(part.getText());
                continue;
            }
            if (part.getText().equals(STEVE)) {
                assertEquals(0, cursor, "the head starts where it was placed");
                // The STATED advance, not what a table of vanilla's widths makes
                // of one private-use character — which is the table's default and
                // would leave the next label most of a head to the left.
                assertNotEquals(HEAD_ADVANCE, TextWidth.of(STEVE));
                cursor += HEAD_ADVANCE;
            } else {
                assertEquals(40, cursor, "the label lands where the author put it, after the head");
                cursor += TextWidth.of(part.getText());
            }
        }
        assertEquals(257, cursor, "the overlay's own advance is restored, so nothing drifts sideways");
    }

    @Test void everyPlayerGetsTheirOwnFaceAndStrangersGetSteve() {
        assertEquals(ALICE_FACE, drawnHead(ALICE));
        assertEquals(BOB_FACE, drawnHead(BOB));
        // Nobody in particular: an exported pack, or somebody who joined after
        // the push. The default character is Steve's glyph rather than no glyph,
        // because a missing character renders as the missing-glyph box.
        assertEquals(STEVE, drawnHead(UUID.randomUUID()));
        assertEquals(STEVE, drawnHead(null));
    }

    private String drawnHead(UUID viewer) {
        return overlay(OverlayInfo.Slot.ACTION_BAR).runs().get(0).textFor(viewer);
    }

    @Test void aRunWithNoStatedWidthIsStillMeasured() {
        assertEquals(0, overlay(OverlayInfo.Slot.ACTION_BAR).runs().get(1).advance(),
                "a label states nothing, so the composer measures it");
    }

    /**
     * The boss bar draws the same runs, positioned the same way.
     *
     * <p>It could not, once: a legacy string has no code for a font, and the
     * canvas glyph used to live in one of its own. The glyph is in the pack's
     * default font now, so the only thing legacy has to spell is the exact RGB
     * the shader matches on — which {@code §x} does.
     */
    @Test void theBossBarCarriesThePictureTheShiftsAndTheColour() {
        String legacy = Overlays.legacyComposed(overlay(OverlayInfo.Slot.BOSS_BAR),
                Map.of("player", "Alice"), null);
        assertTrue(legacy.contains("canvas"), legacy);
        assertTrue(legacy.contains(STEVE), "the head's default glyph is drawn");
        assertTrue(legacy.contains("Alice"), "the placeholder is filled");
        // The signature, spelled the only way a legacy string can spell one.
        assertTrue(legacy.contains("§x§f§d§0§0§0§2"), legacy);
        assertTrue(legacy.chars().anyMatch(c -> PLUS.indexOf(c) >= 0 || MINUS.indexOf(c) >= 0),
                "the runs are positioned rather than appended");
    }
}
