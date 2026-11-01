package ai.resourcepack.engine.core.command;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.StingerInfo;
import ai.resourcepack.engine.core.stinger.StingerRuntime;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Stinger transitions: {@code stingers}, and
 * {@code stinger <name> [player|selector] [holdTicks]}.
 *
 * <p>Plays the whole stinger, in, hold, out, which is what Studio's "Play in
 * game" relays (as the console, naming {@code @p}). A plugin that wants to do
 * something while the screen is covered uses the API instead.
 */
public final class StingerCommands implements Area {

    /** The longest hold a command may ask for: a minute. */
    private static final int MAX_HOLD = 1200;

    private final StingerRuntime runtime;

    public StingerCommands(StingerRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public String title() {
        return "Stingers";
    }

    @Override
    public List<Help> help() {
        return List.of(
                Help.of("stingers", "each stinger transition this server can play"),
                Help.of("stinger", "<name> [player] [holdTicks]", "cover a player's screen, hold, uncover it"));
    }

    @Override
    public boolean run(CommandSender sender, String sub, String[] args) {
        if ("stingers".equals(sub)) {
            return list(sender);
        }
        return play(sender, args);
    }

    private boolean list(CommandSender sender) {
        List<ContentId> ids = runtime.ids();
        if (ids.isEmpty()) {
            Reply.to(sender, "No stingers. Make one in Studio and sync or publish the pack.");
            return true;
        }
        Reply.heading(sender, "Stingers", Reply.plural(ids.size(), "stinger") + ", play one with /rp stinger <name>");
        for (ContentId id : ids) {
            runtime.info(id).ifPresent(info -> Reply.row(sender, id.toString(), info.name() + " · "
                    + seconds(info.inTicks()) + " in, " + seconds(info.holdTicks()) + " hold, "
                    + seconds(info.outTicks()) + " out"));
        }
        return true;
    }

    private boolean play(CommandSender sender, String[] args) {
        if (args.length < 2) {
            Reply.to(sender, "/rp stinger <name> [player] [holdTicks]. /rp stingers lists them.");
            return true;
        }
        Optional<StingerInfo> found = runtime.find(args[1]);
        if (found.isEmpty()) {
            Reply.to(sender, "No stinger called " + args[1] + ". /rp stingers lists them.");
            return true;
        }
        StingerInfo info = found.get();

        String who = null;
        String holdWord = null;
        if (args.length >= 4) {
            who = args[2];
            holdWord = args[3];
        } else if (args.length == 3) {
            // One word: a hold when it is a number nobody online is called.
            if (isWhole(args[2]) && Bukkit.getPlayerExact(args[2]) == null) {
                holdWord = args[2];
            } else {
                who = args[2];
            }
        }
        int hold = info.holdTicks();
        if (holdWord != null) {
            if (!isWhole(holdWord)) {
                Reply.to(sender, "The hold is a whole number of ticks, like 40.");
                return true;
            }
            hold = Math.min(MAX_HOLD, Integer.parseInt(holdWord));
        }

        List<Player> targets = targets(sender, who);
        if (targets.isEmpty()) {
            Reply.to(sender, who != null ? "No player matches " + who + "." : "/rp stinger <name> <player>, from the console.");
            return true;
        }
        int played = 0;
        List<String> without = new ArrayList<>();
        for (Player target : targets) {
            if (!Targets.permitted(sender, "stinger", target)) {
                Targets.refuse(sender, "stinger", "play a stinger on");
                return true;
            }
            if (runtime.play(target, info.id(), hold, null).isPresent()) {
                played++;
            } else {
                without.add(target.getName());
            }
        }
        if (!without.isEmpty()) {
            Reply.to(sender, String.join(", ", without) + (without.size() == 1 ? "'s pack does" : "'s packs do")
                    + " not have " + info.id() + ": it plays only for players holding the pack it came in.");
        }
        // Said only when it is not the sender's own screen, which shows it.
        if (played > 0 && !(targets.size() == 1 && targets.get(0) == sender)) {
            Reply.to(sender, "Playing " + info.id() + " on " + Reply.plural(played, "player") + ".");
        }
        return true;
    }

    /** A name, a selector ({@code @a}, {@code @p}), or the sender. */
    private static List<Player> targets(CommandSender sender, String token) {
        if (token == null || !token.startsWith("@")) {
            Player one = Targets.of(sender, token);
            return one == null ? List.of() : List.of(one);
        }
        Set<Player> out = new LinkedHashSet<>();
        try {
            for (Entity found : Bukkit.selectEntities(sender, token)) {
                if (found instanceof Player player) {
                    out.add(player);
                }
            }
        } catch (IllegalArgumentException ignored) {
            // Not a selector this server reads; fall back to whoever ran it.
        }
        if (out.isEmpty() && sender instanceof Player self) {
            out.add(self);
        }
        return new ArrayList<>(out);
    }

    private static boolean isWhole(String word) {
        return word != null && !word.isEmpty() && word.length() <= 6 && word.chars().allMatch(Character::isDigit);
    }

    private static String seconds(int ticks) {
        double s = ticks / 20.0;
        return (s == Math.floor(s) ? String.valueOf((int) s) : String.format(Locale.ROOT, "%.1f", s)) + "s";
    }

    @Override
    public List<String> complete(CommandSender sender, String sub, String[] args) {
        if (!"stinger".equals(sub)) {
            return List.of();
        }
        if (args.length == 2) {
            List<String> names = new ArrayList<>();
            for (ContentId id : runtime.ids()) {
                names.add(id.toString());
            }
            return startingWith(names, args[1]);
        }
        if (args.length == 3) {
            List<String> out = new ArrayList<>(Targets.names(sender, "stinger"));
            if (Targets.mayTargetOthers(sender, "stinger")) {
                out.addAll(List.of("@a", "@p"));
            }
            return startingWith(out, args[2]);
        }
        if (args.length == 4) {
            return runtime.find(args[1]).map(info -> List.of(String.valueOf(info.holdTicks()))).orElse(List.of());
        }
        return List.of();
    }

    private static List<String> startingWith(List<String> options, String typed) {
        String prefix = typed.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
