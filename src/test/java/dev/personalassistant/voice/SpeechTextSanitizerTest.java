package dev.personalassistant.voice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SpeechTextSanitizerTest {
    @TempDir Path temp;

    @Test
    void removesCodeFormattingUrlsAndSymbols() {
        SpeechTextSanitizer sanitizer = new SpeechTextSanitizer(temp.resolve("missing.properties"));
        String result = sanitizer.sanitize("""
                ## Result ✨
                Use **rice** & [beans](https://example.test).
                ```java
                System.out.println("do not speak");
                ```
                Then visit https://example.test/help.
                """);

        assertEquals("Result Use rice and beans. Then visit.", result);
        assertFalse(result.contains("System.out"));
    }

    @Test
    void removesCompositeKeycapsWithoutLeavingSpokenDigits() {
        SpeechTextSanitizer sanitizer = new SpeechTextSanitizer(temp.resolve("missing.properties"));

        String result = sanitizer.sanitize("Ready 0\uFE0F\u20E3 \u2070 "
                + new String(Character.toChars(0x24EA)) + " to cook.");

        assertEquals("Ready to cook.", result);
        assertFalse(result.contains("0"));
    }
}
