package ai.resourcepack.engine.core.pack;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelAtlasTest {

    private static byte[] modelDrawing(String texture) {
        return ("{\"textures\":{\"0\":\"" + texture + "\"},\"elements\":[]}").getBytes(StandardCharsets.UTF_8);
    }

    private static String atlas(DeterministicZip zip) {
        return new String(zip.get(ModelAtlas.BLOCKS), StandardCharsets.UTF_8);
    }

    @Test
    void aBlockModelDrawsAnItemTextureThroughABlocksAtlasAlias() {
        DeterministicZip zip = new DeterministicZip();
        zip.add("assets/crates/models/block/crate.json", modelDrawing("crates:item/crate"));
        zip.add("assets/crates/textures/item/crate.png", new byte[] {1});

        assertEquals(1, ModelAtlas.complete(zip));
        assertTrue(atlas(zip).contains("\"resource\":\"crates:item/crate\""));
        assertTrue(atlas(zip).contains("\"sprite\":\"crates:block/item/crate\""));
        String model = new String(zip.get("assets/crates/models/block/crate.json"), StandardCharsets.UTF_8);
        assertTrue(model.contains("\"0\":\"crates:block/item/crate\""), model);
    }

    @Test
    void theSameAliasIsListedOnceAndAPacksOwnIsKept() {
        DeterministicZip zip = new DeterministicZip();
        zip.add("assets/crates/models/block/a.json", modelDrawing("crates:item/crate"));
        zip.add("assets/crates/models/block/b.json", modelDrawing("crates:item/crate"));
        zip.add("assets/crates/textures/item/crate.png", new byte[] {1});
        zip.add(ModelAtlas.BLOCKS, ("{\"sources\":[{\"type\":\"single\",\"resource\":\"crates:item/crate\","
                + "\"sprite\":\"crates:block/item/crate\"}]}").getBytes(StandardCharsets.UTF_8));

        assertEquals(0, ModelAtlas.complete(zip));
    }

    @Test
    void anItemModelsItemTextureIsLeftToTheItemsAtlas() {
        DeterministicZip zip = new DeterministicZip();
        zip.add("assets/crates/models/item/crate.json", modelDrawing("crates:item/crate"));
        zip.add("assets/crates/textures/item/crate.png", new byte[] {1});

        assertEquals(0, ModelAtlas.complete(zip));
        assertFalse(zip.has(ModelAtlas.BLOCKS));
    }

    @Test
    void aBlockModelsBlockTextureIsAlreadyStitched() {
        DeterministicZip zip = new DeterministicZip();
        zip.add("assets/crates/models/block/crate.json", modelDrawing("crates:block/crate"));
        zip.add("assets/crates/textures/block/crate.png", new byte[] {1});

        assertEquals(0, ModelAtlas.complete(zip));
    }

    @Test
    void anItemTextureThePackDoesNotShipIsNotListed() {
        DeterministicZip zip = new DeterministicZip();
        zip.add("assets/crates/models/block/crate.json", modelDrawing("minecraft:item/diamond"));

        assertEquals(0, ModelAtlas.complete(zip));
    }
}
