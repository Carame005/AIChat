@echo off
REM CaramelAI - llama-server launcher (router mode: Q4 / Q5 selectable from the app)
REM Model card: https://huggingface.co/Vortex5/Chaos-Unknown-12b
REM GGUF:       https://huggingface.co/mradermacher/Chaos-Unknown-12b-GGUF
REM
REM Prerequisites:
REM   1. Run download-model.bat and download-model-q5.bat as needed
REM   2. Put a CUDA Windows llama.cpp build in this folder, with MATCHING cudart zip:
REM      https://github.com/ggml-org/llama.cpp/releases
REM      Driver CUDA 12.x - nvidia-smi - use win-cuda-12.4 + matching cudart
REM      Driver CUDA 13.x - use win-cuda-13.5 + matching cudart
REM      Mixing CUDA 13 binaries with a CUDA 12-only driver = CPU-only.
REM   3. Allow inbound TCP 8081 on the Private firewall profile only

setlocal EnableExtensions
cd /d "%~dp0"

set "LLAMA_SERVER=%~dp0llama-server.exe"
set "MODELS_DIR=%~dp0models"
set "MODEL_Q4=%MODELS_DIR%\Chaos-Unknown-12b.Q4_K_M.gguf"
set "MODEL_Q5=%MODELS_DIR%\Chaos-Unknown-12b.Q5_K_M.gguf"
set "HOST=0.0.0.0"
REM 8080 is often taken on Windows; use 8081 by default.
set "PORT=8081"
REM auto = offload as many layers as fit in VRAM (99 often fails to 0 on 4 GB GPUs)
set "NGL=auto"
REM 12288: ~8k for chat + room for a background summary on a second parallel slot
REM Raise to 14336 if you have spare VRAM and summaries still fail under a full chat window
set "CTX=12288"
REM Two slots: chat (id_slot 0) and summary (id_slot 1); --kv-unified shares the pool
set "NP=2"
REM Keep less free VRAM margin so more layers land on GPU (default fit-target is 1024 MiB)
set "FIT_TARGET=128"
REM Only one quant loaded at a time; switching from the app unloads the other
set "MODELS_MAX=1"
set "API_KEY=caramel-local-key"

set "LOG_DIR=%~dp0logs"
set "LOG_FILE=%LOG_DIR%\llama-server.log"
set "DIAG_FILE=%LOG_DIR%\preflight.log"
REM 0 = quiet-ish; 1 = device/fit/offload; higher = very noisy
set "LOG_VERBOSITY=1"

if not exist "%LLAMA_SERVER%" (
    echo ERROR: llama-server.exe not found at:
    echo   %LLAMA_SERVER%
    echo Download a CUDA build from llama.cpp releases and place it here.
    pause
    exit /b 1
)

if not exist "%MODELS_DIR%" mkdir "%MODELS_DIR%"

set "HAS_MODEL="
if exist "%MODEL_Q4%" set "HAS_MODEL=1"
if exist "%MODEL_Q5%" set "HAS_MODEL=1"
if not defined HAS_MODEL (
    echo ERROR: No Chaos-Unknown GGUF found in:
    echo   %MODELS_DIR%
    echo Run download-model.bat for Q4, or download-model-q5.bat for Q5.
    pause
    exit /b 1
)

if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

echo ========================================
echo CaramelAI llama-server preflight
echo ========================================
echo Log file: %LOG_FILE%
echo Models dir: %MODELS_DIR%
echo.

