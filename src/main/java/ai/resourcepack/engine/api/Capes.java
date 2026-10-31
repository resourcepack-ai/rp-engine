package ai.resourcepack.engine.api;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;

/**
 * Capes: a texture in vanilla's cape layout, hung off a player's back and
 * swung by the game's own cape physics, shown to every player on the server.
 *
 * <p>A cape comes from a content folder's {@code capes/} ({@code namespace:path})
 * or from a Studio push ({@code studio:<id>}). What a player wears is kept on
 * the player, so it survives a relog and a restart.
 *
 * <p>Drawn as a display entity, so it needs Minecraft 1.21.4 or newer;
 * {@link #available()} says whether this server can.
 */
public interface Capes {

    /** Whether this server can draw a cape at all. */
    boolean available();

    /** Every cape this server holds, content folders' first. */
    List<ContentId> ids();

    /** A cape's display name, or empty if there is no such cape. */
    Optional<String> name(ContentId id);

    /** The cape {@code player} has on, or empty. */
    Optional<ContentId> worn(Player player);

    /**
     * Puts a cape on {@code player}, replacing any other. Checks no permission:
     * that is the caller's decision.
     *
     * @return false if there is no such cape
     */
    boolean wear(Player player, ContentId id);

    /**
     * Takes {@code player}'s cape off.
     *
     * @return whether they had one on
     */
    boolean remove(Player player);
}
