package ai.resourcepack.engine.core.font;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * What a <code>{name}</code> in an overlay comes to. Internal.
 *
 * <h2>Why this exists</h2>
 *
 * A <code>{name}</code> used to have exactly one source: whatever a plugin had
 * last handed {@code Overlays.set}. That is the right primitive and it is not
 * enough on its own, because it means a server owner who has written no code has
 * a placeholder syntax in front of them that can only ever produce nothing — and
 * an empty string is indistinguishable from a broken pack. Every label anybody
 * wrote read as blank.
 *
 * <p>So there are three sources now, asked in this order, and the order is the
 * design:
 *
 * <ol>
 *   <li><b>Whatever a plugin set</b>, because a plugin that took the trouble to
 *       publish a number means that number and must not be overruled by a
 *       built-in that happens to share its name.</li>
 *   <li><b>The built-ins below</b>, which are the questions the server can
 *       already answer about a player without asking anything else.</li>
 *   <li><b>PlaceholderAPI</b>, if it is installed, which is everybody else's
 *       answer to the same problem and the reason this list does not have to
 *       grow forever.</li>
 * </ol>
 *
 * <p>Anything none of the three answers becomes empty, which is
 * {@code OverlayRuntime.fill}'s rule and unchanged: a gap reads as "no value
 * yet" where a leftover brace reads as a broken pack.
 *
 * <h2>What is deliberately not here</h2>
 *
 * Anything needing a second plugin — a balance, a rank, a clan tag. Those are
 * exactly what PlaceholderAPI is for, and re-implementing a handful of them
 * badly would mean a server owner having to know which of two systems owns a
 * given name.
 */
public final class Placeholders {

    private Placeholders() {
    }

    /**
     * Whether PlaceholderAPI is on this server, resolved once.
     *
     * <p>Reflection rather than a compile-time dependency: PAPI is optional, and
     * an engine that will not load without it is one every server owner has to
     * install a plugin for to use an overlay. {@code null} means "not here", and
     * this class then behaves exactly as it did before PAPI was consulted at
     * all.
     */
    private static volatile Method papi;
    private static volatile boolean papiChecked;

    /**
     * The value of one placeholder for one player, or empty if nothing knows.
     *
     * @param set what a plugin published for this player, which wins outright
     */
    public static String resolve(Player viewer, String name, Map<String, String> set) {
        String published = set == null ? null : set.get(name);
        if (published != null) {
            return published;
        }
        if (viewer == null) {
            return "";
        }
        String built = builtIn(viewer, name.toLowerCase(Locale.ROOT));
        if (built != null) {
            return built;
        }
        return papiValue(viewer, name);
    }

    /**
     * The questions the server can answer about a player from what it holds.
     *
     * <p>Null means "not one of ours", which is what sends the name on to
     * PlaceholderAPI. An empty string would have claimed it and answered
     * nothing.
     */
    private static String builtIn(Player viewer, String name) {
        Location at = viewer.getLocation();
        switch (name) {
            case "player":
            case "name":
                return viewer.getName();
            case "displayname":
                return viewer.getDisplayName();
            case "uuid":
                return viewer.getUniqueId().toString();
            case "world":
                return at.getWorld() == null ? "" : at.getWorld().getName();
            case "x":
                return String.valueOf(at.getBlockX());
            case "y":
                return String.valueOf(at.getBlockY());
            case "z":
                return String.valueOf(at.getBlockZ());
            case "health":
                // Rounded UP, so a player one hit from death never reads 0 while
                // still standing. Hearts rather than half-hearts is what the bar
                // above the hotbar shows, and this is the same number.
                return String.valueOf((int) Math.ceil(viewer.getHealth()));
            case "health_max":
                return String.valueOf((int) Math.ceil(maxHealth(viewer)));
            case "health_percent":
                double max = maxHealth(viewer);
                return String.valueOf(max <= 0 ? 0 : Math.round(viewer.getHealth() / max * 100.0));
            case "food":
                return String.valueOf(viewer.getFoodLevel());
            case "level":
                return String.valueOf(viewer.getLevel());
            case "xp":
                return String.valueOf(Math.round(viewer.getExp() * 100));
            case "ping":
                return String.valueOf(viewer.getPing());
            case "gamemode":
                return capitalised(viewer.getGameMode().name());
            case "online":
                return String.valueOf(Bukkit.getOnlinePlayers().size());
            case "max_online":
                return String.valueOf(Bukkit.getMaxPlayers());
            case "air":
                // In seconds, which is what a bar counting down wants. The game
                // holds it in ticks and nobody thinks in ticks.
                return String.valueOf(Math.max(0, viewer.getRemainingAir() / 20));
            case "direction":
                return direction(at.getYaw());
            case "time":
                return clock(at.getWorld() == null ? 0 : at.getWorld().getTime());
            case "day":
                return String.valueOf(at.getWorld() == null ? 0 : at.getWorld().getFullTime() / 24000L);
            default:
                return null;
        }
    }

