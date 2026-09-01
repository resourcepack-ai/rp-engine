package ai.resourcepack.engine.core.model;

import ai.resourcepack.engine.api.BoneBehaviour;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

/**
 * Turning a bone marked as a head toward whatever the rig is riding is looking
 * at.
 *
 * <p>Split out of {@link RigAnimator} because it is one bone behaviour rather
 * than part of posing: the animator composes a part's animation and then, if
 * that part is a head and the rig is bound to a living entity, this appends a
 * look. It read no field of the animator, so it was already static in
 * everything but the keyword.
 *
 * <p>The neck limits are the point of the class. A head is a bone like any
 * other and will happily rotate to any angle a matrix can express, which on a
 * model of a person is the horror-film result; clamping to a real neck's range
 * is what keeps it looking like a creature following you.
 */
final class HeadLook {

    private HeadLook() {
    }

    /**
     * How far a neck turns, in degrees. Vanilla's own for a player's head,
     * and near enough for everything else — the alternative is a mob whose
     * head is on backwards, which is what the raw numbers say.
     */
    private static final float MAX_NECK_YAW = 75f;

    private static final float MAX_NECK_PITCH = 89f;

    /**
     * Turns a head bone toward whatever its host is looking at.
     *
     * <p>Only on a model worn by an entity: a placed statue has nothing to
     * look with, and a head that swivelled to follow a passing player would be
     * a different feature and a much creepier one.
     *
     * <p><strong>The yaw is a difference, not an angle.</strong> The whole rig
     * already turns with the body (see {@code yawOf}), so applying the head's
     * absolute yaw here would turn it twice and leave a mob whose head faces
     * backwards while it walks. What is left over is exactly how far the head
     * is turned relative to the shoulders, which is what a neck does.
     *
     * <p>Clamped the way vanilla clamps a neck. Without it a mob looking
     * behind itself gets its head on backwards, which is what the numbers
     * literally say and not what anybody wants to see.
     */
    static void applyTo(Matrix4f m, RigStore.Part part, ItemDisplay display) {
        if (part.behaviour == null || part.pivot == null || part.pivot.length != 3) return;
        if (!isHeadBone(part)) return;
        Entity host = display.getVehicle();
        if (!(host instanceof LivingEntity)) return;

        float[] look = lookOf((LivingEntity) host);
        float yaw = clamp(wrap(look[0]), MAX_NECK_YAW);
        float pitch = clamp(look[1], MAX_NECK_PITCH);
        if (yaw == 0f && pitch == 0f) return;

        float px = (part.pivot[0] - 8f) / 16f;
        float py = (part.pivot[1] - 8f) / 16f;
        float pz = (part.pivot[2] - 8f) / 16f;
        m.translate(px, py, pz);
        // Yaw negated for the same reason the placement yaw is: the rig's
        // space turns the other way round from the world's.
        m.rotateY((float) Math.toRadians(-yaw));
        m.rotateX((float) Math.toRadians(pitch));
        m.translate(-px, -py, -pz);
    }

    /**
     * How far the head is turned from the body: yaw relative to the shoulders,
     * then pitch.
     *
     * <p><strong>Bukkit does not expose a mob's head yaw.</strong>
     * {@code getEyeLocation()} carries the entity's own yaw, which IS the body
     * yaw for everything that is not a player — so the obvious implementation
     * subtracts a number from itself, gets zero, and quietly does nothing but
     * pitch while looking like it does more.
     *
     * <p>So the answer is taken from what the mob is actually looking AT. A
     * mob with a target is turning its head toward it, which is both the thing
     * a boss should visibly do and the only version of this the API can
     * honestly support. A mob with no target faces the way its body does,
     * which is exactly right: an idle mob's head is straight ahead.
     */
    private static float[] lookOf(LivingEntity host) {
        Location eyes = host.getEyeLocation();
        float pitch = eyes.getPitch();
        if (!(host instanceof Mob)) {
            // A player: their yaw already IS their head yaw, and the body is
            // drawn from it, so there is nothing left over.
            return new float[]{0f, pitch};
        }
        LivingEntity target = ((Mob) host).getTarget();
        if (target == null || !target.getWorld().equals(host.getWorld())) {
            return new float[]{0f, pitch};
        }
        Vector to = target.getEyeLocation().toVector().subtract(eyes.toVector());
        if (to.lengthSquared() < 1.0E-4) {
            return new float[]{0f, pitch};
        }
        float wanted = (float) Math.toDegrees(Math.atan2(-to.getX(), to.getZ()));
        float flat = (float) Math.sqrt(to.getX() * to.getX() + to.getZ() * to.getZ());
        return new float[]{
                wanted - host.getLocation().getYaw(),
                (float) Math.toDegrees(Math.atan2(-to.getY(), flat))};
    }

    private static boolean isHeadBone(RigStore.Part part) {
        BoneBehaviour behaviour = behaviourOf(part);
        return behaviour.isHead();
    }

    /** A part's behaviour, or {@link BoneBehaviour#NONE} on an older manifest. */
    static BoneBehaviour behaviourOf(RigStore.Part part) {
        if (part == null || part.behaviour == null) return BoneBehaviour.NONE;
        for (BoneBehaviour behaviour : BoneBehaviour.values()) {
            if (behaviour.name().equalsIgnoreCase(part.behaviour)) return behaviour;
        }
        return BoneBehaviour.NONE;
    }

    /** Into -180..180, so a turn across north is a small number and not 350 degrees. */
    static float wrap(float degrees) {
        float wrapped = degrees % 360f;
        if (wrapped > 180f) wrapped -= 360f;
        if (wrapped < -180f) wrapped += 360f;
        return wrapped;
    }

    static float clamp(float degrees, float limit) {
        return Math.max(-limit, Math.min(limit, degrees));
    }
}
