package ai.resourcepack.engine.core.sync;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.ContentRegistration;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.MergeResult;
import ai.resourcepack.engine.api.Namespace;
import ai.resourcepack.engine.api.OverlayInfo;
import ai.resourcepack.engine.api.SoundInfo;
import ai.resourcepack.engine.api.VehicleHitbox;
import ai.resourcepack.engine.api.VehicleInfo;
import ai.resourcepack.engine.api.VehicleMedium;
import ai.resourcepack.engine.api.VehicleSeat;

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
        List<Vehicle> vehicles;
    }

    static final class Sound {
        String id;
        /** The sounds.json key, which is NOT the id — see {@link SoundInfo#event()}. */
        String event;
        String category;
    }

    /**
     * A vehicle, whose art is named the way a pushed pack names art.
     *
     * <p>{@code carrier} rather than {@code model}: a Studio pack is a zip
     * with no plugin behind it, so its models borrow paper wearing a string
     * {@code custom_model_data}, and that string is the only handle there is.
     * See {@link VehicleInfo#carrier()}.
     *
     * <p><strong>Seat offsets arrive in BLOCKS.</strong> Studio authors them
     * in model pixels, because that is what its editor's inspector prints for
     * a cube, and converts on the way out — so both sides of this manifest
     * speak the same unit as a hand-written {@code vehicles/*.yml}. The
     * conversion is stated in studio's {@code lib/studio-content.ts}; getting
     * it wrong is a seat a sixteenth of the way to where it should be.
     */
    static final class Vehicle {
        String id;
        String carrier;
        String name;
        String medium;
        double weight;
        double speed;
        double acceleration;
        double turnSpeed;
        double hitboxWidth;
        double hitboxHeight;
        double hitboxLength;
        List<Seat> seats;
    }

    static final class Seat {
        String role;
        String pose;
        double x;
        double y;
        double z;
        double yaw;
        String name;
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
    private volatile Map<ContentId, VehicleInfo> vehicles = Map.of();
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

    /** The pushed vehicles, keyed by id. */
    public Map<ContentId, VehicleInfo> vehicles() {
        return vehicles;
    }

    /** Whether there is anything at all. */
    public boolean isEmpty() {
        return sounds.isEmpty() && screens.isEmpty() && huds.isEmpty() && vehicles.isEmpty();
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

        Map<ContentId, VehicleInfo> readVehicles = new LinkedHashMap<>();
        for (Vehicle vehicle : manifest.vehicles == null ? List.<Vehicle>of() : manifest.vehicles) {
            id(vehicle == null ? null : vehicle.id)
                    .flatMap(id -> vehicle(id, vehicle))
                    .ifPresent(info -> readVehicles.put(info.id(), info));
        }

        sounds = Map.copyOf(readSounds);
        screens = Map.copyOf(readScreens);
        huds = Map.copyOf(readHuds);
        vehicles = Map.copyOf(readVehicles);
        packId = manifest.packId == null ? "" : manifest.packId;
        return MergeResult.ok(packId,
                sounds.size() + screens.size() + huds.size() + vehicles.size());
    }

    /**
     * One vehicle off the manifest, or empty if it is not usable.
     *
     * <p><strong>Skipped rather than refused, and a vehicle with no driver
     * seat is skipped.</strong> That is the same rule the folder loader
     * enforces with a diagnostic — a vehicle nobody can steer is not a vehicle
     * — but there is nobody to show a diagnostic to here: this runs off a
     * websocket frame, and studio's own editor already refuses to call such a
     * model finished. Dropping it silently is the honest end of a check that
     * has already been made somewhere a person could see it.
     */
    private static java.util.Optional<VehicleInfo> vehicle(ContentId id, Vehicle vehicle) {
        if (vehicle.seats == null || vehicle.seats.isEmpty()) {
            return java.util.Optional.empty();
        }
        List<VehicleSeat> seats = new ArrayList<>();
        boolean driverTaken = false;
        for (Seat seat : vehicle.seats) {
            if (seat == null) {
                continue;
            }
            boolean driver = "driver".equalsIgnoreCase(seat.role) && !driverTaken;
            driverTaken |= driver;
            seats.add(VehicleSeat.of(
                    driver ? VehicleSeat.Role.DRIVER : VehicleSeat.Role.PASSENGER,
                    "standing".equalsIgnoreCase(seat.pose)
                            ? VehicleSeat.Pose.STANDING
                            : VehicleSeat.Pose.SITTING,
                    seat.x, seat.y, seat.z, (float) seat.yaw, seat.name));
        }
        if (!driverTaken) {
            return java.util.Optional.empty();
        }
        // The driver to the front, everything else keeping its order — the
        // same partition VehicleDefinitions does, and for the same reason:
        // seat order is the contract, and nothing downstream searches for the
        // driver.
        List<VehicleSeat> ordered = new ArrayList<>();
        for (VehicleSeat seat : seats) {
            if (seat.isDriver()) {
                ordered.add(seat);
            }
        }
        for (VehicleSeat seat : seats) {
            if (!seat.isDriver()) {
                ordered.add(seat);
            }
        }
        // A manifest from a studio that predates the hitbox has three zeroes
        // here, and zero is not a size — VehicleHitbox clamps it to its
        // minimum, which would be a sliver nobody can click. So an unset box
        // is the default block rather than a clamped nothing.
        VehicleHitbox hitbox = vehicle.hitboxWidth > 0 && vehicle.hitboxHeight > 0 && vehicle.hitboxLength > 0
                ? VehicleHitbox.of(vehicle.hitboxWidth, vehicle.hitboxHeight, vehicle.hitboxLength)
                : VehicleHitbox.DEFAULT;
        return java.util.Optional.of(VehicleInfo.pushed(id, vehicle.carrier, vehicle.name,
                VehicleMedium.parse(vehicle.medium).orElse(VehicleMedium.LAND),
                vehicle.weight, vehicle.speed, vehicle.acceleration, vehicle.turnSpeed,
                hitbox, List.copyOf(ordered)));
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
        for (ContentId id : vehicles.keySet()) {
            claimed.define(ContentKind.VEHICLE, id.path());
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
        manifest.vehicles = new ArrayList<>();
        for (Map.Entry<ContentId, VehicleInfo> entry : vehicles.entrySet()) {
            manifest.vehicles.add(vehicleOf(entry.getKey(), entry.getValue()));
        }
        try {
            Files.createDirectories(file.getParentFile().toPath());
            Files.write(file.toPath(), gson.toJson(manifest).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warning("Could not write " + file.getName() + ": " + e.getMessage());
        }
    }

    /**
     * A vehicle back out, in the shape it arrived in.
     *
     * <p>Round-tripping matters here more than for the others: this is what is
     * written to {@code studio-content.json}, and a vehicle somebody parked
     * before a restart has to come back with the same seats in the same order
     * or the people who get into it end up somewhere else.
     */
    private static Vehicle vehicleOf(ContentId id, VehicleInfo info) {
        Vehicle out = new Vehicle();
        out.id = id.path();
        out.carrier = info.carrier().orElse("");
        out.name = info.name().orElse("");
        out.medium = info.medium().key();
        out.weight = info.weight();
        out.speed = info.speed();
        out.acceleration = info.acceleration();
        out.turnSpeed = info.turnSpeed();
        out.hitboxWidth = info.hitbox().width();
        out.hitboxHeight = info.hitbox().height();
        out.hitboxLength = info.hitbox().length();
        out.seats = new ArrayList<>();
        for (VehicleSeat seat : info.seats()) {
            Seat written = new Seat();
            written.role = seat.role().key();
            written.pose = seat.pose().key();
            written.x = seat.x();
            written.y = seat.y();
            written.z = seat.z();
            written.yaw = seat.yaw();
            written.name = seat.name().orElse("");
            out.seats.add(written);
        }
        return out;
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
