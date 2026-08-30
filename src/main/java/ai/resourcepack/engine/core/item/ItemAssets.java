package ai.resourcepack.engine.core.item;

import ai.resourcepack.engine.api.Bundle;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ItemInfo;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.core.model.ModelRigs;
import ai.resourcepack.engine.core.pack.PackContributor;

import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

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
    private final Map<String, ModelRigs.Rig> rigs = new LinkedHashMap<>();

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
    }

    private void writeItem(ItemInfo item, Contribution into) {
        ContentId id = item.id();
        String namespace = id.namespace();
        String modelRef = namespace + ":item/" + id.path();
        String modelPath = "assets/" + namespace + "/models/item/" + id.path() + ".json";

        // The item definition is the same either way. What differs is what the
        // model it points at turns out to be.
        into.add("assets/" + namespace + "/items/" + id.path() + ".json",
                json("{\"model\":{\"type\":\"minecraft:model\",\"model\":\"" + modelRef + "\"}}"));

        if (item.model().isPresent()) {
            writeModel(item, namespace, modelPath, into);
        } else {
            writeSprite(item, namespace, modelPath, into);
        }

        item.armor().ifPresent(slot -> writeEquipment(item, namespace, slot, into));
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
        into.add("assets/" + namespace + "/equipment/" + name + ".json",
                json("{\"layers\":{\"" + layer + "\":[{\"texture\":\""
                        + namespace + ":" + name + "\"}]}}"));
        requireTexture(item, namespace,
                "assets/" + namespace + "/textures/entity/equipment/" + layer + "/" + name + ".png",
                into);
    }

    /** The vanilla case: a PNG extruded by {@code minecraft:item/generated}. */
    private void writeSprite(ItemInfo item, String namespace, String modelPath, Contribution into) {
        String texture = namespace + ":" + item.texture();
        into.add(modelPath, json("{\"parent\":\"minecraft:item/generated\","
                + "\"textures\":{\"layer0\":\"" + texture + "\"}}"));
        requireTexture(item, namespace, "assets/" + namespace + "/textures/" + item.texture() + ".png", into);
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
        String sourcePath = "assets/models/" + name + ".json";
        Optional<byte[]> source = source(into, namespace, "models/" + name + ".json");
        if (source.isEmpty()) {
            into.error(namespace + "/items", item.id().path(),
                    "No model at assets/models/" + name + ".bbmodel or " + sourcePath
                            + ". Save the Blockbench project into assets/models/ "
                            + "and it is read directly.");
            // Falls back to the sprite, so the item still exists and still
            // stacks. An item that vanishes because its art is missing is a
            // much worse failure than one that renders wrong.
            writeSprite(item, namespace, modelPath, into);
            return;
        }
        Optional<Geometry.Model> model = Geometry.read(source.get(), namespace);
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
        // referenced stays, because it is probably a shared parent.
        into.drop("assets/" + namespace + "/models/" + name + ".json");
        into.add(modelPath, model.get().json());
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

    /**
     * A source file, from under {@code assets/} or from an ItemsAdder pack's
     * root.
     *
     * <p>Ours is the documented layout and is tried first. Theirs keeps
     * {@code models/} and {@code textures/} beside the configs, and a pack
     * copied straight out of ItemsAdder should not need its folders moved
     * around before its models are read.
     */
    private static Optional<byte[]> source(Contribution into, String namespace, String path) {
        Optional<byte[]> ours = into.source(namespace, "assets/" + path);
        if (ours.isPresent()) {
            return ours;
        }
        // An ItemsAdder pack keeps models/ at its root; a ModelEngine one
        // keeps blueprints/. Both are read where they lie, so a pack copied
        // out of either needs no folders moved.
        Optional<byte[]> theirs = into.source(namespace, path);
        return theirs.isPresent()
                ? theirs
                : into.source(namespace, path.replaceFirst("^models/", "blueprints/"));
    }

    /**
     * A Blockbench project, converted where it stands.
     *
     * @return whether one was there
     */
    private boolean writeProject(ItemInfo item, String namespace, String name,
                                 String modelPath, Contribution into) {
        String sourcePath = "assets/models/" + name + ".bbmodel";
        Optional<byte[]> source = source(into, namespace, "models/" + name + ".bbmodel");
        if (source.isEmpty()) {
            return false;
        }
        Optional<BbModel.Converted> converted = BbModel.convert(source.get(), namespace, name);
        if (converted.isEmpty()) {
            into.error(namespace + "/items", item.id().path(),
                    sourcePath + " has no cube geometry in it. A mesh cannot become a "
                            + "Minecraft model; convert it to cubes in Blockbench first.");
            return false;
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
        into.drop("assets/" + namespace + "/models/" + name + ".bbmodel");
        into.add(modelPath, converted.get().model().toString().getBytes(StandardCharsets.UTF_8));
        writeRig(item, namespace, converted.get().model(), into);
        return true;
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
