package ai.resourcepack.engine.core.edit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replacing one entry and leaving the rest of somebody's file alone.
 *
 * <p>The cases that matter are the destructive ones, and they are all about
 * where an entry ENDS: a heading comment written above the next vehicle is,
 * textually, inside the previous one's block, and swallowing it would delete
 * it every time anybody saved.
 */
class YamlBlocksTest {

    private static final String FILE = String.join("\n",
            "# The fleet",
            "",
            "hatchback:",
            "  model: mypack:hatchback",
            "  speed: 18",
            "  seats:",
            "    - {role: driver, y: 0.6}",
            "",
            "# The big one",
            "bus:",
            "  model: mypack:bus",
            "  speed: 9",
            "",
            "tractor:",
            "  speed: 4",
            "");

    @Test
    void findsATopLevelEntry() {
        int[] range = YamlBlocks.find(FILE, "hatchback");
        assertNotNull(range);
        assertEquals(2, range[0]);
        // Ends before the blank line and the comment that belong to `bus`.
        assertEquals(7, range[1]);
    }

    @Test
    void anEntryThatIsNotThereIsNotFound() {
        assertNull(YamlBlocks.find(FILE, "lorry"));
        assertNull(YamlBlocks.find(FILE, "speed"), "an indented key is not a top-level entry");
        assertNull(YamlBlocks.find(FILE, "model"));
    }

    @Test
    void theHeadingAboveTheNextEntrySurvives() {
        String updated = YamlBlocks.replace(FILE, "hatchback", "hatchback:\n  speed: 20");
        assertNotNull(updated);
        assertTrue(updated.contains("# The big one\nbus:"), updated);
        assertTrue(updated.contains("# The fleet"), "the file's own heading stays");
        assertTrue(updated.contains("  speed: 20"));
        assertTrue(updated.contains("tractor:\n  speed: 4"), "the entries after it are untouched");
    }

    @Test
    void theFirstAndLastEntriesBothWork() {
        String first = YamlBlocks.replace(FILE, "hatchback", "hatchback:\n  speed: 1");
        assertTrue(first.startsWith("# The fleet\n\nhatchback:\n  speed: 1\n"), first);

        String last = YamlBlocks.replace(FILE, "tractor", "tractor:\n  speed: 99");
        assertTrue(last.endsWith("tractor:\n  speed: 99\n"), last);
        assertTrue(last.contains("bus:"));
    }

    @Test
    void replacingSomethingAbsentAnswersNull() {
        assertNull(YamlBlocks.replace(FILE, "lorry", "lorry:\n  speed: 1"));
    }

    /**
     * A key an author quoted is still their key. Both quote styles, because a
     * vehicle id with a dot in it is usually written one of the two ways.
     */
    @Test
    void quotedKeysAreFound() {
        String file = "\"my.car\":\n  speed: 3\n'other':\n  speed: 4\n";
        assertNotNull(YamlBlocks.find(file, "my.car"));
        assertNotNull(YamlBlocks.find(file, "other"));
    }

    /** A list item at the root is not an entry, and neither is a document marker. */
    @Test
    void sequencesAndDocumentMarkersAreNotEntries() {
        String file = "---\n- one\n- two\ncar:\n  speed: 3\n";
        int[] range = YamlBlocks.find(file, "car");
        assertNotNull(range);
        assertEquals(3, range[0]);
    }

    /** A file with one entry and nothing else: the whole thing is the block. */
    @Test
    void oneEntryFile() {
        String file = "car:\n  speed: 3\n";
        String updated = YamlBlocks.replace(file, "car", "car:\n  speed: 9");
        assertEquals("car:\n  speed: 9\n", updated);
    }

    /** Windows line endings, because half of these files are written on one. */
    @Test
    void carriageReturnsDoNotHideAnEntry() {
        String file = "car:\r\n  speed: 3\r\nbus:\r\n  speed: 4\r\n";
        assertNotNull(YamlBlocks.find(file, "car"));
        assertNotNull(YamlBlocks.find(file, "bus"));
    }
}
