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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.nio.file.Files;
import java.util.UUID;

@RestController
@RequestMapping("/api/assistant")
public class AssistantController {
    private static final String CONVERSATION = "web-home";
    private final Database conversations;
    private final HomeDatabase home;
    private final LocalModelProvider provider;
    private final LocalVisionProvider vision;
    private final WhisperTranscriber transcriber;
    private final PiperSpeechSynthesizer speaker;
    private final Path appHome;

    AssistantController(Database conversations, HomeDatabase home, LocalModelProvider provider,
                        LocalVisionProvider vision, WhisperTranscriber transcriber,
                        PiperSpeechSynthesizer speaker) {
        this.conversations = conversations;
        this.home = home;
        this.provider = provider;
        this.vision = vision;
        this.transcriber = transcriber;
        this.speaker = speaker;
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
                "voiceUploadAvailable", transcriber.available(),
                "speechOutputAvailable", speaker.available()
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
        String text = request.text() == null ? "" : request.text().trim();
        if (text.isBlank() || text.length() > 4000) throw new IllegalArgumentException("Speech text is invalid.");
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
    synchronized MessageView chat(@RequestBody ChatRequest request) throws Exception {
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
    public record SpeechRequest(String text) {}
    public record MessageView(String role, String content) {}

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
