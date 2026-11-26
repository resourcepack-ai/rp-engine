package ai.resourcepack.engine.core.scoreboard;

import java.awt.image.BufferedImage;

/**
 * The panel drawn behind a scoreboard, and the pictures it is made of.
 *
 * <h2>How a picture gets behind a sidebar</h2>
 *
 * The game draws a sidebar in a fixed order: its own two grey rectangles, then
 * the title, then each line. So a picture that is part of the TITLE lands
 * over the rectangles and under the lines, which is exactly where a background
 * belongs. The panel is therefore font glyphs, put in front of the title's
 * words and stepped back over with negative space; {@link ScoreboardLayout}
 * writes that string.
 *
 * <h2>Why it is three kinds of piece</h2>
 *
 * A sidebar is as wide as its widest line, and what a line says is only known
 * when it is filled in for a player. So the panel cannot be one picture: it is
 * a left end, a right end, and a middle made of plain columns in widths of
 * powers of two, joined per player to whatever width their lines came to. Its
 * height is fixed, because the number of lines is.
 *
 * <h2>The game's own rectangles are still there</h2>
 *
 * Nothing a server sends takes them away, so they are allowed for instead.
 * They are black at a known strength (three tenths behind the lines, four
 * tenths behind the title), and a pixel of the panel that lands on one is
 * painted lighter and thinner by exactly as much as the rectangle under it
 * will add back ({@link #over}). What reaches the screen is then the colour
 * the pack asked for, the same inside the rectangle as in the padding around
 * it. A panel thinner than the rectangle itself cannot be reached that way,
 * and shows the rectangle; so does a client set to draw text backgrounds
 * everywhere, which uses another strength.
 *
 * <p>Free of Bukkit. Every measurement is in GUI pixels.
 */
public final class ScoreboardPanel {

    /** How many middle pieces there are: one, two, four ... 128 pixels wide. */
    public static final int MIDDLES = 8;

    /** How many glyphs a panel takes: the two ends and every middle. */
    public static final int GLYPHS = 2 + MIDDLES;

    /** Which glyph of a panel's run is which. The middles follow, narrowest first. */
    public static final int LEFT = 0;
    public static final int RIGHT = 1;
    public static final int FIRST_MIDDLE = 2;

    /** How far the game's rectangle reaches left of the text, and right of it. */
    static final int BOX_SIDE = 2;

    /** The rows of the game's rectangle behind the title: one above the text, and its eight. */
    static final int TITLE_ROWS = 9;

    /** The rows a line takes. */
    static final int LINE_ROWS = 9;

    /** How strong the game's rectangle is behind the title, and behind the lines. */
    static final double TITLE_BOX = 0.4;
    static final double BODY_BOX = 0.3;

    /** The text shader draws nothing fainter than this. */
    private static final double FAINTEST = 0.1;

    private final int lines;
    private final int padTop;
    private final int padRight;
    private final int padBottom;
    private final int padLeft;
    private final int radius;
    private final int fill;
    private final int border;
    private final int borderWidth;
    private final int header;
    private final int stripe;
    private final int stripeWidth;
    private final String texture;
    private final int sliceTop;
    private final int sliceRight;
    private final int sliceBottom;
    private final int sliceLeft;

    private ScoreboardPanel(Builder from) {
        this.lines = Math.max(1, from.lines);
        this.padTop = Math.max(0, from.padTop);
        this.padRight = Math.max(0, from.padRight);
        this.padBottom = Math.max(0, from.padBottom);
        this.padLeft = Math.max(0, from.padLeft);
        this.radius = Math.max(0, from.radius);
        this.fill = from.fill;
        this.border = from.border;
        this.borderWidth = (from.border >>> 24) == 0 ? 0 : Math.max(1, from.borderWidth);
        this.header = from.header;
        this.stripe = from.stripe;
        this.stripeWidth = (from.stripe >>> 24) == 0 ? 0 : Math.max(1, from.stripeWidth);
        this.texture = from.texture == null || from.texture.isBlank() ? null : from.texture.trim();
        this.sliceTop = Math.max(0, from.sliceTop);
        this.sliceRight = Math.max(0, from.sliceRight);
        this.sliceBottom = Math.max(0, from.sliceBottom);
        this.sliceLeft = Math.max(0, from.sliceLeft);
    }

    /** What a panel is made from. Colours are ARGB; one with no alpha is not drawn. */
    public static final class Builder {
        int lines = 1;
        int padTop = 5;
        int padRight = 5;
        int padBottom = 5;
        int padLeft = 5;
        int radius = 3;
        int fill = 0xC710121A;
        int border;
        int borderWidth = 1;
        int header;
        int stripe;
        int stripeWidth = 2;
        String texture;
        int sliceTop = 4;
        int sliceRight = 4;
        int sliceBottom = 4;
        int sliceLeft = 4;

        public Builder lines(int count) {
            this.lines = count;
            return this;
        }

