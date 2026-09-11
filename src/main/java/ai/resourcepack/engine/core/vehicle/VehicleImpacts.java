package ai.resourcepack.engine.core.vehicle;

/**
 * What happens when two vehicles hit each other, as arithmetic.
 *
 * <p><strong>Pure, on the same terms as {@link VehiclePhysics}</strong> and for
 * the same reason: nothing here touches Bukkit, reads a block or knows what a
 * world is. It takes two boxes with a position, a heading, a velocity, a spin
 * and a mass, and answers two questions — are they overlapping, and if so what
 * does each of them come away with. Finding the pairs, and refusing a shove
 * that would put a car inside a wall, is the runtime's job.
 *
 * <h2>The model</h2>
 *
 * <p>An impulse between two rigid bodies, in two dimensions, which is the
 * ordinary way this is done and is worth stating because the obvious
 * alternative — push both apart along the line between their centres, by an
 * amount that reads well — is a rule rather than a model and behaves like one.
 * It has no answer for a lorry meeting a bicycle, none for a car clipped on the
 * corner rather than square in the back, and it cannot tell a nudge in a car
 * park from a head-on at forty.
 *
 * <p>Four steps, in order:
 *
 * <ul>
 *   <li><strong>The contact.</strong> Two rectangles, each turned to its own
 *   heading, are separated or they are not: {@link #contact} is the separating
 *   axis test over the four face directions the pair has between them. The
 *   shallowest overlap is the one they are actually touching along, and its
 *   direction is the collision NORMAL — which is what makes a corner clip and a
 *   rear-ending two different events rather than the same push at two
 *   speeds.</li>
 *   <li><strong>The point.</strong> Where along the normal's face they met, by
 *   clipping the incident edge to the reference one. This is the step it would
 *   be tempting to skip — take the deepest corner and be done — and skipping it
 *   is a square-on rear-ender that spins both cars, because one corner is
 *   always a shade deeper than the other. See {@link #point}.</li>
 *   <li><strong>The impulse.</strong> The relative velocity AT THAT POINT
 *   (which is not the relative velocity of the two centres, once either is
 *   turning) resolved along the normal, and an impulse that removes it, split
 *   between the two by mass and by how far off-centre the point is. A tangential
 *   impulse follows it, bounded by friction, and that is what makes a glancing
 *   blow drag the other vehicle along instead of sliding frictionlessly off
 *   it.</li>
 *   <li><strong>The separation.</strong> A fraction of whatever overlap is left,
 *   shared out by mass — see {@link #CORRECTION}. Velocity alone never
 *   un-overlaps anything within the tick it happened in, and two vehicles left
 *   inside each other are the one failure of this whole file that a player
 *   cannot drive out of.</li>
 * </ul>
 *
 * <h2>What it deliberately does not do</h2>
 *
 * <p><strong>It is flat.</strong> Everything here is x and z; the vertical is a
 * yes/no overlap test the runtime makes before it asks. Two aircraft that meet
 * exchange the horizontal half of their momentum and keep their climb, which is
 * wrong and is a great deal less wrong than the alternative — a vertical
 * impulse would have to argue with the ground, the buoyancy and the flight
 * model, none of which is expecting one.
 *
 * <p><strong>And it is discrete.</strong> The boxes are tested where they ended
 * the tick, not swept along the way they travelled, on the same argument
 * {@code VehicleRuntime.blocked} makes for blocks: a sweep is a much bigger
 * piece of work, this runs for every moving vehicle, and the failure — two
 * aircraft at sixty blocks a second passing through each other — is rarer and
 * far less visible than the cost.
 */
public final class VehicleImpacts {

    /**
     * How much of the closing speed comes back as a bounce.
     *
     * <p>Low, because cars are not billiard balls: a real impact spends nearly
     * all of it on the bodywork. Enough that a hard hit visibly rebounds rather
     * than reading as two vehicles sticking together.
     */
    public static final double RESTITUTION = 0.15;

    /**
     * Below this closing speed, in blocks per second, nothing bounces at all.
     *
     * <p>The standard cure for the standard failure, and it is worth naming
     * what that failure is: a resting contact is never exactly resting. The
     * separation below pushes two touching vehicles apart by a hair, gravity or
     * a throttle brings them back, and with any restitution at all that
     * hair-sized approach comes back as a hair-sized bounce — twenty times a
     * second, for as long as they are touching. Which is a car park that
     * hums.
     */
    public static final double RESTITUTION_SPEED = 1.5;

