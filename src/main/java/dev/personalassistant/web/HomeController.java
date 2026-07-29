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

    @PostMapping("/api/meals")
    Meal addMeal(@RequestBody Meal meal) {
        return database.saveMeal(meal);
    }

    @DeleteMapping("/api/meals")
    Map<String, Boolean> deleteMeal(@RequestParam long id) {
        database.deleteMeal(id);
        return Map.of("ok", true);
    }

    @GetMapping("/api/shopping")
    List<HomeDatabase.ShoppingEntry> shopping(@RequestParam String from, @RequestParam String to) {
        return database.shoppingList(from, to);
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

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("error", error.getMessage()));
    }
}
