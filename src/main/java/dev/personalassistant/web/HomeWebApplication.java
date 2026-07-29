package dev.personalassistant.web;

import dev.personalassistant.home.HomeDatabase;
import dev.personalassistant.data.Database;
import dev.personalassistant.provider.LocalModelProvider;
import dev.personalassistant.provider.LocalVisionProvider;
import dev.personalassistant.voice.PiperSpeechSynthesizer;
import dev.personalassistant.voice.WhisperTranscriber;
import dev.personalassistant.voice.SpeechTextSanitizer;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;

import java.awt.Desktop;
import java.net.URI;
import java.nio.file.Path;

@SpringBootApplication(scanBasePackages = "dev.personalassistant")
public class HomeWebApplication {
    @Bean(destroyMethod = "close")
    HomeDatabase homeDatabase() {
        Path home = Path.of(System.getProperty("personalassistant.home", ".")).toAbsolutePath();
        return new HomeDatabase(home.resolve("data/home.db"));
    }

    @Bean(destroyMethod = "close")
    Database conversationDatabase() {
        Path home = Path.of(System.getProperty("personalassistant.home", ".")).toAbsolutePath();
        return new Database(home.resolve("data/conversations.db"));
    }

    @Bean(destroyMethod = "close")
    LocalModelProvider localModelProvider() {
        Path home = Path.of(System.getProperty("personalassistant.home", ".")).toAbsolutePath();
        return new LocalModelProvider(home);
    }

    @Bean
    LocalVisionProvider localVisionProvider() {
        Path home = Path.of(System.getProperty("personalassistant.home", ".")).toAbsolutePath();
        return new LocalVisionProvider(home);
    }

    @Bean
    WhisperTranscriber whisperTranscriber() {
        Path home = Path.of(System.getProperty("personalassistant.home", ".")).toAbsolutePath();
        return new WhisperTranscriber(home);
    }

    @Bean(destroyMethod = "close")
    PiperSpeechSynthesizer piperSpeechSynthesizer() {
        Path home = Path.of(System.getProperty("personalassistant.home", ".")).toAbsolutePath();
        return new PiperSpeechSynthesizer(home);
    }

    @Bean
    SpeechTextSanitizer speechTextSanitizer() {
        Path home = Path.of(System.getProperty("personalassistant.home", ".")).toAbsolutePath();
        return new SpeechTextSanitizer(home.resolve("config/voice-filter.properties"));
    }

    @EventListener(ApplicationReadyEvent.class)
    void openBrowser() {
        String scheme = Boolean.parseBoolean(System.getenv().getOrDefault("PA_HTTPS", "false"))
                ? "https" : "http";
        URI address = URI.create(scheme + "://127.0.0.1:8787");
        System.out.println("Personal Assistant Home is running at " + address);
        if (!Boolean.parseBoolean(System.getProperty("personalassistant.open-browser", "true"))) return;
        if (Desktop.isDesktopSupported()) {
            try {
                Desktop.getDesktop().browse(address);
            } catch (Exception ignored) {
                System.out.println("Open " + address + " in your browser.");
            }
        }
    }
}
