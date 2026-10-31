package ai.resourcepack.engine.core.entity;

import ai.resourcepack.common.scheduler.Tasks;
import ai.resourcepack.engine.api.ContentDefinition;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.LoadReport;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.NamespacedKey;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Fixed-point mob spawners, MythicMobs' Spawners: keep up to {@code max-mobs}
 * of one mob alive round a point while a player is within
 * {@code activation-range}, one batch every {@code cooldown} seconds, and pull
 * any that strays past {@code leash-range} back (healed, with
 * {@code heal-on-leash}).
 *
 * <p>Two sources, one runtime: {@code spawners/} files in a content pack
 * (reloaded with the content, never written), and spawners made in game with
 * {@code /rp spawner create}, kept in {@code plugins/RPEngine/spawners.yml}.
 *
 * <p><b>Each spawner ticks on the region that owns its point</b>
 * ({@code Tasks.atTimer}), so on Folia the spawning, counting and the
 * nearby-entity scan are all its region's own work; a leashed mob is pulled
 * back on its own thread with {@code Tasks.teleport}. A spawner's mobs carry
 * its id in their persistent data, which is how one finds its mobs again
 * after a restart: they are counted by a scan round the point the first time
 * it ticks.
 */
public final class Spawners {

    /** How often a spawner looks, in ticks. */
    static final long PERIOD = 20;
    /** The most mobs one spawner keeps, and the most one batch spawns. */
    static final int MAX_MOBS = 64;

    private final Plugin plugin;
    private final CustomEntities creatures;
    private final NamespacedKey spawnerKey;
    private final File file;

    /** Every spawner, content and saved, by id. */
    private final Map<String, Spawner> all = new ConcurrentHashMap<>();
    /** The content folder's, as last loaded. */
    private volatile Map<String, Spawner> fromContent = Map.of();
    private final Map<String, Tasks.Handle> timers = new ConcurrentHashMap<>();
    private volatile boolean started;

    public Spawners(Plugin plugin, CustomEntities creatures) {
        this.plugin = plugin;
        this.creatures = creatures;
        this.spawnerKey = new NamespacedKey(plugin, "spawner");
        this.file = new File(plugin.getDataFolder(), "spawners.yml");
    }

    /** One spawner's settings and its live bookkeeping (touched on its region's thread). */
    public static final class Spawner {
        public final String id;
        public String mob;
        public String world;
        public double x;
        public double y;
        public double z;
        public double cooldown = 10;
        public double warmup;
        public int maxMobs = 1;
        public int perSpawn = 1;
        public double radius;
        public double activationRange = 32;
        public double leashRange;
        public boolean healOnLeash;
        public int level;
        /** Saved by a command (and removable by one), rather than read from a pack. */
        public final boolean saved;
        final String origin;

        final Set<UUID> mobs = ConcurrentHashMap.newKeySet();
        long nextSpawn;
        long activeSince = -1;
        boolean scanned;

        Spawner(String id, boolean saved, String origin) {
            this.id = id;
            this.saved = saved;
            this.origin = origin;
        }

        public int alive() {
            return mobs.size();
        }

