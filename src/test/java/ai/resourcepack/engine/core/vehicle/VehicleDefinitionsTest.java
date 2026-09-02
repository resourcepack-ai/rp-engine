package ai.resourcepack.engine.core.vehicle;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.api.VehicleInfo;
import ai.resourcepack.engine.api.VehicleMedium;
import ai.resourcepack.engine.api.VehicleSeat;
import ai.resourcepack.engine.core.content.ContentFolderLoader;
import ai.resourcepack.engine.core.registry.ContentRegistryImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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

    /**
     * The rule the whole feature rests on, and the one studio's editor mirrors:
     * a vehicle nobody can steer is not a vehicle, so it does not load at all.
     */
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
}
