package ai.resourcepack.engine.core.sync;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.ContentRegistration;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.MergeResult;
import ai.resourcepack.engine.api.Namespace;
import ai.resourcepack.engine.api.OverlayInfo;
import ai.resourcepack.engine.api.SoundInfo;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * What a pushed Studio pack holds that a command can name.
 *
 * <p>A pack is a zip of art. Wearing it is enough to <em>see</em> a screen or
 * <em>hear</em> a sound, but not to ask for one: opening a GUI means knowing
 * which container it was drawn for and which characters position it, and
 * playing a sound means knowing the event name and its category. None of that
 * is recoverable from the zip, so Studio sends a small manifest beside it —
 * exactly as it already does for emote rigs.
 *
 * <p><strong>What arrives is registered, not kept to one side.</strong> The
 * ids go into the same {@link ai.resourcepack.engine.api.ContentRegistry}
 * everything else lives in, under the namespace {@code studio}, so
 * {@code /rp sound studio:chime} is the same kind of command as
 * {@code /rp sound mypack:chime} and {@code /rp sounds} lists both. That is
 * what {@link ContentSource#STUDIO} was put in the enum for.
 *
 * <p>The whole namespace is replaced on every push, because a push is a whole
 * pack — the same rule {@link Namespace#release()} documents, applied to the
 * one source that changes most often.
 *
 * <p>Persisted to {@code studio-content.json} beside the emote store, and for
 * the same reason: the pack a player is wearing survives a restart, so the
 * ability to name what is in it has to as well.
 */
public final class StudioContent {

    /**
     * The namespace pushed content lands in.
     *
     * <p>Fixed rather than the pack's own name: one pack is pushed at a time,
     * into one bundle ({@link StudioPush#BUNDLE}), and a namespace that
     * changed with the pack would leave the last one's ids in the registry
     * pointing at art nobody is wearing any more.
     */
    public static final String NAMESPACE = "studio";

    /** The manifest's shape. Written by Studio's content-manifest writer. */
    static final class Manifest {
        String packId;
        List<Sound> sounds;
        List<Overlay> screens;
        List<Overlay> huds;
    }

    static final class Sound {
        String id;
        /** The sounds.json key, which is NOT the id — see {@link SoundInfo#event()}. */
        String event;
        String category;
    }

    static final class Overlay {
        String id;
        /** The characters that draw it: negative space, then the glyph. */
        String title;
        /** A screen's container. Empty on a HUD. */
        String container;
        /** A HUD's slot. Empty on a screen. */
        String slot;
    }

    private final Gson gson = new Gson();
    private final File file;

    private volatile Map<ContentId, SoundInfo> sounds = Map.of();
    private volatile Map<ContentId, OverlayInfo> screens = Map.of();
    private volatile Map<ContentId, OverlayInfo> huds = Map.of();
    private volatile String packId = "";

    /** The registry handle, held for as long as the content is registered. */
    private Namespace claimed;

    public StudioContent(File dataFolder) {
        this.file = new File(dataFolder, "studio-content.json");
    }

    /** The pushed sounds, keyed by id. */
    public Map<ContentId, SoundInfo> sounds() {
        return sounds;
    }

    /** The pushed screens, keyed by id. */
    public Map<ContentId, OverlayInfo> screens() {
        return screens;
    }

    /** The pushed HUD overlays, keyed by id. */
    public Map<ContentId, OverlayInfo> huds() {
        return huds;
    }

    /** Whether there is anything at all. */
    public boolean isEmpty() {
        return sounds.isEmpty() && screens.isEmpty() && huds.isEmpty();
    }

    /**
     * Replaces everything with what one push carried.
     *
     * <p>Replaces rather than merges. Two pushes of one pack are two versions
     * of the same thing, and a sound deleted in the editor has to disappear
     * here too or the command still offers it.
     */
    public MergeResult updateFromJson(String json) {
        if (json == null || json.isEmpty()) {
            return MergeResult.failed("empty manifest");
        }
        Manifest manifest;
        try {
            manifest = gson.fromJson(json, Manifest.class);
        } catch (JsonSyntaxException e) {
            return MergeResult.failed("not JSON: " + e.getMessage());
        }
        if (manifest == null) {
            return MergeResult.failed("not a manifest");
        }

        Map<ContentId, SoundInfo> readSounds = new LinkedHashMap<>();
        for (Sound sound : manifest.sounds == null ? List.<Sound>of() : manifest.sounds) {
            if (sound == null || sound.event == null || sound.event.isEmpty()) {
                continue;
            }
            id(sound.id).ifPresent(id ->
                    readSounds.put(id, SoundInfo.pushed(id, sound.event, sound.category)));
        }

        Map<ContentId, OverlayInfo> readScreens = new LinkedHashMap<>();
        for (Overlay screen : manifest.screens == null ? List.<Overlay>of() : manifest.screens) {
            if (screen == null || screen.title == null || screen.container == null) {
                continue;
            }
            id(screen.id).ifPresent(id -> readScreens.put(id,
                    OverlayInfo.pushed(id, screen.title, screen.container, null)));
        }

        Map<ContentId, OverlayInfo> readHuds = new LinkedHashMap<>();
        for (Overlay hud : manifest.huds == null ? List.<Overlay>of() : manifest.huds) {
            if (hud == null || hud.title == null) {
                continue;
            }
            id(hud.id).ifPresent(id ->
                    readHuds.put(id, OverlayInfo.pushed(id, hud.title, "", slotOf(hud.slot))));
        }

        sounds = Map.copyOf(readSounds);
        screens = Map.copyOf(readScreens);
        huds = Map.copyOf(readHuds);
        packId = manifest.packId == null ? "" : manifest.packId;
        return MergeResult.ok(packId, sounds.size() + screens.size() + huds.size());
    }

    /**
     * Puts the ids into the registry, replacing whatever was there.
     *
     * <p>Called after a push and again after every reload, because a reload
     * clears the registry and rebuilds it from the content folder — which
     * knows nothing about a pack somebody is wearing.
     */
    public void register(ContentRegistration registration, Logger log) {
        release();
        if (registration == null || isEmpty()) {
            return;
        }
        claimed = registration.claim(NAMESPACE, ContentSource.STUDIO).namespace().orElse(null);
        if (claimed == null) {
            // Only if a content folder is called "studio", which is a name
            // clash a server owner can fix in one rename.
            log.warning("The namespace " + NAMESPACE + " is taken, so a pushed pack's "
                    + "sounds and screens cannot be named. Rename that content folder.");
            return;
        }
        for (ContentId id : sounds.keySet()) {
            claimed.define(ContentKind.SOUND, id.path());
        }
        for (ContentId id : screens.keySet()) {
            claimed.define(ContentKind.SCREEN, id.path());
        }
        for (ContentId id : huds.keySet()) {
            claimed.define(ContentKind.HUD, id.path());
        }
    }

    /** Drops the namespace, if it is held. */
    public void release() {
        if (claimed != null) {
            claimed.release();
            claimed = null;
        }
    }

    /** Reads what was saved. A missing file is an empty pack, not a problem. */
    public void load(Logger log) {
        if (!file.isFile()) {
            return;
        }
        try {
            MergeResult result = updateFromJson(
                    new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            if (result.ok()) {
                if (result.count() > 0) {
                    log.info("Loaded " + result.count() + " pushed asset(s) from " + file.getName());
                }
            } else {
                log.warning(file.getName() + " could not be read: " + result.error());
            }
        } catch (IOException e) {
            log.warning(file.getName() + " could not be read: " + e.getMessage());
        }
    }

    /**
     * Writes it back out, in the shape it arrived in.
     *
     * <p>An empty store deletes the file rather than writing an empty one. A
     * server that has never taken a push should not find a file suggesting it
     * has, and the next push writes it again.
     */
    public void save(Logger log) {
        if (isEmpty()) {
            try {
                Files.deleteIfExists(file.toPath());
            } catch (IOException e) {
                log.warning("Could not remove " + file.getName() + ": " + e.getMessage());
            }
            return;
        }
        Manifest manifest = new Manifest();
        manifest.packId = packId;
        manifest.sounds = new ArrayList<>();
        for (Map.Entry<ContentId, SoundInfo> entry : sounds.entrySet()) {
            Sound sound = new Sound();
            sound.id = entry.getKey().path();
            sound.event = entry.getValue().event();
            sound.category = entry.getValue().category();
            manifest.sounds.add(sound);
        }
        manifest.screens = new ArrayList<>();
        for (Map.Entry<ContentId, OverlayInfo> entry : screens.entrySet()) {
            manifest.screens.add(overlay(entry.getKey(), entry.getValue(), true));
        }
        manifest.huds = new ArrayList<>();
        for (Map.Entry<ContentId, OverlayInfo> entry : huds.entrySet()) {
            manifest.huds.add(overlay(entry.getKey(), entry.getValue(), false));
        }
        try {
            Files.createDirectories(file.getParentFile().toPath());
            Files.write(file.toPath(), gson.toJson(manifest).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warning("Could not write " + file.getName() + ": " + e.getMessage());
        }
    }

    private static Overlay overlay(ContentId id, OverlayInfo info, boolean screen) {
        Overlay out = new Overlay();
        out.id = id.path();
        out.title = info.title();
        out.container = screen ? info.container() : "";
        out.slot = screen ? "" : info.slot().name().toLowerCase(Locale.ROOT);
        return out;
    }

    /** A manifest id, in our namespace. Anything unusable is skipped. */
    private static java.util.Optional<ContentId> id(String path) {
        return path == null ? java.util.Optional.empty() : ContentId.parse(NAMESPACE + ":" + path);
    }

    private static OverlayInfo.Slot slotOf(String slot) {
        return slot != null && slot.toLowerCase(Locale.ROOT).startsWith("boss")
                ? OverlayInfo.Slot.BOSS_BAR
                : OverlayInfo.Slot.ACTION_BAR;
    }
}
