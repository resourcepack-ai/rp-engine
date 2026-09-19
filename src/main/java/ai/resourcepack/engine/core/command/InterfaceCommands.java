package ai.resourcepack.engine.core.command;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.core.font.IconsImpl;
import ai.resourcepack.engine.core.font.Overlays;
import ai.resourcepack.engine.core.sound.SoundsImpl;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * What a player hears and sees that is not the world: {@code sounds},
 * {@code sound}, {@code icons}, {@code say}, {@code screens}, {@code screen},
 * {@code hud}.
 *
 * <p>The listing halves are here rather than beside the content commands
 * because they exist to be used with the playing halves — you run
 * {@code /rp icons} to find the one you are about to put in a {@code /rp say}.
 *
 * <p>The three that draw something take an optional player, because they have
 * two callers: somebody testing their own pack, and Studio relaying the same
 * command through the console. See {@link Targets}.
 */
public final class InterfaceCommands implements Area {

    /** The ones that draw something on one client, and so take a player. */
    private static final List<String> DRAWS = List.of("sound", "screen", "hud", "dialog");

    private final SoundsImpl sounds;
    private final IconsImpl icons;
    private final Overlays overlays;
    private final ai.resourcepack.engine.api.Dialogs dialogs;
    private final ai.resourcepack.engine.core.font.OverlayRuntime runtime;

    public InterfaceCommands(SoundsImpl sounds, IconsImpl icons, Overlays overlays,
                             ai.resourcepack.engine.core.font.OverlayRuntime runtime,
                             ai.resourcepack.engine.api.Dialogs dialogs) {
        this.sounds = sounds;
        this.icons = icons;
        this.overlays = overlays;
        this.runtime = runtime;
        this.dialogs = dialogs;
    }

    @Override
    public String title() {
        return "Sound and interface";
    }

    @Override
    public List<Help> help() {
        return List.of(
                Help.of("sounds", "list the custom sounds"),
                Help.of("sound", "<id> [player]", "play one"),
                Help.of("icons", "list icons and their characters"),
                Help.of("say", "<text>", "text with :pack:icon: in it"),
                Help.of("screens", "list the screens and HUDs"),
                Help.of("screen", "<id> [player]", "open a screen"),
                Help.of("hud", "<id|clear> [player]", "show or clear one"),
                Help.of("dialogs", "list the dialogs"),
                Help.of("dialog", "<id> [player]", "open one (1.21.6+)"));
    }

    @Override
    public boolean run(CommandSender sender, String sub, String[] args) {
        switch (sub) {
            case "sounds":
                return sounds(sender);
            case "sound":
                return sound(sender, args);
            case "icons":
                return icons(sender);
            case "say":
                return say(sender, args);
            case "screens":
                return screens(sender);
            case "screen":
                return screen(sender, args);
            case "dialogs":
                return dialogs(sender);
            case "dialog":
                return dialog(sender, args);
            default:
                return hud(sender, args);
        }
    }

