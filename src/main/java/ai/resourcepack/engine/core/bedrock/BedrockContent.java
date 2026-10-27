package ai.resourcepack.engine.core.bedrock;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.core.pack.DeterministicZip;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The Bedrock twin of the server's own content: a {@code .mcpack} built from
 * the Java pack this engine just built, plus the item mappings Geyser needs to
 * show that pack's items.
 *
 * <p>Deliberately free of Geyser and Bukkit, so it can be built and tested on
 * any server; only {@link GeyserBridge} hands the result to Geyser.
 *
 * <p>What crosses, and why each is a rename rather than a conversion:
 * <ul>
 *   <li><strong>Items with a flat sprite.</strong> The sprite becomes a Bedrock
 *       item icon, and the {@link Registration} tells Geyser which Java stacks
 *       are that item: the {@code item_model} on 1.21.4+, the custom model
 *       data number below it. A 3D item is registered only if it also ships a
 *       sprite at its texture path — Bedrock has no way to draw a Java block
 *       model in a hand, and an icon of nothing is worse than the base item.</li>
 *   <li><strong>Armour</strong> on those items: an attachable over Bedrock's
 *       own armour geometry, textured with the item's equipment layer.</li>
 *   <li><strong>Sounds.</strong> Geyser plays a Java sound it has no mapping
 *       for under the Java name minus "minecraft:", so a content sound,
 *       played as {@code ns:path}, reaches Bedrock as exactly that key.</li>
 *   <li><strong>Icons</strong>, as Bedrock glyph pages: Geyser hands chat text
 *       over character for character, and a Private Use Area character is
 *       looked up in {@code font/glyph_<page>.png}.</li>
 * </ul>
 *
 * <p>What does not: screens, HUD overlays, shaders, dialogs, placed models and
 * emotes. Each is drawn by a mechanism Bedrock does not have, and pretending
 * otherwise ships files nothing reads.
 */
public final class BedrockContent {

    private BedrockContent() {
    }

    /** One content item, as the build needs it. */
    public record Item(ContentId id, String material, String displayName, String texture,
                       ContentId modelId, Integer modelNumber, String armorSlot) {
    }

    /**
     * One chat icon.
     *
     * <p>{@code rows}, {@code columns} and {@code cell} say which part of the
     * PNG it is when the icon is one cell of a sheet. Java needs no cropping
     * for that - its bitmap provider splits the sheet itself - but a Bedrock
     * glyph page is pictures laid out by us, so the cell is cut out here.
     */
    public record Icon(int codepoint, String zipPath, int rows, int columns, int cell) {
        /** A whole-file icon, which is every icon that is not on a sheet. */
        public Icon(int codepoint, String zipPath) {
            this(codepoint, zipPath, 1, 1, 1);
        }
    }

    /**
     * One placeable model: the id its hitbox carries, and where the block
     * model it is drawn with sits in the Java pack.
     */
    public record Model(ContentId id, String modelZipPath, float scale) {
    }

    /**
     * Everything the build reads besides the Java pack.
     *
     * @param vanilla    a vanilla texture path ({@code block/stone}) to its
     *                   image, or null for "not to hand"; may itself be null
     * @param soundAlias a vanilla Java sound event to the Bedrock sound Geyser
     *                   plays for it, or null when it plays none by name; may
     *                   itself be null, which leaves vanilla replacements out
     */
    public record Inputs(List<Item> items, List<Icon> icons, List<Model> models,
                         java.util.function.Function<String, BufferedImage> vanilla,
                         java.util.function.UnaryOperator<String> soundAlias) {
    }

    /** How many placeable models one pack can draw for Bedrock; the bridge registers this many. */
    public static final int MODEL_POOL = 200;

    /** The entity a model in slot {@code slot} is spawned as. */
    public static String modelIdentifier(int slot) {
        return "rpengine:model_" + slot;
    }

    /** The Bedrock-safe name a model's geometry, texture and animations are filed under. */
    public static String modelKey(String contentId) {
        return sanitize(contentId);
    }

    /**
     * What Geyser is told about one item.
     *
     * @param javaModel   the {@code item_model} a stack carries on 1.21.4+, or
     *                    null below it
     * @param legacyNumber the custom model data number below 1.21.4, or null
     * @param armorSlot   {@code head}, {@code chest}, {@code legs}, {@code feet}
     *                    or null
     */
    public record Registration(String bedrockId, String javaItem, String javaModel, Integer legacyNumber,
                               String icon, String displayName, String armorSlot) {
    }

