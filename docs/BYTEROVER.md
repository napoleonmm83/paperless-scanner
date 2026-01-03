# ByteRover CLI - Setup & Nutzung

## Übersicht

ByteRover ist ein Memory Layer für AI Coding Agents, der Projekt-Kontext und Wissen automatisch verwaltet. Es ermöglicht AI-Assistenten wie Claude Code, besser auf projektspezifisches Wissen zuzugreifen und zu lernen.

**Aktuelle Version:** 1.0.3
**Login:** marcusmartini83@gmail.com
**Status:** ✅ Vollständig eingerichtet und einsatzbereit
**Team/Space:** Marcus's Team-7p3det/paperless

---

## Warum ByteRover?

- **Automatisches Kontext-Management:** Kein manuelles Kopieren von Code-Snippets mehr
- **Persistentes Projekt-Gedächtnis:** AI lernt aus vorherigen Konversationen und Änderungen
- **Team-Synchronisation:** Kontext kann mit dem Team geteilt werden
- **Agentic Search:** Intelligente Suche im Projekt-Kontext
- **Multi-Agent Support:** Funktioniert mit Claude Code, Cursor, Windsurf, Codex, etc.

---

## Installation (Bereits erledigt)

```bash
# Node.js Version prüfen (benötigt: v20+)
node --version  # Aktuell: v22.21.1 ✓

# ByteRover CLI installieren
npm install -g byterover-cli

# Status prüfen
brv status
```

---

## Projekt-Initialisierung ✅

Das Projekt wurde erfolgreich initialisiert!

### ✅ Abgeschlossene Schritte:

1. **ByteRover REPL gestartet**
2. **Projekt initialisiert** (`/init`)
3. **Team/Space konfiguriert:** Marcus's Team-7p3det/paperless
4. **Kontext kuratiert:** 9 Bereiche mit projektspezifischem Wissen

### Ursprüngliche Anleitung (für Referenz):

#### 1. ByteRover REPL starten

```bash
brv
```

#### 2. Projekt initialisieren

Im REPL-Modus:

```bash
/init
```

Sie werden aufgefordert:
- **Team auswählen:** Wählen Sie Ihr ByteRover-Team
- **Space auswählen:** Wählen Sie oder erstellen Sie einen Space für dieses Projekt
  - Empfehlung: Space-Name = `paperless-scanner`

### 3. Initiale Kontext-Konfiguration

Nach der Initialisierung sollten Sie wichtige Projekt-Informationen kuratieren:

```bash
# Im REPL-Modus
/curate "Paperless Scanner - Android Kotlin App für Paperless-ngx"
/curate "Tech Stack: Kotlin 2.0, Jetpack Compose, Hilt, Retrofit, MLKit"
/curate "API Besonderheit: Upload-Endpoint gibt Plain String zurück, nicht JSON"
```

---

## Workflow & Nutzung

### Kontext hinzufügen

```bash
# Allgemeine Information
brv curate "JWT Token Expiration ist 24h"

# Mit spezifischen Dateien (max 5)
brv curate "Neue Upload-Logik implementiert" --files app/src/main/java/com/paperless/scanner/data/repository/DocumentRepository.kt
```

### Kontext abfragen

```bash
brv query "Wie ist die Authentifizierung implementiert?"
brv query "Welche API-Endpoints werden verwendet?"
brv query "Wo wird der Scanner initialisiert?"
```

### REPL-Mode Befehle

| Befehl | Beschreibung |
|--------|--------------|
| `/status` | Zeigt Login-Status und Projekt-Info |
| `/init` | Initialisiert Projekt (nur einmal) |
| `/push` | Synchronisiert Context Tree zur Cloud |
| `/pull` | Lädt Context von der Cloud |
| `/space list` | Zeigt verfügbare Spaces |
| `/space switch` | Wechselt aktiven Space |
| `/gen-rules` | Generiert Agent-Konfigurationsdateien |
| `/clear` | Setzt Context Tree zurück |
| `/logout` | Meldet ab |

---

## Best Practices

### Was sollte kuratiert werden?

✅ **DO:**
- Architektur-Entscheidungen (z.B. "Clean Architecture mit MVVM")
- API-Besonderheiten (z.B. Response-Format-Quirks)
- Wichtige Business-Logik-Regeln
- Gelöste Bugs mit Kontext
- Projekt-spezifische Patterns
- Konfiguration-Details

❌ **DON'T:**
- Keine generischen Kotlin-Tutorials
- Keine sensiblen Daten (API Keys, Passwörter)
- Keine temporären Debug-Informationen
- Keine redundanten Informationen aus Docs

### Wann kuratieren?

- Nach größeren Features
- Nach Bug-Fixes mit wichtigen Erkenntnissen
- Bei Architektur-Änderungen
- Bei neuen API-Integrationen
- Vor wichtigen Projekt-Meilensteinen

