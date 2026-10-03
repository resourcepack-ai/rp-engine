package ai.resourcepack.engine.core.serve;

import ai.resourcepack.engine.api.BuiltPack;
import ai.resourcepack.engine.api.Feature;
import ai.resourcepack.engine.api.event.PackSendEvent;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Turns a {@link BundleSessions.Delta} into the calls Bukkit understands.
 *
 * <p>The only Bukkit-facing class in {@code core/serve}, and deliberately the
 * thinnest thing that could work. Every decision about what to send lives in
 * {@link BundleSessions}, which is testable without a server; this does the
 * sending and nothing else, so there is very little here that can be wrong in
 * a way a test would have caught.
 */
public final class PackDelivery {

    private final BundleSessions sessions;
    private final PackHost host;
    private final String prompt;
    private final boolean force;
    private final PackSending sending;
    private final Logger logger;

    /** Said once rather than on every join, which is where this would land. */
    private boolean warnedAboutStacking;

    public PackDelivery(BundleSessions sessions, PackHost host, String prompt, boolean force,
                        PackSending sending, Logger logger) {
        this.sessions = sessions;
        this.host = host;
        this.prompt = prompt == null || prompt.isEmpty() ? null : prompt;
        this.force = force;
        this.sending = sending;
        this.logger = logger;
    }

    /**
     * Sends {@code player} whatever it takes to be holding exactly
     * {@code desired}, in stack order, bottom first.
     *
     * @return whether anything was sent
     */
    public boolean apply(Player player, List<BuiltPack> desired) {
        if (player == null) {
            return false;
        }
        BundleSessions.Delta delta = sessions.plan(player.getUniqueId(), desired);
        if (delta.isEmpty()) {
            return false;
        }
        for (BundleSessions.Held held : delta.remove()) {
            sending.remove(player, held.uuid());
        }
        // On a server with one pack slot, only the last one sent survives, so
        // sending the whole stack would leave the player holding the top of it
        // and nothing else — with the earlier sends wasted downloads. Sending
        // only the top is the same outcome without the waste, and it is said
        // out loud because a bundle silently not arriving is the worst
        // version of this.
        List<BuiltPack> adding = delta.add();
        if (!sending.stacks() && adding.size() > 1) {
            BuiltPack top = adding.get(adding.size() - 1);
            if (!warnedAboutStacking) {
                warnedAboutStacking = true;
                logger.warning("This server's Minecraft holds one resource pack at a time, so "
                        + "of the " + adding.size() + " bundles a player was due, only the last "
                        + "(" + top.bundle() + ") is sent. Stacking bundles needs Minecraft "
                        + Feature.PACK_STACKING.since() + ".");
            }
            adding = List.of(top);
        }
        for (BuiltPack pack : adding) {
            // A pushed pack is already served, at an address Studio signed and
            // the client can reach. Ours are served by us. Asking the host for
            // a pushed pack's address is how a working push became "failed to
            // download" on every server whose host.public-address is still the
            // default — which is every server nobody has configured.
            Optional<String> url = pack.url().isEmpty()
                    ? host.url(pack.bundle())
                    : Optional.of(pack.url());
            if (url.isEmpty()) {
                // Built but not being served, which happens when hosting is
                // turned off and the owner publishes the zips themselves. Not
                // an error, and not something to spam a console over.
                continue;
            }
            sending.send(player, pack.uuid(), url.get(), hash(pack.sha1()), prompt, force);
            player.getServer().getPluginManager().callEvent(
                    new PackSendEvent(player, pack.bundle(), url.get()));
        }
        // Recorded once the sends have gone out, never at plan time. See
        // BundleSessions for why the two are separate.
        sessions.applied(player.getUniqueId(), desired);
        return true;
    }

    /** Drops everything {@code player} is holding from us. */
    public void clear(Player player) {
        if (player != null) {
            apply(player, List.of());
        }
    }

    /**
     * Hex to bytes. Bukkit wants the SHA-1 as raw bytes and everything else
     * here carries it as hex, because hex is what a log line, a URL and a
     * human all want.
     */
    static byte[] hash(String hex) {
        int length = hex.length() / 2;
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) ((Character.digit(hex.charAt(i * 2), 16) << 4)
                    | Character.digit(hex.charAt(i * 2 + 1), 16));
        }
        return bytes;
    }
}