    /**
     * The built pack, or null when nothing crossed; and what Geyser needs.
     *
     * @param modelSlots  a model's content id to its entity slot
     * @param modelScales a model's content id to the size it is placed at
     */
    public record Result(Path file, List<Registration> items, int sounds, int icons, int armour,
                         Map<String, Integer> modelSlots, Map<String, Float> modelScales) {
        public boolean empty() {
            return file == null;
        }

        public int models() {
            return modelSlots.size();
        }
    }

    // Bedrock's glyph cells are sized per page; small icons get small cells.
    private static final int GLYPH_MIN_CELL = 16;
    private static final int GLYPH_MAX_CELL = 128;

    /**
     * Builds {@code <out>/<bundle>.mcpack} from the Java pack at {@code javaZip}.
     * Removes a stale one when nothing crossed.
     */
    public static Result build(Path javaZip, List<Item> items, List<Icon> icons, Path out, String bundle,
                               String description) throws IOException {
        return build(javaZip, new Inputs(items, icons, List.of(), null, null), out, bundle, description);
    }

    public static Result build(Path javaZip, Inputs inputs, Path out, String bundle,
                               String description) throws IOException {
        List<Item> items = inputs.items();
        List<Icon> icons = inputs.icons();
        Map<String, byte[]> files = new TreeMap<>();
        Map<String, Integer> modelSlots = new TreeMap<>();
        Map<String, Float> modelScales = new TreeMap<>();
        List<Registration> registrations = new ArrayList<>();
        int sounds;
        int glyphs;
        int armour = 0;

        try (ZipFile zip = new ZipFile(javaZip.toFile())) {
            // --- Items -----------------------------------------------------
            JsonObject textureData = new JsonObject();
            for (Item item : items) {
                String sid = sanitize(item.id().namespace() + "_" + item.id().path());
                byte[] sprite = read(zip, ai.resourcepack.engine.core.item.Geometry.zipPathOf(
                        ai.resourcepack.engine.core.item.ModelSources.textureLocation(
                                item.modelId().namespace(), item.texture())));
                if (sprite == null) continue;
                String iconKey = "rpe_" + sid;
                files.put("textures/items/rpe/" + sid + ".png", sprite);
                JsonObject entry = new JsonObject();
                entry.addProperty("textures", "textures/items/rpe/" + sid);
                textureData.add(iconKey, entry);

                String bedrockId = "rpengine:" + sid;
                String slot = normaliseSlot(item.armorSlot());
                if (slot != null) {
                    String layer = slot.equals("legs") ? "humanoid_leggings" : "humanoid";
                    byte[] sheet = read(zip, "assets/" + item.id().namespace() + "/textures/entity/equipment/"
                            + layer + "/" + item.id().path() + ".png");
                    if (sheet != null) {
                        files.put("textures/models/armor/rpe_" + sid + ".png", sheet);
                        files.put("attachables/rpe_" + sid + ".json", attachable(bedrockId, sid, slot));
                        armour++;
                    } else {
                        slot = null;
                    }
                }
                registrations.add(new Registration(
                        bedrockId,
                        "minecraft:" + item.material().toLowerCase(Locale.ROOT),
                        item.modelNumber() == null ? item.modelId().toString() : null,
                        item.modelNumber(),
                        iconKey,
                        item.displayName(),
                        slot));
            }
            if (!registrations.isEmpty()) {
                JsonObject atlas = new JsonObject();
                atlas.addProperty("resource_pack_name", "rpengine");
                atlas.addProperty("texture_name", "atlas.items");
                atlas.add("texture_data", textureData);
                files.put("textures/item_texture.json", json(atlas));
            }

            // --- Sounds ----------------------------------------------------
            JsonObject definitions = new JsonObject();
            sounds = 0;
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                String name = entry.getName();
                if (!name.startsWith("assets/") || !name.endsWith("/sounds.json")) continue;
                String namespace = name.substring("assets/".length(), name.length() - "/sounds.json".length());
                if (namespace.contains("/")) continue;
                // A replaced VANILLA sound reaches Bedrock only under the name
                // Geyser plays it by, which only Geyser knows.
                boolean vanilla = namespace.equals("minecraft");
                if (vanilla && inputs.soundAlias() == null) continue;
                JsonObject events;
                try (InputStream in = zip.getInputStream(entry)) {
                    events = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                            .getAsJsonObject();
                } catch (RuntimeException bad) {
                    continue;
                }
                for (Map.Entry<String, JsonElement> event : events.entrySet()) {
                    if (!event.getValue().isJsonObject()) continue;
                    JsonObject java = event.getValue().getAsJsonObject();
                    JsonArray bedrockSounds = new JsonArray();
                    JsonArray javaSounds = java.has("sounds") ? java.getAsJsonArray("sounds") : new JsonArray();
                    for (JsonElement sound : javaSounds) {
                        String ref = sound.isJsonObject() ? sound.getAsJsonObject().get("name").getAsString()
                                : sound.getAsString();
                        int colon = ref.indexOf(':');
                        String ns = colon < 0 ? "minecraft" : ref.substring(0, colon);
                        String file = colon < 0 ? ref : ref.substring(colon + 1);
                        byte[] ogg = read(zip, "assets/" + ns + "/sounds/" + file + ".ogg");
                        if (ogg == null) continue;
                        String target = "sounds/rpe/" + ns + "/" + file;
                        files.put(target + ".ogg", ogg);
                        JsonObject out1 = new JsonObject();
                        out1.addProperty("name", target);
                        if (sound.isJsonObject() && sound.getAsJsonObject().has("stream")
                                && sound.getAsJsonObject().get("stream").getAsBoolean()) {
                            out1.addProperty("stream", true);
                        }
                        bedrockSounds.add(out1);
                    }
                    if (bedrockSounds.isEmpty()) continue;
                    JsonObject def = new JsonObject();
                    String category = java.has("category") ? java.get("category").getAsString() : "master";
                    def.addProperty("category", bedrockCategory(category));
                    def.add("sounds", bedrockSounds);
                    String bedrockName = vanilla ? inputs.soundAlias().apply(event.getKey())
                            : namespace + ":" + event.getKey();
                    if (bedrockName == null || bedrockName.isEmpty()) continue;
                    definitions.add(bedrockName, def);
                    sounds++;
                }
            }
            if (sounds > 0) {
                JsonObject root = new JsonObject();
                root.addProperty("format_version", "1.20.20");
                root.add("sound_definitions", definitions);
                files.put("sounds/sound_definitions.json", json(root));
            }

            // --- Icons -----------------------------------------------------
            List<Glyph> decoded = new ArrayList<>();
            for (Icon icon : icons) {
                if (icon.codepoint() < 0xE000 || icon.codepoint() > 0xF8FF) continue;
                byte[] png = read(zip, icon.zipPath());
                if (png == null) continue;
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
                if (image != null) image = cellOf(image, icon);
                if (image != null) decoded.add(new Glyph(icon.codepoint(), image));
            }
            glyphs = decoded.size();
            files.putAll(glyphPages(decoded));

            byte[] packPng = read(zip, "pack.png");
            if (packPng != null) files.put("pack_icon.png", packPng);

            // --- Placeable models ------------------------------------------
            // Sorted, so a model keeps its slot while the set is unchanged; the
            // bridge reads the slots a session was actually served, so a reload
            // that reshuffles them is still drawn right for everybody.
            List<Model> models = new ArrayList<>(inputs.models());
            models.sort(java.util.Comparator.comparing(m -> m.id().toString()));
            for (Model model : models) {
                if (modelSlots.size() >= MODEL_POOL) break;
                byte[] source = read(zip, model.modelZipPath());
                if (source == null) continue;
                JsonObject json;
                try {
                    json = JsonParser.parseString(new String(source, StandardCharsets.UTF_8)).getAsJsonObject();
                } catch (RuntimeException bad) {
                    continue;
                }
                int slot = modelSlots.size() + 1;
                String key = modelKey(model.id().toString());
                java.util.Optional<BedrockGeometry.Converted> converted = BedrockGeometry.convert(
                        modelIdentifier(slot), key, json, ref -> texture(zip, ref, inputs.vanilla()));
                if (converted.isEmpty()) continue;
                ByteArrayOutputStream atlas = new ByteArrayOutputStream();
                ImageIO.write(converted.get().atlas(), "png", atlas);
                files.put("textures/entity/rpe_" + key + ".png", atlas.toByteArray());
                files.put("models/entity/rpe_" + key + ".geo.json", json(converted.get().geometry()));
                files.put("entity/rpe_" + key + ".entity.json", json(converted.get().entity()));
                if (converted.get().animations() != null) {
                    files.put("animations/rpe_" + key + ".animation.json", json(converted.get().animations()));
                }
                modelSlots.put(model.id().toString(), slot);
                modelScales.put(model.id().toString(), model.scale());
            }
        }

