package ai.resourcepack.engine.core.model;

import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import ai.resourcepack.engine.api.event.ModelSeatEvent;
import ai.resourcepack.engine.api.Feature;
import ai.resourcepack.engine.core.version.Compatibility;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityEvent;
import org.bukkit.event.Listener;
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
     * How far a seated player is drawn ABOVE their own position.
     *
     * <p>{@code seat:} in a pack means "where somebody's backside goes": the
     * height of the seat's SURFACE. A riding player's legs swing forward about
     * the hip, 12 pixels above their position, and a leg is 4 pixels thick, so
     * the underside of the thigh rests 10 pixels up. That is the number.
     *
     * <p>It was 0.3 until 2026-10-12, which sat everybody a third of a block
     * above every chair, floating over the canvas of a director's chair whose
     * {@code seat: 0.5} was exactly right. Vehicles carry 12/16 for the same
     * leg (VehicleRuntime) because a vehicle seat's point is the hip, not the
     * surface under it.
     *
     * <p>{@link #calibrate(double)} still adds to it, for a rig nobody here
     * built.
     */
    private static final double SEATED_POSE = 10 / 16.0;

    private final Plugin plugin;

    /** Added to every seat, from config.yml. Nudges every chair on the server at once. */
    private volatile double calibration;

    /** Player -> the stand they are riding. */
    private final Map<UUID, UUID> seated = new ConcurrentHashMap<>();

    /** Which of the two mount offsets this server's vanilla actually uses. */
    private final double mountOffset;

    public Seats(Plugin plugin, Compatibility compatibility) {
        this.plugin = plugin;
        // MountOffset rather than a constant here: vehicles seat people on the
        // same kind of marker stand, and this is the number that is invisible
        // when it is wrong.
        this.mountOffset = MountOffset.forServer(compatibility);
    }

    /**
     * Listens for dismounts, whichever package this server keeps that event in.
     *
     * <p>{@code EntityDismountEvent} moved from {@code org.spigotmc.event.entity}
     * to {@code org.bukkit.event.entity} in 1.20.1. That is not something an
     * {@code if} can span: an {@code @EventHandler} method names its event
     * type in its signature, and Bukkit reflects over those when the listener
     * is registered — so a handler for the class this server does not have
     * fails the whole registration, taking every other listener in the class
     * with it.
     *
     * <p>So the class is looked up by name and registered through an executor
     * instead. Both versions of the event extend {@code EntityEvent}, which is
     * where {@code getEntity()} lives, so nothing past the lookup is
     * reflective.
     */
    @SuppressWarnings("unchecked")
    public void registerDismount(Plugin owner) {
        Class<?> found = null;
        for (String name : new String[] {
                "org.bukkit.event.entity.EntityDismountEvent",
                "org.spigotmc.event.entity.EntityDismountEvent" }) {
            try {
                found = Class.forName(name);
                break;
            } catch (ClassNotFoundException ignored) {
                // Try the other spelling.
            }
        }
        if (found == null || !Event.class.isAssignableFrom(found)) {
            // Neither exists, which no supported version does. Seats still
            // work; they are cleaned up on quit and on the model breaking,
            // and only standing up early leaves one behind.
            owner.getLogger().warning("This server has no EntityDismountEvent, so a seat is "
                    + "cleared when you log out or the model breaks rather than the moment "
                    + "you stand up.");
            return;
        }
        owner.getServer().getPluginManager().registerEvent(
                (Class<? extends Event>) found, this, EventPriority.NORMAL,
                (listener, event) -> {
                    if (event instanceof EntityEvent
                            && ((EntityEvent) event).getEntity() instanceof Player) {
                        stand((Player) ((EntityEvent) event).getEntity());
                    }
                },
                owner);
    }

    /**
     * Adopts {@code models.seat-offset}. Called on enable and on every reload.
     *
     * <p>Here rather than only on the model because the thing being corrected
     * is the SITTING, not the chair: it is the same amount wrong for every
     * piece of furniture on the server, and a server owner should be able to
     * fix all of them by looking at one and typing {@code /rp reload} rather
     * than editing every pack they installed.
     */
    public void calibrate(double offset) {
        this.calibration = offset;
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

        Location at = where.clone().add(0, mountOffset - SEATED_POSE + calibration, 0);
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
    public void onQuit(PlayerQuitEvent event) {
        // Logging out while seated leaves the stand behind otherwise, and it
        // is not persistent, so it would survive exactly until the chunk
        // unloaded and no longer.
        stand(event.getPlayer());
    }
}
