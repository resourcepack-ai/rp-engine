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
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    // ---- smithing, brewing, anvil ---------------------------------------------------

    @Test
    void readsASmithingUpgrade() throws IOException {
        write("mypack/recipes/a.yml", """
                ruby_sword:
                  type: smithing
                  template: NETHERITE_UPGRADE_SMITHING_TEMPLATE
                  base: mypack:obsidian_sword
                  addition: mypack:ruby
                  result: mypack:ruby_sword
                """);

        RecipeInfo recipe = one(parse(), "mypack:ruby_sword");

        assertEquals(RecipeInfo.Type.SMITHING, recipe.type());
        assertEquals("NETHERITE_UPGRADE_SMITHING_TEMPLATE", recipe.template().orElseThrow());
        assertEquals("mypack:obsidian_sword", recipe.base().orElseThrow());
        assertEquals("mypack:ruby", recipe.addition().orElseThrow());
        assertEquals("mypack:ruby_sword", recipe.result());
        // Vanilla's upgrade keeps what you did to the sword, and so does ours
        // unless told otherwise.
        assertTrue(recipe.copyData());
    }

    @Test
    void copyDataCanBeTurnedOff() throws IOException {
        write("mypack/recipes/a.yml", """
                fresh:
                  type: smithing
                  template: PAPER
                  base: IRON_SWORD
                  addition: mypack:ruby
                  result: mypack:ruby_sword
                  copy-data: false
                """);

        assertFalse(one(parse(), "mypack:fresh").copyData());
    }

    @Test
    void aSmithingRecipeNamesEveryMissingSlot() throws IOException {
        write("mypack/recipes/a.yml",
                "half:\n  type: smithing\n  base: IRON_SWORD\n  result: DIAMOND_SWORD\n");

        RecipeDefinitions.Result result = parse();

        assertTrue(result.recipes().isEmpty());
        String message = result.diagnostics().get(0).message();
        assertTrue(message.contains("template") && message.contains("addition"), message);
    }

    @Test
    void aTrimHasNoResultAndKeepsItsPattern() throws IOException {
        write("mypack/recipes/a.yml", """
                ruby_trim:
                  type: smithing-trim
                  template: mypack:ruby_template
                  base: mypack:ruby_chestplate
                  addition: AMETHYST_SHARD
                  pattern: minecraft:silence
                  result: DIAMOND
                """);

        RecipeDefinitions.Result result = parse();
        RecipeInfo recipe = one(result, "mypack:ruby_trim");

        assertEquals(RecipeInfo.Type.SMITHING_TRIM, recipe.type());
        assertEquals("", recipe.result());
        assertEquals("minecraft:silence", recipe.pattern().orElseThrow());
        assertTrue(result.diagnostics().get(0).message().contains("no result"));
    }

    @Test
    void readsABrewingRecipe() throws IOException {
        write("mypack/recipes/a.yml", """
                tonic:
                  type: brewing
                  base: potion/awkward
                  ingredient: mypack:ruby_dust
                  result: mypack:ruby_tonic
                """);

        RecipeInfo recipe = one(parse(), "mypack:tonic");

        assertEquals(RecipeInfo.Type.BREWING, recipe.type());
        assertEquals("potion/awkward", recipe.base().orElseThrow());
        assertEquals(List.of("mypack:ruby_dust"), recipe.ingredients());
    }

    @Test
    void aBrewingBaseMayBeCalledInput() throws IOException {
        write("mypack/recipes/a.yml",
                "tonic:\n  type: brewing\n  input: GLASS_BOTTLE\n  ingredient: DIAMOND\n  result: STICK\n");

        assertEquals("GLASS_BOTTLE", one(parse(), "mypack:tonic").base().orElseThrow());
    }

    @Test
    void readsAnAnvilRecipe() throws IOException {
        write("mypack/recipes/a.yml", """
                sharpen:
                  type: anvil
                  base: mypack:dull_blade
                  addition: mypack:whetstone
                  addition-amount: 2
                  result: mypack:sharp_blade
                  cost: 3
                """);

        RecipeInfo recipe = one(parse(), "mypack:sharpen");

        assertEquals(RecipeInfo.Type.ANVIL, recipe.type());
        assertFalse(recipe.isRepair());
        assertEquals(2, recipe.additionAmount());
        assertEquals(3, recipe.cost());
    }

    @Test
    void anAnvilRecipeMayLeaveTheSecondSlotEmpty() throws IOException {
        write("mypack/recipes/a.yml", "polish:\n  type: anvil\n  base: mypack:dull\n  result: mypack:shiny\n");

        RecipeInfo recipe = one(parse(), "mypack:polish");

        assertTrue(recipe.addition().isEmpty());
        assertEquals(1, recipe.cost(), "a level, as vanilla never charges nothing");
    }

    @Test
    void anAnvilRepairIsAPercentageOrAPointCount() throws IOException {
        write("mypack/recipes/a.yml", """
                mend:
                  type: anvil
                  base: mypack:ruby_sword
                  addition: mypack:ruby
                  repair: 25%
                patch:
                  type: anvil
                  base: mypack:ruby_sword
                  addition: STICK
                  repair: 40
                """);

        RecipeDefinitions.Result result = parse();
        RecipeInfo mend = one(result, "mypack:mend");
        RecipeInfo patch = one(result, "mypack:patch");

        assertTrue(mend.isRepair());
        assertEquals(0.25f, mend.repairFraction(), 1e-6);
        assertEquals(40, patch.repairPoints());
        assertEquals("", patch.result());
    }

    @Test
    void anAnvilRecipeNeedsAResultOrARepairButNotBoth() throws IOException {
        write("mypack/recipes/a.yml", """
                neither:
                  type: anvil
                  base: STICK
                both:
                  type: anvil
                  base: STICK
                  addition: STICK
                  result: DIAMOND
                  repair: 10
                bare:
                  type: anvil
                  base: STICK
                  repair: 10%
                """);

        RecipeDefinitions.Result result = parse();

        assertTrue(result.recipes().isEmpty());
        assertEquals(3, result.diagnostics().size());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("needs an addition")));
    }

    @Test
    void nothingLoadedMeansNothingParsed() {
        assertTrue(RecipeDefinitions.parse(null).recipes().isEmpty());
        assertTrue(RecipeDefinitions.parse(LoadReport.empty()).recipes().isEmpty());
    }
}
