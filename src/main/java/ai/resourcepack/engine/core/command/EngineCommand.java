package ai.resourcepack.engine.core.command;

import ai.resourcepack.engine.api.BuiltPack;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.ContentRegistry;
import ai.resourcepack.engine.core.Chat;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The one command, and the two that stand beside it.
 *
 * <p>{@code /rpengine} is a router and nothing else: it decides whether
 * somebody may run a subcommand, hands it to the {@link Area} that owns it,
 * and prints the summary when nothing was asked for. Every subcommand's
 * behaviour lives in its area, which is why adding one is a change to one
 * small class rather than a case in a switch nobody can see the end of.
 *
 * <p>{@code /emote} and {@code /emotereply} are separate commands rather than
 * subcommands because most players will never type {@code /rp} at all, and
 * they are answered here so that all three share one completer.
 */
public final class EngineCommand implements CommandExecutor, TabCompleter {

    private final ContentRegistry registry;
    private final Supplier<List<BuiltPack>> built;
    private final EmoteCommands emote;

    /** Subcommand to the area that owns it, in the order they are offered. */
    private final Map<String, Area> areas = new LinkedHashMap<>();

    /** The areas themselves, in the order the help reads them out. */
    private final List<Area> groups;

    public EngineCommand(ContentRegistry registry, Supplier<List<BuiltPack>> built,
                         ContentCommands content, ModelCommands models,
                         InterfaceCommands ui, EmoteCommands emote,
                         SyncCommands sync, LiquidCommands liquid) {
        this.registry = registry;
        this.built = built;
        this.emote = emote;
        this.groups = List.of(content, models, ui, emote, sync, liquid);
        for (Area area : groups) {
            for (String sub : area.subcommands()) {
                areas.put(sub, area);
            }
        }

        // What Chat needs to tell "/rp sync <code>" from "/rp sync <code>
        // first": every command there is, not every command with a line of
        // help. Areas whose help covers several verbs in one line say so in
        // signatures(); see Area.
        Set<String> known = new LinkedHashSet<>();
        for (Area area : groups) {
            known.addAll(area.signatures());
        }
        Chat.commands(known);
    }

    /**
     * Adopts a chat palette. Called on enable and on every reload.
     *
     * <p>Static, and on the router rather than on {@link Reply}, because Reply
     * is package-private: this is the command layer's one public door for it,
     * and the plugin walks through it before anything can talk.
     */
    public static void style(ChatStyle style) {
        Reply.style(style);
    }

    /**
     * The tag in front of every line, for the handful of places outside this
     * package that write to a player directly.
     */
    public static String prefix() {
        return Reply.style().prefix();
    }

    /**
     * One prefixed line, for those same places.
     *
     * <p>{@link #prefix()} plus {@code sendMessage} was what they all did, and
     * it is now the wrong way round: a line sent that way is plain text, so a
     * command in it is something to type rather than something to click. This
     * is the same door, one step further in.
     */
    public static void say(CommandSender who, String line) {
        Reply.to(who, line);
    }

    /**
     * What a subcommand needs: {@code rpengine.<subcommand>}, without
     * exception.
     *
     * <p>A node per command rather than three buckets. Buckets are somebody
     * else's guess about which permissions belong together, and the guess is
     * always wrong for some server: the person who may reload content is not
     * necessarily the person who may purge every model in a hundred blocks.
     * The parents in {@code plugin.yml} are how a server that does not care
     * grants them in one line — which is the convenience buckets were reaching
     * for, without the guess.
     */
    public static String permissionFor(String sub) {
        return "rpengine." + sub;
    }

    /** Every subcommand there is. The plugin's own {@code plugin.yml} mirrors it. */
    public List<String> subcommands() {
        return List.copyOf(areas.keySet());
    }

    private boolean allowed(CommandSender sender, String sub) {
        return areas.containsKey(sub) && sender.hasPermission(permissionFor(sub));
    }

