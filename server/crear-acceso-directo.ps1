# Crea un acceso directo en el Escritorio para arrancar el servidor con doble clic.
# Usa cmd.exe /k para que la ventana no se cierre si hay un error.
$ErrorActionPreference = "Stop"

$serverDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$bat = Join-Path $serverDir "Iniciar-Servidor.bat"
$desktop = [Environment]::GetFolderPath("Desktop")
$shortcutPath = Join-Path $desktop "CaramelAI Server.lnk"
$iconCandidate = Join-Path $serverDir "llama-server.exe"

$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut($shortcutPath)
$shortcut.TargetPath = "$env:ComSpec"
$shortcut.Arguments = "/k `"$bat`""
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
