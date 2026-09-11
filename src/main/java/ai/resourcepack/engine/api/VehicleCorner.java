package ai.resourcepack.engine.api;

/**
 * One of the four corners a vehicle stands on.
 *
 * <p>The engine already thinks in these: the ground under a vehicle is sampled
 * at four points and that is where its pitch, its roll and its ride height come
 * from. This is the name for one of them, so a plugin describing something that
 * has gone wrong at a corner — see {@link VehicleDamage} — and the engine
 * deciding what that does to the handling are talking about the same place.
 *
 * <p><strong>Front and left are the vehicle's own, not the driver's view of
 * it.</strong> In the body frame the engine uses everywhere else, {@code +x} is
 * the vehicle's right, {@code +y} is up and {@code +z} is the way it drives —
 * so {@link #FRONT_LEFT} is at negative x and positive z. A vehicle with no
 * wheels at all still has four corners; they are corners of the hitbox, and a
 * boat or an aircraft has them too.
 */
public enum VehicleCorner {

    FRONT_LEFT,
    FRONT_RIGHT,
    REAR_LEFT,
    REAR_RIGHT;

    /** Whether this corner is at the front. */
    public boolean front() {
        return this == FRONT_LEFT || this == FRONT_RIGHT;
    }

    /** Whether this corner is on the vehicle's left — negative x in the body frame. */
    public boolean left() {
        return this == FRONT_LEFT || this == REAR_LEFT;
    }

    /** The corner diagonally opposite this one. */
    public VehicleCorner opposite() {
        return switch (this) {
            case FRONT_LEFT -> REAR_RIGHT;
            case FRONT_RIGHT -> REAR_LEFT;
            case REAR_LEFT -> FRONT_RIGHT;
            case REAR_RIGHT -> FRONT_LEFT;
        };
    }

    /**
     * Which corner a point on the body is nearest, given where it sits in the
     * vehicle's own frame.
     *
     * <p>The rule a plugin would otherwise write for itself, stated once here
     * so that it agrees with the engine's. It is the obvious one — the signs of
     * {@code x} and {@code z} — and the reason it is worth a method is that the
     * mirroring is easy to get backwards and nothing reports it when you do:
     * the vehicle simply drops the wrong corner.
     *
     * <p>The units do not matter, only the signs, so this takes a part's offset
     * ({@link Vehicle#partOffset}) or a contact point
     * ({@code VehicleImpactEvent.Outcome.contactOffset()}) equally well. A
     * point exactly on an axis resolves toward the front and toward the right,
     * which is arbitrary and only has to be consistent.
     */
    public static VehicleCorner of(double x, double z) {
        if (!Double.isFinite(x)) {
            x = 0;
        }
        if (!Double.isFinite(z)) {
            z = 0;
        }
        if (z >= 0) {
            return x < 0 ? FRONT_LEFT : FRONT_RIGHT;
        }
        return x < 0 ? REAR_LEFT : REAR_RIGHT;
    }
}
