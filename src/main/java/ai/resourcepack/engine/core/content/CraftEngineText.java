package ai.resourcepack.engine.core.content;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CraftEngine's item text, which is MiniMessage, as the {@code &} colour codes
 * an RP Engine name or lore line is written in.
 *
 * <p>Colours ({@code <red>}, {@code <#FF8C00>}, {@code <color:#..>}) and the
 * decorations ({@code <bold>}, {@code <i>}, ...) come across. The language
 * tags are resolved to their English text where the pack has some:
 * {@code <lang:key>} from its {@code lang:} entries, and {@code <l10n:key>} or
 * {@code <i18n:key>} from its {@code translations:}. Every other tag - a
 * gradient, a hover, {@code <!i>}, an {@code <image:...>} - is removed, and
 * the ones that changed what the text looks like are named so the caller can
 * warn.
 */
final class CraftEngineText {

    private static final Pattern TAG = Pattern.compile("<(/?)([!a-zA-Z0-9_#.\\-]+)((?::(?:'[^']*'|\"[^\"]*\"|[^>:]*))*)>");

    private static final Map<String, Character> COLOURS = Map.ofEntries(
            Map.entry("black", '0'), Map.entry("dark_blue", '1'), Map.entry("dark_green", '2'),
            Map.entry("dark_aqua", '3'), Map.entry("dark_red", '4'), Map.entry("dark_purple", '5'),
            Map.entry("gold", '6'), Map.entry("gray", '7'), Map.entry("grey", '7'),
            Map.entry("dark_gray", '8'), Map.entry("dark_grey", '8'), Map.entry("blue", '9'),
            Map.entry("green", 'a'), Map.entry("aqua", 'b'), Map.entry("red", 'c'),
            Map.entry("light_purple", 'd'), Map.entry("yellow", 'e'), Map.entry("white", 'f'));

    private static final Map<String, Character> DECORATIONS = Map.ofEntries(
            Map.entry("bold", 'l'), Map.entry("b", 'l'), Map.entry("italic", 'o'), Map.entry("i", 'o'),
            Map.entry("em", 'o'), Map.entry("underlined", 'n'), Map.entry("u", 'n'),
            Map.entry("strikethrough", 'm'), Map.entry("st", 'm'), Map.entry("obfuscated", 'k'),
            Map.entry("obf", 'k'), Map.entry("reset", 'r'));

    /** Tags that only say how text behaves, not what it looks like; dropping them loses nothing visible. */
    private static final Set<String> HARMLESS = Set.of("!i", "!italic", "shadow", "!shadow", "hover", "click",
            "insert", "insertion", "font", "/");

    private CraftEngineText() {
    }

    /** The converted text, and the tags that were removed and changed how it looks. */
    record Converted(String text, Set<String> dropped, Set<String> unresolved) {
    }

    /**
     * @param lookup the English text for a {@code <lang:>}, {@code <l10n:>}
     *               or {@code <i18n:>} key, or null when the pack has none
     */
    static Converted convert(String text, Function<String, String> lookup) {
        Set<String> dropped = new LinkedHashSet<>();
        Set<String> unresolved = new LinkedHashSet<>();
        String current = text;
        // Language text is itself MiniMessage, so it is put in first and the
        // colours converted afterwards, a few rounds deep at most.
        for (int round = 0; round < 4; round++) {
            String expanded = expandLanguage(current, lookup, unresolved);
            if (expanded.equals(current)) break;
            current = expanded;
        }
        StringBuilder out = new StringBuilder();
        Matcher matcher = TAG.matcher(current);
        int last = 0;
        while (matcher.find()) {
            out.append(current, last, matcher.start());
            last = matcher.end();
            boolean closing = !matcher.group(1).isEmpty();
            String name = matcher.group(2).toLowerCase(Locale.ROOT);
            String arguments = matcher.group(3);
            if (closing) {
                continue;
            }
            if (COLOURS.containsKey(name)) {
                out.append('&').append(COLOURS.get(name));
            } else if (name.startsWith("#") && name.length() == 7) {
                out.append(hex(name.substring(1)));
            } else if ((name.equals("color") || name.equals("colour") || name.equals("c")) && !arguments.isEmpty()) {
                String colour = arguments.substring(1).toLowerCase(Locale.ROOT);
                if (COLOURS.containsKey(colour)) out.append('&').append(COLOURS.get(colour));
                else if (colour.startsWith("#") && colour.length() == 7) out.append(hex(colour.substring(1)));
            } else if (DECORATIONS.containsKey(name)) {
                out.append('&').append(DECORATIONS.get(name));
            } else if (name.equals("gradient") || name.equals("rainbow") || name.equals("transition")) {
                // The first colour of a gradient is the nearest single colour.
                Matcher colour = Pattern.compile("#[0-9a-fA-F]{6}").matcher(arguments);
                if (colour.find()) out.append(hex(colour.group().substring(1)));
                dropped.add(name);
            } else if (name.equals("newline") || name.equals("br")) {
                out.append(' ');
            } else if (!HARMLESS.contains(name) && !name.startsWith("!")) {
                dropped.add(name);
            }
        }
        out.append(current.substring(last));
        return new Converted(out.toString(), dropped, unresolved);
    }

    private static final Pattern LANGUAGE = Pattern.compile("<(lang|l10n|i18n|tr|translate):([^:>]+)(?::[^>]*)?>");

    private static String expandLanguage(String text, Function<String, String> lookup, Set<String> unresolved) {
        Matcher matcher = LANGUAGE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(2).replace("'", "").replace("\"", "");
            String value = lookup.apply(key);
            if (value == null) {
                unresolved.add(key);
                value = key;
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** {@code &x&F&F&8&C&0&0}, the legacy spelling of a hex colour Bukkit understands. */
    private static String hex(String rgb) {
        StringBuilder out = new StringBuilder("&x");
        for (char c : rgb.toCharArray()) {
            out.append('&').append(Character.toLowerCase(c));
        }
        return out.toString();
    }
}
