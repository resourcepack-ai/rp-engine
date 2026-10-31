package ai.resourcepack.engine.core.font;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.IconInfo;
import ai.resourcepack.engine.api.Icons;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves icon ids into characters. Internal.
 *
 * <p>Free of Bukkit, so the placeholder syntax is tested rather than assumed.
 *
 * <p>It also owns the one clock animated icons are timed against, and takes it
 * as a constructor argument so the frame arithmetic is tested against a clock
 * a test moves by hand rather than against the wall.
 */
public final class IconsImpl implements Icons {

    /**
     * {@code :namespace:path:} — the same characters an id is allowed, wrapped
     * in colons.
     *
     * <p>Deliberately requires the namespace. {@code :sword:} would be shorter
     * and would mean guessing which pack somebody meant, which is a guess that
     * changes answer the day a second pack is installed.
     */
    private static final Pattern PLACEHOLDER =
            Pattern.compile(":([a-z0-9_.-]+):([a-z0-9_.\\-/]+):");

    private final LongSupplier clock;

    /**
     * When this server started, by {@link #clock}: what a non-looping
     * animation is timed from.
     *
     * <p>A looping one is timed from the epoch instead, which gives the same
     * picture at the same instant on every server of a network and across a
     * restart. One that stops on its last frame has to count from SOMETHING
     * that happened recently or it would have finished in 1970; the server
     * starting is the only such moment every viewer shares. It is honest about
     * what that means — it plays once, just after a restart — and the docs say
     * so rather than pretending it plays when somebody looks.
     */
    private final long started;

    private volatile Map<ContentId, IconInfo> icons = Map.of();

    /** Every frame of every icon, which is what {@link #whiten} looks for. */
    private volatile Set<Integer> codepoints = Set.of();

    private static volatile IconsImpl installed;

    /** What chat matches, built once per reload rather than once per message. */
    private volatile ChatShortcuts shortcuts = ChatShortcuts.EMPTY;

    public IconsImpl() {
        this(System::currentTimeMillis);
    }

    /** With a clock of the caller's choosing, in milliseconds. */
    public IconsImpl(LongSupplier clock) {
        this.clock = clock;
        this.started = clock.getAsLong();
    }

    /** Replaces the catalogue, as a reload does. */
    public void replace(Map<ContentId, IconInfo> loaded) {
        Map<ContentId, IconInfo> next = loaded == null ? Map.of() : Map.copyOf(loaded);
        Set<Integer> all = new HashSet<>();
        for (IconInfo icon : next.values()) {
            for (int frame = 0; frame < icon.frames(); frame++) {
                all.add(icon.codepoint(frame));
            }
        }
        this.shortcuts = ChatShortcuts.of(next.values());
        this.codepoints = Set.copyOf(all);
        this.icons = next;
    }

    /** Makes this the catalogue {@link #formatInstalled} reads. */
    public void install() {
        installed = this;
    }

    /** Forgets it again, on disable. */
    public static void uninstall() {
        installed = null;
    }

    /**
     * {@link #format(String)} by the running engine's icons, or the text
     * unchanged when there are none (a test, or before enable). For the
     * engine's own item names and lore, AFTER the ampersand codes are turned
     * into section signs, since the colour an icon hands back is read from
     * those.
     */
    public static String formatInstalled(String text) {
        IconsImpl here = installed;
        return here == null ? (text == null ? "" : text) : here.format(text);
    }

    /** What chat matches: every icon's name and aliases. */
    ChatShortcuts shortcuts() {
        return shortcuts;
    }

    @Override
    public Collection<ContentId> ids() {
        List<ContentId> sorted = new ArrayList<>(icons.keySet());
        sorted.sort(ContentId::compareTo);
        return List.copyOf(sorted);
    }

    @Override
    public Optional<IconInfo> info(ContentId id) {
        return id == null ? Optional.empty() : Optional.ofNullable(icons.get(id));
    }

    @Override
    public Optional<IconInfo> info(String id) {
        return ContentId.parse(id).flatMap(this::info);
    }

    @Override
    public Optional<String> character(ContentId id) {
        return info(id).map(IconInfo::character);
    }

