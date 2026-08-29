package ai.resourcepack.engine.core.command;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.EntityInfo;
import ai.resourcepack.engine.api.Items;
import ai.resourcepack.engine.core.block.BlockStates;
import ai.resourcepack.engine.core.block.CustomBlocks;
import ai.resourcepack.engine.core.entity.CustomEntities;
import ai.resourcepack.engine.core.model.BoundModels;
import ai.resourcepack.engine.core.model.ModelPlacementListener;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Things standing in the world: {@code models}, {@code purge},
 * {@code entities}, {@code spawn}, {@code bind}, {@code unbind}.
 *
 * <p>Every one of them is about a place, so every one of them refuses a
 * console: "around you" has no meaning typed from a terminal, and answering
 * with the world spawn would be answering a different question.
 */
public final class ModelCommands implements Area {

    /** How far ahead {@code bind} will look for something to bind to. */
    private static final double REACH = 12;

    private final ModelPlacementListener placements;
    private final CustomEntities creatures;
    private final BoundModels bound;
    private final Items items;
    private final CustomBlocks customBlocks;
    private final BlockStates states;

    public ModelCommands(ModelPlacementListener placements, CustomEntities creatures,
                         BoundModels bound, Items items, CustomBlocks customBlocks,
                         BlockStates states) {
        this.placements = placements;
        this.creatures = creatures;
        this.bound = bound;
        this.items = items;
        this.customBlocks = customBlocks;
        this.states = states;
    }

    @Override
    public String title() {
        return "In the world";
    }

    @Override
    public List<Help> help() {
        return List.of(
                Help.of("models", "[radius]", "placed models near you"),
                Help.of("blocks", "every custom block, and states left"),
                Help.of("purge", "[radius]", "remove orphaned ones"),
                Help.of("entities", "list the custom entities"),
                Help.of("spawn", "<id>", "spawn one where you stand"),
                Help.of("bind", "<id>", "put a model on the mob you face"),
                Help.of("unbind", "take it off again"));
    }

    @Override
    public boolean run(CommandSender sender, String sub, String[] args) {
        switch (sub) {
            case "blocks":
                return blocks(sender);
            case "models":
                return models(sender, args);
            case "purge":
                return purge(sender, args);
            case "entities":
                return entities(sender);
            case "bind":
                return bind(sender, args);
            case "unbind":
                return unbind(sender);
            default:
                return spawn(sender, args);
        }
    }

    @Override
    public List<String> complete(CommandSender sender, String sub, String[] args) {
        if (args.length != 2) {
            return List.of();
        }
        switch (sub) {
            case "models":
            case "purge":
                // The radii somebody actually wants, rather than nothing at all
                // because the argument happens to be a number.
                return Completions.matching(args[1], "8", "16", "32", "64", "128");
            case "spawn":
                return Completions.matchingIds(args[1], creatures.ids());
            case "bind":
                // Every item id, because any of them may carry a model. The
                // ones that do not are refused by bind itself, which can say
                // why; a completion list that quietly omitted them could not.
                return Completions.matchingIds(args[1], items.ids());
            default:
                return List.of();
        }
    }

