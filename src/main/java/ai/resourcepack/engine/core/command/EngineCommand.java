package ai.resourcepack.engine.core.command;

import ai.resourcepack.engine.api.BuiltPack;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.ContentRegistry;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
        if (!allowed(sender, sub)) {
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

        // One width across every group, so the descriptions line up down the
        // whole menu rather than per section.
        List<Help> mine = new ArrayList<>();
        for (Area area : groups) {
            for (Help line : area.help()) {
                if (allowed(sender, line.command())) {
                    mine.add(line);
                }
            }
        }
        if (mine.isEmpty()) {
            Reply.to(sender, "You have no RP Engine commands. Ask for rpengine.admin.");
            return true;
        }
        int width = Help.width(mine);

        for (Area area : groups) {
            List<Help> lines = area.help().stream()
                    .filter(line -> allowed(sender, line.command()))
                    .toList();
            if (lines.isEmpty()) {
                continue;
            }
            sender.sendMessage("");
            sender.sendMessage(Reply.HEADING + area.title());
            for (Help line : lines) {
                sender.sendMessage(Reply.BODY + line.render(width));
            }
        }
        sender.sendMessage("");
        sender.sendMessage(Reply.BODY + "  /emote <name|stop> [player...]     play an emote on yourself");
        return true;
    }
}
