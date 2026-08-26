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

    /** The three that draw something on one client, and so take a player. */
    private static final List<String> DRAWS = List.of("sound", "screen", "hud");

    private final SoundsImpl sounds;
    private final IconsImpl icons;
    private final Overlays overlays;

    public InterfaceCommands(SoundsImpl sounds, IconsImpl icons, Overlays overlays) {
        this.sounds = sounds;
        this.icons = icons;
        this.overlays = overlays;
    }

    @Override
    public List<String> subcommands() {
        return List.of("sounds", "sound", "icons", "say", "screens", "screen", "hud");
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
            Reply.to(sender, "No sounds loaded.");
        }
        for (ContentId id : sounds.ids()) {
            Reply.to(sender, id + "  " + sounds.info(id).map(s -> s.category()).orElse("?"));
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
            Reply.to(sender, "No icons loaded.");
        }
        for (ContentId id : icons.ids()) {
            Reply.to(sender, id + "  " + icons.character(id).orElse("?") + "  :" + id + ":");
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
            overlays.clear(target);
            Reply.to(sender, "Cleared.");
            return true;
        }
        boolean drawn = ContentId.parse(args[1]).map(id -> overlays.draw(target, id))
                .orElse(Boolean.FALSE);
        if (!drawn) {
            Reply.to(sender, "No HUD called " + args[1] + ".");
        }
        return true;
    }
}
