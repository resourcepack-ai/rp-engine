package ai.resourcepack.engine.core.emote;

import ai.resourcepack.engine.api.EmoteTrigger;
import ai.resourcepack.engine.api.Keyframe;
import ai.resourcepack.engine.api.MergeResult;

import com.google.gson.Gson;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * In-memory store of player emotes, keyed by emote id, plus the baked rig each
 * recipient of the last push has. The panel ships a manifest alongside a pack
 * push exactly as it does for animation rigs; entries merge in per pack and
 * persist to plugins/&lt;plugin&gt;/emotes.json.
 *
 * JSON shape is produced by Studio's emote manifest writer — keep the two in
 * sync. It reuses {@link Keyframe} deliberately: an emote's keyframes
 * are the same keyframes a rig has, and one type here means one sampler
 * ({@link ai.resourcepack.engine.core.animation.Sampler#sample}) rather than a second copy of the interpolation
 * rules to keep in step with the editor's.
 *
 * <p><b>The players map is why an emote is a sync feature rather than a
 * distribution one.</b> A placed model renders art the pack already holds, so
 * naming it is enough; an emote renders the player, and a resource pack cannot
 * reach a live skin. So a pushed pack carries a baked copy of each recipient's
 * sheet and this map says which item belongs to whom. A player absent from it
 * has no rig in the pack they are wearing and cannot emote — a real state with
 * a real cause (they joined the party after the push), which the command
 * reports rather than papering over with a Steve.
 */
public final class EmoteStore {

    static final class Bone {
        String key;
        /** Block-model space; studio converts, so nothing here has to. */
        float[] pivot;
        /**
         * The bone this one is posed inside, on the jointed table only. A
         * forearm names its upper arm; {@code EmoteDirector.pose} composes the
         * parent's matrix before the child's, and the table lists a parent
         * before its children so that is a single forward pass. Null on every
         * whole-limb bone and on every manifest from before joints existed.
         */
        String parent;
    }

    static final class Emote {
        String name;
        double length;
        boolean loop;
        /** bone key -> channel ("rotation"/"position"/"scale") -> keyframes. */
        Map<String, Map<String, List<Keyframe>>> animators;
        /** Models this emote carries. Null and empty mean the same thing. */
        List<Prop> props;
        /**
         * The whole-body transform, applied to every bone before its own.
         *
         * Null on every emote authored before it existed, and null means the
         * identity — this skeleton is flat, so without it nothing can say
         * "the character moves" and a flip could only twist the torso.
         */
        Map<String, List<Keyframe>> root;
        /** Whether the player should travel the root's path. Off by default. */
        boolean rootMotion;
        /**
         * The movement states this is WORN for, or null for an ordinary emote.
         *
         * <p>Null and empty mean the same thing and that thing is every emote
         * authored before stances existed: plays where you stand, ends the
         * moment you move. Non-empty makes it a stance — see
         * {@link EmoteTrigger}.
         *
         * <p>Strings rather than the enum because this is what gson fills in
         * straight off the manifest, and a name a newer studio invented must
         * not fail the parse of the whole pack. {@link #triggersOf} is the one
         * place they are resolved.
         */
        List<String> triggers;
        /**
         * Everybody in this emote other than the person who ran the command.
         *
         * Null on every emote authored before it existed, and null and empty
         * mean the same thing: a solo emote. An emote WITH entries cannot be
         * run alone — {@code /emote <name>} needs a player named per slot, and
         * the command refuses rather than dancing a duet with a gap in it.
         */
        List<Performer> performers;
    }

    /**
     * A movement SET: one emote per state, worn as a single thing.
     *
     * <p><b>It exists because a rig has one clock.</b> A stance already plays
     * while its wearer walks around, but only one can be on and only one
     * timeline can run — so a walk cycle, a sprint and a sneak could be three
     * stances and never one character. A group is that character: it resolves
     * the same {@link EmoteTrigger} a stance does and swaps which emote is
     * driving the rig as the player moves.
     *
     * <p><b>A state with no entry is the player's own body.</b> The rig is put
     * away, the player is shown as themselves, and vanilla animates them —
     * which is the whole of "keep the default for jumping", and why this map is
     * allowed to be partial.
     *
     * <p>Null and empty {@code parts} mean a group that wears nothing anywhere.
     * Studio does not ship one (the manifest builder drops it), and this jar
     * refuses to start one rather than putting somebody in an emote in which
     * nothing ever happens.
     */
    static final class Group {
        /** What {@code /emote <this>} is called. Shares the emote name space. */
        String name;
        /**
         * Trigger wire name -> the id of an emote in the same manifest.
         *
         * <p>Ids rather than inline animations: a member is an ordinary emote
         * that can also be played on its own and worn by a second group, and a
         * copy of its keyframes per group is how those copies come to disagree.
         */
        Map<String, String> parts;
    }

    /**
     * One of the other people in an emote.
     *
     * <p>The person who ran the command is NOT one of these — their part is the
     * emote's own {@link Emote#animators} and {@link Emote#root}. So an older
     * jar reading a duet plays the lead's half and ignores the rest, which is a
     * feature that isn't there yet rather than an emote that does nothing.
     *
     * <p>Placement is in the LEAD's frame: the offset is rotated by the lead's
     * yaw before it becomes a world position, so a partner standing "in front"
     * stands in front of them whichever way they happen to be facing. Same
     * convention root motion's displacement uses, and for the same reason.
     */
    static final class Performer {
        String id;
        /** What this slot is called, for the message naming who fills it. */
        String name;
        /** Rest placement relative to the lead, block-model px. */
        float[] offset;
        /** Facing relative to the lead, degrees. 180 is face to face. */
        float yaw;
        /** bone key -> channel -> keyframes, exactly like the lead's. */
        Map<String, Map<String, List<Keyframe>>> animators;
        /** Their whole-body transform, on the same terms as the lead's root. */
        Map<String, List<Keyframe>> root;
    }

    /**
     * A pack model an emote carries.
     *
     * `modelId` is the model's id, which is also the custom_model_data STRING
     * the pack dispatches it under — so showing one is the same act as showing
     * a rig bone, and the model is already in the pack because a model is
     * ordinary pack content. Nothing is downloaded for a prop.
     */
    static final class Prop {
        String id;
        String modelId;
        /** A bone key, "root" for the whole body, or "none" to stand still. */
        String attach;
        /**
         * Whose bone it hangs from — a performer id, or null for the lead.
         *
         * Null on every emote authored before a cast existed, and null means
         * the lead, so nothing had to be migrated. {@code attach} names the
         * JOINT and this names the person it belongs to; without the second
         * half "attach to the right arm" could only ever mean the lead's.
         */
        String performer;
        /** Rest offset from whatever it rides, block-model px. */
        float[] offset;
        float scale;
        /** Its own motion, on top of what it is attached to. */
        Map<String, List<Keyframe>> animator;
    }

    static final class PlayerRig {
        /** Item-model prefix; the per-bone item is prefix + "__" + bone key. */
        String item;
        /**
         * The arm width studio GUESSED from the sheet's pixels, which is what
         * the unqualified arm models were baked at. A fallback: the player's
         * own profile is asked first (SkinModel), and this is what answers
         * when it can't.
         */
        String variant;
        /**
         * The arm widths this rig carries a qualified arm pair for, as
         * prefix + "__" + variant + "__" + bone. Null on a manifest from
         * before studio baked both, which sends everybody to the unqualified
         * arms — a model that exists — rather than to one that doesn't.
         */
        List<String> arms;
        /**
         * Whether this rig also carries the jointed skeleton — elbows and
         * knees — under {@code __jointed__} item names. False on a manifest
         * from before joints existed, which keeps this jar on the whole-limb
         * models it has always spawned.
         */
        boolean jointed;
        /**
         * Whether this rig carries a cape model, under the manifest's
         * {@code capeBone} key.
         *
         * <p>False on a player with no cape and on every manifest from before
         * capes existed. It is checked before the cape bone is ever spawned,
         * because a model the pack does not contain renders as the
         * purple-and-black missing-model cube — a chequered slab hanging off
         * the back of everybody who has no cape.
         */
        boolean cape;
    }

    /**
     * The bones the arm width changes, lowercased as they appear in a model
     * id. Mirrors the variant-bone set in Studio's emote skeleton: these two
     * are the only bones the pack bakes under a qualified name, so qualifying
     * any other would name a model that isn't there.
     */
    private static final Set<String> VARIANT_BONES =
        Set.of("rightarm", "leftarm", "rightforearm", "leftforearm");

    /**
     * The key the cape's bone carries. Mirrors the cape-bone key in Studio's
     * emote skeleton.
     *
     * <p>Named here rather than matched as a literal at the one place that
     * needs it, because two of them are two chances for the physics in
     * {@link CapeSway} to be applied to a bone that isn't a cape, or to no
     * bone at all — and either is invisible until somebody with a cape emotes.
     */
    static final String CAPE_BONE = "cape";

    /** Bone keys, lowercased, whose end a held prop actually rides — the hand
     *  is on the forearm, the foot on the shin. Mirrors the attach-bone map
     *  in Studio's emote skeleton. Only meaningful on the jointed skeleton; on the
     *  whole-limb one the end bone doesn't exist and the caller falls back. */
    private static final Map<String, String> ATTACH_END = Map.of(
        "rightarm", "rightForearm",
        "leftarm", "leftForearm",
        "rightleg", "rightShin",
        "leftleg", "leftShin");

    /** The bone a prop attached to {@code attach} rides on the jointed table:
     *  the end of the limb chain, else the bone itself. */
    static String attachEndBone(String attach) {
        if (attach == null) return null;
        return ATTACH_END.getOrDefault(attach.toLowerCase(Locale.ROOT), attach);
    }

    /**
     * The custom_model_data of one bone of this rig, for a player wearing
     * {@code variant}.
     *
     * Lowercased to match the item names Studio's emote manifest writes. A
     * resource location path may only contain [a-z0-9_.-/], so a camelCase
     * bone key names a model the client cannot load and the limb renders as
     * the missing-model cube. Keep the two in step.
     *
     * The qualified name is used only when all three hold: the bone is one
     * the variant changes, the player's width is known, and the manifest says
     * that width's pair was baked. Otherwise the unqualified model — which
     * every rig has, at studio's guessed width — so an older pack, an
     * unresolved profile and a head all take the path that cannot miss.
     */
    static String boneItemId(PlayerRig rig, String boneKey, String variant) {
        return boneItemId(rig, boneKey, variant, false);
    }

    /**
     * The movement states this emote is worn for, resolved to the enum.
     *
     * <p>Empty means it is an ordinary emote — plays where you stand, ends on
     * the first step. Non-empty makes it a stance.
     *
     * <p><b>An unrecognised name is dropped, and if that leaves nothing the
     * emote is an ordinary one.</b> A newer studio can name a state this jar
     * cannot detect, and the alternative — a stance whose condition never holds
     * — is a rig standing frozen inside somebody with nothing to explain it.
     * Playing it the old way is the same graceful step down a jar without this
     * field at all takes, which is what makes the field additive in both
     * directions.
     */
    static Set<EmoteTrigger> triggersOf(Emote emote) {
        if (emote == null || emote.triggers == null || emote.triggers.isEmpty()) {
            return Collections.emptySet();
        }
        EnumSet<EmoteTrigger> resolved = EnumSet.noneOf(EmoteTrigger.class);
        for (String name : emote.triggers) {
            EmoteTrigger trigger = EmoteTrigger.of(name);
            if (trigger != null) resolved.add(trigger);
        }
        return resolved;
    }

    /**
     * The same set, said as states that can actually hold.
     *
     * <p>For DESCRIBING an emote rather than for driving one: an umbrella name
     * covers more than itself (see {@link EmoteTrigger#covers()}), so a stance
     * that named the old single crouching state is worn in both of the two it
     * became, and reporting the name it used would answer "no" about a state it
     * plainly plays in.
     *
     * <p>Playback does not go through this — {@code EmoteDirector.plays} asks
     * the other way round, from the resolved state down to the name a pack
     * used — because that is one lookup per pass rather than a set built per
     * pass to hold the same answer.
     */
    static Set<EmoteTrigger> statesOf(Set<EmoteTrigger> named) {
        if (named == null || named.isEmpty()) return Collections.emptySet();
        EnumSet<EmoteTrigger> states = EnumSet.noneOf(EmoteTrigger.class);
        for (EmoteTrigger trigger : named) states.addAll(trigger.covers());
        return states;
    }

    /**
     * The custom_model_data of one bone, on the whole-limb or the jointed
     * skeleton.
     *
     * On the jointed skeleton studio bakes every arm bone qualified at BOTH
     * widths and nothing unqualified, so a jointed arm always carries a width —
     * defaulting to wide when the profile could not answer, because a wide
     * jointed arm is a model that exists and a bare one is not. On the
     * whole-limb skeleton the rule is unchanged: qualify an arm when the rig
     * says that width was baked, else the unqualified model every rig has.
     */
    static String boneItemId(PlayerRig rig, String boneKey, String variant, boolean jointed) {
        String bone = boneKey.toLowerCase(Locale.ROOT);
        boolean arm = VARIANT_BONES.contains(bone);
        if (jointed) {
            String v = arm ? (SkinModel.WIDE.equals(variant) || SkinModel.SLIM.equals(variant) ? variant : SkinModel.WIDE) : null;
            return rig.item + "__jointed" + (v != null ? "__" + v : "") + "__" + bone;
        }
        if (variant != null && rig.arms != null && rig.arms.contains(variant) && arm) {
            return rig.item + "__" + variant + "__" + bone;
        }
        return rig.item + "__" + bone;
    }

    /** Whether a cape can be spawned for this rig: they have one baked AND
     *  this manifest carried the bone to hang it from. */
    boolean usesCape(PlayerRig rig) {
        return rig != null && rig.cape && capeBone != null;
    }

    /** Whether this rig's jointed models exist to spawn: the rig was baked
     *  jointed and this manifest carried the jointed bone table. */
    boolean usesJointed(PlayerRig rig) {
        return rig != null && rig.jointed && jointedBones != null && !jointedBones.isEmpty();
    }

    /** The bone table to spawn and pose for this player: the ten jointed bones
     *  when both the rig and the manifest have them, else the whole-limb six. */
    List<Bone> bonesFor(PlayerRig rig) {
        List<Bone> table = usesJointed(rig) ? jointedBones : bones;
        if (!usesCape(rig)) return table;
        // Appended rather than shipped inside the table, which is what keeps
        // the manifest readable by a jar that has never heard of capes. It
        // goes LAST so its parent — the body — is already composed by the time
        // the poser reaches it, the same single forward pass the jointed table
        // relies on.
        List<Bone> withCape = new java.util.ArrayList<>(table.size() + 1);
        withCape.addAll(table);
        withCape.add(capeBone);
        return withCape;
    }

    private static final class Manifest {
        String packId;
        List<Bone> bones;
        /** The ten-bone table with elbows and knees, parents before children.
         *  Absent on a manifest from before joints; this jar then keeps every
         *  player on the whole-limb `bones`. */
        List<Bone> jointedBones;
        /** The root's joint, block-model space. Absent on older manifests. */
        float[] rootPivot;
        /** The cape's bone, or null on a pack whose recipients have none. */
        Bone capeBone;
        Map<String, Emote> emotes;
        /** Movement sets, keyed in the SAME id space as the emotes above.
         *  Absent on every manifest from before groups existed. */
        Map<String, Group> groups;
        Map<String, PlayerRig> players;
        /** emoteId -> packId, written by save() only. See RigStore.save. */
        Map<String, String> packs;
        /** groupId -> packId, on the same terms as `packs`. */
        Map<String, String> groupPacks;
    }

    private final Gson gson = new Gson();
    private final Map<String, Emote> byId = new ConcurrentHashMap<>();
    private final Map<String, Group> groupsById = new ConcurrentHashMap<>();
    private final Map<String, String> packOfEmote = new ConcurrentHashMap<>();
    private final Map<String, String> packOfGroup = new ConcurrentHashMap<>();
    private final Map<String, PlayerRig> byPlayer = new ConcurrentHashMap<>();
    private volatile List<Bone> bones = Collections.emptyList();
    private volatile List<Bone> jointedBones = Collections.emptyList();
    private volatile float[] rootPivot = null;
    private volatile Bone capeBone = null;
    private final File file;

    public EmoteStore(File dataFolder) {
        this.file = new File(dataFolder, "emotes.json");
    }

    List<Bone> bones() {
        return bones;
    }

    List<Bone> jointedBones() {
        return jointedBones;
    }

    /**
     * Where the root turns about, or null if this manifest predates the root.
     *
     * Null is not defaulted to a guess: a wrong pivot turns a flip into a
     * swing about somebody's ankles, and an older manifest has no root
     * keyframes to apply anyway, so there is nothing to guess FOR.
     */
    float[] rootPivot() {
        return rootPivot;
    }

    /**
     * The nil UUID, which Studio bakes a vanilla Steve rig under. Mirrors the
     * default emote player in its manifest writer — keep the two in sync.
     */
    private static final String DEFAULT_PLAYER = "00000000000000000000000000000000";

    /** The rig baked for this player specifically, ignoring the fallback. */
    PlayerRig ownRigFor(java.util.UUID playerId) {
        return playerId == null ? null : byPlayer.get(key(playerId));
    }

    /**
     * The rig this player should wear: their own, else the shared Steve.
     *
     * Falling back rather than refusing is the whole reason the default is
     * baked — somebody who joined the party after the last sync would
     * otherwise be told to go and re-sync, which is a poor answer to a person
     * standing in game who wants to wave.
     */
    PlayerRig rigFor(java.util.UUID playerId) {
        PlayerRig own = ownRigFor(playerId);
        return own != null ? own : byPlayer.get(DEFAULT_PLAYER);
    }

    /** Whether the pack carries any rig at all — see the /emote diagnostics. */
    public boolean hasAnyRig() {
        return !byPlayer.isEmpty();
    }

    /**
     * The emote with this name, matched the way the code API matches an
     * animation: exact first, then case-insensitively, so a pack holding both
     * "Wave" and "wave" keeps both reachable. Ids are slugs and names are free
     * text, so both are tried — somebody typing what they see in the panel is
     * typing the name.
     */
    Emote find(String query) {
        if (query == null || query.isEmpty()) return null;
        Emote exact = byId.get(query);
        if (exact != null) return exact;
        for (Emote emote : byId.values()) {
            if (emote != null && query.equals(emote.name)) return emote;
        }
        String lower = query.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Emote> entry : byId.entrySet()) {
            if (entry.getKey().toLowerCase(Locale.ROOT).equals(lower)) return entry.getValue();
        }
        for (Emote emote : byId.values()) {
            if (emote != null && emote.name != null && emote.name.toLowerCase(Locale.ROOT).equals(lower)) return emote;
        }
        return null;
    }

    /**
     * The group with this name, matched exactly as {@link #find} matches an
     * emote — and searched FIRST by every caller, for one reason: studio
     * allocates both out of one id space, so a name can only ever be one of
     * them, and asking the smaller map first costs nothing.
     */
    Group findGroup(String query) {
        if (query == null || query.isEmpty()) return null;
        Group exact = groupsById.get(query);
        if (exact != null) return exact;
        for (Group group : groupsById.values()) {
            if (group != null && query.equals(group.name)) return group;
        }
        String lower = query.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Group> entry : groupsById.entrySet()) {
            if (entry.getKey().toLowerCase(Locale.ROOT).equals(lower)) return entry.getValue();
        }
        for (Group group : groupsById.values()) {
            if (group != null && group.name != null && group.name.toLowerCase(Locale.ROOT).equals(lower)) {
                return group;
            }
        }
        return null;
    }

    /**
     * The states this group wears something in, resolved to the enum and to
     * emotes this jar actually holds.
     *
     * <p>A part naming a state this jar has never heard of is dropped, exactly
     * as {@link #triggersOf} drops one — a newer studio can name a state this
     * version cannot detect, and a condition that never holds is worse than a
     * state the group simply does not cover. A part naming an emote that is not
     * here is dropped too: studio does not ship one (its manifest builder drops
     * a part whose emote it dropped), so reaching that is a hand-edited file,
     * and the honest answer is that the state falls back to the player's own
     * body rather than to a rig frozen at frame zero.
     */
    Map<EmoteTrigger, Emote> partsOf(Group group) {
        if (group == null || group.parts == null || group.parts.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<EmoteTrigger, Emote> resolved = new java.util.EnumMap<>(EmoteTrigger.class);
        for (Map.Entry<String, String> entry : group.parts.entrySet()) {
            EmoteTrigger trigger = EmoteTrigger.of(entry.getKey());
            if (trigger == null || entry.getValue() == null) continue;
            Emote emote = byId.get(entry.getValue());
            if (emote != null) resolved.put(trigger, emote);
        }
        return resolved;
    }

    /**
     * Every emote's and every group's display name, for tab-completion and the
     * error message.
     *
     * <p>Groups are in it because {@code /emote} is how one is worn: a name a
     * player has to type and cannot be offered is a feature they have to be
     * told about out of band.
     */
    List<String> names() {
        List<String> out = new java.util.ArrayList<>(byId.size() + groupsById.size());
        for (Emote emote : byId.values()) {
            if (emote != null && emote.name != null && !emote.name.isEmpty()) out.add(emote.name);
        }
        for (Group group : groupsById.values()) {
            if (group != null && group.name != null && !group.name.isEmpty()) out.add(group.name);
        }
        Collections.sort(out);
        return out;
    }

    private static String key(java.util.UUID id) {
        return id.toString().replace("-", "").toLowerCase(Locale.ROOT);
    }

    /**
     * Applies a manifest. Safe to call from any thread.
     *
     * REPLACES everything the same pack supplied before, so an emote deleted
     * in the panel stops existing here — the same contract {@link RigStore}
     * documents, and for the same reason: a per-key merge would leave a
     * deleted emote playable forever, since nothing else tells us it went.
     *
     * <p>The players map is replaced wholesale rather than merged per pack.
     * A baked rig belongs to one push, not to a pack: the pack a player is
     * currently wearing is the last one pushed to them, so an older push's
     * item names name models that pack no longer contains.
     */
    public MergeResult updateFromJson(String json) {
        Manifest manifest;
        try {
            manifest = gson.fromJson(json, Manifest.class);
        } catch (RuntimeException e) {
            return MergeResult.failed("emote manifest wasn't readable: " + e.getMessage());
        }
        if (manifest == null) {
            return MergeResult.failed("emote manifest was empty");
        }

        if (manifest.bones != null) bones = manifest.bones;
        // Replaced, not merged with the old value: a manifest that carries a
        // whole-limb `bones` but no `jointedBones` is an OLDER studio, and
        // keeping a previous push's jointed table would pose ten bones from a
        // pack that only baked six. Absent means empty means whole-limb.
        jointedBones = manifest.jointedBones != null ? manifest.jointedBones : Collections.emptyList();
        if (manifest.rootPivot != null && manifest.rootPivot.length == 3) rootPivot = manifest.rootPivot;
        // Replaced rather than merged, like `jointedBones` above and for the
        // same reason: a push from a studio that has capes and one from a pack
        // whose recipients have none must not leave a stale bone behind.
        capeBone = manifest.capeBone;

        if (manifest.packId != null && !manifest.packId.isEmpty()) {
            packOfEmote.entrySet().removeIf(entry -> {
                if (!manifest.packId.equals(entry.getValue())) return false;
                byId.remove(entry.getKey());
                return true;
            });
            // Groups are replaced per pack on exactly the same contract, and
            // for the same reason: a group deleted in the panel has to stop
            // existing here, and nothing else will ever say that it went.
            packOfGroup.entrySet().removeIf(entry -> {
                if (!manifest.packId.equals(entry.getValue())) return false;
                groupsById.remove(entry.getKey());
                return true;
            });
        }

        if (manifest.emotes != null) {
            for (Map.Entry<String, Emote> entry : manifest.emotes.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) continue;
                byId.put(entry.getKey(), entry.getValue());
                if (manifest.packId != null && !manifest.packId.isEmpty()) {
                    packOfEmote.put(entry.getKey(), manifest.packId);
                }
            }
        }

        // After the emotes, because a group's parts name them. Nothing here
        // depends on that order — `partsOf` resolves lazily, at play time — but
        // the file is written in it and reading it the same way keeps the two
        // halves of a push obviously one thing.
        if (manifest.groups != null) {
            for (Map.Entry<String, Group> entry : manifest.groups.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) continue;
                groupsById.put(entry.getKey(), entry.getValue());
                if (manifest.packId != null && !manifest.packId.isEmpty()) {
                    packOfGroup.put(entry.getKey(), manifest.packId);
                }
            }
        }

        byPlayer.clear();
        if (manifest.players != null) {
            for (Map.Entry<String, PlayerRig> entry : manifest.players.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null && entry.getValue().item != null) {
                    byPlayer.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
                }
            }
        }

        if (manifest.packs != null) {
            for (Map.Entry<String, String> entry : manifest.packs.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null && byId.containsKey(entry.getKey())) {
                    packOfEmote.put(entry.getKey(), entry.getValue());
                }
            }
        }

        // Groups count as entries: they are things a player can name at
        // `/emote`, and a pack whose emotes are all worn through one would
        // otherwise report a push that brought nothing.
        return MergeResult.ok(manifest.packId,
            (manifest.emotes == null ? 0 : manifest.emotes.size())
                + (manifest.groups == null ? 0 : manifest.groups.size()));
    }

    /** Every pack that has supplied an emote or a group held right now. */
    public Set<String> packIds() {
        Set<String> ids = new java.util.HashSet<>(packOfEmote.values());
        ids.addAll(packOfGroup.values());
        return Set.copyOf(ids);
    }

    /**
     * Drops every emote one pack supplied.
     *
     * <p>The baked player rigs are left alone: they belong to the pack a player
     * is WEARING rather than to any one emote, and a server holding two packs
     * would otherwise strip the rigs of the one still in use.
     */
    public void retire(String packId) {
        if (packId == null || packId.isEmpty()) return;
        packOfEmote.entrySet().removeIf(entry -> {
            if (!packId.equals(entry.getValue())) return false;
            byId.remove(entry.getKey());
            return true;
        });
        packOfGroup.entrySet().removeIf(entry -> {
            if (!packId.equals(entry.getValue())) return false;
            groupsById.remove(entry.getKey());
            return true;
        });
    }

    /** What one emote is, for a caller deciding whether to offer it. */
    Emote get(String id) {
        return id == null ? null : byId.get(id);
    }

    public void save(Logger logger) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                logger.warning("Couldn't create " + parent + " to persist emotes");
                return;
            }
            Manifest manifest = new Manifest();
            manifest.bones = bones;
            manifest.jointedBones = jointedBones;
            manifest.rootPivot = rootPivot;
            manifest.capeBone = capeBone;
            manifest.emotes = new HashMap<>(byId);
            manifest.groups = new HashMap<>(groupsById);
            manifest.players = new HashMap<>(byPlayer);
            manifest.packs = new HashMap<>(packOfEmote);
            manifest.groupPacks = new HashMap<>(packOfGroup);
            Files.write(file.toPath(), gson.toJson(manifest).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.warning("Couldn't persist emotes.json: " + e.getMessage());
        }
    }

    public void load(Logger logger) {
        if (!file.exists()) return;
        try {
            Manifest saved = gson.fromJson(
                new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8), Manifest.class);
            if (saved == null) return;
            if (saved.bones != null) bones = saved.bones;
            if (saved.jointedBones != null) jointedBones = saved.jointedBones;
            if (saved.rootPivot != null && saved.rootPivot.length == 3) rootPivot = saved.rootPivot;
            if (saved.capeBone != null) capeBone = saved.capeBone;

            // Only emotes whose owning pack is known are restored, for the
            // reason RigStore.load gives: an unattributed entry can never be
            // retired by a later push, so it would outlive every sync.
            Map<String, String> owners = saved.packs == null ? new HashMap<>() : saved.packs;
            if (saved.emotes != null) {
                for (Map.Entry<String, Emote> entry : saved.emotes.entrySet()) {
                    String owner = entry.getKey() == null ? null : owners.get(entry.getKey());
                    if (owner == null || entry.getValue() == null) continue;
                    byId.put(entry.getKey(), entry.getValue());
                    packOfEmote.put(entry.getKey(), owner);
                }
            }
            // Same rule as the emotes above: only a group whose owning pack is
            // known comes back, because an unattributed one could never be
            // retired by a later push and would outlive every sync.
            Map<String, String> groupOwners = saved.groupPacks == null ? new HashMap<>() : saved.groupPacks;
            if (saved.groups != null) {
                for (Map.Entry<String, Group> entry : saved.groups.entrySet()) {
                    String owner = entry.getKey() == null ? null : groupOwners.get(entry.getKey());
                    if (owner == null || entry.getValue() == null) continue;
                    groupsById.put(entry.getKey(), entry.getValue());
                    packOfGroup.put(entry.getKey(), owner);
                }
            }
            if (saved.players != null) {
                for (Map.Entry<String, PlayerRig> entry : saved.players.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        byPlayer.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
                    }
                }
            }
            logger.info("Loaded " + byId.size() + " emote(s) and " + groupsById.size()
                + " group(s) from emotes.json");
        } catch (IOException | RuntimeException e) {
            logger.warning("Couldn't load emotes.json: " + e.getMessage());
        }
    }
}
