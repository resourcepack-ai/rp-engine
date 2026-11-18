package ai.resourcepack.engine.api.event;

import ai.resourcepack.engine.api.BuiltPack;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * RP Engine has built its packs again and they are on disk: at startup, on a
 * reload, when the emote rigs are rebaked on their own, and when another
 * plugin's text shader changed and was merged in again.
 *
 * <p>Fired before players online are sent the new build, so a listener that
 * marks or unmarks somebody ({@code Packs.markExternal}) is honoured by that
 * send. For content (items, models, the registry), wait for
 * {@link ContentLoadEvent}, which comes after.
 *
 * <p>A plugin that loads after RP Engine misses the startup one; read
 * {@code engine.packs().all()} in its {@code onEnable} instead.
 *
 * <p>Not cancellable: it reports something that has already happened. Main
 * thread.
 */
public final class PackBuildEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final List<BuiltPack> packs;
    private final Set<String> changed;

    public PackBuildEvent(List<BuiltPack> packs, Set<String> changed) {
        this.packs = packs == null ? List.of() : List.copyOf(packs);
        this.changed = changed == null ? Set.of() : Set.copyOf(changed);
    }

    /** Every pack the server sends now, as {@code Packs.all()} answers. */
    public List<BuiltPack> packs() {
        return packs;
    }

    /** One of {@link #packs}, by bundle name. */
    public Optional<BuiltPack> pack(String bundle) {
        for (BuiltPack pack : packs) {
            if (pack.bundle().equals(bundle)) {
                return Optional.of(pack);
            }
        }
        return Optional.empty();
    }

    /**
     * The bundles that are new, gone, or have a different SHA-1 since the last
     * time this fired. Empty when the build came out byte-identical, which a
     * reload that changed nothing does: then there is nothing to rebuild.
     */
    public Set<String> changed() {
        return changed;
    }

    /** Whether {@code bundle} is one of {@link #changed}. */
    public boolean changed(String bundle) {
        return changed.contains(bundle);
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
