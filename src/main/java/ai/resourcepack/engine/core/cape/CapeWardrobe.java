package ai.resourcepack.engine.core.cape;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is wearing which cape.
 *
 * <p>Kept on the PLAYER, in their persistent data, under the cape's label
 * ({@code studio:<id>} or {@code namespace:path}): it survives a relog and a
 * restart with nothing of ours to save, and goes wherever the player's data
 * goes. A label naming a cape this server no longer has is simply nothing
 * drawn, and comes back by itself if the cape does (a push that lapsed and was
 * synced again).
 */
public final class CapeWardrobe {

    private final NamespacedKey key;
    private final CapeCatalogue capes;
    /** Who asked not to see their own cape. Session-only. */
    private final Set<UUID> hidingOwn = ConcurrentHashMap.newKeySet();

    public CapeWardrobe(Plugin plugin, CapeCatalogue capes) {
        this.key = new NamespacedKey(plugin, "cape");
        this.capes = capes;
    }

    /** The label stored on the player, whether or not this server has that cape. */
    public String label(Player player) {
        try {
            return player.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The cape the player has on, or null. */
    public Cape worn(Player player) {
        String label = label(player);
        return label == null ? null : capes.find(label);
    }

    public void wear(Player player, Cape cape) {
        player.getPersistentDataContainer().set(key, PersistentDataType.STRING, cape.label());
    }

    /** @return whether they had one on */
    public boolean remove(Player player) {
        boolean had = label(player) != null;
        player.getPersistentDataContainer().remove(key);
        return had;
    }

    /** Whether a player may pick {@code cape} for THEMSELVES: the cape's own node, if it names one. */
    public static boolean mayChoose(Player player, Cape cape) {
        return cape.permission() == null || player.hasPermission(cape.permission());
    }

    /**
     * Toggles whether a player sees their own cape (in F5, as everybody else
     * does). On by default, as vanilla draws its own.
     *
     * @return whether they now see it
     */
    public boolean toggleOwn(Player player) {
        UUID id = player.getUniqueId();
        return !hidingOwn.add(id) && hidingOwn.remove(id);
    }

    public boolean seesOwn(Player player) {
        return !hidingOwn.contains(player.getUniqueId());
    }
}
