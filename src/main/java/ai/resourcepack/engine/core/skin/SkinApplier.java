package ai.resourcepack.engine.core.skin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Puts a signed skin onto a live player, the way SkinRestorer does.
 *
 * Entirely by reflection: {@code Player#setPlayerProfile} and
 * {@code ProfileProperty} are Paper's, and compiling against paper-api would
 * stop this jar loading on Spigot at all, which is a worse trade than one
 * feature reporting "needs-paper".
 *
 * The value/signature pair is opaque here. It has to be signed and has to
 * point at Mojang's own texture host, since the vanilla client refuses skin
 * URLs from anywhere else.
 */
public final class SkinApplier {

    /** Why a skin couldn't be applied, or null when it was. */
    public static final class Result {
        public final boolean ok;
        public final String reason;

        private Result(boolean ok, String reason) {
            this.ok = ok;
            this.reason = reason;
        }

        static Result ok() {
            return new Result(true, null);
        }

        static Result fail(String reason) {
            return new Result(false, reason);
        }
    }

    private final Plugin plugin;

    // Resolved once. All null together when this isn't Paper.
    private final Method getPlayerProfile;
    private final Method setPlayerProfile;
    private final Method setProperty;
    private final Constructor<?> newProfileProperty;
    private final boolean available;

    public SkinApplier(Plugin plugin) {
        this.plugin = plugin;

        Method get = null;
        Method set = null;
        Method prop = null;
        Constructor<?> ctor = null;
        try {
            Class<?> profileClass = Class.forName("com.destroystokyo.paper.profile.PlayerProfile");
            Class<?> propertyClass = Class.forName("com.destroystokyo.paper.profile.ProfileProperty");
            get = Player.class.getMethod("getPlayerProfile");
            set = Player.class.getMethod("setPlayerProfile", profileClass);
            prop = profileClass.getMethod("setProperty", propertyClass);
            ctor = propertyClass.getConstructor(String.class, String.class, String.class);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            plugin.getLogger().info(
                "Skin sync is unavailable: this server is not Paper (or is too old), so a player's skin can't be "
                    + "changed after they join. Everything else works as normal.");
        }

        this.getPlayerProfile = get;
        this.setPlayerProfile = set;
        this.setProperty = prop;
        this.newProfileProperty = ctor;
        this.available = get != null && set != null && prop != null && ctor != null;
    }

    boolean isAvailable() {
        return available;
    }

    /** Apply and make it visible. Main thread only: it re-sends a live entity. */
    public Result apply(Player target, String value, String signature) {
        if (!available) return Result.fail("needs-paper");

        try {
            Object profile = getPlayerProfile.invoke(target);
            // "textures" replaces the existing property rather than stacking
            // beside it, which is what makes this idempotent.
            Object property = newProfileProperty.newInstance("textures", value, signature);
            setProperty.invoke(profile, property);
            setPlayerProfile.invoke(target, profile);
        } catch (ReflectiveOperationException e) {
            plugin.getLogger().warning("Skin sync failed for " + target.getName() + ": " + e.getMessage());
            return Result.fail("apply-failed");
        }

        refreshForOthers(target);
        return Result.ok();
    }

    /**
     * Make everyone else re-render the player. setPlayerProfile updates the
     * profile the server holds, but clients already drawing this player keep
     * the skin they were told about on join; hide/show forces the remove/add
     * player-info pair that carries the new one.
     *
     * It cannot refresh the player's view of themselves. That needs a respawn
     * packet, so NMS, which is the thing this class avoids.
     */
    private void refreshForOthers(Player target) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(target)) continue;
            try {
                viewer.hidePlayer(plugin, target);
                viewer.showPlayer(plugin, target);
            } catch (RuntimeException e) {
                // One viewer failing isn't worth failing the sync; they'll see
                // it on their next reconnect.
                plugin.getLogger().fine("Couldn't refresh " + target.getName() + " for " + viewer.getName());
            }
        }
    }
}
