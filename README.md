# CaramelAI

Android roleplay app with AI characters. The phone talks to a **local LLM server** (llama.cpp) on your PC over the LAN.

```
[ Android CaramelAI ]  --HTTP-->  [ PC: llama-server router :8081 ]  -->  [ Q4 / Q5 GGUF ]
```

Clone on another PC, download the model + server binaries, build the app, and set the PC IP in Settings.

---

## Requirements

### PC (server)

- Windows 10/11
- NVIDIA GPU with recent drivers (recommended; CPU-only works but is slower)
- Disk space for the GGUF(s) you want (see [Minimum specs](#minimum-specs-q4--q5) below)
- Python 3 + [Hugging Face CLI](https://huggingface.co/docs/huggingface_hub/guides/cli) to download models:
  ```bat
  pip install -U "huggingface_hub[cli]"
  ```
- A **CUDA** build of [llama.cpp](https://github.com/ggml-org/llama.cpp/releases) (`llama-server.exe` + matching cudart DLLs)

### Phone / emulator (app)

- [Android Studio](https://developer.android.com/studio) (Ladybug or newer)
- Device or emulator with **API 26+**
- Same Wi‑Fi as the PC (or Tailscale / VPN), or `10.0.2.2` if the emulator should reach the PC’s `localhost`

---

## Minimum specs (Q4 / Q5)

Both quants are **Chaos Unknown 12B** ([mradermacher GGUF](https://huggingface.co/mradermacher/Chaos-Unknown-12b-GGUF)). App prompt budget defaults to **8192** tokens; the server runs **-c 12288** with **-np 2** (chat + summary slots, shared KV). Raise `-c` to **14336** in `start-server.bat` if you have spare VRAM and long-chat summaries still fail.

| | **Q4_K_M** | **Q5_K_M** |
|---|---|---|
| Download script | `server\download-model.bat` | `server\download-model-q5.bat` |
| File | `Chaos-Unknown-12b.Q4_K_M.gguf` (~7.5 GB) | `Chaos-Unknown-12b.Q5_K_M.gguf` (~8.7 GB) |
| **Minimum VRAM** (usable) | **6 GB** with partial offload; **8 GB+** comfortable | **10 GB** with partial offload; **12 GB** comfortable |
| **Recommended VRAM** | 8–12 GB (e.g. RTX 3060 12 GB laptop/desktop) | **12 GB** (e.g. RTX 3060 12 GB desktop) |
| **System RAM** | **16 GB** minimum (32 GB nicer if many layers stay on CPU) | **32 GB** recommended |
| **CPU** | Modern 6+ cores (works on weaker CPUs if GPU holds most layers) | Prefer a strong CPU if any layers stay on RAM (e.g. Ryzen 7) |
| **Good fit** | Laptops with ~8–12 GB VRAM; desktops wanting speed over max quality | Desktops with **12 GB VRAM** and plenty of RAM |
| **Tight fit / slow** | **4 GB VRAM** (e.g. RTX 3050 laptop): only some layers on GPU; expect slower tokens | Not recommended under ~10 GB VRAM |

Notes:

- Figures assume **one chat at a time**, app context **8192**, server `-c 12288` / `-np 2` (~0.6–1 GB extra VRAM vs 8192/np1 on Q4), and CUDA working.
- Q5 needs more VRAM/RAM than Q4 for the same context; if it barely fits, lower context in the app **and** server `-c`/`-np`, or stick to Q4.
- The app picks Q4/Q5 in Settings; `start-server.bat` runs in **router mode** and loads the requested GGUF.

---

## 1. Clone

```bat
git clone https://github.com/Carame005/AIChat.git
cd AIChat
```

(Adjust the URL if the repo name differs.)

---

## 2. Set up the LLM server (PC)

### 2.1 llama.cpp binaries

1. Download a **Windows CUDA** release of llama.cpp.
2. Copy into `server\` at least:
   - `llama-server.exe`
   - the DLLs from the zip (`ggml-cuda.dll`, `cublas*.dll`, etc.)
3. Use a **cudart** zip that matches the build **and** your driver:
   - Driver CUDA 12.x → `win-cuda-12.4` + `cudart-...-cuda-12.4`
   - Driver CUDA 13.x → `win-cuda-13.5` + `cudart-...-cuda-13.5`  
   Mixing CUDA 13 binaries with a CUDA 12-only driver → CPU-only (no VRAM use).

### 2.2 GGUF model(s)

From `server\`:

```bat
download-model.bat
download-model-q5.bat
```

- Q4 → `server\models\Chaos-Unknown-12b.Q4_K_M.gguf`
- Q5 → `server\models\Chaos-Unknown-12b.Q5_K_M.gguf`

`start-server.bat` starts in router mode; choose the quant in the app Settings.

### 2.3 Firewall

Allow inbound **TCP 8081** on the **Private** profile only. Do not expose the port to the public internet.

### 2.4 Start

Double-click:

- `Iniciar CaramelAI Server.bat` (repo root), or  
- `server\Iniciar-Servidor.bat`

You should see it listening on `0.0.0.0:8081`.

Quick check:

```bat
curl http://127.0.0.1:8081/health
```

Default API key: `caramel-local-key`  
Server context (`-c`): **12288** with `-np 2 --kv-unified` (app prompt budget still **8192**).

More detail: [`server/README.md`](server/README.md).

---

## 3. Set up the Android app

1. Open `CaramelAI\` in **Android Studio**.
2. Let Gradle sync (creates machine-local `local.properties`; not committed).
3. Connect a phone with USB debugging or start an emulator.
4. Run ▶ `app`.

App UI language defaults to **English**; switch to Spanish in Settings if you want.

### In-app settings

| Setting | Typical value |
|--------|----------------|
| Base URL | `http://<PC-LAN-IP>:8081` |
| API key | `caramel-local-key` |
| Model | Q4 or Q5 (must exist under `server\models\`) |
| Context size | `8192` (app budget; server `-c` is 12288) |
| Max tokens | Your choice (e.g. 512) |

**PC IP on Windows:**

```bat
ipconfig
```

Use the Wi‑Fi / Ethernet IPv4 (e.g. `192.168.1.42`) → `http://192.168.1.42:8081`.

**Official Android emulator:** use `http://10.0.2.2:8081` to reach the PC’s localhost.

Use **Test connection** in Settings, or send a test message.

---

## 4. Quick use

1. Start the server on the PC and leave it running.
2. Open CaramelAI on the phone.
3. Create or import a character, pick a persona, chat.
4. Send = new message; empty field + **Continue** skips your turn so the bot keeps talking in a new message (when a reply felt short or you want the scene to keep going).

---

## Repo layout

```
AIChat/
├── README.md
├── Iniciar CaramelAI Server.bat
├── CaramelAI/                ← Android app (Kotlin + Compose)
└── server/                   ← scripts + models (GGUF/binaries not in git)
    ├── start-server.bat
    ├── download-model.bat
    ├── download-model-q5.bat
    ├── Iniciar-Servidor.bat
    ├── README.md
    └── models/
```

---

## Notes

- GGUF files and `llama-server.exe` are **not** in Git (too large). Download them per machine.
- With little VRAM (e.g. 4 GB), Q4 will be slow (many layers on CPU). On a 12 GB desktop, try Q5 from Settings.
- For access away from home, Tailscale (or another VPN) is safer than opening the LLM port publicly.
- Author: [Carame005](https://github.com/Carame005)
