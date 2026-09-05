package ai.resourcepack.engine.core.vehicle;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.SoundInfo;
import ai.resourcepack.engine.api.Sounds;
import ai.resourcepack.engine.api.VehicleHitbox;
import ai.resourcepack.engine.api.VehicleInfo;
import ai.resourcepack.engine.api.VehicleMedium;
import ai.resourcepack.engine.api.VehicleSeat;
import ai.resourcepack.engine.api.VehicleState;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The timer, without a server.
 *
 * <p>Everything that makes a vehicle's engine note either seamless or a mess
 * is arithmetic on one number — how long the file runs — so it is worth
 * pinning here rather than by driving a car around and listening. The
 * {@link Location} carries a null world, which is harmless: nothing in
 * {@link VehicleSounds} reads it, it is handed straight to {@link Sounds}, and
 * the fake below only counts it.
 */
class VehicleSoundsTest {

    /** Records what was played and when, and says how long each sound is. */
    private static final class FakeSounds implements Sounds {

        private final Map<String, Double> lengths;
        final List<String> played = new ArrayList<>();

        FakeSounds(Map<String, Double> lengths) {
            this.lengths = lengths;
        }

        @Override
        public Collection<ContentId> ids() {
            return List.of();
        }

        @Override
        public Optional<SoundInfo> info(ContentId id) {
            Double length = lengths.get(id.toString());
            if (length == null) {
                return Optional.empty();
            }
            return Optional.of(SoundInfo.pushed(id, id.path(), "master").withLength(length));
        }

        @Override
        public Optional<SoundInfo> info(String id) {
            return ContentId.parse(id).flatMap(this::info);
        }

        @Override
        public boolean play(Player player, ContentId id) {
            return false;
        }

        @Override
        public boolean play(Player player, ContentId id, float volume, float pitch) {
            return false;
        }

        @Override
        public boolean playAt(Location location, ContentId id) {
            if (!lengths.containsKey(id.toString())) {
                return false;
            }
            played.add(id.toString());
            return true;
        }

        @Override
        public boolean playAt(Location location, ContentId id, float volume, float pitch) {
            return playAt(location, id);
        }
    }

    private static final Location SOMEWHERE = new Location(null, 0, 64, 0);

    private static VehicleInfo car(Map<VehicleState, String> sounds) {
        return VehicleInfo.of(ContentId.parse("mypack:car").orElseThrow(), null, null, VehicleMedium.LAND,
                10, 20, 10, 180, VehicleHitbox.DEFAULT,
                List.of(VehicleSeat.of(VehicleSeat.Role.DRIVER, VehicleSeat.Pose.SITTING, 0, 0, 0, 0, null)),
                Map.of(), List.of())
                .withSounds(sounds);
    }

    private static Map<VehicleState, String> map(VehicleState state, String sound) {
        Map<VehicleState, String> sounds = new EnumMap<>(VehicleState.class);
        sounds.put(state, sound);
        return sounds;
    }

    /**
     * The loop is the file's own length and nothing else. Two seconds is forty
     * ticks, so a hundred ticks of driving is three plays: at 0, 40 and 80.
     */
    @Test
    void aSoundRepeatsOnItsOwnLength() {
        FakeSounds sounds = new FakeSounds(Map.of("mypack:engine", 2.0));
        VehicleSounds noise = new VehicleSounds();
        VehicleInfo car = car(map(VehicleState.MOVING, "mypack:engine"));

        for (long tick = 0; tick < 100; tick++) {
            noise.play(sounds, SOMEWHERE, car, Set.of(VehicleState.MOVING), tick);
        }
        assertEquals(3, sounds.played.size(), sounds.played.toString());
    }

    /**
     * A sound nobody measured plays once and then waits for the state to
     * change. Not on some default interval: a two-second engine looped every
     * half second is four engines, and silence is the better wrong answer.
     */
    @Test
    void aSoundWithNoLengthPlaysOnce() {
        FakeSounds sounds = new FakeSounds(Map.of("mypack:engine", 0.0));
        VehicleSounds noise = new VehicleSounds();
        VehicleInfo car = car(map(VehicleState.MOVING, "mypack:engine"));

        for (long tick = 0; tick < 200; tick++) {
            noise.play(sounds, SOMEWHERE, car, Set.of(VehicleState.MOVING), tick);
        }
        assertEquals(1, sounds.played.size());
    }

