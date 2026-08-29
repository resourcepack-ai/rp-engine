import ai.resourcepack.engine.RPEnginePlugin;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ItemInfo;
import ai.resourcepack.engine.api.LiquidInfo;
import ai.resourcepack.engine.api.event.ContentLoadEvent;
import ai.resourcepack.engine.api.event.EntityDeathEvent;
import ai.resourcepack.engine.api.event.ModelBindEvent;
import ai.resourcepack.engine.core.liquid.LiquidBiomes;
import ai.resourcepack.engine.core.liquid.LiquidBuckets;
import ai.resourcepack.engine.core.liquid.LiquidPools;
import ai.resourcepack.engine.core.liquid.Liquids;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Drives RP Engine against a real server, with no player and no client.
 *
 * <p>One scenario per server boot, named by {@code -Drpengine.it=<name>} —
 * several of the things worth checking are only true on the run AFTER the one
 * that wrote something, which no amount of arranging inside one JVM can fake.
 * {@code integration/run.mjs} runs them in order and reads the RPTEST lines
 * back out of the console.
 *
 * <p>Where this reaches into {@code core} by reflection it is doing so
 * deliberately: this is a test, not a consumer, and the point is to drive the
 * shipping code rather than a copy of it. A player is a dynamic proxy for the
 * same reason — {@code Player} is an interface, and the placement path asks it
 * for a world, a game mode, a server and a permission.
 */
public final class Harness extends JavaPlugin implements Listener {

    private int passed;
    private int failed;
    private final List<String> said = new ArrayList<>();

