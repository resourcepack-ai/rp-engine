package ai.resourcepack.engine.core.recipe;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The pattern a trim recipe borrows from its template on a server that wants
 * the pattern named, when the pack did not name one.
 */
class TrimPatternTest {

    @Test
    void aVanillaTrimTemplateStandsForItsPattern() {
        assertEquals("minecraft:sentry", Recipes.patternOf(Material.SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE));
        assertEquals("minecraft:wayfinder", Recipes.patternOf(Material.WAYFINDER_ARMOR_TRIM_SMITHING_TEMPLATE));
    }

    @Test
    void anythingElseStandsForNone() {
        // The upgrade template is a template and not a trim, and paper is
        // what somebody's custom template is usually made of.
        assertNull(Recipes.patternOf(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE));
        assertNull(Recipes.patternOf(Material.PAPER));
    }
}
