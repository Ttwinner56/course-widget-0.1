# Generate an Android signing keystore without Java/keytool (uses OpenSSL).
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File gen-keystore.ps1 -OutDir .\keystore
#
# Output:
#   coursewidget.p12     keystore (private key + self-signed cert), used for signing
#   cert.pem             certificate, only for inspecting the fingerprint (safe to delete)
#   keystore.properties  passwords, excluded by .gitignore
#
# -legacy keeps the traditional PKCS#12 encryption so Java keytool and all AGP versions can read it.
# NOTE: this file is intentionally ASCII-only, because Windows PowerShell 5.1 misreads
#       UTF-8 scripts without a BOM.

param(
    [string]$OutDir = ".\keystore",
    [string]$Alias = "coursewidget",
    [string]$Password = "CourseWidget2026!"
)

$ErrorActionPreference = "Stop"
# openssl writes normal progress to stderr; without this, PowerShell 5.1 treats it as a fatal error.
$ErrorActionPreference = "Continue"

$openssl = $null
foreach ($candidate in @(
        "C:\Users\VEID\AppData\Local\hermes\git\mingw64\bin\openssl.exe",
        (Get-Command openssl -ErrorAction SilentlyContinue).Source
    )) {
    if ($candidate -and (Test-Path $candidate)) { $openssl = $candidate; break }
}
if (-not $openssl) { throw "openssl.exe not found" }

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$key = Join-Path $OutDir "key.pem"
$crt = Join-Path $OutDir "cert.pem"
$p12 = Join-Path $OutDir "coursewidget.p12"

Write-Host "[1/3] generating private key and self-signed certificate (10000 days)..." -ForegroundColor Cyan
& $openssl req -x509 -newkey rsa:2048 -sha256 -days 10000 -nodes `
    -keyout $key -out $crt `
    -subj "/C=CN/ST=Beijing/L=Beijing/O=Veid/OU=Personal/CN=CourseWidget" `
    -addext "basicConstraints=critical,CA:true" `
    -addext "keyUsage=critical,digitalSignature,keyEncipherment,keyCertSign" `
    -addext "extendedKeyUsage=codeSigning" `
    -addext "subjectKeyIdentifier=hash" 2>$null

Write-Host "[2/3] packing into PKCS#12 (legacy encryption for keytool compatibility)..." -ForegroundColor Cyan
& $openssl pkcs12 -export -legacy -descert `
    -inkey $key -in $crt -out $p12 -name $Alias `
    -passout "pass:$Password" 2>$null

Write-Host "[3/3] verifying..." -ForegroundColor Cyan
& $openssl pkcs12 -in $p12 -nokeys -passin "pass:$Password" -noout 2>$null
if ($LASTEXITCODE -ne 0) { throw "keystore verification failed" }

$props = "storeFile=coursewidget.p12`nstorePassword=$Password`nkeyAlias=$Alias`nkeyPassword=$Password`n"
Set-Content -Path (Join-Path $OutDir "keystore.properties") -Value $props -Encoding ASCII

Remove-Item $key -Force -ErrorAction SilentlyContinue

Write-Host ""
Write-Host "DONE" -ForegroundColor Green
Get-ChildItem $OutDir | Select-Object Name, Length | Format-Table -AutoSize
& $openssl x509 -in $crt -noout -fingerprint -sha256
