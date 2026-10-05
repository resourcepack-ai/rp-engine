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
 * Reads the item YAML Nexo and current Oraxen use.
 *
 * <p>Both formats put one item id at the top level, with {@code material}, a
 * display name, and a {@code Pack} block, a {@code Components} block or a
 * {@code Mechanics} block. Nexo capitalises those three; Oraxen did too and
 * now writes them lowercase ({@code pack}, {@code components},
 * {@code mechanics}), with old files still around in the capitalised form, so
 * every one of them is read in both spellings. That is intentionally unlike
 * our own format (which has no such blocks at all), so recognising it by
 * shape cannot turn a typo in an RP Engine item into a different kind of
 * content.
 *
 * <h2>What comes across</h2>
 *
 * <ul>
 *   <li>The item: material, name, lore, {@code unbreakable},
 *       {@code Enchantments}, {@code AttributeModifiers} (Nexo's list, and
 *       Oraxen's legacy list and newer map), the art ({@code Pack.model}, or
 *       the first texture layer as a flat sprite) and the vanilla components
 *       that have a property here: durability, stack size, food, glint, and
 *       custom armour, its slot and its layer art.</li>
 *   <li>Furniture, as a {@code place:} block: solidity, the hitbox at its
 *       origin, the first seat in full, the light at its origin, the facing
 *       rule, the surfaces it may go on, a uniform scale and the drop.</li>
 *   <li>Custom blocks, as RP Engine blocks: model, hardness, the tool,
 *       the place sound and the drop.</li>
 * </ul>
 *
 * <p>The two plugins own a great deal more than that - actions, storage,
 * jukeboxes, connectable furniture, the non-cube block shapes. Those are not
 * silently guessed at here: the ordinary item still loads, and a warning
 * names the item and what needs re-authoring.
 */
final class NexoOraxen {

    /**
     * How far Oraxen's seat offset sits below the RP Engine seat it means.
     *
     * <p>The two numbers name different things. RP Engine's {@code seat} is
     * where somebody's backside goes. Oraxen's {@code y} is where it spawns a
     * full-size, non-marker armour stand, and a rider sits on top of that: a
     * stand's passenger point is its height, 1.975, less the 0.7 a player
     * hangs below it (the figure {@code MountOffset} measured), plus the 0.3
     * RP Engine's own seat lifts the hips. So Oraxen's chair at
     * {@code 0,-1.2,0} is a seat 0.375 up, which is where a chair's cushion
     * is. Derived rather than measured in game, which is why every converted
     * seat says so.
     */
    static final double ORAXEN_SEAT_LIFT = 1.975 - 0.7 + 0.3;

    /** The custom block mechanics, current and legacy, in both plugins. */
    private static final List<String> BLOCK_MECHANICS =
            List.of("custom_block", "block", "noteblock", "stringblock", "chorusblock");

    private NexoOraxen() {
    }

    static boolean looksLikeOne(DefinitionNode document) {
        for (String id : document.keys()) {
            Optional<DefinitionNode> item = document.node(id);
            if (item.isPresent() && isTheirs(item.get())
                    && (item.get().raw("material") != null || item.get().raw("itemname") != null
                    || item.get().raw("displayname") != null || item.get().raw("display_name") != null
                    || item.get().raw("customname") != null)) {
                return true;
            }
        }
        return false;
    }

    /** A Pack block, a Components block naming a model, or a Mechanics block, in either spelling. */
    private static boolean isTheirs(DefinitionNode item) {
        return section(item, "Pack").isPresent()
                || section(item, "Components").flatMap(c -> c.string("item_model")).isPresent()
                || section(item, "Mechanics").isPresent();
    }

    /** {@code Pack} or {@code pack}: Nexo's spelling, and Oraxen's old and new ones. */
    static Optional<DefinitionNode> section(DefinitionNode node, String capitalised) {
        return node.node(capitalised).or(() -> node.node(capitalised.toLowerCase(Locale.ROOT)));
    }

    static Map<ContentKind, Map<String, Object>> translate(DefinitionNode document,
                                                             String namespace, String origin,
                                                             List<Diagnostic> diagnostics) {
        return translate(document, namespace, origin, diagnostics, ArmourLayers.NONE);
    }

    /** @param layers the armour layer PNGs in the pack, which both plugins find by name */
    static Map<ContentKind, Map<String, Object>> translate(DefinitionNode document,
                                                             String namespace, String origin,
                                                             List<Diagnostic> diagnostics,
                                                             ArmourLayers layers) {
        Map<String, Object> items = new LinkedHashMap<>();
        Map<String, Object> blocks = new LinkedHashMap<>();
        for (String id : document.keys()) {
            DefinitionNode item = document.node(id).orElse(null);
            if (item == null || (!isTheirs(item) && item.raw("material") == null)) {
                continue;
            }
            DefinitionNode mechanics = section(item, "Mechanics").orElse(DefinitionNode.empty());
            String blockMechanic = null;
            for (String name : BLOCK_MECHANICS) {
                if (mechanics.node(name).isPresent()) {
                    blockMechanic = name;
                    break;
                }
            }
            if (blockMechanic != null) {
                blocks.put(id, block(item, blockMechanic, mechanics.node(blockMechanic).orElseThrow(),
                        id, namespace, origin, diagnostics));
            } else {
                Map<String, Object> translated = item(item, id, namespace, origin, diagnostics);
                armour(item, id, namespace, origin, diagnostics, translated, layers);
                items.put(id, translated);
            }
        }
        Map<ContentKind, Map<String, Object>> out = new LinkedHashMap<>();
        if (!items.isEmpty()) out.put(ContentKind.ITEM, items);
        if (!blocks.isEmpty()) out.put(ContentKind.BLOCK, blocks);
        return out;
    }

    // ---- items --------------------------------------------------------------

    private static Map<String, Object> item(DefinitionNode item, String id, String namespace,
                                             String origin, List<Diagnostic> diagnostics) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("material", item.string("material").orElse("PAPER"));
        item.string("itemname").or(() -> item.string("displayname"))
                .or(() -> item.string("display_name"))
                .or(() -> item.string("customname"))
                .ifPresent(name -> out.put("name", name));
        if (!item.strings("lore").isEmpty()) out.put("lore", item.strings("lore"));
        if (item.bool("unbreakable").orElse(Boolean.FALSE)) out.put("unbreakable", true);

