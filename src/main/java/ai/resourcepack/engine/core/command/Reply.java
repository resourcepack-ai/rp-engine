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

    /**
     * A group heading in the help: the brand blue, in bold.
     *
     * <p>Spelled as section signs rather than built from {@code ChatColor.of},
     * which needs a server to resolve a hex colour and so cannot be a constant
     * a test can load. The digits are #3670f8, one section sign each, which is
     * how the game has taken hex colours since 1.16.
     */
    static final String HEADING = "§x§3§6§7§0§f§8§l";

    /** A line of the help. Grey, so the headings carry the structure. */
    static final String BODY = "§7";

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
