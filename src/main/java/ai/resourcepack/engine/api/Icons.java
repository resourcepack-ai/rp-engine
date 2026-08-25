package ai.resourcepack.engine.api;

import java.util.Collection;
import java.util.Optional;

/**
 * The icons this server holds, and the way to put one into a piece of text.
 *
 * <p>Free of Bukkit and safe from any thread: an icon is a character, and
 * turning an id into one involves nothing but a lookup.
 */
public interface Icons {

    /** Every icon id, sorted. */
    Collection<ContentId> ids();

    /** What the pack said an icon is, or empty if there is no such icon. */
    Optional<IconInfo> info(ContentId id);

    /** As {@link #info(ContentId)}, from the text form of an id. */
    Optional<IconInfo> info(String id);

    /**
     * The character for an icon, or empty if there is no such icon.
     *
     * <p>Resolve at the moment you write the text, never earlier. A codepoint
     * moves when content changes — see {@link IconInfo#codepoint()} — so a
     * character stored in a config file, a database or a sign becomes a
     * different picture after the next reload, while an id stored in the same
     * places stays correct for ever.
     */
    Optional<String> character(ContentId id);

    /**
     * Replaces every {@code :namespace:id:} in {@code text} with its icon.
     *
     * <p>This is how an icon reaches a config file somebody else wrote. A
     * server owner puts {@code :mypack:sword:} in a message, a scoreboard, a
     * menu title, and it becomes the picture wherever that text is rendered.
     *
     * <p>An id that names no icon is <strong>left exactly as written</strong>
     * rather than removed. Text that silently loses a chunk of itself is far
     * harder to diagnose than text that visibly still says
     * {@code :mypack:sword:}, and the second tells somebody what to search for.
     */
    String format(String text);
}
