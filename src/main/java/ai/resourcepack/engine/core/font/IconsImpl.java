package ai.resourcepack.engine.core.font;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.IconInfo;
import ai.resourcepack.engine.api.Icons;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        this.icons = loaded == null ? Map.of() : Map.copyOf(loaded);
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
}
