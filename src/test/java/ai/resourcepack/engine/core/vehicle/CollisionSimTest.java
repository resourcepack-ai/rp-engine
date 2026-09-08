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
 * Two cars crashing into each other over and over, on paper.
 *
 * <p>{@link VehicleImpactsTest} checks one collision; this checks what a
 * hundred ticks of them do, which is a different question and the one the
 * failures live in. A momentum exchange that is exactly right in isolation can
 * still buzz for ever against a resting contact, sink two vehicles into each
 * other or feed a spin that never comes back — and none of those is visible
 * from a single tick.
 *
 * <p>Same shape as {@link HandlingSimTest}: it drives, it prints, and the
 * assertions are the coarse sanity that stops the model diverging. Run with
 * {@code -i} and read the rows.
 *
 * <p>What it does NOT have is a world. {@link VehicleRuntime} owns the block
 * reads, so this stands both cars on flat ground for ever and reproduces
 * exactly the part that is arithmetic: step, then let them hit each other, then
 * step again.
 */
class CollisionSimTest {

    private static final double DT = 1 / 20.0;

    private static final VehiclePhysics.Surroundings FLAT =
            new VehiclePhysics.Surroundings(true, false, 0, new double[] {0, 0, 0, 0});

    private static VehicleInfo car(double weight) {
        return VehicleInfo.of(ContentId.parse("mypack:car").orElseThrow(), null, null, VehicleMedium.LAND,
                weight, 18, 7.5, 140, VehicleHitbox.of(1.4, 1.2, 3.0),
                List.of(VehicleSeat.of(VehicleSeat.Role.DRIVER, VehicleSeat.Pose.SITTING, 0, 0, 0, 0, null)),
                Map.of(), List.of());
    }

    /** One vehicle: its definition, where it is, and where it is going. */
    private static final class Car {

        private final VehicleInfo info;
        private VehiclePhysics.State state;
        private double x;
        private double z;
        /**
         * What the driver is asking for, or null for nobody at the wheel.
         *
         * <p>Null rather than an idle demand held in a field, because an idle
         * demand names a heading and steers toward it — see
         * {@code VehiclePhysics.steerInput} — so one built at construction has
         * a car with no driver hauling itself back round to the yaw it was
         * built with. The runtime passes {@code idle(state.yaw())} every tick
         * for exactly this reason, and so does {@link #step}.
         */
        private VehiclePhysics.Demand demand;

        Car(VehicleInfo info, double x, double z, double yaw, double speed) {
            this.info = info;
            this.x = x;
            this.z = z;
            this.state = new VehiclePhysics.State(yaw, speed, 0);
        }

        Car driving(double steer, double throttle) {
            demand = VehiclePhysics.Demand.steering(0, 0, steer, throttle, 0, false);
            return this;
        }

        void step() {
            VehiclePhysics.Demand asked = demand != null
                    ? demand
                    : VehiclePhysics.Demand.idle(state.yaw());
            VehiclePhysics.Step step = VehiclePhysics.step(info, state, asked, FLAT, DT);
            state = step.state();
            x += step.dx();
            z += step.dz();
        }

        VehicleImpacts.Body body() {
            double[] v = VehiclePhysics.worldVelocity(state.yaw(), state.speed(), state.slip());
            VehicleHitbox box = info.hitbox();
            return new VehicleImpacts.Body(x, z, state.yaw(), box.width(), box.length(),
                    v[0], v[1], state.yawRate(), Math.max(0.1, info.weight()));
        }

        /** Exactly what {@code VehicleRuntime.Ride.bump} does, minus asking the world. */
        void bump(VehicleImpacts.Impulse impulse) {
            double[] v = VehiclePhysics.worldVelocity(state.yaw(), state.speed(), state.slip());
            state = state.impacted(v[0] + impulse.dvx(), v[1] + impulse.dvz(),
                    state.yawRate() + impulse.dspin());
            x += impulse.pushX();
            z += impulse.pushZ();
        }

