package ai.resourcepack.engine.core.registry;

import ai.resourcepack.engine.api.ClaimResult;
import ai.resourcepack.engine.api.ContentEntry;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.Namespace;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentRegistryImplTest {

    private final ContentRegistryImpl registry = new ContentRegistryImpl();

    private Namespace claim(String name, ContentSource source) {
        return registry.claim(name, source).namespace().orElseThrow();
    }

    @Test
    void definesAndFindsContent() {
        Namespace pack = claim("mypack", ContentSource.AUTHORED);
        ContentEntry entry = pack.define(ContentKind.ITEM, "ruby").orElseThrow();

        assertEquals(ContentKind.ITEM, entry.kind());
        assertEquals(ContentSource.AUTHORED, entry.source());
        assertTrue(registry.contains(entry.id()));
        assertTrue(registry.contains(entry.id(), ContentKind.ITEM));
        assertFalse(registry.contains(entry.id(), ContentKind.BLOCK));
        assertEquals(entry, registry.entry("mypack:ruby").orElseThrow());
    }

    @Test
    void studioAndAuthoredContentCoexist() {
        // The whole design in one test: two sources, one id space, neither
        // outranking the other, and no shared numeric counter to collide over.
        Namespace authored = claim("mypack", ContentSource.AUTHORED);
        Namespace studio = claim("coolpack", ContentSource.STUDIO);
        authored.define(ContentKind.ITEM, "ruby");
        studio.define(ContentKind.ITEM, "ruby");

        assertEquals(
                List.of("coolpack:ruby", "mypack:ruby"),
                registry.ids(ContentKind.ITEM).stream().map(ContentId::toString).toList());
        assertEquals(ContentSource.STUDIO, registry.sourceOf("coolpack").orElseThrow());
        assertEquals(ContentSource.AUTHORED, registry.sourceOf("mypack").orElseThrow());
    }

    @Test
    void firstClaimWinsAndSaysWhoHoldsIt() {
        claim("mypack", ContentSource.AUTHORED);
        ClaimResult second = registry.claim("mypack", ContentSource.STUDIO);

        assertFalse(second.success());
        assertEquals(ClaimResult.Reason.ALREADY_CLAIMED, second.reason());
        assertEquals(ContentSource.AUTHORED, second.heldBy().orElseThrow());
        assertTrue(second.namespace().isEmpty());
    }

    @Test
    void vanillaNamespacesAreRefused() {
        assertEquals(ClaimResult.Reason.RESERVED, registry.claim("minecraft", ContentSource.AUTHORED).reason());
        assertEquals(ClaimResult.Reason.RESERVED, registry.claim("realms", ContentSource.STUDIO).reason());
    }

    @Test
    void illegalNamespacesAreRefused() {
        assertEquals(ClaimResult.Reason.INVALID, registry.claim("My Pack", ContentSource.AUTHORED).reason());
        assertEquals(ClaimResult.Reason.INVALID, registry.claim(null, ContentSource.AUTHORED).reason());
        assertEquals(ClaimResult.Reason.INVALID, registry.claim("mypack", null).reason());
    }

    @Test
    void aHandleCannotReachOutsideItsNamespace() {
        Namespace pack = claim("mypack", ContentSource.AUTHORED);
        assertTrue(pack.define(ContentKind.ITEM, "chairs/oak").isPresent());
        // There is no way to express "otherpack:thing" through this handle at
        // all, which is the point of registration being handle-based.
        assertEquals("mypack", registry.entry("mypack:chairs/oak").orElseThrow().id().namespace());
    }

    @Test
    void aDuplicateIdKeepsTheFirstDefinition() {
        Namespace pack = claim("mypack", ContentSource.AUTHORED);
        pack.define(ContentKind.ITEM, "ruby");
        assertTrue(pack.define(ContentKind.BLOCK, "ruby").isEmpty());
        assertEquals(ContentKind.ITEM, registry.entry("mypack:ruby").orElseThrow().kind());
    }

    @Test
    void illegalPathsAreRefused() {
        Namespace pack = claim("mypack", ContentSource.AUTHORED);
        assertTrue(pack.define(ContentKind.ITEM, "Ruby").isEmpty());
        assertTrue(pack.define(ContentKind.ITEM, "").isEmpty());
        assertTrue(pack.define(ContentKind.ITEM, null).isEmpty());
        assertTrue(pack.define(null, "ruby").isEmpty());
    }

    @Test
    void releaseDropsTheNamespaceWhole() {
        Namespace pack = claim("mypack", ContentSource.AUTHORED);
        pack.define(ContentKind.ITEM, "ruby");
        pack.define(ContentKind.BLOCK, "ore");
        claim("keepme", ContentSource.STUDIO).define(ContentKind.ITEM, "ruby");

        pack.release();

        assertFalse(pack.active());
        assertTrue(pack.define(ContentKind.ITEM, "late").isEmpty(), "a released handle defines nothing");
        assertTrue(registry.idsIn("mypack").isEmpty());
        assertTrue(registry.sourceOf("mypack").isEmpty());
        assertEquals(List.of("keepme:ruby"), registry.idsIn("keepme").stream().map(ContentId::toString).toList());
    }

    @Test
    void aReloadCanClaimTheSameNamespaceAgain() {
        Namespace first = claim("mypack", ContentSource.AUTHORED);
        first.define(ContentKind.ITEM, "ruby");
        first.release();

        Namespace second = claim("mypack", ContentSource.AUTHORED);
        assertTrue(second.define(ContentKind.ITEM, "ruby").isPresent());
    }

    @Test
    void readsAnswerEmptyRatherThanThrowing() {
        assertTrue(registry.entry("not even an id").isEmpty());
        assertTrue(registry.entry((ContentId) null).isEmpty());
        assertTrue(registry.entry((String) null).isEmpty());
        assertTrue(registry.sourceOf(null).isEmpty());
        assertTrue(registry.idsIn(null).isEmpty());
        assertTrue(registry.ids(null).isEmpty());
        assertFalse(registry.contains(null));
    }
}
