package ai.resourcepack.engine.core.music;

import ai.resourcepack.engine.api.ContentDefinition;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.DefinitionNode;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.api.MusicInfo;
import ai.resourcepack.engine.api.SoundInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads music definitions out of a load report.
 *
 * <pre>
 * # music/lobby.yml
 * lobby:
 *   tracks: [theme, calm]   # sounds; a bare name is this pack's
 *   shuffle: false
 *   loop: true
 *   gap: 2                  # seconds between tracks
 *   vanilla-music: false    # let Minecraft's own music play over it
 *   priority: 0
 *   where:
 *     worlds: [lobby]
 *     regions: [spawn]      # WorldGuard
 *     areas:
 *       - { world: lobby, from: [0, 0, 0], to: [100, 255, 100] }
 * </pre>
 *
 * <p>Free of Bukkit, like every definition reader here.
 */
public final class MusicDefinitions {

    private MusicDefinitions() {
    }

    /**
     * Everything of kind MUSIC in {@code loaded}, parsed. {@code sounds} is
     * the server's sound catalogue, for the warnings about tracks: one this
     * server does not have, or one with no length, after which the playlist
     * cannot go on.
     */
    public static Result parse(LoadReport loaded, Map<ContentId, SoundInfo> sounds) {
        Map<ContentId, MusicInfo> music = new LinkedHashMap<>();
        Map<ContentId, MusicPlace> places = new LinkedHashMap<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (loaded == null) {
            return new Result(Map.of(), Map.of(), List.of());
        }
        Map<ContentId, SoundInfo> known = sounds == null ? Map.of() : sounds;
        for (ContentDefinition definition : loaded.definitions(ContentKind.MUSIC)) {
            DefinitionNode body = definition.body();
            String origin = definition.origin();
            String where = definition.id().path();

            List<ContentId> tracks = new ArrayList<>();
            for (String name : body.strings("tracks")) {
                Optional<ContentId> id = name.indexOf(':') >= 0
                        ? ContentId.parse(name.trim())
                        : ContentId.of(definition.id().namespace(), name.trim());
                if (id.isEmpty()) {
                    diagnostics.add(Diagnostic.warning(origin, where, "tracks: " + name + " is not a sound id."));
                    continue;
                }
                SoundInfo sound = known.get(id.get());
                if (sound == null) {
                    // A Studio sound arrives with a push, after this; only a
                    // pack's own sound can be known to be missing now.
                    if (!id.get().namespace().equals("studio")) {
                        diagnostics.add(Diagnostic.warning(origin, where,
                                "No sound called " + id.get() + ". Declare it in sounds/, or it is skipped."));
                    }
                } else if (sound.length() <= 0) {
                    diagnostics.add(Diagnostic.warning(origin, where,
                            id.get() + " has no length, so the music stops after it. Give the sound a length:, "
                                    + "or check its file is Ogg Vorbis."));
                }
                tracks.add(id.get());
            }
            if (tracks.isEmpty()) {
                diagnostics.add(Diagnostic.error(origin, where, "tracks: names no sounds, so there is nothing to play."));
                continue;
            }

            MusicPlace place = body.node("where").map(node -> place(node, origin, where, diagnostics))
                    .orElse(MusicPlace.NOWHERE);
            music.put(definition.id(), MusicInfo.of(definition.id(), tracks,
                    body.bool("shuffle").orElse(Boolean.FALSE),
                    body.bool("loop").orElse(Boolean.TRUE),
                    clamp(body.decimal("gap").orElse(1d), 0, 600),
                    body.bool("vanilla-music").orElse(Boolean.FALSE),
                    body.integer("priority").orElse(0),
                    !place.nowhere()));
            places.put(definition.id(), place);
        }
        return new Result(Map.copyOf(music), Map.copyOf(places), List.copyOf(diagnostics));
    }

    private static MusicPlace place(DefinitionNode node, String origin, String where, List<Diagnostic> diagnostics) {
        Set<String> worlds = new LinkedHashSet<>(node.strings("worlds"));
        Set<String> regions = new LinkedHashSet<>();
        for (String region : node.strings("regions")) {
            // WorldGuard keeps region ids in lower case.
            regions.add(region.toLowerCase(java.util.Locale.ROOT));
        }
        List<MusicPlace.Box> areas = new ArrayList<>();
        for (DefinitionNode area : node.nodes("areas")) {
            Optional<String> world = area.string("world");
            int[] from = corner(area.strings("from"));
            int[] to = corner(area.strings("to"));
            if (world.isEmpty() || from == null || to == null) {
                diagnostics.add(Diagnostic.warning(origin, where,
                        "An area needs world:, from: [x, y, z] and to: [x, y, z]. That one is skipped."));
                continue;
            }
            areas.add(MusicPlace.Box.of(world.get(), from, to));
        }
        MusicPlace place = new MusicPlace(Set.copyOf(worlds), Set.copyOf(regions), List.copyOf(areas));
        if (place.nowhere()) {
            diagnostics.add(Diagnostic.warning(origin, where,
                    "where: names no worlds, regions or areas, so it only plays when started by command."));
        }
        return place;
    }

    private static int[] corner(List<String> values) {
        if (values.size() != 3) {
            return null;
        }
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            try {
                out[i] = (int) Math.floor(Double.parseDouble(values.get(i).trim()));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return out;
    }

    private static double clamp(double value, double min, double max) {
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : min;
    }

    /** The music, where each plays, and what was wrong. */
    public static final class Result {

        private final Map<ContentId, MusicInfo> music;
        private final Map<ContentId, MusicPlace> places;
        private final List<Diagnostic> diagnostics;

        Result(Map<ContentId, MusicInfo> music, Map<ContentId, MusicPlace> places, List<Diagnostic> diagnostics) {
            this.music = music;
            this.places = places;
            this.diagnostics = diagnostics;
        }

        /** Every piece that parsed, keyed by id. */
        public Map<ContentId, MusicInfo> music() {
            return music;
        }

        Map<ContentId, MusicPlace> places() {
            return places;
        }

        /** What went wrong. */
        public List<Diagnostic> diagnostics() {
            return diagnostics;
        }
    }
}
