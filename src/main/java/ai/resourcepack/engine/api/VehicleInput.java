package ai.resourcepack.engine.api;

/**
 * What a vehicle's driver is asking for, this tick.
 *
 * <p>The keys as the engine read them — so a plugin building on top of a
 * vehicle (a skateboard that pushes, a bike that wheelies, a boat with a
 * throttle lever) reads the same input the physics did, rather than asking
 * the player again and getting a different answer a tick apart.
 *
 * <p>On a server that can read the movement keys (Paper 1.21.4 and up) every
 * field is live. Elsewhere the throttle is a clicked notch, steering is the
 * driver's look and the individual keys read false; {@link #keys()} says
 * which world you are in. Sneak is never here: it dismounts.
 *
 * <p>A value, never stale: {@link Vehicle#input()} returns what the last tick
 * read, and {@link #NONE} when nobody is driving.
 */
public final class VehicleInput {

    /** Nobody at the wheel. */
    public static final VehicleInput NONE = new VehicleInput(false, 0, 0, false, false, false, false, false, false);

    private final boolean keys;
    private final double throttle;
    private final double steer;
    private final boolean forward;
    private final boolean backward;
    private final boolean left;
    private final boolean right;
    private final boolean jump;
    private final boolean sprint;
    private final boolean sneak;

    public VehicleInput(boolean keys, double throttle, double steer, boolean forward, boolean backward,
                        boolean left, boolean right, boolean jump, boolean sprint) {
        this(keys, throttle, steer, forward, backward, left, right, jump, sprint, false);
    }

    /** As above, carrying the sneak key. See {@link #sneak()}. */
    public VehicleInput(boolean keys, double throttle, double steer, boolean forward, boolean backward,
                        boolean left, boolean right, boolean jump, boolean sprint, boolean sneak) {
        this.sneak = sneak;
        this.keys = keys;
        this.throttle = throttle;
        this.steer = steer;
        this.forward = forward;
        this.backward = backward;
        this.left = left;
        this.right = right;
        this.jump = jump;
        this.sprint = sprint;
    }

    /** Whether the individual keys below mean anything on this server. */
    public boolean keys() {
        return keys;
    }

    /** -1 to 1: the back key, nothing, the forward key — or the clicked notch where keys cannot be read. */
    public double throttle() {
        return throttle;
    }

    /** -1 to 1: left to right. Zero where steering is by look. */
    public double steer() {
        return steer;
    }

    public boolean forward() {
        return forward;
    }

    public boolean backward() {
        return backward;
    }

    public boolean left() {
        return left;
    }

    public boolean right() {
        return right;
    }

    /** Space: the handbrake, the jump or the climb, by vehicle. */
    public boolean jump() {
        return jump;
    }

    /**
     * The sneak key. The engine does nothing with it either — but unlike
     * sprint, it already means something to Minecraft: <strong>sneak is how
     * you get out of a vehicle.</strong> So a plugin reading this key sees it
     * on the tick the rider is also leaving, unless it has asked to keep them
     * with {@link Vehicle#holdOccupant}. That is the whole reason both exist:
     * a skateboard wants shift to be a trick, and a trick that ends with you
     * standing in the road is not one.
     */
    public boolean sneak() {
        return sneak;
    }

    /** The sprint key. The engine does nothing with it; it is here for a plugin to give a meaning. */
    public boolean sprint() {
        return sprint;
    }

    @Override
    public String toString() {
        return "VehicleInput(throttle " + throttle + ", steer " + steer
                + (jump ? ", jump" : "") + (sprint ? ", sprint" : "") + ")";
    }
}