    /**
     * How much of the normal impulse the tangential one may be, Coulomb's way.
     *
     * <p>This is the whole difference between a glancing blow and a graze. At
     * zero, two vehicles meeting at a shallow angle slide off each other with
     * nothing exchanged along the paintwork; at this, the faster one drags the
     * slower one a little way with it, which is what side-swiping something
     * looks like.
     */
    public static final double FRICTION = 0.4;

    /**
     * Overlap this deep, in blocks, is left alone.
     *
     * <p>Contact is not a knife edge — the boxes are approximations of art that
     * does not have a boundary either — and a separation that chases zero is
     * one that never stops running. A fiftieth of a block is invisible and is
     * enough for the arithmetic to settle in.
     */
    public static final double SLOP = 0.02;

    /**
     * How much of the remaining overlap is taken out per tick.
     *
     * <p>Not all of it. Taking the whole overlap at once makes the separation
     * an instantaneous teleport that fights the impulse — the two vehicles are
     * moved apart, arrive with their velocities already resolved, and the next
     * tick brings them back together for another full correction. Half is
     * quick enough to be over in three ticks and soft enough to look like the
     * bodywork giving.
     */
    public static final double CORRECTION = 0.5;

    /**
     * As far as one tick's separation may move a vehicle, in blocks.
     *
     * <p>The guard on the case this cannot otherwise survive: two vehicles that
     * arrive deeply overlapped, because one was spawned inside the other or a
     * chunk loaded them on top of each other. Without a cap that is a single
     * frame in which both are flung several blocks; with one it is a second of
     * them easing apart.
     */
    public static final double MAX_CORRECTION = 0.25;

    /**
     * As much spin as one impact may add, in degrees per second.
     *
     * <p>A cap rather than a scale, so an ordinary hit is untouched and only
     * the arithmetic's bad days are bounded — a contact point found at a corner
     * with a very small moment of inertia behind it. Note what happens to the
     * spin that survives: {@link VehiclePhysics#YAW_RESPONSE} pulls the body's
     * yaw rate back toward what the wheels are asking for within a few ticks,
     * because tyres resist being turned. So the visible slew after a broadside
     * is mostly not this — it is the SLIP the impulse leaves behind, which the
     * handling model already turns into rotation through
     * {@link VehiclePhysics#DRIFT_YAW}. That is the intended path, and it is
     * why an impact needs no special case anywhere in the handling model.
     */
    public static final double MAX_SPIN = 200;

    /**
     * How far back, in seconds, an overlap may have started and still be taken
     * as the way the two came in. See {@link #contact}.
     */
    public static final double ENTRY_WINDOW = 0.2;

    private VehicleImpacts() {
    }

    /**
     * One vehicle, as the impulse solver needs it.
     *
     * <p>Immutable and free of everything else a vehicle is. A body is built
     * from a {@link VehiclePhysics.State} and a {@link ai.resourcepack.engine.api.VehicleInfo}
     * at the point of asking, and the answer is handed back the same way, so
     * nothing in here can half-apply.
     */
    public static final class Body {

        private final double x;
        private final double z;
        private final double yaw;
        private final double halfWidth;
        private final double halfLength;
        private final double vx;
        private final double vz;
        private final double spin;
        private final double mass;
        private final double inertia;

        /**
         * @param x,z        where the box is centred
         * @param yaw        its heading, Minecraft's way round
         * @param width      side to side, blocks
         * @param length     front to back, blocks
         * @param vx,vz      its velocity over the ground, blocks per second
         * @param spin       its yaw rate, degrees per second, clockwise positive
         * @param mass       anything positive; only the RATIO between two bodies
         *                   is ever read, so the unit is the caller's business
         */
        public Body(double x, double z, double yaw, double width, double length,
                    double vx, double vz, double spin, double mass) {
            this.x = x;
            this.z = z;
            this.yaw = yaw;
            this.halfWidth = Math.max(0.05, width / 2);
            this.halfLength = Math.max(0.05, length / 2);
            this.vx = vx;
            this.vz = vz;
            this.spin = spin;
            this.mass = Math.max(0.01, mass);
            // A rectangular plate about its centre. The shape matters more than
            // the constant: it is what makes a long vehicle hard to spin and a
            // short one easy, so a bus clipped on the corner shrugs and a go-kart
            // is sent round.
            this.inertia = Math.max(1e-6,
                    this.mass * (4 * this.halfWidth * this.halfWidth + 4 * this.halfLength * this.halfLength) / 12);
        }

