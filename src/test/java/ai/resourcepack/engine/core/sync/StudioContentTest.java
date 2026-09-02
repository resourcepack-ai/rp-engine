package ai.resourcepack.engine.core.sync;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.OverlayInfo;
import ai.resourcepack.engine.api.SoundInfo;
import ai.resourcepack.engine.api.VehicleEmitter;
import ai.resourcepack.engine.api.VehicleInfo;
import ai.resourcepack.engine.api.VehicleMedium;
import ai.resourcepack.engine.api.VehicleSeat;
import ai.resourcepack.engine.api.VehicleState;
import ai.resourcepack.engine.core.registry.ContentRegistryImpl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The manifest that makes a pushed pack nameable.
 *
 * <p>Its shape is written by Studio's content-manifest writer, so the JSON in
 * here is what that emits rather than what would be convenient.
 */
class StudioContentTest {

    private static final Logger LOG = Logger.getLogger(StudioContentTest.class.getName());

    private static final String MANIFEST = """
            {"packId":"ian8vezm",
             "sounds":[{"id":"laser","event":"custom.laser","category":"player"}],
             "screens":[{"id":"shop","title":"\\u0001\\ue001","container":"chest_9x6","slot":""}],
             "huds":[{"id":"mana","title":"\\u0002\\ue002","container":"","slot":"boss_bar"}]}
            """;

    private static StudioContent read(Path dir, String json) {
        StudioContent content = new StudioContent(dir.toFile());
        assertTrue(content.updateFromJson(json).ok(), "manifest did not parse");
        return content;
    }

    @Test
    void aSoundKeepsItsEventSeparateFromItsId(@TempDir Path dir) {
        SoundInfo sound = read(dir, MANIFEST).sounds().get(ContentId.parse("studio:laser").orElseThrow());

        // The id is ours and the event is studio's, and conflating them is
        // exactly the bug this class exists to avoid: studio's events live in
        // the minecraft namespace, which nothing here may claim.
        assertEquals("studio:laser", sound.id().toString());
        assertEquals("custom.laser", sound.event());
        assertEquals("player", sound.category());
    }

    @Test
    void aScreenCarriesItsWholeTitleRatherThanAnOffset(@TempDir Path dir) {
        OverlayInfo screen = read(dir, MANIFEST).screens().get(ContentId.parse("studio:shop").orElseThrow());

        assertEquals("chest_9x6", screen.container());
        // The whole run of characters that draws it, verbatim: two here, a
        // negative-space one and the glyph.
        assertEquals(2, screen.title().length());
        // Studio allocated the codepoint and did the arithmetic, so ours are
        // deliberately absent rather than guessed at.
        assertEquals(0, screen.codepoint());
        assertEquals(0, screen.offset());
    }

    @Test
    void aHudKeepsItsSlot(@TempDir Path dir) {
        OverlayInfo hud = read(dir, MANIFEST).huds().get(ContentId.parse("studio:mana").orElseThrow());
        assertEquals(OverlayInfo.Slot.BOSS_BAR, hud.slot());
    }

    @Test
    void aPushReplacesTheLastOne(@TempDir Path dir) {
        StudioContent content = read(dir, MANIFEST);
        assertTrue(content.updateFromJson("{\"packId\":\"other\",\"sounds\":[]}").ok());

        // A sound deleted in the editor has to stop being offered, so this
        // replaces rather than merges.
        assertTrue(content.sounds().isEmpty());
        assertTrue(content.isEmpty());
    }

    @Test
    void everythingLandsInTheRegistryUnderOneNamespace(@TempDir Path dir) {
        ContentRegistryImpl registry = new ContentRegistryImpl();
        read(dir, MANIFEST).register(registry, LOG);

        assertEquals(java.util.Set.of(StudioContent.NAMESPACE), registry.namespaces());
        assertTrue(registry.contains(ContentId.parse("studio:laser").orElseThrow(), ContentKind.SOUND));
        assertTrue(registry.contains(ContentId.parse("studio:shop").orElseThrow(), ContentKind.SCREEN));
        assertTrue(registry.contains(ContentId.parse("studio:mana").orElseThrow(), ContentKind.HUD));
    }

