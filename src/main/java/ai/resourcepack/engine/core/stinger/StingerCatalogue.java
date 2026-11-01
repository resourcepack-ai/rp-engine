package ai.resourcepack.engine.core.stinger;

import ai.resourcepack.engine.api.ContentId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Every stinger this server can play: every live Studio pack's, asked for each
 * time, since a push lands between reloads. A content folder has no
 * {@code stingers/}: a stinger is shader code only Studio writes.
 */
public final class StingerCatalogue {

    private final Supplier<Map<ContentId, StingerEntry>> studio;

    public StingerCatalogue(Supplier<Map<ContentId, StingerEntry>> studio) {
        this.studio = studio;
    }

    private Map<ContentId, StingerEntry> all() {
        Map<ContentId, StingerEntry> got = studio == null ? null : studio.get();
        return got == null ? Map.of() : got;
    }

    public List<ContentId> ids() {
        List<ContentId> out = new ArrayList<>(all().keySet());
        out.sort(Comparator.comparing(ContentId::toString));
        return out;
    }

    public Optional<StingerEntry> entry(ContentId id) {
        return id == null ? Optional.empty() : Optional.ofNullable(all().get(id));
    }

    /** By what somebody typed: a full id, or a bare path (the first namespace alphabetically). */
    public Optional<StingerEntry> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String wanted = name.trim().toLowerCase(Locale.ROOT);
        if (wanted.contains(":")) {
            return ContentId.parse(wanted).flatMap(this::entry);
        }
        return all().entrySet().stream()
                .filter(e -> e.getKey().path().equals(wanted))
                .min(Comparator.comparing(e -> e.getKey().toString()))
                .map(Map.Entry::getValue);
    }
}
