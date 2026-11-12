// GENERATED from Studio's description of this wire. Do not edit by hand.
package ai.resourcepack.engine.core.sync;

import java.util.List;
import java.util.Map;

/**
 * The push manifest's shape: what a Studio push says is in the pack it
 * carries, as Gson reads it. {@link StudioContent} reads into these and
 * does everything else.
 *
 * <p><strong>Generated.</strong> Studio writes this file from the same
 * description it writes its own side from, so the two cannot disagree
 * about a field's name or type. Do not edit it by hand: a change made here
 * is lost the next time it is generated.
 *
 * <p>Every class is a plain field holder. Gson leaves a field it did not
 * receive at its zero, which is why a number or flag Studio may leave out
 * is boxed: absent is {@code null}, never a confident 0 or false.
 */
final class StudioWire {

    private StudioWire() {
    }

    /** The manifest's shape. Written by Studio's content-manifest writer. */
    static final class Manifest {
        String packId;
        List<Sound> sounds;
        List<Overlay> screens;
        List<Overlay> huds;
        List<Vehicle> vehicles;
        List<Dialog> dialogs;
        List<Model> models;
        List<Armor3d> armor3d;
        List<ShaderObject> objects;
        List<TextEffect> textEffects;
        List<Vfx> vfx;
        List<Stinger> stingers;
        List<Icon> icons;
        List<Mob> mobs;
        /**
         * Written by {@link StudioContent#save} only, and read only by {@link StudioContent#load}: that
         * the push this file holds was read as trusted (see
         * {@link StudioContent#updateFromJson}). A push that
         * carries it is not believed; whoever pushed decides nothing here.
         */
        Boolean commandsKept;
    }

    /**
     * A custom mob: a vanilla mob for the behaviour, a pushed model for the
     * looks, and the rest of what {@code entities/*.yml} can say. Registered
     * as {@code studio:<id>}, spawned with {@code /rp spawn}.
     *
     * <p>{@code model} is the art the way a pushed VEHICLE names it — the
     * {@code custom_model_data} string on paper, which is also the rig store's
     * key — not an item id; see {@link Vehicle}.
     *
     * <p><strong>Every optional field is boxed</strong>, for the reason the
     * vehicle's flight numbers are: Gson leaves an absent primitive at zero,
     * and zero is the wrong default for {@code ai}, {@code speed} and
     * {@code scale}. Absent means the safe default in every case.
     */
    static final class Mob {
        String id;
        String model;
        String name;
        Boolean nameVisible;
        /** A Bukkit {@code EntityType} name: the behaviour underneath. */
        String type;
        Boolean ai;
        Boolean burns;
        Double health;
        Double damage;
        /** A multiplier on the type's own movement speed. */
        Double speed;
        Double knockbackResistance;
        /** How big the MODEL is drawn, as a vehicle's scale. */
        Double scale;
        /** The hitbox height in blocks the model wants; see {@code EntityInfo.height}. */
        Double height;
        /** Degrees added to the model's heading. */
        Double turn;
        String headBone;
        /** State name to animation name. A name this jar does not know is skipped. */
        Map<String, String> animations;
        /**
         * Slot to SOUND id, a bare id of a sound in this same manifest, which
         * becomes {@code studio:<id>} at this end exactly as a vehicle's does.
         */
        Map<String, String> sounds;
        /** Absent and empty both drop nothing: the list REPLACES vanilla loot. */
        List<MobDrop> drops;
        Integer xp;
        /** Absent is on: a mob flashes red when hurt unless told not to. */
        Boolean hurtFlash;
        /** Blocks it notices a target from; absent is the type's own. */
        Double followRange;
        Boolean glowing;
        Boolean fireImmune;
        /** Absent is no boss bar. */
        MobBossBar bossBar;
        /** Absent is a mob that does not shoot. */
        MobRanged ranged;
        /** Absent is a mob that never spawns on its own. */
        MobSpawning spawning;
        /**
         * Its own skill lines, MythicMobs' grammar as written
         * ({@code skill{s=studio:golem.slam} @self ~onTimer:120 <50%}), read by
         * the same parser as a content folder's {@code skills:}. Absent is none.
         */
        List<String> skills;
        /** The stance it starts in; absent is none. */
        String stance;
        /** The metaskills its lines call, registered as {@code studio:<name>}. Absent is none. */
        List<MobMetaskill> metaskills;
    }

