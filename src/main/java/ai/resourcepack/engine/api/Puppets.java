package ai.resourcepack.engine.api;

import org.bukkit.Location;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Player-shaped figures with nobody behind them: a cutscene's actors, a
 * quest-giver, a statue that waves.
 *
 * <p>A puppet is the same rig an emote stands up (one item display per bone,
 * wearing a skin this server baked), posed by the same arithmetic from the same
 * keyframes, but owned by the plugin that spawned it rather than by a player. It
 * goes where it is told and plays what it is told, and nothing else moves it.
 *
 * <p><b>Skins are baked, so a new one waits for a rig rebuild.</b> Register a
 * skin with {@link #skin} (or name a player who has joined, {@link #skinOf}),
 * then {@link #bake} when it is convenient: the rigs pack is rebuilt and sent
 * again, and every puppet spawned after that wears it. Until then a puppet
 * wears the server's default rig (Steve). {@link #ready} says which.
 *
 * <p>Needs baked rigs, which is Minecraft 1.21.4 and up with
 * {@code emotes.rigs} on: {@link #available} says whether this server has them.
 * Main thread (on Folia, the region of the place) for everything that spawns
 * or moves.
 *
 * @since 0.2.92
 */
public interface Puppets {

    /** Whether this server can stand puppets up at all. */
    boolean available();

    /**
     * Keeps a skin for puppets to wear and answers the key to wear it by.
     *
     * <p>The key is derived from the picture, so registering the same PNG
     * twice answers the same key and stores nothing new. The skin is kept
     * until the plugin data is deleted, and is always baked, however many
     * players the server has seen.
     *
     * @param name a label for the console; not part of the key
     * @param png  a 64x64 (or legacy 64x32) skin sheet
     * @param slim the three-pixel arm model
     * @return the key, or empty for bytes that are not a skin sheet
     */
    Optional<String> skin(String name, byte[] png, boolean slim);

    /** The key a player's own skin is kept under, baked once they have joined before a rebuild. */
    String skinOf(UUID player);

    /** Whether the current rigs include this key, so a puppet would wear it rather than the default. */
    boolean ready(String skinKey);

    /**
     * Rebuilds the rigs so every kept skin is in them, and sends the rigs pack
     * again to everybody who holds it. Costs each of them a resource reload,
     * so call it once after registering a batch, not per skin.
     */
    void bake();

    /** Stands a puppet with its feet at {@code feet}, facing the location's yaw. Empty if it could not. */
    Optional<Puppet> spawn(Location feet, PuppetOptions options);

    /** Every puppet standing, whoever spawned it. */
    Collection<Puppet> all();
}
