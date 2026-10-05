package ai.resourcepack.engine.core.model;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.ModelInfo;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.core.content.ContentFolderLoader;
import ai.resourcepack.engine.core.item.ItemDefinitions;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Placed model is a property of an item rather than a thing beside one, because
 * an id is unique across the whole registry and {@code mypack:chair} cannot be
 * both. These tests are as much about that decision as about the parsing.
 */
class ModelDefinitionsTest {

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

    /** An item definition with whatever place block the test needs. */
    private void chair(String placeableBlock) throws IOException {
        write("mypack/items/a.yml",
                "chair:\n  material: PAPER\n  model: chair\n" + placeableBlock);
    }

    private ModelDefinitions.Result parse() {
        LoadReport loaded = new ContentFolderLoader(new ContentRegistryImpl())
                .load(content, ContentSource.AUTHORED);
        return ModelDefinitions.parse(loaded, ItemDefinitions.parse(loaded).items());
    }

    private static ModelInfo one(ModelDefinitions.Result result, String id) {
        return result.model().get(ContentId.parse(id).orElseThrow());
    }

    @Test
    void readsAPlacedModel() throws IOException {
        chair("  place:\n    facing: diagonal\n    scale: 1.5\n"
                + "    width: 0.9\n    height: 1.2\n    solid: true\n");

        ModelInfo model = one(parse(), "mypack:chair");

        assertEquals(ModelInfo.Facing.DIAGONAL, model.facing());
        assertEquals(1.5f, model.scale());
        assertEquals(0.9f, model.width());
        assertEquals(1.2f, model.height());
        assertTrue(model.solid());
    }

    // ---- light, surface and drops ---------------------------------------

    @Test
    void aPieceCanGiveOffLightAndSayWhatItSticksTo() throws IOException {
        chair("  place:\n    light: 14\n    surface: wall\n    drop: mypack:shard\n");

        ModelInfo model = one(parse(), "mypack:chair");

        assertEquals(14, model.light());
        assertEquals(ModelInfo.Surface.WALL, model.surface());
        assertEquals("mypack:shard", model.drop().orElseThrow().toString());
    }

    @Test
    void aPieceUsuallySaysNoneOfThat() throws IOException {
        chair("  place: {}\n");

        ModelInfo model = one(parse(), "mypack:chair");

        assertEquals(0, model.light(), "no light block is placed");
        assertEquals(ModelInfo.Surface.FLOOR, model.surface());
        assertTrue(model.drop().isEmpty(), "it gives back the item it was placed from");
    }

    @Test
    void aLightOutsideTheRangeIsClampedRatherThanRefused() throws IOException {
        chair("  place:\n    light: 40\n");

        assertEquals(15, one(parse(), "mypack:chair").light());
    }

    @Test
    void aSurfaceNobodyRecognisesLeavesItOnTheFloor() throws IOException {
        chair("  place:\n    surface: sideways\n");

        assertEquals(ModelInfo.Surface.FLOOR, one(parse(), "mypack:chair").surface());
    }

    @Test
    void aSurfaceDecidesWhichFaceAPlacementIsAllowedAgainst() {
        // The rule the placement listener asks. A torch goes on a wall and a
        // chandelier under a ceiling, and getting this backwards is somebody's
        // lamp stuck to the underside of a floor.
        assertTrue(ModelInfo.Surface.FLOOR.accepts(org.bukkit.block.BlockFace.UP));
        assertFalse(ModelInfo.Surface.FLOOR.accepts(org.bukkit.block.BlockFace.NORTH));

        assertTrue(ModelInfo.Surface.WALL.accepts(org.bukkit.block.BlockFace.NORTH));
        assertFalse(ModelInfo.Surface.WALL.accepts(org.bukkit.block.BlockFace.UP));
        assertFalse(ModelInfo.Surface.WALL.accepts(org.bukkit.block.BlockFace.DOWN));

        assertTrue(ModelInfo.Surface.CEILING.accepts(org.bukkit.block.BlockFace.DOWN));
        assertFalse(ModelInfo.Surface.CEILING.accepts(org.bukkit.block.BlockFace.UP));

        assertTrue(ModelInfo.Surface.ANY.accepts(org.bukkit.block.BlockFace.DOWN));
        assertTrue(ModelInfo.Surface.ANY.accepts(org.bukkit.block.BlockFace.UP));
    }

