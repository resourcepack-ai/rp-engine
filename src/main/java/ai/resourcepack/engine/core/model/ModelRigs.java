package ai.resourcepack.engine.core.model;

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
 * <p><strong>It is a port of studio's {@code Studio's rig builder}, and staying a
 * port is the point.</strong> Both sides feed one animator, so a rig computed
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

        Step(String target, float[] pivot) {
            this.target = target;
            this.pivot = pivot;
        }

        /** The animator key this step reads: {@code g:<bone>} or an element index. */
        public String target() {
            return target;
        }

        /** The point it rotates and scales about, in model pixels. */
        public float[] pivot() {
            return pivot.clone();
        }
    }

    /** One display entity's worth of a model. */
    public static final class Part {

        private final String item;
        private final List<Integer> elements;
        private final List<Step> program;

        Part(String item, List<Integer> elements, List<Step> program) {
            this.item = item;
            this.elements = List.copyOf(elements);
            this.program = List.copyOf(program);
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
    }

    /** A model's parts and the animations they play. */
    public static final class Rig {

        private final List<Part> parts;
        private final JsonArray animations;

        Rig(List<Part> parts, JsonArray animations) {
            this.parts = List.copyOf(parts);
            this.animations = animations;
        }

        public List<Part> parts() {
            return parts;
        }

        /** The animations, in the shape {@link RigStore} reads. */
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
        if (animated.isEmpty()) {
            return Optional.empty();
        }

        List<Part> parts = new ArrayList<>();
        Set<Integer> claimed = new HashSet<>();

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
                if (animated.contains(String.valueOf(index))) {
                    List<Step> own = new ArrayList<>(chain);
                    own.add(new Step(String.valueOf(index), pivotOf(elements, index)));
                    parts.add(part(modelId, parts.size(), List.of(index), own));
                } else {
                    boneCubes.add(index);
                }
            }
            if (!boneCubes.isEmpty()) {
                parts.add(part(modelId, parts.size(), boneCubes, chain));
            }
        }

        // Cubes animated on their own, including ones sitting in a bone that
        // is not itself animated.
        for (int i = 0; i < elements.size(); i++) {
            if (claimed.contains(i) || !animated.contains(String.valueOf(i))) {
                continue;
            }
            claimed.add(i);
            parts.add(part(modelId, parts.size(), List.of(i),
                    List.of(new Step(String.valueOf(i), pivotOf(elements, i)))));
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
            parts.add(part(modelId, parts.size(), remainder, List.of()));
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
                mine.add(elements.get(index));
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
                    one.addProperty("target", step.target());
                    JsonArray pivot = new JsonArray();
                    for (float value : step.pivot()) {
                        pivot.add(value);
                    }
                    one.add("pivot", pivot);
                    program.add(one);
                }
                JsonObject out = new JsonObject();
                out.addProperty("item", part.item());
                out.add("program", program);
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

    private static Part part(String modelId, int index, List<Integer> elements, List<Step> program) {
        return new Part(modelId + PART_MARKER + index, elements, program);
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
    private static float[] pivotOf(JsonArray elements, int index) {
        if (!elements.get(index).isJsonObject()) {
            return new float[]{8f, 8f, 8f};
        }
        JsonObject element = elements.get(index).getAsJsonObject();
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
