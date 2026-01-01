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

## Version 1.1.0 - Geplant

**Ziel:** Q1 2026

### Neue Features

- [ ] **Multi-Page Scan**
  - Mehrere Seiten scannen
  - Zu einem PDF zusammenfügen
  - Seiten neu anordnen/löschen

- [ ] **Dokumenttyp-Auswahl**
  - Dropdown mit vorhandenen Typen
  - Optional: Neuen Typ erstellen

- [ ] **Korrespondent-Auswahl**
  - Dropdown mit vorhandenen Korrespondenten
  - Optional: Neuen Korrespondenten erstellen

- [ ] **Inline Tag-Erstellung**
  - Neuen Tag während Upload erstellen
  - Farbauswahl für neuen Tag

### Verbesserungen

- [ ] Bessere Fehlerbehandlung
- [ ] Retry bei Netzwerkfehlern
- [ ] Upload-Fortschrittsanzeige (Prozent)
- [ ] Verbesserte Bildkompression

---

## Version 1.2.0 - Geplant

**Ziel:** Q2 2026

### Neue Features

- [ ] **Offline-Queue**
  - Dokumente offline scannen
  - Automatischer Upload bei Netzwerk
  - Queue-Übersicht

- [ ] **Galerie-Import**
  - Bilder aus Galerie wählen
  - Batch-Upload mehrerer Bilder

- [ ] **Benutzerdefinierte Felder**
  - Custom Fields von Paperless
  - Eingabe beim Upload

### Verbesserungen

- [ ] Hintergrund-Upload (WorkManager)
- [ ] Benachrichtigung bei Fertigstellung
- [ ] Verbesserte Bildvorschau (Zoom, Rotate)

---

## Version 1.3.0 - Geplant

**Ziel:** Q3 2026

### Neue Features

- [ ] **Biometrische Authentifizierung**
  - Fingerprint / Face ID
  - Optional aktivierbar

- [ ] **Quick-Scan Widget**
  - Home Screen Widget
  - Direkter Kamera-Zugriff

- [ ] **Dokumentensuche**
  - Volltextsuche in vorhandenen Dokumenten
  - Filter nach Tags, Typ, Datum

### Verbesserungen

- [ ] Speicheroptimierung
- [ ] Schnellerer App-Start
- [ ] Accessibility-Verbesserungen

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

- [ ] Tablet-optimiertes Layout
- [ ] Wear OS Companion (Quick-Status)

---

## Backlog (Ungeplant)

### Nice-to-Have

- [ ] Share-Intent von anderen Apps
- [ ] Automatischer Upload bei Foto
- [ ] Ordner-Struktur in App
- [ ] Export/Backup der App-Einstellungen
- [ ] Themes (Custom Colors)
- [ ] Sprach-Unterstützung (i18n)

### Technisch

- [ ] CI/CD Pipeline (GitHub Actions)
- [ ] Automatische APK-Signierung
- [ ] Play Store Deployment
- [ ] Crash-Reporting (Firebase)
- [ ] Analytics (opt-in)

---

## Priorisierung

### Kriterien

| Priorität | Bedeutung |
|-----------|-----------|
| P0 | Must-Have, blockiert Release |
| P1 | Should-Have, wichtig für UX |
| P2 | Could-Have, Verbesserung |
| P3 | Won't-Have (this release) |

### Aktuell höchste Priorität

1. **Multi-Page Scan** (P1) - Häufig angefragt
2. **Offline-Queue** (P1) - Kritisch für Mobile
3. **Dokumenttyp-Auswahl** (P1) - Vervollständigt Upload
4. **Biometrische Auth** (P2) - Sicherheit

---

## Feedback

Feature-Requests und Bugs:
- GitHub Issues: `<repository>/issues`
- Diskussionen: `<repository>/discussions`

Bitte vor Erstellung prüfen, ob Feature bereits geplant oder als Issue vorhanden.
