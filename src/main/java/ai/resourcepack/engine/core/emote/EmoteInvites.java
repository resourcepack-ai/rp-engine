package ai.resourcepack.engine.core.emote;

import ai.resourcepack.engine.api.EmoteInfo;
import ai.resourcepack.engine.api.EmoteResult;
import ai.resourcepack.engine.api.Emotes;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Consent for an emote that names other people.
 *
 * <p>An emote with a cast teleports the players it names, hides them and holds
 * them for its length. That used to happen the moment somebody typed the
 * command — {@code EmoteMessages.pulledIn} told them about it <em>after</em>
 * they had been moved. This asks first, and the emote does not start until
 * every named player has said yes.
 *
 * <p><b>Only the multiplayer form goes through here.</b> A solo emote still
 * runs straight off {@code Emotes.perform} exactly as it always did: there is
 * nobody to ask, and putting a prompt in front of the common case would make
 * the feature worse to use.
 *
 * <p>Nothing here restricts anything the library would have allowed — every
 * invitation that is fully accepted is handed to {@code Emotes.play}, which
 * runs the same checks it always ran (ground, combat, room, rigs) at the moment
 * it matters rather than at the moment the question was asked. So a player who
 * accepts and then walks into water is refused by the library, in the library's
 * own words, and not by anything invented here.
 *
 * <p><b>{@code resourcepackai.emote.force} skips the asking entirely</b> and
 * starts the emote where the invitation would have gone out. It is the only
 * way past this class, it is default-op, and it changes nothing else: the cast
 * is resolved and checked first exactly as it is for an invitation, and the
 * library still has the last word on whether the thing can run. Staff setting
 * up a shot are not the case consent was written for.
 *
 * <p>Main thread only, like the command that drives it.
 */
public final class EmoteInvites {

    /**
     * How long an invitation stands.
     *
     * <p>Long enough to notice a line of chat and click it, short enough that a
     * lead who has been ignored finds out while they still remember asking.
     */
    public static final int TIMEOUT_SECONDS = 30;

    private final Plugin plugin;
    private final Emotes emotes;

    /** token -> the question it answers. Small: at most one per lead. */
    private final Map<String, Invite> pending = new HashMap<>();

    public EmoteInvites(Plugin plugin, Emotes emotes) {
        this.plugin = plugin;
        this.emotes = emotes;
    }

    /** An emote name and the players named after it, before anybody is asked. */
    public static final class Request {
        private final String emoteId;
        private final List<String> castNames;

        Request(String emoteId, List<String> castNames) {
            this.emoteId = emoteId;
            this.castNames = castNames;
        }

        public String emoteId() {
            return emoteId;
        }

        /** Empty for a solo emote, which is what tells the command to skip all this. */
        public List<String> castNames() {
            return castNames;
        }
    }

    private static final class Invite {
        String token;
        UUID leadId;
        String leadName;
        String emoteId;
        /** Ordered: the library expects the cast in the emote's own slot order. */
        List<UUID> castIds;
        List<String> castNames;
        Set<UUID> accepted = new LinkedHashSet<>();
        int taskId = -1;
    }

    /**
     * Splits {@code <name…> [player…]} into an emote and the people named after
     * it, or null when no leading run of words names an emote at all.
     *
     * <p>This mirrors the library's own rule — the longest leading run of words
     * that names an emote wins — because the command has to know <em>whom to
     * ask</em> before it can ask them, and the library's resolution only comes
     * back attached to an emote that has already started. The duplication is
     * safe in the one direction that matters: every path out of here ends in
     * {@code Emotes.play} or {@code Emotes.perform}, which resolve and validate
     * again, so a disagreement costs a refusal rather than the wrong emote.
     * {@code EmoteStore.find} matches case-insensitively on the whole name,
     * which is what {@code equalsIgnoreCase} does here.
     */
    public Request resolve(String[] args) {
        List<String> words = Arrays.asList(args);
        for (int keep = words.size(); keep >= 1; keep--) {
            String candidate = String.join(" ", words.subList(0, keep));
            for (String id : emotes.ids()) {
                if (id.equalsIgnoreCase(candidate)) {
                    return new Request(id, new ArrayList<>(words.subList(keep, words.size())));
                }
            }
        }
        return null;
    }

