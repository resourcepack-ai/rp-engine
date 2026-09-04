package ai.resourcepack.engine.core.vehicle;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Team;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is in a seat that draws nobody, and the three tools it takes to mean it.
 *
 * <p>A seat marked {@code hidden} exists for a vehicle whose art already has
 * its rider in it — an enclosed cockpit, a tank, a mech — where a body in the
 * seat is a second person inside the fuselage. So the occupant is not dressed
 * in a rig (see {@code Vehicles.dressOccupants}) and is not drawn at all, which
 * is this class.
 *
 * <p><strong>"Not drawn at all" needs three things and it took all three to
 * work.</strong> The first cut did only the first of them, on the argument that
 * the rider's own view was a cosmetic detail worth trading for having no server
 * state to leave behind. It was not: sitting in your own tank and watching
 * yourself sit in it is precisely how somebody checks whether the switch did
 * anything, so a rider hidden from everybody except themselves reads as a
 * feature that does not work.
 *
 * <ul>
 *   <li>{@code hidePlayer}, per viewer, which is the only one that reaches
 *       other people. It does not send the entity at all, so armour, nametag
 *       and held item go with it.</li>
 *   <li>An <strong>infinite invisibility potion</strong>, because Bukkit cannot
 *       hide a player from their own client and this is the only thing that
 *       covers their third person.</li>
 *   <li>A blank <strong>equipment packet to their own client</strong>, because
 *       armour keeps rendering on an invisible player — the potion hides a
 *       skin, and a skin is not a body. Client-side only: nothing is taken off
 *       anybody, so there is no armour to lose.</li>
 * </ul>
 *
 * <p><strong>The potion is only ever applied to somebody who has none</strong>,
 * and that one rule is what keeps this simple where {@code EmoteDirector} needs
 * a codec. That class hides a player who may be mid-potion and has to hand the
 * remainder back afterwards; this one declines the job — if they are already
 * invisible, whether from a potion they drank or from an emote they are
 * wearing, their body is already covered and there is nothing for us to add.
 * So we never overwrite anything and never owe anything back, and taking ours
 * off is unconditional rather than arithmetic.
 *
 * <p><strong>The one persistent thing here is a crash marker.</strong> An
 * infinite potion applied by a plugin that then dies is a player who is
 * invisible for ever, so the fact that we applied one is written to their
 * persistent data and read back on join. Every ordinary way out of a seat —
 * a dismount, a quit, a reload, the plugin disabling — clears it long before
 * that; the marker is for the way out that is not ordinary.
 */
final class HiddenRiders {

    /**
     * The four slots whose contents keep rendering on an invisible player.
     *
     * <p>The hands are deliberately absent. Sending a blank hand is what broke
     * bows, shields, eating and crossbows in the emote subsystem — a client
     * applies the packet to its own inventory and then declines to start a use
     * action it believes it has nothing to start. Armour has no use action to
     * break.
     */
    private static final EquipmentSlot[] ARMOUR = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private final Plugin plugin;

    /** Says we applied an infinite invisibility, for a start-up that follows a crash. */
    private final NamespacedKey markerKey;

    /**
     * Who this class has hidden, so it only ever reveals its own.
     *
     * <p>Also what a joining player is told about: somebody who logs in while a
     * hidden rider is already in a seat was never sent the hide, and would be
     * the one player in the world who can see a driver sitting inside their own
     * cockpit.
     */
    private final Set<UUID> hidden = ConcurrentHashMap.newKeySet();

    /**
     * Who we gave the potion to, as opposed to who was invisible already.
     *
     * <p>The difference is the whole of the restore: taking a potion off
     * somebody who brought their own would be this class ending an effect it
     * never started.
     */
    private final Set<UUID> potioned = ConcurrentHashMap.newKeySet();

    /** Said once, however many riders are ghosted. */
    private boolean warnedGhost;

    HiddenRiders(Plugin plugin) {
        this.plugin = plugin;
        this.markerKey = new NamespacedKey(plugin, "hidden_rider");
    }