    /**
     * The player's maximum health.
     *
     * <p>Through {@code Vanilla.maxHealth()}, which is where the engine already
     * keeps the answer to "which spelling of that attribute does this server
     * have" — the constant was renamed in 1.21.3 and naming either one directly
     * is a jar that will not load on half the versions in the floor. Falls back
     * to twenty, which is what it is unless somebody changed it.
     */
    private static double maxHealth(Player viewer) {
        org.bukkit.attribute.Attribute attribute =
                ai.resourcepack.engine.core.version.Vanilla.maxHealth().orElse(null);
        org.bukkit.attribute.AttributeInstance instance =
                attribute == null ? null : viewer.getAttribute(attribute);
        return instance == null ? 20.0 : instance.getValue();
    }

    /** {@code SURVIVAL} as {@code Survival}. */
    private static String capitalised(String constant) {
        return constant.charAt(0) + constant.substring(1).toLowerCase(Locale.ROOT);
    }

    /** Which way they are facing, as a compass point. */
    private static String direction(float yaw) {
        String[] points = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
        return points[(int) Math.floor(((yaw % 360) + 360) % 360 / 45 + 0.5) % 8];
    }

    /** The world's time as a 24-hour clock. Tick 0 is 06:00, which is dawn. */
    private static String clock(long ticks) {
        long hours = ((ticks / 1000) + 6) % 24;
        long minutes = (ticks % 1000) * 60 / 1000;
        return String.format(Locale.ROOT, "%02d:%02d", hours, minutes);
    }

    /**
     * What PlaceholderAPI makes of a name, or empty if it is not installed or
     * does not know it either.
     *
     * <p>Our syntax is braces and PAPI's is percent signs, so the name is
     * re-spelled on the way in. An unknown placeholder comes back from PAPI
     * unchanged — as the literal {@code %name%} — which is the one answer we
     * must not pass on, because a percent-wrapped word on somebody's HUD reads
     * as a broken pack exactly the way a brace-wrapped one did.
     */
    private static String papiValue(Player viewer, String name) {
        Method method = papiMethod();
        if (method == null) {
            return "";
        }
        try {
            String wrapped = "%" + name + "%";
            Object value = method.invoke(null, viewer, wrapped);
            String text = value == null ? "" : value.toString();
            return text.equals(wrapped) ? "" : text;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "";
        }
    }

    private static Method papiMethod() {
        if (papiChecked) {
            return papi;
        }
        synchronized (Placeholders.class) {
            if (!papiChecked) {
                try {
                    if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
                        papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI")
                                .getMethod("setPlaceholders", org.bukkit.OfflinePlayer.class, String.class);
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    papi = null;
                }
                papiChecked = true;
            }
        }
        return papi;
    }

    /** Forgets whether PlaceholderAPI is here. For a reload that installed it. */
    public static void reset() {
        synchronized (Placeholders.class) {
            papi = null;
            papiChecked = false;
        }
    }

    /**
     * Every built-in name, for the diagnostics command and for documentation.
     *
     * <p>Written out rather than derived from the switch above, which is the one
     * duplication here — a switch over strings has no reflectable list. Keep the
     * two together.
     */
    public static final java.util.List<String> BUILT_IN = java.util.List.of(
            "player", "name", "displayname", "uuid", "world", "x", "y", "z",
            "health", "health_max", "health_percent", "food", "level", "xp",
            "ping", "gamemode", "online", "max_online", "air", "direction",
            "time", "day");

    /** Whether anything here can answer this name for this player. For a preview. */
    public static Optional<String> preview(Player viewer, String name) {
        String value = resolve(viewer, name, Map.of());
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }
}
