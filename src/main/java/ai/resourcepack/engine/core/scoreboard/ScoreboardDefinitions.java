package ai.resourcepack.engine.core.scoreboard;

import ai.resourcepack.engine.api.ContentDefinition;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.api.ScoreboardInfo;
import ai.resourcepack.engine.core.font.GlyphAllocator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Reads scoreboard definitions out of a load report.
 *
 * <pre>
 * # scoreboards/main.yml
 * main:
 *   title: "&amp;b&amp;lMY SERVER"
 *   lines:
 *     - "&amp;7Rank  %rpengine_tag%"
 *     - ""
 *     - "&amp;fCoins  &amp;e{coins}"
 *     - { text: "&amp;7play.example.com", align: center }
 *   background:               # leave out for the game's own grey box
 *     color: "#10121a"
 *     opacity: 78
 *     radius: 3
 *     padding: 5              # or [top, right, bottom, left]
 *     border: "#3b82f6"
 *     header: "#1b2233"       # a band behind the title
 *     stripe: "#3b82f6"       # a stripe down the left edge
 *     texture: board          # or your own picture, textures/gui/board.png, cut into nine
 *     slice: 6
 *   update: 20                # ticks between fills
 *   worlds: [lobby]
 *   permission: myserver.board
 *   priority: 0
 * </pre>
 *
 * <p>Free of Bukkit, like every definition reader here.
 */
public final class ScoreboardDefinitions {

    /** All the game draws. */
    public static final int MAX_LINES = 15;

    private ScoreboardDefinitions() {
    }

    /** One scoreboard, with what the engine needs beyond what {@link ScoreboardInfo} tells a plugin. */
    public record Board(ScoreboardInfo info, ScoreboardPanel panel, int codepoint,
                        List<ScoreboardLayout.Align> aligns, int minWidth) {
    }

    /** Everything of kind SCOREBOARD in {@code loaded}, parsed. */
    public static Result parse(LoadReport loaded) {
        Map<ContentId, Board> boards = new LinkedHashMap<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (loaded == null) {
            return new Result(Map.of(), List.of());
        }
        Map<ContentId, Integer> codepoints = GlyphAllocator.allocate(loaded);
        for (ContentDefinition definition : loaded.definitions(ContentKind.SCOREBOARD)) {
            DefinitionNode body = definition.body();
            String origin = definition.origin();
            String where = definition.id().path();

            List<String> lines = new ArrayList<>();
            List<ScoreboardLayout.Align> aligns = new ArrayList<>();
            readLines(body.raw("lines"), lines, aligns, origin, where, diagnostics);
            if (lines.isEmpty()) {
                diagnostics.add(Diagnostic.error(origin, where, "lines: has nothing in it, so there is no scoreboard to show."));
                continue;
            }
            if (lines.size() > MAX_LINES) {
                diagnostics.add(Diagnostic.warning(origin, where, "lines: has " + lines.size()
                        + ", and the game draws " + MAX_LINES + ". The rest are left off."));
                lines = new ArrayList<>(lines.subList(0, MAX_LINES));
                aligns = new ArrayList<>(aligns.subList(0, MAX_LINES));
            }

            ScoreboardPanel panel = panel(body, lines.size(), origin, where, diagnostics);
            Integer codepoint = codepoints.get(definition.id());
            if (panel != null && codepoint == null) {
                diagnostics.add(Diagnostic.error(origin, where, "There is no room left in the Private Use Area "
                        + "for its background, across icons, screens, HUDs and scoreboards together. It is shown without one."));
                panel = null;
            }

            int update = body.integer("update").orElse(20);
            if (update < 1 || update > 1200) {
                diagnostics.add(Diagnostic.warning(origin, where, "update: " + update
                        + " is outside 1 to 1200 ticks. Using 20."));
                update = 20;
            }
            ScoreboardInfo info = ScoreboardInfo.of(definition.id(), body.string("title").orElse(""), lines,
                    panel != null, update, body.bool("auto").orElse(Boolean.TRUE), body.strings("worlds"),
                    body.string("permission").orElse(null), body.integer("priority").orElse(0));
            boards.put(definition.id(), new Board(info, panel, codepoint == null ? 0 : codepoint,
                    List.copyOf(aligns), Math.max(0, Math.min(400, body.integer("width").orElse(0)))));
        }
        return new Result(Map.copyOf(boards), List.copyOf(diagnostics));
    }

    private static void readLines(Object raw, List<String> lines, List<ScoreboardLayout.Align> aligns,
                                  String origin, String where, List<Diagnostic> diagnostics) {
        if (raw == null) {
            return;
        }
        List<?> entries = raw instanceof List<?> list ? list : List.of(raw);
        for (Object entry : entries) {
            if (entry instanceof Map<?, ?> map) {
                DefinitionNode node = DefinitionNode.of(map);
                lines.add(node.string("text").orElse(""));
                aligns.add(align(node.string("align").orElse("left"), origin, where, diagnostics));
            } else if (entry instanceof List) {
                diagnostics.add(Diagnostic.warning(origin, where, "A line is text, or text: and align:. A list was skipped."));
            } else {
                lines.add(entry == null ? "" : entry.toString());
                aligns.add(ScoreboardLayout.Align.LEFT);
            }
        }
    }

    private static ScoreboardLayout.Align align(String written, String origin, String where, List<Diagnostic> diagnostics) {
        switch (written.trim().toLowerCase(Locale.ROOT)) {
            case "left":
                return ScoreboardLayout.Align.LEFT;
            case "center":
            case "centre":
            case "middle":
                return ScoreboardLayout.Align.CENTER;
            case "right":
                return ScoreboardLayout.Align.RIGHT;
            default:
                diagnostics.add(Diagnostic.warning(origin, where, "align: " + written
                        + " is not left, center or right. Using left."));
                return ScoreboardLayout.Align.LEFT;
        }
    }