    /**
     * Answering an invitation, which nobody needs permission to do.
     *
     * <p>The permission is checked on the first word, which is the subcommand
     * the router dispatches on — and that is right everywhere except here.
     * {@code rpengine.sync} means "may live-test a pack from Studio", defaults
     * to op, and is exactly the right gate on {@code /rp sync <code>} and
     * {@code /rp sync add}. But {@code accept} and {@code deny} are not that
     * command: they are the reply to it, run by the ordinary player somebody
     * just invited, and the blanket check meant the plugin sent them a message
     * saying "/rp sync accept, or deny" and then refused them.
     *
     * <p>Same reasoning as {@code /emotereply}, which is ungated in
     * {@code plugin.yml} for the identical reason: a player who cannot reply
     * is a player who cannot refuse. Neither can do anything except answer
     * something already addressed to them — there is nothing here to abuse
     * without an invitation, and an invitation is somebody else's decision.
     */
    private static boolean ungated(String sub, String[] args) {
        if (!sub.equals("sync") || args.length < 2) {
            return false;
        }
        String verb = args[1].toLowerCase(Locale.ROOT);
        return verb.equals("accept") || verb.equals("deny");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "emote":
                return emote.perform(sender, args);
            case "emotereply":
                return emote.reply(sender, args);
            default:
                break;
        }

        String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("info")) {
            return info(sender);
        }
        if (!areas.containsKey(sub)) {
            Reply.to(sender, "No such command. /rpengine for the list.");
            return true;
        }
        if (!ungated(sub, args) && !allowed(sender, sub)) {
            Reply.to(sender, "You need " + permissionFor(sub) + " for that.");
            return true;
        }
        return areas.get(sub).run(sender, sub, args);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "emote":
                return emote.completePerform(sender, args);
            case "emotereply":
                return emote.completeReply(args);
            default:
                break;
        }

        if (args.length <= 1) {
            // Only what they can actually run. Completing a command that then
            // refuses is worse than not completing it.
            List<String> theirs = new ArrayList<>();
            for (String sub : areas.keySet()) {
                if (allowed(sender, sub)) {
                    theirs.add(sub);
                }
            }
            if (sender.hasPermission(permissionFor("info"))) {
                theirs.add("info");
            }
            return Completions.matching(args.length == 0 ? "" : args[0], theirs);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        return allowed(sender, sub) ? areas.get(sub).complete(sender, sub, args) : List.of();
    }

    /**
     * What this server holds, and every command that reaches it.
     *
     * <p>One subcommand per line, in named groups, with the arguments and a
     * sentence about what it does. It used to be eight lines of pipe-separated
     * names — six unrelated commands crammed onto one row because they
     * happened to fit — which told you what existed and nothing else.
     *
     * <p>Only what the reader can actually run, for the same reason the
     * completer offers only that: a help listing a command that then refuses
     * is worse than one that leaves it out.
     */
    private boolean info(CommandSender sender) {
        if (!sender.hasPermission(permissionFor("info"))) {
            Reply.to(sender, "You need " + permissionFor("info") + " for that.");
            return true;
        }
        Reply.to(sender, Reply.plural(registry.ids().size(), "id") + " across "
                + Reply.plural(registry.namespaces().size(), "namespace") + ", "
                + Reply.plural(built.get().size(), "bundle") + ".");

        List<String> kinds = new ArrayList<>();
        for (ContentKind kind : ContentKind.values()) {
            int count = registry.ids(kind).size();
            if (count > 0) {
                kinds.add(Reply.plural(count, kind.name().toLowerCase(Locale.ROOT)));
            }
        }
        Reply.to(sender, kinds.isEmpty() ? "nothing loaded" : String.join(", ", kinds));

        boolean any = false;
        for (Area area : groups) {
            List<Help> lines = area.help().stream()
                    .filter(line -> allowed(sender, line.command()))
                    .toList();
            if (lines.isEmpty()) {
                continue;
            }
            any = true;
            sender.sendMessage("");
            sender.sendMessage(Reply.style().heading() + area.title());
            for (Help line : lines) {
                // Deliberately NOT through Chat: the help is a list of
                // commands, and a list where every line is a link is a wall of
                // underlines rather than a page somebody can read. The links
                // are for a command named in the middle of a sentence, where
                // one stands out because the rest of the line is prose.
                sender.sendMessage(line.render());
            }
        }
        if (!any) {
            Reply.to(sender, "You have no RP Engine commands. Ask for rpengine.admin.");
        }
        return true;
    }
}
