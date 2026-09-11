package ai.resourcepack.engine.core.vehicle;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.VehicleCorner;
import ai.resourcepack.engine.api.VehicleDamage;
import ai.resourcepack.engine.api.VehicleHitbox;
import ai.resourcepack.engine.api.VehicleInfo;
import ai.resourcepack.engine.api.VehicleMedium;
import ai.resourcepack.engine.api.VehicleSeat;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What damage does to a vehicle, and — first — what it does to one that has
 * none.
 *
 * <p>{@link PhysicsGoldenTest} holds the undamaged trajectory to the bit; this
 * checks the other half, that a damaged one behaves in the direction it should.
 * Directions rather than figures, deliberately: the constants in
 * {@link DamageResponse} are tuning and are meant to move, and a test that
 * pinned them would have to be rewritten every time somebody drove it and
 * disagreed. What must not move is the SIGNS — a vehicle that leans away from
 * its missing wheel, or pulls right when the left corner is dragging, is not a
 * tuning problem.
 */
class VehicleDamageTest {

    private static final double DT = 1 / 20.0;

    private static VehicleInfo car() {
        return VehicleInfo.of(ContentId.parse("mypack:car").orElseThrow(), null, null, VehicleMedium.LAND,
                14, 18, 7.5, 140, VehicleHitbox.of(1.4, 1.2, 3.0),
                List.of(VehicleSeat.of(VehicleSeat.Role.DRIVER, VehicleSeat.Pose.SITTING, 0, 0, 0, 0, null)),
                Map.of(), List.of());
    }

    private static VehiclePhysics.Surroundings flat() {
        return new VehiclePhysics.Surroundings(true, false, 0, new double[] {0, 0, 0, 0});
    }

    private static VehicleDamage lost(VehicleCorner corner) {
        return VehicleDamage.builder().wheel(corner, 0).build();
    }

    // ---- the value ------------------------------------------------------

    @Test
    void aSoundVehicleIsTheSharedNoneRatherThanACopyOfIt() {
        VehicleDamage rebuilt = VehicleDamage.builder()
                .wheels(1).enginePower(1).drag(0).steeringPull(0).steeringSlack(0)
                .build();
        assertSame(VehicleDamage.NONE, rebuilt,
                "a plugin re-asserting that nothing is wrong should allocate nothing");
        assertTrue(rebuilt.none());
    }

    @Test
    void nonsenseIsClampedRatherThanCarried() {
        VehicleDamage damage = VehicleDamage.builder()
                .wheel(VehicleCorner.REAR_LEFT, -5)
                .steeringPull(400)
                .drag(1e9)
                .enginePower(0)
                .build();
        assertEquals(0, damage.wheel(VehicleCorner.REAR_LEFT), "below zero is gone, not negative");
        assertEquals(VehicleDamage.MAX_STEERING_PULL, damage.steeringPull(), "too far is as far as it goes");
        assertEquals(VehicleDamage.MAX_DRAG, damage.drag());
        assertTrue(damage.enginePower() > 0, "an engine making literally nothing is a vehicle nothing can move");
    }

    @Test
    void arithmeticThatWentWrongCostsNothingRatherThanEverything() {
        // A crash that produced a NaN should leave the vehicle as it was. The
        // alternative is a clamp to the maximum, which turns one bad division
        // somewhere in a plugin into a vehicle that steers itself into a wall.
        VehicleDamage damage = VehicleDamage.builder()
                .wheel(VehicleCorner.FRONT_LEFT, Double.NaN)
                .steeringPull(Double.POSITIVE_INFINITY)
                .drag(Double.NaN)
                .enginePower(Double.NEGATIVE_INFINITY)
                .build();
        assertSame(VehicleDamage.NONE, damage, "nothing usable was said, so nothing is wrong with it");
    }

    @Test
    void aCornerIsReadFromTheSignsOfWhereItIs() {
        assertEquals(VehicleCorner.FRONT_LEFT, VehicleCorner.of(-0.7, 1.4));
        assertEquals(VehicleCorner.FRONT_RIGHT, VehicleCorner.of(0.7, 1.4));
        assertEquals(VehicleCorner.REAR_LEFT, VehicleCorner.of(-0.7, -1.4));
        assertEquals(VehicleCorner.REAR_RIGHT, VehicleCorner.of(0.7, -1.4));
        assertEquals(VehicleCorner.REAR_RIGHT, VehicleCorner.FRONT_LEFT.opposite());
        assertTrue(VehicleCorner.FRONT_LEFT.front() && VehicleCorner.FRONT_LEFT.left());
    }