    /** Takes a rider off every client, including their own. */
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
        // Somebody already invisible is already covered — see the class note.
        // Their own effect is left exactly as it is and we owe them nothing
        // back.
        if (player.getPotionEffect(PotionEffectType.INVISIBILITY) == null) {
            player.addPotionEffect(new PotionEffect(
                PotionEffectType.INVISIBILITY,
                PotionEffect.INFINITE_DURATION,
                0,
                false,  // not ambient: ambient is the beacon look, dimmer but still swirling
                false,  // no particles, which is the whole point of using this
                false)); // no inventory icon either — this is not a status they chose
            potioned.add(player.getUniqueId());
            player.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
        }
        armour(player, true);
        warnIfGhosted(player);
    }

    /**
     * And back, as they get out.
     *
     * <p>Takes a uuid rather than a Player because every caller is a path that
     * may be running for somebody who has already logged off — a quit, a chunk
     * unload, the plugin disabling — and the seat still has to be forgotten.
     *
     * @param emoting whether an emote is currently hiding them from other
     *                people, in which case their body stays off everybody
     *                else's screen and that emote is what will put it back.
     *                Our own potion comes off either way: an emote that hid
     *                them applied its own, and ours would outlive it
     */
    void show(UUID id, boolean emoting) {
        boolean wasHidden = hidden.remove(id);
        boolean wasPotioned = potioned.remove(id);
        if (!wasHidden && !wasPotioned) {
            return;
        }
        Player player = plugin.getServer().getPlayer(id);
        if (player == null) {
            return;
        }
        if (wasPotioned) {
            player.removePotionEffect(PotionEffectType.INVISIBILITY);
        }
        player.getPersistentDataContainer().remove(markerKey);
        // Their armour back on their own screen before anything else, so there
        // is never a frame of a visible body in an empty set.
        armour(player, false);
        player.updateInventory();
        if (emoting) {
            return;
        }
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            if (!viewer.getUniqueId().equals(id)) {
                viewer.showPlayer(plugin, player);
            }
        }
    }

    /**
     * Hides everybody currently in a hidden seat from somebody who just
     * arrived, and un-strands the arrival if we crashed while hiding them.
     */
    void greet(Player joiner) {
        if (joiner == null) {
            return;
        }
        // The crash marker. Nothing has seated them yet — this runs on join —
        // so a marker here can only be left over from a run of the plugin that
        // did not get to take the potion off.
        if (!hidden.contains(joiner.getUniqueId())
                && joiner.getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE)) {
            joiner.removePotionEffect(PotionEffectType.INVISIBILITY);
            joiner.getPersistentDataContainer().remove(markerKey);
            armour(joiner, false);
            joiner.updateInventory();
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
        for (UUID id : Set.copyOf(potioned)) {
            show(id, false);
        }
        hidden.clear();
        potioned.clear();
    }

    /**
     * Blanks or restores the wearer's own armour, to their client only.
     *
     * <p>{@code sendEquipmentChange} is a packet to one connection; nothing is
     * taken off anybody and nothing is stored, so there is no armour to lose in
     * a crash and no marker to recover from — a reconnecting client is sent the
     * real equipment by the server as a matter of course.
     */
    private void armour(Player player, boolean blank) {
        ItemStack air = new ItemStack(Material.AIR);
        for (EquipmentSlot slot : ARMOUR) {
            ItemStack real = player.getInventory().getItem(slot);
            player.sendEquipmentChange(player, slot, blank || real == null ? air : real);
        }
    }

    /**
     * Says once that this server's scoreboard is why a hidden rider is still
     * a see-through shape.
     *
     * <p>{@code Team#canSeeFriendlyInvisibles} starts ON, and a client draws a
     * friendly invisible as a translucent copy rather than as nothing — so on a
     * server whose tab-list or name-colour plugin puts players on teams, which
     * is most of them, the rider sees a ghost of themselves in their own
     * cockpit however thoroughly this hides them. <strong>Nothing here can fix
     * it</strong>: the scoreboard belongs to the server, and turning the flag
     * off for somebody else's team would be this plugin making a PvP decision
     * on their behalf. So it is said, once, naming the switch — the alternative
     * is an owner who has done everything right, cannot see what is wrong, and
     * concludes the feature is broken.
     *
     * <p>The same warning exists in {@code EmoteDirector} for the same reason.
     * Two copies rather than one shared helper because the two subsystems share
     * no class and a scoreboard read is four lines; if a third appears, extract
     * it.
     */
    private void warnIfGhosted(Player player) {
        if (warnedGhost) {
            return;
        }
        Team team = player.getScoreboard().getEntryTeam(player.getName());
        if (team == null || !team.canSeeFriendlyInvisibles()) {
            return;
        }
        warnedGhost = true;
        plugin.getLogger().warning("A rider in a hidden vehicle seat can still see a see-through copy "
                + "of themselves, because they are on scoreboard team '" + team.getName() + "' and that "
                + "team has canSeeFriendlyInvisibles switched on - which makes a client draw a friendly "
                + "invisible as a ghost rather than as nothing. Whichever plugin creates that team wants "
                + "setCanSeeFriendlyInvisibles(false); this plugin must not change somebody else's team.");
    }
}
