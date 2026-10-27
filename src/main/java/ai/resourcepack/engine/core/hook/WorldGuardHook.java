package ai.resourcepack.engine.core.hook;

import ai.resourcepack.engine.api.event.ModelBreakEvent;
import ai.resourcepack.engine.api.event.ModelPlaceEvent;
import ai.resourcepack.engine.api.event.ItemUseEvent;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.registry.FlagConflictException;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * Two region flags, so a server can say where this plugin's things may be used.
 *
 * <pre>
 * /rg flag spawn rpengine-place deny
 * /rg flag arena rpengine-use deny
 * </pre>
 *
 * <p><strong>The engine already fires the events this needs.</strong>
 * {@link ModelPlaceEvent}, {@link ModelBreakEvent} and {@link ItemUseEvent}
 * exist precisely so that decisions about somebody's server can be made outside
 * the engine — so this hook is a listener that cancels, with no engine change
 * behind it at all. That is the design working, and it is worth saying because
 * the obvious alternative is a region check baked into placement, which every
 * other protection plugin would then be locked out of.
 *
 * <p><strong>Flags must be registered before WorldGuard loads its regions</strong>,
 * which means {@code onLoad} and not {@code onEnable}. A flag registered late
 * is not merely ignored: the regions have already been parsed, so a region that
 * set it has silently dropped the value, and it comes back as unset for ever.
 * That is why {@link #registerFlags} is separate from {@link #listen}.
 */
public final class WorldGuardHook implements Listener {

    /** May a model be put down or broken here. */
    private static StateFlag placeFlag;

    /** May a custom item be used here. */
    private static StateFlag useFlag;

    private WorldGuardHook() {
    }

    /**
     * Registers the flags, from the plugin's {@code onLoad}.
     *
     * <p>Silent about a conflict: another plugin owning these names is
     * somebody else's decision, and the right answer is to leave their flag
     * alone rather than to fight over it or to refuse to start.
     */
    public static void registerFlags(Plugin plugin) {
        if (plugin.getServer().getPluginManager().getPlugin("WorldGuard") == null) {
            return;
        }
        try {
            FlagRegistry registry = WorldGuard.getInstance().getFlagRegistry();
            placeFlag = register(registry, "rpengine-place");
            useFlag = register(registry, "rpengine-use");
        } catch (NoClassDefFoundError | RuntimeException e) {
            // A WorldGuard too old or too new for these calls. The rest of the
            // plugin does not care and must not fail to load over it.
            plugin.getLogger().info("WorldGuard is present but its flags could not be registered.");
        }
    }

    private static StateFlag register(FlagRegistry registry, String name) {
        try {
            StateFlag flag = new StateFlag(name, true);
            registry.register(flag);
            return flag;
        } catch (FlagConflictException e) {
            Flag<?> existing = registry.get(name);
            return existing instanceof StateFlag ? (StateFlag) existing : null;
        }
    }

    /** Starts enforcing them, from {@code onEnable}. Does nothing if none registered. */
    public static boolean listen(Plugin plugin) {
        if (placeFlag == null && useFlag == null) {
            return false;
        }
        plugin.getServer().getPluginManager().registerEvents(new WorldGuardHook(), plugin);
        return true;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(ModelPlaceEvent event) {
        if (!allowed(event.getPlayer(), event.block().getLocation(), placeFlag)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(ModelBreakEvent event) {
        // A break with no player is the engine tidying up after itself — a
        // purge, a reload — and a region flag is about what PEOPLE may do.
        if (event.player() != null && !allowed(event.player(), event.location(), placeFlag)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onUse(ItemUseEvent event) {
        if (!allowed(event.getPlayer(), event.getPlayer().getLocation(), useFlag)) {
            event.setCancelled(true);
        }
    }

    /**
     * Whether {@code who} may do this here.
     *
     * <p>True when the flag was never registered, when WorldGuard is not
     * answering, or when the player bypasses regions. Every one of those is a
     * "we have nothing to say" rather than a refusal: a protection hook that
     * failed CLOSED would lock a server out of its own content the moment
     * anything went wrong with it.
     */
    private static boolean allowed(Player who, Location where, StateFlag flag) {
        if (flag == null || who == null || where == null) {
            return true;
        }
        try {
            LocalPlayer local = WorldGuardPlugin.inst().wrapPlayer(who);
            if (WorldGuard.getInstance().getPlatform().getSessionManager()
                    .hasBypass(local, BukkitAdapter.adapt(where.getWorld()))) {
                return true;
            }
            RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
            RegionQuery query = container.createQuery();
            return query.testState(BukkitAdapter.adapt(where), local, flag);
        } catch (NoClassDefFoundError | RuntimeException e) {
            return true;
        }
    }
}
