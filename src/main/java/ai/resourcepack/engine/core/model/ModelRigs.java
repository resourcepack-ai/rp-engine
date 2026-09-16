package ai.resourcepack.engine.core.model;

import ai.resourcepack.engine.api.AnimationSettings;
import ai.resourcepack.engine.api.BoneBehaviour;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Splits an animated model into the parts a server can actually move.
 *
 * <p>The client cannot animate a block model. A server can move display
 * entities, so an animated model is placed as one {@link org.bukkit.entity.ItemDisplay}
 * per moving bone plus one for everything that stays still, and
 * {@link RigAnimator} retimes their transforms from the keyframes. That
 * technique arrived here with studio's pushed models; this class is what lets
 * a model somebody authored in Blockbench use it too.
 *
 * <p><strong>A rig is a tree.</strong> A part composes every one of its
 * bone's ancestors' steps before its own, root first, so rotating a torso
 * carries the arms inside it. Both sides flattened that until 0.43.0 and
 * agreed on the wrong answer, which is what a nested rig animating wrongly
 * from a push turned out to be.
 *
 * <p><strong>It is a port of Studio's own rig builder, and staying a port is
 * the point.</strong> Both sides feed one animator, so a rig computed
 * here and a rig computed there have to be the same rig — same parts, same
 * program order, same pivots. Change the rule on one side and a model that
 * plays correctly from a push plays wrong from a folder, which is close to
 * undebuggable because the art is identical.
 *
 * <p>Free of Bukkit, so all of it is tested without a server.
 */
public final class ModelRigs {

    /** Separates a model id from its part index. Studio writes the same. */
    static final String PART_MARKER = "__part";

    private ModelRigs() {
    }

    /** One transform in a part's chain, innermost last. */
    public static final class Step {

        private final String target;
        private final float[] pivot;
        private final float[] rotate;

        Step(String target, float[] pivot) {
            this(target, pivot, null);
        }

        Step(String target, float[] pivot, float[] rotate) {
            this.target = target;
            this.pivot = pivot;
            this.rotate = rotate;
        }

        /**
         * A step that turns a cube by a fixed amount and reads no keyframes.
         *
         * <p>What a cube rotated past the block model format's five legal
         * angles, or on more than one axis, becomes: the pack draws it
         * untilted and the display entity carries the turn. See
         * {@link #rotate()}.
         */
        static Step fixed(float[] pivot, float[] degrees) {
            return new Step(null, pivot, degrees);
        }

        /**
         * The animator key this step reads: {@code g:<bone>} or an element
         * index — or null on a fixed step, which reads none.
         */
        public String target() {
            return target;
        }

        /** The point it rotates and scales about, in model pixels. */
        public float[] pivot() {
            return pivot.clone();
        }

        /**
         * Degrees about x, y and z about {@link #pivot()}, composed in that
         * order — or null on an ordinary animated step.
         *
         * <p>Fixed rather than sampled, so the animator composes it every
         * tick whether or not anything is playing: a model can have no
         * animations at all and still need one of these, which is the point
         * of them. It is always the innermost step of its part, being the
         * cube's rest pose.
         */
        public float[] rotate() {
            return rotate == null ? null : rotate.clone();
        }
    }

    /** One display entity's worth of a model. */
    public static final class Part {

        private final String item;
        private final List<Integer> elements;
        private final List<Step> program;
        private final String bone;
        private final BoneBehaviour behaviour;
        private final float[] pivot;
        private final float size;
        private final List<String> lineage;
        private final float[] anchor;

        Part(String item, List<Integer> elements, List<Step> program,
             String bone, BoneBehaviour behaviour, float[] pivot, float size, List<String> lineage,
             float[] anchor) {
            this.item = item;
            this.elements = List.copyOf(elements);
            this.program = List.copyOf(program);
            this.bone = bone;
            this.behaviour = behaviour;
            this.pivot = pivot;
            this.size = size;
            this.lineage = List.copyOf(lineage);
            this.anchor = anchor;
        }

