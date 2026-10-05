package ai.resourcepack.engine.core.block;

import ai.resourcepack.engine.api.BlockInfo;
import ai.resourcepack.engine.api.BuildReport;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.core.content.ContentFolderLoader;
import ai.resourcepack.engine.core.item.ItemAssets;
import ai.resourcepack.engine.core.pack.PackBuilder;
import ai.resourcepack.engine.core.registry.ContentRegistryImpl;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Blocks that are not plain cubes: cubes with states (a furnace that faces
 * you, a safe that opens) and whole vanilla block types taken over (stairs,
 * slabs, doors).
 */
class BlockShapesTest {

    @TempDir
    Path content;

    @TempDir
    Path out;

    private void write(String path, String text) throws IOException {
        Path file = content.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private LoadReport load() {
        return new ContentFolderLoader(new ContentRegistryImpl()).load(content, ContentSource.AUTHORED);
    }

    private static ContentId id(String id) {
        return ContentId.parse(id).orElseThrow();
    }

    private Map<ContentId, BlockInfo> blocks(String yaml) throws IOException {
        write("mypack/pack.yml", "name: mine\n");
        write("mypack/blocks/blocks.yml", yaml);
        BlockDefinitions.Result result = BlockDefinitions.parse(load());
        return result.blocks();
    }

    @Test
    void aStairMadeFromATextureGetsTheGamesThreeStairParts() throws IOException {
        BlockInfo stairs = blocks("""
                ruby_stairs:
                  shape: stairs
                  textures: { top: block/ruby_top, side: block/ruby_side, bottom: block/ruby_top }
                """).get(id("mypack:ruby_stairs"));

        assertEquals(BlockInfo.Shape.STAIRS, stairs.shape());
        assertEquals(Set.of("straight", "inner", "outer"), stairs.roleModels().keySet());
        JsonObject inner = JsonParser.parseString(stairs.roleModels().get("inner")).getAsJsonObject();
        assertEquals("minecraft:block/inner_stairs", inner.get("parent").getAsString());
        assertEquals("block/ruby_side", inner.getAsJsonObject("textures").get("side").getAsString());
        // The item shows the straight run.
        assertEquals(stairs.roleModels().get("straight"), stairs.model());
    }

    @Test
    void aBaseNamingAVanillaBlockSaysTheShapeAndWhichBlockToTake() throws IOException {
        BlockInfo door = blocks("""
                gate:
                  base: minecraft:spruce_door
                  textures: { top: block/gate_top, bottom: block/gate_bottom }
                  item-texture: item/gate
                """).get(id("mypack:gate"));

        assertEquals(BlockInfo.Shape.DOOR, door.shape());
        assertEquals("minecraft:spruce_door", door.takes().orElseThrow());
        assertEquals(8, door.roleModels().size());
        assertTrue(door.roleModels().get("top_right_open").contains("minecraft:block/door_top_right_open"));
        assertEquals("item/gate", door.itemTexture().orElseThrow());
    }

    @Test
    void shapesAreHandedOutFromTheirPoolInOrderAndKeptByName() throws IOException {
        Map<ContentId, BlockInfo> blocks = blocks("""
                a:
                  shape: slab
                  texture: block/a
                b:
                  shape: slab
                  texture: block/b
                c:
                  base: minecraft:waxed_copper_door
                  texture: block/c
                d:
                  shape: trapdoor
                  texture: block/d
                """);
        BlockStates states = new BlockStates(out.toFile());
        // A server with no copper doors or trapdoors: 1.20.
        java.util.function.Predicate<String> exists = name -> !name.contains("copper_door")
                && !name.contains("copper_trapdoor");

        assertEquals("petrified_oak_slab", states.shapedFor(blocks.get(id("mypack:a")), exists).orElseThrow());
        assertEquals("waxed_weathered_cut_copper_slab",
                states.shapedFor(blocks.get(id("mypack:b")), exists).orElseThrow());
        assertTrue(states.shapedFor(blocks.get(id("mypack:c")), exists).isEmpty(), "asks for a block this server lacks");
        assertTrue(states.shapedFor(blocks.get(id("mypack:d")), exists).isEmpty(), "none of its pool exists");
        assertEquals(3, states.remaining(BlockInfo.Shape.SLAB, exists));

        states.save(java.util.logging.Logger.getAnonymousLogger());
        BlockStates again = new BlockStates(out.toFile());
        again.load(java.util.logging.Logger.getAnonymousLogger());
        assertEquals("waxed_weathered_cut_copper_slab", again.existingShaped(blocks.get(id("mypack:b"))).orElseThrow());
        // On a newer server the trapdoor gets one, and the slabs stay put.
        assertEquals("waxed_weathered_copper_trapdoor",
                again.shapedFor(blocks.get(id("mypack:d")), name -> true).orElseThrow());
        assertEquals("petrified_oak_slab", again.shapedFor(blocks.get(id("mypack:a")), name -> true).orElseThrow());
    }

    @Test
    void aTurningCubeIsOneStatePerFacingEachItsOwnNumber() throws IOException {
        BlockInfo furnace = blocks("""
                kiln:
                  model: kiln
                  rotate: horizontal
                """).get(id("mypack:kiln"));

        assertEquals(List.of("facing=north", "facing=east", "facing=south", "facing=west"), furnace.states());
        assertEquals(90, furnace.appearanceOf("facing=east").y());
        assertEquals("kiln", furnace.appearanceOf("facing=west").model());

        BlockStates states = new BlockStates(out.toFile());
        for (String state : furnace.states()) {
            states.numberFor(furnace, state);
        }
        // The placed state is filed under the id alone, as a block with no
        // states always was, so adding a rotation to an existing block keeps
        // every one already placed.
        assertEquals("mypack:kiln", BlockStates.keyOf(furnace, "facing=north"));
        assertEquals("mypack:kiln[facing=east]", BlockStates.keyOf(furnace, "facing=east"));
        assertEquals(4, states.allocated(BlockInfo.Base.NOTE_BLOCK).size());
        assertEquals(id("mypack:kiln"), states.at(BlockInfo.Base.NOTE_BLOCK,
                states.existing(furnace, "facing=south").orElseThrow()).orElseThrow());
    }

    @Test
    void propertiesAppearancesAndAClickThatTurnsOne() throws IOException {
        Map<ContentId, BlockInfo> blocks = blocks("""
                safe:
                  model: safe
                  base: mushroom_stem
                  properties:
                    facing: facing
                    open: boolean
                  appearances:
                    "open=true": safe_open
                    "facing=east,open=true": { model: safe_open, y: 90 }
                    "facing=east": { y: 90 }
                  click: open
                """);
        BlockInfo safe = blocks.get(id("mypack:safe"));

        assertEquals(8, safe.states().size());
        assertEquals("facing=north,open=false", safe.defaultState());
        assertEquals("open", safe.cycle().orElseThrow());
        assertEquals("facing=east,open=true", safe.next("facing=east,open=false", "open"));
        assertEquals("facing=east,open=false", safe.next("facing=east,open=true", "open"));

        BlockInfo.Appearance eastOpen = safe.appearanceOf("facing=east,open=true");
        assertEquals("safe_open", eastOpen.model());
        assertEquals(90, eastOpen.y());
        assertEquals("safe_open", safe.appearanceOf("facing=south,open=true").model());
        BlockInfo.Appearance eastShut = safe.appearanceOf("facing=east,open=false");
        assertEquals("safe", eastShut.model(), "an appearance with no model draws the block's own");
        assertEquals(90, eastShut.y());
    }

    @Test
    void theBuiltPackDrawsEveryStateAndRepaintsTheTakenBlock() throws IOException {
        write("mypack/pack.yml", "name: mine\n");
        write("mypack/blocks/blocks.yml", """
                kiln:
                  model: kiln
                  rotate: horizontal
                ruby_stairs:
                  shape: stairs
                  texture: block/ruby
                plain:
                  model: kiln
                """);
        write("mypack/assets/models/kiln.json", """
                {"parent": "minecraft:block/orientable", "textures": {"front": "block/kiln_front", "side": "block/kiln", "top": "block/kiln"}}
                """);
        LoadReport report = load();
        BlockStates states = new BlockStates(out.resolve("state").toFile());
        for (BlockInfo block : BlockDefinitions.parse(report).blocks().values()) {
            if (block.shape() == BlockInfo.Shape.CUBE) {
                block.states().forEach(state -> states.numberFor(block, state));
            } else {
                states.shapedFor(block, name -> true);
            }
        }
        BuildReport built = new PackBuilder().with(new ItemAssets()).with(new BlockAssets(states))
                .build(content, out.resolve("build"), report);
        assertTrue(built.diagnostics().stream().noneMatch(d -> d.severity() == Diagnostic.Severity.ERROR),
                built.diagnostics().toString());
        Map<String, String> zip = read(built);

        JsonObject noteBlock = JsonParser.parseString(zip.get("assets/minecraft/blockstates/note_block.json"))
                .getAsJsonObject().getAsJsonObject("variants");
        int kilnEast = states.existing(BlockDefinitions.parse(report).blocks().get(id("mypack:kiln")), "facing=east")
                .orElseThrow();
        JsonObject east = noteBlock.getAsJsonObject("instrument=harp," + BlockStates.identityOf(
                BlockInfo.Base.NOTE_BLOCK, kilnEast));
        assertEquals("mypack:block/kiln", east.get("model").getAsString());
        assertEquals(90, east.get("y").getAsInt());
        // An unturned block is written exactly as it always was.
        assertTrue(zip.get("assets/minecraft/blockstates/note_block.json")
                .contains("{ \"model\": \"minecraft:block/note_block\" }"));

        JsonObject stairs = JsonParser.parseString(
                zip.get("assets/minecraft/blockstates/waxed_weathered_cut_copper_stairs.json"))
                .getAsJsonObject().getAsJsonObject("variants");
        assertEquals(40, stairs.size());
        JsonObject corner = stairs.getAsJsonObject("facing=east,half=top,shape=outer_right");
        assertEquals(180, corner.get("x").getAsInt());
        assertEquals(90, corner.get("y").getAsInt());
        assertTrue(corner.get("uvlock").getAsBoolean());
        String outerModel = corner.get("model").getAsString();
        assertTrue(outerModel.startsWith("mypack:block/ruby_stairs"), outerModel);
        String file = "assets/mypack/models/block/" + outerModel.substring("mypack:block/".length()) + ".json";
        assertTrue(zip.get(file).contains("minecraft:block/outer_stairs"), zip.get(file));
        assertTrue(zip.get(file).contains("mypack:block/ruby"), "the texture is the pack's own");
        assertFalse(zip.containsKey("assets/minecraft/blockstates/waxed_cut_copper_stairs.json"),
                "only the kind handed out is repainted");
    }

    private static Map<String, String> read(BuildReport report) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (InputStream in = Files.newInputStream(report.pack("main").orElseThrow().file());
             ZipInputStream zin = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zin.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }
}
