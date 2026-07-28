package dev.personalassistant.voice;

import dev.personalassistant.provider.LocalModelProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class WhisperTranscriber {
    private final Path executable;
    private final Path model;
    private final Path logs;

    public WhisperTranscriber(Path home) {
        String nativeDirectory = LocalModelProvider.detectPlatform().nativeDirectory();
        String executableName = System.getProperty("os.name").toLowerCase().contains("win")
                ? "whisper-cli.exe" : "whisper-cli";
        Path runtime = home.resolve("native").resolve(nativeDirectory).resolve("speech-to-text");
        this.executable = find(runtime, executableName);
        this.model = home.resolve("models/speech-to-text/ggml-base.en.bin");
        this.logs = home.resolve("logs");
    }

    public boolean available() {
        return executable != null && Files.isRegularFile(executable) && Files.isRegularFile(model);
    }

    public CompletableFuture<String> transcribe(Path waveFile) {
        return CompletableFuture.supplyAsync(() -> run(waveFile));
    }

    private String run(Path waveFile) {
        if (!available()) throw new IllegalStateException("Speech-to-text runtime or model is missing.");
        Path outputBase = waveFile.resolveSibling("transcript-" + System.nanoTime());
        Path textFile = Path.of(outputBase + ".txt");
        try {
            Files.createDirectories(logs);
            Process process = new ProcessBuilder(
                    executable.toString(), "--model", model.toString(),
                    "--file", waveFile.toString(), "--language", "en",
                    "--output-txt", "--output-file", outputBase.toString(),
                    "--no-timestamps", "--no-prints")
                    .directory(executable.getParent().toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(logs.resolve("whisper.log").toFile()))
                    .start();
            if (!process.waitFor(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException("Speech transcription timed out.");
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(textFile)) {
                throw new IOException("Whisper failed. See " + logs.resolve("whisper.log"));
            }
            String result = Files.readString(textFile, StandardCharsets.UTF_8).trim();
            Files.deleteIfExists(textFile);
            if (result.isBlank()) throw new IOException("No speech was detected.");
            return result;
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Speech transcription was interrupted.", e);
        }
    }

    private static Path find(Path directory, String filename) {
        if (!Files.isDirectory(directory)) return null;
        try (var files = Files.walk(directory, 3)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equalsIgnoreCase(filename))
                    .findFirst().orElse(null);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot inspect speech runtime " + directory, e);
        }
    }
}