        Path target = out.resolve(bundle + ".mcpack");
        boolean anything = !registrations.isEmpty() || sounds > 0 || glyphs > 0 || !modelSlots.isEmpty();
        if (!anything) {
            Files.deleteIfExists(target);
            return new Result(null, List.of(), 0, 0, 0, Map.of(), Map.of());
        }

        // The version has to change when the content does — a Bedrock client
        // keeps a cached pack whose uuid and version it already holds — and
        // must stay small: a large component makes the client reject the
        // manifest outright. Derived from the content, so an unchanged build
        // is not a redownload.
        int hash = files.entrySet().stream()
                .mapToInt(f -> f.getKey().hashCode() * 31 + java.util.Arrays.hashCode(f.getValue()))
                .reduce(17, (a, b) -> a * 31 + b);
        int minor = Math.floorMod(hash >>> 16, 1000);
        int patch = Math.floorMod(hash, 1000);
        files.put("manifest.json", json(manifest(bundle, description, minor, patch)));

        DeterministicZip zipOut = new DeterministicZip();
        for (Map.Entry<String, byte[]> file : files.entrySet()) zipOut.add(file.getKey(), file.getValue());
        Files.createDirectories(out);
        zipOut.writeTo(target);
        return new Result(target, List.copyOf(registrations), sounds, glyphs, armour,
                java.util.Collections.unmodifiableMap(modelSlots), java.util.Collections.unmodifiableMap(modelScales));
    }

    // --- Pieces ---------------------------------------------------------------

    private record Glyph(int codepoint, BufferedImage image) {
    }

    /**
     * The part of a sheet one icon is, cut the way Java's bitmap provider cuts
     * it: equal cells, the remainder of an uneven division left off the right
     * and bottom. Null for a sheet too small to have that many cells.
     */
    static BufferedImage cellOf(BufferedImage sheet, Icon icon) {
        if (icon.rows() <= 1 && icon.columns() <= 1) return sheet;
        int width = sheet.getWidth() / icon.columns();
        int height = sheet.getHeight() / icon.rows();
        if (width < 1 || height < 1) return null;
        int index = icon.cell() - 1;
        return sheet.getSubimage((index % icon.columns()) * width, (index / icon.columns()) * height,
                width, height);
    }

    static Map<String, byte[]> glyphPages(List<Glyph> glyphs) throws IOException {
        Map<Integer, List<Glyph>> pages = new TreeMap<>();
        for (Glyph glyph : glyphs) pages.computeIfAbsent(glyph.codepoint() >> 8, k -> new ArrayList<>()).add(glyph);
        Map<String, byte[]> out = new TreeMap<>();
        for (Map.Entry<Integer, List<Glyph>> page : pages.entrySet()) {
            int largest = 1;
            for (Glyph g : page.getValue()) largest = Math.max(largest, Math.max(g.image().getWidth(), g.image().getHeight()));
            int cell = GLYPH_MIN_CELL;
            while (cell < largest && cell < GLYPH_MAX_CELL) cell *= 2;
            int size = cell * 16;
            BufferedImage sheet = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            for (Glyph g : page.getValue()) {
                int index = g.codepoint() & 0xFF;
                int cx = (index % 16) * cell;
                int cy = (index / 16) * cell;
                double fit = Math.min((double) cell / g.image().getWidth(), (double) cell / g.image().getHeight());
                int w = Math.max(1, (int) Math.round(g.image().getWidth() * fit));
                int h = Math.max(1, (int) Math.round(g.image().getHeight() * fit));
                int top = cy + (cell - h) / 2;
                // Nearest neighbour, by hand: pixel art must not be smoothed,
                // and left-aligned because the client measures a glyph's width
                // from its rightmost opaque column.
                for (int y = 0; y < h; y++) {
                    int sy = Math.min(g.image().getHeight() - 1, y * g.image().getHeight() / h);
                    for (int x = 0; x < w; x++) {
                        int sx = Math.min(g.image().getWidth() - 1, x * g.image().getWidth() / w);
                        sheet.setRGB(cx + x, top + y, g.image().getRGB(sx, sy));
                    }
                }
            }
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(sheet, "png", png);
            out.put(String.format(Locale.ROOT, "font/glyph_%02X.png", page.getKey()), png.toByteArray());
        }
        return out;
    }

    private static byte[] attachable(String bedrockId, String sid, String slot) {
        String geometry;
        String hide;
        switch (slot) {
            case "head" -> {
                geometry = "geometry.humanoid.armor.helmet";
                hide = "variable.helmet_layer_visible = 0.0;";
            }
            case "chest" -> {
                geometry = "geometry.humanoid.armor.chestplate";
                hide = "variable.chest_layer_visible = 0.0;";
            }
            case "legs" -> {
                geometry = "geometry.humanoid.armor.leggings";
                hide = "variable.leg_layer_visible = 0.0;";
            }
            default -> {
                geometry = "geometry.humanoid.armor.boots";
                hide = "variable.boot_layer_visible = 0.0;";
            }
        }
        JsonObject description = new JsonObject();
        description.addProperty("identifier", bedrockId);
        JsonObject materials = new JsonObject();
        materials.addProperty("default", "armor");
        materials.addProperty("enchanted", "armor_enchanted");
        description.add("materials", materials);
        JsonObject textures = new JsonObject();
        textures.addProperty("default", "textures/models/armor/rpe_" + sid);
        textures.addProperty("enchanted", "textures/misc/enchanted_actor_glint");
        description.add("textures", textures);
        JsonObject geo = new JsonObject();
        geo.addProperty("default", geometry);
        description.add("geometry", geo);
        JsonObject scripts = new JsonObject();
        scripts.addProperty("parent_setup", hide);
        description.add("scripts", scripts);
        JsonArray controllers = new JsonArray();
        controllers.add("controller.render.armor");
        description.add("render_controllers", controllers);
        JsonObject body = new JsonObject();
        body.add("description", description);
        JsonObject root = new JsonObject();
        root.addProperty("format_version", "1.10.0");
        root.add("minecraft:attachable", body);
        return json(root);
    }

    private static JsonObject manifest(String bundle, String description, int minor, int patch) {
        JsonArray version = new JsonArray();
        version.add(1);
        version.add(minor);
        version.add(patch);
        JsonArray engine = new JsonArray();
        engine.add(1);
        engine.add(21);
        engine.add(0);

        JsonObject header = new JsonObject();
        header.addProperty("name", description == null || description.isBlank() ? "RP Engine" : description);
        header.addProperty("description", "Built by RP Engine");
        header.addProperty("uuid", uuid("rpengine:bedrock:" + bundle));
        header.add("version", version);
        header.add("min_engine_version", engine);

        JsonObject module = new JsonObject();
        module.addProperty("type", "resources");
        module.addProperty("uuid", uuid("rpengine:bedrock-module:" + bundle));
        module.add("version", version.deepCopy());
        JsonArray modules = new JsonArray();
        modules.add(module);

        JsonObject root = new JsonObject();
        root.addProperty("format_version", 2);
        root.add("header", header);
        root.add("modules", modules);
        return root;
    }

    /** Java's sound categories onto Bedrock's, which has no master or voice. */
    static String bedrockCategory(String java) {
        return switch (java == null ? "" : java.toLowerCase(Locale.ROOT)) {
            case "music" -> "music";
            case "record", "records" -> "record";
            case "weather" -> "weather";
            case "block", "blocks" -> "block";
            case "hostile" -> "hostile";
            case "player", "players", "voice" -> "player";
            case "ambient" -> "ambient";
            default -> "neutral";
        };
    }

    private static String normaliseSlot(String slot) {
        if (slot == null) return null;
        return switch (slot.toLowerCase(Locale.ROOT)) {
            case "head", "helmet" -> "head";
            case "chest", "chestplate" -> "chest";
            case "legs", "leggings" -> "legs";
            case "feet", "boots" -> "feet";
            default -> null;
        };
    }

    /** A Bedrock-safe identifier: lower case, [a-z0-9_] only. */
    static String sanitize(String name) {
        String cleaned = name.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s/:.-]+", "_")
                .replaceAll("[^a-z0-9_]", "")
                .replaceAll("_{2,}", "_")
                .replaceAll("^_+|_+$", "");
        return cleaned.isEmpty() ? "unnamed" : cleaned;
    }

    private static String uuid(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** A model's texture: the pack's own copy, else the game's, else nothing. */
    private static BufferedImage texture(ZipFile zip, String ref,
                                         java.util.function.Function<String, BufferedImage> vanilla) {
        int colon = ref.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : ref.substring(0, colon);
        String path = colon < 0 ? ref : ref.substring(colon + 1);
        try {
            byte[] png = read(zip, "assets/" + namespace + "/textures/" + path + ".png");
            if (png != null) return ImageIO.read(new ByteArrayInputStream(png));
        } catch (IOException ignored) {
            // Falls through to the game's copy.
        }
        return namespace.equals("minecraft") && vanilla != null ? vanilla.apply(path) : null;
    }

    private static byte[] read(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) return null;
        try (InputStream in = zip.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    private static byte[] json(JsonObject object) {
        return new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
                .toJson(object).getBytes(StandardCharsets.UTF_8);
    }
}
