package ai.resourcepack.engine.core.emote;

import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scoreboard.Team;

/**
 * The floating name over one participant's rig.
 *
 * <p>Split out of {@link EmoteDirector} because it was three fields on a
 * session and six methods that could only be called with that session in hand,
 * describing one object: a text display, whether it is currently up, and
 * whether it is drawn the way a crouching player's name is. Those two flags
 * exist to keep the display from being rewritten every pass, which only works
 * if nothing can set one without the other — and a static method taking the
 * session could, which is exactly the bug {@link #moveTo} carries the note
 * about.
 *
 * <p>Working out what to draw is here too, because reading a team's prefix and
 * falling back to a display name is a question about a player's name and not
 * about emotes at all.
 */
final class EmoteNameTag {

    /**
     * Whether an emote's nametag is drawn through what is in front of it.
     *
     * <p><b>Off, which is not what vanilla does, and the reason is models.</b>
     * A {@code TextDisplay} with see-through on is drawn with no depth test AND
     * no depth write, so it neither hides anything nor is hidden by anything —
     * whatever the client happens to draw AFTER it simply paints over it.
     * Entity draw order is not something a server decides, so a model standing
     * near an emoting player wins that race often enough to read as "the
     * nametag is invisible in front of a model", which is what it was reported
     * as. Depth-tested text writes depth and is drawn when it is genuinely in
     * front, which is the property that actually matters here.
     *
     * <p>What it costs is vanilla's other half: a real nameplate IS visible
     * through terrain until its owner crouches, and this one is not. That is
     * the trade, and it is the right way round for a plugin whose whole point
     * is putting large models next to players.
     *
     * <p>Settable because it is a rendering judgement rather than a fact, and
     * because the two symptoms it sits between look nothing alike: on, a name
     * vanishes behind models; off, a name is hidden by terrain it used to show
     * through. Whoever can see both should be able to pick without a rebuild.
     */
    private static volatile boolean seeThrough = false;

    /**
     * How high above the feet the name floats, in blocks.
     *
     * <p>Vanilla draws a nametag at the entity's height plus half a block, and
     * a player is 1.8 tall — so this is that sum rather than a number picked to
     * look right. The rig's own head tops out lower than the tag (1.875), which
     * is exactly the gap a player's name has above their hat in ordinary play.
     *
     * <p><b>It is not where the DISPLAY goes, and assuming it was is what put
     * the name a line too high.</b> A vanilla nametag is drawn hanging DOWN
     * from this height; a {@link TextDisplay} draws its text UP from wherever
     * it stands. So the display is spawned a line below the number vanilla
     * uses, which is the whole of {@link #height}.
     */
    private static final double NAMETAG_Y = 2.3;

    /**
     * How tall one line of a {@link TextDisplay} stands, in blocks.
     *
     * <p>Nine font pixels at the 0.025 blocks-per-pixel every piece of world
     * text is drawn at. See {@link #NAMETAG_Y} for why it is subtracted.
     */
    private static final double NAMETAG_LINE = 9 * 0.025;

    /**
     * How far a crouching body drops its name, in blocks.
     *
     * <p>Vanilla floats a name at the entity's height plus half a block, and a
     * crouching player is 1.5 tall rather than 1.8 - so their name comes down
     * with them by exactly this. It matters here because a stance is worn while
     * its wearer crouches: a name held at standing height over a crouched rig
     * is the sort of not-quite-right that reads as a floating label rather than
     * as somebody's nametag.
     */
    private static final double SNEAK_DROP = 0.3;

    /**
     * How far a crouching player's name carries, as a display's view range.
     *
     * <p>A display's range is a multiple of the 64 blocks a nametag normally
     * reaches, and vanilla halves that for a crouching player — along with
     * refusing to draw it through walls. Both are how a crouch reads as hiding
     * rather than as walking slowly, and a rig that kept broadcasting its
     * owner's name across the map while they crept would be worse than no
     * nametag at all.
     */
    private static final float SNEAK_RANGE = 0.5f;

    /**
     * The same text with legacy codes taken out, for asking whether there is
     * anything in it. Never what is DRAWN — see {@link #textFor}.
     *
     * <p>The sign is a NUMBER here, not the character and not a unicode escape.
     * A literal section sign in a source file is at the mercy of whichever
     * encoding javac was launched with, and an escape is worse: javac
     * translates those before the parser runs, comments included, so writing
     * one even in prose here is a compile error. (It was, once, in this very
     * comment.) 0xA7 is neither.
     */
    private static final char SECTION_SIGN = (char) 0xA7;

    private final TextDisplay display;

    private boolean shown = true;

    /**
     * Whether the name is currently drawn the way a crouching player's is.
     *
     * <p>Held so the two properties that change with it — how high it floats
     * and whether it shows through walls — are only written when the answer
     * actually changes, rather than being re-sent to everybody nearby on every
     * pass of every stance.
     */
    private boolean sneaking;

