package ai.resourcepack.engine.core.sync;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.ContentRegistration;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.EntityInfo;
import ai.resourcepack.engine.api.MergeResult;
import ai.resourcepack.engine.api.Namespace;
import ai.resourcepack.engine.api.OverlayInfo;
import ai.resourcepack.engine.api.OverlayTrigger;
import ai.resourcepack.engine.api.SoundInfo;
import ai.resourcepack.engine.api.VehicleEmitter;
import ai.resourcepack.engine.api.VehicleFlight;
import ai.resourcepack.engine.api.VehicleHitbox;
import ai.resourcepack.engine.api.VehicleInfo;
import ai.resourcepack.engine.api.VehicleMedium;
import ai.resourcepack.engine.api.VehicleSeat;
import ai.resourcepack.engine.api.VehicleState;
import ai.resourcepack.engine.core.armor3d.Armor3dSet;
import ai.resourcepack.engine.core.sync.StudioWire.*;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * What a pushed Studio pack holds that a command can name.
 *
 * <p>A pack is a zip of art. Wearing it is enough to <em>see</em> a screen or
 * <em>hear</em> a sound, but not to ask for one: opening a GUI means knowing
 * which container it was drawn for and which characters position it, and
 * playing a sound means knowing the event name and its category. None of that
 * is recoverable from the zip, so Studio sends a small manifest beside it —
 * exactly as it already does for emote rigs.
 *
 * <p><strong>What arrives is registered, not kept to one side.</strong> The
 * ids go into the same {@link ai.resourcepack.engine.api.ContentRegistry}
 * everything else lives in, under the namespace {@code studio}, so
 * {@code /rp sound studio:chime} is the same kind of command as
 * {@code /rp sound mypack:chime} and {@code /rp sounds} lists both. That is
 * what {@link ContentSource#STUDIO} was put in the enum for.
 *
 * <p>The whole namespace is replaced on every push, because a push is a whole
 * pack — the same rule {@link Namespace#release()} documents, applied to the
 * one source that changes most often.
 *
 * <p>Persisted to {@code studio-content.json} beside the emote store, and for
 * the same reason: the pack a player is wearing survives a restart, so the
 * ability to name what is in it has to as well.
 */
public final class StudioContent {

    /**
     * The namespace pushed content lands in.
     *
     * <p>Fixed rather than the pack's own name: each player holds one push, in
     * one bundle ({@link StudioPush#BUNDLE}), so a command naming
     * {@code studio:menu} means the menu in the push THEY hold. Several people
     * holding different pushes share the namespace and each is answered from
     * their own — see {@link PushedPacks}.
     */
    public static final String NAMESPACE = "studio";

    /**
     * A pushed mob's skills, kept as they arrived: the engine's skill loader
     * parses them beside the content folder's, and a restart writes them back
     * unchanged. See {@link #mobSkills()}.
     */
    public static final class PushedMobSkills {
        private final List<String> lines;
        private final String stance;
        private final Map<String, Map<String, Object>> metaskills;
        private final boolean trusted;

        PushedMobSkills(List<String> lines, String stance, Map<String, Map<String, Object>> metaskills,
                        boolean trusted) {
            this.lines = List.copyOf(lines);
            this.stance = stance;
            this.metaskills = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(metaskills));
            this.trusted = trusted;
        }

        /**
         * Whether these came in a push read as trusted. When not, the skill
         * loader holds every line to what a stranger may run
         * ({@code SkillDefinitions.strangerRefusal}).
         */
        public boolean trusted() {
            return trusted;
        }

        /** The mob's own lines, as written. */
        public List<String> lines() {
            return lines;
        }

        /** The stance it starts in, or null. */
        public String stance() {
            return stance;
        }

        /**
         * Bare metaskill name to its body in MythicMobs' shape
         * ({@code Cooldown}, {@code Conditions}, {@code TargetConditions},
         * {@code Skills}), which is what the skill loader reads.
         */
        public Map<String, Map<String, Object>> metaskills() {
            return metaskills;
        }

        /**
         * The mob's skills off the wire; with {@code trusted} false, without
         * any line or metaskill step that runs a command ({@link
         * #runsCommand}), each counted in {@code dropped[0]}.
         */
        static PushedMobSkills of(Mob mob, boolean trusted, int[] dropped) {
            List<String> lines = new ArrayList<>();
            for (String line : mob.skills == null ? List.<String>of() : mob.skills) {
                if (line != null && !line.isBlank()) {
                    if (trusted || !runsCommand(line)) {
                        lines.add(line);
                    } else {
                        dropped[0]++;
                    }
                }
            }
            Map<String, Map<String, Object>> metaskills = new LinkedHashMap<>();
            for (MobMetaskill skill : mob.metaskills == null ? List.<MobMetaskill>of() : mob.metaskills) {
                if (skill == null || skill.name == null || skill.name.isBlank()) {
                    continue;
                }
                Map<String, Object> body = new LinkedHashMap<>();
                if (skill.cooldown != null && skill.cooldown > 0) {
                    body.put("Cooldown", skill.cooldown);
                }
                if (skill.conditions != null && !skill.conditions.isEmpty()) {
                    body.put("Conditions", List.copyOf(skill.conditions));
                }
                if (skill.targetConditions != null && !skill.targetConditions.isEmpty()) {
                    body.put("TargetConditions", List.copyOf(skill.targetConditions));
                }
                List<String> steps = new ArrayList<>();
                for (String step : skill.skills == null ? List.<String>of() : skill.skills) {
                    if (step == null) {
                        continue;
                    }
                    if (trusted || !runsCommand(step)) {
                        steps.add(step);
                    } else {
                        dropped[0]++;
                    }
                }
                body.put("Skills", List.copyOf(steps));
                metaskills.put(skill.name.trim().toLowerCase(Locale.ROOT), body);
            }
            String stance = mob.stance == null || mob.stance.isBlank() ? null : mob.stance.trim();
            if (lines.isEmpty() && metaskills.isEmpty() && stance == null) {
                return null;
            }
            return new PushedMobSkills(lines, stance, metaskills, trusted);
        }

        /**
         * The command mechanics ({@code command}, and its aliases {@code cmd}
         * and {@code consolecommand}) named anywhere in a line, inline skills
         * ({@code [ - cmd{...} ]}) and a condition's action included. A mechanic
         * with a command is always its name followed by its arguments, in
         * either bracket (the parser takes {@code command[c=...]} as readily as
         * braces) after anything up to a space, which the parser trims. A search
         * of the text rather than a parse, so that no nesting the parser learns
         * later can hide one; a line that merely says the word before a bracket
         * somewhere is left out too, which is the safe way to be wrong. This is
         * the coarse first pass, counted for the player; the skill loader then
         * holds a stranger's lines to an allowlist (see
         * {@code SkillDefinitions.strangerRefusal}).
         */
        static boolean runsCommand(String line) {
            return line != null && COMMAND_MECHANIC.matcher(line).find();
        }

        private static final java.util.regex.Pattern COMMAND_MECHANIC = java.util.regex.Pattern.compile(
                "(?i)(?<![a-z0-9_])(?:command|cmd|consolecommand)[\\x00-\\x20]*[\\{\\[]");

        /** Back onto a wire mob, in the shape it arrived in. */
        @SuppressWarnings("unchecked")
        void writeTo(Mob out) {
            if (!lines.isEmpty()) {
                out.skills = new ArrayList<>(lines);
            }
            out.stance = stance;
            if (metaskills.isEmpty()) {
                return;
            }
            out.metaskills = new ArrayList<>();
            metaskills.forEach((name, body) -> {
                MobMetaskill skill = new MobMetaskill();
                skill.name = name;
                Object cooldown = body.get("Cooldown");
                skill.cooldown = cooldown instanceof Number ? ((Number) cooldown).doubleValue() : null;
                skill.conditions = (List<String>) body.get("Conditions");
                skill.targetConditions = (List<String>) body.get("TargetConditions");
                skill.skills = new ArrayList<>((List<String>) body.get("Skills"));
                out.metaskills.add(skill);
            });
        }
    }

    private final Gson gson = new Gson();
    private final File file;

    private volatile Map<ContentId, SoundInfo> sounds = Map.of();
    private volatile Map<ContentId, OverlayInfo> screens = Map.of();
    private volatile Map<ContentId, ai.resourcepack.engine.core.font.ScreenHeads> screenHeads = Map.of();
    private volatile Map<ContentId, OverlayInfo> huds = Map.of();
    private volatile Map<ContentId, VehicleInfo> vehicles = Map.of();
    /**
     * The pushed models a vehicle may drive straight through, by bare id.
     *
     * <p>A set of exceptions rather than a map of answers, because the answer
     * for everything else is yes and a map would have to hold every model in
     * the pack to say so. See {@link #modelStopsVehicles}.
     */
    private volatile Set<String> vehiclePassable = Set.of();
    /**
     * The pushed models a player's placement varies, by bare id. Exceptions
     * again: a model absent here goes down exactly as built.
     */
    private volatile Map<String, ai.resourcepack.engine.api.ModelInfo.Variation> variations = Map.of();
    private volatile Map<String, String> works = Map.of();
    private volatile Map<ContentId, ai.resourcepack.engine.api.DialogInfo> dialogs = Map.of();
    private volatile Map<String, Armor3dSet> armor3d = Map.of();
    private volatile Map<ContentId, ai.resourcepack.engine.api.ShaderObjectInfo> objects = Map.of();
    private volatile Map<ContentId, ai.resourcepack.engine.api.TextEffectInfo> textEffects = Map.of();
    /** The pushed icons, so a server can list and name them; the pictures are the pack's own. */
    private volatile Map<ContentId, ai.resourcepack.engine.api.IconInfo> icons = Map.of();
    private volatile Map<ContentId, ai.resourcepack.engine.core.vfx.VfxEntry> vfx = Map.of();
    private volatile Map<ContentId, ai.resourcepack.engine.core.transition.TransitionEntry> stingers = Map.of();
    private volatile Map<ContentId, EntityInfo> mobs = Map.of();
    /** The pushed mobs' skills, keyed as {@link #mobs}: only the mobs that have any. */
    private volatile Map<ContentId, PushedMobSkills> mobSkills = Map.of();
    private volatile String packId = "";

    /** Whether the last manifest was read as trusted; what {@link #save} records. */
    private volatile boolean commandsKept = true;

    /** How many commands the last manifest read had taken out of it; see {@link #droppedCommands()}. */
    private volatile int droppedCommands;

    /** The registry handle, held for as long as the content is registered. */
    private Namespace claimed;

    public StudioContent(File dataFolder) {
        this.file = new File(dataFolder, "studio-content.json");
    }

    /** One with no file behind it: a snapshot of one push, or several merged. See {@link #snapshot}. */
    private StudioContent() {
        this.file = null;
    }

    /**
     * The manifest Studio writes at the root of an exported pack zip, where
     * the game never looks. See {@link #read}.
     */
    public static final String IN_PACK = "studio-content.json";

    /**
     * One manifest that did not come by push: a Studio pack dropped into the
     * content folder, or the pack this server publishes. Null when it reads as
     * nothing, with the reason in the log.
     *
     * <p>A zip is art, and nothing in art says which characters are a shader
     * object, a screen or a dialog. Until this, only a push said so, and a
     * Studio export in {@code content/} brought its pictures and none of its
     * objects.
     */
    public static StudioContent read(String json, String where, Logger log) {
        StudioContent read = new StudioContent();
        MergeResult result = read.updateFromJson(json, log);
        if (!result.ok()) {
            if (log != null) log.warning("Could not read " + where + ": " + result.error());
            return null;
        }
        return read;
    }

    /** A skill line naming the command mechanic anywhere in it, inline skills included. */
    private static final java.util.regex.Pattern COMMAND_SKILL =
            java.util.regex.Pattern.compile("(^|[^a-z0-9_])(command|cmd|consolecommand)([^a-z0-9_]|$)");

    /** What {@link #withoutConsole} returns: the manifest, and how much it took out. */
    public record Stripped(String json, int removed) { }

    /**
     * A pushed manifest with everything that would run a command as the
     * console taken out: each dialog's {@code consoleCommands}, and every mob
     * skill line, metaskill line or metaskill condition that names the
     * {@code command} mechanic. For a push from somebody who may live-test a
     * pack but may not run console commands ({@code rpengine.sync.console}):
     * {@code rpengine.sync} is often given to everybody on a test server, and
     * a pushed dialog button running {@code op <name>} as the console made
     * that the same as giving everybody op.
     *
     * <p>The skill test is deliberately coarse, a lower-cased search for the
     * mechanic's names as words: it cannot be got round by inline skills or
     * odd spacing, and a mob that loses a line it did not need is the whole
     * cost of a false match. A manifest that is not JSON comes back unchanged
     * for {@link #updateFromJson} to refuse.
     */
    public static Stripped withoutConsole(String json) {
        com.google.gson.JsonElement root;
        try {
            root = com.google.gson.JsonParser.parseString(json);
        } catch (RuntimeException e) {
            return new Stripped(json, 0);
        }
        if (!root.isJsonObject()) {
            return new Stripped(json, 0);
        }
        int removed = 0;
        com.google.gson.JsonObject manifest = root.getAsJsonObject();
        for (com.google.gson.JsonObject dialog : objects(manifest.get("dialogs"))) {
            com.google.gson.JsonElement consoles = dialog.remove("consoleCommands");
            if (consoles != null && !(consoles.isJsonArray() && consoles.getAsJsonArray().isEmpty())
                    && !consoles.isJsonNull()) {
                removed++;
            }
        }
        for (com.google.gson.JsonObject mob : objects(manifest.get("mobs"))) {
            removed += dropCommandLines(mob, "skills");
            for (com.google.gson.JsonObject meta : objects(mob.get("metaskills"))) {
                removed += dropCommandLines(meta, "skills");
                removed += dropCommandLines(meta, "conditions");
                removed += dropCommandLines(meta, "targetConditions");
            }
        }
        return removed == 0 ? new Stripped(json, 0) : new Stripped(manifest.toString(), removed);
    }

    private static List<com.google.gson.JsonObject> objects(com.google.gson.JsonElement array) {
        List<com.google.gson.JsonObject> out = new ArrayList<>();
        if (array != null && array.isJsonArray()) {
            for (com.google.gson.JsonElement each : array.getAsJsonArray()) {
                if (each.isJsonObject()) {
                    out.add(each.getAsJsonObject());
                }
            }
        }
        return out;
    }

    /** Takes out of {@code owner.field} (a list of strings) every line naming the command mechanic. */
    private static int dropCommandLines(com.google.gson.JsonObject owner, String field) {
        com.google.gson.JsonElement lines = owner.get(field);
        if (lines == null || !lines.isJsonArray()) {
            return 0;
        }
        com.google.gson.JsonArray kept = new com.google.gson.JsonArray();
        int dropped = 0;
        for (com.google.gson.JsonElement line : lines.getAsJsonArray()) {
            String text = line.isJsonPrimitive() ? line.getAsString() : line.toString();
            if (COMMAND_SKILL.matcher(text.toLowerCase(java.util.Locale.ROOT)).find()) {
                dropped++;
            } else {
                kept.add(line);
            }
        }
        if (dropped > 0) {
            owner.add(field, kept);
        }
        return dropped;
    }

    /**
     * How many commands the last manifest read had taken out of it because it
     * was read without them: console buttons and command skill lines. Zero
     * when it was allowed them or had none.
     */
    public int droppedCommands() {
        return droppedCommands;
    }

    /** The pack this came from, as Studio names it. Empty when the manifest named none. */
    public String packId() {
        return packId;
    }

    /**
     * A copy of what this holds now, with no file and no registry claim.
     *
     * <p>This store is the LAST push, replaced whole by the next — which is
     * right for what it persists and wrong as the only record, because on a
     * server where several people sync, the next push is usually somebody
     * else's. A snapshot is how one push's content outlives the next one for
     * the players still holding it; see {@link PushedPacks}. The maps are
     * immutable and replaced rather than changed, so sharing them is a copy.
     */
    public StudioContent snapshot() {
        StudioContent copy = new StudioContent();
        copy.sounds = sounds;
        copy.screens = screens;
        copy.huds = huds;
        copy.vehicles = vehicles;
        copy.vehiclePassable = vehiclePassable;
        copy.variations = variations;
        copy.works = works;
        copy.dialogs = dialogs;
        copy.armor3d = armor3d;
        copy.objects = objects;
        copy.textEffects = textEffects;
        copy.icons = icons;
        copy.vfx = vfx;
        copy.stingers = stingers;
        copy.mobs = mobs;
        copy.mobSkills = mobSkills;
        copy.packId = packId;
        return copy;
    }

    /**
     * Several pushes as one catalogue, in the order given: a later one wins an
     * id it shares with an earlier one.
     *
     * <p>What the server lists, completes and registers, so nobody's content
     * disappears from it because somebody else pushed. Who may actually be
     * SHOWN a pushed screen or dialog is decided per player, against their own
     * push — see {@link PushedPacks#contentFor}.
     */
    public static StudioContent union(java.util.List<StudioContent> packs) {
        Map<ContentId, SoundInfo> sounds = new LinkedHashMap<>();
        Map<ContentId, OverlayInfo> screens = new LinkedHashMap<>();
        Map<ContentId, OverlayInfo> huds = new LinkedHashMap<>();
        Map<ContentId, VehicleInfo> vehicles = new LinkedHashMap<>();
        Set<String> passable = new java.util.LinkedHashSet<>();
        Map<String, ai.resourcepack.engine.api.ModelInfo.Variation> varied = new LinkedHashMap<>();
        Map<String, String> worked = new LinkedHashMap<>();
        Map<ContentId, ai.resourcepack.engine.api.DialogInfo> dialogs = new LinkedHashMap<>();
        Map<String, Armor3dSet> armor = new LinkedHashMap<>();
        Map<ContentId, ai.resourcepack.engine.api.ShaderObjectInfo> objects = new LinkedHashMap<>();
        Map<ContentId, ai.resourcepack.engine.api.TextEffectInfo> effects = new LinkedHashMap<>();
        Map<ContentId, ai.resourcepack.engine.api.IconInfo> icons = new LinkedHashMap<>();
        Map<ContentId, ai.resourcepack.engine.core.vfx.VfxEntry> vfx = new LinkedHashMap<>();
        Map<ContentId, ai.resourcepack.engine.core.transition.TransitionEntry> stingers = new LinkedHashMap<>();
        Map<ContentId, EntityInfo> mobs = new LinkedHashMap<>();
        Map<ContentId, PushedMobSkills> mobSkills = new LinkedHashMap<>();
        String last = "";
        for (StudioContent pack : packs == null ? java.util.List.<StudioContent>of() : packs) {
            if (pack == null) {
                continue;
            }
            sounds.putAll(pack.sounds);
            screens.putAll(pack.screens);
            huds.putAll(pack.huds);
            vehicles.putAll(pack.vehicles);
            passable.addAll(pack.vehiclePassable);
            varied.putAll(pack.variations);
            worked.putAll(pack.works);
            dialogs.putAll(pack.dialogs);
            armor.putAll(pack.armor3d);
            objects.putAll(pack.objects);
            effects.putAll(pack.textEffects);
            icons.putAll(pack.icons);
            vfx.putAll(pack.vfx);
            stingers.putAll(pack.stingers);
            // A mob's skills go with the mob: a later push of it with none
            // must not keep an earlier push's.
            pack.mobs.keySet().forEach(mobSkills::remove);
            mobs.putAll(pack.mobs);
            mobSkills.putAll(pack.mobSkills);
            last = pack.packId;
        }
        StudioContent all = new StudioContent();
        all.sounds = Map.copyOf(sounds);
        all.screens = Map.copyOf(screens);
        all.huds = Map.copyOf(huds);
        all.vehicles = Map.copyOf(vehicles);
        all.vehiclePassable = Set.copyOf(passable);
        all.variations = Map.copyOf(varied);
        all.works = Map.copyOf(worked);
        all.dialogs = Map.copyOf(dialogs);
        all.armor3d = Map.copyOf(armor);
        all.objects = Map.copyOf(objects);
        all.textEffects = Map.copyOf(effects);
        all.icons = Map.copyOf(icons);
        all.vfx = Map.copyOf(vfx);
        all.stingers = Map.copyOf(stingers);
        all.mobs = Map.copyOf(mobs);
        all.mobSkills = Map.copyOf(mobSkills);
        all.packId = last;
        return all;
    }

    /** The pushed sounds, keyed by id. */
    public Map<ContentId, SoundInfo> sounds() {
        return sounds;
    }

    /** The pushed screens, keyed by id. */
    public Map<ContentId, OverlayInfo> screens() {
        return screens;
    }

    /** The player heads on pushed screens, keyed by screen id. Only screens that have any. */
    public Map<ContentId, ai.resourcepack.engine.core.font.ScreenHeads> screenHeads() {
        return screenHeads;
    }

    /** The pushed HUD overlays, keyed by id. */
    public Map<ContentId, OverlayInfo> huds() {
        return huds;
    }

    /** The pushed vehicles, keyed by id. */
    public Map<ContentId, VehicleInfo> vehicles() {
        return vehicles;
    }

    /** The pushed dialogs, keyed by id. */
    public Map<ContentId, ai.resourcepack.engine.api.DialogInfo> dialogs() {
        return dialogs;
    }

    /**
     * The pushed 3D armour sets, keyed by their bare id.
     *
     * <p>Half of the server's sets: a content folder's {@code armor3d/} is the
     * other, and {@code Armor3dCatalogue} is where the two are one list. A
     * pushed id never has a colon in it and an authored one always does, so
     * the two cannot collide.
     */
    public Map<String, Armor3dSet> armor3d() {
        return armor3d;
    }

    /** The pushed 3D shader objects, keyed by {@code studio:<id>}. */
    public Map<ContentId, ai.resourcepack.engine.api.ShaderObjectInfo> objects() {
        return objects;
    }

    /**
     * The pushed icons, keyed by {@code studio:<id>}. Drawn by the pack they came
     * in, so the engine never builds them into its own: this is only so
     * {@code /rp icons} lists them and {@code :studio:<id>:} types them.
     */
    public Map<ContentId, ai.resourcepack.engine.api.IconInfo> icons() {
        return icons;
    }

    /** The pushed text effects, keyed by {@code studio:<id>}. */
    public Map<ContentId, ai.resourcepack.engine.api.TextEffectInfo> textEffects() {
        return textEffects;
    }

    /** The pushed VFX, keyed by {@code studio:<id>}. */
    public Map<ContentId, ai.resourcepack.engine.core.vfx.VfxEntry> vfx() {
        return vfx;
    }

    /**
     * The pushed transitions, keyed by {@code studio:<id>}. Read from the
     * manifest's {@code stingers} list, the name they shipped under (0.2.63)
     * and still travel by.
     */
    public Map<ContentId, ai.resourcepack.engine.core.transition.TransitionEntry> transitions() {
        return stingers;
    }

    /** The pushed custom mobs, keyed by {@code studio:<id>}. */
    public Map<ContentId, EntityInfo> mobs() {
        return mobs;
    }

    /**
     * The pushed mobs' skills, keyed by {@code studio:<id>} like {@link #mobs()},
     * for the mobs that have any. Lines and metaskills as Studio wrote them,
     * MythicMobs' grammar, parsed by the skill loader beside a content
     * folder's so there is one reader for both.
     */
    public Map<ContentId, PushedMobSkills> mobSkills() {
        return mobSkills;
    }

    /**
     * Whether a vehicle is stopped by a placement of the pushed model
     * {@code id}.
     *
     * <p>Yes for anything this has never heard of, which covers a model in a
     * pack pushed before the field existed, a model in no pack at all, and a
     * placement standing in the world from a pack that has since been
     * replaced. A vehicle that drives through the furniture is the visible
     * failure; one that stops at something it might not have needed to is not.
     */
    public boolean modelStopsVehicles(String id) {
        return id != null && !vehiclePassable.contains(id);
    }

    /**
     * How a player's placement of a pushed model varies its turn and size,
     * or {@link ai.resourcepack.engine.api.ModelInfo.Variation#NONE} for a
     * model this has never heard of.
     */
    /** The game block a pushed model works as (a furnace kind or a station), or null for none. */
    public String modelWorks(String id) {
        return id == null ? null : works.get(id);
    }

    public ai.resourcepack.engine.api.ModelInfo.Variation modelVariation(String id) {
        ai.resourcepack.engine.api.ModelInfo.Variation found = id == null ? null : variations.get(id);
        return found == null ? ai.resourcepack.engine.api.ModelInfo.Variation.NONE : found;
    }

    /** What arrived, cleaned the way a content folder's {@code random:} is. */
    static ai.resourcepack.engine.api.ModelInfo.Variation variationOf(PlaceVariation sent) {
        if (sent == null) {
            return ai.resourcepack.engine.api.ModelInfo.Variation.NONE;
        }
        return ai.resourcepack.engine.api.ModelInfo.Variation.of(
                sent.rotation == null ? 0f : sent.rotation,
                sent.rotationStep == null ? 0f : sent.rotationStep,
                sent.scaleMin == null ? 1f : sent.scaleMin,
                sent.scaleMax == null ? 1f : sent.scaleMax);
    }

    /** Whether there is anything at all. */
    public boolean isEmpty() {
        return sounds.isEmpty() && screens.isEmpty() && huds.isEmpty() && vehicles.isEmpty()
                && vehiclePassable.isEmpty() && variations.isEmpty() && works.isEmpty() && dialogs.isEmpty() && armor3d.isEmpty() && objects.isEmpty()
                && textEffects.isEmpty() && icons.isEmpty() && vfx.isEmpty() && stingers.isEmpty() && mobs.isEmpty();
    }

    /**
     * Replaces everything with what one push carried.
     *
     * <p>Replaces rather than merges. Two pushes of one pack are two versions
     * of the same thing, and a sound deleted in the editor has to disappear
     * here too or the command still offers it.
     */
    public MergeResult updateFromJson(String json) {
        return updateFromJson(json, null);
    }

    /**
     * As above, saying out loud what it had to skip.
     *
     * <p><b>Silence here cost a whole debugging session.</b> An entry whose id
     * this engine cannot parse was dropped by an {@code ifPresent} with no
     * else — so a Studio that sent a shader overlay called {@code Text} (a
     * display name, where a content id is {@code a-z0-9_.-}) produced a server
     * on which that overlay simply did not exist, with a manifest that said it
     * had arrived and nothing anywhere saying otherwise. The count in the log
     * line was the only clue and it is a number nobody knows the right value
     * of.
     *
     * @param log where to say it, or null to keep the old silence
     */
    public MergeResult updateFromJson(String json, Logger log) {
        return updateFromJson(json, log, true);
    }

    /**
     * As above, and with {@code trusted} false, read as a stranger's: everything
     * in it that runs a command, or reaches players who were not pushed it,
     * left out. That is a dialog's "Run as console" buttons and every mob
     * skill line (or metaskill step) that names a command mechanic; a dialog's
     * {@code /command} and its place on the Quick Actions key, which are every
     * player's; and a mob's natural spawning, which is every world's.
     *
     * <p><b>This is what stands between a sync and the console.</b> A push is
     * whatever the person who pressed Sync put in their pack, and on a server
     * where somebody may sync without being trusted with the console (the
     * public test server, where everybody may), a console button reading
     * {@code op {player}} or a mob that casts {@code command{c=op ...}} is that
     * person running anything they like as the server. So the caller decides,
     * by who the push is for (see {@code StudioRelay}), and what is left out is
     * left out of the store, so a restart cannot bring it back.
     * {@link #droppedCommands()} says how many commands went.
     */
    public MergeResult updateFromJson(String json, Logger log, boolean trusted) {
        if (json == null || json.isEmpty()) {
            return MergeResult.failed("empty manifest");
        }
        Manifest manifest;
        try {
            manifest = gson.fromJson(json, Manifest.class);
        } catch (JsonSyntaxException e) {
            return MergeResult.failed("not JSON: " + e.getMessage());
        }
        if (manifest == null) {
            return MergeResult.failed("not a manifest");
        }

        // The commands {@code trusted} false took out, counted for the caller to say so.
        int[] dropped = {0};
        Map<ContentId, SoundInfo> readSounds = new LinkedHashMap<>();
        for (Sound sound : manifest.sounds == null ? List.<Sound>of() : manifest.sounds) {
            if (sound == null || sound.event == null || sound.event.isEmpty()) {
                continue;
            }
            id(sound.id, log, "sound").ifPresent(id ->
                    readSounds.put(id, SoundInfo.pushed(id, sound.event, sound.category)
                            .withLength(sound.durationSeconds == null ? 0 : sound.durationSeconds)));
        }

        Map<ContentId, OverlayInfo> readScreens = new LinkedHashMap<>();
        Map<ContentId, ai.resourcepack.engine.core.font.ScreenHeads> readHeads = new LinkedHashMap<>();
        for (Overlay screen : manifest.screens == null ? List.<Overlay>of() : manifest.screens) {
            if (screen == null || screen.title == null || screen.container == null) {
                continue;
            }
            id(screen.id, log, "screen").ifPresent(id -> {
                readScreens.put(id, OverlayInfo.pushed(id, screen.title, screen.container, null));
                ai.resourcepack.engine.core.font.ScreenHeads heads = headsOf(screen);
                if (heads != null) {
                    readHeads.put(id, heads);
                }
            });
        }

        Map<ContentId, OverlayInfo> readHuds = new LinkedHashMap<>();
        for (Overlay hud : manifest.huds == null ? List.<Overlay>of() : manifest.huds) {
            if (hud == null || hud.title == null) {
                continue;
            }
            id(hud.id, log, "HUD overlay").ifPresent(id ->
                    readHuds.put(id, OverlayInfo.pushed(id, hud.title, "", slotOf(hud.slot),
                            hud.color, hud.font, hud.text, triggers(hud.triggers),
                            runs(hud.runs))
                            .withCursor(hud.advance, hud.shiftPlus, hud.shiftMinus)));
        }

        Map<ContentId, VehicleInfo> readVehicles = new LinkedHashMap<>();
        for (Vehicle vehicle : manifest.vehicles == null ? List.<Vehicle>of() : manifest.vehicles) {
            id(vehicle == null ? null : vehicle.id)
                    .flatMap(id -> vehicle(id, vehicle))
                    .ifPresent(info -> readVehicles.put(info.id(), info));
        }

        Set<String> readPassable = new java.util.LinkedHashSet<>();
        Map<String, ai.resourcepack.engine.api.ModelInfo.Variation> readVariations = new LinkedHashMap<>();
        Map<String, String> readWorks = new LinkedHashMap<>();
        for (Model model : manifest.models == null ? List.<Model>of() : manifest.models) {
            if (model == null || model.id == null || model.id.isEmpty()) {
                continue;
            }
            if (Boolean.FALSE.equals(model.vehicleCollision)) {
                readPassable.add(model.id);
            }
            ai.resourcepack.engine.api.ModelInfo.Variation variation = variationOf(model.variation);
            if (!variation.isNone()) {
                readVariations.put(model.id, variation);
            }
            if (model.works != null && !model.works.isBlank()) {
                readWorks.put(model.id, model.works.trim());
            }
        }

        Map<ContentId, ai.resourcepack.engine.api.DialogInfo> readDialogs = new LinkedHashMap<>();
        for (Dialog dialog : manifest.dialogs == null ? List.<Dialog>of() : manifest.dialogs) {
            if (dialog == null || dialog.json == null || !dialog.json.isJsonObject()) {
                continue;
            }
            Map<String, List<String>> vars = new LinkedHashMap<>();
            for (DialogVariable v : dialog.vars == null ? List.<DialogVariable>of() : dialog.vars) {
                if (v == null || v.name == null || v.values == null
                        || !ai.resourcepack.engine.core.dialog.DialogVariables.NAME.matcher(v.name).matches()) {
                    continue;
                }
                List<String> values = new ArrayList<>();
                for (String value : v.values) {
                    if (value != null && ai.resourcepack.engine.core.dialog.DialogVariables.VALUE.matcher(value).matches()) {
                        values.add(value);
                    }
                }
                if (!values.isEmpty()) {
                    vars.put(v.name, values);
                }
            }
            // Only cells the pack's sheet can hold for an item of its own: past
            // the highlight, before the digits' twins. Anything else is a
            // character the font does not draw as a 17-wide picture.
            Map<String, Integer> icons = new LinkedHashMap<>();
            if (dialog.itemIcons != null) {
                dialog.itemIcons.forEach((model, cell) -> {
                    if (model != null && !model.isEmpty() && model.length() <= 128 && cell != null
                            && ai.resourcepack.engine.core.dialog.DialogItems.packCell(cell)) {
                        icons.put(model, cell);
                    }
                });
            }
            // A sound that is not an event name is left out rather than read
            // as silence, so a typo keeps the click as it was.
            ai.resourcepack.engine.api.DialogInfo.ClickSound clickSound = clickSoundOf(dialog.clickSound);
            List<ai.resourcepack.engine.api.DialogInfo.CommandSound> clickSounds = new ArrayList<>();
            for (DialogSound sound : dialog.clickSounds == null ? List.<DialogSound>of() : dialog.clickSounds) {
                ai.resourcepack.engine.api.DialogInfo.ClickSound read = clickSoundOf(sound);
                if (read != null && sound.command != null && !sound.command.isBlank() && sound.command.length() <= 1024) {
                    clickSounds.add(new ai.resourcepack.engine.api.DialogInfo.CommandSound(sound.command, read));
                }
            }
            // What DialogInfo will not carry (a bad key, a blank or too long
            // command) is left out there; the click then does nothing.
            Map<String, String> consoles = new LinkedHashMap<>();
            for (DialogConsole console : dialog.consoleCommands == null ? List.<DialogConsole>of() : dialog.consoleCommands) {
                if (console != null && console.key != null && console.command != null) {
                    if (trusted) {
                        consoles.put(console.key, console.command);
                    } else {
                        dropped[0]++;
                    }
                }
            }
            id(dialog.id, log, "dialog").ifPresent(id -> readDialogs.put(id,
                    ai.resourcepack.engine.api.DialogInfo.pushed(id, dialog.json.toString(), dialog.name, vars, icons)
                            .withCommand(trusted ? dialog.command : null, dialog.permission)
                            .withClickSounds(clickSound, clickSounds)
                            .withConsoleCommands(consoles)
                            .withClosesAfterClick(Boolean.TRUE.equals(dialog.closeAfterClick))
                            .withQuickAction(dialog.quickAction == null || !trusted ? null
                                    : new ai.resourcepack.engine.api.DialogInfo.QuickAction(
                                            Boolean.TRUE.equals(dialog.quickAction.launcher),
                                            dialog.quickAction.label == null || dialog.quickAction.label.isBlank()
                                                    || dialog.quickAction.label.length() > 200 ? null : dialog.quickAction.label))));
        }

        Map<String, Armor3dSet> readArmor = new LinkedHashMap<>();
        for (Armor3d set : manifest.armor3d == null ? List.<Armor3d>of() : manifest.armor3d) {
            Armor3dSet read = armor3dOf(set, log);
            if (read != null) {
                readArmor.put(read.id(), read);
            }
        }

        Map<ContentId, ai.resourcepack.engine.api.ShaderObjectInfo> readObjects = new LinkedHashMap<>();
        for (ShaderObject object : manifest.objects == null ? List.<ShaderObject>of() : manifest.objects) {
            if (object == null || object.model == null || !object.model.contains(":")) {
                continue;
            }
            Map<String, Integer> states = new LinkedHashMap<>();
            for (ShaderObjectState state : object.states == null ? List.<ShaderObjectState>of() : object.states) {
                if (state != null && state.name != null && state.value != null) {
                    states.put(state.name, state.value);
                }
            }
            double size = object.size == null ? 1 : object.size;
            id(object.id, log, "3D object").ifPresent(id -> readObjects.put(id,
                    new ai.resourcepack.engine.api.ShaderObjectInfo(id, object.model, size, states, null)));
        }

        Map<ContentId, ai.resourcepack.engine.api.TextEffectInfo> readEffects = new LinkedHashMap<>();
        for (TextEffect effect : manifest.textEffects == null ? List.<TextEffect>of() : manifest.textEffects) {
            // A colour is the whole effect, so one that is not exactly
            // #rrggbb is nothing to name.
            if (effect == null || effect.color == null || !effect.color.matches("#[0-9a-fA-F]{6}")) {
                continue;
            }
            id(effect.id, log, "text effect").ifPresent(id -> readEffects.put(id,
                    new ai.resourcepack.engine.api.TextEffectInfo(id, effect.name, effect.color,
                            ai.resourcepack.engine.api.TextEffectInfo.Source.STUDIO, null)));
        }

        Map<ContentId, ai.resourcepack.engine.api.IconInfo> readIcons = new LinkedHashMap<>();
        for (Icon icon : manifest.icons == null ? List.<Icon>of() : manifest.icons) {
            // A character outside the Private Use Area is an icon drawn over a
            // real letter, which the pack may do; anything not a character at
            // all is nothing to name.
            if (icon == null || icon.codepoint == null || !Character.isValidCodePoint(icon.codepoint)
                    || icon.codepoint <= 0x20) {
                continue;
            }
            int height = icon.height == null || icon.height <= 0 ? 8 : icon.height;
            int ascent = icon.ascent == null ? Math.min(height, 7) : icon.ascent;
            id(icon.id, log, "icon").ifPresent(id -> readIcons.put(id,
                    ai.resourcepack.engine.api.IconInfo.of(id, "studio/" + id.path(), height, ascent, icon.codepoint)));
        }

        Map<ContentId, ai.resourcepack.engine.core.vfx.VfxEntry> readVfx = new LinkedHashMap<>();
        for (Vfx effect : manifest.vfx == null ? List.<Vfx>of() : manifest.vfx) {
            if (effect == null || effect.recipe == null) {
                continue;
            }
            ai.resourcepack.engine.core.vfx.VfxRecipe recipe = ai.resourcepack.engine.core.vfx.VfxRecipe.clean(effect.recipe);
            if (!recipe.drawable()) {
                if (log != null) log.warning("Pushed VFX " + effect.id + " has nothing in it that can be drawn: "
                        + String.join("; ", recipe.problems()));
                continue;
            }
            id(effect.id, log, "VFX").ifPresent(id -> readVfx.put(id, new ai.resourcepack.engine.core.vfx.VfxEntry(
                    new ai.resourcepack.engine.api.VfxInfo(id, effect.name, recipe.duration(),
                            ai.resourcepack.engine.api.VfxInfo.Source.STUDIO, null), recipe)));
        }

        Map<ContentId, ai.resourcepack.engine.core.transition.TransitionEntry> readStingers = new LinkedHashMap<>();
        for (Stinger stinger : manifest.stingers == null ? List.<Stinger>of() : manifest.stingers) {
            // The character and the two phase colours are the whole stinger
            // as far as the engine goes; without them it has nothing to send.
            if (stinger == null || stinger.glyph == null || stinger.glyph.isEmpty()
                    || !ai.resourcepack.engine.core.transition.TransitionTitles.isColor(stinger.in)
                    || !ai.resourcepack.engine.core.transition.TransitionTitles.isColor(stinger.out)) {
                if (log != null && stinger != null) log.warning("Pushed transition " + stinger.id
                        + " is missing its character or a #rrggbb colour, so it was skipped.");
                continue;
            }
            id(stinger.id, log, "transition").ifPresent(id -> readStingers.put(id,
                    new ai.resourcepack.engine.core.transition.TransitionEntry(
                            new ai.resourcepack.engine.api.TransitionInfo(id, stinger.name,
                                    stinger.inTicks == null ? 12 : stinger.inTicks,
                                    stinger.holdTicks == null ? 30 : stinger.holdTicks,
                                    stinger.outTicks == null ? 12 : stinger.outTicks,
                                    stinger.soundIn, stinger.soundOut),
                            stinger.glyph, stinger.in.toLowerCase(Locale.ROOT), stinger.out.toLowerCase(Locale.ROOT),
                            ai.resourcepack.engine.core.transition.TransitionTitles.isColor(stinger.auto)
                                    ? stinger.auto.toLowerCase(Locale.ROOT) : null)));
        }

        Map<ContentId, EntityInfo> readMobs = new LinkedHashMap<>();
        Map<ContentId, PushedMobSkills> readMobSkills = new LinkedHashMap<>();
        for (Mob mob : manifest.mobs == null ? List.<Mob>of() : manifest.mobs) {
            if (mob == null) {
                continue;
            }
            if (!trusted) {
                // Natural spawning puts the mob in every world on the server.
                mob.spawning = null;
            }
            id(mob.id, log, "mob").flatMap(id -> mob(id, mob, log))
                    .ifPresent(info -> {
                        readMobs.put(info.id(), info);
                        PushedMobSkills skills = PushedMobSkills.of(mob, trusted, dropped);
                        if (skills != null) {
                            readMobSkills.put(info.id(), skills);
                        }
                    });
        }

        commandsKept = trusted;
        droppedCommands = dropped[0];
        sounds = Map.copyOf(readSounds);
        screens = Map.copyOf(readScreens);
        screenHeads = Map.copyOf(readHeads);
        huds = Map.copyOf(readHuds);
        vehicles = Map.copyOf(readVehicles);
        vehiclePassable = Set.copyOf(readPassable);
        variations = Map.copyOf(readVariations);
        works = Map.copyOf(readWorks);
        dialogs = Map.copyOf(readDialogs);
        armor3d = Map.copyOf(readArmor);
        objects = Map.copyOf(readObjects);
        textEffects = Map.copyOf(readEffects);
        icons = Map.copyOf(readIcons);
        vfx = Map.copyOf(readVfx);
        stingers = Map.copyOf(readStingers);
        mobs = Map.copyOf(readMobs);
        mobSkills = Map.copyOf(readMobSkills);
        packId = manifest.packId == null ? "" : manifest.packId;
        return MergeResult.ok(packId,
                sounds.size() + screens.size() + huds.size() + vehicles.size() + dialogs.size()
                        + armor3d.size() + objects.size() + textEffects.size() + icons.size() + vfx.size() + stingers.size()
                        + mobs.size());
    }

    /**
     * One armour set off the manifest, or null if there is nothing to wear.
     *
     * <p>A piece or a part it cannot read is left out and said, rather than
     * refusing the set: a set missing a pauldron is still a set, and the log
     * line is the only place anybody will find out why.
     */
    private static Armor3dSet armor3dOf(Armor3d set, Logger log) {
        if (set == null || set.id == null || !set.id.matches("[a-z0-9_.-]+")) {
            if (log != null && set != null) {
                log.warning("Skipped a 3D armour set with an unusable id: " + set.id);
            }
            return null;
        }
        Map<Armor3dSet.Piece, Armor3dSet.Worn> pieces = new EnumMap<>(Armor3dSet.Piece.class);
        for (Armor3dPiece piece : set.pieces == null ? List.<Armor3dPiece>of() : set.pieces) {
            if (piece == null || piece.item == null || piece.item.isEmpty()) {
                continue;
            }
            Armor3dSet.Piece which = Armor3dSet.Piece.of(piece.piece).orElse(null);
            if (which == null) {
                if (log != null) {
                    log.warning("3D armour set " + set.id + " has a piece called " + piece.piece
                            + ", which is not helmet, chestplate, leggings or boots.");
                }
                continue;
            }
            List<Armor3dSet.Part> parts = new ArrayList<>();
            for (Armor3dPart part : piece.parts == null ? List.<Armor3dPart>of() : piece.parts) {
                if (part == null || part.model == null || part.anchor == null || part.anchor.length != 3) {
                    continue;
                }
                parts.add(new Armor3dSet.Part(part.bone, part.model, part.anchor,
                        part.rotation != null && part.rotation.length == 3 ? part.rotation : null,
                        part.scale == null || part.scale <= 0 ? 1f : part.scale));
            }
            pieces.put(which, new Armor3dSet.Worn(which, piece.item, List.copyOf(parts),
                    piece.asset == null || piece.asset.isEmpty() ? null : piece.asset,
                    piece.bedrockSlot != null && piece.bedrockSlot > 0 ? piece.bedrockSlot : null));
        }
        List<int[]> ranges = new ArrayList<>();
        for (int[] range : set.shaderProtocols == null ? List.<int[]>of() : set.shaderProtocols) {
            if (range != null && range.length == 2) {
                ranges.add(range.clone());
            }
        }
        return pieces.isEmpty() ? null : new Armor3dSet(set.id, set.name, pieces, ranges);
    }

    /** A dialog's click sound as it arrived, or null when it is absent or not a sound. */
    private static ai.resourcepack.engine.api.DialogInfo.ClickSound clickSoundOf(DialogSound sound) {
        return sound == null || sound.sound == null ? null
                : ai.resourcepack.engine.api.DialogInfo.ClickSound.of(sound.sound, sound.volume, sound.pitch);
    }

    /** A click sound back out, in the shape it arrived in. */
    private static DialogSound soundOut(ai.resourcepack.engine.api.DialogInfo.ClickSound sound, String command) {
        DialogSound out = new DialogSound();
        out.sound = sound.sound();
        out.volume = sound.volume();
        out.pitch = sound.pitch();
        out.command = command;
        return out;
    }

    /** A set back out, in the shape it arrived in — a restart reads nothing else. */
    private static Armor3d armor3dOut(Armor3dSet set) {
        Armor3d out = new Armor3d();
        out.id = set.id();
        out.name = set.name();
        out.shaderProtocols = new ArrayList<>(set.shaderProtocols());
        out.pieces = new ArrayList<>();
        for (Armor3dSet.Worn worn : set.pieces().values()) {
            Armor3dPiece piece = new Armor3dPiece();
            piece.piece = worn.piece().wire();
            piece.item = worn.item();
            piece.asset = worn.asset();
            piece.bedrockSlot = worn.bedrockSlot();
            piece.parts = new ArrayList<>();
            for (Armor3dSet.Part part : worn.parts()) {
                Armor3dPart written = new Armor3dPart();
                written.bone = part.bone();
                written.model = part.model();
                written.anchor = part.anchor();
                written.rotation = part.rotation();
                written.scale = part.scale() == 1f ? null : part.scale();
                piece.parts.add(written);
            }
            out.pieces.add(piece);
        }
        return out;
    }

    /**
     * One vehicle off the manifest, or empty if it is not usable.
     *
     * <p><strong>Skipped rather than refused, and a vehicle with no driver
     * seat is skipped.</strong> That is the same rule the folder loader
     * enforces with a diagnostic — a vehicle nobody can steer is not a vehicle
     * — but there is nobody to show a diagnostic to here: this runs off a
     * websocket frame, and studio's own editor already refuses to call such a
     * model finished. Dropping it silently is the honest end of a check that
     * has already been made somewhere a person could see it.
     */
    private static java.util.Optional<VehicleInfo> vehicle(ContentId id, Vehicle vehicle) {
        if (vehicle.seats == null || vehicle.seats.isEmpty()) {
            return java.util.Optional.empty();
        }
        List<VehicleSeat> seats = new ArrayList<>();
        boolean driverTaken = false;
        for (Seat seat : vehicle.seats) {
            if (seat == null) {
                continue;
            }
            boolean driver = "driver".equalsIgnoreCase(seat.role) && !driverTaken;
            driverTaken |= driver;
            seats.add(VehicleSeat.of(
                    driver ? VehicleSeat.Role.DRIVER : VehicleSeat.Role.PASSENGER,
                    "standing".equalsIgnoreCase(seat.pose)
                            ? VehicleSeat.Pose.STANDING
                            : VehicleSeat.Pose.SITTING,
                    seat.x, seat.y, seat.z, (float) seat.yaw, seat.name,
                    seat.hidden != null && seat.hidden,
                    animations(seat.animations)));
        }
        if (!driverTaken) {
            return java.util.Optional.empty();
        }
        // The driver to the front, everything else keeping its order — the
        // same partition VehicleDefinitions does, and for the same reason:
        // seat order is the contract, and nothing downstream searches for the
        // driver.
        List<VehicleSeat> ordered = new ArrayList<>();
        for (VehicleSeat seat : seats) {
            if (seat.isDriver()) {
                ordered.add(seat);
            }
        }
        for (VehicleSeat seat : seats) {
            if (!seat.isDriver()) {
                ordered.add(seat);
            }
        }
        // A manifest from a studio that predates the hitbox has three zeroes
        // here, and zero is not a size — VehicleHitbox clamps it to its
        // minimum, which would be a sliver nobody can click. So an unset box
        // is the default block rather than a clamped nothing.
        VehicleHitbox hitbox = vehicle.hitboxWidth > 0 && vehicle.hitboxHeight > 0 && vehicle.hitboxLength > 0
                ? VehicleHitbox.of(vehicle.hitboxWidth, vehicle.hitboxHeight, vehicle.hitboxLength)
                : VehicleHitbox.DEFAULT;
        // Field by field rather than all-or-nothing, so a manifest that carries
        // some of them keeps the old behaviour for the rest.
        VehicleFlight legacy = VehicleFlight.forSpeed(vehicle.speed);
        VehicleFlight flight = VehicleFlight.of(
                vehicle.takeoffSpeed == null ? legacy.takeoffSpeed() : vehicle.takeoffSpeed,
                vehicle.climbRate == null ? legacy.climbRate() : vehicle.climbRate,
                vehicle.diveRate == null ? legacy.diveRate() : vehicle.diveRate,
                vehicle.stallSink == null ? legacy.stallSink() : vehicle.stallSink);
        return java.util.Optional.of(VehicleInfo.pushed(id, vehicle.carrier, vehicle.name,
                VehicleMedium.parse(vehicle.medium).orElse(VehicleMedium.LAND),
                vehicle.weight, vehicle.speed, vehicle.acceleration, vehicle.turnSpeed,
                hitbox, flight, List.copyOf(ordered),
                animations(vehicle.animations), emitters(vehicle.particles))
                .withScale(vehicle.scale == null ? 1 : vehicle.scale)
                .withJump(Boolean.TRUE.equals(vehicle.jump))
                .withTurnInPlace(Boolean.TRUE.equals(vehicle.turnInPlace))
                .withSounds(sounds(vehicle.sounds))
                // Absent is TRUE here, unlike everything else on this path: a
                // rider's cape is theirs, and a manifest that predates the
                // field is not a manifest asking for it to be taken off.
                .withCapes(!Boolean.FALSE.equals(vehicle.capes))
                .withAddons(addons(vehicle.addons)));
    }

    /**
     * One mob off the manifest, or empty if its type is not a mob this server
     * can spawn — said in the log, since a Studio that offers a behaviour this
     * Minecraft version lacks is the one way it happens.
     */
    private static java.util.Optional<EntityInfo> mob(ContentId id, Mob mob, Logger log) {
        String type = mob.type == null ? "" : mob.type.trim().toUpperCase(Locale.ROOT);
        boolean spawnable;
        try {
            org.bukkit.entity.EntityType found = org.bukkit.entity.EntityType.valueOf(type);
            spawnable = found.isSpawnable() && found.isAlive();
        } catch (IllegalArgumentException e) {
            spawnable = false;
        }
        if (!spawnable) {
            if (log != null) {
                log.warning("Pushed mob " + id.path() + " is a " + mob.type
                        + ", which is not a mob this server can spawn, so it was skipped.");
            }
            return java.util.Optional.empty();
        }
        Map<ai.resourcepack.engine.api.MobState, String> animations =
                new EnumMap<>(ai.resourcepack.engine.api.MobState.class);
        if (mob.animations != null) {
            mob.animations.forEach((state, animation) ->
                    ai.resourcepack.engine.api.MobState.parse(state).ifPresent(parsed -> {
                        if (animation != null && !animation.isEmpty()) {
                            animations.put(parsed, animation);
                        }
                    }));
        }
        // Qualified here, as a vehicle's are: the mob and its sounds arrive in
        // one push and land in one namespace.
        Map<String, String> sounds = new LinkedHashMap<>();
        if (mob.sounds != null) {
            mob.sounds.forEach((slot, sound) -> {
                if (slot != null && sound != null && !sound.isEmpty()) {
                    id(sound).ifPresent(qualified -> sounds.put(slot.toLowerCase(Locale.ROOT), qualified.toString()));
                }
            });
        }
        List<ai.resourcepack.engine.api.EntityDrop> drops = new ArrayList<>();
        for (MobDrop drop : mob.drops == null ? List.<MobDrop>of() : mob.drops) {
            if (drop == null || drop.item == null || drop.item.isEmpty()) {
                continue;
            }
            int min = drop.min == null ? 1 : drop.min;
            drops.add(ai.resourcepack.engine.api.EntityDrop.of(drop.item, min,
                    drop.max == null ? min : drop.max, drop.chance == null ? 1 : drop.chance));
        }
        return java.util.Optional.of(EntityInfo.builder(id, type)
                .carrier(mob.model)
                .name(mob.name)
                .nameVisible(Boolean.TRUE.equals(mob.nameVisible))
                .ai(!Boolean.FALSE.equals(mob.ai))
                .burns(Boolean.TRUE.equals(mob.burns))
                .health(mob.health == null ? 0 : mob.health)
                .damage(mob.damage == null ? 0 : mob.damage)
                .speed(mob.speed == null ? 1 : mob.speed)
                .knockbackResistance(mob.knockbackResistance == null ? 0 : mob.knockbackResistance)
                .scale(mob.scale == null ? 1f : mob.scale.floatValue())
                .height(mob.height == null ? 0 : mob.height)
                .turn(mob.turn == null ? 0f : mob.turn.floatValue())
                .headBone(mob.headBone)
                .animations(animations)
                .sounds(sounds)
                .drops(drops)
                .xp(mob.xp == null ? 0 : mob.xp)
                .hurtFlash(!Boolean.FALSE.equals(mob.hurtFlash))
                .followRange(mob.followRange == null ? 0 : mob.followRange)
                .glowing(Boolean.TRUE.equals(mob.glowing))
                .fireImmune(Boolean.TRUE.equals(mob.fireImmune))
                .bossBar(mob.bossBar == null ? null : EntityInfo.BossBar.of(mob.bossBar.color, mob.bossBar.style,
                        mob.bossBar.range == null ? 0 : mob.bossBar.range))
                .ranged(mob.ranged == null ? null : EntityInfo.Ranged.of(mob.ranged.projectile,
                        mob.ranged.damage == null ? 0 : mob.ranged.damage,
                        mob.ranged.cooldown == null ? 0 : mob.ranged.cooldown,
                        mob.ranged.range == null ? 0 : mob.ranged.range))
                .spawning(spawning(mob.spawning))
                .build());
    }

    private static EntityInfo.Spawning spawning(MobSpawning rules) {
        if (rules == null) {
            return null;
        }
        int minGroup = rules.minGroup == null ? 1 : rules.minGroup;
        return EntityInfo.Spawning.of(rules.worlds, rules.biomes, rules.replaces,
                rules.chance == null ? 0.1 : rules.chance,
                rules.minLight == null ? 0 : rules.minLight, rules.maxLight == null ? 15 : rules.maxLight,
                rules.time, minGroup, rules.maxGroup == null ? minGroup : rules.maxGroup,
                rules.maxPerChunk == null ? 2 : rules.maxPerChunk);
    }

    /** A mob back out, in the shape it arrived in — a restart reads nothing else. */
    private static Mob mobOut(EntityInfo info) {
        Mob out = new Mob();
        out.id = info.id().path();
        out.model = info.carrier().orElse("");
        out.name = info.name().orElse("");
        out.nameVisible = info.nameVisible();
        out.type = info.type();
        out.ai = info.ai();
        out.burns = info.burns();
        out.health = info.health();
        out.damage = info.damage();
        out.speed = info.speed();
        out.knockbackResistance = info.knockbackResistance();
        out.scale = (double) info.scale();
        out.height = info.height();
        out.turn = (double) info.turn();
        out.headBone = info.headBone().orElse(null);
        out.animations = new LinkedHashMap<>();
        info.animations().forEach((state, animation) -> out.animations.put(state.key(), animation));
        out.sounds = new LinkedHashMap<>();
        // Back to the bare id, or a re-read looks for studio:studio:grunt.
        info.sounds().forEach((slot, sound) -> out.sounds.put(slot,
                ContentId.parse(sound).map(ContentId::path).orElse(sound)));
        out.drops = new ArrayList<>();
        for (ai.resourcepack.engine.api.EntityDrop drop : info.drops()) {
            MobDrop written = new MobDrop();
            written.item = drop.item();
            written.min = drop.min();
            written.max = drop.max();
            written.chance = drop.chance();
            out.drops.add(written);
        }
        out.xp = info.xp();
        out.hurtFlash = info.hurtFlash();
        out.followRange = info.followRange();
        out.glowing = info.glowing();
        out.fireImmune = info.fireImmune();
        info.bossBar().ifPresent(bar -> {
            out.bossBar = new MobBossBar();
            out.bossBar.color = bar.color();
            out.bossBar.style = bar.style();
            out.bossBar.range = bar.range();
        });
        info.ranged().ifPresent(shot -> {
            out.ranged = new MobRanged();
            out.ranged.projectile = shot.projectile();
            out.ranged.damage = shot.damage();
            out.ranged.cooldown = shot.cooldown();
            out.ranged.range = shot.range();
        });
        info.spawning().ifPresent(rules -> {
            out.spawning = new MobSpawning();
            out.spawning.worlds = new ArrayList<>(rules.worlds());
            out.spawning.biomes = new ArrayList<>(rules.biomes());
            out.spawning.replaces = new ArrayList<>(rules.replaces());
            out.spawning.chance = rules.chance();
            out.spawning.minLight = rules.minLight();
            out.spawning.maxLight = rules.maxLight();
            out.spawning.time = rules.time();
            out.spawning.minGroup = rules.minGroup();
            out.spawning.maxGroup = rules.maxGroup();
            out.spawning.maxPerChunk = rules.maxPerChunk();
        });
        return out;
    }

    private static Map<String, DefinitionNode> addons(Map<String, Map<String, Object>> declared) {
        if (declared == null || declared.isEmpty()) {
            return Map.of();
        }
        Map<String, DefinitionNode> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : declared.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                out.put(entry.getKey(), DefinitionNode.of(entry.getValue()));
            }
        }
        return out;
    }

    /**
     * A pushed vehicle's state-to-animation map.
     *
     * <p>An unreadable state is skipped in silence, for the reason the class
     * note gives about a vehicle with no driver seat: there is nobody here to
     * show a diagnostic to, this runs off a websocket frame, and studio's
     * editor only ever writes names out of a fixed list. A state name this
     * build does not know is a studio newer than this jar, and dropping it is
     * the right end of that — the vehicle still works, minus one animation.
     */
    private static Map<VehicleState, String> animations(Map<String, String> declared) {
        if (declared == null || declared.isEmpty()) {
            return Map.of();
        }
        Map<VehicleState, String> animations = new EnumMap<>(VehicleState.class);
        for (Map.Entry<String, String> entry : declared.entrySet()) {
            String animation = entry.getValue();
            if (animation == null || animation.isEmpty()) {
                continue;
            }
            VehicleState.parse(entry.getKey()).ifPresent(state -> animations.put(state, animation));
        }
        return animations;
    }

    /**
     * A pushed vehicle's state-to-sound map, in this end's id space.
     *
     * <p><strong>The manifest names a sound by its own id and this is where
     * that becomes {@code studio:<id>}.</strong> The vehicle and the sound
     * arrive in the same push and land in the same namespace, so the manifest
     * has no reason to repeat it on every row — and a value that arrived
     * already qualified would be a second spelling of one thing.
     */
    private static Map<VehicleState, String> sounds(Map<String, String> declared) {
        if (declared == null || declared.isEmpty()) {
            return Map.of();
        }
        Map<VehicleState, String> sounds = new EnumMap<>(VehicleState.class);
        for (Map.Entry<String, String> entry : declared.entrySet()) {
            String sound = entry.getValue();
            if (sound == null || sound.isEmpty()) {
                continue;
            }
            id(sound).ifPresent(qualified ->
                    VehicleState.parse(entry.getKey())
                            .ifPresent(state -> sounds.put(state, qualified.toString())));
        }
        return sounds;
    }

    /** A pushed vehicle's particle emitters, skipping any that could never fire. */
    private static List<VehicleEmitter> emitters(List<Emitter> declared) {
        if (declared == null || declared.isEmpty()) {
            return List.of();
        }
        List<VehicleEmitter> emitters = new ArrayList<>();
        for (Emitter emitter : declared) {
            if (emitter == null || emitter.effect == null || emitter.effect.isEmpty()) {
                continue;
            }
            Set<VehicleState> states = EnumSet.noneOf(VehicleState.class);
            for (String raw : emitter.states == null ? List.<String>of() : emitter.states) {
                VehicleState.parse(raw).ifPresent(states::add);
            }
            if (states.isEmpty()) {
                continue;
            }
            emitters.add(VehicleEmitter.of(
                    emitter.effect, states, emitter.x, emitter.y, emitter.z,
                    emitter.count, emitter.spread, emitter.speed, emitter.interval,
                    emitter.enabled == null || emitter.enabled,
                    emitter.color == null ? VehicleEmitter.NO_COLOR : emitter.color,
                    emitter.size == null ? 1 : emitter.size));
        }
        return emitters;
    }

    /**
     * Puts the ids into the registry, replacing whatever was there.
     *
     * <p>Called after a push and again after every reload, because a reload
     * clears the registry and rebuilds it from the content folder — which
     * knows nothing about a pack somebody is wearing.
     */
    public void register(ContentRegistration registration, Logger log) {
        release();
        if (registration == null || isEmpty()) {
            return;
        }
        claimed = registration.claim(NAMESPACE, ContentSource.STUDIO).namespace().orElse(null);
        if (claimed == null) {
            // Only if a content folder is called "studio", which is a name
            // clash a server owner can fix in one rename.
            log.warning("The namespace " + NAMESPACE + " is taken, so a pushed pack's "
                    + "sounds and screens cannot be named. Rename that content folder.");
            return;
        }
        for (ContentId id : sounds.keySet()) {
            claimed.define(ContentKind.SOUND, id.path());
        }
        for (ContentId id : screens.keySet()) {
            claimed.define(ContentKind.SCREEN, id.path());
        }
        for (ContentId id : huds.keySet()) {
            claimed.define(ContentKind.HUD, id.path());
        }
        for (ContentId id : vehicles.keySet()) {
            claimed.define(ContentKind.VEHICLE, id.path());
        }
        for (ContentId id : dialogs.keySet()) {
            claimed.define(ContentKind.DIALOG, id.path());
        }
        for (ContentId id : textEffects.keySet()) {
            claimed.define(ContentKind.TEXT_EFFECT, id.path());
        }
        for (ContentId id : icons.keySet()) {
            claimed.define(ContentKind.FONT, id.path());
        }
        for (ContentId id : vfx.keySet()) {
            claimed.define(ContentKind.VFX, id.path());
        }
        // Mobs are deliberately not defined: entities are outside the id
        // space (see ContentFolderLoader.UNREGISTERED), so a mob may share its
        // id with the vehicle or model it is built from.
    }

    /** Drops the namespace, if it is held. */
    public void release() {
        if (claimed != null) {
            claimed.release();
            claimed = null;
        }
    }

    /** {@link #load(Logger, boolean)} on a server that may trust a push. */
    public void load(Logger log) {
        load(log, true);
    }

    /**
     * Reads what was saved. A missing file is an empty pack, not a problem.
     *
     * @param trusted whether this server trusts a push at all; even then the
     *                file is read as trusted only when it says the push it
     *                holds was, so a file written before that was recorded is
     *                read as a stranger's
     */
    public void load(Logger log, boolean trusted) {
        if (file == null || !file.isFile()) {
            return;
        }
        try {
            String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            boolean kept = false;
            try {
                Manifest saved = gson.fromJson(json, Manifest.class);
                kept = saved != null && Boolean.TRUE.equals(saved.commandsKept);
            } catch (RuntimeException unreadable) {
                // updateFromJson says why.
            }
            MergeResult result = updateFromJson(json, log, trusted && kept);
            if (result.ok()) {
                if (result.count() > 0) {
                    log.info("Loaded " + result.count() + " pushed asset(s) from " + file.getName());
                }
            } else {
                log.warning(file.getName() + " could not be read: " + result.error());
            }
        } catch (IOException e) {
            log.warning(file.getName() + " could not be read: " + e.getMessage());
        }
    }

    /**
     * Writes it back out, in the shape it arrived in.
     *
     * <p>An empty store deletes the file rather than writing an empty one. A
     * server that has never taken a push should not find a file suggesting it
     * has, and the next push writes it again.
     */
    public void save(Logger log) {
        if (file == null) {
            return;
        }
        if (isEmpty()) {
            try {
                Files.deleteIfExists(file.toPath());
            } catch (IOException e) {
                log.warning("Could not remove " + file.getName() + ": " + e.getMessage());
            }
            return;
        }
        Manifest manifest = new Manifest();
        manifest.packId = packId;
        manifest.commandsKept = commandsKept ? Boolean.TRUE : null;
        manifest.sounds = new ArrayList<>();
        for (Map.Entry<ContentId, SoundInfo> entry : sounds.entrySet()) {
            Sound sound = new Sound();
            sound.id = entry.getKey().path();
            sound.event = entry.getValue().event();
            sound.category = entry.getValue().category();
            // Kept across a restart, like everything else here: a vehicle that
            // loops an engine note needs the length, and re-reading it is not
            // possible from this side — see Sound.durationSeconds.
            if (entry.getValue().length() > 0) {
                sound.durationSeconds = entry.getValue().length();
            }
            manifest.sounds.add(sound);
        }
        manifest.screens = new ArrayList<>();
        for (Map.Entry<ContentId, OverlayInfo> entry : screens.entrySet()) {
            Overlay written = overlay(entry.getKey(), entry.getValue(), true);
            // Heads come home too, or a restart quietly turns every face back into Steve.
            ai.resourcepack.engine.core.font.ScreenHeads heads = screenHeads.get(entry.getKey());
            if (heads != null) {
                written.advance = heads.advance();
                written.shiftPlus = heads.shiftPlus();
                written.shiftMinus = heads.shiftMinus();
                written.heads = new ArrayList<>();
                for (ai.resourcepack.engine.core.font.ScreenHeads.Head head : heads.heads()) {
                    Head h = new Head();
                    h.x = head.x();
                    h.size = head.size();
                    h.hat = head.hat();
                    h.rows = new ArrayList<>(head.rows());
                    written.heads.add(h);
                }
            }
            manifest.screens.add(written);
        }
        manifest.huds = new ArrayList<>();
        for (Map.Entry<ContentId, OverlayInfo> entry : huds.entrySet()) {
            manifest.huds.add(overlay(entry.getKey(), entry.getValue(), false));
        }
        manifest.vehicles = new ArrayList<>();
        for (Map.Entry<ContentId, VehicleInfo> entry : vehicles.entrySet()) {
            manifest.vehicles.add(vehicleOf(entry.getKey(), entry.getValue()));
        }
        // Only the exceptions, which is the shape they arrived in. Round
        // tripping matters here for the same reason it does for a vehicle's
        // seats: this file is what a restart reads back, and dropping it would
        // put a wall in front of every car at a rug somebody had already said
        // to drive over.
        manifest.models = new ArrayList<>();
        Set<String> modelIds = new java.util.LinkedHashSet<>(vehiclePassable);
        modelIds.addAll(variations.keySet());
        modelIds.addAll(works.keySet());
        for (String id : modelIds) {
            Model model = new Model();
            model.id = id;
            model.works = works.get(id);
            if (vehiclePassable.contains(id)) {
                model.vehicleCollision = Boolean.FALSE;
            }
            ai.resourcepack.engine.api.ModelInfo.Variation variation = variations.get(id);
            if (variation != null) {
                PlaceVariation written = new PlaceVariation();
                written.rotation = variation.rotation();
                written.rotationStep = variation.rotationStep();
                written.scaleMin = variation.scaleMin();
                written.scaleMax = variation.scaleMax();
                model.variation = written;
            }
            manifest.models.add(model);
        }
        // The one entry on this list a restart cannot do without, and it was
        // the only one missing. A dialog is registry data the server reads
        // when it LOADS THE WORLD — before any plugin starts and long before a
        // push can arrive — so the file a push writes is read by the next
        // startup or by nothing. Dropping the dialogs here meant that startup
        // found them, built its registry, and then watched this plugin enable
        // with an empty catalogue and delete the very files it had just read:
        // one more restart and the dialogs were gone, with nothing in the log
        // and a /rp dialog answering "no dialog called that" about an id the
        // running server's registry was holding.
        manifest.dialogs = new ArrayList<>();
        for (Map.Entry<ContentId, ai.resourcepack.engine.api.DialogInfo> entry : dialogs.entrySet()) {
            Dialog dialog = new Dialog();
            dialog.id = entry.getKey().path();
            dialog.name = entry.getValue().name();
            // Back to a tree so gson writes the object rather than a string
            // holding one. Still unread: parsing is not looking.
            dialog.json = com.google.gson.JsonParser.parseString(entry.getValue().json());
            // Everything else it arrived with, too. The variables and the item
            // icons were left out here for as long as they existed, so after a
            // restart /rp var refused every bound switch of a pushed dialog (it
            // declared nothing) and the pack's own items in live slots drew as
            // the question mark, until the next push.
            if (!entry.getValue().variables().isEmpty()) {
                dialog.vars = new ArrayList<>();
                entry.getValue().variables().forEach((name, values) -> {
                    DialogVariable v = new DialogVariable();
                    v.name = name;
                    v.values = new ArrayList<>(values);
                    dialog.vars.add(v);
                });
            }
            if (!entry.getValue().itemIcons().isEmpty()) {
                dialog.itemIcons = new LinkedHashMap<>(entry.getValue().itemIcons());
            }
            dialog.command = entry.getValue().command().orElse(null);
            dialog.permission = entry.getValue().permission().orElse(null);
            dialog.clickSound = entry.getValue().clickSound().map(sound -> soundOut(sound, null)).orElse(null);
            if (!entry.getValue().clickSounds().isEmpty()) {
                dialog.clickSounds = new ArrayList<>();
                for (ai.resourcepack.engine.api.DialogInfo.CommandSound sound : entry.getValue().clickSounds()) {
                    dialog.clickSounds.add(soundOut(sound.sound(), sound.command()));
                }
            }
            // Or after a restart every "Run as console" button does nothing.
            if (!entry.getValue().consoleCommands().isEmpty()) {
                dialog.consoleCommands = new ArrayList<>();
                entry.getValue().consoleCommands().forEach((key, command) -> {
                    DialogConsole console = new DialogConsole();
                    console.key = key;
                    console.command = command;
                    dialog.consoleCommands.add(console);
                });
            }
            manifest.dialogs.add(dialog);
        }
        manifest.armor3d = new ArrayList<>();
        for (Armor3dSet set : armor3d.values()) {
            manifest.armor3d.add(armor3dOut(set));
        }
        manifest.objects = new ArrayList<>();
        for (ai.resourcepack.engine.api.ShaderObjectInfo info : objects.values()) {
            ShaderObject object = new ShaderObject();
            object.id = info.id().path();
            object.model = info.model();
            object.size = info.size();
            object.states = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : info.states().entrySet()) {
                ShaderObjectState state = new ShaderObjectState();
                state.name = entry.getKey();
                state.value = entry.getValue();
                object.states.add(state);
            }
            manifest.objects.add(object);
        }
        manifest.textEffects = new ArrayList<>();
        for (ai.resourcepack.engine.api.TextEffectInfo info : textEffects.values()) {
            TextEffect effect = new TextEffect();
            effect.id = info.id().path();
            effect.name = info.name();
            effect.color = info.color();
            manifest.textEffects.add(effect);
        }
        manifest.icons = new ArrayList<>();
        for (ai.resourcepack.engine.api.IconInfo info : icons.values()) {
            Icon icon = new Icon();
            icon.id = info.id().path();
            icon.name = info.id().path();
            icon.codepoint = info.codepoint();
            icon.height = info.height();
            icon.ascent = info.ascent();
            manifest.icons.add(icon);
        }
        manifest.vfx = new ArrayList<>();
        for (ai.resourcepack.engine.core.vfx.VfxEntry entry : vfx.values()) {
            Vfx effect = new Vfx();
            effect.id = entry.info().id().path();
            effect.name = entry.info().name();
            effect.recipe = entry.recipe().toJson();
            manifest.vfx.add(effect);
        }
        manifest.stingers = new ArrayList<>();
        for (ai.resourcepack.engine.core.transition.TransitionEntry entry : stingers.values()) {
            Stinger out = new Stinger();
            ai.resourcepack.engine.api.TransitionInfo info = entry.info();
            out.id = info.id().path();
            out.name = info.name();
            out.glyph = entry.glyph();
            out.in = entry.inColor();
            out.out = entry.outColor();
            out.auto = entry.autoColor();
            out.inTicks = info.inTicks();
            out.holdTicks = info.holdTicks();
            out.outTicks = info.outTicks();
            out.soundIn = info.soundIn().orElse(null);
            out.soundOut = info.soundOut().orElse(null);
            manifest.stingers.add(out);
        }
        manifest.mobs = new ArrayList<>();
        for (EntityInfo info : mobs.values()) {
            Mob out = mobOut(info);
            PushedMobSkills skills = mobSkills.get(info.id());
            if (skills != null) {
                skills.writeTo(out);
            }
            manifest.mobs.add(out);
        }
        try {
            Files.createDirectories(file.getParentFile().toPath());
            Files.write(file.toPath(), gson.toJson(manifest).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warning("Could not write " + file.getName() + ": " + e.getMessage());
        }
    }

    /**
     * A vehicle back out, in the shape it arrived in.
     *
     * <p>Round-tripping matters here more than for the others: this is what is
     * written to {@code studio-content.json}, and a vehicle somebody parked
     * before a restart has to come back with the same seats in the same order
     * or the people who get into it end up somewhere else.
     */
    private static Vehicle vehicleOf(ContentId id, VehicleInfo info) {
        Vehicle out = new Vehicle();
        out.id = id.path();
        out.carrier = info.carrier().orElse("");
        out.name = info.name().orElse("");
        out.medium = info.medium().key();
        out.weight = info.weight();
        out.speed = info.speed();
        out.acceleration = info.acceleration();
        out.turnSpeed = info.turnSpeed();
        out.jump = info.jumps() ? Boolean.TRUE : null;
        out.turnInPlace = info.turnInPlace();
        out.capes = info.capes();
        out.hitboxWidth = info.hitbox().width();
        out.hitboxHeight = info.hitbox().height();
        out.hitboxLength = info.hitbox().length();
        // The size and the flight numbers were both dropped here, which is a
        // round trip that quietly resizes somebody's airship and grounds their
        // aeroplane: this file is what a restart reads back, so anything the
        // reader honours has to be written or the vehicle changes shape the
        // first time the server is bounced. Written unconditionally rather
        // than only when they differ from the default, because the reader
        // treats absent as "a manifest older than the field" and there is no
        // reason to keep producing one.
        out.scale = info.scale();
        if (info.medium() == VehicleMedium.AIR) {
            out.takeoffSpeed = info.flight().takeoffSpeed();
            out.climbRate = info.flight().climbRate();
            out.diveRate = info.flight().diveRate();
            out.stallSink = info.flight().stallSink();
        }
        out.seats = new ArrayList<>();
        for (VehicleSeat seat : info.seats()) {
            Seat written = new Seat();
            written.role = seat.role().key();
            written.pose = seat.pose().key();
            written.x = seat.x();
            written.y = seat.y();
            written.z = seat.z();
            written.yaw = seat.yaw();
            written.name = seat.name().orElse("");
            if (!seat.animations().isEmpty()) {
                written.animations = new LinkedHashMap<>();
                for (Map.Entry<VehicleState, String> entry : seat.animations().entrySet()) {
                    written.animations.put(entry.getKey().key(), entry.getValue());
                }
            }
            out.seats.add(written);
        }
        if (!info.animations().isEmpty()) {
            out.animations = new LinkedHashMap<>();
            for (Map.Entry<VehicleState, String> entry : info.animations().entrySet()) {
                out.animations.put(entry.getKey().key(), entry.getValue());
            }
        }
        if (!info.sounds().isEmpty()) {
            out.sounds = new LinkedHashMap<>();
            for (Map.Entry<VehicleState, String> entry : info.sounds().entrySet()) {
                // Back to the bare id the manifest carries — `sounds()` above
                // qualified it on the way in, and writing the qualified form
                // would leave a re-read looking for `studio:studio:engine`.
                ContentId sound = ContentId.parse(entry.getValue()).orElse(null);
                out.sounds.put(entry.getKey().key(),
                        sound == null ? entry.getValue() : sound.path());
            }
        }
        if (!info.emitters().isEmpty()) {
            out.particles = new ArrayList<>();
            for (VehicleEmitter emitter : info.emitters()) {
                Emitter written = new Emitter();
                written.effect = emitter.effect();
                written.states = new ArrayList<>();
                for (VehicleState state : emitter.states()) {
                    written.states.add(state.key());
                }
                written.x = emitter.x();
                written.y = emitter.y();
                written.z = emitter.z();
                written.count = emitter.count();
                written.spread = emitter.spread();
                written.speed = emitter.speed();
                written.interval = emitter.interval();
                written.enabled = emitter.enabled();
                written.color = emitter.color().orElse(null);
                written.size = emitter.size();
                out.particles.add(written);
            }
        }
        if (!info.addons().isEmpty()) {
            out.addons = new LinkedHashMap<>();
            for (Map.Entry<String, DefinitionNode> entry : info.addons().entrySet()) {
                out.addons.put(entry.getKey(), entry.getValue().values());
            }
        }
        return out;
    }

    /** A screen's heads off the wire, or null when it has none it could draw. */
    private static ai.resourcepack.engine.core.font.ScreenHeads headsOf(Overlay screen) {
        if (screen.heads == null || screen.heads.isEmpty() || screen.shiftPlus == null || screen.shiftMinus == null) {
            return null;
        }
        List<ai.resourcepack.engine.core.font.ScreenHeads.Head> heads = new ArrayList<>();
        for (Head head : screen.heads) {
            if (head == null || head.rows == null || head.rows.size() != 8 || head.size < 1 || head.size > 16) {
                continue;
            }
            heads.add(new ai.resourcepack.engine.core.font.ScreenHeads.Head(head.x, head.size, head.hat, head.rows));
        }
        if (heads.isEmpty()) {
            return null;
        }
        return new ai.resourcepack.engine.core.font.ScreenHeads(screen.advance, screen.shiftPlus, screen.shiftMinus, heads);
    }

    private static Overlay overlay(ContentId id, OverlayInfo info, boolean screen) {
        Overlay out = new Overlay();
        out.id = id.path();
        out.title = info.title();
        out.container = screen ? info.container() : "";
        out.slot = screen ? "" : info.slot().name().toLowerCase(Locale.ROOT);
        // Written back as null when empty rather than as "": this file is
        // re-read by the constructor above, and a shader overlay that came home
        // from disk without its colour is one that draws nothing at all after a
        // restart. Null and "" both mean white on the way in, so the round trip
        // is lossless either way — but null is what an older manifest holds,
        // and keeping the two spellings identical is what stops a future reader
        // having to know the difference.
        out.color = info.color().isEmpty() ? null : info.color();
        out.font = info.font().isEmpty() ? null : info.font();
        out.text = info.text().isEmpty() ? null : info.text();
        if (!info.runs().isEmpty()) {
            out.runs = new ArrayList<>();
            for (OverlayInfo.OverlayRun run : info.runs()) {
                Run written = new Run();
                written.shift = run.shift();
                written.x = run.x();
                written.align = run.align();
                written.text = run.text();
                written.font = run.font();
                written.color = run.color();
                // Zero is "measure it", which is both the default and what an
                // absent field deserialises to — so a run of words round-trips
                // through disk unchanged and a head keeps its exact width.
                written.advance = run.advance();
                written.players = run.players().isEmpty() ? null : new java.util.LinkedHashMap<>(run.players());
                written.bar = written(run.bar());
                // Null when unset, so a run using none of this writes the JSON it arrived as.
                written.when = run.when().isEmpty() ? null : run.when();
                written.state = run.state().isEmpty() ? null : run.state();
                if (!run.states().isEmpty()) {
                    written.states = new ArrayList<>();
                    for (OverlayInfo.OverlayRun.State state : run.states()) {
                        RunState one = new RunState();
                        one.name = state.name();
                        one.color = state.color();
                        written.states.add(one);
                    }
                }
                out.runs.add(written);
            }
            // Written back beside the runs, because without them the runs come
            // home from disk unpositionable — every one after the first would
            // land a string-width to the right of where it did before the
            // restart, which is the kind of thing nobody connects to a restart.
            out.advance = info.advance();
            out.shiftPlus = info.shiftPlus().isEmpty() ? null : info.shiftPlus();
            out.shiftMinus = info.shiftMinus().isEmpty() ? null : info.shiftMinus();
        }
        if (!info.triggers().isEmpty()) {
            out.triggers = new ArrayList<>();
            for (OverlayTrigger trigger : info.triggers()) {
                Trigger written = new Trigger();
                written.kind = trigger.kind().wire();
                written.item = trigger.item().isEmpty() ? null : trigger.item();
                written.permission = trigger.permission().isEmpty() ? null : trigger.permission();
                out.triggers.add(written);
            }
        }
        return out;
    }

    /** The positioned text runs a manifest carried. Nulls are skipped. */
    /**
     * A HUD overlay Studio drew, read from a CONTENT FOLDER rather than a push.
     *
     * <p>The JSON is one entry of a push manifest's {@code huds} list, which is
     * what Studio's content export writes beside the linked shader and the
     * glyphs ({@code huds/<id>.yml} names it with {@code json:}). Reading it
     * with the push's own model is the point: one shape, one reader, and an
     * exported overlay cannot drift from a pushed one. Empty when the file is
     * not an overlay.
     */
    public static java.util.Optional<OverlayInfo> authoredHud(ContentId id, String json) {
        Overlay hud;
        try {
            hud = new Gson().fromJson(json, Overlay.class);
        } catch (RuntimeException e) {
            return java.util.Optional.empty();
        }
        if (hud == null || hud.title == null || hud.title.isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(OverlayInfo.pushed(id, hud.title, "", slotOf(hud.slot),
                        hud.color, hud.font, hud.text, triggers(hud.triggers), runs(hud.runs))
                .withCursor(hud.advance, hud.shiftPlus, hud.shiftMinus)
                .authored());
    }

    private static List<OverlayInfo.OverlayRun> runs(List<Run> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<OverlayInfo.OverlayRun> out = new ArrayList<>();
        for (Run run : raw) {
            // A bar carries no text of its own — the engine assembles it — so
            // "nothing to draw" cannot be decided by looking at the string here.
            if (run == null || ((run.text == null || run.text.isEmpty()) && run.bar == null)) {
                continue;
            }
            List<OverlayInfo.OverlayRun.State> states = new ArrayList<>();
            for (RunState state : run.states == null ? List.<RunState>of() : run.states) {
                // A state naming no mark would draw in whatever #f0xxxx looks
                // like, the same reason a bar's colourless threshold is dropped.
                if (state != null && state.name != null && !state.name.isEmpty()
                        && state.color != null && !state.color.isEmpty()) {
                    states.add(new OverlayInfo.OverlayRun.State(state.name, state.color));
                }
            }
            out.add(new OverlayInfo.OverlayRun(run.shift, run.x, run.text, run.font, run.color,
                    run.advance, run.players == null ? Map.of() : Map.copyOf(run.players), bar(run.bar),
                    run.align).live(run.when, states, run.state));
        }
        return List.copyOf(out);
    }

    /** A bar spec, both ways round. Null passes straight through. */
    private static OverlayInfo.OverlayRun.Bar bar(Bar raw) {
        if (raw == null) {
            return null;
        }
        List<OverlayInfo.OverlayRun.Bar.Glyph> glyphs = new ArrayList<>();
        for (BarGlyph glyph : raw.glyphs == null ? List.<BarGlyph>of() : raw.glyphs) {
            if (glyph != null && glyph.character != null && !glyph.character.isEmpty() && glyph.px > 0) {
                glyphs.add(new OverlayInfo.OverlayRun.Bar.Glyph(glyph.character, glyph.px));
            }
        }
        List<OverlayInfo.OverlayRun.Bar.Shade> colors = new ArrayList<>();
        for (BarShade shade : raw.colors == null ? List.<BarShade>of() : raw.colors) {
            // A threshold naming no colour is one the engine would draw in the
            // mark itself — a bar in whatever #f0xxxx happens to look like —
            // so it is dropped rather than passed through.
            if (shade != null && shade.color != null && !shade.color.isEmpty()) {
                colors.add(new OverlayInfo.OverlayRun.Bar.Shade(shade.at, shade.color, shade.atFrom));
            }
        }
        return new OverlayInfo.OverlayRun.Bar(glyphs, raw.total, raw.value, raw.max, raw.fill,
                raw.segments, raw.gap, colors).withLength(raw.length);
    }

    /** The same, on the way back out to disk. */
    private static Bar written(OverlayInfo.OverlayRun.Bar from) {
        if (from == null) {
            return null;
        }
        Bar out = new Bar();
        out.glyphs = new ArrayList<>();
        for (OverlayInfo.OverlayRun.Bar.Glyph glyph : from.glyphs()) {
            BarGlyph one = new BarGlyph();
            one.character = glyph.character();
            one.px = glyph.px();
            out.glyphs.add(one);
        }
        out.total = from.total();
        out.length = from.length().isEmpty() ? null : from.length();
        out.value = from.value();
        out.max = from.max();
        out.fill = from.fill();
        out.segments = from.segments();
        out.gap = from.gap();
        // Null rather than an empty list, so a bar of one colour round-trips to
        // the same JSON it arrived as instead of growing an empty array.
        out.colors = from.colors().isEmpty() ? null : new ArrayList<>();
        for (OverlayInfo.OverlayRun.Bar.Shade shade : from.colors()) {
            BarShade one = new BarShade();
            one.at = shade.at();
            one.color = shade.color();
            one.atFrom = shade.atFrom().isEmpty() ? null : shade.atFrom();
            out.colors.add(one);
        }
        return out;
    }

    /**
     * The triggers a manifest named, skipping any this engine does not have.
     *
     * <p>Skipped rather than refused: a newer Studio naming a kind this jar has
     * never heard of should cost that one rule, not the whole overlay. The
     * server owner sees an overlay that does not show itself, which is
     * recoverable; an overlay that failed to load at all is not.
     */
    private static List<OverlayTrigger> triggers(List<Trigger> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<OverlayTrigger> out = new ArrayList<>();
        for (Trigger trigger : raw) {
            if (trigger == null) {
                continue;
            }
            OverlayTrigger.Kind.of(trigger.kind).ifPresent(kind -> {
                // A HOLDING rule with no item would fire constantly. Studio
                // drops one before sending; this is the other end of that.
                if (kind == OverlayTrigger.Kind.HOLDING
                        && (trigger.item == null || trigger.item.isBlank())) {
                    return;
                }
                out.add(OverlayTrigger.of(kind, trigger.item, trigger.permission));
            });
        }
        return List.copyOf(out);
    }

    /** A manifest id, in our namespace. Anything unusable is skipped. */
    private static java.util.Optional<ContentId> id(String path) {
        return path == null ? java.util.Optional.empty() : ContentId.parse(NAMESPACE + ":" + path);
    }

    /** The same, reported rather than dropped in silence. See {@link #updateFromJson(String, Logger)}. */
    private static java.util.Optional<ContentId> id(String path, Logger log, String what) {
        java.util.Optional<ContentId> parsed = id(path);
        if (parsed.isEmpty() && log != null) {
            log.warning("A pushed " + what + " is named \"" + path + "\", which is not usable as a content id "
                    + "(a-z, 0-9, _, . and - only), so it was skipped. Whatever pushed this pack is sending a "
                    + "display name where an id belongs.");
        }
        return parsed;
    }

    private static OverlayInfo.Slot slotOf(String slot) {
        return slot != null && slot.toLowerCase(Locale.ROOT).startsWith("boss")
                ? OverlayInfo.Slot.BOSS_BAR
                : OverlayInfo.Slot.ACTION_BAR;
    }
}
