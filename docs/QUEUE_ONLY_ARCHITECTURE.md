# Queue-Only Upload Architektur

**Status:** ✅ Implementiert (Version 1.4.85+)
**Erstellt:** 2026-01-24
**Letzte Aktualisierung:** 2026-01-24

---

## Zusammenfassung

Das Upload-System wurde von einem **Dual-Path Ansatz** (Direct Upload + Queue) zu einem **Queue-Only Ansatz** refactored. Alle Uploads laufen nun ausschließlich über WorkManager Queue, was zu massiver Code-Reduktion, verbesserter Wartbarkeit und konsistentem Verhalten führt.

---

## Architektur Vorher vs. Nachher

### Vorher: Dual-Path Upload

```
UploadScreen ──► UploadViewModel ──► Server Online?
                                      ├─ Yes → Direct Upload (RetryUtil)
                                      └─ No  → Queue → UploadWorker

BatchImportScreen ───► BatchImportViewModel ──► Always Queue → UploadWorker
```

**Probleme:**
- Inkonsistentes Verhalten (2 unterschiedliche Upload-Pfade)
- UploadViewModel hatte zu viele Verantwortlichkeiten
- Komplexe Retry-Logik in ViewModel
- Server Health Checks in ViewModel statt Worker
- ~950 Zeilen Code in UploadViewModel
- Verschiedene Fehlerbehandlung je nach Upload-Pfad

### Nachher: Queue-Only Upload

```
UploadScreen ────┐
MultiPageUpload ─┼──► UploadViewModel ──► Always Queue ──► UploadWorker
BatchImport ─────┘                                          (handles all logic)
```

**Vorteile:**
- **Single Responsibility:** UploadWorker übernimmt alle Upload-Logik
- **Konsistenz:** Alle Uploads folgen dem gleichen Pfad
- **Offline-First:** Immer robust, egal ob online oder offline
- **Code-Reduktion:** ~30% weniger Code (~660 Zeilen statt ~950)
- **WorkManager Benefits:** Automatisches Retry, Battery-optimiert, Process Death sicher

---

## Code Reduktion Details

### UploadViewModel.kt

**Vorher:** ~950 Zeilen
**Nachher:** ~660 Zeilen
**Reduktion:** ~290 Zeilen (~30%)

#### Entfernte Komponenten:

1. ✅ **RetryUtil.kt** - Komplett entfernt (44 Zeilen)
   - Exponential Backoff Logik
   - Retry Counter
   - Delay Berechnung
   - → Alles jetzt in WorkManager

2. ✅ **Direct Upload Logik** - Entfernt aus ViewModel
   ```kotlin
   ❌ private suspend fun uploadDocumentDirect(...)
   ❌ private suspend fun uploadMultiPageDocumentDirect(...)
   ❌ private fun handleDirectUpload(...)
   ```

3. ✅ **Server Health Checks** - Entfernt aus ViewModel
   ```kotlin
   ❌ private fun checkServerHealth(): Boolean
   ❌ private suspend fun waitForServerAvailable()
   ```

4. ✅ **Manual Retry Funktionen**
   ```kotlin
   ❌ fun retry()
   ❌ fun canRetry(): Boolean
   ❌ private var lastUploadParams: UploadParams? = null
   ```

5. ✅ **UploadParams sealed class**
   ```kotlin
   ❌ sealed class UploadParams {
   ❌     data class Single(...)
   ❌     data class MultiPage(...)
   ❌ }
   ```

6. ✅ **Deprecated UploadUiState States**
   ```kotlin
   ❌ data class Uploading(val progress: Float)
   ❌ data class Retrying(val attempt: Int, val maxAttempts: Int)
   ❌ data class Success(val taskId: String)
   ```

### UploadUiState (Finaler Zustand)

```kotlin
sealed class UploadUiState {
    data object Idle : UploadUiState()
    data object Queuing : UploadUiState()
    data object Queued : UploadUiState()
    data class Error(
        val userMessage: String,
        val technicalDetails: String? = null,
        val isRetryable: Boolean = false
    ) : UploadUiState()
}
```

**State Flow:**
```
Idle → Queuing → Queued
  ↓       ↓
  └───→ Error
```

---

## Komponenten-Übersicht

### UploadViewModel

**Verantwortlichkeiten (Queue-Only):**
- ✅ UI State Management (Idle, Queuing, Queued, Error)
- ✅ Metadaten-Beobachtung (Tags, DocumentTypes, Correspondents)
- ✅ Tag-Erstellung
- ✅ AI-Suggestions Orchestrierung
- ✅ Dateien zur Queue hinzufügen
- ✅ Storage-Checks vor Queueing
- ✅ Status-spezifische Queue Messages

**Nicht mehr verantwortlich für:**
- ❌ Direkter Upload zu Server
- ❌ Retry-Logik
- ❌ Progress Tracking
- ❌ Server Health Monitoring
- ❌ Network Checks während Upload

