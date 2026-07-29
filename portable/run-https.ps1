$ErrorActionPreference = 'Stop'
$appRoot = $PSScriptRoot
$settingsFile = Join-Path $appRoot 'config\https.properties'
$keyStore = Join-Path $appRoot 'config\https.p12'
$jar = Join-Path $appRoot 'personal-assistant.jar'

if (-not (Test-Path -LiteralPath $settingsFile) -or -not (Test-Path -LiteralPath $keyStore)) {
    throw 'HTTPS is not configured. Run: .\setup-https.ps1 -LanIp YOUR_PC_IP'
}
if (-not (Test-Path -LiteralPath $jar)) {
    throw "Application JAR not found: $jar"
}

$settings = @{}
foreach ($line in Get-Content -LiteralPath $settingsFile) {
    if ($line -match '^([^=]+)=(.*)$') { $settings[$matches[1]] = $matches[2] }
}

$env:PA_HTTPS = 'true'
$env:PA_SERVER_ADDRESS = '0.0.0.0'
$env:PA_KEYSTORE = $keyStore
$env:PA_KEYSTORE_PASSWORD = $settings['PA_KEYSTORE_PASSWORD']

Write-Host "Starting Personal Assistant at https://$($settings['PA_LAN_IP']):8787"
Set-Location -LiteralPath $appRoot
& java -jar $jar --no-browser
