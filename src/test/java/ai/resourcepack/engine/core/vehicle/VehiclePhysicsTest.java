package ai.resourcepack.engine.core.vehicle;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.VehicleInfo;
import ai.resourcepack.engine.api.VehicleMedium;
import ai.resourcepack.engine.api.VehicleSeat;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The arithmetic, without a server.
 *
 * <p>This is the whole reason {@link VehiclePhysics} is pure. A vehicle that
 * accelerates wrongly, coasts for ever or drives sideways is a defect in a few
 * lines here, and the alternative way of finding it is driving a car around a
 * test server and guessing.
 */
class VehiclePhysicsTest {

    private static final double DT = 1 / 20.0;

    private static VehicleInfo car(VehicleMedium medium) {
        return VehicleInfo.of(ContentId.parse("mypack:car").orElseThrow(), null, null, medium,
                VehiclePhysics.NOMINAL_WEIGHT, 20, 10, 180,
                List.of(VehicleSeat.of(VehicleSeat.Role.DRIVER, VehicleSeat.Pose.SITTING, 0, 0, 0, 0, null)));
    }

    private static VehiclePhysics.Demand ahead(double yaw) {
        return new VehiclePhysics.Demand(yaw, 0, 1, 0, false);
    }

    private static final VehiclePhysics.Surroundings GROUND =
            new VehiclePhysics.Surroundings(true, false, 0);

    // --- steering -----------------------------------------------------

    /**
     * The short way round. Without it, flicking the view from 350 to 10 sends
     * the vehicle 340 degrees the other way — a car spinning on the spot for
     * two seconds at a realistic turn speed.
     */
    @Test
    void turnsTheShortWayAcrossZero() {
        // 20 degrees of budget covers the 20 degrees from 350 to 10, so it
        // arrives rather than overshooting past it.
        assertEquals(10, VehiclePhysics.turnToward(350, 10, 20));
        // With only 5 it gets 5 of the way there, forwards through zero.
        assertEquals(355, VehiclePhysics.turnToward(350, 10, 5));
        assertEquals(345, VehiclePhysics.turnToward(350, 330, 5));
    }

    /**
     * At exactly 180 both directions are equally short, so either is
     * defensible — but the answer must not depend on which side of a boundary
     * nobody can see the request landed. A U-turn goes to the right.
     */
    @Test
    void aUTurnAlwaysGoesTheSameWay() {
        assertEquals(9, VehiclePhysics.turnToward(0, 180, 9));
        assertEquals(189, VehiclePhysics.turnToward(180, 0, 9));
    }

    @Test
    void neverTurnsPastWhatWasAskedFor() {
        assertEquals(90, VehiclePhysics.turnToward(80, 90, 45));
    }

    @Test
    void turningIsLimitedByTurnSpeed() {
        VehicleInfo info = car(VehicleMedium.LAND);
        VehiclePhysics.State state = VehiclePhysics.State.still(0);
        // 180 degrees a second, a twentieth of a second: nine degrees.
        VehiclePhysics.Step step = VehiclePhysics.step(info, state, ahead(180), GROUND, DT);
        assertEquals(9, step.state().yaw(), 1e-9);
    }

    // --- the throttle -------------------------------------------------

    @Test
    void acceleratesTowardTopSpeedAndStopsThere() {
        VehicleInfo info = car(VehicleMedium.LAND);
        VehiclePhysics.State state = VehiclePhysics.State.still(0);
        for (int tick = 0; tick < 200; tick++) {
            state = VehiclePhysics.step(info, state, ahead(0), GROUND, DT).state();
        }
        assertEquals(info.speed(), state.speed(), 1e-9);
    }

