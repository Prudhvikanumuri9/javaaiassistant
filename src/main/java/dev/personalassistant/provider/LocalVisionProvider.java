package dev.personalassistant.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;

/**
 * Independent llama.cpp multimodal server used only for image understanding.
 * A vision model is always paired with its model-specific mmproj GGUF.
 */
public final class LocalVisionProvider implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(LocalVisionProvider.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int PORT = 18081;
    private static final URI COMPLETIONS =
            URI.create("http://127.0.0.1:" + PORT + "/v1/chat/completions");
    private static final URI HEALTH = URI.create("http://127.0.0.1:" + PORT + "/health");
    private final Path home;
    private final Path directory;
    private final Path selectionFile;
    private final Path executable;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private volatile Path selected;
    private Process server;

    public LocalVisionProvider(Path home) {
        this.home = home;
        directory = home.resolve("models/vision");
        selectionFile = home.resolve("config/model-selection.properties");
        LocalModelProvider.Platform platform = LocalModelProvider.detectPlatform();
        executable = home.resolve("native").resolve(platform.nativeDirectory())
                .resolve(platform.os().equals("windows") ? "llama-server.exe" : "llama-server");
        selected = loadSelection();
    }

    public List<String> models() {
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.toLowerCase(Locale.ROOT).endsWith(".gguf"))
                    .filter(name -> !name.toLowerCase(Locale.ROOT).startsWith("mmproj-"))
                    .sorted(String.CASE_INSENSITIVE_ORDER).toList();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot inspect vision models", e);
        }
    }

    public boolean available() {
        return Files.isRegularFile(executable) && selected != null
                && Files.isRegularFile(selected) && projectorFor(selected) != null;
    }

    public String selectedModel() {
        return selected == null ? "" : selected.getFileName().toString();
    }

    public CompletableFuture<VisionScan> analyze(String imageDataUrl) {
        return CompletableFuture.supplyAsync(() -> analyzeNow(imageDataUrl));
    }

    public synchronized void selectModel(String filename) {
        String safe = Path.of(filename).getFileName().toString();
        if (!models().contains(safe)) throw new IllegalArgumentException("Vision model not found: " + filename);
        stopServer();
        selected = directory.resolve(safe);
        try {
            Files.createDirectories(selectionFile.getParent());
            Properties properties = new Properties();
            if (Files.isRegularFile(selectionFile)) {
                try (var input = Files.newInputStream(selectionFile)) {
                    properties.load(input);
                }
            }
            properties.setProperty("visionModel", safe);
            try (var output = Files.newOutputStream(selectionFile)) {
                properties.store(output, "Personal Assistant model selection");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot save vision model selection", e);
        }
    }

    private VisionScan analyzeNow(String imageDataUrl) {
        if (!available()) {
            throw new IllegalStateException("Vision model, projector, or llama.cpp runtime is missing.");
        }
        if (imageDataUrl == null || !imageDataUrl.matches("^data:image/(jpeg|jpg|png);base64,.+")) {
            throw new IllegalArgumentException("A JPEG or PNG camera image is required.");
        }
        if (imageDataUrl.length() > 11_000_000) {
            throw new IllegalArgumentException(
                    "The encoded image exceeds 8 MB. Retake it after this application is updated, "
                            + "or upload a resized image no larger than 1920 pixels on its longest side.");
        }
        try {
            LOG.info("Vision scan requested (encoded image size: {} KB, model: {})",
                    imageDataUrl.length() / 1024, selectedModel());
            ensureServer();
            LOG.info("Vision engine is ready; submitting image for local OCR and item recognition");
            ObjectNode body = JSON.createObjectNode();
            body.put("model", selected.getFileName().toString());
            body.put("temperature", 0.1);
            body.put("max_tokens", 900);
            ObjectNode format = body.putObject("response_format");
            format.put("type", "json_object");
            ArrayNode messages = body.putArray("messages");
            ObjectNode message = messages.addObject();
            message.put("role", "user");
            ArrayNode content = message.putArray("content");
            content.addObject().put("type", "text").put("text", """
                    Detect up to 12 distinct household or grocery items clearly visible in the image and
                    read useful label text for each one. Return compact, single-line JSON shaped as
                    {"items":[...]} where every item has: name, category, quantity, unit, expiresOn,
                    brand, visibleText, notes, confidence.
                    Use an empty string when unknown. quantity must be a number (default 1). Use YYYY-MM-DD
                    for expiresOn only when clearly visible. Do not invent obscured text. Combine repeated
                    identical objects into one entry with the visible count. Category must be short.
                    Limit visibleText and notes to 120 characters each. Do not describe the scene.
                    """);
            content.addObject().put("type", "image_url").putObject("image_url")
                    .put("url", imageDataUrl);
            HttpRequest request = HttpRequest.newBuilder(COMPLETIONS).timeout(Duration.ofMinutes(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IOException("Vision engine returned HTTP " + response.statusCode() + ": " + response.body());
            }
            JsonNode responseJson = JSON.readTree(response.body());
            JsonNode choice = responseJson.path("choices").path(0);
            String finishReason = choice.path("finish_reason").asText("");
            String contentText = choice.path("message").path("content").asText().trim();
            LOG.info("Vision generation completed (finish reason: {}, response characters: {})",
                    finishReason.isBlank() ? "unspecified" : finishReason, contentText.length());
            if ("length".equalsIgnoreCase(finishReason)) {
                throw new IOException("Vision output exceeded its limit. Try a closer photo with fewer items.");
            }
            JsonNode result;
            try {
                result = JSON.readTree(stripFence(contentText));
            } catch (com.fasterxml.jackson.core.JsonProcessingException invalidJson) {
                LOG.warn("Vision model returned incomplete or invalid JSON ({} characters)",
                        contentText.length());
                throw new IOException(
                        "Vision returned an incomplete item list. Try a closer photo with fewer items.",
                        invalidJson);
            }
            JsonNode items = result.path("items");
            if (!items.isArray()) items = JSON.createArrayNode().add(result);
            List<VisionItem> detected = new java.util.ArrayList<>();
            for (JsonNode item : items) {
                String name = text(item, "name");
                if (!name.isBlank()) detected.add(new VisionItem(name, text(item, "category"),
                        item.path("quantity").asDouble(1), text(item, "unit"),
                        text(item, "expiresOn"), text(item, "brand"), text(item, "visibleText"),
                        text(item, "notes"), item.path("confidence").asDouble(0)));
            }
            LOG.info("Vision scan parsed successfully: {} item(s) detected", detected.size());
            return new VisionScan(detected);
        } catch (IOException e) {
            LOG.error("Vision scan failed: {}", e.getMessage());
            throw new CompletionException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Vision scan was interrupted");
            throw new CompletionException(e);
        }
    }

    private synchronized void ensureServer() throws IOException, InterruptedException {
        if (server != null && server.isAlive() && healthy()) {
            LOG.info("Reusing the running local vision engine");
            return;
        }
        Path projector = projectorFor(selected);
        if (projector == null) throw new IOException("No matching mmproj GGUF was found.");
        Files.createDirectories(home.resolve("logs"));
        Path log = home.resolve("logs/llama-vision-server.log");
        LOG.info("Starting local vision engine; first load may take several minutes");
        LOG.info("Vision model: {}", selected.getFileName());
        LOG.info("Vision projector: {}", projector.getFileName());
        server = new ProcessBuilder(executable.toString(), "--model", selected.toString(),
                "--mmproj", projector.toString(), "--host", "127.0.0.1",
                "--port", Integer.toString(PORT), "--ctx-size", "8192", "--parallel", "1",
                "--image-max-tokens", "1280", "--no-webui")
                .directory(executable.getParent().toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
        for (int attempt = 0; attempt < 600; attempt++) {
            if (!server.isAlive()) throw new IOException("Vision engine stopped during startup. See " + log);
            if (healthy()) {
                LOG.info("Local vision engine is ready on loopback port {}", PORT);
                return;
            }
            Thread.sleep(500);
        }
        stopServer();
        throw new IOException("Vision model startup timed out. See " + log);
    }

    private boolean healthy() {
        try {
            return http.send(HttpRequest.newBuilder(HEALTH).timeout(Duration.ofSeconds(1)).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (Exception ignored) {
            return false;
        }
    }

    private Path projectorFor(Path model) {
        if (model == null || !Files.isDirectory(directory)) return null;
        String identity = model.getFileName().toString().toLowerCase(Locale.ROOT)
                .replaceFirst("-(?:iq|q|f)\\d[^.]*\\.gguf$", "");
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile).filter(path -> {
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                return name.startsWith("mmproj-") && name.endsWith(".gguf") && name.contains(identity);
            }).sorted().findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private Path loadSelection() {
        String configured = "";
        if (Files.isRegularFile(selectionFile)) {
            try (var input = Files.newInputStream(selectionFile)) {
                Properties properties = new Properties();
                properties.load(input);
                configured = properties.getProperty("visionModel", "");
            } catch (IOException ignored) {
                // Fall through to first model.
            }
        }
        if (!configured.isBlank() && models().contains(configured)) return directory.resolve(configured);
        List<String> installed = models();
        return installed.isEmpty() ? null : directory.resolve(installed.getFirst());
    }

    private static String stripFence(String value) {
        return value.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asText("").trim();
    }

    private synchronized void stopServer() {
        if (server != null && server.isAlive()) server.destroy();
        server = null;
    }

    @Override
    public void close() {
        stopServer();
    }

    public record VisionScan(List<VisionItem> items) {}
    public record VisionItem(String name, String category, double quantity, String unit,
                             String expiresOn, String brand, String visibleText, String notes,
                             double confidence) {}
}
