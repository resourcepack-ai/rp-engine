package ai.resourcepack.engine.core.emote;

import ai.resourcepack.engine.api.EmoteTrigger;
import ai.resourcepack.engine.api.Keyframe;
import ai.resourcepack.engine.core.animation.Sampler;
import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.List;
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

    // ---- the gait clock --------------------------------------------------
    //
    // A walk cycle is not a thing that happens over a second, it is a thing
    // that happens over a STRIDE. Left to run at the animation's own rate it
    // plays identically whether its wearer is wading through soul sand, on
    // flat ground, or under Speed II — and the feet slide across the floor,
    // which is the single most visible way an animation reads as fake.
    //
    // So a gait's playhead is advanced by how far its wearer TRAVELLED rather
    // than by how long they took, exactly as a vehicle's wheels are (see the
    // vehicle runtime's animationFollowsSpeed). The reference speeds below are
    // what makes it a no-op at the ordinary pace: a cycle authored for walking
    // and played by somebody walking runs at exactly the rate it was drawn at,
    // which is what every stance built before this already did.

    /**
     * Walking, in blocks per tick — vanilla's 4.317 blocks a second.
     *
     * <p>The speed a {@link EmoteTrigger#WALK} cycle is ASSUMED to have been
     * authored for, which is what makes the link invisible on flat ground and
     * what makes it work everywhere else. Nobody sets this per emote and
     * nobody should have to: an author draws a walk cycle by watching a player
     * walk.
     */
    static final double WALK_SPEED = 4.317 / 20.0;

    /** Sprinting, in blocks per tick — vanilla's 5.612 blocks a second. */
    static final double SPRINT_SPEED = 5.612 / 20.0;

    /** Crouch-walking, in blocks per tick — vanilla's 1.295 blocks a second. */
    static final double SNEAK_SPEED = 1.295 / 20.0;

    /**
     * How fast the measured gait follows the real one, per pass.
     *
     * <p><b>Mojang's own 0.4</b>, and the same number {@link CapeSway} chases
     * its bob with, because it is the same quantity: vanilla's
     * {@code limbSwingAmount} is a one-pole filter over the step the player
     * took, for the same reason this is. A player's position reaches the server
     * in movement packets that arrive when they arrive, so the raw step is two
     * ticks of travel and then a tick of nothing — read literally that is legs
     * stuttering twenty times a second. A total over any window is unchanged by
     * the filter; only the jitter inside it is.
     */
    static final double GAIT_CHASE = 0.4;

    /**
     * The fastest a gait may ever play, as a multiple of its authored rate.
     *
     * <p>Three, which covers Speed II and most of what a server's walk-speed
     * attribute is set to. Beyond it the legs simply stop getting faster —
     * a cycle at ten times its rate is a blur that reads as broken, and a
     * player moving that fast is being carried rather than walking.
     */
    static final double MAX_GAIT_RATE = 3.0;

    /**
     * The biggest step one pass may contribute to the gait, in blocks.
     *
     * <p>A teleport, a knockback or an elytra launch is not a stride, and the
     * filter above would spread one across the next half-second of leg
     * movement. Capped rather than refused, for {@link #leadFor}'s reason: the
     * body really is moving, and the next pass re-reads the truth. Three times
     * a sprint stride, so nothing anybody can do on foot reaches it.
     */
    static final double MAX_GAIT_STEP = SPRINT_SPEED * MAX_GAIT_RATE;

    /** Below this the gait is stopped, in blocks per tick. See LEAD_DEAD_ZONE. */
    static final double GAIT_DEAD_ZONE = 0.002;

    /**
     * The most points of a cycle a join is allowed to look at. See
     * {@link #nearestPhase}.
     *
     * <p>A gait cycle is a second or so, which is twenty ticks and under this
     * either way. The cap is for the emote somebody authored thirty seconds
     * long and dropped into a movement set: six hundred samples per bone on a
     * state change, several times a second, for a join nobody would notice.
     */
    static final int MAX_JOIN_SAMPLES = 64;

    /**
     * The ground speed a state's cycle is authored for, or 0 for a state whose
     * clock is time rather than distance.
     *
     * <p>Standing still, crouching in place and being off the ground are the
     * three states that are not gaits — an idle breathes at its own rate, and
     * a body in the air is on gravity's clock rather than its own, so neither
     * has a stride to be in step with.
     */
    static double gaitSpeed(EmoteTrigger state) {
        if (state == null) return 0;
        switch (state) {
            case WALK: return WALK_SPEED;
            case SPRINT: return SPRINT_SPEED;
            case SNEAK_MOVE: return SNEAK_SPEED;
            default: return 0;
        }
    }

    /**
     * How fast this state's cycle plays, as a multiple of its authored rate.
     *
     * <p>One for a state that is not a gait, which is the whole of "nothing
     * else changed". Zero for a gait whose wearer is not actually moving — the
     * legs stop where they are rather than walking on the spot, which is what a
     * body stopping dead does and what the ease into the idle member then has
     * to hide.
     *
     * @param blocksPerTick the SMOOTHED horizontal step — see {@link #GAIT_CHASE}
     */
    static double gaitRate(EmoteTrigger state, double blocksPerTick) {
        double reference = gaitSpeed(state);
        if (reference <= 0) return 1;
        if (!Double.isFinite(blocksPerTick) || blocksPerTick <= 0) return 0;
        return Math.min(MAX_GAIT_RATE, blocksPerTick / reference);
    }

    /**
     * The gait speed after this pass: the last one chased toward what was
     * actually stepped, in blocks per tick.
     *
     * <p>It has to reach zero rather than approach it, for
     * {@link #LEAD_DEAD_ZONE}'s reason one field over: a playhead creeping
     * forward by a millionth of a second for ever is a rig whose transform
     * differs from the last one on every tick, which is every bone re-sent to
     * every viewer for as long as somebody stands still wearing a set.
     */
    static double chaseGait(double current, double stepped) {
        double measured = !Double.isFinite(stepped) || stepped < 0 ? 0 : Math.min(stepped, MAX_GAIT_STEP);
        double from = Double.isFinite(current) && current > 0 ? current : 0;
        double next = from + (measured - from) * GAIT_CHASE;
        return next < GAIT_DEAD_ZONE ? 0 : next;
    }

    /**
     * How far one pass carried the player, horizontally, in blocks.
     *
     * <p>Zero across a world change and on the first pass, on the same terms
     * as {@link #movedHorizontally} — and horizontal for its reason too, since
     * falling down a shaft is not a stride however fast it happens.
     */
    static double stepBetween(Location previous, Location now) {
        if (previous == null || now == null) return 0;
        if (previous.getWorld() != now.getWorld()) return 0;
        double dx = now.getX() - previous.getX();
        double dz = now.getZ() - previous.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Whether a swap from {@code from} to {@code to} may join the new cycle
     * mid-way rather than at its beginning.
     *
     * <p><b>Both have to be GAITS, and narrowing it to that was a bug fix
     * rather than caution.</b> Two stride cycles of one person have no
     * beginning between them, so joining in phase is the whole point — see
     * {@link #nearestPhase}. Every other member of a set is a NARRATIVE: a jump
     * launches, tucks and lands, an idle settles into itself. Joining one of
     * those at the frame that happens to resemble the walk you arrived from
     * skips the part that makes it read as what it is, which in game is a jump
     * that "just doesn't work".
     *
     * <p>It was first gated on the arriving emote LOOPING, on the reasoning
     * that a cycle has no beginning and a one-shot does. That is true and it is
     * not the same question: whether an animation loops is a fact about how it
     * ends, and whether it has a beginning worth seeing is a fact about what it
     * is. The set on the test server had a three-second looping idle and a
     * one-point-eight-second jump, and only the second was protected.
     */
    static boolean joinsInPhase(EmoteTrigger from, EmoteTrigger to) {
        return gaitSpeed(from) > 0 && gaitSpeed(to) > 0;
    }

    /**
     * Where in {@code next}'s cycle to join it so the body barely moves.
     *
     * <p><b>A movement set is several cycles of ONE body, and they are not
     * unrelated animations.</b> Started from the top every time, a wearer
     * breaking into a run had their walk's mid-stride legs asked for the run's
     * frame zero: a whole stride to cross in five ticks, every time anybody
     * crossed between two states, on a pair of legs that had nothing wrong with
     * where they were. Joined at the point of the new cycle nearest the pose
     * already on screen, there is almost nothing for the ease to do — which is
     * the same fix the vehicle rigs got, for the same reason, and the reason a
     * wheel there keeps its phase across a change of state.
     *
     * <p><b>Ask {@link #joinsInPhase} before calling this.</b> A non-looping
     * animation is refused here as a backstop — joined in the middle it would
     * end early — but that check is not the one that matters, and relying on it
     * alone is what broke jumping: it is a fact about how an animation ends
     * rather than about whether it has a beginning worth seeing.
     *
     * <p>Pure, and free of the plugin: given a pose and an emote it answers a
     * number, which is what lets the join be tested rather than watched.
     *
     * @param shown  the nine values each bone is currently composed from,
     *               index-aligned with {@code bones} — mid-ease included, since
     *               what is ON SCREEN is what the join has to match rather than
     *               what some cycle would sample to
     * @return seconds into {@code next}, or 0 when there is nothing to match
     */
    static double nearestPhase(EmoteStore.Emote next, List<EmoteStore.Bone> bones, float[][] shown) {
        if (next == null || !next.loop || next.length <= 0) return 0;
        if (next.animators == null || next.animators.isEmpty()) return 0;
        if (bones == null || bones.isEmpty() || shown == null) return 0;

        // Only the bones the new cycle actually turns. A bone it says nothing
        // about samples to the same rest pose at every point of it, so it adds
        // the same cost everywhere and cannot move the answer — it would only
        // be work.
        int count = Math.min(bones.size(), shown.length);
        float[][] leaving = new float[count][];
        List<List<Keyframe>> tracks = new java.util.ArrayList<>(count);
        int matched = 0;
        for (int i = 0; i < count; i++) {
            EmoteStore.Bone bone = bones.get(i);
            Map<String, List<Keyframe>> animator = bone == null || bone.key == null
                    ? null : next.animators.get(bone.key);
            List<Keyframe> track = animator == null ? null : animator.get("rotation");
            if (track == null || track.isEmpty() || shown[i] == null) {
                tracks.add(null);
                continue;
            }
            tracks.add(track);
            leaving[i] = shown[i];
            matched++;
        }
        if (matched == 0) return 0;

        int samples = Math.min(MAX_JOIN_SAMPLES, Math.max(1, (int) Math.ceil(next.length * 20)));
        double best = 0;
        double bestCost = Double.MAX_VALUE;
        for (int sample = 0; sample < samples; sample++) {
            double t = next.length * sample / samples;
            double cost = 0;
            for (int i = 0; i < count; i++) {
                List<Keyframe> track = tracks.get(i);
                if (track == null) continue;
                float[] here = Sampler.sample(track, t, NO_TURN);
                for (int axis = 0; axis < 3; axis++) {
                    // The short way round, so a bone at 350 degrees reads as
                    // ten from one at 0 rather than three hundred and fifty.
                    double d = here[axis] - leaving[i][axis];
                    d -= 360 * Math.round(d / 360);
                    cost += d * d;
                }
            }
            if (cost < bestCost) {
                bestCost = cost;
                best = t;
            }
        }
        return best;
    }

    private static final float[] NO_TURN = {0f, 0f, 0f};

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
