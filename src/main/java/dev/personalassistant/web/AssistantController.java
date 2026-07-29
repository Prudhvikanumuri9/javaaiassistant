package dev.personalassistant.web;

import dev.personalassistant.data.Database;
import dev.personalassistant.home.HomeDatabase;
import dev.personalassistant.model.ChatMessage;
import dev.personalassistant.provider.LocalModelProvider;
import dev.personalassistant.provider.LocalVisionProvider;
import dev.personalassistant.skill.Skill;
import dev.personalassistant.skill.SkillLoader;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/assistant")
public class AssistantController {
    private static final String CONVERSATION = "web-home";
    private final Database conversations;
    private final HomeDatabase home;
    private final LocalModelProvider provider;
    private final LocalVisionProvider vision;
    private final Path appHome;

    AssistantController(Database conversations, HomeDatabase home, LocalModelProvider provider,
                        LocalVisionProvider vision) {
        this.conversations = conversations;
        this.home = home;
        this.provider = provider;
        this.vision = vision;
        this.appHome = Path.of(System.getProperty("personalassistant.home", ".")).toAbsolutePath();
    }

    @GetMapping("/status")
    Map<String, Object> status() {
        return Map.of(
                "available", provider.available(),
                "provider", provider.name(),
                "models", provider.models(),
                "selectedModel", provider.selectedModel(),
                "visionAvailable", vision.available(),
                "visionModels", vision.models(),
                "selectedVisionModel", vision.selectedModel(),
                "voiceUploadAvailable", false
        );
    }

    @PostMapping("/vision-model")
    Map<String, Object> selectVisionModel(@RequestBody ModelRequest request) {
        vision.selectModel(request.filename());
        return status();
    }

    @PostMapping("/model")
    Map<String, Object> selectModel(@RequestBody ModelRequest request) {
        provider.selectModel(request.filename());
        return status();
    }

    @GetMapping("/messages")
    List<MessageView> messages() {
        return conversations.messages(CONVERSATION).stream()
                .filter(message -> message.role() != ChatMessage.Role.SYSTEM)
                .map(message -> new MessageView(message.role().name().toLowerCase(), message.content()))
                .toList();
    }

    @PostMapping("/chat")
    MessageView chat(@RequestBody ChatRequest request) throws Exception {
        String text = request.message() == null ? "" : request.message().trim();
        if (text.isBlank()) throw new IllegalArgumentException("Message is required");
        if (!provider.available()) {
            throw new IllegalStateException("The bundled local model or llama.cpp runtime is unavailable.");
        }
        conversations.addMessage(CONVERSATION, ChatMessage.Role.USER, text);
        List<ChatMessage> history = new ArrayList<>();
        history.add(new ChatMessage(0, CONVERSATION, ChatMessage.Role.SYSTEM,
                householdContext(), java.time.Instant.now()));
        List<ChatMessage> stored = conversations.messages(CONVERSATION);
        history.addAll(stored.subList(Math.max(0, stored.size() - 16), stored.size()));
        List<Skill> skills = SkillLoader.load(appHome.resolve("skills"));
        String reply = provider.reply(history, skills).get(4, TimeUnit.MINUTES);
        conversations.addMessage(CONVERSATION, ChatMessage.Role.ASSISTANT, reply);
        return new MessageView("assistant", reply);
    }

    private String householdContext() {
        LocalDate monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        String sunday = monday.plusDays(6).toString();
        StringBuilder context = new StringBuilder("""
                Household context supplied by the local application follows.
                Use it to answer questions about available items, cooking, meals, and shopping.
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

    public record ChatRequest(String message) {}
    public record ModelRequest(String filename) {}
    public record MessageView(String role, String content) {}

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
    Map<String, String> unavailable(IllegalStateException error) {
        return Map.of("error", error.getMessage());
    }
}
