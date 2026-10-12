package ai.resourcepack.engine.core.font;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.chat.ComponentSerializer;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Boss bars whose title is a component rather than a legacy string, through
 * Paper's Adventure API.
 *
 * <p><b>Why a boss bar needs this at all.</b> Bukkit's {@code BossBar} takes
 * its title as a legacy string, and legacy formatting has codes for colour and
 * none for a font. An overlay whose picture or negative-space characters live
 * in a font of their own (any overlay with a {@code font} other than the
 * default) loses that font on the way: the picture glyph is looked up in the
 * default font, which does not have it, so the picture is missing and every
 * shift character draws as a missing-glyph box, and with the shifts gone
 * every run after them lands in the wrong place. The action bar never had
 * this problem because it already takes components.
 *
 * <p>Adventure's boss bar takes a {@code Component}, which keeps fonts, so on
 * a server that has it the boss bar is sent exactly the components the action
 * bar is. A server without it (Spigot) keeps the legacy path, which is still
 * right for an overlay that needs no font.
 *
 * <p>Everything here is reflective. The engine compiles against Spigot so that
 * it runs on Spigot, and one class is not worth a second API on the classpath;
 * the price is that a renamed Paper method shows up as "not available" and
 * falls back to the legacy title rather than failing to compile. It is not a
 * {@code Feature}: Adventure is on Paper at every version the engine
 * supports and on Spigot at none, so there is no version floor to name.
 */
final class AdventureBossBars {

    private final Method deserialize;
    private final Object gson;
    private final Method create;
    private final Method rename;
    private final Method show;
    private final Method hide;
    private final Object white;
    private final Object progress;
    private final Map<UUID, Object> bars = new ConcurrentHashMap<>();

    private AdventureBossBars(Method deserialize, Object gson, Method create, Method rename,
                              Method show, Method hide, Object white, Object progress) {
        this.deserialize = deserialize;
        this.gson = gson;
        this.create = create;
        this.rename = rename;
        this.show = show;
        this.hide = hide;
        this.white = white;
        this.progress = progress;
    }

    /** Adventure's boss bars, or empty on a server that has none. */
    static Optional<AdventureBossBars> find() {
        try {
            Class<?> component = Class.forName("net.kyori.adventure.text.Component");
            Class<?> bar = Class.forName("net.kyori.adventure.bossbar.BossBar");
            Class<?> color = Class.forName("net.kyori.adventure.bossbar.BossBar$Color");
            Class<?> overlay = Class.forName("net.kyori.adventure.bossbar.BossBar$Overlay");
            Class<?> audience = Class.forName("net.kyori.adventure.audience.Audience");
            Class<?> serializer = Class.forName(
                    "net.kyori.adventure.text.serializer.gson.GsonComponentSerializer");
            if (!audience.isAssignableFrom(Player.class)) {
                // Adventure on the classpath from some other plugin, on a
                // server whose players are not audiences. Nothing to show to.
                return Optional.empty();
            }
            Object gson = serializer.getMethod("gson").invoke(null);
            Method deserialize = serializer.getMethod("deserialize", Object.class);
            Method create = bar.getMethod("bossBar", component, float.class, color, overlay);
            Method rename = bar.getMethod("name", component);
            Method show = audience.getMethod("showBossBar", bar);
            Method hide = audience.getMethod("hideBossBar", bar);
            Object white = color.getField("WHITE").get(null);
            Object progress = overlay.getField("PROGRESS").get(null);
            return Optional.of(new AdventureBossBars(deserialize, gson, create, rename, show, hide, white, progress));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Puts this title on the player's boss bar, creating and showing the bar
     * the first time. False if Adventure refused, so the caller can fall back.
     *
     * <p>Progress zero, white, plain: the bar is a rendering surface here
     * rather than a meter, the same bar the legacy path draws.
     */
    boolean show(Player viewer, BaseComponent[] title) {
        try {
            // Through JSON because that is the one format both component
            // models agree on exactly, fonts and hex colours included.
            Object name = deserialize.invoke(gson, ComponentSerializer.toString(title));
            Object bar = bars.get(viewer.getUniqueId());
            if (bar == null) {
                bar = create.invoke(null, name, 0f, white, progress);
                bars.put(viewer.getUniqueId(), bar);
                show.invoke(viewer, bar);
            } else {
                rename.invoke(bar, name);
            }
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return false;
        }
    }

    /** Takes this player's bar away, if they have one. */
    void clear(Player viewer) {
        Object bar = bars.remove(viewer.getUniqueId());
        if (bar == null) {
            return;
        }
        try {
            hide.invoke(viewer, bar);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            // A player who has left has no bar to hide; there is nothing else
            // this can fail on that a retry would fix.
        }
    }
}
