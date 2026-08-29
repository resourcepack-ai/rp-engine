package ai.resourcepack.engine.core.entity;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.EntityInfo;
import ai.resourcepack.engine.api.Items;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Custom entities: a real mob wearing a model.
 *
 * <p>The mob is genuinely a mob. It walks with its own AI, takes damage, drops
 * its loot, is found by {@code @e} selectors and is seen by every other plugin
 * on the server. All this adds is what it looks like — an {@link ItemDisplay}
 * riding it, with the vanilla body made invisible.
 *
 * <p><strong>Riding, rather than a task that moves the display every tick.</strong>
 * A passenger is moved by the server, so the model never lags behind the body,
 * never drifts when the chunk is busy, and costs nothing per tick.
 *
 * <p>The failure this feature usually has is a field of orphaned displays where
 * mobs used to be. Two things prevent it: the display is removed when its mount
 * dies, and the mob never despawns — a removed mount <em>ejects</em> its
 * passengers rather than taking them with it, so a mob that quietly vanished
 * because somebody walked away would leave the model standing there.
 *
 * <p>What it costs: a passenger cannot be rotated independently of what it
 * rides, so the model faces the way the mob faces. For a character that is
 * right anyway.
 */
public final class CustomEntities implements Listener {

    private final Plugin plugin;
    private final Items items;

    /** Marks a mob as ours, and says which entity it is. */
    private final NamespacedKey idKey;

    private volatile Map<ContentId, EntityInfo> entities = Map.of();

    public CustomEntities(Plugin plugin, Items items) {
        this.plugin = plugin;
        this.items = items;
        this.idKey = new NamespacedKey(plugin, "entity");
    }

    /** Replaces the catalogue, as a reload does. */
    public void replace(Map<ContentId, EntityInfo> loaded) {
        this.entities = loaded == null ? Map.of() : Map.copyOf(loaded);
    }

    /** Every custom entity id, sorted. */
    public Collection<ContentId> ids() {
        List<ContentId> sorted = new ArrayList<>(entities.keySet());
        sorted.sort(ContentId::compareTo);
        return List.copyOf(sorted);
    }

    /** What the pack said an entity is. */
    public Optional<EntityInfo> info(ContentId id) {
        return id == null ? Optional.empty() : Optional.ofNullable(entities.get(id));
    }

    /** Which custom entity this is, or empty for an ordinary mob. */
    public Optional<ContentId> idOf(Entity entity) {
        if (entity == null) {
            return Optional.empty();
        }
        return ContentId.parse(entity.getPersistentDataContainer()
                .get(idKey, PersistentDataType.STRING));
    }

    /**
     * Spawns one. Main thread only.
     *
     * @return the mob, or empty if there is no such entity
     */
    public Optional<Entity> spawn(Location where, ContentId id) {
        Optional<EntityInfo> found = info(id);
        if (where == null || where.getWorld() == null || found.isEmpty()) {
            return Optional.empty();
        }
        EntityInfo info = found.get();

        EntityType type;
        try {
            type = EntityType.valueOf(info.type());
        } catch (IllegalArgumentException e) {
            // Validated at load, so this is a mob that existed when the pack
            // was read and does not now: a server downgrade.
            return Optional.empty();
        }

        Entity mob = where.getWorld().spawnEntity(where, type);
        mob.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, id.toString());
        for (String tag : info.tags()) {
            mob.addScoreboardTag(tag);
        }
        info.name().ifPresent(name -> {
            mob.setCustomName(ChatColor.translateAlternateColorCodes('&', name));
            mob.setCustomNameVisible(true);
        });
        mob.setSilent(info.silent());
        // Kept, both ways. A custom entity that despawned because a player
        // walked far enough away would take somebody's boss with it — and
        // worse, leave the display behind, because a removed mount ejects its
        // passengers rather than taking them.
        mob.setPersistent(true);
        if (mob instanceof Mob) {
            ((Mob) mob).setRemoveWhenFarAway(false);
        }

        if (mob instanceof LivingEntity && info.health() > 0) {
            LivingEntity living = (LivingEntity) mob;
            living.getAttribute(Attribute.MAX_HEALTH).setBaseValue(info.health());
            living.setHealth(info.health());
        }

        info.model().flatMap(items::create).ifPresent(item -> dress(mob, item, info.scale()));
        return Optional.of(mob);
    }

    /**
     * Puts the model on, and takes the mob's own body off.
     *
     * <p>Invisible rather than removed: the mob still has its hitbox, so it can
     * be hit, pushed and collided with exactly where it looks like it is.
     */
    private void dress(Entity mob, ItemStack item, float scale) {
        if (mob instanceof LivingEntity) {
            ((LivingEntity) mob).setInvisible(true);
        }
        if (mob instanceof Mob) {
            // Otherwise the vanilla armour and held item still render, floating
            // in the middle of whatever the model is.
            EntityEquipment equipment = ((Mob) mob).getEquipment();
            if (equipment != null) {
                equipment.clear();
            }
        }

        ItemDisplay display = mob.getWorld().spawn(mob.getLocation(), ItemDisplay.class, d -> {
            d.setItemStack(item);
            // NONE for the same reason a placed model uses it: every other
            // transform applies the model's own display block, and a generated
            // model's `fixed` is scale 0.5 with a translation.
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setBillboard(Display.Billboard.FIXED);
            d.setViewRange(1.5f);
            if (scale != 1f) {
                Transformation transformation = d.getTransformation();
                transformation.getScale().set(scale);
                d.setTransformation(transformation);
            }
            d.setPersistent(true);
        });

        // Riding, so the server moves it. A task that followed the mob every
        // tick would lag a frame behind on a busy chunk and leave the display
        // behind entirely when the mob died.
        mob.addPassenger(display);
    }

    /**
     * Takes the display with the mob.
     *
     * <p>A passenger is normally dismounted rather than killed when its mount
     * dies, which would leave the model standing over the corpse.
     */
    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        Optional<ContentId> id = idOf(event.getEntity());
        if (id.isEmpty()) {
            return;
        }
        // Bukkit's event above carries the drops and cannot say what the thing
        // was; ours says what it was and cannot change them. A plugin editing
        // the loot of a custom mob wants both, which is why this is a second
        // event rather than an argument for making one of them do the other's
        // job.
        event.getEntity().getServer().getPluginManager().callEvent(
                new ai.resourcepack.engine.api.event.EntityDeathEvent(event.getEntity(), id.get()));
        for (Entity passenger : event.getEntity().getPassengers()) {
            if (passenger instanceof ItemDisplay) {
                passenger.remove();
            }
        }
    }
}
