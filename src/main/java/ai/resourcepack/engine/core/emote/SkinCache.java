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
 * because a fetch that fails on first start would otherwise be a server with no
 * rigs at all.
 */
public final class SkinCache {

    /** Where the sheets live, under the plugin's data folder. */
    static final String FOLDER = "skins";

    /**
     * The default every server has, shipped in the jar: Steve.
     *
     * <p>It used to be a mannequin drawn for this plugin, on the reasoning
     * that the texture is Mojang's. The cost of that was a first join looking
     * like a stranger's art rather than like Minecraft, which is what
     * everybody actually reads it as - and Steve is the skin every server,
     * every skin site and the game itself already shows for somebody with no
     * skin of their own. Shipped rather than fetched, so it is ready before
     * the first player arrives and on a server with no way out to the
     * internet.
     */
    private static final String DEFAULT_RESOURCE = "/rig/default-skin.png";

    private final Plugin plugin;
    private final Path folder;
    private final Logger log;

    /** Players whose first-join fetch has been reported, so the note is once per session. */
    private final Set<UUID> told = ConcurrentHashMap.newKeySet();

    /** Which keys the LAST bake covered, so a join can say whether its rig is in the pack. */
    private volatile Set<String> baked = Set.of();

    /**
     * Told when a skin arrives that the pack does not have a rig for yet, with
     * whose. See {@link #tellIfUnbaked}.
     */
    private volatile java.util.function.Consumer<Player> onNewSkin = player -> { };

    public SkinCache(Plugin plugin) {
        this.plugin = plugin;
        this.folder = plugin.getDataFolder().toPath().resolve(FOLDER);
        this.log = plugin.getLogger();
    }

    /**
     * What to do when somebody turns up whose rig is not in the pack.
     *
     * <p>A callback rather than this class doing it, because what it costs is
     * a whole pack rebuild and the decision to spend that belongs to the
     * plugin, not to the thing that fetches PNGs.
     */
    public void onNewSkin(java.util.function.Consumer<Player> action) {
        this.onNewSkin = action == null ? player -> { } : action;
    }

    /** What {@link #noticed} found, so a join can decide whether to wait for the sheet. */
    public enum Noticed {
        /** No sheet to have, or one this server's pack already has a rig for. Nothing will follow. */
        SETTLED,
        /** On disk but not in the pack: {@link #onNewSkin} has already been told. */
        KEPT,
        /** Being fetched: {@link #onNewSkin} is told when it lands, unless the fetch fails. */
        FETCHING
    }