        double gap(Car other) {
            return Math.hypot(x - other.x, z - other.z);
        }
    }

    /** The runtime's three passes, without the runtime. */
    private static void tick(Car a, Car b) {
        a.step();
        b.step();
        VehicleImpacts.Contact contact = VehicleImpacts.contact(a.body(), b.body());
        if (contact != null) {
            VehicleImpacts.Exchange x = VehicleImpacts.resolve(a.body(), b.body(), contact);
            a.bump(x.a());
            b.bump(x.b());
        }
    }

    private static String row(int tick, Car a, Car b) {
        return String.format(
                "t=%5.2f | A z=%7.2f v=%6.2f slip=%6.2f yaw=%6.1f | B z=%7.2f v=%6.2f slip=%6.2f yaw=%6.1f | gap=%5.2f",
                tick * DT, a.z, a.state.speed(), a.state.slip(), a.state.yaw(),
                b.z, b.state.speed(), b.state.slip(), b.state.yaw(), a.gap(b));
    }

    /** How deep the two are inside each other right now, or 0 if they are not. */
    private static double overlap(Car a, Car b) {
        VehicleImpacts.Contact c = VehicleImpacts.contact(a.body(), b.body());
        return c == null ? 0 : c.depth();
    }

    @Test
    void aHeadOnEndsWithBothStoppedAndApart() {
        Car a = new Car(car(10), 0, 0, 0, 16);
        Car b = new Car(car(10), 0, 12, 180, 16);
        System.out.println("--- head-on at 16 b/s each, nobody on the throttle");
        double worst = 0;
        for (int t = 0; t <= 80; t++) {
            tick(a, b);
            worst = Math.max(worst, overlap(a, b));
            if (t % 5 == 0) {
                System.out.println(row(t, a, b));
            }
        }
        // They met, they stopped, and they are the right way apart.
        assertTrue(b.z > a.z, "they passed through each other");
        assertTrue(a.gap(b) > 2.9, "still inside each other: " + a.gap(b));
        assertTrue(Math.abs(a.state.speed()) < 1, "A never settled: " + a.state.speed());
        assertTrue(Math.abs(b.state.speed()) < 1, "B never settled: " + b.state.speed());
        // The separation is a correction, not a shove: it never has to dig
        // either of them out of more than a fraction of a block.
        assertTrue(worst < 1.5, "sank far too deep: " + worst);
    }

    @Test
    void aRearEndShuntsTheCarInFrontAlongTheRoad() {
        Car a = new Car(car(10), 0, 0, 0, 16);
        Car b = new Car(car(10), 0, 6, 0, 0);
        System.out.println("--- rear-ending a stationary car at 16 b/s");
        for (int t = 0; t <= 60; t++) {
            tick(a, b);
            if (t % 5 == 0) {
                System.out.println(row(t, a, b));
            }
        }
        assertTrue(b.z > 6.5, "the car in front should have been shunted: " + b.z);
        assertTrue(b.state.speed() >= 0, "and shunted forwards: " + b.state.speed());
        assertTrue(a.gap(b) > 2.9, "and not driven through: " + a.gap(b));
        // Square on: neither is turned by it.
        assertTrue(Math.abs(a.state.yawRate()) < 1, "square hits do not spin: " + a.state.yawRate());
    }

    @Test
    void pushingAtWalkingPaceIsSteadyRatherThanAStringOfBumps() {
        // The case restitution ruins. A car with the throttle held against a
        // parked one should shove it along at a steady speed; with any bounce
        // in it the pair chatter, and the front car's speed sign flips every
        // few ticks.
        Car a = new Car(car(10), 0, 0, 0, 0).driving(0, 0.12);
        Car b = new Car(car(10), 0, 3.0, 0, 0);
        System.out.println("--- pushing a parked car at a crawl");
        int reversals = 0;
        double previous = 0;
        for (int t = 0; t <= 120; t++) {
            tick(a, b);
            if (t > 20 && previous > 0.2 && b.state.speed() < -0.05) {
                reversals++;
            }
            previous = b.state.speed();
            if (t % 10 == 0) {
                System.out.println(row(t, a, b));
            }
        }
        assertTrue(b.z > 4.0, "the parked car should have been pushed along: " + b.z);
        assertTrue(reversals == 0, "the pushed car kept bouncing back: " + reversals);
        assertTrue(a.gap(b) < 3.4, "the pusher lost contact: " + a.gap(b));
    }

