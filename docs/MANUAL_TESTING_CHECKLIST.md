# Manual Testing Checklist - Dark/Light Mode Kontrast

**Status:** In Bearbeitung
**Datum:** 2026-01-25
**Tester:** TBD (User)
**Ziel:** WCAG AA Compliance (4.5:1 für Text, 3:1 für UI Components)

---

## 📋 Test-Setup

### Geräte-Anforderungen
- [ ] **Test-Gerät:** Android Phone/Tablet mit Min SDK 26+
- [ ] **Display-Helligkeit:** Test bei MIN (0%) und MAX (100%)
- [ ] **Ambient Light:** Test bei Tag (hell) und Nacht (dunkel)

### Theme-Modi
- [ ] **Dark Mode aktiviert** (System Settings → Display → Dark Theme)
- [ ] **Light Mode aktiviert** (Dark Theme OFF)

### Test-Tools
- [ ] **WebAIM Contrast Checker:** https://webaim.org/resources/contrastchecker/
- [ ] **Screenshot Tool:** adb screenshot oder direkt auf Gerät
- [ ] **Color Picker:** Digital Color Meter (macOS) oder Pixel Zoomer (Android)

---

## 🧪 Test-Szenarien

### 1. Bottom Navigation Bar

**Komponente:** `app/src/main/java/com/paperless/scanner/ui/components/BottomNavBar.kt`

