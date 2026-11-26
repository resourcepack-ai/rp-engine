package ai.resourcepack.engine.api;

import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Optional;

/**
 * Scoreboards: the sidebar on the right of the screen, one per player.
 *
 * <p>A pack declares them in {@code scoreboards/}. Each player sees at most
 * one. Which one is, in order: the one {@link #show} pinned for them;
 * otherwise the highest-{@code priority} scoreboard that shows itself in the
 * world they are in and whose permission they hold; otherwise none. A player
 * who has hidden theirs ({@link #setHidden}) sees none until it is shown again.
 *
 * <p>The lines are filled in per player: {@code {name}} placeholders, the
 * values a plugin publishes with {@link Overlays#set}, PlaceholderAPI's when
 * the server has it, icons and rank tags. They are sent to that player alone,
 * as packets, so the server's own scoreboard, its teams and other plugins'
 * are left as they were.
 *
 * <p>Main thread only, except {@link #ids} and {@link #info}.
 */
public interface Scoreboards {

    /** Every scoreboard, sorted. */
    Collection<ContentId> ids();

    /** What the pack said a scoreboard is, or empty if there is none. */
    Optional<ScoreboardInfo> info(ContentId id);

    /**
     * Shows one to a player, wherever they are and whatever its permission
     * says, until {@link #reset} or another {@code show}. Also un-hides theirs.
     *
     * @return false if there is no such scoreboard
     */
    boolean show(Player player, ContentId id);

    /** Ends what {@link #show} pinned, so the player is back to the scoreboard of where they are. */
    void reset(Player player);

    /**
     * Hides a player's scoreboard, or brings it back. Hidden stays hidden
     * across worlds and restarts until it is shown again.
     */
    void setHidden(Player player, boolean hidden);

    /** Whether a player has hidden theirs. */
    boolean hidden(Player player);

    /** The scoreboard a player is being shown, if any. */
    Optional<ContentId> showing(Player player);
}
