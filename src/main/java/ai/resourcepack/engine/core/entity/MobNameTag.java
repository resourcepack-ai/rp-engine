package ai.resourcepack.engine.core.entity;

import ai.resourcepack.common.scheduler.Tasks;
import ai.resourcepack.engine.core.version.Vanilla;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

/**
 * A custom mob's name, drawn over its MODEL.
 *
 * <p><strong>Why it exists.</strong> The vanilla body under a model is made
 * invisible, and the client never draws the name of an invisible living
 * entity, so a custom mob's {@code name:} was only ever seen in its boss bar.
 * This is a text display riding the mob, lifted to the model's top (or its
 * {@code tag_name} bone), standing in for the nameplate the game will not draw.
 *
 * <p><strong>Riding, like the model's parts</strong>, so the server carries it
 * and it never trails the mob. Where a passenger stands above its vehicle is
 * decided by the mob's type, its scale and the server version, so the lift is
 * not predicted but MEASURED, exactly as a worn part's is
 * ({@code RigAnimator.applyGroundLift}): the display's own height above the
 * mob is read every look and the translation makes up the difference.
 *
 * <p><strong>Shown the way vanilla shows a custom name.</strong> Always, when
 * the mob's {@code CustomNameVisible} is on ({@code name-visible: true}, a
 * {@code /data} edit, a plugin); otherwise only to a player whose crosshair is
 * on the mob, within their entity reach and not through a block, which is the
 * client's own rule. That one is per player: the display is hidden by default
 * and shown to each aimer alone.
 *
 * <p>Read from the mob every {@link #REFRESH} ticks rather than from any one
 * rename event, so a name tag, {@code /data}, the API and another plugin all
 * reach it the same way. Never saved: it is rebuilt from the mob on the first
 * look after a load, and one that outlives its mob is removed by the tick that
 * notices the mob is gone, or, failing that, the next time anything loads it
 * (see {@link CustomEntities#reapNameTag}).
 */
final class MobNameTag {

    /** Ticks between looks at the mob's name, its visibility and its model's height. */
    static final int REFRESH = 10;

    /**
     * Blocks between the top of what the name floats over and the bottom of
     * its line. Vanilla hangs a name DOWN from half a block above the hitbox,
     * one nine-pixel line at 0.025 a pixel; a text display draws its line UP
     * from where it stands, so the bottom goes at half a block less a line.
     */
    static final double GAP = 0.5 - 9 * 0.025;

    /** Players further than this from the mob are not asked whether they are aiming at it. */
    static final double NEAR = 16;

    /** A change in lift smaller than this is not worth a packet. */
    static final float SETTLE = 0.01f;

    private final Plugin plugin;
    private final NamespacedKey key;
    private final String session;

    private TextDisplay tag;
    private String text;
    private boolean always;
    private float lift = Float.NaN;
    /** Where the bottom of the line goes, in blocks above the mob's feet. */
    private double top;
    private long nextRefresh;
    /** Who has been shown a hidden-by-default tag. */
    private final Set<UUID> viewers = new HashSet<>();

    MobNameTag(Plugin plugin, NamespacedKey key, String session) {
        this.plugin = plugin;
        this.key = key;
        this.session = session;
    }

    /**
     * One look, on the mob's own thread.
     *
     * @param dressed whether the mob still wears its model: without one its
     *                own body is visible and vanilla draws the name itself
     * @param height  where the line's bottom goes now, blocks above the feet
     * @param guess   a part's height above the feet, for a fresh tag to stand
     *                at before it has been measured
     */
    void look(LivingEntity body, boolean dressed, java.util.function.DoubleSupplier height,
              double guess, long now) {
        if (now >= nextRefresh || (tag != null && !tag.isValid())) {
            nextRefresh = now + REFRESH;
            String name = dressed ? body.getCustomName() : null;
            if (name == null || name.isEmpty()) {
                remove();
                return;
            }
            boolean show = body.isCustomNameVisible();
            if (tag != null && (!tag.isValid() || !body.equals(tag.getVehicle()) || show != always)) {
                // Ejected (a teleport between worlds dismounts), or the rule
                // flipped: a fresh one is simpler than undoing per-player
                // visibility on the old.
                remove();
            }
            top = height.getAsDouble();
            if (tag == null) {
                spawn(body, name, show, guess);
            } else if (!name.equals(text)) {
                tag.setText(name);
                text = name;
            }
        }
        if (tag == null) {
            return;
        }
        place(body);
        if (!always) {
            aim(body);
        }
    }

    /** Takes it away, now or on its own thread. */
    void remove() {
        TextDisplay gone = tag;
        tag = null;
        text = null;
        lift = Float.NaN;
        viewers.clear();
        nextRefresh = 0;
        if (gone != null && gone.isValid()) {
            ai.resourcepack.engine.core.Regions.remove(plugin, gone);
        }
    }

