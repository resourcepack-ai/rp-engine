package ai.resourcepack.engine.core.item;

import ai.resourcepack.engine.api.Bundle;
import ai.resourcepack.engine.api.ContentDefinition;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentKind;
import ai.resourcepack.engine.api.ItemInfo;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.api.ModelInfo;
import ai.resourcepack.engine.core.model.ModelDefinitions;
import ai.resourcepack.engine.core.model.ModelRigs;
import ai.resourcepack.engine.core.pack.PackContributor;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Writes the two files an item needs to render, so nobody has to write them by
 * hand.
 *
 * <p>Since 1.21.4 an item's appearance is chosen by the string-valued
 * {@code minecraft:item_model} component, which names an <em>item
 * definition</em> at {@code assets/<namespace>/items/<path>.json}. That file
 * points at a model, and the model points at a texture. So one item id becomes:
 *
 * <pre>
 *   assets/mypack/items/ruby.json          the item definition
 *   assets/mypack/models/item/ruby.json    the model
 *   assets/mypack/textures/item/ruby.png   shipped by the author
 * </pre>
 *
 * <p>Only the first two are generated. The texture is the author's, and the
 * whole point of the id scheme is that its path falls out of the id rather than
 * being allocated: {@code mypack:ruby} is {@code item/ruby} unless the
 * definition says otherwise.
 *
 * <p>An item that borrows another's {@code model:} generates nothing at all,
 * because the files it would write already exist under the id it borrowed.
 */
public final class ItemAssets implements PackContributor {

    private Bundle bundle;

    /**
     * How this server addresses a model, and the numbers to do it with.
     *
     * <p>Null on the definitions era, where nothing is numbered. That is a
     * deliberate null rather than an empty allocator: asking for a number on a
     * server that does not use them would fill a file on disk with assignments
     * nothing reads, and that file is the one thing here that must not drift.
     */
    private final ModelNumbers numbers;
    private final boolean numbered;

    /**
     * Whether this server draws worn armour from the pack's equipment assets
     * ({@code Feature.ARMOUR_ART}). Below it the files are still written, so
     * the pack is the same everywhere, but the build says which items will
     * be worn with vanilla art.
     */
    private final boolean armourArt;

    /** {@code armor-art:} by item, read for this build; see {@link #readArmorArt}. */
    private final Map<ContentId, String> armorArt = new LinkedHashMap<>();
    /** {@code first-person:}, read straight off the definitions like {@code armor-art:}. */
    private final Map<ContentId, String> firstPerson = new LinkedHashMap<>();
    /** {@code pull:}, {@code charged:} and {@code charged-firework:}; see {@link UseStates}. */
    private final Map<ContentId, UseStates> useStates = new LinkedHashMap<>();

    /** The armour this build wrote that the server will draw with vanilla art. */
    private final List<String> vanillaDrawn = new ArrayList<>();

    /** Definitions era: models are named, and nothing is allocated. */
    public ItemAssets() {
        this(null);
    }

    /**
     * @param numbers the allocator on a version that addresses models by
     *                number, or null on 1.21.4 and up
     */
    public ItemAssets(ModelNumbers numbers) {
        this(numbers, true);
    }

    /**
     * @param numbers   as above
     * @param armourArt whether the server draws worn armour from the pack's
     *                  equipment assets, 1.21.4 and up
     */
    public ItemAssets(ModelNumbers numbers, boolean armourArt) {
        this.numbers = numbers;
        this.numbered = numbers != null;
        this.armourArt = armourArt;
    }

    /**
     * Every custom model reachable from one vanilla material, gathered as the
     * items are written and flushed into that material's own model file at the
     * end — see {@link LegacyItemModels}. Empty on the definitions era.
     */
    private final Map<String, List<LegacyItemModels.Override>> legacyBases = new LinkedHashMap<>();
    private final Map<String, ModelRigs.Rig> rigs = new LinkedHashMap<>();
    /** Material and number pairs already in {@link #legacyBases}, so one model is never listed twice. */
    private final Set<String> legacySeen = new HashSet<>();

    /**
     * Items no definition file declares: the one each custom block is placed
     * by, which the plugin makes from the block. Without these written the
     * item in a player's hand named a model the pack did not have.
     */
    private java.util.Collection<ItemInfo> blockItems = List.of();

    /** The items custom blocks are placed by, written beside the declared ones. */
    public ItemAssets withBlockItems(java.util.Collection<ItemInfo> items) {
        this.blockItems = items == null ? List.of() : List.copyOf(items);
        return this;
    }

    /**
     * The rigs this build found, keyed by model id.
     *
     * <p>A by-product rather than a file: everything else a contributor
     * produces goes into the zip, but a rig is what the SERVER needs in order
     * to move the displays, and the client is told nothing about it. The
     * plugin reads this after the build and hands it to the rig store, which
     * is the same place a studio push puts one.
     */
    public Map<String, ModelRigs.Rig> rigs() {
        return Map.copyOf(rigs);
    }

