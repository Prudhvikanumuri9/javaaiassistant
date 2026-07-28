package dev.personalassistant.voice;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

public final class AudioRecorder {
    private final WindowsWaveInRecorder windowsRecorder =
            System.getProperty("os.name", "").toLowerCase().contains("win")
                    ? new WindowsWaveInRecorder() : null;
    private static final AudioFormat[] FORMATS = createFormats();
    private TargetDataLine line;
    private AudioFormat activeFormat;
    private CompletableFuture<byte[]> capture;

    public synchronized void start() {
        if (windowsRecorder != null) {
            windowsRecorder.start();
            return;
        }
        if (line != null) return;
        LineUnavailableException lastFailure = null;
        for (Mixer.Info mixerInfo : captureMixers()) {
            Mixer mixer = AudioSystem.getMixer(mixerInfo);
            for (AudioFormat format : FORMATS) {
                try {
                    DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
                    if (!mixer.isLineSupported(info)) continue;
                    TargetDataLine candidate = (TargetDataLine) mixer.getLine(info);
                    candidate.open(format);
                    candidate.start();
                    line = candidate;
                    activeFormat = candidate.getFormat();
                    capture = CompletableFuture.supplyAsync(() -> readAll(candidate));
                    return;
                } catch (LineUnavailableException | IllegalArgumentException e) {
                    if (e instanceof LineUnavailableException unavailable) lastFailure = unavailable;
                }
            }
        }
        throw new IllegalStateException(
                "No compatible microphone format was found. Check Windows microphone access and input-device settings.",
                lastFailure);
    }

    private static Mixer.Info[] captureMixers() {
        return java.util.Arrays.stream(AudioSystem.getMixerInfo())
                .filter(info -> java.util.Arrays.stream(AudioSystem.getMixer(info).getTargetLineInfo())
                        .anyMatch(lineInfo -> lineInfo instanceof DataLine.Info dataInfo
                                && TargetDataLine.class.isAssignableFrom(dataInfo.getLineClass())))
                .sorted(java.util.Comparator.comparingInt(AudioRecorder::mixerPriority))
                .toArray(Mixer.Info[]::new);
    }

    private static int mixerPriority(Mixer.Info info) {
        String name = info.getName().toLowerCase(java.util.Locale.ROOT);
        if (name.contains("primary sound capture")) return 0;
        if (!name.contains("virtual") && !name.contains("broadcast")) return 1;
        return 2;
    }

    private static AudioFormat[] createFormats() {
        float[] rates = {48_000, 44_100, 32_000, 24_000, 22_050, 16_000, 11_025, 8_000, 96_000};
        java.util.List<AudioFormat> formats = new java.util.ArrayList<>();
        for (float rate : rates) {
            formats.add(new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, 16, 1, 2, rate, false));
            formats.add(new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, 16, 2, 4, rate, false));
            formats.add(new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, 24, 1, 3, rate, false));
            formats.add(new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, 24, 2, 6, rate, false));
            formats.add(new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, 32, 1, 4, rate, false));
            formats.add(new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, 32, 2, 8, rate, false));
            formats.add(new AudioFormat(AudioFormat.Encoding.PCM_FLOAT, rate, 32, 1, 4, rate, false));
            formats.add(new AudioFormat(AudioFormat.Encoding.PCM_FLOAT, rate, 32, 2, 8, rate, false));
            formats.add(new AudioFormat(AudioFormat.Encoding.PCM_UNSIGNED, rate, 8, 1, 1, rate, false));
            formats.add(new AudioFormat(AudioFormat.Encoding.PCM_UNSIGNED, rate, 8, 2, 2, rate, false));
        }
        return formats.toArray(AudioFormat[]::new);
    }

    public synchronized CompletableFuture<Path> stop(Path output) {
        if (windowsRecorder != null) return windowsRecorder.stop(output);
        if (line == null || capture == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Recording has not started."));
        }
        AudioFormat recordedFormat = activeFormat;
        line.stop();
        line.close();
        line = null;
        activeFormat = null;
        CompletableFuture<byte[]> completed = capture;
        capture = null;
        return completed.thenApply(bytes -> writeWave(output, bytes, recordedFormat));
    }

    public synchronized boolean isRecording() {
        if (windowsRecorder != null) return windowsRecorder.isRecording();
        return line != null;
    }

    private static byte[] readAll(TargetDataLine input) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        while (input.isOpen()) {
            int count = input.read(buffer, 0, buffer.length);
            if (count > 0) bytes.write(buffer, 0, count);
        }
        return bytes.toByteArray();
    }

    private static Path writeWave(Path output, byte[] bytes, AudioFormat format) {
        try {
            Files.createDirectories(output.getParent());
            long frames = bytes.length / format.getFrameSize();
            try (AudioInputStream audio = new AudioInputStream(
                    new ByteArrayInputStream(bytes), format, frames)) {
                AudioSystem.write(audio, AudioFileFormat.Type.WAVE, output.toFile());
            }
            return output;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot save microphone recording.", e);
        }
    }
}
