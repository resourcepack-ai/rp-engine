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

import ai.resourcepack.engine.core.model.DisplayCarry;
import ai.resourcepack.engine.core.model.RigTags;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scoreboard.Team;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
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
 * hidden in three halves. {@code hidePlayer} stops everybody else being sent
 * the entity at all (armour included, and with no equipment to take off
 * anybody and hand back). The potion covers the one view {@code hidePlayer}
 * cannot, because Bukkit cannot hide a player from THEMSELVES. And
 * {@link #hideArmourFromWearer} covers what the potion leaves in that same
 * view — a client-side equipment packet for the four armour slots, which is
 * how a rider stops watching their own helmet ride along inside their rig.
 * <b>The hands are not in it</b>, and that is the line to leave alone: blanking
 * a hand is what broke bows, shields and eating, and {@link #conceal} has the
 * whole account.
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
 * wants {@code setCanSeeFriendlyInvisibles(false)} on the teams it creates,
 * and {@link #warnIfGhosted} says so in the console once rather than leaving
 * an owner to work out why a rider is still half there.
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

    /**
     * The scale vanilla draws a humanoid at, and therefore the one this rig
     * has to be drawn at to be the same size as the player wearing it.
     *
     * <p><b>The rig was 6.7% too big, and the arithmetic says exactly why.</b>
     * The skeleton is 32px tall in the space Studio builds it in (see the
     * skeleton's own origin) and an ItemDisplay renders that space
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
     *
     * <p>The rule now lives in {@link DisplayLatency#glideTicks}, shared with
     * the vehicle runtime, which draws rigs the same way and had reasoned its
     * own number out separately. <b>The two still differ, and must:</b> an
     * emote rig is judged against the WORLD, where this is right, and a
     * vehicle's model is judged against the RIDER sitting on it, which has to
     * match {@link DisplayLatency#TRACKED_ENTITY_TICKS} instead.
     */
    private static final int INTERPOLATION_TICKS =
            ai.resourcepack.engine.core.model.DisplayLatency.glideTicks(PERIOD_TICKS);

    /**
     * How long a rig takes to ease out of one animation and into the next.
     *
     * <p><strong>A swap used to be a hard cut, and on a big pose it read as the
     * body teleporting.</strong> Every worn rig went through {@code pose(...,
     * immediate)} on the swap, which sets the client's interpolation to zero —
     * so a vehicle occupant coming out of a look-back snapped their head
     * through 130 degrees in one frame, and a stance going from a walk to a run
     * changed legs between two ticks.
     *
     * <p>Placed rigs have had this all along: {@code RigAnimator} keeps a
     * {@code Blend} per display and eases with {@code RigMath.mix}, over a
     * {@code blend} authored per animation. This is the worn-rig half, and it
     * is deliberately NOT authored — the request was for it to happen
     * automatically, and a number nobody sets is a number nobody gets wrong.
     *
     * <p>Done by lengthening the client's own interpolation for the window
     * rather than by mixing transforms here. The rig is re-posed every tick
     * anyway, so a longer duration makes each pass a step toward a moving
     * target — the rig eases into the new animation and is running it cleanly
     * by the end of the window, with no per-display state to keep, and props
     * and held items come along because they read the same number.
     *
     * <p>Five ticks. Long enough that a head turning right round reads as a
     * turn, short enough that a walk-to-run swap is not visibly late.
     */
    private static final int SWAP_BLEND_TICKS = 5;

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

    /**
     * Said once, however many players are ghosting. See {@link #warnIfGhosted}.
     *
     * <p>Once rather than per player, for the reason every other one-shot
     * warning in this plugin is: it is one fact about this server's scoreboard,
     * and repeating it per rider would bury it in the thing it is warning
     * about.
     */
    private volatile boolean warnedGhost;

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
         * A facing somebody else owns, or null to follow the wearer's look.
         *
         * <p>Set through {@link ai.resourcepack.engine.api.Emotes#face} by
         * whatever is carrying this player — a vehicle seat, today. See there
         * for why a rider's body and a rider's camera are two different
         * questions.
         */
        Float facing;
        /**
         * Where the wearer's feet are, when somebody else knows better than
         * their own position — or null to follow the wearer.
         *
         * <p>Set through {@link ai.resourcepack.engine.api.Emotes#anchor} by
         * whatever is carrying this player, every tick, and the rig is moved
         * as it is set. What is kept here is only so that the pass in
         * {@link #tickStance} places the shadow, the name and any respawned
         * prop against the same point rather than against wherever vanilla
         * has the passenger this tick.
         */
        Location seat;
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
        EmoteNameTag nameTag;
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
         * <p>Horizontal, smoothed, and never more than {@link EmoteStance#MAX_LEAD}. See
         * {@link #LEAD_PIPELINE_TICKS} for what it is cancelling.
         */
        Vector lead = new Vector();
        /**
         * Which glide window this session's displays are currently carrying,
         * or null before the first pass has decided. See {@link #carryTicksFor}.
         *
         * <p>Held so the answer is re-sent on the TRANSITION rather than every
         * pass. Setting a teleport duration is an entity-data write per
         * display, and a stance is worn for minutes at a time — getting in and
         * out of a boat is the only thing that can change it.
         */
        Boolean carriedAsRider;
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
         * The tick this session's ease into a new animation finishes at, or 0.
         *
         * <p>Set at every swap and simply read afterwards — see
         * {@link #SWAP_BLEND_TICKS}. It is a deadline rather than a countdown
         * so nothing has to be decremented on a pass that does no posing.
         */
        long blendUntil;
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
         * <p><b>Always on for a {@link #driven} session</b>, which is not the
         * command's flag reaching a second feature but the same question having
         * the opposite answer there: somebody in a seat is looking at their own
         * vehicle from the outside, so the rig is the thing they came to see.
         * See {@link #beginDriven}.
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
         * Whether what this wears is decided by somebody else.
         *
         * <p>Passed to {@link #begin} by {@link #beginDriven}, and the only
         * thing it changes about playback is that {@code tickStance} does not
         * resolve a movement state for it — see
         * {@link ai.resourcepack.engine.api.Emotes#wear}. Everything else about
         * a driven session is a stance: the rig follows its wearer, there is no
         * anchor, and moving does not end it. {@link #stance()} says so, which
         * it did not until a vehicle proved why it had to.
         *
         * <p>It is a flag rather than a null {@code group} because "no group"
         * already means "a plain stance that names its own triggers", and those
         * two want opposite things from the state machine.
         */
        boolean driven;
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

        /**
         * Whether this is worn rather than performed.
         *
         * <p>A one-shot emote is a performance: it happens at a spot, it ends
         * the moment its wearer moves, and it puts them back where they
         * started. Everything else here is a stance — the rig follows its
         * wearer, moving is expected rather than a reason to stop, and there is
         * nowhere to put anybody back to.
         *
         * <p><strong>{@code driven} counts, and leaving it out was a bug rather
         * than a nuance.</strong> A vehicle occupant is carried, so the tick
         * loop's "did they move" test fired on the first tick the vehicle
         * rolled: the emote ended, the rig came down, and {@code restore}
         * teleported the rider back to wherever they had climbed in. What is
         * meant to be a driver leaning on the wheel for a ten-minute journey
         * lasted one tick and dragged them home at the end of it.
         */
        boolean stance() {
            return !triggers.isEmpty() || group != null || driven;
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
    /**
     * Wears an emote chosen by the caller. See
     * {@link ai.resourcepack.engine.api.Emotes#wear}.
     *
     * <p>Two jobs in one entry point, because from outside they are one thing:
     * starting a driven session, and swapping what an existing one wears. A
     * caller that had to know which it was doing would have to track whether a
     * player is already wearing something, which is what this class is for.
     */
    public EmoteResult wear(Player player, String emoteId) {
        return wear(player, emoteId, null);
    }

    /**
     * The same, over a base pose. See {@link ai.resourcepack.engine.api.Emotes#wear(Player, String, String)}.
     */
    public EmoteResult wear(Player player, String emoteId, String under) {
        if (player == null) {
            return EmoteResult.refused(Reason.INCOMPLETE_EMOTE_DATA);
        }
        // Null is a real answer — a state the pack left blank — and it means
        // the player's own body, not the end of the session. Resolved before
        // anything else so a bad id is refused rather than quietly becoming it.
        EmoteStore.Emote wanted = null;
        if (emoteId != null && !emoteId.isEmpty()) {
            // The engine's own stances first, so they work on a pack that
            // ships no emotes at all — which is most packs, and is exactly the
            // case a vehicle seat needs covered. See Emotes.BUILT_IN_SITTING.
            wanted = builtIn(emoteId);
            if (wanted == null) {
                wanted = emotes.find(emoteId);
            }
            if (wanted == null) {
                return EmoteResult.refused(Reason.UNKNOWN_EMOTE, emoteId, ids());
            }
        }

        Session session = active.get(player.getUniqueId());
        if (session != null && !session.driven) {
            // They are mid-emote of their own. Theirs wins: a vehicle dressing
            // somebody who is halfway through a handshake would be the vehicle
            // deciding something about that player's game.
            return EmoteResult.refused(Reason.ALREADY_EMOTING);
        }
        if (session == null) {
            EmoteResult started = beginDriven(player);
            if (!started.started()) {
                return started;
            }
            session = active.get(player.getUniqueId());
            if (session == null) {
                return EmoteResult.refused(Reason.NO_RIG_FOR_PLAYER);
            }
        }

        swapWorn(player, session, over(builtIn(under), wanted));
        return EmoteResult.started(wanted != null ? wanted.name : "", false);
    }

    /**
     * Points a worn rig somewhere other than its wearer's look, or lets it go.
     *
     * <p>See {@link ai.resourcepack.engine.api.Emotes#face}. Nothing is applied
     * here: the next pass of {@link #tickStance} reads it, which is what keeps
     * this to a field write on a path a vehicle runs every tick for every
     * occupant.
     *
     * <p>Silent for a player with no session. A facing kept for a rig that does
     * not exist would outlive the seat that set it and point the next one.
     *
     * <p><strong>And silent for a session this caller does not own.</strong>
     * Somebody who is already mid-emote of their own when they sit down is
     * refused by {@code wear} and keeps dancing — the seat does not get their
     * body — so pointing that rig at the vehicle would take over an emote the
     * seat was told it could not have. {@code driven} is exactly the set of
     * sessions whose contents belong to somebody else, which is the same
     * question, so it is the same test.
     */
    public void face(Player player, Float yaw) {
        if (player == null) return;
        Session session = active.get(player.getUniqueId());
        if (session != null && session.driven) session.facing = yaw;
    }

    /**
     * See {@link ai.resourcepack.engine.api.Emotes#anchor}.
     *
     * <p>Moves the rig NOW rather than on the next pass, which is the whole
     * of why the method exists — see the interface. The same {@code driven}
     * test as {@link #face}, for the same reason: a rig the caller was refused
     * is not theirs to move.
     *
     * <p>The rig is carried on the rider window before it is moved, without
     * waiting for the next pass to notice its wearer is a passenger: an
     * anchored rig is by definition being carried by something, and the first
     * move on the world's tighter window would step it ahead of the model
     * for a tick.
     */
    public void anchor(Player player, Location feet) {
        if (player == null) return;
        Session session = active.get(player.getUniqueId());
        if (session == null || !session.driven) return;
        if (feet == null) {
            session.seat = null;
            return;
        }
        session.seat = feet.clone();
        carryAs(session, true);
        follow(session, feet, player.isSneaking());
    }

    /**
     * Starts a driven session wearing nothing yet.
     *
     * <p>{@code startGroup} without the group: the same checks in the same
     * order, because every one of them is about whether this player can wear a
     * rig at all rather than about what they are wearing. The rig starts hidden
     * for the reason that one gives — a rig posed at the rest emote's identity
     * is a body standing to attention inside the player for one pass.
     *
     * <h2>A vehicle occupant watches their own rig, and that is the one place
     * the stance default is wrong</h2>
     *
     * <p>{@code hideFromWearer} keeps a worn set off its own wearer's screen
     * because they spend an hour walking around in first person, where being
     * replaced by a server-side rig costs them everything about the game
     * feeling normal and buys them nothing.
     *
     * <p><strong>None of that is true of somebody in a seat.</strong> They are
     * not walking, their hands are not doing anything vanilla has to keep
     * responsive, and the thing they are looking at is the vehicle they are
     * sitting in — from the outside, because that is the camera anybody drives
     * from. Showing them their own untouched body slumped in the seat while
     * everybody else sees the rig rowing is the wrong way round for exactly the
     * duration this is on. So a driven session takes the one-shot treatment:
     * the body goes (invisibility), the rig stays on their screen.
     */
    private EmoteResult beginDriven(Player player) {
        EmoteStore.PlayerRig rig = emotes.rigFor(player.getUniqueId());
        if (rig == null) {
            return EmoteResult.refused(
                emotes.hasAnyRig() ? Reason.NO_RIG_FOR_PLAYER : Reason.NO_RIGS_IN_PACK);
        }
        if (emotes.bones().isEmpty()) {
            return EmoteResult.refused(Reason.INCOMPLETE_EMOTE_DATA);
        }
        if (player.getGameMode() == GameMode.SPECTATOR) {
            return EmoteResult.refused(Reason.IN_SPECTATOR);
        }

        // No START_COOLDOWN_MS check, and no EmoteStartEvent: this is not
        // somebody running a command, it is a seat being sat in, and a cooldown
        // that refused the second vehicle somebody got into in four seconds
        // would be a vehicle whose driver is invisible for no stated reason.

        Location origin = player.getLocation().clone();
        // showSelf, so `hideFromOwnWearer` stays false and the occupant is the
        // one being hidden rather than the rig. See the note above; it is not
        // the `--showYourself` flag being borrowed for a second purpose, it is
        // the same question with the same answer.
        Session session = begin(player, drivenRest(), Collections.emptyMap(), null, emotes.bones(),
            rig, origin, Math.round(origin.getYaw()), player.getWorld().getGameTime(),
            Collections.singletonList(player.getUniqueId()), null, null, true, true);
        if (session == null) {
            return EmoteResult.refused(Reason.NO_RIG_FOR_PLAYER);
        }
        setRigHidden(player, session, true);
        return EmoteResult.started("", false);
    }

    /**
     * Puts {@code wanted} on, or takes the rig away when it is null.
     *
     * <p>The group branch of {@code tickStance}, lifted out so both callers
     * follow one set of rules — <strong>including the one that is easy to lose:
     * the swap is keyed on the EMOTE, not on what the caller called the
     * state.</strong> Two vehicle states answered by one emote is an ordinary
     * shape (idle and submerged both sitting still), and re-keying on the state
     * would restart that emote's clock every time the boat touched the water.
     */
    private void swapWorn(Player player, Session session, EmoteStore.Emote wanted) {
        if (wanted == session.memberEmote && session.memberState == null && session.startTick != 0) {
            return;
        }
        session.memberEmote = wanted;
        session.memberState = null;
        session.emote = wanted != null ? wanted : session.rest;
        session.animators = wanted != null && wanted.animators != null
            ? wanted.animators
            : Collections.<String, Map<String, List<Keyframe>>>emptyMap();
        session.root = wanted != null ? wanted.root : null;
        // From the top, every time — a cycle joined halfway through because the
        // last state ran for two seconds is a limp.
        session.startTick = player.getWorld().getGameTime();
        // Eased into rather than cut to, unless the rig was away — see
        // SWAP_BLEND_TICKS, and `wasHidden` below for the one case where a
        // tween is from a pose nobody authored.
        session.blendUntil = session.startTick + SWAP_BLEND_TICKS;
        Location base = player.getLocation().clone()
            .add(session.lead.getX(), RIG_BASE_Y, session.lead.getZ());
        base.setYaw(0);
        base.setPitch(0);
        spawnProps(player, session, session.emote, base, null);
        boolean wasHidden = session.rigHidden;
        setRigHidden(player, session, wanted == null);
        // Only where a tween would be wrong: easing out of a rig that was put
        // away is a tween from whatever pose it happened to be holding.
        if (wasHidden) session.snap = true;
        pose(player.getUniqueId(), session, wasHidden ? 0 : SWAP_BLEND_TICKS);
    }

    /**
     * The stand-in a driven session holds while it wears nothing.
     *
     * <p>{@code session.emote} is read by the poser, the ending and two logs,
     * and a null there would put a check in each — the same reasoning
     * {@link Session#rest} already carries for a group.
     */
    private static EmoteStore.Emote drivenRest() {
        EmoteStore.Emote rest = new EmoteStore.Emote();
        rest.name = "";
        rest.length = 0;
        rest.loop = true;
        return rest;
    }

    /**
     * How far the thigh swings forward for a seated occupant, in degrees.
     *
     * <p><b>Studio's {@code SEATED_LEG_DEG}, and it has to stay that.</b> The
     * seat preview draws the occupant with this angle, and a rider who sits
     * differently in game from the way the editor drew them is the whole class
     * of bug the seat height was.
     *
     * <p>Positive is forward, which is worth writing down because it is
     * derivable and nobody wants to derive it twice: the rig faces {@code -z},
     * a leg hangs at {@code -y} from a hip pivot at 12px, and a right-handed
     * turn about {@code +x} takes {@code -y} to {@code -z}. Ninety degrees is
     * therefore a leg straight out in front. The knee stays straight because a
     * leg is one box on the whole-limb skeleton, which is what the preview
     * draws too.
     */
    private static final float SEATED_LEG_DEG = 90;

    /** Built once: a static pose has no clock and nothing about it is per player. */
    private static final EmoteStore.Emote BUILT_IN_SITTING_EMOTE =
        stance(ai.resourcepack.engine.api.Emotes.BUILT_IN_SITTING, "rightLeg", "leftLeg");

    private static final EmoteStore.Emote BUILT_IN_STANDING_EMOTE =
        stance(ai.resourcepack.engine.api.Emotes.BUILT_IN_STANDING);

    /**
     * One of the engine's own stances, or null for an ordinary emote id.
     *
     * <p>Checked before the pack, which is what makes these work on a pack that
     * ships no emotes — see {@link ai.resourcepack.engine.api.Emotes#BUILT_IN_SITTING}.
     */
    private static EmoteStore.Emote builtIn(String emoteId) {
        if (ai.resourcepack.engine.api.Emotes.BUILT_IN_SITTING.equals(emoteId)) {
            return BUILT_IN_SITTING_EMOTE;
        }
        if (ai.resourcepack.engine.api.Emotes.BUILT_IN_STANDING.equals(emoteId)) {
            return BUILT_IN_STANDING_EMOTE;
        }
        return null;
    }

    /**
     * A built-in stance: one keyframe per named bone, held for ever.
     *
     * <p>Length zero and a single key at time zero, so {@link Sampler} holds it
     * whatever the clock says — a stance is a shape rather than a performance,
     * and it must not drift, end or be scheduled. Bones the pack does not have
     * are simply never asked for: {@code pose} walks the PACK's bone table and
     * looks each key up here, so naming a bone that is missing costs nothing
     * and a jointed skeleton carries the shin along with the thigh it hangs
     * off.
     */
    /**
     * {@code wanted} with {@code base}'s pose filling in the bones it is silent
     * about.
     *
     * <p><strong>Per BONE, not per channel.</strong> An emote that touches a
     * leg at all owns that leg: half a base pose under half an authored one is
     * a body nobody drew, and the author who rotated the thigh did not ask for
     * the engine's rotation on the same joint from the other direction.
     *
     * <p>Returns {@code wanted} unchanged whenever there is nothing to merge —
     * no base, no emote, or a base that poses nothing — so the ordinary paths
     * allocate nothing and compare identically to how they always did. The
     * result is a fresh object rather than a mutation of either side: both are
     * shared, and {@code BUILT_IN_SITTING_EMOTE} in particular is a static.
     */
    private static EmoteStore.Emote over(EmoteStore.Emote base, EmoteStore.Emote wanted) {
        if (base == null || wanted == null || base.animators == null || base.animators.isEmpty()) {
            return wanted;
        }
        Map<String, Map<String, List<Keyframe>>> merged = new HashMap<>(base.animators);
        if (wanted.animators != null) {
            merged.putAll(wanted.animators);
        }
        EmoteStore.Emote composed = new EmoteStore.Emote();
        composed.name = wanted.name;
        composed.length = wanted.length;
        composed.loop = wanted.loop;
        composed.root = wanted.root;
        composed.rootMotion = wanted.rootMotion;
        composed.props = wanted.props;
        composed.animators = Collections.unmodifiableMap(merged);
        return composed;
    }

    private static EmoteStore.Emote stance(String name, String... legsForward) {
        EmoteStore.Emote stance = new EmoteStore.Emote();
        stance.name = name;
        stance.length = 0;
        stance.loop = true;
        Map<String, Map<String, List<Keyframe>>> animators = new HashMap<>();
        for (String bone : legsForward) {
            animators.put(bone, Collections.singletonMap("rotation",
                Collections.singletonList(
                    new Keyframe(0, new float[] { SEATED_LEG_DEG, 0, 0 }, null))));
        }
        stance.animators = Collections.unmodifiableMap(animators);
        return stance;
    }

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
            Collections.singletonList(player.getUniqueId()), null, group, showSelf, false);
        if (session == null) {
            return EmoteResult.refused(Reason.NO_RIG_FOR_PLAYER);
        }
        session.members = members;
        // Started hidden and rewritten on the first pass. Nothing is worn until
        // `tickStance` has resolved a state, and a rig posed at the rest
        // emote's identity is a body standing to attention inside the player
        // for one pass — visible, and exactly what a swap is meant to avoid.
        setRigHidden(player, session, true);
        if (tickStance(player, session)) pose(player.getUniqueId(), session, 0);
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
            Reason airborne = EmoteGround.groundRefusal(player);
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
            Location spot = EmoteGround.standingSpot(performerSpot(leadOrigin, leadYaw, performers.get(i)));
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
            startTick, troupe, null, null, showSelf, false);
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
                performer.id, null, false, false);
            if (host.messages() != null) host.messages().pulledIn(member, player, emote.name);
        }

        // Posed only once every rig exists, so the first frame of a duet is the
        // whole duet rather than one person moving and the other appearing.
        for (UUID id : troupe) {
            Session session = active.get(id);
            if (session != null) pose(id, session, 0);
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
            boolean showSelf,
            /** Whether somebody else decides what this wears. See {@link Session#driven}. */
            boolean driven) {
        if (rig == null) return null;
        boolean isLead = performerId == null;

        Session session = new Session();
        // Set first, because `stance()` reads it and three decisions below turn
        // on that answer: whether the displays are carried, whether root motion
        // may run, and whether an origin marker is written. `driven` is a
        // parameter rather than a field the caller writes afterwards for
        // exactly that reason — set late, those three would already have been
        // decided as if a vehicle occupant were a performance.
        session.group = group;
        session.driven = driven;
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
            Invisibility.encode(player.getPotionEffect(PotionEffectType.INVISIBILITY), player.getWorld().getGameTime()));
        // Not for a stance, and that omission is the whole of its recovery
        // story. The origin marker means "this emote moved you, put you back";
        // a stance never moves anybody, so writing one would turn a crash into
        // a teleport home from wherever they had walked to while wearing it.
        // `restoreIfStranded` keys on the invisibility marker above, which a
        // stance DOES write, so it is still found and still made visible.
        if (!session.stance()) {
            pdc.set(originKey, PersistentDataType.STRING, encode(player.getLocation()));
        }

        // The rig stands one block above the player's feet — the skeleton's
        // own origin, and the only offset the whole body fits the block-model
        // bounds at.
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
                // A null slot, never a skipped one. `pose` walks
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
        // Only a stance reads the wearer's crouch, and for the reason
        // carryFollowers states: a set has a crouching STATE and its rig
        // really does crouch, while a one-shot plays what it was given
        // whatever the shift key is doing. Carried by a following emote as
        // well as by a stance, for the reason spawnShadow states.
        session.nameTag = EmoteNameTag.spawn(
            player,
            session.origin,
            session.stance() && player.isSneaking(),
            session.stance() || session.following);
        // The name is hidden from its wearer ALWAYS, not only when the rest
        // of the rig is. Nobody sees their own nameplate in vanilla — it is
        // drawn for other people — so the rig's copy of it is the one part of
        // an emote that has no first-person reading at all. On a worn set it
        // went with everything else; on a one-shot, where the wearer is meant
        // to see their own rig, it left their own name hanging in front of
        // them, which is not something the game ever shows you.
        //
        // Independent of setNameTagShown, which works in view RANGE and is
        // about other people's screens. hideEntity is per-viewer and is not
        // undone by a range change, so a group crossing in and out of a
        // vanilla state cannot bring this back.
        hideFromWearer(player, session.nameTag == null ? null : session.nameTag.display());
        if (session.hideFromOwnWearer()) {
            // The shadow goes with everything else. A wearer whose own body is
            // back is casting their own shadow, so leaving the rig's on their
            // screen is the doubling `setRigHidden` avoids for everybody else.
            // Unlike the name, a shadow IS something you see of yourself, which
            // is why it stays on a one-shot and the name does not.
            hideFromWearer(player, session.shadow);
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
        // A following emote moves this every tick too — see carryFollowers —
        // so it needs the interpolation window as much as a stance does, or a
        // walked shadow steps twenty times a second instead of sliding.
        final boolean carried = session.stance() || session.following;
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
        if (session.nameTag != null) session.nameTag.shown(!hidden);
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
            // The potion hides a skin. This is the rest of the body — see
            // hideArmourFromWearer — and without it "hidden" meant a helmet and
            // a pair of boots riding along inside the rig.
            hideArmourFromWearer(player, true);
            warnIfGhosted(player);
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
        // A group and a vehicle seat are what cross this boundary, and for a
        // group the potion half is dead — until its wearer asks to watch it,
        // which is exactly when `conceal` DID apply one and this has to take it
        // off again. A vehicle occupant is that case permanently (see
        // `beginDriven`), so this is the line that gives a rider their body
        // back between two seat animations. Reading the same predicate as
        // `conceal` is what makes that free: the pair being symmetrical is what
        // stops a later change to one of them leaving somebody invisible.
        if (!session.hideFromOwnWearer()) {
            // Their armour back on their own screen, before the potion, so
            // there is never a frame of a visible body in an empty set.
            hideArmourFromWearer(player, false);
            player.removePotionEffect(PotionEffectType.INVISIBILITY);
            String invis = player.getPersistentDataContainer().get(previousInvisKey, PersistentDataType.STRING);
            Invisibility.Stored previous = invis == null
                ? null
                : Invisibility.decode(invis, player.getWorld().getGameTime());
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
        if (session.nameTag != null) session.nameTag.remove();
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
            // See EmoteGround.grounded().
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
            Location endAt = travelled ? EmoteGround.grounded(session.expected) : null;
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

        // The body comes back FIRST, before anything that can fail and
        // before the markers that would let us try again are cleared.
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
        // Their own armour back on their own screen, unconditionally and for
        // the same reason as the potion below: this is the ENDING, and the
        // state worth healing is the one where a blank was sent and the pass
        // that would have cleared it never ran. Sending somebody their real
        // armour when nothing blanked it is four wasted packets.
        hideArmourFromWearer(player, false);
        // Their own hotbar back, on the same unconditional terms. Nothing
        // blanks the HANDS any more (see `conceal`), so in the ordinary case
        // this is one wasted packet — but a player who was mid-emote when a jar
        // older than that change was swapped out is carrying a blank their new
        // session will never clear, and one packet is a cheap price for that
        // not being permanent. It is also what puts the armour SLOTS back in an
        // inventory screen, which the equipment packets above do not.
        player.updateInventory();
        // Unconditional rather than gated on the marker. The marker is our
        // record of what they had BEFORE; the effect is ours either way, and
        // a state where one exists without the other is exactly the state
        // worth healing rather than the one worth trusting.
        player.removePotionEffect(PotionEffectType.INVISIBILITY);
        if (invis != null) {
            Invisibility.Stored previous = Invisibility.decode(invis, player.getWorld().getGameTime());
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
     * of their body in third person. It is not sufficient there either, which
     * is what {@link #hideArmourFromWearer} is for — same leak, one view, and
     * a packet rather than an inventory edit. The three together are what the
     * spectator swap used to do alone.
     */
    private void hideFromOthers(Player player) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.getUniqueId().equals(player.getUniqueId())) viewer.hidePlayer(host.plugin(), player);
        }
    }

    /**
     * The four slots whose contents keep rendering on an invisible player.
     *
     * <p>The hands are deliberately absent, and that omission is the whole
     * reason this is safe — see {@link #conceal}, which has the wreckage of the
     * attempt that included them.
     */
    private static final EquipmentSlot[] ARMOUR = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    /**
     * Takes the wearer's own armour off their own screen.
     *
     * <p><b>The potion hides a skin, and a skin is not a body.</b> Armour keeps
     * rendering on an invisible player, so somebody who got into a vehicle in a
     * full set watched a helmet and a pair of boots ride along inside their own
     * rig. For everybody ELSE that has never been a problem — {@code hidePlayer}
     * does not send the entity at all, armour included — so this closes the one
     * view that cannot be closed that way.
     *
     * <p><b>Client-side only, and that is what makes it affordable.</b>
     * {@code sendEquipmentChange} is a packet to one connection; nothing is
     * taken off anybody and nothing is stored, so there is no armour to lose in
     * a crash and no marker to recover from — a reconnecting client is sent the
     * real equipment by the server as a matter of course. That is the trade the
     * class note says this deliberately avoids making, avoided.
     *
     * <p><b>Only the four armour slots</b>, never the hands. Sending a blank
     * hand is what broke bows, shields, eating and crossbows — a client applies
     * the packet to its own inventory and then declines to start a use action
     * it believes it has nothing to start. Armour has no use action to break,
     * and the armour bar reads a server-synced attribute rather than this. What
     * it does cost is an inventory screen opened mid-ride showing four empty
     * slots until {@link #restore}'s {@code updateInventory} or the next real
     * equipment change puts them back, which is a great deal less than a
     * floating helmet for the whole journey.
     */
    private void hideArmourFromWearer(Player player, boolean hidden) {
        ItemStack air = new ItemStack(Material.AIR);
        for (EquipmentSlot slot : ARMOUR) {
            ItemStack real = player.getInventory().getItem(slot);
            player.sendEquipmentChange(player, slot, hidden || real == null ? air : real);
        }
    }

    /**
     * Says once that this server's scoreboard is why riders see a ghost of
     * themselves.
     *
     * <p>{@code Team#canSeeFriendlyInvisibles} starts ON, and a client renders
     * a friendly invisible as a translucent copy rather than as nothing — so on
     * a server whose tab-list or name-colour plugin puts players on teams,
     * which is most of them, the wearer sees a see-through version of
     * themselves standing inside their own rig however thoroughly this hides
     * them. <b>Nothing here can fix it</b>: the scoreboard belongs to the
     * server, and turning the flag off for somebody else's team would be this
     * library making a PvP decision on their behalf.
     *
     * <p>So it is SAID instead. The alternative is the one thing worse than the
     * ghost: an owner who has done everything right, cannot see what is wrong,
     * and concludes the feature is broken. One line, once, naming the switch.
     */
    private void warnIfGhosted(Player player) {
        if (warnedGhost) return;
        Team team = player.getScoreboard().getEntryTeam(player.getName());
        if (team == null || !team.canSeeFriendlyInvisibles()) return;
        warnedGhost = true;
        host.plugin().getLogger().warning("A player wearing a rig can still see a see-through copy of "
            + "themselves, because they are on scoreboard team '" + team.getName() + "' and that team has "
            + "canSeeFriendlyInvisibles switched on - which makes a client draw a friendly invisible as a "
            + "ghost rather than as nothing. Whichever plugin creates that team wants "
            + "setCanSeeFriendlyInvisibles(false); this plugin must not change somebody else's team.");
    }

    /** And back. Showing somebody who was never hidden is a no-op. */
    private void showToOthers(Player player) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.getUniqueId().equals(player.getUniqueId())) viewer.showPlayer(host.plugin(), player);
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
        if (!EmoteGround.safeDestination(target)) {
            // Authored tracks dip a pixel or two below the floor wherever a
            // body crouches or lands — the RIG may sink into its pose, but the
            // player's feet cannot go below the ground they started on, and
            // refusing over it is what made root motion die at t=0.1 (the
            // wind-up crouch) or on the landing frame two blocks from home.
            // Lift the target back to the starting floor before giving up;
            // only a genuinely blocked spot (a wall, raised ground) refuses.
            Location lifted = target.clone();
            lifted.setY(Math.max(target.getY(), session.origin.getY()));
            if (lifted.getY() > target.getY() && EmoteGround.safeDestination(lifted)) {
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
        carryFollowers(session, target);
    }

    /**
     * The two displays root motion would otherwise leave on the start line.
     *
     * <p><b>The rig walks by TRANSFORM and these two cannot.</b> A moving
     * emote never teleports its bones — they are spawned once and the root
     * track is folded into each bone's matrix, so the body travels while the
     * display entities holding it stand still. That works because a bone is
     * geometry and geometry is what a transformation moves. A shadow is not:
     * it is drawn by the client at the ENTITY's position, radius and all, and
     * a nametag is billboarded at the entity's position too. So both sat at
     * the spot the emote started from for its whole length and only appeared
     * to catch up at the end, when the emote ended and the player's own real
     * shadow and nameplate came back at wherever they had walked to.
     *
     * <p>Called from {@link #follow} rather than the tick loop, so it moves on
     * exactly the steps that were accepted: a refused step ends following and
     * leaves the player where they are, and these stay with them.
     *
     * <p>A stance has its own version of this in {@link #tickStance} and does
     * not come through here — it moves the whole rig by teleport, so the two
     * are already carried with everything else.
     *
     * <p><b>The wearer's crouch is deliberately not read here, and that is the
     * difference from the stance path rather than an omission.</b> A stance has
     * a crouching STATE — the set swaps to an emote authored for it, so the rig
     * really is crouching and its name comes down with it. A one-shot does not:
     * it plays the animation it was given whatever its wearer does with the
     * shift key, so a name that dropped to crouch height sank about a third of
     * a block into the rig's own head. That is the "the nametag is bugged in
     * the model" this method caused.
     */
    private void carryFollowers(Session session, Location target) {
        // Facing zeroed on both, for the reason the stance path states: a
        // shadow is a circle and a billboarded name turns to its reader, so a
        // yaw in the packet changes nothing on screen and is only a rotation
        // to send.
        if (session.shadow != null && session.shadow.isValid()) {
            Location feet = target.clone();
            feet.setYaw(0);
            feet.setPitch(0);
            if (!feet.equals(session.shadow.getLocation())) session.shadow.teleport(feet);
        }
        // Standing height, always. See the note above: the rig is not
        // crouching, so neither is its name.
        if (session.nameTag != null) session.nameTag.moveTo(target, false);
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
     * <p>Set on a stance, and on the two displays a FOLLOWING emote moves —
     * its shadow and its nametag (see {@link #carryFollowers}). Everything
     * else an ordinary emote spawns stands where it was put and is never
     * moved, and a teleport duration on one of those would be a promise about
     * a packet that is never sent.
     *
     * <p>Takes a {@link Display} rather than an ItemDisplay because the name
     * over the rig is carried on exactly the same terms as the bones under it.
     */
    static void carry(Display display) {
        displayCarry.carry(display);
    }

    /**
     * Puts this session's displays on the glide window that matches whether
     * their wearer is riding — see {@link #carryTicksFor}.
     *
     * <p>Only on the change. Every display this touches is one entity-data
     * write and a packet to every viewer, and the answer changes exactly twice
     * a journey: getting in, and getting out.
     *
     * <p>The followers go too. A shadow or a name that stayed on the world's
     * window would separate from the rig it belongs to by the same tick the
     * rig used to separate from the vehicle, which is the bug one level down
     * rather than a different one.
     */
    private void carryAs(Session session, boolean riding) {
        if (session.carriedAsRider != null && session.carriedAsRider == riding) {
            return;
        }
        session.carriedAsRider = riding;
        DisplayCarry how = riding ? riderCarry : displayCarry;
        for (ItemDisplay display : session.parts) {
            if (display != null && display.isValid()) how.carry(display);
        }
        for (ItemDisplay display : session.propParts) {
            if (display != null && display.isValid()) how.carry(display);
        }
        if (session.mainHand != null && session.mainHand.isValid()) how.carry(session.mainHand);
        if (session.offHand != null && session.offHand.isValid()) how.carry(session.offHand);
        if (session.shadow != null && session.shadow.isValid()) how.carry(session.shadow);
        if (session.nameTag != null) session.nameTag.carryAs(how);
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
        return !player.isOnGround() && !EmoteGround.standingOnSomething(now);
    }

    /**
     * Moves this session's lead on by one pass. See {@link #LEAD_PIPELINE_TICKS}.
     *
     * <p>Its own method so the ping — the one part that needs a live player —
     * is read here and the arithmetic under it is reachable from a test.
     */
    private void advanceLead(Player player, Session session, Location now) {
        // A passenger is not led at all, whatever their connection is doing —
        // see leadTicksFor. Asked first because the ping below is only worth
        // reading to size a lead, and a rider is not getting one.
        if (player.isInsideVehicle()) {
            session.lead = EmoteStance.leadFor(session.lead, session.previous, now, leadTicksFor(true, 0));
            return;
        }
        int ping = 0;
        try {
            ping = player.getPing();
        } catch (RuntimeException | LinkageError e) {
            // A server that cannot answer gets the pipeline delay alone, which
            // is the part that is true of every connection including a local one.
            ping = 0;
        }
        session.lead = EmoteStance.leadFor(session.lead, session.previous, now, leadTicksFor(false, ping));
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
        if (EmoteStance.movedHorizontally(session.previous, now)) {
            session.movingFor = MOVING_HOLD_PASSES;
        } else if (session.movingFor > 0) {
            session.movingFor--;
        }
        boolean moving = session.movingFor > 0;
        boolean sneaking = player.isSneaking();
        boolean airborne = airborne(player, now);
        // Whether the rig is judged against the world or against the seat its
        // wearer is sitting on. Both of the numbers below turn on it, and both
        // were the world's answer for a passenger until a vehicle seat started
        // putting rigs on people who were not walking anywhere.
        carryAs(session, player.isInsideVehicle());
        // Read before `previous` is overwritten, because it is the difference
        // between the two. See LEAD_PIPELINE_TICKS for what it is for.
        advanceLead(player, session, now);
        session.previous = now.clone();
        long tick = player.getWorld().getGameTime();

        // Where the rig stands: one block above the feet, on the player's own
        // centre line, exactly as `begin` placed it — the only offset the whole
        // body fits the block-model bounds at — and forward by however far the
        // wearer will have travelled by the time this is on their screen.
        //
        // Unless a seat has said where the feet are, in which case that is
        // the feet and the lead is nothing (a rider is never led — see
        // advanceLead). The rig itself was moved there when the seat said so;
        // what this pass places against the point is everything else.
        Location feet = session.seat != null
            ? session.seat.clone()
            : now.clone().add(session.lead.getX(), 0, session.lead.getZ());
        Location base = feet.clone().add(0, RIG_BASE_Y, 0);
        base.setYaw(0);
        base.setPitch(0);

        if (session.driven) {
            // Nothing to resolve: whoever asked for this session decides what
            // it wears and has already written it in. The swap itself is done
            // by `wear`, which shares the group branch's rules — see there.
            // This arm exists so that a driven session does not fall into the
            // plain-stance branch below and have its clock restarted by a
            // movement state it does not have.
            session.memberState = null;
        } else if (session.group != null) {
            // A group resolves every state, the air included — see
            // `EmoteTrigger`. Which emote drives the rig follows from it, and
            // a state the set left alone is the player's own body.
            EmoteTrigger state = EmoteStance.stanceState(sneaking, player.isSprinting(), moving, airborne);
            EmoteStore.Emote member = EmoteStance.memberFor(session.members, state);
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
                // The same ease a driven swap gets: walking into a run is the
                // case the comment below about tweening was always describing,
                // and until now it had only the step-to-step window to do it in.
                session.blendUntil = tick + SWAP_BLEND_TICKS;
                // Props belong to the emote, not to the set, so the old one's
                // models go away and the new one's stand up. Bones are shared
                // and are never respawned.
                spawnProps(player, session, session.emote, base, null);
                boolean wasHidden = session.rigHidden;
                setRigHidden(player, session, member == null);
                // Only where a tween would be wrong, not on every swap.
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
            EmoteTrigger state = EmoteStance.stanceState(
                sneaking, player.isSprinting(), moving,
                airborne && session.triggers.contains(EmoteTrigger.JUMP));
            if (!EmoteStance.plays(session.triggers, state)) session.startTick = tick;
        }
        // Their facing, unrounded. `begin` rounds to whole degrees because an
        // emote is authored at one fixed yaw and a hundredth of a degree there
        // is noise; here it is the difference between a body that turns
        // smoothly with the mouse and one that steps between degrees.
        //
        // Unless somebody else owns it. A passenger's body is carried by what
        // they are riding and their head is their own, so a seat says which way
        // its occupant points and their camera is left alone — see
        // `Emotes.face`. Null is every rig that was never told, which is all of
        // them until a vehicle says otherwise.
        session.yaw = session.facing != null ? session.facing : now.getYaw();
        session.origin = now.clone();
        // The rig, and everything that travels with it. See follow.
        follow(session, feet, sneaking);
        return true;
    }

    /**
     * Puts the rig and everything that travels with it at {@code feet}.
     *
     * <p>Called once a pass from {@link #tickStance}, and again by
     * {@link #anchor} whenever a seat says where its occupant is — so for a
     * rider it runs twice a tick, and the second run finds nothing to do.
     * That is deliberate: which of the two runs first in a server tick is the
     * scheduler's business, and either order leaves the rig where the seat
     * put it.
     *
     * <p>Two passes that send nothing, and between them they are what pays
     * for stepping every tick rather than every other one:
     *
     * <p>A rig that is AWAY is not carried at all. Its displays are standing
     * where its wearer left them holding air, and a set crossing into a state
     * it leaves to vanilla can hold that for as long as somebody keeps
     * running. {@code setRigHidden} forgets the position, so the pass that
     * brings the rig back is a pass that moves it.
     *
     * <p>A rig that has not MOVED is not re-sent. A teleport is a packet per
     * display per viewer, and a stance is worn standing still at least as
     * often as it is worn walking.
     *
     * @param feet the wearer's feet, lead included; its yaw is not read
     */
    private void follow(Session session, Location feet, boolean sneaking) {
        Location base = feet.clone().add(0, RIG_BASE_Y, 0);
        base.setYaw(0);
        base.setPitch(0);
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
            Location flat = feet.clone();
            flat.setYaw(0);
            flat.setPitch(0);
            if (!flat.equals(session.shadow.getLocation())) session.shadow.teleport(flat);
        }
        // No `moved` gate here either, and for the shadow's reason plus one
        // more: the height moves with the crouch as well as with the feet, so
        // a gate on position alone would miss somebody pressing shift while
        // standing still. Whether anything is actually written is EmoteNameTag's
        // question, and it answers it against both.
        //
        // The same lead the rig gets — a name on the position we were handed
        // would drift off the head it belongs to exactly as far as the rig was
        // drifting before — and it goes on following while the rig is away,
        // since that is hidden by view range rather than by being left behind.
        if (session.nameTag != null) {
            session.nameTag.moveTo(feet.clone(), sneaking);
        }
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
        if (EmoteGround.groundRefusal(other) != null) {
            return EmoteResult.refused(Reason.CAST_NOT_ON_GROUND, other.getName());
        }
        return null;
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
     * at zero ticks, so the client is told to land the frame instead of easing
     * into it over {@link #INTERPOLATION_TICKS}. The two together are
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
        pose(player.getUniqueId(), session, 0);
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
                    pose(entry.getKey(), session, poseTicks(session, player.getWorld().getGameTime()));
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
            pose(entry.getKey(), session, INTERPOLATION_TICKS);
        }
    }

    /**
     * How long the client should take over this pass's pose.
     *
     * <p>Three answers and they are one mechanism at three values, which is why
     * this replaced a boolean: {@code snap} is a hard cut, a swap is easing
     * into the new animation, and everything else is the ordinary step-to-step
     * window. See {@link #SWAP_BLEND_TICKS}.
     */
    private int poseTicks(Session session, long now) {
        if (session.snap) {
            return 0;
        }
        return now < session.blendUntil ? SWAP_BLEND_TICKS : INTERPOLATION_TICKS;
    }

    private void pose(UUID playerId, Session session, int ticks) {
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
            display.setInterpolationDuration(ticks);
            display.setTransformation(next);
        }

        poseProps(session, root, composed, t, ticks);
        poseHands(session, composed, ticks);
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
    private void poseHands(Session session, Map<String, Matrix4f> composed, int ticks) {
        poseHand(session, session.mainHand, HeldItem.MAIN_HAND_ATTACH, false, composed, ticks);
        poseHand(session, session.offHand, HeldItem.OFF_HAND_ATTACH, true, composed, ticks);
    }

    private void poseHand(
            Session session,
            ItemDisplay display,
            String attach,
            boolean offHand,
            Map<String, Matrix4f> composed,
            int ticks) {
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
        display.setInterpolationDuration(ticks);
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
            Session session, Matrix4f root, Map<String, Matrix4f> composed, double t, int ticks) {
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
            display.setInterpolationDuration(ticks);
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
            // Through RigTags because where a bone's identity is carried is
            // the server's version: a string list on 1.21.4 and up, the
            // stack's persistent data below it.
            rigTags.write(meta, Collections.singletonList(modelData));
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

    /**
     * Sets how a rig's held item is turned in its hand. <b>Calibration.</b>
     *
     * <p>Three degrees, applied X then Y then Z, straight through to
     * {@code HeldItem.orient} — which is package-private and is where the whole
     * explanation lives. This exists only because that class is not reachable
     * from the plugin, and because the turn is the one part of the hand that
     * cannot be settled by a test: it has been changed four times from four
     * confident readings of the same arithmetic, and the thing that was missing
     * every time was somebody looking at a rig.
     *
     * <p>The defaults are correct as far as anyone knows. A server owner has no
     * reason to touch these.
     */
    public static void heldItemTurn(float pitch, float yaw, float roll) {
        HeldItem.turn(pitch, yaw, roll);
    }

    /** See {@link EmoteNameTag}. Called from the plugin on load and reload. */
    public static void nameTagsSeeThrough(boolean seeThrough) {
        EmoteNameTag.seeThrough(seeThrough);
    }

    /**
     * How a moved rig gets where it is going, which depends on the server's
     * version — see {@link DisplayCarry}.
     *
     * <p>Defaults to the arm that does nothing, so that a code path reaching
     * {@link #carry} before the plugin has resolved its compatibility snaps
     * rather than throwing on a server where the smooth arm would not load.
     */
    private static volatile DisplayCarry displayCarry = new DisplayCarry.Immediate();

    /**
     * The same, for a wearer who is RIDING something. See {@link #carryTicksFor}.
     *
     * <p>A second arm rather than a number passed to the first, because the
     * choice is made per pass and building a {@code DisplayCarry} per pass to
     * express it would allocate on the hot path for a value that only ever
     * takes two shapes.
     */
    private static volatile DisplayCarry riderCarry = new DisplayCarry.Immediate();

    /** Set from the plugin once the server's version is known. */
    public static void displayCarry(DisplayCarry carry) {
        displayCarry = carry == null ? new DisplayCarry.Immediate() : carry;
    }

    /** The rider arm of the above, set from the same place at the same moment. */
    public static void riderDisplayCarry(DisplayCarry carry) {
        riderCarry = carry == null ? new DisplayCarry.Immediate() : carry;
    }

    /**
     * Where a bone's identity is carried, which is the server's version — see
     * {@link RigTags}. Starts inert for the same reason {@link #displayCarry}
     * does.
     */
    private static volatile RigTags rigTags = RigTags.NONE;

    /** Set from the plugin once the server's version is known. */
    public static void rigTags(RigTags resolved) {
        if (resolved != null) {
            rigTags = resolved;
        }
    }

    /** How long a carried display is given to cover a move. */
    public static int interpolationTicks() {
        return INTERPOLATION_TICKS;
    }

    /** The same, for a wearer who is riding. See {@link #carryTicksFor}. */
    public static int carryTicksForRider() {
        return carryTicksFor(true);
    }

    /**
     * How long a carried display is given to cover a move, given whether its
     * wearer is riding something.
     *
     * <p><b>A rig on a passenger is judged against the RIDER, not the world.</b>
     * That is the whole of it, and it is the distinction
     * {@link ai.resourcepack.engine.core.model.DisplayLatency} already draws:
     * a rider is drawn wherever their mount is, which the client lerps over
     * {@code TRACKED_ENTITY_TICKS}, so anything that has to stay on the seat
     * must be behind by exactly that much. The vehicle's own model already is
     * — see {@code Vehicles.MODEL_GLIDE_TICKS}, which reasoned this out first
     * and says in as many words that being a tick tighter than the rider is
     * the bug rather than an improvement.
     *
     * <p>Off a mount the old answer is the right one: there the rig is judged
     * against the WORLD, where less lag is better and a stutter is the only
     * failure. Nothing about a walking wearer changes.
     */
    static int carryTicksFor(boolean riding) {
        return riding
                ? ai.resourcepack.engine.core.model.DisplayLatency.TRACKED_ENTITY_TICKS
                : INTERPOLATION_TICKS;
    }

    /**
     * How far ahead of themselves a wearer's rig is placed, in ticks.
     *
     * <p><b>Nothing at all for a passenger, and that is not a tuning choice.</b>
     * The lead exists to cancel how stale a player's SELF-REPORTED position is
     * — see {@link #LEAD_PIPELINE_TICKS} — and a passenger reports nothing:
     * the server moves the mount and their location is read back off it. So
     * there is no staleness to cancel, and every block of lead is overshoot
     * with nothing underneath it.
     *
     * <p>It is not a small overshoot either, because the lead is sized by the
     * step taken per tick and a vehicle's step dwarfs a gait. At 12 blocks a
     * second a hull covers 0.6 of a block a tick, so the reckoning wants over
     * a block and is pinned at {@link EmoteStance#MAX_LEAD} for the whole
     * journey: the rig sits two thirds of a block in front of the hull and
     * swings wide of every corner, which is what "it goes faster than the
     * vehicle" is.
     *
     * <p>Zero TICKS rather than a zeroed vector, so climbing into a seat
     * mid-stance unwinds an existing lead through the smoothing that is
     * already there instead of snapping the rig back onto the seat.
     *
     * @param pingMillis the wearer's round trip, halved here because what
     *                   matters is the leg from them to us
     */
    static double leadTicksFor(boolean riding, int pingMillis) {
        if (riding) {
            return 0;
        }
        return Math.min(MAX_LEAD_TICKS, LEAD_PIPELINE_TICKS + Math.max(0, pingMillis) / 100.0);
    }
}
