package ai.resourcepack.engine.core.command;

import ai.resourcepack.engine.core.distribution.DistributionManager;
import ai.resourcepack.engine.core.sync.SyncClient;
import ai.resourcepack.engine.core.sync.SyncCodes;
import ai.resourcepack.engine.core.sync.SyncGroup;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Live-testing a pack from Studio: {@code sync} and {@code distribute}.
 *
 * <p>{@code sync} is one verb group rather than a party system beside it,
 * because the feature is "who else receives my pushes" — a property of a sync,
 * not a social structure. That is also why there is no disband and no
 * ownership transfer: the group lives exactly as long as the sync does.
 *
 * <p>{@code distribute} is the other end of the same idea, and the reason it
 * sits here: one binds a pack to a person for as long as they are testing, the
 * other binds one to the server for everybody who joins.
 */
public final class SyncCommands implements Area {

    private static final List<String> VERBS =
            List.of("add", "accept", "deny", "remove", "leave", "who", "stop");

    private final Server server;
    private final SyncClient sync;
    private final SyncGroup group;
    private final DistributionManager distribution;
    /** Tells studio the roster of a code, which it wants whole on every change. */
    private final Consumer<String> announce;
    /** Takes a pushed pack back off one player, leaving the server's own content. */
    private final Consumer<String> unpush;

    public SyncCommands(Server server, SyncClient sync, SyncGroup group,
                        DistributionManager distribution,
                        Consumer<String> announce, Consumer<String> unpush) {
        this.server = server;
        this.sync = sync;
        this.group = group;
        this.distribution = distribution;
        this.announce = announce;
        this.unpush = unpush;
    }

    @Override
    public String title() {
        return "Studio";
    }

    @Override
    public List<Help> help() {
        return List.of(
                Help.of("sync", "<code>", "pair with a pack open in Studio"),
                Help.of("sync add", "<player>", "share your pushes with somebody"),
                Help.of("sync", "who|leave|stop", "see, leave or end a sync"),
                Help.of("distribute", "<code|off>", "serve a published pack to everybody"));
    }

    @Override
    public boolean run(CommandSender sender, String sub, String[] args) {
        if (sub.equals("distribute")) {
            return distribute(sender, args);
        }
        if (!(sender instanceof Player)) {
            Reply.to(sender, "/rpengine sync, as a player.");
            return true;
        }
        return sync((Player) sender, args);
    }

    @Override
    public List<String> complete(CommandSender sender, String sub, String[] args) {
        if (sub.equals("distribute")) {
            // The code comes off a web page, so only the one word can be
            // offered — but "off" is the one somebody has to guess otherwise.
            return args.length == 2 ? Completions.matching(args[1], "off") : List.of();
        }
        if (args.length == 2) {
            // A code cannot be completed — it comes off a web page — so the
            // words are offered and the code is simply typed.
            return Completions.matching(args[1], VERBS);
        }
        if (args.length != 3) {
            return List.of();
        }
        String verb = args[1].toLowerCase(Locale.ROOT);
        if (verb.equals("add")) {
            // Only people who could actually accept: somebody already on a sync
            // cannot be added to a second one, so offering them is offering a
            // command that will refuse.
            List<String> free = new ArrayList<>();
            for (Player online : server.getOnlinePlayers()) {
                if (group.receiving(online.getName()).isEmpty()) {
                    free.add(online.getName());
                }
            }
            return Completions.matching(args[2], free);
        }
        if (verb.equals("remove")) {
            return Completions.matching(args[2],
                    group.codeOf(sender.getName()).map(group::recipients).orElse(List.of()));
        }
        return List.of();
    }

    private boolean distribute(CommandSender sender, String[] args) {
        if (args.length < 2) {
            Reply.to(sender, "/rpengine distribute <code>  serve a published pack to everybody who joins");
            Reply.to(sender, "/rpengine distribute off     stop serving it");
            return true;
        }
        if (args[1].equalsIgnoreCase("off")) {
            distribution.unbind(sender);
            return true;
        }
        distribution.claim(sender, args[1]);
        return true;
    }

