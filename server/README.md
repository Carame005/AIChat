# CaramelAI — local LLM server

Android talks to **llama-server** (OpenAI-compatible HTTP) on your PC in **router mode**: the app picks which GGUF to load via the `model` field.

## Models

- Card: [Vortex5/Chaos-Unknown-12b](https://huggingface.co/Vortex5/Chaos-Unknown-12b) (Mistral 12B merge)
- GGUF: [mradermacher/Chaos-Unknown-12b-GGUF](https://huggingface.co/mradermacher/Chaos-Unknown-12b-GGUF)
- Quants in the app:
  - **Q4_K_M** — `download-model.bat`
  - **Q5_K_M** — `download-model-q5.bat`
- Chat template: Mistral Instruct; `--jinja` uses the template embedded in the GGUF

## Minimum specs (Q4 / Q5)

Default context in this repo: **8192**, one parallel slot.

| | **Q4_K_M** (~7.5 GB) | **Q5_K_M** (~8.7 GB) |
|---|---|---|
| Minimum usable VRAM | ~6 GB (partial offload) | ~10 GB (partial offload) |
| Comfortable VRAM | 8–12 GB | **12 GB** |
| System RAM | 16 GB min (32 GB better if CPU holds layers) | **32 GB** recommended |
| Example machines | Laptop 8–12 GB VRAM; 3050 4 GB = slow hybrid | Desktop RTX 3060 12 GB + strong CPU |

CUDA must actually initialize (matching Windows CUDA build + cudart + driver). Otherwise everything stays on CPU/RAM.

## Setup

1. Install the Hugging Face CLI if needed:
   ```bat
   pip install -U "huggingface_hub[cli]"
   ```
2. Download one or both models:
   ```bat
   download-model.bat
   download-model-q5.bat
   ```
3. Put a **CUDA** Windows llama.cpp build here with matching **cudart** DLLs  
   ([releases](https://github.com/ggml-org/llama.cpp/releases)):
   - Driver CUDA 12.x → `win-cuda-12.4` + `cudart-...-cuda-12.4`
   - Driver CUDA 13.x → `win-cuda-13.5` + `cudart-...-cuda-13.5`
4. Allow inbound TCP **8081** on the **Private** firewall profile only.
5. Start:
   ```bat
   start-server.bat
   ```

Default API key: `caramel-local-key`  
Listen: `0.0.0.0:8081`

Logs: `logs\preflight.log`, `logs\llama-server.log`

## App settings

- Base URL: `http://<PC-LAN-IP>:8081`
- API key: `caramel-local-key`
- Model: Q4 / Q5 in Settings (must exist under `models\`)
- Context size: `8192` (must match `-c`)

## Smoke tests

```bat
curl http://127.0.0.1:8081/health

curl http://127.0.0.1:8081/v1/models ^
  -H "Authorization: Bearer caramel-local-key"

curl http://127.0.0.1:8081/v1/chat/completions ^
  -H "Authorization: Bearer caramel-local-key" ^
  -H "Content-Type: application/json" ^
  -d "{\"model\":\"Chaos-Unknown-12b.Q4_K_M.gguf\",\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}],\"stream\":false}"
```

## Notes

- Keep the PC on while chatting.
- Prefer Tailscale for remote access; do not expose the LLM port publicly.
- `start-server.bat` uses `--models-max 1`: switching model in the app unloads the other quant.
- First reply after a model switch can take longer while the GGUF loads.
