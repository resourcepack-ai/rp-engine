package ai.resourcepack.engine.core.command;

import ai.resourcepack.engine.api.ContentEntry;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.ContentRegistry;
import ai.resourcepack.engine.api.ItemInfo;
import ai.resourcepack.engine.api.Items;
import ai.resourcepack.engine.api.VehicleInfo;
import ai.resourcepack.engine.core.edit.EditSessions;
import ai.resourcepack.engine.core.vehicle.VehicleRuntime;

import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Editing content in a browser: {@code /rp edit <id>}.
 *
 * <p><strong>One argument, and it works out which editor.</strong> That is not
 * a convenience layered over three commands — it is what the id scheme already
 * guarantees: an id is unique across the whole registry, so
 * {@code mypack:chair} is exactly one thing and asking somebody to also say
 * what kind of thing it is asks them to repeat something the server already
 * knows.
 *
 * <p>The one place a kind maps to two editors is an item, and it is not a
 * choice: an item with a {@code model:} is a 3D model whose art is edited from
 * inside the model editor's paint mode, and an item without one is a flat
 * sprite that has nothing but a texture. So each has exactly one door.
 *
 * <p>A kind with no editor is answered by name — "that is a sound" — rather
 * than by a list of what does work. Somebody who typed a sound id knows what a
 * sound is; what they need is to be told this cannot open one.
 *
 * <p>The command does almost nothing itself. Resolving the files and talking to
 * the editor both happen off the main thread inside {@link EditSessions}, which
 * is why every branch here ends by handing over rather than by answering: the
 * reply arrives when the link does.
 */
public final class EditCommands implements Area {

    private final EditSessions sessions;
    private final ContentRegistry registry;
    private final Items items;
    private final VehicleRuntime vehicles;
    private final boolean enabled;

    public EditCommands(EditSessions sessions, ContentRegistry registry, Items items,
                        VehicleRuntime vehicles, boolean enabled) {
        this.sessions = sessions;
        this.registry = registry;
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
                Help.of("edit", "<id>", "open it in the editor in your browser"),
                Help.of("edit", "close", "end the editor links you have open"));
    }

    @Override
    public List<String> signatures() {
        // Three commands over two lines of help: `edit` on its own lists what
        // is open, and the default derivation cannot see a command that is
        // only a verb. See Area#signatures.
        return List.of("edit", "edit <id>", "edit close");
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

        String raw = args[1];
        // `close` can never be an id — ContentId refuses anything without a
        // colon — so there is nothing to disambiguate here.
        if (raw.equalsIgnoreCase("close")) {
            int closed = sessions.close(sender);
            Reply.to(sender, closed == 0
                    ? "You have no editor links open."
                    : "Closed " + Reply.plural(closed, "editor link") + ".");
            return true;
        }

        Optional<ContentId> id = ContentId.parse(raw);
        if (id.isEmpty()) {
            Reply.error(sender, raw + " is not a namespace:id.");
            return true;
        }
        Optional<ContentEntry> entry = registry.entry(id.get());
        if (entry.isEmpty()) {
            Reply.error(sender, "Nothing on this server is called " + id.get()
                    + ". " + Reply.accent("/rp items") + " and " + Reply.accent("/rp vehicles")
                    + " list what can be edited.");
            return true;
        }
        return open(sender, id.get(), entry.get().kind());
    }

    @Override
    public List<String> complete(CommandSender sender, String sub, String[] args) {
        if (args.length != 2) {
            return List.of();
        }
        // Only what this can actually open. Completing an id that is about to
        // be refused is worse than completing nothing: it reads as an offer.
        List<ContentId> editable = new ArrayList<>(items.ids());
        editable.addAll(vehicles.ids());
        List<String> options = new ArrayList<>(Completions.matchingIds(args[1], editable));
        options.addAll(Completions.matching(args[1], "close"));
        return options;
    }

    private boolean list(CommandSender sender) {
        List<String> open = sessions.describeOpen(sender);
        if (open.isEmpty()) {
            Reply.to(sender, "Nothing open. " + Reply.accent("/rp edit <id>")
                    + " opens it in the editor in your browser; your changes come back "
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

    /** Whichever editor this kind of content belongs in. */
    private boolean open(CommandSender sender, ContentId id, ContentKind kind) {
        switch (kind) {
            case ITEM:
                return item(sender, id);
            case VEHICLE:
                return vehicle(sender, id);
            default:
                Reply.error(sender, id + " is " + article(kind) + ", and there is no editor "
                        + "for one yet. Items and vehicles are what this can open.");
                return true;
        }
    }

    private boolean item(CommandSender sender, ContentId id) {
        Optional<ItemInfo> info = items.info(id);
        if (info.isEmpty()) {
            // Registered but unparsed: the definition failed to load. The
            // startup report said why, and the fix is in the file rather than
            // in a browser.
            Reply.error(sender, id + " did not load. /rp reload and check the console.");
            return true;
        }
        // A 3D item goes to the model editor, whose paint mode holds the pixel
        // editor anyway; a flat sprite has no model to open, so it goes
        // straight to the pixels. Neither is a preference.
        if (info.get().model().isPresent()) {
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
            Reply.error(sender, id + " did not load. /rp reload and check the console.");
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

    /** "a sound", "an entity" — for a sentence rather than for a table. */
    private static String article(ContentKind kind) {
        String noun = noun(kind);
        return ("aeiou".indexOf(noun.charAt(0)) >= 0 ? "an " : "a ") + noun;
    }

    private static String noun(ContentKind kind) {
        return kind.name().toLowerCase(Locale.ROOT);
    }
}
