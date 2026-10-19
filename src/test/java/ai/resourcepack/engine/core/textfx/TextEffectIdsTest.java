package ai.resourcepack.engine.core.textfx;

import ai.resourcepack.engine.api.ContentId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TextEffectIdsTest {

    @TempDir
    Path dir;

    private static ContentId id(String s) {
        return ContentId.parse(s).orElseThrow();
    }

    @Test
    void handedOutFromTheTopAndKeptAcrossARestart() {
        Path file = dir.resolve("ids.properties");
        TextEffectIds ids = new TextEffectIds(file, 32);
        Map<ContentId, Integer> first = ids.assign(List.of(id("a:fire"), id("a:wave")), Set.of());
        assertEquals(31, first.get(id("a:fire")));
        assertEquals(30, first.get(id("a:wave")));
        ids.save(null);

        TextEffectIds again = new TextEffectIds(file, 32);
        again.load(null);
        // A new one comes in front of the old ones in the list, and nobody moves.
        Map<ContentId, Integer> second = again.assign(List.of(id("a:aaa"), id("a:fire"), id("a:wave")), Set.of());
        assertEquals(31, second.get(id("a:fire")));
        assertEquals(30, second.get(id("a:wave")));
        assertEquals(29, second.get(id("a:aaa")));
    }

    @Test
    void aDeletedEffectsColourIsKeptUntilThereIsNoOther() {
        TextEffectIds ids = new TextEffectIds(null, 3);
        ids.assign(List.of(id("a:one")), Set.of());
        Map<ContentId, Integer> later = ids.assign(List.of(id("a:two")), Set.of());
        assertEquals(1, later.get(id("a:two")));
        // Three slots, one held by a deleted effect: the fourth takes it.
        Map<ContentId, Integer> full = ids.assign(List.of(id("a:two"), id("a:three"), id("a:four")), Set.of());
        assertEquals(1, full.get(id("a:two")));
        assertEquals(0, full.get(id("a:three")));
        assertEquals(2, full.get(id("a:four")));
    }

    @Test
    void studiosColoursAreAvoidedWhileThereIsAChoice() {
        TextEffectIds ids = new TextEffectIds(null, 4);
        Map<ContentId, Integer> got = ids.assign(List.of(id("a:x"), id("a:y")), Set.of(3, 2));
        assertEquals(1, got.get(id("a:x")));
        assertEquals(0, got.get(id("a:y")));
        Map<ContentId, Integer> more = ids.assign(List.of(id("a:x"), id("a:y"), id("a:z")), Set.of(3, 2));
        assertEquals(3, more.get(id("a:z")));
    }

    @Test
    void moreThanFitLeavesTheRestOut() {
        TextEffectIds ids = new TextEffectIds(null, 1);
        Map<ContentId, Integer> got = ids.assign(List.of(id("a:x"), id("a:y")), Set.of());
        assertEquals(1, got.size());
        assertFalse(got.containsKey(id("a:y")));
    }
}
