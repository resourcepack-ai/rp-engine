package ai.resourcepack.engine.core.font;

/**
 * How wide a string is when the client draws it, in GUI pixels. Internal.
 *
 * <h2>Why this has to exist here</h2>
 *
 * A pack can position the first run of text after a picture, because it knows
 * how wide the picture is. It cannot position the second one: the cursor has
 * moved by however wide the first run turned out to be, and what it turned out
 * to say is not knowable when the pack is built — a {@code {name}} placeholder
 * is filled per player, one tick before the line is sent. So the only place
 * that can measure is this one, and without the measurement every run after the
 * first lands a whole string-width to the right of where the author put it.
 *
 * <h2>What it is measuring</h2>
 *
 * Vanilla's default font, which is a bitmap: every glyph has an integer width
 * and the renderer puts exactly one pixel between them. The table below is that
 * font's widths, and it is a table rather than a calculation because there is
 * no rule — {@code i} is one pixel wide and {@code @} is six.
 *
 * <p><b>It is an estimate outside ASCII, and deliberately so.</b> A character
 * this table has never heard of is counted as five plus the gap, which is what
 * the great majority of glyphs in the font are. Being a pixel or two out on a
 * label containing Cyrillic is a label a pixel or two out; refusing to measure
 * would be a label somewhere else entirely.
 *
 * <p><b>Except for the engine's own glyphs, which it knows exactly.</b> An
 * icon is a picture this engine put in the font, so its width is not an
 * estimate: {@link FontAssets} records each one as it builds the font, and a
 * value that carries an icon (a kill feed's weapon, a status badge) measures
 * as itself rather than as five pixels, which would put every run after it in
 * the wrong place.
 */
public final class TextWidth {

    private TextWidth() {
    }

    // Measured from vanilla font/ascii.png (rightmost opaque column).
    private static final String NARROW = "!.,:;i|'" + "`l" + "I[]\"* ()t{}" + "fk<>" + "@~";

    private static final int[] NARROW_WIDTH = {
        1, 1, 1, 1, 1, 1, 1, 1,
        2, 2,
        3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3,
        4, 4, 4, 4,
        6, 6,
    };

    /** What the renderer puts between two glyphs. */
    private static final int GAP = 1;

    /** The width of anything the table does not name. See the class note. */
    private static final int DEFAULT = 5;

    /**
     * How far drawing {@code text} moves the cursor.
     *
     * <p>Legacy section signs are skipped along with the character after them —
     * they are formatting rather than glyphs — except that {@code §l} turns bold
     * on, which the font renders by drawing every glyph twice one pixel apart
     * and so costs a pixel per character until it is turned off.
     */
    public static int of(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int width = 0;
        boolean bold = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '§' && i + 1 < text.length()) {
                char code = Character.toLowerCase(text.charAt(++i));
                if (code == 'l') {
                    bold = true;
                } else if (code == 'r' || (code >= '0' && code <= '9') || (code >= 'a' && code <= 'f')) {
                    // A colour resets every style, bold included. The other
                    // codes (k, m, n, o) leave it alone.
                    bold = false;
                }
                continue;
            }
            width += widthOf(c) + GAP + (bold ? 1 : 0);
        }
        return width;
    }

    /** The engine's own glyphs, by codepoint: the drawn width in pixels, without the gap. */
    private static final java.util.Map<Integer, Integer> GLYPHS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Records how wide one of the engine's own glyphs is drawn, in pixels and
     * without the one-pixel gap after it. Called as the font is built; a later
     * build replaces a codepoint's width.
     */
    public static void glyph(int codepoint, int width) {
        GLYPHS.put(codepoint, Math.max(0, width));
    }

    private static int widthOf(int c) {
        int step = c - FontAssets.FIRST_SPACE_CODEPOINT;
        if (step >= 0 && step < FontAssets.SPACE_STEPS) {
            // The engine's negative spaces: the whole move, so less the gap
            // the caller adds back.
            return -(1 << step) - GAP;
        }
        Integer own = GLYPHS.get(c);
        if (own != null) {
            return own;
        }
        int at = NARROW.indexOf(c);
        return at < 0 ? DEFAULT : NARROW_WIDTH[at];
    }
}
