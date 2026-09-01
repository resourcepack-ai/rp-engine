package ai.resourcepack.engine.core.model;

import ai.resourcepack.engine.api.AnimationSettings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a rig's animation list says: which one a trigger picks, how fast it
 * runs, whether it loops, and which parts it actually moves.
 *
 * <p>Split out of {@link RigAnimator} because none of it touches an entity.
 * Every method here is a question about a {@link RigStore.Rig} and its
 * {@link RigStore.Animation}s, answered from the manifest alone — which is why
 * {@code AnimationStateTest} can drive the whole of it from a JSON fixture with
 * no server in the picture, and why it was the part of the animator that was
 * already static.
 *
 * <p>The trigger and mode names live here too. They are the rig format's
 * vocabulary rather than the animator's: a manifest written by hand and one
 * written by the editor both spell {@code right_click} this way, and the
 * animator is only one of the things that reads them.
 */
final class RigAnimations {

    private RigAnimations() {
    }

    static final String TRIGGER_LOOP = "loop";

    static final String TRIGGER_RIGHT_CLICK = "right_click";

    static final String TRIGGER_LEFT_CLICK = "left_click";

    static final String TRIGGER_RANGE = "range";

    static final String TRIGGER_PLACE = "place";

    /** What an animation does when it reaches its end. */
    static final String MODE_LOOP = "loop";

    static final String MODE_HOLD = "hold";

    static final String MODE_ONCE = "once";

    static boolean hasAnimationProgram(RigStore.Part part) {
        return part != null && part.program != null && !part.program.isEmpty();
    }

    static int findAnimationIndex(RigStore.Rig rig, String triggerType) {
        return findAnimationIndex(rig, triggerType, null);
    }

    /**
     * The animation this event should start, preferring the placement's own
     * choice (see {@link ModelPlacementListener}). Several animations can
     * claim one trigger and only one may run, so without a choice the first
     * in rig order wins and the second is unreachable.
     *
     * Falling back to the first claimant is deliberate: a choice names ONE
     * animation, and a model's other triggers usually belong to different
     * ones, so picking "Close" for right-click leaves the idle loop alone.
     */
    static int findAnimationIndex(RigStore.Rig rig, String triggerType, String chosenName) {
        if (rig == null || rig.animations == null || triggerType == null) return -1;
        if (chosenName != null && !chosenName.isEmpty()) {
            for (int i = 0; i < rig.animations.size(); i++) {
                RigStore.Animation animation = rig.animations.get(i);
                if (animation != null && chosenName.equals(animation.name) && hasTrigger(animation, triggerType)) {
                    return i;
                }
            }
        }
        // Highest priority among the claimants, and rig order between equals
        // — which is exactly what this did before priority existed, since
        // every animation then had the same one.
        int best = -1;
        for (int i = 0; i < rig.animations.size(); i++) {
            if (!hasTrigger(rig.animations.get(i), triggerType)) continue;
            if (best < 0 || priorityOf(rig.animations.get(i)) > priorityOf(rig.animations.get(best))) {
                best = i;
            }
        }
        return best;
    }

    /**
     * The animation with this name, and the resolver the code API runs on.
     *
     * **It asks nothing about triggers**, which is the whole difference from
     * {@link #findAnimationIndex} and the whole point of the API. A model's
     * animations are all shipped in the rig manifest whether or not they
     * claim a trigger — Studio says of an empty trigger list that it
     * "deliberately makes it editor-only" — so those have been
     * sitting on every synced server unreachable, because until now the only
     * way in was by trigger. This is the way in.
     *
     * Names are free text in the editor and nothing makes them unique, so
     * the first match in model order wins: the same rule findAnimationIndex
     * already uses when several animations claim one trigger. An exact match
     * is preferred over a case-insensitive one, so a model that really does
     * have both "Wave" and "wave" keeps both addressable; without that the
     * second would be unreachable, which is the bug this method exists to
     * fix, one level down.
     */
    static int findAnimationIndexByName(RigStore.Rig rig, String name) {
        if (rig == null || rig.animations == null || name == null || name.isEmpty()) return -1;
        int looseMatch = -1;
        for (int i = 0; i < rig.animations.size(); i++) {
            RigStore.Animation animation = rig.animations.get(i);
            if (animation == null || animation.name == null) continue;
            if (name.equals(animation.name)) return i;
            if (looseMatch < 0 && name.equalsIgnoreCase(animation.name)) looseMatch = i;
        }
        return looseMatch;
    }

    /**
     * Every animation this model has, in model order — the list a caller
     * picks a name out of. Unnamed entries are dropped rather than reported
     * as empty strings, since a name is how this API addresses them at all.
     */
    static List<String> animationNames(RigStore.Rig rig) {
        if (rig == null || rig.animations == null) return Collections.emptyList();
        List<String> names = new java.util.ArrayList<>(rig.animations.size());
        for (RigStore.Animation animation : rig.animations) {
            if (animation != null && animation.name != null && !animation.name.isEmpty()) names.add(animation.name);
        }
        return Collections.unmodifiableList(names);
    }

    /**
     * Resolves the pose source without causing playback. With no active
     * event, only a loop may animate. A completed one-shot returns to the
     * resting transform unless a separate loop animation is available.
     */
    static int playbackAnimationIndex(RigStore.Rig rig, Integer activeIndex, double elapsed) {
        return playbackAnimationIndex(rig, activeIndex, elapsed, null);
    }

