package ai.resourcepack.engine.api;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Optional;

/**
 * Custom liquids: the ones a content folder's {@code liquids/} declares, and
 * the pools of them marked out in this server's worlds.
 *
 * <p>A pool is a box inside which water (or lava) counts as that liquid. This
 * answers which liquid a place or a player is in; the crossing itself is
 * {@link ai.resourcepack.engine.api.event.PlayerLiquidEvent}.
 */
public interface Liquids {

    /** Every liquid id, sorted. */
    Collection<ContentId> ids();

    /** What one is: its base, its effect, its damage. */
    Optional<LiquidInfo> info(ContentId id);

    /**
     * The liquid whose pool this place lies in, or empty. A question about the
     * pool, not the block: a bridge over the acid is inside the box too.
     */
    Optional<LiquidInfo> at(Location where);

    /**
     * The liquid this player is in right now, as the engine last saw it (it
     * looks once a second): in a pool and actually wet. The same answer
     * {@link ai.resourcepack.engine.api.event.PlayerLiquidEvent} reports the
     * changes of, for a plugin that was not listening when they went in.
     */
    Optional<ContentId> of(Player player);
}
