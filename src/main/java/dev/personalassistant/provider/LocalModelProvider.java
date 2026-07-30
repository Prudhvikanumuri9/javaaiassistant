package dev.personalassistant.provider;

import dev.personalassistant.model.ChatMessage;
import dev.personalassistant.skill.Skill;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.ArrayList;
import java.util.stream.Stream;

public final class LocalModelProvider implements AssistantProvider {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int PORT = 18080;
    private static final URI COMPLETIONS = URI.create("http://127.0.0.1:" + PORT + "/v1/chat/completions");
    private static final URI HEALTH = URI.create("http://127.0.0.1:" + PORT + "/health");

    public record Platform(String os, String architecture, String nativeDirectory) {}
    private final Path home;
    private final Path executable;
    private volatile Path model;
    private final Path selectionFile;
    private final Path inferenceFile;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private Process server;

    public LocalModelProvider(Path home) {
        this.home = home;
        Platform platform = detectPlatform();
        String executableName = platform.os().equals("windows") ? "llama-server.exe" : "llama-server";
        this.executable = home.resolve("native").resolve(platform.nativeDirectory()).resolve(executableName);
        this.selectionFile = home.resolve("config/model-selection.properties");
        this.inferenceFile = home.resolve("config/inference.properties");
        this.model = loadSelectedModel();
    }

