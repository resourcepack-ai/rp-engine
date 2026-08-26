package ai.resourcepack.engine.core.emote;

import ai.resourcepack.engine.api.EmoteMessages;
import ai.resourcepack.engine.api.EmoteResult;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Everything an emote says, in this plugin's voice.
 *
 * <p>The library answers with a typed {@link EmoteResult} and no sentence,
 * deliberately: it runs on servers that are not ours, and a library that
 * returned the wording would be choosing the palette and the language for
 * every one of them. So this is the one place in this jar that knows what a
 * refusal means, which also makes it the one file to open if these ever want
 * translating.
 *
 * <p>It is both halves of that: {@link #of} for a refusal somebody is waiting
 * on, and the {@link EmoteMessages} methods for the three things an emote has
 * to say to somebody who did not run the command.
 */
public final class EmoteWording implements EmoteMessages {

    /**
     * The node that lets a player pull others into an emote, named here
     * because the sentence that gets them unstuck is the one that says which
     * node to ask an admin for. Declared in plugin.yml and handed to the
     * library at startup; see PresencePlugin.
     */
    public static final String MULTI_PERMISSION = "rpengine.emote.cast";

    /**
     * The node that skips the asking.
     *
     * <p>Consent exists because an emote with a cast moves the people it names
     * — but a staff member setting up a shot, or an owner testing a duet on
     * their own alt, is not a stranger grabbing somebody, and making them wait
     * for a click they are about to make themselves is friction with no
     * safety in it. Default op, like every other node here, so a server hands
     * it out on purpose.
     *
     * <p>NOT a child of {@link #MULTI_PERMISSION}: holding one is being
     * allowed to ask, holding this is not having to, and a grant of the first
     * must never quietly confer the second.
     */
    public static final String FORCE_PERMISSION = "rpengine.emote.force";

    /**
     * A refusal, in words, for the player who asked.
     *
     * <p>Every reason gets its own sentence because every reason has its own
     * fix - re-sync, pick another name, get out of combat, ask an admin - and
     * "that didn't work" would leave them guessing at which.
     */
    public static String of(EmoteResult result) {
        String subject = result.subject() == null ? "" : result.subject();
        String named = subject;
        switch (result.reason()) {
            case STARTED:
                return "Playing " + result.emote() + "."
                    + (result.borrowedSkin()
                        ? " " + "(Wearing the default skin - Sync again with you in the party "
                            + "to use your own.)"
                        : "");
            case LEAD_OFFLINE:
                return "You have to be online to do that.";
            case ALREADY_EMOTING:
                return "You're already doing one. " + "/emote stop"
                    + " to stop.";
            case NO_EMOTES:
                return "This server has no emotes yet - sync a pack that has some from the panel.";
            case UNKNOWN_EMOTE:
                return "No emote called " + named + ". Try: "
                    + String.join(", ", result.options());
            case COOLDOWN:
                return "Slow down a moment.";
            case NO_RIG_FOR_PLAYER:
                // Three faults wear one symptom, and this is the one that is
                // ours rather than theirs: the pack ships a fallback Steve on
                // every push that has emotes, so having none is a bug here.
                return "This pack has emote data but no rig for you, and no fallback either "
                    + "- that's a bug, please report it.";
            case NO_RIGS_IN_PACK:
                return "This pack was synced without any emote rigs. Sync again from the panel; "
                    + "if it keeps happening, that's a bug.";
            case INCOMPLETE_EMOTE_DATA:
                return "This pack's emote data is incomplete - Sync again from the panel.";
            // IN_COMBAT and CAST_IN_COMBAT have no branch here on purpose: the
            // library stopped returning them in 2.1.0 when the combat gate was
            // removed, and a sentence for a refusal that cannot happen is a
            // sentence nobody will ever check again. The constants outlive
            // them, because that enum only grows.
            case IN_SPECTATOR:
                return "Not in spectator.";
            case RIDING:
                return "Not while you're riding something.";
            case GLIDING:
                return "Not while you're gliding.";
            case FLYING:
                return "Not while you're flying.";
            case IN_WATER:
                return "Not while you're in water.";
            case IN_BLOCK:
                return "Not while you're in there.";
            case NOT_ON_GROUND:
                return "You have to be on the ground.";
            case SOLO_EMOTE:
                return named + " is a solo emote - it takes no other players.";
            case CAST_NOT_PERMITTED:
                // Named rather than "you don't have permission": the node is
                // op-default and the person to ask is the server owner, so the
                // sentence that gets them unstuck is the one that says which.
                return "That emote takes other players, and you can't start one. Ask an admin for "
                    + MULTI_PERMISSION + ".";
            case CAST_WRONG_SIZE:
                return named + " takes " + result.options().size() + " other player"
                    + (result.options().size() == 1 ? "" : "s") + ": "
                    + "/emote " + subject + " <" + String.join("> <", result.options()) + ">" + ".";
            case CAST_NOT_ONLINE:
                return "Nobody online called " + named + ".";
            case CAST_IS_LEAD:
                return "You're already the lead - name somebody else.";
            case CAST_DUPLICATED:
                return named + " can only be in it once.";
            case CAST_BUSY:
                return named + " is already doing an emote.";
            case CAST_NO_RIG:
                return named + " has no emote rig in this pack - Sync again with them in the party.";
            case CAST_IN_SPECTATOR:
                return named + " is in spectator.";
            case CAST_NOT_ON_GROUND:
                return named + " has to be standing on the ground nearby.";
            case NO_ROOM:
                return "There isn't room for " + named
                    + " where this emote puts them. Try somewhere more open.";
            case CANCELLED:
                // Something on this server refused it and did not say why,
                // which is its right - it may well have said something itself.
                return "Not here.";
            default:
                return "That didn't work.";
        }
    }

    @Override
    public void pulledIn(Player member, Player lead, String emote) {
        member.sendMessage(lead.getName()
            + " pulled you into " + emote
            + ". You'll be put back where you were.");
    }

    @Override
    public void stopped(Player player, boolean alone) {
        player.sendMessage(""
            + (alone ? "Emote stopped." : "Emote stopped - you're back where you were."));
    }

    @Override
    public void restoredAfterInterruption(Player player) {
        player.sendMessage(""
            + "Your emote was interrupted - you're back where you were.");
    }

    // ---- Invitations -------------------------------------------------------
    //
    // An emote with a cast teleports the people it names and holds them for its
    // length, so they are asked first. These are the words either side of that
    // question; EmoteInvites owns when they are said. The reply arrives as
    // /emotereply, which is a command of its own rather than a word on /emote:
    // Bukkit refuses a command on its `permission:` before onCommand runs, and
    // resourcepackai.emote is default-op, so a guest who can be NAMED in an
    // emote could otherwise be shown two buttons neither of which does anything.

    /**
     * The question itself, with the two buttons that answer it.
     *
     * <p>A component rather than a line of text because the answer has to be
     * clickable: the alternative is telling somebody to type a token they can
     * see but not copy, which on Bedrock is not possible at all.
     */
    public static TextComponent invitation(String leadName, String emote, String token, int seconds) {
        TextComponent line = new TextComponent(leadName
            + " wants you in " + emote + ". You'll be moved into place and put "
            + "back afterwards. ");

        TextComponent accept = new TextComponent("[accept]");
        accept.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/emotereply accept " + token));
        accept.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
            new Text("Join " + emote)));
        line.addExtra(accept);

        line.addExtra(new TextComponent(" "));

        TextComponent deny = new TextComponent("[deny]");
        deny.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/emotereply deny " + token));
        deny.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
            new Text("Turn it down")));
        line.addExtra(deny);

        line.addExtra(new TextComponent(" (" + seconds + "s)"));
        return line;
    }

    /** Told to the lead once the invitations are out. */
    public static String inviteSent(List<String> names, int seconds) {
        return "Asked " + String.join(", ", names)
            + ". It starts when everyone accepts, and expires in " + seconds + "s.";
    }

    /** Told to the lead as each answer comes in, while others are still to reply. */
    public static String inviteAccepted(String name, int waitingOn) {
        return name + " accepted." + (waitingOn <= 0 ? ""
            : " Waiting on " + waitingOn + " more.");
    }

    /** Told to the lead when somebody turns it down. The emote is off. */
    public static String inviteDenied(String name) {
        return name + " turned it down.";
    }

    /** Told to the lead when nobody answered in time. */
    public static String inviteExpired(List<String> names) {
        return "Nobody answered in time: " + String.join(", ", names) + ".";
    }

    /**
     * Told to everyone still holding the question when it stops mattering -
     * somebody said no, the lead left, or it ran out. Silence here would leave
     * two buttons in their chat that do nothing.
     */
    public static String inviteOff(String leadName, String emote) {
        return leadName + "'s " + emote
            + " is off.";
    }

    /** Told to whoever just accepted, before the emote starts. */
    public static String replyAccepted(String emote) {
        return "You're in " + emote + ".";
    }

    /** Told to whoever just declined. */
    public static String replyDenied(String emote) {
        return "You turned down " + emote + ".";
    }

    /** The invitation was answered, withdrawn or timed out before the click landed. */
    public static String inviteGone() {
        return "That invitation isn't waiting any more.";
    }

    /** The lead already has one out; two at once would double-book the cast. */
    public static String inviteAlreadyOut() {
        return "You already have an emote invitation out. Wait for it, or "
            + "/emote stop" + ".";
    }

    /** Somebody named is already holding a question from somebody else. */
    public static String castAlreadyInvited(String name) {
        return name + " is already being asked to join one.";
    }
}
