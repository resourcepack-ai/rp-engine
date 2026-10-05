package ai.resourcepack.engine.core.recipe;

import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.ContentSource;
import ai.resourcepack.engine.api.Diagnostic;
import ai.resourcepack.engine.api.LoadReport;
import ai.resourcepack.engine.api.RecipeInfo;
import ai.resourcepack.engine.core.content.ContentFolderLoader;
import ai.resourcepack.engine.core.registry.ContentRegistryImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeDefinitionsTest {

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

    private RecipeDefinitions.Result parse() {
        LoadReport loaded = new ContentFolderLoader(new ContentRegistryImpl())
                .load(content, ContentSource.AUTHORED);
        return RecipeDefinitions.parse(loaded);
    }

    private static RecipeInfo one(RecipeDefinitions.Result result, String id) {
        return result.recipes().get(ContentId.parse(id).orElseThrow());
    }

    @Test
    void readsAShapedRecipe() throws IOException {
        write("mypack/recipes/a.yml",
                "ruby_block:\n"
                        + "  type: shaped\n"
                        + "  result: mypack:ruby_block\n"
                        + "  amount: 2\n"
                        + "  pattern:\n"
                        + "    - \"RRR\"\n"
                        + "    - \"R R\"\n"
                        + "    - \"RRR\"\n"
                        + "  keys:\n"
                        + "    R: mypack:ruby\n");

        RecipeInfo recipe = one(parse(), "mypack:ruby_block");

        assertEquals(RecipeInfo.Type.SHAPED, recipe.type());
        assertEquals("mypack:ruby_block", recipe.result());
        assertEquals(2, recipe.amount());
        assertEquals(List.of("RRR", "R R", "RRR"), recipe.rows());
        assertEquals("mypack:ruby", recipe.keys().get("R"));
    }

    @Test
    void aPatternCharacterWithNoKeyIsRefused() throws IOException {
        write("mypack/recipes/a.yml",
                "thing:\n  result: STICK\n  pattern: [\"XY\"]\n  keys:\n    X: DIAMOND\n");

        RecipeDefinitions.Result result = parse();

        // Otherwise it is a recipe nobody can craft, and nothing in game says
        // why.
        assertTrue(result.recipes().isEmpty());
        assertTrue(result.diagnostics().get(0).message().contains("'Y'"));
    }

    @Test
    void aSpaceIsAnEmptySlotRatherThanAMissingKey() throws IOException {
        write("mypack/recipes/a.yml",
                "thing:\n  result: STICK\n  pattern: [\"X X\"]\n  keys:\n    X: DIAMOND\n");

        assertEquals(1, parse().recipes().size());
    }

    @Test
    void aPatternBiggerThanTheGridIsRefused() throws IOException {
        write("mypack/recipes/a.yml",
                "wide:\n  result: STICK\n  pattern: [\"XXXX\"]\n  keys:\n    X: DIAMOND\n");
        RecipeDefinitions.Result wide = parse();

        write("mypack/recipes/a.yml",
                "tall:\n  result: STICK\n  pattern: [\"X\",\"X\",\"X\",\"X\"]\n  keys:\n    X: DIAMOND\n");
        RecipeDefinitions.Result tall = parse();

        assertTrue(wide.recipes().isEmpty());
        assertTrue(tall.recipes().isEmpty());
        assertTrue(wide.diagnostics().get(0).message().contains("crafting grid is 3"));
    }

    @Test
    void readsAShapelessRecipe() throws IOException {
        write("mypack/recipes/a.yml",
                "paste:\n  type: shapeless\n  result: mypack:paste\n"
                        + "  ingredients: [mypack:ruby, DIAMOND, DIAMOND]\n");

        RecipeInfo recipe = one(parse(), "mypack:paste");

        assertEquals(RecipeInfo.Type.SHAPELESS, recipe.type());
        assertEquals(List.of("mypack:ruby", "DIAMOND", "DIAMOND"), recipe.ingredients());
    }

    @Test
    void aShapelessRecipeCannotExceedTheGrid() throws IOException {
        write("mypack/recipes/a.yml",
                "paste:\n  type: shapeless\n  result: STICK\n"
                        + "  ingredients: [A,A,A,A,A,A,A,A,A,A]\n");

        assertTrue(parse().recipes().isEmpty());
    }

    @Test
    void readsACookingRecipe() throws IOException {
        write("mypack/recipes/a.yml",
                "ingot:\n  type: blasting\n  result: mypack:ingot\n"
                        + "  ingredient: mypack:ore\n  experience: 0.7\n  time: 60\n");

        RecipeInfo recipe = one(parse(), "mypack:ingot");

        assertTrue(recipe.isCooking());
        assertEquals(List.of("mypack:ore"), recipe.ingredients());
        assertEquals(0.7f, recipe.experience());
        assertEquals(60, recipe.cookingTime());
    }

    @Test
    void cookingTimesDefaultToVanillasOwn() throws IOException {
        write("mypack/recipes/a.yml",
                "slow:\n  type: smelting\n  result: STICK\n  ingredient: OAK_LOG\n");
        assertEquals(200, one(parse(), "mypack:slow").cookingTime());

        write("mypack/recipes/a.yml",
                "fast:\n  type: smoking\n  result: STICK\n  ingredient: OAK_LOG\n");
        // A smoker is twice as fast, and getting this wrong is not an error —
        // it is a recipe that feels wrong to play, which nobody reports.
        assertEquals(100, one(parse(), "mypack:fast").cookingTime());
    }

    @Test
    void aCookingRecipeTakesOneIngredientAndSaysSo() throws IOException {
        write("mypack/recipes/a.yml",
                "ingot:\n  type: smelting\n  result: STICK\n  ingredients: [A, B, C]\n");

        RecipeDefinitions.Result result = parse();

        assertEquals(List.of("A"), one(result, "mypack:ingot").ingredients());
        assertEquals(Diagnostic.Severity.WARNING, result.diagnostics().get(0).severity());
    }

    @Test
    void aRecipeWithNoResultIsRefused() throws IOException {
        write("mypack/recipes/a.yml", "nothing:\n  type: shapeless\n  ingredients: [DIAMOND]\n");

        RecipeDefinitions.Result result = parse();

        assertTrue(result.recipes().isEmpty());
        assertTrue(result.diagnostics().get(0).message().contains("has to make something"));
    }

    @Test
    void aRecipeWithNothingToMakeItFromIsRefused() throws IOException {
        write("mypack/recipes/a.yml", "nothing:\n  type: shapeless\n  result: STICK\n");

        assertTrue(parse().recipes().isEmpty());
    }

    @Test
    void anUnknownTypeIsRefusedWithTheList() throws IOException {
        write("mypack/recipes/a.yml", "thing:\n  type: alchemy\n  result: STICK\n");

        RecipeDefinitions.Result result = parse();

        assertTrue(result.recipes().isEmpty());
        assertTrue(result.diagnostics().get(0).message().contains("stonecutting"));
    }

    @Test
    void shapedIsTheDefaultBecauseItIsWhatMostRecipesAre() throws IOException {
        write("mypack/recipes/a.yml",
                "thing:\n  result: STICK\n  pattern: [\"X\"]\n  keys:\n    X: DIAMOND\n");

        assertEquals(RecipeInfo.Type.SHAPED, one(parse(), "mypack:thing").type());
    }

    @Test
    void anAbsurdAmountIsClampedWithAWarning() throws IOException {
        write("mypack/recipes/a.yml",
                "thing:\n  result: STICK\n  amount: 500\n  pattern: [\"X\"]\n  keys:\n    X: DIAMOND\n");

        RecipeDefinitions.Result result = parse();

        assertEquals(1, one(result, "mypack:thing").amount());
        assertEquals(Diagnostic.Severity.WARNING, result.diagnostics().get(0).severity());
    }

    @Test
    void nothingLoadedMeansNothingParsed() {
        assertTrue(RecipeDefinitions.parse(null).recipes().isEmpty());
        assertTrue(RecipeDefinitions.parse(LoadReport.empty()).recipes().isEmpty());
    }
}
