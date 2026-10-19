package ai.resourcepack.engine.core.command;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.resourcepack.engine.api.VfxOptions;

import org.junit.jupiter.api.Test;

/** {@code /rp cast}'s options, read the way a server owner types them. */
class VfxCommandsTest {

    private static VfxOptions read(String line) {
        VfxOptions.Builder b = VfxOptions.builder();
        String problem = VfxCommands.parseOptions(line.split(" "), 0, b, null);
        assertNull(problem, line + ": " + problem);
        return b.build();
    }

    @Test
    void readsEveryOption() {
        VfxOptions o = read("loop:5m speed:2 scale:1.5 turn:-90 offset:0,1,3 delay:2s vary seed:7 quiet volume:0.5 "
                + "only:caster tag:aura check:10t end:cut");
        assertTrue(o.loop());
        assertEquals(6000, o.loopTicks());
        assertEquals(2, o.speed());
        assertEquals(1.5, o.scale());
        assertEquals(-90, o.turn());
        assertArrayEquals(new double[] {0, 1, 3}, o.offset());
        assertEquals(40, o.delay());
        assertTrue(o.vary());
        assertEquals(7, o.seed());
        assertTrue(!o.sounds());
        assertEquals(VfxOptions.Viewers.CASTER, o.viewers());
        assertEquals("aura", o.tag());
        assertEquals(10, o.checkEvery());
        assertEquals(VfxOptions.End.CUT, o.end());
    }

    @Test
    void aConditionTakesTheRestOfTheLine() {
        VfxOptions o = read("loop tag:storm until:{arena_state} == \"ended\" || !perm:vip");
        assertEquals("{arena_state} == \"ended\" || !perm:vip", o.until());
        assertEquals("storm", o.tag());
        assertEquals("{mana} != \"0\"", read("while:{mana} != \"0\"").whileTrue());
    }

    @Test
    void countsAndPlainLoops() {
        assertEquals(3, read("loop:3x").loopTimes());
        VfxOptions forever = read("loop");
        assertTrue(forever.loop());
        assertEquals(0, forever.loopTicks());
        assertEquals(0, forever.loopTimes());
        assertTrue(!read("speed:1").loop());
    }

    @Test
    void saysWhatIsWrong() {
        for (String bad : new String[] {"loop:soon", "speed:fast", "offset:1,2", "end:later", "only:everyone", "wobble"}) {
            assertNotNull(VfxCommands.parseOptions(bad.split(" "), 0, VfxOptions.builder(), null), bad);
        }
    }

    @Test
    void clampsWhatIsTooMuch() {
        VfxOptions o = read("speed:500 scale:0 volume:99");
        assertEquals(10, o.speed());
        assertEquals(0.1, o.scale());
        assertEquals(4, o.volume());
    }
}
