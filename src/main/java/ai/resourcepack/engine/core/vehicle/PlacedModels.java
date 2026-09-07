package ai.resourcepack.engine.core.vehicle;

import ai.resourcepack.engine.api.ModelShape;

/**
 * What a vehicle needs to know about a placed model, without knowing where the
 * answer comes from.
 *
 * <p>Three sources hold pieces of it — the content folder's definitions, the
 * pushed pack's manifest, and the pushed pack's own geometry — and none of them
 * belongs in this package. A vehicle wants an answer about a string; copying
 * those catalogues in here would be a fourth answer to keep in step with the
 * three that already exist.
 *
 * <p>Its own file, rather than nested in {@link ModelObstacles}, only so the
 * plugin can implement it: that class is package-private and should stay that
 * way — the collision arithmetic is nobody else's business.
 *
 * <p><strong>Every method answers for an id from EITHER source and must be
 * safe for one it has never heard of.</strong> A content folder writes
 * {@code mypack:chair} and a Studio push writes a bare slug, so each
 * implementation sees the other's ids constantly. "Not mine" is collidable, no
 * shape, size 1 — which is what lets the two compose without either knowing the
 * other exists.
 */
public interface PlacedModels {

    /** Whether a vehicle is stopped by a placement of this id. */
    boolean stops(String id);

    /** Its boxes, or {@link ModelShape#NONE} if they cannot be had. */
    ModelShape shapeOf(String id);

    /**
     * The size the definition itself asks for, which is NOT the size the
     * placement asks for.
     *
     * <p>An authored piece may say {@code place: scale: 2}; a Studio placement
     * carries its own multiplier on the hitbox instead. The two multiply, and
     * each source only ever supplies one of them, so nothing is counted twice.
     */
    double scaleOf(String id);
}