REM Snapshot environment before start (helps debug CUDA/DLL mismatches)
> "%DIAG_FILE%" (
    echo ==== preflight %DATE% %TIME% ====
    echo cwd=%CD%
    echo LLAMA_SERVER=%LLAMA_SERVER%
    echo MODELS_DIR=%MODELS_DIR%
    echo NGL=%NGL% CTX=%CTX% NP=%NP% FIT_TARGET=%FIT_TARGET% MODELS_MAX=%MODELS_MAX%
    echo.
    echo ---- GGUF files ----
)
dir /b "%MODELS_DIR%\*.gguf" 2>nul >> "%DIAG_FILE%"
echo. >> "%DIAG_FILE%"
echo ---- CUDA / ggml DLLs present ---- >> "%DIAG_FILE%"
dir /b "%~dp0ggml-cuda.dll" "%~dp0cublas*.dll" "%~dp0cudart*.dll" "%~dp0nvcuda.dll" 2>nul >> "%DIAG_FILE%"
echo. >> "%DIAG_FILE%"
echo ---- nvidia-smi ---- >> "%DIAG_FILE%"
nvidia-smi >> "%DIAG_FILE%" 2>&1
echo. >> "%DIAG_FILE%"

REM Lightweight preflight (skip with SKIP_CUDA_DIAG=1 if PowerShell is blocked)
if not defined SKIP_CUDA_DIAG (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0diagnose-cuda.ps1" -ServerDir "%~dp0." -DiagFile "%DIAG_FILE%"
    if errorlevel 1 (
        echo.
        echo WARNING: CUDA preflight reported a problem. See:
        echo   %DIAG_FILE%
        echo Server will still start, but may fall back to CPU.
        echo.
    )
)

echo Starting llama-server ROUTER on http://%HOST%:%PORT%
echo Models dir: %MODELS_DIR%
if exist "%MODEL_Q4%" echo   - Chaos-Unknown-12b.Q4_K_M.gguf
if exist "%MODEL_Q5%" echo   - Chaos-Unknown-12b.Q5_K_M.gguf
echo Context: %CTX%  GPU layers: %NGL%  parallel: %NP%  fit-target: %FIT_TARGET% MiB  models-max: %MODELS_MAX%
echo KV: unified pool (--kv-unified). Extra VRAM vs -np 1 / -c 8192: roughly 0.6-1 GB on Q4.
echo API key: %API_KEY%
echo Chat template: Mistral Instruct via --jinja
echo Logging to: %LOG_FILE%  verbosity %LOG_VERBOSITY%
echo Preflight: %DIAG_FILE%
echo.
echo Models load when the app sends the first chat for that model id.
echo Tip: while running, check VRAM with nvidia-smi
echo.

if exist "%LOG_FILE%" (
    for /f %%i in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"') do set "STAMP=%%i"
    move /y "%LOG_FILE%" "%LOG_DIR%\llama-server-%STAMP%.log" >nul 2>nul
)

REM Free the port if a previous instance is stuck
for /f "tokens=5" %%p in ('netstat -ano ^| findstr /R /C:":%PORT% .*LISTENING"') do (
    echo Port %PORT% in use by PID %%p - stopping it...
    taskkill /F /PID %%p >nul 2>&1
)

REM No -m: router mode. Autoload on request (needed or chat returns "model is not loaded").
"%LLAMA_SERVER%" ^
  --models-dir "%MODELS_DIR%" ^
  --models-max %MODELS_MAX% ^
  --host %HOST% ^
  --port %PORT% ^
  -ngl %NGL% ^
  -c %CTX% ^
  -np %NP% ^
  --kv-unified ^
  -fit on ^
  -fitt %FIT_TARGET% ^
  --jinja ^
  --cache-reuse 256 ^
  --api-key %API_KEY% ^
  --log-file "%LOG_FILE%" ^
  --log-verbosity %LOG_VERBOSITY% ^
  --log-prefix ^
  --log-timestamps

set "EXITCODE=%ERRORLEVEL%"
echo.
echo llama-server exited with code %EXITCODE%
echo Last log: %LOG_FILE%
if exist "%LOG_FILE%" (
    echo ---- last log lines ----
    powershell -NoProfile -Command "Get-Content -LiteralPath '%LOG_FILE%' -Tail 40 -ErrorAction SilentlyContinue"
)
echo.
pause
exit /b %EXITCODE%
endlocal
