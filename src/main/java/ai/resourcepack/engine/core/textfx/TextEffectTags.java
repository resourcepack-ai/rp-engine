package ai.resourcepack.engine.core.textfx;

import ai.resourcepack.engine.api.TextEffectInfo;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code <fx:name>...</fx>}, and the one pass that turns it into colour. Free
 * of Bukkit, and tested.
 *
 * <p>An effect is a colour, so this is a text replacement and nothing more:
 * the opening tag becomes the legacy code for the effect's exact colour
 * ({@code §x§f§c§0§8§f§8}), the closing tag a reset. Legacy because every
 * text a Spigot server takes (an item name, a message, a scoreboard line)
 * takes it, and since 1.16 it spells any colour exactly.
 *
 * <p>Three rules worth keeping, the same ones chat icons keep:
 *
 * <ul>
 *   <li><strong>A tag that names no effect is left exactly as typed</strong>,
 *       and so is the {@code </fx>} that closes it. Text that silently loses
 *       a piece is harder to fix than text that still says what it meant.</li>
 *   <li><strong>A tag the writer may not use is left as typed</strong>, for
 *       the same reason. Chat is where that matters.</li>
 *   <li><strong>No nesting.</strong> One colour at a time is all text has, so
 *       a second opening tag closes the first, and a missing {@code </fx>}
 *       runs to the end.</li>
 * </ul>
 */
public final class TextEffectTags {

    /** {@code <fx:name>}, {@code <fx:pack:name>} or {@code </fx>}, any case. */
    private static final Pattern TAG = Pattern.compile(
            "<fx:(?<name>[a-z0-9_.-]{1,32}:[a-z0-9_./-]{1,64}|[a-z0-9_./-]{1,64})>|</fx>",
            Pattern.CASE_INSENSITIVE);

    /** What ends an effect: a reset, since legacy text has no "the colour before". */
    static final String RESET = "§r";

    private TextEffectTags() {
    }

    /** Whether {@code text} could have a tag in it at all. The early-out for chat, which runs on every line. */
    public static boolean mightMatch(String text) {
        return text != null && text.toLowerCase(Locale.ROOT).contains("<fx:");
    }

    /**
     * Every tag in {@code text} that {@code find} resolves and {@code allowed}
     * permits, replaced.
     */
    public static String format(String text, Function<String, Optional<TextEffectInfo>> find,
                                Predicate<TextEffectInfo> allowed) {
        if (!mightMatch(text)) {
            return text == null ? "" : text;
        }
        Matcher m = TAG.matcher(text);
        StringBuilder out = new StringBuilder(text.length() + 16);
        int last = 0;
        boolean open = false;
        while (m.find()) {
            out.append(text, last, m.start());
            last = m.end();
            String name = m.group("name");
            if (name == null) {
                // </fx>: closes ours, and is only text after one we left alone.
                out.append(open ? RESET : m.group());
                open = false;
                continue;
            }
            Optional<TextEffectInfo> effect = find.apply(name.toLowerCase(Locale.ROOT));
            if (effect.isEmpty() || !allowed.test(effect.get())) {
                out.append(m.group());
                open = false;
                continue;
            }
            out.append(legacy(effect.get().color()));
            open = true;
        }
        out.append(text, last, text.length());
        if (open) {
            out.append(RESET);
        }
        return out.toString();
    }

    /** {@code text} wrapped in the effect. */
    public static String apply(TextEffectInfo effect, String text) {
        return legacy(effect.color()) + (text == null ? "" : text) + RESET;
    }

    /** {@code #fc08f8} as the legacy code for exactly that colour: {@code §x§f§c§0§8§f§8}. */
    public static String legacy(String color) {
        String hex = color.startsWith("#") ? color.substring(1) : color;
        StringBuilder out = new StringBuilder("§x");
        for (char c : hex.toLowerCase(Locale.ROOT).toCharArray()) {
            out.append('§').append(c);
        }
        return out.toString();
    }
}
