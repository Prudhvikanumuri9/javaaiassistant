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
                       int servings, List<Ingredient> ingredients, String notes) {}

    public record ShoppingEntry(String name, double needed, double available,
                                double toBuy, String unit, List<String> meals) {}

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
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_inventory_name ON inventory_items(name)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_meals_date ON meal_plans(meal_date)");
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

    public synchronized Item saveItem(Item item) {
        String now = Instant.now().toString();
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
            String ingredients = JSON.writeValueAsString(meal.ingredients() == null ? List.of() : meal.ingredients());
            String sql = """
                    INSERT INTO meal_plans(meal_date, meal_type, recipe_name, servings, ingredients_json, notes)
                    VALUES(?,?,?,?,?,?)""";
            try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, clean(meal.mealDate()));
                statement.setString(2, clean(meal.mealType()));
                statement.setString(3, clean(meal.recipeName()));
                statement.setInt(4, Math.max(1, meal.servings()));
                statement.setString(5, ingredients);
                statement.setString(6, clean(meal.notes()));
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    long id = keys.next() ? keys.getLong(1) : 0;
                    return new Meal(id, meal.mealDate(), meal.mealType(), meal.recipeName(),
                            Math.max(1, meal.servings()), meal.ingredients(), meal.notes());
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot save meal", e);
        }
    }

    public synchronized void deleteMeal(long id) {
        executeDelete("DELETE FROM meal_plans WHERE id=?", id);
    }

    public synchronized List<ShoppingEntry> shoppingList(String from, String to) {
        Map<String, Item> inventory = new HashMap<>();
        for (Item item : items()) inventory.put(normalize(item.name()), item);
        record Need(String display, String unit, double quantity, Set<String> meals) {}
        Map<String, Need> needs = new LinkedHashMap<>();
        for (Meal meal : meals(from, to)) {
            for (Ingredient ingredient : meal.ingredients()) {
                String key = normalize(ingredient.name()) + "|" + normalize(ingredient.unit());
                Need current = needs.get(key);
                Set<String> usedBy = current == null ? new LinkedHashSet<>() : current.meals();
                usedBy.add(meal.recipeName());
                needs.put(key, new Need(ingredient.name(), ingredient.unit(),
                        (current == null ? 0 : current.quantity()) + ingredient.quantity(), usedBy));
            }
        }
        List<ShoppingEntry> result = new ArrayList<>();
        for (Need need : needs.values()) {
            Item stocked = inventory.get(normalize(need.display()));
            double available = stocked != null && normalize(stocked.unit()).equals(normalize(need.unit()))
                    ? stocked.quantity() : 0;
            double toBuy = Math.max(0, need.quantity() - available);
            if (toBuy > 0) {
                result.add(new ShoppingEntry(need.display(), need.quantity(), available, toBuy,
                        need.unit(), List.copyOf(need.meals())));
            }
        }
        result.sort(Comparator.comparing(ShoppingEntry::name, String.CASE_INSENSITIVE_ORDER));
        return result;
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
                row.getString("location"), row.getDouble("quantity"), row.getString("unit"),
                row.getDouble("min_quantity"), row.getString("expires_on"), row.getString("notes"),
                row.getString("image_filename"), row.getString("updated_at"));
    }

    private static Meal readMeal(ResultSet row) throws SQLException {
        try {
            List<Ingredient> ingredients = JSON.readValue(row.getString("ingredients_json"),
                    new TypeReference<>() {});
            return new Meal(row.getLong("id"), row.getString("meal_date"), row.getString("meal_type"),
                    row.getString("recipe_name"), row.getInt("servings"), ingredients, row.getString("notes"));
        } catch (Exception e) {
            throw new SQLException("Invalid meal ingredients", e);
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalize(String value) {
        return clean(value).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
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
