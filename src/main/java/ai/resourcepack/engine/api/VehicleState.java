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
 * <h2>{@link #IDLE} is the floor, and that is the one rule that is not
 * precedence</h2>
 *
 * <p>Every state above it names something the vehicle is <em>doing</em>, so
 * falling through from a blank one to the next is right: a cornering car with
 * no {@code turning} animation should carry on driving. {@code IDLE} says it is
 * doing nothing, and there is nothing quieter to fall through to — so a blank
 * {@code idle} is silence rather than a licence for a lower state to take over.
 *
 * <p>That is not theoretical. {@link #SUBMERGED} is permanently true for a
 * boat, so under a plain fall-through a rowing cycle mapped to it played for
 * ever, moored or not, and clearing {@code idle} did nothing at all — the
 * author had turned the animation off in the one place the engine would never
 * look. {@code SUBMERGED} therefore sits <em>below</em> {@code IDLE}, where the
 * class note already said being in water belonged, and {@code IDLE} stops the
 * walk.
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

    /**
     * Swinging round faster than a nudge.
     *
     * <p>Measured against how fast the body is actually turning rather than
     * against what the driver asked for, so a vehicle at its {@code turn-speed}
     * clamp is turning and one being fought by a wall is not.
     *
     * <p><strong>Above the two travel states, which is a reversal.</strong> It
     * used to sit below them on the grounds that a car reversing round a corner
     * is reversing — which is defensible and meant `turning` never played while
     * the vehicle was going anywhere, because a corner is nearly always taken
     * under way. A pack that authored a leaning cycle got it only when spinning
     * on the spot, which is not a thing anybody drives. Cornering is the more
     * specific thing a vehicle is doing, so it wins.
     *
     * <p>Nothing is lost by the change: an unconfigured state falls through, so
     * a pack that maps only `moving` still plays it through the corner — see
     * {@link #choose}. A SEAT has no fall-through, so it gets a narrow one for
     * exactly this state; see {@link #forSeat}.
     */
    TURNING,

    /** Travelling backwards. */
    REVERSING,

    /** Travelling forwards. */
    MOVING,

    /**
     * Stationary, or near enough that nobody can tell.
     *
     * <p><strong>The floor.</strong> Nothing below it may be reached while it
     * holds — see the class note. A vehicle that is doing nothing plays what
     * {@code idle} names, or nothing at all.
     */
    IDLE,

    /**
     * Its base is in water. For a boat this is the ordinary case, not an event.
     *
     * <p>Below {@link #IDLE} on purpose, which makes it the state a vehicle
     * reaches only while it is <em>doing</em> something the pack left blank —
     * a car crossing a ford with no {@code moving} animation, say. A moored
     * boat is idle, not submerged, however true the water is.
     */
    SUBMERGED;

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
     * The single state that best describes what {@code active} is, or empty if
     * it is nothing.
     *
     * <p><strong>The precedence without the fall-through</strong>, which is the
     * difference between this and {@link #choose} and is the whole reason both
     * exist. A vehicle's own animation map falls through a blank state, because
     * a cornering car with no leaning animation should carry on driving. A
     * SEAT's map must not: a state it left blank means the occupant's own body,
     * and "no pose" is an answer a fall-through cannot spell — see
     * {@link VehicleSeat#animations()}. Falling through there would leave a
     * driver hauling an imaginary wheel round while the car sat still.
     *
     * <p>So a caller that wants "what is this vehicle doing" asks here and
     * looks the answer up itself; a caller that wants "what should it play"
     * asks {@link #choose}.
     */
    public static Optional<VehicleState> current(Collection<VehicleState> active) {
        if (active == null) {
            return Optional.empty();
        }
        for (VehicleState state : values()) {
            if (active.contains(state)) {
                return Optional.of(state);
            }
        }
        return Optional.empty();
    }

    /**
     * The state a SEAT should dress its occupant for.
     *
     * <p>{@link #current} with one exception, and the exception is the whole
     * reason this exists: <strong>{@link #TURNING} is a refinement of
     * travelling, not a separate thing a vehicle does.</strong> It outranks
     * {@code moving} and {@code reversing} so that a pack which authored a
     * cornering pose gets it — but a seat has no general fall-through, so
     * without this a seat that maps {@code moving} and not {@code turning}
     * would drop its occupant back to their own body on every corner. Their
     * pose would snap off and on with the steering.
     *
     * <p>So an unmapped {@code turning} is passed over and the state underneath
     * answers. Only that state, and only when it is unmapped: every other blank
     * row still means the occupant's own body, which is the rule
     * {@link VehicleSeat#animations()} rests on and the reason this is not
     * simply {@link #choose}.
     */
    public static Optional<VehicleState> forSeat(Collection<VehicleState> active,
                                                 Map<VehicleState, String> dressed) {
        if (active == null) {
            return Optional.empty();
        }
        for (VehicleState state : values()) {
            if (!active.contains(state)) {
                continue;
            }
            if (state == TURNING && (dressed == null || !dressed.containsKey(TURNING))) {
                // A refinement nobody refined. Ask what it is a refinement OF.
                continue;
            }
            return Optional.of(state);
        }
        return Optional.empty();
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
     *
     * <p><strong>Except at {@link #IDLE}, where the walk stops.</strong> The
     * fall-through is "carry on with what you were doing", and a vehicle that
     * is idle is not doing anything — so a blank {@code idle} means silence.
     * Without that, the one state a boat can never leave sat under the one it
     * is in whenever it is moored, and a pack that mapped both got its rowing
     * cycle for ever with no mapping the editor could write to stop it.
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
            if (state == IDLE) {
                // The floor. Reaching it means nothing above it is happening,
                // and nothing below it is a better description of standing
                // still than standing still is.
                return Optional.empty();
            }
        }
        return Optional.empty();
    }
}
