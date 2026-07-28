package dev.personalassistant.data;

import dev.personalassistant.model.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DatabaseTest {
    @TempDir
    Path temp;

    @Test
    void persistsMessagesAndMemory() {
        Path file = temp.resolve("assistant.db");
        try (Database database = new Database(file)) {
            database.addMessage("chat", ChatMessage.Role.USER, "hello");
            database.remember("name", "Ada");
            assertEquals("hello", database.messages("chat").getFirst().content());
            assertEquals("Ada", database.recall("name"));
        }
    }
}
