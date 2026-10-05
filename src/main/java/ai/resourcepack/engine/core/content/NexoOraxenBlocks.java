package ai.resourcepack.engine.core.content;

import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A Nexo or Oraxen custom block, as an RP Engine block.
 *
 * <p>Both plugins hide a block in a spare state of a vanilla one, and which
 * vanilla block is the {@code type}: a note block for a full block, a tripwire
 * for a plant, a chorus plant for something you see through, and in Oraxen a
 * whole kind of waxed copper for a stair, slab, door, trapdoor, grate or bulb.
 * Each has its own here:
 *
 * <ul>
 *   <li>{@code NOTEBLOCK}/{@code FULL}: a note block, as before; {@code directional}
 *       turns it the way a log, a furnace or a dropper turns, each direction
 *       drawn by its sub-block's model where one is named.</li>
 *   <li>{@code STRINGBLOCK}/{@code STRING}: a plant, on cut tripwire.</li>
 *   <li>{@code CHORUSBLOCK}/{@code CHORUS}: a {@code grate}, the see-through
 *       full block. A chorus plant would need Paper to stop chorus updating
 *       and would repaint the End's own chorus; a grate needs neither.</li>
 *   <li>Oraxen's shapes: the same shape here, on <strong>the same waxed copper
 *       Oraxen used</strong> - its {@code custom_variation} 1 to 4 names the
 *       age of copper - so every one of those already standing in a world is
 *       still that block after the move.</li>
 * </ul>
 *
 * <p>Their sub-mechanics that have an equivalent come across: {@code storage},
 * {@code is_falling}, {@code blast_resistant} and {@code log_strip}. A block
 * drawn by a model Nexo/Oraxen would generate from {@code parent_model} and
 * textures is generated here too, written inline.
 */
final class NexoOraxenBlocks {

    private NexoOraxenBlocks() {
    }

    /** The waxed copper Oraxen's custom_variation 1 to 4 means, per shape: fresh, exposed, weathered, oxidized. */
    private static final Map<String, List<String>> ORAXEN_COPPER = Map.of(
            "stairs", List.of("waxed_cut_copper_stairs", "waxed_exposed_cut_copper_stairs",
                    "waxed_weathered_cut_copper_stairs", "waxed_oxidized_cut_copper_stairs"),
            "slab", List.of("waxed_cut_copper_slab", "waxed_exposed_cut_copper_slab",
                    "waxed_weathered_cut_copper_slab", "waxed_oxidized_cut_copper_slab"),
            "door", copper("copper_door"),
            "trapdoor", copper("copper_trapdoor"),
            "grate", copper("copper_grate"),
            "bulb", copper("copper_bulb"));

    private static List<String> copper(String kind) {
        return List.of("waxed_" + kind, "waxed_exposed_" + kind, "waxed_weathered_" + kind, "waxed_oxidized_" + kind);
    }

    /** Whether an item is one direction of another block's directional set, and so not a block of its own. */
    static boolean isDirectionalPart(DefinitionNode mechanic) {
        return mechanic.node("directional").flatMap(directional -> directional.string("parent_block")).isPresent();
    }

    static Map<String, Object> block(DefinitionNode document, DefinitionNode item, String mechanicName,
                                     DefinitionNode mechanic, String id, String namespace, String origin,
                                     List<Diagnostic> diagnostics) {
        Map<String, Object> out = new LinkedHashMap<>();
        DefinitionNode pack = NexoOraxen.section(item, "Pack").orElse(DefinitionNode.empty());
        String type = mechanic.string("type").orElse("").trim().toUpperCase(Locale.ROOT);
        String shape = shapeOf(mechanicName, type);

        if (shape.equals("plant")) {
            out.put("base", "tripwire");
        } else if (shape.equals("mushroom")) {
            out.put("base", "mushroom_stem");
        } else if (!shape.equals("cube")) {
            out.put("shape", shape);
            int variation = mechanic.integer("custom_variation").or(() -> mechanic.integer("custom-variation"))
                    .orElse(0);
            List<String> copper = ORAXEN_COPPER.get(shape);
            if (copper != null && variation >= 1 && variation <= 4) {
                // The very copper Oraxen put these on, so the ones already
                // built with are still these.
                out.put("base", "minecraft:" + copper.get(variation - 1));
            }
        }

        art(item, pack, mechanic, shape, id, namespace, origin, diagnostics, out);
        directional(document, mechanic, shape, id, namespace, origin, diagnostics, out);

        NexoOraxen.breaking(mechanic, id, namespace, origin, diagnostics, out);
        NexoOraxen.sound(mechanic, id, origin, diagnostics, out);
        NexoOraxenActions.standing(mechanic, id, origin, diagnostics, out);
        behaviours(mechanic, shape, id, namespace, origin, diagnostics, out);
        return out;
    }

