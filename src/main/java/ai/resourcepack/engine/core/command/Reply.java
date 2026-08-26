package ai.resourcepack.engine.core.command;

import org.bukkit.command.CommandSender;

/**
 * How this plugin talks in chat.
 *
 * <p>One prefix, in one place. It was a literal at every call site until the
 * fourth area class copied it, which is exactly how a plugin ends up with two
 * spellings of its own name in the same chat window.
 */
final class Reply {

    static final String PREFIX = "[RPEngine] ";

    private Reply() {
    }

    /** One line, prefixed. */
    static void to(CommandSender who, String line) {
        who.sendMessage(PREFIX + line);
    }

    /** "1 item", "2 items". Its absence is the sort of thing that reads as unfinished. */
    static String plural(int count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }
}
