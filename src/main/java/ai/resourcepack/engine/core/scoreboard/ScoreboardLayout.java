package ai.resourcepack.engine.core.scoreboard;

import ai.resourcepack.engine.core.font.Overlays;
import ai.resourcepack.engine.core.font.TextWidth;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a scoreboard's filled-in text into what is sent: the title carrying
 * the panel, and each line set where it was asked to be.
 *
 * <h2>The arithmetic it rests on</h2>
 *
 * The game makes a sidebar as wide as the widest of its title and lines, sets
 * every line at its left edge and centres the title. So a title made EXACTLY
 * as wide as the sidebar starts at that same left edge, and from a known
 * place the panel can be put anywhere: step left past the edge, draw the
 * pieces, step back to where the words go, write them, then step on to where
 * the title has to end. The widths come from {@link TextWidth}, which is
 * exact for the game's own letters and the engine's own icons and an estimate
 * for anything else; a line it under-measures makes the game's sidebar wider
 * than the title, and the panel then sits that far out.
 *
 * <p>Free of Bukkit.
 */
public final class ScoreboardLayout {

    private ScoreboardLayout() {
    }

    /** Where a line sits across the sidebar. */
    public enum Align {
        LEFT, CENTER, RIGHT
    }

    /** What is sent: the title, the lines, and how wide the text of the sidebar came to. */
    public record Composed(String title, List<String> lines, int width) {
    }

    /**
     * @param panel     the panel behind it, or null for the game's own box
     * @param codepoint the first of the panel's glyphs
     * @param title     the title, filled in, as a legacy string
     * @param lines     the lines, filled in
     * @param aligns    where each sits; a missing one is left
     * @param minWidth  the narrowest the text may be
     * @param numbers   whether the game draws its red numbers (before 1.20.3 it always does)
     */
    public static Composed compose(ScoreboardPanel panel, int codepoint, String title, List<String> lines,
                                   List<Align> aligns, int minWidth, boolean numbers) {
        int titleWidth = TextWidth.of(title);
        int[] widths = new int[lines.size()];
        int widest = 0;
        // What the number column takes beside each line, where there is one.
        int column = 0;
        for (int i = 0; i < lines.size(); i++) {
            widths[i] = TextWidth.of(lines.get(i));
            widest = Math.max(widest, widths[i]);
            if (numbers) {
                column = Math.max(column, TextWidth.of(": " + (lines.size() - 1 - i)));
            }
        }
        // The room the lines have, then the sidebar's whole text width.
        int room = Math.max(Math.max(widest, titleWidth - column), minWidth);
        int extra = panel == null ? 0 : panel.extraRight();
        int width = room + column + extra;
        if (panel != null) {
            int more = panel.narrowest() - width;
            if (more > 0) {
                room += more;
                width += more;
            }
        }

        List<String> out = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            Align align = i < aligns.size() && aligns.get(i) != null ? aligns.get(i) : Align.LEFT;
            int spare = room - widths[i];
            int indent = align == Align.CENTER ? spare / 2 : align == Align.RIGHT ? spare : 0;
            String line = lines.get(i);
            // The line's own width has to reach the title's when nothing else
            // does, or the game would not make the sidebar that wide; the
            // title carries it instead, so only the indent is added here.
            out.add(indent > 0 ? Overlays.space(indent) + line : line);
        }

        if (panel == null) {
            return new Composed(title, List.copyOf(out), width);
        }
        return new Composed(titled(panel, codepoint, title, titleWidth, width), List.copyOf(out), width);
    }

    /** The title: the panel's pieces, then its words centred on the panel, ending exactly {@code width} along. */
    private static String titled(ScoreboardPanel panel, int codepoint, String title, int titleWidth, int width) {
        StringBuilder out = new StringBuilder("§f");
        int start = panel.outLeft() + ScoreboardPanel.BOX_SIDE;
        out.append(Overlays.shift(start));
        int middle = panel.widthFor(width) - panel.leftWidth() - panel.rightWidth();
        piece(out, codepoint + ScoreboardPanel.LEFT);
        for (int index = ScoreboardPanel.MIDDLES - 1; index >= 0; index--) {
            int size = ScoreboardPanel.middleWidth(index);
            while (middle >= size) {
                piece(out, codepoint + ScoreboardPanel.FIRST_MIDDLE + index);
                middle -= size;
            }
        }
        piece(out, codepoint + ScoreboardPanel.RIGHT);
        // The cursor is now at the panel's right edge, two past the text's.
        int cursor = width + ScoreboardPanel.BOX_SIDE;
        // Centred on the panel rather than on the text, which is the same
        // place when the padding is even and the better one when it is not.
        int room = width - panel.extraRight();
        int at = Math.max(0, (room - titleWidth) / 2);
        out.append(Overlays.shift(cursor - at));
        if (!title.isEmpty()) {
            out.append("§r").append(title);
        }
        // A reset first: the words' own bold would make each space a pixel wider.
        out.append("§r").append(Overlays.space(width - at - titleWidth));
        return out.toString();
    }

    /**
     * One piece, and the pixel back that the font puts after every bitmap
     * glyph, so the next piece touches it.
     */
    private static void piece(StringBuilder out, int codepoint) {
        out.appendCodePoint(codepoint).append(Overlays.shift(1));
    }
}
