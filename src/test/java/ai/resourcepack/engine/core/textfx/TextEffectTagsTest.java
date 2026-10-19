package ai.resourcepack.engine.core.textfx;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.TextEffectInfo;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextEffectTagsTest {

    private static final String FIRE = "§x§f§c§8§0§f§8";
    private static final String WAVE = "§x§f§c§7§c§f§8";

    private static TextEffectInfo effect(String id, String color, TextEffectInfo.Source source) {
        return new TextEffectInfo(ContentId.parse(id).orElseThrow(), null, color, source, null);
    }

    private static TextEffectCatalogue catalogue() {
        TextEffectCatalogue c = new TextEffectCatalogue(() -> Map.of(
                ContentId.parse("studio:fire").orElseThrow(), effect("studio:fire", "#fc04f8", TextEffectInfo.Source.STUDIO),
                ContentId.parse("studio:glow").orElseThrow(), effect("studio:glow", "#fc08f8", TextEffectInfo.Source.STUDIO)));
        c.replaceAuthored(Map.of(
                ContentId.parse("mypack:fire").orElseThrow(), effect("mypack:fire", "#fc80f8", TextEffectInfo.Source.CONTENT),
                ContentId.parse("mypack:fx/wave").orElseThrow(), effect("mypack:fx/wave", "#fc7cf8", TextEffectInfo.Source.CONTENT)));
        return c;
    }

    @Test
    void aTagBecomesTheEffectsExactColourAndAReset() {
        assertEquals("Hot " + FIRE + "stuff§r here", catalogue().format("Hot <fx:fire>stuff</fx> here"));
        assertEquals("§x§f§c§0§8§f§8x§r", TextEffectTags.legacy("#fc08f8") + "x" + TextEffectTags.RESET);
    }

    @Test
    void aBareNameIsTheContentFoldersBeforeStudios() {
        assertEquals(FIRE + "a§r", catalogue().format("<fx:fire>a</fx>"));
        assertEquals("§x§f§c§0§4§f§8a§r", catalogue().format("<fx:studio:fire>a</fx>"));
        assertEquals("§x§f§c§0§8§f§8a§r", catalogue().format("<fx:glow>a</fx>"));
        assertEquals(WAVE + "a§r", catalogue().format("<fx:fx/wave>a</fx>"));
    }

    @Test
    void anUnknownTagAndItsCloseAreLeftExactlyAsTyped() {
        assertEquals("<fx:nope>a</fx> b", catalogue().format("<fx:nope>a</fx> b"));
        assertEquals("a</fx>", catalogue().format("a</fx>"));
    }

    @Test
    void aTagSomebodyMayNotUseIsLeftAsTyped() {
        TextEffectCatalogue c = catalogue();
        String typed = "<fx:fire>a</fx> <fx:glow>b</fx>";
        assertEquals("<fx:fire>a</fx> §x§f§c§0§8§f§8b§r",
                c.format(typed, e -> !e.id().path().equals("fire")));
    }

    @Test
    void anUnclosedTagRunsToTheEndAndASecondOneClosesTheFirst() {
        assertEquals(FIRE + "all of it§r", catalogue().format("<fx:fire>all of it"));
        assertEquals(FIRE + "a" + WAVE + "b§r", catalogue().format("<fx:fire>a<fx:fx/wave>b</fx>"));
    }

    @Test
    void tagsAreAnyCaseAndOtherFormattingIsKept() {
        assertEquals("&l" + FIRE + "Big§r", catalogue().format("&l<FX:Fire>Big</FX>"));
    }

    @Test
    void textWithNoTagIsReturnedUntouched() {
        String plain = "nothing <here> at all </fx";
        assertFalse(TextEffectTags.mightMatch(plain));
        assertEquals(plain, catalogue().format(plain));
        assertTrue(TextEffectTags.mightMatch("x <Fx:y>"));
        assertEquals("", catalogue().format(null));
    }

    @Test
    void applyWrapsAndAnUnknownIdIsUnchanged() {
        TextEffectCatalogue c = catalogue();
        assertEquals(FIRE + "x§r", c.apply(ContentId.parse("mypack:fire").orElseThrow(), "x"));
        assertEquals("x", c.apply(ContentId.parse("mypack:nope").orElseThrow(), "x"));
    }

    @Test
    void idsAreBothSourcesSorted() {
        assertEquals("[mypack:fire, mypack:fx/wave, studio:fire, studio:glow]", catalogue().ids().toString());
        assertEquals(0xfc80f8, catalogue().find("fire").orElseThrow().rgb());
    }
}
