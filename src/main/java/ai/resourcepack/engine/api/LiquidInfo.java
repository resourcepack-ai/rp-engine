package ai.resourcepack.engine.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What a content pack said a liquid is.
 *
 * <h2>What a custom liquid actually is, and is not</h2>
 *
 * <p>Minecraft has two fluids and a server cannot add a third. Nothing here
 * changes that. A custom liquid is <strong>real vanilla water or lava, wearing
 * a texture of yours, with rules of yours applied to whoever is in it</strong>.
 *
 * <p>That means it genuinely behaves like a fluid — you swim in it, boats float
 * on it, it flows downhill, it puts out fire or sets it, other plugins and
 * datapacks see water — because it <em>is</em> one. And it means two things
 * that are worth knowing before you design around it:
 *
 * <ul>
 *   <li><strong>The texture is per-pack, not per-liquid.</strong> The pack
 *       replaces the water texture, so every water on the server looks the
 *       same. A server wanting acid pools and a normal ocean wants the acid to
 *       be lava, or to accept that its oceans are acid too.</li>
 *   <li><strong>The rules are per-pool.</strong> What tells acid from ocean is
 *       where it is, so a liquid is a marked-out volume rather than a block —
 *       and anything inside it is in the acid, whatever the water there came
 *       from.</li>
 * </ul>
 *
 * <p>The alternative — a fake fluid built from display entities — renders
 * whatever you like and then has to reimplement swimming, buoyancy, flow,
 * light and every interaction a fluid has. It looks better in a screenshot and
 * worse in every other way, which is why it is not what this does.
 */
public final class LiquidInfo {

    /** Which vanilla fluid a liquid is made of. */
    public enum Base {

        /** Swim in it, breathe out of it, put fires out. */
        WATER,

        /** Burns, lights, and is far slower to move through. */
        LAVA
    }

    private final ContentId id;
    private final Base base;
    private final String effect;
    private final int amplifier;
    private final double damage;
    private final boolean fireproof;
    private final List<String> tags;

    private LiquidInfo(ContentId id, Base base, String effect, int amplifier,
                       double damage, boolean fireproof, List<String> tags) {
        this.id = id;
        this.base = base;
        this.effect = effect;
        this.amplifier = amplifier;
        this.damage = damage;
        this.fireproof = fireproof;
        this.tags = tags;
    }

    /** Engine internal; built by the liquid loader. */
    public static LiquidInfo of(ContentId id, Base base, String effect, int amplifier,
                                double damage, boolean fireproof, List<String> tags) {
        return new LiquidInfo(
                Objects.requireNonNull(id, "id"),
                base == null ? Base.WATER : base,
                effect == null ? "" : effect,
                amplifier, damage, fireproof,
                tags == null ? List.of() : List.copyOf(tags));
    }

    /** Its id. */
    public ContentId id() {
        return id;
    }

    /** Which vanilla fluid it is made of. */
    public Base base() {
        return base;
    }

    /** A potion effect applied while somebody is in it, or empty for none. */
    public Optional<String> effect() {
        return effect.isEmpty() ? Optional.empty() : Optional.of(effect);
    }

    /** How strong that effect is. */
    public int amplifier() {
        return amplifier;
    }

    /** Damage per second to anything in it. */
    public double damage() {
        return damage;
    }

    /** Whether it stops things in it burning, even when it is lava. */
    public boolean fireproof() {
        return fireproof;
    }

    /**
     * Scoreboard-style tags recorded on each pool of it.
     *
     * <p>So another plugin or a command block can find them without knowing
     * anything about this engine.
     */
    public List<String> tags() {
        return tags;
    }

    @Override
    public String toString() {
        return id + " (" + base + ")";
    }
}
