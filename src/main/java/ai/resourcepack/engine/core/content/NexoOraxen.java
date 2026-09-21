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
 * display name, and either a capitalised {@code Pack} block or (on recent
 * Nexo) {@code Components.item_model}. That is intentionally unlike our own
 * format (which has lowercase {@code model} and {@code texture}), so
 * recognising it by shape cannot turn a typo in an RP Engine item into a
 * different kind of content.
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
            if ((item.node("Pack").isPresent() || item.node("Components").flatMap(c -> c.string("item_model")).isPresent())
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
        Map<String, Object> blocks = new LinkedHashMap<>();
        for (String id : document.keys()) {
            DefinitionNode item = document.node(id).orElse(DefinitionNode.empty());
            if (!item.node("Pack").isPresent() && item.node("Components").flatMap(c -> c.string("item_model")).isEmpty()) {
                continue;
            }
            DefinitionNode mechanics = item.node("Mechanics").orElse(DefinitionNode.empty());
            DefinitionNode block = mechanics.node("custom_block")
                    .or(() -> mechanics.node("block")).orElse(DefinitionNode.empty());
            if (!block.isEmpty()) {
                blocks.put(id, block(item, block, id, namespace, origin, diagnostics));
            } else {
                items.put(id, item(item, id, namespace, origin, diagnostics));
            }
        }
        Map<ContentKind, Map<String, Object>> out = new LinkedHashMap<>();
        if (!items.isEmpty()) out.put(ContentKind.ITEM, items);
        if (!blocks.isEmpty()) out.put(ContentKind.BLOCK, blocks);
        return out;
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

        if (!out.containsKey("model")) {
            item.node("Components").flatMap(components -> components.string("item_model"))
                    .flatMap(model -> localPath(model, namespace, id, origin, diagnostics, "Components.item_model"))
                    .ifPresent(model -> out.put("model", model));
        }

        item.node("Mechanics").ifPresent(mechanics -> {
            for (String mechanic : mechanics.keys()) {
                if (mechanic.equals("furniture")) {
                    out.put("place", furniture(mechanics.node("furniture").orElse(DefinitionNode.empty()), namespace));
                    continue;
                }
                diagnostics.add(Diagnostic.warning(origin, id,
                        mechanic + " is a Nexo/Oraxen mechanic rather than an RP Engine item property, so it was skipped. "
                                + "The item itself still loads."));
            }
        });
        return out;
    }

    /** A custom block is its own RP Engine content id; its item comes with it. */
    private static Map<String, Object> block(DefinitionNode item, DefinitionNode mechanic, String id,
                                             String namespace, String origin, List<Diagnostic> diagnostics) {
        Map<String, Object> out = new LinkedHashMap<>();
        DefinitionNode pack = item.node("Pack").orElse(DefinitionNode.empty());
        mechanic.string("model").or(() -> mechanic.node("appearance").flatMap(a -> a.string("model")))
                .or(() -> pack.string("model"))
                .flatMap(model -> localPath(model, namespace, id, origin, diagnostics, "model"))
                .ifPresent(model -> out.put("model", model));
        mechanic.string("hardness").ifPresent(hardness -> out.put("hardness", hardness));
        mechanic.node("hardness").flatMap(hardness -> hardness.string("hardness"))
                .ifPresent(hardness -> out.put("hardness", hardness));
        mechanic.node("drop").flatMap(drop -> drop.string("best_tool"))
                .ifPresent(tool -> out.put("tool", tool.toLowerCase()));
        mechanic.string("sound").or(() -> mechanic.node("block_sounds").flatMap(sounds -> sounds.string("place_sound")))
                .ifPresent(sound -> out.put("sound", sound));
        String type = mechanic.string("type").orElse("").toLowerCase();
        if (type.contains("mushroom")) out.put("base", "mushroom_stem");
        for (String name : mechanic.keys()) {
            if (!List.of("model", "appearance", "hardness", "drop", "sound", "block_sounds", "type", "custom_variation").contains(name)) {
                diagnostics.add(Diagnostic.warning(origin, id,
                        "custom block " + name + " has no RP Engine equivalent and was skipped."));
            }
        }
        return out;
    }

    /** The common furniture properties that have direct meaning on an RP Engine placed model. */
    private static Map<String, Object> furniture(DefinitionNode furniture, String namespace) {
        Map<String, Object> place = new LinkedHashMap<>();
        if (!furniture.nodes("hitbox").isEmpty() || furniture.node("hitbox").isPresent()
                || furniture.raw("barriers") != null) place.put("solid", true);
        furniture.string("seat").or(() -> furniture.string("seat_height"))
                .ifPresent(seat -> place.put("seat", seat));
        furniture.string("rotation").ifPresent(rotation -> place.put("facing", "free"));
        furniture.node("drop").flatMap(drop -> drop.string("nexo_item").or(() -> drop.string("oraxen_item")))
                .ifPresent(drop -> place.put("drop", drop.contains(":") ? drop : namespace + ":" + drop));
        return place;
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
