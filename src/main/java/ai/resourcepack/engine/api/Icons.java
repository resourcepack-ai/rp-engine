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
     *
     * <p>The FIRST frame of an animated icon, always. That is the right answer
     * for anything drawn once — a chat line, an item name, a book — and the
     * stable one: the same id gives the same character however long the server
     * has been up. Something you redraw yourself wants
     * {@link #characterNow(ContentId)}.
     */
    Optional<String> character(ContentId id);

    /**
     * The character of the frame an icon is showing right now, or empty if
     * there is no such icon. The same as {@link #character(ContentId)} for a
     * still picture.
     *
     * <p>This is how an icon animates. Each frame is a glyph of its own and
     * the server picks which one to send, so the animation exists only in text
     * that is sent again as time passes: a scoreboard line you update, a boss
     * bar, a hologram, an action bar you repeat. Call this every time you
     * redraw — a frame lasts {@code 1000 / fps} milliseconds, so redrawing
     * once a tick shows every frame of anything up to 20 fps, and redrawing
     * once a second shows one frame a second.
     */
    default Optional<String> characterNow(ContentId id) {
        return character(id);
    }

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
     *
     * <p>Animated icons come out as their first frame, as
     * {@link #character(ContentId)} does.
     */
    String format(String text);

    /**
     * As {@link #format(String)}, with every animated icon at the frame it is
     * showing right now — for text you send again and again, so that each send
     * is the next picture. See {@link #characterNow(ContentId)}.
     */
    default String formatNow(String text) {
        return format(text);
    }
}
