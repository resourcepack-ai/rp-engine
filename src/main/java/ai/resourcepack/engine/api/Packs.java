package ai.resourcepack.engine.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The resource packs this server builds, and which players already have them
 * from somewhere else.
 *
 * <p>For a plugin that builds one pack out of several plugins' packs (a
 * network pack, a pack manager): read RP Engine's from {@link #readable},
 * rebuild yours when {@link ai.resourcepack.engine.api.event.PackBuildEvent}
 * says it changed, and {@link #markExternal mark} each player you sent yours
 * to, so RP Engine does not send its own on top.
 *
 * <p>A pushed Studio pack is not one of these: it belongs to one player and
 * is sent to them on top of everything here.
 *
 * <p>Any thread.
 */
public interface Packs {

    /**
     * Every pack the server sends, in stack order, bottom first: the bundles,
     * then the emote rigs pack where it is a pack of its own (Minecraft
     * 1.20.3+ with the engine serving its packs; elsewhere the rigs are inside
     * the bundle). Each {@link BuiltPack#sha1()} is the hash its players are
     * told. Empty until the first build at startup has finished.
     */
    List<BuiltPack> all();

    /** One of {@link #all}, by bundle name. */
    Optional<BuiltPack> get(String bundle);

    /**
     * The same pack as {@link #get} before PackSquash, as an ordinary zip any
     * library can open. With {@code pack.packsquash} on, the file players are
     * sent may be one only the game can read; this is the one to merge into a
     * pack of your own. With PackSquash off it is the same file as
     * {@link #get}.
     */
    Optional<BuiltPack> readable(String bundle);

    /** The bundle a player is sent on join ({@code default-bundle}), or empty when there is none. */
    String defaultBundle();

    /**
     * Says a player already has a bundle's files, from a pack RP Engine did
     * not send them: one you merged its zip into. RP Engine then never sends
     * them that bundle, and takes it off them if it already had. Everything
     * else still goes on top of it (the emote rigs, a player's own rig, a
     * Studio push), and every feature works as though RP Engine had sent it.
     *
     * <p>Mark before RP Engine sends at join, or it sends and then takes it
     * back off: in {@code AsyncPlayerPreLoginEvent}, or in
     * {@code PlayerJoinEvent} at {@code LOWEST}. A mark lasts until the player
     * leaves. Keeping your pack up to date with RP Engine's is yours to do:
     * {@code PackBuildEvent} says when it changed.
     *
     * @return false if the mark was already there
     */
    boolean markExternal(UUID player, String bundle);

    /**
     * Takes a mark off, so RP Engine sends the bundle itself again (at once,
     * when {@code send.on-join} is on or it has sent them anything already).
     *
     * @return false if there was no such mark
     */
    boolean unmarkExternal(UUID player, String bundle);

    /** Whether a player is marked as having a bundle from a pack RP Engine did not send. */
    boolean isExternal(UUID player, String bundle);
}
