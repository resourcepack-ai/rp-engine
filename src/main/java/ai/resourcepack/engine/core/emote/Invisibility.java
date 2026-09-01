package ai.resourcepack.engine.core.emote;

import org.bukkit.potion.PotionEffect;

/**
 * The invisibility a player was under before an emote hid them, written small
 * enough to live in a persistent-data string and given back afterwards.
 *
 * <p>Split out of {@link EmoteDirector} because none of it is about emotes: it
 * is a codec, and the only reason it is difficult is that giving a potion back
 * is not the same as re-applying one. The tick of capture travels with the
 * effect so the time an emote took can be deducted on the way out.
 *
 * <p>Free of Bukkit apart from the one convenience overload, which is what
 * lets the arithmetic be tested — {@code PotionEffectType} reads a registry
 * that only exists inside a running server.
 */
final class Invisibility {

    private Invisibility() {
    }

    /**
     * The invisibility this player already had, as something a PDC can hold.
     *
     * <p>Encoded rather than "did they have one", because giving back a plain
     * 30-second invisibility to somebody who was three minutes into a potion is
     * not giving it back. The tick it was captured at rides along so the
     * remainder can be worked out on the way out — see {@link #decode}.
     *
     * <p>{@code "-"} means they had none, which is a different fact from the
     * marker being absent: absent means they were never emoting at all.
     */
    static String encode(PotionEffect effect, long now) {
        if (effect == null) return "-";
        return encode(effect.getDuration(), effect.getAmplifier(),
            effect.isAmbient(), effect.hasParticles(), effect.hasIcon(), now);
    }

    /**
     * The same, from primitives.
     *
     * <b>Free of Bukkit so it can be tested</b>, exactly like
     * {@link EmoteDirector#applyPropStep} and {@link EmoteDirector#rigToWorld}: {@code PotionEffectType}
     * reads a registry that only exists inside a running server, so a test that
     * built a real effect could not run at all. The arithmetic is the part
     * worth testing and none of it needs the API.
     */
    static String encode(
            int duration, int amplifier, boolean ambient, boolean particles, boolean icon, long now) {
        return duration + "," + amplifier + "," + ambient + "," + particles + "," + icon + "," + now;
    }

    /** One stored effect, with nothing of Bukkit's in it. */
    static final class Stored {
        final int duration;
        final int amplifier;
        final boolean ambient;
        final boolean particles;
        final boolean icon;

        Stored(int duration, int amplifier, boolean ambient, boolean particles, boolean icon) {
            this.duration = duration;
            this.amplifier = amplifier;
            this.ambient = ambient;
            this.particles = particles;
            this.icon = icon;
        }
    }

    /**
     * Their own effect back, with the time the emote took deducted.
     *
     * <p>Deducted rather than restored whole, because an emote is not a pause
     * on a potion — standing still for twenty seconds of a handshake should
     * cost twenty seconds of invisibility, exactly as it would have if we had
     * never touched it. An effect that would have run out in the meantime comes
     * back as null, which is the honest answer rather than a second of it.
     *
     * <p>Infinite stays infinite: there is nothing to deduct from, and it is
     * the one duration where arithmetic would end a potion that should not end.
     */
    static Stored decode(String raw, long now) {
        if (raw == null || raw.equals("-")) return null;
        String[] parts = raw.split(",");
        if (parts.length != 6) return null;
        try {
            int duration = Integer.parseInt(parts[0]);
            int amplifier = Integer.parseInt(parts[1]);
            boolean ambient = Boolean.parseBoolean(parts[2]);
            boolean particles = Boolean.parseBoolean(parts[3]);
            boolean icon = Boolean.parseBoolean(parts[4]);
            long captured = Long.parseLong(parts[5]);

            int remaining;
            if (duration < 0) {
                remaining = PotionEffect.INFINITE_DURATION;
            } else {
                // A world that went backwards (a restore from backup, a
                // /time set) reads as no time passing rather than as a
                // negative elapsed that would hand back MORE than they had.
                long elapsed = Math.max(0, now - captured);
                long left = duration - elapsed;
                if (left <= 0) return null;
                remaining = (int) Math.min(Integer.MAX_VALUE, left);
            }
            return new Stored(remaining, amplifier, ambient, particles, icon);
        } catch (NumberFormatException e) {
            // A marker we cannot read means we cannot honestly give anything
            // back; they simply end up visible, which is the safe direction.
            return null;
        }
    }
}
