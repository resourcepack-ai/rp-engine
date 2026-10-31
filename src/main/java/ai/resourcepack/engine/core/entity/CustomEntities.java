package ai.resourcepack.engine.core.entity;

import ai.resourcepack.common.scheduler.Tasks;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.EntityDrop;
import ai.resourcepack.engine.api.EntityInfo;
import ai.resourcepack.engine.api.Items;
import ai.resourcepack.engine.api.MobState;
import ai.resourcepack.engine.api.Mobs;
import ai.resourcepack.engine.api.Sounds;
import ai.resourcepack.engine.api.event.MobStateChangeEvent;
import ai.resourcepack.engine.core.Regions;
import ai.resourcepack.engine.core.model.BoundModels;
import ai.resourcepack.engine.core.model.RigTags;
import ai.resourcepack.engine.core.version.Vanilla;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.SoundCategory;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityCombustByBlockEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Custom mobs: a real mob wearing a rig, animated by what it is doing.
 *
 * <p>The mob is genuinely a mob. It walks with its own AI, takes damage, is
 * found by {@code @e} selectors and is seen by every other plugin on the
 * server. What this adds is what it looks like (the whole rig, through
 * {@link BoundModels}, with the vanilla body invisible), what it sounds like,
 * what it drops, and which animation plays: {@link MobBrain} decides that from
 * what happened to it, and this class only reports the happenings.
 *
 * <p><strong>Riding, rather than a task that moves the displays every
 * tick.</strong> A passenger is moved by the server, so the model never lags
 * behind the body and costs nothing per tick. The per-mob work here is one
 * look every {@link #PERIOD} ticks at how far it moved.
 *
 * <p>The failure this feature usually has is a field of orphaned displays
 * where mobs used to be. What prevents it:
 * <ul>
 *   <li>the mob never despawns — a removed mount EJECTS its passengers, so a
 *   mob that vanished because somebody walked away would leave its model
 *   standing;</li>
 *   <li>at death the parts are let go non-persistent ({@link BoundModels#release})
 *   and removed when the death animation ends, at most {@link #DEATH_CAP}
 *   later, or at once without one;</li>
 *   <li>a bound part that loads riding nothing, or marked dying, is removed on
 *   sight by the animator's reaper, which covers a mob some other plugin
 *   deleted, a crash in the death window, and anything an older jar left.</li>
 * </ul>
 *
 * <p>Everything here runs on the mob's own thread: events arrive there, and
 * the one timer hands each look to the region that owns the mob.
 */
public final class CustomEntities implements Listener, Mobs {

    /** Ticks between looks at how a mob is moving. */
    static final int PERIOD = 2;

    /** The longest a death animation may hold the model up, in ticks. */
    static final int DEATH_CAP = 100;

    /** Blocks per tick: anything under this is standing still. */
    static final double MOVING = 0.015;

    /** Blocks per tick: anything over this is running. A zombie walks at about 0.1. */
    static final double RUNNING = 0.18;

    private final Plugin plugin;
    private final Items items;
    /** Set once at startup, after the models it wears exist. See {@link #bound(BoundModels)}. */
    private BoundModels bound;
    private final RigTags tags;
    private final Sounds sounds;

    /** Marks a mob as ours, and says which entity it is. */
    private final NamespacedKey idKey;
    /** Marks a spawn egg as ours, and says what it hatches. */
    private final NamespacedKey eggKey;

    private volatile Map<ContentId, EntityInfo> entities = Map.of();

    /** The mobs standing in loaded chunks, by uuid. Concurrent: regions add their own. */
    private final Map<UUID, Living> living = new ConcurrentHashMap<>();
    /** Parts let go for a death animation, until it ends. */
    private final Map<UUID, List<ItemDisplay>> dying = new ConcurrentHashMap<>();
    private Regions.Fanout<Living> looking;
    private Tasks.Handle task;

    /** One mob's bookkeeping. Touched only on the mob's thread. */
    private static final class Living {
        final LivingEntity entity;
        final ContentId id;
        final MobBrain brain = new MobBrain();
        double lastX;
        double lastZ;
        boolean seen;
        MobState shown;
        boolean showing;
        long nextAmbient;
        long nextStep;
        /** An animation played by name through the API holds the stage until this tick. */
        long heldUntil;

        Living(LivingEntity entity, ContentId id) {
            this.entity = entity;
            this.id = id;
        }
    }

    public CustomEntities(Plugin plugin, Items items, RigTags tags, Sounds sounds) {
        this.plugin = plugin;
        this.items = items;
        this.tags = tags;
        this.sounds = sounds;
        this.idKey = new NamespacedKey(plugin, "entity");
        this.eggKey = new NamespacedKey(plugin, "entity-egg");
    }

    /**
     * Wires the rig machinery. Separate from the constructor because the
     * plugin builds this before the animator exists and the content load that
     * fills the catalogue does not need it.
     */
    public void bound(BoundModels bound) {
        this.bound = bound;
    }

    /** Replaces the catalogue, as a reload or a push does. */
    public void replace(Map<ContentId, EntityInfo> loaded) {
        this.entities = loaded == null ? Map.of() : Map.copyOf(loaded);
    }

    /** Starts looking at the mobs, and adopts the ones already standing. */
    public void start() {
        looking = new Regions.Fanout<>(plugin);
        task = Tasks.globalTimer(plugin, this::tick, PERIOD, PERIOD);
        Regions.loaded(plugin, LivingEntity.class, this::adopt);
    }

    /**
     * Stops, and takes away any model mid-death. Off Folia only: there the
     * plugin is disabled after every region has stopped, and the parts were
     * non-persistent anyway, so they go with the server.
     */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (looking != null) {
            looking.clear();
        }
        if (!Regions.folia()) {
            for (List<ItemDisplay> parts : dying.values()) {
                bound.discard(parts);
            }
        }
        dying.clear();
        living.clear();
    }

    @Override
    public Collection<ContentId> ids() {
        List<ContentId> sorted = new ArrayList<>(entities.keySet());
        sorted.sort(ContentId::compareTo);
        return List.copyOf(sorted);
    }

    @Override
    public Optional<EntityInfo> info(ContentId id) {
        return id == null ? Optional.empty() : Optional.ofNullable(entities.get(id));
    }

    @Override
    public Optional<ContentId> idOf(Entity entity) {
        if (entity == null) {
            return Optional.empty();
        }
        return ContentId.parse(entity.getPersistentDataContainer()
                .get(idKey, PersistentDataType.STRING));
    }

    /**
     * Spawns one. On the thread that owns {@code where}.
     *
     * @return the mob, or empty if there is no such entity
     */
    @Override
    public Optional<Entity> spawn(Location where, ContentId id) {
        Optional<EntityInfo> found = info(id);
        if (where == null || where.getWorld() == null || found.isEmpty()) {
            return Optional.empty();
        }
        EntityInfo info = found.get();
        EntityType type;
        try {
            type = EntityType.valueOf(info.type());
        } catch (IllegalArgumentException e) {
            // Validated at load, so this is a mob that existed when the pack
            // was read and does not now: a server downgrade.
            return Optional.empty();
        }
        Class<? extends Entity> kind = type.getEntityClass();
        if (kind == null) {
            return Optional.empty();
        }
        // Configured BEFORE it is added to the world, so a CreatureSpawnEvent
        // listener already sees the mark and the name, and nobody ever sees a
        // bare zombie for a tick.
        Entity mob = ai.resourcepack.engine.core.version.Spawning.spawn(where.getWorld(), where, kind,
                spawned -> configure(spawned, id, info));
        if (mob instanceof LivingEntity) {
            LivingEntity body = (LivingEntity) mob;
            unjockey(body);
            applyHeight(body, info);
            if (info.health() > 0) {
                body.setHealth(Math.min(info.health(), maxHealth(body)));
            }
            dress(body, info);
            Living tracked = new Living(body, id);
            living.put(mob.getUniqueId(), tracked);
            // Its first look picks the loop; a spawn animation goes first.
            oneShot(tracked, MobState.SPAWN);
        }
        return Optional.of(mob);
    }

    private void configure(Entity mob, ContentId id, EntityInfo info) {
        mob.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, id.toString());
        for (String tag : info.tags()) {
            mob.addScoreboardTag(tag);
        }
        info.name().ifPresent(name -> mob.setCustomName(ChatColor.translateAlternateColorCodes('&',
                ai.resourcepack.engine.core.textfx.TextEffectCatalogue.formatInstalled(name))));
        mob.setCustomNameVisible(info.name().isPresent() && info.nameVisible());
        mob.setSilent(info.silent());
        // Kept, both ways. A custom entity that despawned because a player
        // walked far enough away would take somebody's boss with it, and
        // leave the model behind: a removed mount ejects its passengers.
        mob.setPersistent(true);
        if (mob instanceof Mob) {
            ((Mob) mob).setRemoveWhenFarAway(false);
        }
        if (!(mob instanceof LivingEntity)) {
            return;
        }
        LivingEntity body = (LivingEntity) mob;
        body.setCanPickupItems(false);
        if (!info.ai()) {
            body.setAI(false);
        }
        EntityEquipment equipment = body.getEquipment();
        if (equipment != null) {
            // Otherwise the vanilla armour and held item still render,
            // floating in the middle of whatever the model is.
            equipment.clear();
        }
        // Through keys rather than constants: the fields are GENERIC_* before
        // 1.21.3 on a type that is an enum there and an interface now, so a
        // constant would not compile against both. See Vanilla.
        if (info.health() > 0) {
            setBase(body, Vanilla.maxHealth(), info.health());
        }
        if (info.damage() > 0) {
            setBase(body, Vanilla.attribute("attack_damage"), info.damage());
        }
        if (info.speed() != 1) {
            Attribute speed = Vanilla.attribute("movement_speed").orElse(null);
            AttributeInstance instance = speed == null ? null : body.getAttribute(speed);
            if (instance != null) {
                instance.setBaseValue(instance.getBaseValue() * info.speed());
            }
        }
        if (info.knockbackResistance() > 0) {
            setBase(body, Vanilla.attribute("knockback_resistance"), info.knockbackResistance());
        }
    }

    /**
     * Undoes the extras vanilla rolls for a freshly spawned mob: a baby, a
     * spider jockey's skeleton, a baby zombie's chicken.
     *
     * <p>Vanilla's spawn finalising does these by chance, and a custom mob
     * is the model and nothing else: a baby is a smaller, faster hitbox under
     * a full-size model, and a jockey is a second, visible mob standing on it
     * or under it. A spider in the first real-client test arrived with a
     * skeleton riding it.
     */
    private static void unjockey(LivingEntity body) {
        if (body instanceof org.bukkit.entity.Ageable) {
            ((org.bukkit.entity.Ageable) body).setAdult();
        }
        for (Entity rider : List.copyOf(body.getPassengers())) {
            body.removePassenger(rider);
            rider.remove();
        }
        Entity mount = body.getVehicle();
        if (mount != null) {
            body.leaveVehicle();
            mount.remove();
        }
    }

    /**
     * The hitbox as tall as the model wants, through the {@code scale}
     * attribute. After spawning, because the mob's own height is the thing
     * being divided by and a baby or a slime size answers it differently from
     * the type. Below 1.20.5 there is no such attribute and the lookup is
     * empty, so the mob keeps its own box.
     */
    private static void applyHeight(LivingEntity body, EntityInfo info) {
        if (info.height() <= 0 || body.getHeight() <= 0) {
            return;
        }
        double scale = Math.max(0.0625, Math.min(16, info.height() / body.getHeight()));
        setBase(body, Vanilla.attribute("scale"), scale);
    }

    private static void setBase(LivingEntity body, Optional<Attribute> attribute, double value) {
        Attribute found = attribute.orElse(null);
        AttributeInstance instance = found == null ? null : body.getAttribute(found);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    private static double maxHealth(LivingEntity body) {
        Attribute max = Vanilla.maxHealth().orElse(null);
        AttributeInstance instance = max == null ? null : body.getAttribute(max);
        return instance == null ? 20 : instance.getValue();
    }

    /**
     * Puts the model on, through the same machinery {@code /rp bind} uses, so
     * an animated model is the whole rig and a still one is one display.
     *
     * <p>Two ways to name the art, and the fork is how it was delivered:
     * content-folder mobs name an item, Studio mobs a {@code custom_model_data}
     * string on paper, exactly as vehicles do.
     */
    private void dress(LivingEntity body, EntityInfo info) {
        ItemStack whole;
        String rigId;
        java.util.function.Function<String, ItemStack> partItem;
        if (info.carrier().isPresent()) {
            rigId = info.carrier().get();
            whole = carrierStack(rigId);
            partItem = this::carrierStack;
        } else if (info.model().isPresent()) {
            ContentId model = info.model().get();
            whole = items.create(model).orElse(null);
            rigId = model.toString();
            ItemStack base = whole;
            partItem = part -> {
                if (base == null) {
                    return null;
                }
                ItemStack stack = base.clone();
                stack.setAmount(1);
                // See Items.wearModel: below 1.21.4 a model is a number, not a name.
                ContentId.parse(part).ifPresent(id -> items.wearModel(stack, id));
                return stack;
            };
        } else {
            return;
        }
        if (whole == null) {
            return;
        }
        bound.dress(body, rigId, whole, partItem, info.scale(), info.turn(), info.headBone().orElse(null));
    }

    /** Paper wearing a Studio string: how a pushed pack's models are drawn. */
    private ItemStack carrierStack(String model) {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            tags.write(meta, List.of(model));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    // --- the animation runtime ------------------------------------------

    private void tick() {
        for (Living mob : living.values()) {
            if (!mob.entity.isValid()) {
                // Dead, or its chunk unloaded: EntitiesLoadEvent brings the
                // second kind back.
                living.remove(mob.entity.getUniqueId());
                continue;
            }
            looking.on(mob, mob.entity, this::look);
        }
    }

    private void look(Living mob) {
        LivingEntity body = mob.entity;
        if (!body.isValid() || body.isDead()) {
            return;
        }
        Location at = body.getLocation();
        double speed = 0;
        if (mob.seen) {
            double dx = at.getX() - mob.lastX;
            double dz = at.getZ() - mob.lastZ;
            speed = Math.sqrt(dx * dx + dz * dz) / PERIOD;
        }
        mob.seen = true;
        mob.lastX = at.getX();
        mob.lastZ = at.getZ();
        boolean moving = speed > MOVING;
        boolean chasing = body instanceof Mob && ((Mob) body).getTarget() != null;
        boolean running = speed > RUNNING || (moving && chasing);

        EntityInfo info = entities.get(mob.id);
        long now = body.getWorld().getGameTime();
        if (info != null) {
            MobState desired = MobState.loop(running, moving, info.animations()).orElse(null);
            if (now >= mob.heldUntil && (mob.brain.tick(now, desired) || mob.heldUntil != 0)) {
                mob.heldUntil = 0;
                show(mob, info, mob.brain.showing(), false);
            }
            sounds(mob, info, now, moving, running);
        }
    }

    /** Plays what the brain says, and tells anybody listening when it changed. */
    private void show(Living mob, EntityInfo info, MobState state, boolean restart) {
        String animation = state == null ? null : info.animations().get(state);
        if (animation == null) {
            bound.stop(mob.entity);
        } else {
            bound.play(mob.entity, animation, restart || state.oneShot());
        }
        MobState was = mob.shown;
        boolean changed = !mob.showing || was != state || restart;
        mob.shown = state;
        mob.showing = true;
        if (changed) {
            mob.entity.getServer().getPluginManager().callEvent(
                    new MobStateChangeEvent(mob.entity, mob.id, was, state));
        }
    }

    /**
     * A one-shot happened. Plays it if the mob has an animation for it and
     * nothing more important is playing.
     */
    private boolean oneShot(Living mob, MobState state) {
        EntityInfo info = entities.get(mob.id);
        if (info == null) {
            return false;
        }
        String animation = info.animations().get(state);
        if (animation == null) {
            return false;
        }
        double seconds = bound.animationSeconds(mob.entity, animation);
        if (seconds < 0) {
            return false;
        }
        long ticks = Math.max(1, Math.round(seconds * 20));
        if (state == MobState.DEATH) {
            ticks = Math.min(ticks, DEATH_CAP);
        }
        if (!mob.brain.trigger(state, mob.entity.getWorld().getGameTime(), ticks)) {
            return false;
        }
        mob.heldUntil = 0;
        show(mob, info, state, true);
        return true;
    }

    /** Ambient now and then while somebody is near, and footsteps while it moves. */
    private void sounds(Living mob, EntityInfo info, long now, boolean moving, boolean running) {
        if (info.sounds().isEmpty()) {
            return;
        }
        String ambient = info.sounds().get("ambient");
        if (ambient != null) {
            if (mob.nextAmbient == 0) {
                mob.nextAmbient = now + ThreadLocalRandom.current().nextInt(160, 400);
            } else if (now >= mob.nextAmbient) {
                mob.nextAmbient = now + ThreadLocalRandom.current().nextInt(160, 400);
                if (anybodyNear(mob.entity)) {
                    play(mob.entity, ambient);
                }
            }
        }
        String step = info.sounds().get("step");
        if (step != null && moving && mob.entity.isOnGround() && now >= mob.nextStep) {
            mob.nextStep = now + (running ? 6 : 9);
            play(mob.entity, step);
        }
    }

    private static boolean anybodyNear(LivingEntity body) {
        Location at = body.getLocation();
        for (Player player : body.getWorld().getPlayers()) {
            // A racy read of somebody else's region on Folia, and that is
            // fine: this decides whether a grunt is worth playing.
            if (player.getLocation().distanceSquared(at) < 24 * 24) {
                return true;
            }
        }
        return false;
    }

    /**
     * Plays one of a mob's sounds from it: a pack's or a push's by id, or a
     * vanilla sound event by name, which is what anything this server has
     * never heard of is taken to be.
     */
    private void play(LivingEntity body, String sound) {
        Optional<ContentId> id = ContentId.parse(sound);
        if (id.isPresent() && sounds.info(id.get()).isPresent()) {
            sounds.playFrom(body, id.get());
            return;
        }
        body.getWorld().playSound(body.getLocation(), sound, SoundCategory.HOSTILE, 1f, 1f);
    }

    /** Takes in a mob already standing in the world: a chunk load, a restart, a reload. */
    private void adopt(Entity entity) {
        if (!(entity instanceof LivingEntity) || entity.isDead()) {
            return;
        }
        Optional<ContentId> id = idOf(entity);
        if (id.isEmpty() || living.containsKey(entity.getUniqueId())) {
            return;
        }
        LivingEntity body = (LivingEntity) entity;
        EntityInfo info = entities.get(id.get());
        if (info != null && upgrade(body)) {
            // Spawned by a jar from before mobs were rigs: one plain display.
            // Dressed again, so it animates like one spawned today.
            dress(body, info);
        }
        living.put(entity.getUniqueId(), new Living(body, id.get()));
    }

    /** Removes the single display an older jar put on a mob, and says whether there was one. */
    private boolean upgrade(LivingEntity body) {
        boolean had = false;
        for (Entity passenger : List.copyOf(body.getPassengers())) {
            if (passenger instanceof ItemDisplay && bound.parts(body).stream()
                    .noneMatch(part -> part.getUniqueId().equals(passenger.getUniqueId()))) {
                passenger.remove();
                had = true;
            }
        }
        return had;
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            adopt(entity);
        }
    }

    // --- what happens to it ---------------------------------------------

    /** Hurt, unless the blow kills it, which is the death's to show. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        Living mob = living.get(event.getEntity().getUniqueId());
        if (mob == null || mob.entity.getHealth() - event.getFinalDamage() <= 0) {
            return;
        }
        oneShot(mob, MobState.HURT);
        EntityInfo info = entities.get(mob.id);
        if (info != null && info.sounds().containsKey("hurt")) {
            play(mob.entity, info.sounds().get("hurt"));
        }
    }

    /** It hit something, with its own body or something it fired. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (damager instanceof Projectile && ((Projectile) damager).getShooter() instanceof Entity) {
            damager = (Entity) ((Projectile) damager).getShooter();
        }
        Living mob = damager == null ? null : living.get(damager.getUniqueId());
        if (mob == null) {
            return;
        }
        // On the attacker's own thread? Not necessarily on Folia — the victim
        // owns this event. Close enough to be the same region for a melee hit;
        // a shot across a region border is handed over.
        Runnable act = () -> {
            oneShot(mob, MobState.ATTACK);
            EntityInfo info = entities.get(mob.id);
            if (info != null && info.sounds().containsKey("attack")) {
                play(mob.entity, info.sounds().get("attack"));
            }
        };
        if (Tasks.owns(mob.entity)) {
            act.run();
        } else {
            Tasks.on(plugin, mob.entity, act);
        }
    }

    /**
     * Its loot, its sound, its event, and its model's last act.
     *
     * <p>The model is let go rather than removed: vanilla keeps a dead body a
     * second, and a death animation may want longer, so the parts are made
     * non-persistent and taken away by a task when it ends.
     */
    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        LivingEntity body = event.getEntity();
        Optional<ContentId> id = idOf(body);
        if (id.isEmpty()) {
            return;
        }
        EntityInfo info = entities.get(id.get());
        if (info != null) {
            // Replaced, never added to: a rhino that also drops a polar bear's
            // fish is two animals again.
            event.getDrops().clear();
            event.getDrops().addAll(roll(info.drops()));
            event.setDroppedExp(info.xp());
            if (info.sounds().containsKey("death")) {
                play(body, info.sounds().get("death"));
            }
        }
        // Bukkit's event cannot say what the thing was; ours can, and hands
        // over Bukkit's own drops list so the loot of a custom mob is edited
        // in one listener rather than two that have to find each other.
        body.getServer().getPluginManager().callEvent(
                new ai.resourcepack.engine.api.event.EntityDeathEvent(body, id.get(),
                        body.getKiller(), event.getDrops()));

        Living mob = living.remove(body.getUniqueId());
        boolean animating = mob != null && oneShot(mob, MobState.DEATH);
        List<ItemDisplay> parts = bound.release(body);
        // A display an older jar put on, which is no part of a rig.
        for (Entity passenger : List.copyOf(body.getPassengers())) {
            if (passenger instanceof ItemDisplay && !parts.contains(passenger)) {
                passenger.remove();
            }
        }
        if (parts.isEmpty()) {
            return;
        }
        if (!animating) {
            bound.discard(parts);
            return;
        }
        double seconds = bound.animationSeconds(body, info.animations().get(MobState.DEATH));
        long ticks = Math.max(1, Math.min(DEATH_CAP, Math.round(seconds * 20)));
        UUID key = body.getUniqueId();
        dying.put(key, parts);
        Tasks.atLater(plugin, body.getLocation(), () -> {
            List<ItemDisplay> left = dying.remove(key);
            if (left != null) {
                bound.discard(left);
            }
        }, ticks);
    }

    private List<ItemStack> roll(List<EntityDrop> drops) {
        List<ItemStack> out = new ArrayList<>();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (EntityDrop drop : drops) {
            if (drop.chance() < 1 && random.nextDouble() >= drop.chance()) {
                continue;
            }
            int count = drop.min() + (drop.max() > drop.min() ? random.nextInt(drop.max() - drop.min() + 1) : 0);
            if (count <= 0) {
                continue;
            }
            ItemStack stack = dropStack(drop.item(), count);
            if (stack != null) {
                out.add(stack);
            }
        }
        return out;
    }

    /**
     * A drop's item: a pack's own by id, a vanilla one by name, or for a
     * {@code studio:} id this server has no item for, paper wearing that
     * string — how a pushed pack draws its things, as {@link #carrierStack}.
     */
    private ItemStack dropStack(String item, int count) {
        Optional<ContentId> id = ContentId.parse(item);
        if (id.isPresent() && !"minecraft".equals(id.get().namespace())) {
            Optional<ItemStack> made = items.create(id.get(), count);
            if (made.isPresent()) {
                return made.get();
            }
            if ("studio".equals(id.get().namespace())) {
                ItemStack paper = carrierStack(id.get().path());
                paper.setAmount(count);
                return paper;
            }
            return null;
        }
        Material material = Material.matchMaterial(item);
        return material == null || !material.isItem() ? null : new ItemStack(material, count);
    }

    // --- what it is not -------------------------------------------------

    /**
     * No breeding, taming, trading, riding, milking, shearing or dyeing: a
     * rhino that opens a villager's trade screen is a villager. A name tag
     * and a lead are what is left, because those are about the player's
     * relationship with any animal, not about the one underneath.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (idOf(event.getRightClicked()).isEmpty()) {
            return;
        }
        ItemStack held = event.getHand() == org.bukkit.inventory.EquipmentSlot.OFF_HAND
                ? event.getPlayer().getInventory().getItemInOffHand()
                : event.getPlayer().getInventory().getItemInMainHand();
        Material type = held == null ? Material.AIR : held.getType();
        if (type != Material.NAME_TAG && type != Material.LEAD) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        if (idOf(event.getMother()).isPresent() || idOf(event.getFather()).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTame(EntityTameEvent event) {
        if (idOf(event.getEntity()).isPresent()) {
            event.setCancelled(true);
        }
    }

    /** A zombie drowning, a pig struck by lightning: the mob underneath must stay what it is. */
    @EventHandler(ignoreCancelled = true)
    public void onTransform(EntityTransformEvent event) {
        if (idOf(event.getEntity()).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (idOf(event.getEntity()).isPresent()) {
            event.setCancelled(true);
        }
    }

    /**
     * Daylight, and only daylight: a plain combust event is the sun, while
     * fire, lava and a flaming sword arrive as the block and entity kinds and
     * should still burn it.
     */
    @EventHandler(ignoreCancelled = true)
    public void onCombust(EntityCombustEvent event) {
        if (event instanceof EntityCombustByEntityEvent || event instanceof EntityCombustByBlockEvent) {
            return;
        }
        Optional<ContentId> id = idOf(event.getEntity());
        if (id.isPresent() && !info(id.get()).map(EntityInfo::burns).orElse(false)) {
            event.setCancelled(true);
        }
    }

    // --- spawn eggs -----------------------------------------------------

    @Override
    public Optional<ItemStack> egg(ContentId id, int amount) {
        Optional<EntityInfo> found = info(id);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        EntityInfo info = found.get();
        // The underlying mob's own egg, so the colours say roughly what kind of
        // creature it is; a mob without one gets a pig's, which nobody fears.
        Material egg = Material.matchMaterial(info.type() + "_SPAWN_EGG");
        if (egg == null || !egg.isItem()) {
            egg = Material.PIG_SPAWN_EGG;
        }
        ItemStack stack = new ItemStack(egg, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            String name = info.name().map(n -> ChatColor.translateAlternateColorCodes('&', n))
                    .orElse(id.path());
            meta.setDisplayName(ChatColor.RESET + name + ChatColor.RESET + " Spawn Egg");
            meta.setLore(List.of(ChatColor.GRAY + id.toString()));
            meta.getPersistentDataContainer().set(eggKey, PersistentDataType.STRING, id.toString());
            stack.setItemMeta(meta);
        }
        return Optional.of(stack);
    }

    private Optional<ContentId> eggOf(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return Optional.empty();
        }
        ItemMeta meta = stack.getItemMeta();
        return meta == null ? Optional.empty()
                : ContentId.parse(meta.getPersistentDataContainer().get(eggKey, PersistentDataType.STRING));
    }

    /**
     * Our egg used on a block hatches our mob there. Cancelled first in every
     * case, including on a spawner, which a vanilla egg would otherwise turn
     * into a spawner of the plain mob underneath.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onEgg(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_AIR) {
            return;
        }
        Optional<ContentId> id = eggOf(event.getItem());
        if (id.isEmpty()) {
            return;
        }
        event.setCancelled(true);
        Block clicked = event.getClickedBlock();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || clicked == null) {
            return;
        }
        Block at = clicked.getRelative(event.getBlockFace());
        Location where = at.getLocation().add(0.5, 0, 0.5);
        where.setYaw(event.getPlayer().getLocation().getYaw() + 180f);
        if (spawn(where, id.get()).isPresent() && event.getPlayer().getGameMode() != GameMode.CREATIVE) {
            ItemStack used = event.getItem();
            used.setAmount(used.getAmount() - 1);
        }
    }

    /** Our egg used on a mob would hatch a vanilla baby; it does nothing. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onEggOnMob(PlayerInteractEntityEvent event) {
        ItemStack held = event.getHand() == org.bukkit.inventory.EquipmentSlot.OFF_HAND
                ? event.getPlayer().getInventory().getItemInOffHand()
                : event.getPlayer().getInventory().getItemInMainHand();
        if (eggOf(held).isPresent()) {
            event.setCancelled(true);
        }
    }

    /** A dispenser would hatch the vanilla mob. */
    @EventHandler(ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        if (eggOf(event.getItem()).isPresent()) {
            event.setCancelled(true);
        }
    }

    // --- the rest of the API ---------------------------------------------

    @Override
    public Optional<MobState> state(Entity entity) {
        Living mob = entity == null ? null : living.get(entity.getUniqueId());
        return mob == null ? Optional.empty() : Optional.ofNullable(mob.brain.showing());
    }

    @Override
    public boolean play(Entity entity, MobState state) {
        Living mob = entity == null ? null : living.get(entity.getUniqueId());
        if (mob == null || state == null) {
            return false;
        }
        if (state.oneShot()) {
            return oneShot(mob, state);
        }
        // A loop asked for by name: shown until the mob's movement says
        // otherwise, which is the next change of loop.
        EntityInfo info = entities.get(mob.id);
        if (info == null || !info.animations().containsKey(state)) {
            return false;
        }
        show(mob, info, state, true);
        return true;
    }

    @Override
    public boolean play(Entity entity, String animation, double seconds) {
        Living mob = entity == null ? null : living.get(entity.getUniqueId());
        if (mob == null || animation == null) {
            return false;
        }
        double length = seconds > 0 ? seconds : bound.animationSeconds(entity, animation);
        if (length < 0 || !bound.play(entity, animation, true)) {
            return false;
        }
        mob.heldUntil = entity.getWorld().getGameTime() + Math.max(1, Math.round(length * 20));
        return true;
    }

    /** For the commands' list: what the mob's catalogue entry is, briefly. */
    public static String describe(EntityInfo entity) {
        StringBuilder said = new StringBuilder(entity.type().toLowerCase(Locale.ROOT));
        if (entity.health() > 0) {
            said.append(" · ").append(trim(entity.health())).append(" hp");
        }
        if (entity.scale() != 1f) {
            said.append(" · ").append(trim(entity.scale())).append("x");
        }
        if (!entity.animations().isEmpty()) {
            said.append(" · ").append(entity.animations().size()).append(" anim");
        }
        entity.model().ifPresent(model -> said.append(" · ").append(model));
        return said.toString();
    }

    /** 40 rather than 40.0, since a whole number of hearts is the usual case. */
    private static String trim(double value) {
        return value == Math.rint(value)
                ? String.valueOf((long) value)
                : String.valueOf(Math.round(value * 10) / 10.0);
    }
}
