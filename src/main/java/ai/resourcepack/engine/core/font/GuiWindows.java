package ai.resourcepack.engine.core.font;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Where a container's window sits on the screen, and what that means for a
 * backdrop drawn over it.
 *
 * <p>Ported from studio's {@code Studio's vanilla GUI catalogue}, which is the only
 * place this arithmetic has ever been correct. Guessing at it produces a
 * picture that is nearly right, which is worse than one that is obviously
 * wrong because it looks like a rounding bug rather than a missing formula.
 *
 * <p>The shape of it: a GUI sheet is a 256×256 image with the window art
 * centred on it. The glyph's top renders at {@code titleY + 7 - ascent}, so an
 * ascent of {@code 13 + offsetY} pins the sheet's top edge {@code offsetY}
 * pixels above the window — which is exactly where it needs to be, because the
 * art is inset by that much. The negative space is the mirror of it
 * horizontally: the title starts at {@code titleX}, and the sheet has to start
 * {@code titleX + offsetX} pixels to the left of that.
 */
public final class GuiWindows {

    /** A GUI sheet is this square. Studio writes them at this size and so does everybody else. */
    public static final int SHEET_SIZE = 256;

    /** Vanilla draws a container title with its glyph cell's top at this y. */
    private static final int TITLE_Y = 6;

    /** Where a container title starts, measured from the window's left edge. */
    private static final int TITLE_X = 8;

    /** The width and height of each container's window, in pixels. */
    private static final Map<String, int[]> WINDOWS = windows();

    private GuiWindows() {
    }

    private static Map<String, int[]> windows() {
        Map<String, int[]> map = new LinkedHashMap<>();
        // Vanilla composes every chest out of one sheet: 17px of title bar,
        // 18px per slot row, then a 97px player-inventory block.
        for (int rows = 1; rows <= 6; rows++) {
            map.put("chest_9x" + rows, new int[]{176, 114 + 18 * rows});
        }
        map.put("dispenser", new int[]{176, 166});
        map.put("hopper", new int[]{176, 133});
        map.put("anvil", new int[]{176, 166});
        map.put("beacon", new int[]{230, 219});
        map.put("brewing", new int[]{176, 166});
        map.put("crafting", new int[]{176, 166});
        map.put("enchanting", new int[]{176, 166});
        map.put("furnace", new int[]{176, 166});
        map.put("grindstone", new int[]{176, 166});
        map.put("loom", new int[]{176, 166});
        map.put("cartography", new int[]{176, 166});
        map.put("stonecutter", new int[]{176, 166});
        return Map.copyOf(map);
    }

    /** Every container this knows the geometry of. */
    public static java.util.Set<String> containers() {
        return WINDOWS.keySet();
    }

    /**
     * The ascent that lands a sheet's top edge on the window's top edge.
     *
     * <p>{@code 13 + offsetY}, where the offset is how far down the 256 sheet
     * the window art was centred.
     */
    public static Optional<Integer> ascentFor(String container) {
        int[] window = WINDOWS.get(container);
        return window == null
                ? Optional.empty()
                : Optional.of(TITLE_Y + 7 + (SHEET_SIZE - window[1]) / 2);
    }

    /**
     * The negative space, in pixels, that slides a sheet onto its window.
     *
     * <p>{@code titleX + offsetX}. Studio's catalogue carries this as a signed
     * advance; here it is a leftward distance, because that is the only
     * direction a backdrop is ever shifted and a sign nobody varies is a sign
     * everybody gets wrong once.
     *
     * <p>This is the left-aligned case, which is every container title the game
     * draws. A <em>centred</em> title would need the glyph's own measured
     * advance — the game scans the sheet from the right for its first opaque
     * column — and nothing here has the pixels. If a centred screen ever
     * matters, that measurement is `guiGlyphAdvance` in studio's catalogue and
     * belongs in the builder, where the PNG is in hand.
     */
    public static Optional<Integer> offsetFor(String container) {
        int[] window = WINDOWS.get(container);
        return window == null
                ? Optional.empty()
                : Optional.of(TITLE_X + (SHEET_SIZE - window[0]) / 2);
    }
}
