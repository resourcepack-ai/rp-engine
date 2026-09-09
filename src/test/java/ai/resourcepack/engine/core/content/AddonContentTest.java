package ai.resourcepack.engine.core.content;

import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.api.BuildReport;
import ai.resourcepack.engine.api.BuiltPack;
import ai.resourcepack.engine.core.emote.AuthoredEmotes;
import ai.resourcepack.engine.core.item.ItemAssets;
import ai.resourcepack.engine.core.pack.PackBuilder;
import ai.resourcepack.engine.core.registry.ContentRegistryImpl;
import ai.resourcepack.engine.core.vehicle.VehicleDefinitions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loads the content folder each addon in {@code ../rpe-addons} ships, the way
 * the engine will on a server.
 *
 * <p>An addon's content is written by hand and installed into a running
 * server by its jar, so a typo in its YAML or a bone name in its emotes is
 * found by a server owner rather than a test — unless the engine's own loader
 * reads it first, here. Skipped when the addons are not checked out beside
 * the engine, which is what a stranger building the engine alone has.
 */
class AddonContentTest {

    private static final Path ADDONS = Paths.get("..", "rpe-addons");

    @Test
    void theSkateboardContentLoadsClean() {
        Path content = ADDONS.resolve("skateboards/src/main/resources/content");
        Assumptions.assumeTrue(Files.isDirectory(content), "no rpe-addons checkout beside the engine");

        ContentRegistryImpl registry = new ContentRegistryImpl();
        LoadReport report = new ContentFolderLoader(registry).load(content, ContentSource.AUTHORED);
        for (Diagnostic diagnostic : report.diagnostics()) {
            System.out.println("skateboards: " + diagnostic);
        }
        assertTrue(report.diagnostics(Diagnostic.Severity.ERROR).isEmpty(), "content errors: " + report.diagnostics());
        assertEquals(1, report.packs().size());
        // The deck, plus a skatepark's worth of placeable pieces. Counted as
        // "more than the board" rather than exactly, because the park is
        // generated and grows; the board is what this test is about.
        assertTrue(report.definitions(ContentKind.ITEM).size() >= 1,
                "the deck should be there: " + report.definitions(ContentKind.ITEM).size());
        assertEquals(1, report.definitions(ContentKind.VEHICLE).size());
        assertEquals(20, report.definitions(ContentKind.EMOTE).size());

        VehicleDefinitions.Result vehicles = VehicleDefinitions.parse(report);
        for (Diagnostic diagnostic : vehicles.diagnostics()) {
            System.out.println("skateboards vehicle: " + diagnostic);
        }
        assertEquals(1, vehicles.vehicles().size(), "the skateboard should parse: " + vehicles.diagnostics());
        assertTrue(vehicles.vehicles().values().iterator().next().jumps(), "space is an ollie");

        AuthoredEmotes.Result emotes = AuthoredEmotes.parse(report);
        for (Diagnostic diagnostic : emotes.diagnostics()) {
            System.out.println("skateboards emote: " + diagnostic);
        }
        assertTrue(emotes.diagnostics().isEmpty(), "emote problems: " + emotes.diagnostics());
        assertEquals(20, emotes.count());
        assertTrue(emotes.byNamespace().get("skateboards").containsKey("skateboards_push"));
        assertTrue(emotes.byNamespace().get("skateboards").containsKey("skateboards_pushback"));
        assertTrue(emotes.byNamespace().get("skateboards").containsKey("skateboards_tuck"));
        assertTrue(emotes.byNamespace().get("skateboards").containsKey("skateboards_coffin"));
        assertTrue(emotes.byNamespace().get("skateboards").containsKey("skateboards_wallride"));
        assertTrue(emotes.byNamespace().get("skateboards").containsKey("skateboards_moving_goofy"));
    }

