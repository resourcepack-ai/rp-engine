package ai.resourcepack.engine.core.command;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.VfxInfo;
import ai.resourcepack.engine.api.VfxOptions;
import ai.resourcepack.engine.core.model.Durations;
import ai.resourcepack.engine.core.vfx.VfxCatalogue;
import ai.resourcepack.engine.core.vfx.VfxData;
import ai.resourcepack.engine.core.vfx.VfxEntry;
import ai.resourcepack.engine.core.vfx.VfxRuntime;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ProxiedCommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Arrays;

/**
 * VFX: {@code vfx}, {@code vfx presets}, {@code cast <name> [player]},
 * {@code cast <name> at <x> <y> <z> [world]} and {@code cast stop}.
 *
 * <p>A cast plays at the player's feet, facing the way they face, which is the
 * frame every recipe is written in. A cast {@code at} a place has no caster:
 * nobody's placeholders, nobody to follow, and it faces the way whoever ran
 * the command faces (south from the console), which {@code turn:} changes.
 */
public final class VfxCommands implements Area {

    private static final String STOP = "stop";

    /**
     * The word before coordinates. Never a player's name: those are three
     * characters at least.
     */
    private static final String AT = "at";

    /** {@code rpengine.cast.at}: a cast at a place is anywhere on the server, not on somebody. */
    static final String AT_PERMISSION = EngineCommand.permissionFor("cast") + ".at";

    private final VfxCatalogue catalogue;
    private final VfxRuntime runtime;

    public VfxCommands(VfxCatalogue catalogue, VfxRuntime runtime) {
        this.catalogue = catalogue;
        this.runtime = runtime;
    }

    @Override
    public String title() {
        return "VFX";
    }

    @Override
    public List<Help> help() {
        return List.of(
                Help.of("vfx", "each VFX this server can play"),
                Help.of("vfx", "presets", "the ready-made ones"),
                Help.of("cast", "<name> [player] [options]", "play a VFX where a player stands"),
                Help.of("cast", "<name> at <x> <y> <z> [world] [options]", "play a VFX at a place, cast by nobody"),
                Help.of("cast", "stop [player|tag:<name>]", "end VFX playing"));
    }

    @Override
    public List<String> signatures() {
        return List.of("vfx presets", "vfx", "cast stop", "cast");
    }

    @Override
    public boolean run(CommandSender sender, String sub, String[] args) {
        if ("vfx".equals(sub)) {
            if (args.length >= 2 && "presets".equalsIgnoreCase(args[1])) {
                return presets(sender);
            }
            return list(sender);
        }
        return cast(sender, args);
    }

    private boolean list(CommandSender sender) {
        List<ContentId> ids = catalogue.ids();
        if (ids.isEmpty()) {
            Reply.to(sender, "No VFX. Make one in Studio, or write one in a content folder's vfx/ "
                    + "(/rp vfx presets lists the ready-made ones).");
            return true;
        }
        Reply.heading(sender, "VFX", Reply.plural(ids.size(), "effect") + ", play one with /rp cast <name>");
        for (ContentId id : ids) {
            Optional<VfxInfo> info = catalogue.info(id);
            if (info.isEmpty()) {
                continue;
            }
            VfxInfo effect = info.get();
            String from = effect.source() == VfxInfo.Source.STUDIO ? "Studio"
                    : effect.preset().map(p -> "preset " + p).orElse("own recipe");
            Reply.row(sender, id.toString(), effect.name() + " · " + seconds(effect.durationTicks()) + " · " + from);
        }
        if (!runtime.supported()) {
            Reply.note(sender, "This server is older than 1.21.4, so none of them can play here.");
        }
        return true;
    }

    private boolean presets(CommandSender sender) {
        List<VfxData.Preset> all = VfxData.get().presets();
        Reply.heading(sender, "Presets", Reply.plural(all.size(), "preset") + ", use one with preset: <key> in vfx/");
        for (VfxData.Preset preset : all) {
            Reply.row(sender, preset.key(), preset.name() + " · " + preset.blurb());
        }
        return true;
    }