    /** The key a player's files are named by: the UUID as 32 hex digits. */
    public static String keyOf(UUID id) {
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
    public Noticed noticed(Player player) {
        if (player == null) {
            return Noticed.SETTLED;
        }
        String url;
        String capeUrl;
        String variant;
        try {
            PlayerProfile profile = player.getPlayerProfile();
            PlayerTextures textures = profile == null ? null : profile.getTextures();
            URL skin = textures == null ? null : textures.getSkin();
            if (skin == null) {
                // An offline-mode server, or a Bedrock player whose skin has
                // not resolved: nothing to fetch, and the default rig is the
                // honest answer for them.
                return Noticed.SETTLED;
            }
            url = skin.toString();
            URL cape = textures.getCape();
            capeUrl = cape == null ? "" : cape.toString();
            variant = textures.getSkinModel() == PlayerTextures.SkinModel.SLIM
                    ? RigGeometry.SLIM : RigGeometry.WIDE;
        } catch (RuntimeException | LinkageError e) {
            return Noticed.SETTLED;
        }

        String key = keyOf(player.getUniqueId());
        Path png = folder.resolve(key + ".png");
        Path capePng = folder.resolve(key + "_cape.png");
        Path meta = folder.resolve(key + ".txt");
        // The stamp carries the cape URL too, so a player who puts a cape on
        // or takes one off is fetched again.
        String want = variant + "\n" + url + "\n" + capeUrl + "\n";
        try {
            if (Files.isRegularFile(png) && Files.isRegularFile(meta)
                    && want.equals(Files.readString(meta, StandardCharsets.UTF_8))) {
                return tellIfUnbaked(player, key) ? Noticed.KEPT : Noticed.SETTLED;
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
            // The cape is optional twice over: no URL means none, and a fetch
            // that fails costs the cape and nothing else.
            byte[] capeBytes = capeUrl.isEmpty() ? null : fetch(capeUrl);
            try {
                Files.createDirectories(folder);
                Path part = folder.resolve(key + ".png.part");
                Files.write(part, bytes);
                Files.move(part, png, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                if (capeBytes != null) {
                    Path capePart = folder.resolve(key + "_cape.png.part");
                    Files.write(capePart, capeBytes);
                    Files.move(capePart, capePng, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } else {
                    Files.deleteIfExists(capePng);
                }
                Files.writeString(meta, want, StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.warning("Could not keep " + name + "'s skin for their emote rig: " + e.getMessage());
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> tellIfUnbaked(player, key));
        });
        return Noticed.FETCHING;
    }

    /** @return whether they were unbaked, and so told about */
    private boolean tellIfUnbaked(Player player, String key) {
        if (baked.contains(key)) {
            return false;
        }
        // The rebuild is asked for on EVERY arrival of an unbaked skin, not
        // only the first time this player is seen: `told` stops the console
        // line repeating, and hanging the rebuild off it as well meant a
        // player who joined while a bake was already running never got one.
        onNewSkin.accept(player);
        if (!told.add(player.getUniqueId())) {
            return true;
        }
        log.info(player.getName() + "'s skin is kept for their emote rig. They see it straight away; "
                + "everybody else does once a rebuild has run and their client has the new pack.");
        return true;
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
                // A cape sheet (<key>_cape.png) is read beside its skin, not on its own.
                continue;
            }
            RigBaker.Skin skin = read(key, file);
            if (skin != null) {
                skins.add(skin);
            }
        }
        return skins;
    }

    private RigBaker.Skin read(String key, File file) {
        try {
            byte[] png = Files.readAllBytes(file.toPath());
            Path capeFile = folder.resolve(key + "_cape.png");
            byte[] cape = Files.isRegularFile(capeFile) ? Files.readAllBytes(capeFile) : null;
            String variant = RigGeometry.WIDE;
            Path meta = folder.resolve(key + ".txt");
            if (Files.isRegularFile(meta)) {
                String first = Files.readString(meta, StandardCharsets.UTF_8).split("\n", 2)[0].trim();
                if (RigGeometry.SLIM.equals(first)) {
                    variant = RigGeometry.SLIM;
                }
            }
            return new RigBaker.Skin(key, png, variant, cape);
        } catch (IOException e) {
            log.warning("Could not read " + file.getName() + " from " + FOLDER + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * One player's kept sheet, ready to bake, or null when there is none on
     * disk. The same reading as {@link #snapshot}, for one key.
     */
    RigBaker.Skin skin(UUID player) {
        String key = keyOf(player);
        Path png = folder.resolve(key + ".png");
        if (!Files.isRegularFile(png)) {
            return null;
        }
        return read(key, png.toFile());
    }

    /** A decoded face, kept against the file's modification time so a new skin replaces it. */
    private record Face(long modified, int[] withHat, int[] bare) { }

    private final java.util.Map<String, Face> faces = new ConcurrentHashMap<>();

    /**
     * A player's face as 64 ARGB pixels, row by row: the front of the head at
     * (8, 8) of the skin, with the hat layer at (40, 8) over it when asked.
     *
     * <p>For a screen's player head (see {@code ScreenHeads}). Read from the
     * skin this cache already keeps for the emote rig, which is fetched on
     * join; until it has arrived, and on an offline-mode server that has no
     * skin to fetch, the answer is Steve's. The same half-alpha rule as
     * Studio's head crop decides whether a hat pixel covers the face.
     */
    public int[] face(UUID player, boolean hat) {
        String key = player == null ? "" : keyOf(player);
        Path png = key.isEmpty() ? null : folder.resolve(key + ".png");
        long modified = -1;
        try {
            if (png != null && Files.isRegularFile(png)) {
                modified = Files.getLastModifiedTime(png).toMillis();
            }
        } catch (IOException ignored) {
            modified = -1;
        }
        String cacheKey = modified < 0 ? "" : key;
        Face cached = faces.get(cacheKey);
        if (cached == null || cached.modified() != modified) {
            byte[] bytes = null;
            if (modified >= 0) {
                try {
                    bytes = Files.readAllBytes(png);
                } catch (IOException ignored) {
                    bytes = null;
                }
            }
            if (bytes == null) {
                bytes = defaultSheet();
            }
            cached = decodeFace(bytes, modified);
            if (cached == null) {
                return null;
            }
            faces.put(cacheKey, cached);
        }
        return hat ? cached.withHat() : cached.bare();
    }

    private static Face decodeFace(byte[] bytes, long modified) {
        if (bytes == null) {
            return null;
        }
        java.awt.image.BufferedImage image;
        try {
            image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        } catch (IOException e) {
            return null;
        }
        if (image == null || image.getWidth() < 64 || image.getHeight() < 32) {
            return null;
        }
        // A skin is 64 wide; a hi-res one is a multiple, read at its own scale.
        // The hat is in the top half, so an old 64x32 skin has one too.
        int scale = image.getWidth() / 64;
        int[] withHat = new int[64];
        int[] bare = new int[64];
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int face = image.getRGB((8 + x) * scale, (8 + y) * scale);
                if ((face >>> 24) < 128) {
                    face = 0;
                }
                bare[y * 8 + x] = face;
                int top = image.getRGB((40 + x) * scale, (8 + y) * scale);
                withHat[y * 8 + x] = (top >>> 24) >= 128 ? top : face;
            }
        }
        return new Face(modified, withHat, bare);
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
