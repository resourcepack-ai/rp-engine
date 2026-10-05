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
 * What a Nexo or Oraxen piece of furniture does, as an RP Engine placed
 * model's {@code storage}, {@code jukebox}, {@code states} and {@code grow}.
 *
 * <ul>
 *   <li>{@code storage}: the same five kinds, the same keys.</li>
 *   <li>{@code jukebox}: discs in, music out; Oraxen's {@code active_model} is
 *       the model it wears while one plays.</li>
 *   <li>{@code door}: a second state, turned a quarter (or slid, for
 *       {@code is_sliding}) by its {@code open_properties}, solid or not as
 *       {@code toggle_hitbox_on_open} says, with its two sounds and its
 *       {@code automatic_close_delay}.</li>
 *   <li>Nexo's {@code states}: one state each, wearing its item model, light,
 *       barrier and translation.</li>
 *   <li>A toggleable light ({@code lights.toggleable}, Oraxen's
 *       {@code toggle_light}): off as placed, on as a state, wearing
 *       {@code toggled_model}/{@code toggled_item_model}.</li>
 *   <li>{@code rotatable}: states that each turn the piece a step further,
 *       so a click turns it as it does there.</li>
 *   <li>{@code evolution}: {@code grow} into its {@code next_stage}.</li>
 * </ul>
 *
 * <p>A piece has one list of states here, so of a door, a set of states, a
 * switched light and turning by clicks, the first in that order wins and the
 * others say so.
 */
final class NexoOraxenFurniture {

    private NexoOraxenFurniture() {
    }

    /** Keys this reads, so the furniture translation does not also call them unknown. */
    static final List<String> KEYS = List.of("storage", "jukebox", "door", "states", "evolution", "rotatable");

    static void behaviours(DefinitionNode item, DefinitionNode furniture, String id, String namespace, String origin,
                           List<Diagnostic> diagnostics, Map<String, Object> place) {
        furniture.node("storage").ifPresent(storage -> place.put("storage", NexoOraxenBlocks.storage(storage)));
        furniture.node("jukebox").ifPresent(jukebox -> place.put("jukebox", jukebox(jukebox, namespace)));

        String claimedBy = null;
        Optional<DefinitionNode> door = furniture.node("door");
        if (door.isPresent()) {
            door(furniture, door.get(), id, origin, diagnostics, place);
            claimedBy = "door";
        }
        Optional<DefinitionNode> states = furniture.node("states");
        if (states.isPresent()) {
            if (claimedBy == null) {
                states(states.get(), id, namespace, origin, diagnostics, place);
                claimedBy = "states";
            } else {
                diagnostics.add(Diagnostic.warning(origin, id,
                        "states were skipped: its " + claimedBy + " already uses the piece's one list of states."));
            }
        }
        Optional<DefinitionNode> lights = furniture.node("lights");
        Optional<DefinitionNode> toggle = NexoOraxen.section(item, "Mechanics")
                .flatMap(mechanics -> mechanics.node("toggle_light"));
        boolean switched = lights.flatMap(node -> node.bool("toggleable")).orElse(Boolean.FALSE) || toggle.isPresent();
        if (switched) {
            if (claimedBy == null) {
                lightSwitch(lights.orElse(DefinitionNode.empty()), toggle.orElse(null), namespace, place);
                claimedBy = "switched light";
            } else {
                diagnostics.add(Diagnostic.warning(origin, id,
                        "its light switch was skipped: its " + claimedBy + " already uses the piece's one list of "
                                + "states, so the light stays as placed."));
            }
        }
        if (furniture.bool("rotatable").orElse(Boolean.FALSE)) {
            if (claimedBy == null) {
                String facing = String.valueOf(place.getOrDefault("facing", "diagonal"));
                int step = facing.equals("cardinal") ? 90 : 45;
                List<Object> turns = new ArrayList<>();
                for (int turn = step; turn < 360; turn += step) {
                    Map<String, Object> state = new LinkedHashMap<>();
                    state.put("turn", turn);
                    turns.add(state);
                }
                place.put("states", turns);
            } else {
                diagnostics.add(Diagnostic.warning(origin, id,
                        "rotatable was skipped: its " + claimedBy + " already uses the piece's one list of states."));
            }
        }
        furniture.node("evolution").ifPresent(evolution -> evolution(evolution, id, namespace, origin, diagnostics,
                place));
    }

