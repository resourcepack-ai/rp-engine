package ai.resourcepack.engine.api;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * What an emote is, without playing it: how long it runs, whether it loops,
 * who else it needs, and whether it is worn rather than performed.
 *
 * <p>Enough to build a menu of emotes, or to decide whether to offer one at
 * all. Obtained from {@link Emotes#info}.
 */
public final class EmoteInfo {

    private final String name;
    private final double length;
    private final boolean loop;
    private final List<String> castSlots;
    private final Set<EmoteTrigger> triggers;
    private final boolean group;

    /**
     * @deprecated Use the constructor taking {@code triggers}. This one reads
     *             an emote as never worn, which is right for everything
     *             authored before stances existed and wrong for a stance.
     */
    @Deprecated
    public EmoteInfo(String name, double length, boolean loop, List<String> castSlots) {
        this(name, length, loop, castSlots, Collections.emptySet());
    }

    public EmoteInfo(String name, double length, boolean loop, List<String> castSlots,
                     Set<EmoteTrigger> triggers) {
        this(name, length, loop, castSlots, triggers, false);
    }

    public EmoteInfo(String name, double length, boolean loop, List<String> castSlots,
                     Set<EmoteTrigger> triggers, boolean group) {
        this.name = name;
        this.length = length;
        this.loop = loop;
        this.castSlots = castSlots == null ? List.of() : List.copyOf(castSlots);
        this.triggers = triggers == null || triggers.isEmpty()
            ? Collections.<EmoteTrigger>emptySet()
            : Collections.unmodifiableSet(EnumSet.copyOf(triggers));
        this.group = group;
    }

    /** Its name, as the panel shows it. This is also what {@link Emotes#play} takes. */
    public String name() {
        return name;
    }

    /**
     * How long one run lasts, in seconds.
     *
     * <p><b>Zero for a group</b>, which has no single length: each of its
     * members has its own, and which one is running depends on what the wearer
     * is doing. Ask {@link #isGroup()} before showing this.
     */
    public double length() {
        return length;
    }

    /**
     * Whether it repeats until stopped rather than ending on its own.
     *
     * <p>Always true for a group: it is worn until taken off, whatever the
     * member currently driving the rig says about itself.
     */
    public boolean loop() {
        return loop;
    }

    /**
     * What each cast slot is called, in the order {@link Emotes#play} expects
     * the players. Empty for a solo emote.
     *
     * <p>These are labels for a prompt ("partner", "the one being hugged"), not
     * player names, and a slot can be unnamed - in which case it reads as
     * "player".
     */
    public List<String> castSlots() {
        return castSlots;
    }

    /** Whether this emote needs other players to run at all. */
    public boolean needsCast() {
        return !castSlots.isEmpty();
    }

    /**
     * The movement states this is worn for. Empty on an ordinary emote.
     *
     * <p>Non-empty means a stance: the player keeps it on and keeps playing,
     * and it animates only while they are in one of these. See
     * {@link EmoteTrigger}.
     */
    public Set<EmoteTrigger> triggers() {
        return triggers;
    }

    /**
     * Whether this is worn rather than performed.
     *
     * <p>The question a menu wants: a stance is offered as something to put on
     * and take off, and an ordinary emote as something to do once.
     */
    public boolean isWorn() {
        return !triggers.isEmpty();
    }

    /**
     * Whether this is a movement GROUP rather than a single emote.
     *
     * <p>A group is a set with one emote per movement state, worn as one thing:
     * the rig swaps to whichever member matches what the wearer is doing, and a
     * state the set left alone shows the player's own animation. It is played
     * exactly like an emote — same name, same {@link Emotes#play} — so most
     * callers never have to ask. The two that do are a menu deciding what to
     * print (see {@link #length()}) and anything reasoning about the emote
     * behind a name, because a group has no keyframes of its own.
     *
     * <p>{@link #triggers()} is the states it covers, so a group is always
     * {@link #isWorn()}.
     */
    public boolean isGroup() {
        return group;
    }

    @Override
    public String toString() {
        return (group ? "EmoteInfo(group " : "EmoteInfo(") + name
                + (group ? "" : ", " + length + "s")
                + (castSlots.isEmpty() ? "" : ", cast " + castSlots)
                + (triggers.isEmpty() ? "" : ", worn while " + triggers) + ")";
    }
}
