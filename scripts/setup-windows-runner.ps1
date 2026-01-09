# Windows Self-Hosted Runner Setup Script
# Dieses Script hilft beim Setup des GitHub Actions Runners auf Windows

Write-Host "=====================================" -ForegroundColor Cyan
Write-Host "GitHub Actions Runner Setup (Windows)" -ForegroundColor Cyan
Write-Host "=====================================" -ForegroundColor Cyan
Write-Host ""

# 1. Voraussetzungen prüfen
Write-Host "1. Prüfe Voraussetzungen..." -ForegroundColor Yellow

$checks = @{
    "Java 21" = { java -version 2>&1 | Select-String "21\." }
    "Android SDK" = { Test-Path "$env:LOCALAPPDATA\Android\Sdk" }
    "Git" = { git --version }
    "Ruby" = { ruby --version 2>&1 | Select-String "3\.[2-9]|3\.[1-9][0-9]" }
}

$allGood = $true
foreach ($check in $checks.GetEnumerator()) {
    Write-Host "  Checking $($check.Key)..." -NoNewline
    try {
        $result = & $check.Value
        if ($result) {
            Write-Host " OK" -ForegroundColor Green
        } else {
            Write-Host " FEHLT" -ForegroundColor Red
            $allGood = $false
        }
    } catch {
        Write-Host " FEHLT" -ForegroundColor Red
        $allGood = $false
    }
}

if (-not $allGood) {
    Write-Host ""
    Write-Host "Fehlende Software installieren:" -ForegroundColor Red
    Write-Host "  winget install EclipseAdoptium.Temurin.21.JDK" -ForegroundColor White
    Write-Host "  winget install Google.AndroidStudio" -ForegroundColor White
    Write-Host "  winget install Git.Git" -ForegroundColor White
    Write-Host "  winget install RubyInstallerTeam.RubyWithDevKit.3.2" -ForegroundColor White
    Write-Host ""
    Write-Host "Nach Installation: PowerShell neu starten und Script erneut ausführen." -ForegroundColor Yellow
    exit 1
}

Write-Host ""
Write-Host "2. Umgebungsvariablen..." -ForegroundColor Yellow

# ANDROID_HOME prüfen
if (-not $env:ANDROID_HOME) {
    Write-Host "  ANDROID_HOME nicht gesetzt - setze jetzt..." -ForegroundColor Yellow
    $androidSdk = "$env:LOCALAPPDATA\Android\Sdk"
    [System.Environment]::SetEnvironmentVariable("ANDROID_HOME", $androidSdk, "User")
    [System.Environment]::SetEnvironmentVariable("ANDROID_SDK_ROOT", $androidSdk, "User")
    $env:ANDROID_HOME = $androidSdk
    Write-Host "  ANDROID_HOME gesetzt: $androidSdk" -ForegroundColor Green
} else {
    Write-Host "  ANDROID_HOME bereits gesetzt: $env:ANDROID_HOME" -ForegroundColor Green
}

# JAVA_HOME prüfen
if (-not $env:JAVA_HOME) {
    Write-Host "  JAVA_HOME nicht gesetzt - bitte manuell setzen!" -ForegroundColor Red
    Write-Host "  Beispiel: [System.Environment]::SetEnvironmentVariable('JAVA_HOME', 'C:\Program Files\Eclipse Adoptium\jdk-21.0.5.11-hotspot', 'Machine')" -ForegroundColor White
} else {
    Write-Host "  JAVA_HOME bereits gesetzt: $env:JAVA_HOME" -ForegroundColor Green
}

Write-Host ""
Write-Host "3. Fastlane installieren..." -ForegroundColor Yellow
try {
    fastlane --version | Out-Null
    Write-Host "  Fastlane bereits installiert" -ForegroundColor Green
} catch {
    Write-Host "  Installiere Fastlane..." -ForegroundColor Yellow
    gem install fastlane --no-document
    Write-Host "  Fastlane installiert" -ForegroundColor Green
}

Write-Host ""
Write-Host "=====================================" -ForegroundColor Cyan
Write-Host "Voraussetzungen erfüllt!" -ForegroundColor Green
Write-Host "=====================================" -ForegroundColor Cyan
Write-Host ""

Write-Host "NAECHSTE SCHRITTE:" -ForegroundColor Yellow
Write-Host ""
Write-Host "1. GitHub Runner Token holen:" -ForegroundColor White
Write-Host "   https://github.com/napoleonmm83/paperless-scanner/settings/actions/runners/new" -ForegroundColor Cyan
Write-Host ""
Write-Host "2. Runner herunterladen und entpacken:" -ForegroundColor White
Write-Host "   - Download von der obigen URL" -ForegroundColor Gray
Write-Host "   - Entpacke nach: C:\actions-runner" -ForegroundColor Gray
Write-Host ""
Write-Host "3. Runner konfigurieren (in C:\actions-runner):" -ForegroundColor White
Write-Host "   .\config.cmd --url https://github.com/napoleonmm83/paperless-scanner --token DEIN_TOKEN" -ForegroundColor Cyan
Write-Host ""
Write-Host "4. Als Windows-Dienst installieren (als Administrator):" -ForegroundColor White
Write-Host "   .\svc.cmd install" -ForegroundColor Cyan
Write-Host "   .\svc.cmd start" -ForegroundColor Cyan
Write-Host ""
Write-Host "5. Status pruefen:" -ForegroundColor White
Write-Host "   .\svc.cmd status" -ForegroundColor Cyan
Write-Host ""
Write-Host "Siehe docs/WINDOWS_RUNNER_SETUP.md fuer Details!" -ForegroundColor Gray
Write-Host ""