        Location location() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x, y, z);
        }

        @Override
        public String toString() {
            return id + " (" + mob + " at " + world + " " + Math.round(x) + " " + Math.round(y) + " "
                    + Math.round(z) + ")";
        }
    }

    // --- loading --------------------------------------------------------

    /** The content folder's spawners. */
    public static Result parse(LoadReport loaded) {
        Map<String, Spawner> out = new LinkedHashMap<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (loaded != null) {
            for (ContentDefinition definition : loaded.definitions(ContentKind.SPAWNER)) {
                read(definition.id().toString(), definition.body().values(), false, definition.origin(),
                        diagnostics).ifPresent(spawner -> out.put(spawner.id, spawner));
            }
        }
        return new Result(out, diagnostics);
    }

    /**
     * One spawner's settings. Keys are matched without case, dashes or
     * underscores, so MythicMobs' {@code MaxMobs}/{@code ActivationRange}
     * read as our {@code max-mobs}/{@code activation-range}.
     */
    static Optional<Spawner> read(String id, Map<String, Object> body, boolean saved, String origin,
                                  List<Diagnostic> diagnostics) {
        Map<String, Object> keys = new HashMap<>();
        body.forEach((key, value) -> keys.put(key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", ""), value));
        Spawner spawner = new Spawner(id, saved, origin);
        Object mob = keys.containsKey("mob") ? keys.get("mob") : keys.containsKey("mobname")
                ? keys.get("mobname") : keys.get("type");
        if (mob == null || mob.toString().isBlank()) {
            diagnostics.add(Diagnostic.warning(origin, id, "A spawner needs mob: <id>."));
            return Optional.empty();
        }
        spawner.mob = mob.toString().trim();
        Object location = keys.get("location");
        if (location instanceof Map) {
            DefinitionNode at = DefinitionNode.of((Map<?, ?>) location);
            spawner.world = at.string("world").orElse(null);
            spawner.x = at.decimal("x").orElse(Double.NaN);
            spawner.y = at.decimal("y").orElse(Double.NaN);
            spawner.z = at.decimal("z").orElse(Double.NaN);
        } else if (location != null) {
            String[] parts = location.toString().split("[,\\s]+");
            if (parts.length == 4) {
                spawner.world = parts[0];
                spawner.x = parse(parts[1]);
                spawner.y = parse(parts[2]);
                spawner.z = parse(parts[3]);
            }
        } else {
            spawner.world = keys.get("world") == null ? null : keys.get("world").toString();
            spawner.x = parse(String.valueOf(keys.get("x")));
            spawner.y = parse(String.valueOf(keys.get("y")));
            spawner.z = parse(String.valueOf(keys.get("z")));
        }
        if (spawner.world == null || Double.isNaN(spawner.x) || Double.isNaN(spawner.y) || Double.isNaN(spawner.z)) {
            diagnostics.add(Diagnostic.warning(origin, id,
                    "A spawner needs location: world,x,y,z (or location: {world, x, y, z})."));
            return Optional.empty();
        }
        spawner.cooldown = number(keys.get("cooldown"), 10, 0, 86400);
        spawner.warmup = number(keys.get("warmup"), 0, 0, 86400);
        spawner.maxMobs = (int) number(keys.get("maxmobs"), 1, 1, MAX_MOBS);
        spawner.perSpawn = (int) number(keys.containsKey("mobsperspawn") ? keys.get("mobsperspawn")
                : keys.get("amount"), 1, 1, MAX_MOBS);
        spawner.radius = number(keys.containsKey("radius") ? keys.get("radius") : keys.get("spawnradius"), 0, 0, 64);
        spawner.activationRange = number(keys.get("activationrange"), 32, 0, 256);
        spawner.leashRange = number(keys.get("leashrange"), 0, 0, 256);
        spawner.healOnLeash = Boolean.parseBoolean(String.valueOf(keys.getOrDefault("healonleash", "false")));
        spawner.level = (int) number(keys.containsKey("level") ? keys.get("level") : keys.get("moblevel"), 0, 0, 1000);
        return Optional.of(spawner);
    }

    private static double parse(String text) {
        try {
            return Double.parseDouble(text.trim());
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    private static double number(Object raw, double otherwise, double low, double high) {
        if (raw == null) {
            return otherwise;
        }
        double value = parse(raw.toString());
        return Double.isNaN(value) ? otherwise : Math.max(low, Math.min(high, value));
    }

    /** Replaces the content folder's spawners (a reload); the saved ones are read again too. */
    public void replace(Result loaded) {
        this.fromContent = loaded.spawners;
        Map<String, Spawner> next = new LinkedHashMap<>(fromContent);
        next.putAll(readSaved());
        // Keep what the old ones knew about their mobs: a reload is not a new spawner.
        for (Spawner spawner : next.values()) {
            Spawner old = all.get(spawner.id);
            if (old != null) {
                spawner.mobs.addAll(old.mobs);
                spawner.nextSpawn = old.nextSpawn;
                spawner.scanned = old.scanned;
            }
        }
        all.clear();
        all.putAll(next);
        if (started) {
            restartTimers();
        }
    }

    private Map<String, Spawner> readSaved() {
        Map<String, Spawner> out = new LinkedHashMap<>();
        if (!file.isFile()) {
            return out;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        List<Diagnostic> problems = new ArrayList<>();
        for (String key : yaml.getKeys(false)) {
            ConfigurationSection section = yaml.getConfigurationSection(key);
            if (section != null) {
                read(key, section.getValues(false), true, "spawners.yml", problems)
                        .ifPresent(spawner -> out.put(spawner.id, spawner));
            }
        }
        for (Diagnostic problem : problems) {
            plugin.getLogger().warning("spawners.yml: " + problem.message());
        }
        return out;
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Spawner spawner : all.values()) {
            if (!spawner.saved) {
                continue;
            }
            ConfigurationSection section = yaml.createSection(spawner.id);
            section.set("mob", spawner.mob);
            section.set("location", spawner.world + "," + spawner.x + "," + spawner.y + "," + spawner.z);
            section.set("cooldown", spawner.cooldown);
            section.set("warmup", spawner.warmup);
            section.set("max-mobs", spawner.maxMobs);
            section.set("mobs-per-spawn", spawner.perSpawn);
            section.set("radius", spawner.radius);
            section.set("activation-range", spawner.activationRange);
            section.set("leash-range", spawner.leashRange);
            section.set("heal-on-leash", spawner.healOnLeash);
            section.set("level", spawner.level);
        }
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save spawners.yml: " + e.getMessage());
        }
    }

    // --- commands -------------------------------------------------------

    public Map<String, Spawner> all() {
        return Map.copyOf(all);
    }

    public Optional<Spawner> get(String id) {
        return Optional.ofNullable(all.get(id.toLowerCase(Locale.ROOT)));
    }

    /** A new saved spawner; false when one of that id exists already. */
    public boolean create(String id, String mob, Location at) {
        String key = id.toLowerCase(Locale.ROOT);
        if (all.containsKey(key) || at.getWorld() == null) {
            return false;
        }
        Spawner spawner = new Spawner(key, true, "spawners.yml");
        spawner.mob = mob;
        spawner.world = at.getWorld().getName();
        spawner.x = at.getBlockX() + 0.5;
        spawner.y = at.getBlockY();
        spawner.z = at.getBlockZ() + 0.5;
        all.put(key, spawner);
        save();
        if (started) {
            startTimer(spawner);
        }
        return true;
    }

    /** Removes a saved spawner (its mobs stay). False for a content one or none. */
    public boolean remove(String id) {
        Spawner spawner = all.get(id.toLowerCase(Locale.ROOT));
        if (spawner == null || !spawner.saved) {
            return false;
        }
        all.remove(spawner.id);
        Tasks.Handle timer = timers.remove(spawner.id);
        if (timer != null) {
            timer.cancel();
        }
        save();
        return true;
    }

    /** Changes one setting of a saved spawner; an error message, or null when it took. */
    public String set(String id, String key, String value) {
        Spawner spawner = all.get(id.toLowerCase(Locale.ROOT));
        if (spawner == null) {
            return "No spawner " + id + ".";
        }
        if (!spawner.saved) {
            return id + " is a content pack's (" + spawner.origin + "); edit that file instead.";
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("mob", spawner.mob);
        values.put("location", spawner.world + "," + spawner.x + "," + spawner.y + "," + spawner.z);
        values.put("cooldown", spawner.cooldown);
        values.put("warmup", spawner.warmup);
        values.put("max-mobs", spawner.maxMobs);
        values.put("mobs-per-spawn", spawner.perSpawn);
        values.put("radius", spawner.radius);
        values.put("activation-range", spawner.activationRange);
        values.put("leash-range", spawner.leashRange);
        values.put("heal-on-leash", spawner.healOnLeash);
        values.put("level", spawner.level);
        String normal = key.toLowerCase(Locale.ROOT).replace("_", "-");
        if (!values.containsKey(normal) || normal.equals("location")) {
            return "Settings: mob, cooldown, warmup, max-mobs, mobs-per-spawn, radius, activation-range, "
                    + "leash-range, heal-on-leash, level.";
        }
        values.put(normal, value);
        List<Diagnostic> problems = new ArrayList<>();
        Optional<Spawner> changed = read(spawner.id, values, true, "spawners.yml", problems);
        if (changed.isEmpty()) {
            return problems.isEmpty() ? "That did not take." : problems.get(0).message();
        }
        changed.get().mobs.addAll(spawner.mobs);
        changed.get().scanned = spawner.scanned;
        all.put(spawner.id, changed.get());
        save();
        if (started) {
            Tasks.Handle timer = timers.remove(spawner.id);
            if (timer != null) {
                timer.cancel();
            }
            startTimer(changed.get());
        }
        return null;
    }

    // --- running --------------------------------------------------------

    public void start() {
        started = true;
        restartTimers();
    }

    public void stop() {
        started = false;
        for (Tasks.Handle timer : timers.values()) {
            timer.cancel();
        }
        timers.clear();
    }

    private void restartTimers() {
        for (Tasks.Handle timer : timers.values()) {
            timer.cancel();
        }
        timers.clear();
        for (Spawner spawner : all.values()) {
            startTimer(spawner);
        }
    }

    private void startTimer(Spawner spawner) {
        Location at = spawner.location();
        if (at == null) {
            plugin.getLogger().warning("Spawner " + spawner.id + ": no world called " + spawner.world + ".");
            return;
        }
        timers.put(spawner.id, Tasks.atTimer(plugin, at, () -> tick(spawner, at), PERIOD, PERIOD));
    }

    /** One look, on the region that owns the spawner's point. */
    private void tick(Spawner spawner, Location at) {
        World world = at.getWorld();
        if (world == null || !world.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) {
            return;
        }
        if (!spawner.scanned) {
            spawner.scanned = true;
            double reach = Math.max(16, Math.max(spawner.leashRange, spawner.radius + 8));
            for (Entity entity : world.getNearbyEntities(at, reach, reach, reach)) {
                if (spawner.id.equals(entity.getPersistentDataContainer().get(spawnerKey, PersistentDataType.STRING))) {
                    spawner.mobs.add(entity.getUniqueId());
                }
            }
        }
        spawner.mobs.removeIf(uuid -> {
            Entity entity = Bukkit.getEntity(uuid);
            return entity == null || !entity.isValid() || entity.isDead();
        });
        if (spawner.leashRange > 0) {
            for (UUID uuid : spawner.mobs) {
                Entity entity = Bukkit.getEntity(uuid);
                if (entity instanceof LivingEntity) {
                    Tasks.on(plugin, entity, () -> leash((LivingEntity) entity, spawner, at));
                }
            }
        }
        long now = world.getGameTime();
        if (!playerNear(at, spawner.activationRange)) {
            spawner.activeSince = -1;
            return;
        }
        if (spawner.activeSince < 0) {
            spawner.activeSince = now;
        }
        if (now - spawner.activeSince < Math.round(spawner.warmup * 20) || now < spawner.nextSpawn
                || spawner.mobs.size() >= spawner.maxMobs) {
            return;
        }
        Optional<ContentId> id = ContentId.parse(spawner.mob.toLowerCase(Locale.ROOT));
        if (id.isEmpty() || creatures.info(id.get()).isEmpty()) {
            if (spawner.nextSpawn >= 0) {
                plugin.getLogger().warning("Spawner " + spawner.id + ": no custom mob " + spawner.mob + ".");
            }
            spawner.nextSpawn = now + 20 * 60;
            return;
        }
        int batch = Math.min(spawner.perSpawn, spawner.maxMobs - spawner.mobs.size());
        for (int i = 0; i < batch; i++) {
            Location place = spawner.radius > 0 ? scatter(at, spawner.radius) : at.clone();
            if (place == null) {
                continue;
            }
            place.setYaw(ThreadLocalRandom.current().nextFloat() * 360f);
            creatures.spawn(place, id.get(), spawner.level).ifPresent(mob -> {
                mob.getPersistentDataContainer().set(spawnerKey, PersistentDataType.STRING, spawner.id);
                spawner.mobs.add(mob.getUniqueId());
            });
        }
        spawner.nextSpawn = now + Math.max(1, Math.round(spawner.cooldown * 20));
    }

    /** Pulls a mob that strayed past the leash back to the spawner; on the mob's own thread. */
    private void leash(LivingEntity mob, Spawner spawner, Location home) {
        if (!mob.isValid() || mob.isDead()) {
            return;
        }
        Location at = mob.getLocation();
        if (at.getWorld() == home.getWorld() && at.distanceSquared(home) <= spawner.leashRange * spawner.leashRange) {
            return;
        }
        if (mob instanceof Mob) {
            ((Mob) mob).setTarget(null);
        }
        if (spawner.healOnLeash) {
            mob.setHealth(Math.max(1, Math.min(mob.getHealth() + 1000, maxHealth(mob))));
        }
        Location back = home.clone();
        back.setYaw(at.getYaw());
        relocate(plugin, mob, back);
    }

    /**
     * Moves a mob with its passengers. A worn model's parts ride the mob, and
     * an entity with passengers refuses to teleport, so they get off, all of
     * them move, and they get back on.
     */
    static void relocate(Plugin plugin, LivingEntity mob, Location to) {
        List<Entity> riders = new ArrayList<>(mob.getPassengers());
        for (Entity rider : riders) {
            mob.removePassenger(rider);
        }
        Tasks.teleport(mob, to).thenAccept(moved -> Tasks.on(plugin, mob, () -> {
            for (Entity rider : riders) {
                if (!rider.isValid()) {
                    continue;
                }
                Tasks.teleport(rider, to).thenAccept(done -> Tasks.on(plugin, mob, () -> {
                    if (rider.isValid() && mob.isValid()) {
                        mob.addPassenger(rider);
                    }
                }));
            }
        }));
    }

    private static double maxHealth(LivingEntity body) {
        org.bukkit.attribute.Attribute max = ai.resourcepack.engine.core.version.Vanilla.maxHealth().orElse(null);
        org.bukkit.attribute.AttributeInstance instance = max == null ? null : body.getAttribute(max);
        return instance == null ? 20 : instance.getValue();
    }

    private static boolean playerNear(Location at, double range) {
        World world = at.getWorld();
        if (world == null) {
            return false;
        }
        for (Player player : world.getPlayers()) {
            if (player.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            if (range <= 0 || player.getLocation().distanceSquared(at) <= range * range) {
                return true;
            }
        }
        return false;
    }

    /** A place a mob can stand within {@code radius} of {@code at}, in a loaded chunk this region owns, or null. */
    private static Location scatter(Location at, double radius) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 8; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double r = Math.sqrt(random.nextDouble()) * radius;
            Location tried = at.clone().add(Math.cos(angle) * r, 0, Math.sin(angle) * r);
            if (!Tasks.owns(tried) || !tried.getWorld().isChunkLoaded(tried.getBlockX() >> 4, tried.getBlockZ() >> 4)) {
                continue;
            }
            for (int dy = 2; dy >= -3; dy--) {
                Block feet = tried.getBlock().getRelative(0, dy, 0);
                if (feet.isPassable() && feet.getRelative(0, 1, 0).isPassable()
                        && feet.getRelative(0, -1, 0).getType().isSolid()) {
                    return feet.getLocation().add(0.5, 0, 0.5);
                }
            }
        }
        return at.clone();
    }

    /** The content folder's spawners and what was wrong with them. */
    public static final class Result {
        final Map<String, Spawner> spawners;
        private final List<Diagnostic> diagnostics;

        Result(Map<String, Spawner> spawners, List<Diagnostic> diagnostics) {
            this.spawners = Map.copyOf(spawners);
            this.diagnostics = List.copyOf(diagnostics);
        }

        public List<Diagnostic> diagnostics() {
            return diagnostics;
        }

        public int size() {
            return spawners.size();
        }
    }
}