### UploadQueueRepository

**Verantwortlichkeiten:**
- Upload-Items in Room Datenbank speichern
- Content URIs zu lokalem Storage kopieren (für Process Death Safety)
- Storage-Checks durchführen
- Upload-Queue beobachten (Flow)

### UploadWorkManager

**Verantwortlichkeiten:**
- WorkManager Jobs schedulen
- Upload-Worker starten (immediate oder delayed)
- Work Constraints definieren (WiFi, Battery, etc.)

### UploadWorker

**Verantwortlichkeiten:**
- ✅ Tatsächlicher Upload zu Paperless Server
- ✅ Exponential Backoff Retry (via WorkManager)
- ✅ Server Health Checks
- ✅ Network Monitoring
- ✅ Progress Tracking (via ForegroundInfo)
- ✅ Error Handling & User Notifications
- ✅ Queue Item Lifecycle (pending → uploading → completed/failed)

---

## Status-spezifische Queue Messages

Seit Version 1.4.85+ zeigt die App status-spezifische Nachrichten beim Queueing:

```kotlin
val queuedMessage = when {
    !isOnline -> stringResource(R.string.upload_queued_no_internet)
    !isServerReachable -> stringResource(R.string.upload_queued_server_offline)
    else -> stringResource(R.string.upload_queued_processing)
}
```

**Nachrichten:**
- 🟢 **Beide online:** "Dokument wird im Hintergrund hochgeladen"
- 🔴 **Server offline:** "Upload startet, sobald Server erreichbar ist"
- 🔴 **Internet offline:** "Upload startet, sobald Internetverbindung besteht"

**String Resources (17 Sprachen):**
- `upload_queued_processing`
- `upload_queued_server_offline`
- `upload_queued_no_internet`

### Nicht-blockierende Navigation

**BEST PRACTICE: Sofortige Navigation nach Queueing**

Um die User Experience zu optimieren, verwenden alle Upload-Screens nicht-blockierende Snackbars:

```kotlin
LaunchedEffect(uiState) {
    when (val state = uiState) {
        is UploadUiState.Queued -> {
            // Snackbar in separater Coroutine (non-blocking)
            launch { snackbarHostState.showSnackbar(queuedMessage) }
            // Sofortige Navigation zurück (wartet nicht auf Snackbar)
            onUploadSuccess()
        }
    }
}
```

**Vorteile:**
- ⚡ **Instant Navigation:** Keine 4-Sekunden Wartezeit mehr
- 📱 **Bessere UX:** User wird nicht aufgehalten
- ✅ **Feedback erhalten:** Snackbar-Nachricht wird trotzdem kurz angezeigt
- 🔄 **Konsistent:** Alle Upload-Screens folgen dem gleichen Pattern

### Custom Snackbar Design

**Dark Tech Precision Pro Snackbar**

Alle Upload-Screens verwenden eine Custom Snackbar-Komponente (`CustomSnackbarHost`) die dem App-Design folgt:

**Design-Features:**
- 🌓 **Light/Dark Mode Support** - Automatische Anpassung via MaterialTheme.colorScheme
- 📐 **20dp Corner Radius** (statt Material 3 Standard 4dp)
- 🔲 **1dp Subtle Outline** - Passt sich dem Theme an
- 🚫 **No Elevation** (0dp) - Flaches Design ohne Schatten
- 📍 **Top Position** - Verdeckt keine Bottom Navigation
- 🧠 **Smart Icons** - Automatische Icon-Auswahl basierend auf Message-Inhalt

**Farben nach Theme:**

**DARK MODE:**
- Background: `#141414` (dark surface)
- Text/Icons: `#E1FF8D` (neon-yellow primary)
- Border: `#27272A` (dark outline)

**LIGHT MODE:**
- Background: Neon-yellow surface
- Text/Icons: `#0A0A0A` (deep black primary)
- Border: Dark outline

**Implementiert in:**
- `ui/components/CustomSnackbar.kt` - Reusable Composable
- Alle Upload-Screens verwenden `CustomSnackbarHost` statt Standard `SnackbarHost`
- Position: `Modifier.align(Alignment.TopCenter)` via Box Layout
- Theme-aware: Verwendet `MaterialTheme.colorScheme` statt hardcoded Farben

---

## Migration & Testing

### UI Screens angepasst

1. ✅ **UploadScreen.kt**
   - Removed: Uploading/Retrying/Success States
   - Added: Queuing State
   - Added: Status-specific messages

2. ✅ **MultiPageUploadScreen.kt**
   - Removed: Progress Indicators
   - Removed: Retry Button
   - Added: Queuing State
   - Added: Status-specific messages

3. ✅ **BatchMetadataScreen.kt**
   - Removed: Static success messages (single/multiple)
   - Added: Status-specific messages
   - Changed: BatchImportUiState.Success → Queued

### Tests aktualisiert

