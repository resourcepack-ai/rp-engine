package ai.resourcepack.engine.core.vehicle;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.VehicleHitbox;
import ai.resourcepack.engine.api.VehicleInfo;
import ai.resourcepack.engine.api.VehicleMedium;
import ai.resourcepack.engine.api.VehicleSeat;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives a car around on paper and prints what it did.
 *
 * <p>Not a check of a number; a check of a SHAPE. Run with {@code -i} and read
 * the trajectories: does a corner at speed go wide rather than spin, does a
 * handbrake turn actually swing, does the body settle after a kerb. The
 * assertions are the coarse sanity that stops the model diverging.
 */
class HandlingSimTest {

    private static final double DT = 1 / 20.0;

    private static VehicleInfo car() {
        return VehicleInfo.of(ContentId.parse("mypack:car").orElseThrow(), null, null, VehicleMedium.LAND,
                14, 18, 7.5, 140, VehicleHitbox.of(1.4, 1.2, 3.0),
                List.of(VehicleSeat.of(VehicleSeat.Role.DRIVER, VehicleSeat.Pose.SITTING, 0, 0, 0, 0, null)),
                Map.of(), List.of());
    }

    private static final VehiclePhysics.Surroundings FLAT =
            new VehiclePhysics.Surroundings(true, false, 0, new double[] {0, 0, 0, 0});

    private static VehiclePhysics.Demand keys(double steer, double throttle, boolean handbrake) {
        return VehiclePhysics.Demand.steering(0, 0, steer, throttle, 0, handbrake);
    }

    private static String row(double t, VehiclePhysics.State s, double x, double z) {
        return String.format("t=%5.2f yaw=%6.1f rate=%7.1f steer=%5.1f v=%5.2f slip=%6.2f | x=%7.2f z=%7.2f | pitch=%5.1f roll=%5.1f lift=%5.2f",
                t, s.yaw(), s.yawRate(), s.steer(), s.speed(), s.slip(), x, z, s.pitch(), s.roll(), s.lift());
    }

    @Test
    void fullLockCornerAtSpeedGoesWideRatherThanSpinning() {
        VehicleInfo info = car();
        VehiclePhysics.State s = new VehiclePhysics.State(0, 16, 0);
        double x = 0, z = 0;
        double maxSlip = 0;
        System.out.println("--- full lock right at 16 b/s, throttle held");
        for (int tick = 0; tick <= 80; tick++) {
            VehiclePhysics.Step step = VehiclePhysics.step(info, s, keys(1, 1, false), FLAT, DT);
            s = step.state();
            x += step.dx();
            z += step.dz();
            maxSlip = Math.max(maxSlip, Math.abs(s.slip()));
            if (tick % 5 == 0) {
                System.out.println(row(tick * DT, s, x, z));
            }
        }
        assertTrue(maxSlip < 6, "a gripping corner should not become a spin, slip peaked at " + maxSlip);
        assertTrue(Math.abs(s.yawRate()) > 30, "the car should be turning");
    }

    @Test
    void handbrakeTurnSwingsTheBackOut() {
        VehicleInfo info = car();
        VehiclePhysics.State s = new VehiclePhysics.State(0, 16, 0);
        double x = 0, z = 0;
        double maxSlip = 0;
        System.out.println("--- 16 b/s, full lock right + handbrake for 0.6 s, then throttle and straighten");
        for (int tick = 0; tick <= 80; tick++) {
            boolean pulling = tick < 12;
            VehiclePhysics.Demand demand = pulling ? keys(1, 0, true) : keys(0, 1, false);
            VehiclePhysics.Step step = VehiclePhysics.step(info, s, demand, FLAT, DT);
            s = step.state();
            x += step.dx();
            z += step.dz();
            maxSlip = Math.max(maxSlip, Math.abs(s.slip()));
            if (tick % 4 == 0) {
                System.out.println(row(tick * DT, s, x, z));
            }
        }
        assertTrue(maxSlip > 4, "a handbrake turn should slide, slip peaked at " + maxSlip);
        assertTrue(Math.abs(s.slip()) < 1.5, "and the slide should be caught once the driver eases off, slip " + s.slip());
    }

