package ai.resourcepack.engine;

import ai.resourcepack.engine.api.BuildReport;
import ai.resourcepack.engine.api.BuiltPack;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentRegistration;
import ai.resourcepack.engine.api.ContentRegistry;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.Emotes;
import ai.resourcepack.engine.api.Icons;
import ai.resourcepack.engine.api.Items;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.api.Models;
import ai.resourcepack.engine.api.Namespace;
import ai.resourcepack.engine.api.OverlayInfo;
import ai.resourcepack.engine.api.SoundInfo;
import ai.resourcepack.engine.api.Sounds;
import ai.resourcepack.engine.core.Host;
import ai.resourcepack.engine.core.bedrock.GeyserBridge;
import ai.resourcepack.engine.core.command.ChatStyle;
import ai.resourcepack.engine.core.command.ContentCommands;
import ai.resourcepack.engine.core.command.EmoteCommands;
import ai.resourcepack.engine.core.command.EngineCommand;
import ai.resourcepack.engine.core.command.InterfaceCommands;
import ai.resourcepack.engine.api.event.ContentLoadEvent;
import ai.resourcepack.engine.core.command.LiquidCommands;
import ai.resourcepack.engine.core.command.ModelCommands;
import ai.resourcepack.engine.core.command.SyncCommands;
import ai.resourcepack.engine.core.content.ContentFolderLoader;
import ai.resourcepack.engine.core.distribution.BedrockSupport;
import ai.resourcepack.engine.core.distribution.DistributionManager;
import ai.resourcepack.engine.core.distribution.ProtocolResolver;
import ai.resourcepack.engine.core.emote.EmoteDirector;
import ai.resourcepack.engine.core.emote.EmoteInvites;
import ai.resourcepack.engine.core.emote.EmoteStore;
import ai.resourcepack.engine.core.emote.EmoteWording;
import ai.resourcepack.engine.core.emote.EmotesImpl;
import ai.resourcepack.engine.core.entity.CustomEntities;
import ai.resourcepack.engine.core.entity.EntityDefinitions;
import ai.resourcepack.engine.core.font.ChatIcons;
import ai.resourcepack.engine.core.font.FontAssets;
import ai.resourcepack.engine.core.hook.CitizensTrait;
import ai.resourcepack.engine.core.hook.MythicHook;
import ai.resourcepack.engine.core.hook.Placeholders;
import ai.resourcepack.engine.core.hook.WorldGuardHook;
import ai.resourcepack.engine.core.font.IconDefinitions;
import ai.resourcepack.engine.core.font.IconsImpl;
import ai.resourcepack.engine.core.font.OverlayDefinitions;
import ai.resourcepack.engine.core.font.Overlays;
import ai.resourcepack.engine.core.item.Geometry;
import ai.resourcepack.engine.core.item.ActionRunner;
import ai.resourcepack.engine.core.item.ItemAssets;
import ai.resourcepack.engine.core.item.ItemDefinitions;
import ai.resourcepack.engine.core.item.ItemListener;
import ai.resourcepack.engine.core.item.ItemsImpl;
import ai.resourcepack.engine.core.liquid.LiquidBiomes;
import ai.resourcepack.engine.core.liquid.LiquidBuckets;
import ai.resourcepack.engine.core.liquid.LiquidDefinitions;
import ai.resourcepack.engine.core.liquid.LiquidPools;
import ai.resourcepack.engine.core.liquid.Liquids;
import ai.resourcepack.engine.core.model.ModelDefinitions;
import ai.resourcepack.engine.core.model.ModelPlacementListener;
import ai.resourcepack.engine.core.model.ModelsImpl;
import ai.resourcepack.engine.core.model.RigAnimator;
import ai.resourcepack.engine.core.model.RigPlacementListener;
import ai.resourcepack.engine.api.MergeResult;
import ai.resourcepack.engine.core.model.BoneListener;
import ai.resourcepack.engine.core.model.BoundModels;
import ai.resourcepack.engine.core.model.ModelRigs;
import ai.resourcepack.engine.core.model.RigStore;
import ai.resourcepack.engine.core.model.Seats;
import ai.resourcepack.engine.core.pack.PackBuilder;
import ai.resourcepack.engine.core.recipe.RecipeDefinitions;
import ai.resourcepack.engine.core.recipe.Recipes;
import ai.resourcepack.engine.core.registry.ContentRegistryImpl;
import ai.resourcepack.engine.core.serve.BundleSessions;
import ai.resourcepack.engine.core.serve.PackDelivery;
import ai.resourcepack.engine.core.serve.PackHost;
import ai.resourcepack.engine.core.skin.SkinApplier;
import ai.resourcepack.engine.core.sound.SoundAssets;
import ai.resourcepack.engine.core.sound.SoundDefinitions;
import ai.resourcepack.engine.core.sound.SoundsImpl;
import ai.resourcepack.engine.core.sync.StudioContent;
import ai.resourcepack.engine.core.sync.StudioPush;
import ai.resourcepack.engine.core.sync.StudioRelay;
import ai.resourcepack.engine.core.sync.SyncClient;
import ai.resourcepack.engine.core.sync.SyncGroup;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * The plugin: loads the content folder, builds a pack per bundle, serves them,
 * and hands players the bundle they should be holding.
 *
 * <p>Thin on purpose. Everything it does is assembled from pieces that work
 * without a server, so this class is wiring and console output rather than
 * logic. If something here starts making decisions, that is the signal it
 * belongs one layer down where it can be tested.
 *
 * <p><strong>The plugin name is load-bearing and cannot change.</strong>
 * Persistent-data keys are namespaced by it, so a rename would orphan
 * everything placed in every world by every earlier version.
 */
