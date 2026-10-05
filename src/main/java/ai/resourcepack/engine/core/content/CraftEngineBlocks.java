package ai.resourcepack.engine.core.content;

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
import static ai.resourcepack.engine.core.content.CraftEngine.number;
import static ai.resourcepack.engine.core.content.CraftEngine.string;
import static ai.resourcepack.engine.core.content.CraftEngine.strings;
import static ai.resourcepack.engine.core.content.CraftEngine.truthy;

/**
 * A CraftEngine block's states, appearances and behaviours, as an RP Engine
 * block's.
 *
 * <p>CraftEngine describes a block as {@code properties}, {@code appearances}
 * (each a vanilla state it hides in and a model) and {@code variants} that say
 * which appearance each combination of property values wears. RP Engine has
 * the same three ideas under its own names - {@code properties},
 * {@code appearances} keyed by state, and a spare state per combination - so
 * a block with five states comes across with five states.
 *
 * <p>Where the appearances hide in a <em>stair, slab, door or trapdoor</em>,
 * the block is one of RP Engine's shapes, on the very vanilla block the
 * CraftEngine pack chose, appearance for appearance: blocks of it already
 * standing in a world stay what they were.
 *
 * <p>The behaviours with an equivalent come across ({@code crop_block} and
 * its kin grow, {@code falling_block} falls, {@code leaves_block} is see-through,
 * {@code strippable_block} strips, {@code simple_storage_block} stores,
 * {@code lamp_block} gives light on redstone); each of the rest is named in a
 * warning with the reason.
 */
final class CraftEngineBlocks {

    private CraftEngineBlocks() {
    }

    /** The properties a shape's blockstate file looks at; the rest of a CraftEngine state is not drawn. */
    private static final Map<String, Set<String>> DRAWN = Map.of(
            "stairs", Set.of("facing", "half", "shape"),
            "slab", Set.of("type"),
            "door", Set.of("facing", "half", "hinge", "open"),
            "trapdoor", Set.of("facing", "half", "open"),
            "grate", Set.of(),
            "bulb", Set.of("lit", "powered"));

    /** Behaviours that make a plant. */
    private static final Set<String> PLANTS = Set.of("bush_block", "crop_block", "vertical_crop_block",
            "sapling_block", "stem_block", "attached_stem_block", "on_liquid_block", "near_liquid_block",
            "liquid_flowable_block", "hanging_block", "seagrass_like_block", "vine_crop_head_block",
            "vine_crop_body_block", "double_high_block", "multi_high_block");

    /** Behaviours that grow, and the property each grows through. */
    private static final Map<String, String> GROWERS = Map.of("crop_block", "age", "vertical_crop_block", "age",
            "stem_block", "age", "sapling_block", "stage", "vine_crop_head_block", "age",
            "change_over_time_block", "age");

    /** Behaviours that become an RP Engine shape. */
    private static final Map<String, String> SHAPED = Map.of("stairs_block", "stairs", "slab_block", "slab",
            "door_block", "door", "trapdoor_block", "trapdoor", "lamp_block", "bulb");

