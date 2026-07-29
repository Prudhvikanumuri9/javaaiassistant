package dev.personalassistant.voice;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.regex.Pattern;

public final class SpeechTextSanitizer {
    private static final Pattern FENCED_CODE = Pattern.compile("(?s)```.*?```|~~~.*?~~~");
    private static final Pattern INLINE_CODE = Pattern.compile("`[^`\\r\\n]+`");
    private static final Pattern IMAGE = Pattern.compile("!\\[[^]]*]\\([^)]*\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^]]+)]\\([^)]*\\)");
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|www\\.)[^\\s<>()]+(?<![.,!?;:])");
    private static final Pattern HTML = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern MARKDOWN = Pattern.compile("(?m)^\\s{0,3}(?:#{1,6}|[-+*]>?|\\d+[.)])\\s+|[*_~]{1,3}");

    private final Settings settings;

    public SpeechTextSanitizer(Path configuration) {
        this.settings = Settings.load(configuration);
    }

    public String sanitize(String input) {
        if (input == null || input.isBlank()) return "";
        String text = input;
        if (settings.skipFencedCode()) text = FENCED_CODE.matcher(text).replaceAll(" ");
        if (settings.skipInlineCode()) text = INLINE_CODE.matcher(text).replaceAll(" ");
        text = IMAGE.matcher(text).replaceAll(" ");
        text = LINK.matcher(text).replaceAll("$1");
        if (settings.removeUrls()) text = URL.matcher(text).replaceAll(" ");
        if (settings.removeHtml()) text = HTML.matcher(text).replaceAll(" ");
        if (settings.removeMarkdown()) text = MARKDOWN.matcher(text).replaceAll("");
        text = text.replace("&", " and ");
        if (settings.removeSymbols()) text = removeUnicodeSymbols(text);
        text = text.replaceAll("[\\p{Cc}\\p{Cf}&&[^\\r\\n\\t]]", " ")
                .replaceAll("\\s+", " ")
                .replaceAll("\\s+([,.;:!?])", "$1").trim();
        return limit(text, settings.maxCharacters());
    }

    private static String removeUnicodeSymbols(String text) {
        StringBuilder result = new StringBuilder(text.length());
        text.codePoints().forEach(codePoint -> {
            int type = Character.getType(codePoint);
            if (type != Character.MATH_SYMBOL && type != Character.CURRENCY_SYMBOL
                    && type != Character.MODIFIER_SYMBOL && type != Character.OTHER_SYMBOL) {
                result.appendCodePoint(codePoint);
            }
        });
        return result.toString();
    }

    private static String limit(String text, int maximum) {
        if (text.length() <= maximum) return text;
        int end = Math.max(text.lastIndexOf(". ", maximum), text.lastIndexOf("? ", maximum));
        if (end < maximum / 2) end = maximum;
        return text.substring(0, end).trim() + ".";
    }

    record Settings(boolean skipFencedCode, boolean skipInlineCode, boolean removeUrls,
                    boolean removeHtml, boolean removeMarkdown, boolean removeSymbols,
                    int maxCharacters) {
        static Settings load(Path path) {
            Properties values = new Properties();
            if (Files.isRegularFile(path)) {
                try (InputStream input = Files.newInputStream(path)) {
                    values.load(input);
                } catch (IOException e) {
                    throw new IllegalStateException("Cannot read voice filter configuration " + path, e);
                }
            }
            return new Settings(
                    bool(values, "skipFencedCode", true),
                    bool(values, "skipInlineCode", true),
                    bool(values, "removeUrls", true),
                    bool(values, "removeHtml", true),
                    bool(values, "removeMarkdown", true),
                    bool(values, "removeSymbols", true),
                    Math.max(200, Math.min(10_000, integer(values, "maxCharacters", 2000))));
        }

        private static boolean bool(Properties values, String key, boolean fallback) {
            return Boolean.parseBoolean(values.getProperty(key, Boolean.toString(fallback)));
        }

        private static int integer(Properties values, String key, int fallback) {
            try {
                return Integer.parseInt(values.getProperty(key, Integer.toString(fallback)));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
    }
}
