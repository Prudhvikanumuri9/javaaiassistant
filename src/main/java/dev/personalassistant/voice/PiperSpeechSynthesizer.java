package dev.personalassistant.voice;

import dev.personalassistant.provider.LocalModelProvider;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class PiperSpeechSynthesizer implements AutoCloseable {
    private final Path executable;
    private final Path model;
    private final Path logs;
    private Clip playing;

    public PiperSpeechSynthesizer(Path home) {
        String nativeDirectory = LocalModelProvider.detectPlatform().nativeDirectory();
        String executableName = System.getProperty("os.name").toLowerCase().contains("win")
                ? "piper.exe" : "piper";
        Path runtime = home.resolve("native").resolve(nativeDirectory).resolve("text-to-speech");
        this.executable = find(runtime, executableName);
        this.model = home.resolve("models/text-to-speech/en_US-lessac-medium.onnx");
        this.logs = home.resolve("logs");
    }

    public boolean available() {
        return executable != null && Files.isRegularFile(executable)
                && Files.isRegularFile(model) && Files.isRegularFile(Path.of(model + ".json"));
    }

    public CompletableFuture<Void> speak(String text) {
        return CompletableFuture.runAsync(() -> synthesizeAndPlay(text));
    }

    public CompletableFuture<Path> synthesize(String text, Path output) {
        return CompletableFuture.supplyAsync(() -> synthesizeFile(text, output));
    }

    private void synthesizeAndPlay(String text) {
        playUnchecked(synthesizeFile(text, logs.resolve("spoken-reply.wav")));
    }

    private Path synthesizeFile(String text, Path wave) {
        if (!available()) throw new IllegalStateException("Text-to-speech runtime or voice is missing.");
        try {
            Files.createDirectories(logs);
            Process process = new ProcessBuilder(
                    executable.toString(), "--model", model.toString(), "--output_file", wave.toString())
                    .directory(executable.getParent().toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(logs.resolve("piper.log").toFile()))
                    .start();
            try (var input = process.getOutputStream()) {
                input.write((text + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
            }
            if (!process.waitFor(Duration.ofMinutes(1).toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException("Speech synthesis timed out.");
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(wave)) {
                throw new IOException("Piper failed. See " + logs.resolve("piper.log"));
            }
            return wave;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot speak response: " + e.getMessage(), e);
        }
    }

    private void playUnchecked(Path wave) {
        try {
            play(wave);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot play response: " + e.getMessage(), e);
        }
    }

    private synchronized void play(Path wave) throws Exception {
        if (playing != null) {
            playing.stop();
            playing.close();
        }
        playing = AudioSystem.getClip();
        playing.open(AudioSystem.getAudioInputStream(wave.toFile()));
        playing.start();
    }

    @Override
    public synchronized void close() {
        if (playing != null) {
            playing.stop();
            playing.close();
        }
    }

    private static Path find(Path directory, String filename) {
        if (!Files.isDirectory(directory)) return null;
        try (var files = Files.walk(directory, 3)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equalsIgnoreCase(filename))
                    .findFirst().orElse(null);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot inspect voice runtime " + directory, e);
        }
    }
}
