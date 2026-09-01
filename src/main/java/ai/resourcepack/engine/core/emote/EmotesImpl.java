package ai.resourcepack.engine.core.emote;

import ai.resourcepack.engine.api.EmoteInfo;
import ai.resourcepack.engine.api.EmoteResult;
import ai.resourcepack.engine.api.Emotes;
import ai.resourcepack.engine.api.event.EmoteEndEvent;
import ai.resourcepack.engine.core.Host;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The public {@link Emotes} surface over {@link EmoteDirector}.
 *
 * <p>Thin, like {@code ModelsImpl}: the thread check, and the translation from
 * the store's shapes to the API's. Everything about poses, troupes and
 * invisibility stays in the director.
 *
 * <p>Internal. Not part of the supported API.
 */
public final class EmotesImpl implements Emotes {

    private final EmoteDirector director;
    private final EmoteStore store;

    public EmotesImpl(EmoteDirector director, EmoteStore store) {
        this.director = director;
        this.store = store;
    }

    @Override
    public List<String> ids() {
        return director.ids();
    }

    @Override
    public Optional<EmoteInfo> info(String emoteId) {
        if (emoteId == null) return Optional.empty();
        // Groups first, on the same terms the director resolves a typed name:
        // one id space, so a name is one or the other and never both.
        EmoteStore.Group group = store.findGroup(emoteId);
        if (group != null) {
            // No length, because a group has none — see EmoteInfo.length(). It
            // always loops (it is worn until taken off) and its triggers are
            // the states it actually wears something in, which is what makes a
            // group `isWorn()` like the stance it behaves as.
            return Optional.of(new EmoteInfo(
                group.name, 0, true, List.of(),
                EmoteStore.statesOf(java.util.Set.copyOf(store.partsOf(group).keySet())), true));
        }
        EmoteStore.Emote emote = store.find(emoteId);
        if (emote == null) return Optional.empty();
        List<String> slots = new ArrayList<>();
        if (emote.performers != null) {
            for (EmoteStore.Performer performer : emote.performers) {
                slots.add(performer.name == null || performer.name.isEmpty() ? "player" : performer.name);
            }
        }
        return Optional.of(new EmoteInfo(
            emote.name, emote.length, emote.loop, slots,
            // The states it PLAYS in rather than the names it used: a stance
            // built before crouching was split names a state nothing resolves
            // to, and a caller asking whether it covers a crouch-walk is asking
            // about the state, not about the vocabulary of its manifest.
            EmoteStore.statesOf(EmoteStore.triggersOf(emote))));
    }

    @Override
    public boolean isEmoting(UUID playerId) {
        return playerId != null && director.isEmoting(playerId);
    }

    @Override
    public boolean canPerform(Player player) {
        // The rig, not the ground or the combat timer: this answers "could this
        // player ever emote in the pack they are wearing", which is the
        // question worth asking before offering the feature at all.
        return player != null && store.rigFor(player.getUniqueId()) != null;
    }

    @Override
    public EmoteResult play(Player lead, String emoteId) {
        return play(lead, emoteId, List.of());
    }

    @Override
    public EmoteResult play(Player lead, String emoteId, List<Player> cast) {
        Host.requireMainThread();
        if (lead == null || !lead.isOnline()) return EmoteResult.refused(EmoteResult.Reason.LEAD_OFFLINE);
        if (emoteId == null) return EmoteResult.refused(EmoteResult.Reason.UNKNOWN_EMOTE, null, director.ids());
        return director.play(lead, emoteId, cast);
    }

    @Override
    public EmoteResult perform(Player lead, List<String> words) {
        Host.requireMainThread();
        if (lead == null || !lead.isOnline()) return EmoteResult.refused(EmoteResult.Reason.LEAD_OFFLINE);
        if (words == null || words.isEmpty()) {
            return EmoteResult.refused(EmoteResult.Reason.UNKNOWN_EMOTE, null, director.ids());
        }
        return director.perform(lead, words);
    }

    @Override
    public List<String> castCandidates(CommandSender sender, String[] words) {
        Host.requireMainThread();
        if (sender == null || words == null || words.length == 0) return List.of();
        return director.castCandidates(sender, words);
    }

    @Override
    public boolean stop(Player player) {
        Host.requireMainThread();
        if (player == null) return false;
        boolean emoting = director.isEmoting(player.getUniqueId());
        // Handed over even when they are not emoting, and the return value
        // still says they weren't. The director's no-session path is a
        // restore: it puts the body back for anybody carrying a leftover
        // marker or a leftover invisibility, which is the state a crash — or a
        // jar older than the fix in `restore` — leaves somebody in.
        //
        // That makes this the rescue. Somebody stuck invisible has no session
        // to end, so the old guard refused before reaching the one call that
        // would have helped them, and the only way out was an operator with
        // `/effect`. The answer they will try first is `/emote stop`, and it
        // now works.
        director.stop(player, !emoting, EmoteEndEvent.Cause.STOPPED);
        return emoting;
    }
}
