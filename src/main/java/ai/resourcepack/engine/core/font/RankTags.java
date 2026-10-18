package ai.resourcepack.engine.core.font;

import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Rank tags: an icon whose picture is drawn from words and a few choices
 * instead of read from a PNG — the rounded red OWNER a server puts in front of
 * a player's name, as a LuckPerms prefix or suffix.
 *
 * <pre>
 * owner:
 *   tag:
 *     text: OWNER
 *     background: "#e53935"
 *     face: gloss
 *     icon: crown
 *     animation: glint
 * </pre>
 *
 * <p>Everything after that is an icon like any other: one codepoint (one per
 * frame when it is animated), one bitmap provider,
 * {@code %rpengine_icon_mypack:owner%}, {@code :owner:} in chat. This class only
 * decides the pixels, and the build writes them into the bundle where the
 * provider points (see {@link FontAssets}).
 *
 * <p>It is a kit of parts rather than one layout: a shape, a face, a frame
 * (border, rim, a lip under it), the words' effect, an icon in a cell, beside
 * the words or on a tile of its own, and an animation. <strong>The drawing is
 * ResourcePack AI Studio's rank tag kit, pixel for pixel</strong>, so a tag
 * designed there and the same tag written here come out identical. Every rule
 * below is the kit's, in its order and with its rounding, and the test beside
 * this class draws the fixtures Studio wrote, every frame of every animation,
 * and compares every pixel. Change one end and the test fails until the other
 * agrees.
 *
 * <p>The words are set in the kit's block capitals ({@code rank-tags/font.txt},
 * Tiny5's capitals under the SIL Open Font License), and the icons are Studio's
 * own set ({@code rank-tags/icons.txt}). Studio can also set a tag in the
 * game's own font or a pack's uploaded one, and make its face of a block
 * texture; those are Mojang's or the pack owner's and not ours to put in a
 * plugin, so a tag using one arrives from Studio already drawn, and a
 * hand-written one is told and drawn without it.
 *
 * <p><strong>An animation is frames, played by the server</strong>, as every
 * animated icon here is ({@code IconInfo}): sixteen frames at ten a second,
 * Studio's loop. Text sent once, a chat line, shows the first frame; the tab
 * list, a scoreboard or anything else that redraws shows it play. (A tag drawn
 * in Studio plays everywhere, through the pack's text shader, which a content
 * folder does not carry.)
 */
public final class RankTags {

    private RankTags() {
    }

    /** What a tag reads at most, in characters. */
    public static final int MAX_TEXT = 24;
    public static final int MAX_PAD = 12;
    public static final int MAX_SCALE = 4;
    public static final int MAX_OFFSET = 12;
    /** A font glyph can be no bigger than this either way. */
    public static final int MAX_EDGE = 256;
    /** An animated tag's loop: Studio's. */
    public static final int FRAMES = 16;
    public static final int FPS = 10;

    public static final List<String> SHAPES = List.of("rect", "round", "pill", "chamfer", "arrow", "ribbon", "slant");
    public static final List<String> FACES = List.of("flat", "gloss", "panel", "stripes", "dither", "sparkle");
    public static final List<String> EFFECTS = List.of("none", "shadow", "drop", "outline", "outline+shadow");
    public static final List<String> ANIMATIONS = List.of("none", "glint", "rainbow", "twinkle", "pulse");
    public static final List<String> ICON_STYLES = List.of("cell", "inline", "tile");

    /**
     * A tag, every choice resolved. Colours are {@code 0xRRGGBB}; a null
     * optional colour is the kit's own, worked out from the background, and
     * {@code noBorder} is a border taken away rather than left to it.
     */
    public record Spec(String text, String shape, String face, int fill, Integer fill2, Integer fill3,
                       Integer border, boolean noBorder, Integer rim, boolean lip, int ink, Integer ink2,
                       Integer accent, String effect, Integer effectColor, String icon, String iconStyle,
                       Integer iconFill, Integer pad, String animation, int scale, int offset) {

        /** Studio's new tag: a rounded red OWNER, white words with a shadow. */
        public static Spec defaults(String text) {
            return new Spec(text, "round", "flat", 0xd9413b, null, null, 0x7c1717, false, null, false, 0xffffff,
                    null, null, "shadow", 0xa82a26, null, null, null, null, "none", 1, 0);
        }

        /** Whether it plays: a pulse lights the border, so with none it is a still tag. */
        public boolean animated() {
            return !animation.equals("none") && !(animation.equals("pulse") && noBorder);
        }

        /** How many frames it has: one for a still tag. */
        public int frames() {
            return animated() ? FRAMES : 1;
        }
    }

    /** A drawn tag: ARGB, row by row, at its scale — one frame of what the PNG holds. */
    public record Drawn(int width, int height, int ascent, int[] argb) {

        public boolean fits() {
            return width <= MAX_EDGE && height <= MAX_EDGE;
        }

        public int at(int x, int y) {
            return argb[y * width + x];
        }
    }

    // ------------------------------------------------------------- colours

    /** The game's sixteen chat colours, by their code and by their name, for a hand-written colour. */
    private static final Map<String, Integer> NAMED = new HashMap<>();

