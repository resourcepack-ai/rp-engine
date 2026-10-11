package ai.resourcepack.engine.core.command;

import ai.resourcepack.engine.core.armor3d.Armor3dCatalogue;
import ai.resourcepack.engine.core.armor3d.Armor3dSet;
import ai.resourcepack.engine.core.armor3d.Wardrobe;
import ai.resourcepack.engine.core.armor3d.WornArmour;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 3D armour: {@code armor}.
 *
 * <p>One subcommand with four verbs, because they are four ends of one thing:
 * see what there is, put a set on, hand its pieces over, and look at your own.
 * {@code wear} is what Studio's Give in-game button runs, relayed through the
 * console as {@code wear studio:<id> @p} — the {@code studio:} is accepted and
 * dropped, so the command reads like every other one Studio sends. A set from
 * a content folder is named by its id, {@code christmas:santa}, and is listed,
 * worn and handed over exactly like a pushed one; see {@link Armor3dCatalogue}.
 */
public final class Armor3dCommands implements Area {

    private final Armor3dCatalogue sets;
    private final Wardrobe wardrobe;
    private final WornArmour worn;
    /** Null where the server cannot draw one; the command then says why. */
    private final String unsupported;

    public Armor3dCommands(Armor3dCatalogue sets, Wardrobe wardrobe, WornArmour worn,
                           String unsupported) {
        this.sets = sets;
        this.wardrobe = wardrobe;
        this.worn = worn;
        this.unsupported = unsupported;
    }

    @Override
    public String title() {
        return "3D armour";
    }

    @Override
    public List<Help> help() {
        return List.of(
                Help.of("armor", "list the 3D armour sets"),
                Help.of("armor", "wear <set> [player]", "put a whole set on"),
                Help.of("armor", "give <set> [player]", "hand over its pieces"),
                Help.of("armor", "self", "see your own, in F5"));
    }

    @Override
    public List<String> signatures() {
        return List.of("armor", "armor wear <set>", "armor give <set>", "armor self");
    }

    @Override
    public boolean run(CommandSender sender, String sub, String[] args) {
        if (unsupported != null) {
            Reply.error(sender, unsupported);
            return true;
        }
        String verb = args.length >= 2 ? args[1].toLowerCase(java.util.Locale.ROOT) : "list";
        switch (verb) {
            case "wear":
            case "give":
                return hand(sender, verb.equals("wear"), args);
            case "self":
                return self(sender);
            case "list":
            default:
                return list(sender);
        }
    }

    @Override
    public List<String> complete(CommandSender sender, String sub, String[] args) {
        if (args.length == 2) {
            return Completions.matching(args[1], "wear", "give", "self", "list");
        }
        if (args.length == 3 && (args[1].equalsIgnoreCase("wear") || args[1].equalsIgnoreCase("give"))) {
            List<String> ids = new ArrayList<>();
            for (String id : sets.all().keySet()) {
                ids.add(Armor3dCatalogue.label(id));
            }
            return Completions.matching(args[2], ids);
        }
        if (args.length == 4) {
            return Completions.matching(args[3], Targets.names(sender, "armor"));
        }
        return List.of();
    }

    private boolean list(CommandSender sender) {
        Map<String, Armor3dSet> known = sets.all();
        if (known.isEmpty()) {
            Reply.note(sender, "No 3D armour yet. Sync a Studio pack that has some, "
                    + "or add a set to a content folder's armor3d/.");
            return true;
        }
        Reply.heading(sender, "3D armour", Reply.plural(known.size(), "set"));
        for (Armor3dSet set : known.values()) {
            Reply.row(sender, Armor3dCatalogue.label(set), set.name());
        }
        return true;
    }

    /** {@code wear} puts the set on; {@code give} puts it in the inventory. */
    private boolean hand(CommandSender sender, boolean wear, String[] args) {
        if (args.length < 3) {
            Reply.error(sender, "Which set? /rp armor " + (wear ? "wear" : "give") + " <set> [player]");
            return true;
        }
        Armor3dSet set = sets.find(args[2]);
        if (set == null) {
            Reply.error(sender, "No 3D armour called " + Reply.accent(args[2]) + ". /rp armor lists them.");
            return true;
        }
        Player player = Targets.of(sender, args.length >= 4 ? args[3] : null);
        if (player == null) {
            Reply.error(sender, args.length >= 4 ? "Nobody called " + args[3] + " is online." : "Name a player.");
            return true;
        }
        if (!Targets.permitted(sender, "armor", player)) {
            Targets.refuse(sender, "armor", wear ? "dress" : "give armour to");
            return true;
        }
        // The same path the API's Armor.wear runs: what they had on goes back
        // in their bag rather than into the void.
        if (wear) {
            wardrobe.wear(player, set);
        } else {
            wardrobe.give(player, set);
        }
        if (wear) {
            Reply.to(player, "You're wearing " + Reply.accent(set.name()) + ".");
            if (!worn.showsSelf(player) && !worn.drawsItself(player, set)) {
                Reply.note(player, "Everyone else sees the whole set; you see the helmet. "
                        + "/rp armor self shows you the rest, in F5.");
            }
        } else {
            Reply.to(player, "Here's " + Reply.accent(set.name()) + ".");
        }
        if (sender != player) {
            Reply.to(sender, (wear ? "Dressed " : "Gave ") + player.getName() + " in " + set.name() + ".");
        }
        return true;
    }

    private boolean self(CommandSender sender) {
        if (!(sender instanceof Player)) {
            Reply.error(sender, "Only a player has a self to see.");
            return true;
        }
        boolean now = worn.toggleSelf((Player) sender);
        Reply.to(sender, now
                ? "You'll see your own 3D armour now, in F5 and in first person. /rp armor self hides it again."
                : "Your own 3D armour is hidden from you again. Everyone else still sees it.");
        return true;
    }
}
