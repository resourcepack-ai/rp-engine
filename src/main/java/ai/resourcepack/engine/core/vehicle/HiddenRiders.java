package ai.resourcepack.engine.core.vehicle;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is in a seat that draws nobody.
 *
 * <p>A seat marked {@code hidden} exists for a vehicle whose art already has
 * its rider in it — an enclosed cockpit, a tank, a mech — where a body in the
 * seat is a second person inside the fuselage. So the occupant is not dressed
 * in a rig (see {@code Vehicles.dressOccupants}) and is not sent to anybody
 * else's client either, which is this class.
 *
 * <p><strong>{@code hidePlayer} and nothing else, deliberately.</strong> The
 * emote system hides a rig's wearer with three tools — {@code hidePlayer} for
 * everybody else, an infinite invisibility potion for the wearer's own third
 * person, and an equipment packet for the armour that keeps rendering through
 * it — and it pays for the second one with a persistent-data marker and a
 * restore-if-stranded pass on join, because an infinite potion applied by a
 * plugin that then crashes is a player who is invisible for ever.
 *
 * <p>None of that is worth buying here. {@code hidePlayer} is per-viewer and
 * client-side: it writes nothing to the player, survives no restart, and a
 * server that goes down mid-flight comes back up with everybody visible. The
 * honest cost is that a hidden rider who presses F5 still sees themselves —
 * Bukkit cannot hide a player from their own client, and the only thing that
 * can is the potion this refuses. In first person, which is where somebody
 * flying a plane is, there is nothing to see either way.
 *
 * <p><strong>Somebody the emote system is already hiding is left alone.</strong>
 * Both use the same plugin as the key, so showing them again would take a
 * running emote's body out of hiding while its rig was still on. Whoever hid
 * them is who reveals them.
 */
final class HiddenRiders {

    private final Plugin plugin;

    /**
     * Who this class has hidden, so it only ever reveals its own.
     *
     * <p>Also what a joining player is told about: somebody who logs in while
     * a hidden rider is already in a seat was never sent the hide, and would be
     * the one player in the world who can see a driver sitting inside their own
     * cockpit.
     */
    private final Set<UUID> hidden = ConcurrentHashMap.newKeySet();

    HiddenRiders(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Takes a rider off every other client. */
    void hide(Player player) {
        if (player == null) {
            return;
        }
        hidden.add(player.getUniqueId());
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            if (!viewer.getUniqueId().equals(player.getUniqueId())) {
                viewer.hidePlayer(plugin, player);
            }
        }
    }

    /**
     * And back, as they get out.
     *
     * <p>Takes a uuid rather than a Player because every caller is a path that
     * may be running for somebody who has already logged off — a quit, a chunk
     * unload, the plugin disabling — and the seat still has to be forgotten.
     *
     * @param emoting whether something else is currently hiding them, in which
     *                case the body stays hidden and that other thing is what
     *                will reveal it
     */
    void show(UUID id, boolean emoting) {
        if (id == null || !hidden.remove(id)) {
            return;
        }
        Player player = plugin.getServer().getPlayer(id);
        if (player == null || emoting) {
            return;
        }
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            if (!viewer.getUniqueId().equals(id)) {
                viewer.showPlayer(plugin, player);
            }
        }
    }

    /** Hides everybody currently in a hidden seat from somebody who just arrived. */
    void greet(Player joiner) {
        if (joiner == null) {
            return;
        }
        for (UUID id : hidden) {
            Player rider = plugin.getServer().getPlayer(id);
            if (rider != null && !rider.getUniqueId().equals(joiner.getUniqueId())) {
                joiner.hidePlayer(plugin, rider);
            }
        }
    }

    /** Whether this class is hiding somebody. */
    boolean holds(UUID id) {
        return id != null && hidden.contains(id);
    }

    /**
     * Reveals everybody, for the plugin unloading.
     *
     * <p>A reload takes the seats apart, and a rider left invisible by a plugin
     * that is no longer running has nothing left to fix it.
     */
    void clear() {
        for (UUID id : Set.copyOf(hidden)) {
            show(id, false);
        }
        hidden.clear();
    }
}
