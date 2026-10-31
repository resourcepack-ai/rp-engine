package ai.resourcepack.engine.core.cape;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Every cape this server holds: the content folder's {@code capes/}, replaced
 * on each load, and every live push's. One list, so the command, the API and
 * the displays never disagree about what exists. {@code Armor3dCatalogue}'s
 * shape exactly, and for the same reasons.
 */
public final class CapeCatalogue {

    /** What a pushed cape's id is prefixed with where a person reads or types it. */
    public static final String STUDIO_PREFIX = "studio:";

    private final Supplier<Map<String, Cape>> pushed;
    private volatile Map<String, Cape> authored = Map.of();

    private Map<String, Cape> seenPushed;
    private Map<String, Cape> seenAuthored;
    private Map<String, Cape> merged = Map.of();

    public CapeCatalogue(Supplier<Map<String, Cape>> pushed) {
        this.pushed = pushed;
    }

    /** Replaces the content folder's capes, on every load. */
    public void replaceAuthored(Map<String, Cape> capes) {
        authored = capes == null ? Map.of() : capes;
    }

    /** Every cape, the content folder's first, keyed by {@link Cape#id()}. */
    public synchronized Map<String, Cape> all() {
        Map<String, Cape> push = pushed.get();
        Map<String, Cape> own = authored;
        if (push != seenPushed || own != seenAuthored) {
            Map<String, Cape> both = new LinkedHashMap<>(own);
            if (push != null) {
                push.forEach(both::putIfAbsent);
            }
            merged = Collections.unmodifiableMap(both);
            seenPushed = push;
            seenAuthored = own;
        }
        return merged;
    }

    /** The cape somebody typed or stored: a content folder's id as written, a push's with or without {@code studio:}. */
    public Cape find(String typed) {
        if (typed == null || typed.isEmpty()) {
            return null;
        }
        Map<String, Cape> capes = all();
        Cape exact = capes.get(typed);
        if (exact != null) {
            return exact;
        }
        return typed.startsWith(STUDIO_PREFIX) ? capes.get(typed.substring(STUDIO_PREFIX.length())) : null;
    }

    public static String label(String id) {
        return id.indexOf(':') > 0 ? id : STUDIO_PREFIX + id;
    }
}