    @Override
    public List<String> complete(CommandSender sender, String sub, String[] args) {
        if (args.length == 3 && DRAWS.contains(sub)) {
            List<String> online = new ArrayList<>();
            for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
                online.add(player.getName());
            }
            return Completions.matching(args[2], online);
        }
        if (args.length != 2) {
            return List.of();
        }
        switch (sub) {
            case "sound":
                return Completions.matchingIds(args[1], sounds.ids());
            case "screen":
                return Completions.matchingIds(args[1], overlays.screenIds());
            case "dialog":
                return Completions.matchingIds(args[1], dialogs.ids());
            case "hud": {
                List<String> options = new ArrayList<>(Completions.matchingIds(args[1], overlays.hudIds()));
                options.addAll(Completions.matching(args[1], "clear"));
                return options;
            }
            case "say":
                // Every icon as a ready-made placeholder, because the colons
                // are the part people get wrong.
                return Completions.matchingIds(args[1], icons.ids()).stream()
                        .map(id -> ":" + id + ":").toList();
            default:
                return List.of();
        }
    }

    private boolean sounds(CommandSender sender) {
        if (sounds.ids().isEmpty()) {
            Reply.to(sender, "No sounds loaded. A pack declares them in sounds/.");
            return true;
        }
        Reply.heading(sender, "Sounds", Reply.plural(sounds.ids().size(), "sound")
                + ", /rp sound <id> to hear one");
        for (ContentId id : sounds.ids()) {
            Reply.row(sender, id.toString(), sounds.info(id)
                    .map(sound -> sound.category()
                            + sound.subtitle().map(text -> " · \"" + text + "\"").orElse(""))
                    .orElse(""));
        }
        return true;
    }

    private boolean sound(CommandSender sender, String[] args) {
        if (args.length < 2) {
            Reply.to(sender, "/rpengine sound <id> [player]");
            return true;
        }
        Player target = Targets.of(sender, args.length > 2 ? args[2] : null);
        if (target == null) {
            Reply.to(sender, "Name a player: /rpengine sound <id> <player>");
            return true;
        }
        boolean played = ContentId.parse(args[1]).map(id -> sounds.play(target, id))
                .orElse(Boolean.FALSE);
        if (!played) {
            Reply.to(sender, "No sound called " + args[1] + ".");
            return true;
        }
        // The client silently drops a sound it does not have, so a bare
        // "played" is a half-truth worth completing.
        Reply.to(sender, "Played " + args[1] + " to " + target.getName()
                + ". Heard nothing? The pack has to be on before the sound is in it.");
        return true;
    }

    private boolean icons(CommandSender sender) {
        if (icons.ids().isEmpty()) {
            Reply.to(sender, "No icons loaded. A pack declares them in fonts/.");
            return true;
        }
        Reply.heading(sender, "Icons", Reply.plural(icons.ids().size(), "icon")
                + ", write one as :namespace:id:");
        for (ContentId id : icons.ids()) {
            // The character itself, so somebody can see it rendered right
            // there beside the id it came from.
            Reply.row(sender, id.toString(),
                    icons.character(id).orElse("?") + " · :" + id + ":");
        }
        return true;
    }

    private boolean say(CommandSender sender, String[] args) {
        // Proof the placeholder works in ordinary text, which is the whole
        // point of putting the glyphs in the default font.
        if (args.length < 2) {
            Reply.to(sender, "/rpengine say <text with :namespace:id: in it>");
            return true;
        }
        sender.sendMessage(icons.format(String.join(" ", Arrays.copyOfRange(args, 1, args.length))));
        return true;
    }

    private boolean screens(CommandSender sender) {
        for (ContentId id : overlays.screenIds()) {
            Reply.to(sender, id + "  " + overlays.screen(id).map(o -> o.container()).orElse("?"));
        }
        for (ContentId id : overlays.hudIds()) {
            Reply.to(sender, id + "  "
                    + overlays.hud(id).map(o -> o.slot().name().toLowerCase(Locale.ROOT)).orElse("?"));
        }
        if (overlays.screenIds().isEmpty() && overlays.hudIds().isEmpty()) {
            Reply.to(sender, "No screens or HUDs loaded.");
        }
        return true;
    }

    private boolean screen(CommandSender sender, String[] args) {
        if (args.length < 2) {
            Reply.to(sender, "/rpengine screen <id> [player]");
            return true;
        }
        Player target = Targets.of(sender, args.length > 2 ? args[2] : null);
        if (target == null) {
            Reply.to(sender, "Name a player: /rpengine screen <id> <player>");
            return true;
        }
        if (ContentId.parse(args[1]).flatMap(id -> overlays.open(target, id)).isEmpty()) {
            Reply.to(sender, "No screen called " + args[1] + ".");
        }
        return true;
    }

    private boolean dialogs(CommandSender sender) {
        if (dialogs.ids().isEmpty()) {
            Reply.to(sender, "No dialogs loaded. A pack declares them in dialogs/.");
            return true;
        }
        Reply.heading(sender, "Dialogs", Reply.plural(dialogs.ids().size(), "dialog")
                + ", /rp dialog <id> to open one");
        for (ContentId id : dialogs.ids()) {
            Reply.row(sender, id.toString(), dialogs.info(id).map(d -> d.name()).orElse(""));
        }
        // Both of these are things somebody would otherwise find out by a
        // command doing nothing, which is the failure this whole listing
        // exists to prevent.
        if (!dialogs.supported()) {
            Reply.to(sender, "This server is older than 1.21.6, so none of these will open.");
        } else if (dialogs.pending()) {
            Reply.to(sender, "Run /minecraft:reload (or restart) — dialogs are datapack data "
                    + "and the server reads it before plugins start.");
        }
        return true;
    }

    private boolean dialog(CommandSender sender, String[] args) {
        if (args.length < 2) {
            Reply.to(sender, "/rpengine dialog <id> [player]");
            return true;
        }
        Player target = Targets.of(sender, args.length > 2 ? args[2] : null);
        if (target == null) {
            Reply.to(sender, "Name a player: /rpengine dialog <id> <player>");
            return true;
        }
        boolean shown = ContentId.parse(args[1]).map(id -> dialogs.show(target, id)).orElse(Boolean.FALSE);
        if (shown) {
            return true;
        }
        // Three ways to fail and they want different answers — a version, a
        // reload, or a name. Saying "no dialog called that" to somebody on
        // 1.21.5 sends them looking for a typo that is not there.
        if (!dialogs.supported()) {
            Reply.to(sender, "Dialogs need Minecraft 1.21.6. Use a screen instead: /rp screens.");
        } else if (ContentId.parse(args[1]).flatMap(dialogs::info).isEmpty()) {
            Reply.to(sender, "No dialog called " + args[1] + ".");
        } else if (dialogs.pending()) {
            Reply.to(sender, "That dialog is on disk but not yet loaded. "
                    + "Run /minecraft:reload (or restart) and try again.");
        } else {
            Reply.to(sender, "Couldn't open " + args[1] + " for " + target.getName()
                    + ". A pushed dialog only opens for the player holding that pack.");
        }
        return true;
    }

    private boolean hud(CommandSender sender, String[] args) {
        if (args.length < 2) {
            Reply.to(sender, "/rpengine hud <id|clear> [player]");
            return true;
        }
        Player target = Targets.of(sender, args.length > 2 ? args[2] : null);
        if (target == null) {
            Reply.to(sender, "Name a player: /rpengine hud <id|clear> <player>");
            return true;
        }
        if (args[1].equalsIgnoreCase("clear")) {
            // Both halves: the boss bar this engine may be holding, and
            // whatever the player is WEARING on the action bar.
            runtime.hideAll(target);
            overlays.clear(target);
            Reply.to(sender, "Cleared.");
            return true;
        }
        // Shown rather than sent. The action bar fades after about three
        // seconds, so a one-shot draw is a picture that vanishes — which is
        // what this command used to do, against an enum whose javadoc has
        // always said an overlay is "redrawn while shown". `clear` is how it
        // comes off.
        boolean drawn = ContentId.parse(args[1]).map(id -> runtime.show(target, id))
                .orElse(Boolean.FALSE);
        if (!drawn) {
            Reply.to(sender, "No HUD called " + args[1] + ".");
        }
        return true;
    }
}
