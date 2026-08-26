package ai.resourcepack.engine.api;

import org.bukkit.entity.Player;

/**
 * Putting a signed skin on a player.
 *
 * <p>A skin is not a file the client downloads on our terms: it is a
 * {@code textures} property on the player's GameProfile, and the vanilla client
 * only loads skin images from Mojang's own host - so a link to anywhere else
 * renders as nothing however correct this end is. What this takes is therefore
 * a signed value/signature pair from a service that has already put the pixels
 * on that host. Producing one is not something this library does.
 *
 * <p><b>Main thread only</b>, except {@link #available()}, which is a field
 * resolved once at startup.
 */
public interface Skins {

    /**
     * Whether this server can change a skin at all - false on Spigot.
     *
     * <p>Ask before offering the feature; {@link #apply} answers the same thing
     * with {@link SkinResult#NEEDS_PAPER} if you don't.
     */
    boolean available();

    /** Wears a signed skin, now. */
    SkinResult apply(Player target, String value, String signature);
}
