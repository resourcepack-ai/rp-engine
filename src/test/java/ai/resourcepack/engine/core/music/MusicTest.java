package ai.resourcepack.engine.core.music;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.api.MusicInfo;
import ai.resourcepack.engine.api.SoundInfo;
import ai.resourcepack.engine.core.content.ContentFolderLoader;
import ai.resourcepack.engine.core.registry.ContentRegistryImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.ToDoubleFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MusicTest {

    @TempDir
    Path root;

    private static ContentId id(String text) {
        return ContentId.parse(text).orElseThrow();
    }

    private static MusicInfo music(String name, boolean shuffle, boolean loop, String... tracks) {
        return MusicInfo.of(id("p:" + name), java.util.Arrays.stream(tracks).map(MusicTest::id).toList(),
                shuffle, loop, 1, false, 0, true);
    }

    /** Every track ten seconds long, so one is due 220 ticks after it starts (200 + a 1s gap). */
    private static final ToDoubleFunction<ContentId> TEN = track -> 10;

    // ---- playback --------------------------------------------------------

    @Test
    void playsEachTrackWhenTheOneBeforeHasRunItsLengthAndGap() {
        MusicInfo lobby = music("lobby", false, true, "p:a", "p:b");
        Playback playback = new Playback();
        Random random = new Random(1);

        assertEquals(id("p:a"), playback.advance(lobby, 0, TEN, random).play());
        assertEquals(Playback.Step.NOTHING, playback.advance(lobby, 210, TEN, random));
        assertEquals(id("p:b"), playback.advance(lobby, 220, TEN, random).play());
        // And round again, because it loops.
        assertEquals(id("p:a"), playback.advance(lobby, 440, TEN, random).play());
    }

    @Test
    void withoutLoopItFallsSilentAfterTheLastTrack() {
        MusicInfo once = music("once", false, false, "p:a");
        Playback playback = new Playback();
        Random random = new Random(1);

        assertEquals(id("p:a"), playback.advance(once, 0, TEN, random).play());
        assertEquals(Playback.Step.NOTHING, playback.advance(once, 220, TEN, random));
        assertNull(playback.track());
        assertEquals(Playback.Step.NOTHING, playback.advance(once, 5000, TEN, random));
    }

    @Test
    void changingPieceStopsTheOldTrackAndStartsTheNew() {
        Playback playback = new Playback();
        Random random = new Random(1);
        playback.advance(music("lobby", false, true, "p:a"), 0, TEN, random);

        Playback.Step step = playback.advance(music("arena", false, true, "p:fight"), 40, TEN, random);
        assertEquals(id("p:a"), step.stop());
        assertEquals(id("p:fight"), step.play());

        Playback.Step quiet = playback.advance(null, 80, TEN, random);
        assertEquals(id("p:fight"), quiet.stop());
        assertNull(quiet.play());
        assertNull(playback.music());
    }

    @Test
    void aTrackTheServerDoesNotHaveIsPassedOver() {
        MusicInfo lobby = music("lobby", false, true, "p:missing", "p:b");
        ToDoubleFunction<ContentId> lengths = track -> track.path().equals("missing") ? -1 : 10;
        assertEquals(id("p:b"), new Playback().advance(lobby, 0, lengths, new Random(1)).play());

        // All of them missing ends the music rather than spinning.
        MusicInfo nothing = music("nothing", false, true, "p:x", "p:y");
        assertEquals(Playback.Step.NOTHING, new Playback().advance(nothing, 0, track -> -1, new Random(1)));
    }

    @Test
    void aTrackWithNoLengthPlaysOnceAndTheMusicWaits() {
        MusicInfo lobby = music("lobby", false, true, "p:a", "p:b");
        Playback playback = new Playback();
        Random random = new Random(1);

        assertEquals(id("p:a"), playback.advance(lobby, 0, track -> 0, random).play());
        assertEquals(Playback.Step.NOTHING, playback.advance(lobby, 100_000, track -> 0, random));
    }

    @Test
    void skipStopsTheTrackAndStartsTheNext() {
        MusicInfo lobby = music("lobby", false, true, "p:a", "p:b");
        Playback playback = new Playback();
        Random random = new Random(1);
        playback.advance(lobby, 0, TEN, random);

        Playback.Step step = playback.skip(20, TEN, random);
        assertEquals(id("p:a"), step.stop());
        assertEquals(id("p:b"), step.play());
    }

    @Test
    void aShuffleNeverRepeatsATrackBackToBack() {
        MusicInfo mix = music("mix", true, true, "p:a", "p:b", "p:c");
        Playback playback = new Playback();
        Random random = new Random(7);
        Set<ContentId> heard = new HashSet<>();
        ContentId last = playback.advance(mix, 0, TEN, random).play();
        heard.add(last);
        for (int n = 1; n < 60; n++) {
            ContentId next = playback.advance(mix, n * 220L, TEN, random).play();
            assertFalse(next.equals(last), "the same track twice in a row at " + n);
            heard.add(next);
            last = next;
        }
        assertEquals(3, heard.size());
    }

    // ---- places ----------------------------------------------------------

    @Test
    void aPlaceIsAnyOfItsWorldsRegionsAndAreas() {
        MusicPlace place = new MusicPlace(Set.of("lobby"), Set.of("spawn"),
                List.of(MusicPlace.Box.of("world", new int[] {10, 0, 10}, new int[] {0, 64, 0})));

        assertTrue(place.contains("lobby", 1e6, 0, 0, Set.of()));
        assertTrue(place.contains("world_nether", 0, 0, 0, Set.of("spawn")));
        assertTrue(place.contains("world", 10.9, 64.5, 0, Set.of()));
        assertFalse(place.contains("world", 11, 64, 0, Set.of()));
        assertFalse(place.contains("other", 5, 5, 5, Set.of()));
    }

    // ---- definitions -----------------------------------------------------

    private LoadReport load(String yaml) throws IOException {
        Path content = root.resolve("content");
        Files.createDirectories(content.resolve("p/music"));
        Files.writeString(content.resolve("p/pack.yml"), "{}\n", StandardCharsets.UTF_8);
        Files.writeString(content.resolve("p/music/a.yml"), yaml, StandardCharsets.UTF_8);
        return new ContentFolderLoader(new ContentRegistryImpl()).load(content, ContentSource.AUTHORED);
    }

    private static Map<ContentId, SoundInfo> sounds(String... names) {
        java.util.Map<ContentId, SoundInfo> out = new java.util.HashMap<>();
        for (String name : names) {
            out.put(id(name), SoundInfo.of(id(name), id(name).path(), "music", null, 1, 1, true).withLength(60));
        }
        return out;
    }

    @Test
    void readsAPlaylistAndWhereItPlays() throws IOException {
        MusicDefinitions.Result result = MusicDefinitions.parse(load(
                "lobby:\n"
                        + "  tracks: [theme, p:calm]\n"
                        + "  shuffle: true\n"
                        + "  gap: 3\n"
                        + "  priority: 2\n"
                        + "  where:\n"
                        + "    worlds: [lobby]\n"
                        + "    regions: [Spawn]\n"
                        + "    areas:\n"
                        + "      - { world: world, from: [0, 0, 0], to: [10, 64, 10] }\n"),
                sounds("p:theme", "p:calm"));

        MusicInfo lobby = result.music().get(id("p:lobby"));
        assertEquals(List.of(id("p:theme"), id("p:calm")), lobby.tracks());
        assertTrue(lobby.shuffle());
        assertTrue(lobby.loop());
        assertEquals(3, lobby.gap());
        assertEquals(2, lobby.priority());
        assertFalse(lobby.vanilla());
        assertTrue(lobby.placed());
        MusicPlace place = result.places().get(id("p:lobby"));
        assertTrue(place.regions().contains("spawn"));
        assertEquals(1, place.areas().size());
        assertTrue(result.diagnostics().isEmpty(), () -> result.diagnostics().toString());
    }

    @Test
    void warnsAboutTracksItCannotPlayThrough() throws IOException {
        Map<ContentId, SoundInfo> known = new java.util.HashMap<>(sounds("p:theme"));
        known.put(id("p:short"), SoundInfo.of(id("p:short"), "short", "music", null, 1, 1, false));

        MusicDefinitions.Result result = MusicDefinitions.parse(load(
                "lobby:\n  tracks: [theme, short, nope, studio:pushed]\n"), known);

        assertFalse(result.music().get(id("p:lobby")).placed());
        String said = result.diagnostics().toString();
        assertTrue(said.contains("p:short has no length"), said);
        assertTrue(said.contains("No sound called p:nope"), said);
        assertFalse(said.contains("studio:pushed"), said);
    }

    @Test
    void musicWithNoTracksIsAnError() throws IOException {
        MusicDefinitions.Result result = MusicDefinitions.parse(load("lobby:\n  shuffle: true\n"), Map.of());
        assertTrue(result.music().isEmpty());
        assertEquals(1, result.diagnostics().size());
    }
}
