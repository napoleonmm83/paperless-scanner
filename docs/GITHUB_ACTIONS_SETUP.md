# GitHub Actions Setup für automatisches Deployment

Dieses Dokument beschreibt, wie du GitHub Actions für automatisches Deployment zur Google Play Console einrichtest.

---

## Übersicht der Workflows

### 1. **android-ci.yml** (CI Build)
- **Trigger:** Push/PR auf `main` oder `develop`
- **Funktion:** Unit Tests + Build Debug APK + Lint
- **Output:** Debug APK als Artifact

### 2. **auto-deploy-internal.yml** ⭐ NEU - Automatisches Deployment
- **Trigger:** Push auf `main` (außer Dokumentation)
- **Funktion:**
  1. Unit Tests ausführen
  2. Patch-Version automatisch erhöhen
  3. Release AAB bauen
  4. Automatisch zu Internal Track deployen
- **Output:** Version Bump Commit + AAB auf Google Play

### 3. **deploy.yml** (Manuelles Deployment)
- **Trigger:** Manuell (workflow_dispatch)
- **Funktion:** Deploy zu Internal/Alpha/Beta/Production Track
- **Nutzung:** Für gezielte Deployments zu spezifischen Tracks

### 4. **release.yml** (Release Build)
- **Trigger:** Git Tag `v*` oder manuell
- **Funktion:** Release APK bauen + GitHub Release erstellen

---

## Benötigte GitHub Secrets

Gehe zu: **GitHub Repo → Settings → Secrets and variables → Actions**

### 1. **KEYSTORE_BASE64** (Required)
Android Signing Keystore als Base64.

**Erstellen:**
```bash
# 1. Erstelle Keystore (falls nicht vorhanden)
keytool -genkey -v -keystore paperless-scanner.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias paperless-key

# 2. Konvertiere zu Base64
base64 -w 0 paperless-scanner.jks > keystore.b64

# 3. Kopiere Inhalt von keystore.b64 in GitHub Secret
# Windows: cat keystore.b64 | clip
# macOS: cat keystore.b64 | pbcopy
# Linux: cat keystore.b64
```

### 2. **KEYSTORE_PASSWORD** (Required)
Passwort für den Keystore.

**Wert:** Das Passwort, das du beim Erstellen des Keystores eingegeben hast.

### 3. **KEY_ALIAS** (Required)
Alias des Keys im Keystore.

**Wert:** Der Alias-Name (z.B. `paperless-key`)

### 4. **KEY_PASSWORD** (Required)
Passwort für den Key (kann identisch mit KEYSTORE_PASSWORD sein).

**Wert:** Das Key-Passwort.

### 5. **PLAY_STORE_KEY_JSON** (Required für Deployment)
Service Account JSON für Google Play Console API-Zugriff.

**Erstellen:**

1. **Google Cloud Console öffnen:**
   - Gehe zu: https://console.cloud.google.com/
   - Wähle dein Projekt (oder erstelle ein neues)

2. **Service Account erstellen:**
   ```
   IAM & Admin → Service Accounts → CREATE SERVICE ACCOUNT

   Name: github-actions-deploy
   Role: Service Account User

   → Create Key → JSON → Download
   ```

3. **Play Console zugriff geben:**
   - Gehe zu: https://play.google.com/console/
   - Wähle deine App
   - **Setup → API access**
   - Verknüpfe das Google Cloud Projekt
   - Gehe zu **Service Accounts**
   - Finde `github-actions-deploy@...`
   - Klicke **Grant Access**
   - Wähle Rolle: **Release Manager** oder **Admin**
   - Speichern

4. **JSON zu Base64 konvertieren:**
   ```bash
   # Linux/macOS:
   base64 -w 0 service-account-key.json > play-store-key.b64

   # Windows:
   certutil -encode service-account-key.json play-store-key.b64
   # (Entferne die -----BEGIN/END----- Zeilen manuell)
   ```

5. **Inhalt in GitHub Secret speichern:**
   - Name: `PLAY_STORE_KEY_JSON`
   - Value: Base64-kodierter JSON-Inhalt

---

## Secrets in GitHub setzen

```bash
# Via GitHub CLI (empfohlen)
gh secret set KEYSTORE_BASE64 < keystore.b64
gh secret set KEYSTORE_PASSWORD
gh secret set KEY_ALIAS
gh secret set KEY_PASSWORD
gh secret set PLAY_STORE_KEY_JSON < play-store-key.b64

# Oder manuell via GitHub UI:
# Settings → Secrets and variables → Actions → New repository secret
```

---

## Workflow-Verhalten

### Bei Push auf `main`:

1. **CI Build** (`android-ci.yml`) startet parallel:
   - Build & Test Job
   - Lint Job

