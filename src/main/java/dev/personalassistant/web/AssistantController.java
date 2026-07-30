package dev.personalassistant.web;

import dev.personalassistant.data.Database;
import dev.personalassistant.home.HomeDatabase;
import dev.personalassistant.model.ChatMessage;
import dev.personalassistant.provider.LocalModelProvider;
import dev.personalassistant.provider.LocalVisionProvider;
import dev.personalassistant.skill.Skill;
import dev.personalassistant.skill.SkillLoader;
import dev.personalassistant.voice.PiperSpeechSynthesizer;
import dev.personalassistant.voice.WhisperTranscriber;
import dev.personalassistant.voice.SpeechTextSanitizer;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api/assistant")
public class AssistantController {
    private static final Logger LOG = LoggerFactory.getLogger(AssistantController.class);
    private static final String DEFAULT_CONVERSATION = "web-home";
    private final Database conversations;
    private final HomeDatabase home;
    private final LocalModelProvider provider;
    private final LocalVisionProvider vision;
    private final WhisperTranscriber transcriber;
    private final PiperSpeechSynthesizer speaker;
    private final SpeechTextSanitizer speechSanitizer;
    private final Path appHome;
    private final Map<String, PendingMeal> pendingMeals = new ConcurrentHashMap<>();
    private final Map<String, PendingShopping> pendingShopping = new ConcurrentHashMap<>();

    AssistantController(Database conversations, HomeDatabase home, LocalModelProvider provider,
                        LocalVisionProvider vision, WhisperTranscriber transcriber,
                        PiperSpeechSynthesizer speaker, SpeechTextSanitizer speechSanitizer) {
        this.conversations = conversations;
        this.home = home;
        this.provider = provider;
        this.vision = vision;
        this.transcriber = transcriber;
        this.speaker = speaker;
        this.speechSanitizer = speechSanitizer;
        this.appHome = Path.of(System.getProperty("personalassistant.home", ".")).toAbsolutePath();
    }

    @GetMapping("/status")
    Map<String, Object> status() {
        List<Skill> installedSkills = SkillLoader.load(appHome.resolve("skills"));
        return Map.of(
                "available", provider.available(),
                "provider", provider.name(),
                "models", provider.models(),
                "selectedModel", provider.selectedModel(),
                "visionAvailable", vision.available(),
                "visionModels", vision.models(),
                "selectedVisionModel", vision.selectedModel(),
                "voiceUploadAvailable", transcriber.available(),
                "speechOutputAvailable", speaker.available(),
                "skills", installedSkills.stream()
                        .map(skill -> new SkillView(skill.id(), skill.name(), skill.description())).toList()
        );
    }

    @PostMapping(value = "/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String, String> transcribe(@RequestPart("audio") MultipartFile audio) throws Exception {
        if (!transcriber.available()) throw new IllegalStateException("Local Whisper is not installed.");
        if (audio.isEmpty() || audio.getSize() > 15_000_000) {
            throw new IllegalArgumentException("Recording must be between 1 byte and 15 MB.");
        }
        byte[] bytes = audio.getBytes();
        if (bytes.length < 12 || bytes[0] != 'R' || bytes[1] != 'I'
                || bytes[2] != 'F' || bytes[3] != 'F') {
            throw new IllegalArgumentException("Recording must be a PCM WAV file.");
        }
        Path upload = appHome.resolve("data/voice-web-" + UUID.randomUUID() + ".wav");
        try {
            Files.write(upload, bytes);
            String text = transcriber.transcribe(upload).get(2, TimeUnit.MINUTES);
            return Map.of("text", text);
        } finally {
            Files.deleteIfExists(upload);
        }
    }

    @PostMapping(value = "/speech", produces = "audio/wav")
    ResponseEntity<byte[]> speech(@RequestBody SpeechRequest request) throws Exception {
        if (!speaker.available()) throw new IllegalStateException("Local Piper voice is not installed.");
        String text = speechSanitizer.sanitize(request.text());
        if (text.isBlank()) throw new IllegalArgumentException(
                "The response contains no speakable text after voice cleanup.");
        Path wave = appHome.resolve("data/speech-web-" + UUID.randomUUID() + ".wav");
        try {
            speaker.synthesize(text, wave).get(1, TimeUnit.MINUTES);
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/wav"))
                    .body(Files.readAllBytes(wave));
        } finally {
            Files.deleteIfExists(wave);
        }
    }