        /**
         * How far this part's own geometry was moved so that its innermost
         * pivot sits at the model's centre, in model pixels - or empty for a
         * part drawn where the source put it.
         *
         * <p><strong>This is what stops a spinning wheel shaking.</strong> The
         * client tweens a display's translation and its rotation as two
         * separate quantities. A part rotating about a pivot a distance r from
         * the entity's origin has a translation that CHANGES with the angle
         * (it is {@code p - R*p}), so between two sends the tween passes
         * through poses with the part off its pivot, bulging outward by
         * r(1 - cos(step/2)). A go-kart's wheels breathed at 10 Hz because of
         * it; a BMX's, nine pixels from the origin and turning fast, visibly
         * wobbled. With the geometry re-centred on the pivot the translation
         * is the constant {@code anchor} and only the rotation moves, and a
         * rotation tweened on its own is exactly a rotation.
         *
         * <p>The animator adds it back as the innermost step of the part's
         * transform, so the part draws where it always did. A pushed rig
         * without one is drawn the old way, which is why it is optional.
         */
        public Optional<float[]> anchor() {
            return anchor == null ? Optional.empty() : Optional.of(anchor.clone());
        }

        /** The item model id this part renders as: {@code <modelId>__part<n>}. */
        public String item() {
            return item;
        }

        /** Which of the source model's elements it draws. */
        public List<Integer> elements() {
            return elements;
        }

        /** Empty for the static remainder, which never moves. */
        public List<Step> program() {
            return program;
        }

        /** The bone this part came from, or empty for a loose cube. */
        public String bone() {
            return bone == null ? "" : bone;
        }

        /** What that bone does besides being drawn. Usually nothing. */
        public BoneBehaviour behaviour() {
            return behaviour;
        }

        /**
         * The bone's own pivot, in model pixels.
         *
         * <p>Carried separately from the program because a behaviour needs to
         * know WHERE the bone is even when the bone does not move: a seat and
         * a nametag on a still bone are both perfectly ordinary.
         */
        public float[] pivot() {
            return pivot == null ? new float[]{8f, 8f, 8f} : pivot.clone();
        }

        /**
         * How big this part is, in blocks: its widest side.
         *
         * <p>Measured here because this is the only place that holds the
         * geometry — the model is in the pack and the server never sees it.
         * A sub-hitbox that had to guess would be either a box round the whole
         * animal, which defeats the point, or a number somebody made up.
         */
        public float size() {
            return size;
        }

        /**
         * Every bone this part hangs off, root first, ending with its own.
         *
         * <p>What makes "this layer moves the upper body only" answerable. A
         * mask naming a torso has to reach the arms inside it, and the arms
         * know their own name and nothing else \u2014 so the lineage is written
         * down here, where the tree is still in hand.
         */
        public List<String> lineage() {
            return lineage;
        }
    }

    /** A model's parts and the animations they play. */
    public static final class Rig {

        private final List<Part> parts;
        private final JsonArray animations;
        /** Bone name (lowercased) -> damage multiplier. Usually empty. */
        private final Map<String, Double> damage = new LinkedHashMap<>();

        Rig(List<Part> parts, JsonArray animations) {
            this.parts = List.copyOf(parts);
            this.animations = animations;
        }

        public List<Part> parts() {
            return parts;
        }

        /**
         * The animations, in the shape {@link RigStore} reads.
         *
         * <p>A copy, so a caller cannot reach in and change a rig that has
         * already been registered. {@link #apply} edits the real one, which is
         * why it is a method here and not something a caller does itself.
         */
        public JsonArray animations() {
            return animations.deepCopy();
        }
    }

