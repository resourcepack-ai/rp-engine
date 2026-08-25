package ai.resourcepack.engine.core.command;

import ai.resourcepack.engine.api.ContentId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompletionsTest {

    private static List<ContentId> ids(String... raw) {
        return java.util.Arrays.stream(raw).map(id -> ContentId.parse(id).orElseThrow()).toList();
    }

    @Test
    void matchesByPrefixAndSorts() {
        assertEquals(List.of("reload"), Completions.matching("rel", "reload", "items", "give"));
        assertEquals(List.of("give", "items", "reload"),
                Completions.matching("", "reload", "items", "give"));
    }

    @Test
    void matchingIsCaseInsensitive() {
        assertEquals(List.of("reload"), Completions.matching("REL", "reload"));
    }

    @Test
    void nothingTypedOffersEverything() {
        assertEquals(2, Completions.matching(null, "a", "b").size());
    }

    @Test
    void anIdCompletesFromItsNamespace() {
        assertEquals(List.of("gallery:luigi"),
                Completions.matchingIds("gall", ids("gallery:luigi", "example:ruby")));
    }

    @Test
    void anIdAlsoCompletesFromItsPath() {
        // Somebody who knows they want "ruby" and has forgotten which pack it
        // is in should be shown the answer, not asked for it. Completion that
        // requires you to already know is not completion.
        assertEquals(List.of("example:ruby"),
                Completions.matchingIds("ruby", ids("gallery:luigi", "example:ruby")));
    }

    @Test
    void oncePastTheColonOnlyTheFullFormMatches() {
        // "example:r" is somebody halfway through a namespaced id, so offering
        // matches from other packs would be noise at exactly the moment they
        // have said which pack they mean.
        assertEquals(List.of("example:ruby"),
                Completions.matchingIds("example:r", ids("gallery:ruby_thing", "example:ruby")));
    }

    @Test
    void aNestedPathCompletesTheWholeThing() {
        assertEquals(List.of("mypack:weapons/sword"),
                Completions.matchingIds("weap", ids("mypack:weapons/sword", "mypack:ruby")));
    }

    @Test
    void resultsAreSortedSoTheListDoesNotJumpAround() {
        assertEquals(List.of("a:one", "b:two", "c:three"),
                Completions.matchingIds("", ids("c:three", "a:one", "b:two")));
    }

    @Test
    void nothingMatchingIsAnEmptyListRatherThanEverything() {
        assertTrue(Completions.matching("zzz", "reload", "items").isEmpty());
        assertTrue(Completions.matchingIds("zzz", ids("a:one")).isEmpty());
    }
}
