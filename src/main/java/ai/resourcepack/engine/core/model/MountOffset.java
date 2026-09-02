package ai.resourcepack.engine.core.model;

import ai.resourcepack.engine.api.Feature;
import ai.resourcepack.engine.core.version.Compatibility;

/**
 * How far below a marker armour stand its rider actually sits.
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
     * Before 1.20.2, where a passenger sat ABOVE its vehicle by a fixed amount
     * rather than below it by the attachment point.
     *
     * <p>Negative because the correction goes the other way: on those versions
     * the stand is spawned below the seat rather than above it.
     */
    public static final double LEGACY = -1.75;

    private MountOffset() {
    }

    /** Which of the two this server's vanilla actually uses. */
    public static double forServer(Compatibility compatibility) {
        return compatibility != null && compatibility.has(Feature.MODERN_PASSENGER_OFFSET)
                ? MODERN
                : LEGACY;
    }
}
