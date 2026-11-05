package ai.resourcepack.engine.core.serve;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptTextTest {

    @Test
    void plainTextStaysPlain() {
        assertEquals("Accept the pack", PromptText.toLegacy("Accept the pack"));
        assertEquals("", PromptText.toLegacy(""));
        assertEquals("", PromptText.toLegacy(null));
    }

    @Test
    void namedColoursBecomeTheirCodes() {
        assertEquals("§cRed§r plain", PromptText.toLegacy("<red>Red</red> plain"));
        assertEquals("§cRed §lbold", PromptText.toLegacy("&cRed &lbold"));
    }

    @Test
    void hexColoursBecomeSectionX() {
        assertEquals("§x§f§a§4§9§4§3Hi", PromptText.toLegacy("<#fa4943>Hi"));
        assertEquals("§x§f§a§4§9§4§3Hi", PromptText.toLegacy("&#FA4943Hi"));
        assertEquals("§x§f§a§4§9§4§3Hi", PromptText.toLegacy("<color:#fa4943>Hi"));
    }

    @Test
    void aGradientColoursEachCharacterFromFirstStopToLast() {
        String out = PromptText.toLegacy("<gradient:#000000:#FFFFFF>abc</gradient>");
        assertEquals("§0a§x§8§0§8§0§8§0b§fc", out);
    }

    @Test
    void closingATagPutsBackTheColourItFound() {
        assertEquals("§cA §lB§cC", PromptText.toLegacy("<red>A <bold>B</bold>C"));
    }

    @Test
    void nexosOwnExampleConverts() {
        String out = PromptText.toLegacy("<#fa4943>Accept the pack to enjoy a full "
                + "<b><gradient:#9055FF:#13E2DA>Nexo</b><#fa4943>experience");
        assertTrue(out.startsWith("§x§f§a§4§9§4§3Accept the pack to enjoy a full "), out);
        assertTrue(out.contains("§x§9§0§5§5§f§f§lN"), out);
        assertTrue(out.endsWith("experience"), out);
        assertTrue(!out.contains("<"), out);
    }

    @Test
    void anUnknownTagIsLeftAsTyped() {
        assertEquals("a <wave>b", PromptText.toLegacy("a <wave>b"));
        assertEquals("a <b", PromptText.toLegacy("a \\<b"));
    }
}
