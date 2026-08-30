package ai.resourcepack.engine.core.block;

import ai.resourcepack.engine.api.BlockInfo;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How long a custom block takes to break, and what has to be held.
 *
 * <p>Both are properties of a block's TYPE in Minecraft, and a custom block's
 * type is a note block: 0.8 hardness, breakable by hand in under a second,
 * whatever the pack says it is. So a pack's {@code hardness} and {@code tool}
 * mean nothing unless the engine breaks the block itself — which is what this
 * does.
 *
 * <p>Vanilla's own breaking is cancelled, and a timer takes its place: progress
 * accumulates each tick, the ten-stage crack overlay is sent to everybody who
 * can see it, and the block is broken through the ordinary
 * {@code BlockBreakEvent} when it reaches one. Every protection plugin
 * therefore still gets its say, and so does {@link CustomBlocks}, which is what
 * drops the right item.
 *
 * <p><strong>Speed follows vanilla's own arithmetic</strong>, near enough: a
 * block takes {@code hardness × 1.5} seconds with the right tool and
 * {@code hardness × 5} without, divided by the tool's own speed. The numbers
 * are vanilla's so that a pack saying 1.5 gets something that feels like stone,
 * which is the only reason anybody writes a hardness at all.
 */
public final class BlockBreaking {

    /** Vanilla's own multipliers, by tool tier. */
    private static final Map<String, Double> TIERS = Map.of(
            "WOODEN", 2.0, "STONE", 4.0, "IRON", 6.0,
            "DIAMOND", 8.0, "NETHERITE", 9.0, "GOLDEN", 12.0);

    /** How often progress is recomputed and the overlay re-sent. */
    private static final long PERIOD_TICKS = 2L;

    /** One player breaking one block. */
    private static final class Attempt {
        final Block block;
        final BlockInfo info;
        final int overlay;
        double progress;
        BukkitTask task;

        Attempt(Block block, BlockInfo info, int overlay) {
            this.block = block;
            this.info = info;
            this.overlay = overlay;
        }
    }

    private final Plugin plugin;
    private final Map<UUID, Attempt> breaking = new ConcurrentHashMap<>();

    public BlockBreaking(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Starts breaking, replacing whatever this player was breaking before.
     *
     * <p>The overlay id is the player's entity id, so two people breaking two
     * blocks do not overwrite each other's cracks.
     */
    public void start(Player player, Block block, BlockInfo info) {
        stop(player);
        Attempt attempt = new Attempt(block, info, player.getEntityId());
        breaking.put(player.getUniqueId(), attempt);
        attempt.task = plugin.getServer().getScheduler().runTaskTimer(plugin,
                () -> tick(player, attempt), PERIOD_TICKS, PERIOD_TICKS);
    }

    /** Gives up, and clears the cracks. */
    public void stop(Player player) {
        Attempt attempt = breaking.remove(player.getUniqueId());
        if (attempt == null) {
            return;
        }
        if (attempt.task != null) {
            attempt.task.cancel();
        }
        show(player, attempt, -1);
    }

    /** Whether this player is part-way through breaking something of ours. */
    public boolean isBreaking(Player player) {
        return breaking.containsKey(player.getUniqueId());
    }

    private void tick(Player player, Attempt attempt) {
        // Anything that means they are no longer breaking this: they left, the
        // block is gone, or they walked away from it.
        if (!player.isOnline() || attempt.block.getType() == Material.AIR
                || attempt.block.getLocation().distanceSquared(player.getLocation()) > 36) {
            stop(player);
            return;
        }

        attempt.progress += PERIOD_TICKS / 20.0 / secondsToBreak(attempt.info, player.getInventory().getItemInMainHand());
        if (attempt.progress < 1) {
            show(player, attempt, (int) (attempt.progress * 9));
            return;
        }

        stop(player);
        // Through the ordinary event, so protection plugins and our own drop
        // handling both run exactly as they would for a vanilla break.
        player.breakBlock(attempt.block);
    }

    /** The crack overlay, or -1 to clear it. */
    private void show(Player player, Attempt attempt, int stage) {
        Location at = attempt.block.getLocation();
        for (Player viewer : attempt.block.getWorld().getPlayers()) {
            if (viewer.getLocation().distanceSquared(at) < 64 * 64) {
                viewer.sendBlockDamage(at, stage < 0 ? 0f : Math.min(1f, (stage + 1) / 10f),
                        attempt.overlay);
            }
        }
    }

    /**
     * How long this block takes with this tool, in seconds.
     *
     * <p>Vanilla's shape: the wrong tool is five times the hardness, the right
     * one is one and a half, and the tier divides it. A hardness of zero is
     * instant, which is what a plant wants.
     */
    static double secondsToBreak(BlockInfo block, ItemStack held) {
        if (block.hardness() <= 0) {
            return 0.05;
        }
        boolean correct = isCorrectTool(block, held);
        double seconds = block.hardness() * (correct ? 1.5 : 5.0);
        return Math.max(0.05, seconds / (correct ? tierSpeed(held) : 1.0));
    }

    /** Whether what is held is the tool the block asked for. */
    static boolean isCorrectTool(BlockInfo block, ItemStack held) {
        Optional<String> wanted = block.tool();
        if (wanted.isEmpty()) {
            return true;
        }
        String material = held == null ? "AIR" : held.getType().name();
        return material.endsWith("_" + wanted.get().toUpperCase(Locale.ROOT));
    }

    /** How much faster this tool's tier is. Bare hands are 1. */
    private static double tierSpeed(ItemStack held) {
        if (held == null) {
            return 1.0;
        }
        String material = held.getType().name();
        for (Map.Entry<String, Double> tier : TIERS.entrySet()) {
            if (material.startsWith(tier.getKey() + "_")) {
                return tier.getValue();
            }
        }
        return 1.0;
    }
}
