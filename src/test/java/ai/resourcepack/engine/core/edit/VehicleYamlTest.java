package ai.resourcepack.engine.core.edit;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.api.VehicleEmitter;
import ai.resourcepack.engine.api.VehicleInfo;
import ai.resourcepack.engine.api.VehicleSeat;
import ai.resourcepack.engine.api.VehicleState;
import ai.resourcepack.engine.core.content.ContentFolderLoader;
import ai.resourcepack.engine.core.registry.ContentRegistryImpl;
import ai.resourcepack.engine.core.vehicle.VehicleDefinitions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A vehicle survives a trip through the editor and back into its own file.
 *
 * <p>This is the test the whole vehicle half of the feature rests on, and it
 * is written as a round trip rather than as a string comparison on purpose:
 * what has to be true is not that the YAML looks a particular way, it is that
 * <strong>parsing what was written gives back the vehicle that was
 * written</strong>. A string assertion would pin the formatting and miss a
 * field that was dropped.
 */
class VehicleYamlTest {

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

    private VehicleInfo parse(String id) {
        LoadReport loaded = new ContentFolderLoader(new ContentRegistryImpl())
                .load(content, ContentSource.AUTHORED);
        return VehicleDefinitions.parse(loaded).vehicles().get(ContentId.parse(id).orElseThrow());
    }

    /** Writes the vehicle back through the editor's own conversion and reparses. */
    private VehicleInfo roundTrip(VehicleInfo original) throws IOException {
        EditWire.Vehicle wire = EditTargets.wire(original);
        String block = VehicleYaml.write(original.id().path(),
                original.model().map(ContentId::toString).orElse(null), wire);
        String file = Files.readString(content.resolve("mypack/vehicles/cars.yml"), StandardCharsets.UTF_8);
        String updated = YamlBlocks.replace(file, original.id().path(), block);
        assertNotNull(updated, "the entry should still be findable");
        write("mypack/vehicles/cars.yml", updated);
        return parse(original.id().toString());
    }

    private static void assertSame(VehicleInfo a, VehicleInfo b) {
        assertEquals(a.model(), b.model());
        assertEquals(a.name(), b.name());
        assertEquals(a.medium(), b.medium());
        assertEquals(a.weight(), b.weight(), 0.001);
        assertEquals(a.speed(), b.speed(), 0.001);
        assertEquals(a.acceleration(), b.acceleration(), 0.001);
        assertEquals(a.turnSpeed(), b.turnSpeed(), 0.001);
        assertEquals(a.scale(), b.scale(), 0.001);
        assertEquals(a.flight().takeoffSpeed(), b.flight().takeoffSpeed(), 0.001);
        assertEquals(a.flight().climbRate(), b.flight().climbRate(), 0.001);
        assertEquals(a.flight().diveRate(), b.flight().diveRate(), 0.001);
        assertEquals(a.flight().stallSink(), b.flight().stallSink(), 0.001);
        assertEquals(a.hitbox().width(), b.hitbox().width(), 0.001);
        assertEquals(a.hitbox().height(), b.hitbox().height(), 0.001);
        assertEquals(a.hitbox().length(), b.hitbox().length(), 0.001);
        assertEquals(a.animations(), b.animations());
        assertEquals(a.seats().size(), b.seats().size());
        for (int i = 0; i < a.seats().size(); i++) {
            VehicleSeat one = a.seats().get(i);
            VehicleSeat two = b.seats().get(i);
            assertEquals(one.role(), two.role(), "seat " + i + " role");
            assertEquals(one.pose(), two.pose(), "seat " + i + " pose");
            assertEquals(one.x(), two.x(), 0.001, "seat " + i + " x");
            assertEquals(one.y(), two.y(), 0.001, "seat " + i + " y");
            assertEquals(one.z(), two.z(), 0.001, "seat " + i + " z");
            assertEquals(one.yaw(), two.yaw(), 0.001, "seat " + i + " yaw");
            assertEquals(one.name(), two.name(), "seat " + i + " name");
            assertEquals(one.hidden(), two.hidden(), "seat " + i + " hidden");
            assertEquals(one.animations(), two.animations(), "seat " + i + " animations");
        }
        assertEquals(a.emitters(), b.emitters());
    }