    /**
     * Weight is the only thing that makes a lorry feel different from a kart,
     * and it works by scaling acceleration around a nominal figure — so a
     * vehicle AT that figure gets exactly the acceleration its pack asked for.
     */
    @Test
    void weightScalesAccelerationAroundTheNominalWeight() {
        VehicleInfo light = VehicleInfo.of(ContentId.parse("mypack:a").orElseThrow(), null, null,
                VehicleMedium.LAND, VehiclePhysics.NOMINAL_WEIGHT, 20, 10, 180, car(VehicleMedium.LAND).seats());
        VehicleInfo heavy = VehicleInfo.of(ContentId.parse("mypack:b").orElseThrow(), null, null,
                VehicleMedium.LAND, VehiclePhysics.NOMINAL_WEIGHT * 2, 20, 10, 180, car(VehicleMedium.LAND).seats());

        double lightSpeed = VehiclePhysics.step(light, VehiclePhysics.State.still(0), ahead(0), GROUND, DT)
                .state().speed();
        double heavySpeed = VehiclePhysics.step(heavy, VehiclePhysics.State.still(0), ahead(0), GROUND, DT)
                .state().speed();

        assertEquals(10 * DT, lightSpeed, 1e-9);
        assertEquals(lightSpeed / 2, heavySpeed, 1e-9);
    }

    @Test
    void reverseIsSlowerThanForward() {
        VehicleInfo info = car(VehicleMedium.LAND);
        VehiclePhysics.State state = VehiclePhysics.State.still(0);
        VehiclePhysics.Demand astern = new VehiclePhysics.Demand(0, 0, -1, 0, false);
        for (int tick = 0; tick < 200; tick++) {
            state = VehiclePhysics.step(info, state, astern, GROUND, DT).state();
        }
        assertEquals(-info.speed() * VehiclePhysics.REVERSE_FRACTION, state.speed(), 1e-9);
    }

    /** Braking beats the throttle: somebody holding both is asking to stop. */
    @Test
    void brakingBeatsTheThrottle() {
        VehicleInfo info = car(VehicleMedium.LAND);
        VehiclePhysics.State moving = new VehiclePhysics.State(0, 20, 0);
        VehiclePhysics.Demand both = new VehiclePhysics.Demand(0, 0, 1, 0, true);
        double after = VehiclePhysics.step(info, moving, both, GROUND, DT).state().speed();
        assertTrue(after < 20, "braking while accelerating should still slow down");
    }

    @Test
    void coastsToAStopWithNoThrottle() {
        VehicleInfo info = car(VehicleMedium.LAND);
        VehiclePhysics.State state = new VehiclePhysics.State(0, 20, 0);
        for (int tick = 0; tick < 400; tick++) {
            state = VehiclePhysics.step(info, state, VehiclePhysics.Demand.idle(0), GROUND, DT).state();
        }
        assertEquals(0, state.speed(), 1e-9);
    }

    // --- which way is forward -----------------------------------------

    /**
     * Minecraft's yaw: 0 faces +z and increases clockwise, so forward is
     * (-sin, cos). Getting the pair wrong is a vehicle that drives sideways,
     * and it is the single easiest thing in the file to get wrong.
     */
    @Test
    void yawZeroDrivesTowardPositiveZ() {
        VehicleInfo info = car(VehicleMedium.LAND);
        VehiclePhysics.State moving = new VehiclePhysics.State(0, 20, 0);
        VehiclePhysics.Step step = VehiclePhysics.step(info, moving, ahead(0), GROUND, DT);
        assertEquals(0, step.dx(), 1e-9);
        assertTrue(step.dz() > 0, "yaw 0 should move south");
    }

    @Test
    void yawNinetyDrivesTowardNegativeX() {
        VehicleInfo info = car(VehicleMedium.LAND);
        VehiclePhysics.State moving = new VehiclePhysics.State(90, 20, 0);
        VehiclePhysics.Step step = VehiclePhysics.step(info, moving, ahead(90), GROUND, DT);
        assertTrue(step.dx() < 0, "yaw 90 should move west");
        assertEquals(0, step.dz(), 1e-9);
    }

    // --- where a seat goes ---------------------------------------------

