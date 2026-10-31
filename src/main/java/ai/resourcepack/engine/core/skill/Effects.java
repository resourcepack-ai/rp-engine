package ai.resourcepack.engine.core.skill;

import ai.resourcepack.engine.api.ContentId;
import org.bukkit.Color;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Particles, sounds and items by the names a MythicMobs file uses, on every
 * version from 1.19.4.
 *
 * <p>Both particles and sounds are looked up by NAME through reflection,
 * never as constants: 1.20.5 renamed a third of the particles
 * ({@code REDSTONE} is {@code DUST} now) and 1.21.3 made {@code Sound} an
 * interface, so a constant compiled against today's API is a
 * {@code NoSuchFieldError} or an {@code IncompatibleClassChangeError} on an
 * older server. A field read by name is neither.
 */
final class Effects {

    private Effects() {
    }

    /** MythicMobs' (and pre-1.20.5 Bukkit's) particle names, each to the names to try, newest first. */
    private static final Map<String, List<String>> PARTICLES = Map.ofEntries(
            Map.entry("reddust", List.of("DUST", "REDSTONE")),
            Map.entry("dust", List.of("DUST", "REDSTONE")),
            Map.entry("redstone", List.of("DUST", "REDSTONE")),
            Map.entry("largeexplode", List.of("EXPLOSION", "EXPLOSION_LARGE")),
            Map.entry("explosion_large", List.of("EXPLOSION", "EXPLOSION_LARGE")),
            Map.entry("explosion", List.of("EXPLOSION", "EXPLOSION_LARGE")),
            Map.entry("hugeexplosion", List.of("EXPLOSION_EMITTER", "EXPLOSION_HUGE")),
            Map.entry("explode", List.of("POOF", "EXPLOSION_NORMAL")),
            Map.entry("poof", List.of("POOF", "EXPLOSION_NORMAL")),
            Map.entry("smoke", List.of("SMOKE", "SMOKE_NORMAL")),
            Map.entry("largesmoke", List.of("LARGE_SMOKE", "SMOKE_LARGE")),
            Map.entry("witchmagic", List.of("WITCH", "SPELL_WITCH")),
            Map.entry("spell_witch", List.of("WITCH", "SPELL_WITCH")),
            Map.entry("mobspell", List.of("ENTITY_EFFECT", "SPELL_MOB")),
            Map.entry("mobspellambient", List.of("ENTITY_EFFECT", "SPELL_MOB_AMBIENT")),
            Map.entry("spell", List.of("EFFECT", "SPELL")),
            Map.entry("instantspell", List.of("INSTANT_EFFECT", "SPELL_INSTANT")),
            Map.entry("magiccrit", List.of("ENCHANTED_HIT", "CRIT_MAGIC")),
            Map.entry("blockcrack", List.of("BLOCK", "BLOCK_CRACK")),
            Map.entry("blockdust", List.of("BLOCK", "BLOCK_DUST")),
            Map.entry("iconcrack", List.of("ITEM", "ITEM_CRACK")),
            Map.entry("snowballpoof", List.of("ITEM_SNOWBALL", "SNOWBALL")),
            Map.entry("splash", List.of("SPLASH", "WATER_SPLASH")),
            Map.entry("bubble", List.of("BUBBLE", "WATER_BUBBLE")),
            Map.entry("dripwater", List.of("DRIPPING_WATER", "DRIP_WATER")),
            Map.entry("driplava", List.of("DRIPPING_LAVA", "DRIP_LAVA")),
            Map.entry("happyvillager", List.of("HAPPY_VILLAGER", "VILLAGER_HAPPY")),
            Map.entry("angryvillager", List.of("ANGRY_VILLAGER", "VILLAGER_ANGRY")),
            Map.entry("enchantmenttable", List.of("ENCHANT", "ENCHANTMENT_TABLE")),
            Map.entry("townaura", List.of("MYCELIUM", "TOWN_AURA")),
            Map.entry("fireworksspark", List.of("FIREWORK", "FIREWORKS_SPARK")),
            Map.entry("slime", List.of("ITEM_SLIME", "SLIME")),
            Map.entry("totem", List.of("TOTEM_OF_UNDYING", "TOTEM")),
            Map.entry("suspended", List.of("UNDERWATER", "SUSPENDED")),
            Map.entry("depthsuspend", List.of("UNDERWATER", "SUSPENDED_DEPTH")),
            Map.entry("footstep", List.of("CRIT")));

    private static final Map<String, Optional<Particle>> FOUND = new ConcurrentHashMap<>();

    static Optional<Particle> particle(String name) {
        String key = name == null ? "" : name.trim().toLowerCase(Locale.ROOT).replace("minecraft:", "");
        return FOUND.computeIfAbsent(key, k -> {
            List<String> tries = PARTICLES.getOrDefault(k, List.of(k.toUpperCase(Locale.ROOT)));
            for (String constant : tries) {
                Object value = constant(Particle.class, constant);
                if (value instanceof Particle) {
                    return Optional.of((Particle) value);
                }
            }
            return Optional.empty();
        });
    }