    private boolean cast(CommandSender sender, String[] args) {
        if (args.length < 2) {
            Reply.to(sender, "/rp cast <name> [player] [options], /rp cast <name> at <x> <y> <z> [world] [options], "
                    + "or /rp cast stop [player|tag:<name>]. /rp vfx lists them.");
            return true;
        }
        if (STOP.equalsIgnoreCase(args[1])) {
            return stop(sender, args);
        }
        if (!runtime.supported()) {
            Reply.to(sender, "VFX need Minecraft 1.21.4 or newer: a sprite picks its picture through an item model "
                    + "definition, which older servers cannot send.");
            return true;
        }
        Optional<VfxEntry> entry = catalogue.find(args[1]);
        if (entry.isEmpty()) {
            Reply.to(sender, "No VFX called " + args[1] + ". /rp vfx lists them.");
            return true;
        }
        if (args.length > 2 && AT.equalsIgnoreCase(args[2])) {
            return castAt(sender, entry.get(), args);
        }
        int at = 2;
        String named = null;
        if (args.length > 2 && !looksLikeOption(args[2])) {
            named = args[2];
            at = 3;
        }
        Player target = Targets.of(sender, named);
        if (target == null) {
            Reply.to(sender, named != null ? "No player called " + named + " is online." : "/rp cast <name> <player>, from the console.");
            return true;
        }
        if (!Targets.permitted(sender, "cast", target)) {
            Targets.refuse(sender, "cast", "cast a VFX on");
            return true;
        }
        VfxOptions.Builder options = VfxOptions.builder();
        String problem = parseOptions(args, at, options, target);
        if (problem != null) {
            Reply.to(sender, problem + " The options are on the VFX docs page.");
            return true;
        }
        runtime.play(entry.get(), target.getLocation(), target, options.build());
        return true;
    }

    /**
     * {@code cast <name> at <x> <y> <z> [world] [options]}: a cast with nobody
     * casting it, for an ability, an arena or a command block. {@code ~} is
     * relative to whoever runs it: a player, a command block, the entity of an
     * {@code execute as}, or the world's spawn from the console. (Bukkit hands a
     * plugin command no {@code execute positioned}, so {@code as <entity>} is
     * the way to put {@code ~} somewhere.)
     */
    private boolean castAt(CommandSender sender, VfxEntry entry, String[] args) {
        if (!sender.hasPermission(AT_PERMISSION)) {
            Reply.to(sender, "You need " + AT_PERMISSION + " to cast a VFX at a place.");
            return true;
        }
        if (args.length < 6) {
            Reply.to(sender, "/rp cast <name> at <x> <y> <z> [world] [options]");
            return true;
        }
        Location here = placeOf(sender);
        int next = 6;
        World world;
        if (args.length > 6 && !looksLikeOption(args[6])) {
            world = Bukkit.getWorld(args[6]);
            if (world == null) {
                Reply.to(sender, "No world called " + args[6] + ".");
                return true;
            }
            next = 7;
        } else {
            world = here != null ? here.getWorld() : Bukkit.getWorlds().get(0);
        }
        Location base = here != null && here.getWorld() == world ? here : world.getSpawnLocation();
        Double x = coordinate(args[3], base.getX());
        Double y = coordinate(args[4], base.getY());
        Double z = coordinate(args[5], base.getZ());
        if (x == null || y == null || z == null) {
            Reply.to(sender, "The place is three numbers, each of them a number or ~ (~2 for two more): "
                    + "/rp cast <name> at <x> <y> <z>.");
            return true;
        }
        Location place = new Location(world, x, y, z, here != null ? here.getYaw() : 0, 0);
        VfxOptions.Builder options = VfxOptions.builder();
        String problem = parseOptions(args, next, options, null, true);
        if (problem != null) {
            Reply.to(sender, problem + " The options are on the VFX docs page.");
            return true;
        }
        runtime.play(entry, place, null, options.build());
        return true;
    }

    /** Where a command runs, or null for the console. */
    private static Location placeOf(CommandSender sender) {
        // `execute as <entity> run ...` arrives wrapped, with the entity as the callee.
        if (sender instanceof ProxiedCommandSender proxied) {
            return placeOf(proxied.getCallee());
        }
        if (sender instanceof Entity entity) {
            return entity.getLocation();
        }
        if (sender instanceof BlockCommandSender block) {
            return block.getBlock().getLocation().add(0.5, 0.5, 0.5);
        }
        return null;
    }

