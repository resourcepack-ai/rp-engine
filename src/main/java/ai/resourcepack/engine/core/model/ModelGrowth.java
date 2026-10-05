package ai.resourcepack.engine.core.model;

import ai.resourcepack.engine.api.ModelInfo;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Grows the placed pieces that {@code grow:} into others.
 *
 * <p><strong>Not an index of placed models</strong>, which this engine
 * deliberately does not keep: placed pieces are chunk-saved entities, so the
 * world is the index and cannot drift out of step with itself. This is narrower
 * and disposable — the uuids of the growable pieces that happen to be LOADED
 * right now, rebuilt from the world on every start and reload, fed by
 * placement and by chunk loads, and drained by breaks and chunk unloads. Lose
 * it and nothing is lost: {@link ModelPlacementListener#adoptLoaded} puts it
 * back. What it buys is that a check never has to ask the world "where are the
 * saplings", which would be a scan of every entity on the server every second.
 *
 * <p><strong>Work is spread</strong>: each run checks at most
 * {@link #PER_RUN} pieces and puts the rest back in line, so ten thousand
 * crops cost the same per tick as two hundred, and simply take longer to get
 * round. A piece that is not ready yet goes to the back.
 *
 * <p>Main thread only, like the entities it touches.
 */
public final class ModelGrowth {

    /** How often a check runs, in ticks. A second: growth is measured in tens of them. */
    private static final long PERIOD = 20L;

    /** The most pieces one run looks at. */
    private static final int PER_RUN = 200;

    private final Plugin plugin;
    private final ModelPlacementListener placements;

    /**
     * The pieces waiting, in the order they will be looked at. A linked set
     * rather than a queue, because a chunk unloading forgets hundreds at once
     * and removing from the middle of a queue is a walk along it each time.
     */
    private final LinkedHashSet<UUID> waiting = new LinkedHashSet<>();

    private BukkitTask task;

    public ModelGrowth(Plugin plugin, ModelPlacementListener placements) {
        this.plugin = plugin;
        this.placements = placements;
    }

    /** Starts checking. Idempotent. */
    public void start() {
        if (task == null) {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::run, PERIOD, PERIOD);
        }
    }

    /** Stops checking and forgets every piece. */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        waiting.clear();
    }

    /**
     * Forgets everything and asks the world again. After a reload, which may
     * have given pieces {@code grow:} or taken it away.
     */
    public void rescan() {
        waiting.clear();
        placements.adoptLoaded();
    }

    /** Adds a piece that can grow. A piece already waiting keeps its place in line. */
    void track(Interaction hitbox) {
        if (hitbox != null) {
            waiting.add(hitbox.getUniqueId());
        }
    }

    /** A piece that was broken, grew, or went with its chunk. */
    void forget(UUID hitbox) {
        waiting.remove(hitbox);
    }

    /** How many loaded pieces are waiting to grow, for a status line. */
    public int size() {
        return waiting.size();
    }

    private void run() {
        int budget = Math.min(PER_RUN, waiting.size());
        for (int i = 0; i < budget && !waiting.isEmpty(); i++) {
            Iterator<UUID> first = waiting.iterator();
            UUID uuid = first.next();
            first.remove();
            if (check(uuid)) {
                // Not yet: to the back.
                waiting.add(uuid);
            }
        }
    }

    /**
     * One look at one piece.
     *
     * @return whether it should stay in line: true for "not yet", false for
     *         gone, grown, or no longer able to grow
     */
    private boolean check(UUID uuid) {
        Entity entity = Bukkit.getEntity(uuid);
        if (!(entity instanceof Interaction) || !entity.isValid()) {
            return false;
        }
        Interaction hitbox = (Interaction) entity;
        Optional<ModelInfo> from = placements.infoOf(hitbox);
        ModelInfo.Grow grow = from.flatMap(ModelInfo::grow).orElse(null);
        if (grow == null) {
            return false;
        }
        Optional<ModelInfo> into = placements.info(grow.into());
        if (into.isEmpty()) {
            // The load checks this; a reload between two checks is how it can
            // still happen, and the answer is the same: never grow into
            // nothing.
            return false;
        }
        long standing = hitbox.getWorld().getGameTime() - placements.placedAt(hitbox);
        int light = hitbox.getLocation().getBlock().getLightLevel();
        if (!grow.ready(standing, light, ThreadLocalRandom.current().nextDouble())) {
            return true;
        }
        // The old one is forgotten as it is taken apart, and the new one joins
        // the line as it is placed if IT grows too — the next stage of a crop.
        placements.grow(hitbox, from.get(), into.get());
        return false;
    }
}