    private static Object constant(Class<?> type, String name) {
        try {
            return type.getField(name).get(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Spawns {@code name}, with the data it needs (a dust's colour, a block crack's block) made up when not given. */
    static void particle(SkillRuntime runtime, Location at, String name, int amount, double dx, double dy, double dz,
                         double speed, String colour, float size, String material) {
        World world = at.getWorld();
        Optional<Particle> particle = particle(name);
        if (world == null) {
            return;
        }
        if (particle.isEmpty()) {
            runtime.warnOnce("particle:" + name, "No particle " + name + " (a mob skill's particles{p=...}).");
            return;
        }
        Particle p = particle.get();
        Class<?> data = p.getDataType();
        Object extra = null;
        if (data == Particle.DustOptions.class) {
            extra = new Particle.DustOptions(Color.fromRGB(rgb(colour).orElse(0xFF0000)), Math.max(0.1f, size));
        } else if (data == Color.class) {
            extra = Color.fromRGB(rgb(colour).orElse(0xFFFFFF));
        } else if (data == org.bukkit.block.data.BlockData.class) {
            Material block = material == null ? null : Material.matchMaterial(material);
            extra = (block != null && block.isBlock() ? block : Material.STONE).createBlockData();
        } else if (data == ItemStack.class) {
            Material item = material == null ? null : Material.matchMaterial(material);
            extra = new ItemStack(item != null && item.isItem() ? item : Material.STONE);
        } else if (data == Float.class) {
            extra = 0f;
        } else if (data == Integer.class) {
            extra = 0;
        } else if (data != Void.class) {
            runtime.warnOnce("particledata:" + name, "Particle " + name + " needs data this cannot make up; skipped.");
            return;
        }
        int count = Math.max(0, Math.min(500, amount));
        world.spawnParticle(p, at, count, dx, dy, dz, speed, extra);
    }

    /**
     * A sound: a pack's by id ({@code zoo:boom}), else a vanilla event by its
     * key ({@code entity.generic.explode}) or its old Bukkit constant name
     * ({@code ENTITY_GENERIC_EXPLODE}).
     */
    static boolean sound(SkillRuntime runtime, Location at, String name, float volume, float pitch) {
        if (name == null || name.isBlank() || at.getWorld() == null) {
            return false;
        }
        String written = name.trim();
        if (written.indexOf(':') > 0 && !written.startsWith("minecraft:")) {
            Optional<ContentId> id = ContentId.parse(written.toLowerCase(Locale.ROOT));
            if (id.isPresent() && runtime.host.sounds().info(id.get()).isPresent()) {
                return runtime.host.sounds().playAt(at, id.get(), volume, pitch);
            }
        }
        String key = written;
        if (written.indexOf('.') < 0 && written.indexOf('_') > 0) {
            Object constant = constant(org.bukkit.Sound.class, written.toUpperCase(Locale.ROOT));
            if (constant instanceof Keyed) {
                key = ((Keyed) constant).getKey().getKey();
            }
        }
        at.getWorld().playSound(at, key.toLowerCase(Locale.ROOT), SoundCategory.HOSTILE, volume, pitch);
        return true;
    }

    /** {@code #ff0000}, {@code ff0000}, {@code 255,0,0}, or a colour's name. */
    static Optional<Integer> rgb(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String t = text.trim().toLowerCase(Locale.ROOT);
        try {
            if (t.contains(",")) {
                String[] parts = t.split(",");
                if (parts.length >= 3) {
                    int r = Integer.parseInt(parts[0].trim());
                    int g = Integer.parseInt(parts[1].trim());
                    int b = Integer.parseInt(parts[2].trim());
                    return Optional.of(((r & 255) << 16) | ((g & 255) << 8) | (b & 255));
                }
                return Optional.empty();
            }
            if (t.startsWith("#")) {
                t = t.substring(1);
            }
            if (t.length() == 6 && t.matches("[0-9a-f]{6}")) {
                return Optional.of(Integer.parseInt(t, 16));
            }
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        switch (t) {
            case "red":
                return Optional.of(0xFF0000);
            case "green":
                return Optional.of(0x00FF00);
            case "blue":
                return Optional.of(0x0000FF);
            case "yellow":
                return Optional.of(0xFFFF00);
            case "purple":
                return Optional.of(0x8000FF);
            case "orange":
                return Optional.of(0xFF8000);
            case "white":
                return Optional.of(0xFFFFFF);
            case "black":
                return Optional.of(0x000000);
            case "aqua":
                return Optional.of(0x00FFFF);
            default:
                return Optional.empty();
        }
    }

    /** A pack's item by id, else a vanilla one by name. */
    static ItemStack stack(SkillRuntime runtime, String item, int amount) {
        if (item == null || item.isBlank()) {
            return null;
        }
        Optional<ContentId> id = ContentId.parse(item.trim().toLowerCase(Locale.ROOT));
        if (id.isPresent() && !"minecraft".equals(id.get().namespace())) {
            return runtime.host.items().create(id.get(), amount).orElse(null);
        }
        Material material = Material.matchMaterial(item.trim());
        return material == null || !material.isItem() ? null : new ItemStack(material, amount);
    }
}
