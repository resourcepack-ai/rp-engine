package ai.resourcepack.engine.core.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every shape Studio sends runs; nothing else does. */
class GiveCommandsTest {

    private static boolean allowed(String command) {
        return GiveCommands.refusal(command, "Steve").isEmpty();
    }

    @Test
    void theCommandsStudioSendsAreAllowed() {
        assertTrue(allowed("/give Steve paper[minecraft:custom_model_data={strings:[\"chair\"]},minecraft:item_name=\"Oak Chair\"]"));
        assertTrue(allowed("/give Steve paper{CustomModelData:1001}"));
        assertTrue(allowed("/give Steve note_block[block_state={instrument=\"banjo\",note=\"3\"}]"));
        assertTrue(allowed("give steve grass_block"));
        assertTrue(allowed("/item replace entity Steve armor.head with minecraft:diamond_helmet"));
        assertTrue(allowed("tellraw Steve {\"text\":\"\\ue001\"}"));
        assertTrue(allowed("/rp dialog studio:shop Steve"));
        assertTrue(allowed("/rp dialog studio:shop.page-2 Steve"));
        assertTrue(allowed("/rp screen studio:vault Steve"));
        assertTrue(allowed("/rp hud studio:mana Steve"));
        assertTrue(allowed("/rp sound studio:chime Steve"));
        assertTrue(allowed("/rp armor wear studio:knight Steve"));
        assertTrue(allowed("execute as Steve at @s rotated ~ 0 run summon text_display ^ ^1.5 ^3 "
                + "{billboard:\"center\",background:0,shadow:0b,see_through:0b,view_range:4f,"
                + "text:{text:\"\\ue900\",color:\"#0a0b0c\"},transformation:{left_rotation:[0f,0f,0f,1f],"
                + "right_rotation:[0f,0f,0f,1f],translation:[0f,0f,0f],scale:[2f,2f,2f]}}"));
        assertTrue(allowed("execute as Steve at @s rotated ~ 0 run summon item_display ^ ^1 ^3 "
                + "{billboard:\"center\",item:{id:\"minecraft:paper\",count:1}}"));
        // An older Studio that left the selector in place.
        assertTrue(allowed("/give @p diamond"));
    }

    @Test
    void anythingElseIsRefused() {
        assertFalse(allowed("op Steve"));
        assertFalse(allowed("/stop"));
        assertFalse(allowed("lp user Steve permission set *"));
        assertFalse(allowed("minecraft:op Steve"));
        assertFalse(allowed("execute as Steve run op Steve"));
        assertFalse(allowed("execute as Steve at @s rotated ~ 0 run op Steve"));
        assertFalse(allowed("execute as Steve at @s rotated ~ 0 run summon command_block_minecart ^ ^ ^ {Command:\"op Steve\"}"));
        assertFalse(allowed("execute as Steve at @s rotated ~ 0 run summon text_display ^ ^ ^ {Passengers:[{id:\"tnt\"}]}"));
        assertFalse(allowed("/rp reload"));
        assertFalse(allowed("/rp push everything"));
        assertFalse(allowed("/rp armor strip studio:knight Steve"));
        assertFalse(allowed("/item replace block 0 0 0 container.0 with diamond"));
        assertFalse(allowed(""));
        assertFalse(allowed("give Steve diamond\nop Steve"));
    }

    @Test
    void onlyTheAddressedPlayerIsATarget() {
        assertFalse(allowed("give @a diamond"));
        assertFalse(allowed("give Alex diamond"));
        assertFalse(allowed("tellraw @e {\"text\":\"x\"}"));
        assertFalse(allowed("/rp dialog studio:shop Alex"));
        assertFalse(allowed("execute as @a at @s rotated ~ 0 run summon text_display ^ ^ ^ {}"));
    }
}