    /** cube, plant, mushroom, or one of the shapes. */
    private static String shapeOf(String mechanicName, String type) {
        if (mechanicName.equals("stringblock") || type.equals("STRINGBLOCK") || type.equals("STRING")) {
            return "plant";
        }
        if (mechanicName.equals("chorusblock") || type.equals("CHORUSBLOCK") || type.equals("CHORUS")) {
            return "grate";
        }
        switch (type) {
            case "STAIR":
            case "STAIRS":
                return "stairs";
            case "SLAB":
                return "slab";
            case "DOOR":
                return "door";
            case "TRAPDOOR":
                return "trapdoor";
            case "GRATE":
                return "grate";
            case "BULB":
                return "bulb";
            default:
                return type.contains("MUSHROOM") ? "mushroom" : "cube";
        }
    }

    /**
     * The model, or the textures it is made from.
     *
     * <p>A shape takes the textures themselves - its parts are generated from
     * them here, as Oraxen generates them - and so does a cube with no model
     * file, generated from {@code parent_model} the way both plugins do.
     */
    private static void art(DefinitionNode item, DefinitionNode pack, DefinitionNode mechanic, String shape,
                            String id, String namespace, String origin, List<Diagnostic> diagnostics,
                            Map<String, Object> out) {
        List<String> layers = NexoOraxen.textures(pack);
        Map<String, String> slots = textureMap(pack);
        Optional<String> declared = mechanic.string("model")
                .or(() -> mechanic.node("appearance").flatMap(a -> a.string("model")))
                .or(() -> pack.string("model"));
        boolean generated = pack.string("model").isEmpty() && (!layers.isEmpty() || !slots.isEmpty())
                && (declared.isEmpty() || declared.get().equals(id) || declared.get().endsWith(":" + id)
                || declared.get().endsWith("/" + id));

        if (!shape.equals("cube") && !shape.equals("plant") && !shape.equals("mushroom")) {
            // A shape: the mechanic's own textures (a door's bottom and top),
            // else the item's.
            Map<String, String> shapeTextures = shapeTextures(mechanic, layers, slots, shape);
            if (!shapeTextures.isEmpty() && (generated || declared.isEmpty())) {
                Map<String, String> local = new LinkedHashMap<>();
                shapeTextures.forEach((slot, texture) -> NexoOraxen.localPath(texture, namespace, id, origin,
                        diagnostics, "textures").ifPresent(path -> local.put(slot, path)));
                if (local.size() == 1 && local.containsKey("all")) {
                    out.put("texture", local.get("all"));
                } else if (!local.isEmpty()) {
                    out.put("textures", local);
                }
                return;
            }
        }
        if (generated) {
            Map<String, Object> model = generatedModel(pack, layers, slots, shape, id, namespace, origin, diagnostics);
            if (model != null) {
                out.put("model", model);
            }
            return;
        }
        declared.flatMap(value -> NexoOraxen.localPath(value, namespace, id, origin, diagnostics, "model"))
                .ifPresent(value -> out.put("model", value));
    }

    /** {@code textures:} written as a map of slots. */
    private static Map<String, String> textureMap(DefinitionNode pack) {
        Map<String, String> out = new LinkedHashMap<>();
        DefinitionNode map = pack.node("textures").orElse(DefinitionNode.empty());
        for (String slot : map.keys()) {
            map.string(slot).ifPresent(texture -> out.put(slot, texture));
        }
        return out;
    }

