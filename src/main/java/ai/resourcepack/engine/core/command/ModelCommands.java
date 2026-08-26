package ai.resourcepack.engine.core.command;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.core.entity.CustomEntities;
import ai.resourcepack.engine.core.model.ModelPlacementListener;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Things standing in the world: {@code models}, {@code purge},
 * {@code entities}, {@code spawn}.
 *
 * <p>Every one of them is about a place, so every one of them refuses a
 * console: "around you" has no meaning typed from a terminal, and answering
 * with the world spawn would be answering a different question.
 */
public final class ModelCommands implements Area {

    private final ModelPlacementListener placements;
    private final CustomEntities creatures;

    public ModelCommands(ModelPlacementListener placements, CustomEntities creatures) {
        this.placements = placements;
        this.creatures = creatures;
    }

    @Override
    public String title() {
        return "In the world";
    }

    @Override
    public List<Help> help() {
        return List.of(
                Help.of("models", "[radius]", "list the placed models around you"),
                Help.of("purge", "[radius]", "remove the ones whose content is gone"),
                Help.of("entities", "list the custom entities"),
                Help.of("spawn", "<id>", "spawn one where you stand"));
    }

    @Override
    public boolean run(CommandSender sender, String sub, String[] args) {
        switch (sub) {
            case "models":
                return models(sender, args);
            case "purge":
                return purge(sender, args);
            case "entities":
                return entities(sender);
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
            default:
                return List.of();
        }
    }

    private boolean models(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            Reply.to(sender, "Only a player can look around them.");
            return true;
        }
        double radius = args.length > 1 ? Args.radius(args[1]) : Args.DEFAULT_RADIUS;
        List<Interaction> found = placements.near(((Player) sender).getLocation(), radius);
        Reply.to(sender, Reply.plural(found.size(), "model") + " within " + (int) radius + " blocks.");
        for (Interaction hitbox : found) {
            Location at = hitbox.getLocation();
            Reply.to(sender, placements.idOf(hitbox).map(Object::toString).orElse("?")
                    + "  " + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ()
                    + (placements.isOrphan(hitbox) ? "  (orphan)" : ""));
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

    private boolean entities(CommandSender sender) {
        if (creatures.ids().isEmpty()) {
            Reply.to(sender, "No entities loaded.");
        }
        for (ContentId id : creatures.ids()) {
            Reply.to(sender, id + "  " + creatures.info(id).map(e -> e.type()).orElse("?"));
        }
        return true;
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