    /**
     * The rig for {@code model}, or empty if there is nothing to animate.
     *
     * <p>Empty is the ordinary answer and not a failure: most models are
     * still, and a still model places as one display with no rig involved.
     */
    public static Optional<Rig> compute(String modelId, JsonObject model) {
        if (modelId == null || model == null) {
            return Optional.empty();
        }
        JsonArray elements = array(model, "elements");
        JsonArray groups = array(model, "groups");
        JsonArray animations = array(model, "animations");

        Set<String> animated = animatedTargets(animations);
        // A cube turned past what the format can say needs a display of its
        // own even in a model with no animation whatsoever — the entity IS the
        // rotation — so it forces a rig on the same footing as a keyframe, and
        // everything below treats the two alike. Studio's builder has the same
        // rule and the two have to agree; see the class note.
        Set<Integer> freelyRotated = freelyRotated(elements);
        if (animated.isEmpty() && freelyRotated.isEmpty()) {
            return Optional.empty();
        }

        List<Part> parts = new ArrayList<>();
        Set<Integer> claimed = new HashSet<>();
        // Read before the split, because a behaviour is inherited: every bone
        // under an hi_ one is a head bone too, and the bone itself may be a
        // container with no cubes of its own to hang the answer on.
        List<BoneBehaviour> behaviours = behaviours(groups);

        // A bone's cubes move with the bone AND with everything the bone
        // hangs off, so what a part composes is the whole ancestor chain:
        // root first, own bone last, and a cube's own step inside all of it.
        // A bone whose chain is entirely still has no part of its own and its
        // cubes ride along in the remainder.
        for (int g = 0; g < groups.size(); g++) {
            if (!groups.get(g).isJsonObject()) {
                continue;
            }
            List<Step> chain = chainOf(groups, g, animated);
            if (chain.isEmpty()) {
                continue;
            }
            JsonObject group = groups.get(g).getAsJsonObject();

            List<Integer> boneCubes = new ArrayList<>();
            for (JsonElement child : array(group, "children")) {
                if (!child.isJsonPrimitive()) {
                    continue;
                }
                int index;
                try {
                    index = child.getAsInt();
                } catch (NumberFormatException e) {
                    continue;
                }
                if (index < 0 || index >= elements.size() || !claimed.add(index)) {
                    continue;
                }
                if (animated.contains(String.valueOf(index)) || freelyRotated.contains(index)) {
                    parts.add(part(modelId, parts.size(), List.of(index),
                            ownProgram(elements, index, chain, animated, freelyRotated),
                            nameOf(group), behaviours.get(g), originOf(group), elements,
                            lineageOf(groups, g)));
                } else {
                    boneCubes.add(index);
                }
            }
            if (!boneCubes.isEmpty()) {
                parts.add(part(modelId, parts.size(), boneCubes, chain,
                        nameOf(group), behaviours.get(g), originOf(group), elements,
                        lineageOf(groups, g)));
            }
        }

        // Cubes that move or are tilted on their own, including ones sitting
        // in a bone that is not itself animated.
        for (int i = 0; i < elements.size(); i++) {
            if (claimed.contains(i) || !(animated.contains(String.valueOf(i)) || freelyRotated.contains(i))) {
                continue;
            }
            claimed.add(i);
            parts.add(part(modelId, parts.size(), List.of(i),
                    ownProgram(elements, i, List.of(), animated, freelyRotated),
                    "", BoneBehaviour.NONE, pivotOf(elements, i), elements, List.of()));
        }

        // Everything left rides along as one still part. It is a part rather
        // than being left on the original display because the moving parts
        // have taken their cubes out of that model, and what remains has to be
        // drawn by something.
        List<Integer> remainder = new ArrayList<>();
        for (int i = 0; i < elements.size(); i++) {
            if (!claimed.contains(i)) {
                remainder.add(i);
            }
        }
        if (!remainder.isEmpty()) {
            parts.add(part(modelId, parts.size(), remainder, List.of(),
                    "", BoneBehaviour.NONE, new float[]{8f, 8f, 8f}, elements, List.of()));
        }

        boolean moves = parts.stream().anyMatch(part -> !part.program().isEmpty());
        return moves ? Optional.of(new Rig(parts, animations)) : Optional.empty();
    }

    /**
     * A part's own model: the source's textures, and only this part's cubes.
     *
     * <p>Groups and animations are stripped deliberately. They have been read
     * by now, they mean nothing to the client, and a copy in every part file
     * would multiply the largest thing in the model by however many parts it
     * has.
     */
    public static JsonObject partModel(JsonObject model, Part part) {
        JsonArray elements = array(model, "elements");
        JsonArray mine = new JsonArray();
        for (int index : part.elements()) {
            if (index >= 0 && index < elements.size()) {
                // Re-centred on its own pivot where it can be - see anchor().
                JsonElement one = part.anchor == null || !elements.get(index).isJsonObject()
                        ? elements.get(index)
                        : shifted(elements.get(index).getAsJsonObject(), part.anchor);
                mine.add(untilted(one, freeAngles(elements, index) != null));
            }
        }

        JsonObject out = new JsonObject();
        JsonElement parent = model.get("parent");
        out.addProperty("parent", parent != null && parent.isJsonPrimitive()
                ? parent.getAsString()
                : "block/block");
        if (model.get("textures") != null) {
            out.add("textures", model.get("textures"));
        }
        out.add("elements", mine);
        JsonElement renderType = model.get("render_type");
        if (renderType != null) {
            out.add("render_type", renderType);
        }
        return out;
    }