        public double x() {
            return x;
        }

        public double z() {
            return z;
        }

        public double mass() {
            return mass;
        }

        public double yaw() {
            return yaw;
        }

        public double vx() {
            return vx;
        }

        public double vz() {
            return vz;
        }

        /** How far the box reaches from its centre, whichever way it is turned. */
        public double radius() {
            return Math.hypot(halfWidth, halfLength);
        }

        private double[] right() {
            return VehiclePhysics.right(yaw);
        }

        private double[] forward() {
            return VehiclePhysics.forward(yaw);
        }

        /** How far this box reaches along {@code axis}, which must be a unit vector. */
        private double reach(double[] axis) {
            double[] u = right();
            double[] w = forward();
            return halfWidth * Math.abs(u[0] * axis[0] + u[1] * axis[1])
                    + halfLength * Math.abs(w[0] * axis[0] + w[1] * axis[1]);
        }
    }

    /**
     * Where two boxes are touching: the direction to separate them along, how
     * far into each other they are, and the point they met at.
     */
    public static final class Contact {

        private final double nx;
        private final double nz;
        private final double depth;
        private final double px;
        private final double pz;

        Contact(double nx, double nz, double depth, double px, double pz) {
            this.nx = nx;
            this.nz = nz;
            this.depth = depth;
            this.px = px;
            this.pz = pz;
        }

        /** The normal's x, pointing from the first body toward the second. */
        public double nx() {
            return nx;
        }

        /** The normal's z, the same way round. */
        public double nz() {
            return nz;
        }

        /** How far into each other they are, in blocks. Always positive. */
        public double depth() {
            return depth;
        }

        public double px() {
            return px;
        }

        public double pz() {
            return pz;
        }
    }

    /**
     * What one body comes away with: a change of velocity, a change of spin,
     * and how far it is to be moved to un-overlap.
     *
     * <p>Deltas rather than new values, because the runtime applies the
     * separation and the velocity through two different doors — one writes a
     * position and has to ask the world's permission first, the other writes a
     * {@link VehiclePhysics.State} and needs nobody's.
     */
    public static final class Impulse {

        private final double dvx;
        private final double dvz;
        private final double dspin;
        private final double pushX;
        private final double pushZ;

        Impulse(double dvx, double dvz, double dspin, double pushX, double pushZ) {
            this.dvx = dvx;
            this.dvz = dvz;
            this.dspin = dspin;
            this.pushX = pushX;
            this.pushZ = pushZ;
        }

        public double dvx() {
            return dvx;
        }

        public double dvz() {
            return dvz;
        }

        /** Degrees per second to add to the yaw rate. */
        public double dspin() {
            return dspin;
        }

        public double pushX() {
            return pushX;
        }

        public double pushZ() {
            return pushZ;
        }

        /** Whether this does anything at all. */
        public boolean any() {
            return dvx != 0 || dvz != 0 || dspin != 0 || pushX != 0 || pushZ != 0;
        }
    }

    /** Both halves of one collision. {@link #a} is the first body's. */
    public static final class Exchange {

        private final Impulse a;
        private final Impulse b;
        private final double closingSpeed;
        private final double normalImpulse;

        Exchange(Impulse a, Impulse b, double closingSpeed, double normalImpulse) {
            this.a = a;
            this.b = b;
            this.closingSpeed = closingSpeed;
            this.normalImpulse = normalImpulse;
        }

        public Impulse a() {
            return a;
        }

        public Impulse b() {
            return b;
        }

        /** Closing speed at the contact point before the collision, blocks per second. */
        public double closingSpeed() {
            return closingSpeed;
        }

        /** Magnitude of the normal impulse exchanged by the pair. */
        public double normalImpulse() {
            return normalImpulse;
        }
    }

