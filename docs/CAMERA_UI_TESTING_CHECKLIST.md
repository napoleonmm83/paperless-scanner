# Camera UI Contrast Testing Checklist

**Status:** In Bearbeitung
**Datum:** 2026-01-25
**Tester:** TBD (User)
**Ziel:** Verifiziere Button-Sichtbarkeit gegen verschiedene Dokumentenhintergründe

---

## 📋 Test-Setup

### Geräte-Anforderungen
- [ ] **Test-Gerät:** Android Phone/Tablet mit Min SDK 26+
- [ ] **Kamera:** Rückseite-Kamera funktionstüchtig
- [ ] **Test-Dokumente:** Vorbereitet (siehe Scan-Szenarien unten)

### Kamera-Modi
- [ ] **MLKit Document Scanner** - Standard Scan-Flow
- [ ] **Gallery Import** - Bereits aufgenommene Fotos
- [ ] **Files Import** - PDFs/Bilder aus File System

### Test-Tools
- [ ] **Real Documents:** Weißes Papier, schwarzer Vertrag, farbiges Magazin, Neon-Poster
- [ ] **Screenshot Tool:** Power + Vol Down oder adb screenshot
- [ ] **Notizen:** Für jedes Problem-Szenario

---

## 🎨 Button Color Analysis (aus ByteRover Context)

**Dark Tech Precision Pro Design:**
- **Background:** #0A0A0A (tiefes Schwarz)
- **Primary:** #E1FF8D (neon-gelb)
- **Buttons:** Nutzen `surfaceVariant` / `onSurfaceVariant` (theme-aware)
- **Delete Buttons:** `errorContainer` / `onErrorContainer`
- **Design Pattern:** Borders statt Elevation, keine Alpha-Transparenz

**Button-zu-Code Mapping:**

