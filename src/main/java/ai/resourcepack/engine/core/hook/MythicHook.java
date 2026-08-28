package ai.resourcepack.engine.core.hook;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.core.model.BoundModels;

import io.lumine.mythic.api.adapters.AbstractEntity;
import io.lumine.mythic.api.config.MythicLineConfig;
import io.lumine.mythic.api.skills.ITargetedEntitySkill;
import io.lumine.mythic.api.skills.SkillMetadata;
import io.lumine.mythic.api.skills.SkillResult;
import io.lumine.mythic.bukkit.BukkitAdapter;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.bukkit.events.MythicMechanicLoadEvent;
import io.lumine.mythic.core.skills.SkillExecutor;
import io.lumine.mythic.core.skills.SkillMechanic;

import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.Optional;

/**
 * Three mechanics, so a MythicMobs mob can wear one of this server's models.
 *
 * <p>This is where binding actually gets used. Most of the servers that would
 * pay for a model engine are already driving their mobs from MythicMobs, and a
 * feature they cannot reach from a skill line is a feature they do not have —
 * so the API is the capability and this is the delivery of it.
 *
 * <pre>
 * Skills:
 * - rpmodel{model=mypack:golem} @self ~onSpawn
 * - rpanimate{animation=roar} @self ~onDamaged
 * - rpunmodel @self ~onDeath
 * </pre>
 *
 * <p><strong>Registered by event, not by a hard dependency.</strong>
 * MythicMobs fires {@link MythicMechanicLoadEvent} for every mechanic name it
 * fails to recognise; answering it is the whole integration. So this file is
 * the only one that names MythicMobs, the jar is compileOnly, and a server
 * without it never loads this class at all — see
 * {@link #register(Plugin, BoundModels)}.
 *
 * <p><strong>Nothing here spawns anything.</strong> The mob is MythicMobs's,
 * with its own AI, its own skill tree and its own health bar; all this does is
 * change what it looks like. An integration that swapped the entity would take
 * the mob away from the plugin running it.
 */
public final class MythicHook implements Listener {

    private final BoundModels bound;

    private MythicHook(BoundModels bound) {
        this.bound = bound;
    }

    /**
     * Registers the mechanics if MythicMobs is installed.
     *
     * <p>The class check has to be here rather than inside the constructor:
     * this class names MythicMobs types in its signatures, so merely loading
     * it on a server without the jar throws. Guarding the load itself is what
     * keeps that from happening — the same shape as
     * {@link Placeholders#register}.
     *
     * @return whether it registered
     */
    public static boolean register(Plugin plugin, BoundModels bound) {
        if (plugin.getServer().getPluginManager().getPlugin("MythicMobs") == null) {
            return false;
        }
        try {
            Class.forName("io.lumine.mythic.bukkit.events.MythicMechanicLoadEvent");
        } catch (ClassNotFoundException e) {
            return false;
        }
        plugin.getServer().getPluginManager().registerEvents(new MythicHook(bound), plugin);
        return true;
    }

    /**
     * MythicMobs asking whether anybody owns a mechanic name it does not know.
     *
     * <p>Every unrecognised line in every skill file comes through here, so it
     * answers on the three names that are ours and returns silently on
     * everything else. A mechanic registered for a name somebody else owns is
     * a conflict nobody could diagnose.
     */
    @EventHandler
    public void onMechanicLoad(MythicMechanicLoadEvent event) {
        String name = event.getMechanicName().toLowerCase(Locale.ROOT);
        // Not optional and not decorative: SkillMechanic's constructor reads
        // getPlugin() straight off this, so a null one is a NullPointerException
        // inside MythicMobs while it is loading somebody's skill file.
        SkillExecutor manager = MythicBukkit.inst().getSkillManager();
        switch (name) {
            case "rpmodel":
            case "rpengine:model":
                event.register(new Wear(manager, event.getConfig(), bound));
                break;
            case "rpunmodel":
            case "rpengine:unmodel":
                event.register(new Remove(manager, event.getConfig(), bound));
                break;
            case "rpanimate":
            case "rpengine:animate":
                event.register(new Animate(manager, event.getConfig(), bound));
                break;
            default:
                break;
        }
    }

