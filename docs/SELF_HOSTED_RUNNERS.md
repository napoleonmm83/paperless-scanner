# Self-Hosted GitHub Actions Runner Setup

Diese Anleitung beschreibt die Einrichtung von selbstgehosteten GitHub Actions Runnern für Mac und Windows.

## Vorteile

- **Keine GitHub Actions-Minuten verbrauchen** (unbegrenzte Builds)
- **Schnellere Builds** durch lokale Caches
- **Zugang zu lokalen Ressourcen** (Keystore, Secrets, etc.)
- **Automatischer Fallback** auf GitHub-Runner wenn kein lokaler Runner online ist

## Workflow

Die Workflows prüfen automatisch ob ein selbstgehosteter Runner verfügbar ist:
1. Wenn **ja**: Build läuft auf deinem lokalen Mac/Windows
2. Wenn **nein**: Build läuft auf GitHub's `ubuntu-latest`

---

## Mac Runner Setup

### 1. Runner von GitHub holen

1. Öffne: https://github.com/napoleonmm83/paperless-scanner/settings/actions/runners/new
2. Wähle **macOS** und **ARM64** (für Apple Silicon) oder **x64** (für Intel)
3. Kopiere den Token (gültig für 1 Stunde)

### 2. Installation

```bash
# Verzeichnis erstellen
mkdir -p ~/actions-runner-mac
cd ~/actions-runner-mac

# Runner herunterladen (ARM64 für Apple Silicon)
curl -o actions-runner-osx-arm64-2.321.0.tar.gz -L \
  https://github.com/actions/runner/releases/download/v2.321.0/actions-runner-osx-arm64-2.321.0.tar.gz

# Für Intel Mac stattdessen:
# curl -o actions-runner-osx-x64-2.321.0.tar.gz -L \
#   https://github.com/actions/runner/releases/download/v2.321.0/actions-runner-osx-x64-2.321.0.tar.gz

# Entpacken
tar xzf ./actions-runner-*.tar.gz
```

### 3. Konfiguration

```bash
# Konfigurieren (Token von GitHub einfügen!)
./config.sh --url https://github.com/napoleonmm83/paperless-scanner \
  --token DEIN_TOKEN_HIER \
  --labels mac,self-hosted,paperless \
  --name "Mac-Runner"
```

### 4. Als Dienst starten

```bash
# Dienst installieren und starten
./svc.sh install
./svc.sh start

# Status prüfen
./svc.sh status
```

### 5. Dienst verwalten

```bash
# Stoppen
./svc.sh stop

# Neustarten
./svc.sh stop && ./svc.sh start

# Deinstallieren
./svc.sh uninstall
```

---

## Windows Runner Setup

### 1. Runner von GitHub holen

1. Öffne: https://github.com/napoleonmm83/paperless-scanner/settings/actions/runners/new
2. Wähle **Windows** und **x64**
3. Kopiere den Token (gültig für 1 Stunde)

### 2. Installation (PowerShell als Admin)

```powershell
# Verzeichnis erstellen
mkdir C:\actions-runner-win
cd C:\actions-runner-win

# Runner herunterladen
Invoke-WebRequest -Uri https://github.com/actions/runner/releases/download/v2.321.0/actions-runner-win-x64-2.321.0.zip -OutFile actions-runner-win-x64-2.321.0.zip

# Entpacken
Expand-Archive -Path actions-runner-win-x64-2.321.0.zip -DestinationPath .

# Zip löschen
Remove-Item actions-runner-win-x64-2.321.0.zip
```

### 3. Konfiguration

```powershell
# Konfigurieren (Token von GitHub einfügen!)
.\config.cmd --url https://github.com/napoleonmm83/paperless-scanner `
  --token DEIN_TOKEN_HIER `
  --labels windows,self-hosted,paperless `
  --name "Windows-Runner"
```

### 4. Als Windows-Dienst starten

```powershell
# Dienst installieren (als Admin!)
.\svc.cmd install

# Dienst starten
.\svc.cmd start

# Status prüfen
.\svc.cmd status
```

### 5. Dienst verwalten

```powershell
# Stoppen
.\svc.cmd stop

# Neustarten
.\svc.cmd stop
.\svc.cmd start

# Deinstallieren
.\svc.cmd uninstall
```

---

## Voraussetzungen auf den Runnern

### Beide Systeme benötigen:

1. **JDK 21** (Temurin empfohlen)
   - Mac: `brew install --cask temurin@21`
   - Windows: https://adoptium.net/de/temurin/releases/?version=21

2. **Git**
   - Mac: `brew install git`
   - Windows: https://git-scm.com/download/win

3. **Android SDK** (optional, für lokale Builds)
   - Wird automatisch von Gradle heruntergeladen

### Nur Windows zusätzlich:

4. **Ruby 3.2+** (für Fastlane)
   - https://rubyinstaller.org/downloads/

5. **Fastlane**
   ```powershell
   gem install fastlane
   ```

---

## Fehlerbehebung

### Runner ist offline

```bash
# Mac: Status prüfen
cd ~/actions-runner-mac
./svc.sh status

# Neu starten
./svc.sh stop && ./svc.sh start
```

```powershell
# Windows: Status prüfen
cd C:\actions-runner-win
.\svc.cmd status

# Neu starten
.\svc.cmd stop
.\svc.cmd start
```

### Runner neu registrieren

Falls der Runner Probleme macht:

```bash
# Mac
cd ~/actions-runner-mac
./svc.sh uninstall
./config.sh remove --token NEUER_TOKEN
./config.sh --url ... --token ... --labels ...
./svc.sh install && ./svc.sh start
```

```powershell
# Windows
cd C:\actions-runner-win
.\svc.cmd uninstall
.\config.cmd remove --token NEUER_TOKEN
.\config.cmd --url ... --token ... --labels ...
.\svc.cmd install
.\svc.cmd start
```

### Logs ansehen

```bash
# Mac
tail -f ~/actions-runner-mac/_diag/Runner_*.log
```

```powershell
# Windows
Get-Content C:\actions-runner-win\_diag\Runner_*.log -Tail 50 -Wait
```

---

## Workflow-Verhalten

Die Workflows nutzen das Label `paperless` zur Identifikation:

```yaml
runs-on: ["self-hosted", "paperless"]
```

**Fallback-Logik:**
1. `check-runner` Job prüft via GitHub API ob ein Runner online ist
2. Wenn online → `["self-hosted", "paperless"]`
3. Wenn offline → `["ubuntu-latest"]`

**Betroffene Workflows:**
- `android-ci.yml` - Build, Test, Lint, Code Quality
- `auto-deploy-internal.yml` - Tests und Play Store Deploy

---

## Sicherheitshinweise

- Runner laufen unter deinem Benutzeraccount
- Secrets werden verschlüsselt übertragen
- Arbeitsverzeichnis wird nach jedem Job bereinigt
- Empfohlen: Nur für private Repositories verwenden
