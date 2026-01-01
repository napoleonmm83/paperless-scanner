# Product Requirements Document (PRD)

## Paperless Scanner - Android Client

**Version:** 1.0.0
**Datum:** 2026-01-01
**Status:** MVP Released

---

## 1. Produktübersicht

### 1.1 Vision

Eine einfache, native Android-App, die es ermöglicht, physische Dokumente mit dem Smartphone zu scannen und direkt an eine selbstgehostete Paperless-ngx Instanz zu senden.

### 1.2 Problem Statement

Nutzer von Paperless-ngx müssen derzeit Dokumente über den Desktop hochladen oder komplexe Workarounds verwenden. Es fehlt eine native mobile Lösung, die:
- Automatische Dokumentenerkennung bietet
- Direkten Upload ermöglicht
- Tagging vor dem Upload unterstützt

### 1.3 Zielgruppe

- **Primär:** Paperless-ngx Self-Hoster mit Android-Smartphone
- **Sekundär:** Kleine Teams/Familien mit gemeinsamer Paperless-Instanz

### 1.4 Erfolgskriterien

| Metrik | Ziel |
|--------|------|
| Zeit: Dokument scannen → Upload | < 30 Sekunden |
| Erkennungsrate Dokumentenkanten | > 95% |
| App-Größe | < 20 MB |
| Mindest-Android-Version | API 26 (Android 8.0) |

---

## 2. User Stories

### 2.1 MVP (v1.0) - Abgeschlossen

| ID | User Story | Priorität | Status |
|----|------------|-----------|--------|
| US-001 | Als Nutzer möchte ich mich mit meiner Paperless-Instanz verbinden können | P0 | Done |
| US-002 | Als Nutzer möchte ich ein Dokument fotografieren können | P0 | Done |
| US-003 | Als Nutzer möchte ich, dass Dokumentenkanten automatisch erkannt werden | P0 | Done |
| US-004 | Als Nutzer möchte ich das gescannte Dokument an Paperless senden | P0 | Done |
| US-005 | Als Nutzer möchte ich Tags vor dem Upload auswählen können | P0 | Done |
| US-006 | Als Nutzer möchte ich einen optionalen Titel vergeben können | P1 | Done |
| US-007 | Als Nutzer möchte ich mich ausloggen können | P1 | Done |

### 2.2 Phase 2 - Geplant

| ID | User Story | Priorität | Status |
|----|------------|-----------|--------|
| US-101 | Als Nutzer möchte ich neue Tags inline erstellen können | P1 | Planned |
| US-102 | Als Nutzer möchte ich einen Dokumenttyp auswählen können | P1 | Planned |
| US-103 | Als Nutzer möchte ich einen Korrespondenten auswählen können | P2 | Planned |
| US-104 | Als Nutzer möchte ich mehrere Seiten zu einem PDF zusammenfügen | P1 | Planned |
| US-105 | Als Nutzer möchte ich Dokumente offline scannen und später hochladen | P2 | Planned |

### 2.3 Phase 3 - Zukünftig

| ID | User Story | Priorität | Status |
|----|------------|-----------|--------|
| US-201 | Als Nutzer möchte ich mich per Fingerabdruck/Face ID anmelden | P2 | Backlog |
| US-202 | Als Nutzer möchte ich ein Widget für Quick-Scan haben | P3 | Backlog |
| US-203 | Als Nutzer möchte ich in meinen Dokumenten suchen können | P2 | Backlog |
| US-204 | Als Nutzer möchte ich automatische Tag-Vorschläge erhalten | P3 | Backlog |
| US-205 | Als Nutzer möchte ich Bilder aus der Galerie hochladen können | P2 | Backlog |

---

## 3. Funktionale Anforderungen

### 3.1 Authentifizierung

