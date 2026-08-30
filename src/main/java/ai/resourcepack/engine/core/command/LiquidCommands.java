package ai.resourcepack.engine.core.command;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.core.liquid.LiquidBiomes;
import ai.resourcepack.engine.core.liquid.LiquidPools;
import ai.resourcepack.engine.core.liquid.Liquids;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Marking out where a custom liquid is: {@code /rp liquid}.
 *
 * <p>Two corners and a name, because a pool is a box and a box is two corners.
 * Deliberately its own tiny selection rather than asking for a region plugin:
 * needing WorldEdit installed to mark out a pond would make this a feature
 * most servers cannot use.
 */
public final class LiquidCommands implements Area {

    private final Liquids liquids;
    private final LiquidPools pools;
    private final LiquidBiomes biomes;
    private final Logger log;

    /** Where each player's selection starts. Lost on restart, which is fine. */
    private final Map<UUID, int[]> corners = new ConcurrentHashMap<>();

    public LiquidCommands(Liquids liquids, LiquidPools pools, LiquidBiomes biomes, Logger log) {
        this.liquids = liquids;
        this.pools = pools;
        this.biomes = biomes;
        this.log = log;
    }

    @Override
    public String title() {
        return "Liquids";
    }

    @Override
    public List<Help> help() {
        return List.of(
                Help.of("liquid corner", "mark one corner of a pool"),
                Help.of("liquid fill", "<id>", "fill to where you stand"),
                Help.of("liquid clear", "remove the pool you are in"));
    }

    @Override
    public boolean run(CommandSender sender, String sub, String[] args) {
        if (!(sender instanceof Player)) {
            Reply.to(sender, "/rpengine liquid, as a player.");
            return true;
        }
        Player player = (Player) sender;
        String verb = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
        Location standing = player.getLocation();
        int[] here = {standing.getBlockX(), standing.getBlockY(), standing.getBlockZ()};

        switch (verb) {
            case "corner":
                corners.put(player.getUniqueId(), here);
                Reply.to(player, "Corner at " + here[0] + " " + here[1] + " " + here[2]
                        + ". Stand at the opposite one and run /rp liquid fill <id>.");
                return true;
            case "fill":
                return fill(player, args, standing, here);
            case "clear":
                return clear(player, standing);
            default:
                return list(player);
        }
    }

    @Override
    public List<String> complete(CommandSender sender, String sub, String[] args) {
        if (args.length == 2) {
            return Completions.matching(args[1], "corner", "fill", "clear", "list");
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("fill")) {
            return Completions.matchingIds(args[2], liquids.ids());
        }
        return List.of();
    }

    private boolean fill(Player player, String[] args, Location standing, int[] here) {
        int[] from = corners.get(player.getUniqueId());
        if (from == null) {
            Reply.to(player, "Mark a corner first with /rp liquid corner.");
            return true;
        }
        if (args.length < 3) {
            Reply.to(player, "/rpengine liquid fill <id>");
            return true;
        }
        Optional<ContentId> id = ContentId.parse(args[2]);
        if (id.isEmpty() || liquids.info(id.get()).isEmpty()) {
            Reply.to(player, "No liquid called " + args[2] + ".");
            return true;
        }
        LiquidPools.Pool pool = pools.add(id.get(), standing.getWorld().getName(), from, here);
        biomes.paint(standing.getWorld(), pool.min()[0], pool.min()[1], pool.min()[2],
                        pool.max()[0], pool.max()[1], pool.max()[2], id.get())
                .ifPresent(was -> pool.remember(was.getKey().toString()));
        pools.save(log);
        corners.remove(player.getUniqueId());
        Reply.to(player, pool + ".");
        Reply.to(player, "The blocks are still ordinary water. This says what being in them means.");
        if (liquids.info(id.get()).map(l -> l.color().isPresent()).orElse(false)
                && biomes.biomeOf(id.get()).isEmpty()) {
            Reply.to(player, "Its colour needs a restart before it shows: a biome is registered "
                    + "when the server starts and a reload cannot add one.");
        }
        return true;
    }

    private boolean clear(Player player, Location standing) {
        Optional<LiquidPools.Pool> gone = pools.removeAt(standing.getWorld().getName(),
                standing.getX(), standing.getY(), standing.getZ());
        // Painted back to whatever was there when the pool was made. A pool
        // that never had a colour has nothing recorded and nothing to undo.
        gone.ifPresent(pool -> pool.was()
                .map(NamespacedKey::fromString)
                .map(Registry.BIOME::get)
                .ifPresent(before -> biomes.restore(standing.getWorld(),
                        pool.min()[0], pool.min()[1], pool.min()[2],
                        pool.max()[0], pool.max()[1], pool.max()[2], before)));
        pools.save(log);
        Reply.to(player, gone.isPresent() ? "Removed " + gone.get() + "." : "No pool here.");
        return true;
    }

    private boolean list(Player player) {
        for (ContentId id : liquids.ids()) {
            Reply.to(player, id + "  "
                    + liquids.info(id).map(l -> l.base().name().toLowerCase(Locale.ROOT)).orElse("?"));
        }
        if (liquids.ids().isEmpty()) {
            Reply.to(player, "No liquids loaded.");
        }
        for (LiquidPools.Pool pool : pools.pools()) {
            Reply.to(player, "pool: " + pool);
        }
        // One command per line rather than three joined by pipes. The joined
        // form read as a single command to the chat linker, which now
        // correctly recognises "/rp liquid corner" at the front of it — and
        // would have offered to RUN it, marking a corner because somebody
        // clicked a usage line. Three lines are also simply easier to read,
        // and each of them is now separately clickable.
        Reply.to(player, "/rpengine liquid corner");
        Reply.to(player, "/rpengine liquid fill <id>");
        Reply.to(player, "/rpengine liquid clear");
        return true;
    }

    /** Forgets a player's half-made selection when they leave. */
    public void forget(UUID playerId) {
        corners.remove(playerId);
    }
}
