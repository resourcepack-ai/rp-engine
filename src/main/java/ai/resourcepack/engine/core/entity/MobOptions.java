package ai.resourcepack.engine.core.entity;

import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What a hand-authored mob says beyond {@link ai.resourcepack.engine.api.EntityInfo}:
 * the MythicMobs options a server porting its mobs expects (armour, equipment,
 * damage modifiers, despawning, a faction, levels, kill messages, a mount) and
 * its loot in MythicMobs' line form.
 *
 * <p>Engine-internal on purpose. {@code EntityInfo} is the API and what a
 * Studio push carries; these are the content folder's, read by
 * {@link CustomEntities} by id. Free of Bukkit apart from
 * {@code EntityDamageEvent.DamageCause}'s names, which resolve without a
 * server.
 */
public final class MobOptions {

    /** Every option at its default: what a mob with none of these keys behaves as. */
    public static final MobOptions NONE = new MobOptions();

    /** Equipment slots, as written, and MythicMobs' numbers for them (0 the hand to 5 the off hand). */
    public static final List<String> SLOTS = List.of("hand", "feet", "legs", "chest", "head", "offhand");
    public static final List<String> DESPAWN = List.of("never", "natural", "chunk");
    public static final List<String> PER_LEVEL = List.of("health", "damage", "armor", "speed", "power");
    public static final List<String> PREVENT = List.of("leash", "rename", "pickup");

    /** -1 is unset: the mob's own. */
    double armor = -1;
    double armorToughness = -1;
    final Map<String, Equip> equipment = new LinkedHashMap<>();
    final Map<String, Double> damageModifiers = new LinkedHashMap<>();
    /** Null is the old rule: never when commanded, natural when it spawned on its own. */
    String despawn;
    String faction;
    int levelMin = 1;
    int levelMax = 1;
    final Map<String, Double> perLevel = new LinkedHashMap<>();
    boolean invincible;
    Boolean collidable;
    Boolean gravity;
    final Set<String> prevent = new LinkedHashSet<>();
    final List<String> killMessages = new ArrayList<>();
    String mount;
    /** The whole {@code drops:} list, tables and commands included; null when the mob has none. */
    List<MobLoot.Line> loot;
    int xpMin;
    int xpMax;
    boolean barFog;
    boolean barDarken;
    boolean barMusic;

    /** One equipment slot's item and how often it drops. */
    public static final class Equip {
        final String item;
        final double dropChance;

        Equip(String item, double dropChance) {
            this.item = item;
            this.dropChance = dropChance;
        }

        @Override
        public String toString() {
            return item + (dropChance > 0 ? " @" + dropChance : "");
        }
    }

    public double armor() {
        return armor;
    }

    public double armorToughness() {
        return armorToughness;
    }

    public Map<String, Equip> equipment() {
        return equipment;
    }

    public Map<String, Double> damageModifiers() {
        return damageModifiers;
    }

    public Optional<String> despawn() {
        return Optional.ofNullable(despawn);
    }

    public Optional<String> faction() {
        return Optional.ofNullable(faction);
    }

    public int levelMin() {
        return levelMin;
    }

    public int levelMax() {
        return levelMax;
    }

    /** What one level above the first adds to {@code key} (health, damage, armor, speed, power). */
    public double perLevel(String key) {
        return perLevel.getOrDefault(key, 0.0);
    }

    public boolean invincible() {
        return invincible;
    }

    public boolean prevents(String what) {
        return prevent.contains(what);
    }

    public List<String> killMessages() {
        return killMessages;
    }

    public Optional<String> mount() {
        return Optional.ofNullable(mount);
    }

    /** The damage a hit of {@code cause} does, times this; 1 when the cause is not listed. */
    public double modifier(String cause) {
        Double value = damageModifiers.get(cause);
        return value == null ? 1 : value;
    }

    /** {@code power} times what {@code level} adds to it: the multiplier a skill's amounts take. */
    public double levelPower(int level) {
        return 1 + Math.max(0, level - 1) * perLevel("power");
    }

    // --- parsing --------------------------------------------------------