    private EmoteNameTag(TextDisplay display, boolean sneaking) {
        this.display = display;
        this.sneaking = sneaking;
    }

    /**
     * The floating name over one participant's rig.
     *
     * <p>Spawned for every emote, not only a worn one: hiding the body takes
     * the nametag with it either way, and a rig nobody can put a name to is
     * the same puzzle whether it is dancing for two seconds or being worn all
     * afternoon.
     *
     * <p><b>The wearer sees it too, and hiding it from them was a mistake worth
     * writing down.</b> The reasoning was that vanilla never shows you your own
     * name, so putting one over your head reads as the plugin leaking — true of
     * a PLAYER's nametag and wrong about this one, which labels a rig standing
     * in the world that you are looking at on purpose. Somebody testing an
     * emote alone is the commonest way this feature is ever seen, and for them
     * hiding it from the wearer is indistinguishable from the nametag not
     * working at all. That is exactly how it was reported.
     */
    static EmoteNameTag spawn(Player player, Location origin, boolean sneaking, boolean carried) {
        String label = textFor(player);
        if (label.isEmpty()) return null;
        // Only a stance reads the wearer's crouch, and for the reason
        // carryFollowers states: a set has a crouching STATE and its
        // rig really does crouch, while a one-shot plays what it was given
        // whatever the shift key is doing. Spawning a one-shot's name at
        // crouch height put it a third of a block inside the rig's head, and
        // spawning it with see-through off left it occluded by the rig itself.
        Location at = origin.clone().add(0, height(sneaking), 0);
        at.setYaw(0);
        at.setPitch(0);
        // Carried by a following emote as well as by a stance, for the reason
        // spawnShadow states: carryFollowers moves this every accepted step.
        TextDisplay tag = at.getWorld().spawn(at, TextDisplay.class, d -> {
            d.setText(label);
            // Turns to face whoever is reading it, which is what a nametag
            // does — a fixed one is unreadable from three quarters of the
            // angles somebody might be standing at.
            d.setBillboard(Display.Billboard.CENTER);
            // Vanilla's own plate and shadow rather than a hand-mixed
            // background: matching it exactly is the difference between a
            // nametag and a floating sign, and there is nothing to gain by
            // being a shade off.
            d.setSeeThrough(seeThrough && !sneaking);
            d.setViewRange(sneaking ? SNEAK_RANGE : 1f);
            d.setDefaultBackground(true);
            // No drop shadow, because a real nametag has none. Vanilla
            // draws a name plate through the font with shadow off — it is about
            // the only place in the game that does — and a display defaults it
            // on, so ours came out a shade heavier than every other name in the
            // world. The dark plate behind it is what makes it readable, and
            // that IS on.
            d.setShadowed(false);
            d.setPersistent(false);
            if (carried) EmoteDirector.carry(d);
        });
        return new EmoteNameTag(tag, sneaking);
    }

    /** The entity itself, for the per-viewer hiding the director does. */
    TextDisplay display() {
        return display;
    }

    void remove() {
        if (display.isValid()) display.remove();
    }

    /**
     * Puts the name over these feet, at the height for this crouch state.
     *
     * <p>Facing is zeroed: the display is billboarded, so it turns to whoever
     * is reading it whatever its own yaw says, and carrying the player's would
     * be a rotation in every packet that changes nothing on screen.
     */
    void moveTo(Location feet, boolean sneaking) {
        if (!display.isValid()) return;
        Location at = feet.clone().add(0, height(sneaking), 0);
        at.setYaw(0);
        at.setPitch(0);
        if (!at.equals(display.getLocation())) display.teleport(at);
        sneaking(sneaking);
    }

    /**
     * Shows or hides one participant's name along with their rig.
     *
     * <p>They go together and must never disagree. A group crossing into a
     * state it leaves to vanilla puts the rig away and gives the body back —
     * and the body brings its own real nametag with it, so leaving ours up
     * would print the same person's name twice, one above the other.
     *
     * <p>Hidden by RANGE rather than by removing the display, because a set can
     * cross that boundary several times a second — the same reasoning
     * {@link #setRigHidden} gives for swapping items instead of entities. The
     * shown range is whatever the wearer's posture says, so bringing a name
     * back over a crouching player does not also un-crouch it.
     */
    void shown(boolean shown) {
        this.shown = shown;
        if (!display.isValid()) return;
        display.setViewRange(shown ? (sneaking ? SNEAK_RANGE : 1f) : 0f);
    }

    /**
     * Draws the name the way a crouching player's is drawn, or stops.
     *
     * <p>Half the range and no seeing it through walls, which is what vanilla
     * does and what makes a crouch read as hiding. Written only when the answer
     * changes: both are entity metadata, so re-sending them every pass would be
     * two packets a tick to everybody nearby for a fact that changes when
     * somebody presses shift.
     */
    void sneaking(boolean sneaking) {
        if (this.sneaking == sneaking) return;
        this.sneaking = sneaking;
        TextDisplay tag = display;
        if (tag == null || !tag.isValid()) return;
        tag.setSeeThrough(seeThrough && !sneaking);
        if (shown) tag.setViewRange(sneaking ? SNEAK_RANGE : 1f);
    }

