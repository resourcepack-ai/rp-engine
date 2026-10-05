package ai.resourcepack.engine.core.content;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.Diagnostic;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * BetterModel's player animations, as RP Engine emotes.
 *
 * <p>BetterModel keeps them in {@code players/}: a {@code .bbmodel} of a
 * player (its own {@code steve.bbmodel}, or one somebody made from it) whose
 * bones carry tags - {@code ph} the head, {@code pra}/{@code prfa} the right
 * arm and forearm, {@code pll}/{@code plfl} the left leg and shin, and so on -
 * and whose animations are what {@code /bm play} plays on a player wearing
 * their own skin. That is exactly an RP Engine emote, so each animation becomes
 * one, played with {@code /emote}.
 *
 * <p><strong>The numbers carry over unchanged</strong>, and that is checked
 * rather than hoped: BetterModel's rig faces -z with the right hand at +x, which
 * is the emote rig's own frame, and an emote's keyframes are read by the same
 * code that plays a Blockbench model's (see {@code RigMath.applyStep}), so a
 * rotation means the same thing in both.
 *
 * <p>Two things differ and are said in a warning rather than hidden. Their
 * torso is three bones (hip, waist, chest) and ours is one, so the most
 * specific one that moves is the body here; and their arms and head ride the
 * torso while ours stand on their own, so a bow from the waist bends the body
 * here without carrying the arms down with it. Molang in a keyframe has nothing
 * to be evaluated by, so it is read as zero, as for any Blockbench model.
 */
final class BetterModelPlayers {

    private BetterModelPlayers() {
    }

    /** Their bone tags, by the emote bone each is. */
    private static final Map<String, String> BONES = Map.ofEntries(
            Map.entry("ph", "head"),
            Map.entry("pra", "rightArm"), Map.entry("prfa", "rightForearm"),
            Map.entry("pla", "leftArm"), Map.entry("plfa", "leftForearm"),
            Map.entry("prl", "rightLeg"), Map.entry("prfl", "rightShin"),
            Map.entry("pll", "leftLeg"), Map.entry("plfl", "leftShin"),
            Map.entry("pc", "body"), Map.entry("pw", "body"), Map.entry("phip", "body"));

    /** Which of the three torso bones wins the body, most specific first. */
    private static final List<String> TORSO = List.of("pc", "pw", "phip");

    private static final Map<String, String> INTERPOLATIONS = Map.of(
            "catmullrom", "smooth", "smooth", "smooth", "step", "step", "bezier", "smooth");

