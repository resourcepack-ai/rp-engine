package ai.resourcepack.engine.api;

import java.util.Locale;
import java.util.Optional;

/**
 * What a vehicle moves through, and the only thing that decides its vertical
 * rule.
 *
 * <p><strong>One field rather than three switches</strong> (gravity? buoyancy?
 * lift?). Those are not independent — a thing that floats does not also fall,
 * and a thing that flies does neither — so three booleans would mostly spell
 * states that mean nothing, and the runtime would have to pick a winner
 * anyway. Picking here, once, in a word a server owner already understands, is
 * the honest version of that decision.
 *
 * <p>Studio's editor offers exactly these three under the same names. There is
 * no shared type between a Worker and a jar, so that agreement is by hand:
 * change a name here and change {@code VEHICLE_MEDIA} in studio's
 * {@code lib/model-subtypes.ts} in the same breath.
 */
public enum VehicleMedium {

    /**
     * Drives on blocks. Falls when there is nothing under it, and steps up one
     * block the way a player does.
     */
    LAND,

    /**
     * Floats at the water surface, and is dead weight on land.
     *
     * <p>Buoyancy rather than "ignores gravity in water": a boat pushed under
     * rises, which is what makes going over a waterfall look right instead of
     * leaving the hull hanging at the height it entered.
     */
    WATER,

    /**
     * Holds whatever height it is at, and climbs or dives with the driver's
     * look.
     *
     * <p>Deliberately hover rather than aerodynamics — there is no stall, no
     * airspeed and no lift curve. A helicopter and an aeroplane both behave
     * like this, and inventing a flight model the engine cannot show the
     * driver any instruments for would be worse than not having one.
     */
    AIR;

    /** The name an author writes, lowercased. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses what a pack wrote, in any case, or empty if it is not one of
     * these.
     *
     * <p>Empty rather than a default, because the caller is a loader that has
     * a file and a line to name in the diagnostic and this does not.
     */
    public static Optional<VehicleMedium> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
