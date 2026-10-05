package ai.resourcepack.engine.core.content;

import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.Diagnostic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static ai.resourcepack.engine.core.content.CraftEngine.get;
import static ai.resourcepack.engine.core.content.CraftEngine.list;
import static ai.resourcepack.engine.core.content.CraftEngine.location;
import static ai.resourcepack.engine.core.content.CraftEngine.map;
import static ai.resourcepack.engine.core.content.CraftEngine.normalised;
import static ai.resourcepack.engine.core.content.CraftEngine.number;
import static ai.resourcepack.engine.core.content.CraftEngine.string;
import static ai.resourcepack.engine.core.content.CraftEngine.strings;
import static ai.resourcepack.engine.core.content.CraftEngine.truthy;
import static ai.resourcepack.engine.core.content.CraftEngine.vector;

/**
 * CraftEngine items, blocks and furniture, as RP Engine items, custom blocks
 * and placed models.
 *
 * <h2>Items</h2>
 *
 * <p>The material ({@code PAPER} when there is none), the {@code data:}
 * components that have an RP Engine property - name, lore, unbreakable,
 * enchantments, attribute modifiers, food, durability, stack size, glint and
 * {@code equippable} - and the art. Names are MiniMessage in CraftEngine and
 * come across as colour codes, with {@code <lang:...>} replaced by the
 * pack's own English text. The art is read the way CraftEngine reads it:
 * {@code texture(s)} generates a model (one picture is a flat sprite, a tool
 * is held like a tool, several are layers), {@code model:} names a model or
 * describes one ({@code generation:} becomes a model written inline), and an
 * item model definition that switches between several models
 * ({@code select}, {@code condition}, {@code range_dispatch},
 * {@code composite}) comes across as the model it shows by default, named in
 * a warning.
 *
 * <h2>Blocks</h2>
 *
 * <p>An item with a {@code block_item} behaviour and the block it places are
 * ONE id here, because a block's id is also the item that places it: the
 * block comes across with the item's name and lore. A block is a full cube
 * in a spare note block or mushroom stem state; its {@code texture(s)} build
 * the cube model CraftEngine would build, its hardness, tool and place sound
 * come across, and the first thing its loot table drops is its drop. The
 * shapes that are not a full block (leaves, tripwire, saplings, slabs...)
 * come across as a cube and say so.
 *
 * <h2>Furniture</h2>
 *
 * <p>Furniture, top-level or inline under a {@code furniture_item}, becomes
 * the item's {@code place:} block. RP Engine has one variant, one model, one
 * hitbox, one seat and one light per piece, at its anchor, so the ground
 * variant is used (or the only one there is), its first element's model,
 * its interaction hitbox at the origin, its first seat and the light at its
 * origin; everything beyond that is a warning that counts what was left.
 */
final class CraftEngineItems {

    /**
     * How far above CraftEngine's seat {@code y} the RP Engine seat it means is.
     *
     * <p>CraftEngine mounts a seated player on an entity it spawns 0.6 above
     * the seat's {@code y} (a display, or an armour stand put back down by
     * its own height so the rider lands in the same place), and a rider sits
     * 0.7 below a vehicle with no size (the figure {@code MountOffset}
     * measured), so the rider's position is the seat's {@code y} less 0.1.
     * RP Engine's {@code seat} is where the hips go, 0.3 above the rider's
     * position. Derived rather than measured in game, which is why every
     * converted seat says so.
     */
    static final double CRAFTENGINE_SEAT_LIFT = 0.6 - 0.7 + 0.3;

    private static final Set<String> BLOCK_ITEMS = Set.of("block_item", "liquid_collision_block_item",
            "double_high_block_item", "wall_block_item", "ceiling_block_item", "ground_block_item",
            "multi_high_block_item");

    private static final Set<String> FURNITURE_ITEMS = Set.of("furniture_item", "liquid_collision_furniture_item");

    private CraftEngineItems() {
    }

    static void translate(CraftEngine.Library library, CraftEngine.Pack pack, List<CraftEngine.Output> out,
                          List<Diagnostic> diagnostics) {
        Map<String, CraftEngine.Entry> blocks = new LinkedHashMap<>();
        Map<String, CraftEngine.Entry> furniture = new LinkedHashMap<>();
        List<CraftEngine.Entry> items = new ArrayList<>();
        for (CraftEngine.Entry entry : pack.entries) {
            switch (entry.section) {
                case "items":
                    items.add(entry);
                    break;
                case "blocks":
                    blocks.put(entry.id, entry);
                    break;
                case "furniture":
                    furniture.put(entry.id, entry);
                    break;
                default:
            }
        }

        // Which item a piece of furniture is placed by, when the furniture
        // names it rather than the item naming the furniture.
        Map<String, String> furnitureByItem = new LinkedHashMap<>();
        for (CraftEngine.Entry piece : furniture.values()) {
            String item = string(normalised(piece.body.get("settings")).get("item"));
            furnitureByItem.putIfAbsent(item == null ? piece.id : full(item, "minecraft"), piece.id);
        }

        Map<String, Map<String, Object>> blockText = new LinkedHashMap<>();
        Map<String, String> blockItemModel = new LinkedHashMap<>();
        Set<String> usedFurniture = new LinkedHashSet<>();
        Set<String> written = new LinkedHashSet<>();

        for (CraftEngine.Entry entry : items) {
            if (entry.namespace().equals("minecraft")) {
                diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                        "changes a vanilla item, which RP Engine content does not do: an RP Engine item is a "
                                + "new id. It was skipped."));
                continue;
            }
            Map<String, Object> item = item(library, entry, diagnostics);

