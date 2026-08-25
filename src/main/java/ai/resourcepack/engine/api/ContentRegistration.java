package ai.resourcepack.engine.api;

/**
 * The write half of the registry.
 *
 * <p>Separate from {@link ContentRegistry} so that the thing handed to a
 * consumer asking what exists is not also the thing that can define content.
 * A plugin embedding the engine holds both; a plugin merely reading the
 * catalogue holds only the registry.
 */
public interface ContentRegistration {

    /**
     * Asks for exclusive ownership of {@code namespace}.
     *
     * <p>First claim wins, and there is no priority order between sources on
     * purpose. Studio content does not outrank a server owner's hand-authored
     * pack, because the moment it does, the two stop being interchangeable
     * and this stops being a plugin somebody can use without us.
     */
    ClaimResult claim(String namespace, ContentSource source);
}
