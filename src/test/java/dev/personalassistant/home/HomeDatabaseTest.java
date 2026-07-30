package dev.personalassistant.home;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HomeDatabaseTest {
    @TempDir Path temp;

    @Test
    void shoppingListSubtractsMatchingInventory() {
        try (HomeDatabase database = new HomeDatabase(temp.resolve("home.db"))) {
            database.saveItem(new HomeDatabase.Item(0, "Rice", "Pantry", "Kitchen",
                    2, "cups", 1, "", "", "", ""));
            database.saveMeal(new HomeDatabase.Meal(0, "2026-07-28", "Dinner", "Rice bowls", 2,
                    List.of(new HomeDatabase.Ingredient("Rice", 3, "cups"),
                            new HomeDatabase.Ingredient("Beans", 2, "cans")), ""));

            List<HomeDatabase.ShoppingEntry> shopping =
                    database.shoppingList("2026-07-28", "2026-08-03");

            assertEquals(2, shopping.size());
            assertEquals(2, shopping.get(0).toBuy());
            assertEquals(1, shopping.get(1).toBuy());
        }
    }

    @Test
    void recipeMatchesRankRecipesByAvailableInventory() {
        try (HomeDatabase database = new HomeDatabase(temp.resolve("recipes.db"))) {
            database.saveItem(new HomeDatabase.Item(0, "pasta", "Pantry", "Kitchen",
                    8, "oz", 0, "", "", "", ""));
            database.saveItem(new HomeDatabase.Item(0, "tomato sauce", "Pantry", "Kitchen",
                    2, "cups", 0, "", "", "", ""));
            database.saveItem(new HomeDatabase.Item(0, "garlic", "Produce", "Kitchen",
                    2, "cloves", 0, "", "", "", ""));
            database.saveItem(new HomeDatabase.Item(0, "olive oil", "Pantry", "Kitchen",
                    1, "tbsp", 0, "", "", "", ""));

            HomeDatabase.RecipeMatch best = database.recipeMatches().getFirst();

            assertEquals("Tomato Pasta", best.recipe().name());
            assertEquals(1.0, best.coverage());
            assertEquals(0, best.missing().size());
        }
    }

    @Test
    void transcriptDerivedRecipesAreLoaded() {
        try (HomeDatabase database = new HomeDatabase(temp.resolve("transcript-recipes.db"))) {
            List<String> names = database.recipes().stream().map(HomeDatabase.Recipe::name).toList();

            assertEquals(true, names.contains("Chili Paneer"));
            assertEquals(true, names.contains("Chilli Chicken"));
            assertEquals(true, names.contains("Crispy Corn"));
            assertEquals(true, database.recipeEquipment("Crispy Corn").contains("strainer"));
            assertEquals(true, database.isRecipeVerified("Crispy Corn"));
            assertEquals(false, database.isRecipeVerified("Chili Paneer"));
            assertEquals(true, database.isRecipeVerified("Tomato Pasta"));
        }
    }

    @Test
    void administratorCanReviewAndVerifyRecipeDraft() {
        try (HomeDatabase database = new HomeDatabase(temp.resolve("admin-recipes.db"))) {
            HomeDatabase.RecipeDraft draft = database.recipeDrafts().stream()
                    .filter(recipe -> recipe.name().equals("Chili Paneer")).findFirst().orElseThrow();

            HomeDatabase.RecipeDraft saved = database.saveRecipeDraft(new HomeDatabase.RecipeDraft(
                    draft.id(), draft.name(), draft.category(), draft.servings(), draft.minutes(),
                    draft.ingredients(), draft.steps(), draft.equipment(), draft.sourcePath(),
                    "Reviewed against the source video.", true));

            assertEquals(true, saved.verified());
            assertEquals(true, database.isRecipeVerified("Chili Paneer"));
            assertEquals("Reviewed against the source video.", saved.extractionNotes());
            assertEquals(true, saved.equipment().contains("mixing bowl"));
        }
    }

    @Test
    void manualShoppingItemsAreNameOnlyAndNotDuplicated() {
        try (HomeDatabase database = new HomeDatabase(temp.resolve("shopping-dedup.db"))) {
            database.addShoppingItem("Paprika", 1, "each");
            database.addShoppingItem(" paprika ", 1, "each");

            List<HomeDatabase.ShoppingEntry> shopping =
                    database.shoppingList("2026-07-29", "2026-07-29");
            assertEquals(1, shopping.stream().filter(item ->
                    item.name().equalsIgnoreCase("Paprika")).count());
        }
    }

    @Test
    void completingMealReducesStandardizedInventoryOnce() {
        try (HomeDatabase database = new HomeDatabase(temp.resolve("meal-completion.db"))) {
            HomeDatabase.Item flour = database.saveItem(new HomeDatabase.Item(
                    0, "All-purpose flour", "Pantry", "Cabinet", 1, "kilogram",
                    0, "", "", "", ""));
            HomeDatabase.Meal meal = database.saveMeal(new HomeDatabase.Meal(
                    0, "2026-07-29", "Dinner", "Test bread", 2,
                    List.of(new HomeDatabase.Ingredient("All-purpose flour", 250, "grams")), ""));

            HomeDatabase.MealCompletion preview = database.previewMealCompletion(meal.id());
            assertEquals(0.25, preview.consumption().getFirst().deducted(), 0.0001);
            assertEquals("kg", preview.consumption().getFirst().inventoryUnit());

            database.completeMeal(meal.id());
            database.completeMeal(meal.id());

            assertEquals(0.75, database.items().stream()
                    .filter(item -> item.id() == flour.id()).findFirst().orElseThrow().quantity(), 0.0001);
            assertEquals(true, database.meals("2026-07-29", "2026-07-29").getFirst().completed());
            assertEquals(0, database.shoppingList("2026-07-29", "2026-07-29").size());
        }
    }

    @Test
    void publishesAndFlagsEggApproximation() {
        try (HomeDatabase database = new HomeDatabase(temp.resolve("meal-approximation.db"))) {
            database.saveItem(new HomeDatabase.Item(0, "Eggs", "Refrigerator", "Fridge",
                    2, "each", 0, "", "", "", ""));
            HomeDatabase.Meal meal = database.saveMeal(new HomeDatabase.Meal(
                    0, "2026-07-29", "Dinner", "Egg test", 1,
                    List.of(new HomeDatabase.Ingredient("beaten egg", 3, "tbsp")), ""));

            HomeDatabase.Consumption consumption =
                    database.previewMealCompletion(meal.id()).consumption().getFirst();
            assertEquals(1, consumption.deducted(), 0.0001);
            assertEquals(true, consumption.approximate());
            assertEquals(true, HomeDatabase.unitRules().stream().anyMatch(HomeDatabase.UnitRule::approximate));
        }
    }

    @Test
    void partialBeatenEggUseConsumesOneWholeEgg() {
        try (HomeDatabase database = new HomeDatabase(temp.resolve("whole-egg-consumption.db"))) {
            database.saveItem(new HomeDatabase.Item(0, "Eggs", "Refrigerator", "Fridge",
                    2, "each", 0, "", "", "", ""));
            HomeDatabase.Meal meal = database.saveMeal(new HomeDatabase.Meal(
                    0, "2026-07-29", "Dinner", "Coating test", 1,
                    List.of(new HomeDatabase.Ingredient("beaten egg", 1, "tbsp")), ""));

            HomeDatabase.Consumption preview =
                    database.previewMealCompletion(meal.id()).consumption().getFirst();
            assertEquals(1, preview.deducted(), 0.0001);
            assertEquals(true, preview.rule().contains("whole opened egg"));

            database.completeMeal(meal.id());
            assertEquals(1, database.items().getFirst().quantity(), 0.0001);
        }
    }

    @Test
    void ingredientSpecificApproximationsMatchMassAndCountInventory() {
        try (HomeDatabase database = new HomeDatabase(temp.resolve("density-matching.db"))) {
            database.saveItem(new HomeDatabase.Item(0, "All-purpose flour", "Pantry", "",
                    1, "kg", 0, "", "", "", ""));
            database.saveItem(new HomeDatabase.Item(0, "Baking powder", "Pantry", "",
                    100, "gram", 0, "", "", "", ""));
            database.saveItem(new HomeDatabase.Item(0, "Chili sauce", "Pantry", "",
                    100, "gram", 0, "", "", "", ""));
            database.saveItem(new HomeDatabase.Item(0, "Ginger garlic paste", "Pantry", "",
                    200, "gm", 0, "", "", "", ""));
            database.saveItem(new HomeDatabase.Item(0, "Garlic", "Produce", "",
                    10, "count", 0, "", "", "", ""));
            database.saveItem(new HomeDatabase.Item(0, "Bell peppers", "Produce", "",
                    12, "count", 0, "", "", "", ""));

            HomeDatabase.RecipeMatch match = database.recipeMatches().stream()
                    .filter(item -> item.recipe().name().equals("Chili Paneer"))
                    .findFirst().orElseThrow();
            Set<String> missing = match.missing().stream()
                    .map(HomeDatabase.Ingredient::name).collect(java.util.stream.Collectors.toSet());

            assertEquals(false, missing.contains("all-purpose flour"));
            assertEquals(false, missing.contains("baking powder"));
            assertEquals(false, missing.contains("chili sauce"));
            assertEquals(false, missing.contains("ginger garlic paste"));
            assertEquals(false, missing.contains("garlic"));
            assertEquals(false, missing.contains("bell peppers"));
            assertEquals(true, missing.contains("paprika"));
        }
    }

    @Test
    void shoppingPurchaseUnitsMatchRecipeUsingPublishedApproximations() {
        try (HomeDatabase database = new HomeDatabase(temp.resolve("shopping-unit-matching.db"))) {
            database.saveItem(new HomeDatabase.Item(0, "Paprika", "Shopping", "",
                    100, "g", 0, "", "", "", ""));
            database.saveItem(new HomeDatabase.Item(0, "Vegetable seasoning powder", "Shopping", "",
                    100, "g", 0, "", "", "", ""));
            database.saveItem(new HomeDatabase.Item(0, "Spring onions", "Shopping", "",
                    10, "each", 0, "", "", "", ""));

            Set<String> missing = database.recipeMatches().stream()
                    .filter(item -> item.recipe().name().equals("Chili Paneer"))
                    .findFirst().orElseThrow().missing().stream()
                    .map(HomeDatabase.Ingredient::name).collect(java.util.stream.Collectors.toSet());

            assertEquals(false, missing.contains("paprika"));
            assertEquals(false, missing.contains("vegetable seasoning powder"));
            assertEquals(false, missing.contains("spring onions"));
        }
    }

    @Test
    void wholeProduceCountsConvertThroughBaseVolume() {
        try (HomeDatabase database = new HomeDatabase(temp.resolve("whole-produce-volume.db"))) {
            database.saveItem(new HomeDatabase.Item(0, "Garlic", "Produce", "",
                    10, "count", 0, "", "", "", ""));
            database.saveItem(new HomeDatabase.Item(0, "Coriander", "Produce", "",
                    2, "count", 0, "", "", "", ""));

            Set<String> missing = database.recipeMatches().stream()
                    .filter(item -> item.recipe().name().equals("Crispy Corn"))
                    .findFirst().orElseThrow().missing().stream()
                    .map(HomeDatabase.Ingredient::name).collect(java.util.stream.Collectors.toSet());

            assertEquals(false, missing.contains("garlic"));
            assertEquals(false, missing.contains("coriander"));
            assertEquals(1.0, HomeDatabase.convertQuantity(
                    "garlic", 3, "each", "tbsp"), 0.0001);
        }
    }

    @Test
    void cookingTestInventoryIsPartialAndIdempotent() {
        try (HomeDatabase database = new HomeDatabase(temp.resolve("test-pantry.db"))) {
            assertEquals(12, database.seedCookingTestInventory().size());
            assertEquals(0, database.seedCookingTestInventory().size());
            List<String> names = database.items().stream().map(HomeDatabase.Item::name).toList();
            assertEquals(true, names.contains("Paneer"));
            assertEquals(false, names.contains("Chili sauce"));
            assertEquals(false, names.contains("Vinegar"));
        }
    }

    @Test
    void manualShoppingItemsCanBeAddedRemovedAndCleared() {
        try (HomeDatabase database = new HomeDatabase(temp.resolve("shopping.db"))) {
            HomeDatabase.ManualShoppingItem first = database.addShoppingItem("Chili sauce", 2, "bottles");
            database.addShoppingItem("Vinegar", 1, "bottle");
            assertEquals(2, database.shoppingList("2026-07-27", "2026-08-02").size());

            database.removeShoppingItem(first.id(), first.name());
            assertEquals(1, database.shoppingList("2026-07-27", "2026-08-02").size());

            database.clearShoppingList("2026-07-27", "2026-08-02");
            assertEquals(0, database.shoppingList("2026-07-27", "2026-08-02").size());
        }
    }

    @Test
    void eggsSatisfyBeatenEggTablespoons() {
        try (HomeDatabase database = new HomeDatabase(temp.resolve("egg-alias.db"))) {
            database.saveItem(new HomeDatabase.Item(0, "Eggs", "Refrigerator", "Fridge",
                    2, "each", 0, "", "", "", ""));
            database.saveMeal(new HomeDatabase.Meal(0, "2026-07-29", "Dinner", "Crispy Corn", 3,
                    List.of(new HomeDatabase.Ingredient("beaten egg", 1, "tbsp")), ""));

            assertEquals(0, database.shoppingList("2026-07-29", "2026-07-29").size());
        }
    }

    @Test
    void purchasedShoppingItemMovesToInventoryAndNewMealRestoresDismissedShortage() {
        try (HomeDatabase database = new HomeDatabase(temp.resolve("purchase.db"))) {
            database.saveMeal(new HomeDatabase.Meal(0, "2026-07-29", "Dinner", "Test",
                    1, List.of(new HomeDatabase.Ingredient("Chili sauce", 2, "tbsp")), ""));
            database.removeShoppingItem(0, "Chili sauce");
            assertEquals(0, database.shoppingList("2026-07-29", "2026-07-29").size());

            database.saveMeal(new HomeDatabase.Meal(0, "2026-07-29", "Dinner", "Test again",
                    1, List.of(new HomeDatabase.Ingredient("Chili sauce", 2, "tbsp")), ""));
            assertEquals(1, database.shoppingList("2026-07-29", "2026-07-29").size());

            database.purchaseShoppingItem(0, "Chili sauce", 4, "tbsp", "add");
            assertEquals(0, database.shoppingList("2026-07-29", "2026-07-29").size());
            assertEquals(4, database.items().stream()
                    .filter(item -> item.name().equals("Chili sauce")).findFirst().orElseThrow().quantity());
        }
    }

    @Test
    void purchasedItemCanAddToOrReplaceExistingInventory() {
        try (HomeDatabase database = new HomeDatabase(temp.resolve("replace-inventory.db"))) {
            database.saveItem(new HomeDatabase.Item(0, "Rice", "Pantry", "Kitchen",
                    2, "cups", 0, "", "", "", ""));
            database.purchaseShoppingItem(0, "Rice", 3, "cups", "add");
            assertEquals(5, database.matchingInventoryItem("rice").quantity());
            database.purchaseShoppingItem(0, "Rice", 1, "bag", "replace");
            assertEquals(1, database.matchingInventoryItem("rice").quantity());
            assertEquals("bag", database.matchingInventoryItem("rice").unit());
        }
    }
}
