package ai.resourcepack.engine.core.dialog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A LIST in a dialog: rows filled from the server, as many as it has. Internal.
 *
 * <p>Studio draws a list as a fixed number of rows, each the same piece with
 * its own numbered placeholders — row 3 of a list called {@code warps} says
 * {@code {warps_3}}, and {@code {warps_3_note}} for a second field. Nothing
 * about a row is modelled here: the engine still only fills text. What this
 * class adds is the two answers a numbered name needs that no other source
 * gives:
 *
 * <ul>
 *   <li><b>Where a list's values come from.</b> A plugin publishes them
 *       ({@code dialogs().list(...)} sets {@code warps_1}… and
 *       {@code warps_count}); or the command that opens the dialog gives the
 *       whole list at once, {@code warps=Spawn,Shop,Arena}, which is item one,
 *       two and three.</li>
 *   <li><b>That a row past the end is EMPTY</b>, not unknown. An unknown name
 *       is left as written, which for a list would print {@code {warps_7}} in
 *       the seventh row of a list of six; a known list's seventh item is
 *       nothing, so the row's words are blank and Studio's
 *       {@code {warps_7?*:…|-:}} hides its picture.</li>
 * </ul>
 *
 * <p>And one thing the text pass cannot do: an empty row's click. Its picture
 * and words vanish, but its clickable area is still there — a click on empty
 * space running {@code warp } with nothing after it. Studio marks every
 * clickable run of a row with the insertion {@code rp:row:<name>}, and
 * {@link #strip} takes the click and the tooltip off the runs of a row whose
 * value is empty. An older engine leaves them: the area does nothing useful,
 * and nothing breaks.
 */
public final class DialogRows {

    private DialogRows() {
    }

    /** The insertion Studio puts on a list row's clickable runs, before the row's name. */
    public static final String INSERTION = "rp:row:";

    /** {@code warps_3}, or {@code warps_3_note}: the list, the row, and the field. */
    private static final Pattern ITEM = Pattern.compile("^([A-Za-z0-9_]+?)_(\\d{1,3})(?:_([A-Za-z0-9_]+))?$");

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /**
     * A row's value when nothing else answered its name: the item of a list
     * given whole to the opener ({@code warps=Spawn,Shop}), or nothing at all
     * for a row past the end of a list whose length is known — or empty when
     * {@code name} is not a list item, or its list is not known.
     *
     * @param opened what the dialog was opened with, by lower-case name
     * @param lookup every other source, for the list's {@code _count}
     */
    public static Optional<String> item(String name, Map<String, String> opened, Function<String, Optional<String>> lookup) {
        if (name == null) {
            return Optional.empty();
        }
        Matcher m = ITEM.matcher(name);
        if (!m.matches()) {
            return Optional.empty();
        }
        String list = m.group(1);
        int index = Integer.parseInt(m.group(2));
        String field = m.group(3);
        if (index < 1) {
            return Optional.empty();
        }
        String whole = opened == null ? null : opened.get(list.toLowerCase(Locale.ROOT));
        if (whole != null) {
            String[] items = whole.split(",", -1);
            if (index > items.length) {
                return Optional.of("");
            }
            // A comma list is one field a row; a second field has no source in it.
            return Optional.of(field == null ? items[index - 1].trim() : "");
        }
        Optional<String> count = lookup.apply(list + "_count");
        if (count.isPresent()) {
            try {
                if (index > Integer.parseInt(count.get().trim())) {
                    return Optional.of("");
                }
            } catch (NumberFormatException notACount) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** Whether a dialog has list rows whose clicks may need taking off. */
    public static boolean any(String json) {
        return json != null && json.contains(INSERTION);
    }

    /**
     * The JSON with the click and the tooltip taken off every run of a row
     * whose value is empty or unknown, and the row's insertion off every run —
     * it is only an address. The string itself when there is nothing to do.
     */
    public static String strip(String json, Function<String, Optional<String>> lookup) {
        if (!any(json)) {
            return json;
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (JsonParseException | IllegalStateException e) {
            return json;
        }
        walk(root, lookup);
        return GSON.toJson(root);
    }

    private static void walk(JsonElement element, Function<String, Optional<String>> lookup) {
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                walk(child, lookup);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject obj = element.getAsJsonObject();
        JsonElement insertion = obj.get("insertion");
        if (insertion != null && insertion.isJsonPrimitive() && insertion.getAsString().startsWith(INSERTION)) {
            String row = insertion.getAsString().substring(INSERTION.length());
            boolean empty = lookup.apply(row).map(String::isBlank).orElse(true);
            if (empty) {
                obj.remove("click_event");
                obj.remove("hover_event");
            }
            obj.remove("insertion");
        }
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            walk(entry.getValue(), lookup);
        }
    }
}
