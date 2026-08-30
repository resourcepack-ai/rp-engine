package ai.resourcepack.engine.core.command;

import ai.resourcepack.engine.api.EmoteResult;
import ai.resourcepack.engine.api.event.EmoteEndEvent;
import ai.resourcepack.engine.core.emote.EmoteDirector;
import ai.resourcepack.engine.core.emote.EmoteInvites;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@code /emote}, {@code /emotereply}, and the {@code /rp emote} that is the
 * same command reached through the admin one.
 *
 * <p>A solo emote runs straight off the director: there is nobody to ask, and
 * a confirmation in front of the common case would make the feature worse to
 * use. One that NAMES people goes through {@link EmoteInvites} first, because
 * an emote with a cast teleports the people it names, hides them, and holds
 * them for its length — which should not happen to somebody because a stranger
 * typed their name.
 */
public final class EmoteCommands implements Area {

    private final EmoteDirector emotes;
    private final EmoteInvites invites;

    public EmoteCommands(EmoteDirector emotes, EmoteInvites invites) {
        this.emotes = emotes;
        this.invites = invites;
    }

    @Override
    public String title() {
        return "Emotes";
    }

    @Override
    public List<Help> help() {
        return List.of(
                Help.of("emotes", "list the emotes"),
                Help.of("emote", "<name|stop>", "play one on yourself"));
    }

    /**
     * The help covers {@code /rp emote}; this area also owns two commands of
     * its own, {@code /emote} and {@code /emotereply}, and their usage lines
     * are sent as chat like any other message.
     *
     * <p>{@code /emotereply} has no line of help on purpose — it is an answer
     * to an invitation rather than something to go and look up — which is
     * exactly the case that leaves a message naming it truncated if it is not
     * declared here.
     */
    @Override
    public List<String> signatures() {
        List<String> all = new ArrayList<>(Area.super.signatures());
        all.add("<accept|deny> <token>");
        all.add("<name|stop> [player...]");
        return all;
    }

    @Override
    public boolean run(CommandSender sender, String sub, String[] args) {
        if (sub.equals("emotes")) {
            if (emotes.ids().isEmpty()) {
                Reply.to(sender, "No emotes. They arrive with a studio push.");
            }
            for (String id : emotes.ids()) {
                Reply.to(sender, id);
            }
            return true;
        }
        return perform(sender, Arrays.copyOfRange(args, 1, args.length));
    }

    @Override
    public List<String> complete(CommandSender sender, String sub, String[] args) {
        return sub.equals("emote")
                ? completePerform(sender, Arrays.copyOfRange(args, 1, args.length))
                : List.of();
    }

    /**
     * {@code /emote <name|stop> [player...]}, wherever it was typed.
     *
     * <p>Public because it is also a command in its own right — most players
     * will never type {@code /rp} at all.
     */
    public boolean perform(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            Reply.to(sender, "/emote <name|stop> [player...], as a player.");
            return true;
        }
        Player player = (Player) sender;
        if (args.length == 0 || args[0].equalsIgnoreCase("stop")) {
            emotes.stop(player, false, EmoteEndEvent.Cause.STOPPED);
            Reply.to(player, "Stopped.");
            return true;
        }

        // Read off the words before anything else looks at them. A flag is a
        // token in its own right — it needs none of the director's knowledge of
        // where a free-text emote name ends — and leaving it in is how
        // "Sprint --showYourself" becomes a hunt for a player by that name.
        boolean showYourself = hasShowYourself(args);
        String[] rest = withoutFlags(args);

        EmoteInvites.Request request = invites.resolve(rest);
        if (request != null && !request.castNames().isEmpty()) {
            invites.open(player, request);
            return true;
        }

