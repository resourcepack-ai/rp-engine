package ai.resourcepack.engine.core.model;

import ai.resourcepack.engine.api.Feature;
import ai.resourcepack.engine.core.version.Compatibility;

/**
 * How far below a marker armour stand — or a display, which has the same
 * nothing for dimensions — its rider actually sits.
 *
 * <p><strong>This is the number the old-API audit cannot see.</strong> It is
 * arithmetic rather than an API, so it compiles on every supported version and
 * is simply wrong on the ones it is wrong on — and being wrong looks like
 * sitting two and a half blocks inside the floor. It lives in a class of its
 * own because there are now two places that seat somebody on a marker stand —
 * furniture and vehicles — and one number that is invisible when wrong is
 * exactly the kind of thing that must not exist twice.
 *
 * <p>Measured, not remembered: on 1.21.8 a marker stand at y=100 with a
 * humanoid passenger puts that passenger at y=99.3. Vanilla's rule changed in
 * 1.20.2 — a passenger's position is now the vehicle's attachment point minus
 * the passenger's own, and a marker's attachment point is zero — so below that
 * line the correction goes the other way and is a different size.
 *
 * @see Feature#MODERN_PASSENGER_OFFSET
 */
public final class MountOffset {

    /** 1.20.2 and up. The stand is spawned this far ABOVE the seat. */
    public static final double MODERN = 0.7;

    /**
     * Before 1.20.2, where a passenger sat at its vehicle's riding offset plus
     * its OWN riding offset, and a player's own is -0.35: a player rides 0.35
     * below a marker stand (measured on Paper 1.19.4 and 1.20.1, 2026-10-31).
     *
     * <p>The number is 0.45 rather than 0.35 so a rider lands where a MODERN
     * server puts them, which is what every seat was tuned against in game:
     * measured the same day on 1.20.4 and 1.21.11, a player rides 0.6 below a
     * marker stand, not the 0.7 {@link #MODERN} says, so a modern seat puts
     * its rider 0.1 above the arithmetic. 0.45 - 0.35 keeps that 0.1.
     *
     * <p>It was -1.75 until then, which nobody had measured, and put every
     * seated player on 1.19.4 to 1.20.1 two blocks into the floor, where they
     * suffocated.
     */
    public static final double LEGACY = 0.45;

    /**
     * A SMALL armour stand's passenger attachment point, in blocks.
     *
     * <p>Everything above is about a MARKER stand, whose attachment point is
     * zero — which is what makes the numbers above the whole story for a
     * chair, and for a vehicle seat on every server that can teleport a
     * ridden entity, because those seats are displays and a display's
     * dimensions are zero too. A server that cannot falls back to a small
     * stand with gravity (it needs physics to be moved by velocity), and a
     * small stand has a real attachment point that has to come back off the
     * offset.
     *
     * <p>Derived rather than measured: vanilla's default passenger attachment
     * is three quarters of an entity's height, and a small armour stand is
     * 0.9875 tall. It is consistent with the marker figure above — a marker's
     * dimensions are zero, so its attachment is zero, which is exactly what
     * that constant assumes.
     *
     * <p><strong>This is the dial if a rider sits too high or too low in a
     * vehicle on the fallback arm</strong>, and only there: a display-seated
     * rider and furniture both use the marker figures above.
     */
    public static final double SMALL_STAND_ATTACHMENT = 0.74;

    private MountOffset() {
    }

    /** Which of the two this server's vanilla actually uses. */
    public static double forServer(Compatibility compatibility) {
        return compatibility != null && compatibility.has(Feature.MODERN_PASSENGER_OFFSET)
                ? MODERN
                : LEGACY;
    }

    /**
     * The same, for a vehicle seat on the fallback arm — a small stand rather
     * than a marker or a display. See {@link #SMALL_STAND_ATTACHMENT}.
     *
     * <p>Both arms read the stand's own riding offset (three quarters of its
     * height, before 1.20.2 as after), so it comes off both.
     */
    public static double forVehicleSeat(Compatibility compatibility) {
        boolean modern = compatibility != null && compatibility.has(Feature.MODERN_PASSENGER_OFFSET);
        return (modern ? MODERN : LEGACY) - SMALL_STAND_ATTACHMENT;
    }
}