    /** The options in {@code body}; problems go to {@code diagnostics}. */
    static MobOptions parse(DefinitionNode body, String origin, String where, List<Diagnostic> diagnostics) {
        MobOptions out = new MobOptions();
        out.armor = number(body, "armor", -1, 0, 30, origin, where, diagnostics);
        out.armorToughness = number(body, "armor-toughness", -1, 0, 20, origin, where, diagnostics);
        equipment(body, out, origin, where, diagnostics);
        damageModifiers(body, out, origin, where, diagnostics);
        despawn(body, out, origin, where, diagnostics);
        out.faction = body.string("faction").map(String::trim).filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT)).orElse(null);
        level(body, out, origin, where, diagnostics);
        out.invincible = body.bool("invincible").orElse(Boolean.FALSE);
        out.collidable = body.bool("collidable").orElse(null);
        out.gravity = body.bool("gravity").orElse(null);
        for (String what : body.strings("prevent")) {
            String key = what.trim().toLowerCase(Locale.ROOT).replace("prevent", "").replace("-", "")
                    .replace("leashing", "leash").replace("renaming", "rename").replace("itempickup", "pickup");
            if (PREVENT.contains(key)) {
                out.prevent.add(key);
            } else {
                diagnostics.add(Diagnostic.warning(origin, where, "prevent: " + what + " is not one of "
                        + String.join(", ", PREVENT) + "."));
            }
        }
        out.killMessages.addAll(body.strings("kill-messages"));
        out.mount = body.string("mount").map(String::trim).filter(s -> !s.isEmpty()).orElse(null);
        Optional<DefinitionNode> bar = body.node("boss-bar");
        if (bar.isPresent()) {
            out.barFog = bar.get().bool("fog").orElse(bar.get().bool("create-fog").orElse(false));
            out.barDarken = bar.get().bool("darken-sky").orElse(bar.get().bool("darken").orElse(false));
            out.barMusic = bar.get().bool("music").orElse(bar.get().bool("play-music").orElse(false));
        }
        return out;
    }

    /** {@code xp: 5} or {@code xp: 5to10}, into the options; the plain number is also what EntityInfo gets. */
    static int xp(DefinitionNode body, MobOptions out, String origin, String where, List<Diagnostic> diagnostics) {
        if (!body.has("xp")) {
            return 0;
        }
        int[] range = MobLoot.range(body.string("xp").orElse(""));
        if (range == null) {
            diagnostics.add(Diagnostic.warning(origin, where, "xp: " + body.raw("xp")
                    + " is not a number or a range like 5to10. Using 0."));
            return 0;
        }
        out.xpMin = Math.max(0, Math.min(10000, range[0]));
        out.xpMax = Math.max(out.xpMin, Math.min(10000, range[1]));
        return out.xpMin;
    }

    private static void equipment(DefinitionNode body, MobOptions out, String origin, String where,
                                  List<Diagnostic> diagnostics) {
        Object raw = body.has("equipment") ? body.raw("equipment") : body.raw("Equipment");
        if (raw == null) {
            return;
        }
        if (raw instanceof Map) {
            DefinitionNode slots = DefinitionNode.of((Map<?, ?>) raw);
            for (String key : slots.keys()) {
                String slot = slot(key);
                if (slot == null) {
                    diagnostics.add(Diagnostic.warning(origin, where, "equipment: " + key + " is not one of "
                            + String.join(", ", SLOTS) + "."));
                    continue;
                }
                Optional<DefinitionNode> detailed = slots.node(key);
                if (detailed.isPresent()) {
                    Optional<String> item = detailed.get().string("item");
                    if (item.isEmpty()) {
                        diagnostics.add(Diagnostic.warning(origin, where, "equipment." + key + " has no item:."));
                        continue;
                    }
                    out.equipment.put(slot, new Equip(item.get().trim(),
                            clamp(detailed.get().decimal("drop-chance").orElse(0.0), 0, 1)));
                } else {
                    slots.string(key).filter(s -> !s.isBlank())
                            .ifPresent(item -> out.equipment.put(slot, new Equip(item.trim(), 0)));
                }
            }
            return;
        }
        if (!(raw instanceof List)) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "equipment: is not a map. Try equipment: {head: minecraft:iron_helmet, hand: mypack:axe}."));
            return;
        }
        // MythicMobs' list: "- iron_helmet HEAD", "- mypack:axe HAND", "- DIAMOND_SWORD:0".
        for (Object entry : (List<?>) raw) {
            if (entry == null) {
                continue;
            }
            String text = entry.toString().trim();
            String[] parts = text.split("\\s+");
            String item = parts[0];
            String slot = null;
            double chance = 0;
            if (parts.length > 1) {
                slot = slot(parts[1]);
                if (parts.length > 2) {
                    try {
                        chance = clamp(Double.parseDouble(parts[2]), 0, 1);
                    } catch (NumberFormatException ignored) {
                        // A chance that is not a number never drops.
                    }
                }
            } else {
                int colon = item.lastIndexOf(':');
                if (colon > 0 && colon == item.length() - 2 && Character.isDigit(item.charAt(colon + 1))) {
                    slot = slot(item.substring(colon + 1));
                    item = item.substring(0, colon);
                }
            }
            if (slot == null) {
                diagnostics.add(Diagnostic.warning(origin, where, "equipment: \"" + text
                        + "\" names no slot. Write it as item SLOT, like iron_helmet HEAD."));
                continue;
            }
            out.equipment.put(slot, new Equip(item, chance));
        }
    }

    /** A slot as written ({@code HEAD}, {@code mainhand}, {@code 4}) to its name here, or null. */
    static String slot(String written) {
        String key = written.trim().toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        switch (key) {
            case "0":
            case "hand":
            case "mainhand":
                return "hand";
            case "1":
            case "feet":
            case "boots":
                return "feet";
            case "2":
            case "legs":
            case "leggings":
                return "legs";
            case "3":
            case "chest":
            case "chestplate":
            case "body":
                return "chest";
            case "4":
            case "head":
            case "helmet":
                return "head";
            case "5":
            case "offhand":
                return "offhand";
            default:
                return null;
        }
    }

    private static void damageModifiers(DefinitionNode body, MobOptions out, String origin, String where,
                                        List<Diagnostic> diagnostics) {
        Object raw = body.has("damage-modifiers") ? body.raw("damage-modifiers") : body.raw("DamageModifiers");
        if (raw == null) {
            return;
        }
        Map<String, Object> pairs = new LinkedHashMap<>();
        if (raw instanceof Map) {
            ((Map<?, ?>) raw).forEach((k, v) -> pairs.put(String.valueOf(k), v));
        } else if (raw instanceof List) {
            for (Object entry : (List<?>) raw) {
                String[] parts = String.valueOf(entry).trim().split("\\s+");
                if (parts.length == 2) {
                    pairs.put(parts[0], parts[1]);
                } else {
                    diagnostics.add(Diagnostic.warning(origin, where, "damage-modifiers: \"" + entry
                            + "\" is not CAUSE multiplier, like FIRE 0."));
                }
            }
        } else {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "damage-modifiers: is not a map. Try damage-modifiers: {FIRE: 0, PROJECTILE: 0.5}."));
            return;
        }
        for (Map.Entry<String, Object> pair : pairs.entrySet()) {
            double value;
            try {
                value = Double.parseDouble(String.valueOf(pair.getValue()).trim());
            } catch (NumberFormatException e) {
                diagnostics.add(Diagnostic.warning(origin, where, "damage-modifiers." + pair.getKey()
                        + ": " + pair.getValue() + " is not a number."));
                continue;
            }
            value = clamp(value, 0, 100);
            List<String> causes = causes(pair.getKey());
            if (causes.isEmpty()) {
                diagnostics.add(Diagnostic.warning(origin, where, "damage-modifiers: " + pair.getKey()
                        + " is no damage cause this server knows (FIRE, LAVA, PROJECTILE, ENTITY_ATTACK, FALL...)."));
                continue;
            }
            for (String cause : causes) {
                out.damageModifiers.put(cause, value);
            }
        }
    }

    /** A cause as written to Bukkit's names: its own, or one of a few groups MythicMobs users reach for. */
    static List<String> causes(String written) {
        String key = written.trim().toUpperCase(Locale.ROOT).replace("-", "_").replace(' ', '_');
        switch (key) {
            case "MELEE":
                return known(List.of("ENTITY_ATTACK", "ENTITY_SWEEP_ATTACK"));
            case "EXPLOSION":
            case "EXPLOSIONS":
                return known(List.of("BLOCK_EXPLOSION", "ENTITY_EXPLOSION"));
            case "BURNING":
                return known(List.of("FIRE", "FIRE_TICK", "LAVA", "HOT_FLOOR"));
            case "ARROW":
            case "PROJECTILES":
                return known(List.of("PROJECTILE"));
            default:
                return known(List.of(key));
        }
    }

    private static List<String> known(List<String> names) {
        List<String> out = new ArrayList<>();
        for (String name : names) {
            try {
                org.bukkit.event.entity.EntityDamageEvent.DamageCause.valueOf(name);
                out.add(name);
            } catch (IllegalArgumentException ignored) {
                // A cause this server's API does not have (KILL before 1.20.4...).
            }
        }
        return out;
    }

    private static void despawn(DefinitionNode body, MobOptions out, String origin, String where,
                                List<Diagnostic> diagnostics) {
        Object raw = body.has("despawn") ? body.raw("despawn") : body.raw("Despawn");
        if (raw == null) {
            return;
        }
        String key = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        switch (key) {
            case "false":
            case "never":
            case "persistent":
                out.despawn = "never";
                break;
            case "true":
            case "natural":
            case "normal":
                out.despawn = "natural";
                break;
            case "chunk":
                out.despawn = "chunk";
                break;
            default:
                diagnostics.add(Diagnostic.warning(origin, where, "despawn: " + raw
                        + " is not never, natural or chunk."));
        }
    }

    private static void level(DefinitionNode body, MobOptions out, String origin, String where,
                              List<Diagnostic> diagnostics) {
        if (body.has("level")) {
            int[] range = MobLoot.range(body.string("level").orElse(""));
            if (range == null || range[0] < 1 || range[1] > 1000) {
                diagnostics.add(Diagnostic.warning(origin, where, "level: " + body.raw("level")
                        + " is not a level from 1 to 1000 or a range like 1to5. Using 1."));
            } else {
                out.levelMin = range[0];
                out.levelMax = range[1];
            }
        }
        Object raw = body.has("per-level") ? body.raw("per-level") : body.raw("LevelModifiers");
        if (raw == null) {
            return;
        }
        if (!(raw instanceof Map)) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "per-level: is not a map. Try per-level: {health: 10, damage: 1}."));
            return;
        }
        DefinitionNode per = DefinitionNode.of((Map<?, ?>) raw);
        for (String key : per.keys()) {
            String name = key.trim().toLowerCase(Locale.ROOT).replace("armour", "armor")
                    .replace("movementspeed", "speed").replace("movement-speed", "speed");
            if (!PER_LEVEL.contains(name)) {
                diagnostics.add(Diagnostic.warning(origin, where, "per-level: " + key + " is not one of "
                        + String.join(", ", PER_LEVEL) + "."));
                continue;
            }
            Optional<Double> value = per.decimal(key);
            if (value.isEmpty()) {
                diagnostics.add(Diagnostic.warning(origin, where, "per-level." + key + " is not a number."));
                continue;
            }
            out.perLevel.put(name, value.get());
        }
    }

    private static double number(DefinitionNode body, String key, double otherwise, double low, double high,
                                 String origin, String where, List<Diagnostic> diagnostics) {
        if (!body.has(key)) {
            return otherwise;
        }
        Optional<Double> value = body.decimal(key);
        if (value.isEmpty() || !Double.isFinite(value.get())) {
            diagnostics.add(Diagnostic.warning(origin, where, key + ": " + body.raw(key) + " is not a number."));
            return otherwise;
        }
        if (value.get() < low || value.get() > high) {
            diagnostics.add(Diagnostic.warning(origin, where, key + ": " + value.get() + " is outside "
                    + low + " to " + high + "."));
        }
        return clamp(value.get(), low, high);
    }

    private static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }

    /** Health, damage, armour and speed at {@code level}: the base plus what each level above the first adds. */
    public double scaled(String key, double base, int level) {
        return base + Math.max(0, level - 1) * perLevel(key);
    }
}