2. **Auto Deploy** (`auto-deploy-internal.yml`) startet:
   - Wartet auf erfolgreichen Test-Job
   - Bumpt Patch-Version (z.B. `1.4.1` → `1.4.2`)
   - Committed Version Bump mit `[skip ci]` (verhindert Endlosschleife)
   - Baut Release AAB
   - Deployed zu Internal Track

3. **Ergebnis:**
   - Neuer Commit in `main` mit Version Bump
   - AAB verfügbar in Google Play Console → Internal Testing
   - GitHub Action Summary mit Deployment-Info

### Bei Git Tag `v*`:

1. **Release Build** (`release.yml`) startet:
   - Unit Tests
   - Build Release APK
   - Sign APK (falls Keystore vorhanden)
   - Erstellt GitHub Release mit APK

### Manuelles Deployment:

1. Gehe zu: **Actions → Deploy to Play Store → Run workflow**
2. Wähle Track: `internal` / `alpha` / `beta` / `production`
3. Workflow baut AAB und deployed

---

## Lokales Testing (ohne GitHub)

Du kannst Deployment lokal mit Fastlane testen:

```bash
# 1. Service Account JSON platzieren
cp service-account-key.json fastlane/play-store-key.json

# 2. Keystore vorbereiten (falls nicht in gradle.properties)
export KEYSTORE_FILE=/path/to/keystore.jks
export KEYSTORE_PASSWORD=your_password
export KEY_ALIAS=your_alias
export KEY_PASSWORD=your_key_password

# 3. Bundle bauen
./gradlew bundleRelease

# 4. Zu Internal Track deployen
fastlane android internal
```

---

## Troubleshooting

### "Could not find service account key"
- Überprüfe `PLAY_STORE_KEY_JSON` Secret
- Stelle sicher, dass Base64-Kodierung korrekt ist (keine Zeilenumbrüche auf Windows!)
- Verwende `base64 -w 0` (Linux) oder `certutil -encode` (Windows)

### "Version code already exists"
- Manuell Version in `version.properties` erhöhen
- Oder warte bis Auto-Deployment die Version bumpt

### "Service account has no access"
- Gehe zu Play Console → Setup → API Access
- Prüfe Service Account Permissions (mind. "Release Manager")

### "Keystore was tampered with"
- Keystore-Passwort in Secret überprüfen
- Base64-Dekodierung testen:
  ```bash
  echo "$KEYSTORE_BASE64" | base64 --decode > test.jks
  keytool -list -v -keystore test.jks
  ```

### "Tests failed"
- Auto-Deployment wird übersprungen
- Prüfe CI-Build Logs für Fehlerdetails
- Fixe Tests und pushe erneut

---

## Best Practices

1. **Development Branch:**
   - Arbeite in Feature-Branches
   - Merge zu `develop` für Testing
   - Merge `develop` → `main` nur für Releases

2. **Version Management:**
   - Auto-Deployment bumpt automatisch Patch-Version
   - Für Minor/Major Updates: Manuell in `version.properties` ändern

3. **Deployment Tracks:**
   - **Internal:** Automatisch bei jedem Push auf `main`
   - **Alpha/Beta:** Manuell via `deploy.yml` Workflow
   - **Production:** Manuell via `deploy.yml` oder Promote in Play Console

4. **Rollback:**
   - Play Console: Deaktiviere problematisches Release
   - Git: Revert Commit und pushe
   - Auto-Deploy erstellt neues Release mit höherer Version

---

## Sicherheit

⚠️ **NIEMALS Secrets im Code committen!**

- Keystore-Dateien → `.gitignore`
- Service Account JSON → `.gitignore`
- Passwörter nur in GitHub Secrets
- `fastlane/play-store-key.json` ist in `.gitignore`

---

## Workflow aktivieren/deaktivieren

### Auto-Deploy deaktivieren:
Kommentiere in `.github/workflows/auto-deploy-internal.yml`:

```yaml
on:
  # push:
  #   branches:
  #     - main
  workflow_dispatch:  # Nur manuell
```

### Auto-Deploy nur für bestimmte Pfade:
```yaml
on:
  push:
    branches:
      - main
    paths:
      - 'app/src/**'
      - 'build.gradle.kts'
      - 'version.properties'
```

---

## Nützliche Links

- [Fastlane Docs](https://docs.fastlane.tools/)
- [Google Play Console API](https://developers.google.com/android-publisher)
- [GitHub Actions Docs](https://docs.github.com/en/actions)
- [Android App Signing](https://developer.android.com/studio/publish/app-signing)

---

**Setup abgeschlossen?** → Push Code und beobachte den Workflow unter: **GitHub → Actions**