#### Dark Mode
- [ ] **Tab Selected State**
  - Icon Farbe: `MaterialTheme.colorScheme.primary` (#E1FF8D neon-gelb)
  - Text Farbe: `MaterialTheme.colorScheme.primary`
  - Hintergrund: `MaterialTheme.colorScheme.surface` (#141414 dunkelgrau)
  - **Expected Ratio:** ≥ 4.5:1 (Text), ≥ 3:1 (Icon)
  - **Actual Ratio:** _______
  - **Screenshot:** `screenshots/dark/bottom_nav_selected.png`

- [ ] **Tab Unselected State**
  - Icon Farbe: `MaterialTheme.colorScheme.onSurfaceVariant`
  - Text Farbe: `MaterialTheme.colorScheme.onSurfaceVariant`
  - Hintergrund: `MaterialTheme.colorScheme.surface`
  - **Expected Ratio:** ≥ 4.5:1
  - **Actual Ratio:** _______
  - **Screenshot:** `screenshots/dark/bottom_nav_unselected.png`

- [ ] **Tab Hover/Press State** (falls vorhanden)
  - Ripple-Effekt sichtbar: Ja / Nein
  - Keine störenden Transparenz-Artefakte

#### Light Mode
- [ ] **Tab Selected State**
  - Icon Farbe: `MaterialTheme.colorScheme.primary` (dunkel)
  - Text Farbe: `MaterialTheme.colorScheme.primary`
  - Hintergrund: Hell
  - **Expected Ratio:** ≥ 4.5:1
  - **Actual Ratio:** _______
  - **Screenshot:** `screenshots/light/bottom_nav_selected.png`

- [ ] **Tab Unselected State**
  - Icon Farbe: `MaterialTheme.colorScheme.onSurfaceVariant`
  - Text Farbe: `MaterialTheme.colorScheme.onSurfaceVariant`
  - **Expected Ratio:** ≥ 4.5:1
  - **Actual Ratio:** _______
  - **Screenshot:** `screenshots/light/bottom_nav_unselected.png`

---

### 2. Server Offline Banner

**Komponente:** `app/src/main/java/com/paperless/scanner/ui/components/ServerOfflineBanner.kt`

**Setup:** Docker Container stoppen oder Server-URL ungültig machen

#### Dark Mode
- [ ] **Banner Background & Text**
  - Container: `MaterialTheme.colorScheme.errorContainer` (dunkel-rot)
  - Text: `MaterialTheme.colorScheme.onErrorContainer` (hell)
  - Icon: `MaterialTheme.colorScheme.error` (neon-rot)
  - **Expected Ratio:** ≥ 4.5:1 (Text), ≥ 3:1 (Icon)
  - **Actual Ratio:** _______
  - **Screenshot:** `screenshots/dark/server_banner_offline.png`

- [ ] **Action Buttons (Retry, Settings)**
  - Icon Farbe: `MaterialTheme.colorScheme.onErrorContainer`
  - Touch Target Size: ≥ 48dp × 48dp
  - **Sichtbarkeit:** Klar erkennbar / Schwer erkennbar
  - **Screenshot:** `screenshots/dark/server_banner_buttons.png`

#### Light Mode
- [ ] **Banner Background & Text**
  - Container: `MaterialTheme.colorScheme.errorContainer` (hell-rot)
  - Text: `MaterialTheme.colorScheme.onErrorContainer` (dunkel)
  - **Expected Ratio:** ≥ 4.5:1
  - **Actual Ratio:** _______
  - **Screenshot:** `screenshots/light/server_banner_offline.png`

- [ ] **Action Buttons**
  - Icon Farbe lesbar: Ja / Nein
  - **Screenshot:** `screenshots/light/server_banner_buttons.png`

---

### 3. Settings Toggles (Switch)

**Komponente:** `app/src/main/java/com/paperless/scanner/ui/screens/settings/SettingsScreen.kt`

**Setup:** Navigation → Settings Screen

#### Dark Mode
- [ ] **Switch Enabled (ON)**
  - Track Color: `MaterialTheme.colorScheme.primary` (#E1FF8D neon-gelb)
  - Thumb Color: `MaterialTheme.colorScheme.onPrimary` (schwarz)
  - **Kontrast Track/Thumb:** ≥ 3:1
  - **Sichtbarkeit:** Klar erkennbar / Schwer erkennbar
  - **Screenshot:** `screenshots/dark/switch_enabled.png`

- [ ] **Switch Disabled (OFF)**
  - Track Color: `MaterialTheme.colorScheme.surfaceVariant`
  - Thumb Color: `MaterialTheme.colorScheme.outline`
  - **Kontrast zum Background:** ≥ 3:1
  - **Sichtbarkeit:** Klar erkennbar / Schwer erkennbar
  - **Screenshot:** `screenshots/dark/switch_disabled.png`

- [ ] **Settings Labels**
  - Text Farbe: `MaterialTheme.colorScheme.onSurface`
  - Secondary Text: `MaterialTheme.colorScheme.onSurfaceVariant`
  - **Expected Ratio:** ≥ 4.5:1 (Primary), ≥ 4.5:1 (Secondary)
  - **Actual Ratio:** _______

#### Light Mode
- [ ] **Switch Enabled (ON)**
  - Track Color: Primary (dunkel)
  - Thumb Color: onPrimary (hell)
  - **Sichtbarkeit:** Klar erkennbar / Schwer erkennbar
  - **Screenshot:** `screenshots/light/switch_enabled.png`

- [ ] **Switch Disabled (OFF)**
  - Track/Thumb erkennbar: Ja / Nein
  - **Screenshot:** `screenshots/light/switch_disabled.png`

- [ ] **Settings Labels**
  - Text lesbar: Ja / Nein
  - Secondary Text lesbar: Ja / Nein

**Zu testende Toggles:**
- [ ] "Dark Mode" Toggle
- [ ] "Auto-Upload" Toggle (falls vorhanden)
- [ ] "Notifications" Toggle (falls vorhanden)
- [ ] Alle anderen Switch-Komponenten

---

### 4. Toast Messages & Snackbars

**Komponente:** `app/src/main/java/com/paperless/scanner/ui/components/CustomSnackbar.kt`

**Setup:** Trigger Snackbar Actions (z.B. Scan abschließen, Upload-Fehler)

#### Dark Mode
- [ ] **Success Snackbar**
  - Background: `MaterialTheme.colorScheme.surface` (#141414)
  - Text: `MaterialTheme.colorScheme.primary` (#E1FF8D)
  - Icon: `MaterialTheme.colorScheme.primary`
  - Border: `MaterialTheme.colorScheme.outline`
  - **Expected Ratio:** ≥ 4.5:1 (Text), ≥ 3:1 (Icon)
  - **Actual Ratio:** _______
  - **Screenshot:** `screenshots/dark/snackbar_success.png`

- [ ] **Error Snackbar**
  - Icon: ErrorOutline (erkennbar: Ja / Nein)
  - Text lesbar: Ja / Nein
  - **Screenshot:** `screenshots/dark/snackbar_error.png`

- [ ] **Info Snackbar**
  - Icon: Info (erkennbar: Ja / Nein)
  - Text lesbar: Ja / Nein
  - **Screenshot:** `screenshots/dark/snackbar_info.png`

- [ ] **Upload Snackbar**
  - Icon: CloudUpload (erkennbar: Ja / Nein)
  - Text lesbar: Ja / Nein
  - **Screenshot:** `screenshots/dark/snackbar_upload.png`

#### Light Mode
- [ ] **Success Snackbar**
  - Background: Hell
  - Text: Dunkel (Primary)
  - **Expected Ratio:** ≥ 4.5:1
  - **Actual Ratio:** _______
  - **Screenshot:** `screenshots/light/snackbar_success.png`

- [ ] **Error Snackbar**
  - Text/Icon lesbar: Ja / Nein
  - **Screenshot:** `screenshots/light/snackbar_error.png`

- [ ] **Info Snackbar**
  - Text/Icon lesbar: Ja / Nein
  - **Screenshot:** `screenshots/light/snackbar_info.png`

- [ ] **Upload Snackbar**
  - Text/Icon lesbar: Ja / Nein
  - **Screenshot:** `screenshots/light/snackbar_upload.png`

---

## 🔍 Edge Cases & Spezielle Tests

### Display-Helligkeit Variationen
- [ ] **Min Brightness (0%)**
  - Dark Mode: Alle Komponenten lesbar
  - Light Mode: Alle Komponenten lesbar

- [ ] **Max Brightness (100%)**
  - Dark Mode: Kein Glare, Text klar
  - Light Mode: Kein Auswaschen, Kontrast bleibt

### Verschiedene Hintergründe
- [ ] **Snackbar auf HomeScreen**
  - Über weißem Hintergrund (Light)
  - Über schwarzem Hintergrund (Dark)
  - Lesbarkeit: Ja / Nein

- [ ] **Snackbar auf ScanScreen**
  - Über Kamera-Vorschau (variabel)
  - Lesbarkeit: Ja / Nein

- [ ] **Banner auf verschiedenen Screens**
  - HomeScreen, SettingsScreen, DocumentsScreen
  - Konsistenz: Ja / Nein

---

## 📊 Test-Ergebnisse Zusammenfassung

### Kritische Issues (Blocker)
| Komponente | Mode | Issue | Ratio | Status |
|------------|------|-------|-------|--------|
| Beispiel: Bottom Nav | Dark | Text zu dunkel | 2.8:1 | ❌ FAIL |

### Moderate Issues (Should Fix)
| Komponente | Mode | Issue | Ratio | Status |
|------------|------|-------|-------|--------|
|            |      |       |       |        |

### Passed Tests ✅
| Komponente | Mode | Ratio | Status |
|------------|------|-------|--------|
|            |      |       |        |

---

## 📝 Notizen & Beobachtungen

### Dark Mode
```
Notizen zu Dark Mode Tests:
-
```

### Light Mode
```
Notizen zu Light Mode Tests:
-
```

---

## ✅ Acceptance Criteria

- [ ] **Alle Text/Icon Elemente ≥ 4.5:1** (WCAG AA Text)
- [ ] **Alle UI Components ≥ 3:1** (WCAG AA UI)
- [ ] **Keine kritischen Alpha-Transparenz Issues**
- [ ] **Screenshots dokumentiert** (min. 2 pro Komponente: Dark + Light)
- [ ] **Findings in DARK_LIGHT_MODE_AUDIT.md** übertragen

---

## 📸 Screenshot-Struktur

```
docs/screenshots/
├── dark/
│   ├── bottom_nav_selected.png
│   ├── bottom_nav_unselected.png
│   ├── server_banner_offline.png
│   ├── server_banner_buttons.png
│   ├── switch_enabled.png
│   ├── switch_disabled.png
│   ├── snackbar_success.png
│   ├── snackbar_error.png
│   ├── snackbar_info.png
│   └── snackbar_upload.png
└── light/
    ├── bottom_nav_selected.png
    ├── bottom_nav_unselected.png
    ├── server_banner_offline.png
    ├── server_banner_buttons.png
    ├── switch_enabled.png
    ├── switch_disabled.png
    ├── snackbar_success.png
    ├── snackbar_error.png
    ├── snackbar_info.png
    └── snackbar_upload.png
```

---

## 🚀 Nächste Schritte nach Completion

1. **Update DARK_LIGHT_MODE_AUDIT.md** mit allen Findings
2. **Fix kritische Issues** (falls vorhanden)
3. **Mark Archon Task** als "review" oder "done"
4. **Start P0 Task #2:** Kamera-UI Kontrast-Validierung
