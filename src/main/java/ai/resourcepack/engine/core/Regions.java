package ai.resourcepack.engine.core;

import ai.resourcepack.common.scheduler.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Predicate;

/**
 * Where a piece of world work runs, for the engine's own per-tick passes.
 *
 * <p>Every runtime here (rigs, emotes, vehicles, overlays, armour, liquids,
 * objects, VFX, growth) is one timer walking a collection of things, each of
 * which lives on one entity or at one place. On Spigot and Paper that walk is
 * the main thread and touches everything itself, exactly as it always has: a
 * {@link Fanout} there is a direct call.
 *
 * <p>Folia has no main thread. The timer runs on the global region, which may
 * touch no entity and no block, so the walk only decides <em>what</em> is due
 * and a {@link Fanout} hands each item's work to the region that owns its
 * anchor. Two rules make that safe:
 *
 * <ul>
 *   <li><b>One pass in flight per item.</b> A region that is behind would
 *   otherwise be handed a second pass before it ran the first, and a busy one
 *   a growing queue. An item whose last pass has not run is skipped, which is
 *   what a lagging main thread does to it on Paper anyway.</li>
 *   <li><b>The global walk reads, it does not write.</b> Whatever an item's
 *   pass changes, it changes on the item's own thread; the walk only looks at
 *   the collection, which is why the collections these walk are concurrent.</li>
 * </ul>
 *
 * <p>Internal. Not part of the supported API.
 */
public final class Regions {
    private Regions() {
    }

    /** Whether this server is Folia, so that anything world-shaped is regional. */
    public static boolean folia() {
        return Tasks.folia();
    }

    /**
     * Runs {@code work} on the main thread, or Folia's global region: now if
     * this is that thread, else on its next tick. For work that touches the
     * server as a whole (the registry, a reload, the console) and no one
     * entity or place.
     */
    public static void global(Plugin plugin, Runnable work) {
        if (Tasks.global()) {
            work.run();
        } else {
            Tasks.global(plugin, work);
        }
    }

    /**
     * Moves {@code entity} to {@code to}, and says whether it moved.
     *
     * <p>Off Folia this is {@code Entity.teleport} and its answer, unchanged.
     * Folia refuses that call and only teleports asynchronously, so there the
     * move is started and the answer is optimistic: every caller here moves
     * its own display, seat or rider, on the thread that owns it, where the
     * only refusal would be a cancelled event nobody can act on a tick late.
     */
    public static boolean move(Entity entity, Location to) {
        if (!Tasks.folia()) {
            return entity.teleport(to);
        }
        Tasks.teleport(entity, to);
        return true;
    }

    /**
     * Removes {@code entity}: now when this thread owns it (always, off Folia),
     * else on its own region's next tick. For tearing down from a loop, a quit
     * or a reload, which on Folia is seldom the entity's thread.
     */
    public static void remove(Plugin plugin, Entity entity) {
        if (entity == null) {
            return;
        }
        if (Tasks.owns(entity)) {
            entity.remove();
        } else {
            Tasks.on(plugin, entity, entity::remove);
        }
    }

    /** {@link #remove(Plugin, Entity)}, scheduled as the engine itself. */
    public static void remove(Entity entity) {
        if (entity != null && !Tasks.owns(entity)) {
            remove(org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(Regions.class), entity);
        } else if (entity != null) {
            entity.remove();
        }
    }

