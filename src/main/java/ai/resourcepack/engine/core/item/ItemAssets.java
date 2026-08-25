package ai.resourcepack.engine.core.item;

import ai.resourcepack.engine.api.Bundle;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ItemInfo;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.core.pack.PackContributor;

import java.nio.charset.StandardCharsets;

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

        into.add("assets/" + namespace + "/items/" + id.path() + ".json",
                json("{\"model\":{\"type\":\"minecraft:model\",\"model\":\"" + modelRef + "\"}}"));

        String texture = namespace + ":" + item.texture();
        into.add("assets/" + namespace + "/models/item/" + id.path() + ".json",
                json("{\"parent\":\"minecraft:item/generated\","
                        + "\"textures\":{\"layer0\":\"" + texture + "\"}}"));

        // The assets are already in the bundle by the time a contributor runs,
        // so a texture nobody shipped can be named here rather than discovered
        // in game as a purple and black square nobody can trace.
        String texturePath = "assets/" + namespace + "/textures/" + item.texture() + ".png";
        if (!into.has(texturePath)) {
            into.warn(namespace + "/items", id.path(),
                    "No texture at " + texturePath.substring(("assets/" + namespace + "/").length())
                            + ". The item works but renders as a missing texture.");
        }
    }

    private static byte[] json(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
