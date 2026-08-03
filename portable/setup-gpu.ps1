[CmdletBinding()]
param(
    [switch]$SkipModel,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$nativeDirectory = Join-Path $root 'native\windows-x64'
$modelDirectory = Join-Path $root 'models\language'
$configDirectory = Join-Path $root 'config'
$modelName = 'Qwen3-14B-Q4_K_M.gguf'
$modelPath = Join-Path $modelDirectory $modelName
$modelExpectedBytes = 9001752960
$release = 'b10152'
$cudaArchive = "llama-$release-bin-win-cuda-12.4-x64.zip"
$cudaRuntimeArchive = 'cudart-llama-bin-win-cuda-12.4-x64.zip'
$releaseBase = "https://github.com/ggml-org/llama.cpp/releases/download/$release"
$modelUrl = "https://huggingface.co/Qwen/Qwen3-14B-GGUF/resolve/main/${modelName}?download=true"
$workDirectory = Join-Path ([System.IO.Path]::GetTempPath()) ("personal-assistant-gpu-" + [guid]::NewGuid())

function Download-File([string]$Url, [string]$Destination) {
    Write-Host "Downloading $Url"
    Invoke-WebRequest -Uri $Url -OutFile $Destination -UseBasicParsing
}

function Set-Property([string]$Path, [string]$Name, [string]$Value) {
    $lines = if (Test-Path -LiteralPath $Path) {
        @(Get-Content -LiteralPath $Path)
    } else {
        @()
    }
    $found = $false
    $updated = foreach ($line in $lines) {
        if ($line -match ('^\s*' + [regex]::Escape($Name) + '\s*=')) {
            $found = $true
            "$Name=$Value"
        } else {
            $line
        }
    }
    if (-not $found) {
        $updated += "$Name=$Value"
    }
    Set-Content -LiteralPath $Path -Value $updated -Encoding ASCII
}

try {
    if (-not (Get-Command nvidia-smi.exe -ErrorAction SilentlyContinue)) {
        throw 'nvidia-smi was not found. Install/update the NVIDIA Windows driver first.'
    }

    New-Item -ItemType Directory -Force -Path $workDirectory, $nativeDirectory, $modelDirectory, $configDirectory | Out-Null
    $cudaZip = Join-Path $workDirectory $cudaArchive
    $runtimeZip = Join-Path $workDirectory $cudaRuntimeArchive
    Download-File "$releaseBase/$cudaArchive" $cudaZip
    Download-File "$releaseBase/$cudaRuntimeArchive" $runtimeZip

    $cudaExtract = Join-Path $workDirectory 'cuda'
    $runtimeExtract = Join-Path $workDirectory 'cudart'
    Expand-Archive -LiteralPath $cudaZip -DestinationPath $cudaExtract
    Expand-Archive -LiteralPath $runtimeZip -DestinationPath $runtimeExtract
    Get-ChildItem -LiteralPath $cudaExtract -Recurse -File |
        Copy-Item -Destination $nativeDirectory -Force
    Get-ChildItem -LiteralPath $runtimeExtract -Recurse -File |
        Copy-Item -Destination $nativeDirectory -Force

    $modelComplete = (Test-Path -LiteralPath $modelPath) -and
        ((Get-Item -LiteralPath $modelPath).Length -eq $modelExpectedBytes)
    if (-not $SkipModel -and ($Force -or -not $modelComplete)) {
        $temporaryModel = Join-Path $workDirectory $modelName
        Download-File $modelUrl $temporaryModel
        $downloadedBytes = (Get-Item -LiteralPath $temporaryModel).Length
        if ($downloadedBytes -ne $modelExpectedBytes) {
            throw "Incomplete model download: expected $modelExpectedBytes bytes, received $downloadedBytes bytes."
        }
        Move-Item -LiteralPath $temporaryModel -Destination $modelPath -Force
    } elseif ($SkipModel) {
        Write-Host 'Skipping Qwen3-14B model download.'
    } else {
        Write-Host "$modelName is already installed; keeping the existing file."
    }

    $inference = Join-Path $configDirectory 'inference.properties'
    Set-Property $inference 'contextSize' '8192'
    Set-Property $inference 'gpuLayers' '99'
    Set-Property $inference 'flashAttention' 'on'

    if ((Test-Path -LiteralPath $modelPath) -and
            ((Get-Item -LiteralPath $modelPath).Length -eq $modelExpectedBytes)) {
        Set-Property (Join-Path $configDirectory 'model-selection.properties') 'reasoningModel' $modelName
    }

    Write-Host ''
    Write-Host 'GPU setup complete. Start the assistant normally and inspect logs\llama-server.log.'
} finally {
    if (Test-Path -LiteralPath $workDirectory) {
        Remove-Item -LiteralPath $workDirectory -Recurse -Force
    }
}
