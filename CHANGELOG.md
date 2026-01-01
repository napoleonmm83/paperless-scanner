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

## [Unreleased]

### Geplant

- Multi-Page Scan mit PDF-Merge
- Dokumenttyp-Auswahl
- Korrespondent-Auswahl
- Inline Tag-Erstellung
- Offline-Queue