    // ---- the response ---------------------------------------------------

    @Test
    void nothingWrongIsExactlyNeutral() {
        DamageResponse none = DamageResponse.of(VehicleDamage.NONE);
        assertFalse(none.any(), "the physics has to be able to skip the whole of it");
        assertEquals(1, none.frontGrip());
        assertEquals(1, none.rearGrip());
        assertEquals(1, none.steerRate());
        assertEquals(1, none.steerLock());
        assertEquals(1, none.power());
        assertEquals(0, none.drag());
        assertEquals(0, none.rollBias());
        assertEquals(0, none.pitchBias());
        assertEquals(0, none.rideDrop());
        assertEquals(0, none.yawPull(20));
        assertSame(DamageResponse.NONE, DamageResponse.of(null), "null is a sound vehicle");
    }

    @Test
    void aLostCornerLeansOntoItselfAndNotAwayFromIt() {
        DamageResponse left = DamageResponse.of(lost(VehicleCorner.FRONT_LEFT));
        // Roll is right-side-down positive, so dropping the LEFT front is negative.
        assertTrue(left.rollBias() < 0, "a vehicle rests on the corner it lost, roll was " + left.rollBias());
        assertTrue(left.pitchBias() < 0, "and the nose goes down, pitch was " + left.pitchBias());
        assertTrue(left.rideDrop() > 0, "the whole body settles");

        DamageResponse right = DamageResponse.of(lost(VehicleCorner.REAR_RIGHT));
        assertTrue(right.rollBias() > 0, "the mirror image of it");
        assertTrue(right.pitchBias() > 0, "nose up on a lost rear corner");
    }

    @Test
    void aDiagonalPairSitsLevelAndStillDrags() {
        DamageResponse diagonal = DamageResponse.of(VehicleDamage.builder()
                .wheel(VehicleCorner.FRONT_LEFT, 0)
                .wheel(VehicleCorner.REAR_RIGHT, 0)
                .build());
        assertEquals(0, diagonal.rollBias(), 1e-9, "opposite corners cannot lean it either way");
        assertEquals(0, diagonal.pitchBias(), 1e-9);
        assertTrue(diagonal.rideDrop() > 0, "but it is still sitting on two hubs");
        assertTrue(diagonal.drag() > 0, "and still scraping");
    }

    @Test
    void aDraggingRearCornerYawsAndADraggingFrontOneSteers() {
        DamageResponse rear = DamageResponse.of(lost(VehicleCorner.REAR_LEFT));
        assertTrue(rear.yawPull(20) < 0, "a rear-left drag swings the nose left");
        assertEquals(0, rear.steerBias(), 1e-9, "a rear corner is not steering");

        DamageResponse front = DamageResponse.of(lost(VehicleCorner.FRONT_LEFT));
        assertTrue(front.steerBias() < 0, "a front-left drag pulls the steering left");
        assertEquals(0, front.yawPull(20), 1e-9, "a front corner does not yaw it directly");
    }

    @Test
    void aPullNeedsSpeedToPullWith() {
        DamageResponse rear = DamageResponse.of(lost(VehicleCorner.REAR_RIGHT));
        assertEquals(0, rear.yawPull(0), 1e-9, "a wreck standing still must not rotate on the spot");
        assertTrue(Math.abs(rear.yawPull(2)) < Math.abs(rear.yawPull(20)), "and pulls harder the faster it goes");
    }

    @Test
    void aSoftTyreIsNotHalfAMissingWheel() {
        double damaged = Math.abs(DamageResponse.of(VehicleDamage.builder()
                .wheel(VehicleCorner.FRONT_LEFT, 0.5).build()).rollBias());
        double gone = Math.abs(DamageResponse.of(lost(VehicleCorner.FRONT_LEFT)).rollBias());
        assertTrue(damaged > 0, "it is worth something");
        assertTrue(damaged < gone / 3,
                "but most of the lean belongs to the last of the wheel: " + damaged + " vs " + gone);
    }

    @Test
    void anAxleLosingBothWheelsKeepsSomeGrip() {
        DamageResponse front = DamageResponse.of(VehicleDamage.builder()
                .wheel(VehicleCorner.FRONT_LEFT, 0).wheel(VehicleCorner.FRONT_RIGHT, 0).build());
        assertTrue(front.frontGrip() > 0 && front.frontGrip() < 0.5,
                "a bare rim still scrubs round, badly: " + front.frontGrip());
        assertEquals(1, front.rearGrip(), "and the back is untouched");
    }

