package ai.resourcepack.engine.core.content;

import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the item YAML Nexo and current Oraxen use.
 *
 * <p>Both formats put one item id at the top level, with {@code material}, a
 * display name, and a capitalised {@code Pack} block that points at a model or
 * texture. That is intentionally unlike our own format (which has lowercase
 * {@code model} and {@code texture}), so recognising it by shape cannot turn a
 * typo in an RP Engine item into a different kind of content.
 *
 * <p>The two plugins own their block/furniture, glyph and action mechanics.
 * Those are not silently guessed at here: the ordinary item still loads, and
 * a warning names the item and mechanism that needs re-authoring.
 */
final class NexoOraxen {
    private NexoOraxen() {
    }

    static boolean looksLikeOne(DefinitionNode document) {
        for (String id : document.keys()) {
            DefinitionNode item = document.node(id).orElse(DefinitionNode.empty());
            if (item.node("Pack").isPresent()
                    && (item.raw("material") != null || item.raw("itemname") != null
                    || item.raw("displayname") != null || item.raw("display_name") != null)) {
                return true;
            }
        }
        return false;
    }

    static Map<ContentKind, Map<String, Object>> translate(DefinitionNode document,
                                                             String namespace, String origin,
                                                             List<Diagnostic> diagnostics) {
        Map<String, Object> items = new LinkedHashMap<>();
        for (String id : document.keys()) {
            DefinitionNode item = document.node(id).orElse(DefinitionNode.empty());
            if (!item.node("Pack").isPresent()) {
                continue;
            }
            items.put(id, item(item, id, namespace, origin, diagnostics));
        }
        return items.isEmpty() ? Map.of() : Map.of(ContentKind.ITEM, items);
    }

    private static Map<String, Object> item(DefinitionNode item, String id, String namespace,
                                             String origin, List<Diagnostic> diagnostics) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("material", item.string("material").orElse("PAPER"));
        item.string("itemname").or(() -> item.string("displayname"))
                .or(() -> item.string("display_name"))
                .ifPresent(name -> out.put("name", name));

        DefinitionNode pack = item.node("Pack").orElse(DefinitionNode.empty());
        pack.string("model").flatMap(model -> localPath(model, namespace, id, origin, diagnostics, "model"))
                .ifPresent(model -> out.put("model", model));
        if (!out.containsKey("model")) {
            pack.string("texture").flatMap(texture -> localPath(texture, namespace, id, origin, diagnostics, "texture"))
                    .ifPresent(texture -> out.put("texture", texture));
        }

        item.node("Mechanics").ifPresent(mechanics -> {
            for (String mechanic : mechanics.keys()) {
                diagnostics.add(Diagnostic.warning(origin, id,
                        mechanic + " is a Nexo/Oraxen mechanic rather than an RP Engine item property, so it was skipped. "
                                + "The item itself still loads."));
            }
        });
        return out;
    }

    /** A resource location is a path after its own namespace; foreign art is not ours to copy. */
    private static java.util.Optional<String> localPath(String reference, String namespace, String id,
                                                        String origin, List<Diagnostic> diagnostics, String field) {
        String value = reference.endsWith(".json") || reference.endsWith(".png")
                ? reference.substring(0, reference.length() - 4) : reference;
        int colon = value.indexOf(':');
        if (colon < 0) {
            return java.util.Optional.of(value);
        }
        String declared = value.substring(0, colon);
        if (!declared.equals(namespace)) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "Pack." + field + " points at " + reference + ", outside this pack's namespace. "
                            + "RP Engine cannot copy somebody else's asset, so it was skipped."));
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(value.substring(colon + 1));
    }
}
