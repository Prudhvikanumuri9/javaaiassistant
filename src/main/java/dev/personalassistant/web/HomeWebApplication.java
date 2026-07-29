package dev.personalassistant.web;

import dev.personalassistant.home.HomeDatabase;
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

    @EventListener(ApplicationReadyEvent.class)
    void openBrowser() {
        URI address = URI.create("http://127.0.0.1:8787");
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
