# Windows NVIDIA GPU setup

This optional setup is separate from the normal Windows installation. It enables
CUDA inference for the local reasoning model and installs the Qwen3-14B
`Q4_K_M` baseline. It does not install Ollama or Python, and no prompt, image,
inventory, or conversation is sent to an external inference service.

## Baseline

- Windows 10 or 11 x64
- NVIDIA GPU with a current NVIDIA driver
- 16 GB VRAM minimum; 24 GB VRAM recommended
- Approximately 12 GB free disk space for downloads and extracted files
- Java 21 and the normal Personal Assistant installation

The tested target is an RTX 3090 Ti with 24 GB VRAM. The setup pins llama.cpp
release `b10152`, CUDA 12.4 runtime libraries, and
`Qwen3-14B-Q4_K_M.gguf`.

## Install

Stop the assistant before setup. Open **Command Prompt** in the portable
`PersonalAssistant` directory and run:

```cmd
setup-gpu.cmd
```

The script:

1. Confirms that `nvidia-smi` is available.
2. Downloads the matching llama.cpp CUDA 12.4 backend and CUDA runtime DLLs.
3. Downloads the official Qwen3-14B Q4_K_M GGUF (about 9 GB).
4. Selects that model in `config\model-selection.properties`.
5. Sets `gpuLayers=99` and enables flash attention in
   `config\inference.properties`.

To install only the CUDA runtime and keep your existing language model:

```cmd
setup-gpu.cmd -SkipModel
```

Use `-Force` to download the model again:

```cmd
setup-gpu.cmd -Force
```

The downloads are intentionally excluded from Git. Run this setup once on each
Windows NVIDIA computer after cloning and building the portable package.

## Start and verify

Start the application normally:

```cmd
start-assistant.cmd
```

Ask a short question, then open:

```cmd
findstr /i "cuda offloaded flash" logs\llama-server.log
```

The log should identify the CUDA device and show model layers being offloaded.
In a second Command Prompt, GPU memory and utilization can be watched with:

```cmd
nvidia-smi -l 1
```

Stop that display with `Ctrl+C`.

## Native tool-calling evaluation

The Qwen baseline receives llama.cpp/OpenAI-compatible function definitions,
not a prompt asking it to invent JSON. The application still validates each
returned function against its allowlist, and write actions remain proposals
until the user confirms them.

Stop the assistant, open Command Prompt at the source repository root, and run:

```cmd
evaluate-tool-calling.cmd
```

The repeatable cases are stored in
`src\test\resources\tool-calling-evaluation.json`. They cover inventory lookup,
recipe matching, named recipe retrieval, meal-plan proposals, and a general
question that should not select a tool. Maven prints a failed test and the
actual selected tools when the model regresses. Add cases to this JSON file as
new skills are introduced.

## Configuration

`config\inference.properties` controls the local llama.cpp process:

```properties
contextSize=8192
gpuLayers=99
flashAttention=on
```

- `contextSize` is the maximum prompt and response context. Larger values use
  more VRAM.
- `gpuLayers=99` requests that all available model layers be offloaded.
- `flashAttention=on` reduces attention memory use and commonly improves speed.

Restart the assistant after changing these values. If the server cannot start,
check `logs\llama-server.log` first. Reduce `contextSize` to `4096` if VRAM is
exhausted.

## Return to CPU mode

Stop the assistant, edit `config\inference.properties`, and set:

```properties
gpuLayers=0
flashAttention=auto
```

Select the smaller model from the web application after restart, or change
`reasoningModel` in `config\model-selection.properties`. CUDA DLLs may remain in
the native directory; `gpuLayers=0` prevents model-layer offload.

## Official download sources

- [llama.cpp release b10152](https://github.com/ggml-org/llama.cpp/releases/tag/b10152)
- [llama.cpp Windows CUDA 12.4 archive](https://github.com/ggml-org/llama.cpp/releases/download/b10152/llama-b10152-bin-win-cuda-12.4-x64.zip)
- [llama.cpp CUDA 12.4 runtime archive](https://github.com/ggml-org/llama.cpp/releases/download/b10152/cudart-llama-bin-win-cuda-12.4-x64.zip)
- [Official Qwen3-14B-GGUF repository](https://huggingface.co/Qwen/Qwen3-14B-GGUF)
- [Qwen3-14B Q4_K_M model file](https://huggingface.co/Qwen/Qwen3-14B-GGUF/blob/main/Qwen3-14B-Q4_K_M.gguf)
- [llama.cpp function-calling documentation](https://github.com/ggml-org/llama.cpp/blob/master/docs/function-calling.md)

These URLs are also embedded in `setup-gpu.ps1` so the installed runtime and
documentation stay auditable.
