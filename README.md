# Personal Assistant

The default experience is now a local Spring Boot home organizer at
`http://127.0.0.1:8787`. It includes:

For the optional CUDA-enabled Qwen3-14B reasoning baseline, see the separate
[Windows NVIDIA GPU setup](docs/GPU_SETUP.md).

- Camera capture or image upload with photo-backed household inventory
- Inventory-aware local recipe recommendations with confirmed meal-plan actions
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

Use **New chat** to begin a separate persisted conversation, choose an earlier
conversation from **Recent chats**, or use **Clear chat** to permanently delete
only the active conversation. The built-in `HOME_INVENTORY_READ` skill refreshes
from SQLite for every question, so inventory answers use current quantities,
locations, expiration dates, meal plans, and shopping shortages.

### Selecting multiple skills

The assistant header lists enabled skills installed under `skills/`.

- **Home inventory** is built in and always available.
- **Auto skills** matches the question against installed skill metadata and
  includes only relevant prompts.
- Turn off **Auto skills** to select multiple skills manually.
- Manual and Auto preferences are remembered by the browser.

Selective activation is preferable to always enabling every skill: it reduces
conflicting instructions and preserves the limited local-model context window.
Each installed skill remains independently replaceable through its `skill.json`
manifest.

Cooking requests now invoke an executable local recipe matcher. The assistant
can compare recipes with live inventory, explain shortages, and propose planning
a meal. Database changes occur only after **Confirm action** is selected. See
[Executable assistant skills](docs/EXECUTABLE_SKILLS.md).

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
4. A successful non-empty transcript is sent to the assistant automatically.
5. Enable **Speak replies** to synthesize answers locally with Piper.

Audio travels only between the browser and this PC over the configured HTTPS
connection. It is deleted after transcription or playback generation.

Long responses stream into the chat as the local model produces tokens. With
**Speak replies** enabled, complete non-code sentences are cleaned and queued
through Piper while later text is still being generated. Configure cleanup in
`config/voice-filter.properties`; fenced code, inline code, URLs, HTML,
Markdown markers, emoji/symbols, and overlong text are filtered by default.

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

`start-assistant.cmd` runs in the current window by default; press `Ctrl+C` to
stop. Use `start-assistant.cmd --background` for detached operation, and
`stop-assistant.cmd` to stop either mode. Run the stop command before rebuilding
so Windows releases the bundled native AI DLLs.

On the first start for a source checkout, use
`start-assistant.cmd YOUR_PC_IP`. The launcher preserves HTTPS files under
`portable\config`, outside the disposable Maven `target` directory, and restores
them automatically after later clean builds.

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
