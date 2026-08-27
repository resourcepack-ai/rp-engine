package ai.resourcepack.engine.core.sync;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The one part of {@link PlayerCape} that can be tested without a server.
 *
 * <p>Worth testing rather than obvious, because everything downstream of it is
 * invisible when it is wrong: a hash that comes out mangled reaches studio,
 * fails a shape check there or fetches a 404, and the symptom is a player with
 * no cape — which is what most players legitimately look like.
 */
class PlayerCapeTest {

    private static URL url(String spec) {
        try {
            return URI.create(spec).toURL();
        } catch (Exception e) {
            throw new IllegalArgumentException(spec, e);
        }
    }

    @Test
    void readsTheHashOffMojangsUrl() {
        assertEquals("2340c0e03dd24a11b15a8b33c2a7e9e32abb2051b2481d0ba7defd635ca7a933",
            PlayerCape.hashOf(url("http://textures.minecraft.net/texture/"
                + "2340c0e03dd24a11b15a8b33c2a7e9e32abb2051b2481d0ba7defd635ca7a933")));
    }

    @Test
    void acceptsHttpsAndUppercase() {
        assertEquals("abcdef0123", PlayerCape.hashOf(url("https://textures.minecraft.net/texture/ABCDEF0123")));
    }

    @Test
    void nothingToReport() {
        assertNull(PlayerCape.hashOf((URL) null));
    }

    /**
     * The wire separates fields with spaces and colons, so anything that is not
     * a hash has to be refused rather than passed along. Mojang would never
     * send one of these; a proxy, a plugin that rewrites profiles, or an
     * offline-mode server standing in for one might.
     */
    @Test
    void refusesAnythingThatIsNotAHash() {
        // Percent-encoded rather than a literal space: a URL cannot hold one,
        // so this is the shape the awkward case actually arrives in — and
        // getPath does not decode, which is what leaves the escape in the
        // segment to be refused.
        assertNull(PlayerCape.hashOf(url("https://example.test/texture/not%20a%20hash")));
        assertNull(PlayerCape.hashOf(url("https://example.test/texture/zzzz")));
        assertNull(PlayerCape.hashOf(url("https://example.test/texture/")));
        assertNull(PlayerCape.hashOf(url("https://example.test/texture/dead:beef")));
    }
}
