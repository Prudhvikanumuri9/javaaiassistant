# Bundled local model

The Windows package uses a CPU-only `llama.cpp` server and a small Qwen
instruction model. They run on `127.0.0.1` only; chat content is not sent over
the internet.

## Download sources

- `llama.cpp` b10152 Windows x64 CPU runtime:
  <https://github.com/ggml-org/llama.cpp/releases/download/b10152/llama-b10152-bin-win-cpu-x64.zip>
- Official release page:
  <https://github.com/ggml-org/llama.cpp/releases/tag/b10152>
- Qwen2.5 1.5B Instruct GGUF repository:
  <https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF>
- Bundled Q5_K_M model file:
  <https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q5_k_m.gguf?download=true>

## Licenses

- `llama.cpp` is distributed under the MIT License:
  <https://github.com/ggml-org/llama.cpp/blob/master/LICENSE>
- Qwen2.5 1.5B is distributed under the Apache License 2.0. The model card and
  license are available in the official repository:
  <https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF>

The GGUF model can be replaced by placing another compatible `.gguf` file in
`models/language`. Keep only the desired model in that folder; the assistant
selects the first GGUF file alphabetically.

The first reply takes longer because `llama-server` must load the model. Runtime
diagnostics are written to `logs/llama-server.log`.