    /**
     * Writes an author's settings onto the animations of a computed rig.
     *
     * <p>Applied here, at the moment the rig is built, rather than reconciled
     * later: a rig that already carries how it plays is one thing to hand to
     * the store, and there is no window in which the two disagree.
     *
     * <p>A name that matches no animation is ignored. The names live in a
     * {@code .bbmodel} the definition parser never opened, so it cannot warn
     * about one, and this is where both halves are finally in the same room —
     * which makes it the one place that CAN.
     *
     * @return the names that matched nothing, for a caller that wants to say so
     */
    public static List<String> apply(Rig rig, Map<String, AnimationSettings> settings) {
        List<String> unmatched = new ArrayList<>();
        if (rig == null || settings == null || settings.isEmpty()) {
            return unmatched;
        }
        for (Map.Entry<String, AnimationSettings> entry : settings.entrySet()) {
            boolean matched = false;
            for (JsonElement raw : rig.animations) {
                if (!raw.isJsonObject()) {
                    continue;
                }
                JsonObject animation = raw.getAsJsonObject();
                JsonElement name = animation.get("name");
                if (name == null || !name.isJsonPrimitive() || !entry.getKey().equals(name.getAsString())) {
                    continue;
                }
                matched = true;
                AnimationSettings one = entry.getValue();
                if (one.mode() != null) {
                    animation.addProperty("mode", one.mode().written());
                    // The boolean is what a plugin older than modes reads, so
                    // it has to keep agreeing with the mode beside it.
                    animation.addProperty("loop", one.mode() == AnimationSettings.Mode.LOOP);
                }
                if (one.speed() > 0) {
                    animation.addProperty("speed", one.speed());
                }
                if (one.priority() != 0) {
                    animation.addProperty("priority", one.priority());
                }
                if (one.blend() > 0) {
                    animation.addProperty("blend", one.blend());
                }
                if (one.layer() > 0) {
                    animation.addProperty("layer", one.layer());
                }
                if (one.weight() > 0) {
                    animation.addProperty("weight", one.weight());
                }
                if (!one.bones().isEmpty()) {
                    JsonArray bones = new JsonArray();
                    one.bones().forEach(bones::add);
                    animation.add("bones", bones);
                }
            }
            if (!matched) {
                unmatched.add(entry.getKey());
            }
        }
        return unmatched;
    }

    /**
     * Writes per-bone damage multipliers onto a computed rig.
     *
     * <p>Matched against a part's own bone rather than its lineage, unlike an
     * animation's mask: a hitbox is a place you aimed at, and "everything
     * inside the torso counts as a torso hit" would make the head worthless
     * the moment it was inside one.
     *
     * @return the bone names that matched nothing
     */
    public static List<String> applyHitboxes(Rig rig, Map<String, Double> damage) {
        List<String> unmatched = new ArrayList<>();
        if (rig == null || damage == null || damage.isEmpty()) {
            return unmatched;
        }
        for (Map.Entry<String, Double> entry : damage.entrySet()) {
            boolean matched = false;
            for (Part part : rig.parts()) {
                if (part.bone().equalsIgnoreCase(entry.getKey())) {
                    matched = true;
                }
            }
            if (!matched) {
                unmatched.add(entry.getKey());
            }
        }
        rig.damage.clear();
        damage.forEach((bone, multiplier) -> rig.damage.put(bone.toLowerCase(java.util.Locale.ROOT), multiplier));
        return unmatched;
    }

