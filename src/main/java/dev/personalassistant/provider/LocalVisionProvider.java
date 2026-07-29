package dev.personalassistant.provider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Independent model registry for image understanding. Runtime inference is
 * deliberately separate from the language-model server.
 */
public final class LocalVisionProvider {
    private final Path directory;
    private final Path selectionFile;
    private volatile String selected;

    public LocalVisionProvider(Path home) {
        directory = home.resolve("models/vision");
        selectionFile = home.resolve("config/model-selection.properties");
        selected = loadSelection();
    }

    public List<String> models() {
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(this::supported)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot inspect vision models", e);
        }
    }

    public boolean available() {
        return false; // Model-specific runtime adapter is the next installation step.
    }

    public String selectedModel() {
        return selected == null ? "" : selected;
    }

    public synchronized void selectModel(String filename) {
        String safe = Path.of(filename).getFileName().toString();
        if (!models().contains(safe)) throw new IllegalArgumentException("Vision model not found: " + filename);
        selected = safe;
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

    private String loadSelection() {
        if (!Files.isRegularFile(selectionFile)) return "";
        try (var input = Files.newInputStream(selectionFile)) {
            Properties properties = new Properties();
            properties.load(input);
            String value = properties.getProperty("visionModel", "");
            return models().contains(value) ? value : "";
        } catch (IOException e) {
            return "";
        }
    }

    private boolean supported(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        return lower.endsWith(".gguf") || lower.endsWith(".onnx");
    }
}
