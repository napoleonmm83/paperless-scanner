# Camera UI Testing Guide - Button Visibility gegen Dokumentenhintergründe

Dieser Guide erklärt **WIE** die Camera UI Tests aus `CAMERA_UI_TESTING_CHECKLIST.md` durchgeführt werden.

---

## 🎯 Ziel

Verifiziere dass **ALLE** Buttons (Scan Options, Viewer Buttons, Source Badges) sichtbar und erkennbar sind, unabhängig vom Dokumentenhintergrund.

**Kritische Frage:** Kollidieren unsere UI-Farben mit typischen Dokumentenfarben?

---

## 📋 Vorbereitung

### 1. Test-Dokumente sammeln

**Du brauchst:**
- ✅ **Weißes Papier** (blanko oder mit schwarzem Text)
- ✅ **Schwarzer Vertrag** (dunkler Hintergrund, heller Text)
- ✅ **Buntes Magazin** (viele Farben, Logos)
- ⚠️ **Neon-Poster** (KRITISCH - neon-gelb, neon-blau, neon-pink)
- ✅ **Glanzpapier** (mit Reflexionen)
- ✅ **Wasserzeichen-Dokument** (Firmenlogo, "CONFIDENTIAL")

**Tipp:** Falls du keine Neon-Poster hast, nutze einen Bildschirm mit Neon-Farben.

### 2. App auf Test-Gerät installieren

```bash
cd "E:\Dropbox\GIT\paperless client"
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 3. Screenshot-Ordner erstellen

```bash
mkdir -p docs/screenshots/camera
```

---

## 🎬 Test-Workflow

### Schritt-für-Schritt für JEDES Szenario:

1. **App öffnen** → ScanScreen
2. **Dokument vorbereiten** (z.B. weißes Papier auf Tisch legen)
3. **Scan Option Cards screenshotten**
   - Power + Vol Down → Screenshot
   - Speichern als: `white_doc_gallery_card.png`
4. **Scan triggern** (Camera Button antippen)
5. **MLKit Scanner scannt Dokument**
6. **Fullscreen Viewer öffnet**
7. **Viewer Buttons screenshotten**
   - Close Button → Screenshot
   - Rotate Button → Screenshot
   - Delete Button → Screenshot
8. **In Checklist** abhaken

---

## 🧪 Szenario-spezifische Anweisungen

### Szenario 1: Weißes Papier

**Ziel:** Testen ob hellblaue/hellviolette Cards gegen weißen Hintergrund sichtbar sind.

```
1. Weißes Papier auf Tisch legen
2. App öffnen → ScanScreen
3. Kamera zeigt weißes Papier
4. Screenshot: Scan Option Cards (Gallery hellblau, Files hellviolett)
   → Sind die Cards sichtbar?
5. Camera Button antippen → MLKit Scanner
6. Scan abschließen
7. Fullscreen Viewer zeigt weißes Dokument
8. Screenshot: Close Button (surfaceVariant)
   → Icon (X) sichtbar gegen weißen Hintergrund?
9. Screenshot: Rotate Button
10. Screenshot: Delete Button (errorContainer - rot)
```

**Erwartetes Problem:**
- **Gallery Card (#8DD7FF hellblau)** könnte auf weißem Hintergrund zu blass sein
- **Fullscreen Buttons (surfaceVariant)** könnten gegen weißes Dokument verschwinden

**Checklist-Eintrag:**
```markdown
- [x] Gallery Import Card - Sichtbar: Mittel (etwas blass)
- [x] Screenshot: screenshots/camera/white_doc_gallery_card.png
```

---

### Szenario 2: Schwarzer Vertrag

**Ziel:** Testen ob Buttons gegen dunklen Hintergrund erkennbar sind.

```
1. Schwarzen Vertrag (oder schwarzes Papier) vorbereiten
2. App öffnen → ScanScreen
3. Kamera zeigt schwarzes Dokument
4. Screenshot: Scan Option Cards
   → Hellblaue/Hellviolette Cards auf schwarzem Hintergrund sehr kontrastreich?
5. Scan durchführen
6. Fullscreen Viewer zeigt schwarzes Dokument
7. Screenshot: Close Button
   → surfaceVariant Button sichtbar gegen Schwarz?
8. Screenshot: Rotate Button
9. Screenshot: Delete Button
   → errorContainer (rot) gut sichtbar?
```

**Erwartetes Verhalten:**
- **Gallery/Files Cards** sollten SEHR gut sichtbar sein (hell auf dunkel)
- **Fullscreen Buttons (surfaceVariant)** sollten gut kontrastieren

**Problem-Indikator:** Falls Buttons NICHT sichtbar sind, ist das ein kritischer Fehler.

---

### Szenario 3: Buntes Magazin

**Ziel:** Testen ob UI-Farben mit Dokumentenfarben kollidieren.

```
1. Buntes Magazin-Cover mit vielen Farben vorbereiten
2. App öffnen → ScanScreen
3. Kamera zeigt Magazin
4. Screenshot: Scan Option Cards
   → Gibt es Bereiche im Magazin mit ähnlichen Blau/Violett-Tönen?
