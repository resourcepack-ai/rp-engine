package ai.resourcepack.engine.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired when the displays a player's rig is drawn with change: when a rig is
 * put on, and again whenever any of them are spawned anew while it is worn.
 * Not cancellable.
 *
 * <p>For a plugin that mounts a rig on something of its own — see
 * {@link ai.resourcepack.engine.api.Emotes#passengerEntityIds}. A display
 * spawned again has a new entity id, so a mount made with the old list is
 * holding entities that no longer exist while the new ones stand wherever the
 * engine put them. This is the moment to send the mount again.
 *
 * <p>What spawns displays today: putting a rig on, which makes every one of
 * them new, and the worn emote changing to one that carries different models,
 * which replaces the carried models and keeps the bones and the hands. Listen
 * to this rather than to what you think causes it — anything that comes to
 * spawn a rig's displays in future fires it too.
 *
 * <p>Fired on the main thread once the change is complete, so the list is the
 * whole rig as it now stands. It fires for every rig — an emote somebody
 * typed, a movement set, one put on through
 * {@link ai.resourcepack.engine.api.Emotes#wear} — so check the player is one
 * of yours. More than one can fire for a single call: putting a rig on and
 * dressing it in an emote that carries a model is a spawn and then a respawn.
 * Treat each as "this is the list now". When the rig comes off,
 * {@link EmoteEndEvent} fires instead and the displays are gone.
 */
public final class EmoteRigSpawnEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final int[] passengerEntityIds;

    public EmoteRigSpawnEvent(Player player, int[] passengerEntityIds) {
        this.player = player;
        this.passengerEntityIds = passengerEntityIds == null ? new int[0] : passengerEntityIds.clone();
    }

    /** Whose rig it is. */
    public Player getPlayer() {
        return player;
    }

    /**
     * The rig's displays as they now stand — the same list
     * {@link ai.resourcepack.engine.api.Emotes#passengerEntityIds} would give
     * at this moment, and a new array on every call.
     */
    public int[] getPassengerEntityIds() {
        return passengerEntityIds.clone();
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