public final class RPEnginePlugin extends JavaPlugin implements Listener {

    /** So the output folder can answer "is this mine?" without anybody asking. */
    private static final String OUTPUT_README = """
            Everything in this folder is generated by RP Engine.

            Your content goes in ../content/, one folder per pack. This folder holds
            the resource packs built from it, one zip per bundle. Editing a zip here
            does nothing: the next reload overwrites it.

            Safe to delete. It is rebuilt on the next start.
            """;

    private final ContentRegistryImpl registry = new ContentRegistryImpl();
    private final BundleSessions sessions = new BundleSessions();
    private ItemsImpl items;
    private ModelPlacementListener placements;
    private Recipes recipes;
    private SyncClient sync;
    private StudioRelay studio;
    private StudioContent pushed;
    private EmoteStore emoteStore;
    private EmoteDirector emotes;
    private EmoteInvites invites;
    /**
     * The pack id the content folder's own rigs are stored under.
     *
     * <p>Not a namespace and deliberately not one: the store scopes by the
     * pack that supplied a rig, and every content pack on this server is one
     * supplier as far as it is concerned — they are all replaced together
     * because they are all rebuilt together.
     */
    private static final String AUTHORED_RIGS = "content-folder";

    private BoundModels boundModels;
    private RigStore rigs;
    private RigAnimator animator;
    private RigPlacementListener rigPlacement;
    private ModelsImpl models;
    private Seats seats;
    private CustomEntities creatures;
    /** Whether a rebuild has finished once, which is what tells the two causes apart. */
    private boolean started;

    private LiquidPools pools;
    private Liquids liquids;
    private LiquidBiomes liquidBiomes;
    private LiquidCommands liquidCommands;
    private SkinApplier skins;
    private DistributionManager distribution;
    private BedrockSupport bedrock = BedrockSupport.NONE;

    /**
     * Players whose next quit is a Geyser transfer rather than a departure.
     *
     * <p>Serving a Bedrock pack reconnects them, which reaches the server as a
     * disconnect. Without this their sync pairing is dropped mid-apply.
     */
    private final Set<UUID> reconnecting = ConcurrentHashMap.newKeySet();
    private final SyncGroup group = new SyncGroup();
    /** Recipes are outside the id space, so this is the only list of them. */
    private List<ContentId> recipeIds = List.of();
    private final SoundsImpl sounds = new SoundsImpl();
    /**
     * What the content folder said, kept apart from what a push added.
     *
     * <p>Both end up in one catalogue, and a reload has to be able to rebuild
     * that catalogue without losing the pushed half or keeping a stale one.
     */
    private Map<ContentId, SoundInfo> authoredSounds = Map.of();
    private Map<ContentId, OverlayInfo> authoredScreens = Map.of();
    private Map<ContentId, OverlayInfo> authoredHuds = Map.of();
    private final IconsImpl icons = new IconsImpl();
    private final Overlays overlays = new Overlays();

    private PackHost packHost;
    private PackDelivery delivery;
    private List<BuiltPack> built = List.of();
    private String defaultBundle = "";

    @Override
    public void onLoad() {
        // Before onEnable, and it has to be: WorldGuard parses its regions
        // between the two, and a flag registered after that is not merely
        // ignored — every region that set it has already dropped the value.
        //
        // Guarded from OUT HERE rather than inside the hook. See `optional`:
        // this exact call took the whole plugin down on a server with no
        // WorldGuard, and the null check inside registerFlags was correct and
        // never got to run.
        optional("WorldGuard", () -> {
            WorldGuardHook.registerFlags(this);
            return true;
        });
    }

