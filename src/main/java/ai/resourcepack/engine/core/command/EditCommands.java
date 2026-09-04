package ai.resourcepack.engine.core.command;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ItemInfo;
import ai.resourcepack.engine.api.Items;
import ai.resourcepack.engine.api.VehicleInfo;
import ai.resourcepack.engine.core.edit.EditSessions;
import ai.resourcepack.engine.core.vehicle.Vehicles;

import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Editing content in a browser: {@code /rp edit}.
 *
 * <p>One verb with three nouns after it, because the three are three different
 * editors and picking the right one for you is not something a command can do:
 * an item has a sprite <em>and</em> a model, and "edit the sword" would have to
 * guess which of them somebody meant.
 *
 * <p>The command does almost nothing itself. Resolving the files and talking to
 * the editor both happen off the main thread inside {@link EditSessions}, which
 * is why every method here ends by handing over rather than by answering: the
 * reply arrives when the link does.
 */
public final class EditCommands implements Area {

    private final EditSessions sessions;
    private final Items items;
    private final Vehicles vehicles;
    private final boolean enabled;

    public EditCommands(EditSessions sessions, Items items, Vehicles vehicles, boolean enabled) {
        this.sessions = sessions;
        this.items = items;
        this.vehicles = vehicles;
        this.enabled = enabled;
    }

    @Override
    public String title() {
        return "Editing";
    }

    @Override
    public List<Help> help() {
        return List.of(
                Help.of("edit", "model <id>", "open an item's 3D model in the editor"),
                Help.of("edit", "texture <id>", "open an item's texture in the pixel editor"),
                Help.of("edit", "vehicle <id>", "open a vehicle's seats and handling"),
                Help.of("edit", "close", "end the editor links you have open"));
    }

    @Override
    public List<String> signatures() {
        // Four lines share one verb, and one of them ends in a word rather
        // than an argument — the default derivation cannot tell those apart.
        // See Area#signatures.
        return List.of("edit", "edit model <id>", "edit texture <id>", "edit vehicle <id>", "edit close");
    }

    @Override
    public boolean run(CommandSender sender, String sub, String[] args) {
        if (!enabled) {
            Reply.error(sender, "Editing in a browser is switched off in config.yml (edit.enabled).");
            return true;
        }
        if (args.length < 2) {
            return list(sender);
        }
        String noun = args[1].toLowerCase(Locale.ROOT);
        if (noun.equals("close")) {
            int closed = sessions.close(sender);
            Reply.to(sender, closed == 0
                    ? "You have no editor links open."
                    : "Closed " + Reply.plural(closed, "editor link") + ".");
            return true;
        }
        if (args.length < 3) {
            Reply.error(sender, "Which one? /rp edit " + noun + " <id>");
            return true;
        }

        String raw = args[2];
        Optional<ContentId> id = ContentId.parse(raw);
        if (id.isEmpty()) {
            Reply.error(sender, raw + " is not a namespace:id.");
            return true;
        }

        switch (noun) {
            case "model":
                return item(sender, id.get(), true);
            case "texture":
                return item(sender, id.get(), false);
            case "vehicle":
                return vehicle(sender, id.get());
            default:
                Reply.error(sender, noun + " is not something this can edit. "
                        + "Try /rp edit model <id>, /rp edit texture <id> or /rp edit vehicle <id>.");
                return true;
        }
    }

    @Override
    public List<String> complete(CommandSender sender, String sub, String[] args) {
        if (args.length == 2) {
            return Completions.matching(args[1], "model", "texture", "vehicle", "close");
        }
        if (args.length != 3) {
            return List.of();
        }
        String noun = args[1].toLowerCase(Locale.ROOT);
        if (noun.equals("vehicle")) {
            return Completions.matchingIds(args[2], vehicles.ids());
        }
        if (noun.equals("model") || noun.equals("texture")) {
            return Completions.matchingIds(args[2], items.ids());
        }
        return List.of();
    }

    private boolean list(CommandSender sender) {
        List<String> open = sessions.describeOpen(sender);
        if (open.isEmpty()) {
            Reply.to(sender, "Nothing open. " + Reply.accent("/rp edit model <id>")
                    + " opens a model in the editor in your browser; your changes come back "
                    + "to this server when you press Send to server.");
            return true;
        }
        Reply.heading(sender, "Editor links", Reply.plural(open.size(), "session"));
        for (String line : open) {
            Reply.row(sender, line, "");
        }
        Reply.note(sender, "/rp edit close ends them.");
        return true;
    }

    private boolean item(CommandSender sender, ContentId id, boolean model) {
        Optional<ItemInfo> info = items.info(id);
        if (info.isEmpty()) {
            Reply.error(sender, "No item called " + id + ". /rp items lists them.");
            return true;
        }
        if (model) {
            sessions.editModel(sender, info.get());
        } else {
            sessions.editTexture(sender, info.get());
        }
        Reply.to(sender, "Opening " + Reply.accent(id) + "...");
        return true;
    }

    private boolean vehicle(CommandSender sender, ContentId id) {
        Optional<VehicleInfo> info = vehicles.info(id);
        if (info.isEmpty()) {
            Reply.error(sender, "No vehicle called " + id + ". /rp vehicles lists them.");
            return true;
        }
        // The bodywork, so the seats can be placed against something. Absent is
        // not an error here: a vehicle whose model is missing still has seats
        // worth moving, and the editor draws an empty scene rather than
        // refusing to open.
        ItemInfo model = info.get().model().flatMap(items::info).orElse(null);
        sessions.editVehicle(sender, info.get(), model);
        Reply.to(sender, "Opening " + Reply.accent(id) + "...");
        return true;
    }
}
