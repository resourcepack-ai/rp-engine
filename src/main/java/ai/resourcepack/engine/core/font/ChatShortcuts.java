package ai.resourcepack.engine.core.font;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.IconInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything somebody can type in chat to get an icon, and the one pass that
 * finds them. Internal.
 *
 * <p>Free of Bukkit, so every case that is about text — a time of day, a URL, an
 * alias inside a longer word — is tested rather than assumed. {@link ChatIcons}
 * is the listener around it and adds the two things that need a server: the
 * config switch and whether this player has the permission.
 *
 * <p>Built once per reload rather than per message. Chat runs this on every line
 * anybody sends, and the alias pattern is the only regex here that depends on
 * content.
 *
 * <p>Two kinds of thing are matched:
 *
 * <ul>
 *   <li><strong>{@code :name:} and {@code :pack:name:}</strong>, anywhere in a
 *       line, as chat always allowed.</li>
 *   <li><strong>An alias</strong> — {@code <3}, {@code :heart:}, whatever the
 *       pack listed — only as a WHOLE WORD: whitespace or the end of the line on
 *       both sides. An alias is typically punctuation, and punctuation turns up
 *       inside other things: {@code <3} in {@code <33}, {@code :P} in
 *       {@code http://x:Port}. Requiring a word on its own is the rule that
 *       never eats any of those, at the price of {@code <3!} not matching.</li>
 * </ul>
 */
final class ChatShortcuts {

    /**
     * {@code :name:}, or {@code :pack:name:} where two packs collide.
     *
     * <p>The namespaced form is the FIRST branch on purpose. A single
     * character class that allowed a colon inside would match {@code :pack:}
     * out of {@code :pack:name:}, find nothing called "pack", and leave the
     * rest stranded with no opening colon left to match against.
     */
    static final String NAME = "[a-z0-9_.-]{1,32}:[a-z0-9_./-]{1,64}|[a-z0-9_./-]{1,64}";

    private static final Pattern SHORTCODE = Pattern.compile(":(?<name>" + NAME + "):");

    /** No icons: nothing to match. */
    static final ChatShortcuts EMPTY = new ChatShortcuts(Map.of(), Map.of(), Map.of());

    private final Map<String, IconInfo> byId;
    private final Map<String, IconInfo> byPath;
    private final Map<String, IconInfo> aliases;
    private final Pattern pattern;

    private ChatShortcuts(Map<String, IconInfo> byId, Map<String, IconInfo> byPath, Map<String, IconInfo> aliases) {
        this.byId = byId;
        this.byPath = byPath;
        this.aliases = aliases;
        if (aliases.isEmpty()) {
            this.pattern = SHORTCODE;
        } else {
            // Longest first, so `<33` is tried before `<3` where a pack has
            // both: the regex takes the first branch that matches, not the
            // longest one.
            List<String> words = new ArrayList<>(aliases.keySet());
            words.sort(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()));
            StringBuilder alternatives = new StringBuilder();
            for (String word : words) {
                if (alternatives.length() > 0) {
                    alternatives.append('|');
                }
                alternatives.append(Pattern.quote(word));
            }
            // The alias branch first, so an alias spelled like a shortcode
            // (`:heart:` on an icon called `love`) is found as the alias.
            this.pattern = Pattern.compile("(?<!\\S)(?<alias>" + alternatives + ")(?!\\S)|" + SHORTCODE.pattern());
        }
    }

    /**
     * The shortcuts for a set of icons.
     *
     * <p>A bare name is looked up across every namespace, because somebody
     * typing in chat has no reason to know which pack a smiley came from.
     * Ambiguity resolves to the first in sorted order, which is at least
     * stable — a server with two packs that both call something "wave" can
     * write {@code :mypack:wave:} to be specific. The same rule for two icons
     * listing one alias, which the loader has already warned about.
     */
    static ChatShortcuts of(Collection<IconInfo> icons) {
        if (icons == null || icons.isEmpty()) {
            return EMPTY;
        }
        List<IconInfo> sorted = new ArrayList<>(icons);
        sorted.sort(Comparator.comparing(IconInfo::id));
        Map<String, IconInfo> byId = new HashMap<>();
        Map<String, IconInfo> byPath = new HashMap<>();
        Map<String, IconInfo> aliases = new HashMap<>();
        for (IconInfo icon : sorted) {
            byId.put(icon.id().toString(), icon);
            byPath.putIfAbsent(icon.id().path(), icon);
            for (String alias : icon.aliases()) {
                if (!alias.isEmpty()) {
                    aliases.putIfAbsent(alias, icon);
                }
            }
        }
        return new ChatShortcuts(Map.copyOf(byId), Map.copyOf(byPath), Map.copyOf(aliases));
    }

    /**
     * Whether a line could contain anything at all, before running a regex
     * over it. Most lines have no colon, and unless a pack declared an alias
     * that is the end of it.
     */
    boolean mightMatch(String message) {
        return message != null && (!aliases.isEmpty() || message.indexOf(':') >= 0);
    }

    /**
     * Every shortcut in {@code message} that names an icon {@code allowed}
     * lets this person use, replaced by its character.
     *
     * <p>Always the FIRST frame of an animated icon: a chat line is drawn once
     * and never sent again, so there is no later frame for it to show.
     *
     * <p>Anything not replaced is left exactly as typed — a name nobody has, an
     * icon this person may not use. People write {@code 10:30} and {@code :)}
     * in chat, and a replacement that ate either would be a plugin quietly
     * corrupting what somebody said.
     */
    String replace(String message, Predicate<IconInfo> allowed) {
        if (!mightMatch(message)) {
            return message == null ? "" : message;
        }
        Matcher matcher = pattern.matcher(message);
        StringBuilder out = new StringBuilder(message.length());
        int at = 0;
        boolean changed = false;
        while (matcher.find()) {
            IconInfo icon = resolve(matcher);
            if (icon == null || !allowed.test(icon)) {
                continue;
            }
            out.append(message, at, matcher.start()).append(icon.character());
            at = matcher.end();
            changed = true;
        }
        return changed ? out.append(message.substring(at)).toString() : message;
    }

    /** The icon one match names, or null. */
    private IconInfo resolve(Matcher matcher) {
        String alias = aliases.isEmpty() ? null : matcher.group("alias");
        if (alias != null) {
            IconInfo icon = aliases.get(alias);
            if (icon != null) {
                return icon;
            }
            Matcher shortcode = SHORTCODE.matcher(alias);
            return shortcode.matches() ? lookUp(shortcode.group("name")) : null;
        }
        return lookUp(matcher.group("name"));
    }

    private IconInfo lookUp(String name) {
        if (name == null) {
            return null;
        }
        if (name.indexOf(':') >= 0) {
            return ContentId.parse(name).map(id -> byId.get(id.toString())).orElse(null);
        }
        return byPath.get(name);
    }
}
