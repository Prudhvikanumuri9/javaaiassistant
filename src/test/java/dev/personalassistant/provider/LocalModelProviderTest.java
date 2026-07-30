package dev.personalassistant.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LocalModelProviderTest {
    @Test
    void detectsSupportedPlatformDirectory() {
        String directory = LocalModelProvider.detectPlatform().nativeDirectory();
        assertTrue(directory.matches("(windows|linux|macos)-(x64|arm64)"));
    }

    @Test
    void sendsStrictNativeFunctionDefinitions() {
        ObjectNode request = LocalModelProvider.nativeToolRequest("Plan Crispy Corn for dinner");

        assertEquals("auto", request.path("tool_choice").asText());
        assertFalse(request.path("parallel_tool_calls").asBoolean());
        assertEquals(List.of(
                        "inventory_list",
                        "recipes_find_by_available_ingredients",
                        "recipes_get",
                        "meal_plan_propose"),
                request.path("tools").findValuesAsText("name"));
        assertTrue(request.path("tools").path(2).path("function").path("parameters")
                .path("required").toString().contains("query"));
    }

    @Test
    void parsesNativeCallsAndMapsExternalNamesToInternalTools() throws Exception {
        String response = """
                {"choices":[{"message":{"tool_calls":[
                  {"type":"function","function":{"name":"inventory_list","arguments":"{}"}},
                  {"type":"function","function":{"name":"recipes_get",
                    "arguments":"{\\"query\\":\\"Crispy Corn\\"}"}},
                  {"type":"function","function":{"name":"unknown_delete","arguments":"{}"}}
                ]}}]}
                """;

        assertEquals(List.of(
                        new LocalModelProvider.ToolCall("inventory.list", ""),
                        new LocalModelProvider.ToolCall("recipes.get", "Crispy Corn")),
                LocalModelProvider.parseNativeToolCalls(response));
    }

    @Test
    void ignoresMalformedToolArguments() throws Exception {
        String response = """
                {"choices":[{"message":{"tool_calls":[
                  {"function":{"name":"recipes_get","arguments":"{\\"query\\":"}}
                ]}}]}
                """;

        assertTrue(LocalModelProvider.parseNativeToolCalls(response).isEmpty());
    }
}
