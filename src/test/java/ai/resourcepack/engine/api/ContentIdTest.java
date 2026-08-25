package ai.resourcepack.engine.api;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentIdTest {

    @Test
    void parsesTheOrdinaryForm() {
        ContentId id = ContentId.parse("mypack:chairs/oak").orElseThrow();
        assertEquals("mypack", id.namespace());
        assertEquals("chairs/oak", id.path());
        assertEquals("mypack:chairs/oak", id.toString());
    }

    @Test
    void roundTripsThroughItsTextForm() {
        // Load-bearing: this string is what goes into the item_model component
        // and into a placed block's persistent data, and it has to come back.
        String text = "mypack:chairs/oak";
        assertEquals(text, ContentId.parse(text).orElseThrow().toString());
    }

    @Test
    void refusesWhatTheClientCannotBeTold() {
        assertTrue(ContentId.parse("MyPack:chair").isEmpty(), "uppercase is not folded, it is refused");
        assertTrue(ContentId.parse("my pack:chair").isEmpty());
        assertTrue(ContentId.parse("mypack:chair!").isEmpty());
        assertTrue(ContentId.parse("mypack").isEmpty(), "no colon");
        assertTrue(ContentId.parse("mypack:chair:oak").isEmpty(), "two colons");
        assertTrue(ContentId.parse(":chair").isEmpty());
        assertTrue(ContentId.parse("mypack:").isEmpty());
        assertTrue(ContentId.parse("").isEmpty());
        assertTrue(ContentId.parse(null).isEmpty());
    }

    @Test
    void slashesBelongToThePathOnly() {
        assertTrue(ContentId.parse("mypack:chairs/oak/tall").isPresent());
        assertTrue(ContentId.of("my/pack", "chair").isEmpty());
    }

    @Test
    void equalityIsByBothHalves() {
        ContentId a = ContentId.parse("mypack:chair").orElseThrow();
        ContentId b = ContentId.of("mypack", "chair").orElseThrow();
        ContentId other = ContentId.parse("otherpack:chair").orElseThrow();
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, other);
    }

    @Test
    void sortsByNamespaceThenPath() {
        ContentId a = ContentId.parse("a:z").orElseThrow();
        ContentId b = ContentId.parse("b:a").orElseThrow();
        assertTrue(a.compareTo(b) < 0);
        assertTrue(ContentId.parse("a:a").orElseThrow().compareTo(a) < 0);
    }

    @Test
    void rehomingKeepsThePath() {
        Optional<ContentId> moved = ContentId.parse("mypack:chair").orElseThrow().inNamespace("otherpack");
        assertEquals("otherpack:chair", moved.orElseThrow().toString());
        assertTrue(ContentId.parse("mypack:chair").orElseThrow().inNamespace("NOPE").isEmpty());
    }

    @Test
    void validityChecksAgreeWithParsing() {
        assertTrue(ContentId.isValidNamespace("my-pack_1.0"));
        assertFalse(ContentId.isValidNamespace(null));
        assertFalse(ContentId.isValidNamespace(""));
        assertTrue(ContentId.isValidPath("a/b/c"));
        assertFalse(ContentId.isValidPath("a b"));
    }
}
