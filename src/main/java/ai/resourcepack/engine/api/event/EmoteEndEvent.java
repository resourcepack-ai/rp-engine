package ai.resourcepack.engine.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired for each participant after their emote has been torn down and they
 * have been put back.
 *
 * <p>Not cancellable: by the time this fires the rig is gone and the player is
 * visible again, and an emote that could be refused an ending would be one that
 * never ends.
 *
 * <p>An emote ends for everybody or for nobody, so a duet fires this twice -
 * once per person, with the same reason. Half a handshake is one person shaking
 * air.
 */
public final class EmoteEndEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    /** Why it ended. */
    public enum Cause {
        /** It ran to its end. */
        FINISHED,
        /** Somebody asked for it to stop. */
        STOPPED,
        /** Somebody in the troupe moved. */
        MOVED,
        /** Somebody in the troupe was hit. Being hit really does interrupt an emote. */
        DAMAGED,
        /** Somebody in the troupe left the server. */
        QUIT,
        /** The server is shutting down, or the library is being closed. */
        SHUTDOWN
    }

    private final Player player;
    private final String emote;
    private final Cause cause;

    public EmoteEndEvent(Player player, String emote, Cause cause) {
        this.player = player;
        this.emote = emote;
        this.cause = cause;
    }

    /** Whose emote ended. */
    public Player getPlayer() {
        return player;
    }

    /** Which emote, by name. */
    public String getEmote() {
        return emote;
    }

    /** Why it ended. */
    public Cause getCause() {
        return cause;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
