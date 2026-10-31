package ai.resourcepack.engine.core.command;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.core.entity.CustomEntities;
import ai.resourcepack.engine.core.entity.Spawners;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Fixed-point mob spawners: {@code spawner create|remove|set|list|info}.
 *
 * <p>Each verb has its own node under {@code rpengine.spawner}
 * ({@code rpengine.spawner.create}...), so a server can let staff look at
 * spawners without letting them make one. A content pack's spawners are
 * listed and described here but changed only in their files.
 */
public final class SpawnerCommands implements Area {

    static final List<String> VERBS = List.of("create", "remove", "set", "list", "info");
    private static final List<String> SETTINGS = List.of("mob", "cooldown", "warmup", "max-mobs", "mobs-per-spawn",
            "radius", "activation-range", "leash-range", "heal-on-leash", "level");

    private final Spawners spawners;
    private final CustomEntities creatures;

    public SpawnerCommands(Spawners spawners, CustomEntities creatures) {
        this.spawners = spawners;
        this.creatures = creatures;
    }

    @Override
    public String title() {
        return "Mob spawners";
    }

    @Override
    public List<Help> help() {
        return List.of(
                Help.of("spawner", "create <name> <mob> [x y z]", "make one"),
                Help.of("spawner", "set <name> <key> <value>", "tune it"),
                Help.of("spawner", "remove <name>", "take one away"),
                Help.of("spawner", "list", "every spawner"),
                Help.of("spawner", "info <name>", "its settings"));
    }

    @Override
    public List<String> signatures() {
        List<String> out = new ArrayList<>();
        for (String verb : VERBS) {
            out.add("spawner " + verb);
        }
        return out;
    }

    @Override
    public boolean run(CommandSender sender, String sub, String[] args) {
        if (args.length < 2) {
            Reply.to(sender, "/rp spawner <" + String.join("|", VERBS) + "> ...");
            return true;
        }
        String verb = args[1].toLowerCase(Locale.ROOT);
        if (!VERBS.contains(verb)) {
            Reply.to(sender, "No spawner verb " + args[1] + ". One of: " + String.join(", ", VERBS) + ".");
            return true;
        }
        if (!sender.hasPermission("rpengine.spawner." + verb)) {
            Reply.to(sender, "You need rpengine.spawner." + verb + " for that.");
            return true;
        }
        switch (verb) {
            case "create":
                return create(sender, args);
            case "remove":
                if (args.length < 3) {
                    Reply.to(sender, "/rp spawner remove <name>");
                } else if (spawners.remove(args[2])) {
                    Reply.to(sender, "Removed spawner " + args[2] + ". Its mobs are still standing.");
                } else {
                    Reply.to(sender, spawners.get(args[2]).isPresent()
                            ? args[2] + " is a content pack's spawner; remove it from its file."
                            : "No spawner " + args[2] + ".");
                }
                return true;
            case "set":
                if (args.length < 5) {
                    Reply.to(sender, "/rp spawner set <name> <" + String.join("|", SETTINGS) + "> <value>");
                    return true;
                }
                String problem = spawners.set(args[2], args[3], args[4]);
                Reply.to(sender, problem == null ? "Spawner " + args[2] + ": " + args[3] + " is " + args[4] + "."
                        : problem);
                return true;
            case "info":
                if (args.length < 3) {
                    Reply.to(sender, "/rp spawner info <name>");
                    return true;
                }
                Optional<Spawners.Spawner> found = spawners.get(args[2]);
                if (found.isEmpty()) {
                    Reply.to(sender, "No spawner " + args[2] + ".");
                    return true;
                }
                Spawners.Spawner s = found.get();
                Reply.to(sender, s + (s.saved ? "" : ", from a content pack") + ": " + s.alive() + "/" + s.maxMobs
                        + " alive, " + s.perSpawn + " every " + trim(s.cooldown) + "s"
                        + (s.warmup > 0 ? " after " + trim(s.warmup) + "s warm-up" : "")
                        + ", radius " + trim(s.radius) + ", active within " + trim(s.activationRange)
                        + (s.leashRange > 0 ? ", leash " + trim(s.leashRange) + (s.healOnLeash ? " (heals)" : "") : "")
                        + (s.level > 0 ? ", level " + s.level : "") + ".");
                return true;
            default:
                if (spawners.all().isEmpty()) {
                    Reply.to(sender, "No spawners. /rp spawner create <name> <mob> makes one where you stand.");
                    return true;
                }
                List<String> lines = new ArrayList<>();
                spawners.all().values().stream().sorted((a, b) -> a.id.compareTo(b.id))
                        .forEach(one -> lines.add(one.id + " (" + one.mob + ", " + one.alive() + "/" + one.maxMobs + ")"));
                Reply.to(sender, Reply.plural(lines.size(), "spawner") + ": " + String.join(", ", lines));
                return true;
        }
    }

    private boolean create(CommandSender sender, String[] args) {
        if (args.length < 4) {
            Reply.to(sender, "/rp spawner create <name> <mob> [x y z [world]]");
            return true;
        }
        String name = args[2].toLowerCase(Locale.ROOT);
        if (!name.matches("[a-z0-9_.-]{1,48}")) {
            Reply.to(sender, "A spawner's name is letters, digits, _ . and -.");
            return true;
        }
        Optional<ContentId> mob = ContentId.parse(args[3]);
        if (mob.isEmpty() || creatures.info(mob.get()).isEmpty()) {
            Reply.to(sender, "No custom mob " + args[3] + ".");
            return true;
        }
        Location at = null;
        if (args.length >= 7) {
            try {
                World world = args.length > 7 ? Bukkit.getWorld(args[7]) : null;
                if (world == null) {
                    world = sender instanceof Entity ? ((Entity) sender).getWorld()
                            : sender instanceof BlockCommandSender ? ((BlockCommandSender) sender).getBlock().getWorld()
                            : Bukkit.getWorlds().get(0);
                }
                at = new Location(world, Double.parseDouble(args[4]), Double.parseDouble(args[5]),
                        Double.parseDouble(args[6]));
            } catch (NumberFormatException e) {
                Reply.to(sender, "The place is three numbers: x y z.");
                return true;
            }
        } else if (sender instanceof Entity) {
            at = ((Entity) sender).getLocation();
        } else if (sender instanceof BlockCommandSender) {
            at = ((BlockCommandSender) sender).getBlock().getLocation().add(0, 1, 0);
        }
        if (at == null) {
            Reply.to(sender, "From the console: /rp spawner create <name> <mob> <x> <y> <z> [world]");
            return true;
        }
        if (!spawners.create(name, mob.get().toString(), at)) {
            Reply.to(sender, "There is a spawner called " + name + " already.");
            return true;
        }
        Reply.to(sender, "Spawner " + name + " keeps one " + mob.get() + " here. Change it with "
                + "/rp spawner set " + name + " max-mobs 3, cooldown, radius, leash-range...");
        return true;
    }

    @Override
    public List<String> complete(CommandSender sender, String sub, String[] args) {
        if (args.length == 2) {
            return Completions.matching(args[1], VERBS);
        }
        String verb = args[1].toLowerCase(Locale.ROOT);
        if (args.length == 3 && (verb.equals("remove") || verb.equals("set") || verb.equals("info"))) {
            return Completions.matching(args[2], new ArrayList<>(spawners.all().keySet()));
        }
        if (args.length == 4 && verb.equals("create")) {
            return Completions.matchingIds(args[3], creatures.ids());
        }
        if (args.length == 4 && verb.equals("set")) {
            return Completions.matching(args[3], SETTINGS);
        }
        return List.of();
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
