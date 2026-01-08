# Lokales Android CI/CD Deployment

Dieses Dokument beschreibt, wie du die Android App lokal bauen und zum Play Store deployen kannst, ohne auf GitHub Actions warten zu muessen.

---

## WICHTIG: Vor jedem Push

**IMMER vor `git push` ausfuehren:**

```bash
# Validiert EXAKT wie GitHub Actions
./scripts/validate-ci.sh
```

Oder mit Docker:
```bash
./scripts/validate-ci.sh --docker
```

Der Pre-push Hook fuehrt dies automatisch aus. Falls er fehlschlaegt, NICHT pushen!

---

## Option 1: Docker (Empfohlen)

Die einfachste Methode - keine lokale Installation von JDK/Fastlane noetig.

### Voraussetzungen
- Docker Desktop: https://www.docker.com/products/docker-desktop

### Verwendung

```bash
# Tests ausfuehren
./scripts/docker-ci.sh test

# Lint Check
./scripts/docker-ci.sh lint

# Debug APK bauen
./scripts/docker-ci.sh build

# Komplette CI Pipeline (test + lint + build)
./scripts/docker-ci.sh full

# Release AAB bauen
./scripts/docker-ci.sh bundle

# Zu Play Store deployen
./scripts/docker-ci.sh deploy

# Interaktive Shell im Container
./scripts/docker-ci.sh shell

# Docker Caches loeschen
./scripts/docker-ci.sh clean
```

### Docker Compose direkt

```bash
# Tests
docker-compose -f docker-compose.ci.yml run --rm ci-test

# Lint
docker-compose -f docker-compose.ci.yml run --rm ci-lint

# Build
docker-compose -f docker-compose.ci.yml run --rm ci-build

# Komplette Pipeline
docker-compose -f docker-compose.ci.yml run --rm ci-full

# Shell
docker-compose -f docker-compose.ci.yml run --rm ci
```

### Vorteile Docker

- Keine lokale Installation von JDK/Fastlane/Android SDK noetig
- Identische Umgebung wie CI/CD
- Gradle Cache wird persistent gespeichert
- Funktioniert auf jedem OS

---

## Option 2: Native (ohne Docker)

Falls Docker nicht verfuegbar ist.

## Voraussetzungen

### 1. JDK 21 (Temurin)
```bash
# macOS mit Homebrew
brew install --cask temurin@21

# Verify
java -version
```

### 2. Fastlane
```bash
# Mit Ruby gem
gem install fastlane

# Oder mit Homebrew (macOS)
brew install fastlane

# Verify
fastlane --version
```

### 3. Play Store Service Account Key
Die Datei `fastlane/play-store-key.json` muss vorhanden sein.
Diese enthaelt die Zugangsdaten fuer den Google Play Store.

### 4. Release Keystore (fuer signierte Builds)
Setze folgende Umgebungsvariablen:
```bash
export KEYSTORE_FILE=/path/to/your/keystore.jks
export KEYSTORE_PASSWORD=your_password
export KEY_ALIAS=your_alias
export KEY_PASSWORD=your_key_password
```

Oder erstelle eine `.env` Datei im Projektroot:
```bash
KEYSTORE_FILE=/path/to/your/keystore.jks
KEYSTORE_PASSWORD=your_password
KEY_ALIAS=your_alias
KEY_PASSWORD=your_key_password
```

---

## Schnellstart

### Lokale CI-Checks (vor Commit)
```bash
# Standard: Compile + Tests + Lint (~2-3 Min)
./scripts/local-ci.sh

# Quick Mode: Nur Lint (~45 Sek)
./scripts/local-ci.sh --quick

# Full Mode: Alles + Build (~5 Min)
./scripts/local-ci.sh --full
```

### Deployment zum Play Store
```bash
# Internal Track (Standard)
./scripts/local-deploy.sh

# Oder spezifischer Track
./scripts/local-deploy.sh internal
./scripts/local-deploy.sh alpha
./scripts/local-deploy.sh beta
./scripts/local-deploy.sh production
```

---

## Detaillierte Anleitungen

### 1. Lokale CI-Checks

Das `local-ci.sh` Skript simuliert die GitHub Actions CI Pipeline lokal.

