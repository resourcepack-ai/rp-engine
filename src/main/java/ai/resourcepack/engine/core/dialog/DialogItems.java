package ai.resourcepack.engine.core.dialog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Puts a player's own items into a Studio dialog's item slots as it opens.
 * Internal.
 *
 * <h2>What arrives</h2>
 *
 * Studio can draw a grid of item slots in a dialog's picture: a backpack, a
 * shop's stock — or the player's own inventory. The first two are finished
 * when they arrive: each slot's clickable area already carries a
 * {@code show_item} hover, so the game draws that item's own tooltip. The
 * inventory cannot be finished by anybody but the server, so its slots arrive
 * empty with two MARKERS each, both found by their {@code insertion}:
 *
 * <ul>
 *   <li>{@code rp:slot:<key>} on the slot's clickable runs — given the item in
 *   that slot as their hover;</li>
 *   <li>{@code rp:item:<key>}, an empty text in an icon font
 *   ({@code dialog_items_<dy>}), where the item goes — swapped for the item's
 *   icon, its count, and a step back over both.</li>
 * </ul>
 *
 * A key is {@code <container>/<index>} — {@code inv/5} a slot of the player's
 * inventory in Bukkit's numbering (0-8 the hotbar, 9-35 above it),
 * {@code ender/3} one of their ender chest; see {@link DialogSlots}. A bare
 * number is a slot of the inventory, as the first build wrote one.
 *
 * <h2>A stack picked up</h2>
 *
 * In a dialog whose items move, the slot a player has picked a stack up from
 * is drawn lit: the icon font's highlight ({@link DialogItemIcons#HELD}) and a
 * step back go in front of the icon, so the game draws the light first and the
 * item over it — one more glyph that comes to nothing.
 *
 * <h2>Why every swap moves nothing</h2>
 *
 * A Studio picture is set into lines of text that must each come to exactly
 * the body's width, or the client wraps them somewhere else. The icon font's
 * pictures are Studio's own, every one 16 wide and so advancing exactly 17;
 * the count is drawn after a negative "twin" of itself, so it ends where it
 * started, right-aligned at the icon's edge; and a character that steps back
 * 17 ends the run. Icon, twin, digits and step back come to nothing, whatever
 * the slot holds — and an empty slot, or an engine that does not know the
 * marker, leaves an empty text, which is nothing too.
 *
 * <h2>Why this is not "modelling" a dialog</h2>
 *
 * The engine still knows no field of a dialog. This walks the JSON's
 * components and looks at two things that Studio wrote for it to find —
 * insertions in its own namespace — and one thing that would otherwise take
 * the whole dialog down: a {@code show_item} hover naming an item this
 * server's version does not have, which its codec refuses, refusing the
 * dialog with it. That one is taken out, leaving the slot's picture and
 * its click.
 */
public final class DialogItems {

    private DialogItems() {
    }

    /** What an icon marker's insertion starts with; the player's inventory slot follows. Studio writes it. */
    static final String ITEM = "rp:item:";

    /** What a live slot's clickable run carries in its insertion; the slot follows. Studio writes it. */
    static final String SLOT = "rp:slot:";

    /** A digit's negative twin in an icon font: this plus the digit. Studio's icon fonts declare it. */
    public static final int TWIN_BASE = 0xE900;

    /** The character in an icon font that steps back over an icon. */
    public static final int BACK = 0xE9FF;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /**
     * What one of a player's slots holds, as a dialog needs it: the id the icon
     * is drawn by, the count, whether it wears a model of its own (whose
     * picture the icon font does not have), its hover, ready to send, and
     * whether it is the stack they have picked up.
     */
    public record Shown(String id, int count, boolean custom, JsonObject hover, boolean held) {
        public Shown(String id, int count, boolean custom, JsonObject hover) {
            this(id, count, custom, hover, false);
        }
    }

    /** Whether a dialog has anything here to do — so a dialog with none costs one scan. */
    public static boolean any(String json) {
        return json != null && (json.contains(ITEM) || json.contains(SLOT) || json.contains("show_item"));
    }

    /** Whether a dialog shows something per player: a page like that is never sent as somebody else's copy. */
    public static boolean perPlayer(String json) {
        return json != null && (json.contains(ITEM) || json.contains(SLOT));
    }

