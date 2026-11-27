package ai.resourcepack.engine.core.hook;

import ai.resourcepack.engine.api.event.ModelBreakEvent;
import ai.resourcepack.engine.api.event.ModelPlaceEvent;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Lets every protection plugin say no to a model being put down or broken
 * ({@code models.protection-check}).
 *
 * <p>A placed model is entities, not a block, so GriefPrevention, Towny,
 * Lands, Residence, PlotSquared and the rest hear nothing when one goes down
 * or comes away, and somebody could furnish or strip a claim that is not
 * theirs. None of them knows {@link ModelPlaceEvent} or {@link ModelBreakEvent}
 * either. What they all know is the block events, so this asks with those: a
 * {@code BlockPlaceEvent} or {@code BlockBreakEvent} at the model's block, for
 * the player doing it, and the model's own event is cancelled when one of them
 * is. Nothing is placed or broken by the question; it is only asked. A liquid
 * bucket already asks the same way.
 *
 * <p>The question is a real block event, so the engine's own block listeners
 * hear it too. They skip anything {@link #isProbe} says is one of these, so a
 * question never runs an item's break action or adopts a hopper.
 *
 * <p>Off unless a server turns it on. Every plugin hears the question, not
 * only protection plugins: a logging plugin (CoreProtect and the like) records
 * it as a block placed or broken, which a rollback would then act on, and a
 * plugin that rewards placing or breaking blocks (Jobs, mcMMO) may pay for it.
 * Nothing about a server changes until its owner decides that is a fair price.
 */
public final class ProtectionCheck implements Listener {

    /** What the questions this asks are, so the engine's block listeners can leave them alone. */
    public interface Probe {
    }

    private static final class PlaceProbe extends BlockPlaceEvent implements Probe {
        PlaceProbe(Block block, ItemStack held, Player player) {
            super(block, block.getState(), block, held, player, true, EquipmentSlot.HAND);
        }
    }

    private static final class BreakProbe extends BlockBreakEvent implements Probe {
        BreakProbe(Block block, Player player) {
            super(block, player);
        }
    }

    private final Plugin plugin;

    private ProtectionCheck(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Starts asking, from {@code onEnable}. Read once: a change takes a restart. */
    public static boolean listen(Plugin plugin) {
        if (!plugin.getConfig().getBoolean("models.protection-check", false)) {
            return false;
        }
        plugin.getServer().getPluginManager().registerEvents(new ProtectionCheck(plugin), plugin);
        return true;
    }

    /** Whether {@code event} is a question this asked, rather than a block really placed or broken. */
    public static boolean isProbe(Event event) {
        return event instanceof Probe;
    }

    // LOW, so a plugin listening at NORMAL or above to the model's own event
    // still sees it cancelled. A model put down by a plugin with no player
    // behind it is that plugin's call, and is not asked about.
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(ModelPlaceEvent event) {
        Player player = event.getPlayer();
        Block block = event.block();
        if (player == null || block == null) {
            return;
        }
        ItemStack held = event.stack() != null ? event.stack() : player.getInventory().getItemInMainHand();
        PlaceProbe probe = new PlaceProbe(block, held, player);
        plugin.getServer().getPluginManager().callEvent(probe);
        if (probe.isCancelled() || !probe.canBuild()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(ModelBreakEvent event) {
        Player player = event.player();
        Location location = event.location();
        if (player == null || location == null || location.getWorld() == null) {
            return;
        }
        BreakProbe probe = new BreakProbe(location.getBlock(), player);
        plugin.getServer().getPluginManager().callEvent(probe);
        if (probe.isCancelled()) {
            event.setCancelled(true);
        }
    }
}
