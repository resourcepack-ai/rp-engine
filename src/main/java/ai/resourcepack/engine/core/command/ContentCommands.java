package ai.resourcepack.engine.core.command;

import ai.resourcepack.engine.api.BuiltPack;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ItemInfo;
import ai.resourcepack.engine.api.Items;
import ai.resourcepack.engine.core.recipe.Recipes;
import ai.resourcepack.engine.core.serve.PackHost;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * What this server holds, and how to get hold of it: {@code reload},
 * {@code bundles}, {@code items}, {@code give}, {@code recipes}, {@code push}.
 *
 * <p>The two verbs that <em>do</em> something — rebuilding and re-sending a
 * pack — arrive as callbacks rather than as reachable machinery. Both are the
 * plugin's own lifecycle, and a command class holding the pack builder would
 * be a command class that could half-build one.
 */
public final class ContentCommands implements Area {

    private final Items items;
    private final Supplier<List<BuiltPack>> built;
    private final PackHost host;
    private final Recipes recipes;
    private final Supplier<List<ContentId>> recipeIds;
    private final Consumer<CommandSender> rebuild;
    private final Consumer<Player> push;

    public ContentCommands(Items items, Supplier<List<BuiltPack>> built, PackHost host,
                           Recipes recipes, Supplier<List<ContentId>> recipeIds,
                           Consumer<CommandSender> rebuild, Consumer<Player> push) {
        this.items = items;
        this.built = built;
        this.host = host;
        this.recipes = recipes;
        this.recipeIds = recipeIds;
        this.rebuild = rebuild;
        this.push = push;
    }

    @Override
    public String title() {
        return "Content";
    }

    @Override
    public List<Help> help() {
        return List.of(
                Help.of("reload", "reread content and rebuild"),
                Help.of("items", "list the custom items"),
                Help.of("give", "<id> [n]", "give yourself one"),
                Help.of("recipes", "list the recipes"),
                Help.of("bundles", "the built packs and their sizes"),
                Help.of("push", "send the pack to yourself again"));
    }

    @Override
    public boolean run(CommandSender sender, String sub, String[] args) {
        switch (sub) {
            case "reload":
                rebuild.accept(sender);
                return true;
            case "bundles":
                return bundles(sender);
            case "items":
                return items(sender);
            case "give":
                return give(sender, args);
            case "recipes":
                return recipes(sender);
            default:
                return push(sender);
        }
    }

    @Override
    public List<String> complete(CommandSender sender, String sub, String[] args) {
        if (sub.equals("give")) {
            if (args.length == 2) {
                return Completions.matchingIds(args[1], items.ids());
            }
            if (args.length == 3) {
                return Completions.matching(args[2], "1", "8", "16", "64");
            }
        }
        return List.of();
    }

    private boolean bundles(CommandSender sender) {
        List<BuiltPack> packs = built.get();
        if (packs.isEmpty()) {
            Reply.to(sender, "No bundles built. /rp reload after putting a pack in content/.");
            return true;
        }
        long bytes = packs.stream().mapToLong(BuiltPack::size).sum();
        Reply.heading(sender, "Bundles", Reply.plural(packs.size(), "bundle")
                + ", " + Reply.size(bytes));
        for (BuiltPack pack : packs) {
            Reply.row(sender, pack.bundle(), Reply.plural(pack.entries(), "file")
                    + " · " + Reply.size(pack.size())
                    + " · " + pack.sha1().substring(0, 8));
            Reply.note(sender, host.url(pack.bundle()).orElse("not served"));
        }
        return true;
    }

    private boolean items(CommandSender sender) {
        if (items.ids().isEmpty()) {
            Reply.to(sender, "No items loaded.");
        }
        for (ContentId id : items.ids()) {
            ItemInfo info = items.info(id).orElseThrow();
            Reply.to(sender, id + "  " + info.material()
                    + (info.model().isPresent() ? "  model " + info.model().get() : ""));
        }
        return true;
    }

    private boolean give(CommandSender sender, String[] args) {
        if (args.length < 2) {
            Reply.to(sender, "/rpengine give <id> [amount]");
            return true;
        }
        if (!(sender instanceof Player)) {
            Reply.to(sender, "Only a player can be given an item.");
            return true;
        }
        int amount = args.length > 2 ? Args.amount(args[2]) : 1;
        Optional<ItemStack> stack = ContentId.parse(args[1]).flatMap(id -> items.create(id, amount));
        if (stack.isEmpty()) {
            Reply.to(sender, "No item called " + args[1] + ".");
            return true;
        }
        ((Player) sender).getInventory().addItem(stack.get());
        Reply.to(sender, "Gave " + amount + " " + args[1] + ".");
        return true;
    }

    private boolean recipes(CommandSender sender) {
        if (recipes.size() == 0) {
            Reply.to(sender, "No recipes registered.");
        }
        for (ContentId id : recipeIds.get()) {
            Reply.to(sender, id.toString());
        }
        return true;
    }

    private boolean push(CommandSender sender) {
        if (!(sender instanceof Player)) {
            Reply.to(sender, "Only a player can be pushed a pack.");
            return true;
        }
        push.accept((Player) sender);
        return true;
    }
}
