package dev.personalassistant.voice;

import com.sun.jna.*;
import com.sun.jna.ptr.PointerByReference;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

final class WindowsWaveInRecorder {
    private static final int WAVE_MAPPER = -1;
    private static final int WAVE_FORMAT_PCM = 1;
    private static final int MMSYSERR_NOERROR = 0;
    private static final int MAX_SECONDS = 300;
    private static final AudioFormat AUDIO_FORMAT = new AudioFormat(48_000, 16, 1, true, false);

    interface WinMM extends Library {
        WinMM INSTANCE = Native.load("winmm", WinMM.class);

        int waveInOpen(PointerByReference handle, int deviceId, WaveFormat format,
                       Pointer callback, Pointer instance, int flags);
        int waveInPrepareHeader(Pointer handle, WaveHeader header, int size);
        int waveInAddBuffer(Pointer handle, WaveHeader header, int size);
        int waveInStart(Pointer handle);
        int waveInStop(Pointer handle);
        int waveInReset(Pointer handle);
        int waveInUnprepareHeader(Pointer handle, WaveHeader header, int size);
        int waveInClose(Pointer handle);
        int waveInGetErrorTextW(int error, char[] text, int length);
    }

    @Structure.FieldOrder({
            "wFormatTag", "nChannels", "nSamplesPerSec", "nAvgBytesPerSec",
            "nBlockAlign", "wBitsPerSample", "cbSize"
    })
    public static final class WaveFormat extends Structure {
        public short wFormatTag;
        public short nChannels;
        public int nSamplesPerSec;
        public int nAvgBytesPerSec;
        public short nBlockAlign;
        public short wBitsPerSample;
        public short cbSize;
    }

    @Structure.FieldOrder({
            "lpData", "dwBufferLength", "dwBytesRecorded", "dwUser",
            "dwFlags", "dwLoops", "lpNext", "reserved"
    })
    public static final class WaveHeader extends Structure {
        public Pointer lpData;
        public int dwBufferLength;
        public int dwBytesRecorded;
        public Pointer dwUser;
        public int dwFlags;
        public int dwLoops;
        public Pointer lpNext;
        public Pointer reserved;
    }

    private Pointer handle;
    private Memory buffer;
    private WaveHeader header;

    synchronized void start() {
        if (handle != null) return;
        WaveFormat format = new WaveFormat();
        format.wFormatTag = WAVE_FORMAT_PCM;
        format.nChannels = 1;
        format.nSamplesPerSec = 48_000;
        format.wBitsPerSample = 16;
        format.nBlockAlign = 2;
        format.nAvgBytesPerSec = 96_000;
        format.cbSize = 0;
        format.write();

        PointerByReference opened = new PointerByReference();
        check(WinMM.INSTANCE.waveInOpen(opened, WAVE_MAPPER, format, null, null, 0), "open microphone");
        handle = opened.getValue();
        buffer = new Memory((long) format.nAvgBytesPerSec * MAX_SECONDS);
        header = new WaveHeader();
        header.lpData = buffer;
        header.dwBufferLength = (int) buffer.size();
        header.write();
        try {
            check(WinMM.INSTANCE.waveInPrepareHeader(handle, header, header.size()), "prepare microphone buffer");
            check(WinMM.INSTANCE.waveInAddBuffer(handle, header, header.size()), "queue microphone buffer");
            check(WinMM.INSTANCE.waveInStart(handle), "start microphone");
        } catch (RuntimeException e) {
            closeNative();
            throw e;
        }
    }

    synchronized CompletableFuture<Path> stop(Path output) {
        if (handle == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Recording has not started."));
        }
        try {
            check(WinMM.INSTANCE.waveInStop(handle), "stop microphone");
            check(WinMM.INSTANCE.waveInReset(handle), "finish microphone buffer");
            header.read();
            int length = header.dwBytesRecorded;
            byte[] audio = buffer.getByteArray(0, length);
            WinMM.INSTANCE.waveInUnprepareHeader(handle, header, header.size());
            WinMM.INSTANCE.waveInClose(handle);
            handle = null;
            buffer = null;
            header = null;
            return CompletableFuture.completedFuture(writeWave(output, audio));
        } catch (RuntimeException e) {
            closeNative();
            return CompletableFuture.failedFuture(e);
        }
    }

    synchronized boolean isRecording() {
        return handle != null;
    }

    private void closeNative() {
        if (handle != null) {
            WinMM.INSTANCE.waveInReset(handle);
            if (header != null) WinMM.INSTANCE.waveInUnprepareHeader(handle, header, header.size());
            WinMM.INSTANCE.waveInClose(handle);
        }
        handle = null;
        buffer = null;
        header = null;
    }

    private static Path writeWave(Path output, byte[] bytes) {
        try {
            Files.createDirectories(output.getParent());
            try (AudioInputStream audio = new AudioInputStream(
                    new ByteArrayInputStream(bytes), AUDIO_FORMAT, bytes.length / 2L)) {
                AudioSystem.write(audio, AudioFileFormat.Type.WAVE, output.toFile());
            }
            return output;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot save microphone recording.", e);
        }
    }

    private static void check(int code, String action) {
        if (code == MMSYSERR_NOERROR) return;
        char[] text = new char[256];
        WinMM.INSTANCE.waveInGetErrorTextW(code, text, text.length);
        throw new IllegalStateException("Cannot " + action + ": " + Native.toString(text) + " (" + code + ")");
    }
}