    @Override
    public Optional<String> characterNow(ContentId id) {
        return info(id).map(this::current);
    }

    /** The character of the frame {@code icon} is showing by this clock. */
    String current(IconInfo icon) {
        if (!icon.animated()) {
            return icon.character();
        }
        long now = clock.getAsLong();
        return icon.character(icon.frameAt(icon.loops() ? now : now - started));
    }

    @Override
    public String format(String text) {
        return format(text, false);
    }

    @Override
    public String formatNow(String text) {
        return format(text, true);
    }

    private String format(String text, boolean now) {
        return whiten(placeholders(text, now));
    }

    private String placeholders(String text, boolean now) {
        if (text == null || text.indexOf(':') < 0) {
            return text == null ? "" : text;
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        int last = 0;
        while (matcher.find()) {
            out.append(text, last, matcher.start());
            Optional<String> character = ContentId.of(matcher.group(1), matcher.group(2))
                    .flatMap(now ? this::characterNow : this::character);
            // Left exactly as written when it names nothing: text that
            // silently loses a chunk of itself is much harder to diagnose than
            // text that still visibly says :mypack:sword:.
            out.append(character.orElseGet(matcher::group));
            last = matcher.end();
        }
        out.append(text, last, text.length());
        return out.toString();
    }

    /**
     * Every icon character in {@code text} written in white, and the text's
     * own colour put back after it.
     *
     * <p>The game multiplies a glyph by the colour of the text it is in. That
     * is right for a letter and wrong for a picture: an icon in item lore with
     * no colour of its own comes out lore's default purple, and one after
     * {@code §7} comes out grey. White is the one colour that leaves a picture
     * as drawn.
     *
     * <p>What follows the icon gets back what it had: the colour and the
     * formatting codes in force before it, read off the text's own section
     * signs. Text with none in force gets {@code §r}, which is "the colour this
     * line started with", whatever the place the text lands in says that is.
     * A run of icons side by side is one white stretch, and an icon at the
     * very end needs nothing after it.
     */
    String whiten(String text) {
        Set<Integer> known = codepoints;
        if (text == null || text.isEmpty() || known.isEmpty()) {
            return text == null ? "" : text;
        }
        StringBuilder out = null;
        String colour = "";
        StringBuilder formats = new StringBuilder();
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '§' && i + 1 < n) {
                char code = Character.toLowerCase(text.charAt(i + 1));
                int length = 2;
                if (code == 'x' && isHex(text, i + 2)) {
                    length = 14;
                    colour = text.substring(i, i + length);
                    formats.setLength(0);
                } else if ((code >= '0' && code <= '9') || (code >= 'a' && code <= 'f')) {
                    colour = text.substring(i, i + 2);
                    formats.setLength(0);
                } else if (code >= 'k' && code <= 'o') {
                    formats.append(text, i, i + 2);
                } else if (code == 'r') {
                    colour = "";
                    formats.setLength(0);
                }
                if (out != null) {
                    out.append(text, i, i + length);
                }
                i += length;
                continue;
            }
            int cp = text.codePointAt(i);
            if (!known.contains(cp) || ("§f".equals(colour) && formats.length() == 0)) {
                if (out != null) {
                    out.appendCodePoint(cp);
                }
                i += Character.charCount(cp);
                continue;
            }
            if (out == null) {
                out = new StringBuilder(n + 8).append(text, 0, i);
            }
            out.append("§f");
            while (i < n && known.contains(text.codePointAt(i))) {
                int icon = text.codePointAt(i);
                out.appendCodePoint(icon);
                i += Character.charCount(icon);
            }
            if (i < n) {
                out.append(colour.isEmpty() && formats.length() == 0 ? "§r" : colour + formats);
            }
        }
        return out == null ? text : out.toString();
    }

    /** Whether {@code §x} at {@code from - 2} is followed by six {@code §<hex>} pairs. */
    private static boolean isHex(String text, int from) {
        if (from + 12 > text.length()) {
            return false;
        }
        for (int k = 0; k < 6; k++) {
            int at = from + k * 2;
            if (text.charAt(at) != '§' || Character.digit(text.charAt(at + 1), 16) < 0) {
                return false;
            }
        }
        return true;
    }
}
