package ai.resourcepack.engine.core.version;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.potion.PotionEffectType;

import java.util.Locale;
import java.util.Optional;

/**
 * Looking vanilla things up by name, in a way that works on every supported
 * version.
 *
 * <p>Bukkit has been moving its enums into registries for several releases,
 * and the move is not one event: the registry appears in one version, the
 * enum becomes an interface in another, and the keys themselves get renamed
 * in a third. Each of those breaks a different kind of call, and the breakage
 * is a {@code NoSuchFieldError} or a null at runtime rather than anything the
 * build can see.
 *
 * <p>So the rule for the whole engine is: <b>never name a vanilla constant
 * directly.</b> {@code Attribute.MAX_HEALTH} is a field that does not exist
 * before 1.21.3, where it is called {@code GENERIC_MAX_HEALTH}, on a type that
 * is an enum there and an interface here. Every one of those is a compile-time
 * reference that cannot be guarded by an {@code if}. Going through a key
 * instead turns all of it into a lookup that can fail softly and be given a
 * fallback.
 *
 * <p>Nothing here is version-gated in the {@code Feature} sense, because none
 * of it is a capability a server owner loses — it is the same behaviour
 * reached by a route that survives the rename.
 */
public final class Vanilla {

    private Vanilla() {
    }

    /**
     * A potion effect by its vanilla name, e.g. {@code speed}.
     *
     * <p>{@code Registry.EFFECT} would be the modern way and does not exist
     * before 1.20.something; {@code PotionEffectType.getByKey} exists on every
     * supported version and answers the same question. Deprecated on the newer
     * ones, and kept anyway: a deprecation warning is a better problem than a
     * second code path.
     */
    @SuppressWarnings("deprecation")
    public static Optional<PotionEffectType> effect(String name) {
        if (name == null || name.isEmpty()) {
            return Optional.empty();
        }
        NamespacedKey key = NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT));
        return Optional.ofNullable(PotionEffectType.getByKey(key));
    }

    /**
     * An attribute by its vanilla name, tolerating the 1.21.3 rename.
     *
     * <p>The attributes lost their {@code generic.} prefix in 1.21.3 —
     * {@code generic.max_health} became {@code max_health} — so a key that
     * resolves on one side of that line resolves on neither by itself. Both
     * spellings are tried, in either direction, which means a pack may write
     * whichever it knows and content written for one version keeps working on
     * the other.
     */
    public static Optional<Attribute> attribute(String name) {
        if (name == null || name.isEmpty()) {
            return Optional.empty();
        }
        String written = name.toLowerCase(Locale.ROOT);
        Optional<Attribute> direct = byKey(written);
        if (direct.isPresent()) {
            return direct;
        }
        if (written.startsWith("generic.")) {
            return byKey(written.substring("generic.".length()));
        }
        return byKey("generic." + written);
    }

    /**
     * The attribute holding an entity's maximum health.
     *
     * <p>Named rather than left to callers because it is the one attribute the
     * engine itself asks for, and {@code Attribute.MAX_HEALTH} written at that
     * call site is exactly the compile-time reference this class exists to
     * remove.
     */
    public static Optional<Attribute> maxHealth() {
        return attribute("max_health");
    }

    private static Optional<Attribute> byKey(String path) {
        NamespacedKey key = NamespacedKey.fromString(path.contains(":") ? path : "minecraft:" + path);
        if (key == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(Registry.ATTRIBUTE.get(key));
        } catch (RuntimeException | NoClassDefFoundError | ExceptionInInitializerError e) {
            // No server behind the registry, which is the case in a unit test.
            // Empty rather than a throw: every caller already handles an
            // attribute it could not resolve, because a pack can misspell one.
            return Optional.empty();
        }
    }
}
