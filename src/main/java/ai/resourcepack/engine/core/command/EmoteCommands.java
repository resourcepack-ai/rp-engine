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

        EmoteInvites.Request request = invites.resolve(args);
        if (request != null && !request.castNames().isEmpty()) {
            invites.open(player, request);
            return true;
        }

        // The director takes the whole argument list: it owns the rule about
        // what an emote's arguments mean, and a second copy here would drift.
        EmoteResult result = emotes.perform(player, List.of(args));
        if (!result.started()) {
            Reply.to(player, refusal(result));
        }
        return true;
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
