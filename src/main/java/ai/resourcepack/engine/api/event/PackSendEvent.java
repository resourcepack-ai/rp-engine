package ai.resourcepack.engine.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * A pack was sent to a player.
 *
 * <p>Fired as each one goes out — a player joining is usually sent one, a
 * reload re-sends everybody, and a Studio push adds another on top of what
 * they already hold.
 *
 * <p>Not cancellable, and that is a decision rather than an omission. What a
 * player should be holding is worked out as a whole and then recorded as sent;
 * a listener that could veto one pack out of the middle of that would leave
 * the engine believing they hold something they do not, and the next reload
 * would not fix it. A server that wants somebody to have no pack has bundles
 * for saying so.
 *
 * <p>Bukkit's own {@code PlayerResourcePackStatusEvent} is the other half:
 * this one says it was sent, that one says what the client did about it.
 */
public final class PackSendEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String bundle;
    private final String url;
    private final ai.resourcepack.engine.api.BuiltPack pack;

    /** A send with no built pack to name, so {@link #pack()} is null. */
    public PackSendEvent(Player player, String bundle, String url) {
        this(player, bundle, url, null);
    }

    public PackSendEvent(Player player, String bundle, String url, ai.resourcepack.engine.api.BuiltPack pack) {
        super(player);
        this.bundle = bundle;
        this.url = url;
        this.pack = pack;
    }

    /** Which bundle this is. */
    public String bundle() {
        return bundle;
    }

    /** Where the client was told to fetch it from. */
    public String url() {
        return url;
    }

    /**
     * The pack that went out: its {@code uuid()}, which is the id Bukkit's
     * {@code PlayerResourcePackStatusEvent} reports on 1.20.3 and newer, so the
     * two can be matched when a player holds several; its {@code sha1()}; and
     * its size.
     */
    public ai.resourcepack.engine.api.BuiltPack pack() {
        return pack;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
