package dev.personalassistant.provider;

import dev.personalassistant.model.ChatMessage;
import dev.personalassistant.skill.Skill;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface AssistantProvider extends AutoCloseable {
    String name();
    boolean available();
    CompletableFuture<String> reply(List<ChatMessage> history, List<Skill> skills);

    @Override
    default void close() {}
}
