package dev.personalassistant.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "assistant.toolEvaluation", matches = "true")
class ToolCallingEvaluationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TestFactory
    Iterable<DynamicTest> evaluatesNativeToolSelection() throws Exception {
        Path home = Path.of(System.getProperty("personalassistant.home", "target/PersonalAssistant"))
                .toAbsolutePath().normalize();
        JsonNode cases = JSON.readTree(ToolCallingEvaluationTest.class.getResourceAsStream(
                "/tool-calling-evaluation.json"));

        return StreamSupport.stream(cases.spliterator(), false).map(testCase ->
                DynamicTest.dynamicTest(testCase.path("name").asText(), () -> {
                    try (LocalModelProvider provider = new LocalModelProvider(home)) {
                        assertTrue(provider.available(), "Package the portable runtime before evaluation: " + home);
                        List<LocalModelProvider.ToolCall> calls =
                                provider.planTools(testCase.path("prompt").asText()).join();
                        Set<String> actual = new HashSet<>(calls.stream()
                                .map(LocalModelProvider.ToolCall::name).toList());
                        Set<String> expectedAll = textSet(testCase.path("expectedAll"));
                        Set<String> expectedAny = textSet(testCase.path("expectedAny"));
                        assertTrue(actual.containsAll(expectedAll),
                                () -> "Expected all " + expectedAll + " but model selected " + actual);
                        assertTrue(expectedAny.isEmpty() || expectedAny.stream().anyMatch(actual::contains),
                                () -> "Expected any " + expectedAny + " but model selected " + actual);
                        if (expectedAll.isEmpty() && testCase.has("expectedAll") && expectedAny.isEmpty()) {
                            assertTrue(actual.isEmpty(), () -> "Expected no tools but model selected " + actual);
                        }
                    }
                })).toList();
    }

    private static Set<String> textSet(JsonNode values) {
        if (!values.isArray()) return Set.of();
        Set<String> result = new HashSet<>();
        values.forEach(value -> result.add(value.asText()));
        return result;
    }
}
