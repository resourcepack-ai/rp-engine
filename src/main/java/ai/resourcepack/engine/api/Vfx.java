package ai.resourcepack.engine.api;

import java.util.List;
import java.util.Optional;

import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * The VFX this server can play: spell and ability effects drawn in the world
 * out of glowing sprites, a slash, a lightning barrage, a black hole.
 *
 * <p>A VFX is a timeline, not a picture. Every sprite is an item display the
 * engine spawns, moves and removes tick by tick, and every picture it shows is
 * in the resource pack, so a player sees an effect only when their pack
 * carries its sprites. Displays from a Studio effect are shown only to the
 * players whose pack has it; a content folder's are shown to everybody, since
 * this server's own pack carries them.
 *
 * <p>Two sources, one list: a content folder's {@code vfx/} (ids in that
 * folder's namespace) and Studio packs (ids {@code studio:<id>}).
 *
 * <p>Needs Minecraft 1.21.4 or newer ({@link Feature#VFX}); below that the
 * list is still answered and {@link #play} returns empty.
 *
 * <p>Main thread only.
 */
public interface Vfx {

    /** Every effect id, sorted. */
    List<ContentId> ids();

    Optional<VfxInfo> info(ContentId id);

    /**
     * An effect by what somebody typed: {@code mypack:frost_nova}, or a bare
     * {@code frost_nova}, which is a content folder's effect before a Studio one
     * and the first namespace alphabetically within each.
     */
    Optional<VfxInfo> find(String name);

    /**
     * Plays an effect at a place, facing that place's yaw. Empty when there is
     * no such effect or the server is too old for VFX.
     */
    Optional<VfxPlay> play(ContentId id, Location at);

    /** Plays an effect cast by a player: at their feet, facing the way they face. */
    Optional<VfxPlay> play(ContentId id, Player caster);

    /**
     * Plays an effect at a place, as {@code options} say: on a loop, for a
     * time or until a condition, faster, bigger, turned, offset, following an
     * entity, for its caster alone. See {@link VfxOptions}.
     *
     * @param caster whose placeholders and permissions a condition reads, and
     *               who {@link VfxOptions.Viewers#CASTER} means; may be null
     */
    Optional<VfxPlay> play(ContentId id, Location at, Player caster, VfxOptions options);

    /** Plays an effect cast by a player, as {@code options} say. */
    Optional<VfxPlay> play(ContentId id, Player caster, VfxOptions options);

    /** Ends every effect cast with this {@link VfxOptions#tag}, as each one's {@link VfxOptions#end} says. Returns how many. */
    int stop(String tag);

    /** Ends every effect cast on this player. Returns how many. */
    int stop(Player caster);

    /** Every effect playing now. */
    List<VfxPlay> playing();

    /** Stops every effect playing now. Returns how many there were. */
    int stopAll();
}