    static {
        String[][] colours = {
            {"black", "000000"}, {"dark_blue", "0000aa"}, {"dark_green", "00aa00"}, {"dark_aqua", "00aaaa"},
            {"dark_red", "aa0000"}, {"dark_purple", "aa00aa"}, {"gold", "ffaa00"}, {"gray", "aaaaaa"},
            {"dark_gray", "555555"}, {"blue", "5555ff"}, {"green", "55ff55"}, {"aqua", "55ffff"},
            {"red", "ff5555"}, {"light_purple", "ff55ff"}, {"yellow", "ffff55"}, {"white", "ffffff"},
        };
        for (String[] c : colours) {
            NAMED.put(c[0], Integer.parseInt(c[1], 16));
        }
    }

    private static final int WHITE = 0xffffff;
    private static final int T = -1;

    /** The kit's rounding of a channel: half up, then clamped. */
    private static int fmt(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }

    /** k under 1 darkens toward black, over 1 lightens toward white. */
    static int shade(int c, double k) {
        int out = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            double v = (c >> shift) & 0xff;
            out = (out << 8) | fmt(k <= 1 ? v * k : v + (255 - v) * (k - 1));
        }
        return out;
    }

    static int mix(int a, int b, double t) {
        int out = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            double v = (a >> shift) & 0xff;
            double w = (b >> shift) & 0xff;
            out = (out << 8) | fmt(v + (w - v) * t);
        }
        return out;
    }

    private static double[] hsl(int c) {
        double r = ((c >> 16) & 0xff) / 255.0;
        double g = ((c >> 8) & 0xff) / 255.0;
        double b = (c & 0xff) / 255.0;
        double max = Math.max(r, Math.max(g, b));
        double min = Math.min(r, Math.min(g, b));
        double l = (max + min) / 2;
        if (max == min) {
            return new double[] {0, 0, l};
        }
        double d = max - min;
        double s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
        double h = max == r ? (g - b) / d + (g < b ? 6 : 0) : max == g ? (b - r) / d + 2 : (r - g) / d + 4;
        return new double[] {h / 6, s, l};
    }

    private static int fromHsl(double h, double s, double l) {
        int out = 0;
        for (int n : new int[] {0, 8, 4}) {
            double k = (n + h * 12) % 12;
            double a = s * Math.min(l, 1 - l);
            double f = l - a * Math.max(-1, Math.min(Math.min(k - 3, 9 - k), 1));
            out = (out << 8) | (int) Math.round(f * 255);
        }
        return out;
    }

    private static int hueShift(int c, double by) {
        double[] h = hsl(c);
        return fromHsl((h[0] + by + 1) % 1, h[1], h[2]);
    }

    /** {@code #rrggbb}, {@code rrggbb}, {@code #rgb} or one of the game's colour names. */
    static Optional<Integer> colour(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        Integer named = NAMED.get(v.replace(' ', '_'));
        if (named != null) {
            return Optional.of(named);
        }
        if (v.startsWith("#")) {
            v = v.substring(1);
        }
        if (v.length() == 3 && v.matches("[0-9a-f]{3}")) {
            v = "" + v.charAt(0) + v.charAt(0) + v.charAt(1) + v.charAt(1) + v.charAt(2) + v.charAt(2);
        }
        return v.matches("[0-9a-f]{6}") ? Optional.of(Integer.parseInt(v, 16)) : Optional.empty();
    }

    /** The kit's random numbers, for the sparkles and the twinkle: the same sequence as Studio's. */
    private static final class Random {
        private long s;

        Random(long seed) {
            s = seed & 0xffffffffL;
            if (s == 0) {
                s = 1;
            }
        }

        double next() {
            s = (s * 1664525L + 1013904223L) & 0xffffffffL;
            return s / 4294967296.0;
        }
    }

    // ------------------------------------------------------------- the words

    /** Studio's rule for what a tag may say: printable ASCII, single spaces, at most {@link #MAX_TEXT}. */
    static String normalizeText(String raw) {
        String kept = raw == null ? "" : raw.replaceAll("[^\\x20-\\x7e]", "").replaceAll("\\s+", " ").trim();
        return kept.length() > MAX_TEXT ? kept.substring(0, MAX_TEXT) : kept;
    }

    /** One capital: as wide as its rows, five rows tall. */
    record Glyph(int width, boolean[][] rows) {
    }

    private static final int FONT_HEIGHT = 5;
    private static volatile Map<Integer, Glyph> font;

    /** The block capitals, read once from the jar. */
    static Map<Integer, Glyph> font() {
        Map<Integer, Glyph> loaded = font;
        if (loaded != null) {
            return loaded;
        }
        Map<Integer, Glyph> read = new HashMap<>();
        for (String line : lines("/rank-tags/font.txt")) {
            String[] parts = line.split(" ");
            if (parts.length != 2) {
                continue;
            }
            int cp = Integer.parseInt(parts[0], 16);
            if (!parts[1].contains("/") && !parts[1].contains("#") && !parts[1].contains(".")) {
                // A space: its width alone.
                read.put(cp, new Glyph(Integer.parseInt(parts[1]), new boolean[FONT_HEIGHT][Integer.parseInt(parts[1])]));
                continue;
            }
            String[] rows = parts[1].split("/", -1);
            boolean[][] ink = new boolean[rows.length][rows[0].length()];
            for (int y = 0; y < rows.length; y++) {
                for (int x = 0; x < rows[y].length() && x < ink[y].length; x++) {
                    ink[y][x] = rows[y].charAt(x) == '#';
                }
            }
            read.put(cp, new Glyph(rows[0].length(), ink));
        }
        font = Map.copyOf(read);
        return font;
    }

    /** A character's capital, lower case as upper; null when the font has none, and the character is left out. */
    static Glyph glyph(int codepoint) {
        String upper = new String(Character.toChars(codepoint)).toUpperCase(Locale.ROOT);
        if (upper.codePointCount(0, upper.length()) != 1) {
            return null;
        }
        return font().get(upper.codePointAt(0));
    }

    /** Whether the font sets a character; anything else is dropped from a tag. */
    public static boolean covers(int codepoint) {
        return glyph(codepoint) != null;
    }

    /** The ink of the words, and which character each inked pixel belongs to (for the accent). */
    private record Mask(int w, int h, int cap, boolean[][] on, int[][] charAt) {
    }

    private static Mask textMask(String text) {
        record Placed(Glyph glyph, int advance, int index) {
        }
        List<Placed> glyphs = new ArrayList<>();
        int[] chars = text.codePoints().toArray();
        for (int i = 0; i < chars.length; i++) {
            Glyph g = glyph(chars[i]);
            if (g != null) {
                glyphs.add(new Placed(g, g.width() + 1, i));
            }
        }
        int sum = 0;
        for (Placed p : glyphs) {
            sum += p.advance();
        }
        int w = Math.max(0, sum - 1);
        boolean[][] on = new boolean[FONT_HEIGHT][w];
        int[][] charAt = new int[FONT_HEIGHT][w];
        for (int[] row : charAt) {
            java.util.Arrays.fill(row, -1);
        }
        int x = 0;
        for (Placed p : glyphs) {
            boolean[][] rows = p.glyph().rows();
            for (int y = 0; y < rows.length && y < FONT_HEIGHT; y++) {
                for (int gx = 0; gx < rows[y].length; gx++) {
                    if (rows[y][gx]) {
                        on[y][x + gx] = true;
                        charAt[y][x + gx] = p.index();
                    }
                }
            }
            x += p.advance();
        }
        return new Mask(w, FONT_HEIGHT, FONT_HEIGHT, on, charAt);
    }

    // ------------------------------------------------------------- icons

    private static volatile Map<String, int[][]> icons;

    /** The icons a tag can carry, nine pixels square, read once from the jar. Transparent is -1. */
    static Map<String, int[][]> icons() {
        Map<String, int[][]> loaded = icons;
        if (loaded != null) {
            return loaded;
        }
        String digits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        Map<String, int[][]> read = new HashMap<>();
        for (String line : lines("/rank-tags/icons.txt")) {
            String[] parts = line.split(" ");
            if (parts.length != 3) {
                continue;
            }
            String[] palette = parts[1].split(",");
            String[] rows = parts[2].split("/", -1);
            int[][] grid = new int[rows.length][];
            for (int y = 0; y < rows.length; y++) {
                grid[y] = new int[rows[y].length()];
                for (int x = 0; x < rows[y].length(); x++) {
                    char c = rows[y].charAt(x);
                    grid[y][x] = c == '.' ? T : Integer.parseInt(palette[digits.indexOf(c)], 16);
                }
            }
            read.put(parts[0], grid);
        }
        icons = Map.copyOf(read);
        return icons;
    }

    /** The icons a hand-written tag may name. */
    public static boolean isIcon(String id) {
        return icons().containsKey(id);
    }

    private static List<String> lines(String resource) {
        try (InputStream in = RankTags.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource.substring(1) + " is missing from the jar");
            }
            List<String> out = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String trimmed = line.strip();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    out.add(trimmed);
                }
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------- shapes

    /** How many columns each end of a shape takes beyond a plain rectangle. */
    private static int[] shapeEnds(String shape, int h) {
        return switch (shape) {
            case "chamfer", "ribbon" -> new int[] {2, 2};
            case "arrow" -> new int[] {0, (int) Math.ceil((h - 1) / 2.0)};
            case "slant" -> new int[] {(h - 1) / 2, (h - 1) / 2};
            default -> new int[] {0, 0};
        };
    }

    private static boolean inside(String shape, int x, int y, int w, int h) {
        if (x < 0 || y < 0 || x >= w || y >= h) {
            return false;
        }
        int top = y;
        int bottom = h - 1 - y;
        int left = x;
        int right = w - 1 - x;
        double mid = (h - 1) / 2.0;
        switch (shape) {
            case "round":
                return !((top == 0 || bottom == 0) && (left == 0 || right == 0));
            case "pill": {
                int v = Math.min(top, bottom);
                int hz = Math.min(left, right);
                return !(v + hz < 2 || (v == 0 && hz < 2) || (hz == 0 && v < 2));
            }
            case "chamfer": {
                int c = Math.max(0, 2 - Math.min(top, bottom));
                return left >= c && right >= c;
            }
            case "arrow": {
                // Square left end with its corners off; the right end comes to a point.
                if (left == 0 && (top == 0 || bottom == 0)) {
                    return false;
                }
                return right >= Math.ceil(Math.abs(y - mid));
            }
            case "ribbon": {
                long notch = Math.max(0, 2 - Math.round(Math.abs(y - mid)));
                return left >= notch && right >= notch;
            }
            case "slant": {
                int s = (h - 1 - y) / 2;
                int max = (h - 1) / 2;
                return x >= s && x <= w - 1 - (max - s);
            }
            default:
                return true;
        }
    }

    // ------------------------------------------------------------- drawing

    private static final int NONE = 0;
    private static final int FACE = 1;
    private static final int BORDER = 2;
    private static final int RIM = 3;
    private static final int LIP = 4;
    private static final int CELL = 5;
    private static final int ICON = 6;
    private static final int OUTLINE = 7;
    private static final int SHADOW = 8;
    private static final int TEXT = 9;

    /** The provider ascent that centres a glyph this tall on a chat line, then moves it {@code offset} up. */
    static int ascent(int height, int offset) {
        return Math.max(-height, Math.min(height, (int) Math.round(height / 2.0 + 3.5) + offset));
    }

    /** The first frame of a tag. */
    public static Drawn render(Spec spec) {
        return render(spec, 0);
    }

    /**
     * One frame of a tag, at its scale. Pure: the same spec and frame are the
     * same pixels, here and in Studio.
     */
    public static Drawn render(Spec spec, int frame) {
        int[][] grid = kit(spec, frame);
        int h = grid.length;
        int w = h == 0 ? 0 : grid[0].length;
        int k = spec.scale();
        int[] argb = new int[w * k * h * k];
        for (int y = 0; y < h * k; y++) {
            for (int x = 0; x < w * k; x++) {
                int c = grid[y / k][x / k];
                argb[y * w * k + x] = c == T ? 0 : 0xff000000 | c;
            }
        }
        return new Drawn(w * k, h * k, ascent(h * k, spec.offset() * k), argb);
    }

    /** Every frame, stacked top to bottom: the sheet an animated tag's font cuts into one glyph per frame. */
    public static Drawn strip(Spec spec) {
        int frames = spec.frames();
        Drawn first = render(spec, 0);
        int[] argb = new int[first.width() * first.height() * frames];
        System.arraycopy(first.argb(), 0, argb, 0, first.argb().length);
        for (int frame = 1; frame < frames; frame++) {
            int[] next = render(spec, frame).argb();
            System.arraycopy(next, 0, argb, frame * next.length, next.length);
        }
        return new Drawn(first.width(), first.height() * frames, first.ascent(), argb);
    }

    /** The kit itself, at one pixel to a pixel: colours, {@code -1} where nothing is. */
    private static int[][] kit(Spec spec, int frame) {
        String shape = spec.shape();
        String face = spec.face();
        String effect = spec.effect();
        int frames = spec.animation().equals("none") ? 1 : FRAMES;
        double t = frame / (double) frames;
        Mask mask = textMask(spec.text());
        boolean outline = effect.equals("outline") || effect.equals("outline+shadow");
        Integer border = spec.noBorder() ? null : spec.border() != null ? spec.border() : shade(spec.fill(), 0.6);
        int b = (border != null ? 1 : 0) + (spec.rim() != null ? 1 : 0);
        int lip = spec.lip() ? 1 : 0;

        // Vertical: gap, cap height, gap, the same either side of the letters.
        int gap = 1 + (outline ? 1 : 0);
        int height = b + gap + mask.cap() + gap + b + lip;
        int textY = b + gap;

        // Icon sizing: a 9px icon where the inner face has room, else its 7px art.
        int[][] icon = spec.icon() != null ? icons().get(spec.icon()) : null;
        int innerH = height - 2 * b - lip;
        String iconStyle = spec.iconStyle() != null ? spec.iconStyle() : "cell";
        if (icon != null && !iconStyle.equals("tile") && innerH < 9) {
            int[][] cut = new int[7][];
            for (int y = 0; y < 7; y++) {
                cut[y] = java.util.Arrays.copyOfRange(icon[y + 1], 1, 8);
            }
            icon = cut;
        }
        int iconSide = icon != null ? icon.length : 0;
        int tileW = icon != null && iconStyle.equals("tile") ? height + 1 : 0;

        int[] ends = shapeEnds(shape, height - lip);
        int pad = spec.pad() != null ? spec.pad() : 2;
        int x0 = tileW + b + ends[0] + pad + (outline ? 1 : 0);
        int cellW = 0;
        if (icon != null && iconStyle.equals("cell")) {
            cellW = Math.max(iconSide, innerH) + 1;
            x0 = tileW + b + ends[0] + cellW + pad + (outline ? 1 : 0);
        } else if (icon != null && iconStyle.equals("inline")) {
            x0 += iconSide + 2;
        }
        int right = (outline ? 1 : 0) + (effect.equals("drop") ? 1 : 0) + pad + ends[1] + b;
        int width = x0 + mask.w() + right;
        int tagW = width - tileW;

        Canvas c = new Canvas(width, height);
        final int tile = tileW;
        final int lipRows = lip;
        java.util.function.BiPredicate<Integer, Integer> tagIn = (x, y) -> inside(shape, x - tile, y, tagW, height - lipRows);
        // With a lip the silhouette is the shape plus the same shape a row lower.
        java.util.function.BiPredicate<Integer, Integer> solid = (x, y) -> tagIn.test(x, y) || (lipRows > 0 && tagIn.test(x, y - 1));

        // Face colour per row, in bands.
        List<Integer> stops = new ArrayList<>();
        stops.add(spec.fill());
        if (spec.fill2() != null) {
            stops.add(spec.fill2());
        }
        if (spec.fill3() != null) {
            stops.add(spec.fill3());
        }
        int faceTop = b;
        int faceBottom = height - 1 - b - lip;
        java.util.function.IntUnaryOperator rowColor = y -> {
            if (stops.size() == 1) {
                return stops.get(0);
            }
            double u = Math.min(1, Math.max(0, (y - faceTop) / (double) Math.max(1, faceBottom - faceTop)));
            double seg = u * (stops.size() - 1);
            int i = (int) Math.min(stops.size() - 2, Math.floor(seg));
            return mix(stops.get(i), stops.get(i + 1), Math.round((seg - i) * 2) / 2.0);
        };
        String fillHex = String.format("#%06x", spec.fill());
        Random r = new Random((long) spec.text().length() * 7919 + fillHex.charAt(2));
        Set<Long> sparkles = new LinkedHashSet<>();
        for (int i = 0; i < Math.ceil(tagW / 6.0); i++) {
            int sx = tileW + (int) Math.floor(r.next() * tagW);
            int sy = (int) Math.floor(r.next() * height);
            sparkles.add(key(sx, sy));
        }

        boolean panel = face.equals("panel");
        for (int y = 0; y < height; y++) {
            for (int x = tileW; x < width; x++) {
                if (!solid.test(x, y)) {
                    continue;
                }
                int col = rowColor.applyAsInt(y);
                if (panel) {
                    col = mix(shade(col, 0.32), 0x101018, 0.25);
                }
                if (face.equals("gloss") && y < faceTop + Math.ceil((faceBottom - faceTop + 1) / 2.0)) {
                    col = shade(col, 1.14);
                }
                if (face.equals("gloss") && y == faceTop) {
                    col = shade(rowColor.applyAsInt(y), 1.4);
                }
                if (face.equals("stripes") && (x + y) % 6 < 2) {
                    col = shade(col, 1.12);
                }
                if (face.equals("dither") && y < (faceTop + faceBottom) / 2.0 && (x + y) % 2 == 0) {
                    col = shade(col, 1.1);
                }
                c.put(x, y, col, FACE);
            }
        }
        if (face.equals("sparkle")) {
            for (long k : sparkles) {
                int sx = (int) (k >> 32);
                int sy = (int) k;
                if (c.role(sx, sy) == FACE) {
                    c.put(sx, sy, shade(c.color(sx, sy), 1.55), FACE);
                }
            }
        }

        // Edges: border where the silhouette meets outside, rim one in, lip below.
        Set<Long> borderAt = new HashSet<>();
        for (int y = 0; y < height; y++) {
            for (int x = tileW; x < width; x++) {
                if (!solid.test(x, y)) {
                    continue;
                }
                boolean edge = !solid.test(x - 1, y) || !solid.test(x + 1, y) || !solid.test(x, y - 1) || !solid.test(x, y + 1);
                if (border != null && edge) {
                    c.put(x, y, border, BORDER);
                    borderAt.add(key(x, y));
                }
            }
        }
        int[][] four = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        if (spec.rim() != null) {
            for (int y = 0; y < height; y++) {
                for (int x = tileW; x < width; x++) {
                    if (c.role(x, y) != FACE) {
                        continue;
                    }
                    boolean near = false;
                    for (int[] d : four) {
                        if (borderAt.contains(key(x + d[0], y + d[1])) || !solid.test(x + d[0], y + d[1])) {
                            near = true;
                        }
                    }
                    if (near) {
                        c.put(x, y, spec.rim(), RIM);
                    }
                }
            }
        }
        if (lip > 0) {
            for (int x = tileW; x < width; x++) {
                for (int y = height - 1; y >= 0; y--) {
                    if (c.role(x, y) == BORDER && solid.test(x, y) && !solid.test(x, y + 1)) {
                        // The row above the bottom border becomes the lip: the face's darkest tone.
                        if (c.role(x, y - 1) == FACE || c.role(x, y - 1) == RIM) {
                            c.put(x, y - 1, shade(rowColor.applyAsInt(faceBottom), 0.66), LIP);
                        }
                        break;
                    }
                }
            }
        }

        // The tile: a separate square of the tag's height, same frame.
        if (icon != null && iconStyle.equals("tile")) {
            int s = height;
            int fillT = spec.iconFill() != null ? spec.iconFill() : shade(spec.fill(), 0.8);
            String tileShape = shape.equals("pill") ? "pill" : shape.equals("rect") ? "rect" : "round";
            for (int y = 0; y < s; y++) {
                for (int x = 0; x < s; x++) {
                    if (!inside(tileShape, x, y, s, s)) {
                        continue;
                    }
                    boolean e = !inside("round", x - 1, y, s, s) || !inside("round", x + 1, y, s, s)
                            || !inside("round", x, y - 1, s, s) || !inside("round", x, y + 1, s, s);
                    boolean edged = border != null && e;
                    c.put(x, y, edged ? border : fillT, edged ? BORDER : CELL);
                }
            }
            int off = Math.floorDiv(s - iconSide, 2);
            stamp(c, icon, off, off);
        }
        if (icon != null && iconStyle.equals("cell")) {
            int cx0 = tileW + b + ends[0];
            int fillC = spec.iconFill() != null ? spec.iconFill() : shade(rowColor.applyAsInt(faceTop), 0.78);
            for (int y = b; y <= faceBottom; y++) {
                for (int x = cx0; x < cx0 + cellW - 1; x++) {
                    if (c.role(x, y) == FACE || c.role(x, y) == RIM) {
                        c.put(x, y, fillC, CELL);
                    }
                }
            }
            for (int y = 0; y < height; y++) {
                if (solid.test(cx0 + cellW - 1, y) && border != null) {
                    c.put(cx0 + cellW - 1, y, border, BORDER);
                }
            }
            int ox = cx0 + Math.floorDiv(cellW - 1 - iconSide, 2);
            int oy = b + Math.floorDiv(faceBottom - b + 1 - iconSide, 2);
            stamp(c, icon, ox, oy);
        }
        if (icon != null && iconStyle.equals("inline")) {
            int ox = x0 - iconSide - 2 - (outline ? 1 : 0);
            int oy = b + Math.floorDiv(faceBottom - b + 1 - iconSide, 2);
            stamp(c, icon, ox, oy);
        }

        // Words: outline, shadow, ink.
        final int ox = x0;
        int fx = spec.effectColor() != null ? spec.effectColor() : shade(rowColor.applyAsInt(Math.floorDiv(faceTop + faceBottom, 2)), 0.55);
        java.util.function.BiPredicate<Integer, Integer> onFace = (x, y) -> {
            int role = c.role(x, y);
            return role == FACE || role == RIM || role == LIP;
        };
        if (outline) {
            for (int y = -1; y <= mask.h(); y++) {
                for (int x = -1; x <= mask.w(); x++) {
                    if (inked(mask, x, y)) {
                        continue;
                    }
                    boolean near = false;
                    for (int dy = -1; dy <= 1 && !near; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            if (inked(mask, x + dx, y + dy)) {
                                near = true;
                            }
                        }
                    }
                    if (near && onFace.test(ox + x, textY + y)) {
                        c.put(ox + x, textY + y, fx, OUTLINE);
                    }
                }
            }
        }
        if (effect.equals("shadow") || effect.equals("drop") || effect.equals("outline+shadow")) {
            List<int[]> casts = new ArrayList<>();
            if (effect.equals("outline+shadow")) {
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        if (c.role(x, y) == OUTLINE) {
                            casts.add(new int[] {x, y});
                        }
                    }
                }
            } else {
                for (int y = 0; y < mask.h(); y++) {
                    for (int x = 0; x < mask.w(); x++) {
                        if (mask.on()[y][x]) {
                            casts.add(new int[] {ox + x, textY + y});
                        }
                    }
                }
            }
            Set<Long> cast = new HashSet<>();
            for (int[] p : casts) {
                cast.add(key(p[0], p[1]));
            }
            int dx = effect.equals("drop") ? 1 : 0;
            int sc = effect.equals("outline+shadow")
                    ? shade(fx, 0.6)
                    : spec.effectColor() != null
                    ? spec.effectColor()
                    : effect.equals("drop") ? shade(rowColor.applyAsInt(textY + 2), 0.5) : shade(rowColor.applyAsInt(textY + 2), 0.8);
            for (int[] p : casts) {
                if (cast.contains(key(p[0] + dx, p[1] + 1)) || !onFace.test(p[0] + dx, p[1] + 1)) {
                    continue;
                }
                c.put(p[0] + dx, p[1] + 1, sc, SHADOW);
            }
        }
        int plus = spec.text().indexOf('+');
        for (int y = 0; y < mask.h(); y++) {
            for (int x = 0; x < mask.w(); x++) {
                if (!mask.on()[y][x]) {
                    continue;
                }
                int col = spec.ink();
                if (spec.ink2() != null) {
                    col = mix(spec.ink(), spec.ink2(),
                            Math.round((Math.min(y, mask.cap() - 1) / (double) Math.max(1, mask.cap() - 1)) * 2) / 2.0);
                }
                if (spec.accent() != null && plus >= 0 && mask.charAt()[y][x] >= plus) {
                    col = spec.accent();
                }
                if (spec.animation().equals("rainbow")) {
                    col = hueShift(col == WHITE ? 0xff5555 : col, t + (ox + x) / 40.0);
                }
                c.put(ox + x, textY + y, col, TEXT);
            }
        }

        // Animation: changes a frame's pixels, never its layout.
        if (spec.animation().equals("glint")) {
            int span = width + height + 8;
            long pos = (long) Math.floor(t * span) - 4;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    long d = x + (height - 1 - y) - pos;
                    int role = c.role(x, y);
                    boolean lit = role == FACE || role == TEXT || role == RIM || role == CELL || role == LIP;
                    if ((d == 0 || d == 1 || d == 3) && lit) {
                        c.put(x, y, mix(c.color(x, y), WHITE, d == 3 ? 0.3 : 0.55), role);
                    }
                }
            }
        }
        if (spec.animation().equals("twinkle")) {
            Random rr = new Random(frame * 31L + 7);
            for (int i = 0; i < Math.ceil(width / 10.0); i++) {
                int x = (int) Math.floor(rr.next() * width);
                int y = (int) Math.floor(rr.next() * height);
                if (c.role(x, y) != FACE) {
                    continue;
                }
                c.put(x, y, WHITE, FACE);
                for (int[] d : four) {
                    if (c.role(x + d[0], y + d[1]) == FACE) {
                        c.put(x + d[0], y + d[1], mix(c.color(x + d[0], y + d[1]), WHITE, 0.45), FACE);
                    }
                }
            }
        }
        if (spec.animation().equals("pulse") && border != null) {
            double k = 1 + 0.45 * (0.5 - 0.5 * Math.cos(t * Math.PI * 2));
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int role = c.role(x, y);
                    if (role == BORDER || role == RIM) {
                        c.put(x, y, shade(c.color(x, y), k), role);
                    }
                }
            }
        }
        return c.grid;
    }

    private static long key(int x, int y) {
        return ((long) x << 32) | (y & 0xffffffffL);
    }

    private static boolean inked(Mask mask, int x, int y) {
        return y >= 0 && y < mask.h() && x >= 0 && x < mask.w() && mask.on()[y][x];
    }

    private static void stamp(Canvas c, int[][] icon, int ox, int oy) {
        for (int y = 0; y < icon.length; y++) {
            for (int x = 0; x < icon[y].length; x++) {
                if (icon[y][x] != T) {
                    c.put(ox + x, oy + y, icon[y][x], ICON);
                }
            }
        }
    }

    /** A frame being drawn: a colour and what it is, per pixel. Writes outside it are dropped, as the kit's are. */
    private static final class Canvas {
        final int[][] grid;
        final int[][] roles;
        final int width;
        final int height;

        Canvas(int width, int height) {
            this.width = width;
            this.height = height;
            grid = new int[height][width];
            roles = new int[height][width];
            for (int[] row : grid) {
                java.util.Arrays.fill(row, T);
            }
        }

        void put(int x, int y, int color, int role) {
            if (x < 0 || y < 0 || x >= width || y >= height) {
                return;
            }
            grid[y][x] = color;
            roles[y][x] = role;
        }

        int role(int x, int y) {
            return x < 0 || y < 0 || x >= width || y >= height ? NONE : roles[y][x];
        }

        int color(int x, int y) {
            return grid[y][x];
        }
    }

    /** A drawn tag as a PNG. */
    public static byte[] png(Drawn drawn) {
        BufferedImage image = new BufferedImage(drawn.width(), drawn.height(), BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, drawn.width(), drawn.height(), drawn.argb(), 0, drawn.width());
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------- reading

    /**
     * A {@code tag:} block, as written in a content folder, into a spec. Every
     * field is bounded and every mistake is a warning with the field's default
     * in its place, so a typo costs a colour rather than the tag; only a tag
     * with no words is refused.
     */
    public static Optional<Spec> parse(DefinitionNode tag, String origin, String where, List<Diagnostic> diagnostics) {
        String text = normalizeText(tag.string("text").orElse(""));
        if (text.codePoints().noneMatch(RankTags::covers) || text.isBlank()) {
            diagnostics.add(Diagnostic.error(origin, where, "tag: needs text: — the words on the tag, like OWNER."));
            return Optional.empty();
        }
        String raw = tag.string("text").orElse("").trim();
        if (raw.length() > MAX_TEXT) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "tag.text is " + raw.length() + " characters; a tag holds at most " + MAX_TEXT + ", so it reads \"" + text + "\"."));
        }
        if (text.codePoints().anyMatch(cp -> cp != ' ' && !covers(cp))) {
            diagnostics.add(Diagnostic.warning(origin, where, "tag.text: the block capitals have no "
                    + uncovered(text) + ", which " + (uncovered(text).length() == 1 ? "is" : "are") + " left out."));
        }
        tag.string("font").map(f -> f.trim().toLowerCase(Locale.ROOT)).filter(f -> !f.equals("block")).ifPresent(f ->
                diagnostics.add(Diagnostic.warning(origin, where, "tag.font: " + f + " is a ResourcePack AI Studio font "
                        + "(the game's own or an uploaded one), which this engine cannot draw. Using block.")));
        Spec d = Spec.defaults(text);
        String shape = choice(tag, "shape", SHAPES, d.shape(), origin, where, diagnostics);
        String face;
        Optional<String> rawFace = tag.string("face").map(f -> f.trim().toLowerCase(Locale.ROOT));
        if (rawFace.isPresent() && rawFace.get().equals("texture")) {
            diagnostics.add(Diagnostic.warning(origin, where, "tag.face: texture is made of the game's block textures, "
                    + "which this engine does not have. Using flat."));
            face = "flat";
        } else {
            face = choice(tag, "face", FACES, d.face(), origin, where, diagnostics);
        }
        Integer border = null;
        boolean noBorder = false;
        Optional<String> rawBorder = tag.string("border");
        if (tag.bool("border").map(on -> !on).orElse(false)
                || rawBorder.map(v -> v.trim().equalsIgnoreCase("none")).orElse(false)) {
            noBorder = true;
        } else {
            border = colour(tag, "border", origin, where, diagnostics).orElse(null);
        }
        String icon = null;
        String iconStyle = null;
        Optional<String> rawIcon = tag.string("icon").map(String::trim);
        if (rawIcon.isPresent() && !rawIcon.get().isEmpty()) {
            if (isIcon(rawIcon.get())) {
                icon = rawIcon.get();
                iconStyle = choice(tag, "icon-style", ICON_STYLES, "cell", origin, where, diagnostics);
            } else {
                diagnostics.add(Diagnostic.warning(origin, where, "tag.icon: " + rawIcon.get()
                        + " is not one of the tag icons (the UI icon set's names, like crown, star or shield). Leaving it off."));
            }
        }
        Integer pad = tag.integer("padding").isPresent()
                ? number(tag, "padding", 0, MAX_PAD, 2, origin, where, diagnostics) : null;
        return Optional.of(new Spec(
                text,
                shape,
                face,
                colour(tag, "background", origin, where, diagnostics).orElse(d.fill()),
                colour(tag, "background-2", origin, where, diagnostics).orElse(null),
                colour(tag, "background-3", origin, where, diagnostics).orElse(null),
                border,
                noBorder,
                colour(tag, "rim", origin, where, diagnostics).orElse(null),
                tag.bool("lip").orElse(false),
                colour(tag, "text-color", origin, where, diagnostics).orElse(d.ink()),
                colour(tag, "text-fade", origin, where, diagnostics).orElse(null),
                colour(tag, "accent", origin, where, diagnostics).orElse(null),
                choice(tag, "effect", EFFECTS, d.effect(), origin, where, diagnostics),
                colour(tag, "effect-color", origin, where, diagnostics).orElse(null),
                icon,
                iconStyle,
                colour(tag, "icon-fill", origin, where, diagnostics).orElse(null),
                pad,
                choice(tag, "animation", ANIMATIONS, d.animation(), origin, where, diagnostics),
                number(tag, "scale", 1, MAX_SCALE, d.scale(), origin, where, diagnostics),
                number(tag, "offset", -MAX_OFFSET, MAX_OFFSET, d.offset(), origin, where, diagnostics)));
    }

    private static String uncovered(String text) {
        StringBuilder out = new StringBuilder();
        text.codePoints().filter(cp -> cp != ' ' && !covers(cp)).distinct().forEach(out::appendCodePoint);
        return out.toString();
    }

    private static String choice(DefinitionNode tag, String key, List<String> options, String fallback, String origin,
                                 String where, List<Diagnostic> diagnostics) {
        Optional<String> raw = tag.string(key).map(v -> v.trim().toLowerCase(Locale.ROOT));
        if (raw.isEmpty()) {
            return fallback;
        }
        if (options.contains(raw.get())) {
            return raw.get();
        }
        diagnostics.add(Diagnostic.warning(origin, where, "tag." + key + ": " + raw.get() + " is not one of "
                + String.join(", ", options) + ". Using " + fallback + "."));
        return fallback;
    }

    private static Optional<Integer> colour(DefinitionNode tag, String key, String origin, String where,
                                            List<Diagnostic> diagnostics) {
        Optional<String> raw = tag.string(key);
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        Optional<Integer> parsed = colour(raw.get());
        if (parsed.isEmpty()) {
            diagnostics.add(Diagnostic.warning(origin, where, "tag." + key + ": " + raw.get()
                    + " is not a colour. Write it as \"#e53935\" or a name like red or gold."));
        }
        return parsed;
    }

    private static int number(DefinitionNode tag, String key, int min, int max, int fallback, String origin,
                              String where, List<Diagnostic> diagnostics) {
        Optional<Integer> raw = tag.integer(key);
        if (raw.isEmpty()) {
            return fallback;
        }
        int v = raw.get();
        if (v < min || v > max) {
            int clamped = Math.max(min, Math.min(max, v));
            diagnostics.add(Diagnostic.warning(origin, where,
                    "tag." + key + ": " + v + " is outside " + min + " to " + max + ". Using " + clamped + "."));
            return clamped;
        }
        return v;
    }
}
