package ai.resourcepack.engine.core.emote;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.joml.Matrix4f;

/**
 * Vanilla's cape physics, on a rig that has replaced the player.
 *
 * <p><b>Until this the cape was a slab.</b> It is baked as an ordinary bone
 * parented to the body, no emote keys it, so it sat rigid against the back
 * through a sprint, a jump and a fall — which reads as a texture painted on
 * rather than as a cape. The skeleton builder says so at the point it places
 * the geometry: "real cape motion belongs in an animator".
 *
 * <h2>An overlay, like a swing, and for the same reasons</h2>
 *
 * Everything {@link ArmSwing}'s class note gives for not modelling a swing as a
 * state applies here twice over. A cape's angle is not a state — it is a
 * continuous function of how the body has been moving over the last few ticks —
 * it coexists with whatever the emote is doing, and no pack author should have
 * to key it for a cape to behave like a cape. So this is composed on top of the
 * cape bone's own keyframes rather than instead of them: an emote that DOES key
 * the cape gets its authored motion with the physics added, exactly the way a
 * swing adds to a walk cycle.
 *
 * <p>The one difference from a swing is that this carries state. A swing is a
 * pure function of a start tick; a cape is a lagged position chasing the
 * player, so there is a little integrator per session and {@link #step} has to
 * be called once per tick and no more.
 *
 * <h2>The curve is Mojang's</h2>
 *
 * Read off {@code Player.updateCape} (the lag) and {@code CapeLayer.render}
 * (the angles). The whole of it:
 *
 * <ul>
 *   <li>A "cloak" point chases the player at a quarter of the remaining
 *       distance each tick, so the cape trails what the body did rather than
 *       what it is doing.</li>
 *   <li>That lag, resolved into the body's own axes, becomes three angles:
 *       how far the hem lifts away from the back (moving forward), how far it
 *       lifts or drops (falling, jumping), and how far it swings sideways
 *       (strafing, turning).</li>
 *   <li>Plus a bob off the walk cycle, and a fixed lift while crouching, which
 *       is what stops the cape passing through a sneaking body.</li>
 * </ul>
 *
 * <p><b>The signs are the part that is derived rather than copied.</b> Mojang's
 * numbers are stated in a model space whose y points down and whose cloak is
 * built in FRONT of the body and then flipped — the skeleton builder records
 * that flip as the reason the cape's front and back rects are swapped onto
 * south and north. This rig's space is the other one: +y up, the character
 * facing -z, their right hand at +x (the same convention
 * {@link ArmSwing#pitch} states). So each angle is taken as a
 * quantity with a meaning — "away from the back", "toward the rig's right" —
 * and turned into a rotation in this space, rather than lifted as a signed
 * degree count. See {@link #applyTo}.
 */
final class CapeSway {

    /** How much of the gap the cloak closes each tick — Mojang's 0.25. */
    private static final double CHASE = 0.25;

    /**
     * A jump this big is a teleport, and the cloak is put back on the body.
     *
     * <p>Mojang's own guard, and the reason it exists is worth keeping: without
     * it a player who teleports leaves the cloak point behind at the old spot,
     * the lag vector is enormous, and the cape stands straight out behind them
     * for the second or so it takes to catch up. Ours would also do it on every
     * ender pearl and every {@code /tp}.
     */
    private static final double SNAP_DISTANCE = 10.0;

    /** Degrees of lag per block, for all three axes. Mojang's {@code * 100}. */
    private static final double LAG_TO_DEGREES = 100.0;

    /** The vertical term uses a tenth of that — Mojang's {@code * 10}. */
    private static final double RISE_TO_DEGREES = 10.0;

    /** How far the hem may lift away from the back, degrees. */
    private static final double MAX_LEAN = 150.0;

    /** How far the vertical term may lift the hem, and drop it. */
    private static final double MAX_RISE = 32.0;
    private static final double MIN_RISE = -6.0;

    /** How far the hem may swing to either side, degrees. */
    private static final double MAX_SWAY = 20.0;

    /**
     * The cape's rest angle off the back, degrees. Mojang's constant 6.
     *
     * <p>Not zero, and that is not a detail: a cape flat against the body
     * z-fights with the torso it is one pixel behind, which is the shimmer that
     * makes a static cape look broken rather than merely still.
     */
    private static final double REST_LEAN = 6.0;

    /** How much the walk cycle bobs the hem, degrees at full stride. */
    private static final double BOB_DEGREES = 32.0;

    /** The extra lift while crouching, degrees. Mojang's 25. */
    private static final double CROUCH_LEAN = 25.0;