    /**
     * Starts an optional plugin's integration, if that plugin is installed.
     *
     * <p><b>The presence check has to be here, at the call site, and not
     * inside the hook class.</b> Each hook in {@code core.hook} names its
     * plugin's types in its field types and its exception tables, so merely
     * CALLING a method on one makes the JVM resolve, load and verify that
     * class — which throws {@link NoClassDefFoundError} on a server that does
     * not have the plugin, before a single line of the hook's own guard can
     * execute. A check inside the hook can only ever run on a server that did
     * not need it.
     *
     * <p>That is not a hypothetical. {@code WorldGuardHook.registerFlags}
     * opened with exactly the right null check and the plugin still died in
     * {@code onLoad} with {@code NoClassDefFoundError:
     * .../FlagConflictException} on a server with no WorldGuard — taking every
     * other plugin on the box with it, because a plugin that throws from
     * onLoad is never enabled. The hooks keep their internal checks; they are
     * the second belt, for a plugin that is present but the wrong version.
     *
     * <p>Deliberately a {@link BooleanSupplier} rather than a direct call:
     * the lambda's body is not executed until it is invoked below, which is
     * after the plugin has been found, so the hook class is never touched on a
     * server that lacks it.
     */
    private boolean optional(String pluginName, BooleanSupplier start) {
        if (getServer().getPluginManager().getPlugin(pluginName) == null) {
            return false;
        }
        try {
            return start.getAsBoolean();
        } catch (NoClassDefFoundError | RuntimeException e) {
            // Present but not usable — a version whose classes moved. Said once
            // and otherwise ignored: an optional integration is optional, and
            // it must not be the reason a server loses its whole plugin.
            getLogger().info(pluginName + " is present, but its integration could not be started.");
            return false;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // Before anything can talk, including the load diagnostics below.
        applyChatStyle();
        applyHeldItemTurn();
        items = new ItemsImpl(this);
        // Emotes come over from the previous engine whole: the store, the
        // director and the maths. Host is the seam they were written against,
        // so it is what they get.
        // Animated rigs: studio's own placement path, beside ours. A studio
        // model is a carrier item dispatched by custom_model_data, so it is
        // identified and placed differently from a content-folder item — two
        // paths because there are genuinely two kinds of thing.
        // One Host for everything that borrows this plugin's scheduler,
        // listeners and key namespace. The rig side never asks for the emote
        // wording or the cast permission, but two Hosts differing only in the
        // fields one of them ignores is a thing to keep in step for no reason.
        Host library = new Host(this, getDataFolder(), new EmoteWording(),
                getConfig().getString("emotes.cast-permission", EmoteWording.MULTI_PERMISSION));
        skins = new SkinApplier(this);

        // Geyser is a plugin a server may not have, and its classes are simply
        // not there when it does not. Probed once, and everything that asks
        // about Bedrock asks through the seam.
        if (getServer().getPluginManager().getPlugin("Geyser-Spigot") != null) {
            try {
                bedrock = new GeyserBridge(this, reconnecting::add);
                getLogger().info("Geyser found; Bedrock players are served their own pack.");
            } catch (RuntimeException | NoClassDefFoundError e) {
                getLogger().warning("Geyser is here but the bridge would not start: " + e.getMessage());
            }
        }
        distribution = new DistributionManager(this, bedrock, new ProtocolResolver(getLogger()),
                getConfig().getString("distribution.api-url", "https://studio.resourcepack.ai"));
        rigs = new RigStore(getDataFolder());
        rigs.load(getLogger());
        animator = new RigAnimator(library, rigs);
        rigPlacement = new RigPlacementListener(library, rigs, animator);
        models = new ModelsImpl(animator, rigs, rigPlacement);

        emoteStore = new EmoteStore(getDataFolder());
        emoteStore.load(getLogger());
        // What a pushed pack holds that a command can name. Loaded here rather
        // than built on the first push, because a player is still wearing the
        // last one after a restart.
        pushed = new StudioContent(getDataFolder());
        pushed.load(getLogger());
        // EmoteWording rather than nothing. Three things an emote has to say
        // happen to somebody who did not run the command — being pulled in, an
        // emote ending, and finding on join that the one you were in did not
        // survive a crash — and EmoteMessages' own doc says silence there
        // reads as a bug.
        emotes = new EmoteDirector(library, emoteStore);
        seats = new Seats(this);
        creatures = new CustomEntities(this, items);
        pools = new LiquidPools(getDataFolder());
        pools.load(getLogger());
        liquids = new Liquids(this, pools);
        liquidBiomes = new LiquidBiomes(getLogger());
        placements = new ModelPlacementListener(this, items, seats, library, rigs, animator);
        recipes = new Recipes(this, items);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(placements, this);
        getServer().getPluginManager().registerEvents(seats, this);
        getServer().getPluginManager().registerEvents(creatures, this);
        liquids.start();
        getServer().getPluginManager().registerEvents(
                new ItemListener(this, items, new ActionRunner(items, sounds),
                        new LiquidBuckets(liquids, pools, liquidBiomes, getLogger())), this);
        // Off unless a server asks for it: a plugin that starts rewriting
        // what people type in chat the moment it is installed is a plugin
        // somebody has to find the setting for in a hurry.
        getServer().getPluginManager().registerEvents(
                new ChatIcons(icons, getConfig().getBoolean("chat.icons", false)), this);
        // The client and the relay each need the other: the client dispatches
        // to the relay, the relay answers down the client. Nothing fires until
        // open() below, so the lambdas can read a field set on the next line.
        sync = new SyncClient(
                getConfig().getString("sync.url", "wss://sync.resourcepack.ai/connect"),
                getConfig().getString("sync.server-token", ""),
                getLogger(),
                (code, payload) -> studio.onPush(code, payload),
                (code, payload) -> studio.onGive(code, payload),
                (code, payload) -> studio.onSkin(code, payload),
                (code, payload) -> studio.onTell(code, payload));
        studio = new StudioRelay(this, sync, group, rigs, emoteStore, pushed, skins,
                getDataFolder().toPath().resolve("output"),
                pack -> packHost.register(pack), this::pushTo);
        studio.onContent(this::registerPushedContent);
        boundModels = new BoundModels(library, items, rigs, animator);
        models.bound(boundModels);
        invites = new EmoteInvites(this, emotes());
        // Optional, and the only place PlaceholderAPI is named. A server
        // without it loads this plugin exactly as before.
        // All four go through `optional`, which is what keeps a server that
        // has none of them from ever loading their classes. See its note.
        if (optional("PlaceholderAPI",
                () -> Placeholders.register(this, registry, emotes(), items, seats, sessions, group))) {
            getLogger().info("PlaceholderAPI found: %rpengine_...% placeholders are available.");
        }
        if (optional("WorldGuard", () -> WorldGuardHook.listen(this))) {
            getLogger().info("WorldGuard found: rpengine-place and rpengine-use are available.");
        }
        if (optional("Citizens", () -> CitizensTrait.register(this, boundModels))) {
            // So a bind on an NPC survives it being despawned and respawned,
            // which Citizens does on every chunk unload and restart.
            boundModels.remembersWith(CitizensTrait::remember);
            getLogger().info("Citizens found: a bound NPC keeps its model.");
        }
        if (optional("MythicMobs", () -> MythicHook.register(this, boundModels))) {
            getLogger().info("MythicMobs found: rpmodel, rpanimate and rpunmodel are available.");
        }
        // The handle an event carries, wired both ways at startup exactly as
        // the library does it.
        animator.placements(models::handleFor);
        rigPlacement.placements(models::handleFor);
        getServer().getPluginManager().registerEvents(rigPlacement, this);
        getServer().getPluginManager().registerEvents(
                new BoneListener(animator.bones(), seats), this);
        animator.start();

        getServer().getPluginManager().registerEvents(emotes, this);
        emotes.start();
        getServer().getPluginManager().registerEvents(distribution, this);
        distribution.start();
        // A trusted server holds the socket open from startup: it announces
        // who is online rather than waiting for somebody to type a code, and
        // an announcement down a socket that is not there is nothing at all.
        if (sync.announcesPresence() && !sync.open()) {
            getLogger().warning("Could not reach studio, so this server is not announcing presence.");
        }

        startHost();
        registerCommands();
        rebuild(getServer().getConsoleSender());
    }

    /**
     * Hands every command to {@link EngineCommand}.
     *
     * <p>After {@link #startHost()}, because two of the areas need the pack host
     * and the delivery it makes. The areas take the services they use and
     * nothing else; what they cannot be given is this plugin's own lifecycle,
     * which arrives as the four callbacks below.
     */
    private void registerCommands() {
        liquidCommands = new LiquidCommands(liquids, pools, liquidBiomes, getLogger());
        EngineCommand commands = new EngineCommand(registry, () -> built,
                new ContentCommands(items, () -> built, packHost, recipes, () -> recipeIds,
                        this::reloadContent, this::sendPack),
                new ModelCommands(placements, creatures, boundModels, items),
                new InterfaceCommands(sounds, icons, overlays),
                new EmoteCommands(emotes, invites),
                new SyncCommands(getServer(), sync, group, distribution,
                        this::announceMembers, this::unpush),
                liquidCommands);

        // The two player commands are optional. A server that wants everything
        // under /rp — because /emote collides with something it already has,
        // or because it simply does not want a second command — turns them off
        // and loses nothing: /rp emote is the same code.
        boolean players = getConfig().getBoolean("emotes.player-commands", true);

        for (String name : List.of("rpengine", "emote", "emotereply")) {
            PluginCommand registered = getCommand(name);
            if (registered == null) {
                // plugin.yml and this list disagreeing is a packaging mistake,
                // and a silent one: the command simply does nothing in game.
                getLogger().warning("plugin.yml declares no /" + name + ".");
                continue;
            }
            if (!players && !name.equals("rpengine")) {
                withdraw(registered);
                continue;
            }
            registered.setExecutor(commands);
            registered.setTabCompleter(commands);
        }
    }

    /**
     * Takes a command back out of the server, name and aliases.
     *
     * <p>Bukkit registers everything in plugin.yml before a plugin can say it
     * would rather not have one, and an unregistered command still answers —
     * with its usage line, which reads as a broken plugin rather than a
     * command this server does not have. So the entry is removed from the
     * command map itself.
     *
     * <p>By reflection, because the map is CraftBukkit's and the API exposes
     * no way to withdraw a command. It fails soft: the worst case is a server
     * that turned this off still having a /emote that refuses politely, which
     * is a great deal better than one that will not start.
     */
    private void withdraw(PluginCommand command) {
        try {
            Object map = declared(getServer().getPluginManager(), "commandMap")
                    .get(getServer().getPluginManager());
            Map<?, ?> known = (Map<?, ?>) declared(map, "knownCommands").get(map);

            List<String> names = new ArrayList<>(command.getAliases());
            names.add(command.getName());
            for (String name : names) {
                known.remove(name);
                known.remove(getName().toLowerCase(Locale.ROOT) + ":" + name);
            }
            command.unregister((org.bukkit.command.CommandMap) map);

            // Paper builds a Brigadier tree from the command map at startup,
            // and a client is told about commands from that rather than from
            // the map. Not API, so a server that does not have it simply keeps
            // offering a completion for a command that is no longer there.
            try {
                getServer().getClass().getMethod("syncCommands").invoke(getServer());
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                getLogger().fine("No syncCommands on this server; completions may lag.");
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            getLogger().warning("Could not withdraw /" + command.getName()
                    + "; it will answer with its usage line instead: " + e.getMessage());
            command.setExecutor((sender, cmd, label, args) -> {
                sender.sendMessage(EngineCommand.prefix()
                        + "This server has turned that command off. Use /rp emote.");
                return true;
            });
        }
    }

    /**
     * A field on a class or any of its parents, made accessible.
     *
     * <p>Up the hierarchy because the field wanted is declared on
     * {@code SimpleCommandMap} while the object is a server-specific subclass
     * of it, which is exactly the sort of thing that differs between Paper and
     * Spigot and between versions of each.
     */
    private static Field declared(Object of, String name) throws NoSuchFieldException {
        for (Class<?> type = of.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException keepLooking) {
                // The next class up may have it.
            }
        }
        throw new NoSuchFieldException(name + " on " + of.getClass().getName());
    }

    /**
     * {@code /rp reload}: config.yml as well as the content.
     *
     * <p>Reloading the content but not the settings is the sort of half-reload
     * that has somebody restarting the server anyway and wondering why the
     * command exists.
     */
    private void reloadContent(CommandSender to) {
        reloadConfig();
        applyChatStyle();
        applyHeldItemTurn();
        defaultBundle = getConfig().getString("default-bundle", "");
        rebuild(to);
    }

    /**
     * Puts a pushed pack's named content into the registry, and into the
     * catalogues the commands read.
     *
     * <p>Merged over the content folder's own rather than replacing it: a
     * server has its own sounds and screens, and a pack under test does not
     * take them away. The two cannot collide, because {@code studio} is a
     * namespace the folder loader is not allowed to hand out twice.
     */
    private void registerPushedContent() {
        if (pushed == null) {
            return;
        }
        pushed.register(registry, getLogger());

        Map<ContentId, SoundInfo> allSounds =
                new LinkedHashMap<>(authoredSounds);
        allSounds.putAll(pushed.sounds());
        sounds.replace(allSounds);

        Map<ContentId, OverlayInfo> allScreens =
                new LinkedHashMap<>(authoredScreens);
        allScreens.putAll(pushed.screens());
        Map<ContentId, OverlayInfo> allHuds =
                new LinkedHashMap<>(authoredHuds);
        allHuds.putAll(pushed.huds());
        overlays.replace(allScreens, allHuds);
    }

    /**
     * Reads the chat palette out of the config.
     *
     * <p>Here rather than inside {@link ChatStyle} because that class is free
     * of Bukkit — it turns hex into section signs and nothing else, which is
     * what lets it be tested without a server.
     */
    /**
     * Teaches the animator about the rigs in the content folder.
     *
     * <p>A hand-authored model with keyframes in it is placed and played by
     * exactly the machinery a pushed one is — the only difference is where
     * the rig came from, and by the time it reaches the store there is no
     * difference left at all. Until this existed a {@code .bbmodel}'s
     * animations were carried into the pack and played by nothing, which is
     * the one place studio was the contract rather than a content source.
     */
    private void registerAuthoredRigs(java.util.Map<String, ModelRigs.Rig> found) {
        if (rigs == null) {
            return;
        }
        MergeResult merged = rigs.updateFromJson(ModelRigs.manifest(AUTHORED_RIGS, found).toString());
        if (!merged.ok()) {
            getLogger().warning("content rigs: " + merged.error());
        }
    }

    /**
     * How a rig's held item is turned in its hand. <b>Calibration only.</b>
     *
     * <p>The defaults here are the answer as far as anybody knows, and a server
     * owner has no reason to set these. They exist because this turn has been
     * wrong four times and no test can see a rig: they make it something that
     * can be found by looking, with {@code /rpengine reload} between tries,
     * rather than one rebuild per guess. See {@code HeldItem.orient}.
     *
     * <p>Defaulted to the field values rather than to literals, so the answer
     * is stated once, in the class that explains it.
     */
    private void applyHeldItemTurn() {
        EmoteDirector.heldItemTurn(
            (float) getConfig().getDouble("emotes.held-item-pitch", 90.0),
            (float) getConfig().getDouble("emotes.held-item-yaw", 0.0),
            (float) getConfig().getDouble("emotes.held-item-roll", 0.0));
        EmoteDirector.nameTagsSeeThrough(
            getConfig().getBoolean("emotes.nametag-see-through", false));
    }

    private void applyChatStyle() {
        // Read with no fallbacks: an absent key arrives as null and ChatStyle
        // falls back to its own default, so the defaults live in one place
        // rather than being restated here and in config.yml.
        EngineCommand.style(ChatStyle.of(
                getConfig().getString("chat.prefix"),
                getConfig().getString("chat.colour.prefix"),
                getConfig().getString("chat.colour.brackets"),
                getConfig().getString("chat.colour.body"),
                getConfig().getString("chat.colour.accent"),
                getConfig().getString("chat.colour.error"),
                getConfig().getString("chat.colour.success")));
    }

    /** {@code /rp push}: forget what they are holding and send it again. */
    private void sendPack(Player player) {
        sessions.forget(player.getUniqueId());
        player.sendMessage(EngineCommand.prefix()
                + (delivery.apply(player, desiredFor(player)) ? "Sent." : "Nothing to send."));
    }

    /** The studio models this server holds, and the rigs standing in its worlds. */
    public Models models() {
        return models;
    }

    /** The emotes this server holds. */
    public Emotes emotes() {
        return new EmotesImpl(emotes, emoteStore);
    }

    @Override
    public void onDisable() {
        if (invites != null) {
            // An invitation nobody can answer is worse than none: the task
            // that would have expired it goes with the plugin.
            invites.clear();
        }
        if (emotes != null) {
            // Everybody mid-emote is put back before the plugin goes, or they
            // stay invisible with a rig standing where they were.
            emotes.stop();
        }
        if (emoteStore != null) {
            emoteStore.save(getLogger());
        }
        if (pushed != null) {
            pushed.save(getLogger());
        }
        if (liquids != null) {
            liquids.stop();
        }
        if (pools != null) {
            pools.save(getLogger());
        }
        if (seats != null) {
            // Everybody gets up before the plugin goes. A marker stand is not
            // persistent, so one left behind would survive until its chunk
            // unloaded — long enough to be a bug report.
            seats.clear();
        }
        if (distribution != null) {
            distribution.shutdown();
        }
        if (bedrock instanceof GeyserBridge) {
            // Unsubscribes from Geyser's event bus. Without it a reload leaves
            // a dead listener wired to a live bus.
            ((GeyserBridge) bedrock).shutdown();
        }
        if (animator != null) {
            animator.stop();
        }
        if (rigs != null) {
            rigs.save(getLogger());
        }
        // Recipes live in the server's registry rather than ours, so leaving
        // them behind would outlast the plugin that made them.
        if (recipes != null) {
            recipes.clear();
        }
        if (sync != null) {
            sync.close();
        }
        if (packHost != null) {
            packHost.stop();
        }
    }

    /** What this server holds. The supported way in for another plugin. */
    public ContentRegistry registry() {
        return registry;
    }

    /**
     * The way a third-party plugin puts content of its own into this server.
     *
     * <p>Separate from {@link #registry()} because reading what a server holds
     * and adding to it are different privileges, and the great majority of
     * callers only want the first. Claim a namespace, define into the handle,
     * and release it when your plugin disables — see {@link Namespace}.
     */
    public ContentRegistration registration() {
        return registry;
    }

    /** The icons this server holds, and the way to put one into text. */
    public Icons icons() {
        return icons;
    }

    /** The custom sounds this server holds. */
    public Sounds sounds() {
        return sounds;
    }

    /** The custom items this server holds. */
    public Items items() {
        return items;
    }

    private void startHost() {
        defaultBundle = getConfig().getString("default-bundle", "");
        String address = getConfig().getString("host.public-address", "http://127.0.0.1:8181");
        packHost = new PackHost(address);
        delivery = new PackDelivery(sessions, packHost,
                getConfig().getString("prompt.message", ""),
                getConfig().getBoolean("prompt.force", false));

        if (!getConfig().getBoolean("host.enabled", true)) {
            getLogger().info("Pack hosting is off. Packs are built but not served.");
            return;
        }
        try {
            int port = packHost.start(getConfig().getInt("host.port", 8181));
            getLogger().info("Serving packs on port " + port + " as " + address);
        } catch (IOException e) {
            getLogger().warning("Could not listen on port " + getConfig().getInt("host.port", 8181)
                    + ": " + e.getMessage() + ". Packs are built but not served.");
        }
    }

    /**
     * Reads the content folder, builds every bundle, and re-serves them.
     *
     * <p>Everything is rebuilt rather than diffed. A namespace is replaced
     * whole or not at all, and the same reasoning applies here: a partial
     * rebuild is how the registry ends up disagreeing with the zip somebody is
     * holding.
     */
    private void rebuild(CommandSender to) {
        rebuild(to, started ? ContentLoadEvent.Cause.RELOAD : ContentLoadEvent.Cause.STARTUP);
    }

    /** As above, saying why, which is the one thing the event carries. */
    private void rebuild(CommandSender to, ContentLoadEvent.Cause why) {
        // content/ is yours and output/ is ours. The names are doing real work
        // here: a folder called packs/ reads as "put your packs in me", which is
        // the one mistake a new user is most likely to make on day one.
        Path content = getDataFolder().toPath().resolve("content");
        Path output = getDataFolder().toPath().resolve("output");
        try {
            Files.createDirectories(content);
            Files.createDirectories(output);
            Files.writeString(output.resolve("README.txt"), OUTPUT_README);
        } catch (IOException e) {
            to.sendMessage("[RPEngine] Could not prepare " + getDataFolder() + ": " + e.getMessage());
            return;
        }

        // Everything goes, then the folder is read again. See clear().
        registry.clear();

        LoadReport loaded = new ContentFolderLoader(registry).load(content, ContentSource.AUTHORED);
        report(to, "content", loaded.diagnostics());

        ItemDefinitions.Result parsedItems = ItemDefinitions.parse(loaded);
        report(to, "items", parsedItems.diagnostics());
        // Needs a running server, so it lives out here rather than in parse().
        report(to, "items", ItemDefinitions.checkGivable(parsedItems));
        items.replace(parsedItems.items());

        ModelDefinitions.Result parsedModels = ModelDefinitions.parse(loaded, parsedItems.items(),
                Geometry.measure(content, parsedItems.items().values(), getLogger()::fine));
        report(to, "model", parsedModels.diagnostics());
        placements.replace(parsedModels.model());

        EntityDefinitions.Result parsedEntities = EntityDefinitions.parse(loaded);
        report(to, "entities", parsedEntities.diagnostics());
        creatures.replace(parsedEntities.entities());

        LiquidDefinitions.Result parsedLiquids = LiquidDefinitions.parse(loaded);
        report(to, "liquids", parsedLiquids.diagnostics());
        liquids.replace(parsedLiquids.liquids());
        // Written on every load, applied on the next start: a biome is
        // registered when the server boots and nothing can add one after.
        liquidBiomes.write(parsedLiquids.liquids().values());

        SoundDefinitions.Result parsedSounds = SoundDefinitions.parse(loaded);
        report(to, "sounds", parsedSounds.diagnostics());
        authoredSounds = parsedSounds.sounds();
        sounds.replace(authoredSounds);

        IconDefinitions.Result parsedIcons = IconDefinitions.parse(loaded);
        report(to, "icons", parsedIcons.diagnostics());
        icons.replace(parsedIcons.icons());

        OverlayDefinitions.Result parsedScreens = OverlayDefinitions.screens(loaded);
        OverlayDefinitions.Result parsedHuds = OverlayDefinitions.huds(loaded);
        report(to, "screens", parsedScreens.diagnostics());
        report(to, "huds", parsedHuds.diagnostics());
        authoredScreens = parsedScreens.overlays();
        authoredHuds = parsedHuds.overlays();
        overlays.replace(authoredScreens, authoredHuds);

        ItemAssets itemAssets = new ItemAssets();
        BuildReport builtReport = new PackBuilder(
                getConfig().getInt("pack.format", PackBuilder.PACK_FORMAT),
                getConfig().getString("pack.description", "RP Engine"))
                .with(itemAssets)
                .with(new SoundAssets())
                .with(new FontAssets())
                .build(content, output, loaded);
        report(to, "build", builtReport.diagnostics());

        // The rigs the build found, handed to the same store a studio push
        // fills. Under one pack id, so a reload REPLACES them: a model whose
        // animation somebody deleted stops being animated, which a per-key
        // merge would not do. Studio's rigs are keyed by their own pack ids
        // and are untouched by this.
        registerAuthoredRigs(itemAssets.rigs());

        // After the items exist, because a recipe's ingredients and result are
        // resolved against them.
        RecipeDefinitions.Result parsedRecipes = RecipeDefinitions.parse(loaded);
        report(to, "recipes", parsedRecipes.diagnostics());
        recipeIds = List.copyOf(parsedRecipes.recipes().keySet());
        for (String problem : recipes.replace(parsedRecipes.recipes())) {
            getLogger().warning("recipe " + problem);
        }

        // The content folder has just replaced the registry wholesale, which
        // knows nothing about the pack somebody is already wearing.
        registerPushedContent();

        built = builtReport.packs();
        for (BuiltPack pack : built) {
            packHost.register(pack);
        }

        to.sendMessage("[RPEngine] " + plural(loaded.packs().size(), "pack") + ", "
                + plural(loaded.definitions().size(), "definition") + ", "
                + plural(built.size(), "bundle") + " built, "
                + plural(recipes.size(), "recipe") + ".");

        // Everybody online is holding a pack that may no longer exist, so they
        // are re-sent before they notice. Nothing is sent to a player whose
        // stack is already right.
        for (Player player : getServer().getOnlinePlayers()) {
            delivery.apply(player, desiredFor(player));
        }

        // Last, and that is the whole contract: a listener runs once every
        // question the API can answer is answerable. A plugin holding anything
        // resolved rather than an id needs this on a reload as much as it
        // needs it at startup, which is why one event carries both.
        started = true;
        getServer().getPluginManager().callEvent(new ContentLoadEvent(
                why, loaded.packs().size(), loaded.definitions().size()));
    }

    /**
     * Puts a pushed pack on one player.
     *
     * <p>Stacked ON TOP of whatever the server's own content gave THEM — their
     * bundle, not the claimer's, since somebody sharing a sync may be in a
     * different world with different content — so a pack under test is tried
     * against the server it will actually run on.
     */
    private void pushTo(Player player, BuiltPack pack) {
        List<BuiltPack> stack = new ArrayList<>(desiredFor(player));
        stack.removeIf(held -> held.bundle().equals(StudioPush.BUNDLE));
        stack.add(pack);
        delivery.apply(player, stack);
    }

    /** Takes a pushed pack back off somebody, leaving the server's own content. */
    private void unpush(String name) {
        Player player = getServer().getPlayerExact(name);
        if (player != null) {
            delivery.apply(player, desiredFor(player));
        }
    }

    /**
     * Tells studio the roster, which it wants whole on every change.
     *
     * <p>Each entry gained a fourth colon-separated field, the cape hash, for
     * the reason {@code PlayerCape} gives: this server holds the only
     * uncached copy of which cape somebody is wearing, and studio bakes that
     * into a pack people then wear. Positional and last, so a sync that has
     * never heard of it reads the first three and drops it.
     */
    private void announceMembers(String code) {
        List<String> entries = new ArrayList<>();
        for (String name : group.recipients(code)) {
            Player player = getServer().getPlayerExact(name);
            if (player != null) {
                entries.add(player.getUniqueId().toString().replace("-", "")
                        + ":" + player.getName() + ":java"
                        + ":" + ai.resourcepack.engine.core.sync.PlayerCape.token(player));
            }
        }
        sync.members(code, entries);
    }

    private void report(CommandSender to, String stage, List<Diagnostic> diagnostics) {
        for (Diagnostic diagnostic : diagnostics) {
            // The logger adds its own [RPEngine], so this one does not.
            String line = diagnostic.severity().name().toLowerCase(Locale.ROOT)
                    + " in " + stage + ": " + diagnostic;
            if (diagnostic.severity() == Diagnostic.Severity.ERROR) {
                getLogger().warning(line);
            } else {
                getLogger().info(line);
            }
            if (!(to instanceof ConsoleCommandSender)) {
                to.sendMessage(EngineCommand.prefix() + line);
            }
        }
    }

    /** "1 item", "2 items". Cheap, and its absence is the sort of thing that reads as unfinished. */
    private static String plural(int count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }

    /**
     * Which bundles a player should be holding.
     *
     * <p>One bundle, from the config, and that is the whole policy for now.
     * Per-world and per-permission selection is a server's decision rather
     * than ours, so it arrives as an API rather than as a guess.
     */
    private List<BuiltPack> desiredFor(Player player) {
        if (defaultBundle.isEmpty()) {
            return List.of();
        }
        for (BuiltPack pack : built) {
            if (pack.bundle().equals(defaultBundle)) {
                return List.of(pack);
            }
        }
        return List.of();
    }

    /**
     * Tells studio who is here, on a server that holds the trusted token.
     *
     * <p>What it buys: a push addressed to a player by uuid, with nobody
     * typing a code. A server without the token announces nothing and pairs
     * the ordinary way.
     */
    private void announcePresence(Player player, boolean here) {
        if (sync == null || !sync.announcesPresence()) {
            return;
        }
        if (here) {
            sync.present(player.getUniqueId(), player.getName(),
                    bedrock.isBedrock(player.getUniqueId()),
                    ai.resourcepack.engine.core.sync.PlayerCape.token(player));
        } else {
            sync.gone(player.getUniqueId());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        announcePresence(event.getPlayer(), true);
        delivery.apply(event.getPlayer(), desiredFor(event.getPlayer()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (reconnecting.remove(event.getPlayer().getUniqueId())) {
            // A Geyser transfer, not a departure. Everything below would undo
            // an apply that is still in flight.
            return;
        }
        announcePresence(event.getPlayer(), false);
        overlays.clear(event.getPlayer());
        if (liquidCommands != null) {
            liquidCommands.forget(event.getPlayer().getUniqueId());
        }
        if (liquids != null) {
            // Dropped rather than reported as leaving: they did not come out
            // of the acid, they left, and a listener told otherwise would
            // undo something for somebody who is not here.
            liquids.forget(event.getPlayer().getUniqueId());
        }
        // A client drops its packs on disconnect, so believing otherwise would
        // mean sending nothing to somebody who has nothing.
        sessions.forget(event.getPlayer().getUniqueId());
        // Studio stops offering to push to somebody who is not here.
        if (sync != null) {
            // If they owned a sync it ends and everybody on it loses the pack,
            // because nobody inherits one. That is the whole reason this is a
            // sync rather than a party.
            for (String name : group.forget(event.getPlayer().getName())) {
                unpush(name);
            }
            sync.forget(event.getPlayer().getName());
        }
    }

}