**Optionen:**
| Option | Beschreibung |
|--------|--------------|
| `--quick`, `-q` | Nur Lint Check (~45 Sek) |
| `--full`, `-f` | Tests + Lint + Build (~5 Min) |
| `--no-tests` | Unit Tests ueberspringen |
| `--no-lint` | Lint Check ueberspringen |
| `--verbose`, `-v` | Ausfuehrliche Ausgabe |

**Beispiele:**
```bash
# Schneller Check vor Commit
./scripts/local-ci.sh --quick

# Vollstaendiger Check vor Push
./scripts/local-ci.sh

# Mit Build-Pruefung
./scripts/local-ci.sh --full
```

### 2. Lokales Deployment

Das `local-deploy.sh` Skript fuehrt den kompletten Deploy-Prozess durch:

1. Prueft Voraussetzungen
2. Fuehrt Tests aus (optional)
3. Fuehrt Lint Check aus
4. Bumpt Version (optional)
5. Baut Release AAB
6. Deployed zum Play Store

**Tracks:**
| Track | Beschreibung |
|-------|--------------|
| `internal` | Internes Testing (Standard) |
| `alpha` | Alpha-Tester |
| `beta` | Beta-Tester |
| `production` | Produktiv-Release |

**Beispiel:**
```bash
# Deploy zu Internal (mit interaktiven Optionen)
./scripts/local-deploy.sh

# Deploy zu Alpha
./scripts/local-deploy.sh alpha
```

---

## Fastlane Direktbefehle

Falls du Fastlane direkt nutzen moechtest:

```bash
# Tests ausfuehren
fastlane android test

# Debug APK bauen
fastlane android build_debug

# Release AAB bauen
fastlane android build_bundle

# Zu Internal deployen
fastlane android internal

# Zu Production deployen
fastlane android production

# Version bumpen
fastlane android bump_patch
fastlane android bump_minor
fastlane android bump_major

# Nur Metadata hochladen
fastlane android metadata
```

---

## Gradle Direktbefehle

Fuer noch mehr Kontrolle kannst du Gradle direkt nutzen:

```bash
# Compile Check (schnell)
./gradlew compileDebugKotlin --no-daemon

# Unit Tests
./gradlew testDebugUnitTest --no-daemon

# Lint Check
./gradlew lintDebug --no-daemon

# Debug APK bauen
./gradlew assembleDebug --no-daemon

# Release AAB bauen
./gradlew bundleRelease --no-daemon
```

---

## Typische Workflows

### Vor jedem Commit
```bash
./scripts/local-ci.sh --quick
# Bei Erfolg: git commit
```

### Vor Push zu main
```bash
./scripts/local-ci.sh
# Bei Erfolg: git push
```

### Release zum Play Store
```bash
# 1. Pruefe dass alle Tests OK sind
./scripts/local-ci.sh --full

# 2. Deploy zu Internal fuer Tester
./scripts/local-deploy.sh internal

# 3. Nach Feedback: Promote zu Production
./scripts/local-deploy.sh production
```

---

## Troubleshooting

### "SDK location not found"
Stelle sicher, dass `ANDROID_HOME` gesetzt ist:
```bash
export ANDROID_HOME=~/Library/Android/sdk
```

### "Keystore not found"
Setze den Keystore-Pfad:
```bash
export KEYSTORE_FILE=/path/to/keystore.jks
```

### Fastlane Fehler
```bash
# Bundle Update
bundle update

# Fastlane Update
gem update fastlane
```

### Gradle Daemon Probleme
```bash
# Daemon stoppen
./gradlew --stop

# Mit frischem Daemon starten
./gradlew assembleDebug --no-daemon
```

---

## CI/CD Vergleich

| Schritt | GitHub Actions | Lokal |
|---------|----------------|-------|
| Tests | ~3-5 Min | ~2-3 Min |
| Lint | ~2 Min | ~45 Sek |
| Build | ~3-5 Min | ~2-3 Min |
| Deploy | ~5 Min | ~2-3 Min |
| **Gesamt** | **15-20 Min** | **5-8 Min** |

---

## Dateien

| Datei | Beschreibung |
|-------|--------------|
| `scripts/local-ci.sh` | Lokale CI-Checks |
| `scripts/local-deploy.sh` | Lokales Deployment |
| `fastlane/Fastfile` | Fastlane Konfiguration |
| `fastlane/Appfile` | App-spezifische Einstellungen |
| `fastlane/play-store-key.json` | Play Store Credentials |