    private boolean sync(Player player, String[] args) {
        String verb = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "who";
        switch (verb) {
            case "add":
                return add(player, args);
            case "accept":
                return accept(player);
            case "deny":
                Reply.to(player, group.deny(player.getName()) == SyncGroup.Result.OK
                        ? "Declined." : "Nobody has asked you.");
                return true;
            case "remove":
                return remove(player, args);
            case "leave":
                return leave(player);
            case "stop":
                return stop(player);
            case "who":
                return who(player);
            default:
                return claim(player, args[1]);
        }
    }

    private boolean add(Player player, String[] args) {
        if (args.length < 3) {
            Reply.to(player, "/rpengine sync add <player>");
            return true;
        }
        Player target = server.getPlayerExact(args[2]);
        if (target == null) {
            Reply.to(player, args[2] + " is not online.");
            return true;
        }
        switch (group.invite(player.getName(), target.getName())) {
            case OK:
                Reply.to(player, "Asked " + target.getName() + ".");
                Reply.to(target, player.getName()
                        + " wants to share a pack with you. /rp sync accept, or deny.");
                return true;
            case NOT_SYNCED:
                Reply.to(player, "You are not synced. /rp sync <code> first.");
                return true;
            case SELF:
                Reply.to(player, "You already get your own pushes.");
                return true;
            default:
                Reply.to(player, target.getName() + " is already on a sync.");
                return true;
        }
    }

    private boolean accept(Player player) {
        Optional<String> joined = group.accept(player.getName());
        if (joined.isEmpty()) {
            Reply.to(player, "Nobody has asked you.");
            return true;
        }
        announce.accept(joined.get());
        Reply.to(player, "You will get their pushes.");
        return true;
    }

    private boolean remove(Player player, String[] args) {
        if (args.length < 3) {
            Reply.to(player, "/rpengine sync remove <player>");
            return true;
        }
        Optional<String> code = group.remove(player.getName(), args[2]);
        if (code.isEmpty()) {
            Reply.to(player, args[2] + " is not on your sync.");
            return true;
        }
        // A removal that only stopped FUTURE pushes would leave them holding
        // what they already had, which is not what remove means.
        unpush.accept(args[2]);
        announce.accept(code.get());
        Reply.to(player, "Removed " + args[2] + ".");
        return true;
    }

    private boolean leave(Player player) {
        Optional<String> code = group.leave(player.getName());
        if (code.isEmpty()) {
            Reply.to(player, "You are not on anybody's sync.");
            return true;
        }
        unpush.accept(player.getName());
        announce.accept(code.get());
        Reply.to(player, "Left.");
        return true;
    }

    private boolean stop(Player player) {
        List<String> were = group.stop(player.getName());
        if (were.isEmpty()) {
            Reply.to(player, "You are not synced.");
            return true;
        }
        for (String name : were) {
            unpush.accept(name);
        }
        group.codeOf(player.getName()).ifPresent(announce);
        sync.forget(player.getName());
        Reply.to(player, "Stopped.");
        return true;
    }

    private boolean who(Player player) {
        Optional<String> code = group.receiving(player.getName());
        if (code.isEmpty()) {
            Reply.to(player, "You are not synced. /rp sync <code> to start.");
            return true;
        }
        Reply.to(player, code.get() + ": " + String.join(", ", group.recipients(code.get())));
        return true;
    }

    /**
     * Anything that is not a verb is meant to be a code, because that is what
     * somebody types first and asking them to write "sync code 48213097" would
     * be a word that earns nothing.
     *
     * <p>But it has to LOOK like one: a typo landing here used to be claimed
     * and reported as synced, which is a lie about a thing that will never
     * arrive.
     */
    private boolean claim(Player player, String code) {
        if (!SyncCodes.isValid(code)) {
            Reply.to(player, code + " is not a pairing code. "
                    + "Codes are eight digits, from the sync button in the panel.");
            Reply.to(player, "/rpengine sync <code|add|accept|deny|remove|leave|who|stop>");
            return true;
        }
        if (!sync.link(code, player.getName())) {
            Reply.to(player, "Could not reach studio. Check sync.url in config.yml.");
            return true;
        }
        group.claim(code, player.getName());
        announce.accept(code);
        // "Waiting", not "synced". Nothing here can tell whether a well-formed
        // code was ever issued — the far end silently ignores one it does not
        // know and sends nothing back — so the most that can honestly be said
        // is that we are listening.
        Reply.to(player, "Waiting for a push on " + code
                + ". Hit sync in the panel. /rp sync add <player> to share it.");
        return true;
    }
}