    /**
     * The JSON with this player's items in its slots and every item tooltip
     * this server would refuse taken out.
     */
    public static String fill(String json, Player viewer) {
        return fill(json, viewer, null);
    }

    /** {@link #fill(String, Player)}, with the slot they have picked a stack up from drawn lit. */
    public static String fill(String json, Player viewer, DialogSlots.Key held) {
        if (!any(json) || viewer == null) {
            return json;
        }
        return fill(json, key -> shown(viewer, key, key.equals(held)), DialogItems::known);
    }

    /**
     * {@link #fill(String, Player)}, told what each slot holds and which item
     * ids exist rather than asking a server. Returns the input itself when
     * there is nothing to change, or when the JSON cannot be read — a dialog
     * this cannot parse is passed on as it came, for the game to judge.
     */
    static String fill(String json, Function<DialogSlots.Key, Shown> slots, Predicate<String> known) {
        if (!any(json)) {
            return json;
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (JsonParseException e) {
            return json;
        }
        boolean[] changed = {false};
        JsonElement out = walk(root, slots, known, changed);
        return changed[0] ? GSON.toJson(out) : json;
    }

    private static JsonElement walk(JsonElement e, Function<DialogSlots.Key, Shown> slots, Predicate<String> known,
                                    boolean[] changed) {
        if (e.isJsonArray()) {
            JsonArray next = new JsonArray();
            for (JsonElement child : e.getAsJsonArray()) {
                DialogSlots.Key slot = marker(child, ITEM);
                if (slot == null) {
                    next.add(walk(child, slots, known, changed));
                    continue;
                }
                changed[0] = true;
                String font = child.getAsJsonObject().has("font") ? child.getAsJsonObject().get("font").getAsString() : null;
                Shown shown = font == null ? null : slots.apply(slot);
                if (shown != null) {
                    for (JsonObject part : icon(shown, font)) {
                        next.add(part);
                    }
                }
            }
            return next;
        }
        if (!e.isJsonObject()) {
            return e;
        }
        JsonObject next = new JsonObject();
        for (java.util.Map.Entry<String, JsonElement> entry : e.getAsJsonObject().entrySet()) {
            next.add(entry.getKey(), walk(entry.getValue(), slots, known, changed));
        }
        DialogSlots.Key slot = marker(next, SLOT);
        if (slot != null) {
            changed[0] = true;
            next.remove("insertion");
            Shown shown = slots.apply(slot);
            if (shown != null && shown.hover() != null) {
                next.add("hover_event", shown.hover());
            }
        }
        JsonElement hover = next.get("hover_event");
        if (hover != null && hover.isJsonObject() && isShowItem(hover.getAsJsonObject()) && !known.test(itemId(hover.getAsJsonObject()))) {
            changed[0] = true;
            next.remove("hover_event");
        }
        return next;
    }

    /**
     * The slot a marker names, when {@code e} is one with this prefix and the
     * container is one this engine fills; null otherwise, which leaves the
     * marker as Studio wrote it — an empty text, an empty slot.
     */
    private static DialogSlots.Key marker(JsonElement e, String prefix) {
        if (e == null || !e.isJsonObject()) {
            return null;
        }
        JsonElement insertion = e.getAsJsonObject().get("insertion");
        if (insertion == null || !insertion.isJsonPrimitive() || !insertion.getAsJsonPrimitive().isString()) {
            return null;
        }
        String value = insertion.getAsString();
        if (!value.startsWith(prefix)) {
            return null;
        }
        return DialogSlots.Key.parse(value.substring(prefix.length()))
                .filter(key -> DialogSlots.known(key.container()) && key.index() < 128)
                .orElse(null);
    }

    private static boolean isShowItem(JsonObject hover) {
        JsonElement action = hover.get("action");
        return action != null && action.isJsonPrimitive() && "show_item".equals(action.getAsString());
    }

    private static String itemId(JsonObject hover) {
        JsonElement id = hover.get("id");
        return id != null && id.isJsonPrimitive() ? id.getAsString() : "";
    }

    /**
     * What a marker becomes: the icon, untinted and with no shadow (it is a
     * picture, and the game would draw a darker copy of it a pixel off); then
     * the count, white with the game's shadow as a stack's is, after its twin;
     * then the step back over the icon.
     */
    static List<JsonObject> icon(Shown shown, String font) {
        char glyph = shown.custom() ? (char) DialogItemIcons.BASE : DialogItemIcons.glyph(shown.id());
        JsonObject picture = new JsonObject();
        // A stack picked up: the light, a step back over it, then the item on it.
        picture.addProperty("text", shown.held()
                ? "" + (char) DialogItemIcons.HELD + (char) BACK + glyph
                : String.valueOf(glyph));
        picture.addProperty("font", font);
        picture.addProperty("color", "white");
        picture.addProperty("shadow_color", 0);
        StringBuilder rest = new StringBuilder();
        if (shown.count() > 1) {
            String digits = Integer.toString(shown.count());
            for (int i = 0; i < digits.length(); i++) {
                rest.append((char) (TWIN_BASE + (digits.charAt(i) - '0')));
            }
            rest.append(digits);
        }
        rest.append((char) BACK);
        JsonObject after = new JsonObject();
        after.addProperty("text", rest.toString());
        after.addProperty("font", font);
        after.addProperty("color", "white");
        return List.of(picture, after);
    }

    /** What the player keeps in a slot, or null for nothing. */
    private static Shown shown(Player viewer, DialogSlots.Key slot, boolean held) {
        ItemStack stack;
        try {
            stack = DialogSlots.get(viewer, slot);
        } catch (RuntimeException e) {
            return null;
        }
        if (stack == null) {
            return null;
        }
        String id = stack.getType().getKey().toString();
        return new Shown(id, stack.getAmount(), wearsOwnModel(stack), hover(stack, id), held);
    }

    /**
     * An item's tooltip as a hover: the stack itself, written by the server's
     * own codec — every component, so the tooltip is exactly the one the
     * inventory shows. Where the engine cannot reach the codec (Spigot), the id,
     * the count and what Bukkit's meta can say: the name and the lore.
     */
    static JsonObject hover(ItemStack stack, String id) {
        JsonObject hover = new JsonObject();
        hover.addProperty("action", "show_item");
        JsonObject full = DialogPackets.itemJson(stack);
        if (full != null && full.has("id")) {
            for (java.util.Map.Entry<String, JsonElement> entry : full.entrySet()) {
                hover.add(entry.getKey(), entry.getValue());
            }
            return hover;
        }
        hover.addProperty("id", id);
        hover.addProperty("count", Math.max(1, Math.min(99, stack.getAmount())));
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            JsonObject components = new JsonObject();
            if (meta.hasDisplayName()) {
                components.add("minecraft:custom_name", legacy(meta.getDisplayName()));
            }
            List<String> lore = meta.hasLore() ? meta.getLore() : null;
            if (lore != null && !lore.isEmpty()) {
                JsonArray lines = new JsonArray();
                for (String line : lore) {
                    lines.add(legacy(line));
                }
                components.add("minecraft:lore", lines);
            }
            if (components.size() > 0) {
                hover.add("components", components);
            }
        }
        return hover;
    }

