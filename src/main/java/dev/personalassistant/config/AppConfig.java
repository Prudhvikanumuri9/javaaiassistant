package dev.personalassistant.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public record AppConfig(
        String assistantName,
        String provider,
        boolean openAiEnabled,
        boolean requireCloudApprovalEachTime,
        String theme
) {
    private static final ObjectMapper JSON = new ObjectMapper();

    public static AppConfig defaults() {
        return new AppConfig("Personal Assistant", "local", false, true, "system");
    }

    public static AppConfig load(Path path) {
        try {
            Files.createDirectories(path.getParent());
            if (!Files.exists(path)) {
                save(path, defaults());
            }
            return JSON.readValue(path.toFile(), AppConfig.class);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load settings from " + path, e);
        }
    }

    public static void save(Path path, AppConfig config) {
        try {
            Files.createDirectories(path.getParent());
            JSON.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), config);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot save settings to " + path, e);
        }
    }
}