    /**
     * Asks everyone this emote names, or tells the lead why it cannot.
     *
     * <p>Every refusal is built as an {@link EmoteResult} and worded by
     * {@link EmoteWording}, so the sentences a player sees are the same ones
     * they saw before there was a prompt — this checks them early only so that
     * a mistyped command does not put a question in five people's chat.
     */
    public void open(Player lead, Request request) {
        if (!lead.hasPermission(EmoteWording.MULTI_PERMISSION)) {
            refuse(lead, EmoteResult.refused(EmoteResult.Reason.CAST_NOT_PERMITTED));
            return;
        }
        if (emotes.isEmoting(lead.getUniqueId())) {
            refuse(lead, EmoteResult.refused(EmoteResult.Reason.ALREADY_EMOTING));
            return;
        }
        if (ledBy(lead.getUniqueId()) != null) {
            lead.sendMessage(EmoteWording.inviteAlreadyOut());
            return;
        }

        Optional<EmoteInfo> info = emotes.info(request.emoteId());
        if (info.isEmpty()) {
            // resolve() found it in ids() a moment ago, so this is a pack that
            // changed underneath the command rather than a typo.
            refuse(lead, EmoteResult.refused(
                EmoteResult.Reason.UNKNOWN_EMOTE, request.emoteId(), emotes.ids()));
            return;
        }
        List<String> slots = info.get().castSlots();
        if (slots.isEmpty()) {
            refuse(lead, EmoteResult.refused(EmoteResult.Reason.SOLO_EMOTE, request.emoteId()));
            return;
        }
        if (slots.size() != request.castNames().size()) {
            refuse(lead, EmoteResult.refused(
                EmoteResult.Reason.CAST_WRONG_SIZE, request.emoteId(), slots));
            return;
        }

        List<Player> cast = new ArrayList<>(request.castNames().size());
        Set<UUID> seen = new HashSet<>();
        for (String name : request.castNames()) {
            Player other = Bukkit.getPlayerExact(name);
            if (other == null || !other.isOnline()) {
                refuse(lead, EmoteResult.refused(EmoteResult.Reason.CAST_NOT_ONLINE, name));
                return;
            }
            if (other.getUniqueId().equals(lead.getUniqueId())) {
                refuse(lead, EmoteResult.refused(EmoteResult.Reason.CAST_IS_LEAD, name));
                return;
            }
            if (!seen.add(other.getUniqueId())) {
                refuse(lead, EmoteResult.refused(EmoteResult.Reason.CAST_DUPLICATED, name));
                return;
            }
            if (emotes.isEmoting(other.getUniqueId())) {
                refuse(lead, EmoteResult.refused(EmoteResult.Reason.CAST_BUSY, name));
                return;
            }
            if (involving(other.getUniqueId()) != null) {
                lead.sendMessage(EmoteWording.castAlreadyInvited(other.getName()));
                return;
            }
            cast.add(other);
        }

        // Nobody to ask. Same validation as everyone else got above — the
        // bypass is the QUESTION, not the rules — and the same hand-off to the
        // library, which still refuses a cast standing in water or already
        // emoting. The people named are told they were pulled in by
        // EmoteMessages, exactly as they are when they accept.
        if (lead.hasPermission(EmoteWording.FORCE_PERMISSION)) {
            EmoteResult result = emotes.play(lead, info.get().name(), cast);
            lead.sendMessage(EmoteWording.of(result));
            return;
        }

        Invite invite = new Invite();
        invite.token = newToken();
        invite.leadId = lead.getUniqueId();
        invite.leadName = lead.getName();
        invite.emoteId = info.get().name();
        invite.castIds = new ArrayList<>();
        invite.castNames = new ArrayList<>();
        for (Player member : cast) {
            invite.castIds.add(member.getUniqueId());
            invite.castNames.add(member.getName());
        }
        pending.put(invite.token, invite);

        for (Player member : cast) {
            member.spigot().sendMessage(EmoteWording.invitation(
                invite.leadName, invite.emoteId, invite.token, TIMEOUT_SECONDS));
        }
        lead.sendMessage(EmoteWording.inviteSent(invite.castNames, TIMEOUT_SECONDS));

        invite.taskId = Bukkit.getScheduler().runTaskLater(plugin, () -> expire(invite),
            TIMEOUT_SECONDS * 20L).getTaskId();
    }

    /**
     * Answers an invitation. {@code accept} and {@code deny} are the only two
     * verbs; anything else is a command somebody typed by hand.
     *
     * @return whether the verb was understood, so the caller can print usage.
     */
    public boolean reply(Player who, String verb, String token) {
        boolean accept = verb.equalsIgnoreCase("accept");
        if (!accept && !verb.equalsIgnoreCase("deny")) return false;

        Invite invite = pending.get(token);
        // Answered twice, withdrawn, or timed out between the click and the
        // command landing. Said rather than ignored: two buttons that do
        // nothing read as a broken plugin.
        if (invite == null || !invite.castIds.contains(who.getUniqueId())) {
            who.sendMessage(EmoteWording.inviteGone());
            return true;
        }

        if (!accept) {
            close(invite);
            who.sendMessage(EmoteWording.replyDenied(invite.emoteId));
            Player lead = Bukkit.getPlayer(invite.leadId);
            if (lead != null) lead.sendMessage(EmoteWording.inviteDenied(who.getName()));
            tellOthersItIsOff(invite, who.getUniqueId());
            return true;
        }

        if (!invite.accepted.add(who.getUniqueId())) {
            // Clicked twice. Not an error, and not worth a second sentence.
            return true;
        }
        who.sendMessage(EmoteWording.replyAccepted(invite.emoteId));
        int waitingOn = invite.castIds.size() - invite.accepted.size();
        Player lead = Bukkit.getPlayer(invite.leadId);
        if (lead != null) {
            lead.sendMessage(EmoteWording.inviteAccepted(who.getName(), waitingOn));
        }
        if (waitingOn <= 0) start(invite);
        return true;
    }

