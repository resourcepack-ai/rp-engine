package ai.resourcepack.engine.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * RP Engine has baked its emote rigs again: one per player skin it has kept,
 * so that a player riding, sitting or dancing wears their own skin.
 *
 * <p>Fired on every content load, just before {@link ContentLoadEvent}, and on
 * its own when only the rigs were rebuilt: after a new player's skin arrived,
 * or when a plugin called {@code reloadRigs()}. A rigs-only rebuild reloads no
 * content and fires no {@link ContentLoadEvent}.
 *
 * <p>Where packs stack (Minecraft 1.20.3+) and the engine serves its own
 * packs, the rigs are a small resource pack of their own stacked on top of the
 * server's bundle, so a rigs-only rebuild leaves the bundle byte-identical and
 * re-sends nothing but the rigs. Elsewhere they are inside the bundle.
 *
 * <p>Rebuilding is not sending: a rebuild nobody asked for is sent to players
 * as they join and to anybody next sent a pack anyway, never pushed onto a
 * client that already has a working pack. A {@code PlayerResourcePackStatusEvent}
 * for the rigs pack says when a client has loaded it.
 *
 * <p>Not cancellable: it reports something that has already happened. Main
 * thread.
 */
public final class RigPackBuildEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final int rigs;
    private final boolean separate;
    private final boolean alone;

    public RigPackBuildEvent(int rigs, boolean separate, boolean alone) {
        this.rigs = rigs;
        this.separate = separate;
        this.alone = alone;
    }

    /** How many rigs were baked, the shared default (Steve) included. */
    public int rigs() {
        return rigs;
    }

    /** Whether the rigs are a pack of their own on top of the bundle, rather than inside it. */
    public boolean separate() {
        return separate;
    }

    /** Whether only the rigs were rebuilt, with no content load around it. */
    public boolean alone() {
        return alone;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
