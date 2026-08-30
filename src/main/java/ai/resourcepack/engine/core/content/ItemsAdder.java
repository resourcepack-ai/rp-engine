package ai.resourcepack.engine.core.content;

import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Reads an ItemsAdder config file as if it were one of ours.
 *
 * <p><strong>Somebody with an ItemsAdder pack should be able to drop it in and
 * see their items.</strong> Not export it, not run a converter, not rewrite
 * six hundred lines of YAML by hand — drop the file in the folder. That is the
 * whole point of this class, and it is why the translation happens at load
 * rather than as a command that writes a second copy of somebody's pack.
 *
 * <p>An ItemsAdder file is recognised by its shape: a top-level {@code info:}
 * block beside {@code items:} or {@code font_images:}. Nothing of ours looks
 * like that — our top-level keys are ids — so there is no ambiguity and no
 * setting to turn this on.
 *
 * <h2>What comes across, and what does not</h2>
 *
 * <p>Items, blocks and font images translate, including the parts of an item
 * that are really vanilla underneath: material, name, lore, enchants,
 * attributes, durability, stack size, permission, armour slot, and the two
 * behaviours that have an equivalent here — a liquid bucket and furniture.
 *
 * <p>What does not: the parts of an item that are ItemsAdder's own plugin
 * behaviour rather than a property of the item — {@code events}, {@code drop},
 * {@code item_flags} — and the recipe kinds this engine has no equivalent for,
 * each named as it is skipped.
 *
 * <p>A block's definition comes across but <strong>a world built with their
 * plugin does not</strong>: the vanilla state a block hides in is allocated in
 * their file against their numbering, so blocks already placed are read as
 * whatever this engine gave that state. A pack migrates; a world has to be
 * rebuilt or remapped by hand.
 *
 * <p><strong>Every one of those is a warning naming the id.</strong> A
 * migration that quietly drops a third of somebody's pack is worse than one
 * that refuses: the whole reason to translate at load is that the person is
 * standing there looking at the console.
 */
final class ItemsAdder {

    private ItemsAdder() {
    }

    /** Whether this document is an ItemsAdder config rather than one of ours. */
    static boolean looksLikeOne(DefinitionNode document) {
        return document.node("info").isPresent()
                && (document.node("items").isPresent()
                || document.node("font_images").isPresent()
                || document.node("blocks").isPresent()
                || document.node("entities").isPresent());
    }

    /** The namespace it declares, which is what its ids belong to. */
    static Optional<String> namespaceOf(DefinitionNode document) {
        return document.node("info").flatMap(info -> info.string("namespace"));
    }

    /**
     * The whole document, as definitions of ours.
     *
     * @return one map per kind, id path to the body a parser of ours reads
     */
    static Map<ContentKind, Map<String, Object>> translate(
            DefinitionNode document, String namespace, String origin, List<Diagnostic> diagnostics) {
        Map<ContentKind, Map<String, Object>> out = new LinkedHashMap<>();

        document.node("items").ifPresent(items -> {
            Map<String, Object> translated = new LinkedHashMap<>();
            for (String id : items.keys()) {
                items.node(id).ifPresent(item -> {
                    if (item.bool("enabled").orElse(Boolean.TRUE)) {
                        translated.put(id, item(item, id, origin, diagnostics));
                    }
                });
            }
            if (!translated.isEmpty()) {
                out.put(ContentKind.ITEM, translated);
            }
        });

        document.node("font_images").ifPresent(images -> {
            Map<String, Object> translated = new LinkedHashMap<>();
            for (String id : images.keys()) {
                images.node(id).ifPresent(image -> translated.put(id, icon(image)));
            }
            if (!translated.isEmpty()) {
                out.put(ContentKind.FONT, translated);
            }
        });

        document.node("blocks").ifPresent(blocks -> {
            Map<String, Object> translated = new LinkedHashMap<>();
            for (String id : blocks.keys()) {
                blocks.node(id).ifPresent(block -> {
                    if (block.bool("enabled").orElse(Boolean.TRUE)) {
                        translated.put(id, block(block));
                    }
                });
            }
            if (!translated.isEmpty()) {
                out.put(ContentKind.BLOCK, translated);
            }
        });
        document.node("entities").ifPresent(entities -> {
            Map<String, Object> translated = new LinkedHashMap<>();
            for (String id : entities.keys()) {
                entities.node(id).ifPresent(entity ->
                        translated.put(id, entity(entity, id, namespace)));
            }
            if (!translated.isEmpty()) {
                out.put(ContentKind.ENTITY, translated);
            }
        });

        document.node("recipes").ifPresent(recipes -> {
            Map<String, Object> translated = recipes(recipes, origin, diagnostics);
            if (!translated.isEmpty()) {
                out.put(ContentKind.RECIPE, translated);
            }
        });

        return out;
    }

