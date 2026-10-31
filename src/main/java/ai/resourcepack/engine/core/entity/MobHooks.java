package ai.resourcepack.engine.core.entity;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.EntityInfo;
import org.bukkit.entity.LivingEntity;

/**
 * What else wants to hear about a custom mob's life: the skill runtime, which
 * keeps its own state, but needs the two moments only {@link CustomEntities}
 * sees. On the mob's own thread.
 */
public interface MobHooks {

    MobHooks NONE = new MobHooks() {
        @Override
        public void spawned(LivingEntity body, ContentId id) {
        }

        @Override
        public void looked(LivingEntity body, ContentId id, EntityInfo info, long now) {
        }
    };

    /** It was just spawned by a command, an egg, a plugin or natural spawning. */
    void spawned(LivingEntity body, ContentId id);

    /** One of its looks, every {@link CustomEntities#PERIOD} ticks while it is alive and loaded. */
    void looked(LivingEntity body, ContentId id, EntityInfo info, long now);
}
