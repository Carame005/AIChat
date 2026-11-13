@echo off
REM CaramelAI - llama-server launcher (router mode: Q4 / Q5 selectable from the app)
REM Model card: https://huggingface.co/Vortex5/Chaos-Unknown-12b
REM GGUF:       https://huggingface.co/mradermacher/Chaos-Unknown-12b-GGUF
REM
REM Prerequisites:
REM   1. Run download-model.bat and/or download-model-q5.bat
REM   2. Put a CUDA Windows llama.cpp build in this folder, with MATCHING cudart zip:
REM      https://github.com/ggml-org/llama.cpp/releases
REM      Driver CUDA 12.x (nvidia-smi) -> win-cuda-12.4 + cudart-...-cuda-12.4
REM      Driver CUDA 13.x              -> win-cuda-13.5 + cudart-...-cuda-13.5
REM      Mixing 13 binaries with 12 DLLs (or a driver that only supports 12) = CPU-only.
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
set "CTX=8192"
REM One chat slot; default parallel multiplies KV and wastes VRAM
set "NP=1"
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
    echo Run download-model.bat (Q4) and/or download-model-q5.bat (Q5).
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

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0diagnose-cuda.ps1" -ServerDir "%~dp0." -DiagFile "%DIAG_FILE%"
if errorlevel 1 (
    echo.
    echo WARNING: CUDA preflight reported a problem. See:
    echo   %DIAG_FILE%
    echo Server will still start, but may fall back to CPU.
    echo.
)

echo Starting llama-server ROUTER on http://%HOST%:%PORT%
echo Models dir: %MODELS_DIR%
if exist "%MODEL_Q4%" echo   - Chaos-Unknown-12b.Q4_K_M.gguf
if exist "%MODEL_Q5%" echo   - Chaos-Unknown-12b.Q5_K_M.gguf
echo Context: %CTX%  GPU layers: %NGL%  parallel: %NP%  fit-target: %FIT_TARGET% MiB  models-max: %MODELS_MAX%
echo API key: %API_KEY%
echo Chat template: Mistral Instruct via --jinja
echo Logging to: %LOG_FILE%  (verbosity %LOG_VERBOSITY%)
echo Preflight: %DIAG_FILE%
echo.
echo The app picks the model via the OpenAI "model" field /v1/chat/completions
echo Tip: while running, check VRAM with: nvidia-smi
echo.

if exist "%LOG_FILE%" (
    for /f %%i in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"') do set "STAMP=%%i"
    move /y "%LOG_FILE%" "%LOG_DIR%\llama-server-%STAMP%.log" >nul
)

REM No -m: router mode. Instances inherit -ngl/-c/-np/-fit from here.
"%LLAMA_SERVER%" ^
  --models-dir "%MODELS_DIR%" ^
  --models-max %MODELS_MAX% ^
  --host %HOST% ^
  --port %PORT% ^
  -ngl %NGL% ^
  -c %CTX% ^
  -np %NP% ^
  -fit on ^
  -fitt %FIT_TARGET% ^
  --jinja ^
  --api-key %API_KEY% ^
  --log-file "%LOG_FILE%" ^
  --log-verbosity %LOG_VERBOSITY% ^
  --log-prefix ^
  --log-timestamps

echo.
echo llama-server exited with code %ERRORLEVEL%
echo Last log: %LOG_FILE%
pause
endlocal
