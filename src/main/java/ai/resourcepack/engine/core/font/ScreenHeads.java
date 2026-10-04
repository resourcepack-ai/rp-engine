package ai.resourcepack.engine.core.font;

import java.util.List;

/**
 * The player heads on one pushed screen: where the viewer's face goes, and the
 * characters that draw it.
 *
 * <p>A screen is one picture in the container's title, so the face cannot be a
 * texture in the pack — every viewer's is different and the pack is the same
 * file for all of them. It is drawn out of text instead, the way chat-head
 * plugins draw one: the pack carries, per head, eight white square glyphs, one
 * per face row, each pinned at that row's height; the title gets the viewer's
 * sixty-four face pixels as those glyphs, each in its pixel's colour. A bitmap
 * glyph advances one pixel past its width, so every pixel is followed by a
 * one-pixel step back, and every row by a step back to the head's left edge.
 *
 * <p>Studio worked out everything that depends on the pack — the row glyphs,
 * their heights and the shift alphabet — and the engine adds only what it alone
 * knows: whose face, in which colours. The walk starts and ends where the
 * screen glyph left the cursor ({@code advance}), so the title is exactly as
 * wide as it was without the heads, and a centred title stays where it was.
 */
public final class ScreenHeads {

    /** One head: its left edge in sheet pixels, its scale, and its eight row characters. */
    public record Head(int x, int size, boolean hat, List<String> rows) {
        public Head {
            rows = List.copyOf(rows);
        }
    }

    private final int advance;
    private final String shiftPlus;
    private final String shiftMinus;
    private final List<Head> heads;

    public ScreenHeads(int advance, String shiftPlus, String shiftMinus, List<Head> heads) {
        this.advance = advance;
        this.shiftPlus = shiftPlus == null ? "" : shiftPlus;
        this.shiftMinus = shiftMinus == null ? "" : shiftMinus;
        this.heads = List.copyOf(heads);
    }

    public int advance() {
        return advance;
    }

    public String shiftPlus() {
        return shiftPlus;
    }

    public String shiftMinus() {
        return shiftMinus;
    }

    public List<Head> heads() {
        return heads;
    }

    /** Whether there is anything to draw and the alphabet to draw it with. */
    public boolean usable() {
        return !heads.isEmpty() && !shiftPlus.isEmpty() && !shiftMinus.isEmpty();
    }

    /** Where the face pixels come from: 64 ARGB values, row by row, with or without the hat. */
    @FunctionalInterface
    public interface Faces {
        int[] face(java.util.UUID player, boolean hat);
    }

    /**
     * The characters to append after the screen's own title.
     *
     * <p>A pixel the face leaves transparent (alpha under half) is stepped over
     * rather than drawn, so the sheet's own art shows through it — which is
     * Steve, where studio put him.
     */
    public String draw(java.util.UUID viewer, Faces faces) {
        if (!usable()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (Head head : heads) {
            if (head.rows().size() != 8 || head.size() < 1) {
                continue;
            }
            int[] face = faces.face(viewer, head.hat());
            if (face == null || face.length < 64) {
                continue;
            }
            out.append(shift(head.x() - advance));
            String colour = null;
            for (int row = 0; row < 8; row++) {
                String glyph = head.rows().get(row);
                for (int col = 0; col < 8; col++) {
                    int argb = face[row * 8 + col];
                    if ((argb >>> 24) < 128) {
                        out.append(shift(head.size()));
                        continue;
                    }
                    String next = colour(argb);
                    if (!next.equals(colour)) {
                        out.append(next);
                        colour = next;
                    }
                    out.append(glyph).append(shift(-1));
                }
                out.append(shift(-8 * head.size()));
            }
            out.append(shift(advance - head.x()));
        }
        // Back to white, so nothing after the heads is tinted by the last pixel.
        if (out.length() > 0) {
            out.append(net.md_5.bungee.api.ChatColor.WHITE);
        }
        return out.toString();
    }

    private static String colour(int argb) {
        return net.md_5.bungee.api.ChatColor.of(new java.awt.Color(argb & 0xFFFFFF)).toString();
    }

    /** Powers of two from the pack's own alphabet, the largest step repeated past 1023. */
    String shift(int pixels) {
        String alphabet = pixels < 0 ? shiftMinus : shiftPlus;
        if (alphabet.isEmpty() || pixels == 0) {
            return "";
        }
        int left = Math.abs(pixels);
        StringBuilder out = new StringBuilder();
        int steps = alphabet.codePointCount(0, alphabet.length());
        for (int step = steps - 1; step >= 0; step--) {
            int size = 1 << step;
            int at = alphabet.offsetByCodePoints(0, step);
            String ch = new String(Character.toChars(alphabet.codePointAt(at)));
            while (left >= size) {
                out.append(ch);
                left -= size;
            }
        }
        return out.toString();
    }
}