    /**
     * One metaskill a pushed mob's lines call: MythicMobs' file shape in
     * camelCase, handed to the same reader as a content folder's
     * {@code skills/} file. {@code name} is the bare id, unique in the push.
     */
    static final class MobMetaskill {
        String name;
        /** Seconds, per mob. Absent is none. */
        Double cooldown;
        /** Each tested on the mob, with an optional action ({@code stance{s=x} false}). */
        List<String> conditions;
        /** Each target must pass. */
        List<String> targetConditions;
        List<String> skills;
    }

    /** A pushed mob's boss bar: Bukkit {@code BarColor} and {@code BarStyle} names. */
    static final class MobBossBar {
        String color;
        String style;
        Double range;
    }

    /** How a pushed mob shoots; see {@code EntityInfo.Ranged}. */
    static final class MobRanged {
        String projectile;
        Double damage;
        Double cooldown;
        Double range;
    }

    /** Where a pushed mob spawns on its own; see {@code EntityInfo.Spawning}. */
    static final class MobSpawning {
        List<String> worlds;
        List<String> biomes;
        List<String> replaces;
        Double chance;
        Integer minLight;
        Integer maxLight;
        String time;
        Integer minGroup;
        Integer maxGroup;
        Integer maxPerChunk;
    }

    /** One line of a pushed mob's loot. */
    static final class MobDrop {
        /** {@code minecraft:leather}, or {@code studio:<item id>} of the same pack. */
        String item;
        Integer min;
        Integer max;
        Double chance;
    }

    /**
     * A VFX: its name and its whole recipe, the timeline the engine plays
     * ({@code core/vfx/}). The sprites it draws are in the zip; a zip has no
     * way to say "play this", so the timeline travels here. Cleaned again on
     * the way in, by the same rules Studio cleaned it with.
     */
    static final class Vfx {
        String id;
        String name;
        com.google.gson.JsonObject recipe;
    }

    /**
     * A stinger transition: the character its title shows, the trigger
     * colours that make the pack's text shader play each half, and how long
     * each half runs. The drawing is all in the pack; the engine only sends
     * titles ({@code core/transition/}).
     *
     * <p><strong>Boxed ticks</strong>: an absent one is Studio's default, never 0.
     */
    static final class Stinger {
        String id;
        String name;
        String glyph;
        String in;
        String out;
        String auto;
        Integer inTicks;
        Integer holdTicks;
        Integer outTicks;
        String soundIn;
        String soundOut;
    }

    /**
     * An icon: its name and the character the pack draws it at. The picture
     * is in the pack's default font and needs nothing from here; this is so a
     * server can NAME it ({@code :studio:heart:}) and list it.
     */
    static final class Icon {
        String id;
        String name;
        Integer codepoint;
        Integer height;
        Integer ascent;
    }

    /**
     * A text effect: its name and the colour that is it. The effect itself is
     * code in the pack's text shader and needs nothing from here to draw; this
     * is so a server can NAME it ({@code <fx:fire>}) rather than write hex.
     */
    static final class TextEffect {
        String id;
        String name;
        String color;
    }

    /**
     * A 3D shader object a server can place and drive: the item model that
     * draws it, the size it was made at and the states its code names. The
     * code itself is in the pack; see {@code core/object/}.
     */
    static final class ShaderObject {
        String id;
        String model;
        /** Boxed: absent is 1, and zero would be an object drawn at no size. */
        Double size;
        List<ShaderObjectState> states;
    }

    static final class ShaderObjectState {
        String name;
        /** Boxed: an absent value is a state nobody can enter, not state 0. */
        Integer value;
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
        /**
         * What a click on its picture sounds like — see
         * {@link ai.resourcepack.engine.api.DialogInfo#clickSound()}. Absent:
         * the server's configured click.
         */
        DialogSound clickSound;
        /** The clicks with a sound of their own, each naming its command. */
        List<DialogSound> clickSounds;
        /**
         * What its "Run as console" clicks run, each by the key its click names
         * — see {@link ai.resourcepack.engine.api.DialogInfo#consoleCommands()}.
         * Absent: none.
         */
        List<DialogConsole> consoleCommands;
        /**
         * The engine closes it after a click that opens nothing else — see
         * {@link ai.resourcepack.engine.api.DialogInfo#closesAfterClick()}.
         * Studio's "Seamless", sent with {@code after_action: none}. Absent: false.
         */
        Boolean closeAfterClick;
        /**
         * On the Quick Actions key (G) — see
         * {@link ai.resourcepack.engine.api.DialogInfo#quickAction()}. Absent:
         * not on it.
         */
        DialogQuickAction quickAction;
    }

