package ai.resourcepack.engine.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * RP Engine has finished loading content, and its packs are built.
 *
 * <p><strong>This is the one to listen to before touching anything else.</strong>
 * A plugin that asks for an item in its own {@code onEnable} may be asking
 * before the content folder has been read, and a reload replaces every
 * definition on the server — so anything holding resolved content rather than
 * {@link ai.resourcepack.engine.api.ContentId IDs} needs to hear about it.
 *
 * <p>Not cancellable: it reports something that has already happened. It is
 * fired on the main thread once the registry holds everything and the packs
 * are built, so every question the API can answer is answerable by the time a
 * listener runs.
 */
public final class ContentLoadEvent extends Event {

    /** Why content was loaded. */
    public enum Cause {

        /** The server started and the plugin enabled. */
        STARTUP,

        /** Somebody ran {@code /rp reload}, or a plugin asked for one. */
        RELOAD
    }

    private static final HandlerList HANDLERS = new HandlerList();

    private final Cause cause;
    private final int namespaces;
    private final int definitions;

    public ContentLoadEvent(Cause cause, int namespaces, int definitions) {
        this.cause = cause == null ? Cause.RELOAD : cause;
        this.namespaces = namespaces;
        this.definitions = definitions;
    }

    /** Whether this was the server starting or a reload. */
    public Cause cause() {
        return cause;
    }

    /** How many packs are loaded. */
    public int namespaces() {
        return namespaces;
    }

    /** How many pieces of content, across all of them. */
    public int definitions() {
        return definitions;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
