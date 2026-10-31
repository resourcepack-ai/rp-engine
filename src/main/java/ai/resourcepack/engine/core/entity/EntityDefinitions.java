package ai.resourcepack.engine.core.entity;

import ai.resourcepack.engine.api.ContentDefinition;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.EntityDrop;
import ai.resourcepack.engine.api.EntityInfo;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.api.MobState;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads custom entity definitions.
 *
 * <p>Free of Bukkit apart from {@link org.bukkit.entity.EntityType}, whose enum
 * constants resolve without a server. Validating the mob type at load rather
 * than at spawn is the difference between a console line naming the file and a
 * command that quietly produces nothing.
 */
public final class EntityDefinitions {

    private EntityDefinitions() {
    }

    /** Everything of kind ENTITY in {@code loaded}, parsed. */
    public static Result parse(LoadReport loaded) {
        return parse(loaded, null);
    }

    /**
     * The same, and a warning for every {@code model:} that is not one of
     * {@code items}. Without it a mob whose model names no item spawns as the
     * plain vanilla mob with nothing anywhere to say why, and the usual cause
     * is somebody naming their {@code .bbmodel} where an item id belongs.
     */
    public static Result parse(LoadReport loaded, Set<ContentId> items) {
        Map<ContentId, EntityInfo> entities = new LinkedHashMap<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (loaded == null) {
            return new Result(Map.of(), List.of());
        }
        for (ContentDefinition definition : loaded.definitions(ContentKind.ENTITY)) {
            parseOne(definition, items, diagnostics).ifPresent(entity -> entities.put(entity.id(), entity));
        }
        return new Result(Map.copyOf(entities), List.copyOf(diagnostics));
    }

