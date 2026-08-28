package ai.resourcepack.engine.core.sync;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Who receives a synced pack besides the person who claimed the code.
 *
 * <p><strong>Deliberately not a party system.</strong> A party outlives a
 * session, needs an owner, an ownership transfer, a disband, and a story for
 * members who are offline. None of that is wanted here: the group lives exactly
 * as long as the sync does, so dropping the code ends it and disconnecting ends
 * it. That is a whole class of state that never has to exist, and the reason
 * this is {@code /rp sync add} rather than {@code /rp party invite}.
 *
 * <p>Free of Bukkit, keyed by name, and every rule below is a test.
 */
public final class SyncGroup {

    /** What happened when somebody asked for something. */
    public enum Result {

        /** Done. */
        OK,

        /** The asker has not claimed a code, so there is nothing to share. */
        NOT_SYNCED,

        /** You cannot invite yourself. */
        SELF,

        /** They are already receiving this, or somebody else's. */
        ALREADY,

        /** No invite is waiting for them. */
        NO_INVITE,

        /** They are not on this sync. */
        NOT_A_MEMBER
    }

    /** Code -> who claimed it. */
    private final Map<String, String> owners = new LinkedHashMap<>();

    /** Code -> everybody else receiving it, in the order they accepted. */
    private final Map<String, Set<String>> members = new LinkedHashMap<>();

    /** Invitee -> the code they were invited to. One at a time, so accept takes no argument. */
    private final Map<String, String> invites = new LinkedHashMap<>();

    /** Records that {@code owner} claimed {@code code}. */
    public void claim(String code, String owner) {
        if (code == null || owner == null) {
            return;
        }
        owners.put(code, owner);
        members.computeIfAbsent(code, key -> new LinkedHashSet<>());
    }

    /** The code {@code player} claimed, if any. */
    public Optional<String> codeOf(String player) {
        for (Map.Entry<String, String> entry : owners.entrySet()) {
            if (matches(entry.getValue(), player)) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    /** The code {@code player} is receiving, whether they own it or joined it. */
    public Optional<String> receiving(String player) {
        Optional<String> owned = codeOf(player);
        if (owned.isPresent()) {
            return owned;
        }
        for (Map.Entry<String, Set<String>> entry : members.entrySet()) {
            for (String member : entry.getValue()) {
                if (matches(member, player)) {
                    return Optional.of(entry.getKey());
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Everybody a push for {@code code} should reach: the owner first, then
     * whoever accepted, in the order they did.
     */
    public List<String> recipients(String code) {
        List<String> all = new ArrayList<>();
        String owner = owners.get(code);
        if (owner == null) {
            return all;
        }
        all.add(owner);
        all.addAll(members.getOrDefault(code, Set.of()));
        return List.copyOf(all);
    }

    /**
     * Asks {@code invitee} to receive {@code owner}'s pushes.
     *
     * <p>An invite rather than a straight add, because a push changes somebody's
     * client mid-session and that should not happen because a stranger typed
     * their name.
     */
    public Result invite(String owner, String invitee) {
        Optional<String> code = codeOf(owner);
        if (code.isEmpty()) {
            return Result.NOT_SYNCED;
        }
        if (matches(owner, invitee)) {
            return Result.SELF;
        }
        if (receiving(invitee).isPresent()) {
            return Result.ALREADY;
        }
        invites.put(key(invitee), code.get());
        return Result.OK;
    }

    /** Whether {@code player} has an invite waiting. */
    public boolean invited(String player) {
        return player != null && invites.containsKey(key(player));
    }

    /** Who claimed {@code code}, if anybody still has. */
    public Optional<String> owner(String code) {
        return Optional.ofNullable(code == null ? null : owners.get(code));
    }

    /**
     * Who asked {@code player} to join, if anybody is waiting on an answer.
     *
     * <p>Here rather than worked out by the caller because an invite stores
     * the code and the answer has to be given BEFORE the invite is consumed:
     * accepting removes it, and there is then nobody left to tell.
     */
    public Optional<String> invitedBy(String player) {
        if (player == null) {
            return Optional.empty();
        }
        return owner(invites.get(key(player)));
    }

    /**
     * Accepts the waiting invite.
     *
     * @return the code they joined, or empty if there was no invite or the
     *         sync it named has since ended
     */
    public Optional<String> accept(String player) {
        String code = player == null ? null : invites.remove(key(player));
        if (code == null || !owners.containsKey(code)) {
            return Optional.empty();
        }
        members.computeIfAbsent(code, key -> new LinkedHashSet<>()).add(player);
        return Optional.of(code);
    }

    /** Declines the waiting invite. */
    public Result deny(String player) {
        return player != null && invites.remove(key(player)) != null ? Result.OK : Result.NO_INVITE;
    }

    /**
     * Takes {@code member} off {@code owner}'s sync.
     *
     * @return the code they were on, so the caller can take the pack back too
     */
    public Optional<String> remove(String owner, String member) {
        Optional<String> code = codeOf(owner);
        if (code.isEmpty()) {
            return Optional.empty();
        }
        Set<String> current = members.get(code.get());
        if (current == null || !current.removeIf(name -> matches(name, member))) {
            return Optional.empty();
        }
        return code;
    }

    /** Takes {@code player} off whoever's sync they were on. */
    public Optional<String> leave(String player) {
        for (Map.Entry<String, Set<String>> entry : members.entrySet()) {
            if (entry.getValue().removeIf(name -> matches(name, player))) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    /**
     * Ends {@code owner}'s sync entirely.
     *
     * @return everybody who was receiving it, so the caller can take the pack
     *         back from all of them at once
     */
    public List<String> stop(String owner) {
        Optional<String> code = codeOf(owner);
        if (code.isEmpty()) {
            return List.of();
        }
        List<String> was = recipients(code.get());
        owners.remove(code.get());
        members.remove(code.get());
        invites.entrySet().removeIf(entry -> entry.getValue().equals(code.get()));
        return was;
    }

    /**
     * Forgets a player who has gone.
     *
     * <p>If they owned a sync it ends, because the group lives exactly as long
     * as the sync and nobody is left holding it. If they had merely joined one,
     * only they leave.
     *
     * @return everybody who was receiving a sync that has now ended
     */
    public List<String> forget(String player) {
        invites.remove(key(player));
        Optional<String> owned = codeOf(player);
        if (owned.isPresent()) {
            return stop(player);
        }
        leave(player);
        return List.of();
    }

    /** Every code currently claimed. */
    public Set<String> codes() {
        return Set.copyOf(owners.keySet());
    }

    // Minecraft names are case-insensitive to type and case-preserving to
    // display, so a comparison that is not folded fails for the one person who
    // typed their friend's name in lowercase.
    private static boolean matches(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
