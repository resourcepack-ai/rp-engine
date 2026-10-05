package ai.resourcepack.engine.core.content;

import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Static bitmap glyphs have the same font-provider meaning in all three plugins.
 *
 * <p>A Nexo/Oraxen glyph is {@code texture}, {@code height} and
 * {@code ascent}, which is an icon of ours under another name. Four of their
 * shapes need more than a rename:
 *
 * <ul>
 *   <li><strong>A multi-bitmap glyph</strong> ({@code rows}/{@code columns})
 *       is one PNG cut into cells. It becomes one icon per cell,
 *       {@code <id>_1} to {@code <id>_N}, left to right and then down - the
 *       numbers Nexo's own {@code <glyph:id:N>} uses - each drawing its cell
 *       of the same sheet.</li>
 *   <li><strong>A reference glyph</strong> ({@code reference} and
 *       {@code index}) names one cell of a multi-bitmap glyph in the same
 *       file, so it becomes an icon drawing that cell of that sheet.</li>
 *   <li><strong>An Oraxen animation</strong> is a strip of frames stacked
 *       vertically, which is exactly an animated icon of ours:
 *       {@code animation: {frames, fps, loop}} carries straight across.</li>
 *   <li><strong>A Nexo GIF</strong> ({@code gif}, {@code frame_count}) is an
 *       animated icon drawn from a GIF, which the build turns into a strip.</li>
 * </ul>
 *
 * <p>Both animate differently here, and that is worth saying once rather than
 * in a warning on every glyph: they play their frames in the client with a
 * text shader, and RP Engine picks the frame on the server instead (see
 * FORMAT.md). So an animated glyph moves on a scoreboard or a hologram showing
 * {@code %rpengine_icon_<id>%}, and is its first frame in chat.
 *
 * <p>The chat half comes across too: {@code placeholders} (Nexo) and
 * {@code chat.placeholders} (Oraxen) are an icon's {@code aliases}, and
 * {@code permission} / {@code chat.permission} its permission. What does not
 * is tab completion, a fixed character and a font of its own - RP Engine icons
 * go into the default font and get a codepoint by id - and each is a warning
 * naming the glyph.
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
            if (glyph.has("reference")) {
                reference(document, glyph, namespace, id, origin, diagnostics)
                        .ifPresent(icon -> out.put(id, chat(glyph, icon, id, origin, diagnostics)));
                continue;
            }
            if (glyph.has("gif")) {
                gif(glyph, namespace, id, origin, diagnostics)
                        .ifPresent(icon -> out.put(id, chat(glyph, icon, id, origin, diagnostics)));
                continue;
            }
            String texture = glyph.string("texture").orElse(null);
            if (texture == null) continue;
            String file = localPath(texture, namespace, id, origin, diagnostics);
            if (file == null) continue;

            Map<String, Object> icon = new LinkedHashMap<>();
            icon.put("file", file);
            glyph.integer("height").ifPresent(height -> icon.put("height", height));
            glyph.integer("ascent").ifPresent(ascent -> icon.put("ascent", ascent));

            int rows = glyph.integer("rows").orElse(1);
            int columns = glyph.integer("columns").orElse(1);
            Optional<DefinitionNode> animation = glyph.node("animation");
            if (animation.isPresent()) {
                // Oraxen's strip, frames top to bottom: our animation block
                // says the same thing in the same words.
                Map<String, Object> frames = new LinkedHashMap<>();
                animation.get().integer("frames").ifPresent(n -> frames.put("frames", n));
                animation.get().integer("fps").ifPresent(fps -> frames.put("fps", fps));
                animation.get().bool("loop").ifPresent(loop -> frames.put("loop", loop));
                icon.put("animation", frames);
                out.put(id, chat(glyph, icon, id, origin, diagnostics));
                continue;
            }
            if (rows > 1 || columns > 1) {
                int cells = rows * columns;
                // Every cell is its own icon, so a placeholder typed for the
                // glyph has no one cell to mean. The permission applies to all.
                Map<String, Object> shared = chat(glyph, icon, id, origin, diagnostics);
                if (shared.remove("aliases") != null) {
                    diagnostics.add(Diagnostic.warning(origin, id,
                            "placeholders skipped: a multi-bitmap glyph becomes " + cells
                                    + " icons, and a placeholder cannot type all of them. Add aliases to the one "
                                    + "it meant, or point a reference glyph at that cell."));
                }
                for (int cell = 1; cell <= cells; cell++) {
                    out.put(id + "_" + cell, withGrid(shared, rows, columns, cell));
                }
                diagnostics.add(Diagnostic.warning(origin, id,
                        "is a " + rows + " by " + columns + " multi-bitmap glyph, so it became " + cells
                                + " icons, " + id + "_1 to " + id + "_" + cells
                                + ", numbered left to right and then down as Nexo numbers them."));
                continue;
            }
            out.put(id, chat(glyph, icon, id, origin, diagnostics));
        }
        return out;
    }

    /**
     * Nexo's GIF glyph: {@code gif} is a texture reference like
     * {@code nexo:gifs/necoflap.gif}, read the same way {@code texture} is, and
     * {@code frame_count} keeps the first so many frames.
     *
     * <p>{@code offset} is not carried. It shifts where Nexo's generated frames
     * sit, and the strip here is drawn from the GIF's own frames at the GIF's
     * own size, so there is nothing for it to shift; {@code ascent} is the knob
     * that moves an icon.
     */
    private static Optional<Map<String, Object>> gif(DefinitionNode glyph, String namespace, String id,
                                                     String origin, List<Diagnostic> diagnostics) {
        String reference = glyph.string("gif").orElse("");
        String bare = reference.endsWith(".gif") ? reference.substring(0, reference.length() - 4) : reference;
        String file = bare.isEmpty() ? null : localPath(bare, namespace, id, origin, diagnostics);
        if (file == null) return Optional.empty();
        Map<String, Object> icon = new LinkedHashMap<>();
        icon.put("gif", file + ".gif");
        glyph.integer("height").ifPresent(height -> icon.put("height", height));
        glyph.integer("ascent").ifPresent(ascent -> icon.put("ascent", ascent));
        Map<String, Object> animation = new LinkedHashMap<>();
        glyph.integer("frame_count").filter(n -> n > 0).ifPresent(n -> animation.put("frames", n));
        glyph.node("animation").flatMap(a -> a.integer("fps")).ifPresent(fps -> animation.put("fps", fps));
        if (!animation.isEmpty()) icon.put("animation", animation);
        if (glyph.raw("offset") != null) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "offset skipped: the GIF is drawn as its own frames at its own size here, so there is "
                            + "nothing to offset. Use ascent to move it up or down."));
        }
        return Optional.of(icon);
    }

    /**
     * A reference glyph: {@code reference: <multi-bitmap id>} and
     * {@code index: N}, one cell of a sheet declared in the same file. It
     * draws that cell, at the sheet's height and ascent unless it says its own.
     */
    private static Optional<Map<String, Object>> reference(DefinitionNode document, DefinitionNode glyph,
                                                           String namespace, String id, String origin,
                                                           List<Diagnostic> diagnostics) {
        String target = glyph.string("reference").orElse("");
        DefinitionNode sheet = document.node(target).orElse(null);
        String texture = sheet == null ? null : sheet.string("texture").orElse(null);
        if (texture == null) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "references " + target + ", which is not a glyph with a texture in this file, so it was skipped."));
            return Optional.empty();
        }
        String file = localPath(texture, namespace, id, origin, diagnostics);
        if (file == null) return Optional.empty();
        int rows = sheet.integer("rows").orElse(1);
        int columns = sheet.integer("columns").orElse(1);
        int index = glyph.integer("index").orElse(1);
        if (index < 1 || index > rows * columns) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "index: " + index + " is not one of the " + rows * columns + " cells of " + target
                            + ", which are numbered from 1, so it was skipped."));
            return Optional.empty();
        }
        Map<String, Object> icon = new LinkedHashMap<>();
        icon.put("file", file);
        Optional<Integer> height = glyph.integer("height").or(() -> sheet.integer("height"));
        Optional<Integer> ascent = glyph.integer("ascent").or(() -> sheet.integer("ascent"));
        height.ifPresent(h -> icon.put("height", h));
        ascent.ifPresent(a -> icon.put("ascent", a));
        return Optional.of(rows * columns > 1 ? withGrid(icon, rows, columns, index) : icon);
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

    /**
     * The chat half of a glyph, onto the icon: placeholders become aliases and
     * the permission comes along, whichever plugin's spelling it is in. What
     * has no equivalent is a warning.
     */
    private static Map<String, Object> chat(DefinitionNode glyph, Map<String, Object> icon, String id, String origin,
                                            List<Diagnostic> diagnostics) {
        Map<String, Object> out = new LinkedHashMap<>(icon);
        DefinitionNode oraxen = glyph.node("chat").orElse(DefinitionNode.empty());
        List<String> aliases = new ArrayList<>(glyph.strings("placeholders"));
        for (String alias : oraxen.strings("placeholders")) {
            if (!aliases.contains(alias)) aliases.add(alias);
        }
        if (!aliases.isEmpty()) out.put("aliases", aliases);
        glyph.string("permission").or(() -> oraxen.string("permission"))
                .filter(permission -> !permission.isBlank())
                .ifPresent(permission -> out.put("permission", permission));

        List<String> skipped = new ArrayList<>();
        for (String key : List.of("tabcomplete", "is_emoji", "char")) {
            if (glyph.raw(key) != null) skipped.add(key);
        }
        if (oraxen.raw("tabcomplete") != null) skipped.add("chat.tabcomplete");
        glyph.string("font").filter(font -> !font.equals("minecraft:default") && !font.equals("default"))
                .ifPresent(font -> skipped.add("font"));
        if (!skipped.isEmpty()) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    String.join(", ", skipped) + " skipped: an RP Engine icon goes in the default font, is typed as :"
                            + id + ": (or an alias) with chat.icons on, and is given its character by id."));
        }
        return out;
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