    /** The panel a definition asks for, or null when it keeps the game's own box. */
    private static ScoreboardPanel panel(DefinitionNode body, int lines, String origin, String where,
                                         List<Diagnostic> diagnostics) {
        Object raw = body.raw("background");
        if (raw == null || Boolean.FALSE.equals(raw) || "none".equals(raw) || "false".equals(raw)) {
            return null;
        }
        ScoreboardPanel.Builder builder = ScoreboardPanel.builder().lines(lines);
        // background: "#10121a" is the colour and nothing else.
        DefinitionNode node = raw instanceof Map || Boolean.TRUE.equals(raw) || "true".equals(raw)
                ? body.node("background").orElse(DefinitionNode.empty())
                : DefinitionNode.of(Map.of("color", raw.toString()));

        Optional<String> colour = node.string("color").or(() -> node.string("colour"));
        int opacity = percent(node, "opacity", 78, origin, where, diagnostics);
        builder.fill(argb(colour.orElse("#10121a"), opacity, "color", origin, where, diagnostics));
        builder.radius(clamp(node.integer("radius").orElse(3), 0, 16));

        Optional<String> border = node.string("border");
        if (border.isPresent()) {
            builder.border(argb(border.get(), percent(node, "border-opacity", 100, origin, where, diagnostics),
                    "border", origin, where, diagnostics), clamp(node.integer("border-width").orElse(1), 1, 8));
        }
        Optional<String> header = node.string("header");
        if (header.isPresent()) {
            builder.header(argb(header.get(), percent(node, "header-opacity", Math.max(opacity, 90), origin, where,
                    diagnostics), "header", origin, where, diagnostics));
        }
        Optional<String> stripe = node.string("stripe");
        if (stripe.isPresent()) {
            builder.stripe(argb(stripe.get(), percent(node, "stripe-opacity", 100, origin, where, diagnostics),
                    "stripe", origin, where, diagnostics), clamp(node.integer("stripe-width").orElse(2), 1, 16));
        }

        int[] padding = sides(node, "padding", 5, 0, 32);
        builder.padding(padding[0], padding[1], padding[2], padding[3]);

        Optional<String> texture = node.string("texture");
        if (texture.isPresent()) {
            String file = texture.get().trim();
            if (file.toLowerCase(Locale.ROOT).endsWith(".png")) {
                file = file.substring(0, file.length() - 4);
            }
            int[] slice = sides(node, "slice", 4, 0, 64);
            builder.texture(file, slice[0], slice[1], slice[2], slice[3]);
        }

        ScoreboardPanel panel = builder.build();
        if (panel.height() > 256) {
            diagnostics.add(Diagnostic.error(origin, where, "Its background would be " + panel.height()
                    + " pixels tall, and a font glyph can be 256 at most. Use less padding. It is shown without one."));
            return null;
        }
        return panel;
    }

    /** One number for every side, two for top-and-bottom then sides, or four: top, right, bottom, left. */
    private static int[] sides(DefinitionNode node, String key, int fallback, int min, int max) {
        List<String> written = node.strings(key);
        int[] values = new int[written.size()];
        int read = 0;
        for (String value : written) {
            try {
                values[read++] = clamp((int) Math.round(Double.parseDouble(value.trim())), min, max);
            } catch (NumberFormatException e) {
                // Left out; the count below decides what the rest mean.
            }
        }
        if (read == 1) {
            return new int[] {values[0], values[0], values[0], values[0]};
        }
        if (read == 2) {
            return new int[] {values[0], values[1], values[0], values[1]};
        }
        if (read >= 4) {
            return new int[] {values[0], values[1], values[2], values[3]};
        }
        return new int[] {fallback, fallback, fallback, fallback};
    }

    private static int percent(DefinitionNode node, String key, int fallback, String origin, String where,
                               List<Diagnostic> diagnostics) {
        Optional<Double> written = node.decimal(key);
        if (written.isEmpty()) {
            return fallback;
        }
        double value = written.get();
        if (!Double.isFinite(value) || value < 0 || value > 100) {
            diagnostics.add(Diagnostic.warning(origin, where, key + ": " + written.get()
                    + " is not a percentage from 0 to 100. Using " + fallback + "."));
            return fallback;
        }
        return (int) Math.round(value);
    }

    /** {@code #rrggbb} at {@code opacity} percent, or {@code #rrggbbaa} with its own alpha. */
    static int argb(String written, int opacity, String key, String origin, String where, List<Diagnostic> diagnostics) {
        String hex = written.trim();
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }
        try {
            if (hex.length() == 6) {
                return ((int) Math.round(opacity * 2.55) << 24) | Integer.parseInt(hex, 16);
            }
            if (hex.length() == 8) {
                long rgba = Long.parseLong(hex, 16);
                return (int) ((rgba & 0xFF) << 24 | (rgba >>> 8));
            }
        } catch (NumberFormatException e) {
            // Falls through to the warning.
        }
        diagnostics.add(Diagnostic.warning(origin, where, key + ": " + written
                + " is not a colour like #10121a. Using black."));
        return (int) Math.round(opacity * 2.55) << 24;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** The scoreboards, and what was wrong. */
    public static final class Result {

        private final Map<ContentId, Board> boards;
        private final List<Diagnostic> diagnostics;

        Result(Map<ContentId, Board> boards, List<Diagnostic> diagnostics) {
            this.boards = boards;
            this.diagnostics = diagnostics;
        }

        /** Every scoreboard that parsed, keyed by id. */
        public Map<ContentId, Board> boards() {
            return boards;
        }

        /** What went wrong. */
        public List<Diagnostic> diagnostics() {
            return diagnostics;
        }
    }
}
