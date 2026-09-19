package ai.resourcepack.engine.core.pack;

/**
 * The {@code pack.mcmeta} for a datapack this engine generates.
 *
 * <p>There are two of them — the dialogs and the liquid colours — and both got
 * this wrong in the same way, so it is written once here.
 *
 * <p><strong>A generated datapack has to declare its format three times, and
 * leaving any of them out makes it invisible on some version of the game.</strong>
 * Mojang has changed how a pack states what it runs on twice:
 *
 * <ul>
 *   <li>{@code pack_format} — one number, the only field the oldest versions
 *       read. A pack whose number is not the server's own reads as out of date.
 *   <li>{@code supported_formats} — a range, honoured from 1.20.2 up to and
 *       including 1.21.8. Where it is read it wins over {@code pack_format},
 *       which is what lets one generated pack cover several versions.
 *   <li>{@code min_format} / {@code max_format} — the same idea renamed in
 *       25w31a (1.21.9), where {@code supported_formats} was <em>removed</em>.
 * </ul>
 *
 * <p>The cost of missing the last one is the thing worth remembering, because
 * it fails silently in both directions: a pack declaring only
 * {@code supported_formats} on 1.21.9+ is read as whatever its bare
 * {@code pack_format} says — for the dialogs pack, 1.21.5 against a 1.21.9
 * server — so the pack is INCOMPATIBLE, {@code /minecraft:reload} does not
 * enable it, nothing is written to the log because nothing was ever read, and
 * the only symptom is a registry that does not contain what the pack holds.
 * That presented as "I reload and the dialog still is not loaded", for ever.
 *
 * <p>Carrying all three is legal everywhere: a version that has not heard of a
 * field ignores it, which is exactly why the newest two can be added without
 * dropping the oldest.
 */
public final class DataPackMeta {

    /**
     * How far ahead a generated pack claims to work.
     *
     * <p>Deliberately enormous. What these packs contain is a handful of files
     * whose schema is the game's own, and a version that changes one will say
     * so by failing to read the file — which is a message somebody can act on.
     * A ceiling that has to be raised every release is a pack that stops
     * working on release day for no reason anybody can see.
     */
    private static final int OPEN_ENDED = 9999;

    private DataPackMeta() {
    }

    /**
     * @param description what the pack list shows
     * @param minFormat   the data-pack format of the oldest Minecraft this
     *                    pack's contents make sense on — NOT the running
     *                    server's, which nothing here needs to know
     */
    public static String mcmeta(String description, int minFormat) {
        return "{\n"
                + "  \"pack\": {\n"
                + "    \"description\": \"" + description + "\",\n"
                + "    \"pack_format\": " + minFormat + ",\n"
                + "    \"supported_formats\": { \"min_inclusive\": " + minFormat
                + ", \"max_inclusive\": " + OPEN_ENDED + " },\n"
                + "    \"min_format\": " + minFormat + ",\n"
                + "    \"max_format\": " + OPEN_ENDED + "\n"
                + "  }\n"
                + "}\n";
    }
}
