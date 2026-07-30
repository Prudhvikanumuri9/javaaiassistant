param(
    [Parameter(Position = 0)]
    [ValidatePattern('^\d{1,3}(\.\d{1,3}){3}$')]
    [string]$LanIp,
    [switch]$Background
)

$ErrorActionPreference = 'Stop'
$appRoot = $PSScriptRoot
$settingsFile = Join-Path $appRoot 'config\https.properties'
$keyStore = Join-Path $appRoot 'config\https.p12'
$jar = Join-Path $appRoot 'personal-assistant.jar'
$pidFile = Join-Path $appRoot 'data\personal-assistant.pid'
$outputLog = Join-Path $appRoot 'logs\personal-assistant-console.log'
$errorLog = Join-Path $appRoot 'logs\personal-assistant-error.log'
$sourceRoot = [System.IO.Path]::GetFullPath((Join-Path $appRoot '..\..'))
$persistentConfig = Join-Path $sourceRoot 'portable\config'
$isSourceBuild = (Test-Path -LiteralPath (Join-Path $sourceRoot 'pom.xml')) -and
    (Test-Path -LiteralPath (Join-Path $sourceRoot 'portable'))
$httpsFiles = @('https.properties', 'https.p12', 'local-ca.p12', 'personal-assistant-ca.cer')

# target/ is disposable. Restore private HTTPS state from portable/config after mvn clean.
if ($isSourceBuild -and -not (Test-Path -LiteralPath $settingsFile)) {
    foreach ($name in $httpsFiles) {
        $saved = Join-Path $persistentConfig $name
        if (Test-Path -LiteralPath $saved) {
            Copy-Item -LiteralPath $saved -Destination (Join-Path $appRoot 'config') -Force
        }
    }
}

if (-not (Test-Path -LiteralPath $settingsFile) -or -not (Test-Path -LiteralPath $keyStore)) {
    if ([string]::IsNullOrWhiteSpace($LanIp)) {
        throw 'HTTPS is not configured. Run: start-assistant.cmd YOUR_PC_IP'
    }
    & (Join-Path $appRoot 'setup-https.ps1') -LanIp $LanIp
}

# Keep the one-time certificate and PIN outside target so clean builds cannot erase them.
if ($isSourceBuild) {
    New-Item -ItemType Directory -Path $persistentConfig -Force | Out-Null
    foreach ($name in $httpsFiles) {
        $current = Join-Path (Join-Path $appRoot 'config') $name
        if (Test-Path -LiteralPath $current) {
            Copy-Item -LiteralPath $current -Destination $persistentConfig -Force
        }
    }
}
if (-not (Test-Path -LiteralPath $jar)) {
    throw "Application JAR not found: $jar"
}

if (Test-Path -LiteralPath $pidFile) {
    $savedPid = [int](Get-Content -LiteralPath $pidFile -Raw)
    $running = Get-Process -Id $savedPid -ErrorAction SilentlyContinue
    if ($running) {
        Write-Host "Personal Assistant is already running (PID $savedPid)."
        exit 0
    }
    Remove-Item -LiteralPath $pidFile -Force
}

$settings = @{}
foreach ($line in Get-Content -LiteralPath $settingsFile) {
    if ($line -match '^([^=]+)=(.*)$') { $settings[$matches[1]] = $matches[2] }
}
if ([string]::IsNullOrWhiteSpace($settings['PA_ACCESS_PIN'])) {
    throw 'Access PIN is missing. Run setup-https.cmd again.'
}

New-Item -ItemType Directory -Force -Path (Join-Path $appRoot 'data'), (Join-Path $appRoot 'logs') | Out-Null
$env:PA_HTTPS = 'true'
$env:PA_SERVER_ADDRESS = '0.0.0.0'
$env:PA_KEYSTORE = $keyStore
$env:PA_KEYSTORE_PASSWORD = $settings['PA_KEYSTORE_PASSWORD']
$env:PA_ACCESS_PIN = $settings['PA_ACCESS_PIN']

Write-Host "URL: https://$($settings['PA_LAN_IP']):8787"
Write-Host 'Username: home'
Write-Host "Access PIN: $($settings['PA_ACCESS_PIN'])"

if ($Background) {
    $process = Start-Process -FilePath 'java.exe' `
        -ArgumentList @('-Dpersonalassistant.open-browser=false', '-jar', "`"$jar`"", '--no-browser') `
        -WorkingDirectory $appRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $outputLog -RedirectStandardError $errorLog
    Set-Content -LiteralPath $pidFile -Value $process.Id
    Write-Host "Personal Assistant started in the background (PID $($process.Id))."
    Write-Host "Logs: $outputLog"
    exit 0
}

Write-Host 'Starting in the current window. Press Ctrl+C to stop.'
Set-Location -LiteralPath $appRoot
try {
    & java.exe '-Dpersonalassistant.open-browser=false' -jar $jar --no-browser
    exit $LASTEXITCODE
} finally {
    if (Test-Path -LiteralPath $pidFile) {
        Remove-Item -LiteralPath $pidFile -Force
    }
}