    /** Why the behaviours with no equivalent stay behind. */
    private static final Map<String, String> REASONS = Map.ofEntries(
            Map.entry("fence_block", "a fence joins its neighbours by a multipart model and a fence's own state "
                    + "logic, and RP Engine has no fence to take over"),
            Map.entry("fence_gate_block", "RP Engine has no fence gate to take over; it places as a block"),
            Map.entry("button_block", "RP Engine has no button to take over; actions on interact are the nearest"),
            Map.entry("pressure_plate_block", "RP Engine has no pressure plate to take over"),
            Map.entry("concrete_powder_block", "turning into another block in water is not something RP Engine "
                    + "blocks do; falls: comes across"),
            Map.entry("grass_block", "spreading to its neighbours is not something RP Engine blocks do"),
            Map.entry("spreading_block", "spreading to its neighbours is not something RP Engine blocks do"),
            Map.entry("surface_spreading_block", "spreading to its neighbours is not something RP Engine blocks do"),
            Map.entry("drawer_block", "a drawer showing its contents on its face is CraftEngine's own; storage: is "
                    + "the nearest"),
            Map.entry("display_item_block", "an item shown on a block is CraftEngine's own"),
            Map.entry("item_frame_block", "an item shown on a block is CraftEngine's own"),
            Map.entry("seat_block", "sitting on a block is a placed model's seat: here"),
            Map.entry("sofa_block", "sitting on a block is a placed model's seat: here"),
            Map.entry("bouncing_block", "bouncing is the base block's own physics"),
            Map.entry("simple_particle_block", "particles from a standing block are not something RP Engine blocks "
                    + "do"),
            Map.entry("wall_torch_particle_block", "particles from a standing block are not something RP Engine "
                    + "blocks do"),
            Map.entry("chime_block", "a block that plays when struck is CraftEngine's own; actions on interact "
                    + "are the nearest"),
            Map.entry("budding_block", "growing crystals on its faces is CraftEngine's own"),
            Map.entry("decay_block", "decaying away is CraftEngine's own"),
            Map.entry("stackable_block", "stacking into one block is CraftEngine's own"),
            Map.entry("snowy_block", "a block changing under snow is CraftEngine's own"),
            Map.entry("sturdy_base_block", "a placement rule; RP Engine blocks go where their base block may"),
            Map.entry("directional_attached_block", "a placement rule; RP Engine blocks go where their base block may"),
            Map.entry("face_attached_horizontal_directional_block", "a placement rule; RP Engine blocks go where "
                    + "their base block may"),
            Map.entry("hangable_block", "a placement rule; RP Engine blocks go where their base block may"),
            Map.entry("drop_experience_block", "experience is not a drop RP Engine blocks give"),
            Map.entry("drop_exp_block", "experience is not a drop RP Engine blocks give"),
            Map.entry("tint_source_block", "tinting by biome is the base block's own"));

