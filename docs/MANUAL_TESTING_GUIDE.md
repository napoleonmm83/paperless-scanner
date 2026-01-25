# Manual Testing Guide - Dark/Light Mode Kontrast

Dieser Guide erklärt **WIE** die Manual Tests aus `MANUAL_TESTING_CHECKLIST.md` durchgeführt werden.

---

## 🎯 Ziel

WCAG AA Compliance für Dark/Light Mode sicherstellen:
- **Text/Icons:** Mindestens 4.5:1 Kontrast-Ratio
- **UI Components:** Mindestens 3:1 Kontrast-Ratio

---

## 🛠️ Vorbereitung

### 1. App auf Test-Gerät installieren

```bash
# Debug Build erstellen
cd "E:\Dropbox\GIT\paperless client"
./gradlew assembleDebug

# APK installieren (Gerät per USB verbunden)
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Alternativ: APK manuell übertragen und installieren
```

### 2. Theme-Modi wechseln

**Dark Mode aktivieren:**
```
Android Settings → Display → Dark Theme → ON
```

**Light Mode aktivieren:**
```
Android Settings → Display → Dark Theme → OFF
```

**Tipp:** Nutze Quick Settings Panel (von oben wischen) für schnellen Wechsel.

### 3. Screenshots erstellen

**Methode 1: Direkt auf Gerät**
```
Power + Volume Down (gleichzeitig drücken)
```
Screenshots landen in: `Pictures/Screenshots/`

**Methode 2: Über ADB**
```bash
# Screenshot aufnehmen
adb exec-out screencap -p > screenshot.png

# Oder mit Timestamp
adb exec-out screencap -p > "dark_mode_$(date +%Y%m%d_%H%M%S).png"
```

**Methode 3: Android Studio Device Manager**
```
View → Tool Windows → Device Manager → Screenshot Icon
```

### 4. Screenshot-Ordner erstellen

```bash
cd "E:\Dropbox\GIT\paperless client"
mkdir -p docs/screenshots/dark
mkdir -p docs/screenshots/light
```

---

## 📸 Screenshot-Workflow

### Für jede Komponente:

1. **Dark Mode Screenshot**
   ```bash
   # Dark Mode aktivieren
   # App öffnen, zu relevanter Screen navigieren
   # Screenshot machen (Power + Vol Down)
   # In docs/screenshots/dark/ speichern
   ```

2. **Light Mode Screenshot**
   ```bash
   # Light Mode aktivieren (Dark Theme OFF)
   # Screenshot machen
   # In docs/screenshots/light/ speichern
   ```

3. **Naming Convention:**
   ```
   {komponente}_{state}.png

   Beispiele:
   - bottom_nav_selected.png
   - bottom_nav_unselected.png
   - server_banner_offline.png
   - switch_enabled.png
   - snackbar_success.png
   ```

---

## 🎨 Kontrast-Ratio messen

### Methode 1: WebAIM Contrast Checker (Empfohlen)

1. **Screenshot in Bildbearbeitung öffnen** (z.B. Paint, GIMP, Photoshop)
2. **Farben mit Pipette/Eyedropper Tool extrahieren:**
   - Vordergrund-Farbe (Text/Icon)
   - Hintergrund-Farbe
3. **Hex-Werte notieren** (z.B. `#E1FF8D`, `#141414`)
4. **WebAIM Checker öffnen:** https://webaim.org/resources/contrastchecker/
5. **Werte eingeben:**
   - Foreground: `#E1FF8D`
   - Background: `#141414`
6. **Ratio ablesen:**
   - Normal Text: Mindestens 4.5:1 (WCAG AA)
   - Large Text: Mindestens 3:1
   - UI Components: Mindestens 3:1

**Beispiel-Screenshot:**
```
WebAIM Result:
Foreground: #E1FF8D
Background: #141414
Ratio: 12.8:1
WCAG AA: ✅ PASS
WCAG AAA: ✅ PASS
```

### Methode 2: Digital Color Meter (macOS)

```bash
# App starten
open -a "Digital Color Meter"

# Einstellungen:
- Display in Generic RGB
- View → Display Values → Hexadecimal
```

1. **Cursor über Text/Icon positionieren** → Hex-Wert notieren
2. **Cursor über Background positionieren** → Hex-Wert notieren
3. **Werte in WebAIM eingeben**

### Methode 3: Android Studio Layout Inspector