            String blockId = null;
            String furnitureId = null;
            Map<String, Object> inlineFurniture = null;
            Map<String, Object> rules = null;
            List<String> skipped = new ArrayList<>();
            for (Object raw : list(get(entry.body, "behavior", "behaviors"))) {
                Map<String, Object> behaviour = map(raw);
                if (behaviour == null) continue;
                String type = type(behaviour.get("type"));
                if (BLOCK_ITEMS.contains(type)) {
                    Object block = behaviour.get("block");
                    if (block instanceof Map<?, ?> inline) {
                        blockId = entry.id;
                        if (!blocks.containsKey(entry.id)) {
                            CraftEngine.Entry made = new CraftEngine.Entry("blocks", entry.id, inline, entry.origin,
                                    entry.folder);
                            made.body = CraftEngineYaml.cast(inline);
                            blocks.put(entry.id, made);
                        }
                    } else if (string(block) != null) {
                        blockId = full(string(block), "minecraft");
                    }
                    if (!type.equals("block_item")) {
                        diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                                type + " places its block by a rule of CraftEngine's own; here it places like an "
                                        + "ordinary block."));
                    }
                } else if (FURNITURE_ITEMS.contains(type)) {
                    Object piece = behaviour.get("furniture");
                    if (piece instanceof Map<?, ?> inline) {
                        inlineFurniture = CraftEngineYaml.cast(inline);
                        furnitureId = entry.id;
                    } else if (string(piece) != null) {
                        furnitureId = full(string(piece), "minecraft");
                    }
                    rules = map(behaviour.get("rules"));
                    if (type.equals("liquid_collision_furniture_item")) {
                        diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                                "is placed on liquid in CraftEngine; here it is placed on a block."));
                    }
                    for (String key : List.of("against_blocks", "against_block_tags")) {
                        if (behaviour.get(key) != null) {
                            diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                                    key + " limits which blocks it goes on, which RP Engine has no rule for."));
                        }
                    }
                } else {
                    skipped.add(type);
                }
            }
            if (!skipped.isEmpty()) {
                diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                        "the behaviours " + String.join(", ", skipped) + " are CraftEngine's own and were skipped. "
                                + "The item itself still loads."));
            }

            // An item and a block written under the same id are one thing.
            if (blockId == null && blocks.containsKey(entry.id)) {
                blockId = entry.id;
            }
            if (blockId != null && !blocks.containsKey(blockId) && !library.blocks.containsKey(blockId)) {
                diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                        "places the block " + blockId + ", which no loaded CraftEngine pack defines. It came across "
                                + "as an item that places nothing."));
                blockId = null;
            }
            if (blockId != null) {
                if (blockId.equals(entry.id)) {
                    // The item's own events ride with it into the block.
                    CraftEngineEvents.translate(get(entry.body, "event", "events"), CraftEngineEvents.Owner.ITEM,
                            true, entry.id, entry.origin, diagnostics, item);
                    blockText.put(blockId, item);
                    if (item.get("model") != null) blockItemModel.put(blockId, String.valueOf(item.get("model")));
                    continue;
                }
                String target = library.reference(blockId, "minecraft");
                diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                        "places the block " + blockId + ". In RP Engine a block is placed by its own id, so "
                                + (target != null && target.contains(":") ? "/rp give " + target
                                : "that block's item") + " places it; this item came across without placing anything."));
            }

            if (furnitureId == null && furnitureByItem.containsKey(entry.id)) {
                furnitureId = furnitureByItem.get(entry.id);
            }
            if (furnitureId != null) {
                Map<String, Object> body = inlineFurniture;
                if (body == null) {
                    CraftEngine.Entry piece = furniture.containsKey(furnitureId) ? furniture.get(furnitureId)
                            : library.furniture.get(furnitureId);
                    body = piece == null ? null : piece.body;
                }
                if (body == null) {
                    diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                            "places the furniture " + furnitureId + ", which no loaded CraftEngine pack defines. "
                                    + "It came across as an item that places nothing."));
                } else {
                    usedFurniture.add(furnitureId);
                    item.put("place", place(library, entry, body, rules, diagnostics));
                    CraftEngineEvents.translate(get(body, "event", "events"), CraftEngineEvents.Owner.FURNITURE,
                            true, entry.id, entry.origin, diagnostics, item);
                }
            }
            CraftEngineEvents.translate(get(entry.body, "event", "events"), CraftEngineEvents.Owner.ITEM,
                    item.containsKey("place"), entry.id, entry.origin, diagnostics, item);
            written.add(entry.id);
            out.add(new CraftEngine.Output(ContentKind.ITEM, entry.path(), item, entry.origin));
        }

        for (CraftEngine.Entry block : blocks.values()) {
            if (written.contains(block.id)) continue;
            if (block.namespace().equals("minecraft")) {
                diagnostics.add(Diagnostic.warning(block.origin, block.id,
                        "changes a vanilla block, which RP Engine content does not do: an RP Engine block is a new "
                                + "id. It was skipped."));
                continue;
            }
            Map<String, Object> translated = block(library, block, blockText.get(block.id),
                    blockItemModel.get(block.id), diagnostics);
            out.add(new CraftEngine.Output(ContentKind.BLOCK, block.path(), translated, block.origin));
            written.add(block.id);
        }

        // Furniture no item places: CraftEngine gives it the item its
        // settings name, or one under its own id. Here that is an item of
        // its own that wears the model its first element shows.
        for (CraftEngine.Entry piece : furniture.values()) {
            if (usedFurniture.contains(piece.id) || written.contains(piece.id)) continue;
            Map<String, Object> variant = chosenVariant(piece.body);
            Map<String, Object> element = variant == null ? null : firstElement(variant);
            String shown = element == null ? null : string(element.get("item"));
            String reference = shown == null ? null : library.reference(full(shown, "minecraft"), "minecraft");
            if (reference == null || !reference.contains(":") || reference.equals(library.ours(piece))) {
                diagnostics.add(Diagnostic.warning(piece.origin, piece.id,
                        "is furniture no item places, and the model it shows is not an item of a loaded pack, "
                                + "so there is nothing to put down. It was skipped."));
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("material", "PAPER");
            item.put("copy-model", reference);
            item.put("place", place(library, piece, piece.body, null, diagnostics));
            CraftEngineEvents.translate(get(piece.body, "event", "events"), CraftEngineEvents.Owner.FURNITURE,
                    true, piece.id, piece.origin, diagnostics, item);
            out.add(new CraftEngine.Output(ContentKind.ITEM, piece.path(), item, piece.origin));
            diagnostics.add(Diagnostic.warning(piece.origin, piece.id,
                    "is furniture no item places, so it came across as an item of its own, " + library.ours(piece)
                            + ", wearing " + reference + "'s model."));
        }
    }

    // ---- items --------------------------------------------------------------------

    static Map<String, Object> item(CraftEngine.Library library, CraftEngine.Entry entry,
                                    List<Diagnostic> diagnostics) {
        Map<String, Object> body = entry.body;
        Map<String, Object> out = new LinkedHashMap<>();
        String material = material(string(body.get("material")));
        out.put("material", material);

        Map<String, Object> data = normalised(body.get("data"));
        Map<String, Object> settings = normalised(body.get("settings"));
        List<String> skipped = new ArrayList<>();
        components(library, entry, data, material, out, skipped, diagnostics);
        if (!skipped.isEmpty()) {
            diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                    "the data " + String.join(", ", skipped) + " have no RP Engine property and were skipped. "
                            + "The item itself still loads."));
        }
        settings(library, entry, settings, material, out, diagnostics);
        art(entry, body, material, out, library.generated, diagnostics);

        for (String key : List.of("updater", "client_bound_data", "client_bound_material", "client_bound_model",
                "custom_model_data", "item_model", "override_data")) {
            if (get(body, key) != null) {
                diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                        key + " was skipped: RP Engine gives each item its model by id."));
            }
        }
        return out;
    }

    /** {@code minecraft:golden_sword} to {@code GOLDEN_SWORD}; none is {@code PAPER}. */
    static String material(String declared) {
        if (declared == null || declared.isBlank()) return "PAPER";
        String name = declared.trim().toLowerCase(Locale.ROOT);
        if (name.startsWith("minecraft:")) name = name.substring("minecraft:".length());
        return name.toUpperCase(Locale.ROOT);
    }

    /** Text out of MiniMessage, with what could not come across named. */
    static String text(CraftEngine.Library library, CraftEngine.Entry entry, String raw, String field,
                       List<Diagnostic> diagnostics) {
        CraftEngineText.Converted converted = CraftEngineText.convert(raw, library::english);
        if (!converted.unresolved().isEmpty()) {
            diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                    field + " uses the language key" + (converted.unresolved().size() == 1 ? " " : "s ")
                            + String.join(", ", converted.unresolved()) + ", which no loaded pack gives English "
                            + "text for, so the key itself is shown."));
        }
        if (!converted.dropped().isEmpty()) {
            diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                    field + ": the MiniMessage tags " + String.join(", ", converted.dropped())
                            + " were removed; RP Engine text is & colour codes."));
        }
        return converted.text();
    }

    /** The {@code data:} block, and the components inside {@code components:}. */
    private static void components(CraftEngine.Library library, CraftEngine.Entry entry, Map<String, Object> data,
                                   String material, Map<String, Object> out, List<String> skipped,
                                   List<Diagnostic> diagnostics) {
        for (Map.Entry<String, Object> component : data.entrySet()) {
            String key = component.getKey();
            if (key.startsWith("minecraft:")) key = key.substring("minecraft:".length());
            Object value = component.getValue();
            switch (key) {
                case "item_name":
                case "display_name":
                case "custom_name": {
                    String name = string(value);
                    // A custom name is drawn over an item name, so it wins.
                    if (name != null && (!out.containsKey("name") || key.equals("custom_name"))) {
                        out.put("name", text(library, entry, name, key, diagnostics));
                    }
                    break;
                }
                case "lore": {
                    List<String> lines = new ArrayList<>();
                    for (Object line : list(value)) {
                        Map<String, Object> block = map(line);
                        if (block != null) {
                            lines.addAll(strings(block.get("content")));
                        } else if (line != null) {
                            lines.add(line.toString());
                        }
                    }
                    List<String> converted = new ArrayList<>();
                    for (String line : lines) converted.add(text(library, entry, line, "lore", diagnostics));
                    if (!converted.isEmpty()) out.put("lore", converted);
                    break;
                }
                case "unbreakable":
                    if (!(value instanceof Boolean b) || b) out.put("unbreakable", true);
                    break;
                case "enchantment":
                case "enchantments":
                    enchantments(entry, value, out, diagnostics);
                    break;
                case "attribute_modifiers":
                case "attributes":
                    attributes(entry, value, out, diagnostics);
                    break;
                case "food":
                    food(value, out);
                    break;
                case "max_damage": {
                    Double uses = number(value);
                    if (uses != null) out.put("durability", uses.intValue());
                    else skipped.add(key);
                    break;
                }
                case "max_stack_size": {
                    Double stack = number(value);
                    if (stack != null) out.put("stack", stack.intValue());
                    else skipped.add(key);
                    break;
                }
                case "enchantment_glint_override":
                    if (truthy(value)) {
                        out.put("glow", true);
                    } else {
                        diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                                "enchantment_glint_override: false hides the glint of an enchanted item, which "
                                        + "RP Engine has no setting for, so it was skipped."));
                    }
                    break;
                case "equippable":
                    equipment(library, entry, map(value), material, out, diagnostics);
                    break;
                case "components":
                case "component":
                    components(library, entry, normalised(value), material, out, skipped, diagnostics);
                    break;
                default:
                    skipped.add(key);
            }
        }
    }

    private static void enchantments(CraftEngine.Entry entry, Object value, Map<String, Object> out,
                                     List<Diagnostic> diagnostics) {
        Map<String, Object> declared = map(value);
        if (declared == null) return;
        if (declared.get("enchantments") instanceof Map<?, ?> inner) {
            declared = CraftEngineYaml.cast(inner);
        }
        Map<String, Object> enchants = new LinkedHashMap<>();
        for (Map.Entry<String, Object> enchant : declared.entrySet()) {
            if (enchant.getKey().equals("merge")) continue;
            Double level = number(enchant.getValue());
            if (level == null) {
                diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                        "enchantment " + enchant.getKey() + " is not a level, so it was skipped."));
                continue;
            }
            String name = enchant.getKey().toLowerCase(Locale.ROOT);
            if (!name.startsWith("minecraft:") && name.contains(":")) {
                diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                        "enchantment " + name + " is not a vanilla one, so it was skipped."));
                continue;
            }
            enchants.put(name.substring(name.indexOf(':') + 1), level.intValue());
        }
        if (!enchants.isEmpty()) out.put("enchantments", enchants);
    }

    /** {@code [{type, amount, operation, slot}]}; CraftEngine names the attribute {@code type}. */
    private static void attributes(CraftEngine.Entry entry, Object value, Map<String, Object> out,
                                   List<Diagnostic> diagnostics) {
        List<Object> attributes = new ArrayList<>();
        for (Object raw : list(value)) {
            Map<String, Object> modifier = map(raw);
            if (modifier == null) continue;
            String attribute = string(get(modifier, "type", "attribute"));
            Double amount = number(modifier.get("amount"));
            if (attribute == null || amount == null) {
                diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                        "an attribute modifier without a type and a plain number amount was skipped."));
                continue;
            }
            String name = NexoOraxen.attributeName(attribute);
            String operation = string(modifier.get("operation")) == null ? null
                    : NexoOraxen.operation(string(modifier.get("operation")));
            String slot = string(modifier.get("slot")) == null ? null : NexoOraxen.slot(string(modifier.get("slot")));
            if (operation == null && slot == null) {
                attributes.add(Map.of(name, amount));
                continue;
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("amount", amount);
            if (operation != null) detail.put("operation", operation);
            if (slot != null) detail.put("slot", slot);
            attributes.add(Map.of(name, detail));
        }
        if (!attributes.isEmpty()) out.put("attributes", attributes);
    }

    private static void food(Object value, Map<String, Object> out) {
        Map<String, Object> food = map(value);
        if (food == null || out.containsKey("food")) return;
        Map<String, Object> translated = new LinkedHashMap<>();
        Double nutrition = number(food.get("nutrition"));
        Double saturation = number(food.get("saturation"));
        if (nutrition != null) translated.put("nutrition", nutrition.intValue());
        if (saturation != null) translated.put("saturation", saturation);
        Object always = get(food, "can_always_eat");
        if (always != null) translated.put("always", truthy(always));
        if (!translated.isEmpty()) out.put("food", translated);
    }

    private static void settings(CraftEngine.Library library, CraftEngine.Entry entry, Map<String, Object> settings,
                                 String material, Map<String, Object> out, List<Diagnostic> diagnostics) {
        List<String> skipped = new ArrayList<>();
        for (Map.Entry<String, Object> setting : settings.entrySet()) {
            switch (setting.getKey()) {
                case "food":
                    food(setting.getValue(), out);
                    break;
                case "equipment":
                case "equippable":
                    equipment(library, entry, map(setting.getValue()), material, out, diagnostics);
                    break;
                case "keep_on_death_chance": {
                    Double chance = number(setting.getValue());
                    if (chance != null && chance >= 1) {
                        out.put("keep-on-death", true);
                    } else if (chance != null && chance > 0) {
                        diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                                "keep_on_death_chance " + chance + " is a chance, and RP Engine's keep-on-death "
                                        + "is always or never, so it was skipped."));
                    }
                    break;
                }
                default:
                    skipped.add(setting.getKey());
            }
        }
        if (!skipped.isEmpty()) {
            diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                    "the settings " + String.join(", ", skipped) + " are CraftEngine's own and were skipped. "
                            + "The item itself still loads."));
        }
    }

    /**
     * {@code equippable} in {@code data:}, or {@code equipment}/{@code equippable}
     * in {@code settings:}: which slot it is worn in, and the equipment its art
     * comes from.
     */
    private static void equipment(CraftEngine.Library library, CraftEngine.Entry entry, Map<String, Object> declared,
                                  String material, Map<String, Object> out, List<Diagnostic> diagnostics) {
        if (declared == null) return;
        String slot = string(declared.get("slot")) == null ? slotOfMaterial(material)
                : armourSlot(string(declared.get("slot")));
        if (slot == null) {
            diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                    "is equipped in " + (string(declared.get("slot")) == null ? "the slot of its material"
                            : string(declared.get("slot"))) + ", which is not head, chest, legs or feet, the slots "
                            + "RP Engine armour is worn in, so it was skipped."));
            return;
        }
        String asset = string(get(declared, "asset_id"));
        if (asset == null) {
            // No art of its own. Vanilla armour draws its own; anything else
            // worn on the head shows its item model there, which is a hat.
            if (slot.equals(slotOfMaterial(material))) return;
            if (slot.equals("head")) {
                out.put("hat", true);
                return;
            }
            diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                    "is worn in " + slot + " with no art of its own (no asset_id). RP Engine armour draws a texture, "
                            + "so it was not made armour."));
            return;
        }
        String layer = slot.equals("legs") ? "humanoid_leggings" : "humanoid";
        String assetId = full(asset, "minecraft");
        CraftEngine.Entry equipment = library.equipments.get(assetId);
        if (equipment == null) {
            // A vanilla equipment asset (minecraft:diamond) names the game's own
            // layer art, which is exactly what armor-texture means.
            out.put("armor", slot);
            out.put("armor-texture", assetId);
            return;
        }
        String type = type(equipment.body.get("type"));
        Object value = type.equals("trim")
                ? get(equipment.body, layer, layer.equals("humanoid") ? "layer0" : "layer1")
                : get(equipment.body, layer);
        if (value instanceof List<?> layers && !layers.isEmpty()) value = layers.get(0);
        if (value instanceof Map<?, ?> detail) value = CraftEngineYaml.cast(detail).get("texture");
        String texture = string(value);
        List<String> others = new ArrayList<>();
        for (String key : equipment.body.keySet()) {
            if (!Set.of("type", "humanoid", "humanoid_leggings", "humanoid-leggings", "layer0", "layer1")
                    .contains(key)) others.add(key);
        }
        if (texture == null) {
            diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                    "is drawn from " + assetId + ", which has no " + layer + " layer"
                            + (others.isEmpty() ? "" : " (only " + String.join(", ", others) + ")")
                            + ". RP Engine draws worn items on a player's body only, so it is worn as its material is."));
            return;
        }
        String location = location(texture);
        String prefix = "entity/equipment/" + layer + "/";
        String path = location.substring(location.indexOf(':') + 1);
        if (path.startsWith(prefix)) {
            out.put("armor", slot);
            out.put("armor-texture", location.substring(0, location.indexOf(':')) + ":" + path.substring(prefix.length()));
        } else if (type.equals("trim")) {
            // A trim layer names a whole texture path rather than an
            // equipment name, so the build serves that PNG at the item's own
            // equipment path.
            out.put("armor", slot);
            out.put("armor-art", location);
        } else {
            out.put("armor", slot);
            out.put("armor-texture", location);
        }
        if (!others.isEmpty()) {
            diagnostics.add(Diagnostic.warning(equipment.origin, equipment.id,
                    "the layers " + String.join(", ", others) + " were skipped: RP Engine draws a worn item on "
                            + "a player's body only."));
        }
    }

    private static String armourSlot(String slot) {
        switch (slot.trim().toLowerCase(Locale.ROOT)) {
            case "head":
            case "helmet":
            case "hat":
                return "head";
            case "chest":
            case "chestplate":
                return "chest";
            case "legs":
            case "leg":
            case "leggings":
                return "legs";
            case "feet":
            case "boots":
            case "boot":
            case "shoes":
                return "feet";
            default:
                return null;
        }
    }

    private static String slotOfMaterial(String material) {
        String name = material.toLowerCase(Locale.ROOT);
        if (name.endsWith("_helmet") || name.equals("carved_pumpkin") || name.endsWith("_head")
                || name.endsWith("_skull")) return "head";
        if (name.endsWith("_chestplate")) return "chest";
        if (name.endsWith("_leggings")) return "legs";
        if (name.endsWith("_boots")) return "feet";
        return null;
    }

    // ---- art ----------------------------------------------------------------------

    /** Materials whose generated model has a parent of its own, and how many textures that reader takes. */
    private static final Map<String, String> STATE_PARENTS = Map.of(
            "BOW", "minecraft:item/bow",
            "CROSSBOW", "minecraft:item/crossbow",
            "FISHING_ROD", "minecraft:item/fishing_rod",
            "ELYTRA", "minecraft:item/generated",
            "SHIELD", "minecraft:item/generated",
            "MACE", "minecraft:item/handheld_mace",
            "CARROT_ON_A_STICK", "minecraft:item/handheld_rod",
            "WARPED_FUNGUS_ON_A_STICK", "minecraft:item/handheld_rod");

    private static boolean handheld(String material) {
        String name = material.toLowerCase(Locale.ROOT);
        return name.endsWith("_sword") || name.endsWith("_axe") || name.endsWith("_pickaxe")
                || name.endsWith("_shovel") || name.endsWith("_hoe") || Set.of("stick", "blaze_rod",
                "breeze_rod", "bone", "bamboo", "debug_stick").contains(name);
    }

    /**
     * The picture: {@code texture(s)} first, as CraftEngine reads them, then
     * {@code model(s)}, then {@code legacy_model}.
     */
    private static void art(CraftEngine.Entry entry, Map<String, Object> body, String material,
                            Map<String, Object> out, Map<String, Map<String, Object>> generatedModels,
                            List<Diagnostic> diagnostics) {
        List<String> textures = strings(get(body, "texture", "textures"));
        if (!textures.isEmpty()) {
            List<String> locations = new ArrayList<>();
            for (String texture : textures) locations.add(location(texture));
            String parent = STATE_PARENTS.get(material);
            if (parent != null || material.endsWith("_SPEAR")) {
                if (locations.size() > 1) {
                    diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                            "its other " + (locations.size() - 1) + " texture" + (locations.size() == 2 ? " is" : "s are")
                                    + " the states " + material.toLowerCase(Locale.ROOT) + " switches between "
                                    + "(drawn, cast, broken...), which an RP Engine item does not; only the first "
                                    + "is drawn."));
                }
                out.put("model", inline(parent == null ? "minecraft:item/generated" : parent,
                        Map.of("layer0", locations.get(0))));
                return;
            }
            if (material.startsWith("LEATHER_")) {
                diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                        "a dyed leather item's tint is not carried; its texture is drawn undyed."));
            }
            boolean held = handheld(material);
            if (locations.size() == 1 && !held) {
                out.put("texture", locations.get(0));
                return;
            }
            Map<String, Object> layers = new LinkedHashMap<>();
            for (int i = 0; i < locations.size(); i++) layers.put("layer" + i, locations.get(i));
            out.put("model", inline(held ? "minecraft:item/handheld" : "minecraft:item/generated", layers));
            return;
        }
        Object declared = get(body, "model", "models");
        if (declared == null) declared = get(body, "legacy_model");
        if (declared == null && get(body, "blueprint") != null) {
            diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                    "blueprint is CraftEngine's own Blockbench reader; save the .bbmodel into assets/models/ and "
                            + "set model: to it."));
            return;
        }
        if (declared == null) return;
        List<String> lost = new ArrayList<>();
        Object model = modelDefinition(declared, lost, generatedModels);
        if (model != null) out.put("model", model);
        if (!lost.isEmpty()) {
            diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                    "its model definition switches between models (" + String.join(", ", lost) + "), which an RP "
                            + "Engine item does not; it wears " + (model instanceof String ? model : "the default one")
                            + " always."));
        }
    }

    /**
     * An item model definition as one model: a resource location, or a model
     * written inline for one CraftEngine generates. {@code lost} collects
     * what switched between models.
     */
    static Object modelDefinition(Object declared, List<String> lost, Map<String, Map<String, Object>> generated) {
        if (declared instanceof String path) {
            return known(location(path), generated);
        }
        if (declared instanceof List<?> models) {
            if (models.isEmpty()) return null;
            if (models.size() > 1) lost.add("a list of " + models.size() + " models");
            return modelDefinition(models.get(0), lost, generated);
        }
        Map<String, Object> model = map(declared);
        if (model == null) return null;
        String type = type(model.get("type"));
        if (type.isEmpty()) type = "model";
        switch (type) {
            case "model": {
                if (model.get("tints") != null) lost.add("tints");
                Map<String, Object> generation = map(model.get("generation"));
                if (generation != null) return generated(generation);
                String path = string(get(model, "path", "model"));
                return path == null ? null : known(location(path), generated);
            }
            case "composite": {
                lost.add("composite");
                List<Object> models = list(model.get("models"));
                return models.isEmpty() ? null : modelDefinition(models.get(0), new ArrayList<>(), generated);
            }
            case "condition":
                lost.add("condition " + string(model.get("property")));
                return modelDefinition(get(model, "on_false"), new ArrayList<>(), generated);
            case "select": {
                lost.add("select " + string(model.get("property")));
                Object fallback = model.get("fallback");
                if (fallback == null) {
                    List<Object> cases = list(model.get("cases"));
                    fallback = cases.isEmpty() || map(cases.get(0)) == null ? null : map(cases.get(0)).get("model");
                }
                return modelDefinition(fallback, new ArrayList<>(), generated);
            }
            case "range_dispatch": {
                lost.add("range_dispatch " + string(model.get("property")));
                Object fallback = model.get("fallback");
                if (fallback == null) {
                    List<Object> entries = list(model.get("entries"));
                    fallback = entries.isEmpty() || map(entries.get(0)) == null ? null : map(entries.get(0)).get("model");
                }
                return modelDefinition(fallback, new ArrayList<>(), generated);
            }
            case "special": {
                Map<String, Object> special = map(model.get("model"));
                lost.add("special " + (special == null ? "" : type(special.get("type"))));
                Map<String, Object> generation = map(model.get("generation"));
                if (generation != null) return generated(generation);
                String path = string(get(model, "base", "path"));
                return path == null ? null : location(path);
            }
            default:
                lost.add(type);
                return null;
        }
    }

    /**
     * A model CraftEngine generates rather than ships - a block's cube from
     * its textures, or a {@code generation:} at a path - written inline when
     * something names it by that path, because no file is there to read.
     */
    private static Object known(String location, Map<String, Map<String, Object>> generated) {
        Map<String, Object> model = generated == null ? null : generated.get(location);
        return model == null ? location : new LinkedHashMap<>(model);
    }

    /** Every model a loaded pack has CraftEngine generate, by the path it would be written to. */
    static void collectGenerated(CraftEngine.Entry entry, Map<String, Map<String, Object>> into) {
        collect(entry.body, into);
        if (!entry.section.equals("blocks")) return;
        Map<String, Object> state = map(get(entry.body, "state", "states"));
        if (state == null) return;
        List<Map<String, Object>> appearances = new ArrayList<>();
        appearances.add(state);
        Map<String, Object> named = map(get(state, "appearance", "appearances"));
        if (named != null) {
            for (Object appearance : named.values()) {
                if (map(appearance) != null) appearances.add(map(appearance));
            }
        }
        for (Map<String, Object> appearance : appearances) {
            List<String> textures = strings(get(appearance, "texture", "textures"));
            if (textures.isEmpty()) continue;
            String path = string(get(appearance, "model", "models"));
            if (path == null && textures.size() == 1) path = textures.get(0).replace("^", "");
            if (path != null) into.putIfAbsent(location(path), cube(textures));
        }
    }

    private static void collect(Object node, Map<String, Map<String, Object>> into) {
        Map<String, Object> map = map(node);
        if (map != null) {
            Map<String, Object> generation = map(map.get("generation"));
            String path = string(get(map, "path", "model"));
            if (generation != null && path != null && string(generation.get("parent")) != null) {
                into.putIfAbsent(location(path), generated(generation));
            }
            for (Object value : map.values()) collect(value, into);
        } else if (node instanceof List<?> list) {
            for (Object value : list) collect(value, into);
        }
    }

    /** {@code generation: {parent, textures, display, gui_light, ambient_occlusion}} as a model of its own. */
    static Map<String, Object> generated(Map<String, Object> generation) {
        Map<String, Object> textures = new LinkedHashMap<>();
        Map<String, Object> declared = map(generation.get("textures"));
        if (declared != null) {
            for (Map.Entry<String, Object> texture : declared.entrySet()) {
                String value = string(texture.getValue());
                if (value == null) continue;
                textures.put(texture.getKey(), value.startsWith("#") ? value : location(value));
            }
        }
        Map<String, Object> model = inline(location(string(generation.get("parent"))), textures);
        Map<String, Object> displays = map(generation.get("display"));
        if (displays != null) {
            Map<String, Object> display = new LinkedHashMap<>();
            for (Map.Entry<String, Object> position : displays.entrySet()) {
                Map<String, Object> transform = map(position.getValue());
                if (transform == null) continue;
                Map<String, Object> written = new LinkedHashMap<>();
                for (String key : List.of("rotation", "translation", "scale")) {
                    double[] vector = vector(transform.get(key));
                    if (vector != null) written.put(key, List.of(vector[0], vector[1], vector[2]));
                }
                display.put(position.getKey().toLowerCase(Locale.ROOT), written);
            }
            model.put("display", display);
        }
        Object guiLight = get(generation, "gui_light");
        if (guiLight != null) model.put("gui_light", guiLight.toString().toLowerCase(Locale.ROOT));
        Object occlusion = get(generation, "ambientocclusion", "ambient_occlusion");
        if (occlusion != null) model.put("ambientocclusion", truthy(occlusion));
        return model;
    }

    static Map<String, Object> inline(String parent, Map<String, Object> textures) {
        Map<String, Object> model = new LinkedHashMap<>();
        if (parent != null) model.put("parent", parent);
        if (!textures.isEmpty()) model.put("textures", new LinkedHashMap<>(textures));
        return model;
    }

    // ---- furniture ------------------------------------------------------------------

    private static Map<String, Object> chosenVariant(Map<String, Object> furniture) {
        Map<String, Object> variants = map(get(furniture, "variant", "variants", "placement"));
        if (variants == null || variants.isEmpty()) return null;
        for (String name : List.of("ground", "wall", "ceiling")) {
            if (map(variants.get(name)) != null) return map(variants.get(name));
        }
        return map(variants.values().iterator().next());
    }

    private static Map<String, Object> firstElement(Map<String, Object> variant) {
        for (Object raw : list(variant.get("elements"))) {
            Map<String, Object> element = map(raw);
            if (element != null) return element;
        }
        return null;
    }

    /** One piece of furniture as a {@code place:} block. */
    private static Map<String, Object> place(CraftEngine.Library library, CraftEngine.Entry owner,
                                             Map<String, Object> furniture, Map<String, Object> rules,
                                             List<Diagnostic> diagnostics) {
        Map<String, Object> place = new LinkedHashMap<>();
        String id = owner.id;
        String origin = owner.origin;
        Map<String, Object> variants = map(get(furniture, "variant", "variants", "placement"));
        if (variants == null) variants = new LinkedHashMap<>();

        // Surfaces: only ground, wall and ceiling are ever placed.
        List<String> surfaces = new ArrayList<>();
        String chosenName = null;
        for (String name : List.of("ground", "wall", "ceiling")) {
            if (variants.containsKey(name)) {
                surfaces.add(name);
                if (chosenName == null) chosenName = name;
            }
        }
        List<String> others = new ArrayList<>();
        for (String name : variants.keySet()) {
            if (!surfaces.contains(name)) others.add(name);
        }
        if (chosenName == null && !variants.isEmpty()) chosenName = variants.keySet().iterator().next();
        if (surfaces.size() == 1) {
            place.put("surface", surfaces.get(0).equals("ground") ? "floor" : surfaces.get(0));
        } else if (surfaces.size() > 1) {
            place.put("surface", "any");
            diagnostics.add(Diagnostic.warning(origin, id,
                    "has " + String.join(", ", surfaces) + " variants. RP Engine places one model on any surface, "
                            + "so the " + chosenName + " variant's model, hitbox and seat are used on all of them."
                            + (surfaces.size() == 2 ? " It may also go on the surface it had no variant for." : "")));
        }
        if (!others.isEmpty()) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "the variants " + String.join(", ", others) + " are switched to by CraftEngine's own functions, "
                            + "which RP Engine does not have, so they were skipped."));
        }
        Map<String, Object> variant = chosenName == null ? new LinkedHashMap<>() : map(variants.get(chosenName));
        if (variant == null) variant = new LinkedHashMap<>();

        elements(library, owner, variant, place, diagnostics);
        hitboxes(owner, variant, place, diagnostics);

        // Facing, from the item's placement rules (or the legacy ones under
        // the variant). CraftEngine's default is any angle.
        Map<String, Object> rule = rules == null ? null : map(rules.get(chosenName));
        if (rule == null) rule = map(get(variant, "rules"));
        String rotation = rule == null || string(rule.get("rotation")) == null ? "any"
                : string(rule.get("rotation")).toLowerCase(Locale.ROOT);
        switch (rotation) {
            case "four":
                place.put("facing", "cardinal");
                break;
            case "eight":
                place.put("facing", "diagonal");
                break;
            case "sixteen":
                place.put("facing", "diagonal");
                diagnostics.add(Diagnostic.warning(origin, id,
                        "rotation sixteen turns in 22.5 degree steps; RP Engine's nearest is diagonal, 45."));
                break;
            case "north":
            case "south":
            case "east":
            case "west":
                place.put("facing", "fixed");
                diagnostics.add(Diagnostic.warning(origin, id,
                        "rotation " + rotation + " always faces one way; it came across as facing: fixed."));
                break;
            default:
                place.put("facing", "free");
        }
        String alignment = rule == null || string(rule.get("alignment")) == null ? "any"
                : string(rule.get("alignment")).toLowerCase(Locale.ROOT);
        if (!alignment.equals("any") && !alignment.equals("center")) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "alignment " + alignment + " snaps it inside its block, which RP Engine does not; it is placed "
                            + "in the middle of the block."));
        }

        light(owner, furniture, chosenName, place, diagnostics);
        if (place.containsKey("solid") && place.containsKey("light")) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "is both solid and a light. One block cannot be a barrier and a light at once, and RP Engine "
                            + "keeps the barrier."));
        }
        drop(library, owner, map(get(furniture, "loot", "loots")), place, true, diagnostics);

        Map<String, Object> settings = normalised(furniture.get("settings"));
        List<String> skipped = new ArrayList<>();
        for (String key : settings.keySet()) {
            if (!key.equals("item")) skipped.add(key);
        }
        if (!skipped.isEmpty()) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "furniture settings " + String.join(", ", skipped) + " have no RP Engine equivalent and were "
                            + "skipped. The piece itself still places."));
        }
        return place;
    }

    /** The first element is the model; RP Engine places the item's own. */
    private static void elements(CraftEngine.Library library, CraftEngine.Entry owner, Map<String, Object> variant,
                                 Map<String, Object> place, List<Diagnostic> diagnostics) {
        String id = owner.id;
        String origin = owner.origin;
        List<Object> elements = list(variant.get("elements"));
        if (get(variant, "blueprint", "better_model", "model_engine") != null) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "is drawn by a BetterModel or ModelEngine model, which RP Engine cannot call; save the "
                            + ".bbmodel into blueprints/ or assets/models/ and give the item that model."));
        }
        if (elements.isEmpty()) return;
        Map<String, Object> element = map(elements.get(0));
        if (element == null) return;
        if (elements.size() > 1) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "has " + elements.size() + " elements. An RP Engine piece is one model, the item's own, so "
                            + "only the first came across; merge them into one model to keep the rest."));
        }
        String type = type(element.get("type"));
        if (!type.isEmpty() && !type.equals("item_display")) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "its first element is a " + type + "; RP Engine places the item's model as an item display."));
        }
        String shown = string(element.get("item"));
        if (shown != null && !full(shown, "minecraft").equals(id)) {
            String reference = library.reference(full(shown, "minecraft"), "minecraft");
            diagnostics.add(Diagnostic.warning(origin, id,
                    "shows " + shown + "'s model when placed. RP Engine places the item's own model, so give this "
                            + "item that model" + (reference != null && reference.contains(":")
                            ? " (copy-model: " + reference + ")" : "") + " if they differ."));
        }
        String transform = string(get(element, "display_transform", "display_context"));
        if (transform != null && !transform.equalsIgnoreCase("none")) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "display_transform " + transform + " uses the model's own " + transform.toLowerCase(Locale.ROOT)
                            + " transform; RP Engine places a model untransformed, at its real size."));
        }
        double[] translation = vector(element.get("translation"));
        if (translation != null && !(translation[0] == 0 && translation[1] == 0.5 && translation[2] == 0)) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "its element is translated by " + vectorText(translation) + ". RP Engine stands a model's base "
                            + "on the block (what 0,0.5,0 does for an untransformed display), so move the offset "
                            + "into the model itself."));
        }
        double[] position = vector(element.get("position"));
        if (!CraftEngine.isZero(position)) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "its element is placed " + vectorText(position) + " from the anchor; RP Engine places the model "
                            + "at the anchor."));
        }
        Object rotation = element.get("rotation");
        Double yaw = number(element.get("yaw"));
        Double pitch = number(element.get("pitch"));
        if ((rotation != null && !isIdentity(rotation)) || (yaw != null && yaw != 0) || (pitch != null && pitch != 0)) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "its element is turned; RP Engine places the model as the file draws it, so turn it in the "
                            + "model instead."));
        }
        double[] scale = vector(element.get("scale"));
        if (scale != null) {
            if (scale[0] == scale[1] && scale[1] == scale[2]) {
                if (scale[0] != 1) place.put("scale", scale[0]);
            } else {
                diagnostics.add(Diagnostic.warning(origin, id,
                        "its scale is not the same on every axis, and RP Engine scales a placed model by one "
                                + "number, so it was skipped."));
            }
        }
    }

    private static boolean isIdentity(Object rotation) {
        double[] parts = vector(rotation);
        if (parts != null) return CraftEngine.isZero(parts);
        Double single = number(rotation);
        if (single != null) return single == 0;
        List<Object> list = list(rotation instanceof String text ? List.of(text.split(",")) : rotation);
        if (list.size() == 4) {
            Double x = number(list.get(0));
            Double y = number(list.get(1));
            Double z = number(list.get(2));
            Double w = number(list.get(3));
            return x != null && y != null && z != null && w != null && x == 0 && y == 0 && z == 0 && w == 1;
        }
        return false;
    }

    private static String vectorText(double[] vector) {
        return trim(vector[0]) + "," + trim(vector[1]) + "," + trim(vector[2]);
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    /** Hitboxes and the seats they carry. */
    private static void hitboxes(CraftEngine.Entry owner, Map<String, Object> variant, Map<String, Object> place,
                                 List<Diagnostic> diagnostics) {
        String id = owner.id;
        String origin = owner.origin;
        List<Object> hitboxes = list(variant.get("hitboxes"));
        boolean sized = false;
        int unused = 0;
        List<Object> seats = new ArrayList<>();
        for (Object raw : hitboxes) {
            Map<String, Object> hitbox = map(raw);
            if (hitbox == null) continue;
            seats.addAll(list(hitbox.get("seats")));
            String type = type(hitbox.get("type"));
            if (type.isEmpty()) type = "interaction";
            boolean atAnchor = CraftEngine.isZero(vector(hitbox.get("position")));
            switch (type) {
                case "interaction": {
                    if (!atAnchor || sized) {
                        unused++;
                        break;
                    }
                    double width = number(hitbox.get("width")) == null ? 1 : number(hitbox.get("width"));
                    double height = number(hitbox.get("height")) == null ? 1 : number(hitbox.get("height"));
                    Object scale = hitbox.get("scale");
                    if (scale != null) {
                        String[] parts = scale.toString().split(",");
                        Double w = number(parts[0]);
                        Double h = parts.length > 1 ? number(parts[1]) : w;
                        if (w != null) width = w;
                        if (h != null) height = h;
                    }
                    place.put("width", width);
                    place.put("height", height);
                    sized = true;
                    break;
                }
                case "shulker":
                case "happy_ghast": {
                    double scale = number(hitbox.get("scale")) == null ? 1 : number(hitbox.get("scale"));
                    place.put("solid", true);
                    if (!atAnchor || scale != 1 || type.equals("happy_ghast")) {
                        diagnostics.add(Diagnostic.warning(origin, id,
                                "its " + type + " hitbox is solid; RP Engine's solid: true is one barrier at the "
                                        + "anchor block, so only that block is solid."));
                    }
                    break;
                }
                default:
                    unused++;
                    diagnostics.add(Diagnostic.warning(origin, id,
                            "its " + type + " hitbox has no RP Engine equivalent and was skipped."));
            }
        }
        if (unused > 0) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "has " + (unused + (sized ? 1 : 0)) + " interaction hitboxes. RP Engine has one, at the anchor"
                            + (sized ? ", taken from the one at 0,0,0" : ", measured from the model")
                            + "; the rest were skipped."));
        }
        seat(owner, seats, place, diagnostics);
    }

    /**
     * The first seat: {@code "x,y,z"}, {@code "x,y,z yaw"},
     * {@code "x,y,z yaw force"} or {@code {position, yaw}}.
     *
     * <p>CraftEngine's offsets are in the piece's own frame - x to its right,
     * y up, z out of its front - which is RP Engine's, so the side and
     * forward offsets carry over as written and only the height moves; see
     * {@link #CRAFTENGINE_SEAT_LIFT}.
     */
    private static void seat(CraftEngine.Entry owner, List<Object> seats, Map<String, Object> place,
                             List<Diagnostic> diagnostics) {
        if (seats.isEmpty()) return;
        String id = owner.id;
        String origin = owner.origin;
        Object first = seats.get(0);
        double[] position;
        double yaw = 0;
        Map<String, Object> mapped = map(first);
        if (mapped != null) {
            position = vector(mapped.get("position"));
            Double declared = number(mapped.get("yaw"));
            if (declared != null) yaw = declared;
        } else {
            String[] parts = String.valueOf(first).trim().split("\\s+");
            position = vector(parts[0]);
            if (parts.length > 1 && number(parts[1]) != null) yaw = number(parts[1]);
        }
        if (position == null) {
            diagnostics.add(Diagnostic.warning(origin, id, "seat " + first + " is not x,y,z, so it was skipped."));
            return;
        }
        double y = CraftEngine.round(position[1] + CRAFTENGINE_SEAT_LIFT);
        if (position[0] == 0 && position[2] == 0) {
            place.put("seat", y);
        } else {
            Map<String, Object> offset = new LinkedHashMap<>();
            offset.put("x", position[0]);
            offset.put("y", y);
            offset.put("z", position[2]);
            place.put("seat", offset);
        }
        diagnostics.add(Diagnostic.warning(origin, id,
                "seat height " + y + " was converted from CraftEngine's seat (y + " + CraftEngine.round(CRAFTENGINE_SEAT_LIFT)
                        + "). If players sit too high or low, adjust place.seat."));
        if (seats.size() > 1) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "has " + seats.size() + " seats. RP Engine seats one player per piece, so only the first came across."));
        }
        if (yaw != 0) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "the seat's own yaw has no RP Engine equivalent; the rider faces the way the piece does."));
        }
    }

    /**
     * {@code glowing_furniture}'s light at the origin. A light that belongs
     * only to a variant CraftEngine switches to (a lamp's lit state) is not
     * carried, because RP Engine's light is always on.
     */
    private static void light(CraftEngine.Entry owner, Map<String, Object> furniture, String chosen,
                              Map<String, Object> place, List<Diagnostic> diagnostics) {
        String id = owner.id;
        String origin = owner.origin;
        List<String> skipped = new ArrayList<>();
        for (Object raw : list(get(furniture, "behavior", "behaviors"))) {
            Map<String, Object> behaviour = map(raw);
            if (behaviour == null) continue;
            String type = type(behaviour.get("type"));
            if (!type.equals("glowing_furniture")) {
                skipped.add(type);
                continue;
            }
            List<Object> lights = new ArrayList<>(list(behaviour.get("lights")));
            Map<String, Object> byVariant = map(behaviour.get("variants"));
            if (byVariant != null && chosen != null) lights.addAll(list(byVariant.get(chosen)));
            if (lights.isEmpty()) {
                if (byVariant != null && !byVariant.isEmpty()) {
                    diagnostics.add(Diagnostic.warning(origin, id,
                            "lights up only in " + String.join(", ", byVariant.keySet()) + ", switched to by its "
                                    + "events. RP Engine light is always on, so it was given none."));
                }
                continue;
            }
            Integer level = null;
            int entries = 0;
            for (Object light : lights) {
                entries++;
                double[] position;
                int value;
                Map<String, Object> mapped = map(light);
                if (mapped != null) {
                    position = vector(mapped.get("position"));
                    value = number(mapped.get("level")) == null ? 15 : number(mapped.get("level")).intValue();
                } else {
                    String[] parts = String.valueOf(light).trim().split("\\s+");
                    position = vector(parts[0]);
                    value = parts.length > 1 && number(parts[1]) != null ? number(parts[1]).intValue() : 15;
                }
                if (level == null || CraftEngine.isZero(position)) level = Math.max(0, Math.min(15, value));
            }
            if (level != null) place.put("light", level);
            if (entries > 1) {
                diagnostics.add(Diagnostic.warning(origin, id,
                        "has " + entries + " lights. RP Engine lights the anchor block only, at level " + level + "."));
            }
        }
        if (!skipped.isEmpty()) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "the furniture behaviours " + String.join(", ", skipped) + " are CraftEngine's own and were "
                            + "skipped. The piece itself still places."));
        }
    }

    // ---- blocks -----------------------------------------------------------------------

    /** Settings that only say a block is a full, solid, ordinary cube, which a note block already is. */
    private static final Set<String> CUBE_SETTINGS = Set.of("is_suffocating", "is_view_blocking",
            "is_redstone_conductor", "can_occlude", "replaceable", "propagate_skylight",
            "use_shape_for_light_occlusion", "instrument", "map_color", "tags", "item", "name",
            "correct_tools", "require_correct_tools", "required_break_power", "hardness", "sounds", "push_reaction",
            "block_raytrace");

    private static Map<String, Object> block(CraftEngine.Library library, CraftEngine.Entry entry,
                                             Map<String, Object> itemText, String itemModel,
                                             List<Diagnostic> diagnostics) {
        Map<String, Object> out = new LinkedHashMap<>();
        String id = entry.id;
        String origin = entry.origin;
        if (itemText != null) {
            if (itemText.get("name") != null) out.put("name", itemText.get("name"));
            if (itemText.get("lore") != null) out.put("lore", itemText.get("lore"));
            if (itemText.get("actions") != null) out.put("actions", itemText.get("actions"));
        }

        CraftEngineBlocks.translate(library, entry, out, diagnostics);
        if (itemModel != null && out.get("model") != null && !itemModel.equals(String.valueOf(out.get("model")))) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "the item that places it has a model of its own. In RP Engine a block's item wears the "
                            + "block's model."));
        }

        Map<String, Object> settings = normalised(entry.body.get("settings"));
        Double hardness = number(settings.get("hardness"));
        if (hardness != null) out.put("hardness", hardness);
        String tool = tool(settings);
        Double power = number(settings.get("required_break_power"));
        if (tool != null && (truthy(settings.get("require_correct_tools")) || (power != null && power > 0))) {
            out.put("tool", tool);
            if (power != null && power > 1) {
                diagnostics.add(Diagnostic.warning(origin, id,
                        "asks for break power " + power.intValue() + ". RP Engine asks for the kind of tool only, so "
                                + "any " + tool + " gets the drop."));
            }
        } else if (power != null && power > 0) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "asks for break power " + power.intValue() + " without saying which tool, so no tool is "
                            + "required here."));
        }
        sound(entry, map(settings.get("sounds")), out, diagnostics);
        Double luminance = number(settings.get("luminance"));
        if (luminance != null && luminance > 0 && !"bulb".equals(out.get("shape"))) {
            out.put("light", luminance.intValue());
        }
        List<String> skipped = new ArrayList<>();
        for (Map.Entry<String, Object> setting : settings.entrySet()) {
            String key = setting.getKey();
            if (CUBE_SETTINGS.contains(key) || key.equals("luminance")) continue;
            skipped.add(key);
        }
        if (!skipped.isEmpty()) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "block settings " + String.join(", ", skipped) + " have no RP Engine equivalent and were skipped."));
        }

        Object loot = entry.body.get("loot");
        if (loot instanceof String reference) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "uses the loot table " + reference + ", which RP Engine does not read, so it drops itself."));
        } else if (loot == null) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "has no loot, so CraftEngine drops nothing; an RP Engine block drops itself."));
        } else {
            drop(library, entry, map(loot), out, false, diagnostics);
        }

        clickCycles(entry, out);
        CraftEngineEvents.translate(get(entry.body, "event", "events"), CraftEngineEvents.Owner.BLOCK, true, id,
                origin, diagnostics, out);
        return out;
    }

    /**
     * A right-click event that only turns one of the block's properties over
     * ({@code cycle_block_property}, as a lamp or a safe does) is the block's
     * own {@code click:} here, which needs no action at all.
     */
    private static void clickCycles(CraftEngine.Entry entry, Map<String, Object> out) {
        for (Object raw : list(get(entry.body, "event", "events"))) {
            Map<String, Object> event = map(raw);
            if (event == null) continue;
            // YAML reads a bare on: as true, which CraftEngine's own reader allows for.
            boolean rightClick = false;
            for (String on : strings(event.containsKey("on") ? event.get("on") : event.get("true"))) {
                rightClick |= on.endsWith("right_click") || on.endsWith("use_on") || on.endsWith("use");
            }
            if (!rightClick) continue;
            for (Object function : list(get(event, "function", "functions"))) {
                Map<String, Object> step = map(function);
                if (step != null && type(step.get("type")).equals("cycle_block_property")
                        && string(step.get("property")) != null) {
                    out.putIfAbsent("click", string(step.get("property")));
                }
            }
        }
    }

    /**
     * The cube model CraftEngine generates from one to six textures:
     * {@code cube_all}, {@code cube_column} (end, side),
     * {@code cube_bottom_top} (bottom, side, top), {@code orientable}
     * (bottom, front, side, top) or {@code cube} (down, up, north, south,
     * west, east). A file name ending in a slot's name goes to that slot;
     * the rest fill the others in order. A {@code ^} marks the particle.
     */
    static Map<String, Object> cube(List<String> declared) {
        String particle = null;
        List<String> textures = new ArrayList<>();
        for (String texture : declared) {
            if (texture.startsWith("^")) {
                particle = location(texture.substring(1));
                textures.add(particle);
            } else {
                textures.add(location(texture));
            }
        }
        String parent;
        List<String> slots;
        switch (textures.size()) {
            case 1:
                parent = "minecraft:block/cube_all";
                slots = List.of("all");
                break;
            case 2:
                parent = "minecraft:block/cube_column";
                slots = List.of("end", "side");
                break;
            case 3:
                parent = "minecraft:block/cube_bottom_top";
                slots = List.of("bottom", "side", "top");
                break;
            case 4:
                parent = "minecraft:block/orientable";
                slots = List.of("bottom", "front", "side", "top");
                break;
            default:
                parent = "minecraft:block/cube";
                slots = List.of("down", "up", "north", "south", "west", "east");
        }
        Map<String, Object> assigned = new LinkedHashMap<>();
        List<String> left = new ArrayList<>(textures);
        for (String slot : slots) {
            for (String texture : left) {
                String name = texture.substring(texture.lastIndexOf('/') + 1);
                if (name.endsWith("_" + slot) || name.equals(slot)
                        || (slot.equals("end") && name.endsWith("_top"))
                        || (slot.equals("up") && name.endsWith("_top"))
                        || (slot.equals("down") && name.endsWith("_bottom"))) {
                    assigned.put(slot, texture);
                    left.remove(texture);
                    break;
                }
            }
        }
        for (String slot : slots) {
            if (!assigned.containsKey(slot) && !left.isEmpty()) assigned.put(slot, left.remove(0));
        }
        Map<String, Object> ordered = new LinkedHashMap<>();
        for (String slot : slots) {
            if (assigned.containsKey(slot)) ordered.put(slot, assigned.get(slot));
        }
        if (particle != null) {
            ordered.put("particle", particle);
        } else if (!parent.endsWith("cube_all") && !parent.endsWith("cube_column")
                && !parent.endsWith("cube_bottom_top") && !parent.endsWith("orientable") && !textures.isEmpty()) {
            ordered.put("particle", textures.get(0));
        }
        return inline(parent, ordered);
    }

    /** The tool {@code correct_tools} or a {@code mineable} tag asks for. */
    private static String tool(Map<String, Object> settings) {
        List<String> candidates = new ArrayList<>(strings(settings.get("correct_tools")));
        candidates.addAll(strings(settings.get("tags")));
        for (String candidate : candidates) {
            String name = candidate.toLowerCase(Locale.ROOT).replace("#", "");
            name = name.substring(name.lastIndexOf(':') + 1);
            name = name.substring(name.lastIndexOf('/') + 1);
            for (String kind : List.of("pickaxe", "shovel", "hoe", "sword", "axe")) {
                if (name.equals(kind) || name.equals(kind + "s") || name.endsWith("_" + kind)) return kind;
            }
        }
        return null;
    }

    /**
     * The place sound, the one sound a custom block plays here (over the
     * base block's own). The rest belong to the base block.
     */
    private static void sound(CraftEngine.Entry entry, Map<String, Object> sounds, Map<String, Object> out,
                              List<Diagnostic> diagnostics) {
        if (sounds == null) return;
        Object place = sounds.get("place");
        if (place instanceof Map<?, ?> detail) place = CraftEngineYaml.cast(detail).get("id");
        if (string(place) != null) out.put("sound", location(string(place)));
        List<String> others = new ArrayList<>();
        for (String key : sounds.keySet()) {
            if (!key.equals("place")) others.add(key);
        }
        if (!others.isEmpty()) {
            diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                    "block sounds " + String.join(", ", others) + " were skipped: a custom block here plays its "
                            + "place sound over the base block's own, and the rest belong to the base block."));
        }
    }

    /**
     * What a loot table drops, as one item: the first {@code item} entry of
     * the first pool, the last child of an {@code alternatives} (the drop
     * without silk touch, in every table CraftEngine ships), and nothing for
     * a furniture's {@code furniture_item}, which is the piece itself.
     */
    private static void drop(CraftEngine.Library library, CraftEngine.Entry entry, Map<String, Object> loot,
                             Map<String, Object> out, boolean furniture, List<Diagnostic> diagnostics) {
        if (loot == null) return;
        String id = entry.id;
        String origin = entry.origin;
        Set<String> notes = new LinkedHashSet<>();
        String dropped = null;
        boolean self = false;
        List<Object> pools = list(loot.get("pools"));
        for (Object rawPool : pools) {
            Map<String, Object> pool = map(rawPool);
            if (pool == null) continue;
            if (get(pool, "condition", "conditions") != null) notes.add("conditions");
            for (Object rawEntry : list(pool.get("entries"))) {
                Map<String, Object> candidate = map(rawEntry);
                while (candidate != null && Set.of("alternatives", "if_else").contains(type(candidate.get("type")))) {
                    List<Object> children = list(candidate.get("children"));
                    notes.add("alternatives");
                    candidate = children.isEmpty() ? null : map(children.get(children.size() - 1));
                }
                if (candidate == null) continue;
                String type = type(candidate.get("type"));
                if (type.equals("furniture_item")) {
                    self = true;
                    break;
                }
                if (type.equals("exp")) {
                    notes.add("experience");
                    continue;
                }
                if (!type.equals("item")) {
                    notes.add(type);
                    continue;
                }
                if (candidate.get("functions") != null) notes.add("counts and bonuses");
                if (get(candidate, "condition", "conditions") != null) notes.add("conditions");
                dropped = string(get(candidate, "item", "id"));
                break;
            }
            if (dropped != null || self) break;
        }
        if (pools.size() > 1) notes.add(pools.size() + " pools");
        if (dropped != null) {
            String full = full(dropped, "minecraft");
            String reference = library.reference(full, "minecraft");
            if (!full.equals(id)) {
                if (reference != null && reference.contains(":")) {
                    out.put("drop", reference);
                } else {
                    diagnostics.add(Diagnostic.warning(origin, id,
                            "drops " + dropped + ", and an RP Engine " + (furniture ? "piece's" : "block's")
                                    + " drop is one of the packs' own items, so it drops itself."));
                }
            }
        }
        if (!notes.isEmpty()) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "its loot table's " + String.join(", ", notes) + " were not carried: an RP Engine "
                            + (furniture ? "piece" : "block") + " drops one item"
                            + (out.get("drop") != null ? ", " + out.get("drop") : ", itself") + "."));
        }
    }

    // ---- small helpers -------------------------------------------------------------------

    /** A behaviour, hitbox or model type, without CraftEngine's or vanilla's namespace. */
    static String type(Object declared) {
        if (declared == null) return "";
        String type = declared.toString().trim().toLowerCase(Locale.ROOT);
        return type.substring(type.indexOf(':') + 1);
    }

    static String full(String id, String defaultNamespace) {
        String trimmed = id.trim();
        return trimmed.indexOf(':') >= 0 ? trimmed : defaultNamespace + ":" + trimmed;
    }
}
