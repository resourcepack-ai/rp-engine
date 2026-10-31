package ai.resourcepack.engine.core.font;

import ai.resourcepack.engine.api.ContentId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 *
 * <p><strong>A tag can sit in a named {@code slot:}</strong>, which is how one
 * player wears several in different places: a rank in front of the name and a
 * supporter badge after it. Each slot has its own winner, printed by
 * {@code %rpengine_tag_<slot>%}; a tag with no slot is in the main one, which
 * is what {@code %rpengine_tag%} has always printed, so a folder without slots
 * reads exactly as it did.
 */
public final class RankTagWearers {

    /** The slot a tag with no {@code slot:} is in, and the one {@code %rpengine_tag%} prints. */
    public static final String MAIN = "";

    private record Entry(ContentId id, int priority, String slot, String permission) {
    }

    private volatile List<Entry> entries = List.of();
    private volatile Set<String> slots = Set.of();

    /** The node that gives a player this tag: {@code rpengine.tag.<namespace>.<path>}. */
    public static String permission(ContentId id) {
        return "rpengine.tag." + id.namespace() + "." + id.path().replace('/', '.');
    }

    /** Takes the tags of a fresh load, in the order they are asked about: highest priority first. */
    public void replace(IconDefinitions.Result icons) {
        List<Entry> next = new ArrayList<>();
        for (Map.Entry<ContentId, RankTags.Spec> tag : icons.tags().entrySet()) {
            ContentId id = tag.getKey();
            next.add(new Entry(id, icons.tagPriorities().getOrDefault(id, 0),
                    icons.tagSlots().getOrDefault(id, MAIN), permission(id)));
        }
        next.sort(Comparator.comparingInt(Entry::priority).reversed().thenComparing(e -> e.id().toString()));
        Set<String> named = new HashSet<>();
        for (Entry entry : next) {
            named.add(entry.slot());
        }
        entries = List.copyOf(next);
        slots = Set.copyOf(named);
    }

    /** The tag a player wears in the main slot, given what they hold: the first, highest priority first. */
    public Optional<ContentId> tagOf(Predicate<String> holds) {
        return tagOf(MAIN, holds);
    }

    /** The tag a player wears in {@code slot}: the first of that slot's they hold, highest priority first. */
    public Optional<ContentId> tagOf(String slot, Predicate<String> holds) {
        for (Entry entry : entries) {
            if (entry.slot().equals(slot) && holds.test(entry.permission())) {
                return Optional.of(entry.id());
            }
        }
        return Optional.empty();
    }

    /**
     * Every tag a player wears, one per slot, highest priority first: what
     * {@code %rpengine_tags%} prints, for a format with room for one spot.
     */
    public List<ContentId> tagsOf(Predicate<String> holds) {
        List<ContentId> out = new ArrayList<>();
        Set<String> filled = new HashSet<>();
        for (Entry entry : entries) {
            if (!filled.contains(entry.slot()) && holds.test(entry.permission())) {
                filled.add(entry.slot());
                out.add(entry.id());
            }
        }
        return out;
    }

    /** Whether any tag is in {@code slot}, so a misspelt one stays visible as typed. */
    public boolean hasSlot(String slot) {
        return slots.contains(slot);
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
