package ai.resourcepack.engine.api.event;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.EntityInfo;
import org.bukkit.Location;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * A custom mob is about to spawn: from an egg, a spawner, a command, a plugin's
 * {@code Mobs.spawn}, or by itself where its {@code spawn:} rules put one.
 *
 * <p>Cancelling means no mob. This is the event for "none in the safe zone"
 * and "no more than five of these alive": rules about your server, which the
 * engine cannot know. {@link #setLevel} changes the level it comes at.
 *
 * <p>Fires before the mob exists, so there is no entity here. The mob itself
 * arrives a moment later in Bukkit's own {@code CreatureSpawnEvent}, already
 * marked, where {@code Mobs.idOf} says which one it is.
 */
public final class MobSpawnEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final ContentId id;
    private final EntityInfo info;
    private final Location location;
    private final boolean natural;
    private int level;
    private boolean cancelled;

    public MobSpawnEvent(ContentId id, EntityInfo info, Location location, int level, boolean natural) {
        this.id = id;
        this.info = info;
        this.location = location;
        this.level = level;
        this.natural = natural;
    }

    /** Which custom mob. */
    public ContentId id() {
        return id;
    }

    /** What the pack says it is. */
    public EntityInfo info() {
        return info;
    }

    /** Where it will spawn. A copy. */
    public Location location() {
        return location.clone();
    }

    /**
     * Whether the engine is putting it there by itself, in place of a vanilla
     * mob that spawned naturally. False for an egg, a spawner, a command and a
     * plugin.
     */
    public boolean natural() {
        return natural;
    }

    /** The level it will spawn at, 1 and up. */
    public int level() {
        return level;
    }

    /** Changes the level it spawns at. Kept between 1 and 1000. */
    public void setLevel(int level) {
        this.level = Math.max(1, Math.min(1000, level));
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