    /** The textures a shape is made of, as RP Engine's texture slots. */
    private static Map<String, String> shapeTextures(DefinitionNode mechanic, List<String> layers,
                                                     Map<String, String> slots, String shape) {
        Map<String, String> out = new LinkedHashMap<>();
        DefinitionNode own = mechanic.node("textures").orElse(DefinitionNode.empty());
        for (String slot : own.keys()) {
            own.string(slot).ifPresent(texture -> out.put(slot.equals("texture") ? "all" : slot, texture));
        }
        List<String> ownList = mechanic.strings("textures");
        List<String> list = !ownList.isEmpty() && own.keys().isEmpty() ? ownList : layers;
        if (out.isEmpty() && !slots.isEmpty()) {
            slots.forEach((slot, texture) -> out.put(slot.equals("texture") ? "all" : slot, texture));
        }
        if (out.isEmpty() && !list.isEmpty()) {
            if (shape.equals("door") && list.size() > 1) {
                // Oraxen's door is [bottom, top].
                out.put("bottom", list.get(0));
                out.put("top", list.get(1));
            } else if (shape.equals("bulb") && list.size() > 1) {
                out.put("off", list.get(0));
                out.put("on", list.get(1));
            } else {
                out.put("all", list.get(0));
            }
        }
        return out;
    }

    /**
     * The model Nexo and Oraxen generate out of {@code parent_model} and the
     * textures, written inline.
     *
     * <p>A list of textures fills the parent's slots: by a file name ending in a
     * slot's name ({@code _top}, {@code _side}) first, then in the order each
     * parent takes them. One texture fills every slot any of the game's block
     * parents has, which the game ignores where the parent does not use it.
     */
    private static Map<String, Object> generatedModel(DefinitionNode pack, List<String> layers,
                                                      Map<String, String> slots, String shape, String id,
                                                      String namespace, String origin,
                                                      List<Diagnostic> diagnostics) {
        String parent = pack.string("parent_model")
                .orElse(shape.equals("plant") ? "block/cross" : "block/cube_all").trim();
        if (!parent.contains(":")) {
            parent = "minecraft:" + parent;
        }
        Map<String, Object> textures = new LinkedHashMap<>();
        if (!slots.isEmpty()) {
            for (Map.Entry<String, String> slot : slots.entrySet()) {
                NexoOraxen.localPath(slot.getValue(), namespace, id, origin, diagnostics, "Pack.textures")
                        .ifPresent(path -> textures.put(slot.getKey(), path));
            }
        } else {
            List<String> paths = new ArrayList<>();
            for (String layer : layers) {
                NexoOraxen.localPath(layer, namespace, id, origin, diagnostics, "Pack.texture").ifPresent(paths::add);
            }
            if (paths.isEmpty()) {
                return null;
            }
            if (paths.size() == 1) {
                for (String slot : List.of("all", "cross", "texture", "side", "end", "top", "bottom", "front",
                        "particle", "plant")) {
                    textures.put(slot, paths.get(0));
                }
            } else {
                List<String> order = SLOT_ORDER.getOrDefault(parent.substring(parent.indexOf(':') + 1),
                        List.of("all"));
                List<String> unplaced = new ArrayList<>();
                for (String path : paths) {
                    String slot = order.stream().filter(name -> path.endsWith("_" + name) && !textures.containsKey(name))
                            .findFirst().orElse(null);
                    if (slot != null) {
                        textures.put(slot, path);
                    } else {
                        unplaced.add(path);
                    }
                }
                for (String slot : order) {
                    if (!textures.containsKey(slot) && !unplaced.isEmpty()) {
                        textures.put(slot, unplaced.remove(0));
                    }
                }
                diagnostics.add(Diagnostic.warning(origin, id,
                        "its " + paths.size() + " textures were matched to " + parent + "'s slots " + textures.keySet()
                                + " by name and then in order. Check the faces in game."));
            }
        }
        textures.putIfAbsent("particle", textures.values().iterator().next());
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("parent", parent);
        model.put("textures", textures);
        return model;
    }