    /** A number, or {@code ~}/{@code ~n} relative to {@code base}; null when it is neither. */
    private static Double coordinate(String typed, double base) {
        if (typed.startsWith("~")) {
            if (typed.length() == 1) {
                return base;
            }
            Double n = number(typed.substring(1));
            return n == null ? null : base + n;
        }
        return number(typed);
    }

    private static final List<String> FLAGS = List.of("loop", "follow", "vary", "quiet");

    private static final List<String> OPTIONS = List.of("loop", "loop:", "until:", "while:", "check:", "end:finish",
            "end:cut", "speed:", "scale:", "turn:", "offset:", "follow", "delay:", "vary", "seed:", "quiet", "volume:",
            "only:caster", "tag:");

    /** Words that are options rather than a player's name. */
    private static boolean looksLikeOption(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        return w.contains(":") || FLAGS.contains(w);
    }

    /**
     * The options, as {@code key:value} words; null when all of them read, or
     * what is wrong. {@code until:} and {@code while:} take the rest of the
     * line, because a condition has spaces in it.
     */
    static String parseOptions(String[] args, int from, VfxOptions.Builder out, Player target) {
        return parseOptions(args, from, out, target, false);
    }

    /**
     * The options of a cast {@code at} a place when {@code atPlace}, which
     * refuses the two that need somebody it was cast on.
     */
    static String parseOptions(String[] args, int from, VfxOptions.Builder out, Player target, boolean atPlace) {
        for (int i = from; i < args.length; i++) {
            String word = args[i];
            int colon = word.indexOf(':');
            String key = (colon < 0 ? word : word.substring(0, colon)).toLowerCase(Locale.ROOT);
            String value = colon < 0 ? "" : word.substring(colon + 1);
            switch (key) {
                case "loop" -> {
                    if (value.isEmpty()) {
                        out.loop();
                    } else if (value.toLowerCase(Locale.ROOT).endsWith("x")) {
                        Integer n = whole(value.substring(0, value.length() - 1));
                        if (n == null || n < 1) {
                            return "loop:" + value + " should be a count, like loop:3x.";
                        }
                        out.loopTimes(n);
                    } else {
                        OptionalLong t = Durations.ticks(value);
                        if (t.isEmpty() || t.getAsLong() == 0) {
                            return "loop:" + value + " should be a time (30s, 5m, 100t) or a count (3x).";
                        }
                        out.loopFor(t.getAsLong());
                    }
                }
                case "until", "while" -> {
                    String condition = String.join(" ", Arrays.copyOfRange(args, i, args.length));
                    condition = condition.substring(condition.indexOf(':') + 1).trim();
                    if (condition.isEmpty()) {
                        return key + ": needs a condition, like " + key + ":{arena_state} == \"ended\".";
                    }
                    if (key.equals("until")) {
                        out.until(condition);
                    } else {
                        out.whileTrue(condition);
                    }
                    return null;
                }
                case "check" -> {
                    OptionalLong t = Durations.ticks(value);
                    if (t.isEmpty() || t.getAsLong() == 0) {
                        return "check:" + value + " should be a time, like check:10t.";
                    }
                    out.checkEvery((int) Math.min(1200, t.getAsLong()));
                }
                case "end" -> {
                    if (value.equalsIgnoreCase("finish")) {
                        out.end(VfxOptions.End.FINISH);
                    } else if (value.equalsIgnoreCase("cut")) {
                        out.end(VfxOptions.End.CUT);
                    } else {
                        return "end: is finish or cut.";
                    }
                }
                case "speed", "scale", "turn", "volume" -> {
                    Double n = number(value);
                    if (n == null) {
                        return key + ":" + value + " should be a number.";
                    }
                    switch (key) {
                        case "speed" -> out.speed(n);
                        case "scale" -> out.scale(n);
                        case "turn" -> out.turn(n);
                        default -> out.volume(n);
                    }
                }
                case "offset" -> {
                    String[] parts = value.split(",");
                    Double l = parts.length == 3 ? number(parts[0]) : null;
                    Double u = parts.length == 3 ? number(parts[1]) : null;
                    Double a = parts.length == 3 ? number(parts[2]) : null;
                    if (l == null || u == null || a == null) {
                        return "offset: is three numbers, left,up,ahead: offset:0,1,2.";
                    }
                    out.offset(l, u, a);
                }
                case "delay" -> {
                    OptionalLong t = Durations.ticks(value);
                    if (t.isEmpty()) {
                        return "delay:" + value + " should be a time, like delay:2s.";
                    }
                    out.delay((int) Math.min(6000, t.getAsLong()));
                }
                case "seed" -> {
                    Integer n = whole(value);
                    if (n == null) {
                        return "seed:" + value + " should be a whole number.";
                    }
                    out.seed(n);
                }
                case "only" -> {
                    if (atPlace) {
                        return "only:caster needs a player to cast it on; a cast at a place has none.";
                    }
                    if (!value.equalsIgnoreCase("caster")) {
                        return "only: is only:caster, for the player it is cast on alone.";
                    }
                    out.viewers(VfxOptions.Viewers.CASTER);
                }
                case "tag" -> {
                    if (value.isBlank()) {
                        return "tag: needs a name, like tag:aura.";
                    }
                    out.tag(value);
                }
                case "follow" -> {
                    if (atPlace) {
                        return "follow needs a player to cast it on; a cast at a place has none.";
                    }
                    out.follow(target);
                }
                case "vary" -> out.vary(true);
                case "quiet" -> out.sounds(false);
                default -> {
                    return "\"" + word + "\" is not an option.";
                }
            }
        }
        return null;
    }