    @Test
    void aWholeVehicleSurvivesTheRoundTrip() throws IOException {
        write("mypack/vehicles/cars.yml", String.join("\n",
                "hatchback:",
                "  model: mypack:hatchback",
                "  name: \"&bHatchback\"",
                "  medium: land",
                "  speed: 18",
                "  acceleration: 7.5",
                "  turn-speed: 140",
                "  weight: 14",
                "  hitbox: {width: 1.4, height: 1.2, length: 3.0}",
                "  seats:",
                "    - {role: driver, x: -0.4, y: 0.6, z: 0.6}",
                "    - {role: passenger, x: 0.4, y: 0.6, z: -0.5, yaw: 180, pose: standing, name: \"Gunner\"}",
                "  animations:",
                "    idle: parked",
                "    moving: drive",
                "  particles:",
                "    - effect: smoke",
                "      states: [moving, reversing]",
                "      x: 0",
                "      y: 0.3",
                "      z: -1.5",
                "      count: 2",
                "      interval: 2",
                "      spread: 0.1",
                "      speed: 0.05",
                "      size: 1.5",
                "      color: \"#ff8800\"",
                ""));

        VehicleInfo original = parse("mypack:hatchback");
        assertNotNull(original);
        assertSame(original, roundTrip(original));
    }

    /**
     * Twice, because the second pass is what would catch a value that drifts —
     * a number requoted, a default written back as something else. Writing a
     * file that changes on every save is how a content folder ends up with a
     * diff nobody can explain.
     */
    @Test
    void writingItTwiceProducesTheSameFile() throws IOException {
        write("mypack/vehicles/cars.yml", String.join("\n",
                "bus:",
                "  model: mypack:bus",
                "  medium: land",
                "  seats:",
                "    - {role: driver, y: 0.6}",
                "    - {role: passenger, x: 0.5, y: 0.6, z: -1}",
                ""));

        VehicleInfo original = parse("mypack:bus");
        String first = VehicleYaml.write("bus", "mypack:bus", EditTargets.wire(original));
        VehicleInfo once = roundTrip(original);
        String second = VehicleYaml.write("bus", "mypack:bus", EditTargets.wire(once));
        assertEquals(first, second);
    }

    /**
     * A seat that dresses its occupant cannot be a one-line flow map, because
     * its animations are a nested map. The expanded form still has to parse
     * back to the same seat.
     */
    @Test
    void aSeatWithAnimationsExpandsAndStillParses() throws IOException {
        write("mypack/vehicles/cars.yml", String.join("\n",
                "kart:",
                "  model: mypack:kart",
                "  seats:",
                "    - role: driver",
                "      y: 0.4",
                "      yaw: 45",
                "      animations:",
                "        idle: mypack:lean",
                "        moving: mypack:steer",
                ""));

        VehicleInfo original = parse("mypack:kart");
        VehicleInfo back = roundTrip(original);
        assertSame(original, back);
        assertEquals("mypack:steer", back.seats().get(0).animations().get(VehicleState.MOVING));
    }

    /** Defaults are not restated: a file full of them is harder to read. */
    @Test
    void unsetOptionalsAreLeftOut() throws IOException {
        write("mypack/vehicles/cars.yml", String.join("\n",
                "cart:",
                "  model: mypack:cart",
                "  seats:",
                "    - {role: driver, y: 0.5}",
                ""));

        VehicleInfo original = parse("mypack:cart");
        String block = VehicleYaml.write("cart", "mypack:cart", EditTargets.wire(original));
        assertTrue(block.contains("- {role: driver, x: 0, y: 0.5, z: 0}"), block);
        assertTrue(!block.contains("yaw:"), "an unturned seat says nothing about yaw");
        assertTrue(!block.contains("pose:"), "a sitting seat says nothing about pose");
        assertTrue(!block.contains("hidden:"), "a drawn occupant says nothing about hidden");
        assertTrue(!block.contains("scale:"), "a vehicle at its built size says nothing about scale");
        assertTrue(!block.contains("flight:"), "a land vehicle has no flight block");
        assertTrue(!block.contains("particles:"), "no emitters, no list");
    }