    @PostMapping("/vision-model")
    Map<String, Object> selectVisionModel(@RequestBody ModelRequest request) {
        vision.selectModel(request.filename());
        return status();
    }

    @PostMapping("/analyze-image")
    VisionScanView analyzeImage(@RequestBody VisionRequest request) throws Exception {
        LocalVisionProvider.VisionScan scan;
        try {
            scan = vision.analyze(request.photoData()).get(7, TimeUnit.MINUTES);
        } catch (Exception error) {
            throw new IllegalStateException("Image recognition failed: " + rootMessage(error), error);
        }
        List<HomeDatabase.Item> existing = home.items();
        return new VisionScanView(scan.items().stream().map(item -> {
            HomeDatabase.Item match = existing.stream()
                    .filter(saved -> sameItem(normalizeItemName(saved.name()), normalizeItemName(item.name())))
                    .findFirst().orElse(null);
            return new VisionItemView(item, match == null ? 0 : match.id());
        }).toList());
    }

    @PostMapping("/model")
    Map<String, Object> selectModel(@RequestBody ModelRequest request) {
        provider.selectModel(request.filename());
        return status();
    }

    @GetMapping("/messages")
    List<MessageView> messages(@RequestParam(defaultValue = DEFAULT_CONVERSATION) String conversationId) {
        String id = validConversationId(conversationId);
        return conversations.messages(id).stream()
                .filter(message -> message.role() != ChatMessage.Role.SYSTEM)
                .map(message -> new MessageView(message.role().name().toLowerCase(), message.content()))
                .toList();
    }

    @GetMapping("/conversations")
    List<Database.ConversationSummary> conversations() {
        return conversations.conversations();
    }

    @PostMapping("/conversations")
    Map<String, String> newConversation() {
        return Map.of("id", "web-" + UUID.randomUUID());
    }

    @DeleteMapping("/conversations/{conversationId}")
    Map<String, Boolean> clearConversation(@PathVariable String conversationId) {
        conversations.clearConversation(validConversationId(conversationId));
        return Map.of("ok", true);
    }

    @PostMapping("/chat")
    synchronized MessageView chat(@RequestBody ChatRequest request) throws Exception {
        String text = request.message() == null ? "" : request.message().trim();
        if (text.isBlank()) throw new IllegalArgumentException("Message is required");
        if (!provider.available()) {
            throw new IllegalStateException("The bundled local model or llama.cpp runtime is unavailable.");
        }
        String conversationId = validConversationId(request.conversationId());
        conversations.addMessage(conversationId, ChatMessage.Role.USER, text);
        List<ChatMessage> history = new ArrayList<>();
        history.add(new ChatMessage(0, conversationId, ChatMessage.Role.SYSTEM,
                householdContext(), java.time.Instant.now()));
        List<ChatMessage> stored = conversations.messages(conversationId);
        history.addAll(stored.subList(Math.max(0, stored.size() - 16), stored.size()));
        List<Skill> skills = selectedSkills(text, request.selectedSkillIds(), request.autoSkills());
        String reply = provider.reply(history, skills).get(4, TimeUnit.MINUTES);
        conversations.addMessage(conversationId, ChatMessage.Role.ASSISTANT, reply);
        return new MessageView("assistant", reply);
    }