    /**
     * {@code rpmodel{model=mypack:golem;scale=1.5}}
     *
     * <p>Targeted at an entity rather than at no target, so {@code @self} puts
     * it on the caster and any other targeter puts it on whatever that names —
     * which is what makes a summoner able to dress what it summons.
     */
    private static final class Wear extends SkillMechanic implements ITargetedEntitySkill {

        private final BoundModels bound;
        private final String model;
        private final float scale;

        Wear(SkillExecutor manager, MythicLineConfig config, BoundModels bound) {
            // The file is null and may be: it is stored and never dereferenced.
            super(manager, null, config.getLine(), config);
            this.bound = bound;
            // Several spellings for one thing, because a config line is typed
            // from memory and "m" is what somebody reaches for second.
            this.model = config.getString(new String[]{"model", "m", "id"}, null);
            this.scale = (float) config.getDouble(new String[]{"scale", "s"}, 1);
            setAsyncSafe(false);
        }

        @Override
        public SkillResult castAtEntity(SkillMetadata data, AbstractEntity target) {
            Optional<ContentId> id = ContentId.parse(model);
            if (id.isEmpty()) {
                // A bad id is a config mistake, and this is the result that
                // makes MythicMobs say so rather than failing quietly.
                return SkillResult.INVALID_CONFIG;
            }
            Entity entity = BukkitAdapter.adapt(target);
            return entity != null && bound.bind(entity, id.get(), scale)
                    ? SkillResult.SUCCESS
                    : SkillResult.INVALID_TARGET;
        }
    }

    /** {@code rpunmodel} — gives the mob its own body back. */
    private static final class Remove extends SkillMechanic implements ITargetedEntitySkill {

        private final BoundModels bound;

        Remove(SkillExecutor manager, MythicLineConfig config, BoundModels bound) {
            // The file is null and may be: it is stored and never dereferenced.
            super(manager, null, config.getLine(), config);
            this.bound = bound;
            setAsyncSafe(false);
        }

        @Override
        public SkillResult castAtEntity(SkillMetadata data, AbstractEntity target) {
            Entity entity = BukkitAdapter.adapt(target);
            return entity != null && bound.unbind(entity) ? SkillResult.SUCCESS : SkillResult.INVALID_TARGET;
        }
    }

    /**
     * {@code rpanimate{animation=roar}}, or {@code animation=stop}.
     *
     * <p>By name, because a bound model has nothing to punch and nothing to
     * walk into — the triggers a placed rig resolves have nothing to fire
     * from, and the skill tree is what decides when a mob roars anyway.
     */
    private static final class Animate extends SkillMechanic implements ITargetedEntitySkill {

        private final BoundModels bound;
        private final String animation;

        Animate(SkillExecutor manager, MythicLineConfig config, BoundModels bound) {
            // The file is null and may be: it is stored and never dereferenced.
            super(manager, null, config.getLine(), config);
            this.bound = bound;
            this.animation = config.getString(new String[]{"animation", "anim", "a"}, null);
            setAsyncSafe(false);
        }

        @Override
        public SkillResult castAtEntity(SkillMetadata data, AbstractEntity target) {
            Entity entity = BukkitAdapter.adapt(target);
            if (entity == null || animation == null) {
                return SkillResult.INVALID_CONFIG;
            }
            if (animation.equalsIgnoreCase("stop")) {
                return bound.stop(entity) ? SkillResult.SUCCESS : SkillResult.INVALID_TARGET;
            }
            // restart: a skill line firing means the mob is doing the thing
            // NOW, unlike a trigger, which may be several players clicking.
            return bound.play(entity, animation, true) ? SkillResult.SUCCESS : SkillResult.INVALID_TARGET;
        }
    }
}
