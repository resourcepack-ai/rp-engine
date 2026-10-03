package ai.resourcepack.engine.core.command;

/**
 * One line of {@code /rp}'s help.
 *
 * <p><strong>No columns, and short lines.</strong> Both are forced by the same
 * fact: Minecraft's chat font is proportional. An {@code i} is two pixels wide
 * and a {@code W} is six, so padding a column to an equal number of
 * <em>characters</em> lines nothing up — it just makes every row a different
 * length. This class had a {@code width()} and a padding {@code render()} that
 * did exactly that, on a comment claiming the font was monospace.
 *
 * <p>The second fact is that chat wraps at about 53 characters at default
 * settings, and a wrapped line of help is worse than a shorter description. So
 * a line is a command, a dash and a few words, and {@link #MAX_WIDTH} is the
 * budget — asserted by a test, because it is the kind of thing that is only
 * noticed by someone reading it in game.
 */
final class Help {

    /**
     * How wide a line of help may be, in characters.
     *
     * <p>Chat is 320 pixels at default width and most glyphs are six pixels,
     * so about 53 fit. This is under that, because a player may have narrowed
     * their chat and because the count is characters rather than pixels — a
     * line full of {@code W} is wider than a line full of {@code i}.
     */
    static final int MAX_WIDTH = 50;

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

    /** {@code give <id> [n]} — the command without its slash or its root. */
    String signature() {
        return args.isEmpty() ? sub : sub + " " + args;
    }

    String text() {
        return text;
    }

    /** What a player sees, with no colour in it. What the width test measures. */
    String plain() {
        return "  /rp " + signature() + " - " + text;
    }

    /** The line as it goes into chat: the command bright, the rest grey. */
    String render() {
        ChatStyle style = Reply.style();
        return style.command() + "  /rp " + signature() + style.body() + " - " + text;
    }
}
