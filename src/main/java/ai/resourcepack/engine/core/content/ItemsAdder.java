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
 * <p>Items, blocks, font images and sounds ({@link ItemsAdderSound})
 * translate, including the parts of an item
 * that are really vanilla underneath: material, name, lore, enchants,
 * attributes, durability, stack size, permission, armour slot, item flags, and
 * the behaviours that have an equivalent here — a liquid bucket, furniture and
 * a block. Their {@code events} become actions (see {@link ItemsAdderEvents}),
 * and a block's loot table its drop when the table is one certain item.
 *
 * <p>What does not: events and actions with no trigger or step here, loot by
 * chance, and the recipe kinds this engine has no equivalent for, each named
 * as it is skipped.
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
                || document.node("entities").isPresent()
                || document.raw("sounds") instanceof Map
                || document.node("minecraft_lang_overwrite").isPresent()
                || !armours(document).isEmpty()
                || document.node("recipes").isPresent()
                || document.node("loots").isPresent());
    }

    /**
     * What one file of a pack may need from another: armour sets, which an
     * item names by id, and the English text of {@code minecraft_lang_overwrite},
     * which a sound's subtitle names by key, and what each block drops, which
     * their {@code loots.blocks} tables usually say in a file of their own
     * (gathered by the loader through {@link #blockDrops}). Gathered from every file before
     * any is translated, because a pack is free to keep either in a file of
     * its own.
     */
    static final class Shared {
        final Map<String, DefinitionNode> armours = new LinkedHashMap<>();
        final Map<String, String> lang = new LinkedHashMap<>();
        final Map<String, String> drops = new LinkedHashMap<>();

        Shared add(DefinitionNode document) {
            armours(document).forEach(armours::putIfAbsent);
            ItemsAdderSound.lang(document).forEach(lang::putIfAbsent);
            return this;
        }
    }

    /**
     * The armour art a document declares, by name: {@code equipments:} (4.0.10
     * and up), and the older {@code armors_rendering:}, renamed
     * {@code legacy_armor_renderings:} in 4.0.9. All three give a set its
     * {@code layer_1} and {@code layer_2}, which is all RP Engine needs of one.
     *
     * <p>Gathered from every file in a pack before any item is read, because
     * an item names its set by id and a pack is free to keep its armour in
     * one file and its items in another.
     */
    static Map<String, DefinitionNode> armours(DefinitionNode document) {
        Map<String, DefinitionNode> out = new LinkedHashMap<>();
        for (String section : List.of("equipments", "legacy_armor_renderings", "armors_rendering")) {
            document.node(section).ifPresent(sets -> {
                for (String name : sets.keys()) {
                    sets.node(name).ifPresent(set -> out.putIfAbsent(name, set));
                }
            });
        }
        return out;
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
        Shared shared = new Shared().add(document);
        shared.drops.putAll(blockDrops(document, namespace, origin, diagnostics));
        return translate(document, namespace, origin, diagnostics, shared);
    }

    /** @param shared what the whole pack declares; see {@link Shared} */
    static Map<ContentKind, Map<String, Object>> translate(
            DefinitionNode document, String namespace, String origin, List<Diagnostic> diagnostics,
            Shared shared) {
        Map<ContentKind, Map<String, Object>> out = new LinkedHashMap<>();
        Map<String, DefinitionNode> armours = shared.armours;
        Map<String, String> drops = shared.drops;
        // Their blocks are items with a block behaviour, so they come out of
        // items: and go in beside any written under blocks:.
        Map<String, Object> blocksFromItems = new LinkedHashMap<>();

        document.node("items").ifPresent(items -> {
            Map<String, Object> translated = new LinkedHashMap<>();
            for (String id : items.keys()) {
                items.node(id).ifPresent(item -> {
                    if (!item.bool("enabled").orElse(Boolean.TRUE)) {
                        return;
                    }
                    if (blockBehaviour(item).isPresent() && isDirectionalFace(items, id)) {
                        // One face of another block's directional set: drawn
                        // as part of that block, not placed as one of its own.
                        return;
                    }
                    if (blockBehaviour(item).isPresent()) {
                        Map<String, Object> block = blockItem(item, id, namespace, drops, origin, diagnostics);
                        directionalFaces(items, item, id, namespace, block);
                        blocksFromItems.put(id, block);
                    } else {
                        Map<String, Object> body = item(item, id, origin, diagnostics);
                        armour(item, id, origin, diagnostics, body, armours);
                        item.node("events").ifPresent(events -> ItemsAdderEvents.translate(events, id, namespace,
                                body.containsKey("place"), origin, diagnostics).into(body));
                        translated.put(id, body);
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

        Map<String, Object> translatedBlocks = new LinkedHashMap<>(blocksFromItems);
        document.node("blocks").ifPresent(blocks -> {
            for (String id : blocks.keys()) {
                blocks.node(id).ifPresent(block -> {
                    if (block.bool("enabled").orElse(Boolean.TRUE)) {
                        Map<String, Object> body = block(block);
                        drop(id, drops, body);
                        translatedBlocks.put(id, body);
                    }
                });
            }
        });
        if (!translatedBlocks.isEmpty()) {
            out.put(ContentKind.BLOCK, translatedBlocks);
        }
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
            Map<String, Object> translated = recipes(recipes, namespace, origin, diagnostics);
            if (!translated.isEmpty()) {
                out.put(ContentKind.RECIPE, translated);
            }
        });

        if (document.raw("sounds") instanceof Map) {
            Map<String, Object> translated = ItemsAdderSound.translate(
                    document.node("sounds").orElse(DefinitionNode.empty()), namespace, origin, diagnostics,
                    shared.lang);
            if (!translated.isEmpty()) {
                out.put(ContentKind.SOUND, translated);
            }
        }

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
     *
     * <p>{@code smithing}, {@code anvil_repair} and {@code brewing} (the last
     * is ItemsAdderAdditions', an add-on, in the same file) are named slots
     * rather than a machine list, and each is one of ours.
     */
    private static Map<String, Object> recipes(DefinitionNode recipes, String namespace, String origin,
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
                        out.put(name, crafting(recipe, namespace));
                        break;
                    case "cooking":
                        cooking(recipe, name, namespace, out);
                        break;
                    case "campfire_cooking":
                        out.put(name, cooked(recipe, "campfire", namespace));
                        break;
                    case "stonecutter":
                        out.put(name, cooked(recipe, "stonecutting", namespace));
                        break;
                    case "smithing":
                        out.put(name, smithing(recipe, namespace));
                        break;
                    case "anvil_repair":
                        out.put(name, anvilRepair(recipe, namespace));
                        break;
                    case "brewing":
                        out.put(name, brewing(recipe, name, namespace, origin, diagnostics));
                        break;
                    default:
                        diagnostics.add(Diagnostic.warning(origin, name,
                                group + " recipes have no equivalent here and were skipped."));
                }
            }
        }
        return out;
    }

    /** {@code template}, {@code base} and {@code addition}, each one item, and a result. */
    private static Map<String, Object> smithing(DefinitionNode recipe, String namespace) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "smithing");
        for (String slot : List.of("template", "base", "addition")) {
            slotItem(recipe, slot).ifPresent(item -> out.put(slot, reference(item, namespace)));
        }
        result(recipe, namespace, out);
        return out;
    }

    /**
     * {@code item} mended with {@code ingredient} at an anvil. Theirs repairs
     * the way vanilla repairs with a material, a quarter of full durability
     * for each one used, which is what ours does with {@code repair: 25%}.
     */
    private static Map<String, Object> anvilRepair(DefinitionNode recipe, String namespace) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "anvil");
        slotItem(recipe, "item").ifPresent(item -> out.put("base", reference(item, namespace)));
        slotItem(recipe, "ingredient").ifPresent(item -> out.put("addition", reference(item, namespace)));
        out.put("repair", "25%");
        return out;
    }

    /**
     * ItemsAdderAdditions' brewing: {@code base} (once {@code input}) in the
     * bottle slots, {@code ingredient} on top.
     */
    private static Map<String, Object> brewing(DefinitionNode recipe, String name, String namespace,
                                               String origin, List<Diagnostic> diagnostics) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "brewing");
        slotItem(recipe, "base").or(() -> slotItem(recipe, "input"))
                .ifPresent(item -> out.put("base", reference(item, namespace)));
        slotItem(recipe, "ingredient").ifPresent(item -> out.put("ingredient", reference(item, namespace)));
        result(recipe, namespace, out);
        List<String> skipped = new ArrayList<>();
        if (recipe.node("ingredient").flatMap(ingredient -> ingredient.integer("consume")).orElse(1) > 1) {
            skipped.add("ingredient.consume (a brewing stand uses one)");
        }
        for (String key : List.of("brew_time", "fuel_cost", "on_complete")) {
            if (recipe.has(key)) skipped.add(key);
        }
        if (!skipped.isEmpty()) {
            diagnostics.add(Diagnostic.warning(origin, name,
                    String.join(", ", skipped) + " have no RP Engine equivalent and were skipped: it brews as "
                            + "vanilla brewing does. The recipe still works."));
        }
        return out;
    }

    /** A slot written as {@code slot: ID} or {@code slot: {item: ID}}; both spellings are theirs. */
    private static Optional<String> slotItem(DefinitionNode recipe, String slot) {
        return recipe.node(slot).flatMap(node -> node.string("item")).or(() -> recipe.string(slot));
    }

    /**
     * One of their item references as ours. An id with no namespace is this
     * file's, as theirs reads it, and anything with a capital letter in it is
     * a vanilla material. {@code minecraft:awkward_potion} is how their
     * brewing add-on names a vanilla potion, which is {@code potion/awkward}
     * here.
     */
    static String reference(String raw, String namespace) {
        String id = raw.trim();
        if (id.startsWith("minecraft:")) {
            String name = id.substring("minecraft:".length()).toLowerCase(Locale.ROOT);
            if (name.equals("water_bottle")) {
                return "potion/water";
            }
            for (String kind : List.of("splash_potion", "lingering_potion", "potion")) {
                if (name.endsWith("_" + kind)) {
                    return kind + "/" + name.substring(0, name.length() - kind.length() - 1);
                }
            }
            return id;
        }
        if (id.indexOf(':') >= 0 || !id.equals(id.toLowerCase(Locale.ROOT))) {
            return id;
        }
        return namespace + ":" + id;
    }

    /** A shaped recipe. Their pattern uses undefined letters as blanks. */
    private static Map<String, Object> crafting(DefinitionNode recipe, String namespace) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "shaped");
        result(recipe, namespace, out);

        DefinitionNode ingredients = recipe.node("ingredients").orElse(DefinitionNode.empty());
        Map<String, Object> keys = new LinkedHashMap<>();
        for (String key : ingredients.keys()) {
            ingredients.string(key).ifPresent(item -> keys.put(key, reference(item, namespace)));
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
    private static void cooking(DefinitionNode recipe, String name, String namespace, Map<String, Object> out) {
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
            out.put(first ? name : name + "_" + type, cooked(recipe, type, namespace));
            first = false;
        }
    }

    /** The shape every one-ingredient recipe of ours shares. */
    private static Map<String, Object> cooked(DefinitionNode recipe, String type, String namespace) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        result(recipe, namespace, out);
        slotItem(recipe, "ingredient").ifPresent(item -> out.put("ingredient", reference(item, namespace)));
        recipe.string("exp").ifPresent(exp -> out.put("experience", exp));
        recipe.integer("cook_time").ifPresent(time -> out.put("time", time));
        return out;
    }

    /** {@code result: {item: ns:id, amount: 1}}, which both plugins spell the same. */
    private static void result(DefinitionNode recipe, String namespace, Map<String, Object> out) {
        DefinitionNode result = recipe.node("result").orElse(DefinitionNode.empty());
        result.string("item").or(() -> recipe.string("result"))
                .ifPresent(item -> out.put("result", reference(item, namespace)));
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
        DefinitionNode specific = blockBehaviour(block).orElse(DefinitionNode.empty());
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

    /**
     * Where their block settings live: {@code behaviours.block} now, and
     * {@code specific_properties.block} before 4.0.
     */
    private static Optional<DefinitionNode> blockBehaviour(DefinitionNode item) {
        return item.node("behaviours").flatMap(behaviours -> behaviours.node("block"))
                .or(() -> item.node("specific_properties").flatMap(properties -> properties.node("block")));
    }

    /**
     * An item with a block behaviour, which is how ItemsAdder writes a block.
     *
     * <p>The id is the block's here as well, and the item that places it
     * comes with it, so its name and lore ride along. Their placed-block
     * events become the block's own actions, beside the item's.
     */
    private static Map<String, Object> blockItem(DefinitionNode item, String id, String namespace,
                                                 Map<String, String> drops, String origin,
                                                 List<Diagnostic> diagnostics) {
        Map<String, Object> out = block(item);
        DefinitionNode specific = blockBehaviour(item).orElse(DefinitionNode.empty());
        DefinitionNode resource = item.node("resource").orElse(DefinitionNode.empty());

        item.string("name").or(() -> item.string("display_name")).ifPresent(name -> out.put("name", name));
        if (!item.strings("lore").isEmpty()) {
            out.put("lore", item.strings("lore"));
        }

        // placed_model is a block of settings since 3.x; its type is the
        // vanilla block the state hides in.
        String type = specific.node("placed_model").flatMap(model -> model.string("type")).orElse("REAL_NOTE")
                .toUpperCase(Locale.ROOT);
        switch (type) {
            case "REAL":
                out.put("base", "mushroom_stem");
                break;
            case "REAL_WIRE":
                // Tripwire, as theirs is: a plant.
                out.put("base", "tripwire");
                break;
            case "REAL_TRANSPARENT":
                // Chorus there; the see-through full block here, which needs
                // no Paper setting and repaints nothing in the End.
                out.put("shape", "grate");
                break;
            case "REAL_NOTE":
                break;
            default:
                diagnostics.add(Diagnostic.warning(origin, id,
                        "placed_model.type " + type + " draws the block with an entity (a spawner's, or fire's), "
                                + "which RP Engine blocks do not; it came across as a full block. A placed model "
                                + "draws any shape."));
        }
        directional(specific, out);
        variants(specific, namespace, out);
        if (specific.bool("no_explosion").orElse(Boolean.FALSE)) {
            out.put("blast-resistant", true);
        }
        if (specific.integer("light_level").orElse(0) > 0) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "light_level: a custom block's light belongs to the vanilla block underneath, and a note block "
                            + "gives none, so it was skipped. shape: bulb gives light, switched by redstone; a placed "
                            + "model can give any level."));
        }
        // A block whose click swaps it for another (ItemsAdder's on/off pairs)
        // is the block's own click-into: here.
        item.node("events").flatMap(events -> events.node("placed_block"))
                .flatMap(placed -> placed.node("interact")).flatMap(interact -> interact.node("replace_block"))
                .flatMap(replace -> replace.string("to"))
                .ifPresent(to -> out.put("click-into", to.contains(":") ? to : namespace + ":" + to));

        if (!out.containsKey("model")) {
            generatedCube(resource, namespace).ifPresent(model -> out.put("model", model));
        }
        for (String tool : specific.strings("break_tools_whitelist")) {
            String kind = toolKind(tool);
            if (kind != null) {
                out.put("tool", kind);
                break;
            }
        }
        if (!specific.strings("break_tools_blacklist").isEmpty()) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "break_tools_blacklist has no RP Engine equivalent: a block asks for the kind of tool that "
                            + "gets the drop, and nothing else."));
        }
        specific.node("sound").flatMap(sound -> sound.node("place")).flatMap(place -> place.string("name"))
                .ifPresent(sound -> out.put("sound", sound));
        if (Boolean.FALSE.equals(specific.bool("drop_when_mined").orElse(Boolean.TRUE))) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "drop_when_mined: false has no RP Engine equivalent; mined with the right tool, it drops "
                            + (drops.containsKey(id) ? drops.get(id) : "itself") + "."));
        }
        drop(id, drops, out);

        item.node("events").ifPresent(events ->
                ItemsAdderEvents.translate(events, id, namespace, true, origin, diagnostics).into(out));
        for (String plugin : List.of("enchants", "attribute_modifiers", "durability", "permission")) {
            if (item.raw(plugin) != null) {
                diagnostics.add(Diagnostic.warning(origin, id,
                        plugin + " is on a block's item, and the item an RP Engine block is placed from carries "
                                + "only its name and lore, so it was skipped."));
            }
        }
        return out;
    }

    /** The faces a directional block may name a block of its own for. */
    private static final List<String> FACES = List.of("north", "east", "south", "west", "up", "down");

    /**
     * {@code directional_mode}: turned as a log ({@code LOG}), a furnace
     * ({@code FURNACE}) or a dropper ({@code DROPPER}, and {@code ALL}, which
     * ItemsAdder describes as both a log and a dropper; the six directions
     * cover both).
     */
    private static void directional(DefinitionNode specific, Map<String, Object> out) {
        String mode = specific.node("placed_model").flatMap(model -> model.string("directional_mode"))
                .or(() -> specific.string("directional_mode")).orElse("NONE").trim().toUpperCase(Locale.ROOT);
        switch (mode) {
            case "LOG":
                out.put("rotate", "axis");
                break;
            case "FURNACE":
                out.put("rotate", "horizontal");
                break;
            case "DROPPER":
            case "ALL":
                out.put("rotate", "all");
                break;
            default:
        }
    }

    /** Whether {@code id} is {@code <block>_<face>}, a face of a directional block in the same file. */
    private static boolean isDirectionalFace(DefinitionNode items, String id) {
        int underscore = id.lastIndexOf('_');
        if (underscore <= 0 || !FACES.contains(id.substring(underscore + 1))) {
            return false;
        }
        Optional<DefinitionNode> owner = items.node(id.substring(0, underscore));
        return owner.flatMap(ItemsAdder::blockBehaviour)
                .flatMap(specific -> specific.node("placed_model").flatMap(model -> model.string("directional_mode"))
                        .or(() -> specific.string("directional_mode")))
                .filter(mode -> !mode.equalsIgnoreCase("NONE")).isPresent();
    }

    /** A directional block's {@code <id>_<face>} items, as the model each direction wears. */
    private static void directionalFaces(DefinitionNode items, DefinitionNode item, String id, String namespace,
                                         Map<String, Object> out) {
        if (!out.containsKey("rotate") || out.get("rotate").equals("axis")) {
            return;
        }
        Map<String, Object> appearances = new LinkedHashMap<>();
        for (String face : FACES) {
            items.node(id + "_" + face).ifPresent(sibling -> {
                Object model = block(sibling).get("model");
                if (model == null) {
                    model = generatedCube(sibling.node("resource").orElse(DefinitionNode.empty()), namespace)
                            .orElse(null);
                }
                if (model != null) {
                    appearances.put("facing=" + face, model);
                }
            });
        }
        if (!appearances.isEmpty()) {
            out.put("appearances", appearances);
        }
    }

    /** {@code custom_variants}: a look picked at random as each is placed. */
    private static void variants(DefinitionNode specific, String namespace, Map<String, Object> out) {
        DefinitionNode declared = specific.node("custom_variants").orElse(null);
        if (declared == null) {
            return;
        }
        List<Object> picks = new ArrayList<>();
        for (String name : declared.keys()) {
            declared.node(name).ifPresent(variant -> {
                Map<String, Object> pick = new LinkedHashMap<>();
                variant.string("model").ifPresent(model -> pick.put("model", model));
                variant.integer("x").ifPresent(x -> pick.put("x", x));
                variant.integer("y").ifPresent(y -> pick.put("y", y));
                variant.bool("uvlock").ifPresent(uvlock -> pick.put("uvlock", uvlock));
                // A weight is a share of the draw; repeating a pick is the same share.
                int weight = Math.max(1, Math.min(8, variant.integer("weight").orElse(1)));
                for (int i = 0; i < weight; i++) {
                    picks.add(pick);
                }
            });
        }
        if (picks.size() > 1) {
            out.put("random", picks);
        }
    }

    /** The drop its loot table names, if one was found for it. */
    private static void drop(String id, Map<String, String> drops, Map<String, Object> out) {
        String dropped = drops.get(id);
        if (dropped != null) {
            out.put("drop", dropped);
        }
    }

    /**
     * The cube ItemsAdder generates from a block's textures, written inline.
     *
     * <p>One texture is every face. Six are the faces in ItemsAdder's order,
     * which is the faces' names in alphabetical order: down, east, north,
     * south, up, west.
     */
    private static Optional<Map<String, Object>> generatedCube(DefinitionNode resource, String namespace) {
        List<String> textures = new ArrayList<>();
        for (String texture : resource.strings("textures")) {
            String path = texture.endsWith(".png") ? texture.substring(0, texture.length() - 4) : texture;
            textures.add(path.contains(":") ? path : namespace + ":" + path);
        }
        if (textures.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> model = new LinkedHashMap<>();
        Map<String, Object> faces = new LinkedHashMap<>();
        if (textures.size() < 6 || textures.stream().distinct().count() == 1) {
            model.put("parent", "minecraft:block/cube_all");
            faces.put("all", textures.get(0));
        } else {
            model.put("parent", "minecraft:block/cube");
            String[] order = {"down", "east", "north", "south", "up", "west"};
            for (int i = 0; i < order.length; i++) {
                faces.put(order[i], textures.get(i));
            }
            faces.put("particle", textures.get(2));
        }
        model.put("textures", faces);
        return Optional.of(model);
    }

    /** {@code DIAMOND_PICKAXE} or {@code PICKAXE} to {@code pickaxe}, how a block asks for a tool here. */
    private static String toolKind(String tool) {
        String name = tool.toLowerCase(Locale.ROOT);
        name = name.substring(name.lastIndexOf(':') + 1);
        for (String kind : List.of("pickaxe", "shovel", "hoe", "sword", "axe")) {
            if (name.equals(kind) || name.endsWith("_" + kind)) return kind;
        }
        return null;
    }

    /**
     * What each block drops, from a file's {@code loots.blocks}.
     *
     * <p>Theirs is a table of items, each with a chance and an amount; ours is
     * the one item a block gives back. So a table whose first item is certain
     * comes across as that item, and anything with chance in it stays the
     * block's own drop, said so. Mob and fishing loot have no equivalent.
     *
     * @return block id path to the content id it drops
     */
    static Map<String, String> blockDrops(DefinitionNode document, String namespace, String origin,
                                          List<Diagnostic> diagnostics) {
        Map<String, String> out = new LinkedHashMap<>();
        DefinitionNode loots = document.node("loots").orElse(null);
        if (loots == null) {
            return out;
        }
        for (String group : loots.keys()) {
            if (!group.equals("blocks")) {
                diagnostics.add(Diagnostic.warning(origin, "loots." + group,
                        "ItemsAdder " + group + " loot has no RP Engine equivalent and was skipped."));
            }
        }
        DefinitionNode blocks = loots.node("blocks").orElse(DefinitionNode.empty());
        for (String name : blocks.keys()) {
            DefinitionNode loot = blocks.node(name).orElse(DefinitionNode.empty());
            if (!loot.bool("enabled").orElse(Boolean.TRUE)) continue;
            String type = loot.string("type").orElse("");
            int colon = type.indexOf(':');
            String block = colon < 0 ? type : type.substring(colon + 1);
            if (block.isEmpty() || (colon >= 0 && !type.substring(0, colon).equals(namespace))) {
                diagnostics.add(Diagnostic.warning(origin, "loots.blocks." + name,
                        "is for " + type + ", which is not a block of this pack, so it was skipped."));
                continue;
            }
            DefinitionNode items = loot.node("items").orElse(DefinitionNode.empty());
            List<String> entries = new ArrayList<>(items.keys());
            DefinitionNode first = entries.isEmpty() ? DefinitionNode.empty()
                    : items.node(entries.get(0)).orElse(DefinitionNode.empty());
            String item = first.string("item").orElse(null);
            if (item == null || item.startsWith("minecraft:")
                    || (!item.contains(":") && item.equals(item.toUpperCase(Locale.ROOT)))) {
                diagnostics.add(Diagnostic.warning(origin, "loots.blocks." + name,
                        "drops " + (item == null ? "nothing it names" : item) + ", and an RP Engine block's drop is "
                                + "one of the pack's own items, so " + block + " drops itself."));
                continue;
            }
            if (first.decimal("chance").orElse(100d) < 100d) {
                diagnostics.add(Diagnostic.warning(origin, "loots.blocks." + name,
                        "drops " + item + " by chance, and an RP Engine block always gives back one thing, so "
                                + block + " drops itself."));
                continue;
            }
            if (entries.size() > 1 || first.integer("max_amount").orElse(1) > 1
                    || first.integer("min_amount").orElse(1) > 1) {
                diagnostics.add(Diagnostic.warning(origin, "loots.blocks." + name,
                        "drops more than one " + item + " or more than one item. RP Engine gives back one, so "
                                + block + " drops a single " + item + "."));
            }
            out.put(block, item.contains(":") ? item : namespace + ":" + item);
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

        if (!item.strings("item_flags").isEmpty()) {
            out.put("flags", item.strings("item_flags"));
        }
        for (String plugin : List.of("drop", "events_needed_player_stats")) {
            if (item.raw(plugin) != null) {
                diagnostics.add(Diagnostic.warning(origin, id,
                        plugin + " is ItemsAdder's own behaviour rather than a property of the item, "
                                + "so it was skipped."));
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

    /**
     * Custom armour: which slot, and the layer art it is worn with, as
     * {@code armor} and {@code armor-art}.
     *
     * <p>Two spellings. The current one is {@code equipment: {id: ns:set}} on
     * the item, the slot following its material unless {@code slot} says
     * otherwise; the older one is {@code specific_properties.armor} with a
     * {@code slot} and a {@code custom_armor: set}. Either names a set from
     * {@link #armours}, whose {@code layer_1} is the body and boots and whose
     * {@code layer_2} is the leggings - exactly the two sheets vanilla's
     * {@code humanoid} and {@code humanoid_leggings} layers are, so the PNG is
     * used as it is and only has to be served where the game reads it.
     *
     * <p>What does not come across, each a warning: a layer's animation
     * (a strip of frames a worn layer cannot play) and emissive layer (which
     * needed ItemsAdder's shaders), a {@code use_color} tint, and colour-only
     * armour, which has no art to carry and is worn as plain leather.
     */
    private static void armour(DefinitionNode item, String id, String origin, List<Diagnostic> diagnostics,
                               Map<String, Object> out, Map<String, DefinitionNode> armours) {
        String material = item.string("material")
                .or(() -> item.node("resource").flatMap(resource -> resource.string("material")))
                .orElse("").trim().toUpperCase(Locale.ROOT);
        Optional<DefinitionNode> equipment = item.node("equipment");
        if (equipment.isPresent()) {
            String declared = equipment.get().string("slot").orElse(null);
            String slot = declared == null ? materialSlot(material) : armourSlot(declared);
            if (slot == null || !List.of("head", "chest", "legs", "feet").contains(slot)) {
                diagnostics.add(Diagnostic.warning(origin, id,
                        "is equipped in " + (declared == null ? "the slot of its material" : declared)
                                + ", which is not head, chest, legs or feet, the slots RP Engine armour is worn in, "
                                + "so it was skipped."));
                return;
            }
            Optional<String> set = equipment.get().string("id");
            if (set.isEmpty()) {
                // No art of its own. On the head that is ItemsAdder's 3D
                // helmet, which shows its model there whatever its material
                // (their wiki builds one on IRON_HELMET) - a hat here.
                // Anywhere else it is real armour drawing its own.
                if (slot.equals("head")) {
                    out.put("hat", true);
                }
                return;
            }
            wear(id, slot, set.get(), armours, origin, diagnostics, out);
            return;
        }

        Optional<DefinitionNode> legacy = item.node("specific_properties").flatMap(properties -> properties.node("armor"));
        if (legacy.isEmpty() || legacy.get().string("slot").isEmpty()) {
            return;
        }
        String slot = armourSlot(legacy.get().string("slot").get());
        Optional<String> set = legacy.get().string("custom_armor");
        if (set.isPresent()) {
            wear(id, slot, set.get(), armours, origin, diagnostics, out);
            return;
        }
        if (legacy.get().string("color").isEmpty()) {
            // A slot and nothing to draw. Real armour already draws its own,
            // and making it ours would swap that for a missing picture.
            if (!slot.equals(materialSlot(material))) {
                out.put("armor", slot);
            }
        } else {
            out.put("armor", slot);
            out.put("armor-texture", "minecraft:leather");
            diagnostics.add(Diagnostic.warning(origin, id,
                    "is colour-only armour (specific_properties.armor.color), which ItemsAdder tints out of leather. "
                            + "RP Engine cannot tint a layer, so it is worn as plain leather; draw a layer and name it "
                            + "with custom_armor to keep the look."));
        }
    }

    /** One piece of a named set: the slot, and the set's layer for that slot. */
    private static void wear(String id, String slot, String set, Map<String, DefinitionNode> armours,
                             String origin, List<Diagnostic> diagnostics, Map<String, Object> out) {
        out.put("armor", slot);
        String name = set.trim().substring(set.trim().indexOf(':') + 1);
        DefinitionNode rendering = armours.get(name);
        String layer = slot.equals("legs") ? "layer_2" : "layer_1";
        String folder = slot.equals("legs") ? "humanoid_leggings" : "humanoid";
        String fallback = " Put the art at assets/textures/entity/equipment/" + folder + "/" + id + ".png instead.";
        if (rendering == null) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "is drawn from the armour " + set + ", which no equipments: or armors_rendering: section in "
                            + "this pack declares, so it has no layer art." + fallback));
            return;
        }
        Optional<String> art = rendering.string(layer);
        if (art.isEmpty()) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "is worn in " + slot + ", which draws " + layer + ", and the armour " + name + " has none."
                            + fallback));
            return;
        }
        if (rendering.node("animation").isPresent()) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "wears the animated armour " + name + ". Its " + layer + " is a strip of frames, which a worn "
                            + "layer cannot play, so it was not carried across." + fallback
                            + " A single frame of it is what to put there."));
            return;
        }
        String path = art.get().trim();
        out.put("armor-art", path.endsWith(".png") ? path.substring(0, path.length() - 4) : path);
        if (rendering.bool("use_color").orElse(Boolean.FALSE)) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "the armour " + name + " is tinted with its color (use_color), which RP Engine cannot do to a "
                            + "layer; it is worn as drawn."));
        }
        if (rendering.raw("emissive_1") != null || rendering.raw("emissive_2") != null) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "the armour " + name + "'s emissive layers needed ItemsAdder's shaders and were skipped; "
                            + "it is worn without the glow."));
        }
    }

    /** The slot a vanilla armour material is already worn in, or null for anything else. */
    private static String materialSlot(String material) {
        if (material.endsWith("_HELMET")) return "head";
        if (material.endsWith("_CHESTPLATE")) return "chest";
        if (material.endsWith("_LEGGINGS")) return "legs";
        if (material.endsWith("_BOOTS")) return "feet";
        return null;
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