    private static Map<String, Object> jukebox(DefinitionNode jukebox, String namespace) {
        Map<String, Object> out = new LinkedHashMap<>();
        jukebox.decimal("volume").ifPresent(volume -> out.put("volume", volume));
        jukebox.decimal("pitch").ifPresent(pitch -> out.put("pitch", pitch));
        jukebox.string("permission").filter(permission -> !permission.isBlank())
                .ifPresent(permission -> out.put("permission", permission));
        jukebox.string("active_model").ifPresent(model -> out.put("playing-model",
                NexoOraxen.qualified(model, namespace)));
        return out;
    }

    /**
     * A door: open is a second state. A hinged door turns a quarter and moves
     * by the difference between its open and closed translations, which is
     * what puts its edge back on the hinge; a sliding one only moves.
     */
    private static void door(DefinitionNode furniture, DefinitionNode door, String id, String origin,
                             List<Diagnostic> diagnostics, Map<String, Object> place) {
        Map<String, Object> open = new LinkedHashMap<>();
        if (!door.bool("is_sliding").orElse(Boolean.FALSE)) {
            open.put("turn", 90);
        }
        double[] closed = translation(furniture.node("properties").orElse(DefinitionNode.empty()));
        double[] opened = translation(door.node("open_properties").orElse(DefinitionNode.empty()));
        if (opened != null) {
            double[] from = closed == null ? new double[3] : closed;
            open.put("offset", List.of(round(opened[0] - from[0]), round(opened[1] - from[1]),
                    round(opened[2] - from[2])));
        }
        if (door.bool("toggle_hitbox_on_open").orElse(Boolean.FALSE) && Boolean.TRUE.equals(place.get("solid"))) {
            open.put("solid", false);
        }
        door.string("open_sound").ifPresent(sound -> open.put("sound", sound));
        door.string("close_sound").ifPresent(sound -> place.put("base-sound", sound));
        door.string("automatic_close_delay").ifPresent(delay -> place.put("reset-after", delay));
        place.put("states", List.of(open));
        if (door.raw("open_properties") != null && door.node("open_properties")
                .map(properties -> properties.raw("left_rotation") != null || properties.raw("right_rotation") != null)
                .orElse(Boolean.FALSE)) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "its open_properties rotation is not carried; an open hinged door turns a quarter here."));
        }
        if (door.raw("delay_hitbox_toggle") != null) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "delay_hitbox_toggle is not carried: the barrier goes the moment the door opens."));
        }
    }

    /** {@code translation: "x,y,z"} or {@code {x, y, z}}, or null. */
    private static double[] translation(DefinitionNode properties) {
        Optional<String> text = properties.string("translation");
        if (text.isPresent()) {
            double[] parsed = NexoOraxen.numbers(text.get());
            return parsed.length == 3 ? parsed : null;
        }
        Optional<DefinitionNode> map = properties.node("translation");
        return map.map(node -> new double[] {node.decimal("x").orElse(0d), node.decimal("y").orElse(0d),
                node.decimal("z").orElse(0d)}).orElse(null);
    }

    /** Nexo's furniture states, each a state here wearing what it changes. */
    private static void states(DefinitionNode declared, String id, String namespace, String origin,
                               List<Diagnostic> diagnostics, Map<String, Object> place) {
        List<Object> out = new ArrayList<>();
        List<String> lost = new ArrayList<>();
        for (String name : declared.keys()) {
            Optional<DefinitionNode> found = declared.node(name);
            if (found.isEmpty()) continue;
            DefinitionNode state = found.get();
            String type = state.string("type").orElse("DEFAULT").trim().toUpperCase(Locale.ROOT);
            Map<String, Object> written = new LinkedHashMap<>();
            switch (type) {
                case "ITEM_MODEL":
                case "FURNITURE":
                case "DEFAULT":
                    state.string("item_model").ifPresent(model -> written.put("model", model));
                    break;
                default:
                    lost.add(name + " (" + type + ")");
            }
            if (type.equals("FURNITURE")) {
                Optional<DefinitionNode> lights = state.node("lights");
                if (lights.isPresent()) {
                    for (String light : lights.get().strings("light")) {
                        double[] parsed = NexoOraxen.numbers(light);
                        if (parsed.length == 4) written.put("light", (int) parsed[3]);
                    }
                }
                Optional<DefinitionNode> hitbox = state.node("hitbox");
                if (hitbox.isPresent()) {
                    written.put("solid", hitbox.get().raw("barriers") != null || hitbox.get().raw("barrier") != null);
                }
                double[] moved = translation(state.node("properties").orElse(DefinitionNode.empty()));
                if (moved != null) written.put("offset", List.of(moved[0], moved[1], moved[2]));
            }
            if (state.raw("conditions") != null) {
                lost.add(name + "'s conditions");
            }
            out.add(written);
        }
        if (!out.isEmpty()) place.put("states", out);
        declared.string("reset_after").ifPresent(delay -> place.put("reset-after", delay));
        if (declared.raw("next_after") != null) {
            lost.add("next_after (states here only reset)");
        }
        if (declared.raw("condition") != null) {
            lost.add("condition");
        }
        if (!lost.isEmpty()) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "of its states, " + String.join(", ", lost) + " were not carried: ModelEngine states and "
                            + "conditions are Nexo's own, custom_model_data states have no model to wear here."));
        }
    }

    /**
     * A light switched by a click: the piece is placed dark, and its one state
     * is lit and wears the toggled model.
     */
    private static void lightSwitch(DefinitionNode lights, DefinitionNode toggle, String namespace,
                                    Map<String, Object> place) {
        int level = place.get("light") instanceof Number n ? n.intValue() : 15;
        Map<String, Object> on = new LinkedHashMap<>();
        if (toggle != null) {
            // Oraxen: light is always on, toggle_light is the other level.
            int base = toggle.integer("light").orElse(0);
            on.put("light", toggle.integer("toggle_light").orElse(15));
            place.put("light", base);
        } else {
            on.put("light", level);
            place.put("light", 0);
        }
        lights.string("toggled_item_model").ifPresent(model -> on.put("model", model));
        lights.string("toggled_model").ifPresent(model -> on.putIfAbsent("model", NexoOraxen.qualified(model, namespace)));
        place.put("states", List.of(on));
    }

    /** {@code evolution}: grow into {@code next_stage}. */
    private static void evolution(DefinitionNode evolution, String id, String namespace, String origin,
                                  List<Diagnostic> diagnostics, Map<String, Object> place) {
        Optional<String> next = evolution.string("next_stage");
        if (next.isEmpty()) {
            // The last stage: nothing to grow into, which is a plant done growing.
            return;
        }
        Map<String, Object> grow = new LinkedHashMap<>();
        grow.put("into", NexoOraxen.qualified(next.get(), namespace));
        evolution.string("delay").ifPresent(delay -> grow.put("after", delay));
        evolution.decimal("probability").ifPresent(chance -> grow.put("chance", Math.max(0.01, Math.min(1, chance))));
        place.put("grow", grow);
        List<String> lost = new ArrayList<>();
        for (String key : List.of("light_boost", "rain_boost", "bone_meal", "boneMeal")) {
            if (evolution.raw(key) != null) lost.add(key);
        }
        if (!lost.isEmpty()) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "evolution " + String.join(", ", lost) + " were not carried: a piece here grows at one steady "
                            + "chance."));
        }
    }

    private static double round(double value) {
        return Math.round(value * 1000d) / 1000d;
    }
}
