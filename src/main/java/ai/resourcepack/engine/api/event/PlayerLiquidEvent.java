package ai.resourcepack.engine.api.event;

import ai.resourcepack.engine.api.ContentId;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * Somebody went into one of your liquids, or came out of one.
 *
 * <p>A liquid's own {@code effect} and {@code damage} are applied by the
 * engine every second. This is for everything else a server means by acid —
 * a title, a sound, a scoreboard, a quest step — and it fires on the CHANGE
 * rather than every second, so a listener is woken when somebody's state
 * actually differs.
 *
 * <p>Not cancellable, because there is nothing to refuse: the water is
 * already there and they are already standing in it. A server that does not
 * want them in it wants a region plugin, not this.
 *
 * <p>One event with an {@link Action} rather than two classes, on the same
 * reasoning as {@link ModelBindEvent}: a listener that lights somebody on fire
 * when they go in is the same listener that puts them out when they get out.
 */
public final class PlayerLiquidEvent extends PlayerEvent {

    /** Which way they crossed. */
    public enum Action {

        /** They are in it now, and were not a moment ago. */
        ENTERED,

        /** They are out of it now. */
        LEFT
    }

    private static final HandlerList HANDLERS = new HandlerList();

    private final ContentId liquid;
    private final Action action;

    public PlayerLiquidEvent(Player player, ContentId liquid, Action action) {
        super(player);
        this.liquid = liquid;
        this.action = action;
    }

    /** Which liquid. On a {@link Action#LEFT}, the one they were in. */
    public ContentId liquid() {
        return liquid;
    }

    /** Whether they went in or came out. */
    public Action action() {
        return action;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
