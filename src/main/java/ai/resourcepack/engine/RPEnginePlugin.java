package ai.resourcepack.engine;

import ai.resourcepack.engine.api.BuildReport;
import ai.resourcepack.engine.api.BuiltPack;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.ContentRegistry;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.core.content.ContentFolderLoader;
import ai.resourcepack.engine.core.pack.PackBuilder;
import ai.resourcepack.engine.core.registry.ContentRegistryImpl;
import ai.resourcepack.engine.core.serve.BundleSessions;
import ai.resourcepack.engine.core.serve.PackDelivery;
import ai.resourcepack.engine.core.serve.PackHost;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    private final ContentRegistryImpl registry = new ContentRegistryImpl();
    private final BundleSessions sessions = new BundleSessions();

    private PackHost host;
    private PackDelivery delivery;
    private List<BuiltPack> built = List.of();
    private String defaultBundle = "";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        startHost();
        rebuild(getServer().getConsoleSender());
    }

    @Override
    public void onDisable() {
        if (host != null) {
            host.stop();
        }
    }

    /** What this server holds. The supported way in for another plugin. */
    public ContentRegistry registry() {
        return registry;
    }

    private void startHost() {
        defaultBundle = getConfig().getString("default-bundle", "");
        String address = getConfig().getString("host.public-address", "http://127.0.0.1:8181");
        host = new PackHost(address);
        delivery = new PackDelivery(sessions, host,
                getConfig().getString("prompt.message", ""),
                getConfig().getBoolean("prompt.force", false));

        if (!getConfig().getBoolean("host.enabled", true)) {
            getLogger().info("Pack hosting is off. Packs are built but not served.");
            return;
        }
        try {
            int port = host.start(getConfig().getInt("host.port", 8181));
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
        Path content = getDataFolder().toPath().resolve("content");
        Path packs = getDataFolder().toPath().resolve("packs");
        try {
            Files.createDirectories(content);
        } catch (IOException e) {
            to.sendMessage("[RPEngine] Could not create " + content + ": " + e.getMessage());
            return;
        }

        // Everything goes, then the folder is read again. See clear().
        registry.clear();

        LoadReport loaded = new ContentFolderLoader(registry).load(content, ContentSource.AUTHORED);
        report(to, "content", loaded.diagnostics());

        BuildReport builtReport = new PackBuilder(
                getConfig().getInt("pack.format", PackBuilder.PACK_FORMAT),
                getConfig().getString("pack.description", "RP Engine"))
                .build(content, packs, loaded);
        report(to, "build", builtReport.diagnostics());

        built = builtReport.packs();
        for (BuiltPack pack : built) {
            host.register(pack);
        }

        to.sendMessage("[RPEngine] " + loaded.packs().size() + " packs, "
                + loaded.definitions().size() + " definitions, "
                + built.size() + " bundles built.");

        // Everybody online is holding a pack that may no longer exist, so they
        // are re-sent before they notice. Nothing is sent to a player whose
        // stack is already right.
        for (Player player : getServer().getOnlinePlayers()) {
            delivery.apply(player, desiredFor(player));
        }
    }

    private void report(CommandSender to, String stage, List<Diagnostic> diagnostics) {
        for (Diagnostic diagnostic : diagnostics) {
            String line = "[RPEngine] " + diagnostic.severity().name().toLowerCase(Locale.ROOT)
                    + " in " + stage + ": " + diagnostic;
            if (diagnostic.severity() == Diagnostic.Severity.ERROR) {
                getLogger().warning(line);
            } else {
                getLogger().info(line);
            }
            if (!(to instanceof org.bukkit.command.ConsoleCommandSender)) {
                to.sendMessage(line);
            }
        }
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

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        delivery.apply(event.getPlayer(), desiredFor(event.getPlayer()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // A client drops its packs on disconnect, so believing otherwise would
        // mean sending nothing to somebody who has nothing.
        sessions.forget(event.getPlayer().getUniqueId());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload":
                rebuild(sender);
                return true;
            case "bundles":
                if (built.isEmpty()) {
                    sender.sendMessage("[RPEngine] No bundles built.");
                }
                for (BuiltPack pack : built) {
                    sender.sendMessage("[RPEngine] " + pack.bundle() + "  " + pack.entries() + " files, "
                            + pack.size() + " bytes, " + pack.sha1().substring(0, 8)
                            + "  " + host.url(pack.bundle()).orElse("not served"));
                }
                return true;
            case "push":
                if (!(sender instanceof Player)) {
                    sender.sendMessage("[RPEngine] Only a player can be pushed a pack.");
                    return true;
                }
                Player player = (Player) sender;
                sessions.forget(player.getUniqueId());
                sender.sendMessage("[RPEngine] " + (delivery.apply(player, desiredFor(player))
                        ? "Sent." : "Nothing to send."));
                return true;
            default:
                sender.sendMessage("[RPEngine] " + registry.ids().size() + " ids across "
                        + registry.namespaces().size() + " namespaces, " + built.size() + " bundles.");
                List<String> kinds = new ArrayList<>();
                for (ContentKind kind : ContentKind.values()) {
                    int count = registry.ids(kind).size();
                    if (count > 0) {
                        kinds.add(count + " " + kind.name().toLowerCase(Locale.ROOT));
                    }
                }
                sender.sendMessage("[RPEngine] " + (kinds.isEmpty() ? "nothing loaded" : String.join(", ", kinds)));
                sender.sendMessage("[RPEngine] /rpengine reload | bundles | push");
                return true;
        }
    }
}