    /**
     * Minecraft yaw 0 faces SOUTH, and somebody facing south has WEST on their
     * right. So right is {@code -x}, not {@code +x}.
     *
     * <p>This shipped inverted: the basis was written as forward's components
     * swapped, which looks right and puts every occupant on the wrong side of
     * the vehicle. It is exactly the class of thing the forward vector was
     * tested for from the start and this was not.
     */
    @Test
    void atYawZeroForwardIsSouthAndRightIsWest() {
        double[] forward = VehiclePhysics.seatOffset(0, 0, 1);
        assertEquals(0, forward[0], 1e-9);
        assertEquals(1, forward[1], 1e-9);

        double[] right = VehiclePhysics.seatOffset(0, 1, 0);
        assertEquals(-1, right[0], 1e-9);
        assertEquals(0, right[1], 1e-9);
    }

    @Test
    void atYawNinetyForwardIsWestAndRightIsNorth() {
        double[] forward = VehiclePhysics.seatOffset(90, 0, 1);
        assertEquals(-1, forward[0], 1e-9);
        assertEquals(0, forward[1], 1e-9);

        double[] right = VehiclePhysics.seatOffset(90, 1, 0);
        assertEquals(0, right[0], 1e-9);
        assertEquals(-1, right[1], 1e-9);
    }

    /**
     * A seat's forward direction and the direction the vehicle actually
     * travels have to be the same one — the two are computed by different
     * methods, and a driver placed at the back of their own car is what it
     * looks like when they drift apart.
     */
    @Test
    void aSeatsForwardIsTheDirectionTheVehicleTravels() {
        VehicleInfo info = car(VehicleMedium.LAND);
        for (double yaw : new double[] {0, 37, 90, 180, 270, 315}) {
            VehiclePhysics.Step step =
                    VehiclePhysics.step(info, new VehiclePhysics.State(yaw, 20, 0), ahead(yaw), GROUND, DT);
            double[] seat = VehiclePhysics.seatOffset(yaw, 0, 1);
            double travel = Math.hypot(step.dx(), step.dz());
            assertEquals(seat[0], step.dx() / travel, 1e-9, "x disagrees at yaw " + yaw);
            assertEquals(seat[1], step.dz() / travel, 1e-9, "z disagrees at yaw " + yaw);
        }
    }

    /** Right and forward are perpendicular, at every angle. */
    @Test
    void rightIsSquareToForward() {
        for (double yaw = 0; yaw < 360; yaw += 17) {
            double[] forward = VehiclePhysics.seatOffset(yaw, 0, 1);
            double[] right = VehiclePhysics.seatOffset(yaw, 1, 0);
            assertEquals(0, forward[0] * right[0] + forward[1] * right[1], 1e-9,
                    "not perpendicular at yaw " + yaw);
        }
    }

    // --- up and down ---------------------------------------------------

    @Test
    void aLandVehicleFallsWithNothingUnderIt() {
        VehicleInfo info = car(VehicleMedium.LAND);
        VehiclePhysics.Step step = VehiclePhysics.step(info, VehiclePhysics.State.still(0),
                VehiclePhysics.Demand.idle(0), VehiclePhysics.Surroundings.falling(), DT);
        assertTrue(step.dy() < 0, "nothing underneath should mean falling");
        assertEquals(-VehiclePhysics.GRAVITY * DT, step.state().verticalSpeed(), 1e-9);
    }

    @Test
    void aLandVehicleDoesNotFallThroughTheGround() {
        VehicleInfo info = car(VehicleMedium.LAND);
        VehiclePhysics.Step step = VehiclePhysics.step(info, new VehiclePhysics.State(0, 0, -10),
                VehiclePhysics.Demand.idle(0), GROUND, DT);
        assertEquals(0, step.state().verticalSpeed());
        assertEquals(0, step.dy());
    }

    @Test
    void aFallNeverExceedsTerminalVelocity() {
        VehicleInfo info = car(VehicleMedium.LAND);
        VehiclePhysics.State state = VehiclePhysics.State.still(0);
        for (int tick = 0; tick < 400; tick++) {
            state = VehiclePhysics.step(info, state, VehiclePhysics.Demand.idle(0),
                    VehiclePhysics.Surroundings.falling(), DT).state();
        }
        assertEquals(-VehiclePhysics.TERMINAL_FALL, state.verticalSpeed(), 1e-9);
    }