    @Test
    void aKerbBouncesAndSettles() {
        VehicleInfo info = car();
        VehiclePhysics.State s = new VehiclePhysics.State(0, 10, 0);
        System.out.println("--- kerb: front wheels up one block for 0.3 s, then all four");
        for (int tick = 0; tick <= 60; tick++) {
            VehiclePhysics.Surroundings ground;
            if (tick == 5) {
                s = s.stepped(1);
            }
            if (tick >= 5 && tick < 11) {
                ground = new VehiclePhysics.Surroundings(true, false, 0, new double[] {0, 0, -1, -1});
            } else {
                ground = FLAT;
            }
            VehiclePhysics.Step step = VehiclePhysics.step(info, s, keys(0, 1, false), ground, DT);
            s = step.state();
            if (tick % 2 == 0) {
                System.out.println(row(tick * DT, s, 0, 0));
            }
        }
        assertTrue(Math.abs(s.pitch()) < 1, "pitch should settle, at " + s.pitch());
        assertTrue(Math.abs(s.lift()) < 0.05, "ride height should settle, at " + s.lift());
    }

    @Test
    void launchAndBrake() {
        VehicleInfo info = car();
        VehiclePhysics.State s = VehiclePhysics.State.still(0);
        System.out.println("--- launch 3 s, handbrake");
        for (int tick = 0; tick <= 140; tick++) {
            VehiclePhysics.Demand demand = tick < 60 ? keys(0, 1, false) : keys(0, 0, true);
            VehiclePhysics.Step step = VehiclePhysics.step(info, s, demand, FLAT, DT);
            s = step.state();
            if (tick % 5 == 0) {
                System.out.println(row(tick * DT, s, 0, 0));
            }
        }
        assertTrue(Math.abs(s.speed()) < 0.01, "should be stopped, at " + s.speed());
    }

    /**
     * The whole point of the weight transfer: the same corner, entered on the
     * brakes, rotates the car more than entering it on the throttle.
     *
     * <p>Which is the difference between a car you aim and a car you set up.
     * Braking loads the nose and takes the rear light — and it spends the rear
     * tyres' budget as well, since the brakes are on all four wheels — so the
     * back steps out. On the throttle the same corner understeers instead.
     */
    @Test
    void brakingIntoACornerRotatesItMoreThanPoweringThrough() {
        // Settled into the corner first, and identically, so what is compared
        // is the brake and not where the two cars happen to be.
        VehiclePhysics.State entry = new VehiclePhysics.State(0, 16, 0);
        for (int tick = 0; tick < 10; tick++) {
            entry = VehiclePhysics.step(car(), entry, keys(1, 1, false), FLAT, DT).state();
        }
        double braked = cornerSlip(entry, -1);
        double powered = cornerSlip(entry, 1);
        System.out.println("--- from slip " + entry.slip() + ": on the brakes " + braked
                + ", on the throttle " + powered);
        assertTrue(braked > powered * 1.15,
                "braking into a corner should rotate it more, " + braked + " vs " + powered);
    }

    /**
     * How far the back comes out over a quarter of a second of the same
     * corner, from the same state, on {@code throttle}.
     *
     * <p>Short, and that is the whole of why: the FOOT brake here is the back
     * key, which is a full stop rather than a trim, so a long window compares a
     * cornering car against a stationary one — and a stationary car does not
     * slide however light its rear is. A brake in this model is something you
     * touch on the way in.
     */
    private static double cornerSlip(VehiclePhysics.State from, double throttle) {
        VehiclePhysics.State s = from;
        double peak = 0;
        for (int tick = 0; tick < 5; tick++) {
            s = VehiclePhysics.step(car(), s, keys(1, throttle, false), FLAT, DT).state();
            peak = Math.max(peak, Math.abs(s.slip()));
        }
        return peak;
    }