    /**
     * Puts a model on whatever the player is looking at.
     *
     * <p>The mob under the crosshair rather than a selector, because this is a
     * command somebody uses to try a model on a thing they can see. A plugin
     * driving this for real uses {@code Models.bind}, and MythicMobs uses the
     * mechanic — both of which take the entity directly and neither of which
     * needs anybody to be looking anywhere.
     */
    private boolean bind(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            Reply.to(sender, "Only a player can look at something.");
            return true;
        }
        if (args.length < 2) {
            Reply.to(sender, "/rpengine bind <id>");
            return true;
        }
        Player player = (Player) sender;
        Optional<ContentId> id = ContentId.parse(args[1]);
        if (id.isEmpty() || items.info(id.get()).isEmpty()) {
            Reply.error(player, args[1] + " is not an item on this server.");
            return true;
        }
        Entity target = lookingAt(player);
        if (target == null) {
            Reply.error(player, "Look at a mob within " + (int) REACH + " blocks.");
            return true;
        }
        if (bound.bind(target, id.get(), 1f)) {
            Reply.to(player, "That " + target.getType().name().toLowerCase(java.util.Locale.ROOT)
                    + " is wearing " + Reply.accent(id.get()) + " now.");
        } else {
            // The one ordinary way this fails: an id that is an item but has
            // no model behind it, so there is nothing to put on anything.
            Reply.error(player, "Nothing to wear. " + args[1] + " has no model.");
        }
        return true;
    }

    private boolean unbind(CommandSender sender) {
        if (!(sender instanceof Player)) {
            Reply.to(sender, "Only a player can look at something.");
            return true;
        }
        Player player = (Player) sender;
        Entity target = lookingAt(player);
        if (target == null) {
            Reply.error(player, "Look at a mob within " + (int) REACH + " blocks.");
            return true;
        }
        Reply.to(player, bound.unbind(target)
                ? "Took it off."
                : "That one is not wearing anything of ours.");
        return true;
    }

    /**
     * The living entity under the crosshair.
     *
     * <p>Deliberately skips our own displays: a bound model's parts are
     * standing in exactly the place the ray goes, so without this the second
     * {@code bind} looks at the model rather than at the mob wearing it.
     */
    private Entity lookingAt(Player player) {
        RayTraceResult hit = player.getWorld().rayTraceEntities(
                player.getEyeLocation(), player.getEyeLocation().getDirection(), REACH, 0.4,
                entity -> entity instanceof LivingEntity && !entity.equals(player));
        return hit == null ? null : hit.getHitEntity();
    }

    private boolean models(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            Reply.to(sender, "Only a player can look around them.");
            return true;
        }
        Player looking = (Player) sender;
        double radius = args.length > 1 ? Args.radius(args[1]) : Args.DEFAULT_RADIUS;
        List<Interaction> found = new ArrayList<>(placements.near(looking.getLocation(), radius));
        if (found.isEmpty()) {
            Reply.to(sender, "No models within " + (int) radius + " blocks.");
            return true;
        }
        // Nearest first, because the one somebody is asking about is almost
        // always the one they are standing in front of.
        found.sort(Comparator.comparingDouble(
                hitbox -> hitbox.getLocation().distanceSquared(looking.getLocation())));

        Reply.heading(sender, "Models", Reply.plural(found.size(), "model")
                + " within " + (int) radius + " blocks");
        for (Interaction hitbox : found) {
            Location at = hitbox.getLocation();
            int away = (int) Math.round(at.distance(looking.getLocation()));
            Reply.row(sender, placements.idOf(hitbox).map(Object::toString).orElse("unknown"),
                    at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ()
                            + " · " + away + "m"
                            + (placements.isOrphan(hitbox) ? " · orphan" : ""));
        }
        return true;
    }

    private boolean purge(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            Reply.to(sender, "Only a player can purge around them.");
            return true;
        }
        Player around = (Player) sender;
        double radius = args.length > 1 ? Args.radius(args[1]) : Args.DEFAULT_RADIUS;
        int removed = 0;
        for (Interaction hitbox : placements.near(around.getLocation(), radius)) {
            // Orphans only. Purging models a pack still defines would be a
            // demolition command wearing a cleanup command's name.
            if (!placements.isOrphan(hitbox)) {
                continue;
            }
            placements.idOf(hitbox).ifPresent(id -> placements.remove(hitbox, id, around, false));
            removed++;
        }
        Reply.to(sender, "Removed " + Reply.plural(removed, "orphan") + " within " + (int) radius
                + " blocks. Models a pack still defines were left alone.");
        return true;
    }

    /**
     * {@code /rp blocks}: what is defined, and how much of the pool is left.
     *
     * <p>The remaining count is the point of the command. A custom block takes
     * one of a finite number of vanilla states, and an owner should be able to
     * see how close they are to the end before a block silently fails to be
     * placeable.
     */
    private boolean blocks(CommandSender sender) {
        if (customBlocks.ids().isEmpty()) {
            Reply.to(sender, "No custom blocks loaded. A pack declares them in blocks/.");
            return true;
        }
        Reply.heading(sender, "Custom blocks",
                Reply.plural(customBlocks.ids().size(), "block"));
        for (ContentId id : customBlocks.ids()) {
            customBlocks.info(id).ifPresent(block -> Reply.row(sender, id.toString(),
                    block.base().name().toLowerCase(Locale.ROOT)
                            + " · hardness " + block.hardness()
                            + (block.light() > 0 ? " · light " + block.light() : "")));
        }
        for (ai.resourcepack.engine.api.BlockInfo.Base base
                : ai.resourcepack.engine.api.BlockInfo.Base.values()) {
            Reply.note(sender, base.name().toLowerCase(Locale.ROOT) + ": "
                    + states.remaining(base) + " of " + BlockStates.capacity(base) + " states left");
        }
        return true;
    }

    private boolean entities(CommandSender sender) {
        if (creatures.ids().isEmpty()) {
            Reply.to(sender, "No entities loaded. A pack declares them in entities/.");
            return true;
        }
        Reply.heading(sender, "Entities", Reply.plural(creatures.ids().size(), "kind")
                + ", spawn one with /rp spawn <id>");
        for (ContentId id : creatures.ids()) {
            Reply.row(sender, id.toString(), creatures.info(id).map(ModelCommands::describe).orElse(""));
        }
        return true;
    }

    /** "ZOMBIE - 40 hp - 1.2x - wearing mypack:golem", with the empty parts left out. */
    private static String describe(EntityInfo entity) {
        StringBuilder said = new StringBuilder(entity.type().toLowerCase(Locale.ROOT));
        if (entity.health() > 0) {
            said.append(" · ").append(trim(entity.health())).append(" hp");
        }
        if (entity.scale() != 1f) {
            said.append(" · ").append(trim(entity.scale())).append("x");
        }
        entity.model().ifPresent(model -> said.append(" · ").append(model));
        return said.toString();
    }

    /** 40 rather than 40.0, since a whole number of hearts is the usual case. */
    private static String trim(double value) {
        return value == Math.rint(value)
                ? String.valueOf((long) value)
                : String.valueOf(Math.round(value * 10) / 10.0);
    }

    private boolean spawn(CommandSender sender, String[] args) {
        if (args.length < 2 || !(sender instanceof Player)) {
            Reply.to(sender, "/rpengine spawn <id>, as a player.");
            return true;
        }
        Location at = ((Player) sender).getLocation();
        boolean spawned = ContentId.parse(args[1]).flatMap(id -> creatures.spawn(at, id)).isPresent();
        Reply.to(sender, spawned ? "Spawned " + args[1] + "." : "No entity called " + args[1] + ".");
        return true;
    }
}
