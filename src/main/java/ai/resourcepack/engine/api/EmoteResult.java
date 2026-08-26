package ai.resourcepack.engine.api;

import java.util.Collections;
import java.util.List;

/**
 * What happened when an emote was asked for.
 *
 * <p>A reason rather than a sentence, deliberately. Every refusal here has a
 * different fix - re-sync, pick another name, get out of combat, ask an admin
 * for a permission - so "that didn't work" would leave a player guessing at
 * which; and a library that returned the sentence would be choosing the
 * wording, the palette and the language for every server that uses it. The
 * caller writes the words.
 *
 * <pre>{@code
 * EmoteResult result = rpai.emotes().play(player, "bow");
 * if (!result.started()) {
 *     player.sendMessage(switch (result.reason()) {
 *         case NOT_ON_GROUND -> "You have to be standing on solid ground.";
 *         case UNKNOWN_EMOTE -> "No emote called " + result.subject();
 *         default -> "Can't do that right now.";
 *     });
 * }
 * }</pre>
 */
public final class EmoteResult {

    /**
     * Why an emote did or didn't start.
     *
     * <p>This enum only ever grows, so a switch over it wants a default branch:
     * a newer library will hand you constants that did not exist when you
     * compiled.
     */
    public enum Reason {

        /** It started. */
        STARTED,

        /** There was no lead to play it, or they went offline. */
        LEAD_OFFLINE,

        /** This player is already in an emote. */
        ALREADY_EMOTING,

        /** No emote by that name. {@link #options()} lists what there is. */
        UNKNOWN_EMOTE,

        /** This server holds no emotes at all - nothing has been synced yet. */
        NO_EMOTES,

        /** Started too soon after the last one. */
        COOLDOWN,

        /** The lead has no baked rig, and the pack has no fallback either. Report it. */
        NO_RIG_FOR_PLAYER,

        /** The pack arrived with no emote rigs at all. A re-sync is the fix. */
        NO_RIGS_IN_PACK,

        /** The pack's emote data is incomplete - no skeleton. A re-sync is the fix. */
        INCOMPLETE_EMOTE_DATA,

        /**
         * The lead was hurt recently.
         *
         * @deprecated Never returned since 2.1.0 — the combat gate is gone, and
         *     whether a fight should stop somebody emoting is a rule the server
         *     owns rather than one this library invents. Being hit still ENDS a
         *     running emote. The constant stays because this enum only grows;
         *     a {@code switch} that names it now has a branch nothing reaches,
         *     and cancelling {@link ai.resourcepack.api.event.EmoteStartEvent}
         *     on your own combat timer is how to bring the refusal back.
         */
        @Deprecated
        IN_COMBAT,

        /** The lead is in spectator. */
        IN_SPECTATOR,

        /** The lead is riding something. */
        RIDING,

        /** The lead is gliding. */
        GLIDING,

        /** The lead is flying. */
        FLYING,

        /** The lead is in water. */
        IN_WATER,

        /** The lead is standing inside lava or water. */
        IN_BLOCK,

        /** Nothing solid under the lead. An invisible player falls; a spectator didn't. */
        NOT_ON_GROUND,

        /** Names were given for an emote that takes none. */
        SOLO_EMOTE,

        /** This emote takes a cast and the lead may not start one. See the configured permission. */
        CAST_NOT_PERMITTED,

        /** Wrong number of players named. {@link #options()} names the slots. */
        CAST_WRONG_SIZE,

        /** Nobody online by that name. {@link #subject()} is the name typed. */
        CAST_NOT_ONLINE,

        /** The lead named themselves. */
        CAST_IS_LEAD,

        /** The same player was named twice. */
        CAST_DUPLICATED,

        /** {@link #subject()} is already in an emote of their own. */
        CAST_BUSY,

        /** {@link #subject()} has no baked rig in this pack. */
        CAST_NO_RIG,

        /**
         * {@link #subject()} was hurt recently.
         *
         * @deprecated Never returned since 2.1.0, for the same reason as
         *     {@link #IN_COMBAT}.
         */
        @Deprecated
        CAST_IN_COMBAT,

        /** {@link #subject()} is in spectator. */
        CAST_IN_SPECTATOR,

        /** {@link #subject()} isn't standing on the ground nearby. */
        CAST_NOT_ON_GROUND,

        /** Nowhere for {@link #subject()} to stand where this emote puts them. */
        NO_ROOM,

        /**
         * Something on this server refused it by cancelling
         * {@link ai.resourcepack.api.event.EmoteStartEvent}.
         *
         * <p>No reason comes with it, and that is honest rather than lazy: the
         * reason belongs to whoever cancelled, and they are the one who should
         * say it.
         */
        CANCELLED
    }

    private final Reason reason;
    private final String emote;
    private final String subject;
    private final List<String> options;
    private final boolean borrowedSkin;

    private EmoteResult(Reason reason, String emote, String subject, List<String> options, boolean borrowedSkin) {
        this.reason = reason;
        this.emote = emote;
        this.subject = subject;
        this.options = options == null ? Collections.emptyList() : List.copyOf(options);
        this.borrowedSkin = borrowedSkin;
    }

    /** It started. {@code borrowedSkin} means they are wearing the pack's fallback, not their own. */
    public static EmoteResult started(String emote, boolean borrowedSkin) {
        return new EmoteResult(Reason.STARTED, emote, null, null, borrowedSkin);
    }

    /** It didn't start, for this reason. */
    public static EmoteResult refused(Reason reason) {
        return new EmoteResult(reason, null, null, null, false);
    }

    /** It didn't start; {@code subject} is the player or name the reason is about. */
    public static EmoteResult refused(Reason reason, String subject) {
        return new EmoteResult(reason, null, subject, null, false);
    }

    /** It didn't start; {@code options} is what would have worked instead. */
    public static EmoteResult refused(Reason reason, String subject, List<String> options) {
        return new EmoteResult(reason, null, subject, options, false);
    }

    /** Whether the emote started. */
    public boolean started() {
        return reason == Reason.STARTED;
    }

    /** Why. {@link Reason#STARTED} when it worked. */
    public Reason reason() {
        return reason;
    }

    /** The emote's name once it started, otherwise null. */
    public String emote() {
        return emote;
    }

    /**
     * Who or what the reason is about - a cast member's name, or the words that
     * named no emote. Null where the reason is about the lead themselves.
     */
    public String subject() {
        return subject;
    }

    /**
     * What would have worked: the emote names that exist for
     * {@link Reason#UNKNOWN_EMOTE}, or the cast slots for
     * {@link Reason#CAST_WRONG_SIZE}. Empty otherwise.
     */
    public List<String> options() {
        return options;
    }

    /**
     * Whether the lead is wearing the pack's fallback skin rather than their
     * own, which happens when they were not in the party at push time.
     *
     * <p>Worth saying once when it happens rather than refusing: they are
     * emoting, just not as themselves, and the fix is a re-sync they can choose
     * to do later.
     */
    public boolean borrowedSkin() {
        return borrowedSkin;
    }

    @Override
    public String toString() {
        return "EmoteResult(" + reason + (subject == null ? "" : " " + subject) + ")";
    }
}
