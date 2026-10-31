package ai.resourcepack.engine.core.skill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * A mob's threat table, MythicMobs' ThreatTables module: who has hurt it and
 * how much, and so whom it fights.
 *
 * <ul>
 *   <li>Damage dealt to the mob adds that much threat to whoever dealt it;
 *   {@code threat{amount}} adds (or sets, removes, multiplies) by hand.</li>
 *   <li>The mob fights the top of the table, but a new top only takes over
 *   when it holds more than {@link #SWITCH} times the current target's threat
 *   (MythicMobs' 110% rule), so two players trading blows do not make it spin
 *   between them every hit.</li>
 *   <li>Somebody who leaves, dies, goes out of reach or stops being a fair
 *   target is dropped; an empty table is a mob out of combat.</li>
 * </ul>
 *
 * <p>Free of Bukkit: keyed by uuid, with the live checks handed in, so the
 * arithmetic is {@code ThreatTableTest}'s.
 */
final class ThreatTable {

    /** How much more threat a new top needs before the mob turns to it. */
    static final double SWITCH = 1.1;
    /** The most entries a table keeps; the least threatening goes first. */
    static final int MAX = 64;

    private final Map<UUID, Double> threat = new LinkedHashMap<>();

    double get(UUID who) {
        return threat.getOrDefault(who, 0.0);
    }

    boolean isEmpty() {
        return threat.isEmpty();
    }

    int size() {
        return threat.size();
    }

    List<UUID> entries() {
        return new ArrayList<>(threat.keySet());
    }

    void add(UUID who, double amount) {
        if (who == null || !Double.isFinite(amount)) {
            return;
        }
        double now = Math.max(0, get(who) + amount);
        threat.put(who, now);
        trim();
    }

    void set(UUID who, double amount) {
        if (who == null || !Double.isFinite(amount)) {
            return;
        }
        threat.put(who, Math.max(0, amount));
        trim();
    }

    void multiply(UUID who, double factor) {
        if (threat.containsKey(who) && Double.isFinite(factor)) {
            threat.put(who, Math.max(0, get(who) * factor));
        }
    }

    void remove(UUID who) {
        threat.remove(who);
    }

    void clear() {
        threat.clear();
    }

    /** Drops every entry {@code gone} says is no longer a fair target. */
    void prune(Predicate<UUID> gone) {
        threat.keySet().removeIf(gone);
    }

    /** The most threatening, or null for an empty table. Ties go to whoever got there first. */
    UUID top() {
        UUID best = null;
        double most = -1;
        for (Map.Entry<UUID, Double> entry : threat.entrySet()) {
            if (entry.getValue() > most) {
                most = entry.getValue();
                best = entry.getKey();
            }
        }
        return best;
    }

    /**
     * Whom the mob should be fighting, given it fights {@code current} now:
     * the top, if it beats {@code current} by {@link #SWITCH} (or there is no
     * current, or the current is off the table), else {@code current}.
     */
    UUID choose(UUID current) {
        UUID top = top();
        if (top == null) {
            return null;
        }
        if (current == null || !threat.containsKey(current) || current.equals(top)) {
            return top;
        }
        return get(top) > get(current) * SWITCH ? top : current;
    }

    /** One entry at random, weighted evenly; null for an empty table. */
    UUID random(Random random) {
        if (threat.isEmpty()) {
            return null;
        }
        List<UUID> all = entries();
        return all.get(random.nextInt(all.size()));
    }

    private void trim() {
        while (threat.size() > MAX) {
            UUID least = null;
            double lowest = Double.MAX_VALUE;
            for (Map.Entry<UUID, Double> entry : threat.entrySet()) {
                if (entry.getValue() < lowest) {
                    lowest = entry.getValue();
                    least = entry.getKey();
                }
            }
            threat.remove(least);
        }
    }
}