    /**
     * The rigs, in the manifest shape {@link RigStore} merges.
     *
     * <p>Deliberately the same JSON studio sends rather than a second entry
     * point on the store: one shape means one thing to get wrong, and the
     * store's replace-a-whole-pack rule then applies to authored content for
     * free — a reload retires a model somebody deleted from their folder.
     */
    public static JsonObject manifest(String packId, Map<String, Rig> rigs) {
        JsonObject models = new JsonObject();
        for (Map.Entry<String, Rig> entry : rigs.entrySet()) {
            JsonArray parts = new JsonArray();
            for (Part part : entry.getValue().parts()) {
                JsonArray program = new JsonArray();
                for (Step step : part.program()) {
                    JsonObject one = new JsonObject();
                    // A fixed step has no target, and the absence is what says
                    // so at the far end: writing a null would deserialize to
                    // the same thing, but an explicit null in a manifest reads
                    // as a mistake rather than as a kind of step.
                    if (step.target() != null) {
                        one.addProperty("target", step.target());
                    }
                    JsonArray pivot = new JsonArray();
                    for (float value : step.pivot()) {
                        pivot.add(value);
                    }
                    one.add("pivot", pivot);
                    if (step.rotate() != null) {
                        JsonArray rotate = new JsonArray();
                        for (float value : step.rotate()) {
                            rotate.add(value);
                        }
                        one.add("rotate", rotate);
                    }
                    program.add(one);
                }
                JsonObject out = new JsonObject();
                out.addProperty("item", part.item());
                out.add("program", program);
                if (part.anchor != null) {
                    JsonArray anchor = new JsonArray();
                    for (float value : part.anchor) {
                        anchor.add(value);
                    }
                    out.add("anchor", anchor);
                }
                if (!part.bone().isEmpty()) {
                    out.addProperty("bone", part.bone());
                    JsonArray lineage = new JsonArray();
                    part.lineage().forEach(lineage::add);
                    out.add("bones", lineage);
                }
                if (part.behaviour() != BoneBehaviour.NONE) {
                    out.addProperty("behaviour", part.behaviour().name().toLowerCase(java.util.Locale.ROOT));
                    out.addProperty("size", part.size());
                    Double multiplier = entry.getValue().damage
                            .get(part.bone().toLowerCase(java.util.Locale.ROOT));
                    if (multiplier != null && multiplier > 0) {
                        out.addProperty("damage", multiplier);
                    }
                    JsonArray pivot = new JsonArray();
                    for (float value : part.pivot()) {
                        pivot.add(value);
                    }
                    out.add("pivot", pivot);
                }
                parts.add(out);
            }
            JsonObject rig = new JsonObject();
            rig.add("parts", parts);
            rig.add("animations", entry.getValue().animations());
            models.add(entry.getKey(), rig);
        }

        JsonObject manifest = new JsonObject();
        manifest.addProperty("packId", packId);
        manifest.add("models", models);
        return manifest;
    }

