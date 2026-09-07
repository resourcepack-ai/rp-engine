package ai.resourcepack.engine.core.emote;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
 * {@code assets/rpengine/items/} so the bone can be worn by a paper item with
 * an {@code item_model} component. That last is the difference from Studio's
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

    /** What one bake produced: the files, and the rig table the store needs. */
    static final class Baked {
        final Map<String, byte[]> files = new LinkedHashMap<>();
        final Map<String, EmoteStore.PlayerRig> players = new LinkedHashMap<>();
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
     * That is Studio's bake exactly, minus the cape.
     */
    static Baked bake(List<Skin> skins) {
        Baked out = new Baked();
        for (Skin skin : skins) {
            if (skin == null || skin.key == null || skin.png == null || skin.png.length == 0) {
                continue;
            }
            String key = skin.key.toLowerCase(Locale.ROOT);
            if (out.players.containsKey(key)) {
                continue;
            }
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
                    definition(out, modelId);
                }
            }

            EmoteStore.PlayerRig rig = new EmoteStore.PlayerRig();
            rig.item = prefix;
            rig.variant = variant;
            rig.arms = new ArrayList<>(List.of(RigGeometry.WIDE, RigGeometry.SLIM));
            rig.jointed = true;
            rig.cape = cape;
            out.players.put(key, rig);
        }
        return out;
    }

    private static void bakeBone(Baked out, String modelId, String textureRef, com.google.gson.JsonArray elements) {
        out.files.put("assets/" + NAMESPACE + "/models/block/" + modelId + ".json",
                GSON.toJson(RigGeometry.boneModel(textureRef, elements)).getBytes(StandardCharsets.UTF_8));
        definition(out, modelId);
    }

    /** The item definition that lets a paper item wear one of these models by name. */
    private static void definition(Baked out, String modelId) {
        JsonObject definition = new JsonObject();
        JsonObject model = new JsonObject();
        model.addProperty("type", "minecraft:model");
        model.addProperty("model", NAMESPACE + ":block/" + modelId);
        definition.add("model", model);
        out.files.put("assets/" + NAMESPACE + "/items/" + modelId + ".json",
                GSON.toJson(definition).getBytes(StandardCharsets.UTF_8));
    }
}