    @Test
    void registeringTwiceReplacesRatherThanCollides(@TempDir Path dir) {
        ContentRegistryImpl registry = new ContentRegistryImpl();
        StudioContent content = read(dir, MANIFEST);
        content.register(registry, LOG);
        content.register(registry, LOG);

        // A reload re-registers, and a namespace already claimed would
        // otherwise refuse — leaving the pack somebody is wearing unnameable.
        assertTrue(registry.contains(ContentId.parse("studio:laser").orElseThrow()));
    }

    @Test
    void itSurvivesARestart(@TempDir Path dir) {
        StudioContent content = read(dir, MANIFEST);
        String title = content.screens().get(ContentId.parse("studio:shop").orElseThrow()).title();
        content.save(LOG);
        assertTrue(new File(dir.toFile(), "studio-content.json").isFile());

        StudioContent reloaded = new StudioContent(dir.toFile());
        reloaded.load(LOG);

        SoundInfo sound = reloaded.sounds().get(ContentId.parse("studio:laser").orElseThrow());
        assertEquals("custom.laser", sound.event());
        assertEquals("",
                reloaded.screens().get(ContentId.parse("studio:shop").orElseThrow()).title());
        assertEquals(OverlayInfo.Slot.BOSS_BAR,
                reloaded.huds().get(ContentId.parse("studio:mana").orElseThrow()).slot());
    }

    @Test
    void aBadManifestIsRefusedRatherThanEmptying(@TempDir Path dir) {
        StudioContent content = read(dir, MANIFEST);
        assertFalse(content.updateFromJson("not json at all").ok());
        assertFalse(content.updateFromJson("").ok());
        assertFalse(content.sounds().isEmpty(), "a refused manifest threw away the last good one");
    }

    @Test
    void anEntryMissingWhatItNeedsIsSkipped(@TempDir Path dir) {
        StudioContent content = new StudioContent(dir.toFile());
        assertTrue(content.updateFromJson("""
                {"sounds":[{"id":"quiet"},{"id":"loud","event":"custom.loud"}],
                 "screens":[{"id":"nope","title":"x"}]}
                """).ok());

        // A sound with no event and a screen with no container name nothing,
        // and the rest of the manifest is still worth having.
        assertEquals(1, content.sounds().size());
        assertTrue(content.screens().isEmpty());
    }

    // --- vehicles -----------------------------------------------------

    /**
     * A vehicle carried by the manifest, in the shape studio's
     * {@code collectVehicles} emits: seat offsets already in BLOCKS, and the
     * art named by a carrier string rather than an item id.
     */
    private static final String WITH_VEHICLE = """
            {"packId":"ian8vezm","sounds":[],"screens":[],"huds":[],
             "vehicles":[{"id":"hatchback","carrier":"hatchback","name":"Hatchback",
               "medium":"land","weight":14,"speed":18,"acceleration":7.5,"turnSpeed":140,
               "seats":[{"role":"passenger","pose":"sitting","x":0.4,"y":0.6,"z":-0.5,"yaw":0,"name":"back"},
                        {"role":"driver","pose":"sitting","x":-0.4,"y":0.6,"z":0.5,"yaw":0,"name":"wheel"},
                        {"role":"passenger","pose":"standing","x":0,"y":1.2,"z":-1.4,"yaw":180,"name":"gunner"}]}]}
            """;

    @Test
    void readsAPushedVehicle(@TempDir Path dir) {
        VehicleInfo car = read(dir, WITH_VEHICLE).vehicles()
                .get(ContentId.parse("studio:hatchback").orElseThrow());
        assertEquals(VehicleMedium.LAND, car.medium());
        assertEquals(18, car.speed());
        assertEquals(140, car.turnSpeed());
        assertEquals(3, car.capacity());
    }

    /**
     * A pushed vehicle names its art with a carrier string, never an item id.
     * A studio pack is a zip with no plugin behind it, so its models borrow
     * paper — there is no item to name.
     */
    @Test
    void aPushedVehicleNamesACarrierRatherThanAnItem(@TempDir Path dir) {
        VehicleInfo car = read(dir, WITH_VEHICLE).vehicles()
                .get(ContentId.parse("studio:hatchback").orElseThrow());
        assertEquals("hatchback", car.carrier().orElseThrow());
        assertTrue(car.model().isEmpty(), "a pushed vehicle has no item to name");
    }

