$ErrorActionPreference = 'Stop'
$appRoot = $PSScriptRoot
$pidFile = Join-Path $appRoot 'data\personal-assistant.pid'
$jar = (Join-Path $appRoot 'personal-assistant.jar').ToLowerInvariant()
$stoppedJava = $false

if (Test-Path -LiteralPath $pidFile) {
    $savedPid = [int](Get-Content -LiteralPath $pidFile -Raw)
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$savedPid" -ErrorAction SilentlyContinue
    if ($process -and $process.Name -in @('java.exe', 'javaw.exe') -and
        $process.CommandLine -like '*personal-assistant.jar*') {
        Stop-Process -Id $savedPid -Force -ErrorAction SilentlyContinue
        Write-Host "Stopped Personal Assistant Java process (PID $savedPid)."
        $stoppedJava = $true
    } elseif ($process) {
        Write-Warning "PID $savedPid belongs to another program; it was not stopped."
    }
    Remove-Item -LiteralPath $pidFile -Force
}

# Also handles a foreground app being stopped from a second command window.
$foregroundApps = Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" `
    -ErrorAction SilentlyContinue | Where-Object {
        $_.CommandLine -and $_.CommandLine.ToLowerInvariant().Contains($jar)
    }
foreach ($app in $foregroundApps) {
    Stop-Process -Id $app.ProcessId -Force -ErrorAction SilentlyContinue
    Write-Host "Stopped Personal Assistant Java process (PID $($app.ProcessId))."
    $stoppedJava = $true
}

$nativeRoot = (Join-Path $appRoot 'native\windows-x64').ToLowerInvariant()
$servers = Get-Process 'llama-server' -ErrorAction SilentlyContinue | Where-Object {
    $_.Path -and $_.Path.ToLowerInvariant().StartsWith($nativeRoot)
}
foreach ($server in $servers) {
    Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue
    Write-Host "Stopped local AI engine (PID $($server.Id))."
}

if (-not $servers -and -not $stoppedJava) {
    Write-Host 'Personal Assistant is stopped.'
}
