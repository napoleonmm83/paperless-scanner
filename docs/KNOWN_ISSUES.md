# Known Issues & Lessons Learned

## Bekannte Probleme

### 1. Kotlin Daemon GC Crash

**Problem:**
```
The daemon has terminated unexpectedly on startup attempt #1
Problems may have occurred during auto-selection of GC.
```

**Ursache:** Kotlin 2.0 hat Probleme mit Parallel GC auf bestimmten Systemen.

**Lösung:** In `gradle.properties` hinzufügen:
```properties
kotlin.daemon.jvmargs=-Xmx2048m -XX:-UseParallelGC
```

---

### 2. Upload Response Format

**Problem:**
```
java.lang.IllegalStateException: Expected BEGIN_OBJECT but was STRING
```

**Ursache:** Die Paperless-ngx API `/api/documents/post_document/` gibt einen **Plain String** (Task-ID) zurück, kein JSON-Objekt.

**Falsche Annahme:**
```json
{"task_id": "abc-123"}
```

**Tatsächliche Response:**
```
"abc-123-def-456"
```

**Lösung:** `ResponseBody` statt Data Class verwenden:
```kotlin
@POST("api/documents/post_document/")
suspend fun uploadDocument(...): ResponseBody

// Im Repository:
val taskId = response.string().trim().removeSurrounding("\"")
```

**Lesson Learned:** Immer API-Dokumentation verifizieren oder Response testen bevor Datenmodelle erstellt werden.

---

### 3. MLKit Scanner nicht verfügbar

**Problem:** App stürzt ab oder Scanner startet nicht.

**Ursache:** Gerät/Emulator ohne Google Play Services.

**Lösung:** Emulator mit "Google Play" System Image verwenden, nicht nur "Google APIs".

---

### 4. Selbstsignierte Zertifikate

**Problem:** SSL-Verbindung schlägt fehl bei selbstsignierten Zertifikaten.

**Lösung:**
1. CA-Zertifikat auf Android-Gerät installieren
2. `network_security_config.xml` enthält bereits:
```xml
<trust-anchors>
    <certificates src="user" />
</trust-anchors>
```

---

### 5. Widget Crash auf OnePlus Android 11

**Problem:**
```
java.lang.IllegalArgumentException:
List adapter activity trampoline invoked without specifying target intent.
```

**Betroffene Geräte:** OnePlus mit Android 11 (OxygenOS 11.x)

**Stacktrace:**
```
at androidx.glance.appwidget.action.InvisibleActionTrampolineActivity
at v5.f.e (Unknown Source)
```

**Ursache:**
Glance Framework verwendet intern eine `InvisibleActionTrampolineActivity` um Widget-Klicks zu verarbeiten. Auf OnePlus Android 11 scheitert die Intent-Konstruktion durch OEM-spezifische Anpassungen.

**Lösung (implementiert seit v1.4.70):**
- `actionStartActivity()` ersetzt durch `ActionCallback`
- Direkte `PendingIntent` Erstellung mit `FLAG_IMMUTABLE`
- Umgeht Glance Trampoline-Activity komplett
- Fallback auf direktes `context.startActivity()` bei Fehler

**Code:**
```kotlin
class LaunchMainActivityCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, ...) {
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            pendingIntent.send()
        } catch (e: Exception) {
            context.startActivity(intent)  // Fallback
        }
    }
}
```

**Monitoring:** Firebase Crashlytics trackt Device-Manufacturer, Model, Android-Version

**Datei:** `app/src/main/java/com/paperless/scanner/widget/ScannerWidget.kt`

**Referenzen:**
- [Android 11 PendingIntent Mutability](https://developer.android.com/about/versions/12/behavior-changes-12#pending-intent-mutability)
- [Glance ActionCallback API](https://developer.android.com/jetpack/androidx/releases/glance)

---

### 6. Billing NullPointerException in ProxyBillingActivity

**Problem:**
```
java.lang.NullPointerException:
Attempt to invoke virtual method 'android.content.IntentSender
android.app.PendingIntent.getIntentSender()' on a null object reference
at com.android.billingclient.api.ProxyBillingActivity.onCreate
```

**Ursache:**
`launchBillingFlow()` wird aufgerufen bevor BillingClient verbunden ist → PendingIntent ist null → Crash

**Lösung (implementiert seit v1.4.70):**
- `isReady()` Check vor jedem `launchBillingFlow()`
- Null-safe callbacks in allen Billing-Operationen
- ProductDetails retry wenn Cache leer
- PENDING purchase state handling
- Graceful destroy() mit continuation cleanup

**Code:**
```kotlin
// Check if BillingClient is ready
if (billingClient?.isReady != true) {
    return PurchaseResult.Error("Billing not ready")
}

// Only then launch flow
billingClient.launchBillingFlow(activity, params)
```

**Edge Cases gefixt:**
1. **PENDING purchases** - Langsame Zahlungsmethoden (Überweisung)
2. **Null-safe destroy** - App destroyed während Purchase läuft
3. **ProductDetails retry** - Automatisches Nachladen bei leerer Cache
4. **Null-safe callbacks** - Alle Billing-Callbacks prüfen auf null

**Monitoring:** Firebase Crashlytics trackt alle Billing-Operationen

**Datei:** `app/src/main/java/com/paperless/scanner/data/billing/BillingManager.kt`

---

### 7. Launcher Icons fehlen

**Problem:**
```
Cannot resolve symbol '@mipmap/ic_launcher'
```

**Ursache:** Initiales Setup hat keine Icon-Dateien erstellt.

**Lösung:** Vector Drawables in allen mipmap-Ordnern erstellen.

---

## Best Practices

### API-Integration

1. **API-Response immer testen** bevor Data Classes erstellt werden
2. **Logging aktivieren** in Debug-Builds für API-Debugging
3. **ResponseBody verwenden** wenn Response-Format unklar

### Android Development

1. **Gradle Wrapper** immer committen (gradlew, gradle-wrapper.jar)
2. **libs.versions.toml** für zentrale Dependency-Verwaltung
3. **Sealed Classes** für UI State Management

### Projektstruktur

1. **Clean Architecture** von Anfang an
2. **Feature-basierte Packages** für Skalierbarkeit
3. **Dokumentation** parallel zur Entwicklung pflegen

---

## Debugging-Tipps

### OkHttp Logging

```kotlin
HttpLoggingInterceptor().apply {
    level = HttpLoggingInterceptor.Level.BODY
}
```

### Gradle Build mit Stack Trace

```bash
./gradlew assembleDebug --stacktrace
```

### Kotlin Daemon stoppen

```bash
./gradlew --stop
```

### Android Studio Cache leeren

`File → Invalidate Caches → Invalidate and Restart`