    /**
     * Seat order is the contract on this side too: the driver is moved to the
     * front and everybody else keeps the order the manifest wrote them in.
     */
    @Test
    void putsTheDriverFirstAndKeepsTheRestInOrder(@TempDir Path dir) {
        VehicleInfo car = read(dir, WITH_VEHICLE).vehicles()
                .get(ContentId.parse("studio:hatchback").orElseThrow());
        assertEquals("wheel", car.seats().get(0).name().orElseThrow());
        assertTrue(car.seats().get(0).isDriver());
        assertEquals("back", car.seats().get(1).name().orElseThrow());
        assertEquals("gunner", car.seats().get(2).name().orElseThrow());
        assertEquals(VehicleSeat.Pose.STANDING, car.seats().get(2).pose());
    }

    /**
     * The editor already refuses to call such a model finished and studio
     * filters it out before sending, so one arriving here is a manifest from a
     * version that did not — and a vehicle nobody can steer is worse than no
     * vehicle.
     */
    @Test
    void skipsAVehicleWithNoDriverSeat(@TempDir Path dir) {
        StudioContent content = new StudioContent(dir.toFile());
        assertTrue(content.updateFromJson("""
                {"vehicles":[{"id":"bench","carrier":"bench","medium":"land",
                  "seats":[{"role":"passenger","pose":"sitting","x":0,"y":0.5,"z":0,"yaw":0}]}]}
                """).ok());
        assertTrue(content.vehicles().isEmpty());
    }

    @Test
    void aVehicleIsRegisteredUnderTheStudioNamespace(@TempDir Path dir) {
        StudioContent content = read(dir, WITH_VEHICLE);
        ContentRegistryImpl registry = new ContentRegistryImpl();
        content.register(registry, LOG);
        assertTrue(registry.ids(ContentKind.VEHICLE)
                .contains(ContentId.parse("studio:hatchback").orElseThrow()));
    }

    /**
     * A vehicle somebody parked has to come back after a restart with the same
     * seats in the same order, or the people who get into it end up somewhere
     * else.
     */
    @Test
    void aVehicleSurvivesBeingWrittenOutAndReadBack(@TempDir Path dir) {
        StudioContent written = read(dir, WITH_VEHICLE);
        written.save(LOG);

        StudioContent reloaded = new StudioContent(dir.toFile());
        reloaded.load(LOG);

        VehicleInfo before = written.vehicles().get(ContentId.parse("studio:hatchback").orElseThrow());
        VehicleInfo after = reloaded.vehicles().get(ContentId.parse("studio:hatchback").orElseThrow());
        assertEquals(before.capacity(), after.capacity());
        assertEquals(before.carrier(), after.carrier());
        assertEquals(before.medium(), after.medium());
        assertEquals(before.speed(), after.speed());
        for (int i = 0; i < before.capacity(); i++) {
            assertEquals(before.seats().get(i).name(), after.seats().get(i).name());
            assertEquals(before.seats().get(i).role(), after.seats().get(i).role());
            assertEquals(before.seats().get(i).pose(), after.seats().get(i).pose());
            assertEquals(before.seats().get(i).x(), after.seats().get(i).x());
            assertEquals(before.seats().get(i).y(), after.seats().get(i).y());
            assertEquals(before.seats().get(i).yaw(), after.seats().get(i).yaw());
        }
    }

    /**
     * A manifest from a studio that predates vehicles has no such key, and
     * must not be a parse failure — that would take the sounds and screens
     * down with it.
     */
    @Test
    void aManifestWithNoVehiclesKeyIsFine(@TempDir Path dir) {
        StudioContent content = read(dir, MANIFEST);
        assertTrue(content.vehicles().isEmpty());
        assertFalse(content.sounds().isEmpty());
    }

    // --- animations and particles --------------------------------------

    /** The same vehicle, with the two things a zip of art cannot carry. */
    private static final String WITH_BEHAVIOUR = """
            {"packId":"ian8vezm","sounds":[],"screens":[],"huds":[],
             "vehicles":[{"id":"hatchback","carrier":"hatchback","name":"Hatchback",
               "medium":"land","weight":14,"speed":18,"acceleration":7.5,"turnSpeed":140,
               "animations":{"idle":"parked","moving":"drive"},
               "particles":[{"effect":"SMOKE","states":["moving","reversing"],
                             "x":0,"y":0.4,"z":-1.4,"count":3,"spread":0.1,"speed":0.02,
                             "interval":2,"enabled":true,"color":16746496,"size":1}],
               "seats":[{"role":"driver","pose":"sitting","x":0,"y":0.6,"z":0.5,"yaw":0,"name":"wheel"}]}]}
            """;

