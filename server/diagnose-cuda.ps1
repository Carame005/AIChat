# CaramelAI — lightweight CUDA preflight (no LoadLibrary of ggml-cuda.dll)
param(
    [Parameter(Mandatory = $true)][string]$ServerDir,
    [Parameter(Mandatory = $true)][string]$DiagFile
)

$ErrorActionPreference = "Continue"
$ServerDir = (Resolve-Path -LiteralPath $ServerDir).Path
$exitCode = 0

function Add-Diag([string]$line) {
    Add-Content -LiteralPath $DiagFile -Value $line -Encoding utf8
    Write-Host $line
}

Add-Diag ""
Add-Diag "---- diagnose-cuda.ps1 ----"

$ggmlCuda = Join-Path $ServerDir "ggml-cuda.dll"
if (-not (Test-Path -LiteralPath $ggmlCuda)) {
    Add-Diag "ERROR: ggml-cuda.dll missing — this build cannot use NVIDIA GPU."
    exit 1
}

$sizeMb = [math]::Round((Get-Item -LiteralPath $ggmlCuda).Length / 1MB, 1)
Add-Diag "ggml-cuda.dll present ($sizeMb MiB)"

# Detect required cuBLAS major from PE imports (fast_load — never map the whole 600MB+ DLL)
$expectedMajor = $null
try {
    $py = @"
import pefile
pe = pefile.PE(r'$($ggmlCuda.Replace('\','\\'))', fast_load=True)
pe.parse_data_directories(directories=[pefile.DIRECTORY_ENTRY['IMAGE_DIRECTORY_ENTRY_IMPORT']])
for e in pe.DIRECTORY_ENTRY_IMPORT:
    print(e.dll.decode(errors='ignore'))
"@
    $imports = & python -c $py 2>$null
    if ($LASTEXITCODE -eq 0 -and $imports) {
        Add-Diag "ggml-cuda.dll imports:"
        foreach ($dll in ($imports -split "`r?`n")) {
            if ($dll) { Add-Diag "  $dll" }
            if ($dll -match 'cublas64_(\d+)\.dll') { $expectedMajor = [int]$Matches[1] }
        }
    }
} catch {
    Add-Diag "WARN: pefile import parse failed: $($_.Exception.Message)"
}
if ($null -eq $expectedMajor) {
    if (Test-Path -LiteralPath (Join-Path $ServerDir "cublas64_12.dll")) { $expectedMajor = 12 }
    elseif (Test-Path -LiteralPath (Join-Path $ServerDir "cublas64_13.dll")) { $expectedMajor = 13 }
    if ($null -ne $expectedMajor) {
        Add-Diag "Assuming CUDA $expectedMajor from cublas DLLs present on disk"
    }
}

if ($null -ne $expectedMajor) {
    $cublas = Join-Path $ServerDir "cublas64_$expectedMajor.dll"
    $cublasLt = Join-Path $ServerDir "cublasLt64_$expectedMajor.dll"
    $cudart = Join-Path $ServerDir "cudart64_$expectedMajor.dll"
    Add-Diag "Expected CUDA major from ggml-cuda: $expectedMajor"
    Add-Diag ("  cublas64_{0}.dll : {1}" -f $expectedMajor, (Test-Path -LiteralPath $cublas))
    Add-Diag ("  cublasLt64_{0}.dll: {1}" -f $expectedMajor, (Test-Path -LiteralPath $cublasLt))
    Add-Diag ("  cudart64_{0}.dll  : {1}" -f $expectedMajor, (Test-Path -LiteralPath $cudart))
    if (-not (Test-Path -LiteralPath $cublas) -or -not (Test-Path -LiteralPath $cublasLt)) {
        Add-Diag "ERROR: Missing cuBLAS $expectedMajor DLLs. Download matching cudart-llama-bin-win-cuda-$expectedMajor* zip from llama.cpp releases."
        $exitCode = 1
    }
} else {
    Add-Diag "WARN: could not detect required cublas major from ggml-cuda.dll"
}

try {
    $smi = & nvidia-smi 2>&1 | Out-String
    if ($smi -match 'CUDA Version:\s*([\d.]+)') {
        $driverCuda = [version]$Matches[1]
        Add-Diag "Driver reports CUDA Version: $driverCuda"
        if ($null -ne $expectedMajor -and $driverCuda.Major -lt $expectedMajor) {
            Add-Diag "ERROR: Driver CUDA $($driverCuda.Major).x < build CUDA $expectedMajor. Use win-cuda-$($driverCuda.Major)* build (or update the NVIDIA driver)."
            $exitCode = 1
        }
    }
    $csv = & nvidia-smi --query-gpu=name,memory.total,memory.used,memory.free --format=csv,noheader 2>&1
    Add-Diag "GPU: $csv"
} catch {
    Add-Diag "WARN: nvidia-smi failed: $($_.Exception.Message)"
    $exitCode = 1
}

Add-Diag "---- end diagnose-cuda ----"
Add-Diag ""
exit $exitCode
