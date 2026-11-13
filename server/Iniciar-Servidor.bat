@echo off
title CaramelAI Server
cd /d "%~dp0"

echo ========================================
echo   CaramelAI - Iniciando servidor LLM
echo ========================================
echo.

call "%~dp0start-server.bat"
set "EXITCODE=%ERRORLEVEL%"

echo.
if not "%EXITCODE%"=="0" (
    echo El servidor no arranco correctamente. Codigo: %EXITCODE%
    echo Revisa: %~dp0logs\llama-server.log
    echo          %~dp0logs\preflight.log
) else (
    echo Servidor detenido.
)
echo.
REM start-server.bat already pauses; only pause again on early failure before that
exit /b %EXITCODE%
