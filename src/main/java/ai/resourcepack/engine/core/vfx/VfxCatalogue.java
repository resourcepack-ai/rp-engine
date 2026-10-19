package ai.resourcepack.engine.core.vfx;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.VfxInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Every VFX this server can play, from both sources: a content folder's
 * (replaced on each reload) and every live Studio pack's (asked for each time,
 * since a push lands between reloads).
 */
public final class VfxCatalogue {

    private final Supplier<Map<ContentId, VfxEntry>> studio;
    private volatile Map<ContentId, VfxEntry> authored = Map.of();

    public VfxCatalogue(Supplier<Map<ContentId, VfxEntry>> studio) {
        this.studio = studio;
    }

    /** The content folder's effects, after a reload. */
    public void replaceAuthored(Map<ContentId, VfxEntry> effects) {
        authored = Map.copyOf(effects);
    }

    public Map<ContentId, VfxEntry> authored() {
        return authored;
    }

    private Map<ContentId, VfxEntry> studio() {
        Map<ContentId, VfxEntry> got = studio == null ? null : studio.get();
        return got == null ? Map.of() : got;
    }

    public List<ContentId> ids() {
        List<ContentId> out = new ArrayList<>(authored.keySet());
        for (ContentId id : studio().keySet()) {
            if (!out.contains(id)) {
                out.add(id);
            }
        }
        out.sort(Comparator.comparing(ContentId::toString));
        return out;
    }

    public Optional<VfxEntry> entry(ContentId id) {
        if (id == null) {
            return Optional.empty();
        }
        VfxEntry own = authored.get(id);
        return own != null ? Optional.of(own) : Optional.ofNullable(studio().get(id));
    }

    public Optional<VfxInfo> info(ContentId id) {
        return entry(id).map(VfxEntry::info);
    }

    /** By what somebody typed: a full id, or a bare path (content folder first, then the first namespace). */
    public Optional<VfxEntry> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String wanted = name.trim().toLowerCase(Locale.ROOT);
        if (wanted.contains(":")) {
            return ContentId.parse(wanted).flatMap(this::entry);
        }
        Optional<VfxEntry> own = byPath(authored, wanted);
        return own.isPresent() ? own : byPath(studio(), wanted);
    }

    private static Optional<VfxEntry> byPath(Map<ContentId, VfxEntry> effects, String path) {
        return effects.entrySet().stream()
                .filter(e -> e.getKey().path().equals(path))
                .min(Comparator.comparing(e -> e.getKey().toString()))
                .map(Map.Entry::getValue);
    }
}
