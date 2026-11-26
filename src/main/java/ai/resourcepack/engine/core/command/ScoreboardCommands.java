package ai.resourcepack.engine.core.command;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ScoreboardInfo;
import ai.resourcepack.engine.core.scoreboard.ScoreboardRuntime;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /rp scoreboard}: list the scoreboards, and show, hide or toggle one
 * for a player.
 *
 * <p>Each verb has its own node under {@code rpengine.scoreboard}.
 * {@code toggle} is every player's by default, since it only hides or brings
 * back their own; the rest are an operator's. Pointing one at somebody else
 * needs {@code rpengine.scoreboard.others} as well.
 */
public final class ScoreboardCommands implements Area {

    private static final List<String> VERBS = List.of("show", "hide", "auto", "toggle");

    private final ScoreboardRuntime scoreboards;

    public ScoreboardCommands(ScoreboardRuntime scoreboards) {
        this.scoreboards = scoreboards;
    }

    @Override
    public String title() {
        return "Scoreboards";
    }

    @Override
    public List<Help> help() {
        return List.of(
                Help.of("scoreboard", "list the scoreboards"),
                Help.of("scoreboard show", "<id> [player]", "show one"),
                Help.of("scoreboard hide", "[player]", "hide it"),
                Help.of("scoreboard auto", "[player]", "back to the usual one"),
                Help.of("scoreboard toggle", "hide yours, or bring it back"));
    }

    @Override
    public boolean run(CommandSender sender, String sub, String[] args) {
        if (args.length < 2) {
            return list(sender);
        }
        String verb = args[1].toLowerCase(Locale.ROOT);
        if (!VERBS.contains(verb)) {
            Reply.to(sender, "/rp scoreboard [show <id>|hide|auto|toggle] [player]");
            return true;
        }
        if (!sender.hasPermission("rpengine.scoreboard." + verb)) {
            Reply.error(sender, "You need rpengine.scoreboard." + verb + " for that.");
            return true;
        }
        if (verb.equals("show")) {
            return show(sender, args);
        }
        Player target = Targets.of(sender, verb.equals("toggle") ? null : args.length > 2 ? args[2] : null);
        if (target == null) {
            Reply.to(sender, verb.equals("toggle")
                    ? "Only a player has a scoreboard to toggle."
                    : "Name a player: /rp scoreboard " + verb + " <player>");
            return true;
        }
        if (!Targets.permitted(sender, "scoreboard", target)) {
            Targets.refuse(sender, "scoreboard", "change the scoreboard of");
            return true;
        }
        switch (verb) {
            case "hide":
                scoreboards.setHidden(target, true);
                Reply.to(sender, "Hid the scoreboard for " + target.getName() + ". /rp scoreboard toggle brings it back.");
                break;
            case "auto":
                scoreboards.reset(target);
                scoreboards.setHidden(target, false);
                Reply.to(sender, target.getName() + " is back on the scoreboard of where they are.");
                break;
            default:
                boolean hide = !scoreboards.hidden(target);
                scoreboards.setHidden(target, hide);
                Reply.to(sender, hide ? "Scoreboard hidden. /rp scoreboard toggle brings it back." : "Scoreboard shown.");
                break;
        }
        return true;
    }

    private boolean list(CommandSender sender) {
        if (scoreboards.ids().isEmpty()) {
            Reply.to(sender, "No scoreboards loaded. A pack declares them in scoreboards/.");
            return true;
        }
        Reply.heading(sender, "Scoreboards", Reply.plural(scoreboards.ids().size(), "scoreboard")
                + ", /rp scoreboard show <id> to see one");
        for (ContentId id : scoreboards.ids()) {
            ScoreboardInfo info = scoreboards.info(id).orElse(null);
            if (info == null) {
                continue;
            }
            Reply.row(sender, id.toString(), Reply.plural(info.lines().size(), "line")
                    + (info.background() ? " · own background" : "")
                    + (!info.automatic() ? " · by command only"
                    : info.worlds().isEmpty() ? "" : " · in " + String.join(", ", info.worlds())));
        }
        if (sender instanceof Player player) {
            if (scoreboards.hidden(player)) {
                Reply.to(sender, "Yours is hidden. /rp scoreboard toggle brings it back.");
            } else {
                scoreboards.showing(player).ifPresent(id -> Reply.to(sender, "Showing you: " + id
                        + (scoreboards.pinned(player) ? " (set by command; /rp scoreboard auto ends that)" : "")));
            }
        }
        return true;
    }

    private boolean show(CommandSender sender, String[] args) {
        if (args.length < 3) {
            Reply.to(sender, "/rp scoreboard show <id> [player]");
            return true;
        }
        Player target = Targets.of(sender, args.length > 3 ? args[3] : null);
        if (target == null) {
            Reply.to(sender, "Name a player: /rp scoreboard show <id> <player>");
            return true;
        }
        if (!Targets.permitted(sender, "scoreboard", target)) {
            Targets.refuse(sender, "scoreboard", "change the scoreboard of");
            return true;
        }
        boolean shown = ContentId.parse(args[2]).map(id -> scoreboards.show(target, id)).orElse(Boolean.FALSE);
        Reply.to(sender, shown
                ? "Showing " + args[2] + " to " + target.getName() + ". /rp scoreboard auto ends it."
                : "No scoreboard called " + args[2] + ".");
        return true;
    }

    @Override
    public List<String> complete(CommandSender sender, String sub, String[] args) {
        if (args.length == 2) {
            return Completions.matching(args[1], VERBS);
        }
        String verb = args[1].toLowerCase(Locale.ROOT);
        boolean show = verb.equals("show");
        if (show && args.length == 3) {
            return Completions.matchingIds(args[2], scoreboards.ids());
        }
        if (args.length == (show ? 4 : 3) && VERBS.contains(verb) && !verb.equals("toggle")) {
            return Completions.matching(args[args.length - 1], Targets.names(sender, "scoreboard"));
        }
        return new ArrayList<>();
    }
}