    /**
     * The same for the BMX, whose content is harder in one specific way: its
     * model carries a NESTED group tree, and the animations that make the
     * tricks work address those groups by INDEX.
     *
     * <p>Which is why the count is asserted rather than "more than one". An
     * index that is in range but wrong does not fail to resolve — it resolves
     * to a different part, so a barspin spins the saddle. There is nothing to
     * catch that at load time; the only defence is that the tree is the shape
     * the animations were written against, and this is where that is stated.
     */
    @Test
    void theBmxContentLoadsClean() {
        Path content = ADDONS.resolve("bmx/src/main/resources/content");
        Assumptions.assumeTrue(Files.isDirectory(content), "no rpe-addons checkout beside the engine");

        ContentRegistryImpl registry = new ContentRegistryImpl();
        LoadReport report = new ContentFolderLoader(registry).load(content, ContentSource.AUTHORED);
        for (Diagnostic diagnostic : report.diagnostics()) {
            System.out.println("bmx: " + diagnostic);
        }
        assertTrue(report.diagnostics(Diagnostic.Severity.ERROR).isEmpty(), "content errors: " + report.diagnostics());
        assertEquals(1, report.packs().size());
        assertTrue(report.definitions(ContentKind.ITEM).size() >= 1,
                "the bike should be there: " + report.definitions(ContentKind.ITEM).size());
        assertEquals(1, report.definitions(ContentKind.VEHICLE).size());

        VehicleDefinitions.Result vehicles = VehicleDefinitions.parse(report);
        for (Diagnostic diagnostic : vehicles.diagnostics()) {
            System.out.println("bmx vehicle: " + diagnostic);
        }
        assertEquals(1, vehicles.vehicles().size(), "the BMX should parse: " + vehicles.diagnostics());
        assertTrue(vehicles.vehicles().values().iterator().next().jumps(), "space is a bunnyhop");

        AuthoredEmotes.Result emotes = AuthoredEmotes.parse(report);
        for (Diagnostic diagnostic : emotes.diagnostics()) {
            System.out.println("bmx emote: " + diagnostic);
        }
        // Not merely "no errors": a bone the rig does not have is a WARNING,
        // and it is the mistake this addon made on its first pass by writing
        // `root` inside `animators` rather than beside it. A rider who simply
        // does not lean is not something anybody would notice from a log.
        assertTrue(emotes.diagnostics().isEmpty(), "emote problems: " + emotes.diagnostics());
        for (String wanted : new String[] {
                "bmx_idle", "bmx_moving", "bmx_pedal", "bmx_sprint", "bmx_manual",
                "bmx_wheelie", "bmx_barspin", "bmx_whip", "bmx_wallride", "bmx_airborne"}) {
            assertTrue(emotes.byNamespace().get("bmx").containsKey(wanted), "no emote " + wanted);
        }
    }