    @Override
    public void contribute(Bundle bundle, LoadReport loaded, Contribution into) {
        this.bundle = bundle;
        legacyBases.clear();
        legacySeen.clear();
        vanillaDrawn.clear();
        readArmorArt(loaded);
        readFirstPerson(loaded);
        readUseStates(loaded, into);
        ItemDefinitions.Result parsed = ItemDefinitions.parse(loaded);
        for (ItemInfo item : parsed.items().values()) {
            if (!bundle.namespaces().contains(item.id().namespace())) {
                continue;
            }
            if (item.copiedFrom().isPresent()) {
                // Copied. The files are already written under the id it
                // points at, and writing them again here would be two packs
                // fighting over one path.
                continue;
            }
            writeItem(item, into);
        }
        for (ItemInfo item : blockItems) {
            if (!bundle.namespaces().contains(item.id().namespace()) || parsed.items().containsKey(item.id())
                    || (item.model().isEmpty() && item.texture().isEmpty())) {
                continue;
            }
            writeItem(item, into);
        }
        writeLooks(loaded, parsed.items(), into);
        writeLegacyBases(into);
        if (!vanillaDrawn.isEmpty()) {
            // One line for the lot rather than one per piece: it is a fact
            // about the server, and each item is still named.
            into.warn("items", String.join(", ", vanillaDrawn),
                    "worn armour draws with vanilla art on this Minecraft, which reads a pack's equipment "
                            + "art from 1.21.4. The art is packed and the items work; run 1.21.4 or newer to see it.");
        }
    }

    /**
     * {@code armor-art:}, read straight off the definitions.
     *
     * <p>A build instruction rather than a property of the item - nothing at
     * give time looks at it, only this class, which copies the PNG it names
     * to where the game reads worn armour - so it is not carried on
     * {@link ItemInfo}. It is the same reference as {@code texture:}: a path
     * under the pack's own {@code textures/}, or a resource location.
     */
    private void readArmorArt(LoadReport loaded) {
        armorArt.clear();
        if (loaded == null) {
            return;
        }
        for (ContentDefinition definition : loaded.definitions(ContentKind.ITEM)) {
            definition.body().string("armor-art")
                    .map(String::trim)
                    .filter(art -> !art.isEmpty())
                    .map(art -> art.endsWith(".png") ? art.substring(0, art.length() - 4) : art)
                    .ifPresent(art -> armorArt.put(definition.id(), art));
        }
    }

    /**
     * {@code first-person:}: the model an item is drawn with while its holder
     * looks at it, in place of its own.
     *
     * <p>What it is for is a gun that is aimed: down the sights the parts behind
     * the optic (a hopper, a stock) are between the eye and everything else, so
     * the first-person model leaves them out, and everybody else still sees the
     * whole thing. Like {@code armor-art:} it is a build instruction and not a
     * property of the item, so it is read here rather than carried on
     * {@link ItemInfo}.
     */
    private void readFirstPerson(LoadReport loaded) {
        firstPerson.clear();
        if (loaded == null) {
            return;
        }
        for (ContentDefinition definition : loaded.definitions(ContentKind.ITEM)) {
            String model = ItemDefinitions.model(definition.body(), "first-person");
            if (model != null && !model.isBlank()) {
                firstPerson.put(definition.id(), model.trim());
            }
        }
    }

    /**
     * The looks an item takes while it is used, read off the definitions the
     * same way. What cannot be used is reported against the item here, once,
     * rather than again on every era it is written for.
     */
    private void readUseStates(LoadReport loaded, Contribution into) {
        useStates.clear();
        if (loaded == null) {
            return;
        }
        for (ContentDefinition definition : loaded.definitions(ContentKind.ITEM)) {
            List<String> problems = new ArrayList<>();
            UseStates states = UseStates.read(definition.body(), problems);
            for (String problem : problems) {
                into.warn(definition.id().namespace() + "/items", definition.id().path(), problem);
            }
            if (states != null) {
                useStates.put(definition.id(), states);
            }
        }
    }

    /** One custom model on a base material, numbered eras only. A repeat is ignored. */
    private void addLegacy(String material, int number, String modelRef) {
        addLegacy(material, number, modelRef, "", 0);
    }

    /** {@link #addLegacy(String, int, String)} with more predicate after the number. */
    private void addLegacy(String material, int number, String modelRef, String extra, int order) {
        if (legacySeen.add(material + "#" + number + "#" + extra)) {
            legacyBases.computeIfAbsent(material, key -> new ArrayList<>())
                    .add(new LegacyItemModels.Override(number, modelRef, extra, order));
        }
    }