### Team-Synchronisation

```bash
# Änderungen hochladen
/push

# Änderungen vom Team holen
/pull
```

---

## Integration mit Claude Code

ByteRover arbeitet automatisch mit Claude Code zusammen. Wenn Sie Claude Code verwenden:

1. **Automatischer Kontext:** Claude hat Zugriff auf kuratiertes Wissen
2. **Besseres Verständnis:** Projekt-spezifische Patterns werden erkannt
3. **Konsistente Antworten:** Wiederholte Fragen nutzen gespeichertes Wissen

---

## Projekt-spezifische Konfiguration

### Empfohlene Initiale Kuration für Paperless Scanner

Nach `/init` sollten folgende Informationen kuratiert werden:

```bash
# 1. Projekt-Grundlagen
brv curate "Paperless Scanner - Android-Client für Paperless-ngx zum Scannen und Hochladen von Dokumenten"

# 2. Tech Stack
brv curate "Tech Stack: Kotlin 2.0, Jetpack Compose, Material 3, Hilt DI, Retrofit, OkHttp, MLKit Document Scanner, DataStore, Min SDK 26"

# 3. Architektur
brv curate "Architektur: Clean Architecture mit MVVM, Feature-basierte Package-Struktur, Sealed Classes für UI State"

# 4. Kritische API-Details
brv curate "Paperless API: Token-Endpoint gibt JSON {token: ...}, Upload-Endpoint gibt Plain String (task-uuid) zurück - KEIN JSON!"

# 5. Bekannte Probleme
brv curate "Kotlin Daemon GC Crash Fix: gradle.properties mit kotlin.daemon.jvmargs=-Xmx2048m -XX:-UseParallelGC"

# 6. Projekt-Richtlinien
brv curate "Coding Standards: data object für Sealed Class Singletons, StateFlow nicht LiveData, Result<T> für Repository returns"
```

### Kontext-Wartung

**Monatlich:**
- `/status` prüfen
- Veralteten Kontext bereinigen
- Neue wichtige Erkenntnisse hinzufügen

**Nach großen Änderungen:**
- `/push` ausführen
- Team informieren
- Projekt-Dokumentation aktualisieren

---

## Troubleshooting

### Projekt nicht initialisiert

```bash
# Status prüfen
brv status

# Falls "Project Status: Not initialized"
brv  # REPL starten
/init  # Im REPL ausführen
```

### Context Tree Probleme

```bash
# Context Tree zurücksetzen
brv  # REPL starten
/clear  # Vorsicht: Löscht lokalen Context!
/pull  # Von Cloud wiederherstellen
```

### Login-Probleme

```bash
brv  # REPL starten
/logout  # Ausloggen
/login  # Neu einloggen (öffnet Browser)
```

---

## Weiterführende Ressourcen

- **Offizielle Dokumentation:** https://docs.byterover.dev
- **CLI Referenz:** https://docs.byterover.dev/reference/cli-reference
- **Blog & Updates:** https://www.byterover.dev/blog
- **GitHub (Open Source):** https://github.com/campfirein/cipher

---

## Setup-Status

1. ✅ ByteRover CLI installiert (v1.0.3)
2. ✅ Login erfolgreich (marcusmartini83@gmail.com)
3. ✅ Projekt mit `/init` initialisiert (Marcus's Team-7p3det/paperless)
4. ✅ **9 Bereiche kuratiert:**
   - Projekt-Übersicht & Features
   - Tech Stack (Kotlin, Compose, Hilt, etc.)
   - Architektur (Clean Architecture + MVVM)
   - API-Besonderheiten (Plain String Response!)
   - Authentication (Token-basiert, 2FA)
   - Bekannte Probleme & Fixes
   - Coding Standards
   - Drag & Drop Feature (Commit 7eac1f1)
   - Undo Feature (Commit 244ec55)
5. ✅ **Context Tree erstellt:** 20 Dateien automatisch kategorisiert
6. ⏳ **Optional:** Team über ByteRover-Nutzung informieren (via `/push`)

---

## Wie Claude Code ByteRover nutzt

Ab jetzt wird Claude Code automatisch:
- ✅ **Projekt-Wissen abrufen** via `brv query`
- ✅ **Neues Wissen hinzufügen** via `brv curate`
- ✅ **Context-aware Antworten** basierend auf Ihrem Projekt geben

**Beispiel:**
```bash
brv query "What's special about the upload API?"
```
→ Claude weiß jetzt, dass `/api/documents/post_document/` einen Plain String zurückgibt, kein JSON!

---

**Erstellt:** 2026-01-03
**Zuletzt aktualisiert:** 2026-01-03 (Setup abgeschlossen)
