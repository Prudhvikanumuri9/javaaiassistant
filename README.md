# Personal Assistant

The default experience is now a local Spring Boot home organizer at
`http://127.0.0.1:8787`. It includes:

- Camera capture and photo-backed household inventory
- Protected local-AI chat with inventory, meal, and shopping context
- Independent reasoning-model and vision-model selectors
- Local web push-to-talk transcription through bundled Whisper
- Optional locally generated spoken replies through bundled Piper
- Quantities, storage locations, minimum stock, and expiration dates
- Weekly cooking and meal planning
- A shopping list calculated from planned ingredients minus current inventory
- Local SQLite storage in `data/home.db` and photos in `data/item-photos/`

## Web assistant and model switching

Open **AI assistant** in the web navigation. The assistant reads a current
snapshot of inventory, this week's meals, and calculated shopping shortages
before answering. It is intentionally read-only: it may suggest changes, but
inventory and plans are changed only through their dedicated screens.

Every `.gguf` file in `models/language/` appears in the **Reasoning model**
selector. Changing the selection stops the current local reasoning server and
loads the chosen model with the next question. The choice is saved in
`config/model-selection.properties`.

Vision is a separate provider and selector backed by `models/vision/`; captured
images are never sent to the reasoning model. A compatible local vision runtime
and model must be installed before automatic image labels become available.

### Web assistant voice

On the **AI assistant** page:

1. Select **Start talking** and allow microphone permission.
2. Speak, then select **Stop**.
3. Whisper transcribes the browser-generated 16 kHz mono WAV locally.
4. Review the text and select **Ask assistant**.
5. Enable **Speak replies** to synthesize answers locally with Piper.

Audio travels only between the browser and this PC over the configured HTTPS
connection. It is deleted after transcription or playback generation.

Start the packaged application:

```powershell
java -jar personal-assistant.jar
```

The browser opens automatically. Use `java -jar personal-assistant.jar --no-browser`
when starting it from a service or terminal-only session. The original JavaFX chat
interface remains available with `java -jar personal-assistant.jar --desktop`.

By default, the server listens only on `127.0.0.1`, so other devices cannot
access household data. The HTTPS setup below explicitly enables trusted LAN access.

## HTTPS access from a phone

The portable package includes scripts for trusted local HTTPS. First find the
computer's Wi-Fi IPv4 address with `ipconfig`, then run:

```cmd
cd target\PersonalAssistant
setup-https.cmd 192.168.1.25
run-https.cmd
```

Replace `192.168.1.25` with the computer's actual address. Install
`config\personal-assistant-ca.cer` as a trusted CA certificate on the phone,
then browse to `https://192.168.1.25:8787`. Full phone instructions are in
[INSTALL.md](INSTALL.md).

The setup command prints a generated six-digit household PIN. Sign in with
username `home` and that PIN. Use LAN mode only on a trusted private home
network and never forward port 8787 through the router.

Run setup only once. Normal starts require only `run-https.cmd`. Re-running
setup with the same IP preserves the existing certificate and PIN. Use
`setup-https.cmd YOUR_PC_IP --force` only when intentionally replacing them.

A Java 21, local-first desktop assistant MVP. It includes a JavaFX chat UI,
SQLite conversation and memory storage, installable skills, configurable
permissions, and a provider boundary for a future bundled native LLM engine.

For Windows installation, source builds, microphone setup, updates, and
troubleshooting, see [INSTALL.md](INSTALL.md).

## Run

Build with Maven 3.9+:

```powershell
mvn clean package
cd target\PersonalAssistant
java -jar personal-assistant.jar
```

The generated `target/PersonalAssistant` directory is the distributable
application. JavaFX artifacts are selected for the OS on which Maven builds the
JAR. Build once on each target OS for its matching desktop JAR.

On Intel macOS use `mvn -Pmacos-x64 clean package`; on Apple Silicon use
`mvn -Pmacos-arm64 clean package`.

The Windows distribution includes a CPU-only `llama.cpp` runtime and the
Qwen2.5 1.5B Instruct Q5_K_M model. The provider starts the engine on loopback
when the first message is sent. See [docs/LOCAL_MODEL.md](docs/LOCAL_MODEL.md)
for exact download sources, licenses, replacement instructions, and logs.

Push-to-talk input and spoken replies are also bundled for Windows. See
[docs/LOCAL_VOICE.md](docs/LOCAL_VOICE.md) for sources, licenses, and usage.

## Skill format

Each folder under `skills` may contain a `skill.json`:

```json
{
  "id": "example",
  "name": "Example",
  "description": "Adds example context",
  "enabled": true,
  "prompt": "You are especially helpful with examples."
}
```

Only enabled skills are loaded. Settings and cloud permissions live in
`config/assistant.json`; OpenAI is disabled by default and this milestone makes
no network requests.
