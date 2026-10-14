package ai.resourcepack.engine.api;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.util.List;
import java.util.Optional;

/**
 * The 3D shader objects this server holds, and the ones standing in its
 * worlds.
 *
 * <p>A 3D shader object is drawn by a core shader in the resource pack: a
 * black hole, a nuke, a portal, a soap bubble the size of a house. The pack's
 * shader draws it wherever an item display shows its item; this is what puts
 * one down and then DRIVES it, by state, position, turn and size, from a
 * command, a content file's sequence or your own plugin.
 *
 * <p>Two sources, one list: a Studio push (ids {@code studio:<id>}) and a
 * content folder's {@code objects/} files, which can also add sequences to a
 * pushed object.
 *
 * <p>Needs Minecraft 1.21.4 or newer on the server (an object is driven
 * through its item's {@code custom_model_data} colour) and a pack that draws
 * objects with the item marker (Studio's 26.2 packs). {@link #supported} says
 * whether this server can.
 *
 * <p><b>Main thread only</b>, except {@link #ids} and {@link #info}.
 */
public interface ShaderObjects {

    /** Whether this server can place and drive objects at all. */
    boolean supported();

    /** Every object id this server holds, sorted. */
    List<ContentId> ids();

    Optional<ShaderObjectInfo> info(ContentId id);

    /**
     * Puts one down with its middle at {@code where}, at the size it was made
     * at, unturned, in state 0 and settled.
     */
    Optional<ShaderObject> spawn(Location where, ContentId id);

    /** The same at a diameter of {@code size} blocks. */
    Optional<ShaderObject> spawn(Location where, ContentId id, double size);

    /** The object this entity draws, if it is one of ours. */
    Optional<ShaderObject> at(Entity entity);

    /** Every object within {@code radius} blocks, nearest first. */
    List<ShaderObject> near(Location near, double radius);

    /** Every object in a loaded chunk. */
    List<ShaderObject> loaded();
}