    /**
     * Drops whatever this player was holding, as lead or as cast.
     *
     * <p>A lead who logs out has nothing to start; a cast member who logs out
     * can no longer be moved into place, and the library would refuse the whole
     * thing anyway once it looked.
     */
    public void forget(UUID playerId) {
        Invite led = ledBy(playerId);
        if (led != null) {
            close(led);
            tellOthersItIsOff(led, playerId);
        }
        Invite in = involving(playerId);
        if (in != null) {
            close(in);
            Player lead = Bukkit.getPlayer(in.leadId);
            if (lead != null) {
                lead.sendMessage(EmoteWording.of(EmoteResult.refused(
                    EmoteResult.Reason.CAST_NOT_ONLINE, nameOf(in, playerId))));
            }
            tellOthersItIsOff(in, playerId);
        }
    }

    /** Everything still standing goes with the plugin. */
    public void clear() {
        for (Invite invite : new ArrayList<>(pending.values())) {
            close(invite);
        }
    }

    private void start(Invite invite) {
        close(invite);
        Player lead = Bukkit.getPlayer(invite.leadId);
        if (lead == null || !lead.isOnline()) {
            tellOthersItIsOff(invite, null);
            return;
        }
        List<Player> cast = new ArrayList<>(invite.castIds.size());
        for (UUID id : invite.castIds) {
            Player member = Bukkit.getPlayer(id);
            if (member == null || !member.isOnline()) {
                refuse(lead, EmoteResult.refused(EmoteResult.Reason.CAST_NOT_ONLINE, nameOf(invite, id)));
                tellOthersItIsOff(invite, id);
                return;
            }
            cast.add(member);
        }
        // The library validates everything that can have changed while the
        // question stood, and words its own refusal through EmoteWording.
        EmoteResult result = emotes.play(lead, invite.emoteId, cast);
        lead.sendMessage(EmoteWording.of(result));
        if (!result.started()) tellOthersItIsOff(invite, null);
    }

    private void expire(Invite invite) {
        // Already started or called off; the scheduled task outlived it.
        if (pending.get(invite.token) != invite) return;
        invite.taskId = -1;
        close(invite);
        Player lead = Bukkit.getPlayer(invite.leadId);
        if (lead != null) {
            List<String> silent = new ArrayList<>();
            for (int i = 0; i < invite.castIds.size(); i++) {
                if (!invite.accepted.contains(invite.castIds.get(i))) silent.add(invite.castNames.get(i));
            }
            lead.sendMessage(EmoteWording.inviteExpired(silent));
        }
        tellOthersItIsOff(invite, null);
    }

    /** Tells everyone still holding the question that it no longer stands. */
    private void tellOthersItIsOff(Invite invite, UUID except) {
        for (UUID id : invite.castIds) {
            if (id.equals(except)) continue;
            Player member = Bukkit.getPlayer(id);
            if (member != null) {
                member.sendMessage(EmoteWording.inviteOff(invite.leadName, invite.emoteId));
            }
        }
    }

    private void refuse(Player lead, EmoteResult result) {
        lead.sendMessage(EmoteWording.of(result));
    }

    private void close(Invite invite) {
        pending.remove(invite.token);
        if (invite.taskId != -1) {
            Bukkit.getScheduler().cancelTask(invite.taskId);
            invite.taskId = -1;
        }
    }

    private String nameOf(Invite invite, UUID id) {
        int at = invite.castIds.indexOf(id);
        return at < 0 ? "" : invite.castNames.get(at);
    }

    private Invite ledBy(UUID playerId) {
        for (Invite invite : pending.values()) {
            if (invite.leadId.equals(playerId)) return invite;
        }
        return null;
    }

    private Invite involving(UUID playerId) {
        for (Invite invite : pending.values()) {
            if (invite.castIds.contains(playerId)) return invite;
        }
        return null;
    }

    /**
     * Short and random rather than a counter: it rides in a click event, and a
     * guessable one would let somebody accept on another player's behalf by
     * typing the command. Membership is checked too, so this is the second lock
     * rather than the only one.
     */
    private String newToken() {
        String token;
        do {
            token = Long.toString(ThreadLocalRandom.current().nextLong(0x1000000L, 0x10000000L), 16)
                .toLowerCase(Locale.ROOT);
        } while (pending.containsKey(token));
        return token;
    }
}
