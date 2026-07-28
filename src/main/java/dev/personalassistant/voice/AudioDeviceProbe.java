package dev.personalassistant.voice;

import javax.sound.sampled.*;

public final class AudioDeviceProbe {
    private AudioDeviceProbe() {}

    public static void main(String[] args) {
        Mixer.Info[] mixers = AudioSystem.getMixerInfo();
        System.out.println("Java Sound mixers: " + mixers.length);
        for (Mixer.Info mixerInfo : mixers) {
            Mixer mixer = AudioSystem.getMixer(mixerInfo);
            Line.Info[] targets = mixer.getTargetLineInfo();
            if (targets.length == 0) continue;
            System.out.println();
            System.out.println("CAPTURE DEVICE: " + mixerInfo.getName());
            System.out.println("  Vendor: " + mixerInfo.getVendor());
            System.out.println("  Description: " + mixerInfo.getDescription());
            for (Line.Info lineInfo : targets) {
                System.out.println("  Line: " + lineInfo);
                if (lineInfo instanceof DataLine.Info dataInfo) {
                    AudioFormat[] formats = dataInfo.getFormats();
                    if (formats.length == 0) {
                        System.out.println("    Formats: device reports unspecified/dynamic format support");
                    } else {
                        for (AudioFormat format : formats) {
                            System.out.println("    " + format);
                        }
                    }
                }
            }
        }
    }
}
