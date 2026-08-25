package ai.resourcepack.engine.core.font;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.IconInfo;
import ai.resourcepack.engine.api.Icons;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves icon ids into characters. Internal.
 *
 * <p>Free of Bukkit, so the placeholder syntax is tested rather than assumed.
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

    private volatile Map<ContentId, IconInfo> icons = Map.of();

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
    public String format(String text) {
        if (text == null || text.indexOf(':') < 0) {
            return text == null ? "" : text;
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        int last = 0;
        while (matcher.find()) {
            out.append(text, last, matcher.start());
            Optional<String> character = ContentId.of(matcher.group(1), matcher.group(2))
                    .flatMap(this::character);
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
