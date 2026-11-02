package ai.resourcepack.engine.core.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A push from somebody without rpengine.sync.console carries nothing the console would run. */
class StudioContentConsoleTest {

    @Test
    void dialogConsoleButtonsAreTakenOut() {
        String json = "{\"dialogs\":[{\"id\":\"menu\",\"json\":{},\"consoleCommands\":[{\"key\":\"0a1b2c3d\",\"command\":\"op {player}\"}]}]}";
        StudioContent.Stripped stripped = StudioContent.withoutConsole(json);
        assertEquals(1, stripped.removed());
        assertFalse(stripped.json().contains("consoleCommands"));
        assertTrue(stripped.json().contains("\"menu\""));
    }

    @Test
    void mobCommandSkillsAreTakenOutWhereverTheyHide() {
        String json = "{\"mobs\":[{\"id\":\"golem\",\"skills\":["
                + "\"skill{s=studio:golem.slam} @self ~onTimer:120\","
                + "\"command{c=op Steve} @self ~onSpawn\","
                + "\"projectile{onEnd=[ - CMD{c=\\\"op Steve\\\"} ]} @target\""
                + "],\"metaskills\":[{\"name\":\"slam\",\"skills\":[\"damage{a=4} @target\",\"consolecommand{c=stop}\"],"
                + "\"conditions\":[\"stance{s=x} orElseCast command{c=stop}\"]}]}]}";
        StudioContent.Stripped stripped = StudioContent.withoutConsole(json);
        assertEquals(4, stripped.removed());
        String out = stripped.json().toLowerCase();
        assertFalse(out.contains("op steve"));
        assertFalse(out.contains("stop"));
        assertTrue(out.contains("golem.slam"));
        assertTrue(out.contains("damage{a=4}"));
    }

    @Test
    void aCleanManifestComesBackUntouched() {
        String json = "{\"dialogs\":[{\"id\":\"menu\",\"json\":{}}],\"mobs\":[{\"id\":\"a\",\"skills\":[\"damage{a=1} @target\"]}]}";
        StudioContent.Stripped stripped = StudioContent.withoutConsole(json);
        assertEquals(0, stripped.removed());
        assertEquals(json, stripped.json());
        assertEquals("not json", StudioContent.withoutConsole("not json").json());
    }
}
