# Windows Self-Hosted Runner Setup Guide

Diese Anleitung beschreibt die Einrichtung eines GitHub Actions Self-Hosted Runners auf Windows.

## Voraussetzungen

### 1. Software installieren

#### JDK 21 (Temurin)
```powershell
# Mit winget
winget install EclipseAdoptium.Temurin.21.JDK

# Oder Download von: https://adoptium.net/
```

#### Android Studio / SDK
```powershell
# Mit winget
winget install Google.AndroidStudio

# Nach Installation: Android Studio starten und SDK installieren
# Standard-Pfad: C:\Users\<USER>\AppData\Local\Android\Sdk
```

#### Git
```powershell
winget install Git.Git
```

#### Ruby (für Fastlane/Play Store Deployment)
```powershell
# Mit winget (empfohlen)
winget install RubyInstallerTeam.RubyWithDevKit.3.2

# Oder Download von: https://rubyinstaller.org/
# Ruby+Devkit 3.2.x (x64) wählen
```

### 2. Umgebungsvariablen setzen

Öffne PowerShell als Administrator:

```powershell
# JAVA_HOME setzen
[System.Environment]::SetEnvironmentVariable("JAVA_HOME", "C:\Program Files\Eclipse Adoptium\jdk-21.0.5.11-hotspot", "Machine")

# ANDROID_HOME setzen
[System.Environment]::SetEnvironmentVariable("ANDROID_HOME", "$env:LOCALAPPDATA\Android\Sdk", "User")
[System.Environment]::SetEnvironmentVariable("ANDROID_SDK_ROOT", "$env:LOCALAPPDATA\Android\Sdk", "User")

# PATH erweitern
$currentPath = [System.Environment]::GetEnvironmentVariable("Path", "User")
$androidPaths = "$env:LOCALAPPDATA\Android\Sdk\platform-tools;$env:LOCALAPPDATA\Android\Sdk\cmdline-tools\latest\bin"
[System.Environment]::SetEnvironmentVariable("Path", "$currentPath;$androidPaths", "User")
```

### 3. Verifizieren

```powershell
# Neues PowerShell-Fenster öffnen
java -version          # Sollte Java 21 anzeigen
adb --version          # Sollte adb version anzeigen
echo $env:ANDROID_HOME # Sollte SDK-Pfad anzeigen
ruby --version         # Sollte Ruby 3.2+ anzeigen (für Fastlane)
gem --version          # Sollte gem version anzeigen
```

### 4. Fastlane installieren (für Play Store Deployment)

```powershell
gem install fastlane --no-document
fastlane --version     # Verifizieren
```

## GitHub Actions Runner installieren

### 1. Runner herunterladen

1. Gehe zu: https://github.com/napoleonmm83/paperless-scanner/settings/actions/runners
2. Klicke "New self-hosted runner"
3. Wähle "Windows" und "x64"
4. Folge den Anweisungen zum Download

### 2. Runner konfigurieren

```powershell
# In den Runner-Ordner wechseln
cd C:\actions-runner

# Konfigurieren
.\config.cmd --url https://github.com/napoleonmm83/paperless-scanner --token <TOKEN>

# Labels hinzufügen wenn gefragt:
# - self-hosted (automatisch)
# - Windows (automatisch)
# - X64 (automatisch)
# - paperless (custom label - WICHTIG!)
```

### 3. Als Windows-Dienst installieren

```powershell
# Als Administrator ausführen
.\svc.cmd install
.\svc.cmd start

# Status prüfen
.\svc.cmd status
```

### 4. Dienst-Benutzer Umgebungsvariablen

**WICHTIG:** Der Windows-Dienst läuft unter einem Service-Account, der möglicherweise nicht die Benutzer-Umgebungsvariablen hat!

Option A: System-weite Variablen setzen (empfohlen):
```powershell
# Als Administrator
[System.Environment]::SetEnvironmentVariable("ANDROID_HOME", "C:\Users\<USER>\AppData\Local\Android\Sdk", "Machine")
[System.Environment]::SetEnvironmentVariable("ANDROID_SDK_ROOT", "C:\Users\<USER>\AppData\Local\Android\Sdk", "Machine")
```

Option B: Dienst unter Benutzer-Account laufen lassen:
```powershell
# Dienst stoppen
.\svc.cmd stop
.\svc.cmd uninstall

# Neu installieren mit Benutzer-Account
.\svc.cmd install <DOMAIN\USERNAME>
.\svc.cmd start
```

## Workflow-Anpassungen

Die Workflows sind bereits für Windows vorbereitet. Sie erkennen automatisch:
- `runner.os == 'Windows'` für Windows-spezifische Steps
- Verwenden `gradlew.bat` statt `./gradlew`
- Nutzen PowerShell für Windows-Commands

## Fehlerbehebung

### "SDK location not found"
```powershell
# Prüfen ob ANDROID_HOME gesetzt ist
echo $env:ANDROID_HOME

# Prüfen ob SDK existiert
Test-Path "$env:LOCALAPPDATA\Android\Sdk"

# Falls nicht: System-Variable setzen (als Admin)
[System.Environment]::SetEnvironmentVariable("ANDROID_HOME", "C:\Users\<USER>\AppData\Local\Android\Sdk", "Machine")
```

### Runner nimmt keine Jobs an
```powershell
# Dienst-Status prüfen
.\svc.cmd status

# Logs prüfen
Get-Content _diag\Runner_*.log -Tail 50

# Dienst neu starten
.\svc.cmd stop
.\svc.cmd start
```

### Gradle-Probleme
```powershell
# Gradle-Cache löschen
Remove-Item -Recurse -Force "$env:USERPROFILE\.gradle\caches"

# Gradle-Daemon stoppen
.\gradlew.bat --stop
```

## Gleichzeitiger Betrieb Mac + Windows

Beide Runner können parallel laufen! GitHub Actions verteilt Jobs automatisch:
- Wenn beide online: Jobs werden auf verfügbare Runner verteilt
- Wenn nur einer online: Alle Jobs laufen dort
- Fallback: ubuntu-latest wenn kein self-hosted verfügbar

Die Workflows sind so konfiguriert, dass sie automatisch den richtigen Runner wählen.
