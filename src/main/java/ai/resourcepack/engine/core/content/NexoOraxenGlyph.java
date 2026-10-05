package ai.resourcepack.engine.core.content;

import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Static bitmap glyphs have the same font-provider meaning in all three plugins.
 *
 * <p>A Nexo/Oraxen glyph is {@code texture}, {@code height} and
 * {@code ascent}, which is an icon of ours under another name. Three of their
 * shapes need more than a rename:
 *
 * <ul>
 *   <li><strong>A multi-bitmap glyph</strong> ({@code rows}/{@code columns})
 *       is one PNG cut into cells. It becomes one icon per cell,
 *       {@code <id>_1} to {@code <id>_N}, left to right and then down - the
 *       numbers Nexo's own {@code <glyph:id:N>} uses - each drawing its cell
 *       of the same sheet.</li>
 *   <li><strong>An Oraxen animation</strong> is a sprite sheet of frames
 *       stacked vertically, played by a shader. The first frame comes across
 *       as a still icon, which is the same sheet cut into one column.</li>
 *   <li><strong>A Nexo GIF</strong> is frames Nexo generates itself, so there
 *       is no sheet to cut and it is skipped.</li>
 * </ul>
 *
 * <p>What does not come across is everything about the glyph as a chat
 * shortcut - placeholders, a permission, tab completion, a fixed character,
 * a font of its own. RP Engine icons go into the default font, are typed as
 * {@code :namespace:id:}, and get a codepoint by id. Each is a warning naming
 * the glyph.
 */
final class NexoOraxenGlyph {
    private NexoOraxenGlyph() {
    }

    /**
     * Whether a font file is theirs.
     *
     * <p>{@code texture} is the giveaway: an RP Engine icon says {@code file}.
     * {@code gif} used to be one too and is not any more, because an RP Engine
     * icon can be a GIF now and says so with the same word. So a GIF counts as
     * theirs only beside a key nothing of ours has — Nexo's
     * {@code frame_count} or {@code offset}, or the chat keys — and a file of
     * plain {@code gif:} icons is read as ours. Read the other way it would
     * lose every icon in it that has no {@code texture}, which is all of them.
     */
    static boolean looksLikeOne(DefinitionNode document) {
        for (String id : document.keys()) {
            DefinitionNode glyph = document.node(id).orElse(DefinitionNode.empty());
            if (glyph.has("texture")) return true;
            if (glyph.has("gif")) {
                for (String theirs : List.of("frame_count", "offset", "placeholders", "chat", "tabcomplete",
                        "is_emoji", "char")) {
                    if (glyph.has(theirs)) return true;
                }
            }
        }
        return false;
    }

    static Map<String, Object> translate(DefinitionNode document, String namespace, String origin,
                                         List<Diagnostic> diagnostics) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String id : document.keys()) {
            DefinitionNode glyph = document.node(id).orElse(DefinitionNode.empty());
            if (glyph.has("gif")) {
                diagnostics.add(Diagnostic.warning(origin, id,
                        "Animated GIF glyphs need Nexo's generated font frames and have no static RP Engine equivalent."));
                continue;
            }
            String texture = glyph.string("texture").orElse(null);
            if (texture == null) continue;
            String file = localPath(texture, namespace, id, origin, diagnostics);
            if (file == null) continue;
            chatOnly(glyph, id, origin, diagnostics);

            Map<String, Object> icon = new LinkedHashMap<>();
            icon.put("file", file);
            glyph.integer("height").ifPresent(height -> icon.put("height", height));
            glyph.integer("ascent").ifPresent(ascent -> icon.put("ascent", ascent));

            int rows = glyph.integer("rows").orElse(1);
            int columns = glyph.integer("columns").orElse(1);
            if (glyph.node("animation").isPresent()) {
                int frames = glyph.node("animation").flatMap(a -> a.integer("frames")).orElse(1);
                diagnostics.add(Diagnostic.warning(origin, id,
                        "is an animated glyph. RP Engine icons are still pictures, so its first frame came across."));
                if (frames > 1) {
                    out.put(id, withGrid(icon, frames, 1, 1));
                } else {
                    out.put(id, icon);
                }
                continue;
            }
            if (rows > 1 || columns > 1) {
                int cells = rows * columns;
                for (int cell = 1; cell <= cells; cell++) {
                    out.put(id + "_" + cell, withGrid(icon, rows, columns, cell));
                }
                diagnostics.add(Diagnostic.warning(origin, id,
                        "is a " + rows + " by " + columns + " multi-bitmap glyph, so it became " + cells
                                + " icons, " + id + "_1 to " + id + "_" + cells
                                + ", numbered left to right and then down as Nexo numbers them."));
                continue;
            }
            out.put(id, icon);
        }
        return out;
    }

    private static Map<String, Object> withGrid(Map<String, Object> icon, int rows, int columns, int cell) {
        Map<String, Object> out = new LinkedHashMap<>(icon);
        Map<String, Object> grid = new LinkedHashMap<>();
        grid.put("rows", rows);
        grid.put("columns", columns);
        grid.put("cell", cell);
        out.put("grid", grid);
        return out;
    }

    /** The parts of a glyph that are about typing it in chat, which work differently here. */
    private static void chatOnly(DefinitionNode glyph, String id, String origin, List<Diagnostic> diagnostics) {
        List<String> skipped = new ArrayList<>();
        for (String key : List.of("chat", "placeholders", "permission", "tabcomplete", "is_emoji", "char")) {
            if (glyph.raw(key) != null) skipped.add(key);
        }
        glyph.string("font").filter(font -> !font.equals("minecraft:default") && !font.equals("default"))
                .ifPresent(font -> skipped.add("font"));
        if (!skipped.isEmpty()) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    String.join(", ", skipped) + " skipped: an RP Engine icon goes in the default font, is typed as :"
                            + id + ": with chat.icons on, and is given its character by id."));
        }
    }

    /**
     * The texture as a path under {@code assets/textures/font/}, which is
     * where icons are read from. A path that already starts with
     * {@code font/} is that folder written out.
     */
    private static String localPath(String texture, String namespace, String id, String origin,
                                    List<Diagnostic> diagnostics) {
        String value = texture.endsWith(".png") ? texture.substring(0, texture.length() - 4) : texture;
        int colon = value.indexOf(':');
        if (colon >= 0) {
            if (!value.substring(0, colon).equals(namespace)) {
                diagnostics.add(Diagnostic.warning(origin, id,
                        "Glyph texture " + texture + " is outside this pack's namespace, so it was skipped."));
                return null;
            }
            value = value.substring(colon + 1);
        }
        return value.startsWith("font/") ? value.substring("font/".length()) : value;
    }
}
