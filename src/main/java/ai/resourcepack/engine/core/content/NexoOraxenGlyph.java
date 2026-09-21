package ai.resourcepack.engine.core.content;

import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Static bitmap glyphs have the same font-provider meaning in all three plugins. */
final class NexoOraxenGlyph {
    private NexoOraxenGlyph() {
    }

    static boolean looksLikeOne(DefinitionNode document) {
        for (String id : document.keys()) {
            DefinitionNode glyph = document.node(id).orElse(DefinitionNode.empty());
            if (glyph.has("texture") || glyph.has("gif")) return true;
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
            if (glyph.has("rows") || glyph.has("columns")) {
                diagnostics.add(Diagnostic.warning(origin, id,
                        "Multi-bitmap glyphs allocate several characters; split it into one static glyph per cell."));
                continue;
            }
            String file = localPath(texture, namespace, id, origin, diagnostics);
            if (file == null) continue;
            Map<String, Object> icon = new LinkedHashMap<>();
            icon.put("file", file);
            glyph.integer("height").ifPresent(height -> icon.put("height", height));
            glyph.integer("ascent").ifPresent(ascent -> icon.put("ascent", ascent));
            out.put(id, icon);
        }
        return out;
    }

    private static String localPath(String texture, String namespace, String id, String origin,
                                    List<Diagnostic> diagnostics) {
        String value = texture.endsWith(".png") ? texture.substring(0, texture.length() - 4) : texture;
        int colon = value.indexOf(':');
        if (colon < 0) return value;
        if (!value.substring(0, colon).equals(namespace)) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "Glyph texture " + texture + " is outside this pack's namespace, so it was skipped."));
            return null;
        }
        return value.substring(colon + 1);
    }
}
