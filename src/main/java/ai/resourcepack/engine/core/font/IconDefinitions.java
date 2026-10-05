package ai.resourcepack.engine.core.font;

import ai.resourcepack.engine.api.ContentDefinition;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.IconInfo;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.api.PackMeta;
import ai.resourcepack.engine.core.pack.PackFiles;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads icon definitions, taking each one's codepoint from
 * {@link GlyphAllocator}.
 *
 * <p><strong>Allocation lives with the definitions rather than in the
 * builder</strong>, and that is the whole design. Both halves of the engine
 * need the same answer — the build writes the font file, the server writes the
 * character into a chat message — and the only way two pieces of code agree
 * about a number is for one of them to work it out and the other to ask.
 * Deriving it twice is how they drift.
 *
 * <p><strong>An animated icon is a run of codepoints, one per frame</strong>,
 * so how many frames it has is part of the allocation, and for a GIF only the
 * GIF knows. That is why this reads files at all: through {@link PackFiles},
 * which the build and the server both hand it over the same folder, so the GIF
 * is counted once by the same code on both sides.
 */
public final class IconDefinitions {

    private IconDefinitions() {
    }

    /**
     * Everything of kind FONT in {@code loaded}, parsed and allocated, reading
     * no files. A GIF icon cannot be counted without its file, so it is an
     * error here; anything that builds or serves a pack uses
     * {@link #parse(LoadReport, PackFiles)}.
     */
    public static Result parse(LoadReport loaded) {
        return parse(loaded, PackFiles.NONE);
    }

    /** Everything of kind FONT in {@code loaded}, parsed and allocated. */
    public static Result parse(LoadReport loaded, PackFiles files) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (loaded == null) {
            return new Result(Map.of(), List.of());
        }
        PackFiles reader = files == null ? PackFiles.NONE : files;
        List<String> namespaces = new ArrayList<>();
        for (PackMeta pack : loaded.packs()) {
            namespaces.add(pack.namespace());
        }

        // Frames first, because they decide the codepoints: an icon of eight
        // frames owns eight in a row.
        Map<ContentId, Motion> motions = new HashMap<>();
        for (ContentDefinition definition : loaded.definitions(ContentKind.FONT)) {
            motions.put(definition.id(), motion(definition, reader, namespaces, diagnostics));
        }

