package ai.resourcepack.engine.core.distribution;

import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * What the rest of the engine needs to know about Bedrock players.
 *
 * <p>A seam rather than a direct call into Geyser, for one reason: Geyser is a
 * plugin a server may or may not have, and the classes are simply not there
 * when it does not. Everything that asks about Bedrock asks through this, so a
 * server without Geyser answers "no" everywhere instead of throwing
 * {@link NoClassDefFoundError} somewhere unrelated.
 *
 * <p>A Bedrock player cannot be sent a Java resource pack. They get a
 * {@code .mcpack}, over a transfer-and-reconnect, which is a different enough
 * thing that the distribution path has to ask before it acts.
 */
public interface BedrockSupport {

    /** Answers no to everything, for a server with no Geyser on it. */
    BedrockSupport NONE = new BedrockSupport() {

        @Override
        public boolean available() {
            return false;
        }

        @Override
        public boolean isBedrock(UUID playerId) {
            return false;
        }

        @Override
        public boolean applyPack(Player player, String url) {
            return false;
        }
    };

    /** Whether Geyser is on this server at all. */
    boolean available();

    /** Whether this player joined through Geyser. */
    boolean isBedrock(UUID playerId);

    /**
     * Serves {@code url} to a Bedrock player.
     *
     * @return whether it was sent. False is ordinary — no Geyser, or the
     *         player is not Bedrock — rather than a failure worth logging.
     */
    boolean applyPack(Player player, String url);

    // --- Placed models ------------------------------------------------------
    //
    // Geyser does not translate display entities, so a placed rig is invisible
    // to a Bedrock player unless something draws it for them. These are the
    // moments the rig code tells the seam about; the default is to do nothing,
    // which is exactly right on a server with no Geyser. Default methods rather
    // than a cast at each call site, so the rig code never names a class that
    // only exists when Geyser does.

    /** A rig was just put into the world. */
    default void rigPlaced(Interaction hitbox, String modelId) {
    }

    /** A rig was taken out of the world; its hitbox is already gone or going. */
    default void rigRemoved(UUID hitboxId) {
    }

    /** A rig started an animation that Bedrock viewers should play too. */
    default void animationStarted(UUID hitboxId, String modelId, String animation) {
    }

    /** A player's Bedrock session ended (a quit, or the transfer an apply causes). */
    default void forget(UUID playerId) {
    }

    /**
     * The server's own content, rebuilt for Bedrock: served to every Bedrock
     * session as it joins, beside anything studio pushed them.
     */
    default void serverContent(ai.resourcepack.engine.core.bedrock.BedrockContent.Result content) {
    }

    /**
     * The Bedrock sound a vanilla Java sound event is played as, for a pack
     * that replaces one; null (the default) leaves replacements Java-only.
     */
    default java.util.function.UnaryOperator<String> soundAlias() {
        return null;
    }
}
