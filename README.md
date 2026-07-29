# Personal Assistant

The default experience is now a local Spring Boot home organizer at
`http://127.0.0.1:8787`. It includes:

- Camera capture and photo-backed household inventory
- Quantities, storage locations, minimum stock, and expiration dates
- Weekly cooking and meal planning
- A shopping list calculated from planned ingredients minus current inventory
- Local SQLite storage in `data/home.db` and photos in `data/item-photos/`

Start the packaged application:

```powershell
java -jar personal-assistant.jar
```

The browser opens automatically. Use `java -jar personal-assistant.jar --no-browser`
when starting it from a service or terminal-only session. The original JavaFX chat
interface remains available with `java -jar personal-assistant.jar --desktop`.

The server listens only on `127.0.0.1`, so other devices cannot access household
data. LAN/mobile access will be added later together with authentication and HTTPS.

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
