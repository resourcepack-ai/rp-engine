package ai.resourcepack.engine.core.serve;

import ai.resourcepack.engine.api.BuiltPack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which bundles each player already has from a pack another plugin sent, so
 * they are left out of what this server sends them.
 *
 * <p>Taken out of the stack rather than recorded as held: what is held is
 * what {@link BundleSessions} plans removals from, and a bundle it believed in
 * but never sent could not be taken off again. Everything else in the stack
 * keeps its order and sits on whatever the other plugin's pack is.
 *
 * <p>Free of Bukkit, like the rest of the bookkeeping here. Any thread: a mark
 * is usually made while a player is still logging in.
 */
public final class HeldElsewhere {

    private final Map<UUID, Set<String>> marks = new ConcurrentHashMap<>();

    /** @return false if it was already marked */
    public boolean mark(UUID player, String bundle) {
        if (player == null || bundle == null || bundle.isEmpty()) {
            return false;
        }
        return marks.computeIfAbsent(player, key -> ConcurrentHashMap.newKeySet()).add(bundle);
    }

    /** @return false if it was not marked */
    public boolean unmark(UUID player, String bundle) {
        Set<String> mine = player == null ? null : marks.get(player);
        if (mine == null || bundle == null || !mine.remove(bundle)) {
            return false;
        }
        marks.computeIfPresent(player, (key, set) -> set.isEmpty() ? null : set);
        return true;
    }

    public boolean marked(UUID player, String bundle) {
        Set<String> mine = player == null ? null : marks.get(player);
        return mine != null && bundle != null && mine.contains(bundle);
    }

    /** {@code stack} without the bundles {@code player} already has from elsewhere, in the same order. */
    public List<BuiltPack> without(UUID player, List<BuiltPack> stack) {
        Set<String> mine = player == null ? null : marks.get(player);
        if (mine == null || mine.isEmpty() || stack == null || stack.isEmpty()) {
            return stack == null ? List.of() : stack;
        }
        List<BuiltPack> out = new ArrayList<>(stack.size());
        for (BuiltPack pack : stack) {
            if (!mine.contains(pack.bundle())) {
                out.add(pack);
            }
        }
        return List.copyOf(out);
    }

    /** Forgets a player who left: the other plugin's pack went with their connection. */
    public void forget(UUID player) {
        if (player != null) {
            marks.remove(player);
        }
    }
}