    /** Words in the legacy colour codes plugins name items with: the client still reads a § inside a text. */
    private static JsonObject legacy(String text) {
        JsonObject out = new JsonObject();
        out.addProperty("text", text == null ? "" : text);
        return out;
    }

    /**
     * Whether a stack wears a model of its own — custom model data, or an item
     * model that is not its material's — whose picture is not in the icon font.
     * Such an item is drawn as the "no picture" icon rather than as whatever
     * vanilla item it happens to be made from.
     */
    static boolean wearsOwnModel(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        try {
            if (meta.hasCustomModelData()) {
                return true;
            }
        } catch (RuntimeException | LinkageError ignored) {
            // An API without it: nothing to say.
        }
        try {
            // 1.21.4 added the item model component; reached by name so older APIs still load this class.
            Object has = meta.getClass().getMethod("hasItemModel").invoke(meta);
            if (Boolean.TRUE.equals(has)) {
                Object model = meta.getClass().getMethod("getItemModel").invoke(meta);
                return model != null && !model.toString().equals(stack.getType().getKey().toString());
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // Older than item models: custom model data was the only way.
        }
        return false;
    }

    /** Whether this server has an item by that id — what its dialog codec will accept in a tooltip. */
    static boolean known(String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        try {
            Material material = Material.matchMaterial(id);
            return material != null && material.isItem();
        } catch (RuntimeException | LinkageError e) {
            return true;
        }
    }
}
