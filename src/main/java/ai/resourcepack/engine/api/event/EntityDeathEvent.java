package ai.resourcepack.engine.api.event;

import ai.resourcepack.engine.api.ContentId;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

/**
 * A custom entity died.
 *
 * <p>Bukkit's own {@code EntityDeathEvent} fires too, and this one fires
 * from inside it, which is why it can hand over that event's own
 * {@link #drops()} list: change it here and the mob leaves what it says. The
 * reason this exists at all is that Bukkit's cannot tell you the thing that
 * died was {@code mypack:sentry} rather than an ordinary zombie.
 *
 * <p>Not cancellable. Death is not a decision anybody gets to take back here,
 * and a plugin that wants to prevent one has
 * {@code EntityDamageEvent} for it.
 */
public final class EntityDeathEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Entity entity;
    private final ContentId id;
    private final Player killer;
    private final List<ItemStack> drops;

    /** A death with no drops list to hand over: {@link #drops()} is an empty copy. */
    public EntityDeathEvent(Entity entity, ContentId id) {
        this(entity, id, null, new ArrayList<>());
    }

    public EntityDeathEvent(Entity entity, ContentId id, Player killer, List<ItemStack> drops) {
        this.entity = entity;
        this.id = id;
        this.killer = killer;
        this.drops = drops == null ? new ArrayList<>() : drops;
    }

    /** The mob that died. Still in the world, as Bukkit's own event has it. */
    public Entity entity() {
        return entity;
    }

    /** What it was. */
    public ContentId id() {
        return id;
    }

    /** The player who killed it, as Bukkit counts one, or null. */
    public Player killer() {
        return killer;
    }

    /**
     * What it is about to drop: Bukkit's own live list, so adding, removing or
     * clearing here changes the loot. Its experience is still Bukkit's event's
     * to set.
     */
    public List<ItemStack> drops() {
        return drops;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
