package ai.resourcepack.engine.core.command;

import java.util.List;

/**
 * One line of {@code /rp}'s help.
 *
 * <p>A subcommand, what it takes, and what it does — three fields rather than
 * one string, so the router can align them into columns instead of printing a
 * ragged list. Minecraft's chat is monospace, which is the only reason that
 * works at all.
 */
final class Help {

    private final String sub;
    private final String args;
    private final String text;

    private Help(String sub, String args, String text) {
        this.sub = sub;
        this.args = args;
        this.text = text;
    }

    /** A subcommand that takes no arguments. */
    static Help of(String sub, String text) {
        return new Help(sub, "", text);
    }

    /** A subcommand and its arguments, written as a player would read them. */
    static Help of(String sub, String args, String text) {
        return new Help(sub, args, text);
    }

    /**
     * The subcommand this line is about — the FIRST word of it.
     *
     * <p>A line may name a verb inside a subcommand ("sync add", "liquid
     * fill"), because that is how a person reads the command; the router
     * dispatches and gates on the subcommand itself, which is this.
     */
    String command() {
        int space = sub.indexOf(' ');
        return space < 0 ? sub : sub.substring(0, space);
    }

    /** `give <id> [n]` — the whole left-hand column, without the command. */
    String signature() {
        return args.isEmpty() ? sub : sub + " " + args;
    }

    String text() {
        return text;
    }

    /** The width the left column needs to fit all of {@code lines}. */
    static int width(List<Help> lines) {
        int widest = 0;
        for (Help line : lines) {
            widest = Math.max(widest, line.signature().length());
        }
        return widest;
    }

    /** This line, padded to {@code width}. */
    String render(int width) {
        StringBuilder out = new StringBuilder("  ").append(signature());
        while (out.length() < width + 4) {
            out.append(' ');
        }
        return out.append(text).toString();
    }
}
