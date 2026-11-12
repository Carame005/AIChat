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
) else (
    echo Servidor detenido.
)
echo.
pause
exit /b %EXITCODE%
