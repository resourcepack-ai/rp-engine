package ai.resourcepack.engine.core.serve;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is sent this server's own copy of its packs because their game could
 * not download them from the pack host ({@code host.fallback-on-download-failure}).
 *
 * <p>Free of Bukkit, like the rest of the bookkeeping here. {@link PackDelivery}
 * tells it what went out and everything the client said, and asks it one
 * question: is this failure the pack host failing one of our sends?
 *
 * <p>That is harder than it looks, because a status does not say which send
 * it is about. A pack's id is its bundle's, so every build of a bundle shares
 * one, and a failure still on its way from a build that has since been
 * replaced reads exactly like a failure of the new one. Below 1.20.3 a status
 * names no pack at all, and another plugin's pack (or the distribution pack)
 * fails in the same words as ours. What tells them apart is that a client
 * accepts a pack before it downloads it. So a failure counts only when exactly
 * one acceptance has come in since our last send of that pack: none, and it is
 * about an earlier send; two, and somebody else's pack went into the same slot.
 */
public final class DownloadFallback {

    /** Below 1.20.3 a status names no pack, so the one slot is the key. */
    private static final UUID SLOT = new UUID(0, 0);

    /** One send of one pack, and what the client has said about it. */
    private static final class Send {

        final boolean packHost;
        int accepted;
        boolean ended;

        Send(boolean packHost) {
            this.packHost = packHost;
        }
    }

    /** Player -> pack id (or {@link #SLOT}) -> our last send of it. */
    private final Map<UUID, Map<UUID, Send>> sends = new ConcurrentHashMap<>();
    /** Players served this server's own copies from now on. */
    private final Set<UUID> local = ConcurrentHashMap.newKeySet();

    /**
     * A pack went out.
     *
     * @param pack     its id, or null where packs do not stack
     * @param packHost whether the link was the pack host's, the only kind
     *                 there is a copy here to fall back to
     */
    public void sent(UUID player, UUID pack, boolean packHost) {
        if (player != null) {
            sends.computeIfAbsent(player, key -> new ConcurrentHashMap<>()).put(key(pack), new Send(packHost));
        }
    }

    /** A pack was taken off; nothing the client says about it matters any more. */
    public void removed(UUID player, UUID pack) {
        Map<UUID, Send> mine = player == null ? null : sends.get(player);
        if (mine != null) {
            mine.remove(key(pack));
        }
    }

    /**
     * What the client said about a pack, by the status's name.
     *
     * @param pack the pack, or null where packs do not stack
     * @return whether it is the pack host failing a download of ours, for a
     *         player not already being sent this server's copies
     */
    public boolean failedOnPackHost(UUID player, UUID pack, String status) {
        Map<UUID, Send> mine = player == null ? null : sends.get(player);
        Send send = mine == null ? null : mine.get(key(pack));
        if (send == null || send.ended) {
            return false;
        }
        if ("ACCEPTED".equals(status)) {
            send.accepted++;
            return false;
        }
        if (send.accepted == 0 || "DOWNLOADED".equals(status)) {
            // About an earlier send, or progress.
            return false;
        }
        send.ended = true;
        return "FAILED_DOWNLOAD".equals(status) && send.accepted == 1 && send.packHost && !local.contains(player);
    }

    /** From now on {@code player} is sent this server's copies. Once, and for the rest of their session. */
    public void serveLocally(UUID player) {
        if (player != null) {
            local.add(player);
        }
    }

    /** Whether {@code player} is sent this server's copies. */
    public boolean local(UUID player) {
        return player != null && local.contains(player);
    }

    /** Forgets a player who left. */
    public void forget(UUID player) {
        if (player != null) {
            sends.remove(player);
            local.remove(player);
        }
    }

    /**
     * Where in {@code stack} to send again from, after {@code failed} failed:
     * that pack and everything above it. A pack sent again lands on top of the
     * client's stack and there is no putting it back in the middle, so the ones
     * above it have to go again after it or it would override them. Below
     * 1.20.3 ({@code failed} null) the player holds only the top. -1 when the
     * pack is not in the stack.
     */
    static int resendFrom(List<UUID> stack, UUID failed) {
        return failed == null ? stack.size() - 1 : stack.indexOf(failed);
    }

    private static UUID key(UUID pack) {
        return pack == null ? SLOT : pack;
    }
}