    /** A look a placed piece is told to wear by id, and where it was told. */
    private static final class Look {
        final ContentId id;
        final String where;
        final ContentId piece;
        /** The materials of the pieces that wear it, which is what their displays carry. */
        final SortedSet<String> materials = new TreeSet<>();

        Look(ContentId id, String where, ContentId piece) {
            this.id = id;
            this.where = where;
            this.piece = piece;
        }
    }

    /**
     * What a placed piece's other looks need in the pack: each state's
     * {@code model:}, a jukebox's {@code playing-model:} and each
     * {@code connects:} shape.
     *
     * <p>Each may name an item, or a model with no item of its own. An item
     * already has what the client needs to draw it; a bare model used to have
     * nothing, so the display was pointed at an item definition (or, below
     * 1.21.4, a number) the pack never wrote, and the client drew the missing
     * model. A bare model is now written exactly as an item of that id would
     * be, built on the material of the piece that wears it. The runtime already
     * addresses a look by its own id when no item has it, so it finds what is
     * written here on every era.
     *
     * <p>Below 1.21.4 even a look that IS an item has a second half: its number
     * is listed on that item's own material, and the display carries the
     * piece's. Where the two differ the number is listed on the piece's too.
     */
    private void writeLooks(LoadReport loaded, Map<ContentId, ItemInfo> items, Contribution into) {
        if (loaded == null) {
            return;
        }
        Map<ContentId, ModelInfo> pieces = ModelDefinitions.parse(loaded, items).model();
        Map<String, ModelInfo> sortedPieces = new TreeMap<>();
        pieces.forEach((id, piece) -> sortedPieces.put(id.toString(), piece));
        // Sorted throughout, so numbers are handed out in the same order on
        // every build and two builds of the same content are byte-identical.
        Map<String, Look> looks = new TreeMap<>();
        for (ModelInfo piece : sortedPieces.values()) {
            ItemInfo item = items.get(piece.item());
            if (item == null || !bundle.namespaces().contains(item.id().namespace())) {
                continue;
            }
            Map<String, ContentId> named = new LinkedHashMap<>();
            List<ModelInfo.State> states = piece.states();
            for (int i = 0; i < states.size(); i++) {
                String where = "states " + (i + 1) + " model";
                states.get(i).model().ifPresent(model -> named.putIfAbsent(model.toString() + "|" + where, model));
            }
            piece.jukebox().flatMap(ModelInfo.Jukebox::playingModel)
                    .ifPresent(model -> named.putIfAbsent(model + "|jukebox playing-model", model));
            piece.connects().forEach((shape, model) -> named.putIfAbsent(model + "|connects " + shape, model));
            for (Map.Entry<String, ContentId> entry : named.entrySet()) {
                String where = entry.getKey().substring(entry.getKey().indexOf('|') + 1);
                looks.computeIfAbsent(entry.getValue().toString(),
                                key -> new Look(entry.getValue(), where, item.id()))
                        .materials.add(item.material());
            }
        }
        for (Look look : looks.values()) {
            ItemInfo asItem = items.get(look.id);
            if (asItem == null) {
                asItem = blockItems.stream().filter(one -> one.id().equals(look.id)).findFirst().orElse(null);
            }
            if (asItem != null) {
                if (numbered) {
                    ContentId model = asItem.modelId();
                    String owner = items.getOrDefault(model, asItem).material();
                    for (String material : look.materials) {
                        if (!material.equals(owner)) {
                            addLegacy(material, numbers.of(model), model.namespace() + ":item/" + model.path());
                        }
                    }
                }
                continue;
            }
            String namespace = look.id.namespace();
            if (!bundle.namespaces().contains(namespace)) {
                // Another pack's model, or the game's: nothing of ours to write.
                continue;
            }
            String path = look.id.path();
            if (source(into, namespace, path, ".bbmodel").isEmpty()
                    && source(into, namespace, path, ".json").isEmpty()) {
                into.warn(namespace + "/items", look.piece.path(),
                        "place " + look.where + ": " + look.id + " is not an item, and there is no model at "
                                + "assets/models/" + path + ".json or assets/models/" + path + ".bbmodel. "
                                + "The piece draws as the missing model in that look.");
                continue;
            }
            writeItem(ItemInfo.of(look.id, look.materials.first(), null, null, "item/" + path, path,
                    null, null, null, 64, false, false), into);
            if (numbered) {
                for (String material : look.materials) {
                    addLegacy(material, numbers.of(look.id), namespace + ":item/" + path);
                }
            }
        }
    }