```
Tools → Layout Inspector → Connect to Device
```

1. **Komponent auswählen** (z.B. TextView, Icon)
2. **Attributes Panel:** Finde `textColor` oder `tint`
3. **Background Color** vom Parent Container finden
4. **Werte in WebAIM eingeben**

---

## 🧪 Test-Szenarien ausführen

### 1. Bottom Navigation Bar testen

**Ziel:** Alle 4 Tabs in selected/unselected Zustand screenshotten

**Schritte:**
```
1. App öffnen → HomeScreen
2. Dark Mode aktivieren
3. Screenshot: bottom_nav_selected.png (HomeScreen Tab ist selected)
4. Auf DocumentsScreen tippen
5. Screenshot: bottom_nav_unselected.png (HomeScreen Tab ist jetzt unselected)
6. Wiederhole für alle Tabs: Home, Documents, Labels, Settings
7. Light Mode aktivieren
8. Wiederhole Schritte 2-6
```

**Checklist in MANUAL_TESTING_CHECKLIST.md abhaken:**
```markdown
- [x] Tab Selected State - Dark Mode - Screenshot: ✅
- [x] Tab Unselected State - Dark Mode - Screenshot: ✅
- [x] Tab Selected State - Light Mode - Screenshot: ✅
- [x] Tab Unselected State - Light Mode - Screenshot: ✅
```

---

### 2. Server Offline Banner testen

**Ziel:** Banner bei verschiedenen Offline-Szenarien testen

**Setup: Server offline schalten**

**Option A: Docker Container stoppen**
```bash
docker stop paperless
```

**Option B: Ungültige Server-URL**
```
App → Settings → Server URL ändern zu:
http://invalid.local:8000
```

**Option C: Netzwerk deaktivieren**
```
Flugmodus aktivieren
```

**Schritte:**
```
1. Server offline schalten (Option A/B/C)
2. App öffnen → HomeScreen
3. Dark Mode aktivieren
4. Warten bis Banner erscheint (Server-Health-Check läuft alle 30s)
5. Screenshot: server_banner_offline.png
6. Retry-Button antippen → Screenshot (falls Animation/State ändert)
7. Light Mode aktivieren
8. Screenshot: server_banner_offline_light.png
9. Server wieder online schalten
```

**Checklist abhaken:**
```markdown
- [x] Banner Background & Text - Dark Mode - Screenshot: ✅
- [x] Action Buttons - Dark Mode - Screenshot: ✅
- [x] Banner Background & Text - Light Mode - Screenshot: ✅
```

---

### 3. Settings Toggles testen

**Ziel:** Switch-Komponenten in enabled/disabled Zustand screenshotten

**Schritte:**
```
1. App → Settings Screen
2. Dark Mode aktivieren
3. Finde einen Switch (z.B. "Dark Mode" Toggle)
4. Switch ON → Screenshot: switch_enabled.png
5. Switch OFF → Screenshot: switch_disabled.png
6. Light Mode aktivieren
7. Wiederhole Schritte 4-5
```

**Alle Toggles testen:**
```
Settings → Nach unten scrollen → Alle Switch-Komponenten finden:
- Dark Mode Toggle
- Auto-Upload Toggle (falls vorhanden)
- Notifications Toggle (falls vorhanden)
- ... (alle anderen)
```

**Checklist abhaken:**
```markdown
- [x] Switch Enabled (ON) - Dark Mode - Screenshot: ✅
- [x] Switch Disabled (OFF) - Dark Mode - Screenshot: ✅
- [x] Switch Enabled (ON) - Light Mode - Screenshot: ✅
- [x] Switch Disabled (OFF) - Light Mode - Screenshot: ✅
```

---

### 4. Snackbars testen

**Ziel:** Verschiedene Snackbar-Typen triggern und screenshotten

**Setup: Snackbar-Trigger-Actions**

**Success Snackbar triggern:**
```
1. ScanScreen öffnen
2. Gallery Button antippen
3. Bild auswählen
4. Upload abschließen
→ Snackbar erscheint: "Dokument erfolgreich hochgeladen"
```

**Error Snackbar triggern:**
```
1. Server offline schalten
2. Dokument scannen
3. Upload versuchen
→ Snackbar erscheint: "Upload fehlgeschlagen"
```

**Info Snackbar triggern:**
```
(App-spezifisch - z.B. bei Info-Messages)
```

