package ai.resourcepack.engine.core.edit;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

/**
 * The shapes that cross between this plugin and ResourcePack AI Studio's
 * editors.
 *
 * <p>A server owner runs {@code /rp edit chair}, clicks a link, edits
 * the thing in a real editor in a browser, presses one button, and the file on
 * this machine changes. This class is the only place the wire is written down
 * on this side; the other end is studio's {@code lib/temp-edit/protocol.ts}.
 * <strong>The two have to agree and there is no shared type between a jar and
 * a Worker</strong> — the same standing arrangement the push manifest already
 * lives under.
 *
 * <p>Two properties of the wire are worth knowing before changing anything
 * here, because both were chosen rather than fallen into:
 *
 * <ul>
 *   <li><strong>Files cross, not content.</strong> A path relative to the pack
 *       folder and some bytes. The far end decides what a texture is called
 *       and where a new one goes; this end writes what it is given, having
 *       checked the path is inside its own pack. That keeps the editor's own
 *       vocabulary — atlases, slugs, collision suffixes — entirely over
 *       there, where it changes when an editor changes.</li>
 *   <li><strong>YAML never crosses.</strong> A vehicle travels as the same
 *       JSON the push manifest already carries, so
 *       {@code StudioContent}'s reader and this one describe one shape. The
 *       author's file — its comments, its ordering, the other vehicles in it —
 *       stays on the side that owns it, and only the one entry is rewritten.
 *       See {@link YamlBlocks}.</li>
 * </ul>
 *
 * <p>Every field is package-private and set by Gson. Boxed types where absent
 * has to be distinguishable from zero, for the reason {@code StudioContent}'s
 * emitter states at length.
 */
final class EditWire {

    private EditWire() {
    }

    /** What is POSTed to open a session. */
    static final class Open {
        /** {@code RPEngine/<version>}. The far end refuses an unknown client. */
        String client;
        /** {@code model}, {@code texture} or {@code vehicle}. */
        String kind;
        /** The content id, for the editor's header: {@code mypack:chair}. */
        String label;
        /** This server's Minecraft version, so geometry is validated against it. */
        String minecraftVersion;
        Model model;
        List<Texture> textures;
        /** Where a texture created in the editor should be written. */
        String texturePrefix;
        Vehicle vehicle;
    }

    static final class Model {
        /** Relative to the pack folder: {@code assets/models/chair.json}. */
        String path;
        /** {@code json} or {@code bbmodel}. */
        String format;
        /**
         * The Minecraft model, as the editor and the game both understand it.
         *
         * <p>A {@code .bbmodel} is converted before it is sent, by
         * {@link ai.resourcepack.engine.core.item.BbModel} — the reader this
         * plugin already has. Sending the project file instead would mean the
         * far end carrying a second copy of that reader, and the two drifting
         * would be a model that looks different in the editor than in game.
         */
        JsonObject json;
    }

    static final class Texture {
        /** How the model names it: {@code mypack:item/chair}. */
        String ref;
        /** Relative to the pack folder. */
        String path;
        /** PNG bytes, base64. */
        String png;
        /** Set on the one texture a {@code texture} session is about. */
        Boolean primary;
    }

    /** What comes back when a session is opened. */
    static final class Opened {
        String id;
        /** The whole link, token included, to put in chat. */
        String url;
        /** The credential this plugin polls and pulls with. */
        String pullToken;
        String expiresAt;
        int revision;
    }

    /** What a poll answers. */
    static final class Status {
        String id;
        String kind;
        String label;
        int revision;
        String expiresAt;
        Boolean opened;
    }

    /** What a pull hands over. */
    static final class Pull {
        String id;
        String kind;
        int revision;
        List<File> files;
        Vehicle vehicle;
        /** Paths whose texture was deleted in the editor. */
        List<String> removed;
    }

    static final class File {
        String path;
        /** {@code utf8} or {@code base64}. */
        String encoding;
        String content;
    }

    /**
     * A vehicle, in the units and the field names its YAML uses.
     *
     * <p>Deliberately the same shape as the push manifest's, so both readers
     * describe one thing. Offsets are in <strong>blocks</strong>, as a
     * hand-written {@code vehicles/*.yml} writes them; the editor works in
     * model pixels and converts at its own edge.
     */
    static final class Vehicle {
        String name;
        String medium;
        double weight;
        double speed;
        double acceleration;
        double turnSpeed;
        /**
         * How much bigger than built it is drawn. Boxed for the reason the push
         * manifest boxes it: an absent primitive reads as zero, and a scale of
         * zero is a vehicle drawn at no size. Absent means 1.
         */
        Double scale;
        /**
         * Whether space jumps it rather than braking it. Boxed like the scale:
         * absent is false, the handbrake every land vehicle had before.
         */
        Boolean jump;
        /**
         * Whether it turns while standing still. Boxed like the rest, though
         * an absent boolean already reads as the false that means "only while
         * moving".
         */
        Boolean turnInPlace;
        /**
         * Whether a rider's cape is drawn in it. Boxed, and here it matters:
         * the default is TRUE, so an absent primitive's false would undress
         * every rider of a vehicle edited by an older Studio.
         */
        Boolean capes;
        double hitboxWidth;
        double hitboxHeight;
        double hitboxLength;
        /**
         * How it flies, sent only for {@code medium: air} and boxed so that an
         * absent one is told from a zero — a {@code climbRate} of zero is an
         * aircraft that cannot climb. Absent means the speed-derived defaults
         * a file without a {@code flight:} block gets.
         */
        Double takeoffSpeed;
        Double climbRate;
        Double diveRate;
        Double stallSink;
        List<Seat> seats;
        Map<String, String> animations;
        /**
         * State name to a sound's id, whole — {@code mypack:engine}, exactly
         * as the file writes it.
         *
         * <p><strong>Unlike the push manifest, which sends a bare id.</strong>
         * There it can, because a pushed sound and the vehicle naming it are
         * two halves of one pack in one namespace; here the sound is somebody
         * else's content on somebody else's server and the namespace is the
         * only thing saying which pack it belongs to.
         */
        Map<String, String> sounds;
        List<Emitter> particles;
    }

    static final class Seat {
        String role;
        String pose;
        double x;
        double y;
        double z;
        double yaw;
        String name;
        /** Absent is false, as on the push manifest. */
        Boolean hidden;
        Map<String, String> animations;
    }

    static final class Emitter {
        String effect;
        List<String> states;
        double x;
        double y;
        double z;
        int count;
        double spread;
        double speed;
        int interval;
        Boolean enabled;
        /** {@code 0xRRGGBB}, or absent for none. */
        Integer color;
        Double size;
    }

    /** An error body, which every route answers with when it refuses. */
    static final class Failure {
        String error;
    }
}
