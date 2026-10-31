package ai.resourcepack.engine.core.skill;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Placeholders in skill arguments, in MythicMobs' spelling ({@code <caster.hp>},
 * {@code <target.name>}, {@code <trigger.uuid>}) and in the engine's own
 * ({@code {caster_hp}}), plus MythicMobs' escapes for characters the line
 * grammar would otherwise eat ({@code <&sc>} is a semicolon).
 *
 * <p>The resolving is handed in as a function of scope and field, so the
 * substitution itself is tested without a server.
 */
final class SkillText {

    private SkillText() {
    }

    private static final Pattern ANGLE = Pattern.compile("<([a-zA-Z]+)\\.([a-zA-Z0-9_.%-]+)>");
    private static final Pattern BRACE = Pattern.compile("\\{(caster|self|mob|target|trigger|skill)_([a-zA-Z0-9_.%]+)}");
    private static final Map<String, String> ESCAPES = Map.ofEntries(
            Map.entry("<&sq>", "'"), Map.entry("<&da>", "-"), Map.entry("<&sc>", ";"), Map.entry("<&eq>", "="),
            Map.entry("<&co>", ":"), Map.entry("<&lb>", "["), Map.entry("<&rb>", "]"), Map.entry("<&lc>", "{"),
            Map.entry("<&rc>", "}"), Map.entry("<&bs>", "\\"), Map.entry("<&cm>", ","), Map.entry("<&sp>", " "),
            Map.entry("<&ds>", "$"), Map.entry("<&nl>", "\n"));

    /**
     * Fills {@code text}. {@code resolve} answers a scope ({@code caster},
     * {@code target}, {@code trigger}, {@code skill}) and a field, or null to
     * leave the placeholder as written.
     */
    static String fill(String text, BiFunction<String, String, String> resolve) {
        if (text == null || (text.indexOf('<') < 0 && text.indexOf('{') < 0)) {
            return text;
        }
        String out = replace(ANGLE, text, resolve);
        out = replace(BRACE, out, resolve);
        if (out.contains("<&")) {
            for (Map.Entry<String, String> escape : ESCAPES.entrySet()) {
                out = out.replace(escape.getKey(), escape.getValue());
            }
        }
        return out;
    }

    private static String replace(Pattern pattern, String text, BiFunction<String, String, String> resolve) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String scope = matcher.group(1).toLowerCase(Locale.ROOT);
            if (scope.equals("self") || scope.equals("mob")) {
                scope = "caster";
            }
            String value = resolve.apply(scope, matcher.group(2).toLowerCase(Locale.ROOT).replace('_', '.'));
            matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? matcher.group() : value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** One field of an entity, or null when it is not one this knows. */
    static String field(Entity entity, String field, String stance) {
        if (entity == null) {
            return null;
        }
        Location at = entity.getLocation();
        switch (field) {
            case "name":
            case "displayname":
            case "dn":
                return name(entity);
            case "uuid":
                return entity.getUniqueId().toString();
            case "type":
                return entity.getType().name().toLowerCase(Locale.ROOT);
            case "hp":
            case "health":
                return entity instanceof LivingEntity ? number(((LivingEntity) entity).getHealth()) : "0";
            case "mhp":
            case "maxhp":
            case "max.hp":
            case "maxhealth":
                return entity instanceof LivingEntity ? number(SkillMath.maxHealth((LivingEntity) entity)) : "0";
            case "php":
            case "hp%":
            case "percenthealth":
                if (entity instanceof LivingEntity) {
                    double max = SkillMath.maxHealth((LivingEntity) entity);
                    return number(max <= 0 ? 0 : ((LivingEntity) entity).getHealth() / max * 100);
                }
                return "0";
            case "stance":
                return stance == null ? "" : stance;
            case "l.x":
            case "x":
                return number(at.getX());
            case "l.y":
            case "y":
                return number(at.getY());
            case "l.z":
            case "z":
                return number(at.getZ());
            case "l.w":
            case "world":
                return at.getWorld() == null ? "" : at.getWorld().getName();
            default:
                return null;
        }
    }

    static String name(Entity entity) {
        if (entity instanceof Player) {
            return ((Player) entity).getName();
        }
        String custom = entity.getCustomName();
        if (custom != null && !custom.isEmpty()) {
            return custom;
        }
        String type = entity.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(type.charAt(0)) + type.substring(1);
    }

    /** 40 rather than 40.0; one decimal otherwise. */
    static String number(double value) {
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(Math.round(value * 10) / 10.0);
    }
}