| ID | Anforderung | Details |
|----|-------------|---------|
| FA-001 | Server-URL Eingabe | Vollständige URL mit Protokoll (https://) |
| FA-002 | Token-basierte Auth | POST /api/token/ mit username/password |
| FA-003 | Token-Persistenz | Sicheres Speichern in DataStore |
| FA-004 | Auto-Login | Bei gespeichertem Token direkt zum Scan |
| FA-005 | Logout | Token löschen, zurück zum Login |

### 3.2 Dokumentenscan

| ID | Anforderung | Details |
|----|-------------|---------|
| FA-101 | Kamera-Zugriff | Runtime Permission für CAMERA |
| FA-102 | Edge Detection | MLKit Document Scanner mit Auto-Crop |
| FA-103 | Perspektivkorrektur | Automatisch durch MLKit |
| FA-104 | Bildqualität | JPEG, optimiert für OCR |
| FA-105 | Galerie-Import | Optional: Bild aus Galerie wählen |

### 3.3 Upload

| ID | Anforderung | Details |
|----|-------------|---------|
| FA-201 | Multipart Upload | POST /api/documents/post_document/ |
| FA-202 | Titel (optional) | Freitext-Eingabe |
| FA-203 | Tags | Multi-Select aus vorhandenen Tags |
| FA-204 | Fortschrittsanzeige | Loading-Indikator während Upload |
| FA-205 | Erfolgsbestätigung | Snackbar mit Erfolgsmeldung |
| FA-206 | Fehlerbehandlung | Retry-Option bei Netzwerkfehler |

---

## 4. Nicht-funktionale Anforderungen

### 4.1 Performance

| ID | Anforderung | Zielwert |
|----|-------------|----------|
| NFA-001 | App-Start | < 2 Sekunden (Cold Start) |
| NFA-002 | Scan-Vorgang | < 3 Sekunden |
| NFA-003 | Upload (5MB Dokument) | < 10 Sekunden (bei 10 Mbit/s) |
| NFA-004 | Memory Usage | < 150 MB |

### 4.2 Sicherheit

| ID | Anforderung | Details |
|----|-------------|---------|
| NFA-101 | HTTPS | Standard für alle Verbindungen |
| NFA-102 | Token-Speicherung | Encrypted DataStore |
| NFA-103 | Keine Logs in Production | Sensible Daten nicht loggen |
| NFA-104 | ProGuard | Code-Obfuskation in Release |

### 4.3 Usability

| ID | Anforderung | Details |
|----|-------------|---------|
| NFA-201 | Material 3 | Konsistentes Design |
| NFA-202 | Dark Mode | Automatisch nach System |
| NFA-203 | Landscape/Portrait | Beide Orientierungen |
| NFA-204 | Accessibility | ContentDescription für Icons |

---

## 5. Technische Constraints

### 5.1 Plattform

- **Minimum SDK:** 26 (Android 8.0 Oreo)
- **Target SDK:** 35 (Android 15)
- **Architektur:** ARM64-v8a, armeabi-v7a, x86_64

### 5.2 Dependencies

| Dependency | Version | Zweck |
|------------|---------|-------|
| Kotlin | 2.0.21 | Programmiersprache |
| Compose BOM | 2024.12.01 | UI Framework |
| Hilt | 2.53.1 | Dependency Injection |
| Retrofit | 2.11.0 | HTTP Client |
| MLKit Document Scanner | 16.0.0-beta1 | Dokumentenscan |
| DataStore | 1.1.1 | Lokale Speicherung |

### 5.3 Backend-Abhängigkeiten

- Paperless-ngx >= 1.10.0
- API-Zugang aktiviert
- Gültiger Benutzer-Account

---

## 6. Risiken & Mitigationen

| Risiko | Wahrscheinlichkeit | Impact | Mitigation |
|--------|-------------------|--------|------------|
| MLKit nicht verfügbar | Niedrig | Hoch | Fallback auf manuelle Crop-Funktion |
| API-Änderungen Paperless | Mittel | Mittel | API-Version prüfen, Kompatibilitätslayer |
| Große Dateien | Mittel | Mittel | Kompression, Chunk-Upload |
| Offline-Nutzung | Hoch | Mittel | Offline-Queue implementieren (Phase 2) |

---

## 7. Release-Kriterien

### 7.1 MVP (v1.0.0)

- [x] Login funktioniert
- [x] Scan mit Edge Detection
- [x] Upload erfolgreich
- [x] Tags auswählbar
- [x] Keine kritischen Bugs
- [ ] Signierte APK erstellt
- [ ] Dokumentation vollständig

### 7.2 Production (v1.1.0)

- [ ] Multi-Page Scan
- [ ] Offline-Queue
- [ ] Play Store Listing
- [ ] Crash-Reporting (Firebase Crashlytics)
- [ ] Analytics (opt-in)

---

## 8. Stakeholder

| Rolle | Verantwortung |
|-------|---------------|
| Product Owner | Feature-Priorisierung, Abnahme |
| Developer | Implementierung, Testing |
| User | Feedback, Beta-Testing |

---

## Anhang

### A. Wireframes

```
┌─────────────────────┐   ┌─────────────────────┐   ┌─────────────────────┐
│      LOGIN          │   │       SCAN          │   │      UPLOAD         │
│                     │   │                     │   │                     │
│  ┌───────────────┐  │   │   ┌───────────┐     │   │  ┌───────────────┐  │
│  │ Server URL    │  │   │   │    📄     │     │   │  │   Preview     │  │
│  └───────────────┘  │   │   │  Scanner  │     │   │  │   [Image]     │  │
│  ┌───────────────┐  │   │   │   Icon    │     │   │  └───────────────┘  │
│  │ Username      │  │   │   └───────────┘     │   │  ┌───────────────┐  │
│  └───────────────┘  │   │                     │   │  │ Titel         │  │
│  ┌───────────────┐  │   │  Dokument scannen   │   │  └───────────────┘  │
│  │ Password      │  │   │                     │   │                     │
│  └───────────────┘  │   │  ┌───────────────┐  │   │  Tags: [A] [B] [C]  │
│                     │   │  │ 📷 Kamera    │  │   │                     │
│  ┌───────────────┐  │   │  │   öffnen     │  │   │  ┌───────────────┐  │
│  │    LOGIN      │  │   │  └───────────────┘  │   │  │   UPLOAD ☁️   │  │
│  └───────────────┘  │   │                     │   │  └───────────────┘  │
└─────────────────────┘   └─────────────────────┘   └─────────────────────┘
```

### B. API-Referenz

Siehe: [API_REFERENCE.md](./API_REFERENCE.md)

### C. Changelog

Siehe: [CHANGELOG.md](../CHANGELOG.md)
