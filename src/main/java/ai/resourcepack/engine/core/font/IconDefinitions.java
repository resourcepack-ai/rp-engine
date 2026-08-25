package ai.resourcepack.engine.core.font;

import ai.resourcepack.engine.api.ContentDefinition;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.IconInfo;
import ai.resourcepack.engine.api.LoadReport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Reads icon definitions and hands each one a codepoint.
 *
 * <p><strong>The allocator lives here rather than in the builder</strong>, and
 * that is the whole design. Both halves of the engine need the same answer —
 * the build writes the font file, the server writes the character into a chat
 * message — and the only way two pieces of code agree about a number is for
 * one of them to work it out and the other to ask. Deriving it twice is how
 * they drift.
 *
 * <p>Codepoints come out of the Private Use Area in id order. That means they
 * MOVE when content changes, which is a real trade and is documented on
 * {@link IconInfo#codepoint()}: the alternative is a file mapping id to number
 * that must never be lost or reordered, which is exactly the class of problem
 * the item scheme was designed to delete.
 */
public final class IconDefinitions {

    /**
     * The Unicode Private Use Area: {@code U+E000} to {@code U+F8FF}.
     *
     * <p>6,400 icons, which is far more than any pack has, and the only range
     * where a glyph cannot collide with a real character somebody might
     * legitimately type.
     */
    public static final int FIRST_CODEPOINT = 0xE000;
    public static final int LAST_CODEPOINT = 0xF8FF;

    private IconDefinitions() {
    }

    /** Everything of kind FONT in {@code loaded}, parsed and allocated. */
    public static Result parse(LoadReport loaded) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (loaded == null) {
            return new Result(Map.of(), List.of());
        }

        // Sorted by id, so the same content always produces the same
        // allocation on every machine and every restart.
        Map<ContentId, ContentDefinition> sorted = new TreeMap<>();
        for (ContentDefinition definition : loaded.definitions(ContentKind.FONT)) {
            sorted.put(definition.id(), definition);
        }

        Map<ContentId, IconInfo> icons = new LinkedHashMap<>();
        int next = FIRST_CODEPOINT;
        for (ContentDefinition definition : sorted.values()) {
            if (next > LAST_CODEPOINT) {
                diagnostics.add(Diagnostic.error(definition.origin(), definition.id().path(),
                        "There is no room left in the Private Use Area: "
                                + (LAST_CODEPOINT - FIRST_CODEPOINT + 1) + " icons is the limit."));
                continue;
            }
            Optional<IconInfo> icon = parseOne(definition, next, diagnostics);
            if (icon.isPresent()) {
                icons.put(icon.get().id(), icon.get());
                next++;
            }
        }
        return new Result(Map.copyOf(icons), List.copyOf(diagnostics));
    }

    private static Optional<IconInfo> parseOne(ContentDefinition definition, int codepoint,
                                               List<Diagnostic> diagnostics) {
        DefinitionNode body = definition.body();
        String origin = definition.origin();
        String where = definition.id().path();

        String file = body.string("file").orElse(definition.id().path());
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
        return Optional.of(IconInfo.of(definition.id(), file, height, ascent, codepoint));
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