    static int playbackAnimationIndex(RigStore.Rig rig, Integer activeIndex, double elapsed, String chosenName) {
        RigStore.Animation active = animationAt(rig, activeIndex);
        // A held animation never runs out: it stops on its last frame and
        // stays there until something else is asked for. That is what makes
        // an open door stay open rather than swinging shut on its own.
        if (active != null && (loops(active) || holds(active)
                || elapsed * speedOf(active) <= Math.max(0, active.length))) {
            return activeIndex;
        }
        return findAnimationIndex(rig, TRIGGER_LOOP, chosenName);
    }

    static boolean shouldUpdatePose(int playbackIndex, Integer activeIndex, boolean forceRestPose) {
        return playbackIndex >= 0 || activeIndex != null || forceRestPose;
    }

    static boolean hasTrigger(RigStore.Animation animation, String triggerType) {
        if (animation == null || triggerType == null) return false;
        // Backward compatibility with manifests created before the trigger
        // list: loops stayed active; one-shots ran once when placed.
        if (animation.triggers == null) {
            return triggerType.equals(animation.loop ? TRIGGER_LOOP : TRIGGER_PLACE);
        }
        for (RigStore.Trigger trigger : animation.triggers) {
            if (trigger != null && triggerType.equals(trigger.type)) return true;
        }
        return false;
    }

    static double animationTime(RigStore.Animation animation, double elapsed) {
        if (animation.length <= 0) return 0;
        double at = elapsed * speedOf(animation);
        return loops(animation) ? at % animation.length : Math.min(at, animation.length);
    }

    static boolean loops(RigStore.Animation animation) {
        if (MODE_LOOP.equals(animation.mode)) return true;
        if (MODE_HOLD.equals(animation.mode) || MODE_ONCE.equals(animation.mode)) return false;
        return animation.triggers == null ? animation.loop : hasTrigger(animation, TRIGGER_LOOP);
    }

    /**
     * How fast it plays. Absent or nonsensical is 1 rather than a refusal:
     * every manifest written before this has no speed at all, and a rig that
     * froze because somebody typed a zero would be worse than one that runs
     * at the speed it was authored.
     */
    static double speedOf(RigStore.Animation animation) {
        return animation == null || animation.speed <= 0 ? 1 : animation.speed;
    }

    /** Higher wins. Equal falls back to rig order, as it always did. */
    static int priorityOf(RigStore.Animation animation) {
        return animation == null ? 0 : animation.priority;
    }

    /** Seconds of ease in and out. 0, the old behaviour, is a hard cut. */
    static double blendOf(RigStore.Animation animation) {
        return animation == null || animation.blend <= 0 ? 0 : Math.min(5, animation.blend);
    }

    /** Whether it stops on its last frame instead of going back to rest. */
    static boolean holds(RigStore.Animation animation) {
        return animation != null && MODE_HOLD.equals(animation.mode);
    }

    /**
     * The rule itself, with the entity read taken out so it can be pinned by a
     * test: is a one-shot at {@code wantIndex} still running?
     *
     * A loop is never "mid" anything — it has no end to be before, so asking
     * for one always restarts its phase. Ticks rather than seconds because
     * that's what the clock on the display is in.
     */
    static boolean isMidOneShot(RigStore.Animation animation, Integer activeIndex, Long startedTick,
            int wantIndex, long now) {
        if (animation == null || loops(animation)) return false;
        return activeIndex != null && activeIndex == wantIndex && startedTick != null
            && now - startedTick <= Math.ceil(animation.length * 20.0);
    }

    /**
     * Whether a rig has anything that can move, which is what separates a real
     * rig from a model that places as one still display. The still one still
     * gets a hitbox and a model id, so this is the only thing telling them
     * apart — see the guard in {@link #play}.
     */
    static boolean anyPartAnimates(RigStore.Rig rig) {
        if (rig == null || rig.parts == null) return false;
        for (RigStore.Part part : rig.parts) {
            if (hasAnimationProgram(part)) return true;
        }
        return false;
    }

    static RigStore.Animation animationAt(RigStore.Rig rig, Integer index) {
        if (rig == null || rig.animations == null || index == null || index < 0 || index >= rig.animations.size()) return null;
        return rig.animations.get(index);
    }

    static RigStore.Trigger triggerOf(RigStore.Animation animation, String triggerType) {
        if (animation == null || animation.triggers == null) return null;
        for (RigStore.Trigger trigger : animation.triggers) {
            if (trigger != null && triggerType.equals(trigger.type)) return trigger;
        }
        return null;
    }

    /**
     * How strongly an animation applies. Absent is full strength, which is
     * what every manifest written before weights means.
     */
    static float weightOf(RigStore.Animation animation) {
        if (animation == null || animation.weight <= 0) {
            return 1f;
        }
        return (float) Math.min(1, animation.weight);
    }

    /**
     * Whether this animation touches this part at all.
     *
     * <p>A bone mask names bones, and a part belongs to one \u2014 but masking a
     * torso has to reach the arms inside it, so the match is against the
     * part's whole LINEAGE rather than its own name. That is why the lineage
     * is on the manifest at all.
     *
     * <p>No mask means every part, which is what an animation without one has
     * always done.
     */
    static boolean moves(RigStore.Animation animation, RigStore.Part part) {
        if (animation == null || animation.bones == null || animation.bones.length == 0) {
            return true;
        }
        if (part == null || part.bones == null) {
            // A loose cube, or a part from a manifest older than lineages.
            // Left out rather than in: a mask is an author saying "only
            // these", and answering "and also everything I cannot identify"
            // is the opposite of what they asked for.
            return false;
        }
        for (String wanted : animation.bones) {
            if (wanted == null) {
                continue;
            }
            for (String mine : part.bones) {
                if (wanted.equalsIgnoreCase(mine)) {
                    return true;
                }
            }
        }
        return false;
    }
}