    /**
     * Air control: a steering key off a ramp brings the vehicle round, and
     * cannot drag down a bigger rotation a plugin threw it into.
     */
    @Test
    void steeringInMidAirTurnsTheVehicleAndNeverFightsATrick() {
        VehicleInfo info = car();
        VehiclePhysics.State s = new VehiclePhysics.State(0, 12, 6);
        for (int tick = 0; tick < 20; tick++) {
            s = VehiclePhysics.step(info, s, keys(1, 0, false), VehiclePhysics.Surroundings.falling(), DT)
                    .state();
        }
        System.out.println("--- one second of air steering: yaw " + s.yaw() + " rate " + s.yawRate());
        assertTrue(s.yawRate() > 10, "air control should be building a rotation, rate " + s.yawRate());

        // A trick's spin, steered against — compared with the same spin left
        // alone, because a mid-air rotation decays on its own
        // (YAW_RESPONSE_AIRBORNE) and the question is only whether the KEY
        // took anything off it.
        double fought = airborneSpin(info, 400, -1);
        double alone = airborneSpin(info, 400, 0);
        System.out.println("--- a 400 deg/s trick: left alone " + alone + ", steered against " + fought);
        assertTrue(fought == alone, "a key must not touch a trick's spin, " + fought + " vs " + alone);
    }

    /** What a second of mid-air leaves of a spin, with {@code steer} held. */
    private static double airborneSpin(VehicleInfo info, double spin, double steer) {
        VehiclePhysics.State s = new VehiclePhysics.State(0, 12, 6).spun(spin);
        for (int tick = 0; tick < 20; tick++) {
            s = VehiclePhysics.step(info, s, keys(steer, 0, false),
                    VehiclePhysics.Surroundings.falling(), DT).state();
        }
        return s.yawRate();
    }

    /** A kerb costs nothing; coming down from a great height costs speed. */
    @Test
    void aHardLandingScrubsSpeedAndAKerbDoesNot() {
        VehiclePhysics.State kerb = new VehiclePhysics.State(0, 14, -5).landed();
        VehiclePhysics.State drop = new VehiclePhysics.State(0, 14, -28).landed();
        System.out.println("--- landing at 5 keeps " + kerb.speed() + ", at 28 keeps " + drop.speed());
        assertTrue(kerb.speed() == 14, "a hop off a kerb should be free, kept " + kerb.speed());
        assertTrue(drop.speed() < 10 && drop.speed() > 4, "a big drop should hurt, kept " + drop.speed());
    }

    /** A car in water wallows to a stop, whatever its driver is asking for. */
    @Test
    void aLandVehicleInWaterStops() {
        VehicleInfo info = car();
        VehiclePhysics.State s = new VehiclePhysics.State(0, 16, 0);
        VehiclePhysics.Surroundings lake =
                new VehiclePhysics.Surroundings(true, true, 1, new double[] {0, 0, 0, 0});
        for (int tick = 0; tick < 80; tick++) {
            s = VehiclePhysics.step(info, s, keys(0, 1, false), lake, DT).state();
        }
        System.out.println("--- four seconds of flat out in a lake: " + s.speed());
        assertTrue(s.speed() < 0.5, "a car cannot drive in water, at " + s.speed());
    }

    @Test
    void lowSpeedFullLockTurnsTightlyWithoutSliding() {
        VehicleInfo info = car();
        VehiclePhysics.State s = new VehiclePhysics.State(0, 5, 0);
        double maxSlip = 0;
        double startYaw = 0;
        for (int tick = 0; tick < 40; tick++) {
            s = VehiclePhysics.step(info, s, keys(-1, 0.4, false), FLAT, DT).state();
            maxSlip = Math.max(maxSlip, Math.abs(s.slip()));
        }
        System.out.println("--- 2 s of full left at a crawl: yaw " + s.yaw() + " slip peak " + maxSlip);
        assertTrue(maxSlip < 1.5, "no slide at a crawl, slip " + maxSlip);
        assertTrue(VehiclePhysics.wrap180(s.yaw() - startYaw) < -60, "should have come round a good way, yaw " + s.yaw());
    }
}
