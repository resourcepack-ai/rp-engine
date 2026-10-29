package ai.resourcepack.engine.core.sound;

import java.util.Optional;

/**
 * What an {@code .ogg} file is, read off its headers without decoding a sample.
 *
 * <p>Two facts the definitions cannot know and the game cares about: how many
 * channels it has, because Minecraft only places a MONO sound in the world and
 * plays a stereo one flat, at full volume, wherever it was played from; and how
 * long it runs, which is what lets a vehicle or a playlist loop it without a
 * gap.
 *
 * <p>Ogg is a container, so a file can be a perfectly good Ogg and still be
 * silence in game: Minecraft decodes Vorbis and nothing else, and an Opus file
 * carries the same extension. {@link #vorbis} says which this was.
 *
 * <p>The layout is the Ogg spec's (RFC 3533) and Vorbis I's identification
 * header: a page starts {@code OggS}, its granule position is the 8 bytes at 6,
 * its segment count the byte at 26 with the segment table after it, and the
 * first packet of a Vorbis stream is {@code 0x01 "vorbis"}, version, channels,
 * then the sample rate.
 */
public record OggInfo(boolean vorbis, int channels, int sampleRate, double seconds) {

    /** A stereo file plays flat everywhere, which is the point for music and a mistake for an engine. */
    public boolean stereo() {
        return channels >= 2;
    }

    /**
     * The headers of {@code bytes}, or empty when it is not an Ogg file at all,
     * which is what an mp3 or a wav renamed to {@code .ogg} looks like.
     */
    public static Optional<OggInfo> read(byte[] bytes) {
        if (bytes == null || bytes.length < 28 || !capture(bytes, 0)) {
            return Optional.empty();
        }
        int segments = bytes[26] & 0xFF;
        int packet = 27 + segments;
        if (packet + 16 > bytes.length) {
            return Optional.empty();
        }
        if (!matches(bytes, packet, "\u0001vorbis")) {
            // An Ogg holding something else: Opus, FLAC or Speex.
            return Optional.of(new OggInfo(false, 0, 0, 0));
        }
        int channels = bytes[packet + 11] & 0xFF;
        int rate = (int) little(bytes, packet + 12, 4);
        return Optional.of(new OggInfo(true, channels, rate, seconds(bytes, rate)));
    }

    /**
     * The last page's granule position over the sample rate. For Vorbis the
     * granule counts samples per channel, so this is the running time exactly.
     * 0 when nothing says, which every reader of a length treats as unknown.
     */
    private static double seconds(byte[] bytes, int rate) {
        if (rate <= 0) {
            return 0;
        }
        for (int at = bytes.length - 27; at >= 0; at--) {
            if (!capture(bytes, at)) {
                continue;
            }
            long granule = little(bytes, at + 6, 8);
            // -1 is "no packet ends on this page", which says nothing.
            if (granule > 0) {
                return granule / (double) rate;
            }
        }
        return 0;
    }

    private static boolean capture(byte[] bytes, int at) {
        return matches(bytes, at, "OggS");
    }

    private static boolean matches(byte[] bytes, int at, String text) {
        if (at < 0 || at + text.length() > bytes.length) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (bytes[at + i] != (byte) text.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static long little(byte[] bytes, int at, int width) {
        long value = 0;
        for (int i = width - 1; i >= 0; i--) {
            value = (value << 8) | (bytes[at + i] & 0xFF);
        }
        return value;
    }
}
