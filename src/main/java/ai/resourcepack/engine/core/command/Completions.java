package ai.resourcepack.engine.core.command;

import ai.resourcepack.engine.api.ContentId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Filtering a list of suggestions by what somebody has typed so far.
 *
 * <p><strong>Every command in this plugin completes every argument.</strong>
 * That is a rule rather than a nicety: an id here is
 * {@code namespace:some/nested/path}, nobody is typing that from memory, and a
 * command whose arguments cannot be discovered is a command only its author
 * can use. A new subcommand without a completion is an unfinished subcommand.
 *
 * <p>Free of Bukkit so the matching rules are tested rather than eyeballed in
 * a chat box.
 */
public final class Completions {

    private Completions() {
    }

    /** The entries of {@code options} that start with {@code typed}, sorted. */
    public static List<String> matching(String typed, Collection<String> options) {
        String prefix = typed == null ? "" : typed.toLowerCase(Locale.ROOT);
        List<String> found = new ArrayList<>();
        for (String option : options) {
            if (option != null && option.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                found.add(option);
            }
        }
        Collections.sort(found);
        return found;
    }

    /** As {@link #matching(String, Collection)}, over a fixed list. */
    public static List<String> matching(String typed, String... options) {
        return matching(typed, List.of(options));
    }

    /**
     * Content ids, completed the way somebody actually types one.
     *
     * <p>The plain prefix match is offered first, and then — while nothing has
     * been typed past the colon — the bare paths as well. A player who knows
     * they want {@code ruby} and has forgotten which pack it is in can type
     * that and be shown {@code gallery:ruby}, which is the difference between
     * completion that helps and completion that requires you to already know
     * the answer.
     */
    public static List<String> matchingIds(String typed, Collection<ContentId> ids) {
        String prefix = typed == null ? "" : typed.toLowerCase(Locale.ROOT);
        List<String> found = new ArrayList<>();
        for (ContentId id : ids) {
            String full = id.toString();
            if (full.startsWith(prefix)
                    || (prefix.indexOf(':') < 0 && id.path().startsWith(prefix))) {
                found.add(full);
            }
        }
        Collections.sort(found);
        return found;
    }
}