    @Test
    void restingAgainstEachOtherDoesNotJitter() {
        // Nobody driving, parked touching. Nothing should ever happen again.
        Car a = new Car(car(10), 0, 0, 0, 0);
        Car b = new Car(car(10), 0, 2.99, 0, 0);
        for (int t = 0; t <= 200; t++) {
            tick(a, b);
        }
        System.out.println("--- after 10s of resting contact: " + row(200, a, b));
        assertTrue(Math.abs(a.state.speed()) < 1e-6, "A twitched: " + a.state.speed());
        assertTrue(Math.abs(b.state.speed()) < 1e-6, "B twitched: " + b.state.speed());
        assertTrue(Math.abs(a.z) < 0.05 && Math.abs(b.z - 2.99) < 0.05, "they crept apart");
    }

    @Test
    void aSideImpactSlewsTheCarThatWasHitRatherThanTeleportingIt() {
        // Broadside: a driving north into b sitting across its path. The slip
        // the impulse leaves is what the handling model turns into rotation —
        // see VehicleImpacts.MAX_SPIN — so the test is that b ends up both
        // moved and turned, without either running away.
        Car a = new Car(car(10), 0, 0, 0, 18).driving(0, 1);
        Car b = new Car(car(10), 0, 8, 90, 0);
        System.out.println("--- t-boning a car parked across the road at 18 b/s");
        for (int t = 0; t <= 60; t++) {
            tick(a, b);
            if (t % 5 == 0) {
                System.out.println(row(t, a, b));
            }
        }
        assertTrue(b.z > 8.2, "the car hit should have been moved: " + b.z);
        assertTrue(Math.abs(VehiclePhysics.wrap180(b.state.yaw() - 90)) > 2,
                "and slewed round: " + b.state.yaw());
        assertTrue(b.state.groundSpeed() < 25, "and not launched: " + b.state.groundSpeed());
    }

    @Test
    void aLorryMeetingAMopedIsNotAMeetingOfEquals() {
        Car lorry = new Car(car(80), 0, 0, 0, 14).driving(0, 1);
        Car moped = new Car(car(4), 0, 8, 180, 14).driving(0, 1);
        System.out.println("--- 80-weight lorry into a 4-weight moped, both flat out");
        for (int t = 0; t <= 40; t++) {
            tick(lorry, moped);
            if (t % 5 == 0) {
                System.out.println(row(t, lorry, moped));
            }
        }
        assertTrue(lorry.state.speed() > 5, "the lorry should barely notice: " + lorry.state.speed());
        assertTrue(moped.z > 8, "the moped should have been sent back up the road: " + moped.z);
    }

    @Test
    void nothingEverEndsUpStuckInsideAnythingElse() {
        // The long soak, and the one failure a player cannot drive out of.
        // Two cars driven into each other and held there on full throttle for
        // ten seconds: they may overlap while it is happening, and they must
        // not be buried.
        Car a = new Car(car(10), 0, 0, 0, 18).driving(0, 1);
        Car b = new Car(car(14), 0, 10, 180, 18).driving(0, 1);
        double worst = 0;
        for (int t = 0; t <= 200; t++) {
            tick(a, b);
            worst = Math.max(worst, overlap(a, b));
            assertTrue(Double.isFinite(a.z) && Double.isFinite(b.z), "the arithmetic blew up");
        }
        System.out.println("--- nose to nose on full throttle for 10s, deepest overlap " + worst);
        System.out.println(row(200, a, b));
        assertTrue(worst < 1.5, "one buried itself in the other: " + worst);
        assertTrue(b.z > a.z, "they swapped places");
    }
}
