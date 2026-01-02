# Changelog

Alle wesentlichen Änderungen an diesem Projekt werden in dieser Datei dokumentiert.

Das Format basiert auf [Keep a Changelog](https://keepachangelog.com/de/1.0.0/).

---

## [1.0.0] - 2026-01-01

### Hinzugefügt

- **Authentifizierung**
  - Login mit Server-URL, Username und Password
  - Token-basierte Authentifizierung
  - Sichere Token-Speicherung in DataStore
  - Auto-Login bei gespeichertem Token
  - Logout-Funktion

- **Dokumentenscan**
  - Integration MLKit Document Scanner
  - Automatische Kantenerkennung
  - Perspektivkorrektur
  - Galerie-Import-Option

- **Upload**
  - Multipart-Upload zu Paperless-ngx
  - Optionaler Titel
  - Tag-Auswahl aus vorhandenen Tags
  - Erfolgs-/Fehlerbenachrichtigung

- **UI/UX**
  - Material 3 Design
  - Paperless-Branding (Grün-Töne)
  - Dark Mode (automatisch nach System)
  - Deutsche Lokalisierung

### Bekannte Probleme

- Multi-Page Scan noch nicht implementiert
- Offline-Modus nicht verfügbar
- Neue Tags können nicht inline erstellt werden

### Technisch

- Kotlin 2.0.21
- Jetpack Compose mit Material 3
- Hilt für Dependency Injection
- Retrofit + OkHttp für Networking
- Minimum SDK: 26 (Android 8.0)
- Target SDK: 35 (Android 15)

---

## [1.3.0] - 2026-01-02

### Hinzugefügt

- **Neues UI-Design mit Bottom Navigation**
  - 5-Tab Navigation: Home, Dokumente, Scan, Labels, Einstellungen
  - Moderne Pastel-Farbpalette für Cards und Statistiken
  - Durchgängiges Material 3 Design

- **Home Dashboard**
  - Dokumenten-Statistiken (Gesamt, Diesen Monat, Ausstehend)
  - Schnellzugriff-Buttons für Scan und Dokumente
  - Liste der zuletzt hinzugefügten Dokumente
  - **Task-Verarbeitung mit Live-Status**
    - Zeigt Uploads in Echtzeit an (Warte, Verarbeite, Erfolgreich, Fehlgeschlagen)
    - Automatisches Polling alle 3 Sekunden bei aktiven Tasks
    - X-Button zum Bestätigen/Ausblenden abgeschlossener Tasks
    - Optimistisches UI-Update für sofortige Rückmeldung
  - Hinweis-Banner für ungetaggte Dokumente

- **Dokumenten-Übersicht**
  - Vollständige Dokumentenliste mit Pagination
  - Suche nach Dokumententitel
  - Filter nach Tags, Korrespondent, Dokumenttyp
  - Pull-to-Refresh
  - Unendliches Scrollen

- **Dokument-Detailansicht**
  - Vollständige Dokumentinformationen anzeigen
  - Tags, Korrespondent, Dokumenttyp
  - Erstellungs- und Änderungsdatum
  - Direktlink zum Paperless-Web-Interface

- **Labels/Tags-Verwaltung**
  - Alle Tags mit Dokumentenanzahl anzeigen
  - Tags bearbeiten (Name und Farbe)
  - Tags löschen (mit Bestätigungsdialog)
  - Tag-Detail: Alle Dokumente eines Tags anzeigen
  - Farbauswahl mit 12 vordefinierten Farben

- **Einstellungen (vollständig funktional)**
  - Server-URL Anzeige
  - Upload-Benachrichtigungen Toggle (wird persistiert)
  - Upload-Qualität Auswahl (Automatisch, Niedrig, Mittel, Hoch)
  - App-Version Anzeige
  - Open Source Lizenzen Dialog
  - Abmelden mit Bestätigungsdialog

- **Onboarding-Flow**
  - 3-stufige Einführung für neue Benutzer
  - Scan-Feature Erklärung
  - Labels-Feature Erklärung
  - Automatisches Überspringen nach erstem Durchlauf

- **Task-Tracking API**
  - Neue TaskRepository für Paperless Tasks
  - GET /api/tasks/ - Alle Tasks abrufen
  - POST /api/tasks/acknowledge/ - Tasks bestätigen
  - Unbestätigte Tasks werden auf Home angezeigt

### Verbessert

- **Navigation**
  - Korrigierte Bottom Navigation (Home-Button funktioniert jetzt korrekt)
  - Entfernt saveState/restoreState für zuverlässige Navigation
  - Lifecycle-aware Refresh für aktuelle Daten

- **Upload-Flow**
  - Sofortige Navigation nach erfolgreichem Upload (kein Warten auf Snackbar)
  - Verzögerter Task-Refresh (1.5s) um neue Tasks zu erfassen
  - Seiten werden vor Navigation zur Upload-Ansicht geleert

- **Einstellungen-Persistenz**
  - Alle Einstellungen werden in DataStore gespeichert
  - Upload-Benachrichtigungen Toggle funktional
  - Upload-Qualität Auswahl funktional

- **Farbschema**
  - Neue Pastel-Farbpalette für bessere Lesbarkeit
  - Höherer Kontrast für Task-Status-Texte
  - Konsistente Farben über alle Screens

### Behoben

- Task-Status-Karten haben jetzt lesbaren Text (dunkle Textfarben)
- Home-Button in Navigation führt nicht mehr zu Dokumenten
- Tasks verschwinden nach dem Bestätigen dauerhaft
- Tasks erscheinen nach neuem Upload zuverlässig

### Technisch

- **Neue Dateien:**
  - `TaskRepository.kt` - Repository für Task-API
  - `DocumentDetailScreen.kt` - Dokument-Detailansicht
  - `DocumentDetailViewModel.kt` - ViewModel für Details
  - Onboarding-Screens und ViewModel

- **Erweiterte APIs:**
  - `PaperlessApi.kt` - Task-Endpoints hinzugefügt
  - `ApiModels.kt` - PaperlessTask, AcknowledgeTasksRequest
  - `TokenManager.kt` - Upload-Einstellungen

- **Architektur:**
  - Lifecycle-aware Components für zuverlässige Updates
  - Optimistisches UI-Update Pattern
  - Verzögerte Refreshs für API-Timing

---

## [Unreleased]

### Geplant

- Benutzerdefinierte Felder
- Verbesserte Bildvorschau (Zoom, Rotation, Crop)
- Biometrische Authentifizierung
- Dokumenten-Volltextsuche
- Mehrsprachigkeit (i18n)