    @Test
    void theItemAndThePlacedModelAreTheSameId() throws IOException {
        chair("  place: {}\n");

        ModelInfo model = one(parse(), "mypack:chair");

        // One chair, one id. They cannot disagree about which model to use
        // because there is only one of each.
        assertEquals(model.id(), model.item());
    }

    @Test
    void anItemWithNoPlaceableBlockCannotBePlaced() throws IOException {
        chair("");

        assertTrue(parse().model().isEmpty());
    }

    @Test
    void theDefaultsAreAWholeBlockFacingCardinal() throws IOException {
        chair("  place: {}\n");

        ModelInfo model = one(parse(), "mypack:chair");

        assertEquals(ModelInfo.Facing.CARDINAL, model.facing());
        assertEquals(1f, model.scale());
        assertEquals(1f, model.width());
        assertEquals(1f, model.height());
        assertFalse(model.solid(), "walk-through by default: a display entity has no collision");
    }

    @Test
    void anItemThatDidNotParseCannotBePlaced() throws IOException {
        write("mypack/items/a.yml", "chair:\n  material: NOT_A_THING\n  place: {}\n");

        ModelDefinitions.Result result = parse();

        // The bad material already has a diagnostic of its own. Saying it
        // twice helps nobody.
        assertTrue(result.model().isEmpty());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void anUnknownFacingWarnsAndFallsBackToCardinal() throws IOException {
        chair("  place:\n    facing: sideways\n");

        ModelDefinitions.Result result = parse();

        assertEquals(ModelInfo.Facing.CARDINAL, one(result, "mypack:chair").facing());
        assertEquals(Diagnostic.Severity.WARNING, result.diagnostics().get(0).severity());
        assertTrue(result.diagnostics().get(0).message().contains("cardinal"));
    }

    @Test
    void everyFacingIsAccepted() throws IOException {
        for (ModelInfo.Facing facing : ModelInfo.Facing.values()) {
            chair("  place:\n    facing: " + facing.name().toLowerCase() + "\n");
            assertEquals(facing, one(parse(), "mypack:chair").facing());
        }
    }

    @Test
    void anImpossibleHitboxIsClampedRatherThanRefused() throws IOException {
        chair("  place:\n    width: 0\n    height: 400\n");

        ModelDefinitions.Result result = parse();
        ModelInfo model = one(result, "mypack:chair");

        // A hitbox of 0 is a model nobody can break: it would be
        // permanent with no way to find out why.
        assertTrue(model.width() > 0f);
        assertEquals(16f, model.height());
        assertEquals(2, result.diagnostics().size());
    }

    @Test
    void aSizeThatIsNotANumberWarnsAndUsesTheDefault() throws IOException {
        chair("  place:\n    scale: big\n");

        ModelDefinitions.Result result = parse();

        assertEquals(1f, one(result, "mypack:chair").scale());
        assertEquals(Diagnostic.Severity.WARNING, result.diagnostics().get(0).severity());
    }

    @Test
    void aPieceCanBeFoundByTheItemThatPlacesIt() throws IOException {
        chair("  place: {}\n");

        ModelDefinitions.Result result = parse();

        assertEquals(ContentId.parse("mypack:chair").orElseThrow(),
                result.byItem(ContentId.parse("mypack:chair").orElseThrow()).orElseThrow().id());
        assertTrue(result.byItem(ContentId.parse("mypack:ruby").orElseThrow()).isEmpty());
    }

    /**
     * The opposite default from {@code solid} beside it, and the reason is in
     * {@link ModelInfo#vehicleCollision()}: a car through somebody's fence is
     * wrong in every pack that has one, so the exception is what gets written
     * down. Asserted rather than assumed because a flipped default here is
     * invisible until somebody drives into something.
     */
    @Test
    void aPieceStopsVehiclesUnlessItSaysOtherwise() throws IOException {
        chair("  place: {}\n");

        assertTrue(one(parse(), "mypack:chair").vehicleCollision());
    }

    @Test
    void aPieceCanLetVehiclesThrough() throws IOException {
        chair("  place:\n    vehicle-collision: false\n");

        assertFalse(one(parse(), "mypack:chair").vehicleCollision());
    }

    /**
     * The two `with` copies each rebuild the model through {@code of}, which
     * resets everything the other one set. They are applied in one chain by the
     * parser, so a copy that drops the other's value is a seat offset or a
     * collision flag silently going back to its default.
     */
    @Test
    void aSeatOffsetAndACollisionFlagSurviveEachOther() throws IOException {
        chair("  place:\n    vehicle-collision: false\n    seat:\n      y: 0.5\n      x: 0.25\n");

        ModelInfo info = one(parse(), "mypack:chair");

        assertFalse(info.vehicleCollision());
        assertEquals(0.25f, info.seatSide());
        assertEquals(0.5f, info.seat());
    }

    // ---- storage --------------------------------------------------------

    @Test
    void aPieceCanBeAContainer() throws IOException {
        chair("  place:\n    storage:\n      type: chest\n      rows: 2\n      title: \"Cabinet\"\n");

        ai.resourcepack.engine.api.StorageSpec storage = one(parse(), "mypack:chair").storage().orElseThrow();

        assertEquals(ai.resourcepack.engine.api.StorageSpec.Type.CHEST, storage.type());
        assertEquals(2, storage.rows());
        assertEquals("Cabinet", storage.title().orElseThrow());
    }

    @Test
    void aPieceUsuallyHoldsNothing() throws IOException {
        chair("  place: {}\n");

        assertTrue(one(parse(), "mypack:chair").storage().isEmpty());
    }

    @Test
    void aShulkerAlwaysGivesBackItselfWhateverDropSays() throws IOException {
        // The contents travel inside the dropped item and are unpacked by
        // placing it. A different item dropped would carry contents no
        // placement could ever unpack.
        chair("  place:\n    drop: mypack:shard\n    storage: shulker\n");

        ModelDefinitions.Result result = parse();
        ModelInfo info = one(result, "mypack:chair");

        assertTrue(info.drop().isEmpty());
        assertEquals(ai.resourcepack.engine.api.StorageSpec.Type.SHULKER, info.storage().orElseThrow().type());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("shulker")));
    }

    @Test
    void storageSurvivesTheOtherCopies() throws IOException {
        // withStorage sits in the same chain as withSeatOffset and the rest;
        // a copy that forgot it would make every container a plain piece.
        chair("  place:\n    storage: true\n    vehicle-collision: false\n    seat: 0.5\n");

        ModelInfo info = one(parse(), "mypack:chair");

        assertTrue(info.storage().isPresent());
        assertTrue(info.withSeatOffset(1f, 1f).storage().isPresent());
        assertTrue(info.withShape(null).storage().isPresent());
        assertTrue(info.withVehicleCollision(true).storage().isPresent());
    }

    // ---- jukebox --------------------------------------------------------

    @Test
    void aPieceCanBeAJukebox() throws IOException {
        chair("  place:\n    jukebox:\n      volume: 2\n      pitch: 0.75\n"
                + "      permission: mypack.jukebox.use\n      playing-model: mypack:gramophone_on\n");

        ModelInfo.Jukebox jukebox = one(parse(), "mypack:chair").jukebox().orElseThrow();

        assertEquals(2f, jukebox.volume());
        assertEquals(0.75f, jukebox.pitch());
        assertEquals("mypack.jukebox.use", jukebox.permission().orElseThrow());
        assertEquals("mypack:gramophone_on", jukebox.playingModel().orElseThrow().toString());
    }

    @Test
    void jukeboxTrueIsAVanillaOneThatAnybodyMayUse() throws IOException {
        chair("  place:\n    jukebox: true\n");

        ModelInfo.Jukebox jukebox = one(parse(), "mypack:chair").jukebox().orElseThrow();

        assertEquals(1f, jukebox.volume());
        assertEquals(1f, jukebox.pitch());
        assertTrue(jukebox.permission().isEmpty());
        assertTrue(jukebox.playingModel().isEmpty());
    }

    @Test
    void aJukeboxOutOfRangeFallsBackAndSaysSo() throws IOException {
        chair("  place:\n    jukebox:\n      volume: -1\n      pitch: 9\n      playing-model: not an id\n");

        ModelDefinitions.Result result = parse();
        ModelInfo.Jukebox jukebox = one(result, "mypack:chair").jukebox().orElseThrow();

        assertEquals(1f, jukebox.volume());
        assertEquals(1f, jukebox.pitch());
        assertTrue(jukebox.playingModel().isEmpty());
        assertEquals(3, result.diagnostics().size(), result.diagnostics().toString());
    }

    @Test
    void aJukeboxBehindAContainerIsToldItIsNeverReached() throws IOException {
        chair("  place:\n    storage: true\n    jukebox: true\n");

        assertTrue(parse().diagnostics().stream().anyMatch(d -> d.message().contains("never reached")));
    }

    @Test
    void aPieceUsuallyPlaysNothing() throws IOException {
        chair("  place: {}\n");

        assertTrue(one(parse(), "mypack:chair").jukebox().isEmpty());
    }

    // ---- states ---------------------------------------------------------

    @Test
    void aStateReadsEverySetting() throws IOException {
        chair("  place:\n    reset-after: 10s\n    base-sound: minecraft:block.wooden_door.close\n"
                + "    states:\n"
                + "      - model: mypack:lamp_on\n        light: 14\n        solid: false\n"
                + "        turn: 90\n        offset: [-0.85, 0, 0.5]\n"
                + "        sound: minecraft:block.wooden_door.open\n");

        ModelDefinitions.Result result = parse();
        ModelInfo info = one(result, "mypack:chair");
        ModelInfo.State state = info.states().get(0);

        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        assertEquals(1, info.states().size());
        assertEquals("mypack:lamp_on", state.model().orElseThrow().toString());
        assertEquals(14, state.light().orElseThrow());
        assertFalse(state.solid().orElseThrow());
        assertEquals(90f, state.turn());
        assertEquals(-0.85f, state.offsetX());
        assertEquals(0f, state.offsetY());
        assertEquals(0.5f, state.offsetZ());
        assertEquals("minecraft:block.wooden_door.open", state.sound().orElseThrow());
        assertTrue(state.moves());
        assertEquals(200L, info.stateResetTicks());
        assertEquals("minecraft:block.wooden_door.close", info.baseSound().orElseThrow());
    }

    @Test
    void whatAStateLeavesOutIsThePieceAsDefined() throws IOException {
        // A Nexo-style door: it swings and stops being solid, and keeps its
        // model and its (lack of) light.
        chair("  place:\n    solid: true\n    states:\n      - turn: 90\n        solid: false\n");

        ModelInfo info = one(parse(), "mypack:chair");

        assertTrue(info.solidIn(0), "closed, it is the piece as defined");
        assertFalse(info.solidIn(1), "open, it can be walked through");
        assertEquals(0, info.lightIn(1));
        assertTrue(info.states().get(0).model().isEmpty());
        assertTrue(info.state(1).orElseThrow().light().isEmpty());
    }

    @Test
    void aLampThatIsDarkUntilClicked() throws IOException {
        // Light used to be decided once, at placement. A base of 0 and a lit
        // state is the case that has to work now.
        chair("  place:\n    light: 0\n    states:\n      - light: 15\n        model: mypack:lamp_on\n");

        ModelInfo info = one(parse(), "mypack:chair");

        assertEquals(0, info.lightIn(0));
        assertEquals(15, info.lightIn(1));
        assertFalse(info.states().get(0).moves(), "a lamp swaps its model, it does not move");
    }

    @Test
    void aClickCyclesRoundTheStatesAndBackToTheStart() throws IOException {
        chair("  place:\n    states:\n      - light: 5\n      - light: 10\n");

        ModelInfo info = one(parse(), "mypack:chair");

        assertEquals(1, info.nextState(0));
        assertEquals(2, info.nextState(1));
        assertEquals(0, info.nextState(2), "after the last comes the piece as defined");
        assertEquals(0, info.nextState(7), "a state the pack no longer has goes back to the start");
        assertEquals(0, info.nextState(-1));
        assertTrue(info.state(0).isEmpty(), "state 0 is the piece as defined, not an entry");
        assertTrue(info.state(3).isEmpty());
        assertEquals(10, info.lightIn(2));
        assertEquals(0, info.lightIn(9), "out of range reads as the piece as defined");
    }

    @Test
    void aPieceWithNoStatesStaysPut() throws IOException {
        chair("  place: {}\n");

        ModelInfo info = one(parse(), "mypack:chair");

        assertTrue(info.states().isEmpty());
        assertEquals(0, info.nextState(0));
        assertEquals(0L, info.stateResetTicks());
    }

    @Test
    void aStateThatIsNotABlockIsSkippedAndSaidSo() throws IOException {
        chair("  place:\n    states:\n      - on\n      - light: 3\n");

        ModelDefinitions.Result result = parse();

        assertEquals(1, one(result, "mypack:chair").states().size());
        assertEquals(1, result.diagnostics().size(), result.diagnostics().toString());
    }

    @Test
    void statesThatAreNotAListAreRefused() throws IOException {
        chair("  place:\n    states: on\n");

        ModelDefinitions.Result result = parse();

        assertTrue(one(result, "mypack:chair").states().isEmpty());
        assertEquals(1, result.diagnostics().size());
    }

    @Test
    void badStateValuesFallBackOneByOne() throws IOException {
        chair("  place:\n    reset-after: soon\n    states:\n"
                + "      - light: 40\n        solid: maybe\n        turn: left\n"
                + "        offset: [1, 2]\n        sound: door opens\n        model: Not An Id\n");

        ModelDefinitions.Result result = parse();
        ModelInfo info = one(result, "mypack:chair");
        ModelInfo.State state = info.states().get(0);

        assertEquals(15, state.light().orElseThrow(), "clamped");
        assertTrue(state.solid().isEmpty());
        assertFalse(state.moves());
        assertTrue(state.sound().isEmpty());
        assertTrue(state.model().isEmpty());
        assertEquals(0L, info.stateResetTicks());
        assertEquals(7, result.diagnostics().size(), result.diagnostics().toString());
    }

    @Test
    void aTurnIsFoldedIntoOneRevolution() throws IOException {
        chair("  place:\n    states:\n      - turn: 450\n");

        assertEquals(90f, one(parse(), "mypack:chair").states().get(0).turn());
    }

    @Test
    void resetAfterWithNothingToResetIsToldSo() throws IOException {
        chair("  place:\n    reset-after: 5s\n");

        assertEquals(1, parse().diagnostics().size());
    }

    @Test
    void statesSurviveTheOtherCopies() throws IOException {
        chair("  place:\n    reset-after: 20\n    states:\n      - light: 3\n");

        ModelInfo info = one(parse(), "mypack:chair").withSeatOffset(1f, 0f).withStorage(null);

        assertEquals(1, info.states().size());
        assertEquals(20L, info.stateResetTicks());
    }

    // ---- growing ---------------------------------------------------------

    /** A rose in two stages: the first grows into the second. */
    private void rose(String growBlock) throws IOException {
        write("mypack/items/rose.yml",
                "rose:\n  material: PAPER\n  model: rose\n  place:\n" + growBlock
                        + "rose_stage2:\n  material: PAPER\n  model: rose2\n  place: {}\n");
    }

    @Test
    void aPieceCanGrowIntoAnother() throws IOException {
        rose("    grow:\n      into: mypack:rose_stage2\n      after: 10s\n      chance: 0.5\n      light: 9\n");

        ModelDefinitions.Result result = parse();
        ModelInfo.Grow grow = one(result, "mypack:rose").grow().orElseThrow();

        assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
        assertEquals("mypack:rose_stage2", grow.into().toString());
        assertEquals(200L, grow.afterTicks());
        assertEquals(0.5, grow.chance());
        assertEquals(9, grow.minimumLight());
        assertTrue(one(result, "mypack:rose_stage2").grow().isEmpty(), "the last stage stays");
    }

    @Test
    void growingNeedsOnlyWhatItBecomes() throws IOException {
        rose("    grow:\n      into: mypack:rose_stage2\n");

        ModelInfo.Grow grow = one(parse(), "mypack:rose").grow().orElseThrow();

        assertEquals(0L, grow.afterTicks());
        assertEquals(1.0, grow.chance());
        assertEquals(0, grow.minimumLight());
    }

    @Test
    void growingIntoSomethingThatIsNotAPlacedModelNeverGrows() throws IOException {
        // A piece that grew into nothing would be a piece that vanished.
        rose("    grow:\n      into: mypack:tulip\n");

        ModelDefinitions.Result result = parse();

        assertTrue(one(result, "mypack:rose").grow().isEmpty());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("mypack:tulip")),
                result.diagnostics().toString());
    }

    @Test
    void growingIntoAnItemThatCannotBePlacedNeverGrows() throws IOException {
        write("mypack/items/seeds.yml", "seed:\n  material: PAPER\n");
        rose("    grow:\n      into: mypack:seed\n");

        ModelDefinitions.Result result = parse();

        assertTrue(one(result, "mypack:rose").grow().isEmpty());
        assertEquals(1, result.diagnostics().size(), result.diagnostics().toString());
    }

    @Test
    void growingIntoItselfIsRefused() throws IOException {
        rose("    grow:\n      into: mypack:rose\n");

        ModelDefinitions.Result result = parse();

        assertTrue(one(result, "mypack:rose").grow().isEmpty());
        assertEquals(1, result.diagnostics().size());
    }

    @Test
    void badGrowValuesFallBackOneByOne() throws IOException {
        rose("    grow:\n      into: mypack:rose_stage2\n      after: tomorrow\n      chance: 2\n      light: 20\n");

        ModelDefinitions.Result result = parse();
        ModelInfo.Grow grow = one(result, "mypack:rose").grow().orElseThrow();

        assertEquals(0L, grow.afterTicks());
        assertEquals(1.0, grow.chance());
        assertEquals(0, grow.minimumLight());
        assertEquals(3, result.diagnostics().size(), result.diagnostics().toString());
    }

    @Test
    void growWithNoIntoIsRefused() throws IOException {
        rose("    grow:\n      after: 5s\n");

        ModelDefinitions.Result result = parse();

        assertTrue(one(result, "mypack:rose").grow().isEmpty());
        assertEquals(1, result.diagnostics().size());
    }

    @Test
    void aCheckGrowsItOnlyOnceEverythingAgrees() {
        ModelInfo.Grow grow = ModelInfo.Grow.of(ContentId.parse("mypack:rose_stage2").orElseThrow(),
                200, 0.5, 9);

        assertTrue(grow.ready(200, 9, 0.49), "old enough, bright enough, lucky enough");
        assertFalse(grow.ready(199, 15, 0.0), "not standing long enough");
        assertFalse(grow.ready(10_000, 8, 0.0), "too dark");
        assertFalse(grow.ready(10_000, 15, 0.5), "unlucky: the roll has to be under the chance");
    }

    @Test
    void aSureThingAlwaysGrows() {
        ModelInfo.Grow grow = ModelInfo.Grow.of(ContentId.parse("mypack:b").orElseThrow(), 0, 1, 0);

        assertTrue(grow.ready(0, 0, 0.999_999));
    }

    @Test
    void growSurvivesTheOtherCopies() throws IOException {
        rose("    grow:\n      into: mypack:rose_stage2\n");

        ModelInfo info = one(parse(), "mypack:rose").withSeatOffset(0f, 1f).withJukebox(null);

        assertTrue(info.grow().isPresent());
    }

    @Test
    void nothingLoadedMeansNothingParsed() {
        assertTrue(ModelDefinitions.parse(null, null).model().isEmpty());
        assertTrue(ModelDefinitions.parse(LoadReport.empty(), null).model().isEmpty());
    }
}
