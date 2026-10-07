package ai.resourcepack.engine.core.emote;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Bakes emote rigs into pack files, the way a Studio push does — but here,
 * from skins this server has seen, so a server with no Studio in the picture
 * still has bodies to put its emotes on.
 *
 * <p><strong>Why this exists.</strong> An emote renders the PLAYER, and a
 * resource pack cannot reach a live skin. So every rig is a copy of a skin
 * sheet baked into the pack as a texture, plus a set of item models — one per
 * bone, per skeleton, per arm width — that sample it. Until this class, only
 * Studio baked those, for the players a push was addressed to, and a pack
 * that arrived any other way had no rigs at all: a seat could dress nobody,
 * and a hand-authored emote had nothing to play on. That made emotes the one
 * kind of content the folder format could not carry, which broke the rule
 * that everything the engine does is expressible there.
 *
 * <p><strong>Everything lands in the {@code rpengine} namespace</strong> of
 * the pack, which is reserved for exactly this: {@code
 * assets/rpengine/textures/block/<prefix>.png}, a model per bone under {@code
 * assets/rpengine/models/block/}, and an item definition per bone under
 * {@code assets/rpengine/items/}, shared by every skin and choosing between
 * them by the item's {@code custom_model_data} string, so the bone can be worn
 * by a paper item with an {@code item_model} component. That last is the difference from Studio's
 * rigs, which dispatch through a {@code custom_model_data} string on vanilla
 * paper's own model file. Two packs cannot both own that file, and a pushed
 * pack already does, so a native rig reaches its models a way that needs no
 * shared file — {@link NativeRigItems} is the other half.
 *
 * <p>The names are Studio's, deliberately: {@code <prefix>__jointed__slim__rightforearm}
 * is what {@link EmoteStore#boneItemId} composes for every rig it holds, so a
 * baked rig from here and one from a push are indistinguishable to the
 * director. Only the prefix differs — {@link #PREFIX} rather than Studio's —
 * and that is what {@link NativeRigItems#isNative} keys on.
 *
 * <p>Pure: bytes in, files out. Which skins to bake is {@link SkinCache}'s
 * business; where they go is {@link RigAssets}'.
 */
final class RigBaker {

    /** What every native rig's item names start with. Studio's start {@code rpai_emote_}. */
    static final String PREFIX = "rpengine_rig_";

    /** The namespace the files land in. Reserved by the registry, so no pack can be called this. */
    static final String NAMESPACE = "rpengine";

    /** The key the rig everybody without one of their own falls back to is held under. */
    static final String DEFAULT_KEY = "00000000000000000000000000000000";

    /** What the shared per-bone item definitions are called, ahead of the bone's suffix: {@code rig__head}. */
    static final String DEFINITION = "rig";

    private static final Gson GSON = new GsonBuilder().create();

    private RigBaker() {
    }

    /** A skin to bake: whose, the sheet, and which arm width it was drawn for. */
    static final class Skin {
        /** The player's UUID as 32 lowercase hex digits, or {@link #DEFAULT_KEY}. */
        final String key;
        final byte[] png;
        final String variant;
        /** Their cape sheet, 64 by 32, or null for a player without one. */
        final byte[] cape;

        Skin(String key, byte[] png, String variant) {
            this(key, png, variant, null);
        }

        Skin(String key, byte[] png, String variant, byte[] cape) {
            this.key = key;
            this.png = png;
            this.variant = variant;
            this.cape = cape;
        }
    }

    /** One player's share of a bake: their sheet, their models, and the rig the store needs. */
    static final class Part {
        final String key;
        /** The textures and models, but no item definitions: those are shared, see {@link #definitions}. */
        final Map<String, byte[]> files = new LinkedHashMap<>();
        /** Which bones this skin has a model for, as the suffix after its prefix ({@code __jointed__slim__rightforearm}). */
        final Set<String> suffixes = new LinkedHashSet<>();
        final EmoteStore.PlayerRig rig = new EmoteStore.PlayerRig();

        Part(String key) {
            this.key = key;
        }
    }

    /** What one bake produced: the files, and the rig table the store needs. */
    static final class Baked {
        final Map<String, byte[]> files = new LinkedHashMap<>();
        final Map<String, EmoteStore.PlayerRig> players = new LinkedHashMap<>();
        final Map<String, Part> parts = new LinkedHashMap<>();
    }

    /** The texture and model prefix for a player key. The whole key, never truncated: a rig mid-emote holds this name. */
    static String prefixFor(String key) {
        return PREFIX + key.toLowerCase(Locale.ROOT);
    }

    /**
     * Bakes every skin given.
     *
     * <p>Per skin: the sheet as a texture; the six whole-limb bones at the
     * skin's own width; both arm pairs of the whole-limb skeleton, qualified,
     * for the director to choose between off the live profile; and the
     * jointed skeleton, its arms at both widths and everything else once.
     * That is Studio's bake exactly, minus the cape. Then one shared item
     * definition per bone for all of them - see {@link #definitions}.
     */
    static Baked bake(List<Skin> skins) {
        Baked out = new Baked();
        for (Skin skin : skins) {
            Part part = part(skin);
            if (part == null || out.parts.containsKey(part.key)) {
                continue;
            }
            out.parts.put(part.key, part);
            out.players.put(part.key, part.rig);
            out.files.putAll(part.files);
        }
        out.files.putAll(definitions(out.parts.values()));
        return out;
    }

    /**
     * The pack that lets ONE player see their own rig before the server's
     * pack has it, stacked on top of the build {@code current} went into.
     *
     * <p>Their sheet and models, plus every shared definition again with
     * their case added. The definitions have to come whole, because a pack
     * higher in the stack replaces a file rather than merging it - so each
     * one also names every skin in {@code current}, all of whose models the
     * pack underneath already carries. Built against the build the player is
     * sent alongside it, never an older one, or a case would name a model
     * nobody shipped.
     */
    static Map<String, byte[]> selfPack(Baked current, Part self) {
        Map<String, byte[]> files = new LinkedHashMap<>(self.files);
        List<Part> parts = new ArrayList<>();
        for (Part part : current.parts.values()) {
            if (!part.key.equals(self.key)) {
                parts.add(part);
            }
        }
        parts.add(self);
        files.putAll(definitions(parts));
        return files;
    }

    /** One skin's textures, models and rig, or null for a skin with nothing to bake. */
    static Part part(Skin skin) {
        if (skin == null || skin.key == null || skin.png == null || skin.png.length == 0) {
            return null;
        }
        String key = skin.key.toLowerCase(Locale.ROOT);
        Part out = new Part(key);
        String prefix = prefixFor(key);
        String variant = RigGeometry.SLIM.equals(skin.variant) ? RigGeometry.SLIM : RigGeometry.WIDE;
        out.files.put("assets/" + NAMESPACE + "/textures/block/" + prefix + ".png", skin.png);
        String textureRef = NAMESPACE + ":block/" + prefix;

        // The six unqualified whole-limb bones, at the skin's own width.
        for (RigGeometry.Bone bone : RigGeometry.bones(variant, false)) {
            bakeBone(out, RigGeometry.itemName(prefix, bone.key, null, false), textureRef,
                    RigGeometry.elements(bone, variant));
        }
        // Both whole-limb arm pairs, qualified.
        for (String width : List.of(RigGeometry.WIDE, RigGeometry.SLIM)) {
            for (RigGeometry.Bone bone : RigGeometry.bones(width, false)) {
                if (!RigGeometry.VARIANT_BONES.contains(bone.key)) {
                    continue;
                }
                bakeBone(out, RigGeometry.itemName(prefix, bone.key, width, false), textureRef,
                        RigGeometry.elements(bone, width));
            }
        }
        // The jointed skeleton: arms at both widths, everything else once.
        boolean first = true;
        for (String width : List.of(RigGeometry.WIDE, RigGeometry.SLIM)) {
            for (RigGeometry.Bone bone : RigGeometry.bones(width, true)) {
                boolean arm = RigGeometry.VARIANT_BONES.contains(bone.key);
                if (!arm && !first) {
                    continue;
                }
                bakeBone(out, RigGeometry.itemName(prefix, bone.key, arm ? width : null, true), textureRef,
                        RigGeometry.elements(bone, width));
            }
            first = false;
        }

        // The cape, when they have one: two models for one piece of
        // geometry, because the director qualifies a jointed rig's bone
        // names with __jointed__ and baking both costs a hundred bytes
        // where teaching it an exception costs a branch. Studio does the
        // same.
        boolean cape = skin.cape != null && skin.cape.length > 0;
        if (cape) {
            out.files.put("assets/" + NAMESPACE + "/textures/block/" + prefix + "_cape.png", skin.cape);
            String capeRef = NAMESPACE + ":block/" + prefix + "_cape";
            for (boolean jointed : new boolean[] {false, true}) {
                String modelId = RigGeometry.itemName(prefix, RigGeometry.CAPE_BONE, null, jointed);
                out.files.put("assets/" + NAMESPACE + "/models/block/" + modelId + ".json",
                        GSON.toJson(RigGeometry.capeModel(capeRef)).getBytes(StandardCharsets.UTF_8));
                out.suffixes.add(suffixOf(modelId));
            }
        }

        out.rig.item = prefix;
        out.rig.variant = variant;
        out.rig.arms = new ArrayList<>(List.of(RigGeometry.WIDE, RigGeometry.SLIM));
        out.rig.jointed = true;
        out.rig.cape = cape;
        return out;
    }

    private static void bakeBone(Part out, String modelId, String textureRef, JsonArray elements) {
        out.files.put("assets/" + NAMESPACE + "/models/block/" + modelId + ".json",
                GSON.toJson(RigGeometry.boneModel(textureRef, elements)).getBytes(StandardCharsets.UTF_8));
        out.suffixes.add(suffixOf(modelId));
    }

    /** The player key inside a native bone's model id: the 32 hex digits after {@link #PREFIX}. */
    static String keyOf(String modelId) {
        return modelId.substring(PREFIX.length(), PREFIX.length() + DEFAULT_KEY.length());
    }

    /** Everything in a native bone's model id after the player's prefix, starting {@code __}. */
    static String suffixOf(String modelId) {
        return modelId.substring(PREFIX.length() + DEFAULT_KEY.length());
    }

    /** The shared item definition a native bone is worn through, by name in {@link #NAMESPACE}. */
    static String definitionFor(String modelId) {
        return DEFINITION + suffixOf(modelId);
    }

    /**
     * One item definition per bone, shared by every skin: a select on the
     * bone item's {@code custom_model_data} string, which is the player's
     * key, with Steve as the fallback.
     *
     * <p><strong>The fallback is the point.</strong> A rig's displays are
     * seen by everybody nearby, and not everybody nearby holds the same
     * build: a skin baked after somebody's pack loaded is a model their pack
     * does not have. Named directly, as it was until this, that is the
     * missing-model cube on a newcomer for everybody already online. Through
     * a select, a key a pack has no case for is simply Steve - and a bone
     * Steve does not have (a cape) is nothing at all.
     *
     * <p>Cases in key order, so the same skins make the same bytes and an
     * unchanged server rebuilds to an unchanged hash.
     */
    static Map<String, byte[]> definitions(Collection<Part> parts) {
        Map<String, TreeMap<String, String>> bySuffix = new TreeMap<>();
        Part fallback = null;
        for (Part part : parts) {
            if (DEFAULT_KEY.equals(part.key)) {
                fallback = part;
            }
            String prefix = prefixFor(part.key);
            for (String suffix : part.suffixes) {
                bySuffix.computeIfAbsent(suffix, ignored -> new TreeMap<>())
                        .put(part.key, NAMESPACE + ":block/" + prefix + suffix);
            }
        }
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (Map.Entry<String, TreeMap<String, String>> entry : bySuffix.entrySet()) {
            String suffix = entry.getKey();
            JsonObject select = new JsonObject();
            select.addProperty("type", "minecraft:select");
            select.addProperty("property", "minecraft:custom_model_data");
            select.addProperty("index", 0);
            JsonArray cases = new JsonArray();
            for (Map.Entry<String, String> one : entry.getValue().entrySet()) {
                JsonObject when = new JsonObject();
                when.addProperty("when", one.getKey());
                when.add("model", model(one.getValue()));
                cases.add(when);
            }
            select.add("cases", cases);
            if (fallback != null && fallback.suffixes.contains(suffix)) {
                select.add("fallback", model(NAMESPACE + ":block/" + prefixFor(DEFAULT_KEY) + suffix));
            } else {
                JsonObject empty = new JsonObject();
                empty.addProperty("type", "minecraft:empty");
                select.add("fallback", empty);
            }
            JsonObject definition = new JsonObject();
            definition.add("model", select);
            out.put("assets/" + NAMESPACE + "/items/" + DEFINITION + suffix + ".json",
                    GSON.toJson(definition).getBytes(StandardCharsets.UTF_8));
        }
        return out;
    }

    private static JsonObject model(String ref) {
        JsonObject model = new JsonObject();
        model.addProperty("type", "minecraft:model");
        model.addProperty("model", ref);
        return model;
    }
}