    private static Integer whole(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double number(String s) {
        try {
            double d = Double.parseDouble(s.trim());
            return Double.isFinite(d) ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean stop(CommandSender sender, String[] args) {
        if (args.length < 3 || args[2].equalsIgnoreCase("all")) {
            int stopped = runtime.stopAll();
            Reply.to(sender, stopped == 0 ? "No VFX playing." : "Stopped " + Reply.plural(stopped, "VFX") + ".");
            return true;
        }
        if (args[2].toLowerCase(Locale.ROOT).startsWith("tag:")) {
            int n = runtime.stop(args[2].substring(4));
            Reply.to(sender, n == 0 ? "No VFX playing with that tag." : "Ending " + Reply.plural(n, "VFX") + ".");
            return true;
        }
        Player target = Targets.of(sender, args[2]);
        if (target == null) {
            Reply.to(sender, "No player called " + args[2] + " is online.");
            return true;
        }
        int n = runtime.stop(target);
        Reply.to(sender, n == 0 ? "No VFX playing on " + target.getName() + "." : "Ending " + Reply.plural(n, "VFX") + ".");
        return true;
    }

    private static String seconds(int ticks) {
        double s = ticks / 20.0;
        return (s == Math.floor(s) ? String.valueOf((int) s) : String.format(Locale.ROOT, "%.1f", s)) + "s";
    }

    @Override
    public List<String> complete(CommandSender sender, String sub, String[] args) {
        if ("vfx".equals(sub) && args.length == 2) {
            return startingWith(List.of("presets"), args[1]);
        }
        if ("cast".equals(sub) && args.length == 2) {
            List<String> names = new ArrayList<>();
            names.add(STOP);
            for (ContentId id : catalogue.ids()) {
                names.add(id.toString());
            }
            return startingWith(names, args[1]);
        }
        if ("cast".equals(sub) && args.length == 3 && STOP.equalsIgnoreCase(args[1])) {
            List<String> out = new ArrayList<>(List.of("all", "tag:"));
            out.addAll(Targets.names(sender, "cast"));
            return startingWith(out, args[2]);
        }
        if ("cast".equals(sub) && args.length >= 4 && AT.equalsIgnoreCase(args[2])) {
            if (args.length <= 6) {
                return startingWith(List.of("~"), args[args.length - 1]);
            }
            List<String> out = new ArrayList<>(OPTIONS);
            out.removeAll(List.of("follow", "only:caster"));
            if (args.length == 7) {
                for (World world : Bukkit.getWorlds()) {
                    out.add(world.getName());
                }
            }
            return startingWith(out, args[args.length - 1]);
        }
        if ("cast".equals(sub) && args.length >= 3 && !STOP.equalsIgnoreCase(args[1])) {
            List<String> out = new ArrayList<>(OPTIONS);
            if (args.length == 3) {
                out.addAll(Targets.names(sender, "cast"));
                if (sender.hasPermission(AT_PERMISSION)) {
                    out.add(AT);
                }
            }
            return startingWith(out, args[args.length - 1]);
        }
        return List.of();
    }

    private static List<String> startingWith(List<String> options, String typed) {
        String prefix = typed.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
