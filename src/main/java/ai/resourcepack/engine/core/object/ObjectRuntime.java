package ai.resourcepack.engine.core.object;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ShaderObjectInfo;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * The 3D shader objects standing in the worlds, and the tick that drives them.
 *
 * <p>An object is an item display showing its item (see {@link ObjectItems}),
 * not billboarded, so its turn is the display's own. Everything that drives it
 * is something the client already animates smoothly: the display's position
 * (a teleport the client takes {@link #MOVE_TICKS} to cover), its
 * transformation (interpolated) and its item's tint, which carries the state
 * and when it began ({@link ObjectControls}). The shader does the rest.
 *
 * <p>What a display is, which object it draws and what it was last told are
 * kept in its persistent data, so an object outlives a restart and is picked
 * up again when its chunk loads. A sequence or a move in progress does not:
 * it stops where it was.
 */
public final class ObjectRuntime implements Listener {

    /** How long the client takes to cover one tick's move, in ticks. Two smooths over a late packet. */
    static final int MOVE_TICKS = 2;

    private final Plugin plugin;
    private final ObjectCatalogue catalogue;
    private final ObjectItems items;
    private final Logger log;

    final NamespacedKey idKey;
    final NamespacedKey stateKey;
    final NamespacedKey previousKey;
    final NamespacedKey startKey;
    final NamespacedKey seedKey;
    final NamespacedKey sizeKey;
    final NamespacedKey turnKey;

    private final Map<UUID, Driven> live = new LinkedHashMap<>();
    private BukkitTask ticker;
    private long tick;

    /**
     * @param items null where this server cannot drive objects (older than
     *              1.21.4): every spawn then answers empty
     */
    public ObjectRuntime(Plugin plugin, ObjectCatalogue catalogue, ObjectItems items) {
        this.plugin = plugin;
        this.catalogue = catalogue;
        this.items = items;
        this.log = plugin.getLogger();
        this.idKey = new NamespacedKey(plugin, "object");
        this.stateKey = new NamespacedKey(plugin, "object-state");
        this.previousKey = new NamespacedKey(plugin, "object-previous");
        this.startKey = new NamespacedKey(plugin, "object-start");
        this.seedKey = new NamespacedKey(plugin, "object-seed");
        this.sizeKey = new NamespacedKey(plugin, "object-size");
        this.turnKey = new NamespacedKey(plugin, "object-turn");
    }

    public boolean supported() {
        return items != null;
    }

    public ObjectCatalogue catalogue() {
        return catalogue;
    }

    ObjectItems items() {
        return items;
    }

    Logger log() {
        return log;
    }

    /** The runtime's own clock, one a server tick. Moves and waits are measured in it. */
    long now() {
        return tick;
    }

    /**
     * Starts the tick, and picks up every object standing in a loaded chunk.
     * Safe to call again after content changes: it only picks up what it had
     * not, which is how an object whose pack arrived late comes back.
     */
    public void start() {
        if (items == null) {
            return;
        }
        if (ticker == null) {
            ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 1L, 1L);
        }
        for (World world : Bukkit.getWorlds()) {
            for (ItemDisplay display : world.getEntitiesByClass(ItemDisplay.class)) {
                adopt(display);
            }
        }
    }

    public void stop() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        for (Driven driven : live.values()) {
            driven.persist();
        }
        live.clear();
    }

    /** Puts one down. Empty where objects are not supported or there is no world. */
    public Optional<Driven> spawn(Location where, ShaderObjectInfo info, double size) {
        if (items == null || where == null || where.getWorld() == null || info == null) {
            return Optional.empty();
        }
        double diameter = size > 0 ? size : info.size();
        Location at = where.clone();
        at.setYaw(0);
        at.setPitch(0);
        ItemDisplay display = where.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setBillboard(Display.Billboard.FIXED);
            // A big object is seen from far off; the range is a multiple of 64 blocks.
            d.setViewRange((float) Math.max(1.0, diameter / 8.0));
            d.setTeleportDuration(MOVE_TICKS);
            d.setPersistent(true);
            PersistentDataContainer data = d.getPersistentDataContainer();
            data.set(idKey, PersistentDataType.STRING, info.id().toString());
            data.set(stateKey, PersistentDataType.INTEGER, 0);
            data.set(previousKey, PersistentDataType.INTEGER, 0);
            data.set(startKey, PersistentDataType.LONG, -1L);
            data.set(seedKey, PersistentDataType.INTEGER, Math.floorMod(d.getUniqueId().hashCode(), 16));
            data.set(sizeKey, PersistentDataType.DOUBLE, diameter);
            data.set(turnKey, PersistentDataType.STRING, "0,0,0");
        });
        Driven driven = new Driven(this, display, info);
        driven.applyItem();
        driven.applyTransform(0);
        live.put(display.getUniqueId(), driven);
        return Optional.of(driven);
    }

    /** The object this entity draws, picked up if it is ours and nobody had yet. */
    public Optional<Driven> at(Entity entity) {
        if (entity == null) {
            return Optional.empty();
        }
        Driven known = live.get(entity.getUniqueId());
        if (known != null) {
            return known.isValid() ? Optional.of(known) : Optional.empty();
        }
        return entity instanceof ItemDisplay display ? Optional.ofNullable(adopt(display)) : Optional.empty();
    }

    /** Every object within {@code radius} of {@code near}, nearest first. */
    public List<Driven> near(Location near, double radius) {
        List<Driven> found = new ArrayList<>();
        if (near == null || near.getWorld() == null) {
            return found;
        }
        double limit = radius * radius;
        for (Driven driven : live.values()) {
            if (driven.isValid() && driven.entity().getWorld().equals(near.getWorld())
                    && driven.entity().getLocation().distanceSquared(near) <= limit) {
                found.add(driven);
            }
        }
        found.sort(Comparator.comparingDouble(d -> d.entity().getLocation().distanceSquared(near)));
        return found;
    }

    public List<Driven> loaded() {
        List<Driven> out = new ArrayList<>();
        for (Driven driven : live.values()) {
            if (driven.isValid()) {
                out.add(driven);
            }
        }
        return out;
    }

    void forget(Driven driven) {
        live.remove(driven.entity().getUniqueId());
    }

    private Driven adopt(ItemDisplay display) {
        if (items == null) {
            return null;
        }
        String id = display.getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
        if (id == null) {
            return null;
        }
        Optional<ShaderObjectInfo> info = ContentId.parse(id).flatMap(catalogue::info);
        if (info.isEmpty()) {
            // Its pack is gone or not pushed yet; it keeps drawing what its
            // item last said, and is picked up again when it comes back.
            return null;
        }
        Driven driven = live.get(display.getUniqueId());
        if (driven == null) {
            driven = new Driven(this, display, info.get());
            live.put(display.getUniqueId(), driven);
            driven.settleIfOld();
        }
        return driven;
    }

    private void tickAll() {
        tick++;
        if (live.isEmpty()) {
            return;
        }
        for (Driven driven : new ArrayList<>(live.values())) {
            if (!driven.isValid()) {
                live.remove(driven.entity().getUniqueId());
                continue;
            }
            try {
                driven.tick();
            } catch (RuntimeException e) {
                log.warning("A 3D object (" + driven.id() + ") stopped its sequence: " + e);
                driven.stop();
            }
        }
    }

    @EventHandler
    public void onLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof ItemDisplay display) {
                adopt(display);
            }
        }
    }

    @EventHandler
    public void onUnload(EntitiesUnloadEvent event) {
        for (Entity entity : event.getEntities()) {
            Driven driven = live.remove(entity.getUniqueId());
            if (driven != null) {
                driven.persist();
            }
        }
    }
}
