package ai.resourcepack.engine.core.dialog;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.DialogInfo;
import ai.resourcepack.engine.api.LoadReport;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The authored dialog format, and the JSON it becomes.
 *
 * <p>What is checked here is the SHAPE of the output rather than what it looks
 * like on screen: the engine's whole claim about dialogs is that it transports
 * the game's own JSON without interpreting it, so the thing worth holding still
 * is that the keys are the ones the client's codec names.
 */
class DialogDefinitionsTest {

    @TempDir
    Path root;

    private Path content;

    @BeforeEach
    void setUp() throws IOException {
        content = root.resolve("content");
        Files.createDirectories(content);
        write("mypack/pack.yml", "{}\n");
    }

    private void write(String path, String text) throws IOException {
        Path file = content.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private DialogDefinitions.Result parse() {
        LoadReport loaded = new ContentFolderLoader(new ContentRegistryImpl())
                .load(content, ContentSource.AUTHORED);
        return DialogDefinitions.parse(loaded, name -> {
            Path file = content.resolve(name);
            try {
                return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
            } catch (IOException e) {
                return null;
            }
        });
    }

    private DialogInfo one(DialogDefinitions.Result result, String id) {
        return result.dialogs().get(ContentId.parse(id).orElseThrow());
    }

    @Test
    void oneButtonIsANotice() throws IOException {
        write("mypack/dialogs/menus.yml",
                "welcome:\n"
                        + "  title: Welcome\n"
                        + "  body: [\"Glad you made it.\"]\n"
                        + "  buttons:\n"
                        + "    - label: Ok\n");
        DialogInfo dialog = one(parse(), "mypack:welcome");
        assertNotNull(dialog);
        assertTrue(dialog.json().contains("\"type\": \"minecraft:notice\""));
        assertTrue(dialog.json().contains("\"action\":"));
        assertTrue(dialog.json().contains("minecraft:plain_message"));
        assertFalse(dialog.fromPushedPack());
    }

    @Test
    void twoButtonsAreAConfirmation() throws IOException {
        write("mypack/dialogs/menus.yml",
                "quit:\n"
                        + "  title: Sure?\n"
                        + "  buttons:\n"
                        + "    - label: Yes\n"
                        + "      command: spawn\n"
                        + "    - label: No\n");
        String json = one(parse(), "mypack:quit").json();
        assertTrue(json.contains("\"type\": \"minecraft:confirmation\""));
        assertTrue(json.contains("\"yes\":"));
        assertTrue(json.contains("\"no\":"));
        assertTrue(json.contains("\"type\": \"minecraft:run_command\""));
        assertTrue(json.contains("\"command\": \"spawn\""));
    }

    @Test
    void threeButtonsAreAGridWithColumns() throws IOException {
        write("mypack/dialogs/menus.yml",
                "shop:\n"
                        + "  columns: 3\n"
                        + "  buttons:\n"
                        + "    - label: One\n"
                        + "    - label: Two\n"
                        + "    - label: Three\n");
        String json = one(parse(), "mypack:shop").json();
        assertTrue(json.contains("\"type\": \"minecraft:multi_action\""));
        assertTrue(json.contains("\"columns\": 3"));
        assertEquals(3, json.split("\"label\"", -1).length - 1);
    }

    /**
     * A hand-written dialog declares its player settings as a Studio one does,
     * so a toggle built in YAML works without Studio: the names and values are
     * what /rp var will accept, and anything that could not be one is warned
     * about and left out.
     */
    @Test
    void aDialogDeclaresThePlayerSettingsItShows() throws IOException {
        write("mypack/dialogs/menus.yml",
                "settings:\n"
                        + "  title: \"Sidebar: {show_sidebar?off:Off|on:On}\"\n"
                        + "  vars:\n"
                        + "    show_sidebar: [on, off]\n"
                        + "    Bad Name: [x]\n"
                        + "    chat: [all, \"not ok\"]\n"
                        + "  buttons:\n"
                        + "    - label: Toggle\n"
                        + "      command: \"rp var show_sidebar {show_sidebar?off:on|on:off}\"\n");
        DialogDefinitions.Result result = parse();
        DialogInfo dialog = one(result, "mypack:settings");
        assertNotNull(dialog);
        assertEquals(java.util.List.of("on", "off"), dialog.variables().get("show_sidebar"));
        assertEquals(java.util.List.of("all"), dialog.variables().get("chat"));
        assertEquals(2, dialog.variables().size());
        assertTrue(result.diagnostics().size() >= 2, "a bad name and a bad value are both warned about");
    }

    /** A screen with no way out is the one thing refused rather than warned about. */
    @Test
    void aDialogNobodyCanLeaveIsRefused() throws IOException {
        write("mypack/dialogs/menus.yml",
                "trap:\n"
                        + "  title: Ha\n"
                        + "  can_close_with_escape: false\n");
        DialogDefinitions.Result result = parse();
        assertNull(one(result, "mypack:trap"));
        assertTrue(result.diagnostics().stream()
                .anyMatch(d -> d.severity() == Diagnostic.Severity.ERROR));
    }

    /** The escape hatch: a file of the game's own JSON, transported unread. */
    @Test
    void aJsonFileIsTakenVerbatim() throws IOException {
        write("mypack/dialogs/raw.json", "{\"type\":\"minecraft:something_new\"}");
        write("mypack/dialogs/menus.yml",
                "future:\n"
                        + "  json: mypack/dialogs/raw.json\n");
        assertEquals("{\"type\":\"minecraft:something_new\"}", one(parse(), "mypack:future").json());
    }

    @Test
    void aMissingJsonFileIsAnError() throws IOException {
        write("mypack/dialogs/menus.yml",
                "future:\n"
                        + "  json: nowhere.json\n");
        DialogDefinitions.Result result = parse();
        assertNull(one(result, "mypack:future"));
        assertTrue(result.diagnostics().stream()
                .anyMatch(d -> d.severity() == Diagnostic.Severity.ERROR));
    }

    /** Quoting is the only escaping this file does, so it is the one to check. */
    @Test
    void quotesAndUnicodeSurviveIntoJson() throws IOException {
        write("mypack/dialogs/menus.yml",
                "odd:\n"
                        + "  title: 'He said \"hi\"'\n"
                        + "  buttons:\n"
                        + "    - label: Ok\n");
        String json = one(parse(), "mypack:odd").json();
        assertTrue(json.contains("\\\"hi\\\""));
    }

    // A page of a dialog with pages stays up when clicked (after: none), and the
    // game refuses a dialog that pauses under none — so none does not pause
    // unless told to, and told to, is warned about and still does not.

    @Test
    void stayingUpDoesNotPause() throws IOException {
        write("mypack/dialogs/pages.yml",
                "settings:\n"
                        + "  after: none\n"
                        + "  buttons:\n"
                        + "    - label: Audio\n"
                        + "      command: rp page mypack:audio\n"
                        + "audio:\n"
                        + "  after: none\n"
                        + "  pause: true\n"
                        + "  buttons:\n"
                        + "    - label: Close\n"
                        + "      command: rp page close\n"
                        + "plain:\n"
                        + "  buttons:\n"
                        + "    - label: Ok\n");
        DialogDefinitions.Result result = parse();
        String settings = one(result, "mypack:settings").json();
        assertTrue(settings.contains("\"after_action\": \"none\""));
        assertTrue(settings.contains("\"pause\": false"));
        assertTrue(one(result, "mypack:audio").json().contains("\"pause\": false"));
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.severity() == Diagnostic.Severity.WARNING),
                "asking for a pause it cannot have is said");
        assertTrue(one(result, "mypack:plain").json().contains("\"pause\": true"), "a dialog that closes still pauses");
        // And the page link an authored button writes is one /rp page honours.
        assertTrue(DialogLinks.opens(settings, ContentId.parse("mypack:audio").orElseThrow()));
    }
}
