package dev.personalassistant.data;

import dev.personalassistant.model.ChatMessage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class Database implements AutoCloseable {
    private final Connection connection;
    public record ConversationSummary(String id, String title, String updatedAt) {}

    public Database(Path file) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            initialize();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot initialize database " + file, e);
        }
    }

    private void initialize() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS messages (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      conversation_id TEXT NOT NULL,
                      role TEXT NOT NULL,
                      content TEXT NOT NULL,
                      created_at TEXT NOT NULL
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS memory (
                      key TEXT PRIMARY KEY,
                      value TEXT NOT NULL,
                      updated_at TEXT NOT NULL
                    )""");
            statement.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS idx_messages_conversation
                    ON messages(conversation_id, id)""");
        }
    }

    public ChatMessage addMessage(String conversationId, ChatMessage.Role role, String content) {
        String sql = "INSERT INTO messages(conversation_id, role, content, created_at) VALUES(?,?,?,?)";
        Instant now = Instant.now();
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, conversationId);
            statement.setString(2, role.name());
            statement.setString(3, content);
            statement.setString(4, now.toString());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                return new ChatMessage(keys.next() ? keys.getLong(1) : 0, conversationId, role, content, now);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot save message", e);
        }
    }

    public List<ChatMessage> messages(String conversationId) {
        String sql = "SELECT id, role, content, created_at FROM messages WHERE conversation_id=? ORDER BY id";
        List<ChatMessage> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, conversationId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new ChatMessage(
                            rows.getLong("id"), conversationId,
                            ChatMessage.Role.valueOf(rows.getString("role")),
                            rows.getString("content"), Instant.parse(rows.getString("created_at"))));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read messages", e);
        }
    }

    public synchronized List<ConversationSummary> conversations() {
        String sql = """
                SELECT conversation_id,
                       COALESCE(MAX(CASE WHEN role='USER' THEN substr(content,1,60) END), 'New chat') title,
                       MAX(created_at) updated_at
                FROM messages
                GROUP BY conversation_id
                ORDER BY updated_at DESC
                LIMIT 30""";
        List<ConversationSummary> result = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) result.add(new ConversationSummary(
                    rows.getString("conversation_id"), rows.getString("title"), rows.getString("updated_at")));
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot list conversations", e);
        }
    }

    public synchronized void clearConversation(String conversationId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM messages WHERE conversation_id=?")) {
            statement.setString(1, conversationId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot clear conversation", e);
        }
    }

    public void remember(String key, String value) {
        String sql = """
                INSERT INTO memory(key, value, updated_at) VALUES(?,?,?)
                ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_at=excluded.updated_at""";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.setString(3, Instant.now().toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot save memory", e);
        }
    }

    public String recall(String key) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT value FROM memory WHERE key=?")) {
            statement.setString(1, key);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read memory", e);
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot close database", e);
        }
    }
}
