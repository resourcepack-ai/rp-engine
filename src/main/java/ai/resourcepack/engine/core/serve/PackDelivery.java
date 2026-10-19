package ai.resourcepack.engine.core.serve;

import ai.resourcepack.engine.api.BuiltPack;
import ai.resourcepack.engine.api.Feature;
import ai.resourcepack.engine.api.event.PackSendEvent;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
    /** Who has finished loading what. See {@link PackLoads}. */
    private final PackLoads loads = new PackLoads();

    /** Said once rather than on every join, which is where this would land. */
    private boolean warnedAboutStacking;
    /** Pack id -> the address our own host gave it, so a failure can say where the client was sent. */
    private final java.util.Map<java.util.UUID, String> hosted = new java.util.concurrent.ConcurrentHashMap<>();
    /** Said once a run: a wrong address fails every join, and one line says it as well as a thousand. */
    private boolean warnedAboutFailure;
    /** Player -> the stack they are waiting on an upload for. */
    private final java.util.Map<java.util.UUID, List<BuiltPack>> waiting = new java.util.concurrent.ConcurrentHashMap<>();
    /** Player -> pack id -> last sent pack and fallback state. */
    private final Map<UUID, Map<UUID, SentPack>> sentPacks = new ConcurrentHashMap<>();

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
        // Whatever was waiting for an upload on this player's behalf is
        // superseded by this call, which is the newer wish.
        waiting.remove(player.getUniqueId());
        BundleSessions.Delta delta = sessions.plan(player.getUniqueId(), desired);
        if (delta.isEmpty()) {
            return false;
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
        // A pack still on its way to the pack host has no link yet. Nothing is
        // sent until it has one, rather than the rest of the stack now and that
        // one later: a stack is sent bottom first, and a second send is a
        // second loading screen. Nothing is recorded either, so the retry
        // plans from scratch.
        for (BuiltPack pack : adding) {
            if (pack.url().isEmpty() && host.pending(pack.bundle())) {
                java.util.UUID id = player.getUniqueId();
                waiting.put(id, desired);
                host.whenSettled(pack.bundle(), () -> retry(player, id));
                return false;
            }
        }
        for (BundleSessions.Held held : delta.remove()) {
            sending.remove(player, held.uuid());
        }
        for (BuiltPack pack : adding) {
            // A pushed pack is already served, at an address Studio signed and
            // the client can reach. Ours are served by us. Asking the host for
            // a pushed pack's address is how a working push became "failed to
            // download" on every server whose host.public-address is still the
            // default — which is every server nobody has configured.
            Optional<String> url = pack.url().isEmpty()
                    ? host.url(pack.bundle(), player.getUniqueId())
                    : Optional.of(pack.url());
            if (url.isEmpty()) {
                // Built but not being served, which happens when hosting is
                // turned off and the owner publishes the zips themselves. Not
                // an error, and not something to spam a console over.
                continue;
            }
            if (pack.url().isEmpty()) {
                hosted.put(pack.uuid(), url.get());
                sentPacks.computeIfAbsent(player.getUniqueId(),
                        id -> new ConcurrentHashMap<>())
                        .put(pack.uuid(), new SentPack(pack, url.get(), false));
            } else {
                Map<UUID, SentPack> sent = sentPacks.get(player.getUniqueId());
                if (sent != null) {
                    sent.remove(pack.uuid());
                }
            }
            sending.send(player, pack.uuid(), url.get(), hash(pack.sha1()), prompt, force);
            loads.sent(player.getUniqueId(), pack.uuid());
            player.getServer().getPluginManager().callEvent(
                    new PackSendEvent(player, pack.bundle(), url.get()));
        }
        // Recorded once the sends have gone out, never at plan time. See
        // BundleSessions for why the two are separate.
        sessions.applied(player.getUniqueId(), desired);
        return true;
    }

    private record SentPack(BuiltPack pack, String url, boolean retried) {}

    /**
     * A client could not download a pack. Logged when the pack is one this
     * server hosts, since then the address is ours to get right; a pushed or
     * published pack is served by Studio and says so elsewhere.
     *
     * @param pack the pack, or null where packs do not stack (then it is
     *             whichever of ours went out)
     */
    public void failed(Player player, java.util.UUID pack, String status) {
        String url = pack == null
                ? hosted.values().stream().findFirst().orElse(null)
                : hosted.get(pack);
        if (url == null || warnedAboutFailure) {
            return;
        }
        warnedAboutFailure = true;
        String advice;
        if (host.onPackHost(url)) {
            advice = "That is ResourcePack AI's pack host, so this is most likely the player's own connection. "
                    + "If every player sees it, set host.mode to same-port or own-port in config.yml.";
        } else if (host.mode() == PackHost.Mode.OWN_PORT) {
            advice = "That address comes from host.public-address in config.yml and has to be one the PLAYER'S "
                    + "computer can reach: this server's public IP or domain, with the port in host.port open to the "
                    + "internet (on a hosting panel, that usually means allocating the port to this server). "
                    + "Or set host.mode to upload, which needs neither.";
        } else {
            advice = "That is this server's game port, which a player behind a proxy (Velocity, BungeeCord) cannot "
                    + "download from. Set host.mode to upload in config.yml.";
        }
        logger.warning(player.getName() + "'s game could not download " + url + " (" + status + "). "
                + advice + " Said once per restart.");
    }

    /** Retries a failed hosted pack using the local HTTP server. */
    public boolean retryFromLocal(Player player, UUID packId) {
        if (packId == null) {
            return false;
        }

        UUID playerId = player.getUniqueId();
        Map<UUID, SentPack> packs = sentPacks.get(playerId);
        if (packs == null) {
            return false;
        }

        SentPack sent = packs.get(packId);
        if (sent == null || sent.retried()
                || !host.onPackHost(sent.url())
                || !sentHere(player, packId)) {
            return false;
        }

        Optional<String> local = host.localUrl(sent.pack());
        if (local.isEmpty()) {
            return false;
        }

        BuiltPack pack = sent.pack();
        String url = local.get();

        packs.put(packId, new SentPack(pack, url, true));

        sending.send(player, packId, url,
                hash(pack.sha1()), prompt, force);
        loads.sent(playerId, packId);

        player.getServer().getPluginManager().callEvent(
                new PackSendEvent(player, pack.bundle(), url));

        logger.info("Retrying resource pack " + pack.bundle()
                + " for " + player.getName()
                + " using local hosting: " + url);

        return true;
    }

    /** Forgets a player's fallback state. */
    public void forget(UUID playerId) {
        sentPacks.remove(playerId);
    }

    /** Sends what {@code id} was waiting on, if they are still here and still want it. */
    private void retry(Player player, java.util.UUID id) {
        List<BuiltPack> desired = waiting.remove(id);
        Player current = player.getServer().getPlayer(id);
        // The same Player object, or this is a session that has since ended.
        if (desired != null && current == player && player.isOnline()) {
            apply(player, desired);
        }
    }

    /** Whether a client here can hold more than one pack, which a pack stacked on top of another needs. */
    public boolean stacks() {
        return sending.stacks();
    }

    /**
     * Which of the packs sent here each client has said it loaded. The plugin
     * feeds it the status events; {@link #resendUnfinished} reads it.
     */
    public PackLoads loads() {
        return loads;
    }

    /** Which pack a status is about, or null where packs do not stack. See {@link PackSending#statusId}. */
    public java.util.UUID statusId(org.bukkit.event.player.PlayerResourcePackStatusEvent event) {
        return sending.statusId(event);
    }

    /**
     * Sends {@code desired} only if the client has not finished loading the
     * build of {@code bundle} it was last sent.
     *
     * <p>For after a rebuild nobody asked to be re-sent: the file behind the
     * old build is gone, so a download still running against it fails and
     * that player needs the new one, while somebody who already loaded the old
     * build keeps it rather than having their whole client reloaded.
     *
     * @return whether anything was sent
     */
    public boolean resendUnfinished(Player player, String bundle, List<BuiltPack> desired) {
        if (player == null || bundle == null || bundle.isEmpty()
                || loads.settled(player.getUniqueId(), BuiltPack.uuidFor(bundle))) {
            return false;
        }
        return apply(player, desired);
    }

    /** Whether {@code pack} is one of the packs this server sent {@code player}. */
    public boolean sentHere(Player player, java.util.UUID pack) {
        if (player == null || pack == null) {
            return false;
        }
        for (BundleSessions.Held held : sessions.held(player.getUniqueId())) {
            if (held.uuid().equals(pack)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sends {@code desired} again, every pack of it, taken off first so each
     * lands on top of whatever the client holds now.
     *
     * <p>For after another plugin's pack reached the player. Two things can
     * have happened and the server cannot tell which. The other plugin may
     * have used {@code setResourcePack}, which in 1.20.3+ CLEARS every server
     * pack the player holds before adding its own (BetterHud does this): ours
     * are gone from the client while {@link BundleSessions} still believes in
     * them, and an ordinary {@link #apply} would send nothing. Or it may have
     * stacked its pack on top of ours, where it now wins every file both
     * replace. Taking ours off and sending them again is right in both cases:
     * a pack the client already has is cached, so it costs a reload, not a
     * download.
     *
     * @return whether anything was sent
     */
    public boolean restack(Player player, List<BuiltPack> desired) {
        if (player == null || desired == null || desired.isEmpty() || !sending.stacks()) {
            return false;
        }
        sessions.forget(player.getUniqueId());
        for (BuiltPack pack : desired) {
            sending.remove(player, pack.uuid());
        }
        return apply(player, desired);
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
