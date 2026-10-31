package ai.resourcepack.engine.core.version;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;

import java.util.function.Consumer;

/**
 * {@code World.spawn} with a function that dresses the entity before it is
 * added, on every server the engine supports.
 *
 * <p>Spigot's overload took {@code org.bukkit.util.Consumer} until 1.20.2 and
 * {@code java.util.function.Consumer} after. A lambda compiles against either,
 * so the oldest-API audit passes, but the bytecode names the one the build was
 * compiled against: on Paper 1.19.4 that method does not exist and the call
 * throws {@code NoSuchMethodError}. Found the first time a custom mob was
 * spawned on 1.19.4.
 *
 * <p>Off the modern method, the entity is spawned bare and dressed straight
 * after, in the same tick; nothing is sent to a client in between.
 */
public final class Spawning {

    private static volatile boolean modern = true;

    private Spawning() {
    }

    public static <T extends Entity> T spawn(World world, Location where, Class<T> type, Consumer<? super T> dress) {
        if (modern) {
            try {
                return world.spawn(where, type, dress::accept);
            } catch (NoSuchMethodError e) {
                modern = false;
            }
        }
        T entity = world.spawn(where, type);
        dress.accept(entity);
        return entity;
    }
}