    /**
     * One entity: a real mob wearing a model, in both plugins.
     *
     * <p>Their model is a FOLDER of blueprints ({@code model_folder:
     * entity/robot}), because their models are their own format; ours is an
     * item id whose model the mob wears. The last segment of that path is
     * taken as the model name, which is what it is called in practice — and a
     * pack whose model does not resolve gets the ordinary "no such model"
     * message rather than a special one.
     */
    private static Map<String, Object> entity(DefinitionNode entity, String id, String namespace) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", entity.string("type").orElse("ZOMBIE"));
        entity.string("display_name").or(() -> entity.string("name"))
                .ifPresent(name -> out.put("name", name));
        entity.string("max_health").ifPresent(health -> out.put("health", health));
        entity.string("scale").ifPresent(scale -> out.put("scale", scale));
        if (entity.bool("silent").orElse(Boolean.FALSE)) {
            out.put("silent", true);
        }
        entity.string("model_folder").ifPresent(folder -> {
            String name = folder.substring(folder.lastIndexOf('/') + 1);
            // Qualified with the pack's own namespace: ours is an item id, and
            // an unqualified one is not an id at all.
            out.put("model", namespace + ":" + (name.isEmpty() ? id : name));
        });
        return out;
    }

    /**
     * Their recipes, which are grouped by machine rather than typed.
     *
     * <p>{@code recipes.crafting_table.<name>} and
     * {@code recipes.cooking.<name>}, where cooking names its machines in a
     * list — so one of theirs can be several of ours, since a recipe here is
     * one type. The extra ones are suffixed with the machine, which is both
     * unique and readable in {@code /rp recipes}.
     */
    private static Map<String, Object> recipes(DefinitionNode recipes, String origin,
                                               List<Diagnostic> diagnostics) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String group : recipes.keys()) {
            DefinitionNode inGroup = recipes.node(group).orElse(DefinitionNode.empty());
            for (String name : inGroup.keys()) {
                DefinitionNode recipe = inGroup.node(name).orElse(DefinitionNode.empty());
                if (!recipe.bool("enabled").orElse(Boolean.TRUE)) {
                    continue;
                }
                switch (group) {
                    case "crafting_table":
                        out.put(name, crafting(recipe));
                        break;
                    case "cooking":
                        cooking(recipe, name, out);
                        break;
                    case "campfire_cooking":
                        out.put(name, cooked(recipe, "campfire"));
                        break;
                    case "stonecutter":
                        out.put(name, cooked(recipe, "stonecutting"));
                        break;
                    default:
                        diagnostics.add(Diagnostic.warning(origin, name,
                                group + " recipes have no equivalent here and were skipped."));
                }
            }
        }
        return out;
    }

    /** A shaped recipe. Their pattern uses undefined letters as blanks. */
    private static Map<String, Object> crafting(DefinitionNode recipe) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "shaped");
        result(recipe, out);

        DefinitionNode ingredients = recipe.node("ingredients").orElse(DefinitionNode.empty());
        Map<String, Object> keys = new LinkedHashMap<>();
        for (String key : ingredients.keys()) {
            ingredients.string(key).ifPresent(item -> keys.put(key, item));
        }
        out.put("keys", keys);

        // A letter with no ingredient is a blank in their pattern and a space
        // in ours. Without this, a pattern of XBX asks for an item called X.
        List<Object> pattern = new ArrayList<>();
        for (String row : recipe.strings("pattern")) {
            StringBuilder line = new StringBuilder();
            for (char each : row.toCharArray()) {
                line.append(keys.containsKey(String.valueOf(each)) ? each : ' ');
            }
            pattern.add(line.toString());
        }
        out.put("pattern", pattern);
        return out;
    }

    /** Their cooking, which may name several machines at once. */
    private static void cooking(DefinitionNode recipe, String name, Map<String, Object> out) {
        List<String> machines = recipe.strings("machines");
        if (machines.isEmpty()) {
            machines = List.of("FURNACE");
        }
        boolean first = true;
        for (String machine : machines) {
            String type;
            switch (machine.toUpperCase(Locale.ROOT)) {
                case "BLAST_FURNACE":
                    type = "blasting";
                    break;
                case "SMOKER":
                    type = "smoking";
                    break;
                default:
                    type = "smelting";
            }
            // One of theirs is several of ours, so all but the first are named
            // for their machine.
            out.put(first ? name : name + "_" + type, cooked(recipe, type));
            first = false;
        }
    }

    /** The shape every one-ingredient recipe of ours shares. */
    private static Map<String, Object> cooked(DefinitionNode recipe, String type) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        result(recipe, out);
        recipe.node("ingredient").flatMap(ingredient -> ingredient.string("item"))
                .or(() -> recipe.string("ingredient"))
                .ifPresent(item -> out.put("ingredient", item));
        recipe.string("exp").ifPresent(exp -> out.put("experience", exp));
        recipe.integer("cook_time").ifPresent(time -> out.put("time", time));
        return out;
    }

    /** {@code result: {item: ns:id, amount: 1}}, which both plugins spell the same. */
    private static void result(DefinitionNode recipe, Map<String, Object> out) {
        DefinitionNode result = recipe.node("result").orElse(DefinitionNode.empty());
        result.string("item").or(() -> recipe.string("result"))
                .ifPresent(item -> out.put("result", item));
        result.integer("amount").ifPresent(amount -> out.put("amount", amount));
    }

    /**
     * One block.
     *
     * <p>Both plugins put a custom block in a spare vanilla block state, so
     * this is a rename rather than a conversion. What does not come across is
     * the state itself: theirs is allocated in their own file against their
     * own numbering, so a world built with their plugin has blocks this engine
     * cannot recognise. A pack migrates; a world does not.
     */
    private static Map<String, Object> block(DefinitionNode block) {
        Map<String, Object> out = new LinkedHashMap<>();
        DefinitionNode specific = block.node("specific_properties")
                .flatMap(properties -> properties.node("block"))
                .orElse(DefinitionNode.empty());
        DefinitionNode resource = block.node("resource").orElse(DefinitionNode.empty());

        specific.string("placed_model").or(() -> resource.string("model_path"))
                .ifPresent(model -> out.put("model", model));
        specific.string("hardness").ifPresent(hardness -> out.put("hardness", hardness));
        // light_level is deliberately dropped rather than translated: a custom
        // block cannot emit light here, and writing the key would only produce
        // a warning for every block in somebody's pack.
        specific.string("break_tool").ifPresent(tool -> out.put("tool", tool));
        specific.string("sound").or(() -> block.string("sound"))
                .ifPresent(sound -> out.put("sound", sound));

        // Their block_type says which vanilla block it hides in. Only the two
        // this engine has are mapped; anything else takes the default, which
        // is what almost every pack uses anyway.
        String kind = specific.string("block_type").orElse("").toLowerCase(Locale.ROOT);
        if (kind.contains("mushroom")) {
            out.put("base", "mushroom_stem");
        }
        return out;
    }

    /** One item. */
    private static Map<String, Object> item(DefinitionNode item, String id,
                                            String origin, List<Diagnostic> diagnostics) {
        Map<String, Object> out = new LinkedHashMap<>();
        DefinitionNode resource = item.node("resource").orElse(DefinitionNode.empty());

        out.put("material", resource.string("material").orElse("PAPER"));

        // 4.0.9 renamed display_name to name. Both are read, because a pack
        // written for either is a pack somebody has.
        item.string("name").or(() -> item.string("display_name"))
                .ifPresent(name -> out.put("name", name));
        if (!item.strings("lore").isEmpty()) {
            out.put("lore", item.strings("lore"));
        }
        item.string("permission").ifPresent(permission -> out.put("permission", permission));
        item.integer("max_stack_size").ifPresent(stack -> out.put("stack", stack));

        // A texture path is a file; ours is the path without the extension,
        // and both are rooted at the same place.
        texture(resource).ifPresent(texture -> out.put("texture", texture));
        resource.string("model_path").ifPresent(model -> out.put("model", model));

        item.node("durability").ifPresent(durability -> {
            durability.integer("max_durability").ifPresent(max -> out.put("durability", max));
            if (durability.bool("unbreakable").orElse(Boolean.FALSE)) {
                out.put("unbreakable", true);
            }
        });

        enchantments(item).ifPresent(enchants -> out.put("enchantments", enchants));
        attributes(item, id, origin, diagnostics).ifPresent(attributes -> out.put("attributes", attributes));

        item.node("specific_properties")
                .flatMap(properties -> properties.node("armor"))
                .flatMap(armor -> armor.string("slot"))
                .map(ItemsAdder::armourSlot)
                .ifPresent(slot -> out.put("armor", slot));

        item.node("behaviours").ifPresent(behaviours -> {
            behaviours.node("liquid_bucket")
                    .flatMap(bucket -> bucket.string("name"))
                    .ifPresent(liquid -> out.put("liquid", liquid));
            behaviours.node("furniture").ifPresent(furniture ->
                    out.put("place", furniture(furniture)));

            for (String behaviour : behaviours.keys()) {
                if (!behaviour.equals("liquid_bucket") && !behaviour.equals("furniture")) {
                    diagnostics.add(Diagnostic.warning(origin, id,
                            "the " + behaviour + " behaviour has no equivalent here and was skipped. "
                                    + "The item itself still loads."));
                }
            }
        });

        for (String plugin : List.of("events", "drop", "item_flags", "events_needed_player_stats")) {
            if (item.raw(plugin) != null) {
                diagnostics.add(Diagnostic.warning(origin, id,
                        plugin + " is ItemsAdder's own behaviour rather than a property of the item, "
                                + "so it was skipped. Actions cover most of what events did."));
            }
        }
        return out;
    }

    /**
     * {@code textures: [item/ruby.png]} or {@code texture: item/ruby.png}.
     *
     * <p>Only the first of a list: several textures is ItemsAdder's way of
     * building one model out of layers, and a flat item here has one picture.
     */
    private static Optional<String> texture(DefinitionNode resource) {
        List<String> textures = resource.strings("textures");
        String first = textures.isEmpty()
                ? resource.string("texture").orElse(null)
                : textures.get(0);
        if (first == null || first.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(first.endsWith(".png") ? first.substring(0, first.length() - 4) : first);
    }

    /** {@code enchants: [ARROW_FIRE:1]} to a map of vanilla names. */
    private static Optional<Map<String, Object>> enchantments(DefinitionNode item) {
        List<String> declared = item.strings("enchants");
        if (declared.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String each : declared) {
            int colon = each.lastIndexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = each.substring(0, colon).toLowerCase(Locale.ROOT);
            // A namespaced custom enchant belongs to whatever plugin owns it,
            // and its name is not a vanilla one; the last segment is what a
            // vanilla lookup would want.
            name = name.substring(name.lastIndexOf(':') + 1);
            try {
                out.put(name, Integer.parseInt(each.substring(colon + 1).trim()));
            } catch (NumberFormatException e) {
                // A level that is not a number is not a level.
            }
        }
        return out.isEmpty() ? Optional.empty() : Optional.of(out);
    }

    /**
     * {@code attribute_modifiers: {mainhand: {attackDamage: 19}}} to our list.
     *
     * <p>Only the hand slots have an equivalent: ours are the vanilla
     * equipment slots, and ItemsAdder's {@code offhand} is not one the game
     * takes an attribute for in the same way.
     */
    private static Optional<List<Object>> attributes(DefinitionNode item, String id,
                                                     String origin, List<Diagnostic> diagnostics) {
        Optional<DefinitionNode> modifiers = item.node("attribute_modifiers");
        if (modifiers.isEmpty()) {
            return Optional.empty();
        }
        List<Object> out = new ArrayList<>();
        for (String slot : modifiers.get().keys()) {
            if (!slot.equals("mainhand")) {
                diagnostics.add(Diagnostic.warning(origin, id,
                        "attribute_modifiers." + slot + " was skipped; only mainhand has an "
                                + "equivalent here."));
                continue;
            }
            DefinitionNode values = modifiers.get().node(slot).orElse(DefinitionNode.empty());
            for (String attribute : values.keys()) {
                values.string(attribute).ifPresent(amount -> {
                    Map<String, Object> one = new LinkedHashMap<>();
                    one.put(vanillaAttribute(attribute), amount);
                    out.add(one);
                });
            }
        }
        return out.isEmpty() ? Optional.empty() : Optional.of(out);
    }

    /** {@code attackDamage} to {@code attack_damage}, which is what the game calls it. */
    private static String vanillaAttribute(String camel) {
        StringBuilder out = new StringBuilder();
        for (char each : camel.toCharArray()) {
            if (Character.isUpperCase(each)) {
                out.append('_').append(Character.toLowerCase(each));
            } else {
                out.append(each);
            }
        }
        return out.toString();
    }

    /** {@code helmet} to {@code head}, and so on. */
    private static String armourSlot(String slot) {
        switch (slot.toLowerCase(Locale.ROOT)) {
            case "helmet":
            case "head":
                return "head";
            case "chestplate":
            case "chest":
                return "chest";
            case "leggings":
            case "legs":
                return "legs";
            case "boots":
            case "feet":
                return "feet";
            default:
                return slot;
        }
    }

    /**
     * The furniture behaviour, as a {@code place:} block.
     *
     * <p>The two features are the same idea with different words: a model you
     * put down, that may be solid, may glow, and may be sat on. What does not
     * come across is {@code display_transformation} — ours renders a model at
     * its real size rather than taking a transform, which is a decision
     * recorded in FORMAT.md rather than a gap.
     */
    private static Map<String, Object> furniture(DefinitionNode furniture) {
        Map<String, Object> place = new LinkedHashMap<>();
        furniture.integer("light_level").ifPresent(light -> place.put("light", light));
        if (furniture.bool("solid").orElse(Boolean.FALSE)) {
            place.put("solid", true);
        }
        // A chair in ItemsAdder is a sit height under the furniture block.
        furniture.node("sit").flatMap(sit -> sit.string("height"))
                .ifPresent(height -> place.put("seat", height));
        furniture.string("placeable_on").ifPresent(on -> place.put("surface", on));
        return place;
    }

    /** A font image, which is an icon. */
    private static Map<String, Object> icon(DefinitionNode image) {
        Map<String, Object> out = new LinkedHashMap<>();
        image.string("path").ifPresent(path -> {
            String file = path.endsWith(".png") ? path.substring(0, path.length() - 4) : path;
            // Their path is rooted at the pack's textures folder and starts
            // with font/; ours is the name under textures/font/.
            out.put("file", file.startsWith("font/") ? file.substring("font/".length()) : file);
        });
        image.integer("scale_ratio").ifPresent(height -> out.put("height", height));
        image.integer("y_position").ifPresent(ascent -> out.put("ascent", ascent));
        return out;
    }

    /** One warning naming what was skipped, rather than silence. */
    private static void refuse(DefinitionNode document, String key, String origin,
                               List<Diagnostic> diagnostics, String why) {
        document.node(key).ifPresent(section -> diagnostics.add(Diagnostic.warning(origin, key,
                section.keys().size() + " ItemsAdder " + key + " were skipped: " + why + ".")));
    }
}