    /**
     * The three things a push manifest learned after the wire did: how big it
     * is drawn, how it flies, and a seat that draws nobody. Each is the quiet
     * kind of loss — a bus that comes home at 1x, an aeroplane that comes home
     * on speed-derived defaults, a hidden driver who comes home visible — so
     * each is pinned by the round trip.
     */
    @Test
    void anAircraftKeepsItsScaleFlightAndHiddenSeat() throws IOException {
        write("mypack/vehicles/cars.yml", String.join("\n",
                "biplane:",
                "  model: mypack:biplane",
                "  medium: air",
                "  speed: 30",
                "  scale: 2.5",
                "  flight:",
                "    takeoff-speed: 12",
                "    climb-rate: 7",
                "    dive-rate: 14",
                "    stall-sink: 6",
                "  seats:",
                "    - {role: driver, y: 0.9, hidden: true}",
                "    - {role: passenger, y: 0.9, z: -1}",
                ""));

        VehicleInfo original = parse("mypack:biplane");
        assertNotNull(original);
        assertEquals(2.5, original.scale(), 0.001);
        assertEquals(12, original.flight().takeoffSpeed(), 0.001);
        assertTrue(original.seats().get(0).hidden());

        String block = VehicleYaml.write("biplane", "mypack:biplane", EditTargets.wire(original));
        assertTrue(block.contains("scale: 2.5"), block);
        assertTrue(block.contains("takeoff-speed: 12"), block);
        assertTrue(block.contains("hidden: true"), block);

        VehicleInfo back = roundTrip(original);
        assertSame(original, back);
        assertTrue(back.seats().get(0).hidden());
        assertTrue(!back.seats().get(1).hidden());
    }

    /** A car never gains a flight block, however the wire's aircraft fields read. */
    @Test
    void aCarWritesNoFlightBlock() throws IOException {
        write("mypack/vehicles/cars.yml", String.join("\n",
                "coupe:",
                "  model: mypack:coupe",
                "  medium: land",
                "  seats:",
                "    - {role: driver, y: 0.5}",
                ""));

        EditWire.Vehicle wire = EditTargets.wire(parse("mypack:coupe"));
        assertTrue(wire.takeoffSpeed == null, "a car carries no flight numbers");
        String block = VehicleYaml.write("coupe", "mypack:coupe", wire);
        assertTrue(!block.contains("flight:"), block);
    }

    /** A number that a double would otherwise render as 0.30000000000000004. */
    @Test
    void numbersAreWrittenAsPeopleWriteThem() {
        assertEquals("0", VehicleYaml.number(0));
        assertEquals("18", VehicleYaml.number(18.0));
        assertEquals("7.5", VehicleYaml.number(7.5));
        assertEquals("-0.4", VehicleYaml.number(0.1 + 0.1 - 0.6));
        assertEquals("0.3", VehicleYaml.number(0.1 + 0.2));
    }

    /** A colour code at the front of a name is exactly what needs the quotes. */
    @Test
    void stringsAreQuoted() {
        assertEquals("\"&bHatchback\"", VehicleYaml.quote("&bHatchback"));
        assertEquals("\"a \\\"b\\\" c\"", VehicleYaml.quote("a \"b\" c"));
    }

    /** An emitter's colour is a number on the wire and a hex string in the file. */
    @Test
    void anEmitterColourSurvivesBothSpellings() throws IOException {
        write("mypack/vehicles/cars.yml", String.join("\n",
                "van:",
                "  model: mypack:van",
                "  seats:",
                "    - {role: driver, y: 0.6}",
                "  particles:",
                "    - effect: dust",
                "      states: [moving]",
                "      color: \"#3670f8\"",
                ""));

        VehicleInfo original = parse("mypack:van");
        VehicleEmitter emitter = original.emitters().get(0);
        assertEquals(0x3670f8, emitter.color().orElseThrow());
        assertEquals(emitter, roundTrip(original).emitters().get(0));
    }
}
