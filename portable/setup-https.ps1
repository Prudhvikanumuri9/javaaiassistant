param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\d{1,3}(\.\d{1,3}){3}$')]
    [string]$LanIp
)

$ErrorActionPreference = 'Stop'
$appRoot = $PSScriptRoot
$configDir = Join-Path $appRoot 'config'
$keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
if (-not (Test-Path -LiteralPath $keytool)) {
    $javaCommand = Get-Command java -ErrorAction Stop
    $keytool = Join-Path (Split-Path (Split-Path $javaCommand.Source)) 'bin\keytool.exe'
}
if (-not (Test-Path -LiteralPath $keytool)) {
    throw 'keytool.exe was not found. Install Java 21 and set JAVA_HOME.'
}

New-Item -ItemType Directory -Path $configDir -Force | Out-Null
$password = [Guid]::NewGuid().ToString('N')
$caStore = Join-Path $configDir 'local-ca.p12'
$serverStore = Join-Path $configDir 'https.p12'
$caCertificate = Join-Path $configDir 'personal-assistant-ca.cer'
$request = Join-Path $configDir 'https-request.csr'
$signedCertificate = Join-Path $configDir 'https-signed.cer'
$settings = Join-Path $configDir 'https.properties'

foreach ($file in @($caStore, $serverStore, $caCertificate, $request, $signedCertificate, $settings)) {
    if (Test-Path -LiteralPath $file) {
        Remove-Item -LiteralPath $file -Force
    }
}

function Invoke-Keytool {
    & $keytool @args
    if ($LASTEXITCODE -ne 0) { throw "keytool failed with exit code $LASTEXITCODE" }
}

Invoke-Keytool -genkeypair -alias local-ca -keyalg RSA -keysize 3072 `
    -dname 'CN=Personal Assistant Local CA, OU=Home, O=Personal Assistant' `
    -ext 'bc=ca:true' -ext 'ku=keyCertSign,cRLSign' -validity 3650 `
    -keystore $caStore -storetype PKCS12 -storepass $password -keypass $password -noprompt

Invoke-Keytool -exportcert -alias local-ca -keystore $caStore -storepass $password `
    -file $caCertificate

Invoke-Keytool -genkeypair -alias personal-assistant -keyalg RSA -keysize 3072 `
    -dname "CN=$LanIp, OU=Home, O=Personal Assistant" `
    -ext "SAN=ip:$LanIp,ip:127.0.0.1,dns:localhost" `
    -ext 'eku=serverAuth' -validity 825 -keystore $serverStore `
    -storetype PKCS12 -storepass $password -keypass $password -noprompt

Invoke-Keytool -certreq -alias personal-assistant -keystore $serverStore `
    -storepass $password -file $request `
    -ext "SAN=ip:$LanIp,ip:127.0.0.1,dns:localhost"

Invoke-Keytool -gencert -alias local-ca -keystore $caStore -storepass $password `
    -infile $request -outfile $signedCertificate -validity 825 `
    -ext "SAN=ip:$LanIp,ip:127.0.0.1,dns:localhost" `
    -ext 'ku=digitalSignature,keyEncipherment' -ext 'eku=serverAuth'

Invoke-Keytool -importcert -alias local-ca -keystore $serverStore -storepass $password `
    -file $caCertificate -noprompt
Invoke-Keytool -importcert -alias personal-assistant -keystore $serverStore `
    -storepass $password -file $signedCertificate -noprompt

@"
PA_LAN_IP=$LanIp
PA_KEYSTORE_PASSWORD=$password
"@ | Set-Content -LiteralPath $settings -Encoding ASCII

Remove-Item -LiteralPath $request, $signedCertificate -Force
Write-Host ''
Write-Host 'HTTPS certificates created successfully.' -ForegroundColor Green
Write-Host "Phone address: https://${LanIp}:8787"
Write-Host "Install this CA certificate on the phone: $caCertificate"
Write-Host 'Keep local-ca.p12 and https.properties private.'
