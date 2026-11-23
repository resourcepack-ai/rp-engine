package ai.resourcepack.engine.api;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * One standing puppet; see {@link Puppets}.
 *
 * <p>Every method is safe on a removed puppet and does nothing there.
 *
 * @since 0.2.92
 */
public interface Puppet {

    UUID id();

    /** Whether it is still standing. */
    boolean isValid();

    /** Where its feet are, facing its body's yaw. */
    Location location();

    /** The skin key it was asked to wear (it may wear the default until that is baked). */
    String skin();

    /**
     * Moves its feet to {@code feet} and turns its body to that location's yaw,
     * with the client gliding there over {@code glideTicks} (0 to 59). Call it
     * every tick with 1 or 2 for a smooth walk.
     */
    void move(Location feet, int glideTicks);

    /**
     * Plays an emote this server holds, by name, from {@code fromSeconds} into
     * it, at {@code speed}, easing out of whatever it was showing over
     * {@code blendTicks}.
     *
     * @param loop null for the emote's own setting
     * @return false if no emote has that name
     */
    boolean play(String emote, double fromSeconds, double speed, Boolean loop, int blendTicks);

    /** Plays keyframes handed in rather than an emote by name. */
    void play(PuppetAnimation animation, double fromSeconds, double speed, int blendTicks);

    /** Back to standing still, eased over {@code blendTicks}. */
    void rest(int blendTicks);

    /** Moves the playhead of what it is playing. */
    void seek(double seconds);

    /** Seconds into what it is playing. */
    double playhead();

    /**
     * Turns its head relative to its body, on top of what it is playing, in
     * Minecraft's own angles: positive yaw turns right, positive pitch looks
     * down. Null for both goes back to the animation's own head.
     */
    void look(Float yaw, Float pitch);

    /** Puts an item in a hand (null or air empties it). */
    void hold(boolean offHand, ItemStack item);

    /** Outlines it, as the glowing effect outlines an entity. */
    void glow(boolean on);

    /**
     * An entity that moves with it (its body), for anything that follows an
     * entity: {@code VfxOptions.follow}, a particle trail. Empty once removed.
     */
    java.util.Optional<org.bukkit.entity.Entity> anchor();

    /** A name over its head, legacy colour codes allowed; null takes it away. */
    void name(String name);

    /** Shows it to one more player, for a puppet spawned for an audience. */
    void show(Player player);

    /** Hides it from one player. */
    void hide(Player player);

    /** Takes it out of the world. */
    void remove();
}
