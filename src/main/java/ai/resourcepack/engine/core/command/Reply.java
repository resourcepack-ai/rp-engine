package ai.resourcepack.engine.core.command;

import ai.resourcepack.engine.core.Chat;
import org.bukkit.command.CommandSender;

/**
 * How this plugin talks in chat.
 *
 * <p>One prefix and one palette, in one place. It was a literal at every call
 * site until the fourth area class copied it, which is exactly how a plugin
 * ends up with two spellings of its own name in the same chat window.
 *
 * <p>Every line goes out through {@link Chat}, which finds the commands in it
 * and makes them clickable. That is why this is worth having as a funnel at
 * all: one place to send from is one place to improve how sending works.
 *
 * <p><strong>The style is static, and set once at startup.</strong> Every area
 * writes to chat, so threading a {@link ChatStyle} through all of them would
 * be a parameter on every call for a value that is server-wide and changes
 * only on a reload. It is replaced whole rather than mutated, so a message
 * mid-flight reads one palette or the other and never half of each.
 */
final class Reply {

    private static volatile ChatStyle style = ChatStyle.defaults();

    private Reply() {
    }

    /** Adopts what the config said. Called on enable and on every reload. */
    static void style(ChatStyle configured) {
        style = configured == null ? ChatStyle.defaults() : configured;
    }

    /** The palette, for the few places that colour a line themselves. */
    static ChatStyle style() {
        return style;
    }

    /** One line, prefixed, in the body colour. */
    static void to(CommandSender who, String line) {
        Chat.send(who, style.prefix() + line);
    }

    /** One line that reports a failure. */
    static void error(CommandSender who, String line) {
        Chat.send(who, style.prefix() + style.error() + line);
    }

    /** Something worth picking out of a sentence — a name, a count, a code. */
    static String accent(Object value) {
        return style.accent() + value + style.body();
    }

    /**
     * A heading over a list, in the accent colour and bold.
     *
     * <p>A listing that is just rows is a data dump: the eye has nothing to
     * land on and no way to tell where one command's output ended and the next
     * began, which in a chat window is the only structure there is.
     */
    static void heading(CommandSender who, String title, String detail) {
        Chat.send(who, style().heading() + title
                + (detail == null || detail.isEmpty() ? "" : style().body() + "  " + detail));
    }

    /** One row of a listing: a name in the command colour, then grey detail. */
    static void row(CommandSender who, String name, String detail) {
        Chat.send(who, "  " + style().command() + name
                + (detail == null || detail.isEmpty() ? "" : style().body() + "  " + detail));
    }

    /** A row's second line, indented under it. */
    static void note(CommandSender who, String detail) {
        Chat.send(who, "     " + style().body() + detail);
    }

    /**
     * Bytes, as somebody would say them.
     *
     * <p>A pack is quoted in bytes nowhere else in the product, and "3894217"
     * is a number nobody can read at a glance.
     */
    static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return Math.round(bytes / 1024.0) + " KB";
        }
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** "1 item", "2 items". Its absence is the sort of thing that reads as unfinished. */
    static String plural(int count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }
}
