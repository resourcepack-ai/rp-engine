package ai.resourcepack.engine.core.version;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;

import java.util.function.Consumer;

/**
 * The 1.20.2+ arm of {@link Spawning}: the {@code java.util.function.Consumer}
 * overload of {@code World.spawn}, called directly. In version-arms.txt because
 * the method does not exist on 1.19.4; nothing reaches this class without
 * {@link Spawning} having found the method first.
 */
final class ModernSpawning {

    private ModernSpawning() {
    }

    static <T extends Entity> T spawn(World world, Location where, Class<T> type, Consumer<? super T> dress) {
        return world.spawn(where, type, dress::accept);
    }
}
