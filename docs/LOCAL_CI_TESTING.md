# Lokales CI Testing für Android

## Problem
- GitHub Actions Builds dauern 10-15 Minuten
- Fehler werden erst nach Push entdeckt
- Ineffizient: commit → push → wait → fix → repeat

## ⚠️ WICHTIG: act Limitierung für Android-Projekte

**`act` hat eine fundamentale Einschränkung bei Android-Projekten**: Das Android SDK muss im Docker Container verfügbar sein, was komplex einzurichten ist.

**✅ EMPFOHLENE LÖSUNG: Direkte Gradle-Befehle**

Die effizienteste Methode für lokales Android CI Testing ist die **direkte Verwendung von Gradle-Befehlen** (siehe unten "Schneller lokaler Test-Workflow").

## Lösung 1: Direkte Gradle-Befehle (EMPFOHLEN ✅)

### Vorteile
- ⚡ **Sehr schnell**: Lint Check in <1 Minute
- ✅ **Einfach**: Keine Docker/act Setup nötig
- ✅ **Zuverlässig**: Nutzt lokales Android SDK
- ✅ **Identische Ergebnisse**: Gleiche Gradle-Version wie CI

### Nachteile
- ❌ Nicht 100% identisch mit CI-Umgebung (aber sehr nah)

---

## Lösung 2: `act` - GitHub Actions lokal (OPTIONAL)

### Vorteile
- ✅ **Identische Umgebung**: Nutzt dieselben Docker Images wie GitHub
- ✅ **Kostenlos**: Keine CI-Minuten verbraucht

### Nachteile
- ❌ **Android SDK Problem**: SDK muss im Container verfügbar sein
- ❌ **Komplexe Einrichtung**: Erfordert SDK-Mounting oder Installation im Container
- ❌ **Langsamer**: Docker-Overhead + SDK-Download

---

## 1. Installation

### Windows (Chocolatey):
```bash
# Docker Desktop installieren (falls nicht vorhanden)
choco install docker-desktop

# act installieren
choco install act-cli

# Oder mit winget:
winget install nektos.act
```

### Alternativen:
```bash
# Mit scoop
scoop install act

# Manuell: Download von https://github.com/nektos/act/releases
```

---

## 2. Setup für Android

### a) `.env` Datei erstellen (Android SDK Path):
```bash
# E:\Dropbox\GIT\paperless client\.env
ANDROID_HOME=C:\Users\marcu\AppData\Local\Android\Sdk
JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.9.10-hotspot
```

### b) `.secrets` Datei (optional, für Secrets):
```bash
# E:\Dropbox\GIT\paperless client\.secrets
GITHUB_TOKEN=ghp_your_token_here
```

### c) `.gitignore` erweitern:
```
.env
.secrets
```

---

## 3. Verwendung

### Alle Workflows testen:
```bash
act
```

### Nur Build & Test Job:
```bash
act -j "Build & Test"
```

### Mit spezifischem Event:
```bash
act push
```

### Dry-run (zeigt was passieren würde):
```bash
act -n
```

### Mit verbose logging:
```bash
act -v
```

---

## 4. Android-spezifische Optimierungen

### Problem: Docker Image zu groß
**Lösung**: Nutze kleineres Base Image

Erstelle `.actrc` Datei:
```bash
# .actrc
-P ubuntu-latest=catthehacker/ubuntu:act-latest
```

### Problem: Android SDK fehlt
**Lösung**: Wird automatisch von `actions/setup-java@v4` installiert (wie in CI)

---

## 5. Pre-commit Hook (STARK EMPFOHLEN! ✅)

Erstelle `.git/hooks/pre-commit`:
```bash
#!/bin/bash

echo "🚀 Running local CI checks before commit..."

# Syntax-Check (schnell)
export JAVA_HOME="C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.9.10-hotspot"
./gradlew compileDebugUnitTestKotlin --no-daemon

if [ $? -ne 0 ]; then
    echo "❌ Test compilation failed! Fix errors before committing."
    exit 1
fi

# Lint Check (simuliert CI)
./gradlew lintDebug --no-daemon

if [ $? -ne 0 ]; then
    echo "❌ Lint check failed! Fix errors before committing."
    exit 1
fi

echo "✅ All checks passed! Safe to commit."
```

Ausführbar machen:
```bash
chmod +x .git/hooks/pre-commit
```

**Vorteil:** Verhindert automatisch fehlgeschlagene CI-Builds nach Push!

---

## 6. Schneller lokaler Test-Workflow (EMPFOHLEN ✅)