    /**
     * Whether the two boxes overlap, and where — or null if they do not.
     *
     * <p>The separating axis test. Two convex shapes are apart if and only if
     * there is SOME direction along which their shadows do not overlap, and for
     * two rectangles the only directions worth trying are the four their faces
     * point in. Any of them clear and they are apart, which is the answer this
     * gives for almost every pair it is ever handed and is why the cheap exit
     * comes first.
     *
     * <p>When none is clear they are touching, and the axis to separate them
     * along is <strong>the one they most recently came in through</strong>. For
     * a pair that have only just met that is also the shallowest overlap, and
     * the shallowest is what this took at first — but the two part company at
     * speed, and where they part the shallowest is badly wrong. Two cars
     * meeting head-on at sixteen blocks a second each close three blocks in the
     * tick they touch, so the first time they are asked they are already most
     * of a car deep along their length — deeper than they are WIDE — and the
     * shortest way out is sideways. Which is a head-on that squirts both cars
     * past each other and leaves them driving away back to back.
     *
     * <p>So each overlap is divided by how fast the pair are closing along that
     * axis, which says how long ago the overlap on it began, and the axis that
     * began most recently wins. An axis they are not closing on at all has no
     * answer and is not a candidate; nor is one whose overlap is older than
     * {@link #ENTRY_WINDOW}, which is what keeps a pair sitting side by side —
     * overlapping along their whole length for as long as they are alongside —
     * from being separated end to end. With no candidate at all, which is every
     * resting contact, the shallowest overlap stands.
     */
    public static Contact contact(Body a, Body b) {
        double dx = b.x - a.x;
        double dz = b.z - a.z;

        // A circle test first. Nearly every pair this is called with is two
        // vehicles a few blocks apart and the four projections below are wasted
        // on them.
        double reach = a.radius() + b.radius();
        if (dx * dx + dz * dz > reach * reach) {
            return null;
        }

        double relX = b.vx - a.vx;
        double relZ = b.vz - a.vz;

        double[][] axes = {a.right(), a.forward(), b.right(), b.forward()};
        double bestOverlap = Double.MAX_VALUE;
        int bestAxis = -1;
        double bestSign = 1;
        double bestEntry = Double.MAX_VALUE;
        double entryOverlap = 0;
        int entryAxis = -1;
        double entrySign = 1;
        for (int i = 0; i < axes.length; i++) {
            double[] axis = axes[i];
            double gap = dx * axis[0] + dz * axis[1];
            double overlap = a.reach(axis) + b.reach(axis) - Math.abs(gap);
            if (overlap <= 0) {
                return null;
            }
            // Pointing from a toward b. A gap of exactly zero is two
            // concentric boxes and has no direction of its own; the axis
            // as it stands will do, and the separation will pick a side.
            double sign = gap < 0 ? -1 : 1;
            if (overlap < bestOverlap) {
                bestOverlap = overlap;
                bestAxis = i;
                bestSign = sign;
            }
            // How fast b is coming at a along this axis, and therefore how long
            // ago the overlap on it opened.
            double closing = -(relX * axis[0] + relZ * axis[1]) * sign;
            if (closing > 1e-6) {
                double entry = overlap / closing;
                if (entry <= ENTRY_WINDOW && entry < bestEntry) {
                    bestEntry = entry;
                    entryOverlap = overlap;
                    entryAxis = i;
                    entrySign = sign;
                }
            }
        }

        int axis = entryAxis >= 0 ? entryAxis : bestAxis;
        double sign = entryAxis >= 0 ? entrySign : bestSign;
        double depth = entryAxis >= 0 ? entryOverlap : bestOverlap;
        double nx = axes[axis][0] * sign;
        double nz = axes[axis][1] * sign;
        // Whose face the normal belongs to decides which box is the REFERENCE —
        // the one holding the flat surface — and which is the INCIDENT one,
        // poking into it. The first two axes are a's.
        double[] where = axis < 2
                ? point(a, b, nx, nz)
                // From b's side the surface faces the other way, and so does the
                // edge being clipped to it.
                : point(b, a, -nx, -nz);
        return new Contact(nx, nz, depth, where[0], where[1]);
    }