    /** The order the game's own block parents take their textures in, for a list that names none. */
    private static final Map<String, List<String>> SLOT_ORDER = Map.of(
            "block/cube_all", List.of("all"),
            "block/cube_column", List.of("end", "side"),
            "block/cube_column_horizontal", List.of("end", "side"),
            "block/cube_bottom_top", List.of("bottom", "side", "top"),
            "block/cube_top", List.of("side", "top"),
            "block/orientable", List.of("front", "side", "top"),
            "block/orientable_vertical", List.of("front", "side"),
            "block/cube", List.of("down", "east", "north", "south", "up", "west"),
            "block/cross", List.of("cross"));

    /**
     * {@code directional}: a log (along the face it was put against), a
     * furnace (facing the player), or a dropper and Nexo's barrel (facing
     * them, up and down too).
     *
     * <p>Where the set names a block per direction ({@code y_block},
     * {@code north_block}, ...) each direction is drawn by that block's model;
     * otherwise the one model is turned, which is what both plugins do.
     */
    private static void directional(DefinitionNode document, DefinitionNode mechanic, String shape, String id,
                                    String namespace, String origin, List<Diagnostic> diagnostics,
                                    Map<String, Object> out) {
        Optional<DefinitionNode> found = mechanic.node("directional");
        if (found.isEmpty()) {
            return;
        }
        DefinitionNode directional = found.get();
        if (!shape.equals("cube") && !shape.equals("mushroom")) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "directional on a " + shape + " is not something RP Engine turns, so it was skipped."));
            return;
        }
        String kind = directional.string("type").or(() -> directional.string("directional_type")).orElse("LOG")
                .trim().toUpperCase(Locale.ROOT);
        String rotate;
        List<String> directions;
        String property;
        switch (kind) {
            case "FURNACE":
                rotate = "horizontal";
                property = "facing";
                directions = List.of("north", "east", "south", "west");
                break;
            case "DROPPER":
            case "BARREL":
                rotate = "all";
                property = "facing";
                directions = List.of("north", "east", "south", "west", "up", "down");
                break;
            default:
                rotate = "axis";
                property = "axis";
                directions = List.of("y", "x", "z");
        }
        out.put("rotate", rotate);
        Map<String, Object> appearances = new LinkedHashMap<>();
        for (String direction : directions) {
            Optional<String> part = directional.string(direction + "_block");
            if (part.isEmpty()) {
                continue;
            }
            Optional<String> model = modelOf(document, part.get(), namespace, id, origin, diagnostics);
            if (model.isPresent()) {
                appearances.put(property + "=" + direction, model.get());
            } else {
                diagnostics.add(Diagnostic.warning(origin, id,
                        "directional " + direction + "_block " + part.get() + " has no model this file shows, so that "
                                + "direction is the block's own model turned."));
            }
        }
        if (!appearances.isEmpty()) {
            out.put("appearances", appearances);
        }
    }

    /** The model a sibling item in the same file is drawn with. */
    private static Optional<String> modelOf(DefinitionNode document, String itemId, String namespace, String id,
                                            String origin, List<Diagnostic> diagnostics) {
        String bare = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
        Optional<DefinitionNode> sibling = document.node(bare);
        if (sibling.isEmpty()) {
            return Optional.empty();
        }
        DefinitionNode mechanics = NexoOraxen.section(sibling.get(), "Mechanics").orElse(DefinitionNode.empty());
        Optional<String> model = Optional.empty();
        for (String name : List.of("custom_block", "block", "noteblock")) {
            model = model.or(() -> mechanics.node(name).flatMap(block -> block.string("model")));
        }
        model = model.or(() -> NexoOraxen.section(sibling.get(), "Pack").flatMap(pack -> pack.string("model")));
        return model.flatMap(value -> NexoOraxen.localPath(value, namespace, id, origin, diagnostics, "model"));
    }

    /** The sub-mechanics with an equivalent here, and a reason for each of the rest. */
    private static void behaviours(DefinitionNode mechanic, String shape, String id, String namespace,
                                   String origin, List<Diagnostic> diagnostics, Map<String, Object> out) {
        if (mechanic.bool("is_falling").orElse(Boolean.FALSE)) {
            out.put("falls", true);
        }
        if (mechanic.bool("blast_resistant").orElse(Boolean.FALSE)) {
            out.put("blast-resistant", true);
        }
        mechanic.node("log_strip").ifPresent(strip -> {
            Map<String, Object> translated = new LinkedHashMap<>();
            strip.string("stripped_log").ifPresent(into -> translated.put("into", NexoOraxen.qualified(into, namespace)));
            strip.string("drop").ifPresent(drop -> translated.put("drop", NexoOraxen.qualified(drop, namespace)));
            if (translated.containsKey("into")) {
                out.put("strip", translated);
            }
        });
        mechanic.node("storage").ifPresent(storage -> out.put("storage", storage(storage)));

        if (shape.equals("plant")) {
            for (String key : List.of("sapling", "is_tall", "placeable_on_water", "requires_supporting",
                    "random_place", "stackable")) {
                if (mechanic.raw(key) != null) {
                    diagnostics.add(Diagnostic.warning(origin, id, "string block " + key + " was skipped: "
                            + PLANT_REASONS.get(key) + "."));
                }
            }
        }
        if (mechanic.raw("light") != null) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "light: a custom block's light belongs to the vanilla block underneath, and a note block or a "
                            + "tripwire gives none, so it was skipped. shape: bulb gives light, switched by "
                            + "redstone; a placed model can give any level."));
        }
        List<String> skipped = new ArrayList<>();
        for (String name : mechanic.keys()) {
            if (KNOWN.contains(name) || (shape.equals("plant") && PLANT_REASONS.containsKey(name))) continue;
            if (REASONS.containsKey(name)) {
                diagnostics.add(Diagnostic.warning(origin, id,
                        "custom block " + name + " was skipped: " + REASONS.get(name) + "."));
            } else {
                skipped.add(name);
            }
        }
        if (!skipped.isEmpty()) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "custom block " + String.join(", ", skipped) + " have no RP Engine equivalent and were skipped."));
        }
    }

    /** Their storage block, as RP Engine's: the same four kinds and the same keys, spelled ours. */
    static Map<String, Object> storage(DefinitionNode storage) {
        Map<String, Object> out = new LinkedHashMap<>();
        String type = storage.string("type").orElse("STORAGE").trim().toUpperCase(Locale.ROOT);
        out.put("type", switch (type) {
            case "PERSONAL" -> "personal";
            case "ENDERCHEST" -> "enderchest";
            case "DISPOSAL" -> "disposal";
            case "SHULKER" -> "shulker";
            default -> "chest";
        });
        storage.integer("rows").ifPresent(rows -> out.put("rows", rows));
        storage.string("title").ifPresent(title -> out.put("title", title));
        storage.string("open_sound").or(() -> storage.string("open-sound"))
                .ifPresent(sound -> out.put("open-sound", sound));
        storage.string("close_sound").or(() -> storage.string("close-sound"))
                .ifPresent(sound -> out.put("close-sound", sound));
        return out;
    }

    private static final Map<String, String> PLANT_REASONS = Map.of(
            "sapling", "growing into a WorldEdit schematic is a whole tree pasted in, which RP Engine does not do; "
                    + "grow: steps a plant through its own stages instead",
            "is_tall", "a two-block plant is drawn by its model, which may be taller than a block, but only its "
                    + "lower block is the plant",
            "placeable_on_water", "it is placed where string can go, as any plant here is",
            "requires_supporting", "a plant here stands where it was put",
            "random_place", "each placed block is the item that placed it; random: picks a look per placement "
                    + "instead",
            "stackable", "a plant that grows denser when clicked with itself has no equivalent; stages: and click "
                    + "are the nearest");

    private static final Map<String, String> REASONS = Map.of(
            "beacon_base_block", "a beacon base is a property of a vanilla block type, so it would make every note "
                    + "block one",
            "blocklocker", "BlockLocker protection is that plugin's own business",
            "limited_placing", "a block here goes wherever its base block may",
            "placeable", "a block here goes wherever its base block may, floor, wall or ceiling",
            "farmblock", "watering a block on a timer is a whole farming system rather than a property of a block");

    private static final List<String> KNOWN = List.of("model", "appearance", "hardness", "drop", "sound",
            "block_sounds", "block-sounds", "type", "custom_variation", "custom-variation", "breaking", "light",
            "clickActions", "events", "directional", "is_falling", "blast_resistant", "log_strip", "storage",
            "textures");
}
