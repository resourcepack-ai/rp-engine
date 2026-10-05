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
                Help.of("dialog", "<id> [k=v] [player]", "open (1.21.6+)"),
                Help.of("var", "<name> <value>", "set a dialog setting"),
                Help.of("page", "<id|close>", "turn or close a dialog"),
                Help.of("slot", "<slot>", "move an item in a dialog"));
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
            case "slot":
                return slot(sender, args);
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
     * <p>Naming another player is for staff and tests, and needs
     * {@code rpengine.var.others}.
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
            target = org.bukkit.Bukkit.getPlayerExact(args[3]);
            if (target == null) {
                Reply.to(sender, args[3] + " is not online.");
                return true;
            }
            if (!Targets.permitted(sender, "var", target)) {
                Targets.refuse(sender, "var", "change the dialog settings of");
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
     *
     * <p><b>It opens the page at once, in the tick the click arrived in.</b> A
     * paged dialog from Studio is sent with {@code after_action: none}, so the
     * client keeps the page it was on in front of the player until this one
     * replaces it — and every tick spent here is a tick of a dead click. (Under
     * {@code close} the client shut the page before the command even left it,
     * and the player watched the world and their cursor jump to the middle for
     * a round trip; nothing on the server side was waiting on that either.)
     *
     * <p>{@code /rp page close} is the footer button of such a dialog: under
     * {@code none} the game's own button no longer closes anything, so Studio
     * gives it this, which closes whatever dialog the player has open — their
     * own, and nothing anybody could mind them closing.
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
            Reply.to(sender, "/rp page <id|close>");
            return true;
        }
        if (args[1].equalsIgnoreCase("close")) {
            impl.close(player);
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
        if (!impl.follow(player, id, values)) {
            Reply.to(player, !impl.canShow(player, id)
                    ? "That page is drawn in a pack you are not holding, so it would open as missing-glyph boxes."
                    : "The game would not open " + id + ". The console says what it made of it.");
        }
        return true;
    }

    /**
     * {@code /rp slot <container/index>} — what a click on a slot of a dialog
     * whose items move runs, as the player who clicked it: the first picks a
     * stack up, the second puts it down. See
     * {@link ai.resourcepack.engine.core.dialog.DialogSlots}.
     *
     * <p>Every player may run it, for {@code /rp page}'s reason: it acts only on
     * a slot the dialog the player was last shown has a click for, and only on
     * the player's own containers, so typing it does what the click did and
     * nothing more. Then it opens that dialog again at once, items where they
     * now are — Studio sends such a dialog with {@code after_action: none}, so
     * the old one stays up until this replaces it. Silent when it works, a line
     * when it does not.
     */
    private boolean slot(CommandSender sender, String[] args) {
        if (!(dialogs instanceof ai.resourcepack.engine.core.dialog.DialogsImpl impl)) {
            Reply.to(sender, "Dialogs are not available on this server.");
            return true;
        }
        if (!(sender instanceof Player player)) {
            Reply.to(sender, "Only a player moves an item: it is what a click on a dialog runs.");
            return true;
        }
        if (args.length < 2) {
            Reply.to(sender, "/rp slot <slot>");
            return true;
        }
        java.util.Optional<ai.resourcepack.engine.core.dialog.DialogSlots.Key> key =
                ai.resourcepack.engine.core.dialog.DialogSlots.Key.parse(args[1]);
        java.util.Optional<ai.resourcepack.engine.core.dialog.DialogsImpl.Shown> shown = impl.lastShown(player);
        if (key.isEmpty() || shown.isEmpty() || !impl.holdsSlot(player, key.get())) {
            Reply.to(sender, shown.isPresent()
                    ? "The dialog you were shown has no slot " + args[1] + " that items move in."
                    : "Open a dialog first: /rp slot moves an item in the one you are looking at.");
            return true;
        }
        boolean wasHolding = impl.slots().held(player, shown.get().id()).isPresent();
        ai.resourcepack.engine.core.dialog.DialogSlots.Result result =
                impl.slots().click(player, shown.get().id(), key.get());
        if (result == ai.resourcepack.engine.core.dialog.DialogSlots.Result.NO_SUCH_SLOT) {
            Reply.to(sender, "There is no slot " + args[1] + " to move items in.");
            return true;
        }
        // Picked up, put down or put back — or a move refused, which puts the
        // light out — so the dialog again, as things now are. A click on an empty
        // slot with nothing picked up changes nothing on screen, and costs nothing.
        if (result != ai.resourcepack.engine.core.dialog.DialogSlots.Result.NOTHING || wasHolding) {
            impl.reopen(player);
        }
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
            List<String> options = new ArrayList<>(Completions.matchingIds(args[1], impl.linked(player)));
            options.addAll(Completions.matching(args[1], "close"));
            return options;
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
        // After a dialog's id: its values — the one nearly every dialog about
        // somebody wants, offered for every player online — and then, LAST
        // and only to somebody who may open one on somebody else, who to
        // open it for. Nobody else is offered a name at all: the player is
        // always them.
        if (sub.equals("dialog") && args.length >= 3) {
            List<String> options = new ArrayList<>();
            for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
                options.add("target=" + player.getName());
            }
            boolean named = false;
            for (int i = 2; i < args.length - 1; i++) {
                named |= !args[i].contains("=");
            }
            if (!named && Targets.mayTargetOthers(sender, "dialog")) {
                options.addAll(Targets.names(sender, "dialog"));
            }
            return Completions.matching(args[args.length - 1], options);
        }
        if (args.length == 3 && DRAWS.contains(sub)) {
            return Completions.matching(args[2], Targets.names(sender, sub));
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
        if (!Targets.permitted(sender, "sound", target)) {
            Targets.refuse(sender, "sound", "play a sound to");
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
        if (!Targets.permitted(sender, "shader", target)) {
            Targets.refuse(sender, "shader", "show or clear a shader on");
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
        if (!Targets.permitted(sender, "screen", target)) {
            Targets.refuse(sender, "screen", "open a screen on");
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
        boolean slowPages = false;
        for (ContentId id : dialogs.ids()) {
            String name = dialogs.info(id).map(d -> d.name()).orElse("");
            boolean pushed = dialogs.info(id).map(d -> d.fromPushedPack()).orElse(Boolean.FALSE);
            String note = !pushed ? name
                    : self == null ? (name.isEmpty() ? "pushed" : name + " (pushed)")
                    : dialogs.canShow(self, id) ? (name.isEmpty() ? "pushed, you hold it" : name + " (pushed, you hold it)")
                    : (name.isEmpty() ? "pushed, you are NOT holding it" : name + " (pushed, you are NOT holding it)");
            // Whether its pages turn on the client or wait for the server: the
            // difference somebody notices as a delay, and fixes with a restart.
            Boolean instant = dialogs instanceof ai.resourcepack.engine.core.dialog.DialogsImpl impl
                    ? impl.pagesTurnInstantly(id) : null;
            if (instant != null) {
                slowPages |= !instant;
                note = (note.isEmpty() ? "" : note + " · ") + (instant ? "pages turn instantly" : "pages wait for the server");
            }
            // The command players open it with, or why it has none it asked for.
            if (dialogs instanceof ai.resourcepack.engine.core.dialog.DialogsImpl impl) {
                java.util.Optional<String> command = impl.commandOf(id);
                java.util.Optional<ai.resourcepack.engine.api.DialogInfo> info = dialogs.info(id);
                if (command.isPresent()) {
                    note = (note.isEmpty() ? "" : note + " · ") + "opens with " + command.get()
                            + info.flatMap(d -> d.permission()).map(p -> " (" + p + ")").orElse("");
                } else if (info.isPresent() && impl.commandTaken(info.get())) {
                    note = (note.isEmpty() ? "" : note + " · ") + "/" + info.get().command().orElse("")
                            + " is taken by another plugin";
                }
            }
            Reply.row(sender, id.toString(), note);
        }
        if (slowPages && dialogs.supported()) {
            Reply.to(sender, "Pages turn instantly once the server has restarted since they were synced, "
                    + "for pages that show nothing per player ({placeholders} are filled as they open).");
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
        // The player is only offered to somebody who may open one on somebody
        // else: for everybody else it is always themselves.
        boolean others = Targets.mayTargetOthers(sender, "dialog");
        if (args.length < 2) {
            Reply.to(sender, "/rpengine dialog <id> [name=value...]" + (others ? " [player]" : ""));
            return true;
        }
        // Values are name=value; the one word without "=" is the player. It is
        // written LAST ("/rp dialog punish target=Steve Admin") and found
        // wherever it is, because the order it replaced ("/rp dialog punish
        // Admin target=Steve") is in command blocks and other plugins, and
        // should go on working. With none it opens for whoever typed it.
        String named = null;
        java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
        for (int i = 2; i < args.length; i++) {
            int eq = args[i].indexOf('=');
            if (eq > 0) {
                values.put(args[i].substring(0, eq), args[i].substring(eq + 1));
                continue;
            }
            if (eq == 0 || named != null) {
                Reply.to(sender, "\"" + args[i] + "\" is not name=value. A dialog's values look like target=Steve"
                        + (others ? ", and the player to open it for goes last." : "."));
                return true;
            }
            named = args[i];
        }
        Player target = Targets.of(sender, named);
        if (target == null) {
            Reply.to(sender, named == null
                    ? "Name a player: /rpengine dialog <id> [name=value...] <player>"
                    : "Nobody called " + named + " is online.");
            return true;
        }
        // Opening one ABOUT somebody (target=Steve) is opening it on yourself;
        // only putting it on another player's screen is the others node.
        if (!Targets.permitted(sender, "dialog", target)) {
            Targets.refuse(sender, "dialog", "open a dialog on");
            return true;
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
        if (!Targets.permitted(sender, "hud", target)) {
            Targets.refuse(sender, "hud", "show or clear a HUD on");
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
