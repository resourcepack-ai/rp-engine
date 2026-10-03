package ai.resourcepack.engine.core.serve;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which of the packs we sent each player their client has said it loaded.
 *
 * <p>{@link BundleSessions} records what a player was SENT, which is the right
 * thing to plan a swap from and the wrong thing to answer this question with: a
 * download can still be running, or can have failed. The two differ in exactly
 * one situation that matters. A rebuild replaces the file a bundle's URL
 * points at, so somebody whose download of the old build had not finished is
 * left with a failure and needs the new build sent — while somebody who loaded
 * the old one has a working pack, and re-sending would reload their whole
 * client for nothing. Telling those apart is all this is for.
 *
 * <p>Free of Bukkit, like the rest of the bookkeeping here. The caller turns a
 * resource-pack status event into {@link #confirmed}.
 */
public final class PackLoads {

    /** Where one send of one pack has got to. */
    private enum Answer {
        /** Sent, and the client has not said how it ended. */
        PENDING,
        /** The client loaded it. */
        LOADED,
        /** The player said no. Settled: asking again is nagging. */
        DECLINED
    }

    /** Player -> pack id -> what the client last said about it. */
    private final Map<UUID, Map<UUID, Answer>> state = new ConcurrentHashMap<>();

    /** A pack went out; whatever the client said about an earlier send of it no longer counts. */
    public void sent(UUID player, UUID pack) {
        if (player == null || pack == null) {
            return;
        }
        state.computeIfAbsent(player, key -> new ConcurrentHashMap<>()).put(pack, Answer.PENDING);
    }

    /**
     * The client said it loaded a pack.
     *
     * @param pack the pack's id, or null on a server older than 1.20.3, whose
     *             status names no pack because a player holds only one — so it
     *             confirms everything that was sent
     */
    public void confirmed(UUID player, UUID pack) {
        answer(player, pack, Answer.LOADED);
    }

    /** The player declined a pack. Same null rule as {@link #confirmed}. */
    public void declined(UUID player, UUID pack) {
        answer(player, pack, Answer.DECLINED);
    }

    private void answer(UUID player, UUID pack, Answer answer) {
        Map<UUID, Answer> mine = player == null ? null : state.get(player);
        if (mine == null) {
            return;
        }
        if (pack == null) {
            mine.replaceAll((id, was) -> answer);
        } else if (mine.containsKey(pack)) {
            mine.put(pack, answer);
        }
    }

    /** Whether the client loaded the last send of this pack. */
    public boolean loaded(UUID player, UUID pack) {
        return answered(player, pack) == Answer.LOADED;
    }

    /**
     * Whether the last send of this pack has ended in a way that needs nothing
     * more from us: loaded, or declined. A send still running, or one that
     * failed, is not settled.
     */
    public boolean settled(UUID player, UUID pack) {
        Answer answer = answered(player, pack);
        return answer == Answer.LOADED || answer == Answer.DECLINED;
    }

    private Answer answered(UUID player, UUID pack) {
        Map<UUID, Answer> mine = player == null || pack == null ? null : state.get(player);
        return mine == null ? null : mine.get(pack);
    }

    /** Forgets a player who left; their client dropped everything with them. */
    public void forget(UUID player) {
        if (player != null) {
            state.remove(player);
        }
    }
}
