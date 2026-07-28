package dev.personalassistant.model;

import java.time.Instant;

public record ChatMessage(long id, String conversationId, Role role, String content, Instant createdAt) {
    public enum Role {
        USER, ASSISTANT, SYSTEM
    }
}

