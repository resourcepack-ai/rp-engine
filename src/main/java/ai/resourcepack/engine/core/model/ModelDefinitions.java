package ai.resourcepack.engine.core.model;

import ai.resourcepack.engine.api.ContentDefinition;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.ItemInfo;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.api.ModelInfo;

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
        checkGrowth(model, loaded.definitions(ContentKind.ITEM), diagnostics);
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
        // A seat is a height, or three numbers when the seat is not in the
        // middle of the piece — a bench, a car, an L-shaped sofa. The short
        // form stays because almost every chair wants it.
        float seat = 0f;
        float seatSide = 0f;
        float seatForward = 0f;
        Optional<DefinitionNode> placed = body.node("seat");
        if (placed.isPresent()) {
            seat = offset(placed.get(), "y", origin, where, diagnostics);
            seatSide = offset(placed.get(), "x", origin, where, diagnostics);
            seatForward = offset(placed.get(), "z", origin, where, diagnostics);
        } else if (body.string("seat").isPresent()) {
            seat = offset(body, "seat", origin, where, diagnostics);
        }

        int light = body.integer("light").orElse(0);
        if (light < 0 || light > 15) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "light: " + light + " is outside 0-15 and was clamped."));
        }

        ModelInfo.Surface surface = ModelInfo.Surface.FLOOR;
        Optional<String> declaredSurface = body.string("surface");
        if (declaredSurface.isPresent()) {
            try {
                surface = ModelInfo.Surface.valueOf(
                        declaredSurface.get().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        "surface: " + declaredSurface.get()
                                + " is not floor, wall, ceiling or any. It stays on the floor."));
            }
        }

        ContentId drop = null;
        Optional<String> declaredDrop = body.string("drop");
        if (declaredDrop.isPresent()) {
            drop = ContentId.parse(declaredDrop.get()).orElse(null);
            if (drop == null) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        "drop: " + declaredDrop.get() + " is not a namespace:id. "
                                + "It gives back the item it was placed from."));
            }
        }

        // The same parser a custom block's storage goes through, so the two
        // cannot disagree about what a setting means.
        ai.resourcepack.engine.api.StorageSpec storage = ai.resourcepack.engine.core.storage.StorageDefinitions
                .parse(body, origin, where, diagnostics).orElse(null);
        if (storage != null && storage.type() == ai.resourcepack.engine.api.StorageSpec.Type.SHULKER
                && drop != null) {
            // A shulker-style piece gives back ITSELF with the contents inside,
            // and putting that down again is what restores them. Anything else
            // dropped would carry contents no placement can unpack.
            diagnostics.add(Diagnostic.warning(origin, where,
                    "drop: is ignored on a shulker storage, which always gives back itself with its "
                            + "contents inside."));
            drop = null;
        }

        ModelInfo.Jukebox jukebox = jukebox(body, origin, where, diagnostics);
        if (jukebox != null && storage != null) {
            // One click, and the container comes first in the chain, so the
            // jukebox would never hear one. Said rather than silently true.
            diagnostics.add(Diagnostic.warning(origin, where,
                    "jukebox: is never reached on a piece with storage:, which takes the click first. "
                            + "It only opens."));
        }

        // Absent is TRUE, unlike `solid` beside it: a vehicle driving through a
        // bollard is wrong in every pack that has one, so the exception is the
        // thing worth writing down. See ModelInfo.vehicleCollision.
        boolean vehicleCollision = !Boolean.FALSE.equals(
                body.bool("vehicle-collision").orElse(Boolean.TRUE));

        // The model's real boxes, so a vehicle hits the ART rather than a
        // square column around it. Empty when the model could not be measured,
        // which falls back to the hitbox exactly as before.
        ai.resourcepack.engine.api.ModelShape shape = measured == null
                ? ai.resourcepack.engine.api.ModelShape.NONE
                : measured.shape();

        return Optional.of(ModelInfo.of(definition.id(), definition.id(), facing,
                        scale, width, height, body.bool("solid").orElse(Boolean.FALSE), seat,
                        light, surface, drop)
                .withSeatOffset(seatSide, seatForward)
                .withVehicleCollision(vehicleCollision)
                .withShape(shape)
                .withStorage(storage)
                .withJukebox(jukebox)
                .withStates(states(body, origin, where, diagnostics),
                        stateReset(body, origin, where, diagnostics),
                        soundKey(body, "base-sound", origin, where, diagnostics))
                .withGrow(grow(body, origin, where, diagnostics)));
    }

    /**
     * A {@code grow:} block, or null. Whether {@code into} is actually a
     * placed model is checked once everything is parsed, because it may be
     * defined further down the same file or in another one.
     */
    static ModelInfo.Grow grow(DefinitionNode body, String origin, String where,
                               List<Diagnostic> diagnostics) {
        if (!body.has("grow")) {
            return null;
        }
        Optional<DefinitionNode> block = body.node("grow");
        if (block.isEmpty()) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "grow: should be a block with at least `into:`. It never grows."));
            return null;
        }
        DefinitionNode grow = block.get();
        Optional<String> declaredInto = grow.string("into");
        ContentId into = declaredInto.flatMap(text -> ContentId.parse(text.trim())).orElse(null);
        if (into == null) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "grow into: " + declaredInto.orElse("(missing)") + " is not a namespace:id. It never grows."));
            return null;
        }

        long after = 0L;
        Optional<String> declaredAfter = grow.string("after");
        if (declaredAfter.isPresent()) {
            java.util.OptionalLong ticks = Durations.ticks(declaredAfter.get());
            if (ticks.isEmpty()) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        "grow after: " + declaredAfter.get() + " is not a time like 10s, 200t or 2m. "
                                + "It may grow at the first check."));
            } else {
                after = ticks.getAsLong();
            }
        }

        double chance = 1d;
        if (grow.has("chance")) {
            Optional<Double> declared = grow.decimal("chance");
            if (declared.isEmpty() || !(declared.get() > 0) || declared.get() > 1) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        "grow chance: " + grow.raw("chance") + " should be above 0 and at most 1. Using 1."));
            } else {
                chance = declared.get();
            }
        }

        int light = 0;
        if (grow.has("light")) {
            Optional<Integer> declared = grow.integer("light");
            if (declared.isEmpty() || declared.get() < 0 || declared.get() > 15) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        "grow light: " + grow.raw("light") + " should be a light level, 0-15. "
                                + "It grows in any light."));
            } else {
                light = declared.get();
            }
        }
        return ModelInfo.Grow.of(into, after, chance, light);
    }

    /**
     * Takes {@code grow:} off every piece whose {@code into} is not a placed
     * model, saying so. A piece that grew into nothing would be a piece that
     * vanished, which is the one outcome worse than never growing.
     */
    private static void checkGrowth(Map<ContentId, ModelInfo> model, List<ContentDefinition> definitions,
                                    List<Diagnostic> diagnostics) {
        for (Map.Entry<ContentId, ModelInfo> entry : new ArrayList<>(model.entrySet())) {
            ModelInfo.Grow grow = entry.getValue().grow().orElse(null);
            if (grow == null) {
                continue;
            }
            String problem = null;
            if (grow.into().equals(entry.getKey())) {
                problem = "grow into: is the piece itself, which would only ever replace itself.";
            } else if (!model.containsKey(grow.into())) {
                problem = "grow into: " + grow.into() + " is not an item with a place: block, so there is "
                        + "nothing to grow into. It never grows.";
            }
            if (problem != null) {
                String origin = definitions.stream()
                        .filter(one -> one.id().equals(entry.getKey()))
                        .map(ContentDefinition::origin)
                        .findFirst().orElse(entry.getKey().toString());
                diagnostics.add(Diagnostic.warning(origin, entry.getKey().path(), problem));
                model.put(entry.getKey(), entry.getValue().withGrow(null));
            }
        }
    }

    /** More than this is a list somebody generated by mistake, not a lamp. */
    private static final int MAX_STATES = 64;

    /** A sound key: a resource location, namespace optional. */
    private static final java.util.regex.Pattern SOUND_KEY =
            java.util.regex.Pattern.compile("([a-z0-9_.-]+:)?[a-z0-9_./-]+");

    /**
     * The {@code states:} list. Each entry is a change from the piece as
     * defined; see {@link ModelInfo.State}.
     */
    static List<ModelInfo.State> states(DefinitionNode body, String origin, String where,
                                        List<Diagnostic> diagnostics) {
        if (!body.has("states")) {
            return List.of();
        }
        Object raw = body.raw("states");
        List<DefinitionNode> entries = body.nodes("states");
        if (!(raw instanceof List) && !(raw instanceof Map)) {
            // `states: on`, or a state's settings written without a list.
            diagnostics.add(Diagnostic.warning(origin, where,
                    "states: should be a list of blocks like `- light: 15`. A click does not change it."));
            return List.of();
        }
        int written = raw instanceof List ? ((List<?>) raw).size() : 1;
        if (entries.size() < written) {
            // A bare word in the list. Each entry has to be a block of
            // settings, even a one-line one.
            int skipped = written - entries.size();
            diagnostics.add(Diagnostic.warning(origin, where,
                    "states: " + skipped + (skipped == 1 ? " entry is" : " entries are")
                            + " not a block of settings like `- light: 15` and "
                            + (skipped == 1 ? "was" : "were") + " skipped."));
        }
        List<ModelInfo.State> states = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            if (states.size() >= MAX_STATES) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        "states: has more than " + MAX_STATES + " entries. The rest were skipped."));
                break;
            }
            states.add(state(entries.get(i), i + 1, origin, where, diagnostics));
        }
        return states;
    }

    private static ModelInfo.State state(DefinitionNode entry, int index, String origin, String where,
                                         List<Diagnostic> diagnostics) {
        String label = "state " + index + " ";

        ContentId model = null;
        Optional<String> declaredModel = entry.string("model");
        if (declaredModel.isPresent()) {
            model = ContentId.parse(declaredModel.get().trim()).orElse(null);
            if (model == null) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        label + "model: " + declaredModel.get() + " is not a namespace:id. It keeps its model."));
            }
        }

        Integer light = null;
        if (entry.has("light")) {
            Optional<Integer> declared = entry.integer("light");
            if (declared.isEmpty()) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        label + "light: " + entry.raw("light") + " is not a whole number. It keeps its light."));
            } else {
                light = declared.get();
                if (light < 0 || light > 15) {
                    diagnostics.add(Diagnostic.warning(origin, where,
                            label + "light: " + light + " is outside 0-15 and was clamped."));
                }
            }
        }

        Boolean solid = null;
        if (entry.has("solid")) {
            solid = entry.bool("solid").orElse(null);
            if (solid == null) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        label + "solid: " + entry.raw("solid") + " is not true or false. It stays as it was."));
            }
        }

        float turn = 0f;
        if (entry.has("turn")) {
            Optional<Double> declared = entry.decimal("turn");
            if (declared.isEmpty() || !Double.isFinite(declared.get())) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        label + "turn: " + entry.raw("turn") + " is not a number of degrees. It does not turn."));
            } else {
                // 450 and 90 are the same door; folding it keeps the number the
                // engine works with the one an author would recognise.
                turn = (float) (declared.get() % 360.0);
            }
        }

        float[] offset = {0f, 0f, 0f};
        if (entry.has("offset")) {
            Object raw = entry.raw("offset");
            boolean ok = raw instanceof List && ((List<?>) raw).size() == 3;
            if (ok) {
                List<?> parts = (List<?>) raw;
                for (int axis = 0; axis < 3 && ok; axis++) {
                    Object part = parts.get(axis);
                    double value;
                    if (part instanceof Number) {
                        value = ((Number) part).doubleValue();
                    } else {
                        try {
                            value = part == null ? Double.NaN : Double.parseDouble(part.toString().trim());
                        } catch (NumberFormatException e) {
                            value = Double.NaN;
                        }
                    }
                    if (!Double.isFinite(value) || Math.abs(value) > MAX_SIZE) {
                        ok = false;
                    } else {
                        offset[axis] = (float) value;
                    }
                }
            }
            if (!ok) {
                offset = new float[]{0f, 0f, 0f};
                diagnostics.add(Diagnostic.warning(origin, where,
                        label + "offset: should be three numbers of blocks, [right, up, forward], each within "
                                + MAX_SIZE + ". It does not move."));
            }
        }

        String sound = soundKey(entry, "sound", origin, where, diagnostics);
        return ModelInfo.State.of(model, light, solid, turn, offset[0], offset[1], offset[2], sound);
    }

    /** {@code reset-after:} in ticks, or 0 for never. */
    private static long stateReset(DefinitionNode body, String origin, String where,
                                   List<Diagnostic> diagnostics) {
        Optional<String> declared = body.string("reset-after");
        if (declared.isEmpty()) {
            return 0L;
        }
        java.util.OptionalLong ticks = Durations.ticks(declared.get());
        if (ticks.isEmpty()) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "reset-after: " + declared.get() + " is not a time like 10s, 200t or 2m. "
                            + "It stays in whatever state it is clicked into."));
            return 0L;
        }
        if (!body.has("states")) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "reset-after: does nothing without states: to go back from."));
        }
        return ticks.getAsLong();
    }

    /** A sound key under {@code key}, or null for none or one that is not a key. */
    private static String soundKey(DefinitionNode body, String key, String origin, String where,
                                   List<Diagnostic> diagnostics) {
        Optional<String> declared = body.string(key);
        if (declared.isEmpty() || declared.get().isBlank()) {
            return null;
        }
        String lower = declared.get().trim().toLowerCase(Locale.ROOT);
        if (!SOUND_KEY.matcher(lower).matches()) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    key + ": " + declared.get() + " is not a sound key like minecraft:block.wooden_door.open. "
                            + "It is silent."));
            return null;
        }
        return lower;
    }

    /**
     * A {@code jukebox:} block, or {@code jukebox: true} for one with every
     * default. Null for a piece that is not one.
     */
    static ModelInfo.Jukebox jukebox(DefinitionNode body, String origin, String where,
                                     List<Diagnostic> diagnostics) {
        if (!body.has("jukebox")) {
            return null;
        }
        Optional<DefinitionNode> block = body.node("jukebox");
        if (block.isEmpty()) {
            Optional<Boolean> on = body.bool("jukebox");
            if (on.isEmpty()) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        "jukebox: should be a block of settings or true. It plays nothing."));
                return null;
            }
            return on.get() ? ModelInfo.Jukebox.of(1f, 1f, null, null) : null;
        }
        DefinitionNode jukebox = block.get();

        float volume = 1f;
        if (jukebox.has("volume")) {
            Optional<Double> declared = jukebox.decimal("volume");
            if (declared.isEmpty() || !(declared.get() > 0) || declared.get() > 16) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        "jukebox volume: " + jukebox.raw("volume") + " should be a number above 0 and "
                                + "at most 16. Using 1, a vanilla jukebox."));
            } else {
                volume = declared.get().floatValue();
            }
        }
        float pitch = 1f;
        if (jukebox.has("pitch")) {
            Optional<Double> declared = jukebox.decimal("pitch");
            if (declared.isEmpty() || declared.get() < 0.5 || declared.get() > 2) {
                // The game clamps a sound's pitch to this range anyway; saying
                // so here beats an author wondering why 3 sounds like 2.
                diagnostics.add(Diagnostic.warning(origin, where,
                        "jukebox pitch: " + jukebox.raw("pitch") + " should be between 0.5 and 2. "
                                + "Using 1."));
            } else {
                pitch = declared.get().floatValue();
            }
        }
        ContentId playing = null;
        Optional<String> declaredModel = jukebox.string("playing-model");
        if (declaredModel.isPresent()) {
            playing = ContentId.parse(declaredModel.get().trim()).orElse(null);
            if (playing == null) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        "jukebox playing-model: " + declaredModel.get() + " is not a namespace:id. "
                                + "The piece looks the same while it plays."));
            }
        }
        return ModelInfo.Jukebox.of(volume, pitch, jukebox.string("permission").orElse(null), playing);
    }

    /**
     * An offset, which is not a size.
     *
     * <p>Separate from {@link #size} because the two have genuinely different
     * ranges: a size is at least {@link #MIN_SIZE} — nothing is a thousandth
     * of a block wide — while an offset is legitimately zero (no seat) and
     * legitimately negative (behind, or to the left). Running one through the
     * other clamped {@code z: -0.15} up to {@code 0.1} and turned
     * {@code seat: 0} into a seat.
     */
    private static float offset(DefinitionNode body, String key,
                                String origin, String where, List<Diagnostic> diagnostics) {
        Optional<String> declared = body.string(key);
        if (declared.isEmpty()) {
            return 0f;
        }
        float value;
        try {
            value = Float.parseFloat(declared.get().trim());
        } catch (NumberFormatException e) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    key + ": " + declared.get() + " is not a number. Using 0."));
            return 0f;
        }
        if (!Float.isFinite(value) || Math.abs(value) > MAX_SIZE) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    key + ": " + declared.get() + " is further than " + MAX_SIZE
                            + " blocks from the piece. Using 0."));
            return 0f;
        }
        return value;
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
