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
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

public final class LocalModelProvider implements AssistantProvider {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int PORT = 18080;
    private static final URI COMPLETIONS = URI.create("http://127.0.0.1:" + PORT + "/v1/chat/completions");
    private static final URI HEALTH = URI.create("http://127.0.0.1:" + PORT + "/health");

    public record Platform(String os, String architecture, String nativeDirectory) {}
    private final Path home;
    private final Path executable;
    private final Path model;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private Process server;

    public LocalModelProvider(Path home) {
        this.home = home;
        Platform platform = detectPlatform();
        String executableName = platform.os().equals("windows") ? "llama-server.exe" : "llama-server";
        this.executable = home.resolve("native").resolve(platform.nativeDirectory()).resolve(executableName);
        this.model = findModel(home.resolve("models/language"));
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

    private String generate(List<ChatMessage> history, List<Skill> skills) {
        if (!available()) {
            throw new IllegalStateException("Local runtime or GGUF model is missing beside the application.");
        }
        try {
            ensureServer();
            ObjectNode requestBody = JSON.createObjectNode();
            requestBody.put("model", model.getFileName().toString());
            requestBody.put("temperature", 0.7);
            requestBody.put("max_tokens", 512);
            ArrayNode messages = requestBody.putArray("messages");
            ObjectNode system = messages.addObject();
            system.put("role", "system");
            system.put("content", systemPrompt(skills));
            for (ChatMessage message : history) {
                if (message.role() == ChatMessage.Role.SYSTEM) continue;
                ObjectNode item = messages.addObject();
                item.put("role", message.role() == ChatMessage.Role.USER ? "user" : "assistant");
                item.put("content", message.content());
            }
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

    private synchronized void ensureServer() throws IOException, InterruptedException {
        if (server != null && server.isAlive() && healthy()) return;
        Files.createDirectories(home.resolve("logs"));
        Path log = home.resolve("logs/llama-server.log");
        server = new ProcessBuilder(
                executable.toString(), "--model", model.toString(),
                "--host", "127.0.0.1", "--port", Integer.toString(PORT),
                "--ctx-size", "4096", "--parallel", "1", "--no-webui")
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