    /** The part index in {@code <modelId>__part<n>}, or -1 if that is not one. */
    static int partIndexOf(String item) {
        int marker = item == null ? -1 : item.lastIndexOf(PART_MARKER);
        if (marker < 0) {
            return -1;
        }
        try {
            return Integer.parseInt(item.substring(marker + PART_MARKER.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * The steps that move bone {@code g}: its animated ancestors, root first,
     * then itself.
     *
     * <p>Order is the whole of it. {@code RigAnimator} composes a program in
     * list order with the outermost first, so a torso's rotation has to be
     * applied before the arm's or the arm turns about the wrong point in the
     * wrong space.
     *
     * <p>Still ancestors are left out rather than contributing an identity
     * step: they cost a lookup per part per tick and change nothing. An empty
     * result means nothing in this bone's whole lineage moves.
     *
     * <p>A cycle cannot happen from a Blockbench outliner, which is a tree by
     * construction, but the {@code parent} indices arrive as numbers in a file
     * and a malformed one that pointed upward would spin here forever. The
     * visited set is that guard and nothing more.
     */
    private static List<Step> chainOf(JsonArray groups, int g, Set<String> animated) {
        List<Step> chain = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (int at = g; at >= 0 && at < groups.size() && seen.add(at); ) {
            if (!groups.get(at).isJsonObject()) {
                break;
            }
            JsonObject group = groups.get(at).getAsJsonObject();
            String target = "g:" + at;
            if (animated.contains(target)) {
                // Built child-first and reversed at the end, because walking
                // up is the only direction the parent links allow.
                chain.add(new Step(target, vec3(group, "origin", new float[]{8f, 0f, 8f})));
            }
            JsonElement parent = group.get("parent");
            at = parent != null && parent.isJsonPrimitive() ? asInt(parent, -1) : -1;
        }
        Collections.reverse(chain);
        return chain;
    }

    private static int asInt(JsonElement value, int fallback) {
        try {
            return value.getAsInt();
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Part part(String modelId, int index, List<Integer> elements, List<Step> program,
                             String bone, BoneBehaviour behaviour, float[] pivot, JsonArray source,
                             List<String> lineage) {
        return new Part(modelId + PART_MARKER + index, elements, program, bone, behaviour, pivot,
                measure(source, elements), lineage, anchorOf(program, source, elements));
    }

    /**
     * The smallest coordinate a block model element may have, and the largest.
     * A cube outside this range fails the client's model loader and the whole
     * part draws as the missing-texture box.
     */
    static final float ELEMENT_MIN = -16f;
    static final float ELEMENT_MAX = 32f;

    /**
     * Where a moving part's geometry can be re-centred, or null where it
     * cannot. See {@link Part#anchor()}.
     *
     * <p>The pivot chosen is the INNERMOST step's: that is the part's own
     * bone, which is the rotation that turns fastest - a wheel on a tilting
     * frame spins about its axle far more than the frame tilts. Its ancestors'
     * rotations still move the translation, but slowly.
     *
     * <p>Refused, and the part left where the source put it, when the shift
     * would carry any of its cubes outside the range a block model allows: a
     * frame pivoted at its head tube and reaching a block back from it is the
     * case. Nothing is lost by refusing, because the old drawing is still
     * correct - only the smoothing is forgone.
     */
    private static float[] anchorOf(List<Step> program, JsonArray source, List<Integer> elements) {
        if (program == null || program.isEmpty() || elements.isEmpty()) {
            return null;
        }
        float[] pivot = program.get(program.size() - 1).pivot();
        float[] anchor = {pivot[0] - 8f, pivot[1] - 8f, pivot[2] - 8f};
        if (anchor[0] == 0f && anchor[1] == 0f && anchor[2] == 0f) {
            return null;
        }
        for (int index : elements) {
            if (index < 0 || index >= source.size() || !source.get(index).isJsonObject()) {
                continue;
            }
            JsonObject element = source.get(index).getAsJsonObject();
            for (String key : new String[]{"from", "to"}) {
                float[] corner = vec3(element, key, null);
                if (corner == null) {
                    continue;
                }
                for (int axis = 0; axis < 3; axis++) {
                    float moved = corner[axis] - anchor[axis];
                    if (moved < ELEMENT_MIN || moved > ELEMENT_MAX) {
                        return null;
                    }
                }
            }
        }
        return anchor;
    }

    /** A copy of {@code element} moved by {@code -anchor}: its corners and its own rotation origin. */
    static JsonObject shifted(JsonObject element, float[] anchor) {
        JsonObject out = element.deepCopy();
        for (String key : new String[]{"from", "to"}) {
            float[] corner = vec3(out, key, null);
            if (corner != null) {
                out.add(key, moved(corner, anchor));
            }
        }
        JsonElement rotation = out.get("rotation");
        if (rotation != null && rotation.isJsonObject()) {
            JsonObject spin = rotation.getAsJsonObject().deepCopy();
            float[] origin = vec3(spin, "origin", null);
            if (origin != null) {
                spin.add("origin", moved(origin, anchor));
            }
            out.add("rotation", spin);
        }
        return out;
    }

    private static JsonArray moved(float[] point, float[] by) {
        JsonArray out = new JsonArray();
        for (int axis = 0; axis < 3; axis++) {
            float value = point[axis] - by[axis];
            // Whole numbers stay whole: 3.0 reads fine, but a clean file is
            // easier to diff against the one it came from.
            if (value == Math.rint(value)) {
                out.add((int) value);
            } else {
                out.add(value);
            }
        }
        return out;
    }

    /** The names of bone {@code g} and everything it hangs off, root first. */
    private static List<String> lineageOf(JsonArray groups, int g) {
        List<String> names = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (int at = g; at >= 0 && at < groups.size() && seen.add(at); ) {
            if (!groups.get(at).isJsonObject()) {
                break;
            }
            JsonObject group = groups.get(at).getAsJsonObject();
            String name = nameOf(group);
            if (!name.isEmpty()) {
                names.add(name);
            }
            at = parentOf(group);
        }
        Collections.reverse(names);
        return names;
    }

    /**
     * The widest side of what these elements occupy, in blocks.
     *
     * <p>16 model pixels to the block, and a bone with nothing in it — a
     * container that only holds other bones — measures as half a block so that
     * a seat or a hitbox on it still has somewhere to be.
     */
    private static float measure(JsonArray elements, List<Integer> indices) {
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        float maxZ = -Float.MAX_VALUE;
        for (int index : indices) {
            if (index < 0 || index >= elements.size() || !elements.get(index).isJsonObject()) {
                continue;
            }
            JsonObject element = elements.get(index).getAsJsonObject();
            float[] from = vec3(element, "from", null);
            float[] to = vec3(element, "to", null);
            if (from == null || to == null) {
                continue;
            }
            minX = Math.min(minX, Math.min(from[0], to[0]));
            minY = Math.min(minY, Math.min(from[1], to[1]));
            minZ = Math.min(minZ, Math.min(from[2], to[2]));
            maxX = Math.max(maxX, Math.max(from[0], to[0]));
            maxY = Math.max(maxY, Math.max(from[1], to[1]));
            maxZ = Math.max(maxZ, Math.max(from[2], to[2]));
        }
        if (minX > maxX) {
            return 0.5f;
        }
        float widest = Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
        return Math.max(0.125f, widest / 16f);
    }

    /**
     * What each bone does, with inheritance applied.
     *
     * <p>{@code hi_} means "and everything under it", which is the whole
     * difference between it and {@code h_} — a head with a jaw and two eyes
     * hanging off it should not need the prefix written four times. A child
     * that names a behaviour of its own keeps it: an explicit name beats an
     * inherited one, always.
     */
    private static List<BoneBehaviour> behaviours(JsonArray groups) {
        List<BoneBehaviour> out = new ArrayList<>();
        for (int g = 0; g < groups.size(); g++) {
            JsonObject group = groups.get(g).isJsonObject() ? groups.get(g).getAsJsonObject() : null;
            BoneBehaviour own = BoneBehaviour.of(nameOf(group));
            if (own != BoneBehaviour.NONE) {
                out.add(own);
                continue;
            }
            // A parent is always earlier in the list, so its answer is already
            // in `out` by the time this one is asked.
            int parent = group == null ? -1 : parentOf(group);
            out.add(parent >= 0 && parent < out.size()
                            && out.get(parent) == BoneBehaviour.HEAD_INHERITED
                    ? BoneBehaviour.HEAD_INHERITED
                    : BoneBehaviour.NONE);
        }
        return out;
    }

    private static String nameOf(JsonObject group) {
        JsonElement name = group == null ? null : group.get("name");
        return name != null && name.isJsonPrimitive() ? name.getAsString() : "";
    }

    private static int parentOf(JsonObject group) {
        JsonElement parent = group.get("parent");
        return parent != null && parent.isJsonPrimitive() ? asInt(parent, -1) : -1;
    }

    private static float[] originOf(JsonObject group) {
        return vec3(group, "origin", new float[]{8f, 0f, 8f});
    }

    /**
     * Every animator key that has a keyframe anywhere.
     *
     * <p>An animator with empty channels is what Blockbench leaves behind
     * after somebody deletes the last keyframe off a bone. Treating it as
     * animated would give that bone a part of its own that never moves.
     */
    private static Set<String> animatedTargets(JsonArray animations) {
        Set<String> keys = new HashSet<>();
        for (JsonElement raw : animations) {
            if (!raw.isJsonObject()) {
                continue;
            }
            JsonObject animators = object(raw.getAsJsonObject(), "animators");
            if (animators == null) {
                continue;
            }
            for (Map.Entry<String, JsonElement> animator : animators.entrySet()) {
                if (!animator.getValue().isJsonObject()) {
                    continue;
                }
                for (Map.Entry<String, JsonElement> channel : animator.getValue().getAsJsonObject().entrySet()) {
                    if (channel.getValue().isJsonArray() && !channel.getValue().getAsJsonArray().isEmpty()) {
                        keys.add(animator.getKey());
                        break;
                    }
                }
            }
        }
        return keys;
    }

    /**
     * What a loose cube turns about.
     *
     * <p>Its own rotation origin if it has one, and its centre otherwise —
     * because a cube with no rotation has never been asked where its pivot is,
     * and its centre is the only answer that does not translate it.
     */
    /**
     * A freely rotated cube with its rotation taken off, for the part model.
     *
     * <p>Both keys go: the real angles because no block model can state them,
     * and the legal {@code rotation} stand-in because the display is about to
     * apply the real turn to this geometry — leaving an approximation of the
     * same turn in the file applies it twice. Anything else is returned
     * untouched.
     */
    private static JsonElement untilted(JsonElement element, boolean free) {
        if (!free || !element.isJsonObject()) {
            return element;
        }
        JsonObject copy = element.getAsJsonObject().deepCopy();
        copy.remove("rotation");
        copy.remove("free_rotation");
        return copy;
    }

    /**
     * The cubes carrying a rotation the block model format cannot hold.
     *
     * <p>{@code free_rotation} is not vanilla and not something Blockbench
     * writes — it is an extension both Studio and a hand-authored model may
     * carry, and a client ignores it entirely. What makes it work is the
     * display entity this cube is given; the {@code rotation} beside it is
     * only the nearest legal angle, for anyone reading the pack without the
     * plugin.
     */
    private static Set<Integer> freelyRotated(JsonArray elements) {
        Set<Integer> out = new HashSet<>();
        for (int i = 0; i < elements.size(); i++) {
            if (freeAngles(elements, i) != null) {
                out.add(i);
            }
        }
        return out;
    }

    /** This cube's free angles, or null if it has none worth applying. */
    private static float[] freeAngles(JsonArray elements, int index) {
        if (index < 0 || index >= elements.size() || !elements.get(index).isJsonObject()) {
            return null;
        }
        JsonObject free = object(elements.get(index).getAsJsonObject(), "free_rotation");
        if (free == null) {
            return null;
        }
        float[] angles = vec3(free, "angles", new float[]{0f, 0f, 0f});
        return angles[0] == 0f && angles[1] == 0f && angles[2] == 0f ? null : angles;
    }

    /**
     * What one cube's own part composes: its animator step if it has one, and
     * its fixed rotation innermost if it is tilted past the format.
     */
    private static List<Step> ownProgram(JsonArray elements, int index, List<Step> chain,
                                         Set<String> animated, Set<Integer> freelyRotated) {
        List<Step> own = new ArrayList<>(chain);
        if (animated.contains(String.valueOf(index))) {
            own.add(new Step(String.valueOf(index), pivotOf(elements, index)));
        }
        if (freelyRotated.contains(index)) {
            own.add(Step.fixed(pivotOf(elements, index), freeAngles(elements, index)));
        }
        return own;
    }

    private static float[] pivotOf(JsonArray elements, int index) {
        if (!elements.get(index).isJsonObject()) {
            return new float[]{8f, 8f, 8f};
        }
        JsonObject element = elements.get(index).getAsJsonObject();
        JsonObject free = object(element, "free_rotation");
        if (free != null) {
            return vec3(free, "origin", new float[]{8f, 8f, 8f});
        }
        JsonObject rotation = object(element, "rotation");
        if (rotation != null) {
            return vec3(rotation, "origin", new float[]{8f, 8f, 8f});
        }
        float[] from = vec3(element, "from", new float[]{0f, 0f, 0f});
        float[] to = vec3(element, "to", new float[]{16f, 16f, 16f});
        return new float[]{(from[0] + to[0]) / 2f, (from[1] + to[1]) / 2f, (from[2] + to[2]) / 2f};
    }

    // ---- json, gently --------------------------------------------------

    private static JsonArray array(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }

    private static JsonObject object(JsonObject parent, String key) {
        JsonElement value = parent == null ? null : parent.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static float[] vec3(JsonObject object, String key, float[] fallback) {
        JsonArray value = array(object, key);
        if (value.size() < 3) {
            return fallback;
        }
        try {
            return new float[]{value.get(0).getAsFloat(), value.get(1).getAsFloat(), value.get(2).getAsFloat()};
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Kept so a caller can hand back an ordered map and get ordered parts. */
    static Map<String, Rig> ordered() {
        return new LinkedHashMap<>();
    }
}
