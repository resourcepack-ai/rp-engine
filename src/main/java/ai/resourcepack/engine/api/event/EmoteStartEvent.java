package ai.resourcepack.engine.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.List;

/**
 * Fired before an emote starts, after every check has passed and before
 * anybody is moved. Cancel it and nothing happens at all.
 *
 * <p>This is where a server says no on its own terms. The library refuses an
 * emote in combat, in the air and in spectator because those break the emote
 * itself - it deliberately has no opinion about arenas, regions, minigames or
 * whose turn it is, because it runs on servers we don't own and can't guess the
 * rules of.
 *
 * <p>Cancelling reaches the caller as a refusal with no reason attached, which
 * is honest: the reason belongs to whoever cancelled, and they are the one who
 * should say it.
 */
public final class EmoteStartEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player lead;
    private final List<Player> cast;
    private final String emote;
    private boolean cancelled;

    public EmoteStartEvent(Player lead, List<Player> cast, String emote) {
        this.lead = lead;
        this.cast = List.copyOf(cast);
        this.emote = emote;
    }

    /** Whoever asked for it. Their part is the emote's own animation. */
    public Player getLead() {
        return lead;
    }

    /**
     * Everybody else in it, in slot order. Empty for a solo emote.
     *
     * <p>These players are about to be teleported into place and turned to
     * face where the emote puts them, and put back afterwards.
     */
    public List<Player> getCast() {
        return cast;
    }

    /** Which emote, by name. */
    public String getEmote() {
        return emote;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