    @Test
    void readsAPushedVehiclesAnimationsAndParticles(@TempDir Path dir) {
        VehicleInfo car = read(dir, WITH_BEHAVIOUR).vehicles()
                .get(ContentId.parse("studio:hatchback").orElseThrow());

        assertEquals("parked", car.animations().get(VehicleState.IDLE));
        assertEquals("drive", car.animations().get(VehicleState.MOVING));

        assertEquals(1, car.emitters().size());
        VehicleEmitter exhaust = car.emitters().get(0);
        assertEquals("SMOKE", exhaust.effect());
        assertTrue(exhaust.firesIn(java.util.Set.of(VehicleState.REVERSING)));
        assertFalse(exhaust.firesIn(java.util.Set.of(VehicleState.IDLE)));
        assertEquals(2, exhaust.interval());
        assertEquals(0xff8800, exhaust.color().orElseThrow());
    }

    /**
     * Same rule as the seats above: a vehicle somebody parked has to come back
     * after a restart still smoking from the same place. Round-tripping is
     * what {@code studio-content.json} is for, and an emitter that survives
     * three of its ten fields is one that quietly changes behaviour on every
     * restart.
     */
    @Test
    void animationsAndParticlesSurviveBeingWrittenOutAndReadBack(@TempDir Path dir) {
        StudioContent written = read(dir, WITH_BEHAVIOUR);
        written.save(LOG);

        StudioContent reloaded = new StudioContent(dir.toFile());
        reloaded.load(LOG);

        ContentId id = ContentId.parse("studio:hatchback").orElseThrow();
        VehicleInfo before = written.vehicles().get(id);
        VehicleInfo after = reloaded.vehicles().get(id);

        assertEquals(before.animations(), after.animations());
        // VehicleEmitter's equals covers every field, which is the point —
        // adding one and forgetting to write it out fails here.
        assertEquals(before.emitters(), after.emitters());
    }

    /**
     * Gson leaves an absent primitive at its zero, and zero is the wrong
     * default for three of an emitter's fields. An emitter written by an older
     * studio — no {@code enabled}, no {@code color}, no {@code size} — must
     * come back switched ON, uncoloured, and full size, not invisible and
     * black.
     */
    @Test
    void anEmitterMissingItsOptionalFieldsIsOnAndUncoloured(@TempDir Path dir) {
        StudioContent content = read(dir, """
                {"packId":"p","sounds":[],"screens":[],"huds":[],
                 "vehicles":[{"id":"cart","carrier":"cart","medium":"land",
                   "weight":10,"speed":12,"acceleration":6,"turnSpeed":120,
                   "particles":[{"effect":"FLAME","states":["idle"]}],
                   "seats":[{"role":"driver","pose":"sitting","x":0,"y":0.6,"z":0,"yaw":0,"name":""}]}]}
                """);

        VehicleEmitter emitter = content.vehicles()
                .get(ContentId.parse("studio:cart").orElseThrow()).emitters().get(0);
        assertTrue(emitter.enabled());
        assertTrue(emitter.color().isEmpty());
        assertEquals(1, emitter.size());
        assertEquals(1, emitter.interval());
        assertEquals(1, emitter.count());
    }

    /**
     * A state name this jar does not know is a studio newer than it. The
     * vehicle still works, minus one animation — which is the right end of
     * that, and much better than refusing the vehicle.
     */
    @Test
    void anUnknownStateIsSkippedRatherThanFailingTheVehicle(@TempDir Path dir) {
        StudioContent content = read(dir, """
                {"packId":"p","sounds":[],"screens":[],"huds":[],
                 "vehicles":[{"id":"cart","carrier":"cart","medium":"land",
                   "weight":10,"speed":12,"acceleration":6,"turnSpeed":120,
                   "animations":{"idle":"parked","hovering":"float"},
                   "seats":[{"role":"driver","pose":"sitting","x":0,"y":0.6,"z":0,"yaw":0,"name":""}]}]}
                """);

        VehicleInfo cart = content.vehicles().get(ContentId.parse("studio:cart").orElseThrow());
        assertEquals(1, cart.animations().size());
        assertEquals("parked", cart.animations().get(VehicleState.IDLE));
    }
}
