package ai.resourcepack.engine.core.vehicle;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.VehicleHitbox;
import ai.resourcepack.engine.api.VehicleInfo;
import ai.resourcepack.engine.api.VehicleMedium;
import ai.resourcepack.engine.api.VehicleSeat;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What an UNDAMAGED vehicle does, to the last bit.
 *
 * <p>Unlike {@link HandlingSimTest}, which checks the shape of the handling and
 * is meant to be read, this checks that the shape has not moved at all. Every
 * number below was recorded off the model as it stood, and a change to any of
 * them is a change to how every vehicle on every server already running drives
 * — which is a thing to do deliberately, having looked at the trajectory, and
 * never a side effect of adding something a vehicle can opt into.
 *
 * <p>Five scenarios rather than one, because the handling model is several
 * models: the drive, the tyres, the body on its springs, the air and the
 * water each have their own arm and a change can miss four of them.
 *
 * <p><strong>If you are here because this failed:</strong> the question is not
 * how to update the constants. It is which of the five moved and whether you
 * meant it. Re-record only once you have driven it.
 */
class PhysicsGoldenTest {

    private static final double DT = 1 / 20.0;
    /** Tight enough that a reordered expression is caught, loose enough to survive a JIT. */
    private static final double EXACT = 1e-9;

    private static VehicleInfo car() {
        return VehicleInfo.of(ContentId.parse("mypack:car").orElseThrow(), null, null, VehicleMedium.LAND,
                14, 18, 7.5, 140, VehicleHitbox.of(1.4, 1.2, 3.0),
                List.of(VehicleSeat.of(VehicleSeat.Role.DRIVER, VehicleSeat.Pose.SITTING, 0, 0, 0, 0, null)),
                Map.of(), List.of());
    }

    private static VehiclePhysics.Surroundings flat() {
        return new VehiclePhysics.Surroundings(true, false, 0, new double[] {0, 0, 0, 0});
    }

    private static VehiclePhysics.Demand keys(double steer, double throttle, boolean handbrake) {
        return VehiclePhysics.Demand.steering(0, 0, steer, throttle, 0, handbrake);
    }

    /** Runs a scenario and returns the trajectory folded into one number per field. */
    private static double[] run(VehicleInfo info, VehiclePhysics.State start,
                                VehiclePhysics.Surroundings around,
                                java.util.function.IntFunction<VehiclePhysics.Demand> driver, int ticks) {
        VehiclePhysics.State s = start;
        double x = 0;
        double z = 0;
        double y = 0;
        for (int tick = 0; tick < ticks; tick++) {
            VehiclePhysics.Step step = VehiclePhysics.step(info, s, driver.apply(tick), around, DT);
            s = step.state();
            x += step.dx();
            y += step.dy();
            z += step.dz();
        }
        return new double[] {s.yaw(), s.speed(), s.slip(), s.yawRate(), s.steer(),
                s.pitch(), s.roll(), s.lift(), s.verticalSpeed(), x, y, z};
    }

    private static void same(String what, double[] expected, double[] actual) {
        String[] field = {"yaw", "speed", "slip", "yawRate", "steer",
                "pitch", "roll", "lift", "verticalSpeed", "x", "y", "z"};
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], EXACT, what + ": " + field[i] + " moved");
        }
    }

    @Test
    void aStandingStartIsUnchanged() {
        same("standing start",
                new double[] {0.0, 18.0, 0.0, 0.0, 0.0, -2.174238906528848E-4, 0.0, 0.0,
                        0.0, 0.0, 0.0, 59.24361276921363},
                run(car(), new VehiclePhysics.State(0, 0, 0), flat(), tick -> keys(0, 1, false), 100));
    }

    @Test
    void aFullLockCornerIsUnchanged() {
        same("full lock corner",
                new double[] {187.27824483670437, 9.631346898717313, 4.714354405254252,
                        -97.08002539175574, -18.528715840131653, 0.6375673455022701,
                        7.20376004731159, 0.0, 0.0, 21.394750113491682, 0.0, 9.70306447941501},
                run(car(), new VehiclePhysics.State(0, 16, 0), flat(), tick -> keys(-1, 1, false), 40));
    }

    @Test
    void aHandbrakeTurnIsUnchanged() {
        same("handbrake turn",
                new double[] {121.50263851624693, 5.736766547747743, 0.0, 5.333343007825215E-6,
                        4.188432105795913E-9, 1.6631159291568598, -1.790802575705406E-4,
                        0.0, 0.0, -3.1187177119156506, 0.0, 8.92002285807344},
                run(car(), new VehiclePhysics.State(0, 16, 0), flat(),
                        tick -> tick < 12 ? keys(1, 0, true) : keys(0, 1, false), 40));
    }

    @Test
    void aFallAndItsLandingAreUnchanged() {
        same("falling",
                new double[] {0.0, 12.0, 0.0, 0.0, 0.0, -32.84193799380885, 0.0, 0.0,
                        -27.99999999999999, 0.0, -14.7, 11.999999999999996},
                run(car(), new VehiclePhysics.State(0, 12, 0), VehiclePhysics.Surroundings.falling(),
                        tick -> keys(0, 1, false), 20));
    }

    @Test
    void aSlopeIsUnchanged() {
        VehiclePhysics.Surroundings hill =
                new VehiclePhysics.Surroundings(true, false, 0, new double[] {0.5, 0.5, 0, 0});
        same("slope",
                new double[] {0.0, 2.1087751412585343, 0.0, 0.0, 0.0, 11.027484149520305,
                        0.0, 0.24999986949383163, 0.0, 0.0, 0.0, 5.857106949160733},
                run(car(), new VehiclePhysics.State(0, 10, 0), hill, tick -> keys(0, 0, false), 20));
    }
}
