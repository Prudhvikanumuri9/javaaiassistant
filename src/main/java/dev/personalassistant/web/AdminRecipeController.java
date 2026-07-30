package dev.personalassistant.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.personalassistant.home.HomeDatabase;
import dev.personalassistant.home.HomeDatabase.RecipeDraft;
import dev.personalassistant.model.ChatMessage;
import dev.personalassistant.provider.LocalModelProvider;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/admin/recipes")
final class AdminRecipeController {
    private final HomeDatabase database;
    private final LocalModelProvider provider;
    private final ObjectMapper json;

    AdminRecipeController(HomeDatabase database, LocalModelProvider provider, ObjectMapper json) {
        this.database = database;
        this.provider = provider;
        this.json = json;
    }

    @GetMapping
    List<RecipeDraft> recipes() {
        return database.recipeDrafts();
    }

    @PutMapping
    RecipeDraft save(@RequestBody RecipeDraft recipe) {
        return database.saveRecipeDraft(recipe);
    }

    @PostMapping("/ai-draft")
    RecipeDraft aiDraft(@RequestBody AiDraftRequest request) throws Exception {
        if (!provider.available()) throw new IllegalStateException("The local reasoning model is unavailable");
        String existing = request.recipe() == null ? "{}" : json.writeValueAsString(request.recipe());
        List<ChatMessage> prompt = List.of(
                new ChatMessage(0, "recipe-admin", ChatMessage.Role.SYSTEM, """
                        You help an administrator create a structured cooking recipe draft.
                        Return ONLY one valid JSON object with these exact fields:
                        name, category, servings, minutes, ingredients, steps, equipment,
                        sourcePath, extractionNotes, verified.
                        ingredients is an array of {"name":string,"quantity":number,"unit":string}.
                        steps and equipment are string arrays. verified must always be false.
                        Preserve reliable existing values. Never claim the draft was saved or verified.
                        If facts are uncertain, explain them in extractionNotes.
                        """, Instant.now()),
                new ChatMessage(0, "recipe-admin", ChatMessage.Role.USER,
                        "Existing draft:\n" + existing + "\n\nAdministrator request:\n"
                                + (request.instructions() == null ? "" : request.instructions()), Instant.now()));
        String response = provider.reply(prompt, List.of()).get(4, TimeUnit.MINUTES);
        String cleaned = response.trim().replaceFirst("^```(?:json)?\\s*", "")
                .replaceFirst("\\s*```$", "");
        RecipeDraft result = json.readValue(cleaned, RecipeDraft.class);
        return new RecipeDraft(result.id(), result.name(), result.category(), result.servings(),
                result.minutes(), result.ingredients(), result.steps(), result.equipment(),
                result.sourcePath(), result.extractionNotes(), false);
    }

    record AiDraftRequest(RecipeDraft recipe, String instructions) {}

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
    Map<String, String> badRequest(RuntimeException error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        return Map.of("error", cause.getMessage() == null ? error.getMessage() : cause.getMessage());
    }
}
