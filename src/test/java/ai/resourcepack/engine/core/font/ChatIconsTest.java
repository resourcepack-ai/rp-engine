package ai.resourcepack.engine.core.font;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.IconInfo;
import ai.resourcepack.engine.api.Icons;

import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Turning {@code :wave:} into the picture.
 *
 * <p>Every interesting case here is a case of NOT doing it. People type
 * colons: times of day, ratios, emoticons, URLs. A replacement that ate any of
 * those would be the plugin quietly corrupting what somebody said, which is a
 * worse failure than the feature not existing.
 */
class ChatIconsTest {

    private static final int WAVE = 0xE000;
    private static final int SMILE = 0xE001;

    private final ChatIcons chat = new ChatIcons(icons(), true);

    private static Icons icons() {
        Map<ContentId, IconInfo> all = new LinkedHashMap<>();
        put(all, "mypack", "wave", WAVE);
        put(all, "mypack", "smile", SMILE);
        put(all, "otherpack", "wave", 0xE002);
        return new Icons() {
            @Override
            public Collection<ContentId> ids() {
                return all.keySet();
            }

            @Override
            public Optional<IconInfo> info(ContentId id) {
                return Optional.ofNullable(all.get(id));
            }

            @Override
            public Optional<IconInfo> info(String id) {
                return ContentId.parse(id).flatMap(this::info);
            }

            @Override
            public Optional<String> character(ContentId id) {
                return info(id).map(IconInfo::character);
            }

            @Override
            public String format(String text) {
                // The API's own :namespace:id: pass. ChatIcons does not call
                // it — it does one pass that handles bare names too — so this
                // stub only has to exist.
                return text;
            }
        };
    }

    private static void put(Map<ContentId, IconInfo> all, String namespace, String path, int codepoint) {
        ContentId id = ContentId.of(namespace, path).orElseThrow();
        all.put(id, IconInfo.of(id, path + ".png", 8, 7, codepoint));
    }

    private static String character(int codepoint) {
        return new String(Character.toChars(codepoint));
    }

    // ---- doing it --------------------------------------------------------

    @Test
    void aShortcodeBecomesItsIcon() {
        assertEquals("hello " + character(WAVE), chat.replace("hello :wave:"));
    }

    @Test
    void severalInOneLineAllGetReplaced() {
        assertEquals(character(WAVE) + " and " + character(SMILE),
                chat.replace(":wave: and :smile:"));
    }

    @Test
    void aNamespaceCanBeGivenWhereTwoPacksAgreeOnAName() {
        assertEquals(character(0xE002), chat.replace(":otherpack:wave:"));
    }

    @Test
    void aBareNameIsFoundInWhicheverPackHasIt() {
        // Somebody typing in chat has no reason to know which pack a smiley
        // came from.
        assertEquals(character(SMILE), chat.replace(":smile:"));
    }

    // ---- not doing it ----------------------------------------------------

    @Test
    void aTimeOfDayIsLeftAlone() {
        assertEquals("see you at 10:30 tomorrow", chat.replace("see you at 10:30 tomorrow"));
    }

    @Test
    void anEmoticonIsLeftAlone() {
        assertEquals("nice :) :-(", chat.replace("nice :) :-("));
    }

    @Test
    void aNameNobodyHasIsLeftExactlyAsTyped() {
        assertEquals("what :shrug: even", chat.replace("what :shrug: even"));
    }

    @Test
    void aUrlSurvives() {
        assertEquals("https://resourcepack.ai/docs",
                chat.replace("https://resourcepack.ai/docs"));
    }

    @Test
    void aLineWithNoColonsComesBackUntouchedAndIdentical() {
        String said = "hello everyone";
        assertEquals(said, chat.replace(said));
    }

    @Test
    void aStrayColonBetweenTwoIconsDoesNotSwallowThem() {
        // ":wave::smile:" is two icons, not one shortcode called "wave::smile".
        assertEquals(character(WAVE) + character(SMILE), chat.replace(":wave::smile:"));
    }

    @Test
    void textAroundAReplacementIsKeptWhole() {
        assertEquals("a" + character(WAVE) + "b", chat.replace("a:wave:b"));
    }

    @Test
    void nothingHappensWhenTheFeatureIsOff() {
        // The listener is what reads the setting; replace itself is the text
        // rule and is asserted here to be pure.
        assertEquals(character(WAVE), new ChatIcons(icons(), false).replace(":wave:"));
    }

    @Test
    void anEmptyShortcodeIsNotAName() {
        assertEquals("::", chat.replace("::"));
    }

    @Test
    void anUnclosedShortcodeIsLeftAlone() {
        assertEquals("half :wave", chat.replace("half :wave"));
    }
}
