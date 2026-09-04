package ai.resourcepack.engine.api;

import java.util.Locale;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One place somebody sits on a vehicle.
 *
 * <p><strong>A seat's meaning comes from its position in the list, not from
 * anything on it.</strong> The driver is first and the rest are passenger 1,
 * 2, 3… in exactly the order the pack wrote them, so somebody choosing the
 * third seat gets the third seat rather than whichever one happened to be
 * free. {@link VehicleInfo#seats()} is what holds that order and
 * {@code VehicleDefinitions} is what establishes it.
 */
public final class VehicleSeat {

    /** Whether this seat steers. */
    public enum Role {

        /** Steers. Exactly one per vehicle, and always the first seat. */
        DRIVER,

        /** Along for the ride. */
        PASSENGER;

        /** The name an author writes, lowercased. */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * How the occupant is drawn, and how they will actually appear.
     *
     * <p>The distinction is not cosmetic: it decides what the seat's position
     * <em>means</em>. {@link #SITTING} puts the occupant's backside on the
     * point (the reading {@code place: seat:} already uses for furniture);
     * {@link #STANDING} puts their feet on it. A single rule would have made
     * one of the two wrong by about a metre.
     */
    public enum Pose {

        /** Legs out in front. The position is where their backside goes. */
        SITTING,

        /** Upright on the deck. The position is where their feet go. */
        STANDING;

        /** The name an author writes, lowercased. */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final Role role;
    private final Pose pose;
    private final double x;
    private final double y;
    private final double z;
    private final float yaw;
    private final String name;
    private final boolean hidden;
    private final Map<VehicleState, String> animations;

    private VehicleSeat(Role role, Pose pose, double x, double y, double z, float yaw, String name,
                        boolean hidden, Map<VehicleState, String> animations) {
        this.role = role;
        this.pose = pose;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.name = name;
        this.hidden = hidden;
        this.animations = animations;
    }

    /** Engine internal; built by the vehicle loader. */
    public static VehicleSeat of(Role role, Pose pose, double x, double y, double z, float yaw, String name) {
        return of(role, pose, x, y, z, yaw, name, null);
    }

    /**
     * The same, with what its occupant's body does in each vehicle state.
     *
     * <p>A second factory rather than a longer one everywhere, because most
     * seats have no occupant animation at all and a null argument at every
     * call site is how a field nobody uses becomes a field nobody notices.
     */
    public static VehicleSeat of(Role role, Pose pose, double x, double y, double z, float yaw,
                                 String name, Map<VehicleState, String> animations) {
        return of(role, pose, x, y, z, yaw, name, false, animations);
    }

    /** The same again, for a seat whose occupant is not drawn at all. */
    public static VehicleSeat of(Role role, Pose pose, double x, double y, double z, float yaw,
                                 String name, boolean hidden, Map<VehicleState, String> animations) {
        return new VehicleSeat(
                Objects.requireNonNull(role, "role"),
                pose == null ? Pose.SITTING : pose,
                x, y, z, yaw,
                name == null ? "" : name,
                hidden,
                copyAnimations(animations));
    }

    /** An EnumMap, so iterating it is in VehicleState's declaration order. */
    private static Map<VehicleState, String> copyAnimations(Map<VehicleState, String> animations) {
        if (animations == null || animations.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<VehicleState, String> copy = new EnumMap<>(VehicleState.class);
        for (Map.Entry<VehicleState, String> entry : animations.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && !entry.getValue().isEmpty()) {
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Which EMOTE this seat's occupant wears in which state.
     *
     * <p>An emote id, not an animation name — and that is the whole design.
     * The engine already has a rig that animates a player's body, an editor
     * that authors one by hand, and a manifest that ships them; what a driver's
     * arms are doing while they steer is exactly that thing, so it is that
     * thing rather than a second player-animation system beside it.
     *
     * <p>Worn through {@link Emotes#wear}, which is the movement-set machinery
     * with the movement taken out: the rig follows its wearer, there is no
     * anchor to drift off, and what it wears is the vehicle's decision.
     *
     * <p>Empty for a seat whose occupant is just themselves, which is every
     * seat written before this existed. A state with no entry is the player's
     * own body rather than a fall-through — unlike the VEHICLE's animation map,
     * and deliberately: falling through would leave a driver stuck in a
     * steering pose while the car sits still, and "no pose" is a perfectly good
     * answer that a fall-through cannot spell.
     */
    public Map<VehicleState, String> animations() {
        return animations;
    }

    /** Whether this seat steers. */
    public Role role() {
        return role;
    }

    /** Whether this seat steers, said the short way. */
    public boolean isDriver() {
        return role == Role.DRIVER;
    }

    /** How the occupant sits, which is also what the position means. */
    public Pose pose() {
        return pose;
    }

    /**
     * To the vehicle's RIGHT, in blocks. Negative is left.
     *
     * <p>Side and forward rather than world x and z, exactly as
     * {@code place: seat:} states them, so the numbers turn with the vehicle
     * and a bench seats people along itself however it is parked.
     */
    public double x() {
        return x;
    }

    /** Up from the vehicle's base, in blocks. */
    public double y() {
        return y;
    }

    /** In FRONT of the vehicle, in blocks. Negative is behind. */
    public double z() {
        return z;
    }

    /**
     * Which way the occupant faces, in degrees clockwise from the vehicle's
     * own heading.
     *
     * <p>Relative rather than absolute: a rear-facing bench should still face
     * backwards after the vehicle turns, which an absolute yaw cannot express.
     */
    public float yaw() {
        return yaw;
    }

    /** What to call it in a message, or empty for its role and number. */
    public Optional<String> name() {
        return name.isEmpty() ? Optional.empty() : Optional.of(name);
    }

    /**
     * Whether this seat's occupant is drawn at all.
     *
     * <p>Off by default, and it is not the same thing as a seat that dresses
     * nobody: an undressed occupant is an ordinary player sitting there, and a
     * hidden one is nobody. The case for it is a vehicle whose art already has
     * the rider in it — an enclosed cockpit, a tank, a mech — where any body,
     * rigged or not, is a second person inside the fuselage.
     *
     * <p><strong>It beats {@link #animations()} and the seat stance
     * both.</strong> A hidden seat wears nothing: there is no rig to put on
     * somebody nobody can see, and spawning one would be a set of displays
     * hanging inside the bodywork for every occupant.
     */
    public boolean hidden() {
        return hidden;
    }

    @Override
    public String toString() {
        return role.key() + " at " + x + "," + y + "," + z;
    }
}
