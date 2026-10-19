package ai.resourcepack.engine.core.textfx;

import ai.resourcepack.engine.api.ContentId;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * Which colour each of a content folder's text effects is, kept on disk.
 *
 * <p><strong>A colour, once given, is kept</strong>, even for an effect that
 * has since been deleted. The colour is the whole interface: an item named in
 * an effect carries that colour in its name for as long as the item exists,
 * so an effect that moved to another colour would leave every such item
 * wearing whichever effect took its old one. Only when all of them are taken
 * is one of a deleted effect's handed on.
 *
 * <p>Handed out from the TOP of the space down, because Studio allocates its
 * own from the bottom up: a server with a Studio pack and a content folder
 * then has two sets of effects in two sets of colours, and a player whose
 * pack draws only one set sees the other as near-white text rather than as
 * the wrong effect. A colour a Studio effect is using is skipped, too.
 */
public final class TextEffectIds {

    private final Path file;
    private final int size;
    /** By id, as {@link ContentId#toString}. */
    private final Map<String, Integer> assigned = new LinkedHashMap<>();

    /**
     * @param size how many indices there are, {@link TextEffectShaders#maxEffects}
     */
    public TextEffectIds(Path file, int size) {
        this.file = file;
        this.size = size;
    }

    /** Reads what is on disk. A missing or unreadable file is a fresh start, said once. */
    public void load(Logger log) {
        assigned.clear();
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        Properties read = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            read.load(in);
        } catch (IOException e) {
            if (log != null) {
                log.warning("Could not read " + file.getFileName() + " (" + e.getMessage()
                        + "); Text Effects may change colour.");
            }
            return;
        }
        for (String id : new TreeMap<>(read).keySet().stream().map(String::valueOf).toList()) {
            try {
                int index = Integer.parseInt(read.getProperty(id).trim());
                if (index >= 0 && index < size && !assigned.containsValue(index)) {
                    assigned.put(id, index);
                }
            } catch (NumberFormatException ignored) {
                // A hand-edited line: that effect gets a new colour.
            }
        }
    }

    /**
     * An index for every id in {@code ids}, keeping the ones already given.
     *
     * @param studio indices Studio's effects are drawn with, avoided when there is a choice
     * @return the index of each id that got one; an id missing from it did not fit
     */
    public Map<ContentId, Integer> assign(Collection<ContentId> ids, Set<Integer> studio) {
        Map<ContentId, Integer> out = new LinkedHashMap<>();
        Set<String> wanted = new HashSet<>();
        for (ContentId id : ids) {
            wanted.add(id.toString());
        }
        for (ContentId id : ids) {
            Integer index = assigned.get(id.toString());
            if (index != null) {
                out.put(id, index);
            }
        }
        for (ContentId id : ids) {
            if (out.containsKey(id)) {
                continue;
            }
            Optional<Integer> free = free(studio, false);
            if (free.isEmpty()) {
                free = free(studio, true);
            }
            if (free.isEmpty()) {
                // Everything is held by an effect that exists. Take a deleted
                // one's, which costs only the items that still carry it.
                free = reclaim(wanted);
            }
            if (free.isEmpty()) {
                continue;
            }
            assigned.values().remove(free.get());
            assigned.put(id.toString(), free.get());
            out.put(id, free.get());
        }
        return out;
    }

    private Optional<Integer> free(Set<Integer> studio, boolean evenStudios) {
        for (int index = size - 1; index >= 0; index--) {
            if (!assigned.containsValue(index) && (evenStudios || !studio.contains(index))) {
                return Optional.of(index);
            }
        }
        return Optional.empty();
    }

    private Optional<Integer> reclaim(Set<String> wanted) {
        for (Map.Entry<String, Integer> e : assigned.entrySet()) {
            if (!wanted.contains(e.getKey())) {
                return Optional.of(e.getValue());
            }
        }
        return Optional.empty();
    }

    /** Writes what is assigned. A failure is logged: the colours hold until the next restart. */
    public void save(Logger log) {
        if (file == null) {
            return;
        }
        Properties out = new Properties() {
            @Override
            public synchronized java.util.Enumeration<Object> keys() {
                return java.util.Collections.enumeration(new java.util.TreeSet<>(super.keySet()));
            }

            @Override
            public java.util.Set<Map.Entry<Object, Object>> entrySet() {
                java.util.Set<Map.Entry<Object, Object>> sorted = new java.util.TreeSet<>(
                        java.util.Comparator.comparing(entry -> String.valueOf(entry.getKey())));
                sorted.addAll(super.entrySet());
                return sorted;
            }
        };
        assigned.forEach((id, index) -> out.setProperty(id, String.valueOf(index)));
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                out.store(w, " The colour each content folder Text Effect is drawn in, by its index."
                        + " Kept so an item named in an effect keeps it. Delete a line to free one.");
            }
        } catch (IOException e) {
            if (log != null) {
                log.warning("Could not write " + file.getFileName() + ": " + e.getMessage());
            }
        }
    }
}
