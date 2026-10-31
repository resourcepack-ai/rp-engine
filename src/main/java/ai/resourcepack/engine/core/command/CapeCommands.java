package ai.resourcepack.engine.core.command;

import ai.resourcepack.engine.core.cape.Cape;
import ai.resourcepack.engine.core.cape.CapeCatalogue;
import ai.resourcepack.engine.core.cape.CapeWardrobe;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Capes: {@code cape}.
 *
 * <p>{@code wear} is what Studio's Wear in game button runs, relayed through
 * the console as {@code cape wear studio:<id> @p}, the same shape as
 * {@code armor wear}. A cape from a content folder is named by its id,
 * {@code myserver:founder}, and may name a permission of its own that a
 * player needs to pick it for themselves; putting one on somebody else
 * ({@code rpengine.cape.others}) is a staff decision and checks none.
 */
public final class CapeCommands implements Area {

    private final CapeCatalogue capes;
    private final CapeWardrobe wardrobe;
    /** Null where the server cannot draw one; the command then says why. */
    private final String unsupported;

    public CapeCommands(CapeCatalogue capes, CapeWardrobe wardrobe, String unsupported) {
        this.capes = capes;
        this.wardrobe = wardrobe;
        this.unsupported = unsupported;
    }

    @Override
    public String title() {
        return "Capes";
    }

    @Override
    public List<Help> help() {
        return List.of(
                Help.of("cape", "list the capes you can wear"),
                Help.of("cape", "wear <cape> [player]", "put a cape on"),
                Help.of("cape", "off [player]", "take it off"),
                Help.of("cape", "self", "hide or show your own, in F5"));
    }

    @Override
    public List<String> signatures() {
        return List.of("cape", "cape wear <cape>", "cape off", "cape self", "cape list");
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
                return wear(sender, args);
            case "off":
            case "remove":
                return off(sender, args);
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
            return Completions.matching(args[1], "wear", "off", "self", "list");
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("wear")) {
            List<String> ids = new ArrayList<>();
            for (Cape cape : capes.all().values()) {
                if (!(sender instanceof Player) || CapeWardrobe.mayChoose((Player) sender, cape)
                        || Targets.mayTargetOthers(sender, "cape")) {
                    ids.add(cape.label());
                }
            }
            return Completions.matching(args[2], ids);
        }
        if ((args.length == 4 && args[1].equalsIgnoreCase("wear"))
                || (args.length == 3 && args[1].equalsIgnoreCase("off"))) {
            return Completions.matching(args[args.length - 1], Targets.names(sender, "cape"));
        }
        return List.of();
    }

    private boolean list(CommandSender sender) {
        Map<String, Cape> known = capes.all();
        List<Cape> offered = new ArrayList<>();
        for (Cape cape : known.values()) {
            if (!(sender instanceof Player) || CapeWardrobe.mayChoose((Player) sender, cape)) {
                offered.add(cape);
            }
        }
        if (offered.isEmpty()) {
            Reply.note(sender, known.isEmpty()
                    ? "No capes yet. Sync a Studio pack that has some, or add one to a content folder's capes/."
                    : "There are no capes you can wear yet.");
            return true;
        }
        Reply.heading(sender, "Capes", Reply.plural(offered.size(), "cape"));
        Cape on = sender instanceof Player ? wardrobe.worn((Player) sender) : null;
        for (Cape cape : offered) {
            Reply.row(sender, cape.label(), cape.name() + (cape.equals(on) ? " (wearing)" : ""));
        }
        Reply.note(sender, "Put one on with /rp cape wear <cape>, take it off with /rp cape off.");
        return true;
    }

    private boolean wear(CommandSender sender, String[] args) {
        if (args.length < 3) {
            Reply.error(sender, "Which cape? /rp cape wear <cape> [player]");
            return true;
        }
        Cape cape = capes.find(args[2]);
        if (cape == null) {
            Reply.error(sender, "No cape called " + Reply.accent(args[2]) + ". /rp cape lists them.");
            return true;
        }
        Player player = Targets.of(sender, args.length >= 4 ? args[3] : null);
        if (player == null) {
            Reply.error(sender, args.length >= 4 ? "Nobody called " + args[3] + " is online." : "Name a player.");
            return true;
        }
        if (!Targets.permitted(sender, "cape", player)) {
            Targets.refuse(sender, "cape", "put a cape on");
            return true;
        }
        boolean forSelf = sender instanceof Player && ((Player) sender).getUniqueId().equals(player.getUniqueId());
        if (forSelf && !CapeWardrobe.mayChoose(player, cape)) {
            Reply.error(sender, "You can't wear " + Reply.accent(cape.name()) + " yet.");
            return true;
        }
        wardrobe.wear(player, cape);
        Reply.to(player, "You're wearing " + Reply.accent(cape.name()) + ".");
        if (sender != player) {
            Reply.to(sender, "Put " + cape.name() + " on " + player.getName() + ".");
        }
        return true;
    }

    private boolean off(CommandSender sender, String[] args) {
        Player player = Targets.of(sender, args.length >= 3 ? args[2] : null);
        if (player == null) {
            Reply.error(sender, args.length >= 3 ? "Nobody called " + args[2] + " is online." : "Name a player.");
            return true;
        }
        if (!Targets.permitted(sender, "cape", player)) {
            Targets.refuse(sender, "cape", "take a cape off");
            return true;
        }
        if (!wardrobe.remove(player)) {
            Reply.note(sender, sender == player ? "You're not wearing a cape." : player.getName() + " isn't wearing a cape.");
            return true;
        }
        Reply.to(player, "Your cape is off.");
        if (sender != player) {
            Reply.to(sender, "Took " + player.getName() + "'s cape off.");
        }
        return true;
    }

    private boolean self(CommandSender sender) {
        if (!(sender instanceof Player)) {
            Reply.error(sender, "Only a player has a cape to see.");
            return true;
        }
        boolean now = wardrobe.toggleOwn((Player) sender);
        Reply.to(sender, now
                ? "You'll see your own cape again, in F5. /rp cape self hides it."
                : "Your cape is hidden from you. Everyone else still sees it. /rp cape self brings it back.");
        return true;
    }
}
