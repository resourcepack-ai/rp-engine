package ai.resourcepack.engine.core.distribution;

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
}
