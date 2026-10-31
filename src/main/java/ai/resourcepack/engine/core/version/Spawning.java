package ai.resourcepack.engine.core.version;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.Consumer;

/**
 * {@code World.spawn} with a function that dresses the entity before it is
 * added, on every server the engine supports. <b>Every spawn with a callback
 * in this engine goes through here</b>; calling {@code world.spawn(at, type,
 * lambda)} directly is the bug this class exists to prevent.
 *
 * <p>Spigot's overload took {@code org.bukkit.util.Consumer} until 1.20.2 and
 * {@code java.util.function.Consumer} after. A lambda compiles against either,
 * so the source half of the oldest-API audit passes, but the bytecode names the
 * one the build was compiled against: on 1.19.4 to 1.20.1 that method does not
 * exist and the call throws {@code NoSuchMethodError}. Found the first time a
 * custom mob was spawned on 1.19.4; twenty more call sites had it. The
 * audit's bytecode half is what finds the next one.
 *
 * <p>The two arms. Where the {@code java.util.function} overload exists it is
 * called directly ({@link ModernSpawning}). Below that, the old overload is
 * found once by reflection and handed a proxy of {@code org.bukkit.util.Consumer},
 * so the entity is still dressed BEFORE it is added to the world: before
 * {@code EntitySpawnEvent} reaches other plugins and before anything can be
 * sent to a client. Only if neither exists (no supported server) is it spawned
 * bare and dressed straight after.
 */
public final class Spawning {

    private static final Arm ARM = choose();

    private Spawning() {
    }

    public static <T extends Entity> T spawn(World world, Location where, Class<T> type, Consumer<? super T> dress) {
        return ARM.spawn(world, where, type, dress);
    }

    private interface Arm {
        <T extends Entity> T spawn(World world, Location where, Class<T> type, Consumer<? super T> dress);
    }

    private static Arm choose() {
        try {
            World.class.getMethod("spawn", Location.class, Class.class, Consumer.class);
            return ModernSpawning::spawn;
        } catch (NoSuchMethodException ignored) {
            // the 1.19.4 - 1.20.1 shape, below
        }
        try {
            Class<?> bukkitConsumer = Class.forName("org.bukkit.util.Consumer");
            Method method = World.class.getMethod("spawn", Location.class, Class.class, bukkitConsumer);
            MethodHandle handle = MethodHandles.publicLookup().unreflect(method)
                    .asType(MethodType.methodType(Entity.class, World.class, Location.class, Class.class, Object.class));
            return new Arm() {
                @Override
                public <T extends Entity> T spawn(World world, Location where, Class<T> type, Consumer<? super T> dress) {
                    Object callback = Proxy.newProxyInstance(Spawning.class.getClassLoader(),
                            new Class<?>[]{bukkitConsumer}, (proxy, m, args) -> {
                                switch (m.getName()) {
                                    case "accept" -> {
                                        dress.accept(type.cast(args[0]));
                                        return null;
                                    }
                                    case "hashCode" -> {
                                        return System.identityHashCode(proxy);
                                    }
                                    case "equals" -> {
                                        return proxy == args[0];
                                    }
                                    case "toString" -> {
                                        return "Spawning$dress";
                                    }
                                    default -> throw new UnsupportedOperationException(m.getName());
                                }
                            });
                    try {
                        return type.cast((Entity) handle.invokeExact(world, where, (Class<?>) type, callback));
                    } catch (RuntimeException | Error e) {
                        throw e;
                    } catch (Throwable e) {
                        throw new IllegalStateException(e);
                    }
                }
            };
        } catch (ReflectiveOperationException ignored) {
            // neither shape: dress after spawning
        }
        return new Arm() {
            @Override
            public <T extends Entity> T spawn(World world, Location where, Class<T> type, Consumer<? super T> dress) {
                T entity = world.spawn(where, type);
                dress.accept(entity);
                return entity;
            }
        };
    }
}