        /** The room between the panel's edge and the text, on each side. */
        public Builder padding(int top, int right, int bottom, int left) {
            this.padTop = top;
            this.padRight = right;
            this.padBottom = bottom;
            this.padLeft = left;
            return this;
        }

        public Builder radius(int pixels) {
            this.radius = pixels;
            return this;
        }

        public Builder fill(int argb) {
            this.fill = argb;
            return this;
        }

        public Builder border(int argb, int width) {
            this.border = argb;
            this.borderWidth = width;
            return this;
        }

        /** A band of another colour behind the title. */
        public Builder header(int argb) {
            this.header = argb;
            return this;
        }

        /** A stripe down the left edge. */
        public Builder stripe(int argb, int width) {
            this.stripe = argb;
            this.stripeWidth = width;
            return this;
        }

        /** The pack's own picture, cut into nine: its corners kept, its edges and middle stretched. */
        public Builder texture(String path, int top, int right, int bottom, int left) {
            this.texture = path;
            this.sliceTop = top;
            this.sliceRight = right;
            this.sliceBottom = bottom;
            this.sliceLeft = left;
            return this;
        }

        public ScoreboardPanel build() {
            return new ScoreboardPanel(this);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The pack's own picture this is cut from, as written, or null when it is drawn from colours. */
    public String texture() {
        return texture;
    }

    /** How far the panel reaches left of the game's own rectangle. */
    public int outLeft() {
        return Math.max(0, padLeft - BOX_SIDE);
    }

    /** How far it reaches above the game's rectangle. The rectangle is already a row above the title. */
    public int outTop() {
        return Math.max(0, padTop - 1);
    }

    /** How far it reaches below. The last line's own row leaves two under its letters. */
    public int outBottom() {
        return Math.max(0, padBottom - 2);
    }

    /**
     * How much wider than its text the sidebar is made, so the text stops
     * short of the panel's right edge. The panel cannot reach further right
     * than the game's rectangle does, which is a pixel off the screen's edge,
     * so the room is made on the inside.
     */
    public int extraRight() {
        return Math.max(0, padRight - BOX_SIDE);
    }

    /** How tall every piece is. */
    public int height() {
        return outTop() + TITLE_ROWS + 1 + LINE_ROWS * lines + outBottom();
    }

    /**
     * The font ascent that puts a piece's top row where the panel starts: a
     * glyph is drawn from seven above the line's baseline less its ascent, and
     * the title's line starts a row under the top of the game's rectangle.
     */
    public int ascent() {
        return 8 + outTop();
    }

    /** How wide the left end is. Never narrower than what reaches outside the game's rectangle. */
    public int leftWidth() {
        if (texture != null) {
            return Math.max(1, Math.max(sliceLeft, outLeft()));
        }
        return Math.max(1, Math.max(Math.max(outLeft(), radius), borderWidth + stripeWidth));
    }

    /** How wide the right end is. */
    public int rightWidth() {
        if (texture != null) {
            return Math.max(1, sliceRight);
        }
        return Math.max(1, Math.max(radius, borderWidth));
    }

    /** How wide a middle piece is. */
    public static int middleWidth(int index) {
        return 1 << index;
    }

    /**
     * How wide the whole panel is for a sidebar whose text is {@code width}
     * wide: the game's rectangle, and what reaches left of it.
     */
    public int widthFor(int width) {
        return outLeft() + BOX_SIDE + width + BOX_SIDE;
    }

    /** The narrowest a sidebar can be and still hold both ends. */
    public int narrowest() {
        return Math.max(0, leftWidth() + rightWidth() - outLeft() - 2 * BOX_SIDE);
    }

    /** The left end, drawn. {@code source} is the pack's picture, or null for one drawn from colours. */
    public BufferedImage left(BufferedImage source) {
        int width = leftWidth();
        BufferedImage out = new BufferedImage(width, height(), BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height(); y++) {
                out.setRGB(x, y, shipped(Column.LEFT, x, y, source));
            }
        }
        return anchored(out);
    }

    /** The right end, drawn. */
    public BufferedImage right(BufferedImage source) {
        int width = rightWidth();
        BufferedImage out = new BufferedImage(width, height(), BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height(); y++) {
                out.setRGB(x, y, shipped(Column.RIGHT, width - 1 - x, y, source));
            }
        }
        return anchored(out);
    }

