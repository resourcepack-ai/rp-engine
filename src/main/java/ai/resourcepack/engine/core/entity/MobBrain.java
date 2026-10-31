package ai.resourcepack.engine.core.entity;

import ai.resourcepack.engine.api.MobState;

/**
 * Which of a custom mob's animations should be playing, as arithmetic.
 *
 * <p>Free of Bukkit so the rules can be tested without a server, and so the
 * runtime only has to say what HAPPENED (it was hit, it is moving at this
 * speed) and play whatever this answers. Two rules, both from {@link MobState}:
 *
 * <ul>
 *   <li><b>A one-shot runs to its end</b> unless one of higher precedence
 *   starts. An equal one restarts it: a mob hit twice flinches twice.</li>
 *   <li><b>A loop changes only when the new one has held for
 *   {@link #SETTLE} looks in a row.</b> Speed read off two positions is noisy
 *   (a step up, a knock back, the pathfinder hesitating), and a walk cycle
 *   restarted every other tick is a mob that twitches. A one-shot ending hands
 *   straight to whatever the mob is doing now, with no wait.</li>
 * </ul>
 *
 * <p>One per mob, used only on that mob's thread.
 */
final class MobBrain {

    /** How many looks in a row a new loop needs before it takes over. */
    static final int SETTLE = 2;

    private MobState oneShot;
    private long oneShotEnds;
    private MobState loop;
    private boolean loopKnown;
    private MobState pending;
    private int pendingFor;
    private boolean dead;

    /** What should be showing now: the running one-shot, else the loop. Null is the rest pose. */
    MobState showing() {
        return oneShot != null ? oneShot : loop;
    }

    /** Whether DEATH has started; nothing else plays after it. */
    boolean dead() {
        return dead;
    }

    /**
     * Something happened that has a one-shot. The caller has already checked
     * the mob has an animation for it.
     *
     * @param lengthTicks how long it plays, at least one
     * @return whether it starts, so the caller plays it
     */
    boolean trigger(MobState state, long now, long lengthTicks) {
        if (state == null || !state.oneShot() || dead) {
            return false;
        }
        if (oneShot != null && now < oneShotEnds && oneShot.ordinal() < state.ordinal()) {
            return false;
        }
        oneShot = state;
        oneShotEnds = now + Math.max(1, lengthTicks);
        if (state == MobState.DEATH) {
            dead = true;
        }
        return true;
    }

    /**
     * One look at the mob.
     *
     * @param desired the loop its movement asks for ({@link MobState#loop}), or null for rest
     * @return whether {@link #showing()} changed, so the caller plays it
     */
    boolean tick(long now, MobState desired) {
        if (dead) {
            return false;
        }
        boolean ended = oneShot != null && now >= oneShotEnds;
        if (ended) {
            oneShot = null;
            loop = desired;
            loopKnown = true;
            pending = null;
            pendingFor = 0;
            return true;
        }
        if (!loopKnown) {
            loopKnown = true;
            loop = desired;
            return oneShot == null;
        }
        if (desired == loop) {
            pending = null;
            pendingFor = 0;
            return false;
        }
        if (desired != pending) {
            pending = desired;
            pendingFor = 1;
        } else {
            pendingFor++;
        }
        if (pendingFor < SETTLE) {
            return false;
        }
        loop = desired;
        pending = null;
        pendingFor = 0;
        // A loop that changes UNDER a one-shot is remembered, not played: the
        // one-shot hands to it when it ends.
        return oneShot == null;
    }
}
