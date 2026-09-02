package ai.resourcepack.engine.api;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * What a vehicle is doing right now, as the handful of words a pack can hang
 * behaviour off.
 *
 * <p>A vehicle is in <strong>several of these at once</strong> — a car coming
 * down off a kerb mid-corner is turning and airborne together — so this is
 * read as a set rather than as a single value. {@code VehiclePhysics} computes
 * it, which is what keeps it pure and testable: whether a vehicle counts as
 * moving is arithmetic over its speed, not something the world has to be asked
 * about.
 *
 * <h2>Two consumers, two readings, and that is deliberate</h2>
 *
 * <p><strong>Particles match any.</strong> An emitter names the states it
 * fires in and fires when the vehicle is in any of them, so one plume can
 * cover {@code moving} and {@code reversing} without being written twice.
 *
 * <p><strong>Animations pick one</strong>, because a rig has one clock. The
 * declaration order below <em>is</em> the precedence, highest first, and a
 * state the pack did not configure is skipped rather than played empty — so an
 * author who set only {@code idle} and {@code moving} gets sensible behaviour
 * without ever learning that {@link #SUBMERGED} exists. See {@link #choose}.
 *
 * <p>The precedence is the answer to "what is the most interesting thing this
 * vehicle is doing". Leaving the ground beats everything, because a vehicle in
 * the air is not driving; direction of travel beats turning, because a car
 * reversing round a corner is reversing; and being in water is the least
 * specific of them, because for a boat it is simply the normal case.
 *
 * <p>Studio's editor offers exactly these under the same names. There is no
 * shared type between a Worker and a jar, so that agreement is by hand: change
 * a name here and change {@code VEHICLE_STATES} in studio's
 * {@code lib/model-subtypes.ts} in the same breath.
 */
public enum VehicleState {

    /**
     * Off the ground with nothing holding it up.
     *
     * <p>For a land or water vehicle that is a jump or a fall. For an air
     * vehicle it is simply flying — one parked on a runway is not airborne,
     * which is what makes a separate taxiing animation possible.
     */
    AIRBORNE,

    /** Travelling backwards. */
    REVERSING,

    /** Travelling forwards. */
    MOVING,

    /**
     * Swinging round faster than a nudge.
     *
     * <p>Measured against how fast the body is actually turning rather than
     * against what the driver asked for, so a vehicle at its {@code turn-speed}
     * clamp is turning and one being fought by a wall is not.
     */
    TURNING,

    /** Its base is in water. For a boat this is the ordinary case, not an event. */
    SUBMERGED,

    /** Stationary, or near enough that nobody can tell. */
    IDLE;

    /** The name an author writes, lowercased. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses what a pack wrote, in any case, or empty if it is not one of
     * these.
     *
     * <p>Empty rather than a default, for the same reason
     * {@link VehicleMedium#parse} is: the caller is a loader that has a file
     * and a line to name in the diagnostic, and this does not.
     */
    public static Optional<VehicleState> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * The animation to play for a vehicle in {@code active}, given what the
     * pack configured.
     *
     * <p>The highest-precedence active state that has an entry, or empty when
     * none of them do. <strong>An unconfigured state is a fall-through, not a
     * stop</strong> — the same rule an emote group follows, where a movement
     * state with no entry means "carry on with what you were doing" rather
     * than "play nothing". Configuring only {@code idle} therefore gives a
     * vehicle that idles when still and holds that pose when driven, which is
     * a reasonable half-finished vehicle rather than one that flickers.
     */
    public static Optional<String> choose(Collection<VehicleState> active,
                                          Map<VehicleState, String> animations) {
        if (active == null || animations == null || animations.isEmpty()) {
            return Optional.empty();
        }
        // values() is declaration order, which is the precedence. Iterating
        // the enum rather than the set means the answer cannot depend on what
        // kind of collection the caller happened to pass.
        for (VehicleState state : values()) {
            if (!active.contains(state)) {
                continue;
            }
            String animation = animations.get(state);
            if (animation != null && !animation.isEmpty()) {
                return Optional.of(animation);
            }
        }
        return Optional.empty();
    }
}
