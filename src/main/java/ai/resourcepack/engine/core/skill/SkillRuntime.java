package ai.resourcepack.engine.core.skill;

import ai.resourcepack.common.scheduler.Tasks;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.EntityInfo;
import ai.resourcepack.engine.api.Items;
import ai.resourcepack.engine.api.MobSkills;
import ai.resourcepack.engine.api.Mobs;
import ai.resourcepack.engine.api.SkillCondition;
import ai.resourcepack.engine.api.SkillMechanic;
import ai.resourcepack.engine.api.SkillTargeter;
import ai.resourcepack.engine.api.Sounds;
import ai.resourcepack.engine.api.Vfx;
import ai.resourcepack.engine.api.event.MobSkillEvent;
import ai.resourcepack.engine.core.entity.MobHooks;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * Runs custom mobs' skills: hears the triggers, walks the lines, waits out
 * the delays, keeps each mob's cooldowns, stance and health thresholds.
 *
 * <p><b>Everything runs on the caster's own thread</b>: the events that
 * trigger a skill arrive there (an attack is the victim's event, so on Folia
 * an attacker in another region is handed its skill rather than run it in the
 * wrong place), a delay waits on the caster's scheduler, and a death skill,
 * whose caster is gone, waits on the region where it died.
 *
 * <p><b>A bad config degrades, it does not lag.</b> At most
 * {@link #MAX_LINES_PER_TICK} lines run in one go per tree, at most
 * {@link #MAX_RUNNING} trees wait per mob, metaskill calls go at most
 * {@link #MAX_DEPTH} deep, and a skill that sets off another mob's skill
 * (damage reflected back and forth) stops at {@link #MAX_NESTING}. Each limit
 * says so once in the console and drops what is over it.
 */
public final class SkillRuntime implements Listener, MobSkills, MobHooks {

    static final int MAX_LINES_PER_TICK = 64;
    static final int MAX_RUNNING = 200;
    static final int MAX_DEPTH = 16;
    static final int MAX_NESTING = 8;

    /** What the runtime needs from the rest of the engine, wired by the plugin. */
    public interface Host {
        Mobs mobs();

        /** Null when the server is too old for VFX. */
        Vfx vfx();

        Sounds sounds();

        Items items();

        /** Tints a custom mob's model {@code rgb} for {@code ticks}, as its hurt flash does. */
        boolean tint(Entity entity, int rgb, long ticks);

        /** A custom mob's level; 1 for anything without one. */
        default int level(Entity entity) {
            return 1;
        }

        /** What a mob's level multiplies its skills' amounts by ({@code per-level: {power: ...}}). */
        default double levelPower(Entity entity) {
            return 1;
        }

        /** A custom mob's faction, or null. */
        default String faction(Entity entity) {
            return null;
        }

        /** Spawns a custom mob at a level (0: rolled from its own {@code level:}). */
        default Optional<Entity> spawn(Location where, ContentId id, int level) {
            return mobs().spawn(where, id);
        }

        /** Shows or hides a bone of the model {@code entity} wears; false when it has no such bone. */
        default boolean boneVisible(Entity entity, String bone, boolean visible) {
            return false;
        }

        /** Puts another model on a custom mob, keeping its size and turn. */
        default boolean swapModel(Entity entity, ContentId model) {
            return false;
        }

        /** Which animation a custom mob plays for {@code state} from now on; null puts its own back. */
        default boolean defaultState(Entity entity, ai.resourcepack.engine.api.MobState state, String animation) {
            return false;
        }
    }

    final Plugin plugin;
    final Host host;
    final SkillRegistry registry = new SkillRegistry();
    /** Marks what a mob summoned with who summoned it. */
    final NamespacedKey parentKey;
    /** Marks a projectile a skill fired, so its hit is not another ~onAttack. */
    final NamespacedKey skillShotKey;
    /** The key CustomEntities reads a custom mob's shot damage from; a skill's shots wear it too. */
    final NamespacedKey shotKey;

    private volatile Map<ContentId, SkillDefinitions.MobSkillSet> mobs = Map.of();
    private volatile Map<ContentId, SkillDefinitions.Metaskill> metaskills = Map.of();
    private final Map<UUID, SkillState> states = new ConcurrentHashMap<>();
    /** Bumped by every reload: a tree waiting from before it stops instead of running stale lines. */
    volatile int generation;

    /**
     * The most entities skills may have summoned and still alive in one world.
     * {@code summon} takes 32 a cast, and a mob that summons itself on spawn,
     * or TNT on a one-tick timer, was otherwise a server brought to its knees
     * by one mob, every world sharing the one main thread.
     */
    static final int MAX_SUMMONED_PER_WORLD = 100;

    /** What skills have summoned, swept of the dead as it is counted. */
    private final java.util.Set<Entity> summoned = ConcurrentHashMap.newKeySet();

    /** Whether a skill may summon one more into {@code world}. */
    boolean roomToSummon(org.bukkit.World world) {
        int count = 0;
        for (java.util.Iterator<Entity> it = summoned.iterator(); it.hasNext(); ) {
            Entity entity = it.next();
            if (!entity.isValid()) {
                it.remove();
            } else if (entity.getWorld().equals(world)) {
                count++;
            }
        }
        return count < MAX_SUMMONED_PER_WORLD;
    }

    /** Counts {@code entity} against {@link #MAX_SUMMONED_PER_WORLD}. */
    void summoned(Entity entity) {
        summoned.add(entity);
    }
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    /** How deep skills setting off skills go on this thread; see {@link #MAX_NESTING}. */
    private final ThreadLocal<int[]> nesting = ThreadLocal.withInitial(() -> new int[1]);
    /** Set while a skill's own damage is dealt, so it is not also the caster's ~onAttack. */
    final ThreadLocal<boolean[]> skillDamage = ThreadLocal.withInitial(() -> new boolean[1]);
    /** Inline skill lists in arguments ({@code onTick=[ - damage{a=1} ]}), parsed once. */
    private final Map<String, List<SkillLine>> inline = new ConcurrentHashMap<>();
    private Tasks.Handle sweep;
    /** {@code skills.debug: true} in config.yml: every line that runs is logged, with what it aimed at. */
    private volatile boolean debug;

    public SkillRuntime(Plugin plugin, Host host) {
        this.plugin = plugin;
        this.host = host;
        this.parentKey = new NamespacedKey(plugin, "skill-parent");
        this.skillShotKey = new NamespacedKey(plugin, "skill-shot");
        this.shotKey = new NamespacedKey(plugin, "entity-shot");
        Mechanics.install(this);
        MoreMechanics.install(this);
        Targeters.install(this);
        Conditions.install(this);
    }

    /** The names the load checks lines against. */
    public SkillDefinitions.Names names() {
        return registry;
    }

    /** Replaces every mob's skills and every metaskill, as a reload does. Waiting trees stop. */
    public void replace(SkillDefinitions.Result loaded) {
        generation++;
        inline.clear();
        warned.clear();
        this.mobs = loaded.mobs;
        this.metaskills = loaded.metaskills;
        this.debug = plugin.getConfig().getBoolean("skills.debug", false);
        for (SkillState state : states.values()) {
            state.timers.clear();
            state.running = 0;
        }
    }

    public void start() {
        // States of mobs that are gone: dead, unloaded, removed. isValid is a
        // safe read from any thread, Folia included.
        sweep = Tasks.globalTimer(plugin, () -> states.entrySet().removeIf(entry -> {
            Entity entity = plugin.getServer().getEntity(entry.getKey());
            boolean gone = entity == null || !entity.isValid();
            if (gone) {
                hideBars(entry.getValue());
            }
            return gone;
        }), 200, 200);
    }

    public void stop() {
        generation++;
        if (sweep != null) {
            sweep.cancel();
            sweep = null;
        }
        for (SkillState state : states.values()) {
            hideBars(state);
        }
        states.clear();
        Projectiles.stopAll();
    }

    /** Takes down every boss bar a mob's skills put up. */
    static void hideBars(SkillState state) {
        for (org.bukkit.boss.BossBar bar : state.bars.values()) {
            bar.removeAll();
            bar.setVisible(false);
        }
        state.bars.clear();
    }

    // --- state ----------------------------------------------------------

    private Optional<ContentId> mobOf(Entity entity) {
        return entity == null ? Optional.empty() : host.mobs().idOf(entity);
    }

    /** The state of a custom mob, made on first use. Null for anything else. */
    SkillState state(Entity entity) {
        if (!(entity instanceof LivingEntity)) {
            return null;
        }
        SkillState state = states.get(entity.getUniqueId());
        if (state != null) {
            return state;
        }
        Optional<ContentId> id = mobOf(entity);
        if (id.isEmpty()) {
            return null;
        }
        SkillDefinitions.MobSkillSet set = mobs.get(id.get());
        SkillState made = new SkillState(set == null ? null : set.stance);
        if (set != null) {
            made.vars.putAll(set.variables);
            if (set.threatTable) {
                made.threat = new ThreatTable();
            }
        }
        SkillState raced = states.putIfAbsent(entity.getUniqueId(), made);
        return raced == null ? made : raced;
    }

    String stanceOf(Entity entity) {
        SkillState state = entity == null ? null : states.get(entity.getUniqueId());
        return state == null ? null : state.stance;
    }

    void warnOnce(String key, String message) {
        if (warned.add(key)) {
            plugin.getLogger().warning(message);
        }
    }

    // --- the hooks ------------------------------------------------------

    @Override
    public void spawned(LivingEntity body, ContentId id) {
        SkillState state = state(body);
        if (state == null) {
            return;
        }
        Location at = body.getLocation();
        state.spawnedAt = new Object[]{at.getWorld() == null ? null : at.getWorld().getName(), at.getX(), at.getY(), at.getZ()};
        // A tick later: the caller of spawn() has not finished with it yet,
        // and a mob that summons on spawn must not recurse inside its own spawn.
        int gen = generation;
        Tasks.onLater(plugin, body, () -> {
            if (gen == generation && body.isValid()) {
                fire(body, id, "spawn", null, null, 0);
                fire(body, id, "spawnorload", null, null, 0);
            }
        }, () -> { }, 1);
    }

    @Override
    public void looked(LivingEntity body, ContentId id, EntityInfo info, long now) {
        SkillDefinitions.MobSkillSet set = mobs.get(id);
        boolean known = states.containsKey(body.getUniqueId());
        SkillState state = state(body);
        if (state == null) {
            return;
        }
        if (!known) {
            // Not seen spawning: a chunk load, a restart, a reload.
            fire(body, id, "load", null, null, 0);
            fire(body, id, "spawnorload", null, null, 0);
        }
        if (info != null) {
            name(body, info, state);
        }
        if (set == null || set.lines.isEmpty()) {
            return;
        }
        for (SkillLine line : set.lines) {
            if (!"timer".equals(SkillDefinitions.MobSkillSet.trigger(line))) {
                continue;
            }
            long interval;
            try {
                interval = Math.max(1, Long.parseLong(line.trigger.arg.trim()));
            } catch (RuntimeException e) {
                continue;
            }
            if (state.due(line, now, interval)) {
                dispatch(body, id, state, line, "timer", null, null, 0, false, null);
            }
        }
        if (state.threat != null && body instanceof Mob) {
            threat(body, (Mob) body, state);
        }
        if (body instanceof Mob) {
            LivingEntity target = ((Mob) body).getTarget();
            UUID now2 = target == null ? null : target.getUniqueId();
            UUID was = state.lastTarget;
            if (now2 == null ? was != null : !now2.equals(was)) {
                state.lastTarget = now2;
                if (target != null) {
                    if (was == null) {
                        fire(body, id, "entercombat", target, null, 0);
                    }
                    fire(body, id, "changetarget", target, null, 0);
                } else {
                    fire(body, id, "dropcombat", null, null, 0);
                }
            }
        }
    }

    /**
     * A threat-table mob's look: drop whoever is no longer a fair target, put
     * what its own AI chose on the table, and fight the top by the 110% rule.
     * Reads players elsewhere racily on Folia, as the boss bar does.
     */
    private void threat(LivingEntity body, Mob mob, SkillState state) {
        double reach = SkillMath.followRange(body) * 2 + 8;
        Location at = body.getLocation();
        state.threat.prune(uuid -> {
            Entity entity = plugin.getServer().getEntity(uuid);
            if (!(entity instanceof LivingEntity) || !entity.isValid() || entity.isDead()
                    || entity.getWorld() != body.getWorld()) {
                return true;
            }
            if (entity instanceof Player) {
                org.bukkit.GameMode mode = ((Player) entity).getGameMode();
                if (mode == org.bukkit.GameMode.CREATIVE || mode == org.bukkit.GameMode.SPECTATOR) {
                    return true;
                }
            }
            return entity.getLocation().distanceSquared(at) > reach * reach;
        });
        LivingEntity current = mob.getTarget();
        if (current != null && current.isValid() && state.threat.get(current.getUniqueId()) <= 0) {
            state.threat.add(current.getUniqueId(), 1);
        }
        UUID chosen = state.threat.choose(current == null ? null : current.getUniqueId());
        if (chosen == null) {
            if (current != null) {
                mob.setTarget(null);
            }
            return;
        }
        if (current == null || !chosen.equals(current.getUniqueId())) {
            Entity next = plugin.getServer().getEntity(chosen);
            if (next instanceof LivingEntity) {
                mob.setTarget((LivingEntity) next);
            }
        }
    }

    /** The threat table of a custom mob that has one, else null. */
    ThreatTable threatOf(Entity entity) {
        SkillState state = state(entity);
        return state == null ? null : state.threat;
    }

    /**
     * A name with {@code {hp}}, {@code {max_hp}} or {@code {stance}} in it,
     * kept current. Left alone for good once somebody else renames the mob,
     * the way a name tag renames any mob.
     */
    private void name(LivingEntity body, EntityInfo info, SkillState state) {
        Optional<String> written = info.name();
        if (written.isEmpty() || written.get().indexOf('{') < 0 || state.nameTaken) {
            return;
        }
        String current = body.getCustomName();
        if (state.nameSet != null && current != null && !current.equals(state.nameSet)) {
            state.nameTaken = true;
            return;
        }
        double max = SkillMath.maxHealth(body);
        String filled = written.get()
                .replace("{hp}", SkillText.number(Math.ceil(body.getHealth())))
                .replace("{max_hp}", SkillText.number(max))
                .replace("{hp%}", SkillText.number(max <= 0 ? 0 : Math.round(body.getHealth() / max * 100)))
                .replace("{stance}", state.stance == null ? "" : state.stance)
                .replace("{level}", String.valueOf(host.level(body)));
        String name = ChatColor.translateAlternateColorCodes('&',
                ai.resourcepack.engine.core.textfx.TextEffectCatalogue.formatInstalled(filled));
        if (!name.equals(current)) {
            body.setCustomName(name);
        }
        state.nameSet = name;
    }

    /**
     * A condition as written in a drop table's {@code Conditions:}, tested on
     * {@code body} ({@code ?~} or a trigger-scoped one on {@code trigger}).
     * One that does not parse, or names no condition, fails: a table that
     * cannot be checked drops nothing rather than everything.
     */
    @Override
    public boolean condition(LivingEntity body, Entity trigger, String text) {
        boolean onKiller = text.startsWith("~");
        SkillParser.Parsed parsed = SkillParser.condition(onKiller ? text.substring(1) : text, onKiller);
        if (!parsed.ok() || parsed.line.conditions.isEmpty()) {
            warnOnce("tablecond:" + text, "A drop table condition does not parse (" + text + "): " + parsed.error);
            return false;
        }
        SkillLine.Cond cond = parsed.line.conditions.get(0);
        SkillState state = state(body);
        Optional<ContentId> id = mobOf(body);
        if (state == null || id.isEmpty()) {
            return true;
        }
        SkillContext context = new SkillContext(body, id.get(), state, trigger, "droptable",
                new SkillContext.Run(null, generation, true, body.getLocation(), 0));
        Entity subject = cond.onTrigger || onKiller ? trigger : body;
        boolean pass = subject != null && test(context, cond, subject) != cond.negate;
        return cond.action.equals("false") ? !pass : pass;
    }

    /** A mob-scoped variable, or null when it has none of that name. */
    String variable(Entity entity, String name) {
        SkillState state = entity == null ? null : states.get(entity.getUniqueId());
        return state == null ? null : state.vars.get(SkillState.varName(name));
    }

    // --- triggers -------------------------------------------------------

    /**
     * Sets off every one of the mob's lines that listens for {@code trigger}.
     * {@code ~onCombat}, MythicMobs' default, also hears attack, damaged,
     * spawn and death.
     */
    void fire(LivingEntity body, ContentId id, String trigger, Entity triggerEntity,
              org.bukkit.event.Event event, double damage) {
        fire(body, id, trigger, null, triggerEntity, event, damage);
    }

    /**
     * The same, for a trigger with an argument a line may name: a signal's
     * name ({@code ~onSignal:enrage} hears only {@code enrage}; a bare
     * {@code ~onSignal} hears every signal).
     */
    void fire(LivingEntity body, ContentId id, String trigger, String arg, Entity triggerEntity,
              org.bukkit.event.Event event, double damage) {
        SkillDefinitions.MobSkillSet set = mobs.get(id);
        if (set == null || set.lines.isEmpty()) {
            return;
        }
        SkillState state = state(body);
        if (state == null) {
            return;
        }
        boolean death = trigger.equals("death");
        SkillContext.Run run = new SkillContext.Run(event, generation, death,
                death ? body.getLocation() : null, damage);
        int[] depth = nesting.get();
        if (depth[0] >= MAX_NESTING) {
            warnOnce("nesting", "A mob skill set off skills " + MAX_NESTING + " deep (two mobs hurting each "
                    + "other from ~onDamaged?). Stopped there; it happens again without this warning.");
            return;
        }
        depth[0]++;
        try {
            for (SkillLine line : set.lines) {
                String listens = SkillDefinitions.MobSkillSet.trigger(line);
                boolean hears = listens.equals(trigger) || (listens.equals("combat")
                        && (trigger.equals("attack") || trigger.equals("damaged") || trigger.equals("spawn")
                        || trigger.equals("death")));
                if (!hears || listens.equals("timer")) {
                    continue;
                }
                if (arg != null && line.trigger != null && line.trigger.arg != null && !line.trigger.arg.isBlank()
                        && !line.trigger.arg.trim().equalsIgnoreCase(arg)) {
                    continue;
                }
                dispatch(body, id, state, line, trigger, triggerEntity, event, damage, death, run);
            }
        } finally {
            depth[0]--;
            run.eventLive = false;
        }
    }

    private void dispatch(LivingEntity body, ContentId id, SkillState state, SkillLine line, String trigger,
                          Entity triggerEntity, org.bukkit.event.Event event, double damage, boolean death,
                          SkillContext.Run shared) {
        if (line.health != null) {
            double max = SkillMath.maxHealth(body);
            double health = body.getHealth();
            if (trigger.equals("damaged")) {
                // The blow has not landed yet; a threshold is about where it leaves the mob.
                health = Math.max(0, health - damage);
            }
            boolean in = line.health.test(health, max);
            if (line.health.once ? !state.crossing(line, in) : !in) {
                return;
            }
        }
        SkillContext.Run run = shared != null ? shared
                : new SkillContext.Run(event, generation, death, death ? body.getLocation() : null, damage);
        SkillContext context = new SkillContext(body, id, state, triggerEntity, trigger, run);
        context.power = host.levelPower(body);
        context.top = true;
        runLine(context, line);
        if (shared == null) {
            run.eventLive = false;
        }
    }

    // --- running --------------------------------------------------------

    /** Runs {@code lines} from {@code from}, waiting where a {@code delay} says to. */
    void execute(SkillContext context, List<SkillLine> lines, int from) {
        for (int i = from; i < lines.size(); i++) {
            if (!context.alive() || context.run.generation != generation) {
                return;
            }
            SkillLine line = lines.get(i);
            if (line.mechanic.equals("delay") || line.mechanic.equals("wait")) {
                long ticks = Math.round(new SkillCastImpl(this, context, line.args, null)
                        .number("ticks", 0, "t", "", "duration", "d"));
                if (ticks > 0) {
                    int next = i + 1;
                    later(context, ticks, () -> execute(context, lines, next));
                    return;
                }
                continue;
            }
            if (++context.run.executed > MAX_LINES_PER_TICK) {
                warnOnce("budget:" + line.source, "A mob skill ran " + MAX_LINES_PER_TICK + " lines without "
                        + "a delay (" + line.source + "); the rest of that run was dropped. Is a skill calling itself?");
                return;
            }
            runLine(context, line);
        }
    }

    /** One line: its chance, conditions and cooldown, then its delay and repeats, then the mechanic. */
    void runLine(SkillContext context, SkillLine line) {
        if (line.chance < 1 && ThreadLocalRandom.current().nextDouble() >= line.chance) {
            return;
        }
        for (SkillLine.Cond cond : line.conditions) {
            Entity subject = cond.onTrigger ? context.trigger : context.caster;
            boolean pass = subject != null && test(context, cond, subject);
            if (pass == cond.negate) {
                return;
            }
        }
        SkillCastImpl options = new SkillCastImpl(this, context, line.args, null);
        String cooldown = line.args.get("cooldown", "cd");
        if (cooldown != null) {
            String key = context.state.lineKey(line);
            long now = System.currentTimeMillis();
            if (context.state.cooling(key, now)) {
                return;
            }
            context.state.cool(key, now, options.number("cooldown", 0, "cd"));
        }
        if (context.top) {
            MobSkillEvent event = new MobSkillEvent(context.caster, context.mob, line.text, context.triggerName,
                    context.trigger);
            plugin.getServer().getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                return;
            }
            context.top = false;
        }
        long delay = Math.round(options.number("delay", 0));
        int repeat = Math.max(0, Math.min(200, (int) Math.round(options.number("repeat", 0))));
        long interval = Math.max(1, Math.round(options.number("repeatinterval", 20, "ri", "repeati", "interval")));
        if (line.mechanic.equals("aura") || line.mechanic.equals("buff") || line.mechanic.equals("debuff")) {
            // An aura's interval is its tick, not a repeat of the whole aura.
            repeat = 0;
        }
        Runnable once = () -> fire(context, line);
        if (delay > 0) {
            later(context, delay, once);
        } else {
            once.run();
        }
        for (int r = 1; r <= repeat; r++) {
            later(context, delay + r * interval, once);
        }
    }

    private void fire(SkillContext context, SkillLine line) {
        if (!context.alive()) {
            return;
        }
        SkillRegistry.Mechanic mechanic = registry.findMechanic(line.mechanic);
        if (mechanic == null) {
            warnOnce("mechanic:" + line.mechanic, "No mechanic " + line.mechanic + " (" + line.source
                    + "); that line does nothing until a plugin registers it.");
            return;
        }
        List<Object> targets = targets(context, line, mechanic.selfByDefault());
        if (debug) {
            plugin.getLogger().info("[skills] " + context.mob.path() + " ~" + context.triggerName + ": "
                    + line.text + " -> " + describe(targets));
        }
        try {
            mechanic.run(context, line, targets);
        } catch (RuntimeException e) {
            warnOnce("threw:" + line.source + line.mechanic, "Mob skill " + line.text + " (" + line.source
                    + ") failed: " + e);
            plugin.getLogger().log(Level.FINE, "Mob skill failed", e);
        }
    }

    private static String describe(List<Object> targets) {
        List<String> names = new ArrayList<>();
        for (Object target : targets) {
            if (names.size() == 4) {
                names.add("+" + (targets.size() - 4));
                break;
            }
            names.add(target instanceof Entity ? SkillText.name((Entity) target).replaceAll("§.", "")
                    : target instanceof Location ? "(" + Math.round(((Location) target).getX()) + ", "
                    + Math.round(((Location) target).getY()) + ", " + Math.round(((Location) target).getZ()) + ")"
                    : String.valueOf(target));
        }
        return names.isEmpty() ? "nothing" : String.join(", ", names);
    }

    /** Who or where a line aims at. */
    List<Object> targets(SkillContext context, SkillLine line, boolean selfByDefault) {
        if (line.targeter == null) {
            if (context.inherited != null) {
                return context.inherited;
            }
            if (selfByDefault || context.trigger == null) {
                return List.of(context.caster);
            }
            return List.of(context.trigger);
        }
        return Targeters.resolve(this, context, line.targeter);
    }

    /** Tests one condition against an entity or a place. */
    boolean test(SkillContext context, SkillLine.Cond cond, Object subject) {
        SkillRegistry.Condition condition = registry.findCondition(cond.ref.name);
        if (condition == null) {
            warnOnce("condition:" + cond.ref.name, "No condition " + cond.ref.name + "; it never passes.");
            return false;
        }
        try {
            return condition.test(new SkillCastImpl(this, context, cond.ref.args, subject));
        } catch (RuntimeException e) {
            warnOnce("cthrew:" + cond.ref.name, "Mob skill condition " + cond + " failed: " + e);
            return false;
        }
    }

    /** Runs {@code task} {@code ticks} later on the caster's thread (or where it died), counted against its cap. */
    void later(SkillContext context, long ticks, Runnable task) {
        SkillState state = context.state;
        if (state.running >= MAX_RUNNING) {
            if (!state.warnedBudget) {
                state.warnedBudget = true;
                plugin.getLogger().warning("A custom mob has " + MAX_RUNNING + " skills waiting at once; "
                        + "new ones are dropped until some finish.");
            }
            return;
        }
        state.running++;
        int gen = generation;
        Runnable wrapped = () -> {
            state.running = Math.max(0, state.running - 1);
            if (gen != generation) {
                return;
            }
            context.run.executed = 0;
            context.run.eventLive = false;
            task.run();
        };
        if (context.run.death || !context.caster.isValid()) {
            Location at = context.casterLocation();
            if (at.getWorld() == null) {
                state.running--;
                return;
            }
            Tasks.atLater(plugin, at, wrapped, Math.max(1, ticks));
        } else {
            Tasks.onLater(plugin, context.caster, wrapped, () -> state.running = Math.max(0, state.running - 1),
                    Math.max(1, ticks));
        }
    }

    // --- metaskills -----------------------------------------------------

    Optional<SkillDefinitions.Metaskill> metaskill(String name, ContentId from) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String written = name.trim().toLowerCase(Locale.ROOT);
        Map<ContentId, SkillDefinitions.Metaskill> all = metaskills;
        if (written.indexOf(':') > 0) {
            return ContentId.parse(written).map(all::get);
        }
        if (from != null) {
            Optional<ContentId> own = ContentId.of(from.namespace(), written);
            if (own.isPresent() && all.containsKey(own.get())) {
                return Optional.of(all.get(own.get()));
            }
        }
        // Any pack's, the first by id: what a bare name means when the caster's pack has none.
        return all.values().stream().filter(skill -> skill.id.path().equals(written))
                .min((a, b) -> a.id.compareTo(b.id));
    }

    /**
     * Casts a metaskill at {@code targets}: its cooldown, its conditions with
     * their actions, its target and trigger conditions, then its lines.
     */
    boolean cast(SkillContext parent, String name, List<Object> targets) {
        Optional<SkillDefinitions.Metaskill> found = metaskill(name, parent.mob);
        if (found.isEmpty()) {
            warnOnce("skill:" + name, "No metaskill " + name + " for " + parent.mob
                    + ". Metaskills go in a content pack's skills/ folder.");
            return false;
        }
        SkillDefinitions.Metaskill skill = found.get();
        if (parent.depth >= MAX_DEPTH) {
            warnOnce("depth:" + skill.id, "Metaskill " + skill.id + " was called " + MAX_DEPTH
                    + " deep; stopped there. Does it call itself without a delay?");
            return false;
        }
        String key = "skill:" + skill.id;
        long now = System.currentTimeMillis();
        if (parent.state.cooling(key, now)) {
            if (skill.onCooldownSkill != null) {
                cast(parent.child(targets), skill.onCooldownSkill, targets);
            }
            return false;
        }
        SkillContext context = parent.child(targets);
        for (SkillLine.Cond cond : skill.conditions) {
            boolean pass = test(context, cond, context.caster) != cond.negate;
            switch (cond.action) {
                case "false":
                    if (pass) {
                        return false;
                    }
                    break;
                case "power":
                    if (pass) {
                        try {
                            context.power *= Double.parseDouble(cond.actionArg);
                        } catch (NumberFormatException ignored) {
                            // Warned nowhere: a power that is not a number is a power of 1.
                        }
                    }
                    break;
                case "cast":
                    if (pass) {
                        cast(context, cond.actionArg, targets);
                    }
                    break;
                case "castinstead":
                    if (pass) {
                        cast(context, cond.actionArg, targets);
                        return true;
                    }
                    break;
                case "orelsecast":
                    if (!pass) {
                        cast(context, cond.actionArg, targets);
                        return false;
                    }
                    break;
                default:
                    if (!pass) {
                        return false;
                    }
            }
        }
        for (SkillLine.Cond cond : skill.triggerConditions) {
            boolean pass = context.trigger != null && test(context, cond, context.trigger) != cond.negate;
            if (pass == cond.action.equals("false")) {
                return false;
            }
        }
        List<Object> kept = targets;
        if (!skill.targetConditions.isEmpty()) {
            kept = new ArrayList<>();
            for (Object target : targets) {
                boolean all = true;
                for (SkillLine.Cond cond : skill.targetConditions) {
                    boolean pass = test(context, cond, target) != cond.negate;
                    if (pass == cond.action.equals("false")) {
                        all = false;
                        break;
                    }
                }
                if (all) {
                    kept.add(target);
                }
            }
            context.inherited = kept;
        }
        if (skill.cancelIfNoTargets && kept.isEmpty()) {
            return false;
        }
        MobSkillEvent event = new MobSkillEvent(context.caster, context.mob, skill.id.toString(),
                context.triggerName, context.trigger);
        plugin.getServer().getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return false;
        }
        parent.state.cool(key, now, skill.cooldown);
        execute(context, skill.lines, 0);
        return true;
    }

    /** The lines an argument lists inline, or names: {@code [ - damage{a=1} ]} or {@code Burn}. */
    List<SkillLine> inline(String value, String source) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return inline.computeIfAbsent(value.trim(), text -> {
            List<SkillLine> out = new ArrayList<>();
            for (String piece : SkillParser.inline(text)) {
                SkillParser.Parsed parsed = SkillParser.parse(piece);
                if (!parsed.ok()) {
                    warnOnce("inline:" + piece, "Skipped " + piece + " in " + source + ": " + parsed.error);
                    continue;
                }
                parsed.line.source = source;
                out.add(parsed.line);
            }
            return List.copyOf(out);
        });
    }

    // --- events ---------------------------------------------------------

    /** The entity behind a hit: a projectile's shooter, else the damager. */
    private static Entity source(Entity damager) {
        if (damager instanceof Projectile && ((Projectile) damager).getShooter() instanceof Entity) {
            return (Entity) ((Projectile) damager).getShooter();
        }
        return damager;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        Entity victim = event.getEntity();
        double damage = event.getFinalDamage();
        Entity attacker = null;
        Entity damager = null;
        if (event instanceof EntityDamageByEntityEvent) {
            damager = ((EntityDamageByEntityEvent) event).getDamager();
            attacker = source(damager);
        }
        Optional<ContentId> victimId = victim instanceof LivingEntity ? mobOf(victim) : Optional.empty();
        if (victimId.isPresent() && mobs.containsKey(victimId.get())) {
            SkillState hurt = state(victim);
            if (hurt != null && hurt.threat != null && attacker instanceof LivingEntity && attacker != victim
                    && damage > 0) {
                hurt.threat.add(attacker.getUniqueId(), damage);
            }
            fire((LivingEntity) victim, victimId.get(), "damaged", attacker, event, damage);
        }
        if (attacker == null || attacker == victim || skillDamage.get()[0]) {
            return;
        }
        if (damager != null && damager.getPersistentDataContainer().has(skillShotKey, PersistentDataType.BYTE)) {
            return;
        }
        Optional<ContentId> attackerId = attacker instanceof LivingEntity ? mobOf(attacker) : Optional.empty();
        if (attackerId.isEmpty() || !mobs.containsKey(attackerId.get())) {
            return;
        }
        LivingEntity body = (LivingEntity) attacker;
        ContentId id = attackerId.get();
        if (Tasks.owns(body)) {
            fire(body, id, "attack", victim, event, damage);
        } else {
            // The victim's region owns this event; the attacker's skill runs in its own.
            Tasks.on(plugin, body, () -> fire(body, id, "attack", victim, null, damage));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        Entity killer = dead.getKiller();
        EntityDamageEvent last = dead.getLastDamageCause();
        if (killer == null && last instanceof EntityDamageByEntityEvent) {
            killer = source(((EntityDamageByEntityEvent) last).getDamager());
        }
        Optional<ContentId> id = mobOf(dead);
        if (id.isPresent() && mobs.containsKey(id.get())) {
            fire(dead, id.get(), "death", killer, event, 0);
            SkillState gone = states.get(dead.getUniqueId());
            if (gone != null) {
                hideBars(gone);
            }
        }
        if (killer instanceof LivingEntity && killer != dead) {
            Optional<ContentId> killerId = mobOf(killer);
            if (killerId.isPresent() && mobs.containsKey(killerId.get())) {
                LivingEntity body = (LivingEntity) killer;
                Entity victim = dead;
                Runnable act = () -> {
                    fire(body, killerId.get(), "kill", victim, null, 0);
                    if (victim instanceof Player) {
                        fire(body, killerId.get(), "playerkill", victim, null, 0);
                    }
                };
                if (Tasks.owns(body)) {
                    act.run();
                } else {
                    Tasks.on(plugin, body, act);
                }
            }
        }
    }

    /** Not ignoring cancelled: the engine cancels most clicks on a custom mob, and a click is still a click. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) {
            return;
        }
        Entity clicked = event.getRightClicked();
        Optional<ContentId> id = clicked instanceof LivingEntity ? mobOf(clicked) : Optional.empty();
        if (id.isPresent() && mobs.containsKey(id.get())) {
            fire((LivingEntity) clicked, id.get(), "interact", event.getPlayer(), event, 0);
        }
    }

    /**
     * {@code signal{s=name}}: {@code ~onSignal:name} on {@code target}, a
     * custom mob, with the sender as its trigger. On the target's own thread.
     */
    void signal(Entity target, String name, LivingEntity from) {
        if (!(target instanceof LivingEntity)) {
            return;
        }
        Optional<ContentId> id = mobOf(target);
        if (id.isEmpty() || !mobs.containsKey(id.get())) {
            return;
        }
        LivingEntity body = (LivingEntity) target;
        Runnable act = () -> {
            if (body.isValid() && !body.isDead()) {
                fire(body, id.get(), "signal", name, from, null, 0);
            }
        };
        if (Tasks.owns(body)) {
            act.run();
        } else {
            Tasks.on(plugin, body, act);
        }
    }

    /** {@code ~onShoot}: a custom mob launched a projectile of its own (a bow, its ranged:, not a skill shot). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(org.bukkit.event.entity.ProjectileLaunchEvent event) {
        Projectile shot = event.getEntity();
        if (!(shot.getShooter() instanceof LivingEntity)
                || shot.getPersistentDataContainer().has(skillShotKey, PersistentDataType.BYTE)) {
            return;
        }
        LivingEntity shooter = (LivingEntity) shot.getShooter();
        Optional<ContentId> id = mobOf(shooter);
        if (id.isEmpty() || !mobs.containsKey(id.get())) {
            return;
        }
        Entity target = shooter instanceof Mob ? ((Mob) shooter).getTarget() : null;
        Runnable act = () -> fire(shooter, id.get(), "shoot", target, null, 0);
        if (Tasks.owns(shooter)) {
            act.run();
        } else {
            Tasks.on(plugin, shooter, act);
        }
    }

    /** {@code ~onProjectileHit}: a projectile a custom mob shot hit something. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onProjectileHit(org.bukkit.event.entity.ProjectileHitEvent event) {
        Projectile shot = event.getEntity();
        if (!(shot.getShooter() instanceof LivingEntity)
                || shot.getPersistentDataContainer().has(skillShotKey, PersistentDataType.BYTE)) {
            return;
        }
        LivingEntity shooter = (LivingEntity) shot.getShooter();
        Optional<ContentId> id = mobOf(shooter);
        if (id.isEmpty() || !mobs.containsKey(id.get())) {
            return;
        }
        projectileHit(shooter, id.get(), event.getHitEntity());
    }

    /** Fires {@code ~onProjectileHit} on the shooter's thread, with what was hit (if anything) as the trigger. */
    void projectileHit(LivingEntity shooter, ContentId id, Entity hit) {
        Runnable act = () -> {
            if (shooter.isValid() && !shooter.isDead()) {
                fire(shooter, id, "projectilehit", hit, null, 0);
            }
        };
        if (Tasks.owns(shooter)) {
            act.run();
        } else {
            Tasks.on(plugin, shooter, act);
        }
    }

    // --- the API --------------------------------------------------------

    @Override
    public void registerMechanic(String name, SkillMechanic mechanic, String... aliases) {
        if (name == null || mechanic == null) {
            return;
        }
        registry.mechanic((context, line, targets) -> {
            for (Object target : targets) {
                mechanic.cast(new SkillCastImpl(this, context, line.args, target));
            }
        }, name, aliases);
    }

    @Override
    public void registerCondition(String name, SkillCondition condition, String... aliases) {
        if (name == null || condition == null) {
            return;
        }
        registry.condition(condition::test, name, aliases);
    }

    @Override
    public void registerTargeter(String name, SkillTargeter targeter, String... aliases) {
        if (name == null || targeter == null) {
            return;
        }
        registry.targeter(cast -> {
            List<Object> out = new ArrayList<>(targeter.entities(cast));
            out.addAll(targeter.locations(cast));
            return out;
        }, name, aliases);
    }

    @Override
    public Collection<ContentId> skills() {
        List<ContentId> ids = new ArrayList<>(metaskills.keySet());
        ids.sort(ContentId::compareTo);
        return List.copyOf(ids);
    }

    @Override
    public boolean cast(Entity caster, String skill, Entity target) {
        if (!(caster instanceof LivingEntity)) {
            return false;
        }
        Optional<ContentId> id = mobOf(caster);
        SkillState state = state(caster);
        if (id.isEmpty() || state == null) {
            return false;
        }
        SkillContext.Run run = new SkillContext.Run(null, generation, false, null, 0);
        SkillContext context = new SkillContext((LivingEntity) caster, id.get(), state, target, "api", run);
        context.power = host.levelPower(caster);
        context.depth = -1;
        return cast(context, skill, List.of(target != null ? target : caster));
    }

    @Override
    public Optional<String> stance(Entity mob) {
        String stance = stanceOf(mob);
        return stance == null || stance.isEmpty() ? Optional.empty() : Optional.of(stance);
    }

    @Override
    public void stance(Entity mob, String stance) {
        SkillState state = state(mob);
        if (state != null) {
            state.stance = stance == null || stance.isBlank() ? null : stance.trim();
        }
    }

    /** Counts for the startup report: how many mechanics, targeters and conditions there are. */
    public String vocabulary() {
        return registry.mechanics() + " mechanic names";
    }

    /** Ids of every mob that has skills, for tests and the report. */
    Set<ContentId> mobsWithSkills() {
        return new HashSet<>(mobs.keySet());
    }
}
