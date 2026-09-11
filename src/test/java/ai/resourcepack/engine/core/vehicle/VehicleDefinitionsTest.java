package ai.resourcepack.engine.core.vehicle;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.api.VehicleEmitter;
import ai.resourcepack.engine.api.VehicleFlight;
import ai.resourcepack.engine.api.VehicleInfo;
import ai.resourcepack.engine.api.VehicleMedium;
import ai.resourcepack.engine.api.VehicleSeat;
import ai.resourcepack.engine.api.VehicleState;
import ai.resourcepack.engine.core.content.ContentFolderLoader;
import ai.resourcepack.engine.core.registry.ContentRegistryImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VehicleDefinitionsTest {

    @TempDir
    Path content;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(content);
        write("mypack/pack.yml", "{}\n");
    }

    private void write(String path, String text) throws IOException {
        Path file = content.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private VehicleDefinitions.Result parse() {
        LoadReport loaded = new ContentFolderLoader(new ContentRegistryImpl())
                .load(content, ContentSource.AUTHORED);
        return VehicleDefinitions.parse(loaded);
    }

    private VehicleInfo one(String id) {
        return parse().vehicles().get(ContentId.parse(id).orElseThrow());
    }

    private static boolean saysSomethingAbout(VehicleDefinitions.Result result, String fragment) {
        return result.diagnostics().stream().anyMatch(d -> d.message().contains(fragment));
    }

    @Test
    void readsAWholeVehicle() throws IOException {
        write("mypack/vehicles/cars.yml",
                "hatchback:\n"
                        + "  model: mypack:hatchback\n"
                        + "  name: \"&bHatchback\"\n"
                        + "  medium: land\n"
                        + "  weight: 14\n"
                        + "  speed: 18\n"
                        + "  acceleration: 7.5\n"
                        + "  turn-speed: 140\n"
                        + "  seats:\n"
                        + "    - {role: driver, x: -0.4, y: 0.6, z: 0.5}\n"
                        + "    - {role: passenger, x: 0.4, y: 0.6, z: 0.5}\n");

        VehicleInfo car = one("mypack:hatchback");
        assertEquals("mypack:hatchback", car.model().orElseThrow().toString());
        assertEquals(VehicleMedium.LAND, car.medium());
        assertEquals(14, car.weight());
        assertEquals(18, car.speed());
        assertEquals(7.5, car.acceleration());
        assertEquals(140, car.turnSpeed());
        assertEquals(2, car.capacity());
        assertEquals(-0.4, car.driverSeat().x());
        assertEquals(0.5, car.driverSeat().z());
    }

    @Test
    void everythingButTheSeatsHasADefault() throws IOException {
        write("mypack/vehicles/a.yml",
                "cart:\n"
                        + "  seats: [{role: driver}]\n");

        VehicleInfo cart = one("mypack:cart");
        assertEquals(VehicleMedium.LAND, cart.medium());
        assertEquals(1, cart.capacity());
        assertTrue(cart.model().isEmpty());
        // A seat that named no axis sits at the vehicle's own origin rather
        // than being refused: a one-seater writes `y` at most.
        assertEquals(0, cart.driverSeat().x());
    }

    @Test
    void transportsAddonConfigurationWithoutInterpretingIt() throws IOException {
        write("mypack/vehicles/a.yml",
                "car:\n"
                        + "  seats: [{role: driver}]\n"
                        + "  addons:\n"
                        + "    vehicle-status:\n"
                        + "      enabled: true\n"
                        + "      max-health: 250\n"
                        + "      detachable-parts: [hood, left_door]\n"
                        + "      status: {interval-seconds: 2}\n");

        var status = one("mypack:car").addon("vehicle-status").orElseThrow();
        assertTrue(status.bool("enabled").orElseThrow());
        assertEquals(250, status.decimal("max-health").orElseThrow());
        assertEquals(java.util.List.of("hood", "left_door"), status.strings("detachable-parts"));
        assertEquals(2, status.node("status").orElseThrow().decimal("interval-seconds").orElseThrow());
    }

    /**
     * The rule the whole feature rests on, and the one studio's editor mirrors:
     * a vehicle nobody can steer is not a vehicle, so it does not load at all.
     */
    @Test
    void jumpIsOffUnlessAsked() throws IOException {
        write("mypack/vehicles/a.yml",
                "cart:\n"
                        + "  seats: [{role: driver}]\n"
                        + "bike:\n"
                        + "  jump: true\n"
                        + "  seats: [{role: driver}]\n");

        assertEquals(false, one("mypack:cart").jumps());
        assertEquals(true, one("mypack:bike").jumps());
    }

    /**
     * Opt-OUT, unlike every other flag here, and that asymmetry is the point:
     * a dashboard is the ordinary case and a skateboard is the exception, so a
     * pack written before this key existed keeps its readout.
     */
    @Test
    void theSpeedometerIsOnUnlessTheVehicleSaysOtherwise() throws IOException {
        write("mypack/vehicles/a.yml",
                "cart:\n"
                        + "  seats: [{role: driver}]\n"
                        + "board:\n"
                        + "  speedometer: false\n"
                        + "  seats: [{role: driver}]\n");

        assertEquals(true, one("mypack:cart").speedometer());
        assertEquals(false, one("mypack:board").speedometer());
    }

    @Test
    void refusesAVehicleWithNoDriverSeat() throws IOException {
        write("mypack/vehicles/a.yml",
                "bench:\n"
                        + "  seats:\n"
                        + "    - {role: passenger, y: 0.5}\n"
                        + "    - {role: passenger, y: 0.5, x: 0.6}\n");

        VehicleDefinitions.Result result = parse();
        assertTrue(result.vehicles().isEmpty());
        assertTrue(saysSomethingAbout(result, "No driver seat"));
    }

    @Test
    void refusesAVehicleWithNoSeatsAtAll() throws IOException {
        write("mypack/vehicles/a.yml", "brick:\n  medium: land\n");

        VehicleDefinitions.Result result = parse();
        assertTrue(result.vehicles().isEmpty());
        assertTrue(saysSomethingAbout(result, "No seats"));
    }

    /**
     * Seat order is the contract — passenger 1 is the first passenger the pack
     * wrote — so the driver being moved to the front must not disturb the rest.
     */
    @Test
    void putsTheDriverFirstAndKeepsEveryoneElseInOrder() throws IOException {
        write("mypack/vehicles/a.yml",
                "bus:\n"
                        + "  seats:\n"
                        + "    - {role: passenger, name: front}\n"
                        + "    - {role: passenger, name: middle}\n"
                        + "    - {role: driver, name: wheel}\n"
                        + "    - {role: passenger, name: back}\n");

        VehicleInfo bus = one("mypack:bus");
        assertEquals("wheel", bus.seats().get(0).name().orElseThrow());
        assertEquals("front", bus.seats().get(1).name().orElseThrow());
        assertEquals("middle", bus.seats().get(2).name().orElseThrow());
        assertEquals("back", bus.seats().get(3).name().orElseThrow());
        assertTrue(bus.driverSeat().isDriver());
    }

    /**
     * A second driver is seated rather than dropped: refusing it would shrink
     * the vehicle, and "which one steers" has no answer worth guessing at.
     */
    @Test
    void aSecondDriverBecomesAPassenger() throws IOException {
        write("mypack/vehicles/a.yml",
                "tandem:\n"
                        + "  seats:\n"
                        + "    - {role: driver, name: first}\n"
                        + "    - {role: driver, name: second}\n");

        VehicleDefinitions.Result result = parse();
        VehicleInfo tandem = result.vehicles().get(ContentId.parse("mypack:tandem").orElseThrow());
        assertEquals(2, tandem.capacity());
        assertEquals("first", tandem.seats().get(0).name().orElseThrow());
        assertFalse(tandem.seats().get(1).isDriver());
        assertTrue(saysSomethingAbout(result, "More than one driver seat"));
    }

    @Test
    void dropsSeatsPastTheLimitAndSaysSo() throws IOException {
        StringBuilder yaml = new StringBuilder("coach:\n  seats:\n    - {role: driver}\n");
        for (int i = 0; i < VehicleDefinitions.MAX_SEATS; i++) {
            yaml.append("    - {role: passenger}\n");
        }
        write("mypack/vehicles/a.yml", yaml.toString());

        VehicleDefinitions.Result result = parse();
        VehicleInfo coach = result.vehicles().get(ContentId.parse("mypack:coach").orElseThrow());
        assertEquals(VehicleDefinitions.MAX_SEATS, coach.capacity());
        assertTrue(saysSomethingAbout(result, "carries at most"));
    }

    /**
     * A number with an obvious nearest legal value is clamped rather than
     * taking the whole vehicle down with it.
     */
    @Test
    void clampsAnOutOfRangeNumber() throws IOException {
        write("mypack/vehicles/a.yml",
                "rocket:\n"
                        + "  speed: 900\n"
                        + "  seats: [{role: driver}]\n");

        VehicleDefinitions.Result result = parse();
        VehicleInfo rocket = result.vehicles().get(ContentId.parse("mypack:rocket").orElseThrow());
        assertEquals(60, rocket.speed());
        assertTrue(saysSomethingAbout(result, "is outside"));
    }

    @Test
    void refusesAMediumItDoesNotHave() throws IOException {
        write("mypack/vehicles/a.yml",
                "submarine:\n"
                        + "  medium: underwater\n"
                        + "  seats: [{role: driver}]\n");

        VehicleDefinitions.Result result = parse();
        assertTrue(result.vehicles().isEmpty());
        assertTrue(saysSomethingAbout(result, "not land, water or air"));
    }

    @Test
    void refusesAModelThatIsNotAnId() throws IOException {
        write("mypack/vehicles/a.yml",
                "car:\n"
                        + "  model: Not An Id\n"
                        + "  seats: [{role: driver}]\n");

        VehicleDefinitions.Result result = parse();
        assertTrue(result.vehicles().isEmpty());
        assertTrue(saysSomethingAbout(result, "is not a namespace:id"));
    }

    @Test
    void readsAPoseAndWrapsAYaw() throws IOException {
        write("mypack/vehicles/a.yml",
                "ferry:\n"
                        + "  medium: water\n"
                        + "  seats:\n"
                        + "    - {role: driver}\n"
                        + "    - {role: passenger, pose: standing, yaw: -90}\n");

        VehicleInfo ferry = one("mypack:ferry");
        assertEquals(VehicleMedium.WATER, ferry.medium());
        VehicleSeat deck = ferry.seats().get(1);
        assertEquals(VehicleSeat.Pose.STANDING, deck.pose());
        // Wrapped, not clamped: -90 and 270 are the same direction, and a
        // clamp would turn a small turn anticlockwise into a hard stop at 0.
        assertEquals(270f, deck.yaw());
    }

    @Test
    void aSeatMilesAwayFallsBackToTheOriginWithAWarning() throws IOException {
        write("mypack/vehicles/a.yml",
                "car:\n"
                        + "  seats: [{role: driver, x: 4000}]\n");

        VehicleDefinitions.Result result = parse();
        VehicleInfo car = result.vehicles().get(ContentId.parse("mypack:car").orElseThrow());
        assertEquals(0, car.driverSeat().x());
        assertTrue(saysSomethingAbout(result, "blocks from the vehicle"));
    }

    @Test
    void nothingLoadedIsNoVehiclesRatherThanACrash() {
        VehicleDefinitions.Result result = VehicleDefinitions.parse(null);
        assertTrue(result.vehicles().isEmpty());
        assertTrue(result.diagnostics().isEmpty());
    }

    /**
     * Every one of these appears on somebody's console with nothing else to go
     * on, so a diagnostic that cannot be traced back to a file is a diagnostic
     * nobody can act on.
     */
    @Test
    void everyDiagnosticNamesItsFile() throws IOException {
        write("mypack/vehicles/broken.yml", "bench:\n  seats: [{role: passenger}]\n");

        VehicleDefinitions.Result result = parse();
        assertFalse(result.diagnostics().isEmpty());
        for (Diagnostic diagnostic : result.diagnostics()) {
            assertFalse(diagnostic.origin().isEmpty(), "diagnostic with no origin: " + diagnostic);
        }
    }

    // --- animations ----------------------------------------------------

    /** The minimum a driver's file needs: a seat, and two states. */
    private static final String DRIVER = "  seats: [{role: driver, y: 0.6}]\n";

    @Test
    void readsTheAnimationForEachState() throws IOException {
        write("mypack/vehicles/cars.yml",
                "hatchback:\n"
                        + "  model: mypack:hatchback\n"
                        + "  animations:\n"
                        + "    idle: parked\n"
                        + "    moving: drive\n"
                        + "    turning: lean\n"
                        + DRIVER);

        VehicleInfo car = one("mypack:hatchback");
        assertEquals("parked", car.animations().get(VehicleState.IDLE));
        assertEquals("drive", car.animations().get(VehicleState.MOVING));
        assertEquals("lean", car.animations().get(VehicleState.TURNING));
        assertNull(car.animations().get(VehicleState.AIRBORNE));
    }

    /**
     * A misspelt state is one lost animation, not a lost vehicle. Taking the
     * seats and the handling down over "movnig" would be out of all
     * proportion — but it has to SAY so, or the state silently does nothing.
     */
    @Test
    void anUnknownStateIsAWarningAndTheRestStillLoads() throws IOException {
        write("mypack/vehicles/cars.yml",
                "hatchback:\n"
                        + "  model: mypack:hatchback\n"
                        + "  animations:\n"
                        + "    movnig: drive\n"
                        + "    idle: parked\n"
                        + DRIVER);

        VehicleDefinitions.Result result = parse();
        assertTrue(saysSomethingAbout(result, "movnig"));
        VehicleInfo car = result.vehicles().get(ContentId.parse("mypack:hatchback").orElseThrow());
        assertEquals("parked", car.animations().get(VehicleState.IDLE));
    }

    @Test
    void aVehicleWithNoAnimationsHasNone() throws IOException {
        write("mypack/vehicles/cars.yml", "hatchback:\n  model: mypack:hatchback\n" + DRIVER);
        assertTrue(one("mypack:hatchback").animations().isEmpty());
    }

    // --- particles -----------------------------------------------------

    @Test
    void readsAWholeParticleEmitter() throws IOException {
        write("mypack/vehicles/cars.yml",
                "hatchback:\n"
                        + "  model: mypack:hatchback\n"
                        + "  particles:\n"
                        + "    - effect: smoke\n"
                        + "      states: [moving, reversing]\n"
                        + "      x: 0.2\n"
                        + "      y: 0.4\n"
                        + "      z: -1.4\n"
                        + "      count: 3\n"
                        + "      spread: 0.1\n"
                        + "      speed: 0.02\n"
                        + "      interval: 2\n"
                        + "      color: \"#ff8800\"\n"
                        + DRIVER);

        VehicleInfo car = one("mypack:hatchback");
        assertEquals(1, car.emitters().size());
        VehicleEmitter exhaust = car.emitters().get(0);
        // Uppercased on the way out, because that is how the enum spells it.
        assertEquals("SMOKE", exhaust.effect());
        assertEquals(Set.of(VehicleState.MOVING, VehicleState.REVERSING), exhaust.states());
        assertEquals(0.2, exhaust.x(), 1e-9);
        assertEquals(0.4, exhaust.y(), 1e-9);
        assertEquals(-1.4, exhaust.z(), 1e-9);
        assertEquals(3, exhaust.count());
        assertEquals(2, exhaust.interval());
        assertEquals(0xff8800, exhaust.color().orElseThrow());
        // Absent means on: an emitter somebody wrote out in full is one they
        // want, and defaulting it off would be invisible and unexplained.
        assertTrue(exhaust.enabled());
    }

    /**
     * Its own switch rather than deleting the emitter, because turning one
     * plume off while tuning the others is the ordinary thing an author does
     * and losing its settings to do it is not.
     */
    @Test
    void anEmitterCanBeSwitchedOffWithoutLosingIt() throws IOException {
        write("mypack/vehicles/cars.yml",
                "hatchback:\n"
                        + "  model: mypack:hatchback\n"
                        + "  particles:\n"
                        + "    - {effect: smoke, states: [moving], enabled: false}\n"
                        + DRIVER);

        VehicleEmitter off = one("mypack:hatchback").emitters().get(0);
        assertFalse(off.enabled());
        assertFalse(off.firesIn(Set.of(VehicleState.MOVING)));
        assertEquals("SMOKE", off.effect());
    }

    /**
     * Not a silent default of "always". Guessing would turn a typo in the one
     * state they wrote into a particle storm they did not ask for.
     */
    @Test
    void anEmitterWithNoStatesIsDroppedWithAReason() throws IOException {
        write("mypack/vehicles/cars.yml",
                "hatchback:\n"
                        + "  model: mypack:hatchback\n"
                        + "  particles: [{effect: smoke}]\n"
                        + DRIVER);

        VehicleDefinitions.Result result = parse();
        assertTrue(saysSomethingAbout(result, "never fires"));
        assertTrue(result.vehicles().get(ContentId.parse("mypack:hatchback").orElseThrow())
                .emitters().isEmpty());
    }

    /** A count of 500 is somebody being optimistic, and has a nearest legal value. */
    @Test
    void anAbsurdCountIsClampedRatherThanRefused() throws IOException {
        write("mypack/vehicles/cars.yml",
                "hatchback:\n"
                        + "  model: mypack:hatchback\n"
                        + "  particles: [{effect: smoke, states: [moving], count: 500}]\n"
                        + DRIVER);

        assertEquals(VehicleEmitter.MAX_COUNT, one("mypack:hatchback").emitters().get(0).count());
    }

    // --- flight ---------------------------------------------------------

    @Test
    void readsHowAnAircraftFlies() throws IOException {
        write("mypack/vehicles/planes.yml",
                "cessna:\n"
                        + "  model: mypack:cessna\n"
                        + "  medium: air\n"
                        + "  speed: 30\n"
                        + "  flight:\n"
                        + "    takeoff-speed: 12\n"
                        + "    climb-rate: 7\n"
                        + "    dive-rate: 14\n"
                        + "    stall-sink: 6\n"
                        + DRIVER);

        VehicleFlight flight = one("mypack:cessna").flight();
        assertEquals(12, flight.takeoffSpeed());
        assertEquals(7, flight.climbRate());
        assertEquals(14, flight.diveRate());
        assertEquals(6, flight.stallSink());
        assertTrue(flight.needsTakeoffRun());
    }

    /**
     * The compatibility rule, and the one worth a test: an air vehicle written
     * before these numbers existed must keep flying exactly as it did — off the
     * ground from a standstill, climbing at half its top speed.
     */
    @Test
    void anAircraftThatSaysNothingAboutFlightHovers() throws IOException {
        write("mypack/vehicles/planes.yml",
                "saucer:\n"
                        + "  model: mypack:saucer\n"
                        + "  medium: air\n"
                        + "  speed: 24\n"
                        + DRIVER);

        VehicleFlight flight = one("mypack:saucer").flight();
        assertEquals(0, flight.takeoffSpeed());
        assertFalse(flight.needsTakeoffRun());
        assertEquals(12, flight.climbRate());
        assertEquals(0, flight.stallSink());
    }

    /** Each field defaults on its own, so writing one keeps the rest. */
    @Test
    void aPartialFlightBlockKeepsTheOldDefaultsForTheRest() throws IOException {
        write("mypack/vehicles/planes.yml",
                "cessna:\n"
                        + "  model: mypack:cessna\n"
                        + "  medium: air\n"
                        + "  speed: 24\n"
                        + "  flight: {takeoff-speed: 10}\n"
                        + DRIVER);

        VehicleFlight flight = one("mypack:cessna").flight();
        assertEquals(10, flight.takeoffSpeed());
        assertEquals(12, flight.climbRate());
    }

    /** Legal numbers, and an aircraft that can never leave the ground. */
    @Test
    void aTakeoffSpeedAboveTheTopSpeedIsReported() throws IOException {
        write("mypack/vehicles/planes.yml",
                "brick:\n"
                        + "  model: mypack:brick\n"
                        + "  medium: air\n"
                        + "  speed: 10\n"
                        + "  flight: {takeoff-speed: 20}\n"
                        + DRIVER);

        assertTrue(saysSomethingAbout(parse(), "can never take off"));
    }

    @Test
    void flightOnALandVehicleSaysItDoesNothing() throws IOException {
        write("mypack/vehicles/cars.yml",
                "hatchback:\n"
                        + "  model: mypack:hatchback\n"
                        + "  flight: {takeoff-speed: 8}\n"
                        + DRIVER);

        assertTrue(saysSomethingAbout(parse(), "only read by medium: air"));
    }

    // --- hidden seats ----------------------------------------------------

    @Test
    void aSeatCanHideItsOccupant() throws IOException {
        write("mypack/vehicles/tanks.yml",
                "tank:\n"
                        + "  model: mypack:tank\n"
                        + "  seats:\n"
                        + "    - {role: driver, y: 0.6, hidden: true}\n"
                        + "    - {role: passenger, y: 0.6}\n");

        VehicleInfo tank = one("mypack:tank");
        assertTrue(tank.driverSeat().hidden());
        assertFalse(tank.seats().get(1).hidden(), "a seat that says nothing draws its rider");
    }

    /** One bad exhaust pipe is not a reason to lose a bus. */
    @Test
    void oneUnusableEmitterDoesNotTakeTheOthersWithIt() throws IOException {
        write("mypack/vehicles/cars.yml",
                "hatchback:\n"
                        + "  model: mypack:hatchback\n"
                        + "  particles:\n"
                        + "    - {effect: smoke, states: [moving]}\n"
                        + "    - {states: [idle]}\n"
                        + "    - {effect: flame, states: [idle]}\n"
                        + DRIVER);

        VehicleInfo car = one("mypack:hatchback");
        assertEquals(2, car.emitters().size());
        assertEquals("SMOKE", car.emitters().get(0).effect());
        assertEquals("FLAME", car.emitters().get(1).effect());
    }
}
