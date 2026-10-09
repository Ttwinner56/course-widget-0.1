# Local build script: reads version from version.json, signs with the fixed keystore,
# and produces a release APK.
#
# Usage:  powershell -ExecutionPolicy Bypass -File build-local.ps1
# Requires: JDK 17 + Android SDK (ANDROID_HOME) + Gradle 8.7 on PATH
# NOTE: ASCII-only on purpose; Windows PowerShell 5.1 misreads UTF-8 scripts without a BOM.

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
Set-Location $root

# ---- version ----
$version = Get-Content "$root\version.json" -Raw | ConvertFrom-Json
Write-Host "Version: $($version.versionName) ($($version.versionCode)) stage=$($version.stage)" -ForegroundColor Cyan

# ---- signing ----
$propsFile = "$root\keystore\keystore.properties"
if (-not (Test-Path $propsFile)) {
    Write-Warning "keystore\keystore.properties not found: the APK will be UNSIGNED and cannot be installed."
    $signingArgs = @()
} else {
    $props = @{}
    Get-Content $propsFile | ForEach-Object {
        if ($_ -match '^\s*([^#=]+)=(.*)$') { $props[$matches[1].Trim()] = $matches[2].Trim() }
    }
    $signingArgs = @(
        "-Psigning.storeFile=$root\keystore\$($props['storeFile'])",
        "-Psigning.storePassword=$($props['storePassword'])",
        "-Psigning.keyAlias=$($props['keyAlias'])",
        "-Psigning.keyPassword=$($props['keyPassword'])"
    )
}

# ---- gradle ----
$gradle = (Get-Command gradle -ErrorAction SilentlyContinue).Source
if (-not $gradle -and (Test-Path "$root\gradlew.bat")) { $gradle = "$root\gradlew.bat" }
if (-not $gradle) { throw "gradle not found. Install Gradle 8.7, or open this project in Android Studio." }

Write-Host "Building..." -ForegroundColor Cyan
& $gradle assembleRelease --no-daemon --stacktrace `
    "-PversionName=$($version.versionName)" `
    "-PversionCode=$($version.versionCode)" `
    @signingArgs

if ($LASTEXITCODE -ne 0) { throw "build failed" }

$apk = Get-ChildItem "$root\app\build\outputs\apk\release\*.apk" |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
Write-Host ""
Write-Host "BUILD OK" -ForegroundColor Green
Write-Host "  $($apk.FullName)"
Write-Host "  size: $([math]::Round($apk.Length / 1MB, 2)) MB"