    /** Changing state is heard at once rather than at the end of the loop. */
    @Test
    void aStateChangeStartsTheNewSoundImmediately() {
        FakeSounds sounds = new FakeSounds(Map.of("mypack:engine", 5.0, "mypack:idle", 5.0));
        VehicleSounds noise = new VehicleSounds();
        Map<VehicleState, String> both = new EnumMap<>(VehicleState.class);
        both.put(VehicleState.MOVING, "mypack:engine");
        both.put(VehicleState.IDLE, "mypack:idle");
        VehicleInfo car = car(both);

        noise.play(sounds, SOMEWHERE, car, Set.of(VehicleState.MOVING), 0);
        noise.play(sounds, SOMEWHERE, car, Set.of(VehicleState.MOVING), 1);
        noise.play(sounds, SOMEWHERE, car, Set.of(VehicleState.IDLE), 2);
        assertEquals(List.of("mypack:engine", "mypack:idle"), sounds.played);
    }

    /**
     * Going back to a state it was already in restarts that state's sound —
     * the vehicle has been playing something else in between, so there is no
     * playhead to resume.
     */
    @Test
    void comingBackToAStateStartsItAgain() {
        FakeSounds sounds = new FakeSounds(Map.of("mypack:engine", 5.0, "mypack:idle", 5.0));
        VehicleSounds noise = new VehicleSounds();
        Map<VehicleState, String> both = new EnumMap<>(VehicleState.class);
        both.put(VehicleState.MOVING, "mypack:engine");
        both.put(VehicleState.IDLE, "mypack:idle");
        VehicleInfo car = car(both);

        noise.play(sounds, SOMEWHERE, car, Set.of(VehicleState.MOVING), 0);
        noise.play(sounds, SOMEWHERE, car, Set.of(VehicleState.IDLE), 5);
        noise.play(sounds, SOMEWHERE, car, Set.of(VehicleState.MOVING), 10);
        assertEquals(List.of("mypack:engine", "mypack:idle", "mypack:engine"), sounds.played);
    }

    /**
     * A state with no sound falls through to the next one down, exactly as an
     * animation does — so a vehicle that names only `moving` keeps its engine
     * running round a corner.
     */
    @Test
    void aBlankStateFallsThroughLikeAnAnimation() {
        FakeSounds sounds = new FakeSounds(Map.of("mypack:engine", 5.0));
        VehicleSounds noise = new VehicleSounds();
        VehicleInfo car = car(map(VehicleState.MOVING, "mypack:engine"));

        noise.play(sounds, SOMEWHERE, car, Set.of(VehicleState.MOVING, VehicleState.TURNING), 0);
        assertEquals(List.of("mypack:engine"), sounds.played);
    }

    /**
     * A sound this server does not have is not asked for again while the state
     * holds. This runs twenty times a second for every vehicle on the server,
     * and a miss per tick is the shape of thing that costs a tick rate.
     */
    @Test
    void aMissingSoundIsAskedForOnce() {
        FakeSounds sounds = new FakeSounds(Map.of());
        VehicleSounds noise = new VehicleSounds();
        VehicleInfo car = car(map(VehicleState.MOVING, "mypack:nothing"));

        for (long tick = 0; tick < 100; tick++) {
            noise.play(sounds, SOMEWHERE, car, Set.of(VehicleState.MOVING), tick);
        }
        assertTrue(sounds.played.isEmpty());
    }

    /** A state the pack said nothing about is silence, not the last sound again. */
    @Test
    void anUnmappedStateIsSilent() {
        FakeSounds sounds = new FakeSounds(Map.of("mypack:engine", 1.0));
        VehicleSounds noise = new VehicleSounds();
        VehicleInfo car = car(map(VehicleState.MOVING, "mypack:engine"));

        noise.play(sounds, SOMEWHERE, car, Set.of(VehicleState.MOVING), 0);
        for (long tick = 1; tick < 100; tick++) {
            noise.play(sounds, SOMEWHERE, car, Set.of(VehicleState.IDLE), tick);
        }
        assertEquals(List.of("mypack:engine"), sounds.played);
    }
}