    /** Ceiling on the walk term's amplitude — Mojang clamps speed at 0.1. */
    private static final double MAX_BOB_SPEED = 0.1;

    /** How fast the bob amplitude follows the speed. Mojang's 0.4. */
    private static final double BOB_CHASE = 0.4;

    /** Blocks walked, scaled the way Mojang scales it, for the bob's phase. */
    private static final double STRIDE_SCALE = 0.6;

    /** Radians of bob phase per unit of {@link #walked}. Mojang's 6. */
    private static final double STRIDE_TO_PHASE = 6.0;

    /** Whether {@link #step} has placed the cloak point yet. */
    private boolean placed;

    /** The lagged cloak point. Meaningless until {@link #placed}. */
    private double cloakX;
    private double cloakY;
    private double cloakZ;

    /** Where the body was on the previous step, for the walk speed. */
    private Location previous;

    /** Distance walked, for the bob's phase. See {@link #STRIDE_SCALE}. */
    private double walked;

    /** The bob's amplitude, chasing the walk speed. See {@link #BOB_CHASE}. */
    private double bob;

    /**
     * The three angles the last step resolved, in degrees.
     *
     * <p>All three start at zero and a still cape is all three at zero:
     * {@link #REST_LEAN} is added once, in {@link #applyTo}, and seeding one
     * of these with it as well is how a motionless cape ends up nine degrees
     * off the back instead of six.
     */
    private double lean;
    private double rise;
    private double sway;

    /**
     * Advances the cloak one tick behind the player. Call once per tick.
     *
     * <p>Everything read here is read from the PLAYER rather than from the rig,
     * because the rig is where the player was a moment ago plus a lead — see
     * {@code EmoteDirector.advanceLead} — and a cape driven by that would be
     * reacting to a smoothed copy of the motion instead of to the motion.
     */
    // isOnGround is deprecated because the CLIENT owns it, and here that is the
    // point rather than the caveat: vanilla's own cape bob is cut by the same
    // flag on the same client, so reading anything else would be a different
    // animation that happened to be better informed. A spoofed flag costs a bob.
    @SuppressWarnings("deprecation")
    void step(Player player) {
        if (player == null) return;
        Location now = player.getLocation();

        if (!placed || previous == null || !now.getWorld().equals(previous.getWorld())) {
            reset(now);
            return;
        }

        double dx = now.getX() - cloakX;
        double dy = now.getY() - cloakY;
        double dz = now.getZ() - cloakZ;
        if (Math.abs(dx) > SNAP_DISTANCE || Math.abs(dy) > SNAP_DISTANCE || Math.abs(dz) > SNAP_DISTANCE) {
            reset(now);
            return;
        }

        cloakX += dx * CHASE;
        cloakY += dy * CHASE;
        cloakZ += dz * CHASE;

        // The lag: where the cloak still is, measured from where the body now
        // is. It points BACKWARD along the motion, which is what makes every
        // angle below come out positive when moving forward.
        double lagX = cloakX - now.getX();
        double lagY = cloakY - now.getY();
        double lagZ = cloakZ - now.getZ();

        // The walk cycle, for the bob. Both halves are Mojang's: the phase
        // accumulates scaled distance, and the amplitude chases the speed and
        // is cut to nothing off the ground, so a fall bobs from the vertical
        // term alone rather than flapping as if the legs were still running.
        double stepX = now.getX() - previous.getX();
        double stepZ = now.getZ() - previous.getZ();
        double speed = Math.sqrt(stepX * stepX + stepZ * stepZ);
        walked += speed * STRIDE_SCALE;
        double wanted = player.isOnGround() ? Math.min(speed, MAX_BOB_SPEED) : 0;
        bob += (wanted - bob) * BOB_CHASE;

        double[] resolved = angles(lagX, lagY, lagZ, now.getYaw(),
            Math.sin(walked * STRIDE_TO_PHASE) * BOB_DEGREES * bob, player.isSneaking());
        lean = resolved[0];
        rise = resolved[1];
        sway = resolved[2];

        previous = now.clone();
    }

