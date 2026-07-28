package dev.personalassistant;

import dev.personalassistant.config.AppConfig;
import dev.personalassistant.data.Database;
import dev.personalassistant.provider.LocalModelProvider;
import dev.personalassistant.skill.SkillLoader;
import dev.personalassistant.ui.AssistantApp;
import javafx.application.Application;

import java.nio.file.Path;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        Path home = AppHome.resolve();
        System.setProperty("personalassistant.home", home.toString());
        AppConfig.load(home.resolve("config/assistant.json"));
        try (Database ignored = new Database(home.resolve("data/conversations.db"))) {
            // Fail early with a useful error if local storage cannot be initialized.
        }
        SkillLoader.load(home.resolve("skills"));
        LocalModelProvider.detectPlatform();
        Application.launch(AssistantApp.class, args);
    }
}

