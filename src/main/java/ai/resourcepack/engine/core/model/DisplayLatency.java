package ai.resourcepack.engine.core.model;

/**
 * How far behind a moved thing is drawn, in ticks.
 *
 * <p>Two subsystems draw rigs out of {@link org.bukkit.entity.Display}
 * entities and move them every tick — emotes and vehicles — and both had a
 * number for this, chosen separately, documented separately, and meaning
 * different things. This is the shared fact underneath them, stated once.
 *
 * <p><strong>They still use different numbers, and that is correct.</strong>
 * The two are answering different questions, and collapsing them to one value
 * would break whichever one lost. What is shared is the vanilla behaviour they
 * are both reasoning about, and the derivation of each — which is what was
 * missing, and why the vehicle number was picked to look smooth on its own and
 * ended up a tick out from the thing it had to stay glued to.
 */
public final class DisplayLatency {

    /**
     * How long a client takes to interpolate an ordinary entity onto a new
     * position.
     *
     * <p><strong>Vanilla's, not ours, and not something a plugin can ask
     * for.</strong> It is copied here because two things depend on it and
     * neither can read it: a rider is drawn wherever their mount is, so a
     * passenger is exactly this far behind, and anything that must stay glued
     * to a rider has to be behind by the same amount.
     *
     * <p>If a version changes it, this is the one line to change — and the
     * symptom will be a passenger sliding out of their seat, because that is
     * the only thing that reads it.
     */
    public static final int TRACKED_ENTITY_TICKS = 3;

    private DisplayLatency() {
    }

    /**
     * How long to give a display to cover one step, when it is being moved
     * every {@code sendPeriodTicks} ticks and has nothing to stay glued to.
     *
     * <p><strong>One tick longer than the send rate, and the extra tick is the
     * whole point.</strong> Setting it equal to the send rate means the client
     * finishes moving exactly as the next update is due, so a packet that
     * arrives even slightly late leaves the rig standing still until it lands
     * and then jumping. Every wobble in a connection comes out as a stutter —
     * which presents as "a lot laggier" rather than as a rig in the wrong
     * place, and is how the emote rig was reported before it got this slack.
     *
     * <p>This is the right answer when the thing being drawn is judged against
     * the WORLD, where less lag is better and stutter is the only failure. It
     * is the wrong answer when it is judged against a rider — see
     * {@link #TRACKED_ENTITY_TICKS}, which that case must match instead.
     */
    public static int glideTicks(int sendPeriodTicks) {
        return Math.max(1, sendPeriodTicks) + 1;
    }
}
