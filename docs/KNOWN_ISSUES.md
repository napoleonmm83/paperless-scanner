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

### 5. Launcher Icons fehlen

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
