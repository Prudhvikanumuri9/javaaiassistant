package dev.personalassistant.skill;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

public final class SkillLoader {
    private static final ObjectMapper JSON = new ObjectMapper();

    private SkillLoader() {}

    public static List<Skill> load(Path directory) {
        try {
            Files.createDirectories(directory);
            try (Stream<Path> entries = Files.list(directory)) {
                return entries.filter(Files::isDirectory)
                        .map(path -> path.resolve("skill.json"))
                        .filter(Files::isRegularFile)
                        .map(SkillLoader::read)
                        .filter(Skill::enabled)
                        .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                        .toList();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load skills from " + directory, e);
        }
    }

    private static Skill read(Path file) {
        try {
            return JSON.readValue(file.toFile(), Skill.class);
        } catch (IOException e) {
            throw new IllegalStateException("Invalid skill manifest " + file, e);
        }
    }
}

