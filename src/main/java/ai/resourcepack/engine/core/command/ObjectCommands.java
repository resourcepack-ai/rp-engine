package ai.resourcepack.engine.core.command;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.Easing;
import ai.resourcepack.engine.api.ShaderObjectInfo;
import ai.resourcepack.engine.core.object.Driven;
import ai.resourcepack.engine.core.object.ObjectRuntime;

import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 3D shader objects: {@code objects} and {@code object <verb>}.
 *
 * <p>Every verb but {@code spawn} acts on the object nearest whoever typed
 * it, within {@link #REACH} blocks, which is what you want while building a
 * scene; a plugin or a sequence is the way to drive one from afar. Each verb
 * has its own node under {@code rpengine.object}, so a server can let people
 * play an object's sequences without letting them put new ones down.
 */
public final class ObjectCommands implements Area {

    private static final double REACH = 64;

    private static final List<String> VERBS =
            List.of("spawn", "state", "replay", "play", "stop", "move", "turn", "size", "seed", "remove");

    private final ObjectRuntime runtime;

    public ObjectCommands(ObjectRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public String title() {
        return "3D objects";
    }

    @Override
    public List<Help> help() {
        return List.of(
                Help.of("objects", "list the 3D objects, their states and sequences"),
                Help.of("object", "spawn <id> [size] [x y z]", "put one down"),
                Help.of("object", "state <state>", "the nearest one enters a state"),
                Help.of("object", "replay", "play its state again from the start"),
                Help.of("object", "play <sequence>", "run a sequence its file wrote"),
                Help.of("object", "stop", "stop its sequence and moves"),
                Help.of("object", "move <x> <y> <z> [ticks] [ease]", "move it; ~ is where it is"),
                Help.of("object", "turn <yaw> <pitch> <roll> [ticks] [ease]", "turn it; ~ adds"),
                Help.of("object", "size <blocks> [ticks] [ease]", "make it bigger or smaller"),
                Help.of("object", "seed <0-15>", "vary its look"),
                Help.of("object", "remove [all]", "take it away"));
    }

    @Override
    public List<String> signatures() {
        List<String> out = new ArrayList<>();
        out.add("objects");
        for (String verb : VERBS) {
            out.add("object " + verb);
        }
        return out;
    }

    @Override
    public boolean run(CommandSender sender, String sub, String[] args) {
        if ("objects".equals(sub)) {
            return list(sender);
        }
        if (!runtime.supported()) {
            Reply.to(sender, "3D objects need Minecraft 1.21.4 or newer: an object is driven through its "
                    + "item's custom_model_data colour.");
            return true;
        }
        if (args.length < 2) {
            Reply.to(sender, "/rp object <" + String.join("|", VERBS) + "> ...");
            return true;
        }
        String verb = args[1].toLowerCase(Locale.ROOT);
        if (!VERBS.contains(verb)) {
            Reply.to(sender, "No object verb " + args[1] + ". One of: " + String.join(", ", VERBS) + ".");
            return true;
        }
        if (!sender.hasPermission("rpengine.object." + verb)) {
            Reply.to(sender, "You need rpengine.object." + verb + " for that.");
            return true;
        }
        if ("spawn".equals(verb)) {
            return spawn(sender, args);
        }
        // Where the command was typed: a player, a command block, or an entity
        // running it. The console has no place, so it can only spawn by
        // coordinates.
        Location here = placeOf(sender);
        if (here == null) {
            Reply.to(sender, "From the console, only spawn works, with coordinates: "
                    + "/rp object spawn <id> <size> <x> <y> <z> [world]. A command block works for every verb.");
            return true;
        }
        if ("remove".equals(verb) && args.length >= 3 && "all".equalsIgnoreCase(args[2])) {
            List<Driven> all = runtime.near(here, REACH);
            all.forEach(Driven::remove);
            Reply.to(sender, "Removed " + Reply.plural(all.size(), "object") + ".");
            return true;
        }
        List<Driven> found = runtime.near(here, REACH);
        if (found.isEmpty()) {
            Reply.to(sender, "No 3D object within " + (int) REACH + " blocks of you.");
            return true;
        }
        // The nearest one that HAS the state or sequence named, so a scene
        // of several objects can be driven without walking up to each.
        Driven object = found.get(0);
        if (args.length >= 3 && ("state".equals(verb) || "play".equals(verb))) {
            for (Driven candidate : found) {
                boolean has = "state".equals(verb)
                        ? candidate.info().state(args[2]).isPresent()
                        : candidate.info().sequences().contains(args[2].toLowerCase(Locale.ROOT));
                if (has) {
                    object = candidate;
                    break;
                }
            }
        }
        switch (verb) {
            case "state":
                if (args.length < 3) {
                    Reply.to(sender, "Its states: " + String.join(", ", object.info().stateNames()) + ".");
                } else if (object.state(args[2])) {
                    Reply.to(sender, object.id() + " is " + object.state() + ".");
                } else {
                    Reply.to(sender, object.id() + " has no state " + args[2] + ". It has: "
                            + String.join(", ", object.info().stateNames()) + ".");
                }
                return true;
            case "replay":
                object.replay();
                Reply.to(sender, "Playing " + object.state() + " again.");
                return true;
            case "play":
                if (args.length >= 3 && object.play(args[2])) {
                    Reply.to(sender, "Playing " + args[2] + ".");
                } else {
                    Reply.to(sender, object.info().sequences().isEmpty()
                            ? object.id() + " has no sequences. Write some in content/objects/ - see FORMAT.md."
                            : "Its sequences: " + String.join(", ", object.info().sequences()) + ".");
                }
                return true;
            case "stop":
                object.stop();
                Reply.to(sender, "Stopped.");
                return true;
            case "move":
                return move(sender, object, args);
            case "turn":
                return turn(sender, object, args);
            case "size":
                return size(sender, object, args);
            case "seed":
                object.seed(args.length >= 3 ? (int) number(args[2], object.seed()) : (object.seed() + 1) % 16);
                Reply.to(sender, "Seed " + object.seed() + ".");
                return true;
            default:
                object.remove();
                Reply.to(sender, "Removed " + object.id() + ".");
                return true;
        }
    }

    @Override
    public List<String> complete(CommandSender sender, String sub, String[] args) {
        if (!"object".equals(sub)) {
            return List.of();
        }
        if (args.length == 2) {
            return Completions.matching(args[1], VERBS);
        }
        String verb = args[1].toLowerCase(Locale.ROOT);
        if (args.length == 3 && "spawn".equals(verb)) {
            List<ContentId> ids = new ArrayList<>(runtime.catalogue().all().keySet());
            return Completions.matchingIds(args[2], ids);
        }
        Location at = sender instanceof Player player ? player.getLocation() : null;
        List<Driven> near = at == null ? List.of() : runtime.near(at, REACH);
        if (args.length == 3 && !near.isEmpty()) {
            ShaderObjectInfo info = near.get(0).info();
            if ("state".equals(verb)) {
                return Completions.matching(args[2], info.stateNames());
            }
            if ("play".equals(verb)) {
                return Completions.matching(args[2], info.sequences());
            }
        }
        if (args.length == 3 && "remove".equals(verb)) {
            return Completions.matching(args[2], "all");
        }
        int easeAt = "move".equals(verb) || "turn".equals(verb) ? 6 : "size".equals(verb) ? 4 : -1;
        if (args.length == easeAt + 1) {
            List<String> names = new ArrayList<>();
            for (Easing easing : Easing.values()) {
                names.add(easing.key());
            }
            return Completions.matching(args[easeAt], names);
        }
        return List.of();
    }

    private boolean list(CommandSender sender) {
        List<ShaderObjectInfo> all = new ArrayList<>(runtime.catalogue().all().values());
        if (all.isEmpty()) {
            Reply.to(sender, "No 3D objects. Push a pack with one from Studio, or write one in content/objects/.");
            return true;
        }
        Reply.to(sender, Reply.plural(all.size(), "3D object") + ":");
        for (ShaderObjectInfo info : all) {
            Reply.to(sender, "  " + info.id() + " - " + info.size() + " blocks"
                    + (info.states().isEmpty() ? "" : ", states " + String.join(", ", info.stateNames()))
                    + (info.sequences().isEmpty() ? "" : ", sequences " + String.join(", ", info.sequences())));
        }
        if (!runtime.supported()) {
            Reply.to(sender, "This server cannot place them: 3D objects need 1.21.4 or newer.");
        }
        return true;
    }

    private boolean spawn(CommandSender sender, String[] args) {
        if (args.length < 3) {
            Reply.to(sender, "/rp object spawn <id> [size] [x y z] [world]");
            return true;
        }
        Optional<ShaderObjectInfo> info = runtime.catalogue().find(args[2]);
        if (info.isEmpty()) {
            Reply.to(sender, "No 3D object called " + args[2] + ". /rp objects lists them.");
            return true;
        }
        double size = args.length >= 4 ? number(args[3], info.get().size()) : info.get().size();
        Location here = placeOf(sender);
        Location at;
        if (args.length >= 7) {
            // At coordinates, ~ relative to wherever the command was typed.
            org.bukkit.World world = args.length >= 8 ? org.bukkit.Bukkit.getWorld(args[7])
                    : here != null ? here.getWorld() : org.bukkit.Bukkit.getWorlds().get(0);
            if (world == null) {
                Reply.to(sender, "No world called " + args[7] + ".");
                return true;
            }
            Location base = here != null ? here : world.getSpawnLocation();
            at = new Location(world, coordinate(args[4], base.getX()), coordinate(args[5], base.getY()),
                    coordinate(args[6], base.getZ()));
        } else if (sender instanceof Player player) {
            // In front of them, level with their eyes, far enough off that a big
            // one does not swallow them: the spot Studio's Show in game uses.
            Location eye = player.getEyeLocation();
            Vector ahead = eye.getDirection().setY(0);
            if (ahead.lengthSquared() < 1e-6) {
                ahead = new Vector(0, 0, 1);
            }
            at = eye.clone().add(ahead.normalize().multiply(Math.max(3, size * 0.75 + 2)));
        } else if (here != null) {
            at = here.clone().add(0, size / 2 + 1, 0);
        } else {
            Reply.to(sender, "From the console: /rp object spawn <id> <size> <x> <y> <z> [world]");
            return true;
        }
        Optional<Driven> spawned = runtime.spawn(at, info.get(), size);
        Reply.to(sender, spawned.isPresent()
                ? "Put down " + info.get().id() + "."
                + (info.get().states().isEmpty() ? "" : " States: " + String.join(", ", info.get().stateNames()) + ".")
                : "Could not put one down here.");
        return true;
    }

    /** Where a command was typed, or null for the console. */
    private static Location placeOf(CommandSender sender) {
        // `execute as <entity> run ...` arrives wrapped, with the entity as the callee.
        if (sender instanceof org.bukkit.command.ProxiedCommandSender proxied) {
            return placeOf(proxied.getCallee());
        }
        if (sender instanceof org.bukkit.entity.Entity entity) {
            return entity.getLocation();
        }
        if (sender instanceof org.bukkit.command.BlockCommandSender block) {
            return block.getBlock().getLocation().add(0.5, 0.5, 0.5);
        }
        return null;
    }

    private boolean move(CommandSender sender, Driven object, String[] args) {
        if (args.length < 5) {
            Reply.to(sender, "/rp object move <x> <y> <z> [ticks] [ease]");
            return true;
        }
        Location from = object.location();
        Location to = new Location(from.getWorld(),
                coordinate(args[2], from.getX()), coordinate(args[3], from.getY()), coordinate(args[4], from.getZ()));
        object.moveTo(to, ticks(args, 5), ease(args, 6));
        return true;
    }

    private boolean turn(CommandSender sender, Driven object, String[] args) {
        if (args.length < 5) {
            Reply.to(sender, "/rp object turn <yaw> <pitch> <roll> [ticks] [ease]");
            return true;
        }
        float[] now = object.rotation();
        object.turnTo((float) coordinate(args[2], now[0]), (float) coordinate(args[3], now[1]),
                (float) coordinate(args[4], now[2]), ticks(args, 5), ease(args, 6));
        return true;
    }

    private boolean size(CommandSender sender, Driven object, String[] args) {
        if (args.length < 3) {
            Reply.to(sender, "/rp object size <blocks> [ticks] [ease]");
            return true;
        }
        object.resize(coordinate(args[2], object.size()), ticks(args, 3), ease(args, 4));
        return true;
    }

    /** A number, or {@code ~}/{@code ~n} relative to {@code base}. */
    private static double coordinate(String typed, double base) {
        if (typed.startsWith("~")) {
            return base + (typed.length() == 1 ? 0 : number(typed.substring(1), 0));
        }
        return number(typed, base);
    }

    private static int ticks(String[] args, int at) {
        if (args.length <= at) {
            return 0;
        }
        String typed = args[at].toLowerCase(Locale.ROOT);
        return typed.endsWith("s")
                ? (int) Math.round(number(typed.substring(0, typed.length() - 1), 0) * 20)
                : (int) number(typed, 0);
    }

    private static Easing ease(String[] args, int at) {
        return args.length <= at ? Easing.IN_OUT : Easing.of(args[at]).orElse(Easing.IN_OUT);
    }

    private static double number(String typed, double fallback) {
        try {
            return Double.parseDouble(typed.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