    // ---- and what that does to a vehicle --------------------------------

    /** @return final state after driving flat out in a straight line */
    private static VehiclePhysics.State drive(VehicleDamage damage, int ticks) {
        VehicleInfo info = car();
        VehiclePhysics.State s = new VehiclePhysics.State(0, 14, 0);
        for (int tick = 0; tick < ticks; tick++) {
            s = VehiclePhysics.step(info, s, VehiclePhysics.Demand.steering(0, 0, 0, 1, 0, false),
                    flat(), DT, damage).state();
        }
        return s;
    }

    @Test
    void aThreeWheeledCarDoesNotDriveLikeAWholeOne() {
        VehiclePhysics.State sound = drive(VehicleDamage.NONE, 60);
        VehiclePhysics.State broken = drive(lost(VehicleCorner.REAR_LEFT), 60);

        assertTrue(broken.speed() < sound.speed(),
                "it should not reach what it used to: " + broken.speed() + " vs " + sound.speed());
        assertTrue(Math.abs(broken.yaw()) > 1 && Math.abs(broken.yaw() - 360) > 1,
                "and it should not hold a straight line, yaw was " + broken.yaw());
        assertEquals(0, sound.yaw(), 1e-9, "while a sound one still goes exactly where it is pointed");
        assertTrue(broken.roll() < -0.5, "sitting on the corner it lost, roll was " + broken.roll());
    }

    @Test
    void enoughLostCornersStopItOutright() {
        VehiclePhysics.State wreck = drive(VehicleDamage.builder().wheels(0).build(), 120);
        assertTrue(Math.abs(wreck.speed()) < 2,
                "a vehicle on four hubs should not be driveable, it did " + wreck.speed());
    }

    @Test
    void aDamagedEngineIsNotDamagedBrakes() {
        VehicleInfo info = car();
        VehicleDamage weak = VehicleDamage.builder().enginePower(0.3).build();
        // From a standstill, the weak one accelerates worse.
        VehiclePhysics.State slow = new VehiclePhysics.State(0, 0, 0);
        VehiclePhysics.State quick = new VehiclePhysics.State(0, 0, 0);
        for (int tick = 0; tick < 20; tick++) {
            VehiclePhysics.Demand go = VehiclePhysics.Demand.steering(0, 0, 0, 1, 0, false);
            slow = VehiclePhysics.step(info, slow, go, flat(), DT, weak).state();
            quick = VehiclePhysics.step(info, quick, go, flat(), DT, VehicleDamage.NONE).state();
        }
        assertTrue(slow.speed() < quick.speed() * 0.8, "a weak engine should be slow away");

        // From the same speed, both stop in the same distance.
        VehiclePhysics.State a = new VehiclePhysics.State(0, 16, 0);
        VehiclePhysics.State b = new VehiclePhysics.State(0, 16, 0);
        for (int tick = 0; tick < 20; tick++) {
            VehiclePhysics.Demand stop = VehiclePhysics.Demand.steering(0, 0, 0, 0, 0, true);
            a = VehiclePhysics.step(info, a, stop, flat(), DT, weak).state();
            b = VehiclePhysics.step(info, b, stop, flat(), DT, VehicleDamage.NONE).state();
        }
        assertEquals(b.speed(), a.speed(), 1e-9, "the brakes are not the engine");
    }

    @Test
    void damageDoesNothingInTheAir() {
        VehicleInfo info = car();
        VehicleDamage broken = VehicleDamage.builder().wheels(0).drag(VehicleDamage.MAX_DRAG).build();
        VehiclePhysics.State falling = new VehiclePhysics.State(0, 12, 0);
        VehiclePhysics.State sound = new VehiclePhysics.State(0, 12, 0);
        for (int tick = 0; tick < 20; tick++) {
            VehiclePhysics.Demand go = VehiclePhysics.Demand.steering(0, 0, 0, 1, 0, false);
            falling = VehiclePhysics.step(info, falling, go, VehiclePhysics.Surroundings.falling(),
                    DT, broken).state();
            sound = VehiclePhysics.step(info, sound, go, VehiclePhysics.Surroundings.falling(),
                    DT, VehicleDamage.NONE).state();
        }
        assertEquals(sound.speed(), falling.speed(), 1e-9, "a dragging corner needs a road to drag on");
        assertEquals(sound.verticalSpeed(), falling.verticalSpeed(), 1e-9, "and a wreck falls like anything else");
    }
}
