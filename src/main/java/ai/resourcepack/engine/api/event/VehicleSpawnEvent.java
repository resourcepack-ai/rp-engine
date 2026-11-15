package ai.resourcepack.engine.api.event;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.VehicleInfo;
import org.bukkit.Location;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * A vehicle is about to be put down: by {@code /rp vehicle spawn}, or by a
 * plugin through {@code Vehicles.spawn}.
 *
 * <p>Cancelling leaves the world as it was, and the caller is told there is no
 * vehicle. This is the event for "no vehicles in the lobby" and "ten per
 * world": rules about where one may stand, which the engine cannot know.
 *
 * <p>Fires before anything exists, so there is no {@code Vehicle} to hand
 * over: {@link #id}, {@link #info} and {@link #location} are what is about to
 * be made. A vehicle that was already standing and is loaded with its chunk
 * does not fire it.
 */
public final class VehicleSpawnEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final ContentId id;
    private final VehicleInfo info;
    private final Location location;
    private boolean cancelled;

    public VehicleSpawnEvent(ContentId id, VehicleInfo info, Location location) {
        this.id = id;
        this.info = info;
        this.location = location;
    }

    /** Which vehicle. */
    public ContentId id() {
        return id;
    }

    /** What the pack says it is. */
    public VehicleInfo info() {
        return info;
    }

    /** Where it will stand, and which way it will face. A copy. */
    public Location location() {
        return location.clone();
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