    private static Optional<EntityInfo> parseOne(ContentDefinition definition, Set<ContentId> items,
                                                 List<Diagnostic> diagnostics) {
        DefinitionNode body = definition.body();
        String origin = definition.origin();
        String where = definition.id().path();

        Optional<String> declared = body.string("type");
        if (declared.isEmpty()) {
            diagnostics.add(Diagnostic.error(origin, where,
                    "No type. A custom entity is a real mob wearing a model, so it needs a mob "
                            + "to be - try type: ZOMBIE."));
            return Optional.empty();
        }
        String type = declared.get().trim().toUpperCase(Locale.ROOT);
        if (!isSpawnableMob(type)) {
            diagnostics.add(Diagnostic.error(origin, where,
                    declared.get() + " is not a mob this can spawn."));
            return Optional.empty();
        }

        // The model is an item id, validated for SHAPE here and, when the
        // caller has the items, for existence: every pack is loaded by then.
        String model = body.string("model").orElse(null);
        if (model != null && ContentId.parse(model).isEmpty()) {
            diagnostics.add(Diagnostic.error(origin, where,
                    "model: " + model + " is not a namespace:id."));
            return Optional.empty();
        }
        if (model != null && items != null && !items.contains(ContentId.parse(model).get())) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "model: " + model + " is no item and no model, so it spawns as a plain "
                            + type.toLowerCase(Locale.ROOT) + ". Save the Blockbench file as "
                            + "assets/models/<name>.bbmodel in that pack and name it as <pack>:<name>."));
        }

        double health = 0;
        Optional<String> declaredHealth = body.string("health");
        if (declaredHealth.isPresent()) {
            try {
                health = Double.parseDouble(declaredHealth.get().trim());
            } catch (NumberFormatException e) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        "health: " + declaredHealth.get() + " is not a number. Using the mob's own."));
            }
            if (health < 0 || health > 1024) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        "health: " + declaredHealth.get() + " is outside 0 to 1024. Using the mob's own."));
                health = 0;
            }
        }

        float scale = 1f;
        Optional<String> declaredScale = body.string("scale");
        if (declaredScale.isPresent()) {
            try {
                scale = Float.parseFloat(declaredScale.get().trim());
            } catch (NumberFormatException e) {
                scale = 1f;
            }
            if (!Float.isFinite(scale) || scale < 0.1f || scale > 16f) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        "scale: " + declaredScale.get() + " is outside 0.1 to 16. Using 1."));
                scale = 1f;
            }
        }

        EntityInfo.Builder built = EntityInfo.builder(definition.id(), type)
                .model(model)
                .name(body.string("name").orElse(null))
                .nameVisible(body.bool("name-visible").orElse(null))
                .health(health)
                .scale(scale)
                .silent(body.bool("silent").orElse(Boolean.FALSE))
                .tags(body.strings("tags"))
                .animations(animations(body, origin, where, diagnostics))
                .sounds(sounds(body, origin, where, diagnostics))
                .drops(drops(body, origin, where, diagnostics))
                .xp((int) Math.round(number(body, "xp", 0, 0, 10000, origin, where, diagnostics)))
                .damage(number(body, "damage", 0, 0, 2048, origin, where, diagnostics))
                .speed(number(body, "speed", 1, 0.1, 4, origin, where, diagnostics))
                .knockbackResistance(number(body, "knockback-resistance", 0, 0, 1, origin, where, diagnostics))
                .height(number(body, "height", 0, 0, 64, origin, where, diagnostics))
                .turn((float) number(body, "turn", 0, -360, 360, origin, where, diagnostics))
                .headBone(body.string("head-bone").orElse(null))
                .ai(body.bool("ai").orElse(Boolean.TRUE))
                .burns(body.bool("burns").orElse(Boolean.FALSE));
        if (model == null && !body.node("animations").map(DefinitionNode::isEmpty).orElse(true)) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "animations: are set but there is no model: to play them on."));
        }
        return Optional.of(built.build());
    }

    /**
     * {@code animations: {idle: idle, walk: walk, attack: bite}}: state to the
     * name of one of the model's animations. Whether the model HAS that
     * animation is only known once its rig is built, so a wrong name is a
     * state that does not animate rather than a load error.
     */
    private static Map<MobState, String> animations(DefinitionNode body, String origin, String where,
                                                     List<Diagnostic> diagnostics) {
        Optional<DefinitionNode> declared = body.node("animations");
        if (declared.isEmpty()) {
            if (body.has("animations")) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        "animations: is not a map of state to animation name. Try animations: {idle: idle, walk: walk}."));
            }
            return Map.of();
        }
        Map<MobState, String> out = new EnumMap<>(MobState.class);
        for (String key : declared.get().keys()) {
            Optional<MobState> state = MobState.parse(key);
            if (state.isEmpty()) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        "animations: " + key + " is not a state. The states are " + stateNames() + "."));
                continue;
            }
            declared.get().string(key).filter(name -> !name.isBlank())
                    .ifPresent(name -> out.put(state.get(), name.trim()));
        }
        return out;
    }

    /** {@code sounds: {ambient: mypack:grunt, hurt: entity.pig.hurt}}. */
    private static Map<String, String> sounds(DefinitionNode body, String origin, String where,
                                              List<Diagnostic> diagnostics) {
        Optional<DefinitionNode> declared = body.node("sounds");
        if (declared.isEmpty()) {
            if (body.has("sounds")) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        "sounds: is not a map. Try sounds: {ambient: mypack:grunt, hurt: mypack:ouch}."));
            }
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : declared.get().keys()) {
            String slot = key.trim().toLowerCase(Locale.ROOT);
            if (!EntityInfo.SOUND_KEYS.contains(slot)) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        "sounds: " + key + " is not one of " + String.join(", ", EntityInfo.SOUND_KEYS) + "."));
                continue;
            }
            declared.get().string(key).filter(sound -> !sound.isBlank())
                    .ifPresent(sound -> out.put(slot, sound.trim()));
        }
        return out;
    }

    /**
     * {@code drops:} as a list, each either an item on its own or
     * {@code {item, min, max, chance}}. What a mob drops INSTEAD of its
     * vanilla loot, so absent and empty are the same: nothing.
     */
    private static List<EntityDrop> drops(DefinitionNode body, String origin, String where,
                                          List<Diagnostic> diagnostics) {
        Object raw = body.raw("drops");
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List)) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "drops: is not a list. Try drops: [{item: minecraft:leather, min: 1, max: 3}]."));
            return List.of();
        }
        List<EntityDrop> out = new ArrayList<>();
        for (Object entry : (List<?>) raw) {
            if (entry instanceof Map) {
                DefinitionNode drop = DefinitionNode.of((Map<?, ?>) entry);
                Optional<String> item = drop.string("item");
                if (item.isEmpty() || item.get().isBlank()) {
                    diagnostics.add(Diagnostic.warning(origin, where, "A drop has no item:, so it drops nothing."));
                    continue;
                }
                int min = drop.integer("min").orElse(drop.integer("amount").orElse(1));
                int max = drop.integer("max").orElse(min);
                out.add(EntityDrop.of(item.get(), min, max, drop.decimal("chance").orElse(1.0)));
            } else if (entry != null && !(entry instanceof List)) {
                out.add(EntityDrop.of(entry.toString(), 1, 1, 1));
            }
        }
        return out;
    }

    /** A number between {@code low} and {@code high}, or {@code otherwise} with a warning. */
    private static double number(DefinitionNode body, String key, double otherwise, double low, double high,
                                 String origin, String where, List<Diagnostic> diagnostics) {
        if (!body.has(key)) {
            return otherwise;
        }
        Optional<Double> value = body.decimal(key);
        if (value.isEmpty() || !Double.isFinite(value.get())) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    key + ": " + body.raw(key) + " is not a number. Using " + plain(otherwise) + "."));
            return otherwise;
        }
        if (value.get() < low || value.get() > high) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    key + ": " + plain(value.get()) + " is outside " + plain(low) + " to " + plain(high)
                            + ". Using " + plain(Math.max(low, Math.min(high, value.get()))) + "."));
            return Math.max(low, Math.min(high, value.get()));
        }
        return value.get();
    }

    private static String plain(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static String stateNames() {
        List<String> names = new ArrayList<>();
        for (MobState state : MobState.values()) {
            names.add(state.key());
        }
        return String.join(", ", names);
    }

    /**
     * Whether {@code name} is a mob a world can be asked to spawn.
     *
     * <p>{@code EntityType} also carries arrows, boats and dropped items, none
     * of which is a thing to give a health bar and a name tag. The check is
     * {@code isSpawnable} plus being alive, and both resolve off the enum
     * without a server.
     */
    private static boolean isSpawnableMob(String name) {
        try {
            org.bukkit.entity.EntityType type = org.bukkit.entity.EntityType.valueOf(name);
            return type.isSpawnable() && type.isAlive();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** The entities, and what was wrong with the ones that are missing. */
    public static final class Result {

        private final Map<ContentId, EntityInfo> entities;
        private final List<Diagnostic> diagnostics;

        Result(Map<ContentId, EntityInfo> entities, List<Diagnostic> diagnostics) {
            this.entities = entities;
            this.diagnostics = diagnostics;
        }

        /** Every entity that parsed, keyed by id. */
        public Map<ContentId, EntityInfo> entities() {
            return entities;
        }

        /** What went wrong. */
        public List<Diagnostic> diagnostics() {
            return diagnostics;
        }
    }
}
