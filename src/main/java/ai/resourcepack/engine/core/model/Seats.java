package ai.resourcepack.engine.core.model;

import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import ai.resourcepack.engine.api.event.ModelSeatEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sitting on a placed model.
 *
 * <p>A seat is an invisible marker armour stand with the player riding it.
 * That is the only way to sit somebody in vanilla: the player has to be a
 * passenger of something, and an armour stand is the smallest something there
 * is. A marker one has no hitbox, takes no damage and is not saved to the
 * chunk, so an unclean shutdown cannot leave a field of invisible stands
 * behind — the thing that goes wrong with every implementation of this.
 *
 * <p>The stand is removed when the player gets off, when they log out, and when
 * the model is broken. All three, because a seat that outlives its chair is an
 * invisible thing a player can stand on for ever.
 */
public final class Seats implements Listener {

    /**
     * An armour stand sits its passenger this far below its own position, so
     * the stand is spawned that much higher than the seat is meant to be.
     */
    private static final double MOUNT_OFFSET = 1.75;

    private final Plugin plugin;

    /** Player -> the stand they are riding. */
    private final Map<UUID, UUID> seated = new ConcurrentHashMap<>();

    public Seats(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Sits {@code player} at {@code where}. Main thread only.
     *
     * @return whether they sat down
     */
    public boolean sit(Player player, Location where) {
        if (player == null || where == null || where.getWorld() == null) {
            return false;
        }
        if (seated.containsKey(player.getUniqueId()) || player.isInsideVehicle()) {
            return false;
        }

        // Asked here rather than at each call site: a chair, a seat bone and
        // anything added later all arrive through this method, and a server
        // that refuses sitting means all of them.
        ModelSeatEvent asked = new ModelSeatEvent(player, where);
        player.getServer().getPluginManager().callEvent(asked);
        if (asked.isCancelled()) {
            return false;
        }

        Location at = where.clone().subtract(0, MOUNT_OFFSET, 0);
        at.setYaw(where.getYaw());
        ArmorStand stand = where.getWorld().spawn(at, ArmorStand.class, s -> {
            // A marker has no hitbox and no collision, which is what makes it
            // invisible in every sense rather than just unrendered.
            s.setMarker(true);
            s.setVisible(false);
            s.setGravity(false);
            s.setInvulnerable(true);
            s.setSilent(true);
            // Not saved to the chunk: an unclean shutdown then cannot leave a
            // field of these behind, which is how this feature usually rots.
            s.setPersistent(false);
        });

        if (!stand.addPassenger(player)) {
            stand.remove();
            return false;
        }
        seated.put(player.getUniqueId(), stand.getUniqueId());
        return true;
    }

    /** Whether this player is sitting on something of ours. */
    public boolean isSeated(Player player) {
        return player != null && seated.containsKey(player.getUniqueId());
    }

    /** Gets a player up, if they were sitting. */
    public void stand(Player player) {
        if (player == null) {
            return;
        }
        UUID standId = seated.remove(player.getUniqueId());
        if (standId == null) {
            return;
        }
        Entity stand = player.getServer().getEntity(standId);
        if (stand != null) {
            stand.remove();
        }
    }

    /**
     * Removes every stand. Called when the plugin unloads.
     *
     * <p>By stand rather than by player: somebody can be seated and offline for
     * the moment it takes a quit to be processed, and a stand nobody is riding
     * is exactly the thing this class exists to not leave behind.
     */
    public void clear() {
        for (UUID standId : seated.values()) {
            Entity stand = plugin.getServer().getEntity(standId);
            if (stand != null) {
                stand.remove();
            }
        }
        seated.clear();
    }

    @EventHandler
    public void onDismount(EntityDismountEvent event) {
        if (event.getEntity() instanceof Player) {
            stand((Player) event.getEntity());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // Logging out while seated leaves the stand behind otherwise, and it
        // is not persistent, so it would survive exactly until the chunk
        // unloaded and no longer.
        stand(event.getPlayer());
    }
}