    @PostMapping(value = "/chat-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter streamChat(@RequestBody ChatRequest request) {
        String text = request.message() == null ? "" : request.message().trim();
        if (text.isBlank()) throw new IllegalArgumentException("Message is required");
        if (!provider.available()) throw new IllegalStateException("The bundled local model is unavailable.");
        String conversationId = validConversationId(request.conversationId());
        boolean generalOnly = text.startsWith("General knowledge only:");
        String normalizedRequest = normalizeItemName(text);
        HomeDatabase.Recipe namedRecipe = generalOnly ? null : home.recipes().stream()
                .filter(recipe -> normalizedRequest.contains(normalizeItemName(recipe.name())))
                .findFirst().orElse(null);
        HomeDatabase.Recipe unverifiedRecipe = namedRecipe != null
                && !home.isRecipeVerified(namedRecipe.name()) ? namedRecipe : null;
        boolean recipeRequest = namedRecipe != null || normalizedRequest.contains("recipe")
                || normalizedRequest.contains("cook") || normalizedRequest.contains("dinner")
                || normalizedRequest.contains("lunch") || normalizedRequest.contains("breakfast")
                || normalizedRequest.contains("ingredient");
        synchronized (this) {
            conversations.addMessage(conversationId, ChatMessage.Role.USER, text);
        }
        List<ChatMessage> history = new ArrayList<>();
        history.add(new ChatMessage(0, conversationId, ChatMessage.Role.SYSTEM,
                generalOnly ? "GENERAL KNOWLEDGE MODE: No local tools or household data are available. "
                        + "Answer from model knowledge only and clearly say details may be inaccurate."
                        : householdContext(), java.time.Instant.now()));
        List<LocalModelProvider.ToolCall> toolCalls = generalOnly || unverifiedRecipe != null
                ? List.of() : validateToolPlan(text, planToolsSafely(text));
        if (toolCalls.stream().anyMatch(call -> call.name().equals("inventory.list"))) {
            history.add(new ChatMessage(0, conversationId, ChatMessage.Role.SYSTEM,
                    inventoryToolContext(text), java.time.Instant.now()));
        }
        List<HomeDatabase.RecipeMatch> recipeMatches = executeRecipeTools(toolCalls).stream()
                .filter(match -> home.isRecipeVerified(match.recipe().name())).toList();
        if (unverifiedRecipe != null) {
            history.add(new ChatMessage(0, conversationId, ChatMessage.Role.SYSTEM,
                    "The named recipe '" + unverifiedRecipe.name() + "' exists only as an unverified draft. "
                            + "Say the verified local recipe is unavailable. Do not give ingredients or steps, "
                            + "do not propose a meal, and do not claim any action was taken.",
                    java.time.Instant.now()));
        }
        if (!recipeMatches.isEmpty()) {
            history.add(new ChatMessage(0, conversationId, ChatMessage.Role.SYSTEM,
                    recipeToolContext(recipeMatches), java.time.Instant.now()));
        }
        List<ChatMessage> stored = conversations.messages(conversationId);
        if (recipeRequest) {
            // Recipe and availability answers must be based on this request's live SQLite
            // calculation, never on an assistant claim retained from an earlier turn.
            history.add(stored.getLast());
        } else {
            history.addAll(stored.subList(Math.max(0, stored.size() - 16), stored.size()));
        }
        List<Skill> skills = selectedSkills(text, request.selectedSkillIds(), request.autoSkills());
        SseEmitter emitter = new SseEmitter(TimeUnit.MINUTES.toMillis(5));
        HomeDatabase.RecipeMatch directRecipeMatch = namedRecipe == null ? null : recipeMatches.stream()
                .filter(match -> recipeNameMatches(match.recipe().name(), namedRecipe.name()))
                .findFirst().orElse(null);
        CompletableFuture<String> replyFuture;
        if (directRecipeMatch != null) {
            String templatedReply = formattedRecipeResponse(directRecipeMatch);
            replyFuture = streamTemplatedReply(emitter, templatedReply);
        } else {
            replyFuture = provider.streamReply(history, skills, token -> {
                try {
                    emitter.send(SseEmitter.event().name("token").data(Map.of("text", token)));
                } catch (Exception e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            });
        }
        replyFuture.whenComplete((reply, error) -> {
            try {
                if (error != null) {
                    emitter.send(SseEmitter.event().name("error")
                            .data(Map.of("message", rootMessage(error))));
                } else {
                    synchronized (this) {
                        conversations.addMessage(conversationId, ChatMessage.Role.ASSISTANT, reply);
                    }
                    List<String> proposed = explicitMealWriteRequest(text) ? toolCalls.stream()
                            .filter(call -> call.name().equals("meal_plan.propose"))
                            .map(LocalModelProvider.ToolCall::query).toList() : List.of();
                    if (!recipeMatches.isEmpty() && !proposed.isEmpty()) {
                        HomeDatabase.RecipeMatch selectedMatch = recipeMatches.stream()
                                .filter(match -> proposed.stream()
                                        .anyMatch(name -> recipeNameMatches(match.recipe().name(), name)))
                                .findFirst().orElse(recipeMatches.getFirst());
                        HomeDatabase.Recipe recipe = selectedMatch.recipe();
                        boolean hasMissingIngredients = !selectedMatch.missing().isEmpty();
                        String requestedMealType = requestedMealType(text);
                        LocalDate requestedMealDate = requestedMealDate(text);
                        String actionId = UUID.randomUUID().toString();
                        pendingMeals.put(actionId, new PendingMeal(
                                recipe, requestedMealType, requestedMealDate));
                        emitter.send(SseEmitter.event().name("action").data(Map.of(
                                "id", actionId,
                                "label", hasMissingIngredients
                                        ? "Plan " + recipe.name() + " and add missing items"
                                        : "Add " + recipe.name() + " to "
                                                + requestedMealType.toLowerCase(Locale.ROOT) + " plan",
                                "detail", !hasMissingIngredients
                                        ? "All ingredients are available."
                                        : "Missing: " + selectedMatch.missing().stream()
                                        .map(HomeDatabase.Ingredient::name).toList(),
                                "alternatives", hasMissingIngredients
                                        ? List.of(
                                                Map.of("label", "Suggest substitutions",
                                                        "prompt", "Suggest safe substitutions for the missing ingredients in "
                                                                + recipe.name() + " using my inventory."),
                                                Map.of("label", "Choose another recipe",
                                                        "prompt", "Find another recipe I can make with fewer missing ingredients."))
                                        : List.of(Map.of("label", "Choose another recipe",
                                                "prompt", "Find another recipe I can make with my current inventory."))
                        )));
                    } else if (namedRecipe != null && home.isRecipeVerified(namedRecipe.name())
                            && !recipeMatches.isEmpty()) {
                        HomeDatabase.RecipeMatch selectedMatch = recipeMatches.stream()
                                .filter(match -> recipeNameMatches(match.recipe().name(), namedRecipe.name()))
                                .findFirst().orElse(recipeMatches.getFirst());
                        if (!selectedMatch.missing().isEmpty()) {
                            String actionId = UUID.randomUUID().toString();
                            pendingShopping.put(actionId, new PendingShopping(
                                    selectedMatch.recipe().name(), selectedMatch.missing()));
                            emitter.send(SseEmitter.event().name("action").data(Map.of(
                                    "id", actionId,
                                    "label", "Add missing ingredients to shopping list",
                                    "detail", "Missing for " + selectedMatch.recipe().name() + ": "
                                            + selectedMatch.missing().stream()
                                            .map(HomeDatabase.Ingredient::name).toList(),
                                    "alternatives", List.of(Map.of(
                                            "label", "Plan the recipe for dinner",
                                            "prompt", "Plan " + selectedMatch.recipe().name()
                                                    + " for dinner and add the missing ingredients."))
                            )));
                        }
                    }
                    if (unverifiedRecipe != null) {
                        emitter.send(SseEmitter.event().name("action").data(Map.of(
                                "label", "Verified local recipe unavailable",
                                "detail", unverifiedRecipe.name()
                                        + " is an unverified draft and cannot trigger cooking actions.",
                                "alternatives", List.of(Map.of(
                                        "label", "Ask model from general knowledge",
                                        "prompt", "General knowledge only: Give me a possible "
                                                + unverifiedRecipe.name()
                                                + " recipe. Do not use tools or change any local data."
                                ))
                        )));
                    }
                    emitter.send(SseEmitter.event().name("done").data(Map.of("text", reply)));
                }
                emitter.complete();
            } catch (Exception sendFailure) {
                emitter.completeWithError(sendFailure);
            }
        });
        return emitter;
    }

    private CompletableFuture<String> streamTemplatedReply(SseEmitter emitter, String reply) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                int index = 0;
                while (index < reply.length()) {
                    int end = Math.min(reply.length(), index + 48);
                    if (end < reply.length()) {
                        int breakAt = reply.lastIndexOf(' ', end);
                        if (breakAt > index + 20) end = breakAt + 1;
                    }
                    emitter.send(SseEmitter.event().name("token")
                            .data(Map.of("text", reply.substring(index, end))));
                    index = end;
                    Thread.sleep(35);
                }
                return reply;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CompletionException(interrupted);
            } catch (Exception error) {
                throw new java.util.concurrent.CompletionException(error);
            }
        });
    }

    @PostMapping("/actions/{actionId}/confirm")
    Map<String, Object> confirmAction(@PathVariable String actionId) {
        PendingMeal pendingMeal = pendingMeals.remove(actionId);
        if (pendingMeal == null) {
            PendingShopping shopping = pendingShopping.remove(actionId);
            if (shopping == null) {
                throw new IllegalArgumentException("This proposed action expired or was already used.");
            }
            shopping.ingredients().forEach(ingredient ->
                    home.addShoppingItem(ingredient.name(), 1, "each"));
            return Map.of("ok", true, "message",
                    "Missing ingredients for " + shopping.recipeName() + " were added to the shopping list.");
        }
        HomeDatabase.Recipe recipe = pendingMeal.recipe();
        boolean hadMissingIngredients = home.recipeMatches().stream()
                .filter(match -> match.recipe().name().equalsIgnoreCase(recipe.name()))
                .findFirst().map(match -> !match.missing().isEmpty()).orElse(false);
        HomeDatabase.Meal meal = home.saveMeal(new HomeDatabase.Meal(0, pendingMeal.date().toString(),
                pendingMeal.mealType(), recipe.name(), recipe.servings(), recipe.ingredients(),
                "Added after confirmation in AI assistant."));
        return Map.of("ok", true, "meal", meal.recipeName(),
                "message", hadMissingIngredients
                        ? recipe.name() + " was added to " + pendingMeal.date() + " "
                                + pendingMeal.mealType().toLowerCase(Locale.ROOT)
                                + ". Missing ingredients are now on the shopping list."
                        : recipe.name() + " was added to " + pendingMeal.date() + " "
                                + pendingMeal.mealType().toLowerCase(Locale.ROOT)
                                + ". All ingredients are already available.");
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? "Local generation failed." : current.getMessage();
    }

    private String householdContext() {
        LocalDate monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        String sunday = monday.plusDays(6).toString();
        StringBuilder context = new StringBuilder("""
                Built-in skill: HOME_INVENTORY_READ.
                The following is a live, authoritative snapshot from the local SQLite household database.
                For questions about what the user owns, quantities, locations, expiration, cooking,
                planned meals, or shopping shortages, answer from this snapshot rather than guessing.
                If an item is absent, say it is not currently recorded; do not claim the user lacks it.
                Never claim an item was added, removed, purchased, or changed. This chat is read-only.
                Suggest an action and ask the user to perform or confirm it in the relevant screen.

                INVENTORY:
                """);
        home.items().forEach(item -> context.append("- ").append(item.name()).append(": ")
                .append(item.quantity()).append(' ').append(item.unit())
                .append(" | category=").append(item.category())
                .append(" | location=").append(item.location())
                .append(item.expiresOn().isBlank() ? "" : " | expires=" + item.expiresOn())
                .append('\n'));
        context.append("\nMEALS THIS WEEK:\n");
        home.meals(monday.toString(), sunday).forEach(meal -> context.append("- ")
                .append(meal.mealDate()).append(' ').append(meal.mealType())
                .append(": ").append(meal.recipeName()).append('\n'));
        context.append("\nCURRENT SHOPPING SHORTAGES:\n");
        home.shoppingList(monday.toString(), sunday).forEach(item -> context.append("- ")
                .append(item.name()).append(": buy ").append(item.toBuy())
                .append(' ').append(item.unit()).append('\n'));
        return context.toString();
    }

    private List<HomeDatabase.RecipeMatch> executeRecipeTools(List<LocalModelProvider.ToolCall> calls) {
        boolean rank = calls.stream().anyMatch(call ->
                call.name().equals("recipes.findByAvailableIngredients"));
        List<String> requested = calls.stream().filter(call -> call.name().equals("recipes.get"))
                .map(LocalModelProvider.ToolCall::query).filter(query -> !query.isBlank()).toList();
        List<String> proposed = calls.stream().filter(call -> call.name().equals("meal_plan.propose"))
                .map(LocalModelProvider.ToolCall::query).filter(query -> !query.isBlank()).toList();
        List<String> effectiveRequested = requested.isEmpty() ? proposed : requested;
        if (!rank && effectiveRequested.isEmpty()) return List.of();
        List<HomeDatabase.RecipeMatch> matches = home.recipeMatches();
        if (!effectiveRequested.isEmpty()) {
            List<HomeDatabase.RecipeMatch> named = matches.stream().filter(match ->
                    effectiveRequested.stream().anyMatch(query ->
                            recipeNameMatches(match.recipe().name(), query))).toList();
            if (!named.isEmpty()) return named.stream().limit(4).toList();
        }
        return rank ? matches.stream().limit(4).toList() : List.of();
    }

    private List<LocalModelProvider.ToolCall> planToolsSafely(String text) {
        try {
            List<LocalModelProvider.ToolCall> calls =
                    provider.planTools(text).get(3, TimeUnit.MINUTES);
            LOG.info("Assistant model selected tools: {}", calls);
            return calls;
        } catch (Exception error) {
            LOG.warn("Model tool planning failed; continuing without optional tools: {}", rootMessage(error));
            return List.of();
        }
    }

    private List<LocalModelProvider.ToolCall> validateToolPlan(
            String message, List<LocalModelProvider.ToolCall> modelCalls) {
        String normalized = normalizeItemName(message);
        HomeDatabase.Recipe namedRecipe = home.recipes().stream()
                .filter(recipe -> normalized.contains(normalizeItemName(recipe.name())))
                .findFirst().orElse(null);
        if (namedRecipe == null) return modelCalls;
        List<LocalModelProvider.ToolCall> validated = new ArrayList<>(modelCalls.stream()
                .filter(call -> !call.name().equals("recipes.get")
                        && !call.name().equals("meal_plan.propose")).toList());
        validated.add(new LocalModelProvider.ToolCall("recipes.get", namedRecipe.name()));
        if (explicitMealWriteRequest(message)) {
            validated.add(new LocalModelProvider.ToolCall("meal_plan.propose", namedRecipe.name()));
        }
        LOG.info("Validated model tool plan against explicit recipe '{}': {}",
                namedRecipe.name(), validated);
        return List.copyOf(validated);
    }

    private String inventoryToolContext(String userMessage) {
        String question = normalizeItemName(userMessage);
        List<HomeDatabase.Item> items = home.items();
        Map<String, HomeDatabase.Item> byName = new java.util.LinkedHashMap<>();
        items.forEach(item -> byName.put(normalizeItemName(item.name()), item));
        Set<String> knownNames = new java.util.LinkedHashSet<>(byName.keySet());
        home.recipes().stream().flatMap(recipe -> recipe.ingredients().stream())
                .map(ingredient -> normalizeItemName(ingredient.name())).forEach(knownNames::add);
        List<String> requested = knownNames.stream()
                .filter(name -> name.length() >= 3 && question.contains(name)).toList();
        StringBuilder result = new StringBuilder("""
                EXECUTED TOOL RESULT: inventory.list
                This result is authoritative SQLite data. Answer from it exactly. An item marked
                NOT RECORDED must not be described as available. A recorded positive quantity must
                not be described as missing.
                """);
        if (requested.isEmpty()) {
            items.forEach(item -> result.append("\nRECORDED: ").append(item.name()).append(" = ")
                    .append(item.quantity()).append(' ').append(item.unit())
                    .append(" at ").append(item.location()));
        } else {
            for (String name : requested) {
                HomeDatabase.Item item = byName.get(name);
                if (item == null) result.append("\nNOT RECORDED: ").append(name);
                else result.append("\nRECORDED: ").append(item.name()).append(" = ")
                        .append(item.quantity()).append(' ').append(item.unit())
                        .append(" at ").append(item.location());
            }
        }
        LOG.info("inventory.list returned {} requested result(s) from {} inventory record(s)",
                requested.isEmpty() ? items.size() : requested.size(), items.size());
        return result.toString();
    }

    private static boolean recipeNameMatches(String recipe, String query) {
        String left = normalizeItemName(recipe);
        String right = normalizeItemName(query);
        return left.equals(right) || left.contains(right) || right.contains(left);
    }

    private static boolean explicitMealWriteRequest(String message) {
        String text = normalizeItemName(message);
        boolean directPlanningVerb = text.contains("plan ") || text.contains("schedule ")
                || text.contains("add to meal") || text.contains("add it to meal")
                || text.contains("put on meal") || text.contains("add missing")
                || text.contains("shopping list") || text.contains("meal plan");
        boolean commitment = text.matches(".*\\b(want|wants|wanted|would like|d like|"
                + "let s have|lets have|we ll have|we will have|plan|schedule|prepare|"
                + "make|cook|serve|add|put|set|choose|pick|have)\\b.*");
        boolean mealSlot = text.matches(".*\\b(breakfast|brunch|lunch|dinner|supper|snack|meal)\\b.*");
        boolean dateOrOccasion = text.matches(".*\\b(today|tonight|tomorrow|weekday|weekend|"
                + "monday|tuesday|wednesday|thursday|friday|saturday|sunday|"
                + "this morning|this afternoon|this evening|next week)\\b.*");
        boolean naturalMealPhrase = commitment && (mealSlot || dateOrOccasion);
        return directPlanningVerb || naturalMealPhrase;
    }

    private static String requestedMealType(String message) {
        String text = normalizeItemName(message);
        if (text.contains("breakfast")) return "Breakfast";
        if (text.contains("brunch")) return "Brunch";
        if (text.contains("lunch")) return "Lunch";
        if (text.contains("snack")) return "Snack";
        return "Dinner";
    }

    private static LocalDate requestedMealDate(String message) {
        String text = normalizeItemName(message);
        LocalDate today = LocalDate.now();
        if (text.contains("tomorrow")) return today.plusDays(1);
        for (DayOfWeek day : DayOfWeek.values()) {
            if (text.contains(day.name().toLowerCase(Locale.ROOT))) {
                return today.with(TemporalAdjusters.nextOrSame(day));
            }
        }
        return today;
    }

    private String recipeToolContext(List<HomeDatabase.RecipeMatch> matches) {
        StringBuilder out = new StringBuilder("""
                EXECUTED READ-ONLY TOOL: recipes.findByAvailableIngredients
                This is a fresh calculation against the current SQLite inventory for this request.
                It supersedes every inventory or availability statement in previous chat messages.
                Never reuse prior-turn quantities and never claim anything was changed.

                REQUIRED RESPONSE TEMPLATE:
                Recipe: <name>
                Time and servings: <minutes and servings>
                Available ingredients:
                - <ingredient names marked AVAILABLE below>
                Missing or insufficient ingredients:
                - <exact shortages marked MISSING below, or "None">
                Safe substitutions:
                - Only well-established culinary substitutions. Say "None recommended" when uncertain.
                Steps:
                1. <stored recipe steps>
                Next action:
                - Tell the user to use the confirmation action shown below the response.

                Do not substitute coriander for paprika. Do not substitute baking soda one-for-one
                for baking powder. Do not describe an insufficient ingredient as fully available.
                """);
        for (HomeDatabase.RecipeMatch match : matches) {
            Set<String> missingNames = match.missing().stream()
                    .map(ingredient -> normalizeItemName(ingredient.name()))
                    .collect(java.util.stream.Collectors.toSet());
            out.append("\n- ").append(match.recipe().name()).append(" (")
                    .append(match.recipe().minutes()).append(" min, ")
                    .append(Math.round(match.coverage() * 100)).append("% available)")
                    .append("\n  Ingredient status: ").append(match.recipe().ingredients().stream()
                            .map(ingredient -> ingredient.name() + " = "
                                    + (missingNames.contains(normalizeItemName(ingredient.name()))
                                    ? "MISSING OR INSUFFICIENT" : "AVAILABLE")).toList())
                    .append("\n  Exact shortages: ").append(match.missing().isEmpty() ? "nothing" :
                            match.missing().stream().map(i -> i.name() + " " + i.quantity() + " " + i.unit()).toList())
                    .append("\n  Equipment: ").append(home.recipeEquipment(match.recipe().name()))
                    .append("\n  Steps: ").append(match.recipe().steps());
        }
        return out.toString();
    }

    private String formattedRecipeResponse(HomeDatabase.RecipeMatch match) {
        Set<String> missingNames = match.missing().stream()
                .map(ingredient -> normalizeItemName(ingredient.name()))
                .collect(java.util.stream.Collectors.toSet());
        List<HomeDatabase.Ingredient> available = match.recipe().ingredients().stream()
                .filter(ingredient -> !missingNames.contains(normalizeItemName(ingredient.name()))).toList();
        StringBuilder out = new StringBuilder()
                .append("Recipe: ").append(match.recipe().name())
                .append("\n\nTime and servings: ").append(match.recipe().minutes())
                .append(" minutes, ").append(match.recipe().servings()).append(" servings")
                .append("\n\nAvailable ingredients:");
        if (available.isEmpty()) out.append("\n- None recorded in sufficient quantity");
        else available.forEach(ingredient -> out.append("\n- ").append(ingredient.name())
                .append(": ").append(ingredient.quantity()).append(' ').append(ingredient.unit()));
        out.append("\n\nMissing or insufficient ingredients:");
        if (match.missing().isEmpty()) out.append("\n- None");
        else match.missing().forEach(ingredient -> out.append("\n- ").append(ingredient.name())
                .append(": ").append(ingredient.quantity()).append(' ').append(ingredient.unit())
                .append(" short"));
        out.append("\n\nSafe substitutions:\n- None recommended without explicit review.")
                .append("\n\nEquipment:");
        home.recipeEquipment(match.recipe().name()).forEach(item -> out.append("\n- ").append(item));
        out.append("\n\nSteps:");
        for (int index = 0; index < match.recipe().steps().size(); index++) {
            out.append("\n").append(index + 1).append(". ").append(match.recipe().steps().get(index));
        }
        out.append("\n\nNext action:\n- Use the confirmation option below when you want Hearth to update your plan or shopping list.");
        return out.toString();
    }

    private record PendingShopping(String recipeName, List<HomeDatabase.Ingredient> ingredients) {}
    private record PendingMeal(HomeDatabase.Recipe recipe, String mealType, LocalDate date) {}

    private List<Skill> selectedSkills(String message, List<String> requestedIds, Boolean auto) {
        List<Skill> installed = SkillLoader.load(appHome.resolve("skills"));
        if (Boolean.TRUE.equals(auto)) {
            String normalized = message.toLowerCase(Locale.ROOT);
            return installed.stream().filter(skill -> {
                String searchable = (skill.id() + " " + skill.name() + " " + skill.description())
                        .toLowerCase(Locale.ROOT);
                return java.util.Arrays.stream(searchable.split("[^a-z0-9]+"))
                        .filter(word -> word.length() >= 4).anyMatch(normalized::contains);
            }).toList();
        }
        Set<String> ids = requestedIds == null ? Set.of() : Set.copyOf(requestedIds);
        return installed.stream().filter(skill -> ids.contains(skill.id())).toList();
    }

    private static String validConversationId(String value) {
        String id = value == null || value.isBlank() ? DEFAULT_CONVERSATION : value.trim();
        if (!id.matches("[A-Za-z0-9-]{1,80}")) throw new IllegalArgumentException("Invalid conversation id");
        return id;
    }

    public record ChatRequest(String message, String conversationId,
                              List<String> selectedSkillIds, Boolean autoSkills) {}
    public record ModelRequest(String filename) {}
    public record VisionRequest(String photoData) {}
    public record VisionScanView(List<VisionItemView> items) {}
    public record VisionItemView(String name, String category, double quantity, String unit,
                                 String expiresOn, String brand, String visibleText, String notes,
                                 double confidence, long existingItemId) {
        VisionItemView(LocalVisionProvider.VisionItem item, long existingItemId) {
            this(item.name(), item.category(), item.quantity(), item.unit(), item.expiresOn(),
                    item.brand(), item.visibleText(), item.notes(), item.confidence(), existingItemId);
        }
    }
    public record SpeechRequest(String text) {}
    public record SkillView(String id, String name, String description) {}
    public record MessageView(String role, String content) {}

    private static String normalizeItemName(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static boolean sameItem(String left, String right) {
        return !left.isBlank() && !right.isBlank()
                && (left.equals(right) || (left.length() >= 5 && right.contains(left))
                || (right.length() >= 5 && left.contains(right)));
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
    Map<String, String> unavailable(IllegalStateException error) {
        return Map.of("error", error.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
    Map<String, String> invalid(IllegalArgumentException error) {
        return Map.of("error", error.getMessage());
    }
}
