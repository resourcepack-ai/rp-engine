package ai.resourcepack.engine.core.serve;

import ai.resourcepack.engine.api.Feature;
import ai.resourcepack.engine.core.version.Compatibility;

import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * How a resource pack reaches a player, which is the biggest single thing the
 * server's version decides.
 *
 * <p>From 1.20.3 a pack push carries an id, and a player can hold several at
 * once: the engine's whole bundle model rests on that. A player can be booted
 * into a lobby bundle, swapped to a dungeon bundle, or handed a base bundle
 * with an event bundle stacked on top, and because the client caches by hash,
 * swapping back to one it already holds costs nothing.
 *
 * <p>Before 1.20.3 there is one pack slot. Sending a second replaces the
 * first, there is no id to remove by, and nothing can be stacked. That is not
 * a reduced version of the feature so much as the absence of it, and it is why
 * {@link Feature#PACK_STACKING} is the loss worth weighing before running the
 * engine that far back.
 *
 * @see Feature#PACK_STACKING
 */
public interface PackSending {

    /** Whether this server can hold more than one pack per player. */
    boolean stacks();

    /**
     * Sends a pack, replacing whatever occupies its slot.
     *
     * @param id     the pack's identity, ignored where the server has only one
     *               slot to put it in
     * @param prompt the message beside the accept button, or null for the
     *               client's own wording
     */
    void send(Player player, UUID id, String url, byte[] sha1, String prompt, boolean force);

    /**
     * Takes one pack back off a player.
     *
     * <p>A no-op where packs do not stack: there is nothing to remove that
     * does not also remove everything, and the caller's next send replaces it
     * anyway.
     */
    void remove(Player player, UUID id);

    static PackSending forServer(Compatibility compatibility) {
        return compatibility.has(Feature.PACK_STACKING)
                ? new StackedPackSending()
                : new SinglePackSending();
    }
}
