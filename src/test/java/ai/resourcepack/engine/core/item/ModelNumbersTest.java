package ai.resourcepack.engine.core.item;

import ai.resourcepack.engine.api.ContentId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelNumbersTest {

    private static final Logger LOG = Logger.getLogger(ModelNumbersTest.class.getName());

    private static ContentId id(String text) {
        return ContentId.parse(text).orElseThrow();
    }

    @Test
    void assignsFromOneAndKeepsWhatItAssigned(@TempDir Path dir) {
        ModelNumbers numbers = new ModelNumbers(dir.toFile());
        int ruby = numbers.of(id("mypack:ruby"));
        assertEquals(1, ruby);
        // Asked twice is the same answer, which is the entire point.
        assertEquals(ruby, numbers.of(id("mypack:ruby")));
        assertEquals(2, numbers.of(id("mypack:sapphire")));
    }

    @Test
    void survivesARestart(@TempDir Path dir) {
        ModelNumbers first = new ModelNumbers(dir.toFile());
        int ruby = first.of(id("mypack:ruby"));
        int sapphire = first.of(id("mypack:sapphire"));
        first.save(LOG);

        ModelNumbers second = new ModelNumbers(dir.toFile());
        second.load(LOG);
        assertEquals(ruby, second.of(id("mypack:ruby")));
        assertEquals(sapphire, second.of(id("mypack:sapphire")));
    }

    @Test
    void neverReusesTheNumberOfDeletedContent(@TempDir Path dir) {
        // The failure this class exists to prevent. An item deleted from the
        // pack is still in somebody's chest, and handing its number to the
        // next thing turns that item into this one.
        ModelNumbers first = new ModelNumbers(dir.toFile());
        int gone = first.of(id("mypack:deleted"));
        first.save(LOG);

        ModelNumbers second = new ModelNumbers(dir.toFile());
        second.load(LOG);
        // The content folder no longer mentions the deleted id at all.
        int fresh = second.of(id("mypack:brand-new"));
        assertNotEquals(gone, fresh);
        assertTrue(fresh > gone);
    }

    @Test
    void addingContentDoesNotDisturbWhatExists(@TempDir Path dir) {
        ModelNumbers first = new ModelNumbers(dir.toFile());
        first.assignAll(List.of(id("mypack:ruby"), id("mypack:sapphire")));
        int ruby = first.of(id("mypack:ruby"));
        int sapphire = first.of(id("mypack:sapphire"));
        first.save(LOG);

        ModelNumbers second = new ModelNumbers(dir.toFile());
        second.load(LOG);
        // "amethyst" sorts before both, and must NOT push either along.
        second.assignAll(List.of(id("mypack:amethyst"), id("mypack:ruby"), id("mypack:sapphire")));
        assertEquals(ruby, second.of(id("mypack:ruby")));
        assertEquals(sapphire, second.of(id("mypack:sapphire")));
    }

    @Test
    void twoServersBuildingTheSameFolderAgree(@TempDir Path one, @TempDir Path two) {
        // Not a guarantee once either has diverged, but from empty it costs
        // nothing and it means a pack built on a test server is the pack that
        // works on the live one.
        List<ContentId> content = Arrays.asList(
                id("mypack:sapphire"), id("mypack:ruby"), id("other:thing"));
        ModelNumbers here = new ModelNumbers(one.toFile());
        ModelNumbers there = new ModelNumbers(two.toFile());
        here.assignAll(content);
        // Reversed: allocation order must come from the ids, not the caller.
        List<ContentId> shuffled = Arrays.asList(
                id("other:thing"), id("mypack:ruby"), id("mypack:sapphire"));
        there.assignAll(shuffled);
        assertEquals(here.all(), there.all());
    }

    @Test
    void recoversTheCounterFromTheEntriesRatherThanTrustingIt(@TempDir Path dir) throws Exception {
        // A next that has been edited down — or written by a build that saved
        // before it — would hand out a number something already uses.
        File file = new File(dir.toFile(), "model-numbers.json");
        Files.write(file.toPath(),
                ("{\"numbers\":{\"mypack:ruby\":7,\"mypack:sapphire\":9},\"next\":2}")
                        .getBytes(StandardCharsets.UTF_8));

        ModelNumbers numbers = new ModelNumbers(dir.toFile());
        numbers.load(LOG);
        assertEquals(10, numbers.of(id("mypack:new")));
    }

    @Test
    void refusesToStartFreshOverAFileItCannotRead(@TempDir Path dir) throws Exception {
        // Allocating from scratch over a file that exists is how every number
        // moves at once, and it would look like a clean start.
        File file = new File(dir.toFile(), "model-numbers.json");
        Files.write(file.toPath(), "this is not json {{{".getBytes(StandardCharsets.UTF_8));

        ModelNumbers numbers = new ModelNumbers(dir.toFile());
        assertThrows(IllegalStateException.class, () -> numbers.load(LOG));
    }

    @Test
    void writesTheWarningIntoTheFileItself(@TempDir Path dir) throws Exception {
        ModelNumbers numbers = new ModelNumbers(dir.toFile());
        numbers.of(id("mypack:ruby"));
        numbers.save(LOG);
        String written = new String(
                Files.readAllBytes(new File(dir.toFile(), "model-numbers.json").toPath()),
                StandardCharsets.UTF_8);
        assertTrue(written.contains("DO NOT DELETE"),
                "somebody who finds this file has to be told what it is");
    }
}
