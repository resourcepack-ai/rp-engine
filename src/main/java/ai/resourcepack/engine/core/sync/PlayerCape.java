package ai.resourcepack.engine.core.sync;

import org.bukkit.entity.Player;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.net.URL;

/**
 * Which cape a player is actually wearing, off their live game profile.
 *
 * <p><b>This server is the only place that knows.</b> A cape is not part of the
 * skin sheet and is not derivable from it: Mojang serves it from its own CDN,
 * under a content hash named in the signed {@code textures} property that
 * arrives with the player at login. That property is what this client is
 * rendering right now, so what it names is by definition the right answer.
 *
 * <p>Studio used to have to guess, and guessed wrong. Everything reachable from
 * a browser or from a Cloudflare Worker is a CACHE of that property — Mojang's
 * own API refuses datacentre egress, so the fallback path is a mirror
 * serving profiles that may be months old, and
 * the third-party cape hosts cache too. A Java account can OWN several capes
 * and choose between them, which is what turns "slightly stale" into "somebody
 * else's cape": the design changes while the account does not. Reporting the
 * hash from here is what closes that, and it costs one field on a message that
 * is already being sent.
 *
 * <p>Only the HASH goes out, never the texture. Studio fetches the bytes from
 * Mojang's own CDN with it, exactly as it already does for a skin — so a
 * server can say WHICH cape and can never supply the pixels, which is the same
 * line Studio's own profile lookup draws about a mirror.
 *
 * <p>Null for a player with no cape, for an offline-mode server, and for a
 * Bedrock player through Floodgate — none of which have one to report. The
 * caller writes {@link #NONE} in that case rather than dropping the field, so
 * "this jar did not say" and "they have not got one" stay different facts.
 */
public final class PlayerCape {

    /** What goes on the wire for a player with no cape, or none we can see. */
    public static final String NONE = "-";

    private PlayerCape() {
    }

    /**
     * The hash of this player's cape texture, or null if they have none.
     *
     * <p>Never throws: a server old enough to lack the profile API, or a
     * profile implementation that objects to being asked, costs a cape and
     * must not cost the presence announcement it rides on.
     */
    public static String hashOf(Player player) {
        if (player == null) return null;
        try {
            PlayerProfile profile = player.getPlayerProfile();
            if (profile == null) return null;
            PlayerTextures textures = profile.getTextures();
            if (textures == null) return null;
            return hashOf(textures.getCape());
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** The same, as it goes on the wire: the hash or {@link #NONE}. */
    public static String token(Player player) {
        String hash = hashOf(player);
        return hash == null ? NONE : hash;
    }

    /**
     * The last path segment of a texture URL, if it looks like a hash.
     *
     * <p>Package-visible and taking a URL rather than a player so it can be
     * tested. The shape check is not paranoia about Mojang — it is what stops
     * a hostile or misconfigured profile putting arbitrary text into a
     * protocol whose fields are separated by spaces and colons.
     */
    static String hashOf(URL cape) {
        if (cape == null) return null;
        String path = cape.getPath();
        if (path == null) return null;
        int slash = path.lastIndexOf('/');
        String segment = slash < 0 ? path : path.substring(slash + 1);
        if (segment.isEmpty() || segment.length() > 128) return null;
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return null;
        }
        return segment.toLowerCase(java.util.Locale.ROOT);
    }
}
