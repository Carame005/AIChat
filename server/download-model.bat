@echo off
REM Downloads Chaos-Unknown-12B Q4_K_M GGUF for llama-server (RTX 3060 12GB).
REM Source model: https://huggingface.co/Vortex5/Chaos-Unknown-12b
REM GGUF quant:   https://huggingface.co/mradermacher/Chaos-Unknown-12b-GGUF

setlocal
cd /d "%~dp0"
if not exist "models" mkdir models

where hf >nul 2>&1
if errorlevel 1 (
    echo Hugging Face CLI "hf" not found.
    echo Install with:  pip install -U "huggingface_hub[cli]"
    echo Then re-run this script.
    exit /b 1
)

echo Downloading Chaos-Unknown-12b.Q4_K_M.gguf into server\models ...
echo This is several GB; resume is supported if interrupted.
echo.

hf download mradermacher/Chaos-Unknown-12b-GGUF Chaos-Unknown-12b.Q4_K_M.gguf --local-dir models

if errorlevel 1 (
    echo Download failed.
    exit /b 1
)

echo.
echo Done. Model path:
echo   %~dp0models\Chaos-Unknown-12b.Q4_K_M.gguf
echo Run start-server.bat next (after placing llama-server.exe here).
endlocal
