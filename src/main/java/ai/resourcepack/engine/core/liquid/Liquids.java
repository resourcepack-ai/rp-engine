package ai.resourcepack.engine.core.liquid;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.LiquidInfo;
import ai.resourcepack.engine.api.event.PlayerLiquidEvent;
import ai.resourcepack.engine.core.version.Vanilla;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies what a custom liquid does to whoever is standing in it.
 *
 * <p>Once a second, not every tick. Everything a liquid does — a potion effect,
 * a point of damage, putting a fire out — is a thing measured in seconds, and
 * twenty times a second would be nineteen wasted passes over every player on
 * the server for no visible difference.
 */
public final class Liquids {

    /** Once a second. See the class note. */
    private static final long PERIOD_TICKS = 20L;

    /** Slightly longer than the period, so the effect never flickers off between passes. */
    private static final int EFFECT_TICKS = 40;

    private final Plugin plugin;
    private final LiquidPools pools;

    private volatile Map<ContentId, LiquidInfo> liquids = Map.of();
    private BukkitTask task;

    /** Who was in what on the last pass, so a change can be told from a state. */
    private final Map<UUID, ContentId> inside = new ConcurrentHashMap<>();

    public Liquids(Plugin plugin, LiquidPools pools) {
        this.plugin = plugin;
        this.pools = pools;
    }

    /** Replaces the catalogue, as a reload does. */
    public void replace(Map<ContentId, LiquidInfo> loaded) {
        this.liquids = loaded == null ? Map.of() : Map.copyOf(loaded);
    }

    /** Every liquid id, sorted. */
    public Collection<ContentId> ids() {
        List<ContentId> sorted = new ArrayList<>(liquids.keySet());
        sorted.sort(ContentId::compareTo);
        return List.copyOf(sorted);
    }

    /** What the pack said a liquid is. */
    public Optional<LiquidInfo> info(ContentId id) {
        return id == null ? Optional.empty() : Optional.ofNullable(liquids.get(id));
    }

    /** The liquid somebody is standing in, or empty. */
    public Optional<LiquidInfo> at(Location where) {
        if (where == null || where.getWorld() == null) {
            return Optional.empty();
        }
        return pools.at(where.getWorld().getName(), where.getX(), where.getY(), where.getZ())
                .flatMap(LiquidPools.Pool::liquid)
                .flatMap(this::info);
    }

    /** Starts the pass. */
    public void start() {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, PERIOD_TICKS, PERIOD_TICKS);
    }

    /** Stops it. */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        if (liquids.isEmpty() || pools.pools().isEmpty()) {
            // Nobody can be in a liquid that no longer exists, and somebody
            // who was is owed the event saying they are out of it.
            inside.keySet().forEach(id -> left(Bukkit.getPlayer(id)));
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            // In the fluid, not merely inside the box. The box says which
            // liquid a place counts as; being wet is still vanilla's answer,
            // so somebody standing on a bridge over the acid is not in it.
            // Spigot's API has isInWater but no isInLava, so lava is asked of
            // the block the player is standing in.
            if (!player.isInWater()
                    && player.getLocation().getBlock().getType() != Material.LAVA) {
                left(player);
                continue;
            }
            Optional<LiquidInfo> in = at(player.getLocation());
            if (in.isEmpty()) {
                left(player);
                continue;
            }
            apply(player, in.get());
            entered(player, in.get().id());
        }
    }

    /**
     * Fires the crossing, if this is one.
     *
     * <p>On the change rather than every pass: a listener that wants to know
     * somebody is in acid wants waking when they get in, and a server doing
     * anything real with that would otherwise have to keep this bookkeeping
     * itself, once per plugin.
     */
    private void entered(Player player, ContentId liquid) {
        ContentId was = inside.get(player.getUniqueId());
        if (liquid.equals(was)) {
            return;
        }
        if (was != null) {
            // Straight from one pool into another, which is two events and
            // not one: a listener holding "they are in acid" has to be told
            // that is over before it is told about the next one.
            fire(player, was, PlayerLiquidEvent.Action.LEFT);
        }
        inside.put(player.getUniqueId(), liquid);
        fire(player, liquid, PlayerLiquidEvent.Action.ENTERED);
    }

    /** The other half. Silent for somebody who was not in anything. */
    private void left(Player player) {
        if (player == null) {
            return;
        }
        ContentId was = inside.remove(player.getUniqueId());
        if (was != null) {
            fire(player, was, PlayerLiquidEvent.Action.LEFT);
        }
    }

    private static void fire(Player player, ContentId liquid, PlayerLiquidEvent.Action action) {
        Bukkit.getPluginManager().callEvent(new PlayerLiquidEvent(player, liquid, action));
    }

    /** Forgets somebody who left the server. */
    public void forget(UUID playerId) {
        inside.remove(playerId);
    }

    private void apply(Player player, LiquidInfo liquid) {
        liquid.effect()
                .flatMap(Liquids::effectOf)
                .ifPresent(type -> player.addPotionEffect(
                        new PotionEffect(type, EFFECT_TICKS, liquid.amplifier(), true, false, true)));

        if (liquid.damage() > 0) {
            player.damage(liquid.damage());
        }
        if (liquid.fireproof() && player.getFireTicks() > 0) {
            player.setFireTicks(0);
        }
    }

    private static Optional<PotionEffectType> effectOf(String name) {
        return Vanilla.effect(name);
    }
}
