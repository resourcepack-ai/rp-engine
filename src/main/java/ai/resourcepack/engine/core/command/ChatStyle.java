package ai.resourcepack.engine.core.command;

import java.util.Locale;

/**
 * How this plugin looks when it speaks.
 *
 * <p>Every colour is a hex string in the config, because a server owner's
 * palette is theirs and a plugin that shouts in its own brand colours on
 * somebody else's server is a plugin that gets its messages muted. The
 * defaults are ours: {@code #3670f8} is the brand blue, and the greys are the
 * hex equivalents of the legacy colours {@code server-plugin} uses, so the two
 * plugins sit in one chat window without disagreeing about what grey is.
 *
 * <p>Free of Bukkit on purpose. Colours are section-sign sequences, which are
 * a text format rather than an API, so this can be built and tested without a
 * server — and the plugin reads the config and hands the strings in.
 *
 * <p><strong>Hex colours are seven codes, not one.</strong> Since 1.16 the
 * game spells {@code #3670f8} as {@code §x§3§6§7§0§f§8}: a marker, then each
 * digit with its own section sign. Getting that wrong prints the digits.
 */
public final class ChatStyle {

    /** The brand blue. Also the default prefix colour. */
    public static final String BRAND = "#3670f8";

    private static final String LEGACY_GREY = "#aaaaaa";
    private static final String LEGACY_DARK_GREY = "#555555";
    private static final String LEGACY_RED = "#ff5555";
    private static final String LEGACY_GREEN = "#55ff55";
    private static final String WHITE = "#ffffff";

    private final String prefix;
    private final String body;
    private final String accent;
    private final String error;
    private final String success;
    private final String command;

    private ChatStyle(String prefix, String body, String accent, String error, String success,
                      String command) {
        this.prefix = prefix;
        this.body = body;
        this.accent = accent;
        this.error = error;
        this.success = success;
        this.command = command;
    }

    /** What the plugin looks like with nothing configured. */
    public static ChatStyle defaults() {
        return of("RPEngine", BRAND, LEGACY_DARK_GREY, LEGACY_GREY, BRAND, LEGACY_RED, LEGACY_GREEN);
    }

    /**
     * A style from what the config said.
     *
     * <p>Any colour that is not a hex triple falls back to its default rather
     * than throwing or printing raw digits: a typo in a colour is not a reason
     * for a plugin to stop talking, and the wrong shade of grey is a thing
     * somebody can see and fix.
     *
     * @param name     the word inside the brackets, uncoloured
     * @param brackets the colour of the brackets around it
     */
    public static ChatStyle of(String name, String prefixColour, String brackets, String body,
                               String accent, String error, String success) {
        String tag = name == null || name.isBlank() ? "RPEngine" : name.trim();
        String bracket = colour(brackets, LEGACY_DARK_GREY);
        return new ChatStyle(
                bracket + "[" + colour(prefixColour, BRAND) + tag + bracket + "] "
                        + colour(body, LEGACY_GREY),
                colour(body, LEGACY_GREY),
                colour(accent, BRAND),
                colour(error, LEGACY_RED),
                colour(success, LEGACY_GREEN),
                colour(WHITE, WHITE));
    }

    /** The whole tag, coloured, ending in the body colour. */
    public String prefix() {
        return prefix;
    }

    /** Ordinary text. */
    public String body() {
        return body;
    }

    /** Something worth picking out of a sentence — a name, a count, a code. */
    public String accent() {
        return accent;
    }

    /** Something went wrong. */
    public String error() {
        return error;
    }

    /** Something worked, where that is worth saying. */
    public String success() {
        return success;
    }

    /** A command somebody is meant to type. */
    public String command() {
        return command;
    }

    /** A heading in the help: the accent colour, in bold. */
    public String heading() {
        return accent + "§l";
    }

    /**
     * {@code #rrggbb} as the game spells it, or {@code fallback} if it is not
     * a hex triple.
     *
     * <p>Accepts it with or without the hash, and in either case, because a
     * config file is written by a person.
     */
    static String colour(String hex, String fallback) {
        String cleaned = hex == null ? "" : hex.trim().toLowerCase(Locale.ROOT);
        if (cleaned.startsWith("#")) {
            cleaned = cleaned.substring(1);
        }
        if (!cleaned.matches("[0-9a-f]{6}")) {
            return colour(fallback, "aaaaaa");
        }
        StringBuilder out = new StringBuilder("§x");
        for (char digit : cleaned.toCharArray()) {
            out.append('§').append(digit);
        }
        return out.toString();
    }
}
