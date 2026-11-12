# CaramelAI

App Android de roleplay con personajes IA. El móvil habla con un **servidor LLM local** (llama.cpp) en tu PC por la red local.

```
[ Android CaramelAI ]  --HTTP-->  [ PC: llama-server :8081 ]  -->  [ GGUF Chaos-Unknown-12B ]
```

Repositorio pensado para clonar en otro PC, descargar modelo + binarios del servidor, compilar la app e indicar la IP del PC en Ajustes.

---

## Requisitos

### PC (servidor)

- Windows 10/11
- GPU NVIDIA con drivers recientes (recomendado; también puede ir en CPU, más lento)
- ~8 GB libres en disco para el GGUF Q4_K_M
- Python 3 + [Hugging Face CLI](https://huggingface.co/docs/huggingface_hub/guides/cli) para descargar el modelo:
  ```bat
  pip install -U "huggingface_hub[cli]"
  ```
- Build **CUDA** de [llama.cpp](https://github.com/ggml-org/llama.cpp/releases) (carpeta con `llama-server.exe` y DLLs)

### Móvil / emulador (app)

- [Android Studio](https://developer.android.com/studio) (Ladybug o superior)
- Dispositivo o emulador con **API 26+**
- Misma Wi‑Fi que el PC (o Tailscale / VPN), o `10.0.2.2` si usas emulador apuntando a `localhost` del PC

---

## 1. Clonar el repo

```bat
git clone https://github.com/Carame0005/AIChat.git
cd AIChat
```

(Ajusta la URL si el repo tiene otro nombre.)

---

## 2. Montar el servidor LLM (PC)

### 2.1 Binarios de llama.cpp

1. Descarga un release **Windows CUDA** de llama.cpp.
2. Copia en `server\` al menos:
   - `llama-server.exe`
   - las DLLs que vengan en el zip (`ggml-cuda.dll`, `cublas*.dll`, etc.)

### 2.2 Modelo GGUF

Desde `server\`:

```bat
download-model.bat
```

Eso deja el archivo en:

`server\models\Chaos-Unknown-12b.Q4_K_M.gguf`

### 2.3 Firewall

Permite **TCP 8081** entrante en el perfil **Privado** (solo red de confianza). No expongas el puerto a Internet.

### 2.4 Arrancar

Doble clic en:

- `Iniciar CaramelAI Server.bat` (raíz del repo), o  
- `server\Iniciar-Servidor.bat`

Deberías ver algo como: escuchando en `0.0.0.0:8081`.

Prueba en el PC:

```bat
curl http://127.0.0.1:8081/health
```

API key por defecto: `caramel-local-key`  
Contexto del servidor (`-c`): **8192** (debe coincidir con la app).

Más detalle: [`server/README.md`](server/README.md).

---

## 3. Montar la app Android

1. Abre la carpeta `CaramelAI\` en **Android Studio**.
2. Deja que Gradle sync descargue dependencias (crea `local.properties` solo en esa máquina; no se sube al repo).
3. Conecta el móvil con USB debugging o arranca un emulador.
4. Run ▶ `app`.

### Ajustes dentro de la app

| Ajuste | Valor típico |
|--------|----------------|
| Base URL | `http://<IP-LAN-del-PC>:8081` |
| API key | `caramel-local-key` |
| Context size | `8192` (igual que `-c` del servidor) |
| Max tokens | A tu gusto (p. ej. 512) |

**Cómo saber la IP del PC (Windows):**

```bat
ipconfig
```

Busca la IPv4 de Wi‑Fi / Ethernet (ej. `192.168.1.42`). En la app: `http://192.168.1.42:8081`.

**Emulador Android oficial:** usa `http://10.0.2.2:8081` para llegar al `localhost` del PC.

Pulsa **Probar conexión** en Ajustes si está disponible, o envía un mensaje de prueba.

---

## 4. Uso rápido

1. Arranca el servidor en el PC y déjalo abierto.
2. Abre CaramelAI en el móvil.
3. Crea o importa un personaje, elige persona (perfil) y chatea.
4. Botón enviar = mensaje nuevo; si el campo está vacío, el icono **Continuar** alarga la última respuesta del bot (útil si se cortó por tokens).

---

## Estructura del repo

```
AIChat/
├── README.md                 ← esta guía
├── Iniciar CaramelAI Server.bat
├── CaramelAI/                ← proyecto Android (Kotlin + Compose)
└── server/                   ← scripts + modelos (GGUF/binarios NO van en git)
    ├── start-server.bat
    ├── download-model.bat
    ├── Iniciar-Servidor.bat
    ├── README.md
    └── models/               ← vacío en git; download-model.bat lo rellena
```

---

## Notas

- El GGUF y `llama-server.exe` **no están en Git** (pesan mucho). Hay que descargarlos en cada PC.
- Con poca VRAM (p. ej. 4 GB) el 12B Q4 irá lento (parte en CPU); es normal.
- Para acceso fuera de casa, Tailscale (u otra VPN) es más seguro que abrir el puerto al público.
- Autor GitHub: [Carame0005](https://github.com/Carame0005)
