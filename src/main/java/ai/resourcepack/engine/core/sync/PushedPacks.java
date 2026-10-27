package ai.resourcepack.engine.core.sync;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every push still in use, and which one each player is holding.
 *
 * <p><strong>Pushed content belongs to the players holding that push.</strong>
 * {@link StudioContent} keeps the last push and is replaced whole by the next,
 * which was the only record — so on a server where several people sync, the
 * last person to press Sync decided everybody's content. Their always-on shader
 * object went up on every screen that held any Studio pack at all, drawn with
 * glyphs from whatever pack each of those players happened to hold, and the
 * person who pushed before them found their own dialogs gone. This keeps one
 * snapshot per pack and answers per player.
 *
 * <p>A pack is kept while somebody holds it, while it is the latest push (what
 * the server persists, and what a vehicle parked from it is dressed from), and
 * while it is the pack this server publishes to everybody. Anything else is
 * dropped the moment it stops being one of those.
 *
 * <p>Free of Bukkit: players are their ids.
 */
public final class PushedPacks {

    /** Pack id -> its content, oldest push first. Guarded by {@code this}. */
    private final Map<String, StudioContent> packs = new LinkedHashMap<>();

    /** Player -> the pack id of the push they hold; empty for a push that named nothing. */
    private final Map<UUID, String> held = new ConcurrentHashMap<>();

    private volatile String latest = "";

    /**
     * The pack the server publishes to everybody, as Studio named it; null when
     * Studio does not say, or nothing is published.
     */
    private volatile String published;

    /**
     * Studio packs dropped into the content folder, read off their
     * {@link StudioContent#IN_PACK}. Their art is in the pack the engine
     * builds, which every player is sent, so their content is everybody's: it
     * is under whatever a player holds, never instead of it. Null when there
     * are none.
     */
    private StudioContent local;

    /** {@link #local} under each pack's content, made once per pack. Emptied whenever either changes. */
    private final Map<StudioContent, StudioContent> withLocal = new java.util.IdentityHashMap<>();

    /** A push arrived. Kept as the latest, and for whoever is then said to hold it. */
    public synchronized void arrived(StudioContent content) {
        if (content == null) {
            return;
        }
        String id = content.packId();
        // Re-inserted, so push order is recency order and a later push of the
        // same pack wins an id it shares with an older one in the union.
        packs.remove(id);
        packs.put(id, content);
        latest = id;
        withLocal.clear();
        prune();
    }

    /**
     * The published pack's content arrived, from distribution rather than a
     * push: kept as the published pack's, and not as the latest push, which
     * is what the server persists and is somebody's Sync.
     */
    public synchronized void publishedArrived(StudioContent content) {
        if (content == null || content.packId().isEmpty()) {
            return;
        }
        packs.remove(content.packId());
        packs.put(content.packId(), content);
        published = content.packId();
        withLocal.clear();
        prune();
    }

    /** The content folder's Studio packs, replacing the last set. Empty for none. */
    public synchronized void local(List<StudioContent> found) {
        local = found == null || found.isEmpty() ? null : StudioContent.union(found);
        withLocal.clear();
    }

    /**
     * {@code player} now holds the push of {@code packId}.
     *
     * @param packId the manifest's pack id, or empty for a push that carried
     *               no content manifest, whose holder can be shown nothing pushed
     * @return whether a pack stopped being in use because of it
     */
    public synchronized boolean hold(UUID player, String packId) {
        if (player == null) {
            return false;
        }
        held.put(player, packId == null ? "" : packId);
        return prune();
    }

    /**
     * {@code player} no longer holds a push — taken off them, or gone.
     *
     * @return whether a pack stopped being in use because of it
     */
    public synchronized boolean release(UUID player) {
        return player != null && held.remove(player) != null && prune();
    }

    /**
     * What this server publishes, as Studio named it, or null if it does not say.
     *
     * @return whether a pack stopped being in use because of it
     */
    public synchronized boolean published(String packId) {
        if (java.util.Objects.equals(published, packId)) {
            return false;
        }
        published = packId;
        return prune();
    }

    /**
     * The pushed content {@code player} may be shown.
     *
     * <p>Their own push first. Failing that, the pack this server publishes to
     * everybody if they were served it — a published pack is a Studio pack
     * too, and its content arrives the only way content arrives, by a push of
     * it. <b>When Studio does not say which pack it publishes, nothing.</b> The
     * engine used to assume the latest push, and that guess is exactly what
     * put one tester's always-on overlay on every screen of a server that
     * publishes some other pack.
     *
     * @param servedPublished whether this player is holding the published pack
     */
    public synchronized Optional<StudioContent> contentFor(UUID player, boolean servedPublished) {
        StudioContent theirs = null;
        String mine = player == null ? null : held.get(player);
        if (mine != null) {
            theirs = packs.get(mine);
        } else if (servedPublished && published != null) {
            theirs = packs.get(published);
        }
        if (local == null) {
            return Optional.ofNullable(theirs);
        }
        if (theirs == null) {
            return Optional.of(local);
        }
        // Theirs last, so a pack they hold wins an id it shares with the folder's.
        return Optional.of(withLocal.computeIfAbsent(theirs, pack -> StudioContent.union(List.of(local, pack))));
    }

    /** Every pack still in use, the content folder's first, then oldest push first, so a later one wins in a union. */
    public synchronized List<StudioContent> live() {
        List<StudioContent> all = new ArrayList<>();
        if (local != null) {
            all.add(local);
        }
        all.addAll(packs.values());
        return List.copyOf(all);
    }

    private boolean prune() {
        boolean removed = packs.keySet().removeIf(id -> !id.equals(latest) && !id.equals(published) && !held.containsValue(id));
        if (removed) {
            withLocal.clear();
        }
        return removed;
    }
}