        // The director takes the whole argument list: it owns the rule about
        // what an emote's arguments mean, and a second copy here would drift.
        EmoteResult result = emotes.perform(player, List.of(rest), showYourself);
        if (!result.started()) {
            Reply.to(player, refusal(result));
            return true;
        }
        // Asked of the director rather than worked out here, so the warning
        // follows what was actually done: the flag means nothing on a one-shot
        // emote, and a caution about latency in front of an emote that ignored
        // it is noise somebody learns to skip.
        if (emotes.showingSelf(player.getUniqueId())) {
            for (String line : SHOW_YOURSELF_WARNING) Reply.to(player, line);
        }
        return true;
    }

    /**
     * The flag that lets a set's wearer watch it themselves.
     *
     * <p>Spelled with the two dashes a flag is usually spelled with, so it
     * cannot collide with an emote name — those are free text and a server
     * could genuinely have one called "show yourself".
     */
    private static final String SHOW_YOURSELF = "--showyourself";

    /**
     * Why this is a testing tool, in the words a player needs.
     *
     * <p><b>The delay is real and is not a bug to be fixed later</b>, which is
     * why the warning names it rather than hedging: the rig is a server entity
     * moved a tick at a time and interpolated, while the body it stands in for
     * is drawn by the wearer's own client the instant they press a key. Nothing
     * on this side closes that gap for the person wearing it — the lead the rig
     * is carried on is sized for the people WATCHING them.
     */
    private static final List<String> SHOW_YOURSELF_WARNING = List.of(
            "Showing you your own set. For testing only —",
            "it will lag behind you, because your rig is a server",
            "entity and your body is not. Don't leave it on in production.");

    /** Whether the flag is among these words, in any position and any case. */
    static boolean hasShowYourself(String[] args) {
        for (String word : args) {
            if (word.equalsIgnoreCase(SHOW_YOURSELF)) return true;
        }
        return false;
    }

    /**
     * The words with every flag taken out.
     *
     * <p>Anything starting with {@code --} goes, not only the one flag we know:
     * an unrecognised flag left in the list would be resolved as part of an
     * emote name or as a cast member and refused with a sentence about the
     * wrong thing entirely.
     */
    static String[] withoutFlags(String[] args) {
        List<String> kept = new ArrayList<>(args.length);
        for (String word : args) {
            if (!word.startsWith("--")) kept.add(word);
        }
        return kept.toArray(new String[0]);
    }

    /**
     * Completions for {@code /emote}.
     *
     * <p>Names first, and once one is typed, whoever could still join it — the
     * director answers that, because only it knows which emotes take a cast and
     * how many slots are left.
     */
    public List<String> completePerform(CommandSender sender, String[] args) {
        if (args.length <= 1) {
            List<String> names = new ArrayList<>(emotes.ids());
            names.add("stop");
            return Completions.matching(args.length == 0 ? "" : args[0], names);
        }
        return Completions.matching(args[args.length - 1], emotes.castCandidates(sender, args));
    }

    /** {@code /emotereply <accept|deny> <token>}. */
    public boolean reply(CommandSender sender, String[] args) {
        if (!(sender instanceof Player) || args.length < 2
                || !invites.reply((Player) sender, args[0], args[1])) {
            Reply.to(sender, "/emotereply <accept|deny> <token>");
        }
        return true;
    }

    /** Completions for {@code /emotereply}. The token is pasted, not typed. */
    public List<String> completeReply(String[] args) {
        return args.length <= 1
                ? Completions.matching(args.length == 0 ? "" : args[0], "accept", "deny")
                : List.of();
    }

    /**
     * Why an emote was refused, in words.
     *
     * <p>The engine returns a typed reason and the host writes the sentence —
     * an engine that chose the wording would be choosing the language for every
     * server that runs it. This is that choice, made once, here.
     */
    static String refusal(EmoteResult result) {
        switch (result.reason()) {
            case UNKNOWN_EMOTE:
                return "No emote by that name.";
            case NO_EMOTES:
                return "This server has no emotes yet.";
            case NO_RIGS_IN_PACK:
                return "The pack has no emote rigs in it.";
            case NO_RIG_FOR_PLAYER:
                return "There is no rig for you in this pack.";
            case ALREADY_EMOTING:
                return "You are already emoting.";
            case NOT_ON_GROUND:
                return "You need to be on the ground.";
            case IN_WATER:
                return "Not in water.";
            case FLYING:
                return "Not while flying.";
            case GLIDING:
                return "Not while gliding.";
            case RIDING:
                return "Not while riding.";
            case IN_SPECTATOR:
                return "Not in spectator.";
            case NO_ROOM:
                return "Not enough room here.";
            case IN_BLOCK:
                return "Not inside a block.";
            case SOLO_EMOTE:
                return "That emote needs other players.";
            case COOLDOWN:
                return "Give it a moment.";
            case CANCELLED:
                return "Something stopped that.";
            default:
                return "Cannot do that here.";
        }
    }
}