    /**
     * The base item model files, one per vanilla material this bundle put a
     * custom model on. Only on the numbered eras; the definitions era has
     * nothing to write here at all.
     *
     * <p>At the end rather than as each item is written, because one file
     * carries every custom model built on that material and the last writer
     * would otherwise win.
     */
    private void writeLegacyBases(Contribution into) {
        for (Map.Entry<String, List<LegacyItemModels.Override>> entry : legacyBases.entrySet()) {
            String material = entry.getKey();
            into.add(LegacyItemModels.path(material),
                    LegacyItemModels.json(material, isBlock(material), entry.getValue()));
        }
    }

    /**
     * Whether a material's item form is a block model.
     *
     * <p>Asked of the running server rather than guessed from the name,
     * because that is the one place the answer is authoritative. Off a server
     * — a unit test — nothing is a block, which is the shape the overwhelming
     * majority of carriers have anyway.
     */
    private static boolean isBlock(String material) {
        try {
            return org.bukkit.Material.valueOf(material).isBlock();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    private void writeItem(ItemInfo item, Contribution into) {
        ContentId id = item.id();
        String namespace = id.namespace();
        String modelRef = namespace + ":item/" + id.path();
        String modelPath = "assets/" + namespace + "/models/item/" + id.path() + ".json";
        Used used = writeUseStates(item, modelRef, into);

        // How the model is reached is the server's version. On 1.21.4 and up
        // the id names an item definition; below it the id has a number and
        // the reference goes into a predicate on the base material's own
        // model, gathered here and written once at the end.
        if (numbered) {
            String material = item.material();
            addLegacy(material, numbers.of(id), modelRef);
            if (LegacyItemModels.isAwkward(material)) {
                into.warn(namespace + "/items", id.path(),
                        "On this Minecraft a custom model is a predicate inside "
                                + material.toLowerCase(java.util.Locale.ROOT)
                                + "'s own model file, and vanilla's copy of that file cannot be "
                                + "reproduced here. This item works, but plain "
                                + material.toLowerCase(java.util.Locale.ROOT)
                                + " may look wrong for everybody on the server. Build it on a "
                                + "simpler material, or run Minecraft 1.21.4 or newer.");
            }
            if (firstPerson.containsKey(id)) {
                into.warn(namespace + "/items", id.path(),
                        "first-person: needs Minecraft 1.21.4 or newer, where an item can be drawn by where "
                                + "it is seen. On this one the item's own model is drawn in first person too.");
            }
            if (used != null) {
                List<String[]> predicates = UseStates.legacyPredicates(used.pull, used.at, used.charged,
                        used.firework);
                for (int i = 0; i < predicates.size(); i++) {
                    addLegacy(material, numbers.of(id), predicates.get(i)[1], predicates.get(i)[0], i + 1);
                }
            }
        } else {
            String look = "{\"type\":\"minecraft:model\",\"model\":\"" + modelRef + "\"}";
            String firstPersonModel = firstPerson.get(id);
            if (firstPersonModel != null
                    && writeVariant(item, namespace, "first-person", firstPersonModel, null,
                            modelRef + "_first_person", into)) {
                // The game picks by where the item is being drawn: the holder's
                // own view gets the first-person model, everything else (a
                // third-person view, the inventory, the ground) the item's own.
                look = "{\"type\":\"minecraft:select\",\"property\":\"minecraft:display_context\","
                        + "\"cases\":[{\"when\":[\"firstperson_righthand\",\"firstperson_lefthand\"],"
                        + "\"model\":{\"type\":\"minecraft:model\",\"model\":\"" + modelRef + "_first_person\"}}],"
                        + "\"fallback\":" + look + "}";
            }
            if (used != null) {
                // Outside the first-person choice: a bow being drawn is drawn
                // back in every view, the holder's own included.
                look = UseStates.definition(look, item.material(), used.pull, used.at, used.charged, used.firework);
            }
            into.add("assets/" + namespace + "/items/" + id.path() + ".json", json("{\"model\":" + look + "}"));
        }

        if (item.model().isPresent()) {
            writeModel(item, namespace, modelPath, into);
        } else {
            writeSprite(item, namespace, modelPath, into);
        }

        // An item wearing a 3D set's piece is dressed in the set's asset (or
        // none) when it is made, so an asset of its own would go unused.
        if (item.armor3d().isEmpty()) {
            item.armor().ifPresent(slot -> writeEquipment(item, namespace, slot, into));
        }
    }

    /**
     * The equipment asset that draws armour on a body.
     *
     * <p>Vanilla's own path since 1.21.4: an item declares an
     * {@code equippable} component naming an asset, and the asset names the
     * layer the game draws it from.
     *
     * <p><strong>Legs are a different layer, not a second one.</strong> The
     * game draws leggings from {@code humanoid_leggings}, at its own narrower
     * proportions, and everything else from {@code humanoid} — so a slot gets
     * exactly one layer and exactly one texture is asked for. Declaring both
     * would name a file the pack has no reason to ship.
     *
     * <p>This replaces the old tricks entirely. Dyed leather spends a colour
     * that then cannot be used for anything else and looks wrong on every
     * other item; armour trims are limited to the trim palette. Neither is
     * needed now, and neither is worth supporting alongside this.
     */
    private void writeEquipment(ItemInfo item, String namespace, String slot, Contribution into) {
        String name = item.id().path();
        String layer = slot.equals("legs") ? "humanoid_leggings" : "humanoid";
        // The texture is the item's own id unless the definition names
        // another, which is how a CraftEngine equipment's art is reached.
        String texture = item.armorTexture().orElse(namespace + ":" + name);
        String textureNamespace = texture.substring(0, texture.indexOf(':'));
        into.add("assets/" + namespace + "/equipment/" + name + ".json",
                json("{\"layers\":{\"" + layer + "\":[{\"texture\":\"" + texture + "\"}]}}"));
        if (!armourArt) {
            vanillaDrawn.add(item.id().toString());
        }
        String target = equipmentTexture(texture, layer);
        String art = armorArt.get(item.id());
        if (art != null && !copyArmorArt(item, namespace, art, textureNamespace, target, into)) {
            return;
        }
        if (bundle == null || bundle.namespaces().contains(textureNamespace)) {
            requireTexture(item, namespace, target, into);
        }
    }

    /**
     * Serves {@code armor-art}'s PNG at the equipment path the game reads.
     *
     * <p>A copy rather than a move: the plugins this exists for keep their
     * layer art wherever they like ({@code textures/armor/ruby/layer_1.png},
     * {@code ruby_armor_layer_1.png} beside the item icons), and something
     * else may still name the original. A file somebody shipped at the
     * equipment path themselves wins, because it was put there on purpose.
     *
     * @return false when the art was missing and has been reported, so the
     *         caller does not report the same missing picture twice
     */
    private boolean copyArmorArt(ItemInfo item, String namespace, String art, String textureNamespace,
                                 String target, Contribution into) {
        if (!textureNamespace.equals(namespace)) {
            // Copying into another namespace would repaint that namespace's
            // armour - vanilla's, for minecraft:gold - for everybody.
            into.warn(namespace + "/items", item.id().path(),
                    "armor-art was ignored, because armor-texture names art in " + textureNamespace
                            + ", which this pack does not own.");
            return true;
        }
        if (into.has(target)) {
            return true;
        }
        String from = Geometry.zipPathOf(ModelSources.textureLocation(namespace, art));
        Optional<byte[]> bytes = into.read(from);
        if (bytes.isEmpty()) {
            into.warn(namespace + "/items", item.id().path(),
                    "armor-art: No texture at " + from + ". The item works but is worn as a missing texture.");
            return false;
        }
        into.add(target, bytes.get());
        return true;
    }

    /** Where the game reads a {@code namespace:name} equipment texture for one layer. */
    private static String equipmentTexture(String texture, String layer) {
        int colon = texture.indexOf(':');
        return "assets/" + texture.substring(0, colon) + "/textures/entity/equipment/" + layer + "/"
                + texture.substring(colon + 1) + ".png";
    }

    /** The vanilla case: a PNG extruded by {@code minecraft:item/generated}. */
    private void writeSprite(ItemInfo item, String namespace, String modelPath, Contribution into) {
        // A namespaced texture is a resource location, written as it is: a
        // CraftEngine pack names minecraft:item/custom/ruby and ships the PNG
        // in its own resource pack folder.
        String texture = ModelSources.textureLocation(namespace, item.texture());
        into.add(modelPath, json("{\"parent\":\"minecraft:item/generated\","
                + "\"textures\":{\"layer0\":\"" + texture + "\"}}"));
        String textureNamespace = texture.substring(0, texture.indexOf(':'));
        // Only a texture a pack in this bundle is meant to ship can be missing;
        // one in another namespace may be the game's own.
        if (!ModelSources.isLocation(item.texture()) || bundle == null
                || bundle.namespaces().contains(textureNamespace)) {
            requireTexture(item, namespace, Geometry.zipPathOf(texture), into);
        }
    }

    /** The 3D case: a model file the author exported from Blockbench. */
    private void writeModel(ItemInfo item, String namespace, String modelPath, Contribution into) {
        String name = item.model().orElseThrow();
        // The Blockbench project first. A .bbmodel is what Blockbench SAVES
        // and a Java model is what it has to be told to EXPORT, so somebody who
        // forgets the export step gets a build error rather than a model, every
        // time, for ever. Reading the save file removes the step.
        if (writeProject(item, namespace, name, modelPath, into)) {
            return;
        }
        Optional<ModelSources.Found> source = source(into, namespace, name, ".json");
        if (source.isEmpty()) {
            into.error(namespace + "/items", item.id().path(),
                    ModelSources.isLocation(name)
                            ? "No model at " + ModelSources.describe(namespace, name, ".json") + "."
                            : "No model at assets/models/" + name + ".bbmodel or assets/models/" + name
                                    + ".json. Save the Blockbench project into assets/models/ "
                                    + "and it is read directly.");
            // Falls back to the sprite, so the item still exists and still
            // stacks. An item that vanishes because its art is missing is a
            // much worse failure than one that renders wrong.
            writeSprite(item, namespace, modelPath, into);
            return;
        }
        String sourcePath = source.get().path();
        Optional<Geometry.Model> model = Geometry.read(source.get().bytes(), source.get().textureNamespace());
        if (model.isEmpty()) {
            into.error(namespace + "/items", item.id().path(),
                    sourcePath + " is not a model file. Blockbench writes one "
                            + "with File > Export > Java Block/Item model.");
            writeSprite(item, namespace, modelPath, into);
            return;
        }
        // The source was copied in with the rest of assets/. It has been read
        // and rewritten now, so the original goes rather than shipping beside
        // the thing built from it. Only what was consumed: a model nobody
        // referenced stays, because it is probably a shared parent. A file
        // out of a whole resource pack folder always stays, because anything
        // else in that pack may name it.
        if (source.get().consumable()) {
            into.drop("assets/" + namespace + "/models/" + localName(name) + ".json");
        }
        into.add(modelPath, model.get().json());
        // A model file can carry animations too, and for a long time only the
        // .bbmodel branch above looked. Studio EXPORTS a Java model with an
        // `animations` array beside the elements — which is the shape
        // ModelRigs reads — so a pack built from a studio export placed and
        // drove as one still lump: a skateboard whose wheels were animated,
        // in a file that said so, standing still. A model with no animations
        // costs one parse and nothing else, because compute() answers empty.
        writeRigIfAnimated(item, namespace, model.get().json(), into);
        for (String texture : model.get().textures()) {
            String textureNamespace = texture.substring(0, texture.indexOf(':'));
            // Only textures a pack in this bundle is supposed to ship. A model
            // that names minecraft:block/black_wool is asking for a vanilla
            // texture the game already has, and warning about those trains
            // everybody to ignore the warning that matters.
            if (bundle != null && !bundle.namespaces().contains(textureNamespace)) {
                continue;
            }
            requireTexture(item, namespace, Geometry.zipPathOf(texture), into);
        }
    }

    /** The use states an item's definition can name, written; see {@link #writeUseStates}. */
    private static final class Used {
        final List<String> pull;
        final double[] at;
        final String charged;
        final String firework;

        Used(List<String> pull, double[] at, String charged, String firework) {
            this.pull = pull;
            this.at = at;
            this.charged = charged;
            this.firework = firework;
        }
    }

    /**
     * Writes the model of every use state an item has, and says which of them
     * made it. The same on every era: only how they are reached differs.
     *
     * <p>A state whose model is missing is left out with an error rather than
     * taking the item with it, so a bow with one broken frame still draws with
     * the other two.
     *
     * @return null when the item has none, or none that can mean anything here
     */
    private Used writeUseStates(ItemInfo item, String modelRef, Contribution into) {
        UseStates states = useStates.get(item.id());
        if (states == null) {
            return null;
        }
        String namespace = item.id().namespace();
        String material = item.material();
        List<UseStates.Look> pull = states.pull;
        if (!pull.isEmpty() && numbered && !UseStates.legacyPullable(material)) {
            into.warn(namespace + "/items", item.id().path(),
                    "pull: below Minecraft 1.21.4 only a BOW or a CROSSBOW can be drawn back. "
                            + "On this one the item keeps its own look while it is used.");
            pull = List.of();
        }
        UseStates.Look charged = states.charged;
        UseStates.Look firework = states.firework;
        if ((charged != null || firework != null) && !UseStates.chargeable(material)) {
            into.warn(namespace + "/items", item.id().path(),
                    "charged: and charged-firework: are a crossbow's loaded looks, and a "
                            + material.toLowerCase(java.util.Locale.ROOT)
                            + " is never loaded. Build it on CROSSBOW to use them.");
            charged = null;
            firework = null;
        }
        // In threshold order, which is the order both eras need them in.
        double[] at = UseStates.thresholds(material, pull);
        Integer[] order = new Integer[pull.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, java.util.Comparator.comparingDouble(i -> at[i]));
        List<String> refs = new ArrayList<>();
        List<Double> kept = new ArrayList<>();
        for (int i = 0; i < order.length; i++) {
            UseStates.Look look = pull.get(order[i]);
            String ref = modelRef + "_pull_" + i;
            if (writeVariant(item, namespace, "pull", look.model, look.texture, ref, into)) {
                refs.add(ref);
                kept.add(at[order[i]]);
            }
        }
        String chargedRef = charged != null
                && writeVariant(item, namespace, "charged", charged.model, charged.texture, modelRef + "_charged", into)
                ? modelRef + "_charged" : null;
        String fireworkRef = firework != null
                && writeVariant(item, namespace, "charged-firework", firework.model, firework.texture,
                        modelRef + "_charged_firework", into)
                ? modelRef + "_charged_firework" : null;
        if (refs.isEmpty() && chargedRef == null && fireworkRef == null) {
            return null;
        }
        return new Used(refs, kept.stream().mapToDouble(Double::doubleValue).toArray(), chargedRef, fireworkRef);
    }

    /**
     * Writes one more look of an item as {@code ref}: a {@code first-person:}
     * model or a use state. A model is a Blockbench project, a JSON model file
     * or one written inline, exactly as {@code model:} is; a texture is a flat
     * sprite, exactly as an item with no model is.
     *
     * @param label the key it was declared under, which every error starts with
     * @return whether it was written; when it was not, the error says why and
     *         the item is drawn with its own look instead
     */
    private boolean writeVariant(ItemInfo item, String namespace, String label, String name, String texture,
                                 String ref, Contribution into) {
        String path = "assets/" + ref.substring(0, ref.indexOf(':')) + "/models/" + ref.substring(ref.indexOf(':') + 1) + ".json";
        if (texture != null) {
            String location = ModelSources.textureLocation(namespace, texture);
            into.add(path, json("{\"parent\":\"minecraft:item/generated\","
                    + "\"textures\":{\"layer0\":\"" + location + "\"}}"));
            String textureNamespace = location.substring(0, location.indexOf(':'));
            if (!ModelSources.isLocation(texture) || bundle == null || bundle.namespaces().contains(textureNamespace)) {
                requireTexture(item, namespace, Geometry.zipPathOf(location), into);
            }
            return true;
        }
        Optional<JsonObject> project = convertProject(item, namespace, name, into);
        if (project.isPresent()) {
            into.add(path, project.get().toString().getBytes(StandardCharsets.UTF_8));
            return true;
        }
        Optional<ModelSources.Found> source = source(into, namespace, name, ".json");
        Optional<Geometry.Model> model = source.flatMap(found -> Geometry.read(found.bytes(), found.textureNamespace()));
        if (model.isEmpty()) {
            into.error(namespace + "/items", item.id().path(),
                    source.isEmpty()
                            ? label + ": no model at " + (ModelSources.isInline(name) ? "(inline model)"
                            : ModelSources.describe(namespace, name, ".json")) + "."
                            : label + ": " + source.get().path() + " is not a model file.");
            return false;
        }
        // The source stays: unlike the item's own model, one of these is often
        // the parent of another (every frame of a bow built on the first).
        into.add(path, model.get().json());
        for (String used : model.get().textures()) {
            String textureNamespace = used.substring(0, used.indexOf(':'));
            if (bundle != null && !bundle.namespaces().contains(textureNamespace)) {
                continue;
            }
            requireTexture(item, namespace, Geometry.zipPathOf(used), into);
        }
        return true;
    }

    /**
     * A model file for {@code name}, from wherever {@link ModelSources} says
     * one may be.
     *
     * <p>Ours is the documented layout and is tried first. ItemsAdder keeps
     * {@code models/} beside its configs, ModelEngine {@code blueprints/}, and
     * CraftEngine a whole resource pack folder; a pack copied straight out of
     * any of them should not need its folders moved around before its models
     * are read.
     */
    private static Optional<ModelSources.Found> source(Contribution into, String namespace, String name,
                                                        String extension) {
        return ModelSources.find(namespace, name, extension, path -> into.source(namespace, path));
    }

    /** The path part of a model name, which is the whole of a plain one. */
    static String localName(String name) {
        return ModelSources.isLocation(name) ? name.substring(name.indexOf(':') + 1) : name;
    }

    /**
     * A Blockbench project, converted where it stands.
     *
     * @return whether one was there
     */
    private boolean writeProject(ItemInfo item, String namespace, String name,
                                 String modelPath, Contribution into) {
        Optional<JsonObject> model = convertProject(item, namespace, name, into);
        if (model.isEmpty()) {
            return false;
        }
        into.add(modelPath, model.get().toString().getBytes(StandardCharsets.UTF_8));
        writeRig(item, namespace, model.get(), into);
        return true;
    }

    /**
     * A Blockbench project read into a Java model, its textures written and
     * the project itself dropped from the pack.
     *
     * @return empty when there is no project by that name, or it has no cubes
     *         (which has been reported)
     */
    private Optional<JsonObject> convertProject(ItemInfo item, String namespace, String name, Contribution into) {
        Optional<ModelSources.Found> source = source(into, namespace, name, ".bbmodel");
        if (source.isEmpty()) {
            return Optional.empty();
        }
        String sourcePath = source.get().path();
        Optional<BbModel.Converted> converted = BbModel.convert(source.get().bytes(), namespace,
                localName(name));
        if (converted.isEmpty()) {
            into.error(namespace + "/items", item.id().path(),
                    sourcePath + " has no cube geometry in it. A mesh cannot become a "
                            + "Minecraft model; convert it to cubes in Blockbench first.");
            return Optional.empty();
        }

        // The art rides inside the project file, which is the other half of why
        // reading it directly is worth doing: a .bbmodel is a whole model,
        // textures included, in one file somebody can hand to somebody else.
        for (java.util.Map.Entry<String, byte[]> texture : converted.get().textures().entrySet()) {
            into.add("assets/" + namespace + "/textures/item/" + texture.getKey() + ".png",
                    texture.getValue());
        }
        // Consumed, so it goes rather than shipping beside what was built from
        // it. A project file is often the largest thing in a pack, since the
        // textures are inside it twice over once they are extracted.
        if (source.get().consumable()) {
            into.drop("assets/" + namespace + "/models/" + localName(name) + ".bbmodel");
        }
        return Optional.of(converted.get().model());
    }

    /**
     * {@link #writeRig} for a model file that may or may not have keyframes in
     * it, given the bytes rather than a parsed project.
     *
     * <p>Silent when the file will not parse: it has already been read once by
     * {@link Geometry#read}, so getting here with something unreadable is not
     * possible, and a second error about it would say nothing new.
     */
    private void writeRigIfAnimated(ItemInfo item, String namespace, byte[] json,
                                    Contribution into) {
        try {
            JsonElement parsed = JsonParser.parseString(new String(json, StandardCharsets.UTF_8));
            if (parsed.isJsonObject()) {
                writeRig(item, namespace, parsed.getAsJsonObject(), into);
            }
        } catch (RuntimeException ignored) {
            // Read once already; see the note above.
        }
    }

    /**
     * The extra models an animated piece needs: one per moving part.
     *
     * <p>A client cannot animate a block model, so a model with keyframes is
     * placed as several display entities the server retimes. Each of those
     * displays needs something to render, and this is where those something
     * come from — a normal item model per part, addressed by an id derived
     * from the piece's own.
     *
     * <p>The whole model is still written and still what the item looks like
     * in a hand or a chest. Only what is PUT DOWN is split.
     */
    private void writeRig(ItemInfo item, String namespace, JsonObject model, Contribution into) {
        Optional<ModelRigs.Rig> rig = ModelRigs.compute(item.id().toString(), model);
        if (rig.isEmpty()) {
            return;
        }
        for (ModelRigs.Part part : rig.get().parts()) {
            // The part item id is the model id with a suffix, so its path
            // falls out of the piece's own exactly as the piece's fell out of
            // its id. Nothing is allocated here either.
            String path = part.item().substring(part.item().indexOf(':') + 1);
            if (numbered) {
                // Below 1.21.4 a part's display is told its model by the part
                // id's number, on the piece's own material. Without this the
                // number reached no model and every part drew as the missing one.
                ContentId.parse(part.item()).ifPresent(partId ->
                        addLegacy(item.material(), numbers.of(partId), namespace + ":item/" + path));
            }
            into.add("assets/" + namespace + "/items/" + path + ".json",
                    json("{\"model\":{\"type\":\"minecraft:model\",\"model\":\""
                            + namespace + ":item/" + path + "\"}}"));
            into.add("assets/" + namespace + "/models/item/" + path + ".json",
                    ModelRigs.partModel(model, part).toString().getBytes(StandardCharsets.UTF_8));
        }
        // The author's own settings for how these play, baked in now rather
        // than reconciled later.
        for (String unknown : ModelRigs.applyHitboxes(rig.get(), item.hitboxes())) {
            into.warn(namespace + "/items", item.id().path(),
                    "place.hitboxes." + unknown + " names no bone in this model.");
        }
        for (String unknown : ModelRigs.apply(rig.get(), item.animations())) {
            into.warn(namespace + "/items", item.id().path(),
                    "place.animations." + unknown + " names no animation in this model.");
        }
        rigs.put(item.id().toString(), rig.get());
    }

    /**
     * The assets are already in the bundle by the time a contributor runs, so a
     * texture nobody shipped can be named here rather than discovered in game
     * as a purple and black square nobody can trace back to a file.
     */
    private void requireTexture(ItemInfo item, String namespace, String texturePath, Contribution into) {
        if (into.has(texturePath)) {
            return;
        }
        into.warn(namespace + "/items", item.id().path(),
                "No texture at " + texturePath + ". The item works but renders as a missing texture.");
    }

    private static byte[] json(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
