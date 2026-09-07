package ai.resourcepack.engine.core.emote;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * The skins this server has seen, kept on disk so rigs can be baked for them.
 *
 * <p><strong>A rig is a copy of a skin, and the pack is built before most
 * players have joined.</strong> That is the tension this class resolves, and
 * it resolves it the same way Studio does: honestly rather than magically.
 * Every joining player's skin sheet is fetched once from Mojang's texture
 * host — the URL is on their profile, signed, and the client fetches from the
 * same place — and kept under {@code plugins/RPEngine/skins/}. The next pack
 * build bakes a rig for every skin in the folder. A player who joins after the
 * build gets the shared default rig until the next {@code /rp reload} or
 * restart, and the console says so once, naming them.
 *
 * <p>Why not rebuild and re-send on every new face? A rebuilt bundle is a new
 * hash, and a new hash is every player on the server re-downloading the pack
 * — a hundred people's screens going dark because somebody new walked in.
 * That is a worse experience than one newcomer riding as a mannequin for an
 * evening, so the trade is made the quiet way and stated here.
 *
 * <p>The fetch is off the main thread and the file is written whole or not
 * at all. A skin that changes URL (the player changed skin) is fetched again;
 * one that matches what is on disk costs a string comparison.
 *
 * <p>The default sheet — the rig anybody without one of their own wears — is
 * shipped in the jar rather than fetched: a mannequin of this plugin's own,
 * because Mojang's Steve is theirs and a fetch that fails on first start is a
 * server with no rigs at all.
 */
public final class SkinCache {

    /** Where the sheets live, under the plugin's data folder. */
    static final String FOLDER = "skins";

    /** The mannequin every server has, shipped in the jar. */
    private static final String DEFAULT_RESOURCE = "/rig/default-skin.png";

    private final Plugin plugin;
    private final Path folder;
    private final Logger log;

    /** Players whose first-join fetch has been reported, so the note is once per session. */
    private final Set<UUID> told = ConcurrentHashMap.newKeySet();

    /** Which keys the LAST bake covered, so a join can say whether its rig is in the pack. */
    private volatile Set<String> baked = Set.of();

    public SkinCache(Plugin plugin) {
        this.plugin = plugin;
        this.folder = plugin.getDataFolder().toPath().resolve(FOLDER);
        this.log = plugin.getLogger();
    }

    /** The key a player's files are named by: the UUID as 32 hex digits. */
    static String keyOf(UUID id) {
        return id.toString().replace("-", "").toLowerCase(Locale.ROOT);
    }

    /**
     * Called when somebody joins: reads the skin off their profile and keeps
     * it if it is new.
     *
     * <p>Nothing here blocks the join. The profile read is a field access;
     * the download is scheduled off the main thread and the result is a file
     * nobody reads until the next build.
     */
    public void noticed(Player player) {
        if (player == null) {
            return;
        }
        String url;
        String variant;
        try {
            PlayerProfile profile = player.getPlayerProfile();
            PlayerTextures textures = profile == null ? null : profile.getTextures();
            URL skin = textures == null ? null : textures.getSkin();
            if (skin == null) {
                // An offline-mode server, or a Bedrock player whose skin has
                // not resolved: nothing to fetch, and the default rig is the
                // honest answer for them.
                return;
            }
            url = skin.toString();
            variant = textures.getSkinModel() == PlayerTextures.SkinModel.SLIM
                    ? RigGeometry.SLIM : RigGeometry.WIDE;
        } catch (RuntimeException | LinkageError e) {
            return;
        }

        String key = keyOf(player.getUniqueId());
        Path png = folder.resolve(key + ".png");
        Path meta = folder.resolve(key + ".txt");
        String want = variant + "\n" + url + "\n";
        try {
            if (Files.isRegularFile(png) && Files.isRegularFile(meta)
                    && want.equals(Files.readString(meta, StandardCharsets.UTF_8))) {
                tellIfUnbaked(player, key);
                return;
            }
        } catch (IOException ignored) {
            // Unreadable is the same as absent: fetch again.
        }

        String name = player.getName();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            byte[] bytes = fetch(url);
            if (bytes == null) {
                log.warning("Could not fetch " + name + "'s skin for their emote rig from " + url
                        + ". They wear the default rig until it can be fetched.");
                return;
            }
            try {
                Files.createDirectories(folder);
                Path part = folder.resolve(key + ".png.part");
                Files.write(part, bytes);
                Files.move(part, png, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                Files.writeString(meta, want, StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.warning("Could not keep " + name + "'s skin for their emote rig: " + e.getMessage());
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> tellIfUnbaked(player, key));
        });
    }

    private void tellIfUnbaked(Player player, String key) {
        if (baked.contains(key) || !told.add(player.getUniqueId())) {
            return;
        }
        log.info(player.getName() + "'s skin is kept for their emote rig; it is baked into the pack on the next "
                + "/rp reload or restart, and until then they wear the shared default rig.");
    }

    /** Called by the bake, so joins can be told whether their rig is in the pack. */
    void baked(Set<String> keys) {
        baked = Set.copyOf(keys);
    }

    /**
     * Every skin on disk plus the default, ready to bake. Read on the main
     * thread at build time; a folder of a few hundred small PNGs is nothing.
     */
    List<RigBaker.Skin> snapshot() {
        List<RigBaker.Skin> skins = new ArrayList<>();
        byte[] fallback = defaultSheet();
        if (fallback != null) {
            skins.add(new RigBaker.Skin(RigBaker.DEFAULT_KEY, fallback, RigGeometry.WIDE));
        }
        if (!Files.isDirectory(folder)) {
            return skins;
        }
        File[] files = folder.toFile().listFiles((dir, name) -> name.endsWith(".png"));
        if (files == null) {
            return skins;
        }
        for (File file : files) {
            String key = file.getName().substring(0, file.getName().length() - 4).toLowerCase(Locale.ROOT);
            if (key.length() != 32) {
                continue;
            }
            try {
                byte[] png = Files.readAllBytes(file.toPath());
                String variant = RigGeometry.WIDE;
                Path meta = folder.resolve(key + ".txt");
                if (Files.isRegularFile(meta)) {
                    String first = Files.readString(meta, StandardCharsets.UTF_8).split("\n", 2)[0].trim();
                    if (RigGeometry.SLIM.equals(first)) {
                        variant = RigGeometry.SLIM;
                    }
                }
                skins.add(new RigBaker.Skin(key, png, variant));
            } catch (IOException e) {
                log.warning("Could not read " + file.getName() + " from " + FOLDER + ": " + e.getMessage());
            }
        }
        return skins;
    }

    private byte[] defaultSheet() {
        try (InputStream in = SkinCache.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] fetch(String url) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(20_000);
            connection.setInstanceFollowRedirects(true);
            if (connection.getResponseCode() / 100 != 2) {
                return null;
            }
            try (InputStream in = connection.getInputStream()) {
                byte[] bytes = in.readAllBytes();
                // A sheet is a 64x64 PNG of a few kilobytes. Anything else is
                // an error page or a legacy 64x32 sheet, and baking either
                // would put a checkerboard on somebody's face.
                return bytes.length > 100 && bytes.length < 1_000_000 && isPng(bytes) ? bytes : null;
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static boolean isPng(byte[] bytes) {
        return bytes.length > 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G';
    }
}
