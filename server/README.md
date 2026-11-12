# CaramelAI — local LLM server

Android talks to **llama-server** (OpenAI-compatible HTTP) on your PC.

## Model

- Card: [Vortex5/Chaos-Unknown-12b](https://huggingface.co/Vortex5/Chaos-Unknown-12b) (Mistral 12B merge, safetensors)
- GGUF for llama.cpp: [mradermacher/Chaos-Unknown-12b-GGUF](https://huggingface.co/mradermacher/Chaos-Unknown-12b-GGUF)
- Default quant: **Q4_K_M** (`Chaos-Unknown-12b.Q4_K_M.gguf`) — good fit for RTX 3060 12 GB
- Chat template: Mistral Instruct (`[INST]…[/INST]`); `--jinja` applies the one embedded in the GGUF

## Setup

1. Install the Hugging Face CLI if needed:
   ```bat
   pip install -U "huggingface_hub[cli]"
   ```
2. Download the model:
   ```bat
   download-model.bat
   ```
3. Download a **CUDA** Windows build of [llama.cpp](https://github.com/ggml-org/llama.cpp/releases) and put `llama-server.exe` in this folder.
4. Allow inbound TCP **8081** on the **Private** firewall profile only.
5. Start the server:
   ```bat
   start-server.bat
   ```

Default API key: `caramel-local-key`  
Default listen: `0.0.0.0:8081` (8080 is often already taken on Windows)

## App settings

- Base URL: `http://<PC-LAN-IP>:8081`
- API key: `caramel-local-key`
- Context size: `8192` (must match `-c`)

## Smoke tests

```bat
curl http://127.0.0.1:8081/health

curl http://127.0.0.1:8081/v1/chat/completions ^
  -H "Authorization: Bearer caramel-local-key" ^
  -H "Content-Type: application/json" ^
  -d "{\"messages\":[{\"role\":\"user\",\"content\":\"Hola\"}],\"stream\":false}"
```


## Notes

- Keep the PC on while chatting.
- Prefer Tailscale for remote access; do not expose the LLM port publicly.
- For more VRAM headroom use `Q4_K_S` / `IQ4_XS`; for quality with less margin try `Q5_K_M` (edit `MODEL_PATH` / `download-model.bat`).