    /** A variable a dialog declares: see {@link ai.resourcepack.engine.api.DialogInfo#variables()}. */
    static final class DialogVariable {
        String name;
        List<String> values;
    }

    /** How a dialog is on the Quick Actions key: {@code launcher} true for the launcher, and the name its list shows. */
    static final class DialogQuickAction {
        Boolean launcher;
        String label;
    }

    /** A console command a dialog's click runs: the key its {@code rp click} names, and the command. */
    static final class DialogConsole {
        String key;
        String command;
    }

    /**
     * A click sound: a sound event (empty is silence), its volume and pitch,
     * and, in a dialog's {@code clickSounds}, the command whose click it is.
     */
    static final class DialogSound {
        String sound;
        Float volume;
        Float pitch;
        String command;
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
        /**
         * Which game block a placed copy works as, or absent for none. The names are
         * those of a content folder's furnace and station keys.
         */
        String works;
        /**
         * How a player's placement turns and sizes it at random, or absent for
         * not at all. The other fact a zip has no field for, and sent on the
         * same terms: only for the models that have one.
         */
        PlaceVariation variation;
    }

    /**
     * A model's random turn and size, as {@link ai.resourcepack.engine.api.ModelInfo.Variation}
     * takes them. Every field absent means "not that one".
     */
    static final class PlaceVariation {
        /** Degrees either side of its facing, 0-180. */
        Float rotation;
        /** What a turn is a whole number of, in degrees, or absent/0 for any angle. */
        Float rotationStep;
        /** The smallest size, as a multiplier. */
        Float scaleMin;
        /** The largest. */
        Float scaleMax;
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
        /** Opaque addon-owned configuration, transported without interpretation. */
        Map<String, Map<String, Object>> addons;
        String name;
        String medium;
        double weight;
        double speed;
        double acceleration;
        double turnSpeed;
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
        /**
         * Whether a rider's cape is drawn in it — see
         * {@code VehicleInfo.capes}.
         *
         * <p>Boxed, and this one HAS to be: the default is TRUE, so an absent
         * primitive's false would take the cape off every rider in every
         * vehicle pushed before this field existed.
         */
        Boolean capes;
        List<Emitter> particles;
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
        /** What shows it with no plugin involved. See {@link OverlayTrigger}. */
        List<Trigger> triggers;
        /**
         * A SCREEN's player heads, drawn by {@code Overlays.open} from the
         * viewer's own skin. Null on a HUD and on any screen without one; a
         * screen with heads also carries {@link #advance} and the alphabet.
         * See {@link ai.resourcepack.engine.core.font.ScreenHeads}.
         */
        List<Head> heads;
    }

    /** One rule from {@link Overlay#triggers}. */
    static final class Trigger {
        String kind;
        String item;
        String permission;
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
        /** Drawn only while this condition holds. Absent is always. */
        String when;
        /** Colours it can be switched to by name; each color is a MARK. */
        List<RunState> states;
        /** The placeholder whose answer names one of {@link #states}. */
        String state;
    }

    /** One entry of {@link Run#states}. */
    static final class RunState {
        String name;
        String color;
    }

    /** One rectangle a bar is assembled from. */
    static final class BarGlyph {
        String character;
        /** How many pixels wide it draws. A power of two. */
        int px;
    }

    /** How the engine builds one of a bar's runs. See {@link Run#bar}. */
    static final class Bar {
        List<BarGlyph> glyphs;
        /** How many pixels long the bar is when full. */
        int total;
        /** Its length from a value instead (a placeholder or a number). Absent keeps {@link #total}. */
        String length;
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

    /**
     * One colour a bar's fill takes at or below a fraction of full.
     *
     * <p>{@code color} is a MARK — see {@link OverlayInfo.OverlayRun.Bar.Shade},
     * which is the same record one layer out and carries the reasoning.
     */
    static final class BarShade {
        double at;
        String color;
        /** A placeholder whose answer, a percentage, moves {@link #at}. */
        String atFrom;
    }
}
