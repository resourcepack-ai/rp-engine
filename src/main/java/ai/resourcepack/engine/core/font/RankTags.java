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
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Rank tags: an icon whose picture is drawn from words and a background
 * instead of read from a PNG — the rounded red OWNER a server puts in front of
 * a player's name, as a LuckPerms prefix or suffix.
 *
 * <pre>
 * owner:
 *   tag:
 *     text: OWNER
 *     background: "#e53935"
 * </pre>
 *
 * <p>Everything after that is an icon like any other: one codepoint, one
 * bitmap provider, {@code %rpengine_icon_mypack:owner%}, {@code :owner:} in
 * chat. This class only decides the pixels, and the build writes them into the
 * bundle where the provider points (see {@link FontAssets}).
 *
 * <p><strong>The drawing is ResourcePack AI Studio's, pixel for pixel</strong>,
 * so a tag designed there and the same tag written here come out identical.
 * Every rule below is that renderer's, in its order, and the test beside this
 * class draws the fixtures Studio wrote and compares every pixel. Change one
 * end and the test fails until the other agrees. The faces are the two Studio
 * also ships in this form, read from {@code rank-tags/faces.txt}; Studio's
 * other faces (its small capitals, the game's own font, uploaded fonts) are not
 * ours to put in a plugin, and a tag set in one of those arrives from Studio
 * already drawn.
 */
public final class RankTags {

    private RankTags() {
    }

    /** What a tag reads at most, in characters. */
    public static final int MAX_TEXT = 24;
    public static final int MAX_PAD_X = 12;
    public static final int MAX_PAD_Y = 6;
    public static final int MAX_SPACING = 3;
    public static final int MAX_BORDER = 2;
    public static final int MAX_SCALE = 4;
    public static final int MAX_OFFSET = 12;
    /** Any roundness this big or bigger is a pill. */
    public static final int RADIUS_PILL = 6;
    /** A font glyph can be no bigger than this either way. */
    public static final int MAX_EDGE = 256;

    /**
     * A tag, every field resolved. Colours are {@code 0xRRGGBB}; a null
     * optional colour is the tag's own, worked out from the background.
     */
    public record Spec(String text, String font, int textColor, Integer textColor2, boolean bold, boolean shadow,
                       Integer shadowColor, int spacing, int fill, Integer fill2, String style, int border,
                       Integer borderColor, int radius, int padX, int padY, Integer width, int scale, int offset) {

        /** The classic tag: rounded, glossy, outlined, white words with a shadow. */
        public static Spec defaults(String text) {
            return new Spec(text, "classic", 0xffffff, null, false, true, null, 0, 0xe53935, null, "glossy", 1,
                    null, 2, 3, 1, null, 1, 0);
        }
    }

    /** A drawn tag: ARGB, row by row, at its scale — what the PNG holds. */
    public record Drawn(int width, int height, int ascent, int[] argb) {

        public boolean fits() {
            return width <= MAX_EDGE && height <= MAX_EDGE;
        }

        public int at(int x, int y) {
            return argb[y * width + x];
        }
    }

    // ------------------------------------------------------------- colours

    /** The game's sixteen chat colours, by their code and by their name. */
    private static final Map<String, Integer> LEGACY = new HashMap<>();
    private static final Map<String, Integer> NAMED = new HashMap<>();

    static {
        String[][] colours = {
            {"0", "black", "000000"}, {"1", "dark_blue", "0000aa"}, {"2", "dark_green", "00aa00"},
            {"3", "dark_aqua", "00aaaa"}, {"4", "dark_red", "aa0000"}, {"5", "dark_purple", "aa00aa"},
            {"6", "gold", "ffaa00"}, {"7", "gray", "aaaaaa"}, {"8", "dark_gray", "555555"},
            {"9", "blue", "5555ff"}, {"a", "green", "55ff55"}, {"b", "aqua", "55ffff"},
            {"c", "red", "ff5555"}, {"d", "light_purple", "ff55ff"}, {"e", "yellow", "ffff55"},
            {"f", "white", "ffffff"},
        };
        for (String[] c : colours) {
            int rgb = Integer.parseInt(c[2], 16);
            LEGACY.put(c[0], rgb);
            NAMED.put(c[1], rgb);
        }
    }

    static int mix(int a, int b, double t) {
        int r = channel((a >> 16) & 0xff, (b >> 16) & 0xff, t);
        int g = channel((a >> 8) & 0xff, (b >> 8) & 0xff, t);
        int bl = channel(a & 0xff, b & 0xff, t);
        return (r << 16) | (g << 8) | bl;
    }

    private static int channel(int a, int b, double t) {
        return (int) Math.max(0, Math.min(255, Math.round(a + (b - a) * t)));
    }

    static int lighten(int c, double t) {
        return mix(c, 0xffffff, t);
    }

    static int darken(int c, double t) {
        return mix(c, 0x000000, t);
    }

    static int borderColor(Spec spec) {
        return spec.borderColor() != null ? spec.borderColor() : darken(spec.fill(), 0.4);
    }

    static int fill2(Spec spec) {
        return spec.fill2() != null ? spec.fill2() : darken(spec.fill(), 0.3);
    }

    static int shadowColor(Spec spec) {
        return spec.shadowColor() != null ? spec.shadowColor() : darken(spec.fill(), 0.55);
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

    // ------------------------------------------------------------- the words

    /** A run of the words in one colour and weight; a null colour is the tag's own. */
    record Run(String text, Integer color, boolean bold) {
    }

    /**
     * The words as runs, {@code &} codes taken out: {@code &c} and the other
     * fifteen, {@code &#ff8800}, {@code &l} bold, {@code &r} back to the tag's
     * own colour. A code the game does not have is not a code, so the
     * {@code &} stays as a character.
     */
    static List<Run> runs(String text, boolean baseBold) {
        List<Run> runs = new ArrayList<>();
        int[] chars = text.codePoints().toArray();
        StringBuilder buf = new StringBuilder();
        Integer color = null;
        boolean bold = baseBold;
        for (int i = 0; i < chars.length; i++) {
            int ch = chars[i];
            if (ch == '&' && i + 1 < chars.length) {
                String code = new String(Character.toChars(chars[i + 1])).toLowerCase(Locale.ROOT);
                if (code.equals("#")) {
                    StringBuilder hex = new StringBuilder();
                    for (int j = i + 2; j < Math.min(chars.length, i + 8); j++) {
                        hex.appendCodePoint(chars[j]);
                    }
                    if (hex.toString().matches("[0-9a-fA-F]{6}")) {
                        flush(runs, buf, color, bold);
                        color = Integer.parseInt(hex.toString(), 16);
                        i += 7;
                        continue;
                    }
                } else if (LEGACY.containsKey(code)) {
                    flush(runs, buf, color, bold);
                    color = LEGACY.get(code);
                    // A colour code ends bold in the game too.
                    bold = baseBold;
                    i += 1;
                    continue;
                } else if (code.equals("l")) {
                    flush(runs, buf, color, bold);
                    bold = true;
                    i += 1;
                    continue;
                } else if (code.equals("r")) {
                    flush(runs, buf, color, bold);
                    color = null;
                    bold = baseBold;
                    i += 1;
                    continue;
                } else if ("kmno".contains(code)) {
                    flush(runs, buf, color, bold);
                    i += 1;
                    continue;
                }
            }
            buf.appendCodePoint(ch);
        }
        flush(runs, buf, color, bold);
        return runs;
    }

    private static void flush(List<Run> runs, StringBuilder buf, Integer color, boolean bold) {
        if (buf.length() > 0) {
            runs.add(new Run(buf.toString(), color, bold));
            buf.setLength(0);
        }
    }

    /** The words as they will read, codes taken out. */
    static String plain(String text) {
        StringBuilder out = new StringBuilder();
        for (Run run : runs(text, false)) {
            out.append(run.text());
        }
        return out.toString();
    }

    // ------------------------------------------------------------- faces

    /** One glyph of a face: as wide as its rows, eight rows tall, from the top of the line. */
    record Glyph(int width, int height, int advance, boolean[] ink) {
    }

    private static final int TRACKING = 1;
    private static final int CAP_TOP = 2;
    private static final int BASELINE = 7;
    private static volatile Map<String, Map<Integer, String>> faces;

    /** The faces' tables, read once from the jar. */
    static Map<String, Map<Integer, String>> faces() {
        Map<String, Map<Integer, String>> loaded = faces;
        if (loaded != null) {
            return loaded;
        }
        Map<String, Map<Integer, String>> read = new HashMap<>();
        try (InputStream in = RankTags.class.getResourceAsStream("/rank-tags/faces.txt")) {
            if (in == null) {
                throw new IllegalStateException("rank-tags/faces.txt is missing from the jar");
            }
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.trim().split(" ");
                if (parts.length != 3) {
                    continue;
                }
                read.computeIfAbsent(parts[0], f -> new HashMap<>()).put(Integer.parseInt(parts[1], 16), parts[2]);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        faces = Map.copyOf(read);
        return faces;
    }

    /** The faces a hand-written tag may name. */
    public static boolean isFace(String font) {
        return faces().containsKey(font);
    }

    static Glyph glyph(String font, int codepoint) {
        Map<Integer, String> table = faces().getOrDefault(font, faces().get("classic"));
        String packed = table.get(codepoint);
        if (packed == null) {
            packed = table.getOrDefault((int) '?', "?");
        }
        String[] rows = packed.split("/", -1);
        int w = rows[0].length();
        // A space is stored as its width and nothing else.
        int h = rows.length == 1 ? 0 : rows.length;
        boolean[] ink = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w && x < rows[y].length(); x++) {
                ink[y * w + x] = rows[y].charAt(x) == '#';
            }
        }
        return new Glyph(h == 0 ? 0 : w, h, w + TRACKING, ink);
    }

    // ------------------------------------------------------------- drawing

    private static final int[][] CORNER_CUTS = {{}, {1}, {2, 1}, {3, 1, 1}, {4, 2, 1, 1}, {5, 3, 2, 1, 1}};

    /** How far each row from the top (and, mirrored, the bottom) is cut in at each end. */
    static int[] cornerCuts(int radius, int width, int height) {
        int most = Math.min(width, height) / 2;
        int r = Math.max(0, Math.min(radius >= RADIUS_PILL ? most : radius, most));
        if (r >= 3 && (r == most || r >= CORNER_CUTS.length)) {
            return circleCuts(r);
        }
        return CORNER_CUTS[r].clone();
    }

    private static int[] circleCuts(int r) {
        int[] cuts = new int[r];
        double limit = (r - 0.3) * (r - 0.3);
        for (int y = 0; y < r; y++) {
            int c = 0;
            while (c < r && (r - c - 0.5) * (r - c - 0.5) + (r - y - 0.5) * (r - y - 0.5) > limit) {
                c++;
            }
            cuts[y] = c;
        }
        return cuts;
    }

    /** The provider ascent that centres a glyph this tall on a chat line, then moves it {@code offset} up. */
    static int ascent(int height, int offset) {
        return Math.max(-height, Math.min(height, (int) Math.round(height / 2.0 + 3.5) + offset));
    }

    private record Ink(int x, int y, Integer color) {
    }

    /** Draws a tag. Pure: the same spec is the same pixels, here and in Studio. */
    public static Drawn render(Spec spec) {
        // 1. The words, laid out from the pen at the top of the line.
        List<Ink> ink = new ArrayList<>();
        int pen = 0;
        for (Run run : runs(spec.text(), spec.bold())) {
            String text = Normalizer.normalize(run.text(), Normalizer.Form.NFC);
            for (int cp : text.codePoints().toArray()) {
                Glyph g = glyph(spec.font(), cp);
                int passes = run.bold() ? 2 : 1;
                for (int offset = 0; offset < passes; offset++) {
                    for (int gy = 0; gy < g.height(); gy++) {
                        for (int gx = 0; gx < g.width(); gx++) {
                            if (g.ink()[gy * g.width() + gx]) {
                                ink.add(new Ink(pen + gx + offset, gy, run.color()));
                            }
                        }
                    }
                }
                pen += g.advance() + (run.bold() ? 1 : 0) + spec.spacing();
            }
        }

        int left = 0;
        int right = -1;
        int top = CAP_TOP;
        int bottom = BASELINE - 1;
        if (!ink.isEmpty()) {
            left = Integer.MAX_VALUE;
            right = Integer.MIN_VALUE;
            for (Ink p : ink) {
                left = Math.min(left, p.x());
                right = Math.max(right, p.x());
                top = Math.min(top, p.y());
                bottom = Math.max(bottom, p.y());
            }
        }
        int wordsW = right - left + 1;
        int wordsH = bottom - top + 1;

        // 2. The background.
        int b = spec.border();
        int natural = 2 * b + 2 * spec.padX() + Math.max(0, wordsW);
        int width = Math.max(1, Math.max(natural, spec.width() == null ? 0 : spec.width()));
        int height = Math.max(1, 2 * b + 2 * spec.padY() + wordsH);

        // 3. The shape.
        int[] cuts = cornerCuts(spec.radius(), width, height);
        boolean[] inside = new boolean[width * height];
        for (int y = 0; y < height; y++) {
            int fromEdge = Math.min(y, height - 1 - y);
            int cut = fromEdge < cuts.length ? cuts[fromEdge] : 0;
            for (int x = cut; x < width - cut; x++) {
                inside[y * width + x] = true;
            }
        }

        // 4. The outline: distance from the outside in four-neighbour steps.
        int[] depth = new int[width * height];
        java.util.Arrays.fill(depth, -1);
        ArrayDeque<Integer> frontier = new ArrayDeque<>();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (!in(inside, width, height, x, y)) {
                    continue;
                }
                if (!in(inside, width, height, x - 1, y) || !in(inside, width, height, x + 1, y)
                        || !in(inside, width, height, x, y - 1) || !in(inside, width, height, x, y + 1)) {
                    depth[y * width + x] = 1;
                    frontier.add(y * width + x);
                }
            }
        }
        for (int d = 2; d <= b && !frontier.isEmpty(); d++) {
            ArrayDeque<Integer> next = new ArrayDeque<>();
            for (int i : frontier) {
                int x = i % width;
                int y = i / width;
                int[][] around = {{x - 1, y}, {x + 1, y}, {x, y - 1}, {x, y + 1}};
                for (int[] n : around) {
                    if (!in(inside, width, height, n[0], n[1])) {
                        continue;
                    }
                    int j = n[1] * width + n[0];
                    if (depth[j] != -1) {
                        continue;
                    }
                    depth[j] = d;
                    next.add(j);
                }
            }
            frontier = next;
        }

        // 5. The inside, shaded by row.
        int innerH = Math.max(1, height - 2 * b);
        int bottomColor = fill2(spec);
        int outline = borderColor(spec);
        int[] rgb = new int[width * height];
        boolean[] opaque = new boolean[width * height];
        for (int y = 0; y < height; y++) {
            int row = rowColor(spec, y - b, innerH, bottomColor);
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                if (!inside[i]) {
                    continue;
                }
                opaque[i] = true;
                rgb[i] = border(depth, i, b) ? outline : row;
            }
        }

        // 6. The words, and their shadow under them.
        int ox = b + spec.padX() + (int) Math.floor((width - natural) / 2.0) - left;
        int oy = b + spec.padY() - top;
        if (spec.shadow()) {
            int shadow = shadowColor(spec);
            for (Ink p : ink) {
                int x = p.x() + ox + 1;
                int y = p.y() + oy + 1;
                if (in(inside, width, height, x, y) && !border(depth, y * width + x, b)) {
                    rgb[y * width + x] = shadow;
                }
            }
        }
        for (Ink p : ink) {
            int x = p.x() + ox;
            int y = p.y() + oy;
            if (x >= 0 && y >= 0 && x < width && y < height) {
                int i = y * width + x;
                rgb[i] = colorAt(spec, p, left, wordsW);
                opaque[i] = true;
            }
        }

        int k = spec.scale();
        int[] argb = new int[width * k * height * k];
        for (int y = 0; y < height * k; y++) {
            for (int x = 0; x < width * k; x++) {
                int i = (y / k) * width + (x / k);
                argb[y * width * k + x] = opaque[i] ? 0xff000000 | rgb[i] : 0;
            }
        }
        return new Drawn(width * k, height * k, ascent(height * k, spec.offset() * k), argb);
    }

    private static boolean in(boolean[] inside, int width, int height, int x, int y) {
        return x >= 0 && y >= 0 && x < width && y < height && inside[y * width + x];
    }

    private static boolean border(int[] depth, int i, int b) {
        return b > 0 && depth[i] != -1 && depth[i] <= b;
    }

    private static int rowColor(Spec spec, int k, int innerH, int bottomColor) {
        if (spec.style().equals("gradient")) {
            return innerH <= 1 ? spec.fill() : mix(spec.fill(), bottomColor, k / (double) (innerH - 1));
        }
        if (spec.style().equals("glossy")) {
            if (innerH >= 3 && k == innerH - 1) {
                return darken(spec.fill(), 0.15);
            }
            if (k <= 0) {
                return lighten(spec.fill(), 0.35);
            }
            if (k < Math.ceil(innerH / 2.0)) {
                return lighten(spec.fill(), 0.15);
            }
        }
        return spec.fill();
    }

    private static int colorAt(Spec spec, Ink p, int left, int wordsW) {
        if (p.color() != null) {
            return p.color();
        }
        if (spec.textColor2() == null || wordsW <= 1) {
            return spec.textColor();
        }
        return mix(spec.textColor(), spec.textColor2(), (p.x() - left) / (double) (wordsW - 1));
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
        String text = tag.string("text").orElse("");
        String plain = plain(text);
        if (plain.isBlank()) {
            diagnostics.add(Diagnostic.error(origin, where, "tag: needs text: — the words on the tag, like OWNER."));
            return Optional.empty();
        }
        if (plain.codePointCount(0, plain.length()) > MAX_TEXT) {
            diagnostics.add(Diagnostic.error(origin, where,
                    "tag.text reads " + plain.length() + " characters; a tag holds at most " + MAX_TEXT + "."));
            return Optional.empty();
        }
        Spec d = Spec.defaults(text);
        String font = tag.string("font").map(f -> f.trim().toLowerCase(Locale.ROOT)).orElse(d.font());
        if (!isFace(font)) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "tag.font: " + font + " is not a face this engine has (classic or tiny). Using classic."));
            font = "classic";
        }
        String style = tag.string("shading").map(s -> s.trim().toLowerCase(Locale.ROOT)).orElse(d.style());
        if (!style.equals("glossy") && !style.equals("gradient") && !style.equals("flat")) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "tag.shading: " + style + " is not glossy, gradient or flat. Using glossy."));
            style = "glossy";
        }
        int radius;
        Optional<String> roundness = tag.string("roundness");
        if (roundness.isPresent() && roundness.get().trim().equalsIgnoreCase("pill")) {
            radius = RADIUS_PILL;
        } else {
            radius = number(tag, "roundness", 0, RADIUS_PILL, d.radius(), origin, where, diagnostics);
        }
        Integer width = tag.integer("width").isPresent()
                ? number(tag, "width", 1, MAX_EDGE, 1, origin, where, diagnostics) : null;
        boolean shadow = tag.bool("shadow").orElse(d.shadow());
        return Optional.of(new Spec(
                text,
                font,
                colour(tag, "text-color", origin, where, diagnostics).orElse(d.textColor()),
                colour(tag, "text-fade", origin, where, diagnostics).orElse(null),
                tag.bool("bold").orElse(d.bold()),
                shadow,
                colour(tag, "shadow-color", origin, where, diagnostics).orElse(null),
                number(tag, "spacing", 0, MAX_SPACING, d.spacing(), origin, where, diagnostics),
                colour(tag, "background", origin, where, diagnostics).orElse(d.fill()),
                colour(tag, "background-fade", origin, where, diagnostics).orElse(null),
                style,
                number(tag, "outline", 0, MAX_BORDER, d.border(), origin, where, diagnostics),
                colour(tag, "outline-color", origin, where, diagnostics).orElse(null),
                radius,
                number(tag, "padding-x", 0, MAX_PAD_X, d.padX(), origin, where, diagnostics),
                number(tag, "padding-y", 0, MAX_PAD_Y, d.padY(), origin, where, diagnostics),
                width,
                number(tag, "scale", 1, MAX_SCALE, d.scale(), origin, where, diagnostics),
                number(tag, "offset", -MAX_OFFSET, MAX_OFFSET, d.offset(), origin, where, diagnostics)));
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
