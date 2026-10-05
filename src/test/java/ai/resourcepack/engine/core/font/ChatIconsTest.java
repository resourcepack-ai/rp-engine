package ai.resourcepack.engine.core.font;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.IconInfo;
import ai.resourcepack.engine.api.Icons;

import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

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
    private static final int HEART = 0xE003;
    private static final int THUMBS = 0xE004;
    private static final int CROWN = 0xE005;

    private final ChatIcons chat = new ChatIcons(icons(), true);

    private static Icons icons() {
        Map<ContentId, IconInfo> all = new LinkedHashMap<>();
        put(all, "mypack", "wave", WAVE);
        put(all, "mypack", "smile", SMILE);
        put(all, "otherpack", "wave", 0xE002);
        put(all, "mypack", "heart", HEART, List.of("<3", ":love:"), null);
        put(all, "mypack", "thumbs", THUMBS, List.of("(y)"), null);
        put(all, "mypack", "crown", CROWN, List.of(":king:"), "vip.crown");
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

    private static void put(Map<ContentId, IconInfo> all, String namespace, String path, int codepoint,
                            List<String> aliases, String permission) {
        ContentId id = ContentId.of(namespace, path).orElseThrow();
        all.put(id, IconInfo.of(id, path + ".png", 8, 7, codepoint).withChat(aliases, permission));
    }

    /** Somebody holding exactly these permissions. */
    private static Predicate<IconInfo> holding(String... permissions) {
        Set<String> held = Set.of(permissions);
        return icon -> icon.permission().map(held::contains).orElse(true);
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

    // ---- aliases ---------------------------------------------------------

    @Test
    void anAliasTypedAsAWordBecomesItsIcon() {
        assertEquals("I " + character(HEART) + " you", chat.replace("I <3 you"));
        assertEquals(character(HEART), chat.replace("<3"));
        assertEquals(character(HEART) + " " + character(HEART), chat.replace("<3 <3"));
    }

    @Test
    void anAliasInsideAnotherWordIsLeftAlone() {
        // Punctuation turns up inside other things, so an alias has to stand
        // on its own: whitespace or the end of the line on both sides.
        assertEquals("<33", chat.replace("<33"));
        assertEquals("a<3", chat.replace("a<3"));
        assertEquals("<3!", chat.replace("<3!"));
        assertEquals("https://example.com/<3", chat.replace("https://example.com/<3"));
    }

    @Test
    void anAliasIsMatchedLiterallyNotAsAPattern() {
        assertEquals("ok " + character(THUMBS), chat.replace("ok (y)"));
        assertEquals("ok y", chat.replace("ok y"));
        assertEquals("ok (yy)", chat.replace("ok (yy)"));
    }

    @Test
    void anAliasSpelledLikeAShortcodeIsTheAlias() {
        // There is no icon called "love"; the alias is what makes it one.
        assertEquals("so " + character(HEART), chat.replace("so :love:"));
    }

    @Test
    void namesStillWorkBesideAliases() {
        assertEquals(character(WAVE) + " " + character(HEART), chat.replace(":wave: <3"));
        assertEquals("a" + character(WAVE) + "b", chat.replace("a:wave:b"));
        assertEquals("see you at 10:30", chat.replace("see you at 10:30"));
    }

    // ---- permission ------------------------------------------------------

    @Test
    void anIconWithAPermissionIsLeftAsTypedForSomebodyWithoutIt() {
        assertEquals(":crown: :king:", chat.replace(":crown: :king:", holding()));
        assertEquals(":mypack:crown:", chat.replace(":mypack:crown:", holding()));
    }

    @Test
    void anIconWithAPermissionWorksForSomebodyWithIt() {
        assertEquals(character(CROWN) + " " + character(CROWN), chat.replace(":crown: :king:", holding("vip.crown")));
    }

    @Test
    void aPermissionOnOneIconLeavesTheOthersAlone() {
        assertEquals(character(HEART) + " :crown:", chat.replace("<3 :crown:", holding()));
    }
}
