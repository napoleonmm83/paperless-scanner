# App-Lock Test-Anleitung

## Status: Phase 1 - Core Implementation ✅

**Fertig:**
- ✅ AppLockManager Service (BCrypt, Lifecycle-aware)
- ✅ DataStore Integration (TokenManager erweitert)
- ✅ AppLockScreen UI (Material 3 Design)
- ✅ AppLockViewModel
- ✅ Navigation Route & Integration
- ✅ Strings für alle 16 Sprachen
- ✅ jBCrypt Dependency

**Noch fehlend für vollständigen Test:**
- ⏳ Settings UI (zum Aktivieren/Konfigurieren)
- ⏳ Password Setup Flow
- ⏳ Navigation Interceptor (automatisches Sperren)

---

## Manueller Test (Developer Mode)

Da die Settings-UI noch fehlt, kannst du die App-Lock Funktionalität manuell testen.

### Option 1: Direkter UI-Test (AppLockScreen)

Du kannst den AppLockScreen direkt öffnen um die UI zu testen:

1. **Öffne AppLockScreen.kt** in Android Studio
2. **Führe den Preview aus** (falls Preview verfügbar)
3. **Oder navigiere manuell:**
   ```kotlin
   // In deinem Code temporär hinzufügen:
   navController.navigate(Screen.AppLock.route)
   ```

### Option 2: Programmatischer Test

Erstelle eine Test-Klasse:

```kotlin
// In einem ViewModel oder Screen temporär:
viewModelScope.launch {
    // 1. Test: Passwort setzen
    appLockManager.setupAppLock("1234")

    // 2. Test: Prüfen ob gesperrt
    val shouldLock = appLockManager.shouldLock()
    println("Should Lock: $shouldLock")

    // 3. Test: Mit richtigem Passwort entsperren
    val unlocked = appLockManager.unlockWithPassword("1234")
    println("Unlocked: $unlocked")

    // 4. Test: Mit falschem Passwort
    val failedUnlock = appLockManager.unlockWithPassword("0000")
    println("Failed Unlock: $failedUnlock")
    println("Remaining Attempts: ${appLockManager.getRemainingAttempts()}")
}
```

### Option 3: Manuelle DataStore-Manipulation

Aktiviere App-Lock manuell via TokenManager:

```kotlin
// In einer Composable oder ViewModel:
LaunchedEffect(Unit) {
    tokenManager.setAppLockEnabled(true)
    tokenManager.setAppLockPassword("hashed_password_here")
    tokenManager.setAppLockTimeout(AppLockTimeout.FIVE_MINUTES)
}
```

---

## Build & Run

### 1. Gradle Sync
```bash
./gradlew --stop
./gradlew build
```

### 2. Auf Gerät installieren
```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 3. Logcat beobachten
```bash
adb logcat | grep -i "AppLock"
```

---

## Was funktioniert bereits

### ✅ AppLockManager
- BCrypt Password-Hashing (10 Rounds)
- Unlock mit Passwort
- Unlock mit Biometrie
- Fail-Safe (5 Fehlversuche → Logout)
- Lifecycle Observer (Background/Foreground Detection)
- Timeout-Management (Sofort, 1min, 5min, 15min, 30min)

### ✅ DataStore (TokenManager)
- `app_lock_enabled` - Boolean
- `app_lock_password_hash` - String (BCrypt Hash)
- `app_lock_biometric_enabled` - Boolean
- `app_lock_timeout` - String (Enum)

### ✅ AppLockScreen UI
- Material 3 Design (Dark Tech Precision Pro Style)
- Password Input mit Show/Hide Toggle
- Biometric Button (wenn verfügbar)
- Error Messages mit verbleibenden Versuchen
- Locked-Out State

### ✅ AppLockViewModel
- Password-Unlock Logic
- Biometric-Unlock Logic
- State Management (Idle, Unlocking, Unlocked, LockedOut, Error)
- Failed Attempts Tracking

---

## Bekannte Einschränkungen (aktuell)

1. **Keine Settings UI** - App-Lock kann nicht über UI aktiviert werden
2. **Kein Password Setup Flow** - Passwort muss programmatisch gesetzt werden
3. **Kein automatisches Sperren** - Navigation Interceptor fehlt noch
4. **Kein Biometric-Fallback** - Wenn Biometric fehlschlägt, gibt es keine Rückfalloptionen

---

## Nächste Schritte

### Phase 2: Settings Integration
1. Settings-Screen erweitern mit "App-Sperre" Sektion
2. Enable/Disable Toggle
3. Timeout-Auswahl (Dropdown/Dialog)
4. Biometric-Toggle
5. "Passwort ändern" Button

### Phase 3: Password Setup Flow
1. SetupAppLockScreen erstellen
2. Passwort-Eingabe mit Bestätigung
3. Validierung (min. 4 Zeichen)
4. Password Mismatch Handling
5. "Passwort ändern" Flow

### Phase 4: Navigation Interceptor
1. Navigation Interceptor erstellen
2. Bei jeder Navigation Lock-Status prüfen
3. Bei Locked → umleiten zu AppLockScreen
4. White-List für ungeschützte Screens (Login, Onboarding)

---

## Debug-Tipps

### DataStore Inhalt prüfen
```kotlin
// In ViewModel oder Screen:
viewModelScope.launch {
    val enabled = tokenManager.isAppLockEnabledSync()
    val hash = tokenManager.getAppLockPasswordHash()
    val timeout = tokenManager.getAppLockTimeout()
    println("App-Lock: enabled=$enabled, hash=$hash, timeout=$timeout")
}
```

### Lock-State beobachten
```kotlin
LaunchedEffect(Unit) {
    appLockManager.lockState.collect { state ->
        println("Lock State: $state")
    }
}
```

### Biometric Availability prüfen
```kotlin
val canUseBiometric = biometricHelper.isAvailable()
println("Biometric Available: $canUseBiometric")
```

---

## Testing Checklist

- [ ] Gradle Build erfolgreich
- [ ] App startet ohne Crash
- [ ] AppLockScreen kann geöffnet werden (manuell)
- [ ] Password-Input funktioniert
- [ ] BCrypt Hashing funktioniert
- [ ] DataStore speichert/lädt korrekt
- [ ] Übersetzungen sind vorhanden (alle 16 Sprachen)
- [ ] UI entspricht Dark Tech Precision Pro Style

---

## Fragen/Probleme?

Falls etwas nicht funktioniert:
1. `./gradlew clean build` ausführen
2. Android Studio Cache leeren (File → Invalidate Caches)
3. Logcat auf Exceptions prüfen
4. DataStore-Pfad prüfen: `/data/data/com.paperless.scanner.debug/files/datastore/`
