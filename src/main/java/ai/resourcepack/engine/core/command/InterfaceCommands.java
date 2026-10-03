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
 * {@code hud}, {@code shaders}, {@code shader}, and the dialogs with their two
 * player-run halves, {@code var} and {@code page}.
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
    private static final List<String> DRAWS = List.of("sound", "screen", "hud", "shader", "dialog");

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
                Help.of("shaders", "list the shader objects"),
                Help.of("shader", "<id|clear> [player]", "show one"),
                Help.of("dialogs", "list the dialogs"),
                Help.of("dialog", "<id> [player] [k=v]", "open (1.21.6+)"),
                Help.of("var", "<name> <value>", "set a dialog setting"),
                Help.of("page", "<id>", "turn a dialog to another page"));
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
            case "var":
                return var(sender, args);
            case "page":
                return page(sender, args);
            case "shaders":
                return shaders(sender);
            case "shader":
                return shader(sender, args);
            default:
                return hud(sender, args);
        }
    }

    /**
     * {@code /rp var <name> <value> [player]} — what a click on a bound control
     * in a Studio dialog runs, as the player who clicked it.
     *
     * <p>Every player may run it, because the click is theirs; so it sets only
     * a variable some loaded dialog declares, and only to a value that dialog
     * lists — see {@link ai.resourcepack.engine.core.dialog.DialogVariables}.
     * Then it opens the dialog they were looking at again, which reads their
     * values as it opens and so shows the control in its new state. Silent when
     * it works, because the answer is on their screen; a line when it does not,
     * because a click that does nothing is otherwise a mystery.
     *
     * <p>Naming another player is for staff and tests, and needs what opening a
     * dialog on somebody else needs.
     */
    private boolean var(CommandSender sender, String[] args) {
        if (!(dialogs instanceof ai.resourcepack.engine.core.dialog.DialogsImpl impl) || impl.variables() == null) {
            Reply.to(sender, "Dialog settings are not available on this server.");
            return true;
        }
        if (args.length < 3) {
            Reply.to(sender, "/rp var <name> <value> [player]");
            return true;
        }
        Player target;
        if (args.length > 3) {
            if (!sender.hasPermission(EngineCommand.permissionFor("dialog"))) {
                Reply.to(sender, "You need " + EngineCommand.permissionFor("dialog") + " to change somebody else's settings.");
                return true;
            }
            target = org.bukkit.Bukkit.getPlayerExact(args[3]);
            if (target == null) {
                Reply.to(sender, args[3] + " is not online.");
                return true;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            Reply.to(sender, "Name a player: /rp var <name> <value> <player>");
            return true;
        }
        String name = args[1].toLowerCase(Locale.ROOT);
        String value = args[2];
        if (!impl.declares(name, value)) {
            Reply.to(sender, "No dialog has a setting called " + name + " that can be " + value + ".");
            return true;
        }
        if (!impl.variables().set(target, name, value)) {
            Reply.to(sender, "That setting could not be saved: " + target.getName() + " has as many as a player can keep.");
            return true;
        }
        // On the next tick: the click that ran this may still be closing the
        // dialog on the client, and a dialog opened in the same breath would
        // be the one it closes.
        org.bukkit.plugin.Plugin plugin = org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(InterfaceCommands.class);
        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> impl.reopen(target));
        if (sender != target) {
            Reply.to(sender, "Set " + name + " to " + value + " for " + target.getName() + ".");
        }
        return true;
    }

    /**
     * {@code /rp page <id>} — what a click on a paged dialog runs, as the player
     * who clicked it: a sidebar button, a tab, a "Next" arrow.
     *
     * <p>A page is a dialog of its own (a Studio dialog's second page is
     * {@code studio:<id>.<page>}), so this opens a dialog — but only one the
     * dialog the player was last shown turns to, with a click running exactly
     * this command; see {@link ai.resourcepack.engine.core.dialog.DialogLinks}.
     * That is why every player may run it while {@code /rp dialog} is staff's:
     * it does what the click did and nothing else. The page opens with the
     * values the first one was opened with, so a punish menu about Steve is
     * still about Steve on its second page.
     *
     * <p>Silent when it works, like {@code /rp var}: the answer is the page on
     * their screen. A line when it does not, because a click that does nothing
     * is otherwise a mystery.
     */
    private boolean page(CommandSender sender, String[] args) {
        if (!(dialogs instanceof ai.resourcepack.engine.core.dialog.DialogsImpl impl)) {
            Reply.to(sender, "Dialog pages are not available on this server.");
            return true;
        }
        if (!(sender instanceof Player player)) {
            Reply.to(sender, "Only a player turns a page: it is what a click on a dialog runs. "
                    + "/rp dialog <id> <player> opens one on somebody.");
            return true;
        }
        if (args.length < 2) {
            Reply.to(sender, "/rp page <id>");
            return true;
        }
        java.util.Optional<ContentId> parsed = ContentId.parse(args[1].toLowerCase(Locale.ROOT));
        if (parsed.isEmpty() || !impl.links(player, parsed.get())) {
            // Said the same way whether the id is malformed, unknown or simply
            // not linked: from here all three are "no page of what you were
            // shown", and naming which would tell a player what exists.
            Reply.to(sender, impl.lastShown(player).isPresent()
                    ? "The dialog you were shown has no page called " + args[1] + "."
                    : "Open a dialog first: /rp page turns the one you are looking at to another of its pages.");
            return true;
        }
        ContentId id = parsed.get();
        java.util.Map<String, String> values = impl.lastShown(player)
                .map(ai.resourcepack.engine.core.dialog.DialogsImpl.Shown::values)
                .orElse(java.util.Map.of());
        // On the next tick, for the reason /rp var waits: the click that ran
        // this may still be closing the page it was on.
        org.bukkit.plugin.Plugin plugin = org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(InterfaceCommands.class);
        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || impl.show(player, id, values)) {
                return;
            }
            Reply.to(player, !impl.canShow(player, id)
                    ? "That page is drawn in a pack you are not holding, so it would open as missing-glyph boxes."
                    : "The game would not open " + id + ". The console says what it made of it.");
        });
        return true;
    }

    @Override
    public List<String> complete(CommandSender sender, String sub, String[] args) {
        if (sub.equals("page") && dialogs instanceof ai.resourcepack.engine.core.dialog.DialogsImpl impl) {
            // Only the pages a click on what they are looking at could open —
            // the only ones this would open anyway.
            if (args.length != 2 || !(sender instanceof Player player)) {
                return List.of();
            }
            return Completions.matchingIds(args[1], impl.linked(player));
        }
        if (sub.equals("var") && dialogs instanceof ai.resourcepack.engine.core.dialog.DialogsImpl impl) {
            java.util.Map<String, List<String>> declared = impl.declared();
            if (args.length == 2) {
                return Completions.matching(args[1], new ArrayList<>(declared.keySet()));
            }
            if (args.length == 3) {
                return Completions.matching(args[2], declared.getOrDefault(args[1].toLowerCase(Locale.ROOT), List.of()));
            }
            return List.of();
        }
        // A dialog's values: the one nearly every dialog about somebody wants,
        // offered for every player online.
        if (sub.equals("dialog") && args.length >= 4) {
            List<String> targets = new ArrayList<>();
            for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
                targets.add("target=" + player.getName());
            }
            return Completions.matching(args[args.length - 1], targets);
        }
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
                List<String> options = new ArrayList<>(Completions.matchingIds(args[1], plainHudIds()));
                options.addAll(Completions.matching(args[1], "clear"));
                return options;
            }
            case "shader": {
                List<String> options = new ArrayList<>(Completions.matchingIds(args[1], overlays.shaderIds()));
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
        for (ContentId id : plainHudIds()) {
            Reply.to(sender, id + "  "
                    + overlays.hud(id).map(o -> o.slot().name().toLowerCase(Locale.ROOT)).orElse("?"));
        }
        if (overlays.screenIds().isEmpty() && plainHudIds().isEmpty()) {
            Reply.to(sender, "No screens or HUDs loaded.");
        }
        return true;
    }

    /**
     * The HUDs that are not shader objects.
     *
     * <p>A shader object is a HUD to the runtime and not to the person typing:
     * they made it in Studio's shader editor, and finding it under
     * {@code /rp hud} is finding it under a name they never used. So each
     * command lists only its own.
     */
    private List<ContentId> plainHudIds() {
        List<ContentId> ids = new ArrayList<>(overlays.hudIds());
        ids.removeAll(overlays.shaderIds());
        return ids;
    }

    private boolean isShader(ContentId id) {
        return overlays.hud(id).map(o -> o.isShader()).orElse(Boolean.FALSE);
    }

    private boolean shaders(CommandSender sender) {
        java.util.Collection<ContentId> ids = overlays.shaderIds();
        if (ids.isEmpty()) {
            Reply.to(sender, "No shader objects loaded. Make one in Studio and press Sync.");
            return true;
        }
        Reply.heading(sender, "Shaders", Reply.plural(ids.size(), "shader object")
                + ", /rp shader <id> to show one");
        // Whether the sender would see each one, for the same reason /rp
        // dialogs says it: a shader's picture is in one pushed pack, and
        // somebody not holding it gets a success and a blank screen.
        Player self = sender instanceof Player ? (Player) sender : null;
        for (ContentId id : ids) {
            String slot = overlays.hud(id).map(o -> o.slot() == ai.resourcepack.engine.api.OverlayInfo.Slot.BOSS_BAR
                    ? "boss bar" : "action bar").orElse("?");
            String note = self == null ? slot
                    : runtime.isShowing(self, id) ? slot + ", showing"
                    : overlays.canShow(self, id) ? slot
                    : slot + ", you are NOT holding its pack";
            Reply.row(sender, id.toString(), note);
        }
        return true;
    }

    private boolean shader(CommandSender sender, String[] args) {
        if (args.length < 2) {
            Reply.to(sender, "/rpengine shader <id|clear> [player]");
            return true;
        }
        Player target = Targets.of(sender, args.length > 2 ? args[2] : null);
        if (target == null) {
            Reply.to(sender, "Name a player: /rpengine shader <id|clear> <player>");
            return true;
        }
        if (args[1].equalsIgnoreCase("clear")) {
            // Only the shaders. A plain HUD somebody else put up is not this
            // command's to take down; /rp hud clear is the one that clears all.
            for (ContentId id : overlays.shaderIds()) {
                runtime.hide(target, id);
            }
            Reply.to(sender, "Cleared.");
            return true;
        }
        java.util.Optional<ContentId> parsed = ContentId.parse(args[1]).filter(this::isShader);
        if (parsed.isEmpty()) {
            boolean isHud = ContentId.parse(args[1]).flatMap(overlays::hud).isPresent();
            Reply.to(sender, isHud
                    ? args[1] + " is a HUD, not a shader: /rp hud " + args[1] + "."
                    : "No shader called " + args[1] + ". /rp shaders lists them.");
            return true;
        }
        if (!overlays.canShow(target, parsed.get())) {
            Reply.to(sender, target.getName() + " is not holding the pack " + args[1] + " is in, "
                    + "so there is nothing on their screen to draw. Push the pack to them and try again.");
            return true;
        }
        runtime.show(target, parsed.get());
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
        // Whether the sender could be shown each one, when the sender is
        // somebody who could be. It is the question that is otherwise only
        // answerable by trying: a pushed dialog belongs to one pack, and a
        // listing that does not say so reads as a menu of screens that all
        // work.
        org.bukkit.entity.Player self = sender instanceof org.bukkit.entity.Player
                ? (org.bukkit.entity.Player) sender
                : null;
        for (ContentId id : dialogs.ids()) {
            String name = dialogs.info(id).map(d -> d.name()).orElse("");
            boolean pushed = dialogs.info(id).map(d -> d.fromPushedPack()).orElse(Boolean.FALSE);
            String note = !pushed ? name
                    : self == null ? (name.isEmpty() ? "pushed" : name + " (pushed)")
                    : dialogs.canShow(self, id) ? (name.isEmpty() ? "pushed, you hold it" : name + " (pushed, you hold it)")
                    : (name.isEmpty() ? "pushed, you are NOT holding it" : name + " (pushed, you are NOT holding it)");
            Reply.row(sender, id.toString(), note);
        }
        // Both of these are things somebody would otherwise find out by a
        // command doing nothing, which is the failure this whole listing
        // exists to prevent.
        if (!dialogs.supported()) {
            Reply.to(sender, "This server is older than 1.21.6, so none of these will open.");
        }
        return true;
    }

    private boolean dialog(CommandSender sender, String[] args) {
        if (args.length < 2) {
            Reply.to(sender, "/rpengine dialog <id> [player] [name=value...]");
            return true;
        }
        // The player is optional, so a first word with "=" in it is already a
        // value: "/rp dialog punish target=Steve" opens it for whoever typed it.
        boolean named = args.length > 2 && !args[2].contains("=");
        Player target = Targets.of(sender, named ? args[2] : null);
        if (target == null) {
            Reply.to(sender, "Name a player: /rpengine dialog <id> <player> [name=value...]");
            return true;
        }
        java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
        for (int i = named ? 3 : 2; i < args.length; i++) {
            int eq = args[i].indexOf('=');
            if (eq <= 0) {
                Reply.to(sender, "\"" + args[i] + "\" is not name=value. A dialog's values look like target=Steve.");
                return true;
            }
            values.put(args[i].substring(0, eq), args[i].substring(eq + 1));
        }
        java.util.Optional<ContentId> parsed = ContentId.parse(args[1]);
        if (parsed.map(id -> dialogs.show(target, id, values)).orElse(Boolean.FALSE)) {
            return true;
        }
        // FOUR ways to fail and they want different answers — a version, a
        // name, a pack, or the dialog itself. Saying "no dialog called that"
        // to somebody on 1.21.5 sends them looking for a typo that is not
        // there, and saying anything about the pack to somebody whose JSON is
        // malformed sends them re-pushing for ever.
        //
        // The fourth used to be "restart the server", which was the single
        // most common answer this command gave and is now never the right one:
        // a dialog travels inside the command that opens it, so reaching here
        // means the GAME would not take it. That is a fault in the dialog, and
        // the console has the game's own words for it.
        if (!dialogs.supported()) {
            Reply.to(sender, "Dialogs need Minecraft 1.21.6. Use a screen instead: /rp screens.");
        } else if (parsed.flatMap(dialogs::info).isEmpty()) {
            Reply.to(sender, "No dialog called " + args[1] + ".");
        } else if (!parsed.map(id -> dialogs.canShow(target, id)).orElse(Boolean.FALSE)) {
            Reply.to(sender, target.getName() + " is not holding the pack " + args[1] + "'s picture is in, "
                    + "so it would open as a screen of missing-glyph boxes. Push the pack to them and try again.");
        } else {
            Reply.to(sender, "The game would not open " + args[1] + ". The console says what it made of it — "
                    + "usually a field this Minecraft version does not have, or a line break inside one of "
                    + "the strings.");
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
        // A shader id still draws here although nothing lists or completes it:
        // Studio relayed `/rp hud <shader>` before `/rp shader` existed, and a
        // Studio talking to an engine that predates it still has to.
        boolean drawn = ContentId.parse(args[1]).map(id -> runtime.show(target, id))
                .orElse(Boolean.FALSE);
        if (!drawn) {
            Reply.to(sender, "No HUD called " + args[1] + ".");
        }
        return true;
    }
}
