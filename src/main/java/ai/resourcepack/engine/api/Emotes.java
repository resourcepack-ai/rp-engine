package ai.resourcepack.engine.api;

import org.bukkit.command.CommandSender;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Emotes: animations played on a PLAYER rather than on a placed model.
 *
 * <p>An emote renders somebody's skin, and a resource pack cannot reach a live
 * one - so a pack carries a baked rig per player it was built for, and a
 * player with no rig in the pack they are wearing cannot emote.
 * {@link #canPerform} is that question with an answer; it is a real state with
 * a real cause, not a mystery failure.
 *
 * <p>The performer is made invisible for the duration and put back exactly
 * where they were, keeping whatever invisibility they already had minus the
 * time the emote took. Being hit interrupts it. An emote with a cast ends for
 * everybody or for nobody.
 *
 * <p><b>Main thread only</b>, except the catalogue queries ({@code ids},
 * {@code info}) which read concurrent state and are safe anywhere.
 */
public interface Emotes {

    /**
     * The engine's own sitting stance: legs out at the hip, nothing else.
     *
     * <p><strong>A stance every pack has without authoring one.</strong>
     * {@link #wear} resolves this before it looks in the pack, so it works on a
     * pack that ships no emotes at all — the only thing it still needs is a rig
     * for that player, because a rig is their skin and no amount of built-in
     * anything can invent one.
     *
     * <p>It exists because a vehicle seat is the one place a rig has to be
     * worn whether or not anybody authored a pose for it. What the alternative
     * looked like: a driver in a kayak drawn as a vanilla passenger, which is a
     * player standing up to their waist in the hull at the wrong angle. The
     * pose is the same one studio's seat preview draws — the thigh swung
     * forward 90 degrees, the knee left straight because a leg is one box —
     * so the editor and the game show the same person in the same seat.
     *
     * <p>Reserved rather than namespaced: an emote id is a name somebody types
     * after {@code /emote}, studio allocates them as slugs, and no slug starts
     * with {@code @}. So a pack cannot define this and cannot collide with it.
     * A pack that wants something else maps its own emote to the state, which
     * wins because a named emote is only ever fallen back FROM.
     */
    String BUILT_IN_SITTING = "@sitting";

    /**
     * The engine's own standing stance: the rig, at rest, and nothing applied.
     *
     * <p>The twin of {@link #BUILT_IN_SITTING} for a seat somebody stands on —
     * a gunner's step, a ferry deck. There is no pose to give it, because
     * standing upright IS the rest pose; what it buys over passing
     * {@code null} is that the rig is SHOWN rather than put away, which is the
     * whole difference between "wear your rig here" and "be yourself here".
     */
    String BUILT_IN_STANDING = "@standing";

    /**
     * Every emote this server holds, in manifest order.
     *
     * <p>An emote's id is the name the panel shows for it, which is also what
     * a player types - unlike a model id, which is not shown to anybody.
     *
     * <p><b>Movement GROUPS are in this list too.</b> A group is worn by name
     * with {@link #play}, exactly as an emote is, so a caller building a menu
     * or completing a command wants both — and studio allocates the two out of
     * one name space, so nothing here can collide. {@link EmoteInfo#isGroup()}
     * tells them apart for a caller that has to care.
     */
    List<String> ids();

    /**
     * What an emote is: how long, whether it loops, who else it needs, and
     * whether it is a group rather than a single emote.
     */
    Optional<EmoteInfo> info(String emoteId);

    /** Whether this player is in an emote right now, as lead or as cast. */
    boolean isEmoting(UUID playerId);

    /**
     * Whether this player has a rig in the pack they are wearing, and could
     * therefore emote at all.
     *
     * <p>False for somebody who joined after the pack was pushed. It says
     * nothing about whether they are on the ground, in combat, or already
     * emoting - {@link #play} answers all of that, with a reason.
     */
    boolean canPerform(Player player);

    /**
     * Plays a solo emote.
     *
     * @return {@link EmoteResult#started()} on success, otherwise a reason
     *         specific enough to tell the player what to do about it.
     */
    EmoteResult play(Player lead, String emoteId);

    /**
     * Plays an emote with a cast.
     *
     * <p>The cast is teleported into place, put in the emote's own facing, and
     * given back their position afterwards. The list must be exactly as long as
     * the emote's performer list - a duet run alone would put one person
     * through half a handshake with nobody opposite, so a mismatch is refused
     * rather than padded or trimmed.
     *
     * <p>Nothing is spawned and nobody is moved until every participant and
     * every destination has been checked.
     */
    EmoteResult play(Player lead, String emoteId, List<Player> cast);

    /**
     * The free-text form, for building your own command.
     *
     * <p>Emote names are free text with spaces in them, so
     * {@code "Slow clap Steve"} is either one emote or an emote and a player
     * and there is no separator to tell them apart. The longest leading run of
     * words that names an emote wins. That resolution lives here rather than in
     * a command because only this library knows which names exist.
     */
    EmoteResult perform(Player lead, List<String> words);

    /**
     * Who could still be named on a half-typed command, for tab completion.
     *
     * <p>Empty unless the words so far name an emote that takes a cast and
     * there are slots left, so completing a solo emote offers nobody and a duet
     * stops offering once its one partner is typed.
     */
    List<String> castCandidates(CommandSender sender, String[] words);

    /**
     * Wears an emote whose choice belongs to the CALLER rather than to the
     * player's own movement.
     *
     * <p>Everything a stance does — the rig follows its wearer every tick,
     * there is no anchor and no drift-cancel, and the wearer is free to move —
     * with the one difference that nothing here reads
     * {@link EmoteTrigger}. A movement set decides what to wear from whether
     * you are walking; this decides nothing at all, and waits to be told.
     *
     * <p>It exists because a vehicle occupant is the first thing that wants a
     * worn rig for a reason that is not movement: what a driver's body should
     * be doing is decided by the VEHICLE — steering, idling, reversing — and
     * their own legs are not walking anywhere. Driving that through a movement
     * set would mean spelling "the vehicle is turning" as a walk cycle.
     *
     * <p><strong>Calling it again swaps</strong>, without restarting the
     * session or respawning the rig — the same swap a movement set does when
     * its wearer breaks into a run, and keyed the same way, on the emote rather
     * than on whatever the caller called the state. Passing {@code null} puts
     * the rig away and gives the player their own body back while keeping the
     * session, which is what a state the pack left blank means.
     *
     * <p><strong>The wearer sees the rig here</strong>, which is the one place
     * this differs from a movement set. A set is worn for an hour of walking
     * around, so it keeps its wearer's own first person vanilla and the rig is
     * for everybody else; somebody in a seat is looking at the vehicle they are
     * sitting in, from outside, so their body goes and the rig is what they
     * watch. Nobody else's view differs between the two.
     *
     * @return {@link EmoteResult#started()} once it is on — including on a
     *         swap — or a reason specific enough to act on
     */
    EmoteResult wear(Player player, String emoteId);

    /**
     * The same, over a base pose the emote only partly replaces.
     *
     * <p><strong>Naming an emote for a seat used to make its occupant stand
     * up.</strong> A seat with nothing mapped gets {@link #BUILT_IN_SITTING},
     * which is one keyframe on each leg; the moment it named an emote, that
     * emote's animators replaced the lot, the legs fell back to rest, and the
     * rider was drawn standing with their arms doing the steering pose. Every
     * seat that named anything had it.
     *
     * <p>So the base is merged UNDER the emote, per bone: a bone the emote is
     * silent about keeps the base's pose, and a bone it animates is entirely
     * the author's. Explicit beats default, which is the rule the arm swing and
     * the cape already follow — both are applied on top of what an emote asked
     * for rather than instead of it. An emote that deliberately swings a leg
     * out of a kayak therefore keeps its leg.
     *
     * <p>The merge is done once, when what is worn changes, and never per tick.
     *
     * @param under one of the built-in stances, or null for no base — which is
     *              exactly what {@link #wear(Player, String)} means
     */
    default EmoteResult wear(Player player, String emoteId, String under) {
        return wear(player, emoteId);
    }

    /**
     * Which way a worn rig FACES, when that is not the way its wearer is
     * looking.
     *
     * <p>A worn rig turns with its wearer's camera, because for somebody
     * walking around the two are the same thing: you face where you look. A
     * passenger is the case where they are not. Their body is carried by
     * whatever they are riding and their head is their own — a vanilla player
     * in a boat keeps their body square to the hull however far round they
     * turn to look at the scenery — and a rig that spun with the mouse instead
     * put a seated driver sideways in their own kayak while the boat went
     * straight on.
     *
     * <p>So the caller that OWNS the seat says which way it points, and keeps
     * saying it: a vehicle turns, so this is per tick rather than once at the
     * start. It is cheap to repeat — the same value twice changes nothing and
     * sends nothing.
     *
     * <p><strong>{@code null} gives the rig back to its wearer's look</strong>,
     * which is what every rig that has never been told otherwise already does.
     * That is the value to pass when somebody gets out, and it is what the end
     * of a session restores on its own.
     *
     * <p>Ignored for a player who is not wearing anything — there is no rig to
     * point, and a facing remembered for one that might arrive later would be a
     * setting with no way to clear it.
     *
     * <p><strong>Ignored, too, for a rig this caller did not put on.</strong>
     * Somebody already mid-emote of their own when they sit down keeps it —
     * {@link #wear} refuses rather than taking their body — and pointing that
     * rig at the seat would be claiming what was just declined. So this only
     * moves a rig that arrived through {@code wear}.
     *
     * @param yaw degrees, the same frame a {@link org.bukkit.Location}'s yaw
     *            uses, or {@code null} to follow the wearer's own look
     */
    void face(Player player, Float yaw);

    /**
     * Where a worn rig STANDS, when its wearer's own position is not the
     * answer.
     *
     * <p>A worn rig follows its wearer's feet, because for somebody walking
     * around that is where they are. A passenger is the case where it is not
     * — or rather, where it is only approximately: their position is wherever
     * vanilla put them over their mount, a tick after the mount moved, by a
     * rule that has changed between versions. The seat's owner knows exactly
     * where the seat is, in the same arithmetic it places the vehicle's model
     * with, and this is how it says so.
     *
     * <p><strong>Calling it moves the rig there and then</strong>, in the
     * caller's tick, rather than noting the position for the emote system's
     * own pass — that pass may run before or after the vehicle's in a given
     * server tick, and a rig placed a tick behind the model it sits in is
     * half a block out of its seat at speed. So the caller that owns the seat
     * calls this from the same place it moves the model, every tick, and the
     * two arrive together.
     *
     * <p>{@code feet} is the occupant's feet — for a sitting seat, a hip
     * below its point — and its yaw is ignored: {@link #face} owns the
     * direction. Ignored, like {@code face}, for a player wearing nothing and
     * for a rig this caller did not put on.
     *
     * @param feet where the wearer's feet are this tick, or {@code null} to
     *             give the rig back to following its wearer
     */
    void anchor(Player player, Location feet);

    /**
     * Where a worn rig's clock IS, when somebody else's clock is the one that
     * matters.
     *
     * <p>A worn emote runs on its own clock from the moment it was put on. A
     * vehicle's paddle and the arms holding it are two animations on two
     * clocks that started on the same tick — and stay together only until
     * one of them is restarted, or a loop wraps a tick apart, or the vehicle
     * flickers between two states. Called every tick with the rig animation's
     * own {@link Placement#playhead}, this makes the rider's clock the
     * vehicle's: the emote is posed at exactly that time, loop wrap included,
     * and the two cannot drift.
     *
     * <p>Ignored, like {@code face} and {@code anchor}, for a player wearing
     * nothing and for a rig this caller did not put on. Quantised to the
     * server tick; a call that would move the clock by less than one does
     * nothing, so calling it every tick with a steady clock costs nothing.
     *
     * @param seconds where in the worn emote the rig should be, in seconds.
     *                An emote shorter than that wraps if it loops and holds
     *                its end if it does not
     */
    void seek(Player player, double seconds);

    /**
     * Whether a worn rig's CAPE is drawn.
     *
     * <p>A cape is baked into the rig as an extra bone and hangs off the back
     * of it, which is right for somebody standing in a field and wrong for
     * somebody sitting inside something: in a car's cabin or an aeroplane's
     * fuselage it clips straight out through the bodywork, and there is
     * nothing the author of either the emote or the model can do about it from
     * their end. So the thing that put the rider there is what decides.
     *
     * <p><strong>The display is kept and its item swapped for air</strong>,
     * the same trick a movement group's hidden rig uses — so this is one
     * packet, is free to call every tick, and cannot fall out of step with the
     * bones it was spawned beside.
     *
     * <p>Ignored, like {@link #face}, {@link #anchor} and {@link #seek}, for a
     * player wearing nothing and for a rig this caller did not put on. Doing
     * nothing is also the answer for a rider who has no cape, which is most of
     * them.
     *
     * @param show false to put it away for as long as they wear this rig
     */
    default void cape(Player player, boolean show) {
        // A default keeps an implementation compiled against an older RP
        // Engine API binary-compatible. The engine's implementation overrides
        // this; an external implementation that knows no cape bone has nothing
        // honest to do.
    }

    /**
     * Stops this player's emote, and everybody else's in the same troupe.
     *
     * @return whether they were emoting.
     */
    boolean stop(Player player);
}
