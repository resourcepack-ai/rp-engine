package ai.resourcepack.engine.core.liquid;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.LiquidInfo;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

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
        java.util.List<ContentId> sorted = new java.util.ArrayList<>(liquids.keySet());
        sorted.sort(ContentId::compareTo);
        return java.util.List.copyOf(sorted);
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
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            // In the fluid, not merely inside the box. The box says which
            // liquid a place counts as; being wet is still vanilla's answer,
            // so somebody standing on a bridge over the acid is not in it.
            // Spigot's API has isInWater but no isInLava, so lava is asked of
            // the block the player is standing in.
            if (!player.isInWater()
                    && player.getLocation().getBlock().getType() != org.bukkit.Material.LAVA) {
                continue;
            }
            at(player.getLocation()).ifPresent(liquid -> apply(player, liquid));
        }
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
        return Optional.ofNullable(
                Registry.EFFECT.get(NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT))));
    }
}
