# CaramelAI — CUDA / VRAM preflight for llama-server
param(
    [Parameter(Mandatory = $true)][string]$ServerDir,
    [Parameter(Mandatory = $true)][string]$DiagFile
)

$ErrorActionPreference = "Continue"
$ServerDir = (Resolve-Path $ServerDir).Path
$exitCode = 0

function Add-Diag([string]$line) {
    Add-Content -Path $DiagFile -Value $line
    Write-Host $line
}

Add-Diag ""
Add-Diag "---- diagnose-cuda.ps1 ----"

$ggmlCuda = Join-Path $ServerDir "ggml-cuda.dll"
if (-not (Test-Path $ggmlCuda)) {
    Add-Diag "ERROR: ggml-cuda.dll missing — this build cannot use NVIDIA GPU."
    exit 1
}

# Which cuBLAS major does ggml-cuda.dll import?
$expectedMajor = $null
try {
    $pefileOk = $false
    try {
        python -c "import pefile" 2>$null
        if ($LASTEXITCODE -eq 0) { $pefileOk = $true }
    } catch { $pefileOk = $false }

    if ($pefileOk) {
        $imports = python -c @"
import pefile
pe = pefile.PE(r'$($ggmlCuda.Replace('\','\\'))')
for e in pe.DIRECTORY_ENTRY_IMPORT:
    print(e.dll.decode(errors='ignore'))
"@
        Add-Diag "ggml-cuda.dll imports:"
        foreach ($dll in ($imports -split "`r?`n")) {
            if ($dll) { Add-Diag "  $dll" }
            if ($dll -match 'cublas64_(\d+)\.dll') { $expectedMajor = [int]$Matches[1] }
        }
    } else {
        # Fallback: strings scan without pefile
        $bytes = [System.IO.File]::ReadAllBytes($ggmlCuda)
        $text = [System.Text.Encoding]::ASCII.GetString($bytes)
        foreach ($m in [regex]::Matches($text, 'cublas(?:Lt)?64_(\d+)\.dll')) {
            $expectedMajor = [int]$m.Groups[1].Value
            Add-Diag "ggml-cuda.dll references: $($m.Value)"
        }
    }
} catch {
    Add-Diag "WARN: could not parse ggml-cuda imports: $($_.Exception.Message)"
}

if ($null -ne $expectedMajor) {
    $cublas = Join-Path $ServerDir "cublas64_$expectedMajor.dll"
    $cublasLt = Join-Path $ServerDir "cublasLt64_$expectedMajor.dll"
    $cudart = Join-Path $ServerDir "cudart64_$expectedMajor.dll"
    Add-Diag "Expected CUDA major from ggml-cuda: $expectedMajor"
    Add-Diag ("  cublas64_{0}.dll : {1}" -f $expectedMajor, (Test-Path $cublas))
    Add-Diag ("  cublasLt64_{0}.dll: {1}" -f $expectedMajor, (Test-Path $cublasLt))
    Add-Diag ("  cudart64_{0}.dll  : {1}" -f $expectedMajor, (Test-Path $cudart))
    if (-not (Test-Path $cublas) -or -not (Test-Path $cublasLt)) {
        Add-Diag "ERROR: Missing cuBLAS $expectedMajor DLLs. Download matching cudart-llama-bin-win-cuda-$expectedMajor* zip from llama.cpp releases."
        $exitCode = 1
    }
} else {
    Add-Diag "WARN: could not detect required cublas major from ggml-cuda.dll"
}

# Driver CUDA capability (nvidia-smi header)
try {
    $smi = nvidia-smi 2>&1 | Out-String
    if ($smi -match 'CUDA Version:\s*([\d.]+)') {
        $driverCuda = [version]$Matches[1]
        Add-Diag "Driver reports CUDA Version: $driverCuda"
        if ($null -ne $expectedMajor -and $driverCuda.Major -lt $expectedMajor) {
            Add-Diag "ERROR: Driver CUDA $($driverCuda.Major).x < build CUDA $expectedMajor. Use win-cuda-$($driverCuda.Major)* build (or update the NVIDIA driver)."
            $exitCode = 1
        }
    }
} catch {
    Add-Diag "WARN: nvidia-smi failed: $($_.Exception.Message)"
    $exitCode = 1
}

# Try LoadLibrary on ggml-cuda (catches missing deps before llama starts)
try {
    Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class LlamaCudaLoad {
  [DllImport("kernel32", SetLastError=true, CharSet=CharSet.Unicode)]
  public static extern bool SetDllDirectory(string lpPathName);
  [DllImport("kernel32", SetLastError=true, CharSet=CharSet.Unicode)]
  public static extern IntPtr LoadLibrary(string lpFileName);
  [DllImport("kernel32", SetLastError=true)]
  public static extern bool FreeLibrary(IntPtr hModule);
  [DllImport("kernel32")]
  public static extern uint GetLastError();
}
"@ -ErrorAction SilentlyContinue
    [LlamaCudaLoad]::SetDllDirectory($ServerDir) | Out-Null
    $h = [LlamaCudaLoad]::LoadLibrary($ggmlCuda)
    if ($h -eq [IntPtr]::Zero) {
        $err = [LlamaCudaLoad]::GetLastError()
        Add-Diag "ERROR: LoadLibrary(ggml-cuda.dll) failed (Win32=$err). Usually missing/mismatched cublas/cudart."
        $exitCode = 1
    } else {
        Add-Diag "LoadLibrary(ggml-cuda.dll): OK"
        [LlamaCudaLoad]::FreeLibrary($h) | Out-Null
    }
} catch {
    Add-Diag "WARN: LoadLibrary probe failed: $($_.Exception.Message)"
}

# VRAM snapshot
try {
    $csv = nvidia-smi --query-gpu=name,memory.total,memory.used,memory.free --format=csv,noheader 2>&1
    Add-Diag "GPU: $csv"
} catch {}

Add-Diag "---- end diagnose-cuda ----"
Add-Diag ""
exit $exitCode
