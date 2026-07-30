package dev.personalassistant;

import dev.personalassistant.config.AppConfig;
import dev.personalassistant.data.Database;
import dev.personalassistant.provider.LocalModelProvider;
import dev.personalassistant.skill.SkillLoader;
import dev.personalassistant.ui.AssistantApp;
import dev.personalassistant.web.HomeWebApplication;
import dev.personalassistant.home.HomeDatabase;
import javafx.application.Application;
import org.springframework.boot.SpringApplication;

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
        if (java.util.Arrays.asList(args).contains("--seed-cooking-test-data")) {
            try (HomeDatabase homeDatabase = new HomeDatabase(home.resolve("data/home.db"))) {
                var added = homeDatabase.seedCookingTestInventory();
                System.out.println("Cooking test inventory ready. Added " + added.size()
                        + " missing sample item(s); existing records were preserved.");
                System.out.println("Intentionally absent: chili sauce, vinegar, spring onions, paprika, "
                        + "baking powder, chicken, frozen sweet corn, white pepper, beaten egg, coriander.");
            }
            return;
        }
        if (java.util.Arrays.asList(args).contains("--desktop")) {
            Application.launch(AssistantApp.class, args);
            return;
        }
        System.setProperty("personalassistant.open-browser",
                Boolean.toString(!java.util.Arrays.asList(args).contains("--no-browser")));
        SpringApplication.run(HomeWebApplication.class, args);
    }
}
