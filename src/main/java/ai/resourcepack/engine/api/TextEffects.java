package ai.resourcepack.engine.api;

import java.util.List;
import java.util.Optional;

/**
 * The text effects this server has: colours that make any text drawn in them
 * move, burn, shine or fill with water.
 *
 * <p>An effect is drawn by the resource pack's text shader, so it needs no
 * packet, no entity and nothing on the server beyond the right colour: an
 * item name, a chat line, a title, a scoreboard line or an inventory title in
 * the effect's exact colour is drawn by the effect, for every player whose
 * pack carries it. A player without the pack sees near-white text.
 *
 * <p>Two sources, one list: a content folder's {@code text-effects/} (ids in
 * that folder's namespace, drawn by this server's own pack) and Studio packs
 * (ids {@code studio:<id>}, drawn by that pack's shader).
 *
 * <p>Only text drawn flat on the screen wears an effect: chat, titles, the
 * action bar, item names and lore, scoreboards, menus. Text standing in the
 * world (signs, name tags, text displays) is drawn as plain text in that
 * colour.
 *
 * <p>Any thread.
 */
public interface TextEffects {

    /** Every effect id, sorted. */
    List<ContentId> ids();

    Optional<TextEffectInfo> info(ContentId id);

    /**
     * An effect by what somebody typed: {@code mypack:fire}, or a bare
     * {@code fire}, which is a content folder's effect before a Studio one and
     * the first namespace alphabetically within each.
     */
    Optional<TextEffectInfo> find(String name);

    /**
     * {@code text} in the effect, as a legacy string ({@code §x§f§c...}) that
     * resets after itself. Unchanged when {@code id} is no effect.
     *
     * <p>Formatting inside it is kept, except a colour: {@code §l} bold still
     * wears the effect, {@code §c} red is red text and no effect at all,
     * because the colour IS the effect.
     */
    String apply(ContentId id, String text);

    /**
     * Every {@code <fx:name>...</fx>} in {@code text} replaced by that text in
     * the effect, as {@link #apply} writes it. A tag that names no effect is
     * left exactly as written. A tag with no {@code </fx>} runs to the end, and
     * a second opening tag closes the first.
     *
     * <p>What RP Engine does to every name, lore line, message and title it
     * reads from a content file, and what {@code /rp say} does.
     */
    String format(String text);
}
