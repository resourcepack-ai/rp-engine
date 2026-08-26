package ai.resourcepack.engine.core.emote;

import org.bukkit.entity.Player;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

/**
 * Which arm width a player is actually wearing, off their live game profile.
 *
 * Every player who joins hands the server Mojang's signed {@code textures}
 * property, and its {@code metadata.model} says whether the skin is the slim
 * (Alex, 3px arm) or the wide (Steve, 4px arm) model. Bukkit already parses it
 * — {@link PlayerTextures#getSkinModel()} — so this is a read, not a lookup:
 * nothing is fetched, nobody is asked, and it is right for a skin this plugin
 * applied itself, because {@code setPlayerProfile} is what it reads from.
 *
 * Studio cannot see this flag when it bakes a rig (it fetches the sheet from a
 * mirror, in the browser, and the pixels of a slim sheet do not reliably say
 * so), which is why the pack carries BOTH arm pairs and the choice is made
 * here. See EmoteStore.boneItemId for how the answer turns into a model.
 *
 * Null when the profile carries no textures at all — an offline-mode server,
 * a Bedrock player through Floodgate before their skin resolves — and the
 * caller falls back to the manifest's guess rather than to Steve.
 */
final class SkinModel {

    static final String WIDE = "wide";
    static final String SLIM = "slim";

    private SkinModel() {
    }

    /** {@code "slim"}, {@code "wide"}, or null if the profile doesn't say. */
    static String of(Player player) {
        if (player == null) return null;
        try {
            PlayerProfile profile = player.getPlayerProfile();
            if (profile == null) return null;
            PlayerTextures textures = profile.getTextures();
            if (textures == null || textures.isEmpty()) return null;
            return textures.getSkinModel() == PlayerTextures.SkinModel.SLIM ? SLIM : WIDE;
        } catch (RuntimeException | LinkageError e) {
            // A server old enough to lack the profile API, or a profile
            // implementation that throws on an unresolved skin: the manifest's
            // guess is a worse answer than this one but a far better one than
            // refusing the emote.
            return null;
        }
    }
}
