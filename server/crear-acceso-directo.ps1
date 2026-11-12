# Crea un acceso directo en el Escritorio para arrancar el servidor con doble clic.
$ErrorActionPreference = "Stop"

$serverDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$target = Join-Path $serverDir "Iniciar-Servidor.bat"
$desktop = [Environment]::GetFolderPath("Desktop")
$shortcutPath = Join-Path $desktop "CaramelAI Server.lnk"
$iconCandidate = Join-Path $serverDir "llama-server.exe"

$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut($shortcutPath)
$shortcut.TargetPath = $target
$shortcut.WorkingDirectory = $serverDir
$shortcut.WindowStyle = 1
$shortcut.Description = "Inicia el servidor LLM de CaramelAI"
if (Test-Path $iconCandidate) {
    $shortcut.IconLocation = "$iconCandidate,0"
}
$shortcut.Save()

Write-Host "Acceso directo creado en:"
Write-Host "  $shortcutPath"
Write-Host ""
Write-Host "Puedes fijarlo a la barra de tareas o al menu Inicio."
