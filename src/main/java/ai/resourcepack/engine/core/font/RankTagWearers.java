package ai.resourcepack.engine.core.font;

import ai.resourcepack.engine.api.ContentId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Who wears which rank tag: a permission per tag, and
 * {@code %rpengine_tag%} for the one that wins.
 *
 * <p><strong>This exists because a content folder's characters move.</strong>
 * Icons are numbered in ID order (see {@link GlyphAllocator}), so a LuckPerms
 * prefix that stored a tag's character would show some other picture after
 * the next tag added before it. A permission is a name, and names do not
 * move: a group is given {@code rpengine.tag.mypack.owner}, and the chat
 * format, the tab list or the nametag plugin prints {@code %rpengine_tag%},
 * which is resolved to the character as it is drawn. It works with any
 * permissions plugin, which is the other half of the point.
 *
 * <p>When a player holds several, the highest {@code priority} wins, and an ID
 * breaks a tie so the answer never depends on load order. The nodes default to
 * nobody, ops included, because an op holding every tag would wear whichever
 * happened to rank highest.
 */
public final class RankTagWearers {

    private record Entry(ContentId id, int priority, String permission) {
    }

    private volatile List<Entry> entries = List.of();

    /** The node that gives a player this tag: {@code rpengine.tag.<namespace>.<path>}. */
    public static String permission(ContentId id) {
        return "rpengine.tag." + id.namespace() + "." + id.path().replace('/', '.');
    }

    /** Takes the tags of a fresh load, in the order they are asked about: highest priority first. */
    public void replace(IconDefinitions.Result icons) {
        List<Entry> next = new ArrayList<>();
        for (Map.Entry<ContentId, RankTags.Spec> tag : icons.tags().entrySet()) {
            ContentId id = tag.getKey();
            next.add(new Entry(id, icons.tagPriorities().getOrDefault(id, 0), permission(id)));
        }
        next.sort(Comparator.comparingInt(Entry::priority).reversed().thenComparing(e -> e.id().toString()));
        entries = List.copyOf(next);
    }

    /** The tag a player wears, given what they hold: the first, highest priority first. */
    public Optional<ContentId> tagOf(Predicate<String> holds) {
        for (Entry entry : entries) {
            if (holds.test(entry.permission())) {
                return Optional.of(entry.id());
            }
        }
        return Optional.empty();
    }

    /** Every tag's node, with the tag it gives, to register with the server. */
    public List<Map.Entry<String, ContentId>> permissions() {
        List<Map.Entry<String, ContentId>> out = new ArrayList<>();
        for (Entry entry : entries) {
            out.add(Map.entry(entry.permission(), entry.id()));
        }
        return out;
    }
}
