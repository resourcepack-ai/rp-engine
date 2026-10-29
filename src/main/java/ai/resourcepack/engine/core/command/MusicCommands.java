package ai.resourcepack.engine.core.command;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.MusicInfo;
import ai.resourcepack.engine.core.music.MusicRuntime;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /rp music}: list the music, and start, stop or skip it for a player.
 *
 * <p>Each verb has its own node under {@code rpengine.music}, so a server can
 * let players stop or skip their own music without letting them start any.
 * Pointing one at somebody else needs {@code rpengine.music.others} as well.
 */
public final class MusicCommands implements Area {

    private static final List<String> VERBS = List.of("play", "stop", "skip");

    private final MusicRuntime music;

    public MusicCommands(MusicRuntime music) {
        this.music = music;
    }

    @Override
    public String title() {
        return "Music";
    }

    @Override
    public List<Help> help() {
        return List.of(
                Help.of("music", "list the music"),
                Help.of("music play", "<id> [player]", "start some"),
                Help.of("music stop", "[player]", "stop it"),
                Help.of("music skip", "[player]", "next track"));
    }

    @Override
    public boolean run(CommandSender sender, String sub, String[] args) {
        if (args.length < 2) {
            return list(sender);
        }
        String verb = args[1].toLowerCase(Locale.ROOT);
        if (!VERBS.contains(verb)) {
            Reply.to(sender, "/rp music [play <id>|stop|skip] [player]");
            return true;
        }
        if (!sender.hasPermission("rpengine.music." + verb)) {
            Reply.to(sender, "You need rpengine.music." + verb + " for that.");
            return true;
        }
        if (verb.equals("play")) {
            return play(sender, args);
        }
        Player target = Targets.of(sender, args.length > 2 ? args[2] : null);
        if (target == null) {
            Reply.to(sender, "Name a player: /rp music " + verb + " <player>");
            return true;
        }
        if (!Targets.permitted(sender, "music", target)) {
            Targets.refuse(sender, "music", verb + " the music for");
            return true;
        }
        if (verb.equals("stop")) {
            music.stop(target);
            Reply.to(sender, "Stopped the music for " + target.getName() + ".");
            return true;
        }
        Reply.to(sender, music.skip(target)
                ? "Skipped to the next track for " + target.getName() + "."
                : "Nothing is playing for " + target.getName() + ".");
        return true;
    }

    private boolean list(CommandSender sender) {
        if (music.ids().isEmpty()) {
            Reply.to(sender, "No music loaded. A pack declares it in music/.");
            return true;
        }
        Reply.heading(sender, "Music", Reply.plural(music.ids().size(), "piece")
                + ", /rp music play <id> to hear one");
        for (ContentId id : music.ids()) {
            MusicInfo info = music.info(id).orElse(null);
            if (info == null) {
                continue;
            }
            Reply.row(sender, id.toString(), Reply.plural(info.tracks().size(), "track")
                    + (info.shuffle() ? " · shuffled" : "")
                    + (info.placed() ? "" : " · by command only"));
        }
        if (sender instanceof Player player) {
            music.playing(player).ifPresent(id -> Reply.to(sender, "Playing for you: " + id
                    + music.track(player).map(track -> " (" + track + ")").orElse("")));
        }
        return true;
    }

    private boolean play(CommandSender sender, String[] args) {
        if (args.length < 3) {
            Reply.to(sender, "/rp music play <id> [player]");
            return true;
        }
        Player target = Targets.of(sender, args.length > 3 ? args[3] : null);
        if (target == null) {
            Reply.to(sender, "Name a player: /rp music play <id> <player>");
            return true;
        }
        if (!Targets.permitted(sender, "music", target)) {
            Targets.refuse(sender, "music", "start music for");
            return true;
        }
        boolean played = ContentId.parse(args[2]).map(id -> music.play(target, id)).orElse(Boolean.FALSE);
        Reply.to(sender, played
                ? "Playing " + args[2] + " for " + target.getName() + ". /rp music stop ends it."
                : "No music called " + args[2] + ".");
        return true;
    }

    @Override
    public List<String> complete(CommandSender sender, String sub, String[] args) {
        if (args.length == 2) {
            return Completions.matching(args[1], VERBS);
        }
        boolean play = args[1].equalsIgnoreCase("play");
        if (play && args.length == 3) {
            return Completions.matchingIds(args[2], music.ids());
        }
        if (args.length == (play ? 4 : 3) && VERBS.contains(args[1].toLowerCase(Locale.ROOT))) {
            return Completions.matching(args[args.length - 1], Targets.names(sender, "music"));
        }
        return new ArrayList<>();
    }
}