### Option A: Lint Check (schnell, zuverlässig) ⚡
```bash
JAVA_HOME="C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.9.10-hotspot" ./gradlew lintDebug --no-daemon
# Dauer: ~45 Sekunden (getestet)
# Ergebnis: app/build/reports/lint-results-debug.html
```

### Option B: Nur Kompilierung (sehr schnell) ⚡⚡
```bash
JAVA_HOME="C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.9.10-hotspot" ./gradlew compileDebugUnitTestKotlin --no-daemon
# Dauer: ~30 Sekunden
# Prüft: Syntax und Typ-Fehler
```

### Option C: Vollständige Unit Tests (mittel)
```bash
JAVA_HOME="C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.9.10-hotspot" ./gradlew testDebugUnitTest --no-daemon
# Dauer: 2-3 Minuten
# Prüft: Alle Unit Tests
```

### Option D: act (NICHT EMPFOHLEN für Android)
```bash
act -j lint  # Funktioniert NICHT ohne SDK im Container
```

---

## 7. Empfohlener Workflow (✅ BEST PRACTICE)

**Vor jedem Commit (ZWINGEND):**
```bash
# 1. Schneller Syntax-Check (30 Sek)
JAVA_HOME="C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.9.10-hotspot" ./gradlew compileDebugUnitTestKotlin --no-daemon

# 2. Lint Check lokal (45 Sek) - SIMULIERT CI LINT JOB
JAVA_HOME="C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.9.10-hotspot" ./gradlew lintDebug --no-daemon
```

**Optional - Vor wichtigen Pushes:**
```bash
# Vollständige Unit Tests (2-3 Min)
JAVA_HOME="C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.9.10-hotspot" ./gradlew testDebugUnitTest --no-daemon
```

**NICHT EMPFOHLEN für Android:**
```bash
# act funktioniert nicht ohne SDK im Container
act -j "Lint Check"  # ❌ Schlägt fehl
```

---

## 8. Troubleshooting

### act: "SDK location not found" Fehler (BEKANNTES PROBLEM)
```
Error: Could not determine the dependencies of task ':app:lintReportDebug'.
> SDK location not found. Define a valid SDK location with an ANDROID_HOME
  environment variable or by setting the sdk.dir path in your project's
  local properties file
```

**Ursache:** Android SDK ist im Docker Container nicht verfügbar.

**Lösung:** Verwende stattdessen **direkte Gradle-Befehle** (siehe oben).

**Technischer Hintergrund:**
- act läuft in Docker Container mit Linux
- Android SDK ist auf Windows installiert (`C:\Users\...\Android\Sdk`)
- Container kann nicht auf Windows-Pfade zugreifen
- SDK müsste in Container gemountet oder installiert werden (komplex)

### act startet nicht
```bash
# Check Docker läuft
docker info

# Update act
choco upgrade act-cli
```

### Tests hängen in Docker
```bash
# Erhöhe Docker Memory in Docker Desktop Settings
# Empfohlen: 8GB RAM, 4 CPUs
```

### "insufficient memory" Fehler
```bash
# In build.gradle.kts:
testOptions {
    unitTests {
        all {
            it.maxHeapSize = "2048m"  # Reduziert für Docker
        }
    }
}
```

---

## Quellen & Weitere Infos

- [nektos/act auf GitHub](https://github.com/nektos/act)
- [Testing GitHub Actions for Android locally with Docker](https://proandroiddev.com/testing-github-actions-workflows-for-android-locally-with-docker-eb73b683dc34)
- [BrowserStack Guide: Test GitHub Actions locally](https://www.browserstack.com/guide/test-github-actions-locally)
- [droidcon: Testing GitHub Actions workflows locally](https://www.droidcon.com/2022/12/22/testing-github-actions-workflows-for-android-locally-with-docker/)

---

## 🎯 Zusammenfassung

**FÜR ANDROID-PROJEKTE:**
- ✅ **EMPFOHLEN:** Direkte Gradle-Befehle (`lintDebug`, `compileDebugUnitTestKotlin`)
  - Schnell (30-45 Sek)
  - Zuverlässig
  - Simuliert CI Lint Check genau

- ❌ **NICHT EMPFOHLEN:** act
  - Android SDK nicht im Docker Container verfügbar
  - Komplex einzurichten
  - Kein Vorteil gegenüber direkten Gradle-Befehlen

**BEST PRACTICE VOR JEDEM COMMIT:**
```bash
# 1. Syntax-Check (30 Sek)
JAVA_HOME="C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.9.10-hotspot" ./gradlew compileDebugUnitTestKotlin --no-daemon

# 2. Lint Check (45 Sek)
JAVA_HOME="C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.9.10-hotspot" ./gradlew lintDebug --no-daemon
```

**Ergebnis:** Keine CI-Fehler mehr nach Push! 🎉
