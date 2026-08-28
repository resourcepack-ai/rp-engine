package ai.resourcepack.engine.core.hook;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.ContentRegistry;
import ai.resourcepack.engine.api.Emotes;
import ai.resourcepack.engine.api.Items;
import ai.resourcepack.engine.core.model.Seats;
import ai.resourcepack.engine.core.serve.BundleSessions;
import ai.resourcepack.engine.core.sync.SyncGroup;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * What this plugin knows, said in the language every configuration-driven
 * server already speaks.
 *
 * <p>A scoreboard, a chat format, a hologram and a menu plugin can none of them
 * call an API. They can all read a placeholder, which is why this is the
 * cheapest possible way for the rest of somebody's server to see what the
 * engine is doing — and why not having it made every one of those integrations
 * a plugin somebody had to write.
 *
 * <p><strong>It answers questions and never performs actions.</strong> A
 * placeholder is evaluated wherever a string is rendered, which can be several
 * times a tick per player on a scoreboard, so everything here is a map lookup
 * or a field read. Nothing here builds a pack, walks the world, or writes.
 *
 * <p><strong>PlaceholderAPI is compileOnly and this class is the only thing
 * that touches it.</strong> A server without it must load this plugin
 * normally, so nothing outside this file names a PlaceholderAPI type and the
 * registration is guarded by whether the class is there at all — see
 * {@link #register}.
 */
public final class Placeholders extends PlaceholderExpansion {

    private static final String YES = "true";
    private static final String NO = "false";

    private final Plugin plugin;
    private final ContentRegistry registry;
    private final Emotes emotes;
    private final Items items;
    private final Seats seats;
    private final BundleSessions sessions;
    private final SyncGroup group;

    private Placeholders(Plugin plugin, ContentRegistry registry, Emotes emotes, Items items,
                         Seats seats, BundleSessions sessions, SyncGroup group) {
        this.plugin = plugin;
        this.registry = registry;
        this.emotes = emotes;
        this.items = items;
        this.seats = seats;
        this.sessions = sessions;
        this.group = group;
    }

    /**
     * Registers the expansion if PlaceholderAPI is installed.
     *
     * <p>The class check is what keeps this optional. {@code getPlugin} alone
     * would be enough to know it is loaded, but naming the type in a field or
     * a return would make this method itself unloadable on a server without
     * it — so the whole expansion is behind a {@link Class#forName} and a
     * catch, and the failure mode on such a server is one debug line rather
     * than a plugin that will not enable.
     *
     * @return whether it registered
     */
    public static boolean register(Plugin plugin, ContentRegistry registry, Emotes emotes,
                                   Items items, Seats seats, BundleSessions sessions, SyncGroup group) {
        if (plugin.getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return false;
        }
        try {
            Class.forName("me.clip.placeholderapi.expansion.PlaceholderExpansion");
        } catch (ClassNotFoundException e) {
            // Installed but not loadable, which is somebody's broken install
            // rather than our problem to solve.
            return false;
        }
        return new Placeholders(plugin, registry, emotes, items, seats, sessions, group).register();
    }

    @Override
    public String getIdentifier() {
        // The prefix every placeholder here is written with, lowercase
        // because PlaceholderAPI folds one before it looks it up.
        return "rpengine";
    }

    @Override
    public String getAuthor() {
        return "ResourcePack AI";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        // Survives a PlaceholderAPI reload. Without this the expansion is
        // unregistered by /papi reload and every placeholder on the server
        // silently starts rendering as its own literal text.
        return true;
    }

    /**
     * One placeholder.
     *
     * <p>Null means "not one of ours", which PlaceholderAPI renders as the
     * text the author typed — the right answer for a typo, because it is
     * visible and traceable rather than looking like an empty value.
     */
    @Override
    public String onRequest(OfflinePlayer who, String params) {
        String name = params == null ? "" : params.toLowerCase(Locale.ROOT);

        // Server-wide, so they answer for an offline player and for the
        // console rendering a message about somebody.
        switch (name) {
            case "version":
                return plugin.getDescription().getVersion();
            case "items":
                return count(ContentKind.ITEM);
            case "models":
                return count(ContentKind.MODEL);
            case "sounds":
                return count(ContentKind.SOUND);
            case "screens":
                return count(ContentKind.SCREEN);
            case "huds":
                return count(ContentKind.HUD);
            case "entities":
                return count(ContentKind.ENTITY);
            case "recipes":
                return count(ContentKind.RECIPE);
            case "content":
                return String.valueOf(registry.ids().size());
            case "namespaces":
                return String.valueOf(registry.namespaces().size());
            case "emotes":
                return String.valueOf(emotes.ids().size());
            default:
                break;
        }

        Player player = who == null ? null : who.getPlayer();
        if (player == null) {
            // Everything below is about somebody who is here. Empty rather
            // than "false", which would be a claim about an absent player.
            return "";
        }

        switch (name) {
            case "emoting":
                return yesNo(emotes.isEmoting(player.getUniqueId()));
            case "can_emote":
                return yesNo(emotes.canPerform(player));
            case "seated":
                return yesNo(seats.isSeated(player));
            case "holding":
                // The content id of what is in their hand, or empty for a
                // vanilla item — which is a fact worth being able to test.
                return heldId(player).map(ContentId::toString).orElse("");
            case "holding_name":
                return heldId(player).map(ContentId::path).orElse("");
            case "bundle":
                return topBundle(player);
            case "bundles":
                return String.valueOf(sessions.held(player.getUniqueId()).size());
            case "synced":
                return yesNo(group.receiving(player.getName()).isPresent());
            case "sync_code":
                return group.receiving(player.getName()).orElse("");
            case "sync_owner":
                return group.receiving(player.getName()).flatMap(group::owner).orElse("");
            case "sync_size":
                return group.receiving(player.getName())
                        .map(code -> String.valueOf(group.recipients(code).size()))
                        .orElse("0");
            default:
                break;
        }

        // Parameterised: %rpengine_holds_<bundle>% and %rpengine_has_<id>%.
        if (name.startsWith("holds_")) {
            return yesNo(sessions.holds(player.getUniqueId(), params.substring("holds_".length())));
        }
        if (name.startsWith("has_")) {
            return yesNo(ContentId.parse(params.substring("has_".length()))
                    .filter(registry::contains)
                    .isPresent());
        }
        return null;
    }

    private String count(ContentKind kind) {
        return String.valueOf(registry.ids(kind).size());
    }

    private Optional<ContentId> heldId(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        return items.idOf(held);
    }

    /**
     * The bundle a player is actually seeing.
     *
     * <p>The LAST one, because the held list is a stack and a later pack
     * overrides an earlier one — so the top of it is what is on screen, and
     * the first would be the one most likely to have been overridden.
     */
    private String topBundle(Player player) {
        List<BundleSessions.Held> held = sessions.held(player.getUniqueId());
        return held.isEmpty() ? "" : held.get(held.size() - 1).bundle();
    }

    private static String yesNo(boolean value) {
        return value ? YES : NO;
    }
}