    /**
     * Where the two boxes met, given that {@code reference}'s face is the flat
     * one and {@code normal} points out of it toward {@code incident}.
     *
     * <p>The incident box's nearest EDGE is clipped to the width of the
     * reference face and the middle of what is left is the answer. Which is the
     * whole of why a rear-ending does not spin anybody: the front edge of the
     * car behind lies entirely within the back of the car in front, so the clip
     * takes nothing off, the middle of the edge is on the centre line, and an
     * impulse through the centre line is a push with no turn in it. Clip that
     * same edge down to a hand's width because only a corner is inside, and the
     * middle of THAT is off to one side, and the same impulse spins the car —
     * which is what being clipped on the corner does.
     *
     * @return the point, x then z
     */
    static double[] point(Body reference, Body incident, double normal0, double normal1) {
        // The incident box's face that most nearly faces back down the normal.
        double[] u = incident.right();
        double[] w = incident.forward();
        double alongU = -(u[0] * normal0 + u[1] * normal1);
        double alongW = -(w[0] * normal0 + w[1] * normal1);
        double[] out;
        double outReach;
        double[] edge;
        double edgeReach;
        if (Math.abs(alongU) >= Math.abs(alongW)) {
            out = u;
            outReach = incident.halfWidth * Math.signum(alongU == 0 ? 1 : alongU);
            edge = w;
            edgeReach = incident.halfLength;
        } else {
            out = w;
            outReach = incident.halfLength * Math.signum(alongW == 0 ? 1 : alongW);
            edge = u;
            edgeReach = incident.halfWidth;
        }
        double faceX = incident.x + out[0] * outReach;
        double faceZ = incident.z + out[1] * outReach;

        // Along the face, which is the normal turned a quarter turn. The two
        // ends of the incident edge, and how far the reference face runs.
        double tx = -normal1;
        double tz = normal0;
        double[] tangent = {tx, tz};
        double middle = faceX * tx + faceZ * tz;
        double half = Math.abs(edge[0] * tx + edge[1] * tz) * edgeReach;
        double low = middle - half;
        double high = middle + half;

        double centre = reference.x * tx + reference.z * tz;
        double span = reference.reach(tangent);
        low = Math.max(low, centre - span);
        high = Math.min(high, centre + span);
        // The separating axis test passed, so the two shadows on this tangent
        // do overlap and low <= high. Belt and braces: a clip that came out
        // inside out would put the contact somewhere neither box is.
        double at = low <= high ? (low + high) / 2 : middle;

        // Back onto the incident edge at that point along it. The edge runs
        // along `edge`, so stepping from its middle by the shortfall in
        // tangent units, divided by how much of a step along the edge that is
        // worth — zero when the edge is perpendicular to the tangent, which
        // cannot happen here because it is the face's own direction.
        double rate = edge[0] * tx + edge[1] * tz;
        double step = Math.abs(rate) < 1e-9 ? 0
                : Math.max(-edgeReach, Math.min(edgeReach, (at - middle) / rate));
        return new double[] {faceX + edge[0] * step, faceZ + edge[1] * step};
    }

