package ai.resourcepack.engine.core.font;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.OverlayInfo;
import ai.resourcepack.engine.api.OverlayTrigger;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What each player is wearing, and the loop that keeps it on screen. Internal;
 * {@link ai.resourcepack.engine.api.Overlays} is the face of it.
 *
 * <h2>Why there is a loop at all</h2>
 *
 * The action bar fades after about three seconds. A picture that is meant to
 * stay therefore has to be re-sent, which makes an overlay a thing a player
 * WEARS rather than a message they were sent — and that is the whole design:
 * {@link #show} adds to a set, {@link #hide} takes away, and the loop draws
 * whatever is in the set. Nothing here is a one-shot.
 *
 * <h2>Why the top one wins</h2>
 *
 * There is one action bar. Two overlays cannot both have it, and merging them
 * would mean recomputing every negative-space shift in both runs — which is
 * arithmetic Studio did when it built them and which this engine deliberately
 * does not repeat (see {@code OverlayInfo.title()}). So the most recently shown
 * overlay is the one drawn, and the rest wait underneath: a caller that shows a
 * second one gets the second one, and hiding it uncovers the first.
 *
 * <p>That is a real limitation rather than a resting place. Merging is what a
 * composed run would need, and it belongs with the font-glyph backend that can
 * compute shifts, not here.
 *
 * <h2>Values</h2>
 *
 * Held per player rather than per overlay, because a number like "mana" means
 * the same thing to every overlay that prints it. Substituted at draw time, so
 * setting several in a row costs one draw.
 */
public final class OverlayRuntime {

    /**
     * How often the action bar is refreshed, in ticks.
     *
     * <p>Comfortably inside the client's own fade (about 60 ticks) so the
     * picture never dims between draws, and not so often that a server with a
     * hundred wearers spends its tick budget on packets nobody asked for.
     */
    private static final long PERIOD_TICKS = 30L;

    private final Overlays overlays;
    private final Map<UUID, Set<ContentId>> worn = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, String>> values = new ConcurrentHashMap<>();
    /**
     * Which overlays a STATE trigger put on, per player.
     *
     * <p>Kept apart from {@link #worn} so a trigger only ever takes off what it
     * itself put on. Without it, standing up out of a crouch would remove an
     * overlay a plugin had shown deliberately.
     */
    private final Map<UUID, Set<ContentId>> triggered = new ConcurrentHashMap<>();
    private BukkitTask task;

    public OverlayRuntime(Overlays overlays) {
        this.overlays = overlays;
    }

    /** Starts the redraw loop. Idempotent. */
    public void start(Plugin plugin) {
        if (task != null) {
            return;
        }
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, PERIOD_TICKS, PERIOD_TICKS);
    }

    /** Stops the loop and forgets everything. For disable and for reload. */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        worn.clear();
        values.clear();
        triggered.clear();
    }

    private void tick() {
        // Over the ONLINE players rather than over who is wearing something:
        // a state trigger has to be able to turn an overlay ON for somebody
        // wearing nothing, which a loop over wearers can never do.
        for (Player viewer : org.bukkit.Bukkit.getOnlinePlayers()) {
            applyStateTriggers(viewer);
            draw(viewer);
        }
        // Anyone who left takes their set with them. An overlay is something a
        // caller put on; a caller that wants it back on login has a JOIN
        // trigger or a join handler.
        worn.keySet().removeIf(id -> org.bukkit.Bukkit.getPlayer(id) == null);
        values.keySet().removeIf(id -> org.bukkit.Bukkit.getPlayer(id) == null);
        triggered.keySet().removeIf(id -> org.bukkit.Bukkit.getPlayer(id) == null);
    }

    /**
     * Turns state-triggered overlays on and off for one player.
     *
     * <p>Only ever touches overlays it put there itself — {@link #triggered} is
     * that record. Without it, crouching once and standing up would take off an
     * overlay a plugin had shown deliberately, and the two ways in would fight
     * over the same screen.
     */
    private void applyStateTriggers(Player viewer) {
        Set<ContentId> mine = triggered.computeIfAbsent(viewer.getUniqueId(),
                key -> Collections.synchronizedSet(new LinkedHashSet<>()));
        for (ContentId id : overlays.hudIds()) {
            Optional<OverlayInfo> info = overlays.hud(id);
            if (info.isEmpty()) {
                continue;
            }
            List<OverlayTrigger> triggers = info.get().triggers();
            if (triggers.isEmpty()) {
                continue;
            }
            boolean wanted = false;
            boolean hasState = false;
            for (OverlayTrigger trigger : triggers) {
                if (!trigger.isState()) {
                    continue;
                }
                hasState = true;
                if (holds(viewer, trigger)) {
                    wanted = true;
                    break;
                }
            }
            if (!hasState) {
                continue;
            }
            if (wanted && mine.add(id)) {
                show(viewer, id);
            } else if (!wanted && mine.remove(id)) {
                hide(viewer, id);
            }
        }
    }

    /** Whether one rule holds for this player, right now. */
    private static boolean holds(Player viewer, OverlayTrigger trigger) {
        if (!trigger.permission().isEmpty() && !viewer.hasPermission(trigger.permission())) {
            return false;
        }
        switch (trigger.kind()) {
            case ALWAYS:
                return true;
            case SNEAK:
                return viewer.isSneaking();
            case HOLDING:
                return holding(viewer, trigger.item());
            default:
                // JOIN is not a state and never reaches here.
                return false;
        }
    }

    /**
     * Whether they are holding the named item, in either hand.
     *
     * <p>Matched on the VANILLA MATERIAL, case-insensitively, so
     * {@code diamond_sword} and {@code DIAMOND_SWORD} both work. A custom
     * item's content id does NOT match yet — that needs the item registry,
     * which this class deliberately does not hold — so a rule naming one never
     * fires. That is a gap rather than a trap: nothing throws, and a server
     * owner sees an overlay that does not appear rather than a broken one.
     */
    private static boolean holding(Player viewer, String item) {
        return matches(viewer.getInventory().getItemInMainHand(), item)
                || matches(viewer.getInventory().getItemInOffHand(), item);
    }

    private static boolean matches(org.bukkit.inventory.ItemStack stack, String item) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        return stack.getType().name().equalsIgnoreCase(item);
    }

    /**
     * Fires the JOIN triggers for a player who has just arrived.
     *
     * <p>A moment rather than a state, so this is a one-shot: what it shows
     * stays until something hides it, and nothing here takes it off again.
     */
    public void onJoin(Player viewer) {
        for (ContentId id : overlays.hudIds()) {
            overlays.hud(id).ifPresent(info -> {
                for (OverlayTrigger trigger : info.triggers()) {
                    if (trigger.kind() == OverlayTrigger.Kind.JOIN && holds(viewer, joinAsState(trigger))) {
                        show(viewer, id);
                        return;
                    }
                }
            });
        }
    }

    /**
     * A JOIN rule as something {@link #holds} can answer.
     *
     * <p>{@code holds} switches on the kind and JOIN is not a state, so asking
     * it directly would always be false. What a JOIN rule still has is its
     * permission gate, and this is how that one part gets asked.
     */
    private static OverlayTrigger joinAsState(OverlayTrigger trigger) {
        return OverlayTrigger.of(OverlayTrigger.Kind.ALWAYS, trigger.item(), trigger.permission());
    }

    /** Draws whatever this player is wearing, right now. */
    public void draw(Player viewer) {
        top(viewer).flatMap(overlays::hud).ifPresent(info -> overlays.send(viewer, info, valuesOf(viewer)));
    }

    /** The overlay actually on screen — the last one shown. */
    private Optional<ContentId> top(Player viewer) {
        Set<ContentId> set = worn.get(viewer.getUniqueId());
        if (set == null || set.isEmpty()) {
            return Optional.empty();
        }
        ContentId last = null;
        for (ContentId id : set) {
            last = id;
        }
        return Optional.ofNullable(last);
    }

    public boolean show(Player viewer, ContentId id) {
        if (viewer == null || !viewer.isOnline() || id == null || overlays.hud(id).isEmpty()) {
            return false;
        }
        // A LinkedHashSet keyed on insertion order, and re-showing something
        // already worn deliberately does NOT move it to the top: "make sure it
        // is up" is the common call, and having it steal the screen from
        // whatever was shown after it would make that call unsafe to repeat.
        worn.computeIfAbsent(viewer.getUniqueId(), key -> Collections.synchronizedSet(new LinkedHashSet<>())).add(id);
        draw(viewer);
        return true;
    }

    public boolean hide(Player viewer, ContentId id) {
        if (viewer == null || id == null) {
            return false;
        }
        Set<ContentId> set = worn.get(viewer.getUniqueId());
        if (set == null || !set.remove(id)) {
            return false;
        }
        // Whatever was underneath comes back on the next draw; if there is
        // nothing underneath, the bar fades on its own. Clearing it explicitly
        // would mean sending an empty action bar, which stamps on anything else
        // that legitimately wrote there — an item's message, a speedometer.
        if (!set.isEmpty()) {
            draw(viewer);
        }
        return true;
    }

    public void hideAll(Player viewer) {
        if (viewer == null) {
            return;
        }
        worn.remove(viewer.getUniqueId());
        // Forgotten too, or a state trigger that is still holding would not
        // put its overlay back: `applyStateTriggers` only shows what it has not
        // already recorded as shown.
        triggered.remove(viewer.getUniqueId());
    }

    public boolean isShowing(Player viewer, ContentId id) {
        Set<ContentId> set = viewer == null ? null : worn.get(viewer.getUniqueId());
        return set != null && id != null && set.contains(id);
    }

    public Collection<ContentId> showing(Player viewer) {
        Set<ContentId> set = viewer == null ? null : worn.get(viewer.getUniqueId());
        return set == null ? List.of() : List.copyOf(set);
    }

    public void set(Player viewer, String name, String value) {
        if (viewer == null || name == null || name.isEmpty()) {
            return;
        }
        Map<String, String> map = values.computeIfAbsent(viewer.getUniqueId(), key -> new ConcurrentHashMap<>());
        if (value == null) {
            map.remove(name);
        } else {
            map.put(name, value);
        }
        // Not drawn here. The loop is along in at most a second and a half, and
        // a caller updating six values in a row must not send six packets.
    }

    public Optional<String> value(Player viewer, String name) {
        Map<String, String> map = viewer == null ? null : values.get(viewer.getUniqueId());
        return map == null || name == null ? Optional.empty() : Optional.ofNullable(map.get(name));
    }

    private Map<String, String> valuesOf(Player viewer) {
        Map<String, String> map = values.get(viewer.getUniqueId());
        return map == null ? Map.of() : new LinkedHashMap<>(map);
    }

    /**
     * Fills {@code {name}} placeholders from a player's values.
     *
     * <p>An unset placeholder becomes empty rather than staying as
     * {@code {mana}} — a gap in a HUD reads as "no value yet", where a brace
     * reads as a broken pack, and the second is the one people report.
     */
    static String fill(String text, Map<String, String> values) {
        if (text == null || text.isEmpty() || text.indexOf('{') < 0) {
            return text == null ? "" : text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int at = 0;
        while (at < text.length()) {
            int open = text.indexOf('{', at);
            if (open < 0) {
                out.append(text, at, text.length());
                break;
            }
            int close = text.indexOf('}', open + 1);
            if (close < 0) {
                // An unclosed brace is text, not a placeholder. Somebody wrote
                // it on purpose or wrote it wrong, and either way eating the
                // rest of the line would be worse than showing it.
                out.append(text, at, text.length());
                break;
            }
            out.append(text, at, open);
            String name = text.substring(open + 1, close);
            out.append(values.getOrDefault(name, ""));
            at = close + 1;
        }
        return out.toString();
    }

    /** Every id anybody is wearing, for diagnostics. */
    public Collection<UUID> wearers() {
        return new ArrayList<>(worn.keySet());
    }
}
