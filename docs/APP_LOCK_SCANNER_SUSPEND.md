# App-Lock Scanner Suspend/Resume Feature

## Problem

Wenn App-Lock aktiviert ist und der MLKit Document Scanner gestartet wird, tritt folgendes Problem auf:

1. Scanner startet als externe Activity
2. Paperless App geht in den Hintergrund
3. `ProcessLifecycleOwner.onStop()` wird aufgerufen
4. `backgroundTimestamp` wird gesetzt
5. User kehrt vom Scanner zurück (nach Abbruch oder Scan)
6. `ProcessLifecycleOwner.onStart()` wird aufgerufen
7. Timeout-Check: elapsed time > timeout → **App-Lock wird ausgelöst**

**Resultat:** User muss sich nach jedem Scanner-Vorgang erneut authentifizieren, selbst bei sofortigem Abbruch.

## Lösung: Suspend/Resume Pattern

Das Timeout wird während der Scanner-Activity **pausiert** (suspended):

```
User startet Scanner
    ↓
suspendForScanner()  → Timeout pausiert
    ↓
Scanner läuft (beliebig lange, max 10 Min)
    ↓
Scanner kehrt zurück
    ↓
resumeFromScanner()  → Timeout läuft weiter
    ↓
Kein App-Lock!
```

## Implementierung

### 1. AppLockManager.kt - Suspend/Resume Methoden

```kotlin
fun suspendForScanner() {
    isSuspended = true
    suspendStartTime = SystemClock.elapsedRealtime()
    suspendedBy = "mlkit_scanner"

    // Auto-Resume nach 10 Minuten (Security Failsafe)
    autoResumeJob = scope.launch {
        delay(MAX_SUSPEND_DURATION_MILLIS)
        if (isSuspended) resumeFromScanner()
    }
}

fun resumeFromScanner() {
    isSuspended = false
    autoResumeJob?.cancel()
    backgroundTimestamp = System.currentTimeMillis() // Reset
}
```

### 2. ScanScreen.kt - Integration

```kotlin
fun startScanner() {
    scanner.getStartScanIntent(context as Activity)
        .addOnSuccessListener { intentSender ->
            // Suspend DIREKT vor Launch (Race Condition Fix)
            viewModel.appLockManager.suspendForScanner()
            scannerLauncher.launch(intentSender)
        }
}

val scannerLauncher = rememberLauncherForActivityResult(...) { result ->
    // Resume IMMER, auch bei Abbruch/Error
    viewModel.appLockManager.resumeFromScanner()

    if (result.resultCode == Activity.RESULT_OK) {
        // Handle result...
    }
}
```

### 3. Lifecycle Callbacks

```kotlin
override fun onStop(owner: LifecycleOwner) {
    // Skip backgroundTimestamp wenn suspended
    if (!isSuspended) {
        backgroundTimestamp = System.currentTimeMillis()
    }
}

override fun onStart(owner: LifecycleOwner) {
    // Force-Resume bei Process Death (> 1000ms suspended)
    if (isSuspended && suspendDuration > 1000L) {
        resumeFromScanner()
        return // Skip Timeout-Check!
    }

    // Skip Timeout-Check wenn suspended
    if (shouldSkipTimeoutCheck()) return

    // Normal Timeout-Check...
}
```

## Sicherheits-Mitigations

### 1. Unbalanced Calls Protection
**Problem:** `resume()` wird nie aufgerufen (Crash, Force-Kill) → Timeout bleibt dauerhaft suspended

**Mitigation:**
- Auto-Resume nach 10 Minuten
- Duplicate `suspend()` calls werden ignoriert
- Missing `resume()` calls werden gracefully gehandled

### 2. Process Death Protection
**Problem:** App wird gekillt während suspended → State bleibt nach Neustart erhalten

**Mitigation:**
- Suspended state ist **NIEMALS persistiert** (in-memory only)
- Cold Start Detection: Force-Resume bei App-Start wenn suspended > 1s
- Verhindert "stuck suspended" vulnerability

### 3. Time Manipulation Protection
**Problem:** User ändert System-Zeit während suspended

**Mitigation:**
- `SystemClock.elapsedRealtime()` statt `System.currentTimeMillis()`
- Monotonic clock, immun gegen Zeit-Änderungen
- Negative Time Protection

### 4. Maximum Suspend Duration
**Problem:** Scanner läuft ewig → Timeout nie wieder aktiv

**Mitigation:**
- Hard Limit: 10 Minuten
- Auto-Resume via Coroutine Job
- Cancellable bei manuellem Resume

### 5. Race Condition Fix
**Problem:** `suspend()` zu früh → `onStop()` passiert vor `isSuspended=true`

**Mitigation:**
- `suspend()` DIREKT vor `scannerLauncher.launch()`
- Async `getStartScanIntent()` abwarten
- Minimal Time Window zwischen suspend und Launch

### 6. Force-Resume Early Exit
**Problem:** Nach Force-Resume wird Timeout-Check durchgeführt → sofortiger Lock

