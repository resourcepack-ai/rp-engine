package ai.resourcepack.engine.core.hook;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.core.model.BoundModels;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.exception.NPCLoadException;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitInfo;
import net.citizensnpcs.api.util.DataKey;

import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * An NPC that remembers the model it is wearing.
 *
 * <pre>
 * /npc select
 * /trait rpmodel
 * /npc rpmodel mypack:golem
 * </pre>
 *
 * <p><strong>Binding an NPC already worked</strong> — a Citizens NPC is an
 * ordinary Bukkit entity, so {@code /rp bind} has been putting models on them
 * since binding existed. What did not work is the NPC being <em>despawned and
 * respawned</em>, which Citizens does on a chunk unload, a reload and a server
 * restart: the entity that comes back is a new one, and the model went with
 * the old one.
 *
 * <p>So this trait is not "Citizens support". It is the one thing binding
 * could not do on its own: a place to write the model id down that survives
 * the entity, and a callback at the moment the new one exists.
 *
 * <p>Registered by name, guarded by a class check, and the only file besides
 * this one that mentions Citizens is the plugin's wiring. A server without it
 * never loads this class.
 */
public final class CitizensTrait extends Trait {

    /** Set once at registration, because Citizens constructs the trait itself. */
    private static BoundModels models;

    private String model = "";

    public CitizensTrait() {
        super("rpmodel");
    }

    /**
     * Registers the trait, if Citizens is installed.
     *
     * @return whether it registered
     */
    public static boolean register(Plugin plugin, BoundModels bound) {
        if (plugin.getServer().getPluginManager().getPlugin("Citizens") == null) {
            return false;
        }
        try {
            Class.forName("net.citizensnpcs.api.trait.Trait");
        } catch (ClassNotFoundException e) {
            return false;
        }
        models = bound;
        // Citizens builds the trait with a no-argument constructor, so there
        // is nowhere to pass anything in. Hence the static, which is the shape
        // its own API asks for rather than a shortcut.
        CitizensAPI.getTraitFactory().registerTrait(TraitInfo.create(CitizensTrait.class));
        return true;
    }

    /**
     * Remembers a bind against the NPC {@code host} belongs to.
     *
     * <p>What makes {@code /rp bind} work on an NPC properly rather than until
     * the next chunk unload. Reached through a function handed to
     * {@code BoundModels}, so nothing outside this file names Citizens.
     *
     * @return whether it was an NPC and the id was written down
     */
    public static boolean remember(Entity host, ContentId model) {
        try {
            if (host == null || !CitizensAPI.hasImplementation()) {
                return false;
            }
            net.citizensnpcs.api.npc.NPC npc = CitizensAPI.getNPCRegistry().getNPC(host);
            if (npc == null) {
                return false;
            }
            CitizensTrait trait = npc.getOrAddTrait(CitizensTrait.class);
            trait.model = model == null ? "" : model.toString();
            return true;
        } catch (NoClassDefFoundError | RuntimeException e) {
            // Its problem. The bind itself still happened.
            return false;
        }
    }

    /** What this NPC wears, or empty. */
    public String model() {
        return model;
    }

    /**
     * Puts a model on, and remembers it.
     *
     * <p>An empty id takes it off, which is what makes the command a toggle
     * rather than needing a second one.
     */
    public void model(String id) {
        this.model = id == null ? "" : id.trim();
        apply();
    }

    @Override
    public void onSpawn() {
        // The whole reason this exists: the entity here is a NEW one, and
        // whatever was bound to the old one went with it.
        apply();
    }

    @Override
    public void onDespawn() {
        // Nothing. The displays were passengers of an entity that has gone,
        // so they went with it — and reaching for them now would be reaching
        // for something already removed.
    }

    @Override
    public void load(DataKey key) throws NPCLoadException {
        model = key.getString("model", "");
    }

    @Override
    public void save(DataKey key) {
        key.setString("model", model);
    }

    private void apply() {
        Entity entity = getNPC() == null ? null : getNPC().getEntity();
        if (entity == null || models == null) {
            return;
        }
        if (model.isEmpty()) {
            models.unbind(entity);
            return;
        }
        ContentId.parse(model).ifPresent(id -> models.bind(entity, id, 1f));
    }
}