    /**
     * Runs {@code command} as the console. On Folia the console's commands
     * belong to the global region, so one asked for from a region (a click, a
     * sequence step) is sent there, to run on its next tick; elsewhere, and
     * from the global region itself, it runs now. The answer is whether it was
     * found and run, which on Folia is only known now when it ran now; a
     * deferred one says true.
     */
    public static boolean console(Plugin plugin, String command) {
        if (!Tasks.folia() || Tasks.global()) {
            return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        }
        Tasks.global(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
        return true;
    }

    /**
     * Runs {@code work} for each player on that player's thread, then hands
     * {@code then} how many it counted, on the main thread or Folia's global
     * region. Off Folia all of it happens before this returns, exactly as a
     * loop would. On Folia each player's part runs on their region's next tick
     * and {@code then} after the last of them; a player who leaves in between
     * is not counted.
     */
    public static void each(Plugin plugin, Collection<? extends Player> players, Predicate<Player> work,
                            IntConsumer then) {
        if (!Tasks.folia()) {
            int counted = 0;
            for (Player player : players) {
                if (work.test(player)) {
                    counted++;
                }
            }
            then.accept(counted);
            return;
        }
        List<Player> all = new ArrayList<>(players);
        if (all.isEmpty()) {
            Tasks.global(plugin, () -> then.accept(0));
            return;
        }
        AtomicInteger left = new AtomicInteger(all.size());
        AtomicInteger counted = new AtomicInteger();
        Runnable one = () -> {
            if (left.decrementAndGet() == 0) {
                Tasks.global(plugin, () -> then.accept(counted.get()));
            }
        };
        for (Player player : all) {
            Tasks.Handle handle = Tasks.onLater(plugin, player, () -> {
                try {
                    if (work.test(player)) {
                        counted.incrementAndGet();
                    }
                } finally {
                    one.run();
                }
            }, one, 1);
            if (handle == Tasks.Handle.NONE) {
                one.run();
            }
        }
    }

    /**
     * Hands {@code found} every loaded entity of {@code type}, for a runtime
     * picking up what was already standing in the world (a reload, a late
     * start). Off Folia that is each world's entity list, at once. On Folia no
     * one thread can read every region's entities, so it is each loaded
     * chunk's, read on the region that owns the chunk, a tick or so later;
     * a chunk that unloads in between simply has nothing to say, and whatever
     * loads afterwards arrives by {@code EntitiesLoadEvent} as it always has.
     */
    public static <T extends Entity> void loaded(Plugin plugin, Class<T> type, Consumer<T> found) {
        if (!Tasks.folia()) {
            for (World world : Bukkit.getWorlds()) {
                for (T entity : world.getEntitiesByClass(type)) {
                    found.accept(entity);
                }
            }
            return;
        }
        loadedChunks(plugin, chunk -> {
            for (Entity entity : chunk.getEntities()) {
                if (type.isInstance(entity)) {
                    found.accept(type.cast(entity));
                }
            }
        });
    }

    /**
     * Hands {@code found} every loaded chunk, on the thread that owns it: at
     * once off Folia, and on Folia from each chunk's region a tick or so later,
     * skipping one that has unloaded since.
     */
    public static void loadedChunks(Plugin plugin, Consumer<Chunk> found) {
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                if (!Tasks.folia()) {
                    found.accept(chunk);
                    continue;
                }
                int x = chunk.getX();
                int z = chunk.getZ();
                Tasks.at(plugin, new Location(world, x << 4, 0, z << 4), () -> {
                    if (world.isChunkLoaded(x, z)) {
                        found.accept(world.getChunkAt(x, z));
                    }
                });
            }
        }
    }

    /**
     * A per-tick pass over items, each run on the thread that owns it. Keep
     * one per collection: it remembers which items have a pass in flight.
     */
    public static final class Fanout<T> {
        private final Plugin plugin;
        private final Set<T> pending = ConcurrentHashMap.newKeySet();

        public Fanout(Plugin plugin) {
            this.plugin = plugin;
        }

        /**
         * Runs {@code work} for {@code item} on {@code anchor}'s thread: now
         * when this thread owns it (always, off Folia), else on its next tick.
         * Nothing happens for a null anchor or, on Folia, one that is gone.
         */
        public void on(T item, Entity anchor, Consumer<T> work) {
            if (anchor == null) {
                return;
            }
            if (Tasks.owns(anchor)) {
                work.accept(item);
                return;
            }
            if (!pending.add(item)) {
                return;
            }
            Tasks.Handle handle = Tasks.onLater(plugin, anchor, () -> {
                pending.remove(item);
                work.accept(item);
            }, () -> pending.remove(item), 1);
            if (handle == Tasks.Handle.NONE) {
                pending.remove(item);
            }
        }

        /** As {@link #on}, for an item that lives at a place rather than on an entity. */
        public void at(T item, Location anchor, Consumer<T> work) {
            if (anchor == null || anchor.getWorld() == null) {
                return;
            }
            if (Tasks.owns(anchor)) {
                work.accept(item);
                return;
            }
            if (!pending.add(item)) {
                return;
            }
            Tasks.at(plugin, anchor, () -> {
                pending.remove(item);
                work.accept(item);
            });
        }

        /** Forgets everything in flight, for a runtime that is stopping. */
        public void clear() {
            pending.clear();
        }
    }
}
