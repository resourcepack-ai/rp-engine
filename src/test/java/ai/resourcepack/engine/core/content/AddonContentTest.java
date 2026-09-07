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
        assertEquals(1, report.definitions(ContentKind.ITEM).size());
        assertEquals(1, report.definitions(ContentKind.VEHICLE).size());
        assertEquals(7, report.definitions(ContentKind.EMOTE).size());

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
        assertEquals(7, emotes.count());
        assertTrue(emotes.byNamespace().get("skateboards").containsKey("skateboards_push"));
        assertTrue(emotes.byNamespace().get("skateboards").containsKey("skateboards_tuck"));
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
    }
}
