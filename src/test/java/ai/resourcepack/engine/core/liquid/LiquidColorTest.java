package ai.resourcepack.engine.core.liquid;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.ItemInfo;
import ai.resourcepack.engine.api.LiquidInfo;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.core.content.ContentFolderLoader;
import ai.resourcepack.engine.core.item.ItemDefinitions;
import ai.resourcepack.engine.core.registry.ContentRegistryImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A liquid's colour, and the bucket that places it.
 *
 * <p>The colour is the half of this feature an author gets wrong in silence —
 * a misspelled name or a five-digit hex leaves a pool that is simply the wrong
 * colour, with nothing to read — so the parsing is worth pinning down.
 */
class LiquidColorTest {

    @TempDir
    Path content;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(content);
        write("mypack/pack.yml", "{}\n");
    }

    private void write(String path, String text) throws IOException {
        Path file = content.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private LoadReport load() {
        return new ContentFolderLoader(new ContentRegistryImpl()).load(content, ContentSource.AUTHORED);
    }

    private LiquidInfo liquid(String written) throws IOException {
        write("mypack/liquids/a.yml", "acid:\n  base: water\n" + written);
        return LiquidDefinitions.parse(load()).liquids().get(ContentId.parse("mypack:acid").orElseThrow());
    }

    @Test
    void readsHexInEverySpellingSomebodyWillType() throws IOException {
        assertEquals(0x3FBF4A, liquid("  color: \"#3FBF4A\"\n").color().orElseThrow());
        assertEquals(0x3FBF4A, liquid("  color: 0x3FBF4A\n").color().orElseThrow());
        assertEquals(0x3FBF4A, liquid("  color: 3FBF4A\n").color().orElseThrow());
    }

    @Test
    void readsAColourName() throws IOException {
        assertEquals(0xB02E26, liquid("  color: RED\n").color().orElseThrow());
        assertEquals(0xB02E26, liquid("  color: red\n").color().orElseThrow());
    }

    @Test
    void aLiquidUsuallyHasNoColourAtAll() throws IOException {
        assertTrue(liquid("").color().isEmpty());
    }

    /**
     * Untinted rather than refused: the pool's rules are the point of a liquid
     * and a bad colour should not cost somebody those.
     */
    @Test
    void aColourThatIsNotOneIsAWarningAndNoTint() throws IOException {
        write("mypack/liquids/a.yml", "acid:\n  base: water\n  color: ochre\n");
        LiquidDefinitions.Result parsed = LiquidDefinitions.parse(load());

        LiquidInfo acid = parsed.liquids().get(ContentId.parse("mypack:acid").orElseThrow());
        assertTrue(acid.color().isEmpty());
        assertTrue(parsed.diagnostics().stream()
                .anyMatch(d -> d.severity() == Diagnostic.Severity.WARNING
                        && d.message().contains("color")));
    }

    @Test
    void oneBiomeKeyPerLiquid() {
        assertEquals("rpengine:mypack_acid",
                LiquidBiomes.keyOf(ContentId.parse("mypack:acid").orElseThrow()).toString());
    }

    // ---- the bucket ------------------------------------------------------

    @Test
    void anItemCanBeABucketOfALiquid() throws IOException {
        write("mypack/items/a.yml", "acid_bucket:\n  material: BUCKET\n  liquid: mypack:acid\n");

        ItemInfo bucket = ItemDefinitions.parse(load()).items()
                .get(ContentId.parse("mypack:acid_bucket").orElseThrow());

        assertEquals("mypack:acid", bucket.liquid().orElseThrow().toString());
    }

    @Test
    void almostNoItemIsOne() throws IOException {
        write("mypack/items/a.yml", "ruby:\n  material: PAPER\n");

        ItemInfo ruby = ItemDefinitions.parse(load()).items()
                .get(ContentId.parse("mypack:ruby").orElseThrow());

        assertTrue(ruby.liquid().isEmpty());
    }

    /**
     * Shape only, like {@code copy-model}: the liquid it names may be in a
     * pack that has not loaded yet, so existence cannot be asked here.
     */
    @Test
    void aBucketOfSomethingThatIsNotAnIdIsJustNotABucket() throws IOException {
        write("mypack/items/a.yml", "acid_bucket:\n  material: BUCKET\n  liquid: \"not an id\"\n");

        ItemDefinitions.Result parsed = ItemDefinitions.parse(load());
        ItemInfo bucket = parsed.items().get(ContentId.parse("mypack:acid_bucket").orElseThrow());

        assertTrue(bucket.liquid().isEmpty());
        assertFalse(parsed.diagnostics().isEmpty());
    }
}
