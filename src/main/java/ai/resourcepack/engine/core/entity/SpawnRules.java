package ai.resourcepack.engine.core.entity;

import ai.resourcepack.engine.api.EntityInfo;

import java.util.Locale;

/**
 * Whether a natural spawn is one a custom mob's {@code spawning:} rules take
 * the place of. Pure, so {@code SpawnRulesTest} can hold it without a server.
 *
 * <p>Two questions, asked at two times. {@link #replaces} is asked in the
 * spawn event and needs only the vanilla mob's type: is it a spawn of the
 * right sort at all. {@link #where} is asked a tick later, when the mob
 * stands in a finished chunk: is this world, biome, light and time one the
 * rules allow.
 */
final class SpawnRules {

    /** A spawn category nothing spawns naturally as: golems, allays, villagers. */
    static final String OTHER = "other";

    /** Vanilla's day ends at 12300 (the sun's lower edge at the horizon) and starts again at 23850. */
    static final long DUSK = 12300;
    static final long DAWN = 23850;

    private SpawnRules() {
    }

    /**
     * The natural-spawn kind of an entity class: a monster, an animal, a water
     * creature, an ambient one (bats), or something else, which never spawns
     * naturally and so is never replaced without {@code replaces:}.
     */
    static String kind(Class<?> type) {
        if (org.bukkit.entity.Enemy.class.isAssignableFrom(type)) {
            return "monster";
        }
        if (org.bukkit.entity.WaterMob.class.isAssignableFrom(type)) {
            return "water";
        }
        if (org.bukkit.entity.Animals.class.isAssignableFrom(type)) {
            return "animal";
        }
        if (org.bukkit.entity.Ambient.class.isAssignableFrom(type)) {
            return "ambient";
        }
        return OTHER;
    }

    /**
     * Whether a natural spawn of {@code type} (a Bukkit {@code EntityType}
     * name), which is a {@code kind}, may be replaced under {@code rules} by a
     * mob of {@code ownKind}. Named types when the rules name any; otherwise
     * the same kind as the custom mob's own, so a zombie-brained mob stands in
     * for monsters and a pig-brained one for animals.
     */
    static boolean replaces(EntityInfo.Spawning rules, String type, String kind, String ownKind) {
        if (!rules.replaces().isEmpty()) {
            return rules.replaces().contains(type.toUpperCase(Locale.ROOT));
        }
        return !OTHER.equals(ownKind) && ownKind.equals(kind);
    }

    /** Whether a world, biome, block light and time of day are ones {@code rules} allow. */
    static boolean where(EntityInfo.Spawning rules, String world, String biome, int light, long time) {
        if (!rules.worlds().isEmpty() && rules.worlds().stream().noneMatch(w -> w.equalsIgnoreCase(world))) {
            return false;
        }
        if (!rules.biomes().isEmpty()) {
            String bare = biome == null ? "" : biome.toLowerCase(Locale.ROOT);
            if (rules.biomes().stream().noneMatch(pattern -> glob(pattern, bare))) {
                return false;
            }
        }
        if (light < rules.minLight() || light > rules.maxLight()) {
            return false;
        }
        long day = ((time % 24000) + 24000) % 24000;
        boolean night = day >= DUSK && day < DAWN;
        return switch (rules.time()) {
            case "day" -> !night;
            case "night" -> night;
            default -> true;
        };
    }

    /** {@code *} matches any run of characters; everything else itself. */
    static boolean glob(String pattern, String text) {
        if (pattern.indexOf('*') < 0) {
            return pattern.equals(text);
        }
        String[] pieces = pattern.split("\\*", -1);
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < pieces.length; i++) {
            if (i > 0) {
                regex.append(".*");
            }
            regex.append(java.util.regex.Pattern.quote(pieces[i]));
        }
        return text.matches(regex.toString());
    }
}
