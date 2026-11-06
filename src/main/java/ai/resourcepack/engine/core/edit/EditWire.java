// GENERATED from Studio's description of this wire. Do not edit by hand.
package ai.resourcepack.engine.core.edit;

import java.util.List;
import java.util.Map;

/**
 * The shapes that cross between this plugin and ResourcePack AI Studio's
 * editors.
 *
 * <p>A server owner runs {@code /rp edit chair}, clicks a link, edits
 * the thing in a real editor in a browser, presses one button, and the file on
 * this machine changes.
 *
 * <p><strong>Generated.</strong> Studio writes this file from the same
 * description it writes its own side from, so the two cannot disagree about a
 * field's name or type. Do not edit it by hand.
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
        com.google.gson.JsonObject json;
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
        /** Opaque addon-owned configuration, transported without interpretation. */
        Map<String, Map<String, Object>> addons;
        String name;
        String medium;
        double weight;
        double speed;
        double acceleration;
        double turnSpeed;
        /**
         * Whether it turns while standing still — see
         * {@code VehicleInfo.turnInPlace}.
         *
         * <p>Boxed like the numbers around it, though for once the unboxed
         * default would have been right: gson leaves an absent boolean at
         * false, which is what a manifest pushed before this existed means.
         * Boxed anyway so this file has one rule about an absent field rather
         * than one per type.
         */
        Boolean turnInPlace;
        /**
         * How much bigger than built it is drawn — see {@code VehicleInfo.scale}.
         *
         * <p><strong>Boxed, on the same argument as the flight numbers below.</strong>
         * Gson leaves an absent primitive at zero, and a scale of zero is a
         * vehicle drawn at no size at all — which is what every vehicle pushed
         * before this field existed would become. Absent means 1.
         */
        Double scale;
        /**
         * Whether space jumps it rather than braking it — see
         * {@code VehicleInfo.jumps()}. Boxed so a manifest older than the field
         * reads as absent, which is false, which is the handbrake every land
         * vehicle had before.
         */
        Boolean jump;
        double hitboxWidth;
        double hitboxHeight;
        double hitboxLength;
        /**
         * How it flies, which only {@code medium: air} reads.
         *
         * <p><strong>Boxed, for the same reason the emitter's optional numbers
         * are.</strong> Gson leaves an absent primitive at zero, and zero is a
         * meaningful value for three of these that is not the right default —
         * a {@code climbRate} of zero is an aircraft that cannot climb, which
         * is what every vehicle pushed before this field existed would silently
         * become. Absent means {@link VehicleFlight#forSpeed}, the behaviour
         * those vehicles were built against.
         */
        Double takeoffSpeed;
        Double climbRate;
        Double diveRate;
        Double stallSink;
        List<Seat> seats;
        /**
         * State name to animation name. Absent on a manifest older than this,
         * which is a vehicle that animates nothing.
         */
        Map<String, String> animations;
        /**
         * State name to SOUND id — the id of a sound in this same manifest,
         * which becomes {@code studio:<id>} at this end like everything else
         * on this path. Absent on a manifest older than this, which is a
         * vehicle that makes no noise.
         */
        Map<String, String> sounds;
        /**
         * Whether a rider's cape is drawn in it — see
         * {@code VehicleInfo.capes}.
         *
         * <p>Boxed, and this one HAS to be: the default is TRUE, so an absent
         * primitive's false would take the cape off every rider in every
         * vehicle pushed before this field existed.
         */
        Boolean capes;
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
        /**
         * Whether this seat's occupant is drawn at all. Absent is false, which
         * is every seat pushed before this existed.
         */
        Boolean hidden;
        /**
         * State name to EMOTE id — what this seat's occupant wears while the
         * vehicle is in that state. Absent on a manifest older than this.
         */
        Map<String, String> animations;
    }

    static final class Emitter {
        String effect;
        List<String> states;
        /** In BLOCKS, like a seat's — see {@link Vehicle}. */
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
