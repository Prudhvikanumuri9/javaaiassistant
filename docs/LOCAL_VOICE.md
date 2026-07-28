# Bundled local voice

The Windows package supports push-to-talk input and spoken assistant replies.
Audio stays on the device:

- Java Sound records a mono 16 kHz WAV.
- `whisper.cpp` converts the recording to English text.
- Piper converts the assistant response to speech.

## Download sources

- `whisper.cpp` v1.9.1 Windows x64 runtime:
  <https://github.com/ggml-org/whisper.cpp/releases/download/v1.9.1/whisper-bin-x64.zip>
- Official Whisper GGML base English model:
  <https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en.bin?download=true>
- Piper standalone Windows x64 runtime:
  <https://github.com/rhasspy/piper/releases/download/2023.11.14-2/piper_windows_amd64.zip>
- Piper `en_US-lessac-medium` voice:
  <https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/lessac/medium/en_US-lessac-medium.onnx?download=true>
- Voice configuration:
  <https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/lessac/medium/en_US-lessac-medium.onnx.json?download=true>

## Licenses

- `whisper.cpp` and its converted models use the MIT License:
  <https://github.com/ggml-org/whisper.cpp/blob/master/LICENSE>
- The bundled standalone Piper runtime uses the MIT License:
  <https://github.com/rhasspy/piper/blob/master/LICENSE.md>
- The Lessac voice model is MIT licensed:
  <https://huggingface.co/rhasspy/piper-voices/tree/main/en/en_US/lessac/medium>
- JNA provides the bundled Java-to-Windows `winmm` bridge and uses Apache
  License 2.0/LGPL 2.1 dual licensing:
  <https://github.com/java-native-access/jna>

The original Piper repository is archived. The maintained project is
<https://github.com/OHF-Voice/piper1-gpl>, but its current binary-release
documentation does not offer a standalone Windows package. The archived
standalone runtime is used to preserve the project's no-Python requirement.

## Usage

1. Click **Talk** and allow microphone access if Windows asks.
2. Speak naturally.
3. Click **Stop**.
4. The transcription is submitted to the assistant.
5. Leave **Speak replies** checked to hear the response.

Diagnostics are written to `logs/whisper.log` and `logs/piper.log`.

On Windows, recording uses the native `winmm` API and the current Windows
default recording device. This avoids Java Sound driver-format failures seen
with some USB headsets and virtual audio drivers.
