package ai.resourcepack.engine.core.edit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which paths a pull may write to.
 *
 * <p>The most security-shaped code in the plugin: these strings arrive over
 * HTTP and are resolved against a folder on somebody's server. Every case here
 * is one that has to be a refusal rather than a best guess.
 */
class EditPathsTest {

    @TempDir
    Path pack;

    @Test
    void ordinaryAssetPathsAreFine() {
        assertTrue(EditPaths.safe("assets/models/chair.json"));
        assertTrue(EditPaths.safe("assets/textures/item/chair_wood.png"));
        assertTrue(EditPaths.safe("textures/item/chair.png"));
        assertTrue(EditPaths.safe(".project-textures/chair.png"));
    }

    @Test
    void traversalIsRefused() {
        assertFalse(EditPaths.safe("../plugins/RPEngine/config.yml"));
        assertFalse(EditPaths.safe("assets/../../secrets"));
        assertFalse(EditPaths.safe("assets/./models/chair.json"));
        assertFalse(EditPaths.safe(".."));
    }

    @Test
    void absoluteAndWindowsSeparatorsAreRefused() {
        assertFalse(EditPaths.safe("/etc/passwd"));
        assertFalse(EditPaths.safe("assets\\models\\chair.json"));
        assertFalse(EditPaths.safe("assets//models/chair.json"));
    }

    @Test
    void controlCharactersAndSpacesAreRefused() {
        assertFalse(EditPaths.safe("assets/mo dels/chair.json"));
        assertFalse(EditPaths.safe("assets/models/chair\u0000.json"));
        assertFalse(EditPaths.safe("assets/models/chair\u007f.json"));
    }

    /**
     * A server owner on Windows is the ordinary case here, not the exotic one.
     * These names are devices whatever extension follows them, and a trailing
     * dot is silently stripped by the filesystem — so a path this let through
     * would not be the path that was written.
     */
    @Test
    void windowsDeviceNamesAndTrailingDotsAreRefused() {
        assertFalse(EditPaths.safe("assets/models/con.json"));
        assertFalse(EditPaths.safe("assets/models/COM1.png"));
        assertFalse(EditPaths.safe("nul"));
        assertFalse(EditPaths.safe("assets/models/chair."));
        assertTrue(EditPaths.safe("assets/models/console.json"), "'console' is not 'con'");
    }

    @Test
    void nothingUnusualIsAllowed() {
        assertFalse(EditPaths.safe(""));
        assertFalse(EditPaths.safe(null));
        assertFalse(EditPaths.safe("assets/models/chair.json?x=1"));
        assertFalse(EditPaths.safe("assets/models/ch*ir.json"));
        assertFalse(EditPaths.safe("a".repeat(300)));
    }

    @Test
    void resolveLandsInsideThePack() {
        Path resolved = EditPaths.resolve(pack, "assets/models/chair.json");
        assertNotNull(resolved);
        assertTrue(resolved.startsWith(pack.toAbsolutePath().normalize()));
        assertEquals("chair.json", resolved.getFileName().toString());
    }

    @Test
    void resolveRefusesAnythingOutside() {
        assertNull(EditPaths.resolve(pack, "../elsewhere.json"));
        assertNull(EditPaths.resolve(pack, "/absolute.json"));
    }

    /**
     * The second half of the fence earning its keep: the string check passes
     * and the resolved location is still somewhere else. Only reachable
     * through a link, which is why the normalised comparison exists rather
     * than trusting {@link EditPaths#safe} alone.
     */
    @Test
    void resolveRefusesALinkOutOfThePack() throws IOException {
        Path outside = pack.getParent().resolve("outside");
        Files.createDirectories(outside);
        try {
            Files.createSymbolicLink(pack.resolve("escape"), outside);
        } catch (IOException | UnsupportedOperationException e) {
            // Windows without developer mode. The check below is the same
            // check either way; there is just nothing to point it at here.
            return;
        }
        assertNull(EditPaths.resolve(pack, "escape/taken.png"));
    }
}
