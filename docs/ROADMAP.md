# Roadmap

## Paperless Scanner - Feature Roadmap

---

## Version 1.0.0 (MVP) - Abgeschlossen

**Release:** 2026-01-01

### Implementierte Features

- [x] Login mit Server-URL, Username, Password
- [x] Token-basierte Authentifizierung
- [x] Token-Persistenz (DataStore)
- [x] Dokumentenscan mit MLKit
- [x] Automatische Kantenerkennung
- [x] Perspektivkorrektur
- [x] Upload zu Paperless-ngx
- [x] Optionaler Titel
- [x] Tag-Auswahl (vorhandene Tags)
- [x] Logout-Funktion
- [x] Material 3 Design
- [x] Dark Mode Support

---

## Version 1.1.0 - Abgeschlossen

**Release:** 2026-01-02

### Implementierte Features

- [x] **Multi-Page Scan**
  - Mehrere Seiten scannen (max. 20)
  - PDF-Zusammenführung mit iText
  - Seiten neu anordnen/löschen
  - Thumbnail-Vorschau

- [x] **Dokumenttyp-Auswahl**
  - Dropdown mit vorhandenen Typen
  - Alphabetisch sortiert

- [x] **Korrespondent-Auswahl**
  - Dropdown mit vorhandenen Korrespondenten
  - Alphabetisch sortiert

- [x] **Inline Tag-Erstellung**
  - Neuen Tag während Upload erstellen
  - 12 Farben zur Auswahl
  - Automatisches Hinzufügen zur Auswahl

- [x] **Offline-Queue**
  - Dokumente offline scannen
  - Room Database für Persistenz
  - Queue-Übersicht im UI

- [x] **Hintergrund-Upload**
  - WorkManager Integration
  - Automatischer Retry bei Fehlern
  - Fortschrittsanzeige

- [x] **Benachrichtigungen**
  - Upload-Fortschritt in Notification
  - Erfolgs-/Fehlermeldungen
  - Throttling für bessere UX

- [x] **Home Screen Widget**
  - Quick-Scan Button
  - Ausstehende Uploads anzeigen
  - Glance AppWidget

- [x] **Galerie-Import**
  - Bilder aus Galerie wählen
  - Batch-Upload mehrerer Bilder
  - Upload-Modus: Einzeln oder als ein Dokument

---

## Version 1.1.6 - Aktuell

**Release:** 2026-01-02

### Verbesserungen

- [x] **Batch Import Upload-Modus**
  - Auswahl: Einzeln oder als ein PDF
  - SegmentedButtonRow UI

- [x] **CI/CD Pipeline**
  - GitHub Actions für Build & Test
  - Lint-Checks
  - Automatische APK-Signierung

- [x] **Play Store Deployment**
  - Fastlane Integration
  - Automatisches Deployment zu Internal/Production
  - Metadata & Screenshots

- [x] **ProGuard/R8 Optimierung**
  - Gson TypeToken Fix für Release Builds
  - Korrekte Keep-Rules

---

## Version 1.2.0 - Geplant

**Ziel:** Q1 2026

### Neue Features

- [ ] **Benutzerdefinierte Felder**
  - Custom Fields von Paperless laden
  - Eingabe beim Upload
  - Verschiedene Feldtypen unterstützen

- [ ] **Share-Intent**
  - Bilder von anderen Apps empfangen
  - PDF-Dateien importieren
  - Quick-Upload ohne App öffnen

- [ ] **Verbesserte Bildvorschau**
  - Zoom-Gesten
  - Rotation
  - Crop-Funktion

### Verbesserungen

- [ ] Upload-Fortschrittsanzeige (Prozent)
- [ ] Verbesserte Bildkompression
- [ ] Speicheroptimierung

---

## Version 1.3.0 - Geplant

**Ziel:** Q2 2026

### Neue Features

- [ ] **Biometrische Authentifizierung**
  - Fingerprint / Face ID
  - Optional aktivierbar
  - App-Lock Funktion

- [ ] **Dokumentensuche**
  - Volltextsuche in vorhandenen Dokumenten
  - Filter nach Tags, Typ, Datum
  - Ergebnisvorschau

- [ ] **Mehrsprachigkeit (i18n)**
  - Englisch
  - Deutsch
  - Weitere Sprachen

### Verbesserungen

- [ ] Schnellerer App-Start
- [ ] Accessibility-Verbesserungen
- [ ] Tablet-optimiertes Layout

---

## Version 2.0.0 - Zukunft

**Ziel:** 2027

### Neue Features

- [ ] **OCR-Vorschau**
  - Extrahierten Text anzeigen vor Upload
  - Manuell editieren

- [ ] **Automatische Tag-Vorschläge**
  - KI-basierte Vorschläge
  - Basierend auf Dokumentinhalt

- [ ] **Multi-Account Support**
  - Mehrere Paperless-Instanzen
  - Schneller Account-Wechsel

- [ ] **Dokumenten-Workflow**
  - Erinnerungen setzen
  - Aufgaben verknüpfen

### Plattform

- [ ] Wear OS Companion (Quick-Status)

---

## Backlog (Ungeplant)

### Nice-to-Have

- [ ] Automatischer Upload bei Foto
- [ ] Ordner-Struktur in App
- [ ] Export/Backup der App-Einstellungen
- [ ] Themes (Custom Colors)

### Technisch

- [ ] Crash-Reporting (Firebase Crashlytics)
- [ ] Analytics (opt-in)
- [ ] End-to-End Tests (Espresso)

---

## Abgeschlossene Meilensteine

| Version | Datum | Highlights |
|---------|-------|------------|
| 1.0.0 | 2026-01-01 | MVP: Scan, Upload, Tags |
| 1.1.0 | 2026-01-02 | Multi-Page, Dokumenttypen, Korrespondenten |
| 1.1.3 | 2026-01-02 | Erstes Play Store Release |
| 1.1.4 | 2026-01-02 | Batch Import Upload-Modus |
| 1.1.5 | 2026-01-02 | ProGuard Fix (Release Builds) |
| 1.1.6 | 2026-01-02 | CI/CD Fixes, Lint-Korrekturen |

---

## Priorisierung

### Kriterien

| Priorität | Bedeutung |
|-----------|-----------|
| P0 | Must-Have, blockiert Release |
| P1 | Should-Have, wichtig für UX |
| P2 | Could-Have, Verbesserung |
| P3 | Won't-Have (this release) |

### Nächste Prioritäten

1. **Share-Intent** (P1) - Häufig angefragt
2. **Benutzerdefinierte Felder** (P1) - Paperless Feature
3. **Biometrische Auth** (P2) - Sicherheit
4. **Dokumentensuche** (P2) - Komfort
5. **i18n** (P2) - Breitere Nutzerbasis

---

## Feedback

Feature-Requests und Bugs:
- GitHub Issues: https://github.com/napoleonmm83/paperless-scanner/issues
- Diskussionen: https://github.com/napoleonmm83/paperless-scanner/discussions

Bitte vor Erstellung prüfen, ob Feature bereits geplant oder als Issue vorhanden.
