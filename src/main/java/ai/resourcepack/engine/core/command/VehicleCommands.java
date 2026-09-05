package ai.resourcepack.engine.core.command;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.VehicleInfo;
import ai.resourcepack.engine.api.VehicleMedium;
import ai.resourcepack.engine.api.VehicleSeat;
import ai.resourcepack.engine.core.vehicle.VehicleRuntime;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * VehicleRuntime: {@code vehicles} and {@code vehicle}.
 *
 * <p>Two subcommands rather than a verb tree, because there are only two
 * things to do from a console: see what this server has, and put one down or
 * pick one up. Getting in is a click, not a command — a vehicle you had to
 * type at would not be a vehicle.
 */
public final class VehicleCommands implements Area {

    /** How far {@code vehicle remove} will look for one to take away. */
    private static final double REACH = 6;

    private final VehicleRuntime vehicles;

    public VehicleCommands(VehicleRuntime vehicles) {
        this.vehicles = vehicles;
    }

    @Override
    public String title() {
        return "VehicleRuntime";
    }

    @Override
    public List<Help> help() {
        return List.of(
                Help.of("vehicles", "list the vehicles, and who fits in them"),
                Help.of("vehicle", "<id>", "park one where you stand"),
                Help.of("vehicle", "remove", "take away the one nearest you"));
    }

    @Override
    public List<String> signatures() {
        // Two lines share the `vehicle` verb and one of them ends in a word
        // rather than an argument, which the default derivation cannot tell
        // apart — see Area#signatures.
        return List.of("vehicles", "vehicle <id>", "vehicle remove");
    }

    @Override
    public boolean run(CommandSender sender, String sub, String[] args) {
        if ("vehicles".equals(sub)) {
            return list(sender);
        }
        if (args.length >= 2 && "remove".equalsIgnoreCase(args[1])) {
            return remove(sender);
        }
        return spawn(sender, args);
    }

    @Override
    public List<String> complete(CommandSender sender, String sub, String[] args) {
        if (args.length != 2 || !"vehicle".equals(sub)) {
            return List.of();
        }
        List<String> options = new ArrayList<>(Completions.matchingIds(args[1], vehicles.ids()));
        options.addAll(Completions.matching(args[1], "remove"));
        return options;
    }

    private boolean list(CommandSender sender) {
        List<ContentId> ids = new ArrayList<>(vehicles.ids());
        if (ids.isEmpty()) {
            Reply.to(sender, "No vehicles. A vehicle is a folder called vehicles/ in your content "
                    + "folder - see FORMAT.md.");
            return true;
        }
        Reply.to(sender, ids.size() + (ids.size() == 1 ? " vehicle:" : " vehicles:"));
        for (ContentId id : ids) {
            Optional<VehicleInfo> info = vehicles.info(id);
            if (info.isEmpty()) {
                continue;
            }
            VehicleInfo vehicle = info.get();
            int passengers = vehicle.capacity() - 1;
            // The two things that are invisible from outside and are the first
            // question when one of them looks broken: whether a seat is meant
            // to draw nobody, and whether this aircraft needs a run-up. Both
            // arrive on a Studio push, so "did my sync actually land" is
            // otherwise unanswerable without reading a JSON file on the server.
            int hidden = 0;
            for (VehicleSeat seat : vehicle.seats()) {
                if (seat.hidden()) {
                    hidden++;
                }
            }
            String flight = vehicle.medium() == VehicleMedium.AIR && vehicle.flight().needsTakeoffRun()
                    ? ", takes off at " + vehicle.flight().takeoffSpeed()
                    : "";
            Reply.to(sender, "  " + id + " - " + vehicle.medium().key()
                    + ", " + vehicle.speed() + " blocks/s, driver"
                    + (passengers == 0 ? " only" : " and " + passengers
                    + (passengers == 1 ? " passenger" : " passengers"))
                    + flight
                    + (hidden == 0 ? "" : ", " + hidden
                    + (hidden == 1 ? " hidden seat" : " hidden seats")));
        }
        Reply.to(sender, "Park one with /rp vehicle <id>.");
        return true;
    }

    private boolean spawn(CommandSender sender, String[] args) {
        Location at = Places.standingAt(sender);
        if (at == null || args.length < 2) {
            Reply.to(sender, "/rpengine vehicle <id>, as a player.");
            return true;
        }
        Optional<Entity> spawned = ContentId.parse(args[1]).flatMap(id -> vehicles.spawn(at, id));
        if (spawned.isEmpty()) {
            Reply.to(sender, "No vehicle called " + args[1] + ".");
            return true;
        }
        // Adopted straight away rather than waiting for the next chunk load,
        // which for a vehicle parked in a chunk that is already loaded would
        // be never — the seats and the model would not appear until somebody
        // walked far enough away and came back.
        vehicles.adopt(spawned.get());
        ContentId id = ContentId.parse(args[1]).orElse(null);
        VehicleInfo info = id == null ? null : vehicles.info(id).orElse(null);
        Reply.to(sender, info == null
                ? "Parked " + args[1] + "."
                : "Parked " + args[1] + ". Right-click a seat to get in - "
                        + seatSummary(info) + ".");
        return true;
    }

    /** "1 driver seat and 3 passenger seats", in the order they are filled. */
    private static String seatSummary(VehicleInfo info) {
        int passengers = 0;
        for (VehicleSeat seat : info.seats()) {
            if (!seat.isDriver()) {
                passengers++;
            }
        }
        if (passengers == 0) {
            return "it seats the driver only";
        }
        return "it seats a driver and " + passengers
                + (passengers == 1 ? " passenger" : " passengers");
    }

    private boolean remove(CommandSender sender) {
        Location at = Places.standingAt(sender);
        if (at == null) {
            Reply.to(sender, "/rpengine vehicle remove, as a player.");
            return true;
        }
        List<Entity> found = vehicles.near(at, REACH);
        if (found.isEmpty()) {
            Reply.to(sender, "No vehicle within " + (int) REACH + " blocks of you.");
            return true;
        }
        Entity nearest = found.get(0);
        double best = nearest.getLocation().distanceSquared(at);
        for (Entity candidate : found) {
            double distance = candidate.getLocation().distanceSquared(at);
            if (distance < best) {
                best = distance;
                nearest = candidate;
            }
        }
        String name = vehicles.idOf(nearest).map(ContentId::toString).orElse("it");
        vehicles.remove(nearest);
        Reply.to(sender, "Removed " + name + ".");
        return true;
    }

    /**
     * Where the sender is standing, or null for a console.
     *
     * <p>Every command in this area is about a place, and answering a console
     * with the world spawn would be answering a different question — the same
     * rule {@code ModelCommands} states for its own.
     */
    private static final class Places {

        private Places() {
        }

        static Location standingAt(CommandSender sender) {
            return sender instanceof Player ? ((Player) sender).getLocation() : null;
        }
    }
}