    public static Platform detectPlatform() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String archName = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String os = osName.contains("win") ? "windows" : osName.contains("mac") ? "macos" : "linux";
        String arch = archName.contains("aarch64") || archName.contains("arm64") ? "arm64" : "x64";
        return new Platform(os, arch, os + "-" + arch);
    }

    @Override
    public String name() {
        return available()
                ? "Local Qwen via llama.cpp (" + detectPlatform().nativeDirectory() + ")"
                : "Local model unavailable (" + detectPlatform().nativeDirectory() + ")";
    }

    @Override
    public boolean available() {
        return Files.isRegularFile(executable) && model != null && Files.isRegularFile(model);
    }

    @Override
    public CompletableFuture<String> reply(List<ChatMessage> history, List<Skill> skills) {
        return CompletableFuture.supplyAsync(() -> generate(history, skills));
    }

    public CompletableFuture<String> streamReply(List<ChatMessage> history, List<Skill> skills,
                                                 Consumer<String> tokenConsumer) {
        return CompletableFuture.supplyAsync(() -> generateStream(history, skills, tokenConsumer));
    }

    public record ToolCall(String name, String query) {}

    /** Uses llama.cpp's OpenAI-compatible native function-calling endpoint. */
    public CompletableFuture<List<ToolCall>> planTools(String userMessage) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ensureServer();
                ObjectNode body = nativeToolRequest(userMessage);
                body.put("model", model.getFileName().toString());
                HttpRequest request = HttpRequest.newBuilder(COMPLETIONS).timeout(Duration.ofMinutes(3))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 != 2) throw new IOException("Tool planner HTTP " + response.statusCode());
                return parseNativeToolCalls(response.body());
            } catch (Exception error) {
                throw new CompletionException(error);
            }
        });
    }

    static ObjectNode nativeToolRequest(String userMessage) {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", "local-model");
        body.put("temperature", 0);
        body.put("max_tokens", 320);
        body.put("tool_choice", "auto");
        body.put("parallel_tool_calls", false);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", """
                /no_think
                Select tools only when they are needed. Read requests may use read tools.
                meal_plan_propose is allowed only when the user explicitly asks to plan,
                schedule, prepare, or have a meal. It proposes a change and never executes it.
                """);
        messages.addObject().put("role", "user").put("content", userMessage);
        ArrayNode tools = body.putArray("tools");
        addTool(tools, "inventory_list",
                "Read current household inventory and quantities.", false);
        addTool(tools, "recipes_find_by_available_ingredients",
                "Rank verified recipes against current inventory.", false);
        addTool(tools, "recipes_get",
                "Read a named verified recipe, equipment, ingredients, and steps.", true);
        addTool(tools, "meal_plan_propose",
                "Propose adding a named recipe to the meal plan; requires user confirmation.", true);
        return body;
    }

    private static void addTool(ArrayNode tools, String name, String description, boolean queryRequired) {
        ObjectNode function = tools.addObject().put("type", "function").putObject("function");
        function.put("name", name);
        function.put("description", description);
        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");
        parameters.put("additionalProperties", false);
        ObjectNode properties = parameters.putObject("properties");
        if (queryRequired) {
            properties.putObject("query").put("type", "string")
                    .put("description", "Exact recipe name");
            parameters.putArray("required").add("query");
        }
    }

    static List<ToolCall> parseNativeToolCalls(String responseBody) throws IOException {
        JsonNode message = JSON.readTree(responseBody).path("choices").path(0).path("message");
        JsonNode calls = message.path("tool_calls");
        List<ToolCall> result = new ArrayList<>();
        if (!calls.isArray()) return List.of();
        for (JsonNode call : calls) {
            JsonNode function = call.has("function") ? call.path("function") : call;
            String name = internalToolName(function.path("name").asText(""));
            if (name == null) continue;
            JsonNode arguments = function.path("arguments");
            if (arguments.isTextual() && !arguments.asText().isBlank()) {
                try {
                    arguments = JSON.readTree(arguments.asText());
                } catch (IOException invalidArguments) {
                    continue;
                }
            }
            result.add(new ToolCall(name, arguments.path("query").asText("").trim()));
        }
        return List.copyOf(result);
    }

    private static String internalToolName(String name) {
        return switch (name) {
            case "inventory_list", "inventory.list" -> "inventory.list";
            case "recipes_find_by_available_ingredients", "recipes.findByAvailableIngredients" ->
                    "recipes.findByAvailableIngredients";
            case "recipes_get", "recipes.get" -> "recipes.get";
            case "meal_plan_propose", "meal_plan.propose" -> "meal_plan.propose";
            default -> null;
        };
    }

    private String generateStream(List<ChatMessage> history, List<Skill> skills,
                                  Consumer<String> tokenConsumer) {
        if (!available()) throw new IllegalStateException("Local runtime or GGUF model is missing beside the application.");
        try {
            ensureServer();
            ObjectNode body = requestBody(history, skills);
            body.put("stream", true);
            HttpRequest request = HttpRequest.newBuilder(COMPLETIONS).timeout(Duration.ofMinutes(4))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
            HttpResponse<Stream<String>> response = http.send(request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() / 100 != 2) throw new IOException("llama.cpp returned HTTP " + response.statusCode());
            StringBuilder complete = new StringBuilder();
            try (Stream<String> lines = response.body()) {
                lines.filter(line -> line.startsWith("data: ")).map(line -> line.substring(6))
                        .filter(data -> !"[DONE]".equals(data)).forEach(data -> {
                            try {
                                String token = JSON.readTree(data).path("choices").path(0)
                                        .path("delta").path("content").asText("");
                                if (!token.isEmpty()) {
                                    complete.append(token);
                                    tokenConsumer.accept(token);
                                }
                            } catch (IOException e) {
                                throw new CompletionException(e);
                            }
                        });
            }
            if (complete.toString().isBlank()) throw new IOException("The local model returned an empty response.");
            return complete.toString().trim();
        } catch (IOException e) {
            throw new CompletionException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException(e);
        }
    }

    public List<String> models() {
        Path directory = home.resolve("models/language");
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gguf"))
                    .map(path -> path.getFileName().toString())
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot inspect language models", e);
        }
    }

    public String selectedModel() {
        return model == null ? "" : model.getFileName().toString();
    }

    public synchronized void selectModel(String filename) {
        Path candidate = home.resolve("models/language").resolve(Path.of(filename).getFileName()).normalize();
        if (!candidate.startsWith(home.resolve("models/language").normalize())
                || !Files.isRegularFile(candidate) || !filename.toLowerCase(Locale.ROOT).endsWith(".gguf")) {
            throw new IllegalArgumentException("Language model not found: " + filename);
        }
        if (server != null && server.isAlive()) server.destroy();
        server = null;
        model = candidate;
        try {
            Files.createDirectories(selectionFile.getParent());
            Properties properties = new Properties();
            if (Files.isRegularFile(selectionFile)) {
                try (var input = Files.newInputStream(selectionFile)) {
                    properties.load(input);
                }
            }
            properties.setProperty("reasoningModel", filename);
            try (var output = Files.newOutputStream(selectionFile)) {
                properties.store(output, "Personal Assistant model selection");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot save model selection", e);
        }
    }

    private Path loadSelectedModel() {
        if (Files.isRegularFile(selectionFile)) {
            try (var input = Files.newInputStream(selectionFile)) {
                Properties properties = new Properties();
                properties.load(input);
                String selected = properties.getProperty("reasoningModel", "");
                Path candidate = home.resolve("models/language").resolve(Path.of(selected).getFileName());
                if (!selected.isBlank() && Files.isRegularFile(candidate)) return candidate;
            } catch (IOException ignored) {
                // Fall back to the first installed model.
            }
        }
        return findModel(home.resolve("models/language"));
    }

    private String generate(List<ChatMessage> history, List<Skill> skills) {
        if (!available()) {
            throw new IllegalStateException("Local runtime or GGUF model is missing beside the application.");
        }
        try {
            ensureServer();
            ObjectNode requestBody = requestBody(history, skills);
            HttpRequest request = HttpRequest.newBuilder(COMPLETIONS)
                    .timeout(Duration.ofMinutes(3))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(requestBody)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IOException("llama.cpp returned HTTP " + response.statusCode() + ": " + response.body());
            }
            JsonNode root = JSON.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText();
            if (content.isBlank()) throw new IOException("The local model returned an empty response.");
            return content.trim();
        } catch (IOException e) {
            throw new CompletionException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException(e);
        }
    }

    private ObjectNode requestBody(List<ChatMessage> history, List<Skill> skills) {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", model.getFileName().toString());
        body.put("temperature", 0.7);
        body.put("max_tokens", 1024);
        ArrayNode messages = body.putArray("messages");
        ObjectNode system = messages.addObject();
        system.put("role", "system");
        StringBuilder systemContent = new StringBuilder(systemPrompt(skills));
        history.stream().filter(message -> message.role() == ChatMessage.Role.SYSTEM)
                .forEach(message -> systemContent.append("\n\n").append(message.content()));
        system.put("content", systemContent.toString());
        for (ChatMessage message : history) {
            if (message.role() == ChatMessage.Role.SYSTEM) continue;
            ObjectNode item = messages.addObject();
            item.put("role", message.role() == ChatMessage.Role.USER ? "user" : "assistant");
            item.put("content", message.content());
        }
        return body;
    }

    private synchronized void ensureServer() throws IOException, InterruptedException {
        if (server != null && server.isAlive() && healthy()) return;
        Files.createDirectories(home.resolve("logs"));
        Path log = home.resolve("logs/llama-server.log");
        InferenceSettings settings = loadInferenceSettings();
        server = new ProcessBuilder(
                executable.toString(), "--model", model.toString(),
                "--host", "127.0.0.1", "--port", Integer.toString(PORT),
                "--ctx-size", Integer.toString(settings.contextSize()),
                "--parallel", "1", "--gpu-layers", Integer.toString(settings.gpuLayers()),
                "--flash-attn", settings.flashAttention(), "--jinja", "--no-webui")
                .directory(executable.getParent().toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .start();
        for (int attempt = 0; attempt < 180; attempt++) {
            if (!server.isAlive()) {
                throw new IOException("llama.cpp stopped during startup. See " + log);
            }
            if (healthy()) return;
            Thread.sleep(500);
        }
        server.destroy();
        throw new IOException("Local model startup timed out. See " + log);
    }

    private InferenceSettings loadInferenceSettings() {
        Properties values = new Properties();
        if (Files.isRegularFile(inferenceFile)) {
            try (var input = Files.newInputStream(inferenceFile)) {
                values.load(input);
            } catch (IOException error) {
                throw new IllegalStateException("Cannot read inference settings " + inferenceFile, error);
            }
        }
        Path cuda = executable.getParent().resolve(
                detectPlatform().os().equals("windows") ? "ggml-cuda.dll" : "libggml-cuda.so");
        int automaticGpuLayers = Files.isRegularFile(cuda) ? 99 : 0;
        return new InferenceSettings(
                boundedInt(values, "contextSize", 8192, 2048, 32768),
                boundedInt(values, "gpuLayers", automaticGpuLayers, 0, 999),
                switch (values.getProperty("flashAttention", "auto").trim().toLowerCase(Locale.ROOT)) {
                    case "on", "off" -> values.getProperty("flashAttention").trim().toLowerCase(Locale.ROOT);
                    default -> "auto";
                });
    }

    private static int boundedInt(Properties values, String key, int fallback, int minimum, int maximum) {
        try {
            return Math.max(minimum, Math.min(maximum,
                    Integer.parseInt(values.getProperty(key, Integer.toString(fallback)).trim())));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    record InferenceSettings(int contextSize, int gpuLayers, String flashAttention) {}

    private boolean healthy() {
        try {
            HttpRequest request = HttpRequest.newBuilder(HEALTH)
                    .timeout(Duration.ofSeconds(1)).GET().build();
            return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String systemPrompt(List<Skill> skills) {
        StringBuilder prompt = new StringBuilder(
                "You are a private, helpful personal assistant running entirely on the user's computer. "
                        + "Be concise and never claim to have used a tool unless a tool result is provided.");
        for (Skill skill : skills) {
            if (skill.prompt() != null && !skill.prompt().isBlank()) {
                prompt.append("\nSkill ").append(skill.name()).append(": ").append(skill.prompt());
            }
        }
        return prompt.toString();
    }

    private static Path findModel(Path directory) {
        if (!Files.isDirectory(directory)) return null;
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gguf"))
                    .sorted()
                    .findFirst().orElse(null);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot inspect local models in " + directory, e);
        }
    }

    @Override
    public synchronized void close() {
        if (server != null && server.isAlive()) {
            server.destroy();
        }
    }
}
