package ai.resourcepack.engine.core.sync;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.ContentRegistration;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.DefinitionNode;
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

    /** The manifest's shape. Written by Studio's content-manifest writer. */
    static final class Manifest {
        String packId;
        List<Sound> sounds;
        List<Overlay> screens;
        List<Overlay> huds;
        List<Vehicle> vehicles;
        List<Model> models;
        List<Dialog> dialogs;
        List<Armor3d> armor3d;
    }

    /**
     * A 3D armour set: which item each piece is, and the displays it puts on
     * the body.
     *
     * <p>Here for the same reason a vehicle is. The art is in the zip — one
     * block model per display and one per piece, dispatched off paper by
     * string — but a zip has no way to say that a model is the left pauldron
     * of a set, that it rides the left arm, or where on the arm it sits. See
     * {@link Armor3dSet} for why the helmet has no parts.
     */
    static final class Armor3d {
        String id;
        String name;
        List<Armor3dPiece> pieces;
        /**
         * Inclusive protocol ranges whose clients draw the set themselves,
         * through the pack's shader overlay — see {@link Armor3dSet#drawnBy}.
         * Absent on a manifest older than the overlay, which is nobody.
         */
        List<int[]> shaderProtocols;
    }

    static final class Armor3dPiece {
        /** {@code helmet}, {@code chestplate}, {@code leggings} or {@code boots}. */
        String piece;
        /** The piece item's own {@code custom_model_data} string. */
        String item;
        List<Armor3dPart> parts;
        /** The equipment asset the item wears, e.g. {@code minecraft:rpai_x_chestplate}, or absent. */
        String asset;
        /** The Bedrock item Geyser maps this piece to, or absent. Boxed: 0 is not a slot. */
        Integer bedrockSlot;
    }

    /**
     * One display. {@code anchor} is in PIXELS in the wearer's frame — see
     * {@link Armor3dSet.Part} — because that is the frame the set was built
     * in and the frame the pose is worked out in; nothing on either side
     * converts it.
     */
    static final class Armor3dPart {
        String bone;
        String model;
        float[] anchor;
        /** Absent for a part the model holds square. */
        float[] rotation;
        /** Boxed: absent is 1, and gson's zero would be a part drawn at no size. */
        Float scale;
    }

    /**
     * A pushed dialog — the whole {@code minecraft:dialog} object.
     *
     * <p>The one entry here that is neither a NAME for something in the zip nor
     * a FACT about something in it, but a file. A dialog is registry data and a
     * resource pack cannot carry one, so Studio sends the object and the engine
     * writes it into a datapack unread. The picture it draws IS in the zip — a
     * font glyph like any overlay's — and the characters that place it are
     * already inside this JSON, put there by the build that allocated them.
     *
     * <p>{@code json} is held as a parsed tree only so gson will accept it;
     * {@link com.google.gson.JsonElement#toString()} is what gets written, which
     * is the same object Studio sent. Nothing here reads a field of it.
     */
    static final class Dialog {
        String id;
        String name;
        com.google.gson.JsonElement json;
        /** The variables its bound controls show, and each one's values. Absent binds nothing. */
        List<DialogVariable> vars;
        /**
         * A dialog with live item slots: which icon-sheet cell draws each of
         * the pack's own items, by the {@code custom_model_data} string a stack
         * of it carries. Absent when the pack painted none.
         */
        Map<String, Integer> itemIcons;
        /**
         * The command a player opens it with, {@code shop} for {@code /shop} —
         * see {@link ai.resourcepack.engine.api.DialogInfo#command()}. Absent has none.
         */
        String command;
        /** What a player needs to use that command. Absent: anybody may. */
        String permission;
    }

    /** A variable a dialog declares: see {@link ai.resourcepack.engine.api.DialogInfo#variables()}. */
    static final class DialogVariable {
        String name;
        List<String> values;
    }

    /**
     * A pushed model, and the one thing about it a zip cannot say.
     *
     * <p>Everything else on this list is here because the art carries no way to
     * NAME it. A model is different: it is in the zip, it is placeable, and it
     * works with nothing sent beside it. What the zip has no field for is
     * whether the piece is something a vehicle can drive through — see
     * {@link ai.resourcepack.engine.api.ModelInfo#vehicleCollision()} — so that
     * one fact travels, and only for the models where the answer is no.
     *
     * <p>A model absent from this list therefore stops vehicles, which is what
     * every model pushed before this field existed did once the engine learned
     * to ask. Sending only the exceptions is the same argument the give
     * command's scale marker makes: an absent value has always meant the
     * default and has to keep meaning it.
     */
    static final class Model {
        String id;
        /** Boxed for the reason every optional field here is. Absent is TRUE. */
        Boolean vehicleCollision;
    }

    static final class Sound {
        String id;
        /** The sounds.json key, which is NOT the id — see {@link SoundInfo#event()}. */
        String event;
        String category;
        /**
         * How long the audio runs, in seconds — see {@link SoundInfo#length()}.
         *
         * <p>The bytes are inside a zip this engine never opens, so this is
         * the only way it can know: Studio measured the file when it encoded
         * it. Boxed, like every optional number here, and absent on a manifest
         * from before vehicles could make a noise.
         */
        Double durationSeconds;
    }

    /**
     * A vehicle, whose art is named the way a pushed pack names art.
     *
     * <p>{@code carrier} rather than {@code model}: a Studio pack is a zip
     * with no plugin behind it, so its models borrow paper wearing a string
     * {@code custom_model_data}, and that string is the only handle there is.
     * See {@link VehicleInfo#carrier()}.
     *
     * <p><strong>Seat offsets arrive in BLOCKS.</strong> Studio authors them
     * in model pixels, because that is what its editor's inspector prints for
     * a cube, and converts on the way out — so both sides of this manifest
     * speak the same unit as a hand-written {@code vehicles/*.yml}. The
     * conversion is stated in studio's {@code lib/studio-content.ts}; getting
     * it wrong is a seat a sixteenth of the way to where it should be.
     */
    static final class Vehicle {
        String id;
        String carrier;
        String name;
        String medium;
        double weight;
        double speed;
        double acceleration;
        double turnSpeed;
        /**
         * How much bigger than built it is drawn — see {@code VehicleInfo.scale}.
         *
         * <p><strong>Boxed, on the same argument as the flight numbers below.</strong>
         * Gson leaves an absent primitive at zero, and a scale of zero is a
         * vehicle drawn at no size at all — which is what every vehicle pushed
         * before this field existed would become. Absent means 1.
         */
        Double scale;
        /**
         * Whether space jumps it rather than braking it — see
         * {@code VehicleInfo.jumps()}. Boxed so a manifest older than the field
         * reads as absent, which is false, which is the handbrake every land
         * vehicle had before.
         */
        Boolean jump;
        /**
         * Whether it turns while standing still — see
         * {@code VehicleInfo.turnInPlace}.
         *
         * <p>Boxed like the numbers around it, though for once the unboxed
         * default would have been right: gson leaves an absent boolean at
         * false, which is what a manifest pushed before this existed means.
         * Boxed anyway so this file has one rule about an absent field rather
         * than one per type.
         */
        Boolean turnInPlace;
        /**
         * Whether a rider's cape is drawn in it — see
         * {@code VehicleInfo.capes}.
         *
         * <p>Boxed, and this one HAS to be: the default is TRUE, so an absent
         * primitive's false would take the cape off every rider in every
         * vehicle pushed before this field existed.
         */
        Boolean capes;
        double hitboxWidth;
        double hitboxHeight;
        double hitboxLength;
        /**
         * How it flies, which only {@code medium: air} reads.
         *
         * <p><strong>Boxed, for the same reason the emitter's optional numbers
         * are.</strong> Gson leaves an absent primitive at zero, and zero is a
         * meaningful value for three of these that is not the right default —
         * a {@code climbRate} of zero is an aircraft that cannot climb, which
         * is what every vehicle pushed before this field existed would silently
         * become. Absent means {@link VehicleFlight#forSpeed}, the behaviour
         * those vehicles were built against.
         */
        Double takeoffSpeed;
        Double climbRate;
        Double diveRate;
        Double stallSink;
        List<Seat> seats;
        /**
         * State name to animation name. Absent on a manifest older than this,
         * which is a vehicle that animates nothing.
         */
        Map<String, String> animations;
        /**
         * State name to SOUND id — the id of a sound in this same manifest,
         * which becomes {@code studio:<id>} at this end like everything else
         * on this path. Absent on a manifest older than this, which is a
         * vehicle that makes no noise.
         */
        Map<String, String> sounds;
        List<Emitter> particles;
        /** Opaque addon-owned configuration, transported without interpretation. */
        Map<String, Map<String, Object>> addons;
    }

    /**
     * One particle emitter on a pushed vehicle.
     *
     * <p><strong>Every optional number here is boxed, and that is not
     * tidiness.</strong> Gson leaves an absent primitive at its zero, and zero
     * is a meaningful value for three of these that is not the right default:
     * {@code enabled} would be false (an emitter nobody can see and nothing
     * explains), {@code color} would be black rather than "no colour asked
     * for", and {@code size} would be clamped up from nothing. A box makes
     * absent distinguishable from written, which is the whole difference.
     */
    static final class Emitter {
        String effect;
        List<String> states;
        /** In BLOCKS, like a seat's — see {@link Vehicle}. */
        double x;
        double y;
        double z;
        int count;
        double spread;
        double speed;
        int interval;
        Boolean enabled;
        /** {@code 0xRRGGBB}, or absent for none. */
        Integer color;
        Double size;
    }

    static final class Seat {
        String role;
        String pose;
        double x;
        double y;
        double z;
        double yaw;
        String name;
        /**
         * Whether this seat's occupant is drawn at all. Absent is false, which
         * is every seat pushed before this existed.
         */
        Boolean hidden;
        /**
         * State name to EMOTE id — what this seat's occupant wears while the
         * vehicle is in that state. Absent on a manifest older than this.
         */
        Map<String, String> animations;
    }

    static final class Overlay {
        String id;
        /** The characters that draw it: negative space, then the glyph. */
        String title;
        /** A screen's container. Empty on a HUD. */
        String container;
        /** A HUD's slot. Empty on a screen. */
        String slot;
        /**
         * The colour the run must be drawn in, {@code #rrggbb}. Null for white.
         *
         * <p><b>For a shader overlay this is an address, not a look.</b> The
         * pack's core shader recognises one of its objects by the exact colour
         * the text arrived with, so a run drawn in any other colour renders
         * nothing at all.
         *
         * <p>Null on every overlay pushed before this field existed, which is
         * why null has to keep meaning white — Gson leaves an absent field
         * null, so an older pack must go on drawing exactly as it did.
         */
        String color;
        /**
         * The font the run must be drawn in. Null for the default font.
         *
         * <p>A shader object's canvas glyph lives in a font of its own, so it
         * never lands in the icon list and its codepoints cannot collide with
         * an icon's.
         */
        String font;
        /**
         * A line drawn after the picture, in the default font.
         *
         * <p>{@code {name}} placeholders are filled per player from whatever
         * {@code Overlays.set} last put there. The only part of an overlay a
         * live server value can drive — see that method.
         */
        String text;
        /** What shows it with no plugin involved. See {@link OverlayTrigger}. */
        List<Trigger> triggers;
        /**
         * Positioned runs of text, drawn after the picture on the same line.
         *
         * <p>Preferred over {@link #text}, which is the older unpositioned
         * form kept so a pack pushed before positioning existed still draws.
         */
        List<Run> runs;
        /**
         * How far the picture moves the cursor, in pixels.
         *
         * <p>Where the cursor is when the first run starts, and so the origin
         * every run's {@code x} is measured from. Zero on a manifest written
         * before the engine did its own positioning, which is what
         * {@link OverlayInfo#positionsRuns()} tests for.
         */
        int advance;
        /**
         * The characters that move the cursor right by 1, 2, 4 … 512 pixels,
         * in that order, and their negative twins.
         *
         * <p>Studio allocates these codepoints and declares their advances in a
         * font only it writes, so the engine is handed the alphabet rather than
         * inventing one. Without them it cannot place a run itself.
         */
        String shiftPlus;
        String shiftMinus;
        /**
         * A SCREEN's player heads, drawn by {@code Overlays.open} from the
         * viewer's own skin. Null on a HUD and on any screen without one; a
         * screen with heads also carries {@link #advance} and the alphabet.
         * See {@link ai.resourcepack.engine.core.font.ScreenHeads}.
         */
        List<Head> heads;
    }

    /** One entry of {@link Overlay#heads}. */
    static final class Head {
        /** Left edge of the face, in sheet pixels from the screen glyph's left edge. */
        int x;
        /** Screen pixels per face pixel. */
        int size;
        /** Whether the skin's hat layer is drawn over the face. */
        boolean hat;
        /** The eight row characters, top first. */
        List<String> rows;
    }

    /** One entry of {@link Overlay#runs}. */
    static final class Run {
        String shift;
        /** Where this run starts, in pixels from the picture's left edge. */
        int x;
        /**
         * How the drawn words line up on {@link #x}: 0 starts them there, 0.5
         * centres them, 1 ends them there. Absent reads as 0, which is every
         * run written before a label could be pinned to the right of the screen.
         * See {@link OverlayInfo.OverlayRun#align()}.
         */
        double align;
        String text;
        String font;
        String color;
        /**
         * How far drawing this run moves the cursor, or absent to measure it.
         *
         * <p>Absent for words, which {@code TextWidth} measures. Present for
         * any run whose glyph the PACK invented and no table of vanilla's
         * widths can hold: a player's head, and any picture the author placed.
         * Both are ordinary runs in every other respect — this field and the
         * one below are the whole of what makes them different, which is why
         * neither has a kind of its own anywhere in this file.
         */
        int advance;
        /**
         * The character to draw instead of {@link #text}, by lowercase undashed
         * UUID.
         *
         * <p>How a head is the viewer's own face: a push bakes one glyph per
         * recipient and this says which is whose. Absent on every overlay
         * written before heads existed, and absent for ordinary text always.
         */
        Map<String, String> players;
        /**
         * How to build this run when its length depends on a number.
         *
         * <p>Present only on a progress bar's two runs. The pack cannot fill in
         * a bar for the same reason it cannot position a second label: the value
         * is not known until a tick before the line is sent. So it ships the
         * rectangles and the engine assembles them.
         */
        Bar bar;
    }

    /** How the engine builds one of a bar's runs. See {@link Run#bar}. */
    static final class Bar {
        List<BarGlyph> glyphs;
        /** How many pixels long the bar is when full. */
        int total;
        /** The placeholder that fills it. A bare name, no braces. */
        String value;
        /** What full means: a placeholder name, or a plain number. */
        String max;
        /** True for the fill run; the background is always {@link #total} long. */
        boolean fill;
        /**
         * How many chunks to break the bar into, or 0 for one continuous strip.
         *
         * <p>Absent on a manifest written before segmented bars existed, which
         * Gson leaves at zero — and zero is the continuous bar those manifests
         * meant. Nothing has to migrate.
         */
        int segments;
        /** Pixels of nothing after each chunk. Read only when {@link #segments} is set. */
        int gap;
        /**
         * What colour the fill takes by how full it is, lowest threshold first.
         *
         * <p>Null on the background run and on any bar of one colour, which is
         * every bar written before thresholds existed.
         */
        List<BarShade> colors;
    }

    /** One rectangle a bar is assembled from. */
    static final class BarGlyph {
        String character;
        /** How many pixels wide it draws. A power of two. */
        int px;
    }

    /**
     * One colour a bar's fill takes at or below a fraction of full.
     *
     * <p>{@code color} is a MARK — see {@link OverlayInfo.OverlayRun.Bar.Shade},
     * which is the same record one layer out and carries the reasoning.
     */
    static final class BarShade {
        double at;
        String color;
    }

    /** One rule from {@link Overlay#triggers}. */
    static final class Trigger {
        String kind;
        String item;
        String permission;
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
    private volatile Map<ContentId, ai.resourcepack.engine.api.DialogInfo> dialogs = Map.of();
    private volatile Map<String, Armor3dSet> armor3d = Map.of();
    private volatile String packId = "";

    /** The registry handle, held for as long as the content is registered. */
    private Namespace claimed;

    public StudioContent(File dataFolder) {
        this.file = new File(dataFolder, "studio-content.json");
    }

    /** One with no file behind it: a snapshot of one push, or several merged. See {@link #snapshot}. */
    private StudioContent() {
        this.file = null;
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
        copy.dialogs = dialogs;
        copy.armor3d = armor3d;
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
        Map<ContentId, ai.resourcepack.engine.api.DialogInfo> dialogs = new LinkedHashMap<>();
        Map<String, Armor3dSet> armor = new LinkedHashMap<>();
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
            dialogs.putAll(pack.dialogs);
            armor.putAll(pack.armor3d);
            last = pack.packId;
        }
        StudioContent all = new StudioContent();
        all.sounds = Map.copyOf(sounds);
        all.screens = Map.copyOf(screens);
        all.huds = Map.copyOf(huds);
        all.vehicles = Map.copyOf(vehicles);
        all.vehiclePassable = Set.copyOf(passable);
        all.dialogs = Map.copyOf(dialogs);
        all.armor3d = Map.copyOf(armor);
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
     * <p>Not registry content, and that is a known gap rather than a choice:
     * nothing but a push can define one yet, so there is no second source for
     * the registry to hold an opinion about. A hand-authored set (a
     * {@code .bbmodel} with MythicArmors' bone names) is the missing half.
     */
    public Map<String, Armor3dSet> armor3d() {
        return armor3d;
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

    /** Whether there is anything at all. */
    public boolean isEmpty() {
        return sounds.isEmpty() && screens.isEmpty() && huds.isEmpty() && vehicles.isEmpty()
                && vehiclePassable.isEmpty() && dialogs.isEmpty() && armor3d.isEmpty();
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
        for (Model model : manifest.models == null ? List.<Model>of() : manifest.models) {
            if (model == null || model.id == null || model.id.isEmpty()) {
                continue;
            }
            if (Boolean.FALSE.equals(model.vehicleCollision)) {
                readPassable.add(model.id);
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
            id(dialog.id, log, "dialog").ifPresent(id -> readDialogs.put(id,
                    ai.resourcepack.engine.api.DialogInfo.pushed(id, dialog.json.toString(), dialog.name, vars, icons)
                            .withCommand(dialog.command, dialog.permission)));
        }

        Map<String, Armor3dSet> readArmor = new LinkedHashMap<>();
        for (Armor3d set : manifest.armor3d == null ? List.<Armor3d>of() : manifest.armor3d) {
            Armor3dSet read = armor3dOf(set, log);
            if (read != null) {
                readArmor.put(read.id(), read);
            }
        }

        sounds = Map.copyOf(readSounds);
        screens = Map.copyOf(readScreens);
        screenHeads = Map.copyOf(readHeads);
        huds = Map.copyOf(readHuds);
        vehicles = Map.copyOf(readVehicles);
        vehiclePassable = Set.copyOf(readPassable);
        dialogs = Map.copyOf(readDialogs);
        armor3d = Map.copyOf(readArmor);
        packId = manifest.packId == null ? "" : manifest.packId;
        return MergeResult.ok(packId,
                sounds.size() + screens.size() + huds.size() + vehicles.size() + dialogs.size()
                        + armor3d.size());
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
    }

    /** Drops the namespace, if it is held. */
    public void release() {
        if (claimed != null) {
            claimed.release();
            claimed = null;
        }
    }

    /** Reads what was saved. A missing file is an empty pack, not a problem. */
    public void load(Logger log) {
        if (file == null || !file.isFile()) {
            return;
        }
        try {
            MergeResult result = updateFromJson(
                    new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8), log);
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
        for (String id : vehiclePassable) {
            Model model = new Model();
            model.id = id;
            model.vehicleCollision = Boolean.FALSE;
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
            manifest.dialogs.add(dialog);
        }
        manifest.armor3d = new ArrayList<>();
        for (Armor3dSet set : armor3d.values()) {
            manifest.armor3d.add(armor3dOut(set));
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
            out.add(new OverlayInfo.OverlayRun(run.shift, run.x, run.text, run.font, run.color,
                    run.advance, run.players == null ? Map.of() : Map.copyOf(run.players), bar(run.bar),
                    run.align));
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
                colors.add(new OverlayInfo.OverlayRun.Bar.Shade(shade.at, shade.color));
            }
        }
        return new OverlayInfo.OverlayRun.Bar(glyphs, raw.total, raw.value, raw.max, raw.fill,
                raw.segments, raw.gap, colors);
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