    private void spawn(LivingEntity body, String name, boolean show, double guess) {
        Location at = body.getLocation().add(0, guess, 0);
        at.setYaw(0f);
        at.setPitch(0f);
        float first = settle(top - guess);
        tag = ai.resourcepack.engine.core.version.Spawning.spawn(body.getWorld(), at, TextDisplay.class, t -> {
            t.setText(name);
            t.setBillboard(Display.Billboard.CENTER);
            // Depth-tested, unlike vanilla's: see-through text is drawn with no
            // depth at all and loses to any model the client draws after it
            // (EmoteNameTag has the story), and a text effect's shader only
            // runs for depth-tested text.
            t.setSeeThrough(false);
            t.setShadowed(true);
            t.setDefaultBackground(true);
            t.setPersistent(false);
            t.setVisibleByDefault(show);
            t.setTransformation(translated(first));
            t.getPersistentDataContainer().set(key, PersistentDataType.STRING, session);
        });
        body.addPassenger(tag);
        text = name;
        always = show;
        lift = first;
    }

    /** The translation that puts the line where it belongs, from where the display actually rides. */
    private void place(LivingEntity body) {
        double above = tag.getLocation().getY() - body.getLocation().getY();
        float wanted = settle(top - above);
        if (Float.isNaN(lift) || Math.abs(wanted - lift) > SETTLE) {
            tag.setTransformation(translated(wanted));
            lift = wanted;
        }
    }

    /** To the thousandth: two large world coordinates differ in their last digits from tick to tick. */
    private static float settle(double value) {
        return Math.round(value * 1000) / 1000f;
    }

    private static Transformation translated(float y) {
        return new Transformation(new Vector3f(0f, y, 0f), new Quaternionf(),
                new Vector3f(1f, 1f, 1f), new Quaternionf());
    }

    /** Shows a hidden name to whoever is aiming at the mob, and hides it from whoever stopped. */
    private void aim(LivingEntity body) {
        Location at = body.getLocation();
        BoundingBox box = body.getBoundingBox();
        World world = body.getWorld();
        Set<UUID> aiming = null;
        for (Player player : world.getPlayers()) {
            // A racy read of somebody else's region on Folia, as the boss
            // bar's is: this decides whether to draw a name.
            if (player.getLocation().distanceSquared(at) > NEAR * NEAR || !aims(player, box, world)) {
                continue;
            }
            if (aiming == null) {
                aiming = new HashSet<>();
            }
            aiming.add(player.getUniqueId());
            if (viewers.add(player.getUniqueId())) {
                visible(player, true);
            }
        }
        for (Iterator<UUID> it = viewers.iterator(); it.hasNext(); ) {
            UUID id = it.next();
            if (aiming == null || !aiming.contains(id)) {
                it.remove();
                Player player = Bukkit.getPlayer(id);
                if (player != null) {
                    visible(player, false);
                }
            }
        }
    }

    private void visible(Player player, boolean show) {
        TextDisplay shown = tag;
        Tasks.now(plugin, player, () -> {
            if (!shown.isValid()) {
                return;
            }
            if (show) {
                player.showEntity(plugin, shown);
            } else {
                player.hideEntity(plugin, shown);
            }
        });
    }

    /**
     * Whether {@code player}'s crosshair is on the box: the client's rule for
     * drawing a mob's custom name. Within their entity reach, and with no block
     * in front of it.
     */
    static boolean aims(Player player, BoundingBox box, World world) {
        if (player.getGameMode() == GameMode.SPECTATOR) {
            return false;
        }
        Location eye = player.getEyeLocation();
        Vector from = eye.toVector();
        Vector direction = eye.getDirection();
        RayTraceResult hit = box.rayTrace(from, direction, reach(player));
        if (hit == null) {
            return false;
        }
        if (!Tasks.owns(player)) {
            // The blocks between are another region's on Folia; the box was
            // hit and that is as far as this thread may look.
            return true;
        }
        double distance = hit.getHitPosition().distance(from);
        return distance < 1.0E-3
                || world.rayTraceBlocks(eye, direction, distance, FluidCollisionMode.NEVER, true) == null;
    }

    /**
     * How far a player's crosshair picks an entity: the
     * {@code entity_interaction_range} attribute from 1.20.5, before it the
     * client's three blocks, six in creative.
     */
    private static double reach(Player player) {
        Attribute range = Vanilla.attribute("entity_interaction_range").orElse(null);
        AttributeInstance instance = range == null ? null : player.getAttribute(range);
        if (instance != null) {
            return instance.getValue();
        }
        return player.getGameMode() == GameMode.CREATIVE ? 6 : 3;
    }

    /** Whether {@code entity} is a mob name tag of any session. */
    static boolean isTag(Entity entity, NamespacedKey key) {
        return entity instanceof TextDisplay
                && entity.getPersistentDataContainer().has(key, PersistentDataType.STRING);
    }

    /** Whether it is one this session made, rather than one an earlier run left. */
    static boolean ours(Entity entity, NamespacedKey key, String session) {
        return session.equals(entity.getPersistentDataContainer().get(key, PersistentDataType.STRING));
    }
}
