package ai.resourcepack.engine.core.sync;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.OverlayInfo;
import ai.resourcepack.engine.api.SoundInfo;
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
 * <p>Its shape is written by studio's `Studio's content-manifest writer`, so the JSON in
 * here is what that file emits rather than what would be convenient.
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
}