        enchantments(item, id, origin, diagnostics).ifPresent(enchants -> out.put("enchantments", enchants));
        attributes(item, id, origin, diagnostics).ifPresent(attributes -> out.put("attributes", attributes));
        art(item, id, namespace, origin, diagnostics, out);
        components(item, id, origin, diagnostics, out);

        section(item, "Mechanics").ifPresent(mechanics -> {
            for (String mechanic : mechanics.keys()) {
                DefinitionNode body = mechanics.node(mechanic).orElse(DefinitionNode.empty());
                if (mechanic.equals("furniture")) {
                    out.put("place", furniture(item, body, id, namespace, origin, diagnostics));
                    continue;
                }
                // Oraxen's custom durability predates the vanilla component
                // and means the same number of uses.
                if (mechanic.equals("durability") && body.integer("value").isPresent()) {
                    out.putIfAbsent("durability", body.integer("value").get());
                    continue;
                }
                diagnostics.add(Diagnostic.warning(origin, id,
                        mechanic + " is a Nexo/Oraxen mechanic rather than an RP Engine item property, so it was skipped. "
                                + "The item itself still loads."));
            }
        });
        return out;
    }

    /**
     * The picture: {@code Pack.model}, or a flat sprite from the first texture.
     *
     * <p>Nexo writes a texture-only item as {@code texture: x} (or a list of
     * layers) and Oraxen as {@code textures: [x]} with {@code generate_model};
     * a texture MAP is the slots of a generated model. Either way the plugin
     * builds a model out of {@code parent_model} and those textures. Here a
     * flat item is already exactly that for {@code item/generated} and
     * {@code item/handheld}, so the first layer is the item; any other parent
     * builds a shape this engine does not generate, and saying so beats a
     * flat sprite nobody can explain.
     */
    private static void art(DefinitionNode item, String id, String namespace, String origin,
                            List<Diagnostic> diagnostics, Map<String, Object> out) {
        DefinitionNode pack = section(item, "Pack").orElse(DefinitionNode.empty());
        pack.string("model").flatMap(model -> localPath(model, namespace, id, origin, diagnostics, "Pack.model"))
                .ifPresent(model -> out.put("model", model));
        if (!out.containsKey("model")) {
            List<String> layers = textures(pack);
            if (!layers.isEmpty()) {
                localPath(layers.get(0), namespace, id, origin, diagnostics, "Pack.texture")
                        .ifPresent(texture -> out.put("texture", texture));
                String parent = pack.string("parent_model").orElse("item/generated")
                        .replace("minecraft:", "");
                if (!parent.equals("item/generated") && !parent.equals("item/handheld")) {
                    diagnostics.add(Diagnostic.warning(origin, id,
                            "Pack.parent_model " + parent + " is a model Nexo/Oraxen generate from its textures. "
                                    + "RP Engine draws a flat sprite from the first texture instead; export the model "
                                    + "to assets/models/ and set Pack.model to keep its shape."));
                } else if (layers.size() > 1) {
                    diagnostics.add(Diagnostic.warning(origin, id,
                            "only the first of its " + layers.size() + " texture layers is drawn. A flat item here "
                                    + "has one picture; merge the layers into one PNG."));
                }
            }
        }
        if (!out.containsKey("model")) {
            section(item, "Components").flatMap(components -> components.string("item_model"))
                    .flatMap(model -> localPath(model, namespace, id, origin, diagnostics, "Components.item_model"))
                    .ifPresent(model -> out.put("model", model));
        }
    }

    /** Every texture layer, in the order Nexo/Oraxen would layer them. */
    private static List<String> textures(DefinitionNode pack) {
        if (!pack.strings("texture").isEmpty()) return pack.strings("texture");
        if (!pack.strings("textures").isEmpty()) return pack.strings("textures");
        DefinitionNode map = pack.node("textures").orElse(DefinitionNode.empty());
        List<String> out = new ArrayList<>();
        map.string("layer0").ifPresent(out::add);
        for (String slot : map.keys()) {
            if (!slot.equals("layer0")) map.string(slot).ifPresent(out::add);
        }
        return out;
    }

    /**
     * The vanilla components that have a property of their own here.
     *
     * <p>Everything else in the block is a real component that RP Engine has
     * no key for yet, and one warning lists them rather than one per key.
     */
    private static void components(DefinitionNode item, String id, String origin,
                                   List<Diagnostic> diagnostics, Map<String, Object> out) {
        DefinitionNode components = section(item, "Components").orElse(null);
        if (components == null) return;
        List<String> skipped = new ArrayList<>();
        for (String key : components.keys()) {
            switch (key) {
                case "item_model":
                    break;
                case "durability":
                case "max_damage": {
                    // Oraxen writes { value: 500, ... }; Nexo's max_damage is the number.
                    Optional<Integer> uses = components.integer(key)
                            .or(() -> components.node(key).flatMap(node -> node.integer("value")));
                    if (uses.isPresent()) out.put("durability", uses.get());
                    else skipped.add(key);
                    break;
                }
                case "max_stack_size":
                    components.integer(key).ifPresentOrElse(stack -> out.put("stack", stack), () -> skipped.add(key));
                    break;
                case "unbreakable":
                    // A bare true, or the component's own (empty) block.
                    if (!Boolean.FALSE.equals(components.bool(key).orElse(Boolean.TRUE))) {
                        out.put("unbreakable", true);
                    }
                    break;
                case "food": {
                    DefinitionNode food = components.node(key).orElse(DefinitionNode.empty());
                    Map<String, Object> translated = new LinkedHashMap<>();
                    food.integer("nutrition").ifPresent(value -> translated.put("nutrition", value));
                    food.decimal("saturation").ifPresent(value -> translated.put("saturation", value));
                    food.bool("can_always_eat").ifPresent(value -> translated.put("always", value));
                    if (translated.isEmpty()) skipped.add(key);
                    else out.put("food", translated);
                    break;
                }
                case "enchantment_glint_override":
                    if (components.bool(key).orElse(Boolean.FALSE)) {
                        out.put("glow", true);
                    } else {
                        diagnostics.add(Diagnostic.warning(origin, id,
                                "enchantment_glint_override: false hides the glint of an enchanted item, which "
                                        + "RP Engine has no setting for, so it was skipped."));
                    }
                    break;
                case "equippable":
                    // Read with the armour art, which is found by name; see armour.
                    break;
                default:
                    skipped.add(key);
            }
        }
        if (!skipped.isEmpty()) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "the components " + String.join(", ", skipped) + " have no RP Engine property and were "
                            + "skipped. The item itself still loads."));
        }
    }

    /**
     * Custom armour: the slot, and the layer art, which RP Engine's
     * {@code armor} and {@code armor-art} are.
     *
     * <p>Both plugins pair a set's art with its pieces by name rather than by
     * anything written in the item. The set is named by
     * {@code equippable.asset_id} (Nexo) or {@code equippable.model} (Oraxen),
     * by {@code trim_pattern} on the older trim-based armour, or failing all
     * three by the item id less its last word ({@code ruby_helmet} is the
     * {@code ruby} set); its art is {@code Pack.CustomArmor.layer1}/{@code
     * layer2} when written, and otherwise whichever {@code ruby_armor_layer_1.png}
     * and {@code _2.png} the pack ships. An item with no {@code equippable} is
     * armour exactly when that art exists and its id ends in a piece's name,
     * which is when both plugins give it the component themselves.
     *
     * <p>That is the same art in every era of theirs - component, trims, the
     * old leather shaders - so all of them come across the same way.
     *
     * <p>What does not: an elytra's wings, which are not drawn on a body, and
     * a 3D helmet, which has no layer art and is worn as a hat, as it is there.
     */
    private static void armour(DefinitionNode item, String id, String namespace, String origin,
                               List<Diagnostic> diagnostics, Map<String, Object> out, ArmourLayers layers) {
        DefinitionNode equippable = section(item, "Components")
                .flatMap(components -> components.node("equippable")).orElse(null);
        String material = item.string("material").orElse("PAPER").trim().toUpperCase(Locale.ROOT);
        String declaredSlot = equippable == null ? null : equippable.string("slot").orElse(null);
        String asset = equippable == null ? null
                : equippable.string("asset_id").or(() -> equippable.string("model")).orElse(null);
        String set = bare(asset).or(() -> item.string("trim_pattern").flatMap(NexoOraxen::bare))
                .orElse(pieceSlot(id) == null ? null : id.substring(0, id.lastIndexOf('_')));

        if (material.equals("ELYTRA") || id.endsWith("_elytra") || (set != null && set.endsWith("_elytra"))) {
            if (equippable != null || layers.find(set, 1).isPresent()) {
                diagnostics.add(Diagnostic.warning(origin, id,
                        "is an elytra. Its wings art was not carried across: RP Engine draws worn items on a "
                                + "player's body only, so it is worn as its material is."));
            }
            return;
        }

        String slot;
        if (declaredSlot != null) {
            slot = armourSlot(declaredSlot);
            if (slot == null) {
                diagnostics.add(Diagnostic.warning(origin, id,
                        "equippable.slot " + declaredSlot + " is not head, chest, legs or feet, which are the slots "
                                + "RP Engine armour is worn in, so it was skipped."));
                return;
            }
        } else {
            slot = pieceSlot(id) != null ? pieceSlot(id) : materialSlot(material);
        }
        if (slot == null) {
            return;
        }

        int layer = ArmourLayers.layerOf(slot);
        DefinitionNode custom = section(item, "Pack").flatMap(pack -> pack.node("CustomArmor")
                .or(() -> pack.node("custom_armor"))).orElse(DefinitionNode.empty());
        // A written layer in another namespace (Nexo's own examples say
        // nexo:...) is only worth a warning when the same file is not also
        // found by its name.
        List<Diagnostic> written = new ArrayList<>();
        Optional<String> art = custom.string("layer" + layer).or(() -> custom.string("layer_" + layer))
                .flatMap(path -> localPath(path, namespace, id, origin, written, "Pack.CustomArmor.layer" + layer))
                .or(() -> layers.find(set, layer));
        if (art.isEmpty()) diagnostics.addAll(written);
        if (art.isPresent()) {
            out.put("armor", slot);
            out.put("armor-art", art.get());
            return;
        }
        if (equippable == null) {
            // No component and no art: an item that happens to be called
            // something_boots, and neither plugin would make it armour.
            return;
        }
        if (asset == null && !slot.equals(materialSlot(material))) {
            if (slot.equals("head")) {
                // A head piece with no equipment asset shows its item model on
                // the head, which is how both plugins do a 3D helmet - and what
                // a hat is here.
                out.put("hat", true);
                return;
            }
        } else if (asset == null) {
            // Real armour with no art of its own draws its own.
            return;
        }
        out.put("armor", slot);
        String folder = slot.equals("legs") ? "humanoid_leggings" : "humanoid";
        diagnostics.add(Diagnostic.warning(origin, id,
                "is worn as armor: " + slot + ", but no layer art was found for it"
                        + (set == null ? "" : " (" + set + "_armor_layer_" + layer + ".png, as both plugins name it)")
                        + ". Ship that PNG in the pack, or put the art at assets/textures/entity/equipment/"
                        + folder + "/" + id + ".png."));
    }

    /** {@code nexo:ruby} to {@code ruby}: the set a name refers to, whatever namespace it was written in. */
    private static Optional<String> bare(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        String value = name.trim().toLowerCase(Locale.ROOT);
        return Optional.of(value.substring(value.indexOf(':') + 1));
    }

    /** The slot an id's last word names, as both plugins read {@code ruby_helmet}. */
    private static String pieceSlot(String id) {
        int underscore = id.lastIndexOf('_');
        if (underscore <= 0) return null;
        switch (id.substring(underscore + 1).toLowerCase(Locale.ROOT)) {
            case "helmet":
                return "head";
            case "chestplate":
                return "chest";
            case "leggings":
                return "legs";
            case "boots":
                return "feet";
            default:
                return null;
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

    private static String armourSlot(String slot) {
        switch (slot.trim().toUpperCase(Locale.ROOT)) {
            case "HEAD":
            case "HELMET":
                return "head";
            case "CHEST":
            case "CHESTPLATE":
                return "chest";
            case "LEGS":
            case "LEGGINGS":
                return "legs";
            case "FEET":
            case "BOOTS":
                return "feet";
            default:
                return null;
        }
    }

    /** {@code Enchantments: {sharpness: 5}}, with a namespace or a capital where somebody wrote one. */
    private static Optional<Map<String, Object>> enchantments(DefinitionNode item, String id, String origin,
                                                              List<Diagnostic> diagnostics) {
        DefinitionNode declared = item.node("Enchantments").or(() -> item.node("enchantments")).orElse(null);
        if (declared == null) return Optional.empty();
        Map<String, Object> out = new LinkedHashMap<>();
        for (String name : declared.keys()) {
            Optional<Integer> level = declared.integer(name);
            if (level.isEmpty()) {
                diagnostics.add(Diagnostic.warning(origin, id, "Enchantments." + name + " is not a level."));
                continue;
            }
            String vanilla = name.toLowerCase(Locale.ROOT);
            out.put(vanilla.substring(vanilla.lastIndexOf(':') + 1), level.get());
        }
        return out.isEmpty() ? Optional.empty() : Optional.of(out);
    }

    /**
     * {@code AttributeModifiers}, in all three shapes it has had.
     *
     * <p>Nexo's is a list of {@code {attribute, amount, operation, slot}}.
     * Oraxen's legacy one is the same list with the operation as 0, 1 or 2,
     * and its newer one is a map of named modifiers with the same fields
     * inside. All three come out as our block form, because a modifier that
     * names an operation or a slot needs it.
     */
    private static Optional<List<Object>> attributes(DefinitionNode item, String id, String origin,
                                                     List<Diagnostic> diagnostics) {
        String key = item.raw("AttributeModifiers") != null ? "AttributeModifiers" : "attribute_modifiers";
        Object raw = item.raw(key);
        List<DefinitionNode> modifiers = new ArrayList<>();
        if (raw instanceof Map) {
            DefinitionNode named = item.node(key).orElse(DefinitionNode.empty());
            for (String name : named.keys()) named.node(name).ifPresent(modifiers::add);
        } else {
            modifiers.addAll(item.nodes(key));
        }
        List<Object> out = new ArrayList<>();
        for (DefinitionNode modifier : modifiers) {
            String attribute = modifier.string("attribute").map(NexoOraxen::attributeName).orElse(null);
            Optional<Double> amount = modifier.decimal("amount");
            if (attribute == null || amount.isEmpty()) {
                diagnostics.add(Diagnostic.warning(origin, id,
                        "an attribute modifier without an attribute and an amount was skipped."));
                continue;
            }
            String operation = modifier.string("operation").map(NexoOraxen::operation).orElse(null);
            if (modifier.string("operation").isPresent() && operation == null) {
                diagnostics.add(Diagnostic.warning(origin, id,
                        "the " + attribute + " modifier's operation " + modifier.string("operation").get()
                                + " is not one RP Engine knows, so it adds."));
            }
            String slot = modifier.string("slot").map(NexoOraxen::slot).orElse(null);
            if (operation == null && slot == null) {
                out.add(Map.of(attribute, amount.get()));
                continue;
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("amount", amount.get());
            if (operation != null) detail.put("operation", operation);
            if (slot != null) detail.put("slot", slot);
            out.add(Map.of(attribute, detail));
        }
        return out.isEmpty() ? Optional.empty() : Optional.of(out);
    }

    /**
     * {@code GENERIC_ATTACK_DAMAGE} or {@code generic.attack_damage} to
     * {@code attack_damage}, vanilla's name since 1.21.3 and the one ours uses.
     */
    static String attributeName(String declared) {
        String name = declared.trim().toLowerCase(Locale.ROOT).replace("minecraft:", "").replace('.', '_');
        for (String prefix : List.of("generic_", "player_", "zombie_", "horse_")) {
            if (name.startsWith(prefix)) return name.substring(prefix.length());
        }
        return name;
    }

    /** Bukkit's names, Mojang's names, and Oraxen's legacy 0/1/2 to ours. */
    static String operation(String declared) {
        switch (declared.trim().toUpperCase(Locale.ROOT)) {
            case "0":
            case "ADD_NUMBER":
            case "ADD_VALUE":
            case "ADD":
                return "add";
            case "1":
            case "ADD_SCALAR":
            case "ADD_MULTIPLIED_BASE":
            case "MULTIPLY_BASE":
                return "multiply_base";
            case "2":
            case "MULTIPLY_SCALAR_1":
            case "ADD_MULTIPLIED_TOTAL":
            case "MULTIPLY":
                return "multiply";
            default:
                return null;
        }
    }

    /** Bukkit's equipment slot names to ours; anything else is passed on lowercase and checked at give time. */
    static String slot(String declared) {
        String slot = declared.trim().toUpperCase(Locale.ROOT);
        switch (slot) {
            case "HAND":
            case "MAINHAND":
            case "MAIN_HAND":
                return "hand";
            case "OFFHAND":
            case "OFF_HAND":
                return "offhand";
            default:
                return slot.toLowerCase(Locale.ROOT);
        }
    }

    // ---- furniture ----------------------------------------------------------

    /**
     * The furniture mechanic, as a {@code place:} block.
     *
     * <p>The same idea in both: a model you put down that may be solid, glow
     * and be sat on. Where they differ from us, they are richer - several
     * barrier blocks, several seats, several lights at offsets - and RP Engine
     * has one of each at the anchor, so the one at the origin (or the first)
     * comes across and a warning counts the rest.
     */
    private static Map<String, Object> furniture(DefinitionNode item, DefinitionNode furniture, String id,
                                                 String namespace, String origin, List<Diagnostic> diagnostics) {
        Map<String, Object> place = new LinkedHashMap<>();
        DefinitionNode hitbox = furniture.node("hitbox").orElse(DefinitionNode.empty());

        // Solidity. Nexo: hitbox.barrier(s) as "x,y,z" (ranges allowed).
        // Oraxen: barrier: true, and barriers as "origin" or {x, y, z} maps.
        List<Object> barriers = new ArrayList<>();
        barriers.addAll(entries(hitbox.raw("barrier")));
        barriers.addAll(entries(hitbox.raw("barriers")));
        barriers.addAll(entries(furniture.raw("barriers")));
        if (furniture.bool("barrier").orElse(Boolean.FALSE)) barriers.add("origin");
        if (!barriers.isEmpty()) {
            place.put("solid", true);
            boolean anchorOnly = barriers.stream().allMatch(NexoOraxen::atOrigin);
            if (!anchorOnly || barriers.size() > 1) {
                diagnostics.add(Diagnostic.warning(origin, id,
                        "has " + barriers.size() + " barrier entries. RP Engine's solid: true is one barrier at the "
                                + "anchor block, so only that one is solid."));
            }
        }

        // Hitbox. Nexo: hitbox.interaction(s); Oraxen: hitboxes; both "x,y,z w,h".
        // Legacy Oraxen: hitbox: {width, height}.
        List<String> boxes = new ArrayList<>();
        boxes.addAll(hitbox.strings("interaction"));
        boxes.addAll(hitbox.strings("interactions"));
        boxes.addAll(furniture.strings("hitboxes"));
        if (hitbox.decimal("width").isPresent() || hitbox.decimal("height").isPresent()) {
            boxes.add("0,0,0 " + hitbox.decimal("width").orElse(1d) + "," + hitbox.decimal("height").orElse(1d));
        }
        boolean sized = false;
        for (String box : boxes) {
            double[] parsed = numbers(box);
            if (parsed.length == 5 && parsed[0] == 0 && parsed[1] == 0 && parsed[2] == 0 && !sized) {
                place.put("width", parsed[3]);
                place.put("height", parsed[4]);
                sized = true;
            }
        }
        if (boxes.size() > (sized ? 1 : 0)) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "has " + boxes.size() + " interaction hitboxes. RP Engine has one, at the anchor"
                            + (sized ? ", taken from the one at 0,0,0" : ", measured from the model")
                            + "; the rest were skipped."));
        }
        for (String other : List.of("shulker", "shulkers", "ghast", "ghasts")) {
            if (hitbox.raw(other) != null) {
                diagnostics.add(Diagnostic.warning(origin, id,
                        "hitbox." + other + " has no RP Engine equivalent and was skipped."));
            }
        }

        seat(item, furniture, id, origin, diagnostics, place);
        light(furniture, id, origin, diagnostics, place);
        if (place.containsKey("solid") && place.containsKey("light")) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "is both solid and a light. One block cannot be a barrier and a light at once, and RP Engine "
                            + "keeps the barrier."));
        }

        // Facing. Both place on eight facings by default (restricted_rotation
        // STRICT) and four on VERY_STRICT. rotatable is something else in both:
        // turning a piece AFTER it is down, by clicking it.
        String restricted = furniture.string("restricted_rotation")
                .or(() -> furniture.node("restricted_rotation").flatMap(node -> node.string("type")))
                .orElse("STRICT").trim().toUpperCase(Locale.ROOT);
        place.put("facing", restricted.equals("VERY_STRICT") ? "cardinal"
                : restricted.equals("NONE") ? "free" : "diagonal");
        if (furniture.bool("rotatable").orElse(Boolean.FALSE)) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "rotatable: turning a placed piece by clicking it has no RP Engine equivalent. It is placed "
                            + "facing the player, on the facings restricted_rotation allows."));
        }

        surface(furniture, id, origin, diagnostics, place);
        scale(furniture, id, origin, diagnostics, place);

        furniture.node("drop").flatMap(drop -> drop.string("nexo_item").or(() -> drop.string("oraxen_item"))
                        .or(() -> firstLoot(drop)))
                .ifPresent(drop -> place.put("drop", qualified(drop, namespace)));

        List<String> skipped = new ArrayList<>();
        for (String key : furniture.keys()) {
            if (!FURNITURE_KEYS.contains(key)) skipped.add(key);
        }
        if (!skipped.isEmpty()) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "furniture " + String.join(", ", skipped) + " have no RP Engine equivalent and were skipped. "
                            + "The piece itself still places."));
        }
        return place;
    }

    /** Furniture keys that are translated, or that only say how their plugin renders it. */
    private static final List<String> FURNITURE_KEYS = List.of(
            "hitbox", "hitboxes", "barrier", "barriers", "seat", "seats", "seat_height", "lights", "light",
            "rotatable", "restricted_rotation", "limited_placing", "properties", "display_entity_properties",
            "drop", "type", "item");

    /**
     * The first seat, all three numbers of it.
     *
     * <p>Nexo writes {@code seat: "x,y,z"} or a list under {@code seats}, and
     * its {@code y} is where the rider sits, which is ours. Oraxen writes
     * {@code seats: ["x,y,z", "x,y,z yaw"]} - or, before that,
     * {@code seat: {height, yaw}}, which Oraxen itself migrates to
     * {@code y = height - 1} - and its {@code y} is where an armour stand goes;
     * see {@link #ORAXEN_SEAT_LIFT}. The side and forward offsets are carried
     * over as written.
     */
    private static void seat(DefinitionNode item, DefinitionNode furniture, String id, String origin,
                             List<Diagnostic> diagnostics, Map<String, Object> place) {
        List<String> seats = new ArrayList<>();
        // Only a seat out of Oraxen's own two spellings is an armour-stand
        // offset. A bare seat: "x,y,z" or seat: 0.6 is Nexo's, or somebody
        // writing ours.
        boolean standOffset;
        Optional<DefinitionNode> legacy = furniture.node("seat");
        if (legacy.isPresent()) {
            standOffset = true;
            seats.add("0," + (legacy.get().decimal("height").orElse(0d) - 1) + ",0");
        } else {
            seats.addAll(furniture.strings("seat"));
            seats.addAll(furniture.strings("seat_height"));
            standOffset = seats.isEmpty() && isOraxen(item, furniture);
        }
        seats.addAll(furniture.strings("seats"));
        if (seats.isEmpty()) return;

        double[] first = numbers(seats.get(0));
        double x = 0;
        double y;
        double z = 0;
        if (first.length >= 3) {
            x = first[0];
            y = first[1];
            z = first[2];
        } else if (first.length >= 1) {
            y = first[0];
        } else {
            diagnostics.add(Diagnostic.warning(origin, id, "seat " + seats.get(0) + " is not x,y,z, so it was skipped."));
            return;
        }
        if (standOffset) {
            y = round(y + ORAXEN_SEAT_LIFT);
            diagnostics.add(Diagnostic.warning(origin, id,
                    "seat height " + y + " was converted from Oraxen's armour-stand offset (y + " + ORAXEN_SEAT_LIFT
                            + "). If players sit too high or low, adjust place.seat."));
        }
        if (x == 0 && z == 0) {
            place.put("seat", y);
        } else {
            Map<String, Object> offset = new LinkedHashMap<>();
            offset.put("x", x);
            offset.put("y", y);
            offset.put("z", z);
            place.put("seat", offset);
            diagnostics.add(Diagnostic.warning(origin, id,
                    "an off-centre seat's side and forward offsets were carried over as written; check it in game."));
        }
        if (seats.size() > 1) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "has " + seats.size() + " seats. RP Engine seats one player per piece, so only the first came across."));
        }
        if (first.length >= 4 && first[3] != 0) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "the seat's own yaw has no RP Engine equivalent; the rider faces the way the piece does."));
        }
    }

    /**
     * Whether this furniture is written in Oraxen's dialect, which matters
     * only for what a seat's {@code y} means. Oraxen now writes the item's
     * blocks lowercase, and its furniture keys ({@code hitboxes},
     * {@code barrier: true}, {@code display_entity_properties}) are not
     * Nexo's ({@code hitbox: {interactions, barriers}}, {@code properties}).
     */
    private static boolean isOraxen(DefinitionNode item, DefinitionNode furniture) {
        return item.node("mechanics").isPresent() || furniture.raw("hitboxes") != null
                || furniture.raw("barrier") instanceof Boolean || furniture.raw("display_entity_properties") != null
                || furniture.raw("barriers") != null;
    }

    /**
     * The light at the origin, or the first one.
     *
     * <p>Nexo: {@code lights: {light: "x,y,z level", lights: [...]}}. Oraxen:
     * {@code lights: ["x,y,z level"]}. Both once had {@code light: N}, which
     * lit the base block.
     */
    private static void light(DefinitionNode furniture, String id, String origin, List<Diagnostic> diagnostics,
                              Map<String, Object> place) {
        List<String> lights = new ArrayList<>();
        Optional<DefinitionNode> nexo = furniture.node("lights");
        if (nexo.isPresent()) {
            lights.addAll(nexo.get().strings("light"));
            lights.addAll(nexo.get().strings("lights"));
            if (nexo.get().bool("toggleable").orElse(Boolean.FALSE)) {
                diagnostics.add(Diagnostic.warning(origin, id,
                        "a light switched by clicking has no RP Engine equivalent; it is always on."));
            }
        } else {
            lights.addAll(furniture.strings("lights"));
        }
        if (lights.isEmpty()) {
            furniture.integer("light").ifPresent(level -> place.put("light", Math.max(0, Math.min(15, level))));
            return;
        }
        double[] first = null;
        double[] anchor = null;
        for (String light : lights) {
            double[] parsed = numbers(light);
            if (parsed.length != 4) continue;
            if (first == null) first = parsed;
            if (anchor == null && parsed[0] == 0 && parsed[1] == 0 && parsed[2] == 0) anchor = parsed;
        }
        double[] chosen = anchor != null ? anchor : first;
        if (chosen == null) return;
        place.put("light", (int) Math.max(0, Math.min(15, chosen[3])));
        if (lights.size() > 1 || chosen[0] != 0 || chosen[1] != 0 || chosen[2] != 0) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "has " + lights.size() + " light entries. RP Engine lights the anchor block only, at level "
                            + (int) chosen[3] + "."));
        }
    }

    /**
     * {@code limited_placing: {floor, roof, wall}}, each true unless it says
     * otherwise, which is both plugins' default. Ours allows one surface or
     * all of them.
     */
    private static void surface(DefinitionNode furniture, String id, String origin, List<Diagnostic> diagnostics,
                                Map<String, Object> place) {
        DefinitionNode limited = furniture.node("limited_placing").orElse(null);
        if (limited == null) return;
        List<String> allowed = new ArrayList<>();
        if (limited.bool("floor").orElse(Boolean.TRUE)) allowed.add("floor");
        if (limited.bool("wall").orElse(Boolean.TRUE)) allowed.add("wall");
        if (limited.bool("roof").orElse(Boolean.TRUE)) allowed.add("ceiling");
        if (allowed.size() == 1) {
            place.put("surface", allowed.get(0));
        } else if (allowed.size() == 3) {
            place.put("surface", "any");
        } else if (allowed.size() == 2) {
            place.put("surface", "any");
            diagnostics.add(Diagnostic.warning(origin, id,
                    "limited_placing allows " + String.join(" and ", allowed) + ". RP Engine allows one surface "
                            + "or all of them, so it goes on any."));
        } else {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "limited_placing allows no surface at all, so it stays on the floor."));
        }
        // type: ALLOW/DENY only means something beside a list of blocks.
        for (String rule : List.of("block_types", "block_tags", "nexo_blocks", "oraxen_blocks",
                "radius_limitation")) {
            Object value = limited.raw(rule);
            if (value != null && !(value instanceof List && ((List<?>) value).isEmpty())) {
                diagnostics.add(Diagnostic.warning(origin, id,
                        "limited_placing." + rule + " has no RP Engine equivalent and was skipped."));
            }
        }
    }

    /**
     * Nexo's {@code properties.scale: "x,y,z"}, Oraxen's
     * {@code display_entity_properties.scale: {x, y, z}}. Ours is one number,
     * so only a uniform scale comes across.
     */
    private static void scale(DefinitionNode furniture, String id, String origin, List<Diagnostic> diagnostics,
                              Map<String, Object> place) {
        double[] scale = null;
        Optional<DefinitionNode> properties = furniture.node("properties");
        if (properties.isPresent() && properties.get().string("scale").isPresent()) {
            scale = numbers(properties.get().string("scale").get());
        }
        Optional<DefinitionNode> display = furniture.node("display_entity_properties")
                .flatMap(node -> node.node("scale"));
        if (display.isPresent()) {
            scale = new double[]{display.get().decimal("x").orElse(1d), display.get().decimal("y").orElse(1d),
                    display.get().decimal("z").orElse(1d)};
        }
        List<String> unread = new ArrayList<>();
        for (DefinitionNode node : List.of(properties.orElse(DefinitionNode.empty()),
                furniture.node("display_entity_properties").orElse(DefinitionNode.empty()))) {
            for (String key : node.keys()) {
                if (!key.equals("scale") && !key.equals("display_transform")) unread.add(key);
            }
        }
        if (!unread.isEmpty()) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "display properties " + String.join(", ", unread) + " have no RP Engine equivalent: a placed "
                            + "model renders as its file says, at its real size. They were skipped."));
        }
        if (scale == null || scale.length == 0) return;
        if (scale.length == 1 || (scale.length == 3 && scale[0] == scale[1] && scale[1] == scale[2])) {
            if (scale[0] != 1) place.put("scale", scale[0]);
        } else {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "its scale is not the same on every axis, and RP Engine scales a placed model by one number, "
                            + "so it was skipped."));
        }
    }

    /** A YAML value that may be one entry or a list of them, as a list. */
    private static List<Object> entries(Object raw) {
        if (raw == null || raw instanceof Boolean) return List.of();
        if (raw instanceof List) return new ArrayList<>((List<?>) raw);
        return List.of(raw);
    }

    /** "origin", "0,0,0" or {x: 0, z: 0}: a barrier in the anchor block. */
    private static boolean atOrigin(Object entry) {
        if (entry instanceof Map) {
            for (Object value : ((Map<?, ?>) entry).values()) {
                if (!(value instanceof Number) || ((Number) value).doubleValue() != 0) return false;
            }
            return true;
        }
        String text = String.valueOf(entry).trim();
        if (text.equalsIgnoreCase("origin")) return true;
        double[] parsed = numbers(text);
        return parsed.length == 3 && parsed[0] == 0 && parsed[1] == 0 && parsed[2] == 0;
    }

    /**
     * The numbers in "x,y,z", "x,y,z w,h" or "x,y,z level", in order. Empty
     * when any of it is not a number - a range such as {@code 0..2} included,
     * which is never the anchor.
     */
    static double[] numbers(String text) {
        String[] parts = text.trim().replaceAll("\\s*,\\s*", ",").split("[\\s,]+");
        double[] out = new double[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                out[i] = Double.parseDouble(parts[i]);
            }
        } catch (NumberFormatException e) {
            return new double[0];
        }
        return out;
    }

    private static double round(double value) {
        return Math.round(value * 1000d) / 1000d;
    }

    // ---- custom blocks ------------------------------------------------------

    /**
     * A custom block is its own RP Engine content id; its item comes with it.
     *
     * <p>Nexo's {@code custom_block} and Oraxen's {@code block} (and their
     * legacy {@code noteblock}/{@code stringblock}/{@code chorusblock}) all
     * become a full cube in a spare block state here. Their non-cube shapes -
     * tripwire plants, chorus leaves, Oraxen's stairs, slabs and doors - are
     * different blocks underneath, which this engine does not have, so they
     * come across as a cube and say so.
     */
    private static Map<String, Object> block(DefinitionNode item, String mechanicName, DefinitionNode mechanic,
                                             String id, String namespace, String origin,
                                             List<Diagnostic> diagnostics) {
        Map<String, Object> out = new LinkedHashMap<>();
        DefinitionNode pack = section(item, "Pack").orElse(DefinitionNode.empty());

        // A model a file names, or the one Nexo/Oraxen generate out of
        // parent_model and textures, which has no file to point at.
        boolean generated = pack.string("model").isEmpty() && !textures(pack).isEmpty();
        Optional<String> model = mechanic.string("model")
                .or(() -> mechanic.node("appearance").flatMap(a -> a.string("model")))
                .or(() -> pack.string("model"));
        if (generated && (model.isEmpty() || model.get().equals(id)
                || model.get().endsWith(":" + id) || model.get().endsWith("/" + id))) {
            String parent = pack.string("parent_model").orElse("block/cube_all").replace("minecraft:", "");
            diagnostics.add(Diagnostic.warning(origin, id,
                    "is drawn by a model Nexo/Oraxen generate from Pack.parent_model " + parent
                            + " and its texture. RP Engine blocks take a model file and do not generate one, "
                            + "so it renders as a plain note block until there is one: write assets/models/" + id
                            + ".json with \"parent\": \"minecraft:" + parent + "\" and the texture, and set model: "
                            + id + "."));
        } else {
            model.flatMap(value -> localPath(value, namespace, id, origin, diagnostics, "model"))
                    .ifPresent(value -> out.put("model", value));
        }

        String type = mechanic.string("type").orElse("").trim().toUpperCase(Locale.ROOT);
        if (mechanicName.equals("stringblock") || mechanicName.equals("chorusblock")
                || List.of("STRINGBLOCK", "CHORUSBLOCK", "STRING", "CHORUS", "STAIR", "SLAB", "DOOR", "TRAPDOOR",
                "GRATE", "BULB").contains(type)) {
            String kind = type.isEmpty() ? mechanicName : type.toLowerCase(Locale.ROOT);
            diagnostics.add(Diagnostic.warning(origin, id,
                    kind + " is not a full block, and RP Engine custom blocks are full cubes inside a note block. "
                            + "It came across as one; a placed model suits a plant or a decoration better."));
        }
        if (type.contains("MUSHROOM")
                || (mechanicName.equals("block") && type.isEmpty() && mechanic.raw("custom_variation") != null)) {
            // Oraxen's original block mechanic hid in mushroom stems.
            out.put("base", "mushroom_stem");
        }

        breaking(mechanic, id, namespace, origin, diagnostics, out);
        sound(mechanic, id, origin, diagnostics, out);

        if (mechanic.raw("light") != null) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "light: a custom block cannot give off light here - it belongs to the block's type, not its "
                            + "state. A placed model can."));
        }
        List<String> skipped = new ArrayList<>();
        for (String name : mechanic.keys()) {
            if (!BLOCK_KEYS.contains(name)) skipped.add(name);
        }
        if (!skipped.isEmpty()) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "custom block " + String.join(", ", skipped) + " have no RP Engine equivalent and were skipped."));
        }
        return out;
    }

    private static final List<String> BLOCK_KEYS = List.of("model", "appearance", "hardness", "drop", "sound",
            "block_sounds", "block-sounds", "type", "custom_variation", "custom-variation", "breaking", "light");

    /**
     * Hardness, tool and drop.
     *
     * <p>Nexo: {@code hardness} and {@code drop: {best_tool, loots}}. Oraxen:
     * {@code breaking}, a list of rules each with {@code when} (the tools),
     * {@code hardness} and {@code drops}, ending in an {@code else}; older
     * Oraxen had Nexo's shape. The first rule is the intended way to break
     * it, which is what a hardness and a tool say here: the wrong tool still
     * breaks it and gives nothing, which is what an {@code else} without
     * drops meant.
     */
    private static void breaking(DefinitionNode mechanic, String id, String namespace, String origin,
                                 List<Diagnostic> diagnostics, Map<String, Object> out) {
        List<DefinitionNode> rules = mechanic.nodes("breaking");
        if (!rules.isEmpty()) {
            DefinitionNode first = rules.get(0);
            first.string("hardness").ifPresent(hardness -> out.put("hardness", hardness));
            List<String> tools = first.strings("when");
            String kind = null;
            for (String tool : tools) {
                kind = toolKind(tool);
                if (kind != null) break;
            }
            if (kind != null) {
                out.put("tool", kind);
                String asked = kind;
                boolean tiered = tools.stream().anyMatch(tool -> tool.toLowerCase(Locale.ROOT).endsWith("_" + asked));
                if (tiered && tools.stream().noneMatch(tool -> tool.toLowerCase(Locale.ROOT).contains("wooden_"))) {
                    diagnostics.add(Diagnostic.warning(origin, id,
                            "breaking.when asks for particular tiers of " + kind + ". RP Engine asks for the kind of "
                                    + "tool only, so any " + kind + " gets the drop."));
                }
            } else if (!tools.isEmpty()) {
                diagnostics.add(Diagnostic.warning(origin, id,
                        "breaking.when lists " + tools + ", which is not a pickaxe, axe, shovel, hoe or sword, "
                                + "so no tool is required."));
            }
            for (DefinitionNode drop : first.nodes("drops")) {
                Optional<String> dropped = drop.string("oraxen_item").or(() -> drop.string("nexo_item"))
                        .or(() -> drop.string("item"));
                if (dropped.isPresent()) {
                    dropOf(dropped.get(), id, namespace, origin, diagnostics, out);
                    break;
                }
            }
            return;
        }
        mechanic.string("hardness").ifPresent(hardness -> out.put("hardness", hardness));
        mechanic.node("hardness").flatMap(hardness -> hardness.string("hardness"))
                .ifPresent(hardness -> out.put("hardness", hardness));
        DefinitionNode drop = mechanic.node("drop").orElse(DefinitionNode.empty());
        drop.string("best_tool").ifPresent(tool -> out.put("tool", tool.toLowerCase(Locale.ROOT)));
        if (drop.has("minimal_type")) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "drop.minimal_type asks for a tier of tool. RP Engine asks for the kind of tool only."));
        }
        drop.string("nexo_item").or(() -> drop.string("oraxen_item")).or(() -> firstLoot(drop))
                .ifPresent(dropped -> dropOf(dropped, id, namespace, origin, diagnostics, out));
    }

    /** One of their item ids as the block's drop; a vanilla item cannot be one here. */
    private static void dropOf(String dropped, String id, String namespace, String origin,
                               List<Diagnostic> diagnostics, Map<String, Object> out) {
        if (dropped.startsWith("minecraft:")) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "drops " + dropped + ", and an RP Engine block's drop is one of the pack's own items, "
                            + "so it drops itself."));
            return;
        }
        out.put("drop", qualified(dropped, namespace));
    }

    /** {@code minecraft:iron_pickaxe} to {@code pickaxe}, which is how a block asks for a tool here. */
    private static String toolKind(String tool) {
        String name = tool.toLowerCase(Locale.ROOT);
        name = name.substring(name.lastIndexOf(':') + 1);
        for (String kind : List.of("pickaxe", "shovel", "hoe", "sword", "axe")) {
            if (name.equals(kind) || name.endsWith("_" + kind) || name.endsWith(kind + "s")) return kind;
        }
        return null;
    }

    /**
     * The place sound, which is the one sound a block of ours plays.
     *
     * <p>{@code block_sounds} (Nexo, Oraxen) or {@code block-sounds} (Oraxen),
     * with {@code place_sound}/{@code place-sound} as a string or
     * {@code place: {sound}}. The break, step, hit and fall sounds belong to
     * the base block's type here and are named in a warning.
     */
    private static void sound(DefinitionNode mechanic, String id, String origin, List<Diagnostic> diagnostics,
                              Map<String, Object> out) {
        mechanic.string("sound").ifPresent(sound -> out.put("sound", sound));
        DefinitionNode sounds = mechanic.node("block_sounds").or(() -> mechanic.node("block-sounds"))
                .orElse(DefinitionNode.empty());
        sounds.string("place_sound").or(() -> sounds.string("place-sound"))
                .or(() -> sounds.string("place"))
                .or(() -> sounds.node("place").flatMap(place -> place.string("sound").or(() -> place.string("name"))))
                .ifPresent(sound -> out.put("sound", sound));
        List<String> others = new ArrayList<>();
        for (String key : sounds.keys()) {
            if (!key.startsWith("place")) others.add(key);
        }
        if (!others.isEmpty()) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "block sounds " + String.join(", ", others) + " were skipped: a custom block here plays its "
                            + "place sound over the base block's own, and the rest belong to the base block."));
        }
    }

    /** The usual Nexo/Oraxen drop is a one-entry loots list. Randomness remains plugin behaviour. */
    private static Optional<String> firstLoot(DefinitionNode drop) {
        for (DefinitionNode loot : drop.nodes("loots")) {
            Optional<String> item = loot.string("nexo_item").or(() -> loot.string("oraxen_item"));
            if (item.isPresent()) return item;
        }
        return Optional.empty();
    }

    private static String qualified(String id, String namespace) {
        return id.contains(":") ? id : namespace + ":" + id;
    }

    /** A resource location is a path after its own namespace; foreign art is not ours to copy. */
    static Optional<String> localPath(String reference, String namespace, String id,
                                      String origin, List<Diagnostic> diagnostics, String field) {
        String value = reference.endsWith(".json") || reference.endsWith(".png")
                ? reference.substring(0, reference.length() - (reference.endsWith(".json") ? 5 : 4)) : reference;
        int colon = value.indexOf(':');
        if (colon < 0) {
            return Optional.of(value);
        }
        String declared = value.substring(0, colon);
        if (!declared.equals(namespace)) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    field + " points at " + reference + ", outside this pack's namespace. "
                            + "RP Engine cannot copy somebody else's asset, so it was skipped."));
            return Optional.empty();
        }
        return Optional.of(value.substring(colon + 1));
    }
}