    /**
     * The lag vector as three angles, in degrees: lean, rise, sway.
     *
     * <p>Split out of {@link #step} and free of the Bukkit interfaces so it
     * can be tested, exactly like {@code EmoteDirector.leadFor} and
     * {@code movedHorizontally} — and it is the half worth testing, because
     * it is where a sign or an axis being wrong produces a cape that behaves
     * plausibly and backwards.
     *
     * @param bobOffset the walk cycle's contribution to the rise, already
     *     scaled — passed in rather than computed here because it is the one
     *     term that depends on state this method deliberately does not hold.
     */
    static double[] angles(
            double lagX, double lagY, double lagZ, float yawDegrees,
            double bobOffset, boolean sneaking) {
        // Resolved into the body's own axes. Minecraft's yaw has the player
        // facing +z at zero, so forward is (-sin, cos) and their right hand is
        // at (-cos, -sin) — the same pair vanilla's CapeLayer builds, written
        // out rather than as its two unnamed doubles.
        double yaw = Math.toRadians(yawDegrees);
        double behind = -(lagX * -Math.sin(yaw) + lagZ * Math.cos(yaw));
        double rightward = -(lagX * Math.cos(yaw) + lagZ * Math.sin(yaw));

        double lean = clamp(behind * LAG_TO_DEGREES, 0, MAX_LEAN);
        double rise = clamp(lagY * RISE_TO_DEGREES, MIN_RISE, MAX_RISE) + bobOffset;
        double sway = clamp(rightward * LAG_TO_DEGREES, -MAX_SWAY, MAX_SWAY);
        // On the vertical term rather than the lean, and unhalved, exactly
        // where vanilla puts it — which is what makes a crouch lift the hem
        // clear of the body instead of merely leaning it a little further out.
        if (sneaking) rise += CROUCH_LEAN;
        return new double[] {lean, rise, sway};
    }

    /** Puts the cloak on the body and stops it dead. See {@link #SNAP_DISTANCE}. */
    private void reset(Location now) {
        placed = true;
        cloakX = now.getX();
        cloakY = now.getY();
        cloakZ = now.getZ();
        previous = now.clone();
        bob = 0;
        lean = 0;
        rise = 0;
        sway = 0;
    }

    private static double clamp(double value, double low, double high) {
        if (Double.isNaN(value)) return low;
        return Math.max(low, Math.min(high, value));
    }

    /**
     * Composes the sway into the cape bone's matrix, about its joint.
     *
     * <p>Same translate-rotate-translate-back shape as {@link ArmSwing#applyTo}
     * and {@code RigMath.applyStep}, and appended after the emote's own step so
     * an authored cape keeps its keys and gets the physics on top.
     *
     * <p><b>Every sign here is reasoned in THIS rig's space</b>, not carried
     * over from vanilla's — see the class note for why they cannot be:
     *
     * <ul>
     *   <li>The cape hangs behind the body, at +z, from a joint at its top. A
     *       right-handed turn about +x takes a point below the joint toward
     *       -z, which is in FRONT — so lifting the hem away from the back is a
     *       NEGATIVE x rotation. This is the same derivation
     *       {@link ArmSwing#pitch} makes for an arm and lands on the opposite
     *       sign, because an arm swings forward and a cape trails backward.</li>
     *   <li>A turn about +z takes a point below the joint toward +x, which is
     *       the rig's right — so a lag to the right is a POSITIVE z
     *       rotation.</li>
     *   <li>The y term is a counter-twist, half the sway and against it, so the
     *       face of the cape keeps pointing outward as it swings rather than
     *       presenting its edge. Vanilla's is folded into the 180 that flips
     *       its cloak the right way round; ours is modelled in place, so only
     *       the counter-twist is left.</li>
     * </ul>
     *
     * <p>{@code pivot} is in block-model px, exactly as it arrives from the
     * manifest.
     */
    void applyTo(Matrix4f m, float[] pivot) {
        applyTo(m, pivot, lean, rise, sway);
    }

    /** The same, from three angles rather than from this cape's own. Testable. */
    static void applyTo(Matrix4f m, float[] pivot, double lean, double rise, double sway) {
        if (pivot == null || pivot.length != 3) return;
        // Mojang's `6 + f2/2 + f1`: the lean is halved and the vertical term is
        // not. Reversing the two is an easy and very visible mistake — it makes
        // a sprint barely move the cape and a fall throw it over the head.
        float outward = (float) Math.toRadians(REST_LEAN + lean / 2.0 + rise);
        float sideways = (float) Math.toRadians(sway / 2.0);
        // No early-out for "nothing to do", unlike a swing: the rest angle is
        // six degrees rather than zero, so there is never a pass where the
        // cape wants the identity. See REST_LEAN.
        float px = (pivot[0] - 8f) / 16f;
        float py = (pivot[1] - 8f) / 16f;
        float pz = (pivot[2] - 8f) / 16f;
        m.translate(px, py, pz);
        m.rotateX(-outward);
        m.rotateZ(sideways);
        m.rotateY(-sideways);
        m.translate(-px, -py, -pz);
    }
}