**UploadViewModelTest.kt:**
- ✅ Alle ViewModel-Konstruktor Aufrufe aktualisiert
- ✅ DocumentRepository Mocks durch UploadQueueRepository ersetzt
- ✅ Direct upload tests durch Queue tests ersetzt
- ✅ Retry tests entfernt (WorkManager handles this)
- ✅ Success State tests durch Queued State tests ersetzt
- ✅ NetworkMonitor.checkOnlineStatus() durch isOnline StateFlow ersetzt

**Test-Abdeckung:**
- Queue Success (Single & Multi-Page)
- Queue Errors (Storage, File not found)
- State Transitions (Idle → Queuing → Queued)
- Parameter Verification (Queue Repository calls)
- Reset State Functionality

---

## Vorteile des Queue-Only Ansatzes

### 1. Wartbarkeit
- **Code-Reduktion:** ~30% weniger Code
- **Einfacheres Debugging:** Nur ein Upload-Pfad
- **Klare Verantwortlichkeiten:** Separation of Concerns

### 2. Zuverlässigkeit
- **Process Death Safety:** WorkManager überlebt App-Neustart
- **Battery-optimiert:** WorkManager respektiert Doze Mode
- **Automatisches Retry:** Exponential Backoff out-of-the-box
- **Offline-First:** Immer robust, egal ob online oder offline

### 3. Konsistenz
- **Einheitliches Verhalten:** Alle Screens nutzen gleichen Pfad
- **Gleiche Fehlerbehandlung:** Keine Unterschiede zwischen Direct/Queue
- **Predictable State:** Klare State-Machine (Idle → Queuing → Queued → Error)

### 4. User Experience
- **Transparenz:** Status-spezifische Nachrichten
- **Kein Blocking:** Uploads laufen im Hintergrund
- **Sofortige Navigation:** Navigation zurück erfolgt ohne Wartezeit
- **Fortschritt sichtbar:** Notification mit Progress
- **Kein Datenverlust:** Queue überlebt App-Crash

### 5. Performance
- **Weniger Memory:** Keine parallelen Upload-Pfade
- **Batching:** WorkManager kann Uploads batchen
- **Network-effizient:** WorkManager wartet auf gute Bedingungen

---

## Bekannte Einschränkungen

### Keine Sofortige Feedback

**Problem:** User sieht nicht sofort ob Upload erfolgreich war (alles geht zur Queue)

**Mitigation:**
- Status-spezifische Nachrichten zeigen erwartetes Verhalten
- Notification zeigt Upload-Fortschritt
- Queue Screen zeigt alle pending/failed uploads

### Tests für UploadWorker

**Problem:** UploadWorker Tests müssen noch geschrieben werden

**TODO:**
- Worker Success Tests
- Worker Retry Tests
- Worker Network Change Tests
- Worker Server Health Tests

---

## Zukunft & Roadmap

### Geplante Verbesserungen

1. **Queue Management Screen**
   - User kann Queue sehen
   - Failed uploads retry/delete
   - Priorität ändern

2. **Upload Analytics**
   - Success/Failure Rate
   - Average Upload Time
   - Network vs. WiFi Performance

3. **Smart Batching**
   - Mehrere Dokumente in einem Request
   - Reduziert API Calls
   - Schnellere Verarbeitung

4. **Background Sync**
   - Periodisches Upload-Check
   - Stuck Uploads erkennen
   - Auto-Cleanup alter Queue Items

---

## Referenzen

**Code-Dateien:**
- `app/src/main/java/com/paperless/scanner/ui/screens/upload/UploadViewModel.kt`
- `app/src/main/java/com/paperless/scanner/ui/screens/upload/UploadScreen.kt`
- `app/src/main/java/com/paperless/scanner/ui/screens/upload/MultiPageUploadScreen.kt`
- `app/src/main/java/com/paperless/scanner/ui/screens/batchimport/BatchImportViewModel.kt`
- `app/src/main/java/com/paperless/scanner/ui/screens/batchimport/BatchMetadataScreen.kt`
- `app/src/main/java/com/paperless/scanner/ui/components/CustomSnackbar.kt`
- `app/src/main/java/com/paperless/scanner/data/repository/UploadQueueRepository.kt`
- `app/src/main/java/com/paperless/scanner/worker/UploadWorkManager.kt`
- `app/src/main/java/com/paperless/scanner/worker/UploadWorker.kt`

**Tests:**
- `app/src/test/java/com/paperless/scanner/ui/screens/upload/UploadViewModelTest.kt`
- `app/src/test/java/com/paperless/scanner/worker/UploadWorkerTest.kt`

**Verwandte Dokumente:**
- `docs/TECHNICAL.md` - Technische Architektur
- `docs/BEST_PRACTICES.md` - Best Practices
- `CLAUDE.md` - Project Instructions

---

**Erstellt von:** Claude Code (Archon Project: 782d8125-cce9-499c-bcfd-e4491bab4ccf)
**Projekt:** Upload Queue Vereinfachung
