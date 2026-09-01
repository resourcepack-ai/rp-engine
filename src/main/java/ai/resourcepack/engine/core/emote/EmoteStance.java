package ai.resourcepack.engine.core.emote;

import ai.resourcepack.engine.api.EmoteTrigger;
import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.Set;

/**
 * Which emote of a worn set a player's body is currently asking for, and how
 * far ahead of them their rig should stand.
 *
 * <p>Split out of {@link EmoteDirector} because it is the one part of a worn
 * emote that is a pure function of the player's movement: given where they
 * were, where they are, and which keys they are holding, these answer which
 * member plays and where it goes. Nothing here touches a display entity, a
 * session or the server, which is why {@code EmoteStanceTest} and
 * {@code EmoteGroupTest} can drive the whole of it with plain values.
 *
 * <p>The director owns the state these read — the previous location, the
 * accumulated lead, the hold counters — and calls in once a pass. Keeping the
 * arithmetic here and the state there is what stops a rule about gaits being
 * expressible only against a live {@code Player}.
 */
final class EmoteStance {

    private EmoteStance() {
    }

    /**
     * How far a stance's wearer must travel in one pass to count as moving.
     *
     * <p>Horizontal only, and measured over a single poll pass — the slowest
     * real gait is a crouch at about 0.065 blocks a tick, so this sits well
     * under it and well over the hundredths a standing player's position
     * jitters by as the client reconciles. It decides between
     * {@link EmoteTrigger#IDLE} and the moving states, so getting it wrong
     * shows up as a walk cycle flickering under somebody standing still.
     */
    static final double STANCE_MOVING_STEP = 0.015;

    /**
     * The furthest ahead of themselves a wearer's rig may be put, in blocks.
     *
     * <p>The cost of leading is that a body which stops, turns or is teleported
     * was not going where it was going — so the rig overshoots by up to this and
     * is walked back. Two thirds of a block is under half a stride at a sprint
     * and is unwound in a few passes by the smoothing below; a metre would be a
     * rig that swings wide of every corner.
     */
    static final double MAX_LEAD = 0.65;

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
    static final double LEAD_SMOOTHING = 0.5;

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
    static final double LEAD_DEAD_ZONE = 0.01;

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
     * exactly like {@link EmoteDirector#rigToWorld} and
     * {@link EmoteDirector#applyPropStep}.
     */
    static boolean movedHorizontally(Location previous, Location now) {
        if (previous == null || now == null) return false;
        if (previous.getWorld() != now.getWorld()) return false;
        double dx = previous.getX() - now.getX();
        double dz = previous.getZ() - now.getZ();
        return dx * dx + dz * dz > STANCE_MOVING_STEP * STANCE_MOVING_STEP;
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
     * exactly like {@link EmoteDirector#rigToWorld} and {@link #movedHorizontally}.
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
}
