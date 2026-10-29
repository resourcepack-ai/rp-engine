package ai.resourcepack.engine.core.sound;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OggInfoTest {

    /** One page: the capture pattern, a granule, and one packet as its only segment. */
    static byte[] page(long granule, byte[] packet) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("OggS".getBytes(StandardCharsets.US_ASCII));
        out.write(0);
        out.write(0);
        for (int i = 0; i < 8; i++) {
            out.write((int) (granule >>> (8 * i)) & 0xFF);
        }
        out.writeBytes(new byte[12]);
        out.write(1);
        out.write(packet.length);
        out.writeBytes(packet);
        return out.toByteArray();
    }

    /** A Vorbis file's identification header followed by a last page at {@code samples}. */
    static byte[] vorbis(int channels, int rate, long samples) {
        ByteArrayOutputStream id = new ByteArrayOutputStream();
        id.write(1);
        id.writeBytes("vorbis".getBytes(StandardCharsets.US_ASCII));
        id.writeBytes(new byte[4]);
        id.write(channels);
        for (int i = 0; i < 4; i++) {
            id.write((rate >>> (8 * i)) & 0xFF);
        }
        id.writeBytes(new byte[14]);
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.writeBytes(page(0, id.toByteArray()));
        file.writeBytes(page(-1, new byte[40]));
        file.writeBytes(page(samples, new byte[40]));
        return file.toByteArray();
    }

    @Test
    void readsChannelsAndLength() {
        OggInfo stereo = OggInfo.read(vorbis(2, 44100, 44100L * 90)).orElseThrow();
        assertTrue(stereo.vorbis());
        assertTrue(stereo.stereo());
        assertEquals(44100, stereo.sampleRate());
        assertEquals(90.0, stereo.seconds(), 1e-9);

        OggInfo mono = OggInfo.read(vorbis(1, 48000, 24000)).orElseThrow();
        assertFalse(mono.stereo());
        assertEquals(0.5, mono.seconds(), 1e-9);
    }

    @Test
    void anOggThatIsNotVorbisSaysSo() {
        byte[] opus = page(0, "OpusHead\u0001\u0002\u0000\u0000\u0080»\u0000\u0000\u0000\u0000\u0000"
                .getBytes(StandardCharsets.ISO_8859_1));
        assertFalse(OggInfo.read(opus).orElseThrow().vorbis());
    }

    @Test
    void aRenamedMp3IsNotAnOgg() {
        byte[] mp3 = "ID3\u0004\u0000\u0000\u0000\u0000\u0000\u0000 and then a great deal of mpeg"
                .getBytes(StandardCharsets.ISO_8859_1);
        assertTrue(OggInfo.read(mp3).isEmpty());
        assertTrue(OggInfo.read(new byte[0]).isEmpty());
    }
}
