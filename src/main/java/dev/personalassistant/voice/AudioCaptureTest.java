package dev.personalassistant.voice;

import java.nio.file.Path;

public final class AudioCaptureTest {
    private AudioCaptureTest() {}

    public static void main(String[] args) throws Exception {
        AudioRecorder recorder = new AudioRecorder();
        recorder.start();
        Thread.sleep(1_000);
        Path output = recorder.stop(Path.of("target/microphone-test.wav")).get();
        System.out.println("Microphone capture succeeded: " + output.toAbsolutePath());
    }
}