| Button | Background Color | Icon/Text Color | Location (ScanScreen.kt) | Shape |
|--------|------------------|-----------------|--------------------------|-------|
| **Camera Scan FAB** | `primary` (#E1FF8D) | `onPrimary` (schwarz) | Line ~XXX | Circle |
| **Gallery Import Card** | Custom `Color(0xFF8DD7FF)` | `Color.Black` (fixed) | Line 416 | RoundedCornerShape(20dp) |
| **Files Import Card** | Custom `Color(0xFFD7B3FF)` | `Color.Black` (fixed) | Line 426 | RoundedCornerShape(20dp) |
| **Fullscreen Close Button** | `surfaceVariant` | `onSurfaceVariant` | Line 991 | CircleShape |
| **Fullscreen Rotate Button** | `surfaceVariant` | `onSurfaceVariant` | Line 1031 | CircleShape |
| **Fullscreen Delete Button** | `errorContainer` | `onErrorContainer` | Line 1065 | CircleShape |
| **Source Badges** | `tertiaryContainer` | `onTertiaryContainer` | PageThumbnail | 24dp Circle |

---

## 🧪 Scan-Szenarien & Test-Cases

### Szenario 1: Sehr helle Dokumente (White Paper Test)

**Setup:**
- Weißes Druckerpapier (blanko oder mit schwarzem Text)
- Helle Rechnung (weiß/beige)
- Heller Kontoauszug

**Zu testen:**

#### 1.1 Scan Option Cards (Startscreen)
- [ ] **Gallery Import Card** (hellblau `#8DD7FF`)
  - Karte sichtbar gegen weißen Hintergrund: Ja / Nein
  - Icon (PhotoLibrary) erkennbar: Ja / Nein
  - Text "Gallery" lesbar: Ja / Nein
  - **Problem:** ___________
  - **Screenshot:** `screenshots/camera/white_doc_gallery_card.png`

- [ ] **Files Import Card** (hellviolett `#D7B3FF`)
  - Karte sichtbar: Ja / Nein
  - Icon (Folder) erkennbar: Ja / Nein
  - Text "Files" lesbar: Ja / Nein
  - **Problem:** ___________
  - **Screenshot:** `screenshots/camera/white_doc_files_card.png`

#### 1.2 Fullscreen Viewer (nach Scan)
- [ ] **Close Button** (surfaceVariant)
  - Icon (X) sichtbar gegen weißes Dokument: Ja / Nein
  - Button-Background erkennbar: Ja / Nein
  - **Problem:** ___________
  - **Screenshot:** `screenshots/camera/white_doc_viewer_close.png`

- [ ] **Rotate Button** (surfaceVariant)
  - Icon (Rotate) sichtbar: Ja / Nein
  - **Problem:** ___________
  - **Screenshot:** `screenshots/camera/white_doc_viewer_rotate.png`

- [ ] **Delete Button** (errorContainer - rot)
  - Icon (Delete) sichtbar: Ja / Nein
  - Farbe ausreichend prominent: Ja / Nein
  - **Problem:** ___________
  - **Screenshot:** `screenshots/camera/white_doc_viewer_delete.png`

#### 1.3 Source Badges
- [ ] **Camera Badge** (tertiaryContainer)
  - Badge sichtbar auf Page Thumbnail: Ja / Nein
  - Icon erkennbar: Ja / Nein
  - **Screenshot:** `screenshots/camera/white_doc_source_badge.png`

---

### Szenario 2: Sehr dunkle Dokumente (Black Paper Test)

**Setup:**
- Schwarzer Vertrag (dunkler Hintergrund, weißer Text)
- Dunkle Broschüre (dunkelblau/grau)
- Schwarzweißes Dokument (invertiert)

**Zu testen:**

#### 2.1 Scan Option Cards
- [ ] **Gallery Import Card** (hellblau)
  - Karte sichtbar gegen schwarzen Hintergrund: Ja / Nein
  - Kontrast ausreichend: Ja / Nein
  - **Problem:** ___________
  - **Screenshot:** `screenshots/camera/black_doc_gallery_card.png`

- [ ] **Files Import Card** (hellviolett)
  - Karte sichtbar: Ja / Nein
  - Kontrast ausreichend: Ja / Nein
  - **Problem:** ___________
  - **Screenshot:** `screenshots/camera/black_doc_files_card.png`

#### 2.2 Fullscreen Viewer
- [ ] **Close Button** (surfaceVariant)
  - Icon sichtbar gegen schwarzes Dokument: Ja / Nein
  - Button-Background erkennbar: Ja / Nein
  - **Problem:** ___________
  - **Screenshot:** `screenshots/camera/black_doc_viewer_close.png`

- [ ] **Rotate Button**
  - Icon sichtbar: Ja / Nein
  - **Screenshot:** `screenshots/camera/black_doc_viewer_rotate.png`

- [ ] **Delete Button** (errorContainer)
  - Icon sichtbar: Ja / Nein
  - Farbe klar rot erkennbar: Ja / Nein
  - **Screenshot:** `screenshots/camera/black_doc_viewer_delete.png`

#### 2.3 Source Badges
- [ ] **Camera Badge**
  - Badge sichtbar: Ja / Nein
  - **Screenshot:** `screenshots/camera/black_doc_source_badge.png`

---

### Szenario 3: Farbige Magazine & Logos (Mixed Backgrounds)

**Setup:**
- Buntes Magazin-Cover (viele Farben)
- Logo-haltiges Dokument (Firmenlogo, Wasserzeichen)
- Farbiger Flyer/Prospekt

**Zu testen:**

#### 3.1 Scan Option Cards
- [ ] **Gallery Import Card** (hellblau `#8DD7FF`)
  - Karte kollidiert mit ähnlichen Blautönen im Dokument: Ja / Nein
  - Sichtbarkeit: Sehr gut / Gut / Mittel / Schlecht
  - **Problem-Farben:** ___________
  - **Screenshot:** `screenshots/camera/color_doc_gallery_card.png`

- [ ] **Files Import Card** (hellviolett `#D7B3FF`)
  - Karte kollidiert mit ähnlichen Violett/Pinktönen: Ja / Nein
  - Sichtbarkeit: Sehr gut / Gut / Mittel / Schlecht
  - **Problem-Farben:** ___________
  - **Screenshot:** `screenshots/camera/color_doc_files_card.png`

#### 3.2 Fullscreen Viewer
- [ ] **Alle Buttons** (Close, Rotate, Delete)
  - Buttons sichtbar über farbigem Logo: Ja / Nein
  - Schlechteste Farb-Kombination: ___________
  - **Screenshot:** `screenshots/camera/color_doc_viewer_all.png`

#### 3.3 Problematische Farben identifizieren
- [ ] **Liste alle Dokument-Farben die mit UI-Farben kollidieren:**
  ```
  Dokument-Farbe: #_______ kollidiert mit UI-Element: _______
  Dokument-Farbe: #_______ kollidiert mit UI-Element: _______
  ```

---

### Szenario 4: Edge Cases

**Setup:**
- Neon-Poster (sehr helle, gesättigte Farben)
- Glanzpapier mit Reflexionen
- Transparente Folie (falls möglich)
- Dokument mit Wasserzeichen

**Zu testen:**

#### 4.1 Neon-Farben (kritisch für #E1FF8D Primary)
- [ ] **Neon-Gelbes Dokument** vs. **Primary Button (#E1FF8D)**
  - Kamera Scan FAB sichtbar: Ja / Nein
  - **KRITISCH:** Neon-Gelb des Dokuments ähnlich zu Primary Color
  - Kontrast-Problem: Ja / Nein
  - **Screenshot:** `screenshots/camera/neon_yellow_doc.png`

- [ ] **Neon-Pink/Violett Dokument** vs. **Files Card (#D7B3FF)**
  - Files Import Card sichtbar: Ja / Nein
  - Kontrast ausreichend: Ja / Nein
  - **Screenshot:** `screenshots/camera/neon_pink_doc.png`

- [ ] **Neon-Blau Dokument** vs. **Gallery Card (#8DD7FF)**
  - Gallery Import Card sichtbar: Ja / Nein
  - Kontrast ausreichend: Ja / Nein
  - **Screenshot:** `screenshots/camera/neon_blue_doc.png`

#### 4.2 Glanzpapier & Reflexionen
- [ ] **Glanzpapier mit Kamera-Reflexion**
  - Buttons sichtbar trotz Reflexion: Ja / Nein
  - Reflexion verdeckt wichtige UI-Elemente: Ja / Nein
  - **Screenshot:** `screenshots/camera/glossy_paper_reflection.png`

#### 4.3 Wasserzeichen
- [ ] **Dokument mit Wasserzeichen**
  - Buttons über Wasserzeichen erkennbar: Ja / Nein
  - **Screenshot:** `screenshots/camera/watermark_doc.png`

---

## 📊 Test-Ergebnisse Zusammenfassung

### Kritische Issues (Farb-Kollisionen)
| Dokument-Typ | UI-Element | Farb-Kollision | Sichtbarkeit | Screenshot |
|--------------|------------|----------------|--------------|------------|
| Beispiel: Neon-Gelb | Primary FAB | #E1FF8D ≈ Doc Color | ❌ Schlecht | neon_yellow.png |
|              |            |                |              |            |

### Moderate Issues (Kontrast schwach)
| Dokument-Typ | UI-Element | Sichtbarkeit | Empfehlung |
|--------------|------------|--------------|------------|
|              |            |              |            |

### Passed Tests ✅
| Dokument-Typ | UI-Element | Sichtbarkeit | Notiz |
|--------------|------------|--------------|-------|
|              |            |              |       |

---

## 💡 Verbesserungsvorschläge

**Falls Probleme gefunden:**

### Option 1: Dynamischer Button-Hintergrund
- Analysiere Dokumenten-Helligkeit (Image Histogram)
- Wenn Dokument sehr hell → Dunkle Button-Backgrounds
- Wenn Dokument sehr dunkel → Helle Button-Backgrounds

### Option 2: Button-Outlines
- Füge 2dp Outline/Border zu allen Buttons hinzu
- Outline Farbe: Invers zum Button-Background
- Beispiel: Weißer Button mit schwarzem Outline

### Option 3: Drop Shadow (mit Vorsicht!)
- Kleine drop shadow (2-4dp, alpha 0.4f)
- NUR wenn andere Optionen nicht ausreichen
- Kann gegen "Dark Tech Precision Pro" Design verstoßen

### Option 4: Fixed Position Overlay
- Buttons IMMER auf semi-transparentem Overlay-Bereich
- Overlay-Bereich: `Color.Black.copy(alpha = 0.5f)`
- Garantiert Buttons nie direkt über Dokumentenhintergrund

---

## ✅ Acceptance Criteria

- [ ] **Alle Buttons sichtbar** auf weißen Dokumenten (Szenario 1)
- [ ] **Alle Buttons sichtbar** auf schwarzen Dokumenten (Szenario 2)
- [ ] **Keine kritischen Farb-Kollisionen** (Neon-Gelb, Neon-Blau, Neon-Pink)
- [ ] **Buttons erkennbar** auf farbigen Magazinen (Szenario 3)
- [ ] **Edge Cases dokumentiert** (Glanzpapier, Wasserzeichen)
- [ ] **Screenshots für alle Szenarien** (min. 15 Screenshots)
- [ ] **Findings in DARK_LIGHT_MODE_AUDIT.md** übertragen

---

## 📸 Screenshot-Struktur

```
docs/screenshots/camera/
├── white_doc_gallery_card.png
├── white_doc_files_card.png
├── white_doc_viewer_close.png
├── white_doc_viewer_rotate.png
├── white_doc_viewer_delete.png
├── white_doc_source_badge.png
├── black_doc_gallery_card.png
├── black_doc_files_card.png
├── black_doc_viewer_close.png
├── black_doc_viewer_rotate.png
├── black_doc_viewer_delete.png
├── black_doc_source_badge.png
├── color_doc_gallery_card.png
├── color_doc_files_card.png
├── color_doc_viewer_all.png
├── neon_yellow_doc.png
├── neon_pink_doc.png
├── neon_blue_doc.png
├── glossy_paper_reflection.png
└── watermark_doc.png
```

---

## 🚀 Nächste Schritte nach Completion

1. **Update DARK_LIGHT_MODE_AUDIT.md** mit Camera UI Findings
2. **Fix kritische Issues** (falls Farb-Kollisionen gefunden)
3. **Mark Archon Task** als "review" oder "done"
4. **Consider Paparazzi Tests** für automatisierte Regression (P2 Task)
