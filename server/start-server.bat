@echo off
REM CaramelAI - llama-server launcher for Chaos-Unknown-12B
REM Model card: https://huggingface.co/Vortex5/Chaos-Unknown-12b
REM GGUF:       https://huggingface.co/mradermacher/Chaos-Unknown-12b-GGUF
REM
REM Prerequisites:
REM   1. Run download-model.bat  (or place the GGUF under models\)
REM   2. Put a CUDA Windows llama-server.exe in this folder
REM      https://github.com/ggml-org/llama.cpp/releases
REM   3. Allow inbound TCP 8080 on the Private firewall profile only

setlocal
cd /d "%~dp0"

set "LLAMA_SERVER=%~dp0llama-server.exe"
set "MODEL_PATH=%~dp0models\Chaos-Unknown-12b.Q4_K_M.gguf"
set "HOST=0.0.0.0"
REM 8080 is often taken on Windows; use 8081 by default.
set "PORT=8081"
set "NGL=99"
set "CTX=8192"
set "API_KEY=caramel-local-key"

if not exist "%LLAMA_SERVER%" (
    echo ERROR: llama-server.exe not found at:
    echo   %LLAMA_SERVER%
    echo Download a CUDA build from llama.cpp releases and place it here.
    pause
    exit /b 1
)

if not exist "%MODEL_PATH%" (
    echo ERROR: Model GGUF not found at:
    echo   %MODEL_PATH%
    echo Run download-model.bat first.
    pause
    exit /b 1
)

echo Starting llama-server on http://%HOST%:%PORT%
echo Model: %MODEL_PATH%
echo Context: %CTX%  GPU layers: %NGL%
echo API key: %API_KEY%
echo Chat template: Mistral Instruct via --jinja
echo.

"%LLAMA_SERVER%" ^
  -m "%MODEL_PATH%" ^
  --host %HOST% ^
  --port %PORT% ^
  -ngl %NGL% ^
  -c %CTX% ^
  --jinja ^
  --api-key %API_KEY%

endlocal