        // The codepoints come from the shared allocator: icons, screens and
        // HUD overlays are one mechanism wearing three names and share one
        // number line. See GlyphAllocator.
        Map<ContentId, Integer> codepoints = GlyphAllocator.allocate(loaded,
                definition -> motions.getOrDefault(definition.id(), Motion.STILL).glyphs());
        Map<ContentId, IconInfo> icons = new LinkedHashMap<>();
        for (ContentDefinition definition : loaded.definitions(ContentKind.FONT)) {
            Integer codepoint = codepoints.get(definition.id());
            if (codepoint == null) {
                diagnostics.add(Diagnostic.error(definition.origin(), definition.id().path(),
                        "There is no room left in the Private Use Area: "
                                + (GlyphAllocator.LAST_CODEPOINT - GlyphAllocator.FIRST_CODEPOINT + 1)
                                + " glyphs is the limit, across icons (one per frame), screens and HUDs together."));
                continue;
            }
            Motion motion = motions.getOrDefault(definition.id(), Motion.STILL);
            if (motion.broken) {
                continue;
            }
            parseOne(definition, codepoint, motion, diagnostics)
                    .ifPresent(icon -> icons.put(icon.id(), icon));
        }
        return new Result(Map.copyOf(unshared(icons, loaded, diagnostics)), List.copyOf(diagnostics));
    }

    private static Optional<IconInfo> parseOne(ContentDefinition definition, int codepoint, Motion motion,
                                               List<Diagnostic> diagnostics) {
        DefinitionNode body = definition.body();
        String origin = definition.origin();
        String where = definition.id().path();

        String file = motion.gif != null ? motion.gif : body.string("file").orElse(definition.id().path());
        int height = body.integer("height").orElse(8);
        // Vanilla's own rule, and breaking it makes the glyph vanish rather
        // than draw badly, which is a much harder thing to diagnose.
        int ascent = body.integer("ascent").orElse(Math.min(height, 8));

        if (height < 1 || height > 256) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "height: " + height + " is outside 1 to 256. Using 8."));
            height = 8;
            ascent = Math.min(ascent, height);
        }
        if (ascent > height) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "ascent: " + ascent + " is greater than height: " + height
                            + ", which the game refuses to draw at all. Using " + height + "."));
            ascent = height;
        }
        IconInfo icon = IconInfo.of(definition.id(), file, height, ascent, codepoint);
        Optional<DefinitionNode> grid = body.node("grid");
        if (motion.rows > 1) {
            // A strip: frame k is row k, one column, so the font cuts it with
            // nothing cropped, as it cuts any other sheet.
            icon = icon.withCell(motion.rows, 1, 1);
            if (motion.frames > 1) {
                icon = icon.withAnimation(motion.frames, motion.fps, motion.loops);
            }
        } else if (grid.isPresent() && motion.gif == null) {
            icon = cell(icon, grid.get(), origin, where, diagnostics);
        }
        return Optional.of(icon.withChat(aliases(body, origin, where, diagnostics),
                body.string("permission").map(String::trim).orElse(null)));
    }

    /**
     * {@code aliases: ["<3", ":heart:"]}: more ways to type the icon in chat.
     *
     * <p>Chat matches an alias as a whole word, so one with a space in it could
     * never match anything and is refused rather than kept as a promise that
     * cannot be kept.
     */
    private static List<String> aliases(DefinitionNode body, String origin, String where,
                                        List<Diagnostic> diagnostics) {
        List<String> kept = new ArrayList<>();
        for (String alias : body.strings("aliases")) {
            String word = alias.trim();
            if (word.isEmpty()) {
                continue;
            }
            if (word.chars().anyMatch(Character::isWhitespace) || word.length() > 32) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        "aliases: \"" + alias + "\" is skipped. Chat matches an alias as one word on its own, "
                                + "so it cannot contain a space, and it has to be 32 characters or fewer."));
                continue;
            }
            if (!kept.contains(word)) {
                kept.add(word);
            }
        }
        return kept;
    }

    /**
     * One alias per icon: when two list the same one, the first by id keeps it
     * and the others are told. Chat would pick one either way; saying which is
     * the difference between a choice and a mystery.
     */
    private static Map<ContentId, IconInfo> unshared(Map<ContentId, IconInfo> icons, LoadReport loaded,
                                                     List<Diagnostic> diagnostics) {
        Map<String, ContentId> owner = new HashMap<>();
        Map<ContentId, String> origins = new HashMap<>();
        for (ContentDefinition definition : loaded.definitions(ContentKind.FONT)) {
            origins.put(definition.id(), definition.origin());
        }
        Map<ContentId, IconInfo> out = new LinkedHashMap<>(icons);
        for (ContentId id : new java.util.TreeSet<>(icons.keySet())) {
            IconInfo icon = icons.get(id);
            if (icon.aliases().isEmpty()) {
                continue;
            }
            List<String> kept = new ArrayList<>();
            for (String alias : icon.aliases()) {
                ContentId first = owner.putIfAbsent(alias, id);
                if (first == null) {
                    kept.add(alias);
                } else {
                    diagnostics.add(Diagnostic.warning(origins.getOrDefault(id, ""), id.path(),
                            "aliases: \"" + alias + "\" is already " + first + "'s, so it types that icon, not this one."));
                }
            }
            if (kept.size() != icon.aliases().size()) {
                out.put(id, icon.withChat(kept, icon.permission().orElse(null)));
            }
        }
        return out;
    }

    /**
     * How many frames an icon has, and how fast and whether it loops.
     *
     * <p>Two ways to have frames. {@code animation: {frames}} on an ordinary
     * PNG says it is a strip — frames stacked top to bottom, each a cell the
     * same size — and a {@code gif:} is read for them. Both end up as the same
     * thing, a strip the font cuts into one glyph per frame; a GIF's strip is
     * simply one the build draws.
     *
     * <p>Not on a {@code grid:} sheet. A sheet whose cells are frames would be
     * a reading order somebody has to know, and the two cases that exist in the
     * wild (Oraxen's strips and Nexo's GIFs) are both covered without it.
     */
    private static Motion motion(ContentDefinition definition, PackFiles files, List<String> namespaces,
                                 List<Diagnostic> diagnostics) {
        DefinitionNode body = definition.body();
        String origin = definition.origin();
        String where = definition.id().path();
        Optional<DefinitionNode> animation = body.node("animation");
        boolean loops = animation.flatMap(a -> a.bool("loop")).orElse(true);
        Optional<Integer> askedFps = animation.flatMap(a -> a.integer("fps"));
        Optional<Integer> askedFrames = animation.flatMap(a -> a.integer("frames"));
        askedFps.filter(fps -> fps < 1 || fps > IconInfo.MAX_FPS).ifPresent(fps ->
                diagnostics.add(Diagnostic.warning(origin, where,
                        "animation.fps: " + fps + " is outside 1 to " + IconInfo.MAX_FPS
                                + ". Nothing the server sends changes faster than once a tick, so "
                                + Math.max(1, Math.min(IconInfo.MAX_FPS, fps)) + " is used.")));

        String gif = gifOf(body);
        if (gif != null) {
            if (body.node("grid").isPresent()) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        "grid: is ignored on a GIF, whose frames are the animation rather than cells of a sheet."));
            }
            String location = FontAssets.textureOf(definition.id().namespace(), gif);
            String source = FontAssets.gifSource(location);
            Optional<byte[]> bytes = files.find(source, namespaces);
            if (bytes.isEmpty()) {
                diagnostics.add(Diagnostic.error(origin, where, "No GIF at " + source + "."));
                return Motion.BROKEN;
            }
            Optional<GifFrames> read = GifFrames.measure(bytes.get(), IconInfo.MAX_FRAMES);
            if (read.isEmpty()) {
                diagnostics.add(Diagnostic.error(origin, where,
                        source + " could not be read as a GIF. Re-export it, or draw the frames as a strip "
                                + "and use file: with animation.frames."));
                return Motion.BROKEN;
            }
            GifFrames frames = read.get();
            if (frames.total() > IconInfo.MAX_FRAMES) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        source + " has " + frames.total() + " frames and an icon can have "
                                + IconInfo.MAX_FRAMES + ", one codepoint each. The first "
                                + IconInfo.MAX_FRAMES + " are used."));
            }
            int count = frames.count();
            if (askedFrames.isPresent()) {
                int asked = askedFrames.get();
                if (asked >= 1 && asked <= count) {
                    count = asked;
                } else {
                    diagnostics.add(Diagnostic.warning(origin, where,
                            "animation.frames: " + asked + " is not between 1 and the " + count
                                    + " frames the GIF has, so all " + count + " are used."));
                }
            }
            int fps = askedFps.orElse(frames.fps(IconInfo.MAX_FPS));
            return new Motion(count, count, fps, loops, gif, false);
        }

        if (animation.isEmpty()) {
            return Motion.STILL;
        }
        if (body.node("grid").isPresent()) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "animation: on a grid: sheet is not supported. An animation is a strip of frames stacked "
                            + "top to bottom, or a GIF. The cell is drawn as a still picture."));
            return Motion.STILL;
        }
        int frames = askedFrames.orElse(1);
        if (frames < 1) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "animation.frames: " + frames + " is not a number of frames. The whole picture is used."));
            return Motion.STILL;
        }
        if (frames > IconInfo.MAX_FRAMES) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "animation.frames: " + frames + " is more than the " + IconInfo.MAX_FRAMES
                            + " an icon can have, one codepoint each. Its first frame is drawn as a still picture."));
            return new Motion(frames, 1, IconInfo.DEFAULT_FPS, true, null, false);
        }
        return new Motion(frames, frames, askedFps.orElse(IconInfo.DEFAULT_FPS), loops, null, false);
    }

    /**
     * The GIF an icon is drawn from, with its {@code .gif}: {@code gif:}, or a
     * {@code file:} that names one. Null for an ordinary PNG.
     */
    private static String gifOf(DefinitionNode body) {
        Optional<String> gif = body.string("gif").filter(value -> !value.isBlank());
        if (gif.isPresent()) {
            String value = gif.get();
            return value.endsWith(".gif") ? value : value + ".gif";
        }
        return body.string("file").filter(file -> file.endsWith(".gif")).orElse(null);
    }

    /**
     * {@code grid: {rows, columns, cell}}: one picture out of a sheet of them.
     *
     * <p>A sheet of emotes, or a picture bigger than the 256 pixels a font
     * glyph may be, is one PNG cut into equal cells. Each icon names its cell,
     * counted from 1 left to right and then down, which is the numbering Nexo
     * uses for the same thing, so a pack moved across keeps its numbers.
     *
     * <p>Refused rather than clamped when the cell is not on the sheet: a
     * clamped cell is a different picture, and that is harder to notice than
     * a warning and a whole-sheet icon.
     */
    private static IconInfo cell(IconInfo icon, DefinitionNode grid, String origin, String where,
                                 List<Diagnostic> diagnostics) {
        int rows = grid.integer("rows").orElse(1);
        int columns = grid.integer("columns").orElse(1);
        int cell = grid.integer("cell").orElse(1);
        if (rows < 1 || columns < 1 || rows > 16 || columns > 16) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "grid: " + rows + " rows by " + columns + " columns is outside 1 to 16 each. "
                            + "The whole picture is used."));
            return icon;
        }
        if (cell < 1 || cell > rows * columns) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "grid.cell: " + cell + " is not one of the " + rows * columns
                            + " cells, which are numbered from 1. The whole picture is used."));
            return icon;
        }
        return icon.withCell(rows, columns, cell);
    }

    /**
     * How one icon moves: how many rows its strip has, how many of them are
     * frames, and the GIF it is read from if it is one.
     *
     * <p>{@code rows} and {@code frames} differ in exactly one case: a strip of
     * more frames than an icon may have, which is cut into its real rows so
     * the first frame still draws right, and shown still.
     */
    private record Motion(int rows, int frames, int fps, boolean loops, String gif, boolean broken) {

        static final Motion STILL = new Motion(1, 1, IconInfo.DEFAULT_FPS, true, null, false);

        /** A GIF that could not be read: still one codepoint, so nothing after it moves, but no icon. */
        static final Motion BROKEN = new Motion(1, 1, IconInfo.DEFAULT_FPS, true, null, true);

        /** How many codepoints it takes. */
        int glyphs() {
            return Math.max(1, frames);
        }
    }

    /** The icons, and what was wrong with the ones that are missing. */
    public static final class Result {

        private final Map<ContentId, IconInfo> icons;
        private final List<Diagnostic> diagnostics;

        Result(Map<ContentId, IconInfo> icons, List<Diagnostic> diagnostics) {
            this.icons = icons;
            this.diagnostics = diagnostics;
        }

        /** Every icon that parsed, keyed by id, in codepoint order. */
        public Map<ContentId, IconInfo> icons() {
            return icons;
        }

        /** What went wrong. */
        public List<Diagnostic> diagnostics() {
            return diagnostics;
        }
    }
}