**Mitigation:**
- Nach Force-Resume: `return` (keine weitere Verarbeitung)
- `backgroundTimestamp` wurde gerade gesetzt → elapsed ~0ms
- Bei IMMEDIATE Timeout würde Lock sofort triggern

## Testing

### Erfolgreiche Test-Szenarien

✅ **Scanner Abbruch (1.6s)**
```
16:38:20.184 ⏸️ Timeout suspended
16:38:21.807 ⚠️ Force resuming (1623ms)
16:38:21.808 Force-resumed, skipping timeout check
→ Kein App-Lock
```

✅ **Normaler Scan (5.8s)**
```
16:38:23.424 ⏸️ Timeout suspended
16:38:29.200 ⚠️ Force resuming (5775ms)
16:38:29.201 Force-resumed, skipping timeout check
→ Kein App-Lock, Document erfolgreich hochgeladen
```

✅ **Echter App-Wechsel (Home-Button)**
```
→ Timeout NICHT suspended
→ Normal backgroundTimestamp gesetzt
→ App-Lock triggert wie erwartet
```

### Edge Cases

⚠️ **Scanner > 10 Minuten**
```
Timeout suspended → 10 Min Auto-Resume → App-Lock triggert
→ Erwartetes Verhalten (Security)
```

## Limitierungen

| Aspekt | Limitierung | Grund |
|--------|-------------|-------|
| **Max Suspend Duration** | 10 Minuten | Security: Verhindert dauerhaft suspended state |
| **Suspend Detection** | > 1 Sekunde | Race Condition: Scanner-Start vs. Process Death |
| **Scanner-Specific** | Nur MLKit Scanner | Andere Activities könnten andere Timeouts brauchen |

## Logs

**Erfolgreicher Scanner-Vorgang:**
```
AppLockManager: ⏸️ Timeout suspended for scanner (max 10 minutes)
AppLockManager: onStop: timeout is SUSPENDED, NOT setting timestamp
AppLockManager: ⚠️ SECURITY: App started while suspended for 1623ms - force resuming
AppLockManager: ▶️ Resumed after 1623ms suspend (1s)
AppLockManager: onStart: Force-resumed, skipping timeout check this time
```

**Monitoring:**
```bash
adb logcat | grep AppLockManager
```

## Architektur-Diagramm

```
┌─────────────────────────────────────────────────────────┐
│                    User Action                           │
│                "Start Scanner"                           │
└────────────────────┬────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────┐
│              ScanScreen.startScanner()                   │
│  scanner.getStartScanIntent()                            │
│    .addOnSuccessListener {                               │
│      appLockManager.suspendForScanner()  ◄───┐          │
│      scannerLauncher.launch()                 │          │
│    }                                          │          │
└────────────────────┬──────────────────────────┼─────────┘
                     │                          │
                     ▼                          │
┌─────────────────────────────────────────────┐ │
│         AppLockManager                      │ │
│  isSuspended = true                         │ │
│  suspendStartTime = now                     │ │
│  autoResumeJob.launch(10 min)               │ │
└────────────────────┬────────────────────────┘ │
                     │                          │
                     ▼                          │
┌─────────────────────────────────────────────┐ │
│      MLKit Scanner Activity                 │ │
│    (User scans document)                    │ │
└────────────────────┬────────────────────────┘ │
                     │                          │
                     ▼                          │
┌─────────────────────────────────────────────┐ │
│  ProcessLifecycleOwner.onStop()             │ │
│    if (!isSuspended) {  ◄───────────────────┘
│      backgroundTimestamp = now
│    } else {
│      // SKIP - Scanner läuft
│    }
└────────────────────┬────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────┐
│   User kehrt vom Scanner zurück             │
└────────────────────┬────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────┐
│  ProcessLifecycleOwner.onStart()            │
│    if (isSuspended && duration > 1s) {      │
│      resumeFromScanner()                    │
│      return  // Skip Timeout-Check!         │
│    }                                        │
└────────────────────┬────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────┐
│  scannerLauncher callback                   │
│    resumeFromScanner()                      │
│    // Process result...                     │
└─────────────────────────────────────────────┘
```

## Code Locations

| Komponente | Datei | Zeilen |
|------------|-------|--------|
| **Suspend/Resume** | `AppLockManager.kt` | 237-315 |
| **Lifecycle Callbacks** | `AppLockManager.kt` | 389-509 |
| **Scanner Integration** | `ScanScreen.kt` | 207-227 |
| **Scanner Callback** | `ScanScreen.kt` | 133-148 |
| **ViewModel Injection** | `ScanViewModel.kt` | 83 |

## Changelog

### v1.4.54 - 2026-01-17
- ✅ Suspend/Resume Pattern implementiert
- ✅ Alle 6 Sicherheits-Mitigations aktiv
- ✅ Race Condition Fixes
- ✅ Force-Resume Early Exit
- ✅ Tested: Scanner Abbruch & Normaler Scan funktionieren

## Weiterführende Dokumentation

- `docs/APP_LOCK_TEST.md` - Manuelle Test-Cases
- `docs/SECURITY_ANALYSIS.md` - Security Threat Model
- `app/src/main/java/com/paperless/scanner/util/AppLockManager.kt` - Source Code
