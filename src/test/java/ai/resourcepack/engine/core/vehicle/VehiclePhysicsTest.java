package ai.resourcepack.engine.core.vehicle;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.VehicleFlight;
import ai.resourcepack.engine.api.VehicleHitbox;
import ai.resourcepack.engine.api.VehicleInfo;
import ai.resourcepack.engine.api.VehicleMedium;
import ai.resourcepack.engine.api.VehicleSeat;
import ai.resourcepack.engine.api.VehicleState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

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
                VehiclePhysics.NOMINAL_WEIGHT, 20, 10, 180, VehicleHitbox.DEFAULT,
                List.of(VehicleSeat.of(VehicleSeat.Role.DRIVER, VehicleSeat.Pose.SITTING, 0, 0, 0, 0, null)),
                Map.of(), List.of());
    }

    private static VehiclePhysics.Demand ahead(double yaw) {
        return new VehiclePhysics.Demand(yaw, 0, 1, 0, false);
    }

    private static final VehiclePhysics.Surroundings GROUND =
            new VehiclePhysics.Surroundings(true, false, 0);

    // --- jumping ------------------------------------------------------

    /**
     * Space lifts a jumping land vehicle off the ground, once, and does
     * nothing to one that does not jump — that one's space is the handbrake,
     * which the control layer turns into {@code braking} rather than
     * {@code lift} before it gets here.
     */
    @Test
    void aJumpingVehicleLeavesTheGroundOnSpaceAndOnlyFromTheGround() {
        VehicleInfo bike = car(VehicleMedium.LAND).withJump(true);
        VehiclePhysics.Demand hop = new VehiclePhysics.Demand(0, 0, 0, 1, false);

        VehiclePhysics.Step off = VehiclePhysics.step(bike, VehiclePhysics.State.still(0), hop, GROUND, DT);
        assertEquals(VehiclePhysics.JUMP_SPEED, off.state().verticalSpeed(), 1e-9);
        assertTrue(off.dy() > 0);

        // In the air the held key is nothing: gravity has it now.
        VehiclePhysics.Surroundings air = new VehiclePhysics.Surroundings(false, false, 0);
        VehiclePhysics.Step up = VehiclePhysics.step(bike, off.state(), hop, air, DT);
        assertTrue(up.state().verticalSpeed() < VehiclePhysics.JUMP_SPEED);
        assertTrue(up.states().contains(VehicleState.AIRBORNE));

        VehiclePhysics.Step stays = VehiclePhysics.step(car(VehicleMedium.LAND), VehiclePhysics.State.still(0), hop, GROUND, DT);
        assertEquals(0, stays.state().verticalSpeed(), 1e-9);
        assertEquals(0, stays.dy(), 1e-9);
    }

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

    /**
     * The key arm turns the body directly rather than pointing it at the
     * driver's look, which is the whole reason it exists: steering by look
     * means steering is turning your head, and a player's body follows their
     * head.
     */
    @Test
    void keysTurnTheBodyAndIgnoreTheLook() {
        VehicleInfo info = car(VehicleMedium.LAND);
        VehiclePhysics.State state = VehiclePhysics.State.still(0);
        // Looking hard left, steering hard right: the keys win and the look is
        // not consulted at all.
        VehiclePhysics.Demand demand = VehiclePhysics.Demand.steering(270, 0, 1, 1, 0, false);
        assertEquals(9, VehiclePhysics.step(info, state, demand, GROUND, DT).state().yaw(), 1e-9);
    }

    @Test
    void steeringLeftTurnsAnticlockwise() {
        VehicleInfo info = car(VehicleMedium.LAND);
        VehiclePhysics.State state = VehiclePhysics.State.still(0);
        VehiclePhysics.Demand demand = VehiclePhysics.Demand.steering(0, 0, -1, 1, 0, false);
        // 360 - 9: Minecraft yaw runs clockwise, so left is down through zero.
        assertEquals(351, VehiclePhysics.step(info, state, demand, GROUND, DT).state().yaw(), 1e-9);
    }

    /** No steer key held is a vehicle that keeps its heading, not one that centres. */
    @Test
    void noSteerKeyHoldsTheHeading() {
        VehicleInfo info = car(VehicleMedium.LAND);
        VehiclePhysics.State state = VehiclePhysics.State.still(123);
        VehiclePhysics.Demand demand = VehiclePhysics.Demand.steering(0, 0, 0, 1, 0, false);
        assertEquals(123, VehiclePhysics.step(info, state, demand, GROUND, DT).state().yaw(), 1e-9);
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
                VehicleMedium.LAND, VehiclePhysics.NOMINAL_WEIGHT, 20, 10, 180,
                VehicleHitbox.DEFAULT, car(VehicleMedium.LAND).seats(), Map.of(), List.of());
        VehicleInfo heavy = VehicleInfo.of(ContentId.parse("mypack:b").orElseThrow(), null, null,
                VehicleMedium.LAND, VehiclePhysics.NOMINAL_WEIGHT * 2, 20, 10, 180,
                VehicleHitbox.DEFAULT, car(VehicleMedium.LAND).seats(), Map.of(), List.of());

        double lightSpeed = VehiclePhysics.step(light, VehiclePhysics.State.still(0), ahead(0), GROUND, DT)
                .state().speed();
        double heavySpeed = VehiclePhysics.step(heavy, VehiclePhysics.State.still(0), ahead(0), GROUND, DT)
                .state().speed();

        assertEquals(10 * DT, lightSpeed, 1e-9);
        assertEquals(lightSpeed / 2, heavySpeed, 1e-9);
    }

    /**
     * The back key at speed is a brake, not a gear change. Aiming straight at
     * the reverse target made a vehicle doing 20 crawl down through zero at
     * ordinary acceleration and keep going, which reads as a car that will not
     * stop rather than one changing direction.
     */
    @Test
    void theBackKeyBrakesBeforeItReverses() {
        VehicleInfo info = car(VehicleMedium.LAND);
        VehiclePhysics.Demand astern = new VehiclePhysics.Demand(0, 0, -1, 0, false);

        VehiclePhysics.State fast = new VehiclePhysics.State(0, 20, 0);
        double braked = 20 - VehiclePhysics.step(info, fast, astern, GROUND, DT).state().speed();
        // Braking rate, not the ordinary one — that is what makes it a brake.
        assertEquals(10 * VehiclePhysics.BRAKE_MULTIPLIER * DT, braked, 1e-9);

        // Once it is genuinely stopped the same key reverses.
        VehiclePhysics.State stopped = VehiclePhysics.State.still(0);
        assertTrue(VehiclePhysics.step(info, stopped, astern, GROUND, DT).state().speed() < 0);
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

    /**
     * There is nothing to reverse against in the air, so the back key means
     * "down" — and an aeroplane that flew backwards on S would be the only
     * vehicle here doing something no real one does.
     */
    @Test
    void anAirborneAircraftDescendsOnTheBackKeyRatherThanReversing() {
        VehicleInfo plane = car(VehicleMedium.AIR);
        VehiclePhysics.State cruising = new VehiclePhysics.State(0, 20, 0);
        VehiclePhysics.Demand back = VehiclePhysics.Demand.steering(0, 0, 0, -1, 0, false);
        VehiclePhysics.Step step =
                VehiclePhysics.step(plane, cruising, back, VehiclePhysics.Surroundings.falling(), DT);

        assertTrue(step.dy() < 0, "the back key should descend");
        assertTrue(step.state().speed() > 0, "and must not put it into reverse");
    }

    /** On the ground it taxis backwards like anything else. */
    @Test
    void aGroundedAircraftStillReverses() {
        VehicleInfo plane = car(VehicleMedium.AIR);
        VehiclePhysics.Demand back = VehiclePhysics.Demand.steering(0, 0, 0, -1, 0, false);
        VehiclePhysics.State state = VehiclePhysics.State.still(0);
        for (int tick = 0; tick < 40; tick++) {
            state = VehiclePhysics.step(plane, state, back, GROUND, DT).state();
        }
        assertTrue(state.speed() < 0, "on the ground the back key reverses");
    }

    /** Asking to climb and descend at once is asking to climb. */
    @Test
    void spaceBeatsTheBackKey() {
        VehicleInfo plane = car(VehicleMedium.AIR);
        VehiclePhysics.Demand both = VehiclePhysics.Demand.steering(0, 0, 0, -1, 1, false);
        VehiclePhysics.Step step = VehiclePhysics.step(plane, new VehiclePhysics.State(0, 20, 0),
                both, VehiclePhysics.Surroundings.falling(), DT);
        assertTrue(step.dy() > 0);
    }

    /**
     * Where the keys can be read, looking around must not fly the aircraft —
     * steering already costs the driver their head and the climb must not too.
     */
    @Test
    void pitchDoesNotFlyItWhenTheKeysAreReadable() {
        VehicleInfo plane = car(VehicleMedium.AIR);
        VehiclePhysics.State cruising = new VehiclePhysics.State(0, 20, 0);
        VehiclePhysics.Demand looking = VehiclePhysics.Demand.steering(0, -45, 0, 1, 0, false);
        assertEquals(0, VehiclePhysics.step(plane, cruising, looking, GROUND, DT).dy(), 1e-9);
    }

    /** The arm that has no keys keeps vanilla's answer: look up to climb. */
    @Test
    void pitchStillFliesItWhenTheKeysCannotBeRead() {
        VehicleInfo plane = car(VehicleMedium.AIR);
        VehiclePhysics.State cruising = new VehiclePhysics.State(0, 20, 0);
        VehiclePhysics.Demand looking = new VehiclePhysics.Demand(0, -45, 1, 0, false);
        assertTrue(VehiclePhysics.step(plane, cruising, looking, GROUND, DT).dy() > 0);
    }

    @Test
    void anAirVehicleIgnoresTheGround() {
        VehicleInfo plane = car(VehicleMedium.AIR);
        VehiclePhysics.Step step = VehiclePhysics.step(plane, VehiclePhysics.State.still(0),
                new VehiclePhysics.Demand(0, 0, 0, 1, false), VehiclePhysics.Surroundings.falling(), DT);
        assertTrue(step.dy() > 0, "jump should climb rather than fall");
    }

    // --- aeroplanes ----------------------------------------------------

    /** The same aircraft, with a takeoff run and a stall. */
    private static VehicleInfo aeroplane() {
        return VehicleInfo.of(ContentId.parse("mypack:plane").orElseThrow(), null, null, VehicleMedium.AIR,
                VehiclePhysics.NOMINAL_WEIGHT, 20, 10, 180, VehicleHitbox.DEFAULT,
                VehicleFlight.of(8, 6, 12, 5),
                List.of(VehicleSeat.of(VehicleSeat.Role.DRIVER, VehicleSeat.Pose.SITTING, 0, 0, 0, 0, null)),
                Map.of(), List.of());
    }

    /**
     * The whole of what a takeoff speed means: the climb key does nothing on
     * the runway until the aircraft is fast enough, and then it flies.
     */
    @Test
    void anAeroplaneWillNotLeaveTheGroundBelowItsTakeoffSpeed() {
        VehicleInfo plane = aeroplane();
        VehiclePhysics.Demand climbing = VehiclePhysics.Demand.steering(0, 0, 0, 1, 1, false);

        VehiclePhysics.State stopped = VehiclePhysics.State.still(0);
        assertEquals(0, VehiclePhysics.step(plane, stopped, climbing, GROUND, DT).dy(), 1e-9,
                "standing still, the climb key does nothing");

        VehiclePhysics.State rolling = new VehiclePhysics.State(0, 4, 0);
        assertEquals(0, VehiclePhysics.step(plane, rolling, climbing, GROUND, DT).dy(), 1e-9,
                "half way down the runway, still nothing");

        VehiclePhysics.State fast = new VehiclePhysics.State(0, 12, 0);
        assertTrue(VehiclePhysics.step(plane, fast, climbing, GROUND, DT).dy() > 0,
                "past the takeoff speed it flies");
    }

    /** The takeoff run really is reachable with the throttle it was given. */
    @Test
    void anAeroplaneReachesItsTakeoffSpeedOnItsOwnAcceleration() {
        VehicleInfo plane = aeroplane();
        VehiclePhysics.State state = VehiclePhysics.State.still(0);
        int ticks = 0;
        while (ticks < 200 && !VehiclePhysics.airborneEnough(plane, state.speed())) {
            state = VehiclePhysics.step(plane, state, ahead(0), GROUND, DT).state();
            ticks++;
        }
        assertTrue(VehiclePhysics.airborneEnough(plane, state.speed()), "it has to be able to take off at all");
        // 8 blocks per second at 10 blocks per second squared is eight tenths
        // of a second, which is exactly sixteen ticks. Worth pinning: the
        // takeoff RUN is this number and the acceleration together, which is
        // why the pack states a speed rather than a time.
        assertEquals(16, ticks);
    }

    /**
     * A stall. An aircraft that ran out of speed used to hover there, because
     * the medium's only vertical rule was "hold your height".
     */
    @Test
    void anAeroplaneTooSlowToFlySinksRatherThanHovering() {
        VehicleInfo plane = aeroplane();
        VehiclePhysics.State crawling = new VehiclePhysics.State(0, 2, 0);
        VehiclePhysics.Step step = VehiclePhysics.step(plane, crawling,
                VehiclePhysics.Demand.steering(0, 0, 0, 0, 1, false),
                VehiclePhysics.Surroundings.falling(), DT);
        assertEquals(-5 * DT, step.dy(), 1e-9, "it sinks at its stall rate, climb key or not");
    }

    /**
     * <strong>The complaint this was written for.</strong> Pressing the back
     * key at cruising speed used to brake the aircraft to a standstill in a few
     * seconds and leave it hanging in the air. It should go DOWN and keep most
     * of what it had.
     */
    @Test
    void theBackKeyDivesAnAeroplaneWithoutTakingItsMomentum() {
        VehicleInfo plane = aeroplane();
        VehiclePhysics.State state = new VehiclePhysics.State(0, 20, 0);
        VehiclePhysics.Demand back = VehiclePhysics.Demand.steering(0, 0, 0, -1, 0, false);

        VehiclePhysics.Step first = VehiclePhysics.step(plane, state, back,
                VehiclePhysics.Surroundings.falling(), DT);
        assertTrue(first.dy() < 0, "the back key descends");

        // A whole second of it.
        for (int tick = 0; tick < 20; tick++) {
            state = VehiclePhysics.step(plane, state, back, VehiclePhysics.Surroundings.falling(), DT).state();
        }
        assertTrue(state.speed() > 18, "a second of diving must not scrub the speed: " + state.speed());
        assertTrue(state.speed() < 20, "but it is not free either");
    }

    /**
     * The same number on the ground is a brake, and has to stay one — a plane
     * taxiing is a vehicle on wheels.
     */
    @Test
    void anAeroplaneOnTheGroundStillStopsLikeAVehicle() {
        VehicleInfo plane = aeroplane();
        VehiclePhysics.State ground = new VehiclePhysics.State(0, 20, 0);
        VehiclePhysics.State air = new VehiclePhysics.State(0, 20, 0);
        VehiclePhysics.Demand coasting = VehiclePhysics.Demand.steering(0, 0, 0, 0, 0, false);
        for (int tick = 0; tick < 20; tick++) {
            ground = VehiclePhysics.step(plane, ground, coasting, GROUND, DT).state();
            air = VehiclePhysics.step(plane, air, coasting, VehiclePhysics.Surroundings.falling(), DT).state();
        }
        assertTrue(ground.speed() < air.speed() - 1,
                "the runway takes speed off much faster than the air does");
    }

    /** Up and down are separate numbers, because an aircraft dives faster than it climbs. */
    @Test
    void theDiveRateIsItsOwnNumber() {
        VehicleInfo plane = aeroplane();
        VehiclePhysics.State cruising = new VehiclePhysics.State(0, 20, 0);
        double up = VehiclePhysics.step(plane, cruising,
                VehiclePhysics.Demand.steering(0, 0, 0, 0, 1, false),
                VehiclePhysics.Surroundings.falling(), DT).dy();
        double down = VehiclePhysics.step(plane, cruising,
                VehiclePhysics.Demand.steering(0, 0, 0, 0, -1, false),
                VehiclePhysics.Surroundings.falling(), DT).dy();
        assertEquals(6 * DT, up, 1e-9);
        assertEquals(-12 * DT, down, 1e-9);
    }

    /**
     * A pack that says nothing about flight flies exactly as it did before
     * these numbers existed: lifts from a standstill, climbs at half its top
     * speed, never stalls. Every air vehicle already published depends on it.
     */
    @Test
    void aVehicleThatSaysNothingAboutFlightKeepsTheOldBehaviour() {
        VehicleInfo plane = car(VehicleMedium.AIR);
        assertEquals(0, plane.flight().takeoffSpeed());
        assertEquals(plane.speed() * VehiclePhysics.AIR_CLIMB_FRACTION, plane.flight().climbRate(), 1e-9);
        assertTrue(VehiclePhysics.airborneEnough(plane, 0), "it flies from a standstill");
        VehiclePhysics.Step step = VehiclePhysics.step(plane, VehiclePhysics.State.still(0),
                new VehiclePhysics.Demand(0, 0, 0, 1, false), VehiclePhysics.Surroundings.falling(), DT);
        assertEquals(plane.speed() * VehiclePhysics.AIR_CLIMB_FRACTION * DT, step.dy(), 1e-9);
    }

    /** Being pushed backwards down a runway is not airworthy. */
    @Test
    void reversingDoesNotCountAsFlyingSpeed() {
        VehicleInfo plane = aeroplane();
        assertTrue(VehiclePhysics.airborneEnough(plane, -12), "the magnitude is what flies it");
        assertTrue(!VehiclePhysics.airborneEnough(plane, -4));
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

    // --- water vehicles out of water -----------------------------------

    private static final VehiclePhysics.Surroundings WATER =
            new VehiclePhysics.Surroundings(false, true, 0);

    /**
     * The bug this was written for: a boat drove on grass exactly as fast as
     * it sailed, because the medium decided only the vertical rule and nothing
     * asked whether the hull had anything to push against.
     */
    @Test
    void aBoatOnLandIsMuchSlowerThanABoatInWater() {
        VehicleInfo boat = car(VehicleMedium.WATER);
        VehiclePhysics.State state = VehiclePhysics.State.still(0);

        for (int tick = 0; tick < 200; tick++) {
            state = VehiclePhysics.step(boat, state, ahead(0), WATER, DT).state();
        }
        double afloat = state.speed();

        state = VehiclePhysics.State.still(0);
        for (int tick = 0; tick < 200; tick++) {
            state = VehiclePhysics.step(boat, state, ahead(0), GROUND, DT).state();
        }
        double beached = state.speed();

        assertEquals(boat.speed(), afloat, 1e-6);
        assertEquals(boat.speed() * VehiclePhysics.BEACHED_FRACTION, beached, 1e-6);
    }

    /**
     * Not zero, and this is the test that says so. A boat that cannot move at
     * all on land beaches itself on the first shore and is lost for ever —
     * the driver has no way back to the water. It has to crawl.
     */
    @Test
    void aBeachedBoatCanStillCrawlBackToTheWater() {
        VehicleInfo boat = car(VehicleMedium.WATER);
        VehiclePhysics.State state = VehiclePhysics.State.still(0);
        for (int tick = 0; tick < 40; tick++) {
            state = VehiclePhysics.step(boat, state, ahead(0), GROUND, DT).state();
        }
        assertTrue(state.speed() > 0, "a beached boat must still be able to move");
    }

    /** A car is not "beached" for being on a road. */
    @Test
    void onlyAWaterVehicleIsSlowedByBeingOutOfWater() {
        assertTrue(VehiclePhysics.beached(car(VehicleMedium.WATER), GROUND));
        assertTrue(!VehiclePhysics.beached(car(VehicleMedium.WATER), WATER));
        assertTrue(!VehiclePhysics.beached(car(VehicleMedium.LAND), GROUND));
        assertTrue(!VehiclePhysics.beached(car(VehicleMedium.AIR), GROUND));
    }

    /**
     * Steering is deliberately NOT slowed with the speed: a beached boat that
     * could crawl but not turn would be pointed away from the water as often
     * as toward it.
     */
    @Test
    void aBeachedBoatTurnsAtItsFullRate() {
        VehicleInfo boat = car(VehicleMedium.WATER);
        VehiclePhysics.State state = VehiclePhysics.State.still(0);
        double afloat = VehiclePhysics.step(boat, state, ahead(90), WATER, DT).state().yaw();
        double beached = VehiclePhysics.step(boat, state, ahead(90), GROUND, DT).state().yaw();
        assertEquals(afloat, beached, 1e-9);
    }

    // --- the states an animation and a particle hang off ----------------

    /**
     * Idle and moving are the two every pack uses, and they are decided by
     * speed alone rather than by whether anybody is holding a key — a vehicle
     * coasting is moving.
     */
    @Test
    void aStationaryVehicleIsIdleAndAMovingOneIsNot() {
        VehicleInfo info = car(VehicleMedium.LAND);
        Set<VehicleState> stopped =
                VehiclePhysics.step(info, VehiclePhysics.State.still(0),
                        VehiclePhysics.Demand.idle(0), GROUND, DT).states();
        assertTrue(stopped.contains(VehicleState.IDLE));
        assertTrue(!stopped.contains(VehicleState.MOVING));

        Set<VehicleState> driving =
                VehiclePhysics.step(info, new VehiclePhysics.State(0, 10, 0),
                        ahead(0), GROUND, DT).states();
        assertTrue(driving.contains(VehicleState.MOVING));
        assertTrue(!driving.contains(VehicleState.IDLE));
    }

    @Test
    void goingBackwardsIsReversingRatherThanMoving() {
        Set<VehicleState> states =
                VehiclePhysics.step(car(VehicleMedium.LAND), new VehiclePhysics.State(0, -5, 0),
                        new VehiclePhysics.Demand(0, 0, -1, 0, false), GROUND, DT).states();
        assertTrue(states.contains(VehicleState.REVERSING));
        assertTrue(!states.contains(VehicleState.MOVING));
        assertTrue(!states.contains(VehicleState.IDLE));
    }

    /**
     * Turning is measured against how far the body ACTUALLY came round, not
     * against what the driver asked for — so a vehicle already pointing where
     * the driver is looking is not turning however hard they hold the mouse.
     */
    @Test
    void turningIsMeasuredAgainstTheBodyRatherThanTheDriver() {
        VehicleInfo info = car(VehicleMedium.LAND);
        VehiclePhysics.State state = new VehiclePhysics.State(0, 10, 0);

        assertTrue(VehiclePhysics.step(info, state, ahead(90), GROUND, DT)
                .states().contains(VehicleState.TURNING));
        assertTrue(!VehiclePhysics.step(info, state, ahead(0), GROUND, DT)
                .states().contains(VehicleState.TURNING));
    }

    /**
     * The wrap matters, and this is the case that proves it rather than merely
     * exercising it.
     *
     * <p>The threshold is 15 degrees a second, which over one tick is 0.75 of
     * a degree — so the turn has to be SMALLER than that to tell the two
     * readings apart. A vehicle drifting from 359.9 to 0.2 has turned three
     * tenths of a degree and is driving straight; the naive subtraction calls
     * it 359.7 and would report every vehicle that crosses north as cornering
     * hard. A two-degree turn is above the threshold either way and proves
     * nothing.
     */
    @Test
    void turningPastNorthIsTheShortWayRound() {
        VehicleInfo info = car(VehicleMedium.LAND);
        VehiclePhysics.State nearlyNorth = new VehiclePhysics.State(359.9, 10, 0);
        assertTrue(!VehiclePhysics.step(info, nearlyNorth, ahead(0.2), GROUND, DT)
                .states().contains(VehicleState.TURNING));
    }

    /** Water is support, not a fall — a boat riding the surface is doing its job. */
    @Test
    void aFloatingBoatIsSubmergedRatherThanAirborne() {
        Set<VehicleState> states =
                VehiclePhysics.step(car(VehicleMedium.WATER), VehiclePhysics.State.still(0),
                        VehiclePhysics.Demand.idle(0), WATER, DT).states();
        assertTrue(states.contains(VehicleState.SUBMERGED));
        assertTrue(!states.contains(VehicleState.AIRBORNE));
        // And IDLE with it: a boat sitting in water is doing nothing, which is
        // the state most packs actually map. Water is not motion.
        assertTrue(states.contains(VehicleState.IDLE));
        assertTrue(!states.contains(VehicleState.MOVING));
    }

    @Test
    void aVehicleWithNothingUnderItIsAirborne() {
        Set<VehicleState> states =
                VehiclePhysics.step(car(VehicleMedium.LAND), VehiclePhysics.State.still(0),
                        VehiclePhysics.Demand.idle(0), VehiclePhysics.Surroundings.falling(), DT)
                        .states();
        assertTrue(states.contains(VehicleState.AIRBORNE));
    }

    /**
     * Several at once is the whole point of a set — a car cornering off a kerb
     * is turning and airborne and moving together, and an emitter naming any
     * one of them should fire.
     */
    @Test
    void aVehicleIsInSeveralStatesAtOnce() {
        Set<VehicleState> states =
                VehiclePhysics.step(car(VehicleMedium.LAND), new VehiclePhysics.State(0, 10, 0),
                        ahead(90), VehiclePhysics.Surroundings.falling(), DT).states();
        assertTrue(states.contains(VehicleState.MOVING));
        assertTrue(states.contains(VehicleState.TURNING));
        assertTrue(states.contains(VehicleState.AIRBORNE));
    }

    // --- what a plugin can do to a running vehicle ----------------------

    /**
     * A disabled vehicle is given the idle demand whoever is in the seat,
     * so this is what running out of fuel does: the heading is kept and the
     * speed goes to nothing on the coast rate, rather than stopping dead.
     */
    @Test
    void theIdleDemandCoastsToAStopAndKeepsTheHeading() {
        VehiclePhysics.State state = new VehiclePhysics.State(90, 20, 0);
        for (int tick = 0; tick < 20 * 10; tick++) {
            state = VehiclePhysics.step(car(VehicleMedium.LAND), state,
                    VehiclePhysics.Demand.idle(state.yaw()), GROUND, DT).state();
        }
        assertEquals(0, state.speed(), 1e-9);
        assertEquals(90, state.yaw(), 1e-9);
    }

    /**
     * A speed limit is applied as a lower top speed, not a smaller throttle,
     * so the vehicle tops out exactly at it and everything derived from the
     * top speed scales with it. {@code withSpeed} leaves the rest alone.
     */
    @Test
    void aSpeedLimitIsALowerTopSpeed() {
        VehicleInfo car = car(VehicleMedium.LAND);
        VehicleInfo limited = car.withSpeed(5);
        assertEquals(5, limited.speed());
        assertEquals(car.acceleration(), limited.acceleration());
        assertEquals(car.seats(), limited.seats());
        assertEquals(car.hitbox(), limited.hitbox());
        assertEquals(car.id(), limited.id());
        // Ignored rather than refused, like withScale.
        assertTrue(car.withSpeed(0) == car);
        assertTrue(car.withSpeed(Double.NaN) == car);

        VehiclePhysics.State state = VehiclePhysics.State.still(0);
        for (int tick = 0; tick < 20 * 10; tick++) {
            state = VehiclePhysics.step(limited, state, ahead(0), GROUND, DT).state();
        }
        assertEquals(5, state.speed(), 1e-9);
    }

    // --- getting back out of a wall -------------------------------------

    /**
     * A vehicle already inside something may move, even into more of it.
     *
     * <p><strong>This is the whole bug.</strong> The refusal used to be decided
     * on the destination alone, so once a vehicle overlapped a solid — turned
     * into it, tunnelled through it at speed, or had a block placed on it —
     * every destination within a tick's travel overlapped too, every move was
     * refused, and the refusal zeroed the speed. From a standing start one tick
     * of reverse is about 1.5cm, so the vehicle could never accumulate enough
     * to leave the block it was in: it was pinned for good, and the driver's
     * back key did nothing for ever.
     *
     * <p>Refusing achieves nothing here — it cannot keep the vehicle out of a
     * wall it is already in — so the only thing it buys is the trap.
     */
    @Test
    void aVehicleAlreadyInsideAWallMayDriveOut() {
        assertEquals(VehiclePhysics.Collision.MOVE,
                VehiclePhysics.resolve(true, false, true));
    }

    /** The ordinary case is untouched: clear here, solid there, stop dead. */
    @Test
    void aVehicleDrivingIntoAWallStillStops() {
        assertEquals(VehiclePhysics.Collision.STOP,
                VehiclePhysics.resolve(true, false, false));
    }

    /** A clear destination is a move, whatever the vehicle is standing in. */
    @Test
    void aClearDestinationIsAlwaysAMove() {
        assertEquals(VehiclePhysics.Collision.MOVE, VehiclePhysics.resolve(false, false, false));
        assertEquals(VehiclePhysics.Collision.MOVE, VehiclePhysics.resolve(false, false, true));
    }

    /** A kerb is still a kerb, and beats both other answers. */
    @Test
    void aStepUpBeatsStoppingAndEscaping() {
        assertEquals(VehiclePhysics.Collision.STEP_UP, VehiclePhysics.resolve(true, true, false));
        assertEquals(VehiclePhysics.Collision.STEP_UP, VehiclePhysics.resolve(true, true, true));
    }
}