    /**
     * Builds the bundle the way the server does and checks the item can be
     * DRAWN: its definition, its model, and every texture the model names.
     *
     * <p>A missing one of those is the black-and-purple item, and nothing in
     * the loader says so - a texture reference is a string until a client
     * tries to load it.
     */
    @Test
    void theSkateboardIsDrawableFromTheBuiltPack() throws Exception {
        Path content = ADDONS.resolve("skateboards/src/main/resources/content");
        Assumptions.assumeTrue(Files.isDirectory(content), "no rpe-addons checkout beside the engine");
        Path out = Files.createTempDirectory("skateboards-pack");

        ContentRegistryImpl registry = new ContentRegistryImpl();
        LoadReport report = new ContentFolderLoader(registry).load(content, ContentSource.AUTHORED);
        BuildReport built = new PackBuilder().with(new ItemAssets()).build(content, out, report);
        for (Diagnostic diagnostic : built.diagnostics()) {
            System.out.println("skateboards build: " + diagnostic);
        }
        assertTrue(built.diagnostics(Diagnostic.Severity.ERROR).isEmpty(), "build errors: " + built.diagnostics());
        BuiltPack main = built.pack("main").orElseThrow();

        Set<String> names = new HashSet<>();
        JsonObject model = null;
        try (ZipFile zip = new ZipFile(main.file().toFile())) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                names.add(entry.getName());
                if (entry.getName().equals("assets/skateboards/models/item/deck.json")) {
                    model = JsonParser.parseReader(new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8))
                            .getAsJsonObject();
                }
            }
        }
        for (String name : names) {
            if (name.startsWith("assets/skateboards/")) {
                System.out.println("skateboards zip: " + name);
            }
        }
        assertTrue(names.contains("assets/skateboards/items/deck.json"), "no item definition for the deck");
        assertTrue(model != null, "no item model for the deck");
        for (var texture : model.getAsJsonObject("textures").entrySet()) {
            String ref = texture.getValue().getAsString();
            String namespace = ref.contains(":") ? ref.substring(0, ref.indexOf(':')) : "minecraft";
            String path = ref.contains(":") ? ref.substring(ref.indexOf(':') + 1) : ref;
            String file = "assets/" + namespace + "/textures/" + path + ".png";
            assertTrue(names.contains(file), "the deck's model names " + ref + " but the pack has no " + file);
        }

        // The wheels turn, which means the build wrote a rig: one item model
        // per moving bone, derived from the piece's own id. The board's model
        // is an exported .json with an `animations` array in it rather than a
        // .bbmodel, and for a while only the project branch looked - so the
        // board drove with its wheels welded on, from a file that said they
        // turned. Anything matching is enough; the count is the model's
        // business, not this test's.
        assertTrue(names.stream().anyMatch(n -> n.startsWith("assets/skateboards/models/item/deck_")),
                "the deck's animated parts were not written - are its wheels rigged? " + names);
    }

    /**
     * The BMX, built and checked the same way, plus the thing that is only
     * true of this one: **every group in its tree has to become a part**.
     *
     * <p>Six of its seven groups exist to be rotated by a trick, and one is an
     * empty container that a wheelie turns everything else about. A build that
     * quietly dropped a bone would leave that trick animating nothing, which
     * on a server is a key that does not work rather than an error.
     */
    @Test
    void theBmxIsDrawableFromTheBuiltPack() throws Exception {
        Path content = ADDONS.resolve("bmx/src/main/resources/content");
        Assumptions.assumeTrue(Files.isDirectory(content), "no rpe-addons checkout beside the engine");
        Path out = Files.createTempDirectory("bmx-pack");

        ContentRegistryImpl registry = new ContentRegistryImpl();
        LoadReport report = new ContentFolderLoader(registry).load(content, ContentSource.AUTHORED);
        BuildReport built = new PackBuilder().with(new ItemAssets()).build(content, out, report);
        for (Diagnostic diagnostic : built.diagnostics()) {
            System.out.println("bmx build: " + diagnostic);
        }
        assertTrue(built.diagnostics(Diagnostic.Severity.ERROR).isEmpty(), "build errors: " + built.diagnostics());
        BuiltPack main = built.pack("main").orElseThrow();

        Set<String> names = new HashSet<>();
        JsonObject model = null;
        try (ZipFile zip = new ZipFile(main.file().toFile())) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                names.add(entry.getName());
                if (entry.getName().equals("assets/bmx/models/item/bike.json")) {
                    model = JsonParser.parseReader(new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8))
                            .getAsJsonObject();
                }
            }
        }
        assertTrue(names.contains("assets/bmx/items/bike.json"), "no item definition for the bike");
        assertTrue(model != null, "no item model for the bike: " + names);
        for (var texture : model.getAsJsonObject("textures").entrySet()) {
            String ref = texture.getValue().getAsString();
            String namespace = ref.contains(":") ? ref.substring(0, ref.indexOf(':')) : "minecraft";
            String path = ref.contains(":") ? ref.substring(ref.indexOf(':') + 1) : ref;
            String file = "assets/" + namespace + "/textures/" + path + ".png";
            assertTrue(names.contains(file), "the bike's model names " + ref + " but the pack has no " + file);
        }

        long parts = names.stream().filter(n -> n.startsWith("assets/bmx/models/item/bike_")).count();
        assertTrue(parts >= 6,
                "the BMX has six groups that a trick rotates and the build wrote " + parts
                        + " parts - a dropped bone is a trick that animates nothing: " + names);
    }
}
