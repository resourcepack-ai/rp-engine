package ai.resourcepack.engine.core.model;

import ai.resourcepack.engine.api.ContentDefinition;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.ModelInfo;
import ai.resourcepack.engine.api.ItemInfo;
import ai.resourcepack.engine.api.LoadReport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Reads placed model out of the item definitions that declare it.
 *
 * <p><strong>Placed model is a property of an item, not a thing beside one.</strong>
 * An id is unique across the whole registry, so {@code mypack:chair} cannot be
 * an item and a placed model at the same time — and needing
 * {@code mypack:chair} plus {@code mypack:chair_placed} for one chair is the
 * sort of tax that makes a format feel like paperwork. So an item that can be
 * put down says so, in a {@code placed model:} block of its own definition.
 *
 * <p>It also removes a whole failure mode: the item and the placed model cannot
 * disagree about which model to use, because there is only one of each.
 *
 * <p>Free of Bukkit entirely, which is the point: everything about what a piece
 * of placed model IS gets decided here and tested, and the listener that spawns
 * entities is left with nothing to be clever about.
 */
public final class ModelDefinitions {

    /** Beyond this a hitbox is more likely a typo than a statue. */
    private static final float MAX_SIZE = 16f;
    private static final float MIN_SIZE = 0.1f;

    private ModelDefinitions() {
    }

    /** As {@link #parse(LoadReport, Map, Map)} with no measurements available. */
    public static Result parse(LoadReport loaded, Map<ContentId, ItemInfo> items) {
        return parse(loaded, items, Map.of());
    }

    /**
     * Every item that declared a {@code placed model:} block, parsed.
     *
     * @param bounds how big each item's model turned out to be, so a piece
     *               that did not state a hitbox gets one that matches what you
     *               can see. Whoever modelled it already decided how big it is;
     *               making them say it again in YAML is how the two end up
     *               disagreeing
     */
    public static Result parse(LoadReport loaded, Map<ContentId, ItemInfo> items,
                               Map<ContentId, ai.resourcepack.engine.core.item.Geometry.Bounds> bounds) {
        Map<ContentId, ModelInfo> model = new LinkedHashMap<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (loaded == null) {
            return new Result(Map.of(), List.of());
        }
        Map<ContentId, ItemInfo> known = items == null ? Map.of() : items;
        for (ContentDefinition definition : loaded.definitions(ContentKind.ITEM)) {
            // Only items that actually parsed. One that named a material
            // nobody has already has a diagnostic; saying it twice helps
            // nobody.
            if (!known.containsKey(definition.id())) {
                continue;
            }
            Optional<DefinitionNode> declared = definition.body().node("place");
            if (declared.isEmpty()) {
                continue;
            }
            parseOne(definition, declared.get(),
                    bounds == null ? null : bounds.get(definition.id()), diagnostics)
                    .ifPresent(one -> model.put(one.id(), one));
        }
        return new Result(Map.copyOf(model), List.copyOf(diagnostics));
    }

    private static Optional<ModelInfo> parseOne(ContentDefinition definition,
                                                    DefinitionNode body,
                                                    ai.resourcepack.engine.core.item.Geometry.Bounds measured,
                                                    List<Diagnostic> diagnostics) {
        String origin = definition.origin();
        String where = definition.id().path();

        ModelInfo.Facing facing = ModelInfo.Facing.CARDINAL;
        Optional<String> declaredFacing = body.string("facing");
        if (declaredFacing.isPresent()) {
            try {
                facing = ModelInfo.Facing.valueOf(declaredFacing.get().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        "facing: " + declaredFacing.get() + " is not one of cardinal, diagonal, free, fixed. "
                                + "Using cardinal."));
            }
        }

        float scale = size(body, "scale", 1f, origin, where, diagnostics);
        // The model's own size is the default, so a two-block statue is
        // punchable everywhere you can see it without anybody measuring
        // anything. A pack that states a hitbox still wins: a chair you are
        // meant to be able to walk close to is a design decision, not a
        // measurement.
        float width = size(body, "width", measured == null ? 1f : measured.width(),
                origin, where, diagnostics);
        float height = size(body, "height", measured == null ? 1f : measured.height(),
                origin, where, diagnostics);

        // The item IS the model, so the two cannot disagree about which
        // model to use.
        // 0 means nobody sits on it, which is every model that does not say
        // otherwise. A seat is measured from the block floor, so a chair whose
        // cushion is drawn 7px up says 0.44.
        float seat = body.string("seat").isPresent()
                ? size(body, "seat", 0f, origin, where, diagnostics)
                : 0f;

        return Optional.of(ModelInfo.of(definition.id(), definition.id(), facing,
                scale, width, height, body.bool("solid").orElse(Boolean.FALSE), seat));
    }

    /**
     * A size, clamped rather than refused.
     *
     * <p>A hitbox of 0 is placed model nobody can break, which is worse than a
     * hitbox that is the wrong size — the piece would be permanent and there
     * would be no way to find out why.
     */
    private static float size(DefinitionNode body, String key, float fallback,
                              String origin, String where, List<Diagnostic> diagnostics) {
        Optional<String> declared = body.string(key);
        if (declared.isEmpty()) {
            return fallback;
        }
        float value;
        try {
            value = Float.parseFloat(declared.get().trim());
        } catch (NumberFormatException e) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    key + ": " + declared.get() + " is not a number. Using " + fallback + "."));
            return fallback;
        }
        if (!Float.isFinite(value) || value < MIN_SIZE || value > MAX_SIZE) {
            float clamped = Math.max(MIN_SIZE, Math.min(MAX_SIZE, Float.isFinite(value) ? value : fallback));
            diagnostics.add(Diagnostic.warning(origin, where,
                    key + ": " + declared.get() + " is outside " + MIN_SIZE + " to " + MAX_SIZE
                            + ". Using " + clamped + "."));
            return clamped;
        }
        return value;
    }

    /** The model, and what was wrong with the pieces that are missing. */
    public static final class Result {

        private final Map<ContentId, ModelInfo> model;
        private final List<Diagnostic> diagnostics;

        Result(Map<ContentId, ModelInfo> model, List<Diagnostic> diagnostics) {
            this.model = model;
            this.diagnostics = diagnostics;
        }

        /** Every piece that parsed, keyed by id. */
        public Map<ContentId, ModelInfo> model() {
            return model;
        }

        /** The piece placed by {@code item}, if any is. */
        public Optional<ModelInfo> byItem(ContentId item) {
            for (ModelInfo one : model.values()) {
                if (one.item().equals(item)) {
                    return Optional.of(one);
                }
            }
            return Optional.empty();
        }

        /** What went wrong. */
        public List<Diagnostic> diagnostics() {
            return diagnostics;
        }
    }
}