    /**
     * What the two bodies come away with.
     *
     * <p>Never null: two bodies already moving apart get no impulse — hitting
     * something twice for one collision is how a vehicle ends up shot across
     * the map — but they still get the separation, because an overlap that is
     * being driven out of is still an overlap while it lasts.
     */
    public static Exchange resolve(Body a, Body b, Contact c) {
        double nx = c.nx;
        double nz = c.nz;
        // From each centre to the point they are touching at. Everything
        // rotational below is this pair of arms and nothing else.
        double rax = c.px - a.x;
        double raz = c.pz - a.z;
        double rbx = c.px - b.x;
        double rbz = c.pz - b.z;

        double dvax = 0;
        double dvaz = 0;
        double dspa = 0;
        double dvbx = 0;
        double dvbz = 0;
        double dspb = 0;
        double normalImpulse = 0;

        double[] relative = relative(a, b, rax, raz, rbx, rbz);
        double closing = relative[0] * nx + relative[1] * nz;
        if (closing < 0) {
            // A turning body's edge is not moving at the speed its centre is,
            // and this is where that shows up: `cross` is how much of the
            // point's motion a spin is responsible for, and it appears both in
            // what the impulse has to overcome and in what the impulse costs.
            double crossA = rax * nz - raz * nx;
            double crossB = rbx * nz - rbz * nx;
            double share = 1 / a.mass + 1 / b.mass
                    + crossA * crossA / a.inertia + crossB * crossB / b.inertia;
            // No bounce out of a touch, only out of a hit. See RESTITUTION_SPEED.
            double bounce = -closing < RESTITUTION_SPEED ? 0 : RESTITUTION;
            double j = -(1 + bounce) * closing / share;
            normalImpulse = j;

            dvax -= j * nx / a.mass;
            dvaz -= j * nz / a.mass;
            dspa -= Math.toDegrees(j * crossA / a.inertia);
            dvbx += j * nx / b.mass;
            dvbz += j * nz / b.mass;
            dspb += Math.toDegrees(j * crossB / b.inertia);

            // And now along the paintwork. Measured on the velocity the normal
            // impulse LEFT, not the one it found, so the two do not both answer
            // for the same motion.
            double tx = -nz;
            double tz = nx;
            double[] after = relative(a, b, rax, raz, rbx, rbz,
                    dvax, dvaz, dspa, dvbx, dvbz, dspb);
            double sliding = after[0] * tx + after[1] * tz;
            double crossAt = rax * tz - raz * tx;
            double crossBt = rbx * tz - rbz * tx;
            double shareT = 1 / a.mass + 1 / b.mass
                    + crossAt * crossAt / a.inertia + crossBt * crossBt / b.inertia;
            double jt = -sliding / shareT;
            // Coulomb: the paintwork can only hold so much sideways before it
            // gives up and lets the two slide. Without the clamp a shallow
            // graze at speed would stop both vehicles dead.
            double limit = FRICTION * Math.abs(j);
            jt = Math.max(-limit, Math.min(limit, jt));

            dvax -= jt * tx / a.mass;
            dvaz -= jt * tz / a.mass;
            dspa -= Math.toDegrees(jt * crossAt / a.inertia);
            dvbx += jt * tx / b.mass;
            dvbz += jt * tz / b.mass;
            dspb += Math.toDegrees(jt * crossBt / b.inertia);
        }

        double push = Math.min(MAX_CORRECTION, Math.max(0, c.depth - SLOP) * CORRECTION);
        // Shared out by how hard each is to move, so a lorry parked on a moped
        // shifts a moped's worth and the moped shifts the rest.
        double total = 1 / a.mass + 1 / b.mass;
        double shareA = push * (1 / a.mass) / total;
        double shareB = push - shareA;

        return new Exchange(
                new Impulse(finite(dvax), finite(dvaz), spin(dspa), -nx * shareA, -nz * shareA),
                new Impulse(finite(dvbx), finite(dvbz), spin(dspb), nx * shareB, nz * shareB),
                Math.max(0, -closing), finite(normalImpulse));
    }

    /** How fast the contact point on {@code b} is moving relative to the one on {@code a}. */
    private static double[] relative(Body a, Body b, double rax, double raz, double rbx, double rbz) {
        return relative(a, b, rax, raz, rbx, rbz, 0, 0, 0, 0, 0, 0);
    }

    /**
     * The same, with each body's velocity and spin adjusted by what an impulse
     * has already given it.
     *
     * <p>A point on a turning body moves at its centre's velocity plus the
     * spin times the arm turned a quarter turn — {@code (-rz, rx)} for
     * Minecraft's clockwise-positive yaw. Getting that quarter turn the wrong
     * way round is a vehicle that spins INTO whatever hit it, which reads
     * plausibly and is exactly backwards, so it is derived once here and
     * everything above goes through it.
     */
    private static double[] relative(Body a, Body b, double rax, double raz, double rbx, double rbz,
                                     double dvax, double dvaz, double dspa,
                                     double dvbx, double dvbz, double dspb) {
        double wa = Math.toRadians(a.spin + dspa);
        double wb = Math.toRadians(b.spin + dspb);
        double ax = a.vx + dvax - wa * raz;
        double az = a.vz + dvaz + wa * rax;
        double bx = b.vx + dvbx - wb * rbz;
        double bz = b.vz + dvbz + wb * rbx;
        return new double[] {bx - ax, bz - az};
    }

    private static double spin(double degrees) {
        if (!Double.isFinite(degrees)) {
            return 0;
        }
        return Math.max(-MAX_SPIN, Math.min(MAX_SPIN, degrees));
    }

    private static double finite(double value) {
        return Double.isFinite(value) ? value : 0;
    }
}
