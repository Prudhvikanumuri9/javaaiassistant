package dev.personalassistant.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.personalassistant.home.HomeDatabase;
import dev.personalassistant.home.HomeDatabase.Item;
import dev.personalassistant.home.HomeDatabase.Meal;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping
public class HomeController {
    private final HomeDatabase database;
    private final ObjectMapper json;
    private final Path photos;

    HomeController(HomeDatabase database, ObjectMapper json) throws Exception {
        this.database = database;
        this.json = json;
        this.photos = Path.of(System.getProperty("personalassistant.home", "."))
                .toAbsolutePath().resolve("data/item-photos");
        Files.createDirectories(photos);
    }

    @GetMapping("/api/items")
    List<Item> items() {
        return database.items();
    }

    @PostMapping("/api/items")
    Item addItem(@RequestBody Map<String, Object> request) throws Exception {
        request.put("id", 0);
        return saveItem(request);
    }

    @PutMapping("/api/items")
    Item updateItem(@RequestBody Map<String, Object> request) throws Exception {
        return saveItem(request);
    }

    private Item saveItem(Map<String, Object> request) throws Exception {
        String photoData = Objects.toString(request.remove("photoData"), "");
        Item item = json.convertValue(request, Item.class);
        String filename = photoData.isBlank() ? item.imageFilename() : savePhoto(photoData);
        return database.saveItem(new Item(item.id(), item.name(), item.category(), item.location(),
                item.quantity(), item.unit(), item.minQuantity(), item.expiresOn(), item.notes(),
                filename, item.updatedAt()));
    }

    @DeleteMapping("/api/items")
    Map<String, Boolean> deleteItem(@RequestParam long id) {
        database.deleteItem(id);
        return Map.of("ok", true);
    }

    @GetMapping("/api/meals")
    List<Meal> meals(@RequestParam(required = false) String from,
                     @RequestParam(required = false) String to) {
        LocalDate today = LocalDate.now();
        return database.meals(from == null ? today.toString() : from,
                to == null ? today.plusDays(6).toString() : to);
    }

    @GetMapping("/api/recipes")
    List<HomeDatabase.Recipe> verifiedRecipes() {
        return database.recipes().stream()
                .filter(recipe -> database.isRecipeVerified(recipe.name())).toList();
    }

    @PostMapping("/api/meals")
    Meal addMeal(@RequestBody Meal meal) {
        return database.saveMeal(meal);
    }

    @DeleteMapping("/api/meals")
    Map<String, Boolean> deleteMeal(@RequestParam long id) {
        database.deleteMeal(id);
        return Map.of("ok", true);
    }

    @GetMapping("/api/meals/{id}/completion-preview")
    HomeDatabase.MealCompletion completionPreview(@PathVariable long id) {
        return database.previewMealCompletion(id);
    }

    @PostMapping("/api/meals/{id}/complete")
    HomeDatabase.MealCompletion completeMeal(@PathVariable long id) {
        return database.completeMeal(id);
    }

    @GetMapping("/api/units")
    Map<String, Object> units(@RequestParam(required = false, defaultValue = "") String name,
                              @RequestParam(required = false, defaultValue = "") String category) {
        return Map.of("units", HomeDatabase.supportedUnits(),
                "suggested", HomeDatabase.suggestedUnit(name, category),
                "rules", HomeDatabase.unitRules());
    }

    @GetMapping("/api/shopping")
    List<HomeDatabase.ShoppingEntry> shopping(@RequestParam String from, @RequestParam String to) {
        return database.shoppingList(from, to);
    }

    @PostMapping("/api/shopping")
    HomeDatabase.ManualShoppingItem addShopping(@RequestBody ShoppingRequest request) {
        return database.addShoppingItem(request.name(), request.quantity(), request.unit());
    }

    @PutMapping("/api/shopping")
    HomeDatabase.ManualShoppingItem editShopping(@RequestBody ShoppingEditRequest request) {
        return database.editShoppingItem(request.id(), request.originalName(), request.name(),
                request.quantity(), request.unit());
    }

    @PostMapping("/api/shopping/purchase")
    Item purchaseShopping(@RequestBody ShoppingPurchaseRequest request) {
        return database.purchaseShoppingItem(request.id(), request.name(),
                request.quantity(), request.unit(), request.mode());
    }

    @GetMapping("/api/shopping/inventory-match")
    Map<String, Object> shoppingInventoryMatch(@RequestParam String name) {
        Item item = database.matchingInventoryItem(name);
        return item == null ? Map.of("found", false) : Map.of(
                "found", true, "id", item.id(), "name", item.name(),
                "quantity", item.quantity(), "unit", item.unit());
    }

    @DeleteMapping("/api/shopping")
    Map<String, Boolean> removeShopping(@RequestParam(defaultValue = "0") long id,
                                        @RequestParam(required = false) String name) {
        database.removeShoppingItem(id, name);
        return Map.of("ok", true);
    }

    @DeleteMapping("/api/shopping/all")
    Map<String, Boolean> clearShopping(@RequestParam String from, @RequestParam String to) {
        database.clearShoppingList(from, to);
        return Map.of("ok", true);
    }

    @GetMapping(value = "/photos/{filename}", produces = MediaType.IMAGE_JPEG_VALUE)
    ResponseEntity<FileSystemResource> photo(@PathVariable String filename) {
        Path file = photos.resolve(Path.of(filename).getFileName()).normalize();
        if (!file.startsWith(photos) || !Files.isRegularFile(file)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(new FileSystemResource(file));
    }

    private String savePhoto(String dataUrl) throws Exception {
        int comma = dataUrl.indexOf(',');
        if (comma < 0) throw new IllegalArgumentException("Invalid camera photo");
        byte[] bytes = Base64.getDecoder().decode(dataUrl.substring(comma + 1));
        if (bytes.length > 8_000_000) throw new IllegalArgumentException("Photo exceeds 8 MB");
        String filename = UUID.randomUUID() + ".jpg";
        Files.write(photos.resolve(filename), bytes);
        return filename;
    }

    public record ShoppingRequest(String name, double quantity, String unit) {}
    public record ShoppingEditRequest(long id, String originalName, String name,
                                      double quantity, String unit) {}
    public record ShoppingPurchaseRequest(long id, String name, double quantity,
                                          String unit, String mode) {}

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("error", error.getMessage()));
    }
}
