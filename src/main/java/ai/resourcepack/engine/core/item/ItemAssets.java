package ai.resourcepack.engine.core.item;

import ai.resourcepack.engine.api.Bundle;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ItemInfo;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.core.pack.PackContributor;

import java.nio.charset.StandardCharsets;
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

    @Override
    public void contribute(Bundle bundle, LoadReport loaded, Contribution into) {
        ItemDefinitions.Result parsed = ItemDefinitions.parse(loaded);
        for (ItemInfo item : parsed.items().values()) {
            if (!bundle.namespaces().contains(item.id().namespace())) {
                continue;
            }
            if (item.model().isPresent()) {
                // Borrowed. The files are already written under the id it
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

        if (item.geometry().isPresent()) {
            writeGeometry(item, namespace, modelPath, into);
        } else {
            writeSprite(item, namespace, modelPath, into);
        }
    }

    /** The vanilla case: a PNG extruded by {@code minecraft:item/generated}. */
    private void writeSprite(ItemInfo item, String namespace, String modelPath, Contribution into) {
        String texture = namespace + ":" + item.texture();
        into.add(modelPath, json("{\"parent\":\"minecraft:item/generated\","
                + "\"textures\":{\"layer0\":\"" + texture + "\"}}"));
        requireTexture(item, namespace, "assets/" + namespace + "/textures/" + item.texture() + ".png", into);
    }

    /** The 3D case: a model file the author exported from Blockbench. */
    private void writeGeometry(ItemInfo item, String namespace, String modelPath, Contribution into) {
        String name = item.geometry().orElseThrow();
        Optional<byte[]> source = into.source(namespace, "assets/geometry/" + name + ".json");
        if (source.isEmpty()) {
            into.error(namespace + "/items", item.id().path(),
                    "No model at assets/geometry/" + name + ".json. Export it from Blockbench "
                            + "as a Java block/item model and put it there.");
            // Falls back to the sprite, so the item still exists and still
            // stacks. An item that vanishes because its art is missing is a
            // much worse failure than one that renders wrong.
            writeSprite(item, namespace, modelPath, into);
            return;
        }
        Optional<Geometry.Model> model = Geometry.read(source.get(), namespace);
        if (model.isEmpty()) {
            into.error(namespace + "/items", item.id().path(),
                    "assets/geometry/" + name + ".json is not a model file. Blockbench writes one "
                            + "with File > Export > Java Block/Item model.");
            writeSprite(item, namespace, modelPath, into);
            return;
        }
        into.add(modelPath, model.get().json());
        for (String texture : model.get().textures()) {
            requireTexture(item, namespace, Geometry.zipPathOf(texture), into);
        }
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
