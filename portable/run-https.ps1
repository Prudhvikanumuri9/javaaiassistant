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
$env:PA_ACCESS_PIN = $settings['PA_ACCESS_PIN']

if ([string]::IsNullOrWhiteSpace($env:PA_ACCESS_PIN)) {
    throw 'Access PIN is missing. Run setup-https.cmd again to enable secure LAN login.'
}

Write-Host "Starting Personal Assistant at https://$($settings['PA_LAN_IP']):8787"
Write-Host 'Login username: home'
Write-Host "Access PIN: $env:PA_ACCESS_PIN"
Set-Location -LiteralPath $appRoot
& java -jar $jar --no-browser