**Upload Snackbar triggern:**
```
1. Dokument scannen
2. Upload-Button drücken
→ Snackbar erscheint: "Wird hochgeladen..."
```

**Schritte:**
```
1. Dark Mode aktivieren
2. Success Snackbar triggern → Screenshot: snackbar_success.png
3. Error Snackbar triggern → Screenshot: snackbar_error.png
4. Info Snackbar triggern → Screenshot: snackbar_info.png
5. Upload Snackbar triggern → Screenshot: snackbar_upload.png
6. Light Mode aktivieren
7. Wiederhole Schritte 2-5
```

**Checklist abhaken:**
```markdown
- [x] Success Snackbar - Dark Mode - Screenshot: ✅
- [x] Error Snackbar - Dark Mode - Screenshot: ✅
- [x] Info Snackbar - Dark Mode - Screenshot: ✅
- [x] Upload Snackbar - Dark Mode - Screenshot: ✅
- [x] Success Snackbar - Light Mode - Screenshot: ✅
- [x] Error Snackbar - Light Mode - Screenshot: ✅
```

---

## 📊 Ergebnisse dokumentieren

### 1. Kontrast-Ratios messen

Für JEDE Komponente:
```
1. Screenshot in Bildbearbeitung öffnen
2. Vordergrund-Farbe (Text/Icon) mit Pipette extrahieren
3. Hintergrund-Farbe extrahieren
4. WebAIM Checker: Ratio berechnen
5. In MANUAL_TESTING_CHECKLIST.md eintragen:

Beispiel:
- [x] Tab Selected State
  - Expected Ratio: ≥ 4.5:1
  - Actual Ratio: **12.8:1** ✅
  - Screenshot: screenshots/dark/bottom_nav_selected.png
```

### 2. Issues dokumentieren

**Falls Ratio NICHT ausreichend:**
```markdown
### Kritische Issues (Blocker)
| Komponente | Mode | Issue | Ratio | Status |
|------------|------|-------|-------|--------|
| Bottom Nav | Dark | Text zu dunkel | 2.8:1 | ❌ FAIL |
```

**In DARK_LIGHT_MODE_AUDIT.md übertragen:**
```markdown
## Critical Issues

### Critical #14: Bottom Navigation Selected Text - Dark Mode
**Location:** `app/src/main/java/com/paperless/scanner/ui/components/BottomNavBar.kt:123`
**Issue:** Text Kontrast unzureichend
**Measured Ratio:** 2.8:1 (WCAG AA requires 4.5:1)
**Fix:** Replace with higher contrast color
```

---

## ✅ Completion Criteria

**Alle Tests abgeschlossen wenn:**

- [ ] Alle 4 Komponenten getestet (Bottom Nav, Banner, Toggles, Snackbars)
- [ ] Alle Dark + Light Mode Screenshots vorhanden (min. 20 Screenshots)
- [ ] Alle Kontrast-Ratios gemessen und dokumentiert
- [ ] MANUAL_TESTING_CHECKLIST.md vollständig ausgefüllt
- [ ] Issues (falls vorhanden) in DARK_LIGHT_MODE_AUDIT.md übertragen
- [ ] Screenshots committed zu Git (in `docs/screenshots/`)

---

## 🚀 Nächste Schritte

Nach Completion:

1. **Archon Task updaten:**
   ```bash
   # Falls alle Tests PASS:
   manage_task("update", task_id="...", status="done")

   # Falls kritische Issues gefunden:
   manage_task("update", task_id="...", status="review")
   # → Neue Tasks erstellen für Fixes
   ```

2. **Git Commit:**
   ```bash
   git add docs/MANUAL_TESTING_CHECKLIST.md
   git add docs/MANUAL_TESTING_GUIDE.md
   git add docs/screenshots/
   git add docs/DARK_LIGHT_MODE_AUDIT.md
   git commit -m "docs: add manual testing results for Dark/Light mode contrast"
   ```

3. **Start P0 Task #2:**
   ```
   P0: Kamera-UI Kontrast-Validierung gegen Dokumentenhintergründe
   ```

---

## 📞 Support

**Bei Problemen:**
- Screenshots lassen sich nicht erstellen → Android Studio Device Manager nutzen
- Kontrast-Ratio unklar → WebAIM Checker nutzen, Tutorial: https://webaim.org/articles/contrast/
- Komponente nicht sichtbar → App-State prüfen (Server online/offline, Login-Status)