    /** A hull held under rises; one riding proud is pulled back down. */
    @Test
    void buoyancyPushesBothWays() {
        VehicleInfo boat = car(VehicleMedium.WATER);
        VehiclePhysics.Surroundings under = new VehiclePhysics.Surroundings(false, true, 1);
        VehiclePhysics.Surroundings proud = new VehiclePhysics.Surroundings(false, true, -0.5);

        double up = VehiclePhysics.step(boat, VehiclePhysics.State.still(0),
                VehiclePhysics.Demand.idle(0), under, DT).state().verticalSpeed();
        double down = VehiclePhysics.step(boat, VehiclePhysics.State.still(0),
                VehiclePhysics.Demand.idle(0), proud, DT).state().verticalSpeed();

        assertTrue(up > 0, "a submerged hull should rise");
        assertTrue(down < 0, "a hull above the surface should settle");
    }

    @Test
    void aBoatOnLandFallsLikeAnythingElse() {
        VehicleInfo boat = car(VehicleMedium.WATER);
        VehiclePhysics.Step step = VehiclePhysics.step(boat, VehiclePhysics.State.still(0),
                VehiclePhysics.Demand.idle(0), VehiclePhysics.Surroundings.falling(), DT);
        assertTrue(step.dy() < 0);
    }

    /**
     * Vanilla's own answer for a flying multi-seat vehicle, copied on purpose:
     * forward goes where the driver is LOOKING.
     */
    @Test
    void anAirVehicleClimbsWhenTheDriverLooksUp() {
        VehicleInfo plane = car(VehicleMedium.AIR);
        VehiclePhysics.State moving = new VehiclePhysics.State(0, 20, 0);
        // Pitch is negative when looking up, as Minecraft states it.
        VehiclePhysics.Demand climbing = new VehiclePhysics.Demand(0, -45, 1, 0, false);
        VehiclePhysics.Step step = VehiclePhysics.step(plane, moving, climbing, GROUND, DT);
        assertTrue(step.dy() > 0, "looking up should climb");
        assertTrue(step.dz() > 0, "and still make ground");
    }

    /**
     * The horizontal component shrinks as the nose comes up, or the vehicle
     * appears to speed up whenever the driver looks at the sky.
     */
    @Test
    void climbingCostsGroundSpeed() {
        VehicleInfo plane = car(VehicleMedium.AIR);
        VehiclePhysics.State moving = new VehiclePhysics.State(0, 20, 0);
        double level = VehiclePhysics.step(plane, moving,
                new VehiclePhysics.Demand(0, 0, 1, 0, false), GROUND, DT).dz();
        double climbing = VehiclePhysics.step(plane, moving,
                new VehiclePhysics.Demand(0, -45, 1, 0, false), GROUND, DT).dz();
        assertTrue(climbing < level, "a climbing aircraft covers less ground");
    }

    @Test
    void anAirVehicleIgnoresTheGround() {
        VehicleInfo plane = car(VehicleMedium.AIR);
        VehiclePhysics.Step step = VehiclePhysics.step(plane, VehiclePhysics.State.still(0),
                new VehiclePhysics.Demand(0, 0, 0, 1, false), VehiclePhysics.Surroundings.falling(), DT);
        assertTrue(step.dy() > 0, "jump should climb rather than fall");
    }

    // --- guarding the inputs -------------------------------------------

    /**
     * Both arms of the control layer build a Demand and one of them is reading
     * a packet, so this is clamped rather than trusted.
     */
    @Test
    void aWildThrottleIsClamped() {
        VehiclePhysics.Demand absurd = new VehiclePhysics.Demand(0, 0, 900, -900, false);
        assertEquals(1, absurd.throttle());
        assertEquals(-1, absurd.lift());
    }

    @Test
    void aThrottleThatIsNotANumberIsNoThrottle() {
        VehiclePhysics.Demand broken = new VehiclePhysics.Demand(0, 0, Double.NaN, Double.NaN, false);
        assertEquals(0, broken.throttle());
        assertEquals(0, broken.lift());
    }
}
