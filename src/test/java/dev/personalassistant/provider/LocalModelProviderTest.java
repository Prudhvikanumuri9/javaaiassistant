package dev.personalassistant.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LocalModelProviderTest {
    @Test
    void detectsSupportedPlatformDirectory() {
        String directory = LocalModelProvider.detectPlatform().nativeDirectory();
        assertTrue(directory.matches("(windows|linux|macos)-(x64|arm64)"));
    }
}