    /**
     * Fills {@code out} with what the block looks like and does.
     *
     * @return the model its item should show, or null
     */
    static Object translate(CraftEngine.Library library, CraftEngine.Entry entry, Map<String, Object> out,
                            List<Diagnostic> diagnostics) {
        String id = entry.id;
        String origin = entry.origin;
        List<Map<String, Object>> behaviours = new ArrayList<>();
        for (Object raw : list(get(entry.body, "behavior", "behaviors"))) {
            Map<String, Object> behaviour = map(raw);
            if (behaviour == null) continue;
            behaviours.add(behaviour);
            // A composite behaviour holds others.
            for (Object inner : list(behaviour.get("behaviors"))) {
                if (map(inner) != null) behaviours.add(map(inner));
            }
        }
        Set<String> types = new LinkedHashSet<>();
        for (Map<String, Object> behaviour : behaviours) types.add(CraftEngineItems.type(behaviour.get("type")));

        Map<String, Object> state = map(get(entry.body, "state", "states"));
        if (state == null) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "has no state, so there is nothing to draw it with; it renders as a plain note block."));
            behave(library, entry, behaviours, null, out, diagnostics);
            return null;
        }
        Map<String, Object> named = map(get(state, "appearance", "appearances"));
        Map<String, Map<String, Object>> appearances = new LinkedHashMap<>();
        if (named != null) {
            for (Map.Entry<String, Object> appearance : named.entrySet()) {
                if (map(appearance.getValue()) != null) appearances.put(appearance.getKey(), map(appearance.getValue()));
            }
        }
        if (appearances.isEmpty()) {
            appearances.put("", state);
        }
        Map<String, Object> first = appearances.values().iterator().next();

        // What it is underneath.
        String vanilla = vanillaBlock(first);
        String shape = null;
        for (String type : types) {
            if (SHAPED.containsKey(type)) shape = SHAPED.get(type);
        }
        if (shape == null && vanilla != null) {
            shape = shapeNamedBy(vanilla);
        }
        if (shape != null && shape.equals("bulb") && !appearances.keySet().stream().anyMatch(name -> name.contains("lit"))
                && !hasProperty(state, "lit")) {
            shape = null;
        }
        String kind = kind(first, types);

        if (shape != null) {
            return shaped(library, entry, state, appearances, shape, vanilla, behaviours, out, diagnostics);
        }
        if (kind.equals("leaves")) {
            out.put("shape", "grate");
        } else if (kind.equals("plant")) {
            out.put("base", "tripwire");
        } else if (kind.equals("mushroom")) {
            out.put("base", "mushroom_stem");
        }
        if (!kind.equals("leaves")) {
            warnAutoState(entry, first, diagnostics);
        }

        Map<String, Object> properties = map(state.get("properties"));
        Object itemModel;
        if (properties == null || properties.isEmpty() || kind.equals("leaves")) {
            // One look: the first appearance, turned if it is turned.
            Map<String, Object> drawn = drawn(library, entry, first, diagnostics);
            if (drawn != null && drawn.get("random") != null) {
                out.put("random", drawn.remove("random"));
            }
            itemModel = drawn == null ? null : drawn.get("model");
            if (itemModel != null) out.put("model", itemModel);
            if (drawn != null && (drawn.containsKey("x") || drawn.containsKey("y"))) {
                Map<String, Object> every = new LinkedHashMap<>();
                every.put("*", drawn);
                out.put("appearances", every);
            }
            if (properties != null && !properties.isEmpty()) {
                diagnostics.add(Diagnostic.warning(origin, id,
                        "is see-through leaves, whose properties (" + String.join(", ", properties.keySet())
                                + ") are the game's own decay rules; it came across as one look."));
            }
        } else {
            itemModel = stated(library, entry, state, properties, appearances, out, diagnostics);
        }
        behave(library, entry, behaviours, properties, out, diagnostics);
        return itemModel;
    }

    /** The vanilla block an appearance's {@code state:} names, as {@code minecraft:oak_stairs}, or null. */
    private static String vanillaBlock(Map<String, Object> appearance) {
        String declared = string(appearance.get("state"));
        if (declared == null) return null;
        int bracket = declared.indexOf('[');
        String block = (bracket < 0 ? declared : declared.substring(0, bracket)).trim().toLowerCase(Locale.ROOT);
        return block.contains(":") ? block : "minecraft:" + block;
    }

    /** The properties inside an appearance's {@code state:}, as {@code a=b,c=d}. */
    private static String vanillaProperties(Map<String, Object> appearance) {
        String declared = string(appearance.get("state"));
        if (declared == null) return "";
        int open = declared.indexOf('[');
        int close = declared.lastIndexOf(']');
        return open < 0 || close < open ? "" : declared.substring(open + 1, close).replace(" ", "");
    }

    private static String shapeNamedBy(String block) {
        String name = block.substring(block.indexOf(':') + 1);
        if (name.endsWith("_trapdoor")) return "trapdoor";
        if (name.endsWith("_door")) return "door";
        if (name.endsWith("_stairs")) return "stairs";
        if (name.endsWith("_slab")) return "slab";
        if (name.endsWith("_grate")) return "grate";
        if (name.endsWith("_bulb")) return "bulb";
        return null;
    }

    private static boolean hasProperty(Map<String, Object> state, String property) {
        Map<String, Object> properties = map(state.get("properties"));
        return properties != null && properties.containsKey(property);
    }

    /** cube, plant, leaves or mushroom, from the auto_state, the vanilla state, or the behaviours. */
    private static String kind(Map<String, Object> appearance, Set<String> types) {
        if (types.contains("leaves_block")) return "leaves";
        Object auto = get(appearance, "auto_state");
        String group = auto instanceof Map<?, ?> detail
                ? String.valueOf(CraftEngineYaml.cast(detail).getOrDefault("type", "solid"))
                : auto == null ? null : auto.toString();
        if (group != null) {
            group = group.toLowerCase(Locale.ROOT);
            if (group.contains("leaves")) return "leaves";
            if (group.contains("mushroom")) return "mushroom";
            if (group.contains("tripwire") || group.contains("sapling") || group.contains("sugar_cane")
                    || group.contains("cactus") || group.contains("plant") || group.contains("crop")
                    || group.contains("bush") || group.contains("flower") || group.contains("vine")) {
                return "plant";
            }
        }
        String vanilla = vanillaBlock(appearance);
        if (vanilla != null) {
            String name = vanilla.substring(vanilla.indexOf(':') + 1);
            if (name.contains("leaves")) return "leaves";
            if (name.endsWith("_stem") && !name.equals("mushroom_stem") || name.endsWith("sapling")
                    || Set.of("wheat", "carrots", "potatoes", "beetroots", "sweet_berry_bush", "nether_wart",
                    "torchflower_crop", "pitcher_crop", "sugar_cane", "tripwire", "cocoa", "short_grass", "grass",
                    "fern", "dead_bush", "kelp", "kelp_plant", "seagrass", "cave_vines", "twisting_vines",
                    "weeping_vines").contains(name)) {
                return "plant";
            }
            if (name.contains("mushroom")) return "mushroom";
        }
        for (String type : types) {
            if (PLANTS.contains(type)) return "plant";
        }
        return "cube";
    }

    private static void warnAutoState(CraftEngine.Entry entry, Map<String, Object> appearance,
                                      List<Diagnostic> diagnostics) {
        if (string(appearance.get("state")) != null && vanillaBlock(appearance) != null) {
            diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                    "takes the vanilla state " + appearance.get("state") + " for itself; RP Engine hands out a "
                            + "spare state of its own instead, so any of these already in a world are not carried."));
        }
    }

    /**
     * One appearance as an RP Engine one: its model (a location, or written
     * inline where CraftEngine generates it) and its turn.
     */
    private static Map<String, Object> drawn(CraftEngine.Library library, CraftEngine.Entry entry,
                                             Map<String, Object> appearance, List<Diagnostic> diagnostics) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> textures = strings(get(appearance, "texture", "textures"));
        Object model = get(appearance, "model", "models");
        if (model instanceof List<?> weighted) {
            if (weighted.size() > 1) {
                // Several models picked between at random: a random property.
                List<Object> picks = new ArrayList<>();
                for (Object pick : weighted) {
                    Map<String, Object> one = map(pick);
                    Map<String, Object> drawnPick = drawn(library, entry,
                            one == null ? Map.of("model", String.valueOf(pick)) : one, diagnostics);
                    if (drawnPick != null) picks.add(drawnPick);
                }
                out.put("random", picks);
            }
            model = weighted.isEmpty() ? null : weighted.get(0);
        }
        if (!textures.isEmpty()) {
            out.put("model", CraftEngineItems.cube(textures));
        } else if (model instanceof String path) {
            out.put("model", known(library, location(path)));
        } else if (map(model) != null) {
            Map<String, Object> declared = map(model);
            List<String> modelTextures = strings(get(declared, "texture", "textures"));
            Map<String, Object> generation = map(declared.get("generation"));
            if (!modelTextures.isEmpty()) {
                out.put("model", CraftEngineItems.cube(modelTextures));
            } else if (generation != null) {
                out.put("model", CraftEngineItems.generated(generation));
            } else if (string(get(declared, "path", "model")) != null) {
                out.put("model", known(library, location(string(get(declared, "path", "model")))));
            }
            turn(declared, out);
        } else if (get(appearance, "blueprint") != null) {
            diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                    "blueprint is CraftEngine's own Blockbench reader; save the .bbmodel into assets/models/ and "
                            + "set the block's model: to it."));
        }
        turn(appearance, out);
        if (get(appearance, "entity_render", "entity_renderer") != null) {
            diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                    "entity_renderer draws display entities on the block, which RP Engine blocks do not have."));
        }
        return out.containsKey("model") || out.containsKey("random") ? out : null;
    }

    private static Object known(CraftEngine.Library library, String location) {
        Map<String, Object> model = library.generated.get(location);
        return model == null ? location : new LinkedHashMap<>(model);
    }

    /** x, y and uvlock, where they say something. */
    private static void turn(Map<String, Object> from, Map<String, Object> out) {
        for (String key : List.of("x", "y")) {
            Double turn = number(from.get(key));
            if (turn != null && turn.intValue() % 360 != 0) out.put(key, turn.intValue());
        }
        if (truthy(from.get("uvlock"))) out.put("uvlock", true);
    }

    /**
     * A block whose looks are a stair, slab, door, trapdoor or bulb: the same
     * shape here, on the same vanilla block, each appearance keyed by the
     * state of that block it was drawn for.
     */
    private static Object shaped(CraftEngine.Library library, CraftEngine.Entry entry, Map<String, Object> state,
                                 Map<String, Map<String, Object>> appearances, String shape, String vanilla,
                                 List<Map<String, Object>> behaviours, Map<String, Object> out,
                                 List<Diagnostic> diagnostics) {
        out.put("shape", shape);
        if (vanilla != null && shape.equals(shapeNamedBy(vanilla))) {
            // The vanilla block the pack took, so the ones already in a world
            // are still these.
            out.put("base", vanilla);
        }
        Set<String> drawnProperties = DRAWN.getOrDefault(shape, Set.of());
        Map<String, Object> keyed = new LinkedHashMap<>();
        Object itemModel = null;
        Map<String, Object> variants = map(state.get("variants"));
        for (Map.Entry<String, Map<String, Object>> appearance : appearances.entrySet()) {
            Map<String, Object> drawn = drawn(library, entry, appearance.getValue(), diagnostics);
            if (drawn == null || !drawn.containsKey("model")) continue;
            drawn.remove("random");
            if (itemModel == null) itemModel = drawn.get("model");
            String key = vanillaProperties(appearance.getValue());
            if (key.isEmpty()) {
                // No state of its own: whichever variants wear it say which states it is.
                for (String variant : variantsWearing(variants, appearance.getKey())) {
                    keyed.putIfAbsent(only(variant, drawnProperties), drawn);
                }
                continue;
            }
            keyed.putIfAbsent(only(key, drawnProperties), drawn);
        }
        if (!keyed.isEmpty()) out.put("appearances", keyed);
        if (itemModel != null) out.put("model", itemModel);
        behave(library, entry, behaviours, map(state.get("properties")), out, diagnostics);
        return itemModel;
    }

    /** The variants that wear {@code appearance}. */
    private static List<String> variantsWearing(Map<String, Object> variants, String appearance) {
        List<String> out = new ArrayList<>();
        if (variants == null) return out;
        for (Map.Entry<String, Object> variant : variants.entrySet()) {
            Map<String, Object> detail = map(variant.getValue());
            if (detail != null && appearance.equals(string(detail.get("appearance")))) out.add(variant.getKey());
        }
        return out;
    }

    /** {@code a=b,c=d} keeping only the properties in {@code keep}, sorted as a blockstate file writes them. */
    private static String only(String state, Set<String> keep) {
        List<String> pairs = new ArrayList<>();
        for (String pair : state.split(",")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && keep.contains(pair.substring(0, equals).trim())) pairs.add(pair.trim());
        }
        pairs.sort(String::compareTo);
        return String.join(",", pairs);
    }

    /**
     * A cube or plant with properties: each becomes an RP Engine property,
     * and each variant its appearance.
     *
     * @return the model its item shows: the appearance its default state wears
     */
    private static Object stated(CraftEngine.Library library, CraftEngine.Entry entry, Map<String, Object> state,
                                 Map<String, Object> properties, Map<String, Map<String, Object>> appearances,
                                 Map<String, Object> out, List<Diagnostic> diagnostics) {
        Map<String, Object> written = new LinkedHashMap<>();
        Set<String> kept = new LinkedHashSet<>();
        List<String> dropped = new ArrayList<>();
        int combinations = 1;
        for (Map.Entry<String, Object> property : properties.entrySet()) {
            Map<String, Object> detail = map(property.getValue());
            String type = detail == null ? "" : String.valueOf(detail.getOrDefault("type", "")).toLowerCase(Locale.ROOT);
            String fallback = detail == null ? null : string(detail.get("default"));
            Object value;
            switch (type) {
                case "4-direction":
                case "horizontal_direction":
                case "horizontal-direction":
                    value = "facing";
                    combinations *= 4;
                    break;
                case "direction":
                case "6-direction":
                    value = "facing-all";
                    combinations *= 6;
                    break;
                case "axis":
                    value = "axis";
                    combinations *= 3;
                    break;
                case "boolean":
                    value = "true".equals(fallback) ? List.of("true", "false") : List.of("false", "true");
                    combinations *= 2;
                    break;
                case "int": {
                    List<String> values = intRange(detail);
                    if (values.isEmpty()) {
                        dropped.add(property.getKey());
                        continue;
                    }
                    if (fallback != null && values.contains(fallback)) {
                        values.remove(fallback);
                        values.add(0, fallback);
                    }
                    value = values;
                    combinations *= values.size();
                    break;
                }
                case "string":
                case "enum": {
                    List<String> values = new ArrayList<>(strings(detail.get("values")));
                    if (values.isEmpty()) {
                        dropped.add(property.getKey());
                        continue;
                    }
                    if (fallback != null && values.contains(fallback)) {
                        values.remove(fallback);
                        values.add(0, fallback);
                    }
                    value = values;
                    combinations *= values.size();
                    break;
                }
                default:
                    dropped.add(property.getKey() + " (" + type + ")");
                    continue;
            }
            written.put(property.getKey(), value);
            kept.add(property.getKey());
        }
        if (!dropped.isEmpty()) {
            diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                    "the properties " + String.join(", ", dropped) + " are not ones an RP Engine block can have, so "
                            + "every state is drawn at their default."));
        }
        if (!written.isEmpty()) out.put("properties", written);
        if (combinations > 49) {
            diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                    "has " + combinations + " states, more than a note block's pool holds; the ones that do not fit "
                            + "are placed in its first state."));
        }

        Map<String, Object> keyed = new LinkedHashMap<>();
        Map<String, Object> variants = map(state.get("variants"));
        Object itemModel = null;
        Map<String, Object> byName = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> appearance : appearances.entrySet()) {
            Map<String, Object> drawn = drawn(library, entry, appearance.getValue(), diagnostics);
            if (drawn != null && drawn.remove("random") != null) {
                diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                        "the appearance " + appearance.getKey() + " picks a model at random; a block with "
                                + "properties draws each state one way here, so it draws the first."));
            }
            if (drawn != null) byName.put(appearance.getKey(), drawn);
        }
        if (variants != null) {
            for (Map.Entry<String, Object> variant : variants.entrySet()) {
                Map<String, Object> detail = map(variant.getValue());
                String wearing = detail == null ? null : string(detail.get("appearance"));
                Object drawn = wearing == null ? null : byName.get(wearing);
                if (drawn != null) keyed.put(only(variant.getKey(), kept), drawn);
                if (detail != null && map(detail.get("settings")) != null) {
                    diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                            "the variant " + variant.getKey() + " changes the block's settings, which an RP Engine "
                                    + "block's states share; they were skipped."));
                }
            }
        } else {
            // Appearances named for their state, as CraftEngine's own
            // templates name them.
            for (Map.Entry<String, Object> appearance : byName.entrySet()) {
                if (appearance.getKey().contains("=")) {
                    keyed.put(only(appearance.getKey(), kept), appearance.getValue());
                }
            }
        }
        Object firstDrawn = byName.isEmpty() ? null : byName.values().iterator().next();
        if (keyed.isEmpty() && firstDrawn != null) {
            keyed.put("*", firstDrawn);
        }
        out.put("appearances", keyed);
        if (firstDrawn instanceof Map<?, ?> drawnMap) {
            itemModel = drawnMap.get("model");
        }
        if (itemModel != null) out.put("model", itemModel);
        return itemModel;
    }

    private static List<String> intRange(Map<String, Object> detail) {
        Integer low = null;
        Integer high = null;
        String range = string(detail.get("range"));
        if (range != null && range.contains("~")) {
            try {
                low = Integer.parseInt(range.substring(0, range.indexOf('~')).trim());
                high = Integer.parseInt(range.substring(range.indexOf('~') + 1).trim());
            } catch (NumberFormatException ignored) {
                // Falls through to min and max.
            }
        }
        if (low == null && number(detail.get("min")) != null) low = number(detail.get("min")).intValue();
        if (high == null && number(detail.get("max")) != null) high = number(detail.get("max")).intValue();
        List<String> values = new ArrayList<>();
        if (low == null || high == null || high < low || high - low > 31) return values;
        for (int i = low; i <= high; i++) values.add(String.valueOf(i));
        return values;
    }

    /** The behaviours with an equivalent, and a reason for each of the rest. */
    private static void behave(CraftEngine.Library library, CraftEngine.Entry entry,
                               List<Map<String, Object>> behaviours, Map<String, Object> properties,
                               Map<String, Object> out, List<Diagnostic> diagnostics) {
        List<String> skipped = new ArrayList<>();
        for (Map<String, Object> behaviour : behaviours) {
            String type = CraftEngineItems.type(behaviour.get("type"));
            if (GROWERS.containsKey(type)) {
                String property = GROWERS.get(type);
                if (properties != null && properties.containsKey(property)) {
                    Map<String, Object> grow = new LinkedHashMap<>();
                    grow.put("property", property);
                    // A random tick reaches a block about every 68 seconds, and
                    // grow_speed is the chance one makes it grow.
                    Double speed = number(behaviour.get("grow_speed"));
                    int seconds = (int) Math.round(68 / (speed == null || speed <= 0 ? 0.25 : Math.min(1, speed)));
                    grow.put("every", seconds + "s");
                    Double light = number(behaviour.get("light_requirement"));
                    if (light != null && light > 0) grow.put("light", light.intValue());
                    if (behaviour.get("is_bone_meal_target") != null) {
                        grow.put("bone-meal", truthy(behaviour.get("is_bone_meal_target")));
                    }
                    out.put("grow", grow);
                }
                if (type.equals("sapling_block") || type.equals("stem_block")) {
                    diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                            type + " grows through its stages here; what it becomes at the end (a tree, a fruit) "
                                    + "is CraftEngine's own and does not come across."));
                }
                continue;
            }
            switch (type) {
                case "falling_block":
                case "concrete_powder_block":
                    out.put("falls", true);
                    if (type.equals("concrete_powder_block")) skipped.add(type);
                    continue;
                case "strippable_block": {
                    String into = library.reference(string(behaviour.get("stripped")), entry.namespace());
                    if (into != null && into.contains(":")) {
                        Map<String, Object> strip = new LinkedHashMap<>();
                        strip.put("into", into);
                        out.put("strip", strip);
                    } else {
                        diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                                "strips into " + behaviour.get("stripped") + ", which is not a block of a loaded "
                                        + "pack, so stripping was skipped."));
                    }
                    continue;
                }
                case "simple_storage_block": {
                    Map<String, Object> storage = new LinkedHashMap<>();
                    storage.put("type", "chest");
                    Double rows = number(behaviour.get("rows"));
                    storage.put("rows", rows == null ? 1 : rows.intValue());
                    String title = string(behaviour.get("title"));
                    if (title != null) {
                        storage.put("title", CraftEngineItems.text(library, entry, title, "title", diagnostics));
                    }
                    Map<String, Object> sounds = map(behaviour.get("sounds"));
                    if (sounds != null) {
                        String open = soundName(sounds.get("open"));
                        String close = soundName(sounds.get("close"));
                        if (open != null) storage.put("open-sound", open);
                        if (close != null) storage.put("close-sound", close);
                    }
                    out.put("storage", storage);
                    continue;
                }
                case "toggleable_lamp_block":
                    if (properties != null && properties.containsKey("lit")) {
                        out.put("click", "lit");
                    }
                    diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                            "toggleable_lamp_block switches its look on a click here; its light belongs to the "
                                    + "vanilla block underneath, which a note block does not give. shape: bulb gives "
                                    + "real light, switched by redstone."));
                    continue;
                case "leaves_block":
                case "lamp_block":
                case "stairs_block":
                case "slab_block":
                case "door_block":
                case "trapdoor_block":
                case "bush_block":
                case "on_liquid_block":
                case "near_liquid_block":
                case "liquid_flowable_block":
                case "hanging_block":
                case "seagrass_like_block":
                case "attached_stem_block":
                case "vine_crop_body_block":
                case "double_high_block":
                case "multi_high_block":
                case "waterlogged_block":
                case "empty":
                    continue;
                default:
                    skipped.add(type);
            }
        }
        for (String type : skipped) {
            String reason = REASONS.get(type);
            diagnostics.add(Diagnostic.warning(entry.origin, entry.id,
                    "the block behaviour " + type + " was skipped: "
                            + (reason == null ? "it is CraftEngine's own" : reason) + "."));
        }
    }

    private static String soundName(Object declared) {
        if (declared == null) return null;
        Map<String, Object> detail = map(declared);
        String name = detail == null ? string(declared) : string(get(detail, "name", "sound", "id"));
        return name == null ? null : name.trim();
    }
}