    private final List<ContentLoadEvent> loads = new ArrayList<>();
    private final List<ModelBindEvent> binds = new ArrayList<>();
    private final List<EntityDeathEvent> deaths = new ArrayList<>();
    private boolean refuseBinds;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        // Late enough that the engine has built its packs and anything it
        // schedules for its own first tick has run.
        Bukkit.getScheduler().runTaskLater(this, this::run, 60L);
    }

    // ---- listeners -------------------------------------------------------

    @EventHandler
    public void onLoad(ContentLoadEvent event) {
        loads.add(event);
    }

    @EventHandler
    public void onBind(ModelBindEvent event) {
        binds.add(event);
        if (refuseBinds) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        deaths.add(event);
    }

    // ---- reporting -------------------------------------------------------

    private void check(String what, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("RPTEST PASS  " + what);
        } else {
            failed++;
            System.out.println("RPTEST FAIL  " + what);
        }
    }

    private void note(String what) {
        System.out.println("RPTEST NOTE  " + what);
    }

    private void run() {
        String scenario = System.getProperty("rpengine.it", "");
        System.out.println("RPTEST SCENARIO " + scenario);
        try {
            switch (scenario) {
                case "first-boot":
                    firstBoot();
                    break;
                case "painting":
                    painting();
                    break;
                case "buckets":
                    buckets();
                    break;
                case "commands":
                    commands();
                    break;
                case "events":
                    events();
                    break;
                default:
                    failed++;
                    System.out.println("RPTEST FAIL  no scenario named " + scenario);
            }
        } catch (Throwable t) {
            failed++;
            System.out.println("RPTEST FAIL  harness threw: " + t);
            t.printStackTrace(System.out);
        }
        System.out.println("RPTEST DONE  passed=" + passed + " failed=" + failed);
        Bukkit.getScheduler().runTaskLater(this, Bukkit::shutdown, 20L);
    }

    // ---- scenarios -------------------------------------------------------

    /** The run that writes the colour datapack: it cannot be live in this JVM. */
    private void firstBoot() throws Exception {
        RPEnginePlugin engine = engine();
        Liquids liquids = (Liquids) field(engine, "liquids");
        LiquidBiomes biomes = (LiquidBiomes) field(engine, "liquidBiomes");

        LiquidInfo acid = liquids.info(id("testpack:acid")).orElse(null);
        check("acid parsed", acid != null);
        check("acid is #3FBF4A", acid != null && acid.color().orElse(-1) == 0x3FBF4A);
        check("acid keeps its rules",
                acid != null && acid.damage() == 1.0 && acid.amplifier() == 1
                        && "POISON".equals(acid.effect().orElse("")));
        check("a dye name is a colour",
                liquids.info(id("testpack:blood")).map(l -> l.color().orElse(-1) == 0xB02E26)
                        .orElse(false));
        check("0xRRGGBB is a colour, though YAML reads it as a number",
                liquids.info(id("testpack:magma")).map(l -> l.color().orElse(-1) == 0xFF6A00
                        && l.base() == LiquidInfo.Base.LAVA && l.fireproof()).orElse(false));
        check("bare hex is a colour",
                liquids.info(id("testpack:hash_unquoted")).map(l -> l.color().orElse(-1) == 0x3FBF4A)
                        .orElse(false));
        check("a liquid with no colour has none",
                liquids.info(id("testpack:plain")).map(l -> l.color().isEmpty()).orElse(false));
        check("a colour that is not one leaves the liquid loaded and untinted",
                liquids.info(id("testpack:wrong_colour")).map(l -> l.color().isEmpty()).orElse(false));

        check("a bucket item carries its liquid",
                engine.items().info(id("testpack:acid_bucket"))
                        .map(i -> "testpack:acid".equals(i.liquid().map(Object::toString).orElse("")))
                        .orElse(false));
        check("a liquid: that is not an id leaves an ordinary item",
                engine.items().info(id("testpack:broken_bucket"))
                        .map(i -> i.liquid().isEmpty()).orElse(false));

        File pack = packFolder();
        check("the datapack was written", biome(pack, "testpack_acid").isFile());
        check("the biome carries the colour as a number",
                read(biome(pack, "testpack_acid")).contains("\"water_color\": " + 0x3FBF4A));
        check("and leaves grass and foliage alone",
                !read(biome(pack, "testpack_acid")).contains("grass_color"));
        check("an untinted liquid gets no biome", !biome(pack, "testpack_plain").isFile());
        check("nor does one whose colour was rejected",
                !biome(pack, "testpack_wrong_colour").isFile());

        check("the colour is not live on the run that wrote it",
                biomes.biomeOf(id("testpack:acid")).isEmpty());
        check("and the engine knows it", biomes.restartWanted());
    }

    /** The run after that one: the biome exists, so painting can be checked. */
    private void painting() throws Exception {
        RPEnginePlugin engine = engine();
        Liquids liquids = (Liquids) field(engine, "liquids");
        LiquidPools pools = (LiquidPools) field(engine, "pools");
        LiquidBiomes biomes = (LiquidBiomes) field(engine, "liquidBiomes");

        NamespacedKey key = LiquidBiomes.keyOf(id("testpack:acid"));
        Biome tint = Registry.BIOME.get(key);
        check("the generated biome is in the registry after a restart", tint != null);
        check("and the engine resolves it", biomes.biomeOf(id("testpack:acid")).isPresent());
        if (tint == null) {
            return;
        }

        World world = Bukkit.getWorlds().get(0);
        int x = 500;
        int y = 70;
        int z = 500;
        world.getChunkAt(x >> 4, z >> 4).load(true);
        Biome originally = world.getBiome(x, y, z);

        LiquidPools.Pool pool = pools.add(id("testpack:acid"), world.getName(),
                new int[] {x, y, z}, new int[] {x + 9, y + 4, z + 9});
        Optional<Biome> was = biomes.paint(world, x, y, z, x + 9, y + 4, z + 9, id("testpack:acid"));
        check("paint reports the biome it replaced", was.isPresent() && was.get() == originally);
        was.ifPresent(b -> pool.remember(b.getKey().toString()));

        check("the near corner is tinted", world.getBiome(x, y, z) == tint);
        check("the far corner is tinted", world.getBiome(x + 9, y + 4, z + 9) == tint);
        check("the middle is tinted", world.getBiome(x + 5, y + 2, z + 5) == tint);
        check("a cell well outside is untouched", world.getBiome(x + 40, y, z + 40) != tint);

        check("a point inside the box resolves to the liquid",
                liquids.at(new Location(world, x + 3, y + 1, z + 3))
                        .map(l -> "testpack:acid".equals(l.id().toString())).orElse(false));
        check("a point outside does not",
                liquids.at(new Location(world, x + 40, y, z + 40)).isEmpty());

        pools.save(getLogger());
        LiquidPools reread = new LiquidPools(engine.getDataFolder());
        reread.load(getLogger());
        check("pools survive a save and load", reread.pools().size() == pools.pools().size());
        check("the biome to restore survives with them",
                reread.pools().stream().anyMatch(p -> p.was().isPresent()));
        check("a pool written before colours existed still loads",
                pools.pools().stream().anyMatch(p -> "old:pool".equals(
                        p.liquid().map(ContentId::toString).orElse(""))
                        && p.was().isEmpty()));

        biomes.restore(world, x, y, z, x + 9, y + 4, z + 9, originally);
        check("clearing puts the biome back", world.getBiome(x + 5, y + 2, z + 5) == originally);

        check("an untinted liquid paints nothing",
                biomes.paint(world, x, y, z, x + 1, y + 1, z + 1, id("testpack:plain")).isEmpty());
        check("a liquid that does not exist paints nothing",
                biomes.paint(world, x, y, z, x + 1, y + 1, z + 1, id("testpack:nope")).isEmpty());
    }

    /** The bucket, and a reload that adds and removes a colour. */
    private void buckets() throws Exception {
        RPEnginePlugin engine = engine();
        Liquids liquids = (Liquids) field(engine, "liquids");
        LiquidPools pools = (LiquidPools) field(engine, "pools");
        LiquidBiomes biomes = (LiquidBiomes) field(engine, "liquidBiomes");
        LiquidBuckets buckets = new LiquidBuckets(liquids, pools, biomes, getLogger());

        World world = Bukkit.getWorlds().get(0);
        Biome tint = biomes.biomeOf(id("testpack:acid")).orElse(null);
        int x = 320;
        int y = 80;
        int z = 320;
        world.getChunkAt(x >> 4, z >> 4).load(true);
        for (int i = 0; i <= 14; i++) {
            world.getBlockAt(x + i, y, z).setType(Material.STONE, false);
            world.getBlockAt(x + i, y + 1, z).setType(Material.AIR, false);
        }

        Player player = fakePlayer(world, GameMode.SURVIVAL);
        ItemInfo acidBucket = engine.items().info(id("testpack:acid_bucket")).orElseThrow();
        ItemStack held = new ItemStack(Material.BUCKET, 3);
        Block floor = world.getBlockAt(x, y, z);

        int before = pools.pools().size();
        check("the click was handled", buckets.place(player, acidBucket, held, floor, BlockFace.UP));
        check("water is standing on the block clicked",
                world.getBlockAt(x, y + 1, z).getType() == Material.WATER);
        check("the block clicked was not replaced", floor.getType() == Material.STONE);
        check("a pool was made", pools.pools().size() == before + 1);
        check("the place counts as the liquid",
                liquids.at(world.getBlockAt(x, y + 1, z).getLocation())
                        .map(l -> "testpack:acid".equals(l.id().toString())).orElse(false));
        check("the water is tinted", tint != null && world.getBiome(x, y + 1, z) == tint);
        check("a survival bucket is spent", held.getAmount() == 2);

        buckets.place(player, acidBucket, held, world.getBlockAt(x + 1, y, z), BlockFace.UP);
        check("a second click beside it did not make a second pool",
                pools.pools().size() == before + 1);
        check("and both blocks are the liquid",
                liquids.at(world.getBlockAt(x + 1, y + 1, z).getLocation()).isPresent());

        ItemStack full = new ItemStack(Material.BUCKET, 1);
        buckets.place(fakePlayer(world, GameMode.CREATIVE), acidBucket, full,
                world.getBlockAt(x + 3, y, z), BlockFace.UP);
        check("a creative bucket is not spent", full.getAmount() == 1);

        buckets.place(player, engine.items().info(id("testpack:magma_bucket")).orElseThrow(),
                new ItemStack(Material.BUCKET, 1), world.getBlockAt(x + 6, y, z), BlockFace.UP);
        check("a lava-based liquid places lava",
                world.getBlockAt(x + 6, y + 1, z).getType() == Material.LAVA);

        check("an item with no liquid is not a bucket",
                !buckets.place(player, engine.items().info(id("testpack:broken_bucket")).orElseThrow(),
                        new ItemStack(Material.BUCKET, 1), world.getBlockAt(x + 8, y, z), BlockFace.UP));

        said.clear();
        check("a bucket of a liquid this server does not have says so",
                buckets.place(player, engine.items().info(id("testpack:ghost_bucket")).orElseThrow(),
                        new ItemStack(Material.BUCKET, 1), world.getBlockAt(x + 10, y, z), BlockFace.UP)
                        && said.stream().anyMatch(s -> s.contains("no liquid called")));
        check("and places nothing", world.getBlockAt(x + 10, y + 1, z).getType() != Material.WATER);

        world.getBlockAt(x + 12, y + 1, z).setType(Material.STONE, false);
        check("a click with no room places nothing",
                !buckets.place(player, acidBucket, new ItemStack(Material.BUCKET, 1),
                        world.getBlockAt(x + 12, y, z), BlockFace.UP));

        check("clicking standing water replaces that block, not the one above",
                buckets.place(player, acidBucket, new ItemStack(Material.BUCKET, 1),
                        world.getBlockAt(x + 1, y + 1, z), BlockFace.UP)
                        && world.getBlockAt(x + 1, y + 2, z).getType() != Material.WATER);

        world.getBlockAt(x + 14, y + 1, z).setType(Material.TORCH, false);
        check("a click on a torch places above it rather than through it",
                buckets.place(player, acidBucket, new ItemStack(Material.BUCKET, 1),
                        world.getBlockAt(x + 14, y + 1, z), BlockFace.UP)
                        && world.getBlockAt(x + 14, y + 1, z).getType() == Material.TORCH
                        && world.getBlockAt(x + 14, y + 2, z).getType() == Material.WATER);

        // ---- a reload that adds a colour, and one that takes it away -----
        File yml = new File(engine.getDataFolder(), "content/testpack/liquids/liquids.yml");
        String original = read(yml);
        Files.write(yml.toPath(),
                (original + "\nlate:\n  base: water\n  color: CYAN\n").getBytes(StandardCharsets.UTF_8));
        reload(engine);

        check("a liquid added by a reload is loaded", liquids.info(id("testpack:late")).isPresent());
        check("its biome is written", biome(packFolder(), "testpack_late").isFile());
        check("but it is not live until a restart", biomes.biomeOf(id("testpack:late")).isEmpty());
        check("and the engine says so", biomes.restartWanted());
        check("a colour that was already live still resolves",
                biomes.biomeOf(id("testpack:acid")).isPresent());

        Files.write(yml.toPath(), original.getBytes(StandardCharsets.UTF_8));
        reload(engine);
        check("a liquid taken out of the pack takes its biome with it",
                !biome(packFolder(), "testpack_late").isFile());
    }

    /** Whether the two player commands are registered, per the config. */
    private void commands() {
        boolean wanted = engine().getConfig().getBoolean("emotes.player-commands", true);
        note("emotes.player-commands is " + wanted);

        check("/emote matches the setting",
                (Bukkit.getServer().getPluginCommand("emote") != null) == wanted);
        check("/emotereply matches the setting",
                (Bukkit.getServer().getPluginCommand("emotereply") != null) == wanted);
        check("the namespaced form matches too",
                (Bukkit.getServer().getPluginCommand("rpengine:emote") != null) == wanted);
        check("dispatching it matches too",
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "emote wave") == wanted);
        check("/rpengine is there either way",
                Bukkit.getServer().getPluginCommand("rpengine") != null);
        check("and so is its /rp alias", Bukkit.getServer().getPluginCommand("rp") != null);
        check("and /rp emote is there either way",
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "rp emotes"));
    }

    /** The events: do they fire, do they carry the right thing, does a cancel hold. */
    private void events() throws Exception {
        RPEnginePlugin engine = engine();

        note("loads seen before the reload: " + loads.size()
                + " (STARTUP fires before a plugin loading after RP Engine can listen)");
        reload(engine);

        check("a reload fires ContentLoadEvent", !loads.isEmpty());
        ContentLoadEvent load = loads.get(loads.size() - 1);
        check("it says RELOAD", load.cause() == ContentLoadEvent.Cause.RELOAD);
        check("it counts the packs", load.namespaces() >= 1);
        check("it counts the content", load.definitions() >= 1);
        check("and the registry is answerable by then",
                engine.items().info(id("testpack:acid_bucket")).isPresent());

        World world = Bukkit.getWorlds().get(0);
        Location at = new Location(world, 260, 80, 260);
        world.getChunkAt(at).load(true);
        Entity mob = world.spawnEntity(at, EntityType.ZOMBIE);
        ContentId model = id("testpack:acid_bucket");

        boolean bound = engine.models().bind(mob, model);
        check("binding fires ModelBindEvent", !binds.isEmpty());
        check("as a BIND naming the model and the mob",
                !binds.isEmpty() && binds.get(0).action() == ModelBindEvent.Action.BIND
                        && binds.get(0).host() == mob && model.equals(binds.get(0).model()));
        check("and the bind went through", bound);

        int before = binds.size();
        engine.models().unbind(mob);
        check("unbinding fires it too", binds.size() == before + 1);
        check("as an UNBIND naming what came off",
                binds.get(binds.size() - 1).action() == ModelBindEvent.Action.UNBIND
                        && model.equals(binds.get(binds.size() - 1).model()));

        refuseBinds = true;
        boolean refused = engine.models().bind(mob, model);
        refuseBinds = false;
        check("cancelling refuses the bind", !refused);
        check("and nothing was put on the mob", mob.getPassengers().isEmpty());

        Object creatures = field(engine, "creatures");
        Method spawn = creatures.getClass().getMethod("spawn", Location.class, ContentId.class);
        Entity sentry = (Entity) ((Optional<?>) spawn.invoke(creatures,
                new Location(world, 262, 80, 262), id("testpack:sentry"))).orElse(null);
        check("a custom entity spawned", sentry != null);
        if (sentry != null) {
            ((LivingEntity) sentry).setHealth(0);
            check("killing it fires EntityDeathEvent", !deaths.isEmpty());
            check("which says what it was",
                    !deaths.isEmpty() && "testpack:sentry".equals(deaths.get(0).id().toString())
                            && deaths.get(0).entity() == sentry);
        }
        mob.remove();

        note("not reachable without a client: ModelSeatEvent (needs a body to sit),"
                + " PlayerLiquidEvent (needs somebody standing in water),"
                + " ModelAnimationEndEvent (needs a rig with an animation)");
    }

    // ---- plumbing --------------------------------------------------------

    private RPEnginePlugin engine() {
        return (RPEnginePlugin) Bukkit.getPluginManager().getPlugin("RPEngine");
    }

    private static void reload(RPEnginePlugin engine) throws Exception {
        Method reload = engine.getClass().getDeclaredMethod("reloadContent", CommandSender.class);
        reload.setAccessible(true);
        reload.invoke(engine, Bukkit.getConsoleSender());
    }

    private File packFolder() {
        return new File(Bukkit.getWorlds().get(0).getWorldFolder(), "datapacks/rpengine_liquids");
    }

    private static File biome(File pack, String name) {
        return new File(pack, "data/rpengine/worldgen/biome/" + name + ".json");
    }

    private static String read(File file) throws Exception {
        return file.isFile()
                ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) : "";
    }

    private static Object field(Object owner, String name) throws Exception {
        Field found = owner.getClass().getDeclaredField(name);
        found.setAccessible(true);
        return found.get(owner);
    }

    private static ContentId id(String written) {
        return ContentId.parse(written).orElseThrow();
    }

    /** A Player that exists only to be handed to the code under test. */
    private Player fakePlayer(World world, GameMode mode) {
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "getWorld":
                    return world;
                case "getServer":
                    return Bukkit.getServer();
                case "getGameMode":
                    return mode;
                case "hasPermission":
                case "isOp":
                    return true;
                case "sendMessage":
                    if (args != null && args.length > 0 && args[0] instanceof String) {
                        said.add((String) args[0]);
                    }
                    return null;
                case "getName":
                    return "Harness";
                case "toString":
                    return "HarnessPlayer";
                case "hashCode":
                    return 1;
                case "equals":
                    return proxy == args[0];
                default:
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) {
                        return false;
                    }
                    if (type == int.class) {
                        return 0;
                    }
                    if (type == double.class || type == float.class) {
                        return 0d;
                    }
                    return null;
            }
        };
        return (Player) Proxy.newProxyInstance(getClassLoader(),
                new Class<?>[] {Player.class, CommandSender.class}, handler);
    }
}
