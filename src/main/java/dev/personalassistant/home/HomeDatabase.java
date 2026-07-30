package dev.personalassistant.home;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;

public final class HomeDatabase implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Connection connection;

    public record Item(long id, String name, String category, String location,
                       double quantity, String unit, double minQuantity,
                       String expiresOn, String notes, String imageFilename, String updatedAt) {}

    public record Ingredient(String name, double quantity, String unit) {}

    public record Meal(long id, String mealDate, String mealType, String recipeName,
                       int servings, List<Ingredient> ingredients, String notes,
                       boolean completed, String completedAt) {
        public Meal(long id, String mealDate, String mealType, String recipeName,
                    int servings, List<Ingredient> ingredients, String notes) {
            this(id, mealDate, mealType, recipeName, servings, ingredients, notes, false, "");
        }
    }

    public record ShoppingEntry(long id, String name, double needed, double available,
                                double toBuy, String unit, List<String> meals, boolean manual) {}
    public record ManualShoppingItem(long id, String name, double quantity, String unit) {}
    public record Recipe(long id, String name, String category, int servings,
                         int minutes, List<Ingredient> ingredients, List<String> steps) {}
    public record RecipeDraft(long id, String name, String category, int servings,
                              int minutes, List<Ingredient> ingredients, List<String> steps,
                              List<String> equipment, String sourcePath,
                              String extractionNotes, boolean verified) {}
    public record RecipeMatch(Recipe recipe, List<Ingredient> missing, int availableCount,
                              double coverage) {}
    public record UnitRule(String family, String fromUnit, String toUnit,
                           double multiplier, boolean approximate, String notes) {}
    public record Consumption(long itemId, String ingredient, double recipeQuantity,
                              String recipeUnit, double inventoryBefore, double deducted,
                              double inventoryAfter, String inventoryUnit, boolean approximate,
                              boolean fullyCovered, String rule) {}
    public record MealCompletion(long mealId, String recipeName, boolean alreadyCompleted,
                                 List<Consumption> consumption) {}

    public HomeDatabase(Path file) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            initialize();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot initialize home database " + file, e);
        }
    }

    private void initialize() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS inventory_items (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      name TEXT NOT NULL,
                      category TEXT NOT NULL DEFAULT '',
                      location TEXT NOT NULL DEFAULT '',
                      quantity REAL NOT NULL DEFAULT 0,
                      unit TEXT NOT NULL DEFAULT 'each',
                      min_quantity REAL NOT NULL DEFAULT 0,
                      expires_on TEXT NOT NULL DEFAULT '',
                      notes TEXT NOT NULL DEFAULT '',
                      image_filename TEXT NOT NULL DEFAULT '',
                      updated_at TEXT NOT NULL
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS meal_plans (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      meal_date TEXT NOT NULL,
                      meal_type TEXT NOT NULL,
                      recipe_name TEXT NOT NULL,
                      servings INTEGER NOT NULL DEFAULT 1,
                      ingredients_json TEXT NOT NULL DEFAULT '[]',
                      notes TEXT NOT NULL DEFAULT ''
                    )""");
            addColumnIfMissing(statement, "meal_plans",
                    "completed INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(statement, "meal_plans",
                    "completed_at TEXT NOT NULL DEFAULT ''");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS recipes (
                      id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE,
                      category TEXT NOT NULL DEFAULT '', servings INTEGER NOT NULL DEFAULT 2,
                      minutes INTEGER NOT NULL DEFAULT 30, ingredients_json TEXT NOT NULL,
                      steps_json TEXT NOT NULL
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS recipe_sources (
                      recipe_name TEXT PRIMARY KEY, source_path TEXT NOT NULL DEFAULT '',
                      extraction_notes TEXT NOT NULL DEFAULT '',
                      verified INTEGER NOT NULL DEFAULT 0
                    )""");
            try {
                statement.executeUpdate(
                        "ALTER TABLE recipe_sources ADD COLUMN verified INTEGER NOT NULL DEFAULT 0");
            } catch (SQLException alreadyExists) {
                if (!alreadyExists.getMessage().toLowerCase(Locale.ROOT).contains("duplicate column")) {
                    throw alreadyExists;
                }
            }
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS recipe_equipment (
                      recipe_name TEXT NOT NULL, equipment TEXT NOT NULL,
                      PRIMARY KEY(recipe_name, equipment)
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS manual_shopping_items (
                      id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL,
                      quantity REAL NOT NULL DEFAULT 1, unit TEXT NOT NULL DEFAULT 'each'
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS shopping_dismissals (
                      normalized_name TEXT PRIMARY KEY
                    )""");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_inventory_name ON inventory_items(name)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_meals_date ON meal_plans(meal_date)");
        }
        seedRecipes();
    }

    private static void addColumnIfMissing(Statement statement, String table, String definition)
            throws SQLException {
        try {
            statement.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + definition);
        } catch (SQLException alreadyExists) {
            if (!alreadyExists.getMessage().toLowerCase(Locale.ROOT).contains("duplicate column")) {
                throw alreadyExists;
            }
        }
    }

    public synchronized List<Item> items() {
        List<Item> items = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT * FROM inventory_items ORDER BY name COLLATE NOCASE")) {
            while (rows.next()) items.add(readItem(rows));
            return items;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read inventory", e);
        }
    }

    /** Adds a deliberately incomplete pantry for exercising recipe and shortage tools. */
    public synchronized List<Item> seedCookingTestInventory() {
        List<Item> samples = List.of(
                testItem("Paneer", "Dairy", 220, "g"),
                testItem("Salt", "Spices", 10, "tsp"),
                testItem("Vegetable seasoning powder", "Spices", 2, "tsp"),
                testItem("Ginger garlic paste", "Refrigerator", 3, "tsp"),
                testItem("Dark soy sauce", "Sauces", 2, "tsp"),
                testItem("All-purpose flour", "Pantry", 10, "tbsp"),
                testItem("Corn flour", "Pantry", 10, "tbsp"),
                testItem("Garlic", "Produce", 10, "tsp"),
                testItem("Bell peppers", "Produce", 2, "cups"),
                testItem("Rice", "Pantry", 4, "cups"),
                testItem("Eggs", "Refrigerator", 6, "each"),
                testItem("Olive oil", "Pantry", 8, "tbsp"));
        Set<String> existing = items().stream().map(item -> normalize(item.name()))
                .collect(java.util.stream.Collectors.toSet());
        List<Item> added = new ArrayList<>();
        for (Item sample : samples) {
            if (existing.add(normalize(sample.name()))) added.add(saveItem(sample));
        }
        return List.copyOf(added);
    }

    private static Item testItem(String name, String category, double quantity, String unit) {
        return new Item(0, name, category, "Cooking test pantry", quantity, unit,
                0, "", "Sample test data; safe to edit or delete.", "", "");
    }

    public synchronized Item saveItem(Item item) {
        String now = Instant.now().toString();
        item = new Item(item.id(), item.name(), item.category(), item.location(), item.quantity(),
                standardUnit(item.unit()), item.minQuantity(), item.expiresOn(), item.notes(),
                item.imageFilename(), item.updatedAt());
        if (item.id() == 0) {
            String sql = """
                    INSERT INTO inventory_items
                    (name, category, location, quantity, unit, min_quantity, expires_on, notes, image_filename, updated_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?)""";
            try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                bindItem(statement, item, now);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    long id = keys.next() ? keys.getLong(1) : 0;
                    return new Item(id, item.name(), item.category(), item.location(), item.quantity(),
                            item.unit(), item.minQuantity(), item.expiresOn(), item.notes(), item.imageFilename(), now);
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Cannot add inventory item", e);
            }
        }
        String sql = """
                UPDATE inventory_items SET name=?, category=?, location=?, quantity=?, unit=?,
                min_quantity=?, expires_on=?, notes=?, image_filename=?, updated_at=? WHERE id=?""";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindItem(statement, item, now);
            statement.setLong(11, item.id());
            if (statement.executeUpdate() == 0) throw new IllegalArgumentException("Item not found: " + item.id());
            return new Item(item.id(), item.name(), item.category(), item.location(), item.quantity(),
                    item.unit(), item.minQuantity(), item.expiresOn(), item.notes(), item.imageFilename(), now);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot update inventory item", e);
        }
    }

    public synchronized void deleteItem(long id) {
        executeDelete("DELETE FROM inventory_items WHERE id=?", id);
    }

    public synchronized List<Meal> meals(String from, String to) {
        String sql = "SELECT * FROM meal_plans WHERE meal_date BETWEEN ? AND ? ORDER BY meal_date, meal_type";
        List<Meal> meals = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, from);
            statement.setString(2, to);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) meals.add(readMeal(rows));
            }
            return meals;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read meal plan", e);
        }
    }

    public synchronized Meal saveMeal(Meal meal) {
        try {
            List<Ingredient> standardized = standardizeIngredients(meal.ingredients());
            String ingredients = JSON.writeValueAsString(standardized);
            String sql = """
                    INSERT INTO meal_plans(meal_date, meal_type, recipe_name, servings,
                      ingredients_json, notes, completed, completed_at)
                    VALUES(?,?,?,?,?,?,?,?)""";
            try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, clean(meal.mealDate()));
                statement.setString(2, clean(meal.mealType()));
                statement.setString(3, clean(meal.recipeName()));
                statement.setInt(4, Math.max(1, meal.servings()));
                statement.setString(5, ingredients);
                statement.setString(6, clean(meal.notes()));
                statement.setInt(7, meal.completed() ? 1 : 0);
                statement.setString(8, clean(meal.completedAt()));
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    long id = keys.next() ? keys.getLong(1) : 0;
                    clearShoppingDismissals(meal.ingredients());
                    return new Meal(id, meal.mealDate(), meal.mealType(), meal.recipeName(),
                            Math.max(1, meal.servings()), standardized, meal.notes(),
                            meal.completed(), meal.completedAt());
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot save meal", e);
        }
    }

    public synchronized void deleteMeal(long id) {
        executeDelete("DELETE FROM meal_plans WHERE id=?", id);
    }

    public synchronized MealCompletion previewMealCompletion(long mealId) {
        Meal meal = mealById(mealId);
        if (meal.completed()) return new MealCompletion(meal.id(), meal.recipeName(), true, List.of());
        Map<String, Item> inventory = new HashMap<>();
        items().forEach(item -> inventory.put(canonicalIngredient(item.name()), item));
        List<Consumption> result = new ArrayList<>();
        for (Ingredient ingredient : meal.ingredients()) {
            String canonical = canonicalIngredient(ingredient.name());
            Item item = inventory.get(canonical);
            if (item == null) {
                result.add(new Consumption(0, ingredient.name(), ingredient.quantity(),
                        standardUnit(ingredient.unit()), 0, 0, 0, "", false, false,
                        "No matching inventory item"));
                continue;
            }
            Conversion conversion = conversion(canonical, ingredient.quantity(),
                    ingredient.unit(), item.unit());
            boolean wholeEggConsumption = canonical.equals("egg")
                    && standardUnit(item.unit()).equals("each")
                    && !standardUnit(ingredient.unit()).equals("each")
                    && conversion.quantity() > 0;
            double requested = wholeEggConsumption
                    ? Math.ceil(conversion.quantity()) : conversion.quantity();
            double deducted = requested <= 0 ? 0 : Math.min(item.quantity(), requested);
            result.add(new Consumption(item.id(), ingredient.name(), ingredient.quantity(),
                    standardUnit(ingredient.unit()), item.quantity(), deducted,
                    Math.max(0, item.quantity() - deducted), standardUnit(item.unit()),
                    conversion.approximate() || wholeEggConsumption,
                    requested > 0 && item.quantity() + 0.000001 >= requested,
                    wholeEggConsumption
                            ? conversion.rule() + "; consumption rounds up to a whole opened egg"
                            : conversion.rule()));
        }
        return new MealCompletion(meal.id(), meal.recipeName(), false, List.copyOf(result));
    }

    public synchronized MealCompletion completeMeal(long mealId) {
        MealCompletion preview = previewMealCompletion(mealId);
        if (preview.alreadyCompleted()) return preview;
        try {
            connection.setAutoCommit(false);
            String now = Instant.now().toString();
            try (PreparedStatement updateItem = connection.prepareStatement(
                    "UPDATE inventory_items SET quantity=?, updated_at=? WHERE id=?");
                 PreparedStatement complete = connection.prepareStatement(
                         "UPDATE meal_plans SET completed=1, completed_at=? WHERE id=? AND completed=0")) {
                for (Consumption item : preview.consumption()) {
                    if (item.itemId() == 0 || item.deducted() <= 0) continue;
                    updateItem.setDouble(1, item.inventoryAfter());
                    updateItem.setString(2, now);
                    updateItem.setLong(3, item.itemId());
                    updateItem.addBatch();
                }
                updateItem.executeBatch();
                complete.setString(1, now);
                complete.setLong(2, mealId);
                if (complete.executeUpdate() != 1) throw new IllegalStateException(
                        "Meal was completed by another request");
            }
            connection.commit();
            connection.setAutoCommit(true);
            return preview;
        } catch (Exception error) {
            try {
                connection.rollback();
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
            }
            throw new IllegalStateException("Cannot complete meal and reduce inventory", error);
        }
    }

    private Meal mealById(long mealId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM meal_plans WHERE id=?")) {
            statement.setLong(1, mealId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("Meal not found: " + mealId);
                return readMeal(row);
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Cannot read meal", error);
        }
    }

    public synchronized List<ShoppingEntry> shoppingList(String from, String to) {
        Map<String, Item> inventory = new HashMap<>();
        for (Item item : items()) inventory.put(canonicalIngredient(item.name()), item);
        record Need(String display, String unit, double quantity, Set<String> meals) {}
        Map<String, Need> needs = new LinkedHashMap<>();
        Set<String> countedMeals = new HashSet<>();
        for (Meal meal : meals(from, to)) {
            if (meal.completed()) continue;
            String mealKey = meal.mealDate() + "|" + normalize(meal.mealType()) + "|"
                    + normalize(meal.recipeName());
            if (!countedMeals.add(mealKey)) continue;
            for (Ingredient ingredient : meal.ingredients()) {
                String canonical = canonicalIngredient(ingredient.name());
                String key = canonical;
                Need current = needs.get(key);
                if (current != null && convertQuantity(canonical, ingredient.quantity(),
                        ingredient.unit(), current.unit()) == 0
                        && !normalize(ingredient.unit()).equals(normalize(current.unit()))) {
                    key = canonical + "|" + normalize(ingredient.unit());
                    current = needs.get(key);
                }
                Set<String> usedBy = current == null ? new LinkedHashSet<>() : current.meals();
                usedBy.add(meal.recipeName());
                double converted = current == null ? ingredient.quantity()
                        : convertQuantity(canonical, ingredient.quantity(), ingredient.unit(), current.unit());
                needs.put(key, new Need(ingredient.name(), ingredient.unit(),
                        (current == null ? 0 : current.quantity()) + converted, usedBy));
            }
        }
        List<ShoppingEntry> result = new ArrayList<>();
        for (Need need : needs.values()) {
            Item stocked = inventory.get(canonicalIngredient(need.display()));
            double available = stocked == null ? 0 : convertQuantity(
                    canonicalIngredient(need.display()), stocked.quantity(), stocked.unit(), need.unit());
            double toBuy = Math.max(0, need.quantity() - available);
            if (toBuy > 0) {
                if (!isShoppingDismissed(need.display())) {
                    result.add(new ShoppingEntry(0, need.display(), need.quantity(), available, toBuy,
                            need.unit(), List.copyOf(need.meals()), false));
                }
            }
        }
        for (ManualShoppingItem item : manualShoppingItems()) {
            result.add(new ShoppingEntry(item.id(), item.name(), item.quantity(), 0,
                    item.quantity(), item.unit(), List.of("Manually added"), true));
        }
        result.sort(Comparator.comparing(ShoppingEntry::name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    public synchronized ManualShoppingItem addShoppingItem(String name, double quantity, String unit) {
        String cleaned = clean(name);
        if (cleaned.isBlank()) throw new IllegalArgumentException("Shopping item name is required");
        ManualShoppingItem existing = manualShoppingItems().stream()
                .filter(item -> normalize(item.name()).equals(normalize(cleaned)))
                .findFirst().orElse(null);
        if (existing != null) return existing;
        try (PreparedStatement clear = connection.prepareStatement(
                "DELETE FROM shopping_dismissals WHERE normalized_name=?");
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO manual_shopping_items(name,quantity,unit) VALUES(?,?,?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            clear.setString(1, normalize(cleaned)); clear.executeUpdate();
            insert.setString(1, cleaned); insert.setDouble(2, Math.max(0.01, quantity));
            insert.setString(3, clean(unit).isBlank() ? "each" : clean(unit)); insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                return new ManualShoppingItem(keys.next() ? keys.getLong(1) : 0,
                        cleaned, Math.max(0.01, quantity), clean(unit).isBlank() ? "each" : clean(unit));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot add shopping item", e);
        }
    }

    public synchronized ManualShoppingItem editShoppingItem(long id, String originalName,
                                                             String name, double quantity, String unit) {
        if (id <= 0) {
            removeShoppingItem(0, originalName);
            return addShoppingItem(name, quantity, unit);
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE manual_shopping_items SET name=?,quantity=?,unit=? WHERE id=?")) {
            statement.setString(1, clean(name));
            statement.setDouble(2, Math.max(0.01, quantity));
            statement.setString(3, clean(unit).isBlank() ? "each" : clean(unit));
            statement.setLong(4, id);
            if (statement.executeUpdate() == 0) throw new IllegalArgumentException("Shopping item not found");
            return new ManualShoppingItem(id, clean(name), Math.max(0.01, quantity),
                    clean(unit).isBlank() ? "each" : clean(unit));
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot edit shopping item", e);
        }
    }

    public synchronized Item matchingInventoryItem(String name) {
        String canonical = canonicalIngredient(name);
        return items().stream().filter(item -> canonicalIngredient(item.name()).equals(canonical))
                .findFirst().orElse(null);
    }

    public synchronized Item purchaseShoppingItem(long id, String name, double quantity,
                                                  String unit, String mode) {
        String canonical = canonicalIngredient(name);
        Item existing = matchingInventoryItem(name);
        Item saved;
        if (existing == null) {
            saved = saveItem(new Item(0, name, "Shopping", "Unassigned",
                    quantity, unit, 0, "", "Added from completed shopping list.", "", ""));
        } else if ("replace".equalsIgnoreCase(mode)) {
            saved = saveItem(new Item(existing.id(), existing.name(), existing.category(),
                    existing.location(), quantity, unit, existing.minQuantity(), existing.expiresOn(),
                    existing.notes(), existing.imageFilename(), existing.updatedAt()));
        } else {
            double converted = convertQuantity(canonical, quantity, unit, existing.unit());
            double addition = converted > 0 ? converted
                    : normalize(unit).equals(normalize(existing.unit())) ? quantity : 0;
            if (addition <= 0) throw new IllegalArgumentException(
                    "Cannot convert " + unit + " to inventory unit " + existing.unit()
                            + ". Edit the shopping unit first.");
            saved = saveItem(new Item(existing.id(), existing.name(), existing.category(),
                    existing.location(), existing.quantity() + addition, existing.unit(),
                    existing.minQuantity(), existing.expiresOn(), existing.notes(),
                    existing.imageFilename(), existing.updatedAt()));
        }
        removeShoppingItem(id, name);
        return saved;
    }

    public synchronized void removeShoppingItem(long id, String name) {
        try {
            if (id > 0) executeDelete("DELETE FROM manual_shopping_items WHERE id=?", id);
            else if (name != null && !name.isBlank()) try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT OR IGNORE INTO shopping_dismissals(normalized_name) VALUES(?)")) {
                statement.setString(1, normalize(name)); statement.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot remove shopping item", e);
        }
    }

    public synchronized void clearShoppingList(String from, String to) {
        List<String> names = shoppingList(from, to).stream().filter(entry -> !entry.manual())
                .map(ShoppingEntry::name).toList();
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM manual_shopping_items");
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot clear manual shopping items", e);
        }
        names.forEach(name -> removeShoppingItem(0, name));
    }

    private List<ManualShoppingItem> manualShoppingItems() {
        List<ManualShoppingItem> result = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT * FROM manual_shopping_items ORDER BY id")) {
            while (rows.next()) result.add(new ManualShoppingItem(rows.getLong("id"),
                    rows.getString("name"), rows.getDouble("quantity"), rows.getString("unit")));
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read manual shopping items", e);
        }
    }

    private boolean isShoppingDismissed(String name) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM shopping_dismissals WHERE normalized_name=?")) {
            statement.setString(1, normalize(name));
            try (ResultSet row = statement.executeQuery()) { return row.next(); }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read shopping dismissals", e);
        }
    }

    private void clearShoppingDismissals(List<Ingredient> ingredients) throws SQLException {
        if (ingredients == null) return;
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM shopping_dismissals WHERE normalized_name=?")) {
            for (Ingredient ingredient : ingredients) {
                statement.setString(1, normalize(ingredient.name()));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    public synchronized List<Recipe> recipes() {
        List<Recipe> result = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT * FROM recipes ORDER BY name")) {
            while (rows.next()) result.add(readRecipe(rows));
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read recipes", e);
        }
    }

    public synchronized List<RecipeDraft> recipeDrafts() {
        return recipes().stream().map(recipe -> new RecipeDraft(
                recipe.id(), recipe.name(), recipe.category(), recipe.servings(), recipe.minutes(),
                recipe.ingredients(), recipe.steps(), recipeEquipment(recipe.name()),
                recipeSourceValue(recipe.name(), "source_path"),
                recipeSourceValue(recipe.name(), "extraction_notes"),
                isRecipeVerified(recipe.name()))).toList();
    }

    public synchronized RecipeDraft saveRecipeDraft(RecipeDraft draft) {
        String name = clean(draft.name());
        if (name.isBlank()) throw new IllegalArgumentException("Recipe name is required");
        if (draft.verified() && (draft.ingredients() == null || draft.ingredients().isEmpty()
                || draft.steps() == null || draft.steps().isEmpty())) {
            throw new IllegalArgumentException("A verified recipe requires ingredients and steps");
        }
        boolean previousAutoCommit;
        try {
            previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            String recipeSql = """
                    INSERT INTO recipes(name,category,servings,minutes,ingredients_json,steps_json)
                    VALUES(?,?,?,?,?,?)
                    ON CONFLICT(name) DO UPDATE SET category=excluded.category,
                      servings=excluded.servings, minutes=excluded.minutes,
                      ingredients_json=excluded.ingredients_json, steps_json=excluded.steps_json
                    """;
            try (PreparedStatement statement = connection.prepareStatement(recipeSql)) {
                statement.setString(1, name);
                statement.setString(2, clean(draft.category()));
                statement.setInt(3, Math.max(1, draft.servings()));
                statement.setInt(4, Math.max(1, draft.minutes()));
                statement.setString(5, JSON.writeValueAsString(standardizeIngredients(draft.ingredients())));
                statement.setString(6, JSON.writeValueAsString(
                        draft.steps() == null ? List.of() : draft.steps()));
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO recipe_sources(recipe_name,source_path,extraction_notes,verified)
                    VALUES(?,?,?,?)
                    ON CONFLICT(recipe_name) DO UPDATE SET source_path=excluded.source_path,
                      extraction_notes=excluded.extraction_notes, verified=excluded.verified
                    """)) {
                statement.setString(1, name);
                statement.setString(2, clean(draft.sourcePath()));
                statement.setString(3, clean(draft.extractionNotes()));
                statement.setInt(4, draft.verified() ? 1 : 0);
                statement.executeUpdate();
            }
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM recipe_equipment WHERE recipe_name=?")) {
                delete.setString(1, name);
                delete.executeUpdate();
            }
            seedEquipment(name, draft.equipment() == null ? new String[0]
                    : draft.equipment().stream().map(HomeDatabase::clean)
                    .filter(value -> !value.isBlank()).distinct().toArray(String[]::new));
            connection.commit();
            connection.setAutoCommit(previousAutoCommit);
            return recipeDrafts().stream().filter(recipe -> recipe.name().equalsIgnoreCase(name))
                    .findFirst().orElseThrow();
        } catch (Exception error) {
            try {
                connection.rollback();
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
            }
            throw new IllegalStateException("Cannot save recipe draft", error);
        }
    }

    private String recipeSourceValue(String recipeName, String column) {
        if (!column.equals("source_path") && !column.equals("extraction_notes")) return "";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + column + " FROM recipe_sources WHERE recipe_name=?")) {
            statement.setString(1, recipeName);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? clean(row.getString(1)) : "";
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Cannot read recipe source", error);
        }
    }

    public synchronized List<RecipeMatch> recipeMatches() {
        Map<String, Item> stocked = new HashMap<>();
        items().forEach(item -> stocked.put(canonicalIngredient(item.name()), item));
        List<RecipeMatch> matches = new ArrayList<>();
        for (Recipe recipe : recipes()) {
            List<Ingredient> missing = new ArrayList<>();
            int available = 0;
            for (Ingredient ingredient : recipe.ingredients()) {
                String canonical = canonicalIngredient(ingredient.name());
                Item item = stocked.get(canonical);
                double onHand = item == null ? 0 : convertQuantity(
                        canonical, item.quantity(), item.unit(), ingredient.unit());
                if (onHand >= ingredient.quantity()) available++;
                else missing.add(new Ingredient(ingredient.name(),
                        Math.max(0, ingredient.quantity() - onHand),
                        ingredient.unit()));
            }
            double coverage = recipe.ingredients().isEmpty() ? 0
                    : (double) available / recipe.ingredients().size();
            matches.add(new RecipeMatch(recipe, missing, available, coverage));
        }
        matches.sort(Comparator.comparingDouble(RecipeMatch::coverage).reversed()
                .thenComparingInt(match -> match.missing().size())
                .thenComparing(match -> match.recipe().name()));
        return matches;
    }

    public synchronized List<String> recipeEquipment(String recipeName) {
        List<String> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT equipment FROM recipe_equipment WHERE recipe_name=? ORDER BY equipment")) {
            statement.setString(1, clean(recipeName));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(rows.getString(1));
            }
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read recipe equipment", e);
        }
    }

    public synchronized boolean isRecipeVerified(String recipeName) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT verified FROM recipe_sources WHERE recipe_name=?")) {
            statement.setString(1, clean(recipeName));
            try (ResultSet row = statement.executeQuery()) {
                return !row.next() || row.getInt(1) == 1;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read recipe verification state", e);
        }
    }

    private void seedRecipes() throws SQLException {
        List<Recipe> defaults = List.of(
                new Recipe(0, "Vegetable Fried Rice", "Asian", 2, 25,
                        List.of(new Ingredient("rice", 2, "cups"), new Ingredient("eggs", 2, "each"),
                                new Ingredient("mixed vegetables", 2, "cups"), new Ingredient("soy sauce", 3, "tbsp")),
                        List.of("Cook or reheat the rice.", "Scramble the eggs.", "Stir-fry vegetables, add rice and soy sauce.", "Fold in eggs and serve.")),
                new Recipe(0, "Tomato Pasta", "Italian", 2, 25,
                        List.of(new Ingredient("pasta", 8, "oz"), new Ingredient("tomato sauce", 2, "cups"),
                                new Ingredient("garlic", 2, "cloves"), new Ingredient("olive oil", 1, "tbsp")),
                        List.of("Boil pasta.", "Cook garlic in oil.", "Add sauce and simmer.", "Toss with pasta.")),
                new Recipe(0, "Chickpea Curry", "Indian", 3, 35,
                        List.of(new Ingredient("chickpeas", 2, "cans"), new Ingredient("onion", 1, "each"),
                                new Ingredient("tomatoes", 2, "cups"), new Ingredient("curry powder", 2, "tbsp"),
                                new Ingredient("rice", 2, "cups")),
                        List.of("Cook onion until soft.", "Add spices and tomatoes.", "Add chickpeas and simmer.", "Serve with rice.")),
                new Recipe(0, "Vegetable Omelet", "Breakfast", 1, 15,
                        List.of(new Ingredient("eggs", 3, "each"), new Ingredient("mixed vegetables", 1, "cup"),
                                new Ingredient("cheese", 0.5, "cup")),
                        List.of("Whisk eggs.", "Cook vegetables.", "Add eggs and cheese.", "Fold and serve.")),
                new Recipe(0, "Bean Tacos", "Mexican", 2, 20,
                        List.of(new Ingredient("beans", 1, "can"), new Ingredient("tortillas", 6, "each"),
                                new Ingredient("cheese", 1, "cup"), new Ingredient("salsa", 1, "cup")),
                        List.of("Warm beans.", "Warm tortillas.", "Fill with beans, cheese and salsa.")),
                new Recipe(0, "Chili Paneer", "Indo-Chinese", 3, 30,
                        List.of(new Ingredient("paneer", 220, "g"), new Ingredient("salt", 1, "tsp"),
                                new Ingredient("vegetable seasoning powder", 1.5, "tsp"),
                                new Ingredient("paprika", 0.25, "tsp"), new Ingredient("ginger garlic paste", 1, "tsp"),
                                new Ingredient("dark soy sauce", 0.5, "tsp"), new Ingredient("chili sauce", 2.5, "tbsp"),
                                new Ingredient("baking powder", 0.25, "tsp"), new Ingredient("all-purpose flour", 1, "tbsp"),
                                new Ingredient("corn flour", 1, "tbsp"), new Ingredient("garlic", 1, "tsp"),
                                new Ingredient("bell peppers", 0.75, "cup"), new Ingredient("vinegar", 1, "tsp"),
                                new Ingredient("spring onions", 2, "tbsp")),
                        List.of("Season paneer with the marinade ingredients and water.",
                                "Add flour and corn flour for a patchy coating; mix gently.",
                                "Shallow-fry paneer until golden and drain.",
                                "Sauté garlic and bell peppers, then add water and sauce seasonings.",
                                "Add spring onions and corn-flour slurry; reduce to a thick gravy.",
                                "Fold in paneer, heat for 30–40 seconds, and garnish with spring onions.")),
                new Recipe(0, "Chilli Chicken", "Indo-Chinese", 2, 30,
                        List.of(new Ingredient("boneless chicken", 200, "g"), new Ingredient("salt", 1, "tsp"),
                                new Ingredient("chicken seasoning powder", 1, "tsp"),
                                new Ingredient("ginger garlic paste", 1, "tsp"), new Ingredient("soy sauce", 1, "tsp"),
                                new Ingredient("vinegar", 1.5, "tsp"), new Ingredient("all-purpose flour", 1, "tbsp"),
                                new Ingredient("corn flour", 1, "tbsp"), new Ingredient("garlic", 1, "tsp"),
                                new Ingredient("bell peppers", 0.75, "cup"), new Ingredient("chili sauce", 1, "tbsp"),
                                new Ingredient("spring onions", 2, "tbsp")),
                        List.of("Season and coat chicken; rest for two minutes.",
                                "Shallow-fry, waiting 40–50 seconds before turning; cook 6–8 minutes.",
                                "Sauté garlic and bell peppers for one minute.",
                                "Add sauces, seasoning, vinegar and water; cook 2–3 minutes.",
                                "Add spring onion and slurry, then return chicken and heat 1–2 minutes.")),
                new Recipe(0, "Crispy Corn", "Indo-Chinese", 3, 25,
                        List.of(new Ingredient("frozen sweet corn", 200, "g"), new Ingredient("salt", 1.25, "tsp"),
                                new Ingredient("white pepper", 0.25, "tsp"), new Ingredient("chili sauce", 2, "tsp"),
                                new Ingredient("beaten egg", 1, "tbsp"), new Ingredient("all-purpose flour", 1, "tbsp"),
                                new Ingredient("corn flour", 3, "tbsp"), new Ingredient("garlic", 1, "tbsp"),
                                new Ingredient("soy sauce", 1, "tsp"), new Ingredient("coriander", 2, "tbsp")),
                        List.of("Thaw corn in water for 15 minutes, then squeeze it completely dry.",
                                "Season, add chili sauce and egg, then coat with flour and corn flour.",
                                "Fry at medium-high heat; do not move for the first 20 seconds.",
                                "Drain, rest for two minutes, then refry for exactly 30 seconds.",
                                "Stir-fry chopped aromatics, add seasonings and sauces, then toss with corn.",
                                "Garnish with coriander and serve immediately.")));
        String sql = "INSERT OR IGNORE INTO recipes(name,category,servings,minutes,ingredients_json,steps_json) VALUES(?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Recipe recipe : defaults) {
                statement.setString(1, recipe.name()); statement.setString(2, recipe.category());
                statement.setInt(3, recipe.servings()); statement.setInt(4, recipe.minutes());
                statement.setString(5, JSON.writeValueAsString(recipe.ingredients()));
                statement.setString(6, JSON.writeValueAsString(recipe.steps()));
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (Exception e) {
            throw new SQLException("Cannot seed recipes", e);
        }
        seedRecipeSource("Chili Paneer",
                "D:/cooking/Satvir Singh/INDIAN SNACKS & APPETIZERS- Restaurant style cooking course/09 - Indo Chinese or desi Chinese/001 Chili paneer_en.vtt",
                "Machine captions reviewed 2026-07-29. Several fractions and sauce names are uncertain; verify against the video.",
                false);
        seedRecipeSource("Chilli Chicken",
                "D:/cooking/Satvir Singh/INDIAN SNACKS & APPETIZERS- Restaurant style cooking course/09 - Indo Chinese or desi Chinese/002 Chilli chicken_en.vtt",
                "Machine captions reviewed 2026-07-29. Bell-pepper and sauce quantities are partially unclear; verify against the video.",
                false);
        seedRecipeSource("Crispy Corn",
                "D:/cooking/Satvir Singh/INDIAN SNACKS & APPETIZERS- Restaurant style cooking course/09 - Indo Chinese or desi Chinese/003 Crispy corn_en.vtt",
                "Reviewed local recipe imported from the supplied cooking-course captions.",
                true);
        seedEquipment("Chili Paneer", "mixing bowl", "knife", "cutting board",
                "shallow frying pan", "wok or sauté pan", "slotted spoon", "absorbent paper");
        seedEquipment("Chilli Chicken", "mixing bowl", "knife", "cutting board",
                "shallow frying pan", "wok or sauté pan", "tongs", "slotted spoon");
        seedEquipment("Crispy Corn", "mixing bowl", "strainer", "deep frying pan or wok",
                "slotted spoon", "absorbent paper", "knife", "cutting board");
    }

    private void seedRecipeSource(String recipe, String path, String notes, boolean verified) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO recipe_sources(recipe_name,source_path,extraction_notes,verified) VALUES(?,?,?,?)
                ON CONFLICT(recipe_name) DO NOTHING
                """)) {
            statement.setString(1, recipe);
            statement.setString(2, path);
            statement.setString(3, notes);
            statement.setInt(4, verified ? 1 : 0);
            statement.executeUpdate();
        }
    }

    private void seedEquipment(String recipe, String... equipment) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO recipe_equipment(recipe_name,equipment) VALUES(?,?)")) {
            for (String item : equipment) {
                statement.setString(1, recipe);
                statement.setString(2, item);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void executeDelete(String sql, long id) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot delete record", e);
        }
    }

    private static void bindItem(PreparedStatement statement, Item item, String updatedAt) throws SQLException {
        statement.setString(1, clean(item.name()));
        statement.setString(2, clean(item.category()));
        statement.setString(3, clean(item.location()));
        statement.setDouble(4, Math.max(0, item.quantity()));
        statement.setString(5, clean(item.unit()).isBlank() ? "each" : clean(item.unit()));
        statement.setDouble(6, Math.max(0, item.minQuantity()));
        statement.setString(7, clean(item.expiresOn()));
        statement.setString(8, clean(item.notes()));
        statement.setString(9, clean(item.imageFilename()));
        statement.setString(10, updatedAt);
    }

    private static Item readItem(ResultSet row) throws SQLException {
        return new Item(row.getLong("id"), row.getString("name"), row.getString("category"),
                row.getString("location"), row.getDouble("quantity"), standardUnit(row.getString("unit")),
                row.getDouble("min_quantity"), row.getString("expires_on"), row.getString("notes"),
                row.getString("image_filename"), row.getString("updated_at"));
    }

    private static Meal readMeal(ResultSet row) throws SQLException {
        try {
            List<Ingredient> ingredients = standardizeIngredients(JSON.readValue(
                    row.getString("ingredients_json"), new TypeReference<List<Ingredient>>() {}));
            return new Meal(row.getLong("id"), row.getString("meal_date"), row.getString("meal_type"),
                    row.getString("recipe_name"), row.getInt("servings"), ingredients,
                    row.getString("notes"), row.getInt("completed") == 1,
                    row.getString("completed_at"));
        } catch (Exception e) {
            throw new SQLException("Invalid meal ingredients", e);
        }
    }

    private static Recipe readRecipe(ResultSet row) throws SQLException {
        try {
            return new Recipe(row.getLong("id"), row.getString("name"), row.getString("category"),
                    row.getInt("servings"), row.getInt("minutes"),
                    standardizeIngredients(JSON.readValue(row.getString("ingredients_json"),
                            new TypeReference<List<Ingredient>>() {})),
                    JSON.readValue(row.getString("steps_json"), new TypeReference<>() {}));
        } catch (Exception e) {
            throw new SQLException("Invalid recipe", e);
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalize(String value) {
        return clean(value).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    static String canonicalIngredient(String value) {
        String name = normalize(value);
        return switch (name) {
            case "egg", "eggs", "beaten egg", "beaten eggs" -> "egg";
            case "cornstarch", "corn starch", "corn flour", "cornflour" -> "corn starch";
            case "bell pepper", "bell peppers", "capsicum", "capsicums" -> "bell pepper";
            case "spring onion", "spring onions", "green onion", "green onions", "scallion", "scallions"
                    -> "spring onion";
            case "coriander", "cilantro", "fresh coriander", "coriander leaves", "cilantro leaves"
                    -> "coriander";
            case "dark soy sauce", "light soy sauce", "soy sauce" -> "soy sauce";
            case "frozen sweet corn", "sweet corn", "corn kernels", "sweet corn kernels" -> "sweet corn";
            default -> name;
        };
    }

    public static List<String> supportedUnits() {
        return List.of("each", "g", "kg", "oz", "lb", "ml", "l", "tsp", "tbsp", "cup",
                "can", "jar", "bottle", "bag", "package");
    }

    public static List<UnitRule> unitRules() {
        return List.of(
                new UnitRule("Mass", "kg", "g", 1000, false, "Metric mass"),
                new UnitRule("Mass", "oz", "g", 28.3495, false, "International avoirdupois ounce"),
                new UnitRule("Mass", "lb", "g", 453.592, false, "International avoirdupois pound"),
                new UnitRule("Volume", "l", "ml", 1000, false, "Metric volume"),
                new UnitRule("Volume", "tsp", "ml", 4.92892, false, "US teaspoon"),
                new UnitRule("Volume", "tbsp", "ml", 14.7868, false, "US tablespoon"),
                new UnitRule("Volume", "cup", "ml", 236.588, false, "US customary cup"),
                new UnitRule("Ingredient-specific", "egg", "tbsp", 3, true,
                        "Approximation: one beaten large egg is about three tablespoons. "
                                + "Inventory deduction always rounds up to a whole opened egg"),
                new UnitRule("Ingredient density", "all-purpose flour", "g/ml", 0.53, true,
                        "Approximation for mass/volume recipe matching"),
                new UnitRule("Ingredient density", "corn starch", "g/ml", 0.54, true,
                        "Approximation for mass/volume recipe matching"),
                new UnitRule("Ingredient density", "salt", "g/ml", 1.217, true,
                        "About 6 g per US teaspoon"),
                new UnitRule("Ingredient density", "baking powder", "g/ml", 0.81, true,
                        "About 4 g per US teaspoon"),
                new UnitRule("Ingredient density", "ginger garlic paste", "g/ml", 1.0, true,
                        "Approximate paste density"),
                new UnitRule("Ingredient density", "chili sauce", "g/ml", 1.05, true,
                        "Approximate sauce density"),
                new UnitRule("Ingredient density", "paprika", "g/ml", 0.46, true,
                        "About 2.3 g per US teaspoon"),
                new UnitRule("Ingredient density", "vegetable seasoning powder", "g/ml", 0.8, true,
                        "About 4 g per US teaspoon; brand-specific density may vary"),
                new UnitRule("Whole ingredient", "garlic", "each/tsp", 1, true,
                        "One average garlic clove is approximated as one teaspoon minced"),
                new UnitRule("Whole ingredient", "bell pepper", "each/cup", 1, true,
                        "One average bell pepper is approximated as one cup chopped"),
                new UnitRule("Whole ingredient", "spring onion", "each/tbsp", 2, true,
                        "One average spring-onion stalk is approximated as two tablespoons chopped"),
                new UnitRule("Whole ingredient", "coriander/cilantro", "each/cup", 0.5, true,
                        "One bunch/count is approximated as one-half cup chopped"),
                new UnitRule("Whole ingredient", "onion", "each/cup", 1, true,
                        "One medium onion is approximated as one cup chopped"),
                new UnitRule("Whole ingredient", "tomato", "each/cup", 0.75, true,
                        "One medium tomato is approximated as three-quarters cup chopped"),
                new UnitRule("Ingredient density", "ground pepper", "g/ml", 0.46, true,
                        "Approximation for black, white, and chili pepper powders"),
                new UnitRule("Ingredient density", "sugar", "g/ml", 0.85, true,
                        "Approximation for granulated sugar"),
                new UnitRule("Ingredient density", "soy sauce", "g/ml", 1.07, true,
                        "Approximate sauce density"),
                new UnitRule("Ingredient density", "vinegar", "g/ml", 1.0, true,
                        "Approximate liquid density"),
                new UnitRule("Ingredient density", "cooking oil", "g/ml", 0.92, true,
                        "Approximate oil density"),
                new UnitRule("Count", "each/can/jar/bottle/bag/package", "same unit", 1, false,
                        "Counts convert only when their standardized units match"));
    }

    public static String suggestedUnit(String itemName, String category) {
        String text = normalize(itemName + " " + category);
        if (text.matches(".*\\b(egg|eggs)\\b.*")) return "each";
        if (text.matches(".*\\b(milk|juice|vinegar|sauce|oil|water|cream)\\b.*")) return "ml";
        if (text.matches(".*\\b(flour|rice|paneer|cheese|meat|chicken|corn|sugar|salt|powder|spice|"
                + "coriander|cilantro|parsley|herb)\\b.*"))
            return "g";
        if (text.matches(".*\\b(can|canned)\\b.*")) return "can";
        if (text.matches(".*\\b(bottle)\\b.*")) return "bottle";
        return "each";
    }

    public static String standardUnit(String unit) {
        String value = normalize(unit);
        return switch (value) {
            case "gram", "grams", "gm", "gms", "g" -> "g";
            case "kilogram", "kilograms", "kgs", "kg" -> "kg";
            case "ounce", "ounces", "oz" -> "oz";
            case "pound", "pounds", "lbs", "lb" -> "lb";
            case "milliliter", "milliliters", "millilitre", "millilitres", "ml" -> "ml";
            case "liter", "liters", "litre", "litres", "l" -> "l";
            case "teaspoon", "teaspoons", "tsp" -> "tsp";
            case "tablespoon", "tablespoons", "tbsp" -> "tbsp";
            case "cups", "cup" -> "cup";
            case "count", "piece", "pieces", "item", "items", "egg", "eggs", "each", "" -> "each";
            case "cans", "can" -> "can";
            case "jars", "jar" -> "jar";
            case "bottles", "bottle" -> "bottle";
            case "bags", "bag" -> "bag";
            case "pack", "packs", "packages", "package" -> "package";
            default -> value;
        };
    }

    private static List<Ingredient> standardizeIngredients(List<Ingredient> ingredients) {
        if (ingredients == null) return List.of();
        return ingredients.stream().filter(Objects::nonNull)
                .map(item -> new Ingredient(clean(item.name()), Math.max(0, item.quantity()),
                        standardUnit(item.unit()))).toList();
    }

    static double convertQuantity(String canonicalName, double quantity, String fromUnit, String toUnit) {
        return conversion(canonicalName, quantity, fromUnit, toUnit).quantity();
    }

    private static Conversion conversion(String canonicalName, double quantity,
                                         String fromUnit, String toUnit) {
        String from = standardUnit(fromUnit);
        String to = standardUnit(toUnit);
        if (from.equals(to)) return new Conversion(quantity, false, "Same standardized unit");
        Double fromMass = massInGrams(from);
        Double toMass = massInGrams(to);
        if (fromMass != null && toMass != null) {
            return new Conversion(quantity * fromMass / toMass, false,
                    from + " to " + to + " mass conversion");
        }
        Double fromVolume = volumeInMilliliters(from);
        Double toVolume = volumeInMilliliters(to);
        if (fromVolume != null && toVolume != null) {
            return new Conversion(quantity * fromVolume / toVolume, false,
                    from + " to " + to + " US volume conversion");
        }
        if (canonicalName.equals("egg") && from.equals("each") && to.equals("tbsp")) {
            return new Conversion(quantity * 3.0, true, "1 large egg ≈ 3 tbsp beaten egg");
        }
        if (canonicalName.equals("egg") && from.equals("tbsp") && to.equals("each")) {
            return new Conversion(quantity / 3.0, true, "3 tbsp beaten egg ≈ 1 large egg");
        }
        Double density = ingredientDensity(canonicalName);
        if (density != null && fromVolume != null && toMass != null) {
            return new Conversion(quantity * fromVolume * density / toMass, true,
                    "Approximate " + canonicalName + " density " + density + " g/ml");
        }
        if (density != null && fromMass != null && toVolume != null) {
            return new Conversion(quantity * fromMass / density / toVolume, true,
                    "Approximate " + canonicalName + " density " + density + " g/ml");
        }
        Double wholeItemMilliliters = wholeItemVolumeMl(canonicalName);
        if (wholeItemMilliliters != null) {
            if (from.equals("each") && toVolume != null) {
                return new Conversion(quantity * wholeItemMilliliters / toVolume, true,
                        "Approximate chopped volume per whole " + canonicalName);
            }
            if (to.equals("each") && fromVolume != null) {
                return new Conversion(quantity * fromVolume / wholeItemMilliliters, true,
                        "Approximate whole " + canonicalName + " from chopped volume");
            }
        }
        if (canonicalName.equals("garlic")) {
            if (from.equals("each") && to.equals("tsp"))
                return new Conversion(quantity, true, "1 garlic clove ≈ 1 tsp minced");
            if (from.equals("tsp") && to.equals("each"))
                return new Conversion(quantity, true, "1 tsp minced garlic ≈ 1 clove");
        }
        if (canonicalName.equals("bell pepper")) {
            if (from.equals("each") && to.equals("cup"))
                return new Conversion(quantity, true, "1 bell pepper ≈ 1 cup chopped");
            if (from.equals("cup") && to.equals("each"))
                return new Conversion(quantity, true, "1 cup chopped bell pepper ≈ 1 pepper");
        }
        if (canonicalName.equals("spring onion")) {
            if (from.equals("each") && to.equals("tbsp"))
                return new Conversion(quantity * 2.0, true,
                        "1 spring-onion stalk ≈ 2 tbsp chopped");
            if (from.equals("tbsp") && to.equals("each"))
                return new Conversion(quantity / 2.0, true,
                        "2 tbsp chopped spring onion ≈ 1 stalk");
        }
        return new Conversion(0, false, "No safe conversion rule from " + from + " to " + to);
    }

    private static Double ingredientDensity(String canonicalName) {
        return switch (canonicalName) {
            case "all purpose flour" -> 0.53;
            case "corn starch" -> 0.54;
            case "salt" -> 1.217;
            case "baking powder" -> 0.81;
            case "ginger garlic paste" -> 1.0;
            case "chili sauce" -> 1.05;
            case "paprika" -> 0.46;
            case "vegetable seasoning powder" -> 0.8;
            case "white pepper", "black pepper", "chili powder", "red chili powder" -> 0.46;
            case "sugar" -> 0.85;
            case "soy sauce" -> 1.07;
            case "vinegar" -> 1.0;
            case "olive oil", "vegetable oil", "cooking oil" -> 0.92;
            default -> null;
        };
    }

    private static Double wholeItemVolumeMl(String canonicalName) {
        return switch (canonicalName) {
            case "garlic" -> 4.92892;
            case "bell pepper", "onion" -> 236.588;
            case "spring onion" -> 29.5736;
            case "coriander" -> 118.294;
            case "tomato" -> 177.441;
            default -> null;
        };
    }

    private static Double massInGrams(String unit) {
        return switch (unit) {
            case "g" -> 1.0;
            case "kg" -> 1000.0;
            case "oz" -> 28.3495;
            case "lb" -> 453.592;
            default -> null;
        };
    }

    private static Double volumeInMilliliters(String unit) {
        return switch (unit) {
            case "ml" -> 1.0;
            case "l" -> 1000.0;
            case "tsp" -> 4.92892;
            case "tbsp" -> 14.7868;
            case "cup" -> 236.588;
            default -> null;
        };
    }

    private record Conversion(double quantity, boolean approximate, String rule) {}

    private static boolean isTablespoon(String unit) {
        return unit.equals("tbsp") || unit.equals("tablespoon") || unit.equals("tablespoons");
    }

    private static boolean isTeaspoon(String unit) {
        return unit.equals("tsp") || unit.equals("teaspoon") || unit.equals("teaspoons");
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot close home database", e);
        }
    }
}
