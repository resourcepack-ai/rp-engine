package ai.resourcepack.engine.core.content;

import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.core.emote.AuthoredEmotes;
import ai.resourcepack.engine.core.registry.ContentRegistryImpl;
import ai.resourcepack.engine.core.vehicle.VehicleDefinitions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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
}
