package ai.resourcepack.engine.core.armor3d;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Every 3D armour set the server knows: the content folder's and the pushes'.
 *
 * <p>One map, because a set is a set wherever it came from: {@code /rp armor}
 * lists both, the API wears both, and a piece of either is recognised when it
 * is put on. The two cannot share a key. A pushed set's id is bare
 * ({@code a1b2c3}, never a colon), an authored one's is
 * {@code namespace:path}, so a pushed set is named {@code studio:<id>} to
 * people and the bare id is what its pieces carry, as they always have.
 *
 * <p>Asked every tick by {@link WornArmour}, so the merge is cached and redone
 * only when one side has been replaced. Both sides are immutable maps that are
 * swapped whole, never changed, so identity is the whole test.
 */
public final class Armor3dCatalogue {

    /** What a pushed set's id is prefixed with where a person reads or types it. */
    public static final String STUDIO_PREFIX = "studio:";

    private final Supplier<Map<String, Armor3dSet>> pushed;
    private volatile Map<String, Armor3dSet> authored = Map.of();

    private Map<String, Armor3dSet> seenPushed;
    private Map<String, Armor3dSet> seenAuthored;
    private Map<String, Armor3dSet> merged = Map.of();

    public Armor3dCatalogue(Supplier<Map<String, Armor3dSet>> pushed) {
        this.pushed = pushed;
    }

    /** Replaces the content folder's sets, on every load. */
    public void replaceAuthored(Map<String, Armor3dSet> sets) {
        authored = sets == null ? Map.of() : sets;
    }

    /** Every set, authored first, keyed by {@link Armor3dSet#id()}. */
    public synchronized Map<String, Armor3dSet> all() {
        Map<String, Armor3dSet> push = pushed.get();
        Map<String, Armor3dSet> own = authored;
        if (push != seenPushed || own != seenAuthored) {
            Map<String, Armor3dSet> both = new LinkedHashMap<>(own);
            if (push != null) {
                push.forEach(both::putIfAbsent);
            }
            merged = Collections.unmodifiableMap(both);
            seenPushed = push;
            seenAuthored = own;
        }
        return merged;
    }

    /**
     * The set somebody typed: an authored id as written, a pushed one with or
     * without its {@code studio:}.
     */
    public Armor3dSet find(String typed) {
        if (typed == null || typed.isEmpty()) {
            return null;
        }
        Map<String, Armor3dSet> sets = all();
        Armor3dSet exact = sets.get(typed);
        if (exact != null) {
            return exact;
        }
        return typed.startsWith(STUDIO_PREFIX) ? sets.get(typed.substring(STUDIO_PREFIX.length())) : null;
    }

    /** How a set is named to a person: {@code christmas:santa}, or {@code studio:a1b2c3}. */
    public static String label(Armor3dSet set) {
        return label(set.id());
    }

    public static String label(String id) {
        return id.indexOf(':') > 0 ? id : STUDIO_PREFIX + id;
    }
}
