package ai.resourcepack.engine.core.emote;

import ai.resourcepack.engine.api.EmoteResult.Reason;
import ai.resourcepack.engine.api.EmoteResult;
import ai.resourcepack.engine.api.EmoteTrigger;
import ai.resourcepack.engine.api.Keyframe;
import ai.resourcepack.engine.api.event.EmoteEndEvent;
import ai.resourcepack.engine.api.event.EmoteStartEvent;
import ai.resourcepack.engine.core.Host;
import ai.resourcepack.engine.core.animation.RigMath;
import ai.resourcepack.engine.core.animation.Sampler;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plays an emote: hides the player, stands a rig of their own skin where they
 * were, and poses it from the same keyframes the editor previews.
 *
 * <p><b>An ordinary emote's rig does not follow the player, because an ordinary
 * emote ends the moment they move.</b> That is what keeps that path simple —
 * six displays spawned once at a fixed spot, never re-positioned, removed on
 * stop.
 *
 * <h2>Stances</h2>
 *
 * The other kind, and the one exception to everything above. An emote whose
 * manifest names any {@link ai.resourcepack.api.EmoteTrigger} is WORN rather
 * than performed: the player keeps it on and keeps playing — same game mode,
 * free to walk, jump and fight — while the rig is carried with them each pass
 * and the animation's clock runs only in the states the pack author named.
 * Four things flip, and each of them is the same rule read the other way:
 *
 * <ul>
 *   <li><b>Moving does not end it</b>, so {@link #MOVE_TOLERANCE} and the
 *       settle window are not consulted at all — there is no anchor to drift
 *       from.</li>
 *   <li><b>The rig is teleported to the player</b> every pass, at
 *       {@code teleportDuration = INTERPOLATION_TICKS}, so the client
 *       interpolates between our steps rather than the rig stepping after
 *       them — with a tick of slack in hand, so a late packet does not show.
 *       This is the per-tick teleport the paragraph above says an ordinary
 *       emote does not need; a mount offset was the alternative and was not
 *       taken, because a passenger dismounts on damage and cannot ride
 *       somebody who is already in a boat.</li>
 *   <li><b>Damage does not end it.</b> On an ordinary emote that check is what
 *       stops an emote being a place to hide; a stance hides nobody — the rig
 *       stands exactly where the body stands and follows it — so ending one on
 *       every arrow would make the feature unusable in survival for no safety
 *       bought. Death still ends it, since a corpse cannot wear anything.</li>
 *   <li><b>Root motion is off</b> however the manifest is marked. A stance is
 *       driven by the player's own legs and there is nothing to walk them
 *       along; studio does not ship the flag on one either.</li>
 * </ul>
 *
 * <p><b>A stance writes no origin marker</b>, and that is what makes the
 * crash-recovery path correct for it rather than merely survivable: the
 * marker's job is undoing a position the emote chose, and a stance never
 * chooses one. So somebody who logs in after a crash wearing one is made
 * visible again where they stand instead of being yanked back to wherever they
 * put it on.
 *
 * <p>A stance never has a cast — studio refuses the pair at three layers — so
 * nothing here has to decide what a performer held in place around a lead who
 * walks off would mean.
 *
 * <h2>Who the rig is FOR decides how the body is hidden</h2>
 *
 * <b>The two kinds want opposite things, and each gets the opposite
 * treatment.</b> A one-shot emote is a performance you trigger and then watch:
 * you stand still, it plays, it ends the moment you move. So the wearer is
 * hidden behind their own rig and looks at it, which is what the rest of this
 * section describes.
 *
 * <p>A stance is worn for an hour while you play, and is a thing OTHER people
 * see you as. Its wearer spends that hour in first person, where being replaced
 * by a rig buys them nothing — so <b>a stance hides the rig from its wearer
 * instead of hiding the wearer from the world</b>, and their own view stays
 * vanilla down to the arm, the item and the hotbar. Everybody else sees exactly
 * what they saw before. See {@link #hideFromWearer}, which has the trade
 * written out; the paragraphs below are the one-shot path.
 *
 * <h2>Invisibility, not spectator</h2>
 *
 * The player is made invisible for the duration — particle-free and icon-free,
 * so nothing swirls around the rig and no effect appears in their inventory.
 * <b>This replaced a spectator-mode swap</b>, which had bought the same
 * hiding at a price this does not pay:
 *
 * <ul>
 *   <li>Spectator flies through walls, so the mode swap was also a teleport
 *       hack — emote, drift through a wall, stop — and the position had to be
 *       restored to close it. Invisibility leaves the body solid and standing
 *       where it stands, so there is nothing to exploit. The teleport-back
 *       stays regardless, because root motion and a performer's spawn spot
 *       both move people on purpose.</li>
 *   <li>Spectators cannot be damaged, which made an emote a brief
 *       invulnerability that no listener here could close: the damage that
 *       would report it never fired. An invisible player takes damage
 *       normally, so {@link #onDamage} now really does interrupt an emote
 *       that is interrupted.</li>
 * </ul>
 *
 * <p><b>The potion alone does NOT hide a player, and assuming it did was a
 * bug.</b> Invisibility hides the skin and nothing else: armour still renders,
 * and so does the held item, the name plate and the shadow. So the body is
 * hidden in two halves — {@code hidePlayer} stops everybody else being sent the
 * entity at all (armour included, and with no equipment to take off anybody and
 * hand back), and the potion covers the one view {@code hidePlayer} cannot,
 * because Bukkit cannot hide a player from THEMSELVES.
 *
 * <p><b>A scoreboard team can undo all of this, and the default is that it
 * does.</b> {@code Team#canSeeFriendlyInvisibles} starts ON, and a client
 * renders a friendly invisible as a translucent ghost rather than not at all
 * — so on any server whose tab-list or name-colour plugin puts players on
 * teams (most of them), an emoting player sees a see-through copy of
 * themselves standing inside their own rig, and so does every teammate the
 * entity is still sent to. Nothing here can fix it: the scoreboard belongs to
 * the server, and turning the flag off for a team would be this library
 * making a PvP decision on somebody else's behalf. A server seeing ghosts
 * wants {@code setCanSeeFriendlyInvisibles(false)} on the teams it creates.
 *
 * <p><b>There is no combat gate.</b> Starting an emote used to be refused for
 * five seconds after taking damage, on the reasoning that turning invisible
 * mid-fight is an advantage. That rule is gone: it is a gameplay decision, and
 * this library runs on servers whose rules it cannot guess — the same reason
 * there are no permission nodes here either. {@link #onDamage} still ends an
 * emote the moment anybody in it is hit, which is what stops an emote being a
 * place to hide; a server that wants the old refusal back can cancel
 * {@link ai.resourcepack.api.event.EmoteStartEvent} on its own combat timer.
 *
 * <h2>Nobody is left invisible</h2>
 *
 * The effect the player already had (or the fact they had none) and their
 * origin are written to their own persistent data BEFORE the swap, so a crash
 * mid-emote is recoverable: {@link #onJoin} restores anyone still carrying the
 * marker. Storing it on the player rather than in this map is the whole point
 * — a map does not survive the process that owns it dying.
 *
 * <p>Anyone stranded in SPECTATOR by a jar older than this change is still
 * restored, from the marker that version wrote. That path is legacy-only and
 * nothing writes it any more.
 */
public final class EmoteDirector implements Listener {

    private final Map<UUID, Long> lastStart = new ConcurrentHashMap<>();

    /**
     * How often every emote is stepped, in ticks.
     *
     * <p><b>One, and it used to be two.</b> Everything a worn emote does is
     * paced by this — the rig is teleported onto its wearer once a pass, the
     * client is told to spend exactly one pass interpolating there, and each
     * bone's pose is tweened over the same window — so at two ticks the rig was
     * always up to a tenth of a second behind the body it belongs to. Walking
     * backwards is where that reads worst, because the camera moves toward the
     * rig rather than away from it and the lag comes out as the rig drifting in
     * front of the view instead of trailing behind it.
     *
     * <p>Halving it cannot remove the gap — the server's idea of where a player
     * is trails their own client's by however long their ping is, and no
     * position we can send is more current than the one we were given — but it
     * halves the part that is ours.
     *
     * <p>The cost is a second pass per tick, which is a handful of packets per
     * emoting player: the pose loop skips any bone whose transform has not
     * changed, and a hidden rig is not teleported at all.
     */
    private static final int PERIOD_TICKS = 1;
    /** How far a player may drift before the emote is called off, in blocks. */
    private static final double MOVE_TOLERANCE = 0.35;

    /**
     * Minimum gap between two starts by one player.
     *
     * An emote spawns six entities, swaps a gamemode and writes persistent
     * data; a macro'd `/emote` is therefore cheap to send and not cheap to
     * serve. This is a floor on the cost of spamming it, not a gameplay rule —
     * a person emoting normally never reaches it.
     */
    private static final long START_COOLDOWN_MS = 1500L;

    /**
     * The furthest root motion may carry a player from where they started.
     *
     * A ceiling on the feature, not on any emote: authored displacement is
     * bounded in studio too, and this is what holds when a manifest arrives
     * saying otherwise. Four blocks is a long flip and a short walk.
     */
    /** The animator key studio stores the whole-body transform under. */
    private static final String ROOT_TARGET = "root";

    private static final float[] PROP_ZERO = {0f, 0f, 0f};
    private static final float[] PROP_ONE = {1f, 1f, 1f};

    private static final double MAX_ROOT_DISTANCE = 4.0;

    /**
     * The furthest one step may move somebody, in blocks.
     *
     * Steps land every {@link #PERIOD_TICKS} ticks, so a legitimate move is a
     * fraction of a block. Anything larger means the curve jumped — a bad
     * manifest, or one built to fling a player through a wall — and is refused
     * rather than clamped, because a clamped teleport is still a teleport to
     * somewhere nobody chose.
     */
    private static final double MAX_ROOT_STEP = 0.75;

    /** How far below the feet counts as "there is ground there". */
    private static final double GROUND_PROBE = 0.08;

    /**
     * The scale vanilla draws a humanoid at, and therefore the one this rig
     * has to be drawn at to be the same size as the player wearing it.
     *
     * <p><b>The rig was 6.7% too big, and the arithmetic says exactly why.</b>
     * The skeleton is 32px tall in the space studio builds it in (see
     * RIG_ORIGIN_PX in its Studio's emote skeleton) and an ItemDisplay renders that space
     * at 16px to the block — so left alone it stands 2.0 blocks. Vanilla's
     * player renderer scales the same 32px model by 15/16 before drawing it,
     * which is 1.875 blocks. Nothing was wrong with the model; it was simply
     * never told about the scale the game applies to the original.
     *
     * <p>Applied as the OUTERMOST transform on every display, which is what
     * makes it a scale of the whole rig about the display's anchor rather than
     * a per-bone shrink that would pull the limbs out of their sockets. Being
     * uniform, it commutes with the yaw rotation beside it, so where it sits in
     * that one line does not matter — where it sits in the CHAIN does.
     */
    private static final float PLAYER_SCALE = 0.9375f;

    /**
     * How far above the feet the rig's displays are anchored, in blocks.
     *
     * <p>One block in rig space (RIG_ORIGIN_PX = 16px), times the scale above.
     * The anchor has to come down with everything else: scaling about a point
     * a fixed block above the feet would lift the feet a pixel off the floor,
     * so the emote would hover — the exact opposite of what shrinking it to
     * vanilla size is for.
     */
    private static final double RIG_BASE_Y = PLAYER_SCALE;

    /**
     * How wide the rig's shadow is — vanilla's own for a player.
     *
     * <p>{@code PlayerRenderer} sets {@code shadowRadius = 0.5}, and a display
     * entity's radius is the same quantity in the same units, so this is the
     * player's shadow rather than an approximation of it. Deliberately NOT
     * scaled by {@link #PLAYER_SCALE}: the rig is shrunk to fit the block-model
     * bounds and the body it stands in for is full size, and the shadow belongs
     * to the person.
     */
    private static final float SHADOW_RADIUS = 0.5f;

    /** How dark it is. Vanilla's player shadow is drawn at full strength. */
    private static final float SHADOW_STRENGTH = 1f;

    /**
     * How high above the feet the name floats, in blocks.
     *
     * <p>Vanilla draws a nametag at the entity's height plus half a block, and
     * a player is 1.8 tall — so this is that sum rather than a number picked to
     * look right. The rig's own head tops out lower than the tag (1.875), which
     * is exactly the gap a player's name has above their hat in ordinary play.
     *
     * <p><b>It is not where the DISPLAY goes, and assuming it was is what put
     * the name a line too high.</b> A vanilla nametag is drawn hanging DOWN
     * from this height; a {@link TextDisplay} draws its text UP from wherever
     * it stands. So the display is spawned a line below the number vanilla
     * uses, which is the whole of {@link #nameTagY}.
     */
    private static final double NAMETAG_Y = 2.3;

    /**
     * How tall one line of a {@link TextDisplay} stands, in blocks.
     *
     * <p>Nine font pixels at the 0.025 blocks-per-pixel every piece of world
     * text is drawn at. See {@link #NAMETAG_Y} for why it is subtracted.
     */
    private static final double NAMETAG_LINE = 9 * 0.025;

    /**
     * How far a crouching body drops its name, in blocks.
     *
     * <p>Vanilla floats a name at the entity's height plus half a block, and a
     * crouching player is 1.5 tall rather than 1.8 - so their name comes down
     * with them by exactly this. It matters here because a stance is worn while
     * its wearer crouches: a name held at standing height over a crouched rig
     * is the sort of not-quite-right that reads as a floating label rather than
     * as somebody's nametag.
     */
    private static final double SNEAK_NAMETAG_DROP = 0.3;

    /**
     * How far a crouching player's name carries, as a display's view range.
     *
     * <p>A display's range is a multiple of the 64 blocks a nametag normally
     * reaches, and vanilla halves that for a crouching player — along with
     * refusing to draw it through walls. Both are how a crouch reads as hiding
     * rather than as walking slowly, and a rig that kept broadcasting its
     * owner's name across the map while they crept would be worse than no
     * nametag at all.
     */
    private static final float SNEAK_NAMETAG_RANGE = 0.5f;

    /**
     * How many tick passes a participant gets to settle before drift counts.
     *
     * Six ticks: long enough for a teleport to be acknowledged and for gravity
     * to put somebody down on the block under them, short enough that walking
     * away still ends the emote within a third of a second.
     *
     * <p>Written as a span of TICKS divided by the pass rate rather than as a
     * count of passes, so it stays six ticks whatever {@link #PERIOD_TICKS}
     * becomes. It was three passes at two ticks, and halving the period without
     * this would have quietly halved the settle window with it.
     */
    private static final int SETTLE_CHECKS = 6 / PERIOD_TICKS;

    /**
     * How far down a performer's spot may be dropped to find ground.
     *
     * A step and a bit. The offsets an emote authors are horizontal, so the
     * ground under a partner is usually the ground under the lead — but a
     * slab, a stair or a shallow slope puts it a little lower, and refusing
     * those would make the feature unusable anywhere but a flat floor.
     */
    private static final int GROUND_SEARCH_DOWN = 3;

    /** Half the player's width, for probing the corners they stand on. */
    private static final double HALF_WIDTH = 0.3;

    /**
     * How far a stance's wearer must travel in one pass to count as moving.
     *
     * <p>Horizontal only, and measured over {@link #PERIOD_TICKS} — the slowest
     * real gait is a crouch at about 0.065 blocks a tick, so this sits well
     * under it and well over the hundredths a standing player's position
     * jitters by as the client reconciles. It decides between
     * {@link EmoteTrigger#IDLE} and the moving states, so getting it wrong
     * shows up as a walk cycle flickering under somebody standing still.
     */
    private static final double STANCE_MOVING_STEP = 0.015;

    /**
     * How many passes a step keeps somebody counting as moving.
     *
     * <p><b>A gait is not measured, it is HELD.</b> A player's position reaches
     * the server in movement packets that arrive when they arrive: a walk over
     * a laggy connection lands as two ticks of travel and then a tick of
     * nothing, and read literally that is a walk cycle restarting several times
     * a second. So a pass that sees a step arms this many passes of "still
     * moving", and only a genuine stop runs it down.
     *
     * <p>Four passes at one tick each is a fifth of a second — longer than any
     * gap between packets from a playable connection, and short enough that
     * stopping dead reads as stopping rather than as the animation hanging on.
     * It exists in ticks rather than passes for the same reason
     * {@link #SETTLE_CHECKS} does.
     */
    private static final int MOVING_HOLD_PASSES = Math.max(1, 4 / PERIOD_TICKS);

    /**
     * How far ahead of the position we are given the rig is placed, in ticks.
     *
     * <h2>Why a rig is behind its wearer even with nothing wrong</h2>
     *
     * <b>Stepping every tick was not enough, and could not have been.</b> The
     * position this reads is the one the wearer's client last SENT, and the rig
     * built from it does not appear until two ticks later: our teleport goes out
     * with the entity tracker at the end of the tick, and the client then spends
     * a pass interpolating onto it. Add the wearer's own ping and the rig is
     * drawn where they were 100-200ms ago — a quarter of a block at a walk, most
     * of a block at a sprint.
     *
     * <b>Which is invisible in most directions and glaring in one.</b> Walking
     * forward, the lag puts the rig behind the camera where nobody is looking.
     * Walking BACKWARD, the camera travels toward the rig, so the same lag comes
     * out as the rig sliding into view in front of the player — which is exactly
     * how it was reported, twice.
     *
     * <p>So the rig is placed where its wearer is ABOUT to be rather than where
     * they last were: the step they took this pass, times the delay we owe them,
     * capped and smoothed. This is ordinary dead reckoning and it is
     * self-correcting — every pass re-reads the real position, so a wrong guess
     * is one pass old and never accumulates.
     *
     * <p>The pipeline half is ours and is a constant. The rest comes from
     * {@code Player#getPing}, halved because what matters is the trip from them
     * to us rather than the round trip.
     */
    private static final double LEAD_PIPELINE_TICKS = 2.0;

    /** The most a lead may ever be, in ticks, however bad the connection. */
    private static final double MAX_LEAD_TICKS = 5.0;

    /**
     * The furthest ahead of themselves a wearer's rig may be put, in blocks.
     *
     * <p>The cost of leading is that a body which stops, turns or is teleported
     * was not going where it was going — so the rig overshoots by up to this and
     * is walked back. Two thirds of a block is under half a stride at a sprint
     * and is unwound in a few passes by the smoothing below; a metre would be a
     * rig that swings wide of every corner.
     */
    private static final double MAX_LEAD = 0.65;

    /**
     * How much of the way to the new lead each pass moves.
     *
     * <p>A one-pole filter, and both directions matter. Setting the lead
     * outright would snap the rig forward the instant somebody starts moving and
     * snap it back the instant they stop — trading a lag for a jolt. Half a pass
     * at a time ramps it in over about three ticks and unwinds it over about
     * four, which reads as a body leaning into a walk rather than as a rig being
     * repositioned.
     */
    private static final double LEAD_SMOOTHING = 0.5;

    /**
     * Below this the lead is nothing, in blocks.
     *
     * <p><b>An easing that halves forever never arrives, and that cost more
     * than the arithmetic suggests.</b> A stopped wearer's lead went 0.14,
     * 0.07, 0.035 and on down without ever reaching zero — so the rig's target
     * differed from where it already stood on every tick for the rest of the
     * emote, "has it moved" answered yes for ever, and the skip that was meant
     * to keep a standing rig silent never fired again once anybody had taken a
     * step. Every wearer paid eleven teleports a tick, standing still, for as
     * long as they wore it.
     *
     * <p>A hundredth of a block is a quarter of one model pixel at this scale:
     * far below anything a player can see, and far above the tail of a halving
     * sequence.
     */
    private static final double LEAD_DEAD_ZONE = 0.01;

    /**
     * How long the client is given to interpolate one step, in ticks.
     *
     * <p><b>One tick longer than a step, and the extra tick is the whole
     * point.</b> Setting it equal to the send rate — which is what it was —
     * means the client finishes moving exactly as the next update is due, so a
     * packet that arrives even slightly late leaves the rig standing still
     * until it lands and then jumping. Every wobble in a connection came out as
     * a stutter, which is what "a lot laggier" is: not a rig in the wrong
     * place, a rig that stops and starts.
     *
     * <p>So there is always a tick of slack in hand. It costs a tick of
     * latency, which is exactly what the lead above is already paying for.
     */
    private static final int INTERPOLATION_TICKS = PERIOD_TICKS + 1;

    private final Host host;
    private final EmoteStore emotes;
    /**
     * Legacy: the game mode a pre-invisibility jar swapped away from.
     *
     * Read on join so an upgrade cannot leave somebody a spectator forever.
     * Never written.
     */
    private final NamespacedKey previousModeKey;
    /** The invisibility this player already had, or the fact they had none. */
    private final NamespacedKey previousInvisKey;
    private final NamespacedKey originKey;
    private final NamespacedKey emotePartKey;

    private final Map<UUID, Session> active = new ConcurrentHashMap<>();
    private int taskId = -1;

    private static final class Session {
        EmoteStore.Emote emote;
        /**
         * The part THIS player dances.
         *
         * Resolved once at start — the lead's is the emote's own animators, a
         * performer's is theirs — so nothing downstream has to branch on which
         * kind of participant it is holding. {@link #pose} reads this, not
         * {@code emote.animators}.
         */
        Map<String, Map<String, List<Keyframe>>> animators;
        /** Their whole-body transform, from the same place as animators. */
        Map<String, List<Keyframe>> root;
        List<ItemDisplay> parts = new ArrayList<>();
        List<EmoteStore.Bone> bones = Collections.emptyList();
        long startTick;
        float yaw;
        /**
         * Everyone in this emote, the lead first and this player among them.
         *
         * <p><b>An emote ends for everybody or for nobody.</b> Half a
         * handshake is not a degraded handshake, it is one person shaking air
         * — so anything that ends one participant (moving, quitting, being
         * hit, `/emote stop`) walks this list and ends the rest.
         *
         * <p>A solo emote holds just its own id, so there is one code path
         * rather than a special case for the common one.
         */
        List<UUID> troupe = Collections.emptyList();
        /**
         * Where "did they move" is measured from, once they have settled.
         *
         * <b>Not the spot we teleported them to.</b> A performer is put down at
         * a computed position and then the world has its say — they settle onto
         * the block, the client acknowledges the teleport a tick later, and the
         * server's idea of where they are moves by a few hundredths. Measured
         * against the intended spot, that drift read as the player walking off
         * and cancelled the emote for the whole troupe before it had played a
         * frame. So the first few checks ADOPT wherever they actually ended up,
         * and only then does drift start counting.
         */
        Location anchor;
        /** Ticks of the settle window still to run. See {@link #anchor}. */
        int settling = SETTLE_CHECKS;
        /** Where the player stood, where the rig is, and where they go back to. */
        /** One display per prop, index-aligned with emote.props. */
        List<ItemDisplay> propParts = new ArrayList<>();
        /**
         * The floating name over this participant's rig, or null.
         *
         * <p><b>Hiding the body hides the nametag with it</b>, which is the
         * one thing about an emote that reads as a bug rather than a feature:
         * a rig doing a backflip in front of you belongs to somebody, and
         * until this there was nothing at all saying who. It is the player's
         * DISPLAY name, so a prefix another plugin put there comes along.
         */
        TextDisplay nameTag;
        /** Whether the name is currently up. See {@link #setNameTagShown}. */
        boolean nameTagShown = true;
        /**
         * Whether the name is currently drawn the way a crouching player's is.
         *
         * <p>Held so the two properties that change with it — how high it
         * floats and whether it shows through walls — are only written when the
         * answer actually changes, rather than being re-sent to everybody
         * nearby on every pass of every stance.
         */
        boolean nameTagSneaking;
        Location origin;
        /**
         * Where root motion last put the player, or null when it is off.
         *
         * The move-cancels-the-emote check compares against THIS rather than
         * the origin while it is set — otherwise the emote would cancel itself
         * on the first tick it moved somebody.
         */
        Location expected;
        /** Turns off for the rest of the emote once a step is refused. */
        boolean following;
        /**
         * The states this is WORN for, or empty for an ordinary emote.
         *
         * <p>Non-empty is the whole of "this is a stance" — there is no second
         * boolean that could disagree with it. Resolved once at start, from
         * {@link EmoteStore#triggersOf}, so a name this jar cannot detect has
         * already been dropped by the time anything asks.
         */
        Set<EmoteTrigger> triggers = Collections.emptySet();
        /**
         * Where the wearer was on the previous pass, for the moving check.
         *
         * <p>Null on the first pass, which reads as {@link EmoteTrigger#IDLE} —
         * the right answer, since a stance is put on standing still.
         */
        Location previous;
        /**
         * Passes of "still moving" left on the clock. See MOVING_HOLD_PASSES.
         *
         * <p>Armed by a pass that sees a step and run down by every pass that
         * does not, so a gap between movement packets cannot read as a stop.
         */
        int movingFor;
        /**
         * How far ahead of the wearer the rig is being placed, in blocks.
         *
         * <p>Horizontal, smoothed, and never more than {@link #MAX_LEAD}. See
         * {@link #LEAD_PIPELINE_TICKS} for what it is cancelling.
         */
        Vector lead = new Vector();
        /**
         * Whether the next pose should land at once rather than tween.
         *
         * <p>Set by whatever CHANGED the animation and cleared by the pose
         * that honours it. Every other pass eases over
         * {@link #INTERPOLATION_TICKS}, which is what keeps a walk cycle
         * smooth; but a rig coming back from being put away would be tweening
         * out of whatever pose it happened to be holding when it went, which is
         * not a transition anybody authored.
         */
        boolean snap;
        /**
         * Where the displays were last put, or null if they need putting.
         *
         * <p>A teleport is a packet per display per viewer, and a stance is
         * worn standing still as often as it is worn walking — so a pass that
         * would land every part exactly where it already is sends nothing at
         * all. That is what pays for stepping twice as often as this used to.
         *
         * <p>Cleared whenever the rig is hidden or brought back, because a
         * hidden rig is not carried and its displays are wherever its wearer
         * last was.
         */
        Location lastBase;
        /**
         * The set being worn, or null for an ordinary emote or a plain stance.
         *
         * <p>Non-null makes this a stance too — see {@link #stance()} — with
         * one thing added: which emote drives the rig is resolved per pass
         * rather than fixed at the start. {@link #tickStance} does the swap.
         */
        EmoteStore.Group group;
        /**
         * Whether this set's wearer asked to watch it themselves.
         *
         * <p>Off by default, and the default is the whole design — see
         * {@link #hideFromWearer}. Turning it on gives the wearer the ONE-SHOT
         * treatment instead: the potion goes on and none of the rig is hidden
         * from them, so they look at their own set rather than at their own
         * body.
         *
         * <p><b>Inert unless this is a worn set.</b> Every place that reads it
         * is already inside a {@code stance()} branch, so a one-shot emote —
         * which shows its wearer the rig regardless — is unaffected whatever
         * this says. That is what makes the flag safe to accept on any emote
         * and ignore where it means nothing.
         *
         * <p>It costs what {@code hideFromWearer} buys: the rig is a server
         * entity carried on a lead sized for OTHER people's latency, so the
         * wearer watches their own set answer their own input a round trip
         * late, and their first person stops being vanilla. Testing, not
         * playing.
         */
        boolean showSelf;
        /**
         * The states this group wears something in, resolved once at the start.
         *
         * <p>A state that is not a key here is the player's own body: the rig
         * is put away and vanilla animates them. That is an ANSWER rather than
         * a gap, which is why it is a missing key rather than a null value.
         */
        Map<EmoteTrigger, EmoteStore.Emote> members = Collections.emptyMap();
        /**
         * The state whose member is loaded right now, or null before the first
         * pass. Compared against what {@link #tickStance} resolves, so a swap
         * happens exactly when the state changes rather than every pass.
         */
        EmoteTrigger memberState;
        /**
         * The emote that state resolved to, or null while nothing is worn.
         *
         * <p><b>The swap is keyed on THIS rather than on the state</b>, and the
         * difference is a set that answers two states with one emote — which is
         * every set built before crouching was split in two, since a manifest
         * naming {@code sneak} resolves both crouching states to it. Keyed on
         * the state, a wearer starting to crouch-walk would restart a cycle that
         * was already the right one, several times a second while they went
         * round a corner. Keyed on the emote, the clock only ever restarts when
         * a genuinely different animation takes over.
         */
        EmoteStore.Emote memberEmote;
        /**
         * A name and a length for the passes in which nothing is worn.
         *
         * <p>{@code emote} is read by the poser, the ending and two logs, and
         * making it nullable would put a check in each of them for a state that
         * lasts as long as somebody holds a jump. So a group's rest state
         * points at this instead: the group's own name, no animators, and a
         * length nothing samples because the rig is hidden anyway.
         */
        EmoteStore.Emote rest;
        /**
         * Each bone's real item, so a hidden rig can be brought back.
         *
         * <p>Hiding is done by swapping every display's item for AIR rather
         * than by removing the displays: a group can cross into and out of a
         * default state several times a second (a player jumping while
         * running), and spawning ten entities per crossing is a cost paid for
         * nothing. Index-aligned with {@link #parts}.
         */
        List<ItemStack> partItems = Collections.emptyList();
        /** Whether the rig is currently swapped out for air. */
        boolean rigHidden;
        /**
         * The rig's hands, or null on a participant whose arms this pack has
         * no bone for. See {@link HeldItem}.
         *
         * <p>Spawned once and kept for the emote, like the bones and unlike
         * the props: what a player is holding changes constantly and the ITEM
         * is swapped, where a prop's model belongs to an emote and goes away
         * with it. Respawning an entity every time somebody scrolled their
         * hotbar would be the same cost {@link #setRigHidden} avoids.
         */
        ItemDisplay mainHand;
        ItemDisplay offHand;
        /**
         * The blob of shade the rig stands on. See {@link #spawnShadow}.
         *
         * <p>Its own display rather than a property of a bone, because a
         * shadow belongs at the FEET and every bone stands a block above them
         * — see {@link #RIG_BASE_Y} — and a display's shadow fades with its
         * height off the ground.
         */
        ItemDisplay shadow;
        /**
         * What those two were last told to hold.
         *
         * <p>The player's inventory is read every pass — a slot change, an
         * arrow leaving a quiver and a sword breaking are all things nothing
         * fires a usable event for — but the metadata is only written when the
         * answer actually changed. Null means "nothing sent yet", which is a
         * different fact from air.
         */
        ItemStack mainHandItem;
        ItemStack offHandItem;
        /**
         * This wearer's cape, chasing them a tick behind. See {@link CapeSway}.
         *
         * <p>Per session rather than per player: it is state about an emote
         * that is running, and a rig that is put away and brought back should
         * pick the cape up from where the body is rather than from where the
         * rig was left. Stepped once per pass by the tick loop.
         */
        final CapeSway cape = new CapeSway();
        /**
         * Whether this participant's arms are the slim pair.
         *
         * <p>Resolved once at the start from the profile they are wearing,
         * exactly like the arm models are, so the hand sits on the centre line
         * of the arm that was actually spawned.
         */
        boolean slim;
        /**
         * The tick a swing was armed on, or {@link Long#MIN_VALUE} for none.
         *
         * <p>Written by the animation event rather than by the tick loop, which
         * is what keeps it instant — see {@link ArmSwing}. Never cleared: it is
         * read through {@link ArmSwing#running}, so a swing that has run its six
         * ticks stops contributing on its own and there is no tidy-up pass that
         * could miss one.
         */
        long swingTick = ArmSwing.NOT_SWINGING;
        /**
         * Which arm the armed swing belongs to.
         *
         * <p>Left-handedness is a client setting rather than a fact about the
         * world, so it is read off the player when the swing is armed instead
         * of being assumed. It decides which bone the overlay lands on and
         * which way the outward tilt goes.
         */
        boolean swingOffHand;

        boolean stance() {
            return !triggers.isEmpty() || group != null;
        }

        /**
         * Whether the rig is kept off its own wearer's screen.
         *
         * <p>The one place the rule is written, and it is read from BOTH ends:
         * the displays are hidden when it is true, and the invisibility potion
         * goes on when it is false. Those are the two halves of one decision —
         * somebody has to be looking at either the rig or the body, never at
         * both and never at neither — so a single predicate answering both is
         * what stops a later edit to one of them leaving a wearer standing
         * inside their own set, or invisible with nothing to show for it.
         *
         * <p>False for a one-shot emote whatever {@link #showSelf} says, which
         * is what makes the flag inert there: that path already shows its
         * wearer the rig.
         */
        boolean hideFromOwnWearer() {
            return stance() && !showSelf;
        }

        /** The name to report this by: the SET's, never the member's. */
        String label() {
            if (group != null && group.name != null) return group.name;
            return emote != null ? emote.name : "?";
        }
    }

    public EmoteDirector(Host host, EmoteStore emotes) {
        this.host = host;
        this.emotes = emotes;
        this.previousModeKey = host.key("emote-previous-mode");
        this.previousInvisKey = host.key("emote-previous-invis");
        this.originKey = host.key("emote-origin");
        this.emotePartKey = host.key("emote-part");
    }

    public void start() {
        taskId = Bukkit.getScheduler().runTaskTimer(host.plugin(), this::tick, PERIOD_TICKS, PERIOD_TICKS).getTaskId();
        // A reload leaves rigs standing with nobody driving them; the players
        // themselves are restored by onJoin, but the displays have to go.
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            for (ItemDisplay display : world.getEntitiesByClass(ItemDisplay.class)) {
                if (display.getPersistentDataContainer().has(emotePartKey, PersistentDataType.INTEGER)) {
                    display.remove();
                }
            }
        }
        for (Player player : Bukkit.getOnlinePlayers()) restoreIfStranded(player);
    }

    public void stop() {
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId);
        for (UUID id : new ArrayList<>(active.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) stop(player, true, EmoteEndEvent.Cause.SHUTDOWN);
        }
        active.clear();
    }

    public boolean isEmoting(UUID playerId) {
        return active.containsKey(playerId);
    }

    /**
     * Whether this player is being shown their own rig by request.
     *
     * <p>Exists so the command can warn about a flag it actually honoured
     * rather than one that was merely typed: {@code --showYourself} means
     * nothing on a one-shot emote, and a warning about latency in front of
     * something that changed nothing is noise. False for everybody who did not
     * ask, and false for everybody who asked on an emote it does not apply to.
     */
    public boolean showingSelf(UUID playerId) {
        Session session = active.get(playerId);
        return session != null && session.showSelf && session.stance();
    }

    /** Every emote held, in manifest order. An emote's id is its name. */
    public List<String> ids() {
        return emotes.names();
    }


    /**
     * Splits `<name…> [player…]` into an emote and the people named after it.
     *
     * <p>It has to be done here rather than in the command, because only the
     * store knows where the name ends: emote names are free text and "Slow
     * clap" is one emote, so there is no separator to split on. The longest
     * leading run of words that names an emote wins, and whatever is left is
     * the cast — which resolves "Slow clap Steve" the way somebody typing it
     * meant, and still finds an emote genuinely called "Slow clap Steve" first.
     */
    private static final class Query {
        EmoteStore.Emote emote;
        /** Set instead of {@code emote} when the words name a movement set. */
        EmoteStore.Group group;
        List<String> castNames = Collections.emptyList();
        String typed;

        boolean found() {
            return emote != null || group != null;
        }
    }

    private Query resolve(List<String> args) {
        Query query = new Query();
        query.typed = String.join(" ", args);
        for (int keep = args.size(); keep >= 1; keep--) {
            String name = String.join(" ", args.subList(0, keep));
            // Groups first, and it costs nothing to be sure: studio allocates
            // both out of one id space, so a name is one or the other and
            // never both. Asking the smaller map first is only order.
            EmoteStore.Group group = emotes.findGroup(name);
            if (group != null) {
                query.group = group;
                query.castNames = new ArrayList<>(args.subList(keep, args.size()));
                return query;
            }
            EmoteStore.Emote found = emotes.find(name);
            if (found != null) {
                query.emote = found;
                query.castNames = new ArrayList<>(args.subList(keep, args.size()));
                return query;
            }
        }
        return query;
    }

    /**
     * Who could still be named on this half-typed command.
     *
     * <p>Empty unless the words so far name an emote that takes a cast and
     * there are slots left — so completing a solo emote offers nobody, and a
     * duet stops offering once its one partner is typed. Anybody already named
     * is left out, because naming them twice is refused anyway.
     *
     * <p>It lives here rather than in the command for the reason {@link
     * #resolve} does: only the store knows where the emote name ends.
     */
    public List<String> castCandidates(org.bukkit.command.CommandSender sender, String[] args) {
        // The word being typed is not yet a name, so it is not part of what
        // has been decided — completion answers "what could this word be".
        List<String> typed = new ArrayList<>(java.util.Arrays.asList(args).subList(0, args.length - 1));
        if (typed.isEmpty()) return Collections.emptyList();
        Query query = resolve(typed);
        if (query.emote == null || query.emote.performers == null) return Collections.emptyList();
        int remaining = query.emote.performers.size() - query.castNames.size();
        if (remaining <= 0) return Collections.emptyList();

        List<String> names = new ArrayList<>();
        for (Player candidate : Bukkit.getOnlinePlayers()) {
            if (candidate.getName().equals(sender.getName())) continue;
            if (query.castNames.contains(candidate.getName())) continue;
            names.add(candidate.getName());
        }
        return names;
    }

    /**
     * Starts an emote from words somebody typed, or says why it cannot.
     *
     * <p>Where the emote name ends and the cast begins is resolved here rather
     * than by the caller, because only the store knows which names exist: emote
     * names are free text, so "Slow clap Steve" is either one emote or an emote
     * and a player and there is no separator to tell them apart.
     */
    public EmoteResult perform(Player player, List<String> args) {
        return perform(player, args, false);
    }

    /**
     * The same, for a caller that has already read a {@code --showYourself} off
     * the words it was given.
     *
     * <p>The flag is stripped by whoever owns the command surface rather than
     * here, because it is a token in its own right and needs none of
     * {@link #resolve}'s knowledge of where an emote name ends. What this
     * method must not do is see it: an unstripped flag would be taken for the
     * tail of a name, or for a cast member who is not online.
     *
     * @param showSelf whether the wearer asked to watch their own set. Means
     *                 nothing on a one-shot emote — see {@link Session#showSelf}.
     */
    public EmoteResult perform(Player player, List<String> args, boolean showSelf) {
        // Asked before the name is even resolved, and start() asks it again:
        // somebody already emoting who mistypes a name is better told the
        // state they are in than told about the typo, because the state is
        // what they have to fix either way.
        if (active.containsKey(player.getUniqueId())) {
            return EmoteResult.refused(Reason.ALREADY_EMOTING);
        }
        Query query = resolve(args);
        if (!query.found()) {
            List<String> known = emotes.names();
            return known.isEmpty()
                ? EmoteResult.refused(Reason.NO_EMOTES)
                : EmoteResult.refused(Reason.UNKNOWN_EMOTE, query.typed, known);
        }
        if (query.group != null) {
            // A group never takes a cast — it walks off, and a performer is
            // held in place around somebody standing still — so words after
            // its name are answered the way naming somebody in a solo emote
            // is, rather than by ignoring them.
            if (!query.castNames.isEmpty()) {
                return EmoteResult.refused(Reason.SOLO_EMOTE, query.group.name);
            }
            return startGroup(player, query.group, showSelf);
        }
        // The shape of the cast first, from the count alone: naming somebody
        // in a solo emote is answered by "it takes no other players" rather
        // than by whether that somebody happens to be online.
        List<EmoteStore.Performer> performers = query.emote.performers == null
            ? Collections.<EmoteStore.Performer>emptyList()
            : query.emote.performers;
        EmoteResult castRefusal = castRefusal(player, query.emote, performers, query.castNames.size());
        if (castRefusal != null) return castRefusal;

        // Resolved before anything is spawned or anybody is moved, so a cast
        // with one person missing costs nothing rather than half-starting.
        List<Player> cast = new ArrayList<>(query.castNames.size());
        for (String name : query.castNames) {
            Player other = Bukkit.getPlayerExact(name);
            if (other == null || !other.isOnline()) {
                return EmoteResult.refused(Reason.CAST_NOT_ONLINE, name);
            }
            cast.add(other);
        }
        return start(player, query.emote, cast, showSelf);
    }

    /**
     * Starts a named emote — or a movement group — with the cast resolved.
     *
     * <p>Never shows the wearer their own set: this is what the published
     * {@code Emotes} API calls, and that option is not on it yet.
     */
    public EmoteResult play(Player player, String name, List<Player> cast) {
        EmoteStore.Group group = emotes.findGroup(name);
        if (group != null) {
            if (cast != null && !cast.isEmpty()) {
                return EmoteResult.refused(Reason.SOLO_EMOTE, group.name);
            }
            return startGroup(player, group, false);
        }
        EmoteStore.Emote emote = emotes.find(name);
        if (emote == null) {
            List<String> known = emotes.names();
            return known.isEmpty()
                ? EmoteResult.refused(Reason.NO_EMOTES)
                : EmoteResult.refused(Reason.UNKNOWN_EMOTE, name, known);
        }
        return start(player, emote, cast == null ? Collections.<Player>emptyList() : cast, false);
    }

    /**
     * Puts a movement group on.
     *
     * <p>Its own entry rather than a branch inside {@link #start}, because
     * almost none of that method applies: a group has no cast to resolve, no
     * spots to check, no room to make, and no ground rule — it is a stance, and
     * a stance may be put on mid-air for the same reason it does not end when
     * its wearer jumps. What is left is the handful of refusals a stance shares
     * with an emote, and then one call to {@link #begin}.
     *
     * <p>The rig is spawned once, here, and never respawned: which emote drives
     * it changes as the player moves, and the bones are the same bones either
     * way. {@link #tickStance} does that swap.
     */
    private EmoteResult startGroup(Player player, EmoteStore.Group group, boolean showSelf) {
        if (active.containsKey(player.getUniqueId())) {
            return EmoteResult.refused(Reason.ALREADY_EMOTING);
        }

        // Resolved before anything else, because it is the one thing that can
        // be wrong about the group itself. Empty means every state fell through
        // to the player's own body — a group in which nothing would ever be
        // worn — which studio does not ship and this will not start.
        Map<EmoteTrigger, EmoteStore.Emote> members = emotes.partsOf(group);
        if (members.isEmpty()) {
            return EmoteResult.refused(Reason.INCOMPLETE_EMOTE_DATA);
        }

        EmoteStore.PlayerRig rig = emotes.rigFor(player.getUniqueId());
        if (rig == null) {
            return EmoteResult.refused(
                emotes.hasAnyRig() ? Reason.NO_RIG_FOR_PLAYER : Reason.NO_RIGS_IN_PACK);
        }
        boolean borrowed = emotes.ownRigFor(player.getUniqueId()) == null;
        if (emotes.bones().isEmpty()) {
            return EmoteResult.refused(Reason.INCOMPLETE_EMOTE_DATA);
        }
        if (player.getGameMode() == GameMode.SPECTATOR) {
            return EmoteResult.refused(Reason.IN_SPECTATOR);
        }
        Long previous = lastStart.get(player.getUniqueId());
        long now = System.currentTimeMillis();
        if (previous != null && now - previous < START_COOLDOWN_MS) {
            return EmoteResult.refused(Reason.COOLDOWN);
        }

        EmoteStartEvent ask = new EmoteStartEvent(player, Collections.<Player>emptyList(), group.name);
        Bukkit.getPluginManager().callEvent(ask);
        if (ask.isCancelled()) {
            return EmoteResult.refused(Reason.CANCELLED);
        }
        lastStart.put(player.getUniqueId(), now);

        Location origin = player.getLocation().clone();
        Session session = begin(player, restEmote(group), Collections.emptyMap(), null, emotes.bones(),
            rig, origin, Math.round(origin.getYaw()), player.getWorld().getGameTime(),
            Collections.singletonList(player.getUniqueId()), null, group, showSelf);
        if (session == null) {
            return EmoteResult.refused(Reason.NO_RIG_FOR_PLAYER);
        }
        session.members = members;
        // Started hidden and rewritten on the first pass. Nothing is worn until
        // `tickStance` has resolved a state, and a rig posed at the rest
        // emote's identity is a body standing to attention inside the player
        // for one pass — visible, and exactly what a swap is meant to avoid.
        setRigHidden(player, session, true);
        if (tickStance(player, session)) pose(player.getUniqueId(), session, true);
        return EmoteResult.started(group.name, borrowed);
    }

    /**
     * A stand-in emote for the passes in which a group wears nothing.
     *
     * <p>It carries the group's NAME, so the ending event and every log line
     * report the set rather than whichever member happened to be loaded — and
     * no animators, because nothing samples it: the rig is hidden whenever this
     * is what {@code session.emote} points at.
     */
    private static EmoteStore.Emote restEmote(EmoteStore.Group group) {
        EmoteStore.Emote rest = new EmoteStore.Emote();
        rest.name = group.name;
        rest.length = 1;
        rest.loop = true;
        rest.animators = Collections.emptyMap();
        return rest;
    }

    /**
     * Every reason an emote might not happen, and then the emote.
     *
     * <p>The order is deliberate: everything that costs nothing is asked first,
     * and nothing is spawned or moved until every participant and every
     * destination has been checked. A cast half-placed and then refused would
     * leave two people standing in each other and one emote that never started.
     */
    private EmoteResult start(Player player, EmoteStore.Emote emote, List<Player> cast, boolean showSelf) {
        if (active.containsKey(player.getUniqueId())) {
            return EmoteResult.refused(Reason.ALREADY_EMOTING);
        }

        List<EmoteStore.Performer> performers = emote.performers == null
            ? Collections.<EmoteStore.Performer>emptyList()
            : emote.performers;
        EmoteResult castRefusal = castRefusal(player, emote, performers, cast.size());
        if (castRefusal != null) return castRefusal;

        List<Player> troupeCast = new ArrayList<>(cast.size());
        for (Player other : cast) {
            if (other.getUniqueId().equals(player.getUniqueId())) {
                return EmoteResult.refused(Reason.CAST_IS_LEAD, other.getName());
            }
            if (troupeCast.contains(other)) {
                return EmoteResult.refused(Reason.CAST_DUPLICATED, other.getName());
            }
            EmoteResult busy = participantRefusal(other);
            if (busy != null) return busy;
            troupeCast.add(other);
        }

        EmoteStore.PlayerRig rig = emotes.rigFor(player.getUniqueId());
        if (rig == null) {
            // Three different faults wear this one symptom, and telling them
            // apart is the difference between "re-sync" and "this is a bug".
            // The pack carries a vanilla Steve under the nil UUID on every
            // push that has emotes, so reaching here at all means the manifest
            // arrived without ANY rig - which is studio's end, not the
            // player's, and saying "your skin isn't in the pack" would have
            // sent them off to re-sync forever.
            return EmoteResult.refused(
                emotes.hasAnyRig() ? Reason.NO_RIG_FOR_PLAYER : Reason.NO_RIGS_IN_PACK);
        }
        boolean borrowed = emotes.ownRigFor(player.getUniqueId()) == null;
        List<EmoteStore.Bone> bones = emotes.bones();
        if (bones.isEmpty()) {
            return EmoteResult.refused(Reason.INCOMPLETE_EMOTE_DATA);
        }
        if (player.getGameMode() == GameMode.SPECTATOR) {
            return EmoteResult.refused(Reason.IN_SPECTATOR);
        }
        Set<EmoteTrigger> triggers = EmoteStore.triggersOf(emote);
        // The ground check exists because an invisible player FALLS, and a
        // falling player drifts off their anchor and cancels their own emote
        // before it plays a frame. A stance has no anchor and does not cancel
        // on movement, so the reason evaporates — and refusing to put one on
        // mid-jump would be a rule with nothing behind it.
        if (triggers.isEmpty()) {
            Reason airborne = groundRefusal(player);
            if (airborne != null) {
                return EmoteResult.refused(airborne);
            }
        }
        Long previous = lastStart.get(player.getUniqueId());
        long now = System.currentTimeMillis();
        if (previous != null && now - previous < START_COOLDOWN_MS) {
            return EmoteResult.refused(Reason.COOLDOWN);
        }
        lastStart.put(player.getUniqueId(), now);

        // Where the lead stands, and the frame every performer is placed in.
        Location leadOrigin = player.getLocation().clone();
        float leadYaw = Math.round(leadOrigin.getYaw());
        long startTick = player.getWorld().getGameTime();

        // Every destination checked before anybody is moved or anything is
        // spawned. A cast half-placed and then refused would leave two people
        // standing in each other and one emote that never started.
        List<Location> spots = new ArrayList<>(troupeCast.size());
        for (int i = 0; i < troupeCast.size(); i++) {
            Location spot = standingSpot(performerSpot(leadOrigin, leadYaw, performers.get(i)));
            if (spot == null) {
                return EmoteResult.refused(Reason.NO_ROOM, troupeCast.get(i).getName());
            }
            spots.add(spot);
        }

        // The last word, and it belongs to the server rather than to us. This
        // library refuses an emote in combat, in the air and in spectator
        // because those break the emote itself; it has no opinion about arenas,
        // regions or whose turn it is, and a server that does says so here.
        EmoteStartEvent ask = new EmoteStartEvent(player, troupeCast, emote.name);
        Bukkit.getPluginManager().callEvent(ask);
        if (ask.isCancelled()) {
            return EmoteResult.refused(Reason.CANCELLED);
        }

        List<UUID> troupe = new ArrayList<>(troupeCast.size() + 1);
        troupe.add(player.getUniqueId());
        for (Player member : troupeCast) troupe.add(member.getUniqueId());

        begin(player, emote, emote.animators, emote.root, bones, rig, leadOrigin, leadYaw,
            startTick, troupe, null, null, showSelf);
        for (int i = 0; i < troupeCast.size(); i++) {
            Player member = troupeCast.get(i);
            EmoteStore.Performer performer = performers.get(i);
            Location spot = spots.get(i);
            // Their own facing comes from the emote, not from wherever they
            // happened to be looking: a handshake with one person's back to the
            // other is not the emote that was authored.
            spot.setYaw(performerYaw(leadYaw, performer.yaw));
            spot.setPitch(0);
            // Never for a cast member: they did not type the command, and a
            // flag on somebody else's is not theirs to be given.
            begin(member, emote, performer.animators, performer.root, bones,
                emotes.rigFor(member.getUniqueId()), spot, Math.round(spot.getYaw()), startTick, troupe,
                performer.id, null, false);
            if (host.messages() != null) host.messages().pulledIn(member, player, emote.name);
        }

        // Posed only once every rig exists, so the first frame of a duet is the
        // whole duet rather than one person moving and the other appearing.
        for (UUID id : troupe) {
            Session session = active.get(id);
            if (session != null) pose(id, session, true);
        }

        // Said once, when it happens, rather than refusing: they are emoting,
        // just not as themselves, and the fix is a re-sync they can choose to
        // do later.
        return EmoteResult.started(emote.name, borrowed);
    }

    /**
     * Where one performer stands, in the world.
     *
     * The offset is in the LEAD's frame and in block-model px, so it is divided
     * into blocks and rotated by the lead's yaw before it becomes a position —
     * the same conversion {@link #follow} does to root motion's displacement,
     * and for the same reason: "in front of them" has to mean in front of them
     * whichever way they turned before running the command.
     */
    private Location performerSpot(Location leadOrigin, float leadYaw, EmoteStore.Performer performer) {
        if (performer == null) return null;
        double[] world = rigToWorld(leadYaw, performer.offset);
        if (world == null) return null;
        return leadOrigin.clone().add(world[0], world[1], world[2]);
    }

    /**
     * A rig-space offset, in the world, for somebody standing at this yaw.
     *
     * <p><b>This was wrong by 180 degrees in both of its callers, and the shape
     * of the mistake is worth keeping written down.</b> A performer authored
     * standing in front of the lead spawned behind them, and root motion walked
     * players backwards along their own path.
     *
     * <p>The derivation, because the sign cannot be guessed and reasoning about
     * it is what produced the error twice:
     *
     * <ul>
     *   <li>Rig space has <b>-Z forward</b> (the player's front is the north
     *       face) and <b>+X to the player's own right</b>.</li>
     *   <li>Minecraft yaw has 0 facing SOUTH (+Z) and increases toward WEST, so
     *       a player's forward is {@code (-sin Y, cos Y)} and their right is
     *       {@code (-cos Y, -sin Y)}.</li>
     *   <li>An ItemDisplay is rendered with a built-in 180 degree Y spin (see
     *       {@link RigAnimator#toItemDisplaySpace}), which {@link #pose}'s
     *       {@code rotateY(-yaw)} is conjugated through. The two together make
     *       rig space reach the world through a rotation of
     *       {@code 180 - yaw} — and it is that 180, present for the RIG and
     *       absent from this arithmetic, that both callers were missing.</li>
     * </ul>
     *
     * <p>So {@code world = ox * right + (-oz) * forward}, which expands to the
     * negation of what was here before. Sanity check that needs no algebra: at
     * yaw 0 a player faces south, so an offset of "one block forward" has to
     * come out as +Z, and "one block to their right" as -X.
     *
     * <p>Package-visible and free of Bukkit so it can be tested, exactly like
     * {@link #applyPropStep} above it and for the same reason.
     *
     * @return {x, y, z} in blocks, or null if the offset is unusable.
     */
    static double[] rigToWorld(float yaw, float[] offset) {
        if (offset == null || offset.length != 3) return null;
        double ox = offset[0] / 16.0;
        double oy = offset[1] / 16.0;
        double oz = offset[2] / 16.0;
        if (!Double.isFinite(ox) || !Double.isFinite(oy) || !Double.isFinite(oz)) return null;
        double radians = Math.toRadians(yaw);
        double sin = Math.sin(radians);
        double cos = Math.cos(radians);
        return new double[] {-ox * cos + oz * sin, oy, -ox * sin - oz * cos};
    }

    /**
     * Which way a performer faces, as a Minecraft yaw.
     *
     * <p>Their stored yaw is relative to the lead and in the EDITOR's sense,
     * where positive turns the way three.js turns — and Minecraft's yaw runs
     * the other way round (see {@link #rigToWorld}). So it is subtracted, not
     * added. Adding it agreed with the editor at 180 and nowhere else, which
     * is exactly why it survived: face-to-face is the default, and 180 is the
     * one angle where a sign error is invisible.
     */
    static float performerYaw(float leadYaw, float performerYaw) {
        return leadYaw - performerYaw;
    }

    /**
     * Puts one participant into the emote: rig spawned, body moved, mode taken.
     *
     * <p>Everything that used to be the tail of {@link #play}, with the two
     * facts that differ per person passed in — which part they dance, and where
     * they stand. That extraction is the whole of multiplayer on this end: a
     * performer is not a special kind of participant, they are the same
     * participant with a different animator map and a different origin.
     *
     * <p>Props are the LEAD's only, because {@code Emote.props} is the lead's:
     * a sword the emote carries is in their hand, and spawning a copy per
     * performer would put three swords in the scene.
     */
    private Session begin(
            Player player,
            EmoteStore.Emote emote,
            Map<String, Map<String, List<Keyframe>>> animators,
            Map<String, List<Keyframe>> root,
            List<EmoteStore.Bone> bones,
            EmoteStore.PlayerRig rig,
            Location origin,
            float yaw,
            long startTick,
            List<UUID> troupe,
            /** This participant's performer id, or null if they are the lead. */
            String performerId,
            /** The set being worn, or null for an ordinary emote or a stance. */
            EmoteStore.Group group,
            /** Whether the wearer asked to watch their own set. See {@link Session#showSelf}. */
            boolean showSelf) {
        if (rig == null) return null;
        boolean isLead = performerId == null;

        Session session = new Session();
        // Set first, because `stance()` reads it and three decisions below turn
        // on that answer: whether the displays are carried, whether root motion
        // may run, and whether an origin marker is written.
        session.group = group;
        // Recorded whatever kind of emote this is. Every reader is inside a
        // `stance()` branch, so it decides nothing on a one-shot — which is how
        // the flag is accepted on any emote and quietly means nothing on one
        // that already shows its wearer the rig.
        session.showSelf = showSelf;
        session.rest = group != null ? emote : null;
        session.emote = emote;
        session.animators = animators == null ? Collections.<String, Map<String, List<Keyframe>>>emptyMap() : animators;
        session.root = root;
        session.bones = bones;
        session.origin = origin.clone();
        session.troupe = troupe;
        session.triggers = EmoteStore.triggersOf(emote);
        // Only when the pack author asked for it AND this participant actually
        // has a path — one that stands still has nothing to follow, and the
        // manifest builder already refuses to ship the flag without a root.
        // Never on a stance: the wearer's own legs are the path, and studio
        // does not ship the flag on one either. Guarded here as well so a
        // hand-edited manifest cannot teleport somebody along a curve while
        // they are trying to walk.
        session.following = emote.rootMotion && session.root != null && !session.stance();
        session.yaw = yaw;
        session.startTick = startTick;

        // Written BEFORE the swap, so a crash between the two lines leaves a
        // marker that says "put them back", never one that says nothing. It is
        // where they were standing when the command ran, not where the emote
        // moves them to — the marker's whole job is undoing this.
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        pdc.set(previousInvisKey, PersistentDataType.STRING,
            encodeInvisibility(player.getPotionEffect(PotionEffectType.INVISIBILITY), player.getWorld().getGameTime()));
        // Not for a stance, and that omission is the whole of its recovery
        // story. The origin marker means "this emote moved you, put you back";
        // a stance never moves anybody, so writing one would turn a crash into
        // a teleport home from wherever they had walked to while wearing it.
        // `restoreIfStranded` keys on the invisibility marker above, which a
        // stance DOES write, so it is still found and still made visible.
        if (!session.stance()) {
            pdc.set(originKey, PersistentDataType.STRING, encode(player.getLocation()));
        }

        // The rig stands one block above the player's feet — RIG_ORIGIN_PX in
        // Studio's emote skeleton, and the only offset the whole body fits the
        // block-model bounds at.
        Location base = session.origin.clone().add(0, RIG_BASE_Y, 0);
        base.setYaw(0);
        base.setPitch(0);
        // Their arm width, from the profile they are wearing right now; the
        // manifest's guess only if the profile doesn't say. Per participant,
        // because a duet between an Alex and a Steve is two different pairs of
        // arms off the same bone table.
        String variant = SkinModel.of(player);
        if (variant == null) variant = rig.variant;
        // This player's own table: the ten jointed bones when their rig and
        // this pack both carry them, else the whole-limb six. Per participant,
        // and stored on the session so pose() walks the same list it spawned.
        boolean jointed = emotes.usesJointed(rig);
        bones = emotes.bonesFor(rig);
        session.bones = bones;
        // Kept alongside the displays so a hidden rig can be brought back —
        // see `Session.partItems`. Only a group ever hides one, but recording
        // the references costs nothing either way.
        session.partItems = new ArrayList<>(bones.size());
        for (int i = 0; i < bones.size(); i++) {
            EmoteStore.Bone bone = bones.get(i);
            if (bone == null || bone.key == null) {
                // <b>A null slot, never a skipped one.</b> `pose` walks
                // `parts` and `bones` together by index, so dropping an entry
                // from one list and not the other shifts every bone after it
                // onto its neighbour's model — the forearm's geometry posed by
                // the shin's bone, and limbs that bend in the wrong places
                // with nothing in a log to say why. `spawnProps` already pads
                // for exactly this reason; this loop did not, and the two
                // lists had no way to disagree only because a well-formed
                // manifest never took this branch.
                session.parts.add(null);
                session.partItems.add(null);
                continue;
            }
            final int index = i;
            ItemStack item = boneItem(EmoteStore.boneItemId(rig, bone.key, variant, jointed));
            final boolean carried = session.stance();
            ItemDisplay display = base.getWorld().spawn(base, ItemDisplay.class, d -> {
                d.setItemStack(item);
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                // Never chunk-saved: an emote is a moment, and a rig that
                // outlived the server going down would be litter nobody can
                // remove — unlike a placed model, which is meant to persist.
                d.setPersistent(false);
                if (carried) carry(d);
                d.getPersistentDataContainer().set(emotePartKey, PersistentDataType.INTEGER, index);
            });
            session.parts.add(display);
            session.partItems.add(item);
            // A worn set is not for its wearer's own eyes, unless they asked
            // for it. See hideFromWearer and Session.showSelf.
            if (session.hideFromOwnWearer()) hideFromWearer(player, display);
        }

        spawnProps(player, session, emote, base, performerId);
        // The arms this player actually got, so the hand sits on their centre
        // line rather than on a wide arm's. Resolved here because `variant` is
        // already the answer the arm models were chosen with.
        session.slim = SkinModel.SLIM.equals(variant);
        spawnHands(player, session, base);
        // `session.origin` rather than the player's location, and they are not
        // the same thing: a performer is teleported to a computed spot AFTER
        // this runs, so their shadow would otherwise be left lying where they
        // were standing when the command was typed. The origin is the feet the
        // rig stands on either way — `base` is this point a block higher.
        Location feet = session.origin.clone();
        feet.setYaw(0);
        feet.setPitch(0);
        session.shadow = spawnShadow(session, feet);
        session.nameTag = spawnNameTag(player, session);
        if (session.hideFromOwnWearer()) {
            // The shadow and the name go with everything else. A wearer whose
            // own body is back is casting their own shadow and wearing their
            // own nameplate, so leaving either of the rig's on their screen is
            // the doubling `setRigHidden` avoids for everybody else.
            hideFromWearer(player, session.shadow);
            hideFromWearer(player, session.nameTag);
        }

        // Invisible, and nothing more — the camera stays where the body was.
        // A third-person shot was tried here and taken back out at the owner's
        // request: it moved the player, which is a bigger intrusion than
        // looking out from inside your own rig, and F5 already gets you a view
        // of it if you want one.
        //
        // Infinite rather than the emote's length, because a looping emote has
        // no length to expire at and a duration that ran out mid-pose would
        // pop the body back into the middle of its own rig. Every path out of
        // an emote removes it, and a crash is covered by the marker written
        // above — the same guarantee the game-mode swap had.
        conceal(player, session);
        if (!isLead) player.teleport(session.origin);
        active.put(player.getUniqueId(), session);
        return session;
    }

    /**
     * One display per model this emote carries, replacing whatever was there.
     *
     * <p>Same act as spawning a bone: an ItemStack whose custom_model_data
     * string is the model's id, which is what the pack already dispatches that
     * model under. A prop whose model has since been removed from the pack
     * renders as the missing-model cube, which is the honest outcome and not
     * worth a special case.
     *
     * <p>Each participant spawns only what THEY carry, and the list is walked
     * in full so {@code propParts} stays index-aligned with {@code emote.props}
     * — which is what {@code poseProps} relies on. Somebody else's model takes
     * a null slot rather than shifting everybody's index by one.
     *
     * <p><b>It exists as its own method for the group path.</b> Props belong to
     * an EMOTE, so a group swapping which emote drives the rig has to put the
     * old one's models away and stand up the new one's — the bones are shared
     * and the props never are.
     */
    private void spawnProps(
            Player player, Session session, EmoteStore.Emote emote, Location base, String performerId) {
        for (ItemDisplay display : session.propParts) {
            if (display != null && display.isValid()) display.remove();
        }
        session.propParts = new ArrayList<>();
        for (EmoteStore.Prop prop : emote.props == null
                ? java.util.Collections.<EmoteStore.Prop>emptyList()
                : emote.props) {
            if (prop == null || prop.modelId == null || prop.modelId.isEmpty() || !carries(prop, performerId)) {
                session.propParts.add(null);
                continue;
            }
            ItemStack item = boneItem(prop.modelId);
            final boolean carried = session.stance();
            ItemDisplay display = base.getWorld().spawn(base, ItemDisplay.class, d -> {
                d.setItemStack(item);
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                d.setPersistent(false);
                if (carried) carry(d);
            });
            session.propParts.add(display);
            // Respawned on every member swap of a group, so this is not a
            // one-time hide at the start: a prop that arrived with the walk
            // cycle has to be hidden from the wearer exactly as the bones were.
            if (session.hideFromOwnWearer()) hideFromWearer(player, display);
        }
    }

    /**
     * The two displays that hold what the player is holding.
     *
     * <p>Both are spawned whatever the hands contain, including nothing: an
     * empty one carries air, costs a single metadata field, and is there the
     * instant somebody draws a sword. Spawning on demand would put an entity
     * spawn — which a client cannot interpolate into — in front of the first
     * frame of every draw.
     *
     * <p><b>The item display transform is the load-bearing part.</b> Bones are
     * spawned {@code NONE}, because a bone is raw geometry we have posed
     * ourselves; a held item is a real item, and {@code THIRDPERSON_*HAND}
     * makes the client apply that item model's own in-hand display block. So a
     * sword lies along the hand, a torch stands up in it and a shield turns
     * side-on, each because its own model file says so — the same data vanilla
     * reads. Nothing here knows what a sword is.
     *
     * <p><b>The wearer is not sent either of them</b>, and that is what pays
     * for their own item still being real on their own screen — see
     * {@link #conceal}.
     */
    private void spawnHands(Player player, Session session, Location base) {
        session.mainHand = spawnHand(player, session, base, false);
        session.offHand = spawnHand(player, session, base, true);
    }

    private ItemDisplay spawnHand(Player player, Session session, Location base, boolean offHand) {
        final boolean carried = session.stance();
        ItemDisplay display = base.getWorld().spawn(base, ItemDisplay.class, d -> {
            d.setItemStack(new ItemStack(Material.AIR));
            d.setItemDisplayTransform(offHand
                ? ItemDisplay.ItemDisplayTransform.THIRDPERSON_LEFTHAND
                : ItemDisplay.ItemDisplayTransform.THIRDPERSON_RIGHTHAND);
            // Never chunk-saved, for the same reason a bone is not: an emote is
            // a moment and a hand that outlived the server would be litter.
            d.setPersistent(false);
            if (carried) carry(d);
        });
        // Everybody else sees the rig holding the sword; the wearer sees the
        // sword they are really holding, in their own hand, where their client
        // put it. Hiding the ENTITY is what lets both be true at once — the
        // duplicate this used to solve by taking the item off their screen. It
        // is never undone: while the rig is away these hold air anyway, and the
        // displays go with the emote.
        player.hideEntity(host.plugin(), display);
        return display;
    }

    /**
     * The shadow the rig stands on.
     *
     * <p><b>Hiding the body takes its shadow with it</b>, and that turned out
     * to be most of why a rig read as pasted onto the world rather than
     * standing in it: {@code hidePlayer} does not send the entity, and a
     * display entity casts no shadow unless it is asked to, so a stance walked
     * around lit from every angle with nothing underneath it. The class note
     * already listed the shadow among the things invisibility does not hide —
     * this is the other half of that sentence.
     *
     * <p>One display, holding the rig's own first bone with its scale taken to
     * nothing. Three things about that are deliberate:
     *
     * <ul>
     *   <li><b>At the feet, not at the base.</b> Every bone stands a block up
     *       ({@link #RIG_BASE_Y}), and a display's shadow fades over the couple
     *       of blocks between it and the ground — so a shadow hung off a bone
     *       would be a permanently faint one.</li>
     *   <li><b>Real content, scaled to zero</b>, rather than air. The shadow is
     *       drawn beside the item rather than instead of it, so a display the
     *       client has nothing to render may have nothing to hang it on; a
     *       degenerate transform is the one way to be sure there is a model and
     *       still see none of it.</li>
     *   <li><b>Its own entity</b>, so exactly one shadow is cast. Asking the
     *       bones for it would stack six or ten of them into a blot.</li>
     * </ul>
     *
     * <p>Null when this pack's rig has no bones to borrow an item from, which
     * is the same condition that leaves the wearer with no rig worth shading.
     */
    private ItemDisplay spawnShadow(Session session, Location feet) {
        ItemStack item = session.partItems.isEmpty() ? null : session.partItems.get(0);
        if (item == null || feet.getWorld() == null) return null;
        final boolean carried = session.stance();
        return feet.getWorld().spawn(feet, ItemDisplay.class, d -> {
            d.setItemStack(item);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setTransformation(new Transformation(
                new org.joml.Vector3f(), new org.joml.Quaternionf(),
                new org.joml.Vector3f(0f, 0f, 0f), new org.joml.Quaternionf()));
            d.setShadowRadius(SHADOW_RADIUS);
            d.setShadowStrength(SHADOW_STRENGTH);
            d.setPersistent(false);
            if (carried) carry(d);
        });
    }

    /**
     * Puts whatever the player is holding into the rig's hands.
     *
     * <p>Polled rather than driven by an event, and deliberately: a slot change
     * has {@code PlayerItemHeldEvent}, but an item being eaten, a tool breaking,
     * a stack being picked up into the held slot and an arrow leaving a quiver
     * do not have one between them. One reference comparison per hand per tick
     * is cheaper than the four listeners that would still miss a case, and
     * nothing is written unless the answer changed.
     *
     * <p><b>An empty hand is air, not a skipped write.</b> Putting a sword away
     * has to take it out of the rig's hand, and "leave the last thing there"
     * is what a naive change check does.
     *
     * <p>Nothing is sent to the WEARER's client here, and that is the whole of
     * the change that fixed using items while emoting — see {@link #conceal}.
     * This writes entity metadata on two displays the wearer is not sent.
     */
    private void syncHands(Player player, Session session) {
        ItemStack main = player.getInventory().getItemInMainHand();
        ItemStack off = player.getInventory().getItemInOffHand();
        ItemStack wasMain = session.mainHandItem;
        ItemStack wasOff = session.offHandItem;
        session.mainHandItem = syncHand(session.mainHand, main, wasMain, session.rigHidden);
        session.offHandItem = syncHand(session.offHand, off, wasOff, session.rigHidden);
    }

    /**
     * One hand, returning what it is now holding.
     *
     * <p>A hidden rig holds air whatever the player has: the body is back and
     * is rendering its own held item, and two copies of somebody's sword is
     * worse than neither. What is remembered is what was SENT rather than what
     * the player has, which is what makes the crossing work in both directions
     * — the pass that puts the rig away sees air against a sword and writes it,
     * and the pass that brings it back sees the sword against air.
     */
    private static ItemStack syncHand(
            ItemDisplay display, ItemStack held, ItemStack sent, boolean rigHidden) {
        if (display == null || !display.isValid()) return sent;
        ItemStack wanted = rigHidden || held == null ? new ItemStack(Material.AIR) : held.clone();
        if (wanted.equals(sent)) return sent;
        display.setItemStack(wanted);
        return wanted;
    }

    /**
     * Puts the rig away, or brings it back, and swaps the body the other way.
     *
     * <p>Only a group ever calls this, and only for a state it leaves on the
     * player's own animation. The two halves are one act: a rig standing where
     * the player is and the player standing inside it must never both be
     * visible, and must never both be hidden either.
     *
     * <p><b>The displays are kept and their ITEM is swapped for air.</b> A
     * player sprint-jumping crosses this boundary several times a second, and
     * removing and respawning ten entities each time is a cost paid for
     * nothing — where an item swap is one packet per display. It also keeps
     * every index (parts, partItems, bones) aligned, which a rebuild would not.
     */
    private void setRigHidden(Player player, Session session, boolean hidden) {
        if (session.rigHidden == hidden) return;
        session.rigHidden = hidden;
        // A hidden rig is not carried, so its displays are standing wherever
        // its wearer was when it went away. Forgetting where they are is what
        // makes the pass that brings it back teleport them rather than skip
        // them as already-there.
        session.lastBase = null;
        ItemStack air = new ItemStack(Material.AIR);
        for (int i = 0; i < session.parts.size(); i++) {
            ItemDisplay display = session.parts.get(i);
            if (display == null || !display.isValid()) continue;
            ItemStack item = hidden || i >= session.partItems.size() ? air : session.partItems.get(i);
            display.setItemStack(item);
        }
        // The hands go with the rig, and for exactly the reason the name does:
        // while the body is back it is rendering its own held item, so a rig
        // hand still holding a copy of it is the same duplicate the nametag
        // would be. `syncHands` reads `rigHidden`, so this only has to be
        // ordered before it — which the tick loop is.
        syncHands(player, session);
        // And so does the shadow, for the same reason and with the same fix as
        // the nametag: while the body is back it is casting its own, and two
        // shadows on one pair of feet is a smear rather than a shadow.
        if (session.shadow != null && session.shadow.isValid()) {
            session.shadow.setShadowRadius(hidden ? 0f : SHADOW_RADIUS);
        }
        // The name goes with the rig: while the body is back, so is its own
        // real nametag, and two of them stacked is worse than neither.
        setNameTagShown(session, !hidden);
        if (hidden) reveal(player, session);
        else conceal(player, session);
    }

    /**
     * Hides the body behind the rig: our invisibility, and the entity itself.
     *
     * <p>Both halves are needed and neither is enough — see the class note.
     * The potion is INFINITE rather than the emote's length, because a looping
     * emote has no length to expire at and a duration that ran out mid-pose
     * would pop the body back into the middle of its own rig.
     *
     * <h2>What is deliberately NOT hidden: the wearer's own held item</h2>
     *
     * <p>There used to be a third half here. The potion hides a SKIN and leaves
     * the held item rendering, so the wearer saw their own sword next to the
     * copy in the rig's hand — and the fix was to send THEM an empty-hand
     * equipment packet. It worked, and it cost more than it was worth:
     *
     * <ul>
     *   <li>A client applies that packet to its own inventory, so the slot in
     *       their hand drew empty. There is no packet that says "empty for
     *       rendering only", and the elaborate machinery that used to follow
     *       the blank from slot to slot as they scrolled was all downstream of
     *       that one fact.</li>
     *   <li><b>It broke every item with a use action.</b> A client that
     *       believes it is holding air does not start a bow draw, does not
     *       raise a shield, does not begin eating or drinking, and does not
     *       load a crossbow — the server had the real item throughout, so these
     *       half-worked and read as the plugin being broken rather than as the
     *       hand being empty. Bows and potions were the complaint; the list is
     *       every use action in the game.</li>
     *   <li>In first person it removed the last thing left to look at. The
     *       potion already hides the arm, so a wearer saw nothing at all in
     *       front of the camera and an empty hotbar slot under it.</li>
     * </ul>
     *
     * <p><b>So the duplicate is solved from the other end</b>: the rig's two
     * hand displays are not sent to the wearer at all ({@link #spawnHand}),
     * and their own item is left alone — really in their hand, drawn by their
     * own client, predicted like vanilla. The cost is honest and small: in F5
     * the wearer sees their item where their own invisible body holds it rather
     * than on the rig's animated hand. Nobody else sees any of this — everybody
     * else is not sent the body at all, so for them the rig's hand is the only
     * one there has ever been.
     */
    private void conceal(Player player, Session session) {
        // A WORN set is not hidden from its own wearer. See `hideFromWearer`:
        // their first person stays vanilla and the rig is somebody else's view
        // of them, so there is nothing to hide from and no potion to apply.
        //
        // Unless they asked to watch it (`--showYourself`), which puts them
        // back on the one-shot path — the rig is left on their screen, so the
        // body underneath it has to come off or they are standing inside their
        // own set. The two are one decision; see `hideFromOwnWearer`.
        if (!session.hideFromOwnWearer()) {
            player.addPotionEffect(new PotionEffect(
                PotionEffectType.INVISIBILITY,
                PotionEffect.INFINITE_DURATION,
                0,
                false, // not ambient: ambient is the beacon look, dimmer but still swirling
                false, // no particles, which is the whole point of using this
                false)); // no inventory icon either — this is not a status they chose
        }
        hideFromOthers(player);
    }

    /**
     * Takes the rig off the WEARER's screen, so their own view stays vanilla.
     *
     * <h2>The two kinds of emote want opposite things, and that is the whole
     * of this</h2>
     *
     * <p>A one-shot emote is a performance you trigger and then watch: you
     * stand still, it plays, it ends the moment you move or get hit. Being
     * hidden behind your own rig is the point of it, so that path is unchanged
     * — the wearer is made invisible and looks at the rig.
     *
     * <p>A <b>worn set is the opposite</b>. You put it on and then go and play
     * with it on for an hour: walking, mining, fighting, using items. It is a
     * thing OTHER people see you as, and the wearer spends that hour in first
     * person, where being replaced by a rig buys them nothing and costs them
     * everything about the game feeling normal. Every artefact this subsystem
     * has fought — the missing arm, the empty hand, the item floating at an
     * invisible body — exists only in the wearer's own view of a rig that is
     * not for them.
     *
     * <p>So a stance hides the rig from its wearer instead of hiding the wearer
     * from the world. Their first person is vanilla, byte for byte: their real
     * arm, their real item, their real swing, their real hotbar. Everybody else
     * is unaffected — {@code hidePlayer} still takes the body off their screens
     * and the rig is still all they see, animated and holding what its wearer
     * holds.
     *
     * <p><b>The cost is real and is the reason this is a choice rather than an
     * improvement</b>: pressing F5 shows the wearer their own body doing
     * vanilla's animations, not the set they are wearing. They cannot watch
     * their own walk cycle. That is the trade — a normal hour of play against
     * being able to admire yourself — and for something worn all day it is the
     * right way round.
     *
     * <p>Applied to every display an emote owns, and it has to be every one:
     * a bone left visible is a limb hanging in the air, and a nametag left
     * visible is the wearer's own name floating over their head.
     */
    private void hideFromWearer(Player player, org.bukkit.entity.Entity display) {
        if (player == null || display == null) return;
        player.hideEntity(host.plugin(), display);
    }

    /**
     * And back, for a group crossing into a state it leaves to vanilla.
     *
     * <p><b>Not {@link #restore}</b>, which is the ending: that one clears the
     * markers, puts the position back and is not safe to run mid-emote. This
     * gives the body back and leaves every marker where it is, because the
     * emote is still on — the next state may hide it again a tick later.
     *
     * <p>The borrowed invisibility is recomputed from the marker each time
     * rather than remembered, so crossing this boundary a hundred times does
     * not drift what a player's own potion has left on it: the marker carries
     * the tick it was captured at, and the remainder is worked out from now.
     */
    private void reveal(Player player, Session session) {
        // Only a group ever crosses this boundary, and a group is a stance, so
        // the potion half is dead for it — until its wearer asks to watch it,
        // which is exactly when `conceal` DID apply one and this has to take it
        // off again. Reading the same predicate as `conceal` is what makes that
        // free: the pair being symmetrical is what stops a later change to one
        // of them leaving somebody invisible.
        if (!session.hideFromOwnWearer()) {
            player.removePotionEffect(PotionEffectType.INVISIBILITY);
            String invis = player.getPersistentDataContainer().get(previousInvisKey, PersistentDataType.STRING);
            StoredEffect previous = invis == null
                ? null
                : decodeInvisibility(invis, player.getWorld().getGameTime());
            if (previous != null) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY,
                    previous.duration, previous.amplifier,
                    previous.ambient, previous.particles, previous.icon));
            }
        }
        showToOthers(player);
    }

    /**
     * Every display this emote spawned, bones and carried models alike.
     *
     * One place, because there are three ways an emote ends — stopped, the
     * player quit, the tick found them gone — and a display left behind by any
     * of them is litter in somebody's world that nothing will ever collect.
     * Adding the props as a second list without this would have leaked one per
     * emote on two of those three paths.
     */
    private void removeDisplays(Session session) {
        for (ItemDisplay display : session.parts) {
            if (display != null && display.isValid()) display.remove();
        }
        for (ItemDisplay display : session.propParts) {
            if (display != null && display.isValid()) display.remove();
        }
        // The hands are the third list, and the reason this method exists at
        // all: props were once added as a second one without it and leaked on
        // two of the three ways out.
        if (session.mainHand != null && session.mainHand.isValid()) session.mainHand.remove();
        if (session.offHand != null && session.offHand.isValid()) session.offHand.remove();
        if (session.shadow != null && session.shadow.isValid()) session.shadow.remove();
        if (session.nameTag != null && session.nameTag.isValid()) session.nameTag.remove();
    }

    /**
     * Ends an emote and puts everything back. Safe to call when none is running.
     *
     * <p><b>It ends the whole troupe, not one dancer.</b> Every way an emote can
     * finish comes through here or through {@link #endOne}, and half a
     * handshake left running is one person shaking air while their partner
     * walks off. A solo emote's troupe is just themselves, so there is no
     * branch — the common case is the general case with a list of one.
     */
    public void stop(Player player, boolean silent, EmoteEndEvent.Cause cause) {
        Session session = active.get(player.getUniqueId());
        if (session == null) {
            // Still worth a restore: a marker without a session is somebody the
            // server came back up on, and onJoin is not the only way to find them.
            restore(player, null, (Location) null);
            return;
        }
        for (UUID id : session.troupe) {
            Session other = endOne(id, cause);
            if (other == null) continue;
            Player member = Bukkit.getPlayer(id);
            if (member == null) continue;
            if (!silent && host.messages() != null) {
                host.messages().stopped(member,
                    id.equals(player.getUniqueId()) || session.troupe.size() == 1);
            }
        }
    }

    /**
     * Takes one participant out: displays removed, body and mode restored.
     *
     * Never called on its own except by {@link #stop} and the tick loop's
     * gone-player sweep, both of which are responsible for the rest of the
     * troupe. Returns the session it ended, or null if there wasn't one.
     */
    /** Ends every participant of one emote, saying nothing to any of them. */
    private void endTroupe(Session session, EmoteEndEvent.Cause cause) {
        for (UUID id : session.troupe) endOne(id, cause);
    }

    /**
     * The single funnel every ending goes through, which is why
     * {@link EmoteEndEvent} is fired here and nowhere else.
     *
     * <p>Fired after the body, the position and any borrowed invisibility are
     * back, so a listener sees a player who is done rather than one mid-teardown
     * - and not at all for somebody who is offline, since there is nobody to
     * report about.
     */
    private Session endOne(UUID playerId, EmoteEndEvent.Cause cause) {
        Session session = active.remove(playerId);
        if (session == null) return null;
        Player player = Bukkit.getPlayer(playerId);
        removeDisplays(session);
        if (player != null) {
            // Root motion ends where the path left the player — that is the
            // point of the toggle, and for a long time this put everybody back
            // anyway (see follow()'s history). `expected` is always a spot
            // safeDestination accepted when follow() stepped them onto it, so
            // keeping it reaches nowhere new — and that holds for a run whose
            // FOLLOWING was cut short too: a blocked path ends you at the last
            // good step, not yanked back to the start after travelling most of
            // it. Only a run that never moved anybody restores.
            // Grounded, not raw: the last step of a landing sits a little
            // inside the floor, and left there the player stands in the block.
            // See grounded().
            //
            // Never for a worn emote, and a GROUP is why that has to be said
            // rather than left to fall out. A group's member is an ordinary
            // emote that may well carry root motion of its own — it is
            // playable on its own, where that flag means something — and
            // wearing it never walked anybody anywhere, so there is no
            // travelled ending to keep. A stance reaches the same answer
            // because studio refuses to ship the flag with triggers, which is
            // a rule somewhere else; this is the rule here.
            boolean travelled = !session.stance() && session.emote != null && session.emote.rootMotion;
            Location endAt = travelled ? grounded(session.expected) : null;
            boolean keepPosition = endAt != null;
            if (travelled) {
                host.plugin().getLogger().info(String.format(java.util.Locale.ROOT,
                    "Emote '%s' ended: following=%s stepped=%s -> %s",
                    session.emote.name, session.following, session.expected != null,
                    keepPosition ? "keeping end position" : "restoring origin"));
            }
            // A stance passes NO origin, and that is not a shortcut. Where its
            // wearer stands is where they walked to under their own power, so
            // there is nothing to put back — and a teleport to their own
            // current position is not a no-op either: `restore` zeroes velocity
            // and fall distance after one, which would make taking a stance off
            // mid-air a way to cancel any fall.
            restore(player, session.stance() ? null : session.origin, endAt);
            Bukkit.getPluginManager().callEvent(
                new EmoteEndEvent(player, session.label(), cause));
        }
        return session;
    }

    /**
     * Puts the body, the position and any borrowed invisibility back.
     *
     * <p><b>The position half is not cosmetic</b>, even though invisibility no
     * longer lets anybody drift through a wall: root motion walks the lead
     * along the emote's path and a performer is teleported to their spot, so
     * both are somewhere the emote put them rather than somewhere they chose.
     * Reading the origin from persistent data rather than only from the session
     * is what makes it work after a restart too.
     *
     * <p>{@code endAt} is root motion's ending: the author asked for the emote
     * to carry the player, so a finished one is put down where the path left
     * them rather than back on the origin. The marker is still cleared — the
     * position is now theirs, not a loose end for the next restart to undo.
     * Every stranded/no-session caller passes null, because without a session
     * nothing can vouch that where they stand is somewhere follow() checked.
     *
     * <p>Safe to call on somebody who was never emoting — every read is of a
     * marker only {@link #begin} writes.
     */
    private void restore(Player player, Location fallbackOrigin, Location endAt) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        String mode = pdc.get(previousModeKey, PersistentDataType.STRING);
        String invis = pdc.get(previousInvisKey, PersistentDataType.STRING);
        String origin = pdc.get(originKey, PersistentDataType.STRING);

        // <b>The body comes back FIRST, before anything that can fail and
        // before the markers that would let us try again are cleared.</b>
        //
        // This used to be the last thing the method did, under a comment
        // saying it had to be the first — and the gap between those two facts
        // is a player left permanently invisible. Everything above it (three
        // marker deletes, a teleport, a velocity write) runs before it, and
        // the teleport is the part that throws: a decoded origin whose world
        // has since been renamed or removed hands Bukkit a null world. One
        // exception there and the markers are already gone, so the next join
        // finds nothing to recover from and the infinite invisibility we
        // applied is theirs for good. That is not a hypothetical; it happened
        // to somebody mid-emote across a restart.
        //
        // So: un-hide, then clear, then move. Getting the position wrong
        // leaves somebody standing in the wrong place, which they can walk
        // out of. Getting this wrong leaves them a ghost.
        showToOthers(player);
        // Their own hotbar back, on the same unconditional terms as the potion
        // below. Nothing blanks it any more (see `conceal`), so in the ordinary
        // case this is one wasted packet — but a player who was mid-emote when
        // a jar older than this change was swapped out is carrying a blank
        // their new session will never clear, and one packet is a cheap price
        // for that not being permanent.
        player.updateInventory();
        // Unconditional rather than gated on the marker. The marker is our
        // record of what they had BEFORE; the effect is ours either way, and
        // a state where one exists without the other is exactly the state
        // worth healing rather than the one worth trusting.
        player.removePotionEffect(PotionEffectType.INVISIBILITY);
        if (invis != null) {
            StoredEffect previous = decodeInvisibility(invis, player.getWorld().getGameTime());
            if (previous != null) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY,
                    previous.duration, previous.amplifier,
                    previous.ambient, previous.particles, previous.icon));
            }
        }

        pdc.remove(previousModeKey);
        pdc.remove(previousInvisKey);
        pdc.remove(originKey);

        Location destination = endAt;
        if (destination == null) {
            destination = decode(origin);
            if (destination == null) destination = fallbackOrigin;
        }
        if (destination != null) {
            // Wrapped, and the catch is the point rather than caution: this is
            // the step that can throw, and by now the body is already back —
            // so a failure here costs a position and nothing else.
            try {
                // The kept ending is teleported to as well, rather than trusting
                // wherever the player has drifted to since the last step: a couple
                // of ticks of falling happen between one and the finish, so "leave
                // them where the path left them" has to mean the spot the path
                // named.
                if (!player.teleport(destination)) {
                    // A refused teleport is a plugin cancelling the event or a
                    // player in a vehicle, and it leaves somebody standing
                    // wherever the emote's last step put them - worth a line,
                    // since that is exactly what looks like this code failing.
                    host.plugin().getLogger().warning(
                        "Emote ending teleport was refused for " + player.getName());
                }
                // And landed stopped. The client keeps its downward velocity
                // across our teleports, so a flip finishes moving at whatever a
                // second in the air earned it - that momentum is what drives
                // somebody through the block they were just placed on, and the
                // fall they never really took is not one to be hurt by.
                player.setVelocity(new Vector(0, 0, 0));
                player.setFallDistance(0f);
            } catch (RuntimeException e) {
                host.plugin().getLogger().warning(
                    "Couldn't put " + player.getName() + " back after an emote: " + e.getMessage());
            }
        }

        // Legacy only: a jar older than the invisibility swap left people in
        // spectator, and an upgrade must not stand them there forever.
        if (mode != null) {
            try {
                player.setGameMode(GameMode.valueOf(mode));
            } catch (IllegalArgumentException ignored) {
                // An unknown mode name means a Bukkit that renamed one; survival
                // is a safe landing and beats leaving them a spectator.
                player.setGameMode(GameMode.SURVIVAL);
            }
        }
    }

    /**
     * The invisibility this player already had, as something a PDC can hold.
     *
     * <p>Encoded rather than "did they have one", because giving back a plain
     * 30-second invisibility to somebody who was three minutes into a potion is
     * not giving it back. The tick it was captured at rides along so the
     * remainder can be worked out on the way out — see
     * {@link #decodeInvisibility}.
     *
     * <p>{@code "-"} means they had none, which is a different fact from the
     * marker being absent: absent means they were never emoting at all.
     */
    /**
     * Stops everybody else rendering this player at all.
     *
     * <p><b>The potion is not enough on its own, and that is why this exists.</b>
     * Invisibility hides the player's SKIN and nothing else: armour still
     * renders, so somebody emoting in a full set is a floating helmet and
     * boots standing inside their own rig — and their held item, their name
     * plate and the shadow under them all give the body away too. Which is
     * exactly what "the effect is applied but I can still see them" looks
     * like.
     *
     * <p>{@code hidePlayer} does not send the entity at all, so there is
     * nothing left to render — armour included, and with no items to take off
     * anybody and hand back, which is the trade this deliberately avoids.
     *
     * <p>The potion stays, and is not redundant: Bukkit cannot hide a player
     * from THEMSELVES, so it is the only thing covering the emoter's own view
     * of their body in third person. The two together are what the spectator
     * swap used to do alone.
     */
    private void hideFromOthers(Player player) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.getUniqueId().equals(player.getUniqueId())) viewer.hidePlayer(host.plugin(), player);
        }
    }

    /** And back. Showing somebody who was never hidden is a no-op. */
    private void showToOthers(Player player) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.getUniqueId().equals(player.getUniqueId())) viewer.showPlayer(host.plugin(), player);
        }
    }

    static String encodeInvisibility(PotionEffect effect, long now) {
        if (effect == null) return "-";
        return encodeInvisibility(effect.getDuration(), effect.getAmplifier(),
            effect.isAmbient(), effect.hasParticles(), effect.hasIcon(), now);
    }

    /**
     * The same, from primitives.
     *
     * <b>Free of Bukkit so it can be tested</b>, exactly like
     * {@link #applyPropStep} and {@link #rigToWorld}: {@code PotionEffectType}
     * reads a registry that only exists inside a running server, so a test that
     * built a real effect could not run at all. The arithmetic is the part
     * worth testing and none of it needs the API.
     */
    static String encodeInvisibility(
            int duration, int amplifier, boolean ambient, boolean particles, boolean icon, long now) {
        return duration + "," + amplifier + "," + ambient + "," + particles + "," + icon + "," + now;
    }

    /** One stored effect, with nothing of Bukkit's in it. See StoredEffect. */
    static final class StoredEffect {
        final int duration;
        final int amplifier;
        final boolean ambient;
        final boolean particles;
        final boolean icon;

        StoredEffect(int duration, int amplifier, boolean ambient, boolean particles, boolean icon) {
            this.duration = duration;
            this.amplifier = amplifier;
            this.ambient = ambient;
            this.particles = particles;
            this.icon = icon;
        }
    }

    /**
     * Their own effect back, with the time the emote took deducted.
     *
     * <p>Deducted rather than restored whole, because an emote is not a pause
     * on a potion — standing still for twenty seconds of a handshake should
     * cost twenty seconds of invisibility, exactly as it would have if we had
     * never touched it. An effect that would have run out in the meantime comes
     * back as null, which is the honest answer rather than a second of it.
     *
     * <p>Infinite stays infinite: there is nothing to deduct from, and it is
     * the one duration where arithmetic would end a potion that should not end.
     */
    static StoredEffect decodeInvisibility(String raw, long now) {
        if (raw == null || raw.equals("-")) return null;
        String[] parts = raw.split(",");
        if (parts.length != 6) return null;
        try {
            int duration = Integer.parseInt(parts[0]);
            int amplifier = Integer.parseInt(parts[1]);
            boolean ambient = Boolean.parseBoolean(parts[2]);
            boolean particles = Boolean.parseBoolean(parts[3]);
            boolean icon = Boolean.parseBoolean(parts[4]);
            long captured = Long.parseLong(parts[5]);

            int remaining;
            if (duration < 0) {
                remaining = PotionEffect.INFINITE_DURATION;
            } else {
                // A world that went backwards (a restore from backup, a
                // /time set) reads as no time passing rather than as a
                // negative elapsed that would hand back MORE than they had.
                long elapsed = Math.max(0, now - captured);
                long left = duration - elapsed;
                if (left <= 0) return null;
                remaining = (int) Math.min(Integer.MAX_VALUE, left);
            }
            return new StoredEffect(remaining, amplifier, ambient, particles, icon);
        } catch (NumberFormatException e) {
            // A marker we cannot read means we cannot honestly give anything
            // back; they simply end up visible, which is the safe direction.
            return null;
        }
    }

    private void restoreIfStranded(Player player) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        if (pdc.has(previousInvisKey, PersistentDataType.STRING)
                || pdc.has(previousModeKey, PersistentDataType.STRING)) {
            restore(player, null, (Location) null);
            if (host.messages() != null) host.messages().restoredAfterInterruption(player);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        restoreIfStranded(event.getPlayer());
        // Somebody who joins mid-emote was never told to hide anybody, so
        // without this they are the one player in the world who can see a body
        // standing inside its own rig.
        for (UUID id : active.keySet()) {
            Player emoting = Bukkit.getPlayer(id);
            if (emoting != null && !emoting.getUniqueId().equals(event.getPlayer().getUniqueId())) {
                event.getPlayer().hidePlayer(host.plugin(), emoting);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastStart.remove(event.getPlayer().getUniqueId());
        Session session = active.get(event.getPlayer().getUniqueId());
        if (session == null) return;
        // The whole troupe: their partners are still here and still owed their
        // bodies back, and a rig standing where somebody logged out is litter.
        // The quitter's own marker stays on them, deliberately — they are gone
        // before the game mode can be put back, so onJoin is what finishes it.
        endTroupe(session, EmoteEndEvent.Cause.QUIT);
    }

    /**
     * Whether this player took damage recently enough that an emote would be
     * an escape.
     *
     * Tracked from the damage event rather than read off the player, because
     * Bukkit exposes no "ticks since last damage" — {@code getNoDamageTicks}
     * is the brief post-hit invulnerability and expires far sooner than the
     * window worth refusing on.
     */
    /**
     * Why this player may not start an emote right now, or null if they may.
     *
     * <p><b>{@code Player#isOnGround()} is not consulted, and that is the whole
     * point of this method.</b> For a player that flag is whatever their client
     * last said in a movement packet — Paper deprecates it saying so — and a
     * modified client can hold it true while falling down a cliff. So the
     * question is asked of the world instead: is there a solid block under the
     * box they occupy, and are they in any of the states that mean "not simply
     * standing somewhere".
     *
     * <p>The probe tests the four corners of the player's footprint as well as
     * its centre, because standing on the lip of a block is standing on it and
     * refusing that would read as the command being broken.
     *
     * <p>It is deliberately a REFUSAL rather than a boolean: each state has its
     * own sentence, and "you can't do that right now" with no reason is the
     * class of message people report as a bug.
     */
    /**
     * The whole-body transform at time {@code t}, or the identity.
     *
     * Identity whenever the emote has no root, or the manifest that carried it
     * predates the root and therefore ships no pivot to turn about. Both are
     * ordinary states on a server that has not re-synced, not errors.
     */
    private Matrix4f rootMatrix(Session session, double t) {
        Matrix4f m = new Matrix4f();
        Map<String, List<Keyframe>> root = session.root;
        float[] pivot = emotes.rootPivot();
        if (root == null || root.isEmpty() || pivot == null) return m;
        RigMath.applyStep(m, Collections.singletonMap(ROOT_TARGET, root), ROOT_TARGET, pivot, t);
        return m;
    }

    /**
     * Moves the player along the root's path, or stops trying.
     *
     * <p><b>The player ends where the path ends</b> — that is what the Move
     * the player toggle promises, and for a while this put everybody back on
     * the origin however the emote ended, which made the toggle a lie. The
     * safety story is unchanged by keeping the ending: every spot along the
     * way was accepted by {@link #safeDestination} before the player was
     * stepped onto it, so the place they finish is one they already stood.
     * The paths that CAN'T vouch for that still go home: a refused step ends
     * following and the finish restores to origin, and a restart's stranded
     * marker knows nothing of the path, so it restores too.
     *
     * <p>Through the emote the player travels the arc, so a flip carries
     * their view forward and back rather than spinning a rig in front of a
     * stationary camera — and then leaves them where it lands.
     *
     * <p>Every step is checked against the world and refused rather than
     * clamped, and one refusal ends following for the rest of the emote - a
     * path that has left its trajectory is not one to rejoin halfway.
     */
    private void follow(Player player, Session session, double t) {
        if (!session.following) return;
        Map<String, List<Keyframe>> root = session.root;
        float[] pivot = emotes.rootPivot();
        if (root == null || pivot == null) {
            stopFollowing(session, "no root pivot in the manifest");
            return;
        }
        List<Keyframe> track = root.get("position");
        if (track == null || track.isEmpty()) {
            stopFollowing(session, "root has no position track");
            return;
        }

        // The same sampler the rig is posed with, so the body and the player
        // are reading one curve rather than two that agree until they do not.
        float[] offset = Sampler.sample(root, "position", t, new float[] {0f, 0f, 0f});
        if (offset == null || offset.length != 3 || !finite(offset)) {
            stopFollowing(session, "position sample was not a finite point");
            return;
        }

        // Model px to blocks, then into the world, through the one conversion
        // that knows rig space reaches the world via 180 - yaw. This used to do
        // the arithmetic inline and was missing that 180, so an emote that
        // walked you forward walked you backward — see rigToWorld.
        double[] world = rigToWorld(session.yaw, offset);
        if (world == null) {
            stopFollowing(session, "rig-to-world conversion failed");
            return;
        }

        Location target = session.origin.clone().add(world[0], world[1], world[2]);
        Location from = session.expected != null ? session.expected : session.origin;

        if (session.origin.distanceSquared(target) > MAX_ROOT_DISTANCE * MAX_ROOT_DISTANCE
                || from.distanceSquared(target) > MAX_ROOT_STEP * MAX_ROOT_STEP) {
            stopFollowing(session, String.format(java.util.Locale.ROOT,
                "step refused at t=%.2f (from-origin %.2f, step %.2f)",
                t, Math.sqrt(session.origin.distanceSquared(target)),
                Math.sqrt(from.distanceSquared(target))));
            return;
        }
        if (!safeDestination(target)) {
            // Authored tracks dip a pixel or two below the floor wherever a
            // body crouches or lands — the RIG may sink into its pose, but the
            // player's feet cannot go below the ground they started on, and
            // refusing over it is what made root motion die at t=0.1 (the
            // wind-up crouch) or on the landing frame two blocks from home.
            // Lift the target back to the starting floor before giving up;
            // only a genuinely blocked spot (a wall, raised ground) refuses.
            Location lifted = target.clone();
            lifted.setY(Math.max(target.getY(), session.origin.getY()));
            if (lifted.getY() > target.getY() && safeDestination(lifted)) {
                target = lifted;
            } else {
                stopFollowing(session, String.format(java.util.Locale.ROOT,
                    "blocked at t=%.2f (from-origin %.2f)",
                    t, Math.sqrt(session.origin.distanceSquared(target))));
                return;
            }
        }

        // Their own facing is left alone: the emote does not own where somebody
        // is looking, and rewriting it every tick fights the mouse.
        target.setYaw(player.getLocation().getYaw());
        target.setPitch(player.getLocation().getPitch());
        session.expected = target;
        player.teleport(target);
    }

    /**
     * Marks one display as something that will be moved every pass.
     *
     * <p>A display's position is a packet like any other, so without a teleport
     * duration the client SNAPS to each new spot — twenty visible jumps a
     * second, which reads as the rig lagging behind its owner rather than
     * being worn by them.
     *
     * <p><b>The window is one tick LONGER than the gap between steps</b>, and
     * that difference is what stops a wobbly connection reading as a stutter.
     * Set equal, the client finishes moving exactly as the next update is due,
     * so a packet a few milliseconds late leaves the rig standing still and
     * then jumping. See {@link #INTERPOLATION_TICKS}.
     *
     * <p>Only ever set on a stance. An ordinary emote's displays are spawned
     * where they will stand and never moved, and a teleport duration on one
     * would be a promise about a packet that is never sent.
     *
     * <p>Takes a {@link Display} rather than an ItemDisplay because the name
     * over the rig is carried on exactly the same terms as the bones under it.
     */
    private static void carry(Display display) {
        display.setTeleportDuration(INTERPOLATION_TICKS);
    }

    /**
     * The floating name over one participant's rig.
     *
     * <p>Spawned for every emote, not only a worn one: hiding the body takes
     * the nametag with it either way, and a rig nobody can put a name to is
     * the same puzzle whether it is dancing for two seconds or being worn all
     * afternoon.
     *
     * <p><b>The wearer sees it too, and hiding it from them was a mistake worth
     * writing down.</b> The reasoning was that vanilla never shows you your own
     * name, so putting one over your head reads as the plugin leaking — true of
     * a PLAYER's nametag and wrong about this one, which labels a rig standing
     * in the world that you are looking at on purpose. Somebody testing an
     * emote alone is the commonest way this feature is ever seen, and for them
     * hiding it from the wearer is indistinguishable from the nametag not
     * working at all. That is exactly how it was reported.
     */
    private TextDisplay spawnNameTag(Player player, Session session) {
        String label = nameTagText(player);
        if (label.isEmpty()) return null;
        Location at = session.origin.clone().add(0, nameTagY(player.isSneaking()), 0);
        at.setYaw(0);
        at.setPitch(0);
        final boolean carried = session.stance();
        final boolean sneaking = player.isSneaking();
        TextDisplay tag = at.getWorld().spawn(at, TextDisplay.class, d -> {
            d.setText(label);
            // Turns to face whoever is reading it, which is what a nametag
            // does — a fixed one is unreadable from three quarters of the
            // angles somebody might be standing at.
            d.setBillboard(Display.Billboard.CENTER);
            // Vanilla's own plate and shadow rather than a hand-mixed
            // background: matching it exactly is the difference between a
            // nametag and a floating sign, and there is nothing to gain by
            // being a shade off.
            d.setSeeThrough(!sneaking);
            d.setViewRange(sneaking ? SNEAK_NAMETAG_RANGE : 1f);
            d.setDefaultBackground(true);
            // <b>No drop shadow, because a real nametag has none.</b> Vanilla
            // draws a name plate through the font with shadow off — it is about
            // the only place in the game that does — and a display defaults it
            // on, so ours came out a shade heavier than every other name in the
            // world. The dark plate behind it is what makes it readable, and
            // that IS on.
            d.setShadowed(false);
            d.setPersistent(false);
            if (carried) carry(d);
        });
        session.nameTagSneaking = sneaking;
        return tag;
    }

    /**
     * How high the name's DISPLAY stands, for a body in this posture.
     *
     * <p>Two corrections to "the height vanilla draws a name at", and the first
     * one is why the tag used to float a line clear of where it belonged. A
     * vanilla nametag hangs DOWN from {@link #NAMETAG_Y}: the text is drawn at
     * that point and the glyphs descend from it. A {@link TextDisplay} is the
     * other way up — the game lifts its text by its own height before drawing,
     * so the position is the BOTTOM of the line. Spawning one at the number
     * vanilla uses therefore puts the whole line above where a real name sits,
     * and taking {@link #NAMETAG_LINE} back off is what lines the two up.
     *
     * <p>The second is the crouch: vanilla's height is the entity's plus half a
     * block, and a crouching player is shorter, so their name comes down with
     * them. A stance is worn while its wearer crouches, which is exactly when
     * that mattered and nothing here was doing it.
     */
    private static double nameTagY(boolean sneaking) {
        return NAMETAG_Y - NAMETAG_LINE - (sneaking ? SNEAK_NAMETAG_DROP : 0);
    }

    /**
     * The name over the rig, exactly as the game would have drawn it.
     *
     * <p><b>A player's nametag is their SCOREBOARD name, not their display
     * name, and the two are different things.</b> Vanilla renders team prefix +
     * team colour + account name + team suffix — that is the whole rule, and it
     * is why {@code setDisplayName} (which most chat plugins set) changes what
     * appears in chat and nothing at all above anybody's head. So the tag is
     * built from the team, and a player on a coloured team gets their colour
     * here for the same reason they have it in game.
     *
     * <p><b>The colour codes are KEPT.</b> They used to be stripped, on the
     * belief that a TextDisplay renders the section sign literally. It does
     * not: {@code setText(String)} goes through the same server-side conversion
     * every coloured chat message does, which parses the codes into a styled
     * component before anything is sent — so a display takes them exactly as
     * {@code sendMessage} does. Stripping them was throwing away the one thing
     * this is trying to copy.
     *
     * <p>Falls back to the DISPLAY name when no team dresses them, because a
     * server that puts a rank in the display name and nowhere else has put it
     * there deliberately, and the account name alone would be less of that
     * person's name than what everyone is used to seeing. A display name that
     * is nothing but codes leaves an empty string, and a nametag with no name
     * in it is worse than the plain one.
     */
    private static String nameTagText(Player player) {
        String fromTeam = teamName(player);
        if (fromTeam != null) return fromTeam;
        String display = player.getDisplayName();
        return display == null || stripped(display).isEmpty() ? player.getName() : display;
    }

    /**
     * The team-formatted name, or null if the team does not dress it.
     *
     * <p><b>A team that says nothing is not an answer.</b> Plenty of servers
     * put everybody on a team for collision or for whether names show at all,
     * with no prefix, suffix or colour on it — and taking that as the answer
     * would hand back the bare account name and throw away a rank the display
     * name was carrying. So an undressed team falls through to the display
     * name, which is the same thing a player on no team at all gets: whichever
     * of the two names we hold says more about them, rather than an answer that
     * depends on a scoreboard decision made for another reason entirely.
     *
     * <p>Wrapped, and the catch is the point: the scoreboard belongs to the
     * server and a plugin that swaps one out from under us can throw from any
     * of these reads. A name is decoration, and no emote is worth losing to it.
     */
    private static String teamName(Player player) {
        try {
            if (player.getScoreboard() == null) return null;
            org.bukkit.scoreboard.Team team = player.getScoreboard().getEntryTeam(player.getName());
            if (team == null) return null;
            String prefix = team.getPrefix() == null ? "" : team.getPrefix();
            String suffix = team.getSuffix() == null ? "" : team.getSuffix();
            // The colour is written before the name rather than at the front of
            // the whole thing: a prefix carries its own colours, and a code in
            // front of it would be overwritten by the first one inside it.
            String colour = "";
            org.bukkit.ChatColor teamColour = team.getColor();
            if (teamColour != null && teamColour != org.bukkit.ChatColor.RESET) {
                colour = teamColour.toString();
            }
            if (prefix.isEmpty() && suffix.isEmpty() && colour.isEmpty()) return null;
            String built = prefix + colour + player.getName() + suffix;
            return stripped(built).isEmpty() ? null : built;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /**
     * The same text with legacy codes taken out, for asking whether there is
     * anything in it. Never what is DRAWN — see {@link #nameTagText}.
     *
     * <p>The sign is a NUMBER here, not the character and not a unicode escape.
     * A literal section sign in a source file is at the mercy of whichever
     * encoding javac was launched with, and an escape is worse: javac
     * translates those before the parser runs, comments included, so writing
     * one even in prose here is a compile error. (It was, once, in this very
     * comment.) 0xA7 is neither.
     */
    private static final char SECTION_SIGN = (char) 0xA7;

    private static String stripped(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // The code is the sign plus whatever follows it, which is what
            // makes skipping two characters right even for an invalid one.
            if (c == SECTION_SIGN && i + 1 < text.length()) {
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString().trim();
    }

    /**
     * Shows or hides one participant's name along with their rig.
     *
     * <p>They go together and must never disagree. A group crossing into a
     * state it leaves to vanilla puts the rig away and gives the body back —
     * and the body brings its own real nametag with it, so leaving ours up
     * would print the same person's name twice, one above the other.
     *
     * <p>Hidden by RANGE rather than by removing the display, because a set can
     * cross that boundary several times a second — the same reasoning
     * {@link #setRigHidden} gives for swapping items instead of entities. The
     * shown range is whatever the wearer's posture says, so bringing a name
     * back over a crouching player does not also un-crouch it.
     */
    private static void setNameTagShown(Session session, boolean shown) {
        session.nameTagShown = shown;
        TextDisplay tag = session.nameTag;
        if (tag == null || !tag.isValid()) return;
        tag.setViewRange(shown ? (session.nameTagSneaking ? SNEAK_NAMETAG_RANGE : 1f) : 0f);
    }

    /**
     * Draws the name the way a crouching player's is drawn, or stops.
     *
     * <p>Half the range and no seeing it through walls, which is what vanilla
     * does and what makes a crouch read as hiding. Written only when the answer
     * changes: both are entity metadata, so re-sending them every pass would be
     * two packets a tick to everybody nearby for a fact that changes when
     * somebody presses shift.
     */
    private static void setNameTagSneaking(Session session, boolean sneaking) {
        if (session.nameTagSneaking == sneaking) return;
        session.nameTagSneaking = sneaking;
        TextDisplay tag = session.nameTag;
        if (tag == null || !tag.isValid()) return;
        tag.setSeeThrough(!sneaking);
        if (session.nameTagShown) tag.setViewRange(sneaking ? SNEAK_NAMETAG_RANGE : 1f);
    }

    /**
     * What the wearer is doing, as the one state that decides playback.
     *
     * <p><b>Exactly one, and the precedence is not arbitrary.</b> Crouching
     * wins because a crouch-walk reads as crouching to everybody watching it,
     * and sprinting wins over walking because the client only sets the sprint
     * flag while actually running. Without a total order an emote authored for
     * "walking" would also play during a crouch-walk, and two states holding at
     * once has no meaning on a timeline that has one clock.
     *
     * <p><b>Crouching answers with one of TWO states</b>, standing or moving,
     * for the same reason walking and standing are not one state: they are two
     * different things a body does, and a set that could only name "sneaking"
     * had to pick which of them its one emote suited. The umbrella they came
     * from is never returned — see {@link EmoteTrigger#SNEAK} — and a pack that
     * named it is reached through {@link EmoteTrigger#fallback()} at lookup
     * instead, so nothing here has to know which vintage of manifest it is
     * driving.
     *
     * <p>Movement is measured from the previous pass rather than read off
     * velocity: {@code Player#getVelocity} is the server's idea of a body it
     * does not simulate, and for a walking player it is usually zero. The
     * comparison is HORIZONTAL — falling down a shaft is not walking, and a
     * stance authored for idle should keep playing while its wearer drops.
     */
    static EmoteTrigger stanceState(boolean sneaking, boolean sprinting, boolean moving, boolean airborne) {
        if (airborne) return EmoteTrigger.JUMP;
        if (sneaking) return moving ? EmoteTrigger.SNEAK_MOVE : EmoteTrigger.SNEAK_IDLE;
        if (!moving) return EmoteTrigger.IDLE;
        return sprinting ? EmoteTrigger.SPRINT : EmoteTrigger.WALK;
    }

    /**
     * The emote a set wears in this state, or null for its own body.
     *
     * <p>The exact state first, then the broader one an older pack would have
     * named — which is the whole of what makes a set built before crouching
     * was split go on working: its one {@code sneak} entry answers both
     * crouching states, exactly as it did when there was only one.
     */
    static EmoteStore.Emote memberFor(Map<EmoteTrigger, EmoteStore.Emote> members, EmoteTrigger state) {
        if (members == null || state == null) return null;
        EmoteStore.Emote exact = members.get(state);
        if (exact != null) return exact;
        EmoteTrigger fallback = state.fallback();
        return fallback == null ? null : members.get(fallback);
    }

    /**
     * Whether a stance's clock runs in this state.
     *
     * <p>The same two-step lookup {@link #memberFor} does, and it has to be:
     * a stance authored for {@code sneak} names the umbrella, and asking only
     * whether it named the exact state would leave it frozen at frame zero the
     * moment somebody crouched.
     */
    static boolean plays(Set<EmoteTrigger> triggers, EmoteTrigger state) {
        if (triggers == null || triggers.isEmpty() || state == null) return false;
        if (triggers.contains(state)) return true;
        EmoteTrigger fallback = state.fallback();
        return fallback != null && triggers.contains(fallback);
    }

    /**
     * The same ground states, for a caller that does not care about the air.
     *
     * <p>Kept as an overload rather than folded away because it is the whole of
     * the compatibility rule: a stance that never named {@link
     * EmoteTrigger#JUMP} is resolved with this, so being off the ground goes on
     * reading as whatever the body is otherwise doing — which is what such a
     * stance has always done, deliberately. See {@link EmoteTrigger}.
     */
    static EmoteTrigger stanceState(boolean sneaking, boolean sprinting, boolean moving) {
        return stanceState(sneaking, sprinting, moving, false);
    }

    /**
     * Whether this player is off the ground, for the purposes of a stance.
     *
     * <p><b>BOTH answers have to say yes, and that asymmetry is the point.</b>
     * {@link #standingOnSomething} asks whether there is a SOLID block just
     * under the feet, which is the right question for "may somebody be put
     * here" and the wrong one for "are they falling": carpet, a snow layer, a
     * lily pad, a ladder, a boat and standing in water all answer no while the
     * player is plainly not in the air. Taken alone it put a motionless player
     * into {@link EmoteTrigger#JUMP} — and a group that leaves jumping to
     * vanilla answers that state by putting the rig away, so the whole emote
     * looked like it had simply stopped working while they stood still.
     *
     * <p>{@code isOnGround} is the client's own flag, which is exactly what
     * decides the animation vanilla itself plays — and it is spoofable, which
     * is why it is not trusted for anything but this. Requiring both means the
     * error falls toward IDLE, which is the direction a cosmetic should fail
     * in: the worst case is a jump that reads as standing for one pass, rather
     * than a stander who flickers out of their own emote.
     */
    // isOnGround is deprecated on a Player precisely BECAUSE the client owns
    // it. That is the caveat this method is built around rather than one it
    // ignores: a spoofed flag can only ever make somebody read as standing,
    // which is the same answer they would get with no check at all.
    @SuppressWarnings("deprecation")
    private boolean airborne(Player player, Location now) {
        return !player.isOnGround() && !standingOnSomething(now);
    }

    /**
     * Whether one pass carried the player far enough to count as a gait.
     *
     * <p>Horizontal only, on purpose. Falling down a shaft is not walking, and
     * a stance authored for {@link EmoteTrigger#IDLE} should keep playing while
     * its wearer drops rather than flicking to a walk cycle in mid-air.
     *
     * <p>Worlds are compared by identity rather than {@code equals}: Bukkit
     * hands out one instance per world, both being null is the case a test
     * builds, and {@code equals} on a null receiver is the NPE this avoids.
     *
     * <p>Package-visible and free of the Player interface so it can be tested,
     * exactly like {@link #rigToWorld} and {@link #applyPropStep} above.
     */
    static boolean movedHorizontally(Location previous, Location now) {
        if (previous == null || now == null) return false;
        if (previous.getWorld() != now.getWorld()) return false;
        double dx = previous.getX() - now.getX();
        double dz = previous.getZ() - now.getZ();
        return dx * dx + dz * dz > STANCE_MOVING_STEP * STANCE_MOVING_STEP;
    }

    /**
     * Moves this session's lead on by one pass. See {@link #LEAD_PIPELINE_TICKS}.
     *
     * <p>Its own method so the ping — the one part that needs a live player —
     * is read here and the arithmetic under it is reachable from a test.
     */
    private void advanceLead(Player player, Session session, Location now) {
        double ticks = LEAD_PIPELINE_TICKS;
        try {
            // Halved: what matters is how stale the position they sent us is,
            // which is one leg of the round trip rather than both.
            ticks += Math.max(0, player.getPing()) / 100.0;
        } catch (RuntimeException | LinkageError e) {
            // A server that cannot answer gets the pipeline delay alone, which
            // is the part that is true of every connection including a local one.
            ticks = LEAD_PIPELINE_TICKS;
        }
        session.lead = leadFor(session.lead, session.previous, now, Math.min(ticks, MAX_LEAD_TICKS));
    }

    /**
     * Where the rig should sit relative to the position we were handed.
     *
     * <p>One step of dead reckoning: the horizontal step this pass, times the
     * delay owed, capped, and then mixed into the previous answer rather than
     * replacing it — see {@link #LEAD_SMOOTHING}.
     *
     * <p>Vertical is deliberately not led. A jump and a fall are the two things
     * a body does that our own arithmetic cannot improve on: the client is
     * already simulating gravity and a rig led upward would leave the ground
     * before its wearer did.
     *
     * <p>Package-visible and free of the Player interface so it can be tested,
     * exactly like {@link #rigToWorld} and {@link #movedHorizontally}.
     */
    static Vector leadFor(Vector current, Location previous, Location now, double leadTicks) {
        Vector wanted = new Vector();
        if (previous != null && now != null && previous.getWorld() == now.getWorld()) {
            wanted = new Vector(
                (now.getX() - previous.getX()) * leadTicks,
                0,
                (now.getZ() - previous.getZ()) * leadTicks);
            double length = wanted.length();
            // A teleport, a knockback or a lag spike is not a stride, and
            // reckoning from one would fling the rig across the room. Capped
            // rather than refused: the direction is still right, and the next
            // pass re-reads the truth anyway.
            if (length > MAX_LEAD && length > 0) wanted.multiply(MAX_LEAD / length);
            if (!Double.isFinite(wanted.getX()) || !Double.isFinite(wanted.getZ())) wanted = new Vector();
        }
        Vector from = current == null ? new Vector() : current;
        Vector next = new Vector(
            from.getX() + (wanted.getX() - from.getX()) * LEAD_SMOOTHING,
            0,
            from.getZ() + (wanted.getZ() - from.getZ()) * LEAD_SMOOTHING);
        // And it has to actually REACH nothing — see LEAD_DEAD_ZONE. Snapping
        // is invisible at a hundredth of a block and is what lets a standing
        // rig go quiet instead of chasing the tail of a halving sequence for
        // the rest of the emote.
        return next.lengthSquared() < LEAD_DEAD_ZONE * LEAD_DEAD_ZONE ? new Vector() : next;
    }

    /**
     * One pass of a worn emote: rig moved onto the player, clock advanced.
     *
     * <p>Nothing here asks whether they moved away, because there is nowhere to
     * move away from — that is the whole difference between this and the
     * ordinary path in {@link #tick}.
     *
     * <p><b>The clock is expressed by moving {@code startTick}, not by a second
     * elapsed field.</b> While the wearer is not in a state this emote plays
     * for, `startTick` is dragged along to now, so the elapsed time both this
     * method and {@link #pose} compute independently is zero and the rig holds
     * its first frame. The moment they enter one, `startTick` is left where it
     * is and the same subtraction starts counting. One field, and no way for
     * the two readers of it to disagree.
     *
     * @return false when the stance cannot continue and has been ended.
     */
    private boolean tickStance(Player player, Session session) {
        Location now = player.getLocation();
        // The rig lives in the world it was spawned in and an entity cannot be
        // teleported across one, so a portal ends the stance rather than
        // leaving six items standing in the world behind — a rig nobody can
        // see and nothing will ever collect. Said out loud rather than
        // silently, because from the wearer's side their emote simply came off
        // and the reason is not on screen anywhere else.
        //
        // `origin` is what the previous pass left here — for a stance it
        // tracks the wearer rather than naming a fixed spot (nothing else
        // reads it: root motion is off, and the ending passes no origin at
        // all), so this compares the world they were in last pass with the one
        // they are in now.
        if (session.origin.getWorld() == null || !session.origin.getWorld().equals(now.getWorld())) {
            stop(player, false, EmoteEndEvent.Cause.MOVED);
            return false;
        }

        // A step arms the hold and every pass runs it down, so a gap between
        // movement packets — which is what a walk over a real connection looks
        // like from here — cannot read as a stop. See MOVING_HOLD_PASSES.
        if (movedHorizontally(session.previous, now)) {
            session.movingFor = MOVING_HOLD_PASSES;
        } else if (session.movingFor > 0) {
            session.movingFor--;
        }
        boolean moving = session.movingFor > 0;
        boolean sneaking = player.isSneaking();
        boolean airborne = airborne(player, now);
        // Read before `previous` is overwritten, because it is the difference
        // between the two. See LEAD_PIPELINE_TICKS for what it is for.
        advanceLead(player, session, now);
        session.previous = now.clone();
        long tick = player.getWorld().getGameTime();

        // Where the rig stands: one block above the feet, on the player's own
        // centre line, exactly as `begin` placed it — the only offset the whole
        // body fits the block-model bounds at — and forward by however far the
        // wearer will have travelled by the time this is on their screen.
        Location base = now.clone().add(session.lead.getX(), RIG_BASE_Y, session.lead.getZ());
        base.setYaw(0);
        base.setPitch(0);

        if (session.group != null) {
            // A group resolves every state, the air included — see
            // `EmoteTrigger`. Which emote drives the rig follows from it, and
            // a state the set left alone is the player's own body.
            EmoteTrigger state = stanceState(sneaking, player.isSprinting(), moving, airborne);
            EmoteStore.Emote member = memberFor(session.members, state);
            // Keyed on the EMOTE rather than the state: two states answered by
            // one emote is an ordinary shape (every set built before crouching
            // was split has it), and swapping on the state would restart that
            // one emote's clock each time the wearer crossed between them.
            if (member != session.memberEmote || session.memberState == null) {
                session.memberState = state;
                session.memberEmote = member;
                session.emote = member != null ? member : session.rest;
                session.animators = member != null && member.animators != null
                    ? member.animators
                    : Collections.<String, Map<String, List<Keyframe>>>emptyMap();
                session.root = member != null ? member.root : null;
                // From the top, every time. A walk cycle joined halfway through
                // because the last state happened to have run for two seconds
                // is a limp, and the emotes in a set have no reason to share a
                // length for their phases to line up.
                session.startTick = tick;
                // Props belong to the emote, not to the set, so the old one's
                // models go away and the new one's stand up. Bones are shared
                // and are never respawned.
                spawnProps(player, session, session.emote, base, null);
                boolean wasHidden = session.rigHidden;
                setRigHidden(player, session, member == null);
                // <b>Only where a tween would be wrong, not on every swap.</b>
                // Easing between a walk and a run is two cycles blending, which
                // is what you want; easing out of a rig that was put away is a
                // tween from whatever pose it happened to be holding when it
                // went, which is not a transition anybody authored.
                if (wasHidden) session.snap = true;
            } else {
                session.memberState = state;
            }
        } else {
            // A plain stance names its own states, and one that never named
            // JUMP is resolved without it — so the air goes on reading as
            // whatever the body is otherwise doing, exactly as it did before
            // that state existed. See `EmoteTrigger`.
            EmoteTrigger state = stanceState(
                sneaking, player.isSprinting(), moving,
                airborne && session.triggers.contains(EmoteTrigger.JUMP));
            if (!plays(session.triggers, state)) session.startTick = tick;
        }
        // Their facing, unrounded. `begin` rounds to whole degrees because an
        // emote is authored at one fixed yaw and a hundredth of a degree there
        // is noise; here it is the difference between a body that turns
        // smoothly with the mouse and one that steps between degrees.
        session.yaw = now.getYaw();
        session.origin = now.clone();
        // Two passes that send nothing, and between them they are what pays for
        // stepping every tick rather than every other one:
        //
        // A rig that is AWAY is not carried at all. Its displays are standing
        // where its wearer left them holding air, and a set crossing into a
        // state it leaves to vanilla can hold that for as long as somebody
        // keeps running. `setRigHidden` forgets the position, so the pass that
        // brings the rig back is a pass that moves it.
        //
        // A rig that has not MOVED is not re-sent. A teleport is a packet per
        // display per viewer, and a stance is worn standing still at least as
        // often as it is worn walking.
        boolean moved = session.lastBase == null || !session.lastBase.equals(base);
        session.lastBase = base.clone();
        if (!session.rigHidden && moved) {
            for (ItemDisplay display : session.parts) {
                if (display != null && display.isValid()) display.teleport(base);
            }
            for (ItemDisplay display : session.propParts) {
                if (display != null && display.isValid()) display.teleport(base);
            }
            // Carried on the same terms as everything else. A hand left behind
            // would hold the sword where its owner was standing a moment ago,
            // which is the drift the lead exists to cancel.
            if (session.mainHand != null && session.mainHand.isValid()) session.mainHand.teleport(base);
            if (session.offHand != null && session.offHand.isValid()) session.offHand.teleport(base);
        }
        // The shadow follows the FEET rather than the base: the same point a
        // block lower, lead and all.
        //
        // It used to take NO lead, on the reasoning that a shadow running ahead
        // of the body it belongs to is the one part of this a player would read
        // as wrong straight away. That was the wrong body. Nobody who can see
        // this shadow can see the wearer's: a stance hides it from its own
        // wearer along with everything else the emote owns (see
        // hideFromWearer), and for every other player hidePlayer has taken the
        // body off the screen entirely. What is left to judge the shadow
        // against is the RIG, and the rig is led — so an un-led shadow trailed
        // it by as much as MAX_LEAD for as long as a set was walked around,
        // which is what "the shadow lags behind" was. A one-shot emote ends the
        // moment its wearer moves, so its lead is nothing and none of this ever
        // showed up there.
        //
        // No `moved` gate, because it is one entity rather than a dozen, and it
        // keeps moving while the rig is away — its radius is what was zeroed,
        // and a shadow left behind would be a blot waiting where the rig came
        // back.
        if (session.shadow != null && session.shadow.isValid()) {
            Location feet = now.clone().add(session.lead.getX(), 0, session.lead.getZ());
            feet.setYaw(0);
            feet.setPitch(0);
            if (!feet.equals(session.shadow.getLocation())) session.shadow.teleport(feet);
        }
        // The name's height moves with the crouch as well as with the feet, so
        // it is re-placed when either changed. Its x/z follow `base`, which is
        // the same point a block lower.
        if (session.nameTag != null && session.nameTag.isValid()
                && (moved || session.nameTagSneaking != sneaking)) {
            // The name goes on following even while the rig is away — it is
            // hidden by view range rather than by being left behind, and a tag
            // that stayed put would be sitting over wherever the wearer last
            // stood the moment it came back.
            // The same lead the rig gets: a name that stayed on the position we
            // were given would drift off the head it belongs to exactly as far
            // as the rig was drifting before.
            // Facing zeroed like the rig's. A billboarded name turns to the
            // reader whatever its own yaw is, so carrying the player's would
            // be a rotation in every packet that changes nothing on screen.
            Location tagAt = now.clone().add(session.lead.getX(), nameTagY(sneaking), session.lead.getZ());
            tagAt.setYaw(0);
            tagAt.setPitch(0);
            session.nameTag.teleport(tagAt);
            setNameTagSneaking(session, sneaking);
        }
        return true;
    }

    /** Gives up following for the rest of the emote, and says why once. */
    private void stopFollowing(Session session, String reason) {
        session.following = false;
        host.plugin().getLogger().info(
            "Root motion stopped for '" + (session.emote != null ? session.emote.name : "?") + "': " + reason);
    }

    private static boolean finite(float[] values) {
        for (float value : values) {
            if (Float.isNaN(value) || Float.isInfinite(value)) return false;
        }
        return true;
    }

    /**
     * Whether somebody may be put here: loaded, in a world, and not inside a
     * block. An unloaded chunk answers no rather than being loaded to find out
     * - generating terrain to place a cosmetic is not a trade worth making.
     */
    /**
     * The nearest place at or just below this spot that somebody can stand on.
     *
     * <p><b>Ground is now a requirement, and under spectator it was not.</b> A
     * spectator hovers wherever they are put; an invisible player falls. So a
     * performer placed over a one-block drop used to stand there and now drops
     * out of their own emote — and because a participant moving ends it for
     * everybody, one badly-placed partner cancelled the whole thing before it
     * played a frame. That is the bug this exists to stop.
     *
     * <p>Searching DOWN a few blocks rather than demanding an exact match is
     * what makes the feature usable off a flat floor: an emote's offsets are
     * horizontal, so a slab, a stair or a shallow slope puts the partner's
     * ground slightly below the lead's, and refusing those would refuse most
     * real builds. It never searches UP — lifting somebody onto a block they
     * were not placed on is a different spot from the one the emote authored.
     *
     * @return a standable location, or null if there is none.
     */
    /**
     * The end of a path, put down on the floor it finished on.
     *
     * <p>An authored landing dips the body into the ground on purpose - that
     * is what a flip's last frames look like - and which frame the last step
     * of the emote happened to sample is a coin toss, because steps land on a
     * two-tick grid and an emote's length does not divide by it. Half the
     * flips therefore ended a few centimetres inside the block, and vanilla
     * resolves a collision it is already inside by leaving it there: "the
     * right spot, legs through the floor" is where the player stays.
     *
     * <p>So the ending is not merely checked for somewhere the body FITS -
     * {@link #safeDestination} shrinks its box by a margin and would happily
     * accept a spot a hair under the surface, which is the 50/50 above. The
     * feet are snapped onto the real collision top under them, so a slab, a
     * stair or a path block lands as exactly as a full cube does.
     *
     * <p>Only ever upward, and never by more than a block. Somebody who
     * finishes in the air is left there for gravity to deal with, the way any
     * jump ends; a spot with no floor within a block is not a landing to tidy
     * up but a path that ended somewhere it should not have, and the caller
     * puts them back on the origin instead.
     *
     * @return where to land them, or null if there is nowhere to.
     */
    private Location grounded(Location end) {
        if (end == null || end.getWorld() == null) return null;
        Double floor = floorUnder(end);
        if (floor != null && floor > end.getY()) {
            Location landed = end.clone();
            landed.setY(floor);
            if (safeDestination(landed)) return landed;
        }
        // Nothing to snap to, or snapping put them somewhere they don't fit
        // (a landing under a slab). The old climb still covers those: any spot
        // within a block that holds a body beats leaving them in the floor.
        for (int step = 0; step <= 16; step++) {
            Location candidate = end.clone().add(0, step / 16.0, 0);
            if (safeDestination(candidate)) return candidate;
        }
        return null;
    }

    /**
     * The height of the surface under this spot, or null if there isn't one.
     *
     * <p>Collision tops, not block tops: the answer for a slab is y.5 and for
     * a stair it is whichever half the foot is over, which is the whole point
     * of asking. The five probes are the ones {@link #standingOnSomething}
     * uses - the centre and the four corners of the player's footprint - and
     * the highest of them wins, because that is the one holding the body up.
     *
     * <p>Searches a block either side of the spot. Anything higher than that
     * is a wall the player is standing against rather than a floor they are
     * standing on, and lifting them onto it would be a different place from
     * the one the emote authored.
     */
    private Double floorUnder(Location spot) {
        org.bukkit.World world = spot.getWorld();
        if (world == null) return null;
        double[][] offsets = {
            {0, 0}, {HALF_WIDTH, HALF_WIDTH}, {HALF_WIDTH, -HALF_WIDTH},
            {-HALF_WIDTH, HALF_WIDTH}, {-HALF_WIDTH, -HALF_WIDTH},
        };
        Double best = null;
        for (double[] offset : offsets) {
            double x = spot.getX() + offset[0];
            double z = spot.getZ() + offset[1];
            int blockX = (int) Math.floor(x);
            int blockZ = (int) Math.floor(z);
            if (!world.isChunkLoaded(blockX >> 4, blockZ >> 4)) continue;
            for (int y = (int) Math.floor(spot.getY() + 1.0); y >= (int) Math.floor(spot.getY() - 1.0); y--) {
                org.bukkit.block.Block block = world.getBlockAt(blockX, y, blockZ);
                if (!block.getType().isSolid()) continue;
                for (BoundingBox box : block.getCollisionShape().getBoundingBoxes()) {
                    BoundingBox placed = box.clone().shift(blockX, y, blockZ);
                    // Only the shapes this foot is actually over: a stair's
                    // upper step is not what holds up somebody standing on its
                    // lower half.
                    if (x < placed.getMinX() || x > placed.getMaxX()) continue;
                    if (z < placed.getMinZ() || z > placed.getMaxZ()) continue;
                    double top = placed.getMaxY();
                    if (top > spot.getY() + 1.0) continue;
                    if (best == null || top > best) best = top;
                }
            }
        }
        return best;
    }

    private Location standingSpot(Location target) {
        if (target == null || target.getWorld() == null) return null;
        for (int drop = 0; drop <= GROUND_SEARCH_DOWN; drop++) {
            Location candidate = target.clone().subtract(0, drop, 0);
            if (safeDestination(candidate) && standingOnSomething(candidate)) return candidate;
        }
        return null;
    }

    private boolean safeDestination(Location target) {
        if (target.getWorld() == null) return false;
        if (!target.getWorld().isChunkLoaded(target.getBlockX() >> 4, target.getBlockZ() >> 4)) return false;
        // Whether a PLAYER FITS here, asked of the blocks' real collision
        // shapes — not whether the block at floor(y) is solid. That older test
        // refused the spot the player was already standing on whenever they
        // stood on anything shorter than a full cube: on a slab your feet are
        // at y.5, so "the block at the target" IS the slab under you, and root
        // motion died on its first step everywhere the lobby is paved with
        // them. The box is the player's (0.6 x 1.8), shrunk by a margin so
        // standing exactly on a surface, or a teleport acknowledged a hair
        // low, doesn't read as being inside the floor.
        final double margin = 0.05;
        BoundingBox player = new BoundingBox(
            target.getX() - 0.3 + margin, target.getY() + margin, target.getZ() - 0.3 + margin,
            target.getX() + 0.3 - margin, target.getY() + 1.8 - margin, target.getZ() + 0.3 - margin);
        for (int x = (int) Math.floor(player.getMinX()); x <= (int) Math.floor(player.getMaxX()); x++) {
            for (int y = (int) Math.floor(player.getMinY()); y <= (int) Math.floor(player.getMaxY()); y++) {
                for (int z = (int) Math.floor(player.getMinZ()); z <= (int) Math.floor(player.getMaxZ()); z++) {
                    org.bukkit.block.Block block = target.getWorld().getBlockAt(x, y, z);
                    if (!block.getType().isSolid()) continue;
                    for (BoundingBox box : block.getCollisionShape().getBoundingBoxes()) {
                        // Block shapes are block-local; move this one into the world.
                        if (box.clone().shift(x, y, z).overlaps(player)) return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Why this cast cannot be assembled, or null if it can.
     *
     * <p>Every count mismatch is refused rather than padded or trimmed. A duet
     * run alone would put one person through the lead's half of a handshake
     * with nobody opposite, which reads as the emote being broken; and a solo
     * emote given a name is somebody expecting a thing that will not happen.
     */
    private EmoteResult castRefusal(
            Player player,
            EmoteStore.Emote emote,
            List<EmoteStore.Performer> performers,
            int named) {
        if (performers.isEmpty()) {
            if (named == 0) return null;
            return EmoteResult.refused(Reason.SOLO_EMOTE, emote.name);
        }
        if (!host.mayLeadCast(player)) {
            // The node is the host's, not ours, and it is the server owner who
            // grants it - so the caller is handed the node itself to name in
            // the sentence that gets the player unstuck.
            return EmoteResult.refused(Reason.CAST_NOT_PERMITTED, host.castPermission(), slotNames(performers));
        }
        if (named != performers.size()) {
            return EmoteResult.refused(Reason.CAST_WRONG_SIZE, emote.name, slotNames(performers));
        }
        return null;
    }

    /**
     * What each cast slot is called, in order, for a caller that has to ask for
     * them by name. An unnamed slot reads as "player", which is what somebody
     * typing the command needs to see.
     */
    private static List<String> slotNames(List<EmoteStore.Performer> performers) {
        List<String> slots = new ArrayList<>(performers.size());
        for (EmoteStore.Performer performer : performers) {
            slots.add(performer.name == null || performer.name.isEmpty()
                ? "player"
                : performer.name.toLowerCase(java.util.Locale.ROOT).replace(' ', '-'));
        }
        return slots;
    }

    /**
     * Why somebody cannot be pulled INTO an emote, or null if they can.
     *
     * <p>The same questions the lead answers about themselves, asked of a person
     * who did not type the command - plus the two that only apply to them:
     * whether they are already in an emote of their own, and whether they have
     * a rig to wear. Every reason names them, because the person who will read
     * it is the lead, and "you have to be on the ground" about somebody else is
     * a sentence that sends them looking at their own feet.
     */
    private EmoteResult participantRefusal(Player other) {
        if (active.containsKey(other.getUniqueId())) {
            return EmoteResult.refused(Reason.CAST_BUSY, other.getName());
        }
        if (emotes.rigFor(other.getUniqueId()) == null) {
            return EmoteResult.refused(Reason.CAST_NO_RIG, other.getName());
        }
        if (other.getGameMode() == GameMode.SPECTATOR) {
            return EmoteResult.refused(Reason.CAST_IN_SPECTATOR, other.getName());
        }
        if (groundRefusal(other) != null) {
            return EmoteResult.refused(Reason.CAST_NOT_ON_GROUND, other.getName());
        }
        return null;
    }

    /** Why this player is not somewhere an emote can happen, or null. */
    private Reason groundRefusal(Player player) {
        if (player.getVehicle() != null) {
            return Reason.RIDING;
        }
        if (player.isGliding()) {
            return Reason.GLIDING;
        }
        if (player.isFlying()) {
            return Reason.FLYING;
        }
        if (player.isSwimming() || player.isInWater()) {
            return Reason.IN_WATER;
        }
        Location feet = player.getLocation();
        Material at = feet.getBlock().getType();
        if (at == Material.LAVA || at == Material.WATER) {
            return Reason.IN_BLOCK;
        }
        if (!standingOnSomething(feet)) {
            return Reason.NOT_ON_GROUND;
        }
        return null;
    }

    /**
     * Whether anything solid holds up the box whose centre is this location.
     *
     * Reads the world, so it cannot be lied to by a client. Unloaded chunks are
     * treated as NOT ground: the alternative is loading a chunk to answer a
     * cosmetic question, and a player standing over one is not a case that has
     * to work.
     */
    private boolean standingOnSomething(Location feet) {
        double[][] offsets = {
            {0, 0}, {HALF_WIDTH, HALF_WIDTH}, {HALF_WIDTH, -HALF_WIDTH},
            {-HALF_WIDTH, HALF_WIDTH}, {-HALF_WIDTH, -HALF_WIDTH},
        };
        for (double[] offset : offsets) {
            Location probe = feet.clone().add(offset[0], -GROUND_PROBE, offset[1]);
            if (!probe.getWorld().isChunkLoaded(probe.getBlockX() >> 4, probe.getBlockZ() >> 4)) continue;
            Material material = probe.getBlock().getType();
            if (material.isSolid()) return true;
        }
        return false;
    }

    /**
     * A swing, on the rig, in the same tick the click arrived.
     *
     * <p><b>This listener is the entire reason a swing is not late.</b>
     * Everything else about a worn emote is discovered by the tick loop asking
     * the player what they are doing, which costs up to a full tick before
     * anything is even known — acceptable for a gait, where being a tick behind
     * a walk is invisible, and not acceptable for a swing, which is over in six.
     * {@code PlayerAnimationEvent} fires as the client's swing packet is read,
     * so this is the earliest moment the server can possibly know.
     *
     * <p>And it poses straight away rather than leaving it for the next pass,
     * with {@code immediate} set so the client is told to land the frame instead
     * of easing into it over {@link #INTERPOLATION_TICKS}. The two together are
     * what make the round trip the only delay left: the swing goes out on this
     * tick's packet, at the angle the arm should already be at (see
     * {@link ArmSwing#progress}), with no interpolation ramp in front of it.
     *
     * <p>{@code MONITOR} because this changes nothing about the event, and
     * {@code ignoreCancelled} because a plugin that cancelled the swing meant
     * it — an animation nobody else will see should not play on the rig either.
     */
    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwing(org.bukkit.event.player.PlayerAnimationEvent event) {
        if (event.getAnimationType() != org.bukkit.event.player.PlayerAnimationType.ARM_SWING) return;
        Player player = event.getPlayer();
        Session session = active.get(player.getUniqueId());
        if (session == null) return;
        // A rig that is away is the player's own body, and vanilla is already
        // animating the swing on it. Overlaying ours would be a second swing on
        // an arm nobody can see.
        if (session.rigHidden) return;
        session.swingTick = player.getWorld().getGameTime();
        session.swingOffHand = player.getMainHand() == org.bukkit.inventory.MainHand.LEFT;
        pose(player.getUniqueId(), session, true);
    }

    // There was a PlayerItemHeldEvent listener here, whose whole job was moving
    // the wearer's blanked hotbar slot along as they scrolled. Nothing blanks a
    // slot any more — see `conceal` — so scrolling is now just scrolling, and
    // the rig picks up whatever landed in their hand on the next pass of
    // `syncHands` like every other way an item can change.

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        // Being hit really does end an emote now. Under the old spectator swap
        // this fired only in the instant before the mode flipped, because a
        // spectator takes no damage at all — so an emote was a brief
        // invulnerability and this listener could not see it. An invisible
        // player takes damage normally, so the whole troupe stops the moment
        // anybody is attacked.
        //
        // This is all that is left of the combat handling: it ends an emote
        // somebody is in, and no longer remembers the hit afterwards to refuse
        // the next one. Whether being in a fight should stop you emoting at all
        // is a gameplay rule, and gameplay rules belong to the server — see the
        // class comment.
        //
        // A STANCE is the exception, and the reasoning above is what exempts
        // it rather than contradicting it: the check exists so an emote is not
        // somewhere to hide, and a stance hides nobody — the rig stands where
        // the body stands and follows it everywhere. Ending one on every arrow
        // would make the feature unusable on any server where combat happens,
        // for no safety bought. Death still takes it off; see onDeath.
        Session session = active.get(player.getUniqueId());
        if (session != null && !session.stance()) {
            stop(player, true, EmoteEndEvent.Cause.DAMAGED);
        }
    }

    /**
     * Takes a stance off the dead.
     *
     * <p>Ordinary emotes never needed this: {@link #onDamage} fires for the
     * fatal blow too, so the emote was already over before anybody hit the
     * ground. A stance ignores damage, so without this the wearer respawns
     * invisible to everybody — vanilla clears the potion on death, but not
     * {@code hidePlayer}, which is the half that actually removes the body from
     * everyone else's screen — and their rig is left standing where they died
     * with nothing left to drive it.
     */
    @EventHandler
    public void onDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        Session session = active.get(event.getEntity().getUniqueId());
        if (session != null) stop(event.getEntity(), true, EmoteEndEvent.Cause.DAMAGED);
    }

    private void tick() {
        for (Map.Entry<UUID, Session> entry : new ArrayList<>(active.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getKey());
            Session session = entry.getValue();
            if (player == null || !player.isOnline()) {
                // Their partners go too — an emote nobody is left to finish is
                // three rigs frozen mid-pose around an empty spot.
                endTroupe(session, EmoteEndEvent.Cause.QUIT);
                continue;
            }
            // What they are holding, before anything is posed. Both kinds of
            // emote get it: an ordinary one hides the body exactly as a stance
            // does, so a rig playing a wave with an empty hand is the same
            // wrong picture standing still as it is walking.
            syncHands(player, session);
            // The cape chases the body, one step per tick and only here.
            // `pose` reads what this leaves and is called from the swing
            // listener too, so stepping it there as well would run the
            // integrator twice in a tick somebody happened to click in — a
            // cape that flinched when you hit something.
            session.cape.step(player);
            // A worn emote is the whole of the other branch: it never asks
            // whether they moved, because moving is the point of it.
            if (session.stance()) {
                if (!tickStance(player, session)) continue;
                // Nothing to pose while the rig is away: the displays are
                // holding air, and a set can sit in a state it leaves to
                // vanilla for as long as somebody keeps running. It was still
                // writing a transform per bone per tick to entities nobody can
                // see.
                if (!session.rigHidden) {
                    pose(entry.getKey(), session, session.snap);
                    session.snap = false;
                }
                continue;
            }
            // While root motion is running, "did they move" is asked against
            // where WE put them. Against the origin it would be the emote
            // cancelling itself on its own first step.
            Location now = player.getLocation();
            if (session.anchor == null) session.anchor = session.origin;
            // The settle window: adopt wherever they actually ended up rather
            // than measuring against where we aimed. A teleport is acknowledged
            // a tick late and gravity puts people down on the block under them,
            // and both used to read as walking away. Bounded to a spot near the
            // one intended, so this cannot be ridden anywhere.
            if (session.settling > 0) {
                session.settling--;
                if (now.getWorld().equals(session.origin.getWorld())
                        && now.distanceSquared(session.origin) <= MAX_ROOT_STEP * MAX_ROOT_STEP) {
                    session.anchor = now.clone();
                }
            }
            Location anchor = session.expected != null ? session.expected : session.anchor;
            // Position only. Yaw and pitch are deliberately not consulted —
            // `distanceSquared` reads x/y/z — because looking around is not
            // leaving, and an emote that ended when somebody moved the mouse
            // would be unusable.
            boolean moved = session.settling <= 0
                && (!now.getWorld().equals(anchor.getWorld())
                    || now.distanceSquared(anchor) > MOVE_TOLERANCE * MOVE_TOLERANCE);
            double elapsed = Math.max(0, player.getWorld().getGameTime() - session.startTick) / 20.0;
            boolean finished = !session.emote.loop && elapsed > Math.max(0, session.emote.length);
            if (moved || finished) {
                // `finished` is the same answer for everybody — one clock, one
                // length — and `moved` deliberately isn't: anybody stepping out
                // of a duet ends it for both, because the alternative is one
                // half of a handshake carrying on alone.
                stop(player, true, moved ? EmoteEndEvent.Cause.MOVED : EmoteEndEvent.Cause.FINISHED);
                continue;
            }
            follow(player, session, animationTime(session.emote, elapsed));
            pose(entry.getKey(), session, false);
        }
    }

    private void pose(UUID playerId, Session session, boolean immediate) {
        double elapsed = 0;
        Player player = Bukkit.getPlayer(playerId);
        long now = session.startTick;
        if (player != null) {
            now = player.getWorld().getGameTime();
            elapsed = Math.max(0, now - session.startTick) / 20.0;
        }
        double t = animationTime(session.emote, elapsed);

        // How far through a swing this pose is, or 0 for a rig that is not
        // mid-swing. Read once rather than per bone: it is the same answer for
        // the arm, the forearm below it and the hand hanging off that, and the
        // whole point of an overlay is that one value moves all three.
        double swing = ArmSwing.running(session.swingTick, now)
            ? ArmSwing.progress(session.swingTick, now)
            : 0;
        String swingBone = session.swingOffHand ? HeldItem.OFF_HAND_ATTACH : HeldItem.MAIN_HAND_ATTACH;

        // The root, once, as the matrix every bone starts from. Composing it
        // first is what makes it a PARENT: each bone's own step is appended to
        // it, so the bone turns about its own joint inside a body that has
        // already been moved and turned. An emote without one starts from the
        // identity and is posed exactly as it always was.
        Matrix4f root = rootMatrix(session, t);

        // Each bone's composed animation matrix (root and every parent already
        // folded in), so a child starts from its parent instead of from the
        // root. The table lists a parent before its children, so this one
        // forward pass always finds it. A whole-limb bone has no parent and
        // starts from the root, exactly as it always did.
        Map<String, Matrix4f> composed = new HashMap<>();
        for (int i = 0; i < session.parts.size() && i < session.bones.size(); i++) {
            EmoteStore.Bone bone = session.bones.get(i);
            if (bone == null || bone.key == null) continue;

            Matrix4f parent = bone.parent != null ? composed.get(bone.parent) : null;
            Matrix4f animation = new Matrix4f(parent != null ? parent : root);
            RigMath.applyStep(animation, session.animators, bone.key, bone.pivot, t);
            // The swing, on top of what the emote already asked this bone to
            // do rather than instead of it — so a rig can swing mid-walk and
            // the walk carries on underneath. It goes on the UPPER arm, which
            // is what puts it into `composed` before the forearm is built from
            // it, and is why the elbow and the held item both come along
            // without either of them knowing a swing exists.
            if (swing > 0 && swingBone.equalsIgnoreCase(bone.key)) {
                ArmSwing.applyTo(animation, bone.pivot, swing, session.swingOffHand);
            }
            // The cape's physics, on the same terms as the swing: after the
            // emote's own keys rather than instead of them, so an emote that
            // authors a cape keeps what it authored and gets the trailing on
            // top. The bone hangs off the body, so it is already carrying
            // whatever the torso did before this is appended.
            if (EmoteStore.CAPE_BONE.equalsIgnoreCase(bone.key)) {
                session.cape.applyTo(animation, bone.pivot);
            }
            composed.put(bone.key, animation);

            ItemDisplay display = session.parts.get(i);
            if (display == null || !display.isValid()) continue;

            // Identical composition to a placed rig: placement yaw first, then
            // the pose conjugated through the client's built-in 180 degree
            // ItemDisplay spin. See RigAnimator.pose.
            Matrix4f m = new Matrix4f();
            if (session.yaw != 0f) m.rotateY((float) Math.toRadians(-session.yaw));
            // Outermost, so the whole rig shrinks about its anchor rather than
            // each bone shrinking about its own joint. See PLAYER_SCALE.
            m.scale(PLAYER_SCALE);
            m.mul(RigMath.toItemDisplaySpace(animation));

            Transformation next = RigMath.toTransformation(m);
            if (next.equals(display.getTransformation())) continue;
            // Toggling re-arms the client's interpolation; an unchanged delay
            // value is deduplicated out of the packet and playback then steps
            // instead of tweening. Same fix RigAnimator.pose documents.
            display.setInterpolationDelay(1);
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(immediate ? 0 : INTERPOLATION_TICKS);
            display.setTransformation(next);
        }

        poseProps(session, root, composed, t, immediate);
        poseHands(session, composed, immediate);
    }

    /**
     * Both hands, wherever the arms they hang off ended up.
     *
     * <p>Composed exactly like a prop attached to that arm — the bone chain,
     * then the hand's own step — because that is what a held item IS. The one
     * difference is that the step is fixed rather than authored: a prop carries
     * an offset and its own animator, and a hand carries {@link HeldItem}'s
     * socket, which does not move relative to the arm.
     *
     * <p>A pack with no bone for an arm gets nothing rather than a hand at the
     * rig's origin: the whole-limb skeleton has no forearm and falls back to the
     * upper arm, and a table with neither is a manifest whose arms this jar
     * cannot find at all. A sword floating at somebody's feet is worse than a
     * sword that is not drawn.
     */
    private void poseHands(Session session, Map<String, Matrix4f> composed, boolean immediate) {
        poseHand(session, session.mainHand, HeldItem.MAIN_HAND_ATTACH, false, composed, immediate);
        poseHand(session, session.offHand, HeldItem.OFF_HAND_ATTACH, true, composed, immediate);
    }

    private void poseHand(
            Session session,
            ItemDisplay display,
            String attach,
            boolean offHand,
            Map<String, Matrix4f> composed,
            boolean immediate) {
        if (display == null || !display.isValid()) return;

        // The hand is on the forearm where the skeleton has one, else on the
        // arm — the same two-step lookup a prop does, through the same map, so
        // the two cannot disagree about which end of a limb a hand is on.
        Matrix4f chain = composed.get(EmoteStore.attachEndBone(attach));
        if (chain == null) chain = composed.get(attach);
        if (chain == null) {
            // Case-insensitively, once, before giving up: `attach` is the
            // lowercase spelling a manifest's props use and a bone key is
            // camelCase, and the map above is keyed by bone key. The forearm
            // lookup hits because ATTACH_END's values are already camelCase;
            // the whole-limb fallback would not.
            for (Map.Entry<String, Matrix4f> entry : composed.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(attach)) {
                    chain = entry.getValue();
                    break;
                }
            }
        }
        if (chain == null) return;

        Matrix4f m = new Matrix4f(chain);
        HeldItem.applyTo(m, offHand, session.slim);

        Matrix4f out = new Matrix4f();
        if (session.yaw != 0f) out.rotateY((float) Math.toRadians(-session.yaw));
        // The same scale the bones and the props get, for the same reason: a
        // hand placed in rig space has to shrink with the rig, or the sword is
        // sized for a body 6.7% bigger than the one holding it.
        out.scale(PLAYER_SCALE);
        out.mul(RigMath.toItemDisplaySpace(m));
        // The hand's turn goes on OUTSIDE the conjugation, which is the whole
        // of the fix for an item that hung beside the rig instead of in it.
        // `toItemDisplaySpace` rewrites every rotation inside it — it turns the
        // -90 of vanilla's in-hand chain into a +90 — so an orientation applied
        // in model space arrived pitched a half-turn wrong. Here it is in the
        // item's own frame, which is where `ItemInHandLayer` applies it and the
        // only place it means what it says. See HeldItem.applyTo.
        HeldItem.orient(out);

        Transformation next = RigMath.toTransformation(out);
        if (next.equals(display.getTransformation())) return;
        display.setInterpolationDelay(1);
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(immediate ? 0 : INTERPOLATION_TICKS);
        display.setTransformation(next);
    }

    /**
     * Every carried model, posed where whatever it rides has ended up.
     *
     * <p>Attachment is composition, in the same order the editor's scene graph
     * nests it: root, then the attached bone's own step, then the prop's offset
     * and its own animator. A sword in the right hand therefore swings because
     * the arm swings, and nothing here follows anything — which is the same
     * claim the editor makes, made the same way, so the two cannot disagree
     * about where a held thing ends up.
     *
     * <p>"none" skips the root as well, so an unattached model stands still
     * while the player moves through the emote. That is the whole point of it:
     * a chair does not follow you when you sit down.
     */
    private void poseProps(
            Session session, Matrix4f root, Map<String, Matrix4f> composed, double t, boolean immediate) {
        List<EmoteStore.Prop> props = session.emote.props;
        if (props == null) return;
        for (int i = 0; i < props.size() && i < session.propParts.size(); i++) {
            ItemDisplay display = session.propParts.get(i);
            EmoteStore.Prop prop = props.get(i);
            if (display == null || !display.isValid() || prop == null) continue;

            String attach = prop.attach == null ? "none" : prop.attach;
            Matrix4f m = new Matrix4f();
            if (!"none".equals(attach)) {
                if (ROOT_TARGET.equals(attach)) {
                    m.mul(root);
                } else {
                    // The bone the hand is actually on — the forearm, not the
                    // upper arm — when the jointed skeleton has it, else the
                    // named bone. Its composed matrix already carries the root
                    // and every parent step, which is what makes a sword follow
                    // a bent elbow instead of pointing where the shoulder aims.
                    Matrix4f chain = composed.get(EmoteStore.attachEndBone(attach));
                    if (chain == null) chain = composed.get(attach);
                    if (chain != null) {
                        m.set(chain);
                    } else {
                        // No such bone in this player's table (a manifest whose
                        // bones and props disagree): the old whole-limb path.
                        m.mul(root);
                        float[] pivot = pivotOf(session, attach);
                        if (pivot != null) RigMath.applyStep(m, session.animators, attach, pivot, t);
                    }
                }
            }

            applyPropStep(m, prop, t);

            Matrix4f out = new Matrix4f();
            if (session.yaw != 0f) out.rotateY((float) Math.toRadians(-session.yaw));
            // The same scale the bones get, for the same reason: a model an
            // emote carries is placed in rig space, so it has to shrink with
            // the rig or a held sword ends up sized for a body 6.7% bigger
            // than the one holding it.
            out.scale(PLAYER_SCALE);
            out.mul(RigMath.toItemDisplaySpace(m));

            Transformation next = RigMath.toTransformation(out);
            if (next.equals(display.getTransformation())) continue;
            display.setInterpolationDelay(1);
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(immediate ? 0 : INTERPOLATION_TICKS);
            display.setTransformation(next);
        }
    }

    /**
     * The prop's own placement and motion, appended to whatever carries it.
     *
     * <p><b>A prop's offset is a PLACEMENT, not a joint</b>, and that
     * distinction is why this does not call {@link RigAnimator#applyStep}.
     *
     * <p>applyStep is built for a bone: it translates to the pivot, animates,
     * and translates BACK, so the rest transform is the identity and the pivot
     * only decides what a rotation turns about. The editor gives a bone that
     * same shape, with an inner group at -pivot. A prop has no such inner group
     * — its offset moves it and it stays moved — so composing one through
     * applyStep put every KEYFRAMED prop back at the joint it hung from while
     * an un-keyframed one sat correctly. One emote, two answers, neither of
     * them matching the editor.
     *
     * <p>So: translate by the offset plus whatever the keyframe adds, then turn
     * and scale about where that landed, then apply the prop's own scale
     * innermost — which is exactly what the editor's applier does to the prop's
     * group, and what its inner {@code <group scale>} does inside that.
     *
     * <p>Package-visible and free of Bukkit so it can be tested; the editor and
     * this have disagreed about a transform more than once, and reasoning was
     * what produced the disagreement each time.
     */
    static void applyPropStep(Matrix4f m, EmoteStore.Prop prop, double t) {
        boolean placed = prop.offset != null && prop.offset.length == 3;
        float ox = placed ? prop.offset[0] / 16f : 0f;
        float oy = placed ? prop.offset[1] / 16f : 0f;
        float oz = placed ? prop.offset[2] / 16f : 0f;

        float[] rot = Sampler.sample(prop.animator, "rotation", t, PROP_ZERO);
        float[] pos = Sampler.sample(prop.animator, "position", t, PROP_ZERO);
        float[] scl = Sampler.sample(prop.animator, "scale", t, PROP_ONE);

        m.translate(ox + pos[0] / 16f, oy + pos[1] / 16f, oz + pos[2] / 16f);
        m.rotateXYZ(
            (float) Math.toRadians(rot[0]),
            (float) Math.toRadians(rot[1]),
            (float) Math.toRadians(rot[2]));
        m.scale(scl[0], scl[1], scl[2]);

        float scale = prop.scale > 0 ? prop.scale : 1f;
        m.scale(scale);
    }

    /**
     * Whether this participant is the one carrying this model.
     *
     * <p>Exactly one of them must be, or a two-person emote spawns the sword
     * twice — once in each hand it could be in.
     *
     * <p><b>An unattached model is the LEAD's</b>, not nobody's. It follows no
     * bone and stands where it was placed, so which participant spawns it makes
     * no difference to where it ends up — but somebody has to, and letting
     * everybody would stack a chair per person in the same spot.
     */
    private static boolean carries(EmoteStore.Prop prop, String performerId) {
        boolean lead = performerId == null;
        if (prop.attach == null || "none".equals(prop.attach)) return lead;
        if (prop.performer == null || prop.performer.isEmpty()) return lead;
        return prop.performer.equals(performerId);
    }

    /** The joint of the bone with this key, or null if the pack has no such bone. */
    private float[] pivotOf(Session session, String key) {
        for (EmoteStore.Bone bone : session.bones) {
            if (bone != null && key.equals(bone.key)) return bone.pivot;
        }
        return null;
    }

    private static double animationTime(EmoteStore.Emote emote, double elapsed) {
        if (emote.length <= 0) return 0;
        return emote.loop ? elapsed % emote.length : Math.min(elapsed, emote.length);
    }

    private static ItemStack boneItem(String modelData) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            CustomModelDataComponent component = meta.getCustomModelDataComponent();
            component.setStrings(Collections.singletonList(modelData));
            meta.setCustomModelDataComponent(component);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static String encode(Location location) {
        return location.getWorld().getName()
            + "," + location.getX() + "," + location.getY() + "," + location.getZ()
            + "," + location.getYaw() + "," + location.getPitch();
    }

    private static Location decode(String raw) {
        if (raw == null) return null;
        String[] parts = raw.split(",");
        if (parts.length != 6) return null;
        org.bukkit.World world = Bukkit.getWorld(parts[0]);
        if (world == null) return null;
        try {
            return new Location(
                world,
                Double.parseDouble(parts[1]),
                Double.parseDouble(parts[2]),
                Double.parseDouble(parts[3]),
                Float.parseFloat(parts[4]),
                Float.parseFloat(parts[5]));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
