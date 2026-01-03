# Claude Code Instructions

## Projekt: Paperless Scanner

Android-Client für Paperless-ngx zum Scannen und Hochladen von Dokumenten.

---

## Archon Projekt-Management

**Archon Project ID:** `bf5f5402-7de2-4a5d-b4f2-5f18e2cd599a`

Alle Tasks und Dokumentation werden in diesem Archon-Projekt verwaltet.

---

## Projektkontext

### Tech Stack
- **Sprache:** Kotlin 2.0
- **UI:** Jetpack Compose + Material 3
- **DI:** Hilt
- **Networking:** Retrofit + OkHttp
- **Scanner:** MLKit Document Scanner
- **Storage:** DataStore Preferences
- **Min SDK:** 26 (Android 8.0)

### Architektur
- Clean Architecture mit MVVM
- Feature-basierte Package-Struktur
- Sealed Classes für UI State

---

## Wichtige Pfade

```
app/src/main/java/com/paperless/scanner/
├── di/AppModule.kt              # Hilt DI Module
├── data/
│   ├── api/PaperlessApi.kt      # Retrofit Interface
│   ├── api/models/ApiModels.kt  # DTOs
│   ├── datastore/TokenManager.kt
│   └── repository/              # Business Logic
├── ui/
│   ├── theme/                   # Material 3 Theme
│   ├── navigation/              # NavGraph
│   └── screens/{login,scan,upload}/
```

---

## Coding Standards

### Kotlin
- `data object` statt `object` für Sealed Class Singletons
- `StateFlow` für UI State, nicht `LiveData`
- Suspend Functions für alle async Operationen
- `Result<T>` für Repository-Rückgabewerte

### Compose
- Stateless Composables bevorzugen
- State Hoisting anwenden
- `remember` und `rememberSaveable` korrekt nutzen
- Material 3 Components verwenden

### Naming
- ViewModels: `{Feature}ViewModel`
- Screens: `{Feature}Screen`
- Repositories: `{Entity}Repository`
- UI States: `{Feature}UiState`

---

## API-Besonderheiten (Kritisch!)

### Paperless-ngx API

**Token-Endpoint:**
```
POST /api/token/
Response: {"token": "..."}
```

**Upload-Endpoint:**
```
POST /api/documents/post_document/
Response: "task-uuid-string"  ← KEIN JSON-Objekt!
```

**Wichtig:** Upload gibt Plain String zurück, nicht JSON. Verwende `ResponseBody`:
```kotlin
suspend fun uploadDocument(...): ResponseBody
val taskId = response.string().trim().removeSurrounding("\"")
```

---

## Build & Run

### Debug Build
```bash
./gradlew assembleDebug
```

### Release Build
```bash
./gradlew assembleRelease
```

### Gradle Daemon stoppen
```bash
./gradlew --stop
```

---

## Bekannte Probleme

### Kotlin Daemon GC Crash
**Fix in gradle.properties:**
```properties
kotlin.daemon.jvmargs=-Xmx2048m -XX:-UseParallelGC
```

### MLKit nicht verfügbar
Emulator muss "Google Play" System Image haben, nicht nur "Google APIs".

---

## Dokumentation

| Dokument | Pfad |
|----------|------|
| PRD | `docs/PRD.md` |
| Technisch | `docs/TECHNICAL.md` |
| Roadmap | `docs/ROADMAP.md` |
| API Referenz | `docs/API_REFERENCE.md` |
| Known Issues | `docs/KNOWN_ISSUES.md` |

---

## Regeln für Claude

### DO
- API-Dokumentation verifizieren bevor Models erstellt werden
- Bestehende Patterns im Code folgen
- Sealed Classes für State Management verwenden
- Fehler mit konkreten Lösungen dokumentieren
- Tests für neue Features schreiben

### DON'T
- Keine Annahmen über API Response-Formate
- Keine Breaking Changes ohne Dokumentation
- Keine neuen Dependencies ohne Begründung
- Keine hardcodierten Strings (→ strings.xml)
- Keine Logs mit sensiblen Daten

---

## Häufige Tasks

### Neuen Screen hinzufügen
1. Screen Composable in `ui/screens/{feature}/`
2. ViewModel in `ui/screens/{feature}/`
3. Route in `ui/navigation/Screen.kt`
4. NavHost erweitern in `PaperlessNavGraph.kt`

### Neuen API Endpoint
1. Methode in `PaperlessApi.kt`
2. Models in `api/models/` (falls nötig)
3. Repository-Methode erstellen
4. ViewModel-Methode erstellen

### Neues Feature testen
1. API Response manuell testen (curl/Postman)
2. Unit Tests für Repository
3. UI Tests für Screen
4. Manueller Test auf Gerät

---

## Kontakt & Ressourcen

- **Paperless-ngx Docs:** https://docs.paperless-ngx.com/api/
- **Compose Docs:** https://developer.android.com/jetpack/compose
- **MLKit Scanner:** https://developers.google.com/ml-kit/vision/doc-scanner
