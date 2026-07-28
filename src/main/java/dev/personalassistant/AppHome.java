package dev.personalassistant;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class AppHome {
    private AppHome() {}

    public static Path resolve() {
        String override = System.getProperty("personalassistant.home");
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }
        try {
            Path location = Path.of(Main.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath();
            Path candidate = Files.isRegularFile(location) ? location.getParent() : location;
            if (Files.isDirectory(candidate.resolve("config"))) {
                return candidate;
            }
        } catch (URISyntaxException ignored) {
            // Fall back to the working directory below.
        }
        return Path.of("").toAbsolutePath().normalize();
    }
}