    /** One middle piece, drawn: the same column, {@link #middleWidth} times. */
    public BufferedImage middle(int index, BufferedImage source) {
        int width = middleWidth(index);
        BufferedImage out = new BufferedImage(width, height(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height(); y++) {
            int argb = shipped(Column.MIDDLE, 0, y, source);
            for (int x = 0; x < width; x++) {
                out.setRGB(x, y, argb);
            }
        }
        return anchored(out);
    }

    private enum Column {
        /** Counted from the panel's left edge. */
        LEFT,
        /** Any column between the ends: they are all alike. */
        MIDDLE,
        /** Counted from the panel's right edge, the last column being 0. */
        RIGHT
    }

    /** A pixel as it ships: what the pack asked for, allowing for the game's rectangle under it. */
    private int shipped(Column column, int index, int y, BufferedImage source) {
        int wanted = source == null ? drawn(column, index, y) : cut(column, index, y, source);
        boolean insideColumn = column != Column.LEFT || index >= outLeft();
        if (!insideColumn) {
            return visible(wanted);
        }
        int row = y - outTop();
        if (row < 0 || row >= TITLE_ROWS + 1 + LINE_ROWS * lines) {
            return visible(wanted);
        }
        return over(wanted, row < TITLE_ROWS ? TITLE_BOX : BODY_BOX);
    }

    /** A pixel too faint for the shader to draw is not shipped as anything. */
    private static int visible(int argb) {
        return (argb >>> 24) / 255.0 < FAINTEST ? 0 : argb;
    }

    /**
     * The pixel to draw over black of strength {@code box} so that the two
     * together come out as {@code wanted} would alone.
     *
     * <p>Over a background {@code B}, the rectangle leaves {@code B(1 - box)}
     * and a pixel {@code (c, a)} on top makes {@code c·a + B(1 - box)(1 - a)}.
     * For that to be {@code C·A + B(1 - A)} whatever {@code B} is, the two
     * coverages have to multiply to the one wanted, and the colour has to
     * carry the light the black took: {@code a = 1 - (1 - A)/(1 - box)} and
     * {@code c = C·A/a}. A pixel wanted thinner than the rectangle has no
     * answer and is left out, which shows the rectangle as it is.
     */
    static int over(int wanted, double box) {
        double alpha = (wanted >>> 24) / 255.0;
        double needed = 1 - (1 - alpha) / (1 - box);
        if (needed < FAINTEST) {
            return 0;
        }
        int a = (int) Math.round(needed * 255);
        int r = lift((wanted >> 16) & 0xFF, alpha, needed);
        int g = lift((wanted >> 8) & 0xFF, alpha, needed);
        int b = lift(wanted & 0xFF, alpha, needed);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int lift(int channel, double alpha, double needed) {
        return (int) Math.max(0, Math.min(255, Math.round(channel * alpha / needed)));
    }

    /** A pixel of a panel drawn from colours. */
    private int drawn(Column column, int index, int y) {
        int height = height();
        // How far in from the nearest side edge, and from the top or bottom.
        int fromSide = column == Column.MIDDLE ? Integer.MAX_VALUE : index;
        int fromEnd = Math.min(y, height - 1 - y);
        boolean edge = fromEnd < borderWidth || fromSide < borderWidth;
        if (fromSide < radius && fromEnd < radius) {
            double dx = radius - fromSide - 0.5;
            double dy = radius - fromEnd - 0.5;
            double distance = Math.sqrt(dx * dx + dy * dy);
            if (distance > radius) {
                return 0;
            }
            edge = distance > radius - borderWidth;
        }
        if (edge && borderWidth > 0) {
            return border;
        }
        if (column == Column.LEFT && stripeWidth > 0 && index < borderWidth + stripeWidth) {
            return stripe;
        }
        if ((header >>> 24) != 0 && y < outTop() + TITLE_ROWS) {
            return header;
        }
        return fill;
    }

    /** A pixel of a panel cut from the pack's picture: corners kept, the rest stretched. */
    private int cut(Column column, int index, int y, BufferedImage source) {
        int width = source.getWidth();
        int rows = source.getHeight();
        int left = Math.min(sliceLeft, width);
        int right = Math.min(sliceRight, Math.max(0, width - left));
        int centre = Math.min(width - 1, left + Math.max(0, width - left - right) / 2);
        int x;
        if (column == Column.LEFT) {
            x = index < left ? index : centre;
        } else if (column == Column.RIGHT) {
            x = index < right ? width - 1 - index : centre;
        } else {
            x = centre;
        }
        int height = height();
        int top = Math.min(sliceTop, rows);
        int bottom = Math.min(sliceBottom, Math.max(0, rows - top));
        int sourceY;
        if (y < top) {
            sourceY = y;
        } else if (y >= height - bottom) {
            sourceY = rows - (height - y);
        } else {
            int span = Math.max(1, height - top - bottom);
            int from = Math.max(1, rows - top - bottom);
            sourceY = top + Math.min(from - 1, (y - top) * from / span);
        }
        return source.getRGB(Math.max(0, Math.min(width - 1, x)), Math.max(0, Math.min(rows - 1, sourceY)));
    }

    /**
     * Makes sure the game measures a piece as wide as it is. A bitmap glyph is
     * advanced by its last column with anything in it, so a piece whose last
     * column came out empty would be measured short and every piece after it
     * would sit a pixel left. One pixel at the faintest alpha there is, which
     * the shader does not draw, holds the width.
     */
    private static BufferedImage anchored(BufferedImage image) {
        int x = image.getWidth() - 1;
        for (int y = 0; y < image.getHeight(); y++) {
            if ((image.getRGB(x, y) >>> 24) != 0) {
                return image;
            }
        }
        image.setRGB(x, 0, 0x01000000);
        return image;
    }
}
