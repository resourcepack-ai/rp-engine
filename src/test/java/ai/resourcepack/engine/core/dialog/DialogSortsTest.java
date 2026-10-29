package ai.resourcepack.engine.core.dialog;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A box whose pieces a player sees in an order worked out for them. Studio's
 * verify:dialogs runs the cases marked SHARED through its own port
 * ({@code arrangeSorted}) and fails when the two disagree.
 */
class DialogSortsTest {

    /** Six items: rarity 0 legendary .. 3 common, a price, and a VIP-only one. */
    private static final String SHOP = """
            {"name":"shop","slots":4,"page":"{page}",
             "children":[
              {"props":{"rarity":{"value":"3"},"price":{"value":"150"}}},
              {"props":{"rarity":{"value":"1"},"price":{"value":"600"}}},
              {"props":{"rarity":{"value":"0"},"price":{"value":"450"}}},
              {"show":"perm:shop.vip","props":{"rarity":{"value":"0"},"price":{"value":"200"}}},
              {"props":{"rarity":{"value":"1","rules":[{"if":"!perm:shop.epic","value":"-1"}]},"price":{"value":"200"}}},
              {"props":{"rarity":{"value":"3"},"price":{"value":"{cheap_price}"}}}
             ],
             "orders":[
              {"if":"{mode?price:price|rarity:rarity} == \\"price\\"","keys":[{"prop":"price","desc":true}]},
              {"keys":[{"prop":"rarity","desc":true},{"prop":"price"}]}
             ]}
            """;

    private static Map<String, String> arrange(String spec, Map<String, String> values, String... perms) {
        Set<String> held = Set.of(perms);
        Function<String, Optional<String>> lookup = name -> Optional.ofNullable(values.get(name));
        Predicate<String> permission = held::contains;
        JsonObject parsed = JsonParser.parseString(spec).getAsJsonObject();
        return DialogSorts.arrange(parsed, lookup, e -> e == null || e.isBlank() || DialogConditions.test(e, lookup, permission));
    }

    private static String slots(Map<String, String> out) {
        StringBuilder s = new StringBuilder();
        for (int k = 1; out.containsKey("shop_slot_" + k); k++) {
            s.append(k > 1 ? "," : "").append(out.get("shop_slot_" + k));
        }
        return s.toString();
    }

    // SHARED: {} [] -> 0,5,1,2 page 1 of 2, 5 shown
    @Test
    void sortsByEachKeyInTurnTiesInStudiosOrder() {
        Map<String, String> out = arrange(SHOP, Map.of());
        // rarity desc: 3,3,1,0 ... the locked epic is -1 and goes last.
        assertEquals("0,5,1,2", slots(out));
        assertEquals("1", out.get("shop_page"));
        assertEquals("2", out.get("shop_pages"));
        assertEquals("5", out.get("shop_count"));
    }

    // SHARED: {"page":"2"} [] -> 4,,, page 2 of 2, 5 shown
    @Test
    void aPageShowsTheNextSlotsAndAShortLastPageIsEmptyPastTheEnd() {
        Map<String, String> out = arrange(SHOP, Map.of("page", "2"));
        assertEquals("4,,,", slots(out));
        assertEquals("2", out.get("shop_page"));
    }

    // SHARED: {"page":"9"} [] -> 4,,, page 2 of 2, 5 shown
    @Test
    void aPagePastTheLastIsTheLast() {
        assertEquals("2", arrange(SHOP, Map.of("page", "9")).get("shop_page"));
    }

    // SHARED: {"page":"two"} [] -> 0,5,1,2 page 1 of 2, 5 shown
    @Test
    void aPageThatIsNoNumberIsTheFirst() {
        assertEquals("1", arrange(SHOP, Map.of("page", "two")).get("shop_page"));
    }

    // SHARED: {} ["shop.vip","shop.epic"] -> 0,5,4,1 page 1 of 2, 6 shown
    @Test
    void aPieceTheyMaySeeIsSortedAndARuleThatHoldsChangesAValue() {
        Map<String, String> out = arrange(SHOP, Map.of(), "shop.vip", "shop.epic");
        // The epic at 200 now sorts before the epic at 600, by price.
        assertEquals("0,5,4,1", slots(out));
        assertEquals("6", out.get("shop_count"));
    }

    // SHARED: {"mode":"price","cheap_price":"50"} [] -> 1,2,4,0 page 1 of 2, 5 shown
    @Test
    void theFirstOrderThatHoldsIsUsedAndAValueMayBeAPlaceholder() {
        Map<String, String> out = arrange(SHOP, Map.of("mode", "price", "cheap_price", "50"));
        // price desc: 600, 450, 200, 150, 50
        assertEquals("1,2,4,0", slots(out));
        assertEquals("5", arrange(SHOP, Map.of("mode", "price", "cheap_price", "50", "page", "2")).get("shop_slot_1"));
    }

    // SHARED: {"mode":"price"} [] -> 5,1,2,4 page 1 of 2, 5 shown
    @Test
    void aValueThatIsNoNumberSortsAfterTheNumbers() {
        // {cheap_price} unfilled is words, which sort after every number; desc puts it first.
        Map<String, String> out = arrange(SHOP, Map.of("mode", "price"));
        assertEquals("5,1,2,4", slots(out));
    }

    @Test
    void comparesNumbersAsNumbersAndWordsAsWords() {
        assertTrue(DialogSorts.compare("9", "10") < 0);
        assertTrue(DialogSorts.compare("-1", "0") < 0);
        assertEquals(0, DialogSorts.compare("2", "2.0"));
        assertTrue(DialogSorts.compare("10", "apple") < 0);
        assertEquals(0, DialogSorts.compare("Apple", "apple"));
        assertTrue(DialogSorts.compare("apple", "Banana") < 0);
    }

    @Test
    void theRulesAreTakenOutAndAnswerTheSlots() {
        com.google.gson.JsonArray extra = new com.google.gson.JsonArray();
        JsonObject rules = new JsonObject();
        rules.addProperty("text", "");
        rules.addProperty("insertion", DialogSorts.SORT + SHOP);
        extra.add(rules);
        extra.add(JsonParser.parseString("{\"text\":\"\",\"insertion\":\"rp:if:{shop_slot_1} == \\\"0\\\"\",\"extra\":[{\"text\":\"common\"}]}"));
        extra.add(JsonParser.parseString("{\"text\":\"\",\"insertion\":\"rp:if:{shop_slot_1} == \\\"2\\\"\",\"extra\":[{\"text\":\"legendary\"}]}"));
        JsonObject body = new JsonObject();
        body.addProperty("text", "");
        body.add("extra", extra);
        String json = body.toString();
        assertTrue(DialogSorts.any(json));
        Function<String, Optional<String>> none = name -> Optional.empty();
        DialogSorts.Sorted sorted = DialogSorts.apply(json, none, p -> false);
        assertFalse(sorted.json().contains(DialogSorts.SORT));
        assertEquals("0", sorted.values().get("shop_slot_1"));
        String stripped = DialogConditions.strip(sorted.json(), sorted.over(none), p -> false);
        assertTrue(stripped.contains("common"));
        assertFalse(stripped.contains("legendary"));
    }

    @Test
    void aSpecWithoutAProperNameAnswersNothing() {
        assertTrue(arrange("{\"name\":\"Shop!\",\"slots\":2,\"children\":[{}]}", Map.of()).isEmpty());
    }
}