5. Scan durchführen
6. Fullscreen Viewer zeigt Magazin
7. Teste verschiedene Bereiche des Magazins:
   - Button über Logo → Screenshot
   - Button über farbigem Hintergrund → Screenshot
   - Button über Text → Screenshot
8. Notiere alle Problem-Farben
```

**Kritische Farben identifizieren:**
- Falls Magazin **Hellblau (#8DD7FF)** enthält → Kollidiert mit Gallery Card
- Falls Magazin **Hellviolett (#D7B3FF)** enthält → Kollidiert mit Files Card

**Checklist-Eintrag:**
```markdown
### Problematische Farben
- Magazin enthält Hellblau (#7DC8E8) → Ähnlich zu Gallery Card (#8DD7FF)
- Sichtbarkeit: Schlecht
- Screenshot: screenshots/camera/color_doc_gallery_card.png
```

---

### Szenario 4: Neon-Farben (KRITISCHSTER TEST!)

**Ziel:** Testen ob Primary Button (#E1FF8D neon-gelb) gegen neon-gelbes Dokument sichtbar ist.

#### 4.1 Neon-Gelb (CRITICAL!)

```
1. Neon-gelbes Poster vorbereiten
   ODER: Bildschirm mit #E1FF8D Background anzeigen
2. App öffnen → ScanScreen
3. Kamera zeigt neon-gelbes Dokument
4. Screenshot: CAMERA SCAN FAB (Primary Button)
   → Button ist AUCH neon-gelb (#E1FF8D)!
   → IST DER BUTTON SICHTBAR???
```

**Erwartetes Problem:**
```
Primary Button Farbe: #E1FF8D (neon-gelb)
Dokument Farbe:       #FFFF00 (neon-gelb)
→ Kontrast: SEHR NIEDRIG
→ Button verschwindet!
```

**Falls Button NICHT sichtbar:**
→ **KRITISCHER BUG** - Primary Button muss redesigned werden!

#### 4.2 Neon-Pink

```
1. Neon-pinkes Poster vorbereiten
2. App öffnen → ScanScreen
3. Screenshot: Files Import Card (#D7B3FF hellviolett)
   → Kollision mit neon-pink?
```

#### 4.3 Neon-Blau

```
1. Neon-blaues Poster vorbereiten
2. Screenshot: Gallery Import Card (#8DD7FF hellblau)
   → Kollision mit neon-blau?
```

---

### Szenario 5: Glanzpapier & Reflexionen

**Ziel:** Testen ob Kamera-Reflexionen Buttons verdecken.

```
1. Glanzpapier (Hochglanz-Magazin, Fotopapier) vorbereiten
2. App öffnen → ScanScreen
3. Kamera auf Glanzpapier richten
4. Reflexion der Kamera erscheint auf Papier
5. Screenshot: Buttons MIT Reflexion
   → Sind Buttons trotz Reflexion sichtbar?
6. Verschiedene Winkel testen
```

**Problem-Indikator:**
- Reflexion verdeckt wichtige Buttons → User kann nicht interagieren
- Reflexion macht Text unleserlich

---

## 📸 Screenshot-Naming Convention

**Format:** `{dokumenttyp}_{ui_element}.png`

**Beispiele:**
- `white_doc_gallery_card.png` - Gallery Card auf weißem Dokument
- `black_doc_viewer_close.png` - Close Button im Viewer auf schwarzem Dokument
- `neon_yellow_doc.png` - ALLE Buttons auf neon-gelbem Dokument
- `color_doc_viewer_all.png` - Alle Viewer Buttons auf farbigem Magazin

---

## 🔍 Wie erkenne ich ein Problem?

### Problem-Checkliste:

- [ ] **Button verschwindet** - Button-Farbe zu ähnlich zu Dokumentenfarbe
- [ ] **Icon nicht erkennbar** - Icon-Kontrast zu niedrig
- [ ] **Text unleserlich** - Text-Farbe kollidiert mit Hintergrund
- [ ] **Reflexion verdeckt Button** - Glanzpapier-Reflexion macht UI unbenutzbar

### Severity Rating:

| Severity | Beschreibung | Action |
|----------|--------------|--------|
| **CRITICAL** | Button komplett unsichtbar | Muss gefixt werden |
| **HIGH** | Button schwer erkennbar (5+ Sekunden Suche) | Sollte gefixt werden |
| **MEDIUM** | Button erkennbar, aber nicht optimal | Nice-to-have Fix |
| **LOW** | Button klar sichtbar | Kein Fix nötig |

---

## 📊 Ergebnisse dokumentieren

### 1. In CAMERA_UI_TESTING_CHECKLIST.md

Für JEDES Test-Case:
```markdown
- [x] Gallery Import Card - Weißes Papier
  - Sichtbarkeit: Mittel (etwas blass)
  - Problem: Hellblau #8DD7FF auf Weiß hat nur ~5:1 Kontrast
  - Screenshot: screenshots/camera/white_doc_gallery_card.png
```

### 2. In DARK_LIGHT_MODE_AUDIT.md

Neue Sektion erstellen:
```markdown
## Camera UI Testing Results (P0 Priority)

### Critical Issues Found:
- **Neon-Yellow Document Collision**: Primary FAB (#E1FF8D) verschwindet auf neon-gelben Dokumenten
- **Screenshot:** screenshots/camera/neon_yellow_doc.png
- **Fix:** Add 2dp outline oder dynamischer Background basierend auf Dokument-Helligkeit
```

---

## 💡 Fix-Strategien (falls Probleme gefunden)

### Fix 1: Button Outlines (Empfohlen)

```kotlin
// ScanScreen.kt - Camera FAB
FloatingActionButton(
    containerColor = MaterialTheme.colorScheme.primary,
    contentColor = MaterialTheme.colorScheme.onPrimary,
    // NEU: Add outline
    modifier = Modifier.border(
        width = 2.dp,
        color = MaterialTheme.colorScheme.outline,
        shape = CircleShape
    )
)
```

**Vorteile:**
- Garantiert Sichtbarkeit gegen ALLE Hintergründe
- Folgt "Dark Tech Precision Pro" Design (Borders statt Elevation)

### Fix 2: Dynamischer Background

```kotlin
// Analysiere Dokument-Helligkeit
val brightness = analyzeBrightness(documentBitmap)
val buttonColor = if (brightness > 0.7f) {
    // Helles Dokument → Dunkler Button
    MaterialTheme.colorScheme.surfaceVariant
} else {
    // Dunkles Dokument → Heller Button
    MaterialTheme.colorScheme.primary
}
```

**Nachteile:**
- Komplexer
- Performance-Overhead (Bitmap-Analyse)

### Fix 3: Semi-Transparent Overlay-Bar

```kotlin
// Buttons IMMER auf semi-transparentem Overlay
Box(modifier = Modifier.fillMaxSize()) {
    // Dokument
    AsyncImage(...)

    // Overlay-Bar für Buttons
    Box(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .height(72.dp)
            .background(Color.Black.copy(alpha = 0.5f))
    ) {
        // Buttons hier → Garantiert auf dunklem Hintergrund
        IconButton(onClick = { ... }) {
            Icon(Icons.Default.Close, tint = Color.White)
        }
    }
}
```

**Nachteile:**
- Verdeckt Teil des Dokuments
- Kann gegen "No Alpha" Regel verstoßen (aber 0.5f ist akzeptabel für Overlays)

---

## ✅ Completion Criteria

**Alle Tests abgeschlossen wenn:**

- [ ] Alle 4 Szenarien getestet (Weiß, Schwarz, Farbig, Neon)
- [ ] Min. 15 Screenshots vorhanden
- [ ] CAMERA_UI_TESTING_CHECKLIST.md vollständig ausgefüllt
- [ ] Kritische Issues (falls vorhanden) in DARK_LIGHT_MODE_AUDIT.md dokumentiert
- [ ] Screenshots committed zu Git
- [ ] Archon Task updated (status: "review" oder "done")

---

## 🚀 Nächste Schritte

Nach Completion:

1. **Archon Task updaten:**
   ```bash
   # Falls alle Tests PASS (keine kritischen Issues):
   manage_task("update", task_id="23b82bcc-...", status="done")

   # Falls kritische Issues gefunden:
   manage_task("update", task_id="...", status="review")
   # → Neue Fix-Tasks erstellen
   ```

2. **Git Commit:**
   ```bash
   git add docs/CAMERA_UI_TESTING_CHECKLIST.md
   git add docs/CAMERA_UI_TESTING_GUIDE.md
   git add docs/screenshots/camera/
   git add docs/DARK_LIGHT_MODE_AUDIT.md
   git commit -m "docs: add camera UI contrast testing results

   - Tested all buttons against white, black, colorful, neon documents
   - [CRITICAL] Neon-yellow collision: Primary FAB invisible on neon docs
   - [PASSED] All other scenarios: buttons clearly visible
   - 18 screenshots documented

   Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
   ```

3. **Fix kritische Issues** (falls gefunden):
   - Erstelle neue Archon Task für Fixes
   - Implementiere Fix (z.B. Button Outlines)
   - Re-Test nach Fix

4. **Continue mit P1 Tasks** (Custom Fields, Crop, etc.)

---

## 📞 Support

**Bei Problemen:**
- Kamera funktioniert nicht → Emulator Permissions prüfen
- MLKit Scanner startet nicht → Google Play Services installiert?
- Screenshots zu dunkel → Display-Helligkeit auf Max setzen
- Neon-Poster nicht verfügbar → Bildschirm mit Neon-Farben als Ersatz nutzen