    /** See {@link #seeThrough}. Called from the plugin on load and reload. */
    static void seeThrough(boolean value) {
        seeThrough = value;
    }

    /**
     * How high the name's DISPLAY stands, for a body in this posture.
     *
     * <p>Two corrections to "the height vanilla draws a name at", and the first
     * one is why the tag used to float a line clear of where it belonged. A
     * vanilla nametag hangs DOWN from {@link #NAMETAG_Y}: the text is drawn at
     * that point and the glyphs descend from it. A {@link TextDisplay} is the
     * other way up — the game lifts its text by its own height before drawing,
     * so the position is the BOTTOM of the line. Spawning one at the number
     * vanilla uses therefore puts the whole line above where a real name sits,
     * and taking {@link #NAMETAG_LINE} back off is what lines the two up.
     *
     * <p>The second is the crouch: vanilla's height is the entity's plus half a
     * block, and a crouching player is shorter, so their name comes down with
     * them. A stance is worn while its wearer crouches, which is exactly when
     * that mattered and nothing here was doing it.
     */
    static double height(boolean sneaking) {
        return NAMETAG_Y - NAMETAG_LINE - (sneaking ? SNEAK_DROP : 0);
    }

    /**
     * The name over the rig, exactly as the game would have drawn it.
     *
     * <p><b>A player's nametag is their SCOREBOARD name, not their display
     * name, and the two are different things.</b> Vanilla renders team prefix +
     * team colour + account name + team suffix — that is the whole rule, and it
     * is why {@code setDisplayName} (which most chat plugins set) changes what
     * appears in chat and nothing at all above anybody's head. So the tag is
     * built from the team, and a player on a coloured team gets their colour
     * here for the same reason they have it in game.
     *
     * <p><b>The colour codes are KEPT.</b> They used to be stripped, on the
     * belief that a TextDisplay renders the section sign literally. It does
     * not: {@code setText(String)} goes through the same server-side conversion
     * every coloured chat message does, which parses the codes into a styled
     * component before anything is sent — so a display takes them exactly as
     * {@code sendMessage} does. Stripping them was throwing away the one thing
     * this is trying to copy.
     *
     * <p>Falls back to the DISPLAY name when no team dresses them, because a
     * server that puts a rank in the display name and nowhere else has put it
     * there deliberately, and the account name alone would be less of that
     * person's name than what everyone is used to seeing. A display name that
     * is nothing but codes leaves an empty string, and a nametag with no name
     * in it is worse than the plain one.
     */
    static String textFor(Player player) {
        String fromTeam = teamName(player);
        if (fromTeam != null) return fromTeam;
        String display = player.getDisplayName();
        return display == null || stripped(display).isEmpty() ? player.getName() : display;
    }

    /**
     * The team-formatted name, or null if the team does not dress it.
     *
     * <p><b>A team that says nothing is not an answer.</b> Plenty of servers
     * put everybody on a team for collision or for whether names show at all,
     * with no prefix, suffix or colour on it — and taking that as the answer
     * would hand back the bare account name and throw away a rank the display
     * name was carrying. So an undressed team falls through to the display
     * name, which is the same thing a player on no team at all gets: whichever
     * of the two names we hold says more about them, rather than an answer that
     * depends on a scoreboard decision made for another reason entirely.
     *
     * <p>Wrapped, and the catch is the point: the scoreboard belongs to the
     * server and a plugin that swaps one out from under us can throw from any
     * of these reads. A name is decoration, and no emote is worth losing to it.
     */
    private static String teamName(Player player) {
        try {
            if (player.getScoreboard() == null) return null;
            org.bukkit.scoreboard.Team team = player.getScoreboard().getEntryTeam(player.getName());
            if (team == null) return null;
            String prefix = team.getPrefix() == null ? "" : team.getPrefix();
            String suffix = team.getSuffix() == null ? "" : team.getSuffix();
            // The colour is written before the name rather than at the front of
            // the whole thing: a prefix carries its own colours, and a code in
            // front of it would be overwritten by the first one inside it.
            String colour = "";
            org.bukkit.ChatColor teamColour = team.getColor();
            if (teamColour != null && teamColour != org.bukkit.ChatColor.RESET) {
                colour = teamColour.toString();
            }
            if (prefix.isEmpty() && suffix.isEmpty() && colour.isEmpty()) return null;
            String built = prefix + colour + player.getName() + suffix;
            return stripped(built).isEmpty() ? null : built;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static String stripped(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // The code is the sign plus whatever follows it, which is what
            // makes skipping two characters right even for an invalid one.
            if (c == SECTION_SIGN && i + 1 < text.length()) {
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString().trim();
    }
}
