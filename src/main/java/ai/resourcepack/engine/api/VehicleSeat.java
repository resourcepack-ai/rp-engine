package ai.resourcepack.engine.api;

import java.util.Locale;
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

    private VehicleSeat(Role role, Pose pose, double x, double y, double z, float yaw, String name) {
        this.role = role;
        this.pose = pose;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.name = name;
    }

    /** Engine internal; built by the vehicle loader. */
    public static VehicleSeat of(Role role, Pose pose, double x, double y, double z, float yaw, String name) {
        return new VehicleSeat(
                Objects.requireNonNull(role, "role"),
                pose == null ? Pose.SITTING : pose,
                x, y, z, yaw,
                name == null ? "" : name);
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

    @Override
    public String toString() {
        return role.key() + " at " + x + "," + y + "," + z;
    }
}
