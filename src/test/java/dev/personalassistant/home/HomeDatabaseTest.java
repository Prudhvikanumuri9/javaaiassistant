package dev.personalassistant.home;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

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
}
