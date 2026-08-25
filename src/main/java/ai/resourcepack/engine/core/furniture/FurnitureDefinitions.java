package ai.resourcepack.engine.core.furniture;

import ai.resourcepack.engine.api.ContentDefinition;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.FurnitureInfo;
import ai.resourcepack.engine.api.ItemInfo;
import ai.resourcepack.engine.api.LoadReport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Reads furniture out of the item definitions that declare it.
 *
 * <p><strong>Furniture is a property of an item, not a thing beside one.</strong>
 * An id is unique across the whole registry, so {@code mypack:chair} cannot be
 * an item and a piece of furniture at the same time — and needing
 * {@code mypack:chair} plus {@code mypack:chair_furniture} for one chair is the
 * sort of tax that makes a format feel like paperwork. So an item that can be
 * put down says so, in a {@code furniture:} block of its own definition.
 *
 * <p>It also removes a whole failure mode: the item and the furniture cannot
 * disagree about which model to use, because there is only one of each.
 *
 * <p>Free of Bukkit entirely, which is the point: everything about what a piece
 * of furniture IS gets decided here and tested, and the listener that spawns
 * entities is left with nothing to be clever about.
 */
public final class FurnitureDefinitions {

    /** Beyond this a hitbox is more likely a typo than a statue. */
    private static final float MAX_SIZE = 16f;
    private static final float MIN_SIZE = 0.1f;

    private FurnitureDefinitions() {
    }

    /** Every item that declared a {@code furniture:} block, parsed. */
    public static Result parse(LoadReport loaded, Map<ContentId, ItemInfo> items) {
        Map<ContentId, FurnitureInfo> furniture = new LinkedHashMap<>();
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
            Optional<DefinitionNode> declared = definition.body().node("furniture");
            if (declared.isEmpty()) {
                continue;
            }
            parseOne(definition, declared.get(), diagnostics)
                    .ifPresent(one -> furniture.put(one.id(), one));
        }
        return new Result(Map.copyOf(furniture), List.copyOf(diagnostics));
    }

    private static Optional<FurnitureInfo> parseOne(ContentDefinition definition,
                                                    DefinitionNode body,
                                                    List<Diagnostic> diagnostics) {
        String origin = definition.origin();
        String where = definition.id().path();

        FurnitureInfo.Facing facing = FurnitureInfo.Facing.CARDINAL;
        Optional<String> declaredFacing = body.string("facing");
        if (declaredFacing.isPresent()) {
            try {
                facing = FurnitureInfo.Facing.valueOf(declaredFacing.get().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        "facing: " + declaredFacing.get() + " is not one of cardinal, diagonal, free, fixed. "
                                + "Using cardinal."));
            }
        }

        float scale = size(body, "scale", 1f, origin, where, diagnostics);
        float width = size(body, "width", 1f, origin, where, diagnostics);
        float height = size(body, "height", 1f, origin, where, diagnostics);

        // The item IS the furniture, so the two cannot disagree about which
        // model to use.
        return Optional.of(FurnitureInfo.of(definition.id(), definition.id(), facing,
                scale, width, height, body.bool("solid").orElse(Boolean.FALSE)));
    }

    /**
     * A size, clamped rather than refused.
     *
     * <p>A hitbox of 0 is furniture nobody can break, which is worse than a
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

    /** The furniture, and what was wrong with the pieces that are missing. */
    public static final class Result {

        private final Map<ContentId, FurnitureInfo> furniture;
        private final List<Diagnostic> diagnostics;

        Result(Map<ContentId, FurnitureInfo> furniture, List<Diagnostic> diagnostics) {
            this.furniture = furniture;
            this.diagnostics = diagnostics;
        }

        /** Every piece that parsed, keyed by id. */
        public Map<ContentId, FurnitureInfo> furniture() {
            return furniture;
        }

        /** The piece placed by {@code item}, if any is. */
        public Optional<FurnitureInfo> byItem(ContentId item) {
            for (FurnitureInfo one : furniture.values()) {
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