    /**
     * Every animation in a player {@code .bbmodel}, as emote definitions keyed
     * by emote name.
     *
     * @param file the file's name without {@code .bbmodel}; BetterModel's own
     *             {@code steve} names its emotes by the animation alone, any
     *             other file prefixes them with its name, since emote names
     *             are shared by the whole server
     */
    static Map<String, Map<String, Object>> emotes(byte[] source, String file, String origin,
                                                   List<Diagnostic> diagnostics) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        JsonObject project;
        try {
            JsonElement parsed = JsonParser.parseString(new String(source, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                diagnostics.add(Diagnostic.warning(origin, file, "is not a Blockbench project, so it was skipped."));
                return out;
            }
            project = parsed.getAsJsonObject();
        } catch (JsonParseException e) {
            diagnostics.add(Diagnostic.warning(origin, file, "is not a Blockbench project, so it was skipped."));
            return out;
        }
        JsonArray animations = project.has("animations") && project.get("animations").isJsonArray()
                ? project.getAsJsonArray("animations") : new JsonArray();
        boolean expressions = false;
        boolean torsoMerged = false;
        Set<String> ignored = new LinkedHashSet<>();
        for (JsonElement raw : animations) {
            if (!raw.isJsonObject()) continue;
            JsonObject animation = raw.getAsJsonObject();
            String name = text(animation, "name");
            if (name == null || !animation.has("length")) continue;
            String emoteName = (file.equals("steve") ? name : file + "_" + name).toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9_.-]", "_");
            if (!ContentId.isValidPath(emoteName)) continue;

            Map<String, Map<String, Object>> byBone = new LinkedHashMap<>();
            Map<String, Object> root = null;
            Map<String, Map<String, Object>> torso = new LinkedHashMap<>();
            JsonObject animators = animation.has("animators") && animation.get("animators").isJsonObject()
                    ? animation.getAsJsonObject("animators") : new JsonObject();
            for (Map.Entry<String, JsonElement> entry : animators.entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject animator = entry.getValue().getAsJsonObject();
                String boneName = text(animator, "name");
                if (boneName == null) continue;
                boolean[] molang = new boolean[1];
                Map<String, Object> channels = channels(animator, molang);
                expressions |= molang[0];
                if (channels.isEmpty()) continue;
                if (boneName.equals("player_root")) {
                    root = channels;
                    continue;
                }
                String tag = tag(boneName);
                String bone = tag == null ? null : BONES.get(tag);
                if (bone == null) {
                    ignored.add(boneName);
                    continue;
                }
                if (bone.equals("body")) {
                    torso.put(tag, channels);
                    continue;
                }
                byBone.put(bone, channels);
            }
            for (String tag : TORSO) {
                if (torso.containsKey(tag)) {
                    byBone.put("body", torso.get(tag));
                    break;
                }
            }
            torsoMerged |= torso.size() > 1;
            if (byBone.isEmpty() && root == null) continue;

            Map<String, Object> emote = new LinkedHashMap<>();
            emote.put("name", name);
            emote.put("length", animation.get("length").getAsDouble());
            emote.put("loop", "loop".equals(text(animation, "loop")) || isTrue(animation.get("loop")));
            emote.put("animators", byBone);
            if (root != null) emote.put("root", root);
            out.put(emoteName, emote);
        }
        if (expressions) {
            diagnostics.add(Diagnostic.warning(origin, file,
                    "has Molang in its keyframes, which nothing here evaluates, so those values are zero."));
        }
        if (torsoMerged) {
            diagnostics.add(Diagnostic.warning(origin, file,
                    "moves more than one of its hip, waist and chest; the emote rig has one body bone, so the most "
                            + "specific that moves is the body, and the arms and head do not ride it as they do in "
                            + "BetterModel."));
        }
        ignored.removeIf(name -> name.equals("shadow") || name.startsWith("tag_"));
        if (!ignored.isEmpty()) {
            diagnostics.add(Diagnostic.warning(origin, file,
                    "the bones " + String.join(", ", ignored) + " are not ones a player emote has (held items and "
                            + "the cape are the game's), so their keyframes were skipped."));
        }
        return out;
    }

    /** The player tag a bone's name carries: {@code pra_right_arm}, {@code h_ph_head}. */
    private static String tag(String name) {
        for (String token : name.toLowerCase(Locale.ROOT).split("_")) {
            if (BONES.containsKey(token)) return token;
        }
        return null;
    }

    /** An animator's keyframes, by channel, as an emote writes them. */
    private static Map<String, Object> channels(JsonObject animator, boolean[] molang) {
        Map<String, List<Map<String, Object>>> byChannel = new LinkedHashMap<>();
        JsonArray keyframes = animator.has("keyframes") && animator.get("keyframes").isJsonArray()
                ? animator.getAsJsonArray("keyframes") : new JsonArray();
        for (JsonElement raw : keyframes) {
            if (!raw.isJsonObject()) continue;
            JsonObject keyframe = raw.getAsJsonObject();
            String channel = text(keyframe, "channel");
            if (channel == null || !Set.of("rotation", "position", "scale").contains(channel)
                    || !keyframe.has("time")) {
                continue;
            }
            JsonObject point = keyframe.has("data_points") && keyframe.getAsJsonArray("data_points").size() > 0
                    && keyframe.getAsJsonArray("data_points").get(0).isJsonObject()
                    ? keyframe.getAsJsonArray("data_points").get(0).getAsJsonObject() : new JsonObject();
            List<Double> value = new ArrayList<>();
            for (String axis : List.of("x", "y", "z")) {
                value.add(number(point.get(axis), channel.equals("scale") ? 1 : 0, molang));
            }
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("time", keyframe.get("time").getAsDouble());
            frame.put("value", value);
            String interpolation = INTERPOLATIONS.get(String.valueOf(text(keyframe, "interpolation")));
            if (interpolation != null) frame.put("interpolation", interpolation);
            byChannel.computeIfAbsent(channel, key -> new ArrayList<>()).add(frame);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : byChannel.entrySet()) {
            entry.getValue().sort((a, b) -> Double.compare((Double) a.get("time"), (Double) b.get("time")));
            out.put(entry.getKey(), entry.getValue());
        }
        return out;
    }

    private static double number(JsonElement value, double fallback, boolean[] molang) {
        if (value == null || !value.isJsonPrimitive()) return fallback;
        String text = value.getAsString().trim();
        if (text.isEmpty()) return fallback;
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            molang[0] = true;
            return 0;
        }
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || !value.isJsonPrimitive() ? null : value.getAsString();
    }

    private static boolean isTrue(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
    }
}
