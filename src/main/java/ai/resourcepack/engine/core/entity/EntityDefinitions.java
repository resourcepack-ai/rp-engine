package ai.resourcepack.engine.core.entity;

import ai.resourcepack.engine.api.ContentDefinition;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.EntityInfo;
import ai.resourcepack.engine.api.LoadReport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

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
        Map<ContentId, EntityInfo> entities = new LinkedHashMap<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (loaded == null) {
            return new Result(Map.of(), List.of());
        }
        for (ContentDefinition definition : loaded.definitions(ContentKind.ENTITY)) {
            parseOne(definition, diagnostics).ifPresent(entity -> entities.put(entity.id(), entity));
        }
        return new Result(Map.copyOf(entities), List.copyOf(diagnostics));
    }

    private static Optional<EntityInfo> parseOne(ContentDefinition definition, List<Diagnostic> diagnostics) {
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

        // The model is an item id, validated for SHAPE here and for existence
        // at spawn: the item may belong to a pack that has not loaded yet.
        String model = body.string("model").orElse(null);
        if (model != null && ContentId.parse(model).isEmpty()) {
            diagnostics.add(Diagnostic.error(origin, where,
                    "model: " + model + " is not a namespace:id."));
            return Optional.empty();
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

        return Optional.of(EntityInfo.of(definition.id(), type, model,
                body.string("name").orElse(null), health, scale,
                body.bool("silent").orElse(Boolean.FALSE), body.strings("tags")));
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
