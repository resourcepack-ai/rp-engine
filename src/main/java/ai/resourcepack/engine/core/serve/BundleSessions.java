package ai.resourcepack.engine.core.serve;

import ai.resourcepack.engine.api.BuiltPack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which bundles each player is currently holding, and the smallest set of
 * pushes that gets them to a different set.
 *
 * <p>Deliberately free of Bukkit. Everything hard about swapping packs is the
 * bookkeeping — what is applied, in what order, and what actually has to move
 * — and none of it needs a {@code Player}. The caller turns a
 * {@link Delta} into {@code addResourcePack} and {@code removeResourcePack}
 * calls; this decides what those calls should be.
 *
 * <p>Two rules the client imposes and this has to respect:
 *
 * <ul>
 *   <li><strong>Order matters.</strong> A later pack overrides an earlier one,
 *       so the held list is a stack and not a set. Changing the order means
 *       re-sending, which is why {@link #plan} keeps the common prefix and
 *       replaces everything after it rather than trying to be cleverer.</li>
 *   <li><strong>A rebuild is a change.</strong> The same bundle with a new
 *       SHA-1 has to be re-sent, because the client caches by hash and would
 *       otherwise keep showing the old one.</li>
 * </ul>
 */
public final class BundleSessions {

    /** One pack a player is holding: which bundle, and which build of it. */
    public static final class Held {

        private final String bundle;
        private final String sha1;
        private final UUID uuid;

        private Held(String bundle, String sha1) {
            this.bundle = bundle;
            this.sha1 = sha1;
            this.uuid = BuiltPack.uuidFor(bundle);
        }

        static Held of(BuiltPack pack) {
            return new Held(pack.bundle(), pack.sha1());
        }

        /** The bundle name. */
        public String bundle() {
            return bundle;
        }

        /** The build of it the player has. */
        public String sha1() {
            return sha1;
        }

        /** The pack UUID, which is what a removal names. */
        public UUID uuid() {
            return uuid;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Held
                    && bundle.equals(((Held) other).bundle)
                    && sha1.equals(((Held) other).sha1);
        }

        @Override
        public int hashCode() {
            return bundle.hashCode() * 31 + sha1.hashCode();
        }

        @Override
        public String toString() {
            return bundle + "@" + sha1;
        }
    }

    /**
     * What to send a player to get them from what they hold to what they
     * should hold.
     *
     * <p>Removals first, then additions, and both already in the order they
     * should be issued. A caller that reorders them gets a stack in the wrong
     * order, which shows up as one pack's textures inexplicably winning.
     */
    public static final class Delta {

        private final List<Held> remove;
        private final List<BuiltPack> add;

        private Delta(List<Held> remove, List<BuiltPack> add) {
            this.remove = remove;
            this.add = add;
        }

        /** Packs to remove, deepest first. */
        public List<Held> remove() {
            return remove;
        }

        /** Packs to add, in stack order. */
        public List<BuiltPack> add() {
            return add;
        }

        /** Whether there is nothing to do. The common case, and worth checking. */
        public boolean isEmpty() {
            return remove.isEmpty() && add.isEmpty();
        }

        @Override
        public String toString() {
            return "-" + remove + " +" + add;
        }
    }

    private final Map<UUID, List<Held>> held = new ConcurrentHashMap<>();

    /** What {@code player} is holding, in stack order. Never null. */
    public List<Held> held(UUID player) {
        if (player == null) {
            return List.of();
        }
        List<Held> current = held.get(player);
        return current == null ? List.of() : List.copyOf(current);
    }

    /** Whether {@code player} is holding {@code bundle}, in any build of it. */
    public boolean holds(UUID player, String bundle) {
        for (Held one : held(player)) {
            if (one.bundle().equals(bundle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Works out what to send, without recording anything.
     *
     * <p>Separate from {@link #applied} on purpose: a push can be declined, or
     * the player can leave mid-swap, and recording it before it happened would
     * leave the engine believing in a pack nobody has. The caller records the
     * result once the sends have actually gone out.
     *
     * @param desired the bundles the player should end up holding, in stack
     *                order, bottom first
     */
    public Delta plan(UUID player, List<BuiltPack> desired) {
        List<Held> current = held(player);
        List<BuiltPack> wanted = desired == null ? List.of() : desired;

        // How much of the current stack is already right. Everything above the
        // first difference has to go, because a pack's position in the stack is
        // what decides who overrides whom, and there is no way to insert into
        // the middle of a client's stack.
        int shared = 0;
        while (shared < current.size() && shared < wanted.size()
                && current.get(shared).equals(Held.of(wanted.get(shared)))) {
            shared++;
        }

        List<Held> remove = new ArrayList<>(current.subList(shared, current.size()));
        // Deepest first, so a client processing them in order never has a gap
        // in the middle of its stack.
        Collections.reverse(remove);

        List<BuiltPack> add = new ArrayList<>(wanted.subList(shared, wanted.size()));
        return new Delta(List.copyOf(remove), List.copyOf(add));
    }

    /** Records that {@code player} now holds exactly {@code packs}, in stack order. */
    public void applied(UUID player, List<BuiltPack> packs) {
        if (player == null) {
            return;
        }
        if (packs == null || packs.isEmpty()) {
            held.remove(player);
            return;
        }
        List<Held> now = new ArrayList<>(packs.size());
        for (BuiltPack pack : packs) {
            now.add(Held.of(pack));
        }
        held.put(player, List.copyOf(now));
    }

    /**
     * Forgets a player.
     *
     * <p>Called on quit. Not doing so is a slow leak on a busy server, and
     * worse, a returning player would be believed to still hold packs their
     * client dropped when they disconnected.
     */
    public void forget(UUID player) {
        if (player != null) {
            held.remove(player);
        }
    }

    /** How many players are holding anything. */
    public int size() {
        return held.size();
    }
}
