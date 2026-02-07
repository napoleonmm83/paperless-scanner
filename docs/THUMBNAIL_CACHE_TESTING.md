# Thumbnail Cache Testing Guide

## Zweck

Dieser Guide erklärt, wie man verifiziert, dass der OkHttp Disk Cache korrekt funktioniert.

---

## Voraussetzungen

- App installiert (Debug oder Release)
- ADB installiert und Gerät/Emulator verbunden
- Paperless Server mit Dokumenten verfügbar

---

## Test-Schritte

### 1. Cache-Verzeichnis vor Test leeren

```bash
# Verbinde mit ADB Shell
adb shell

# Navigiere zum App-Cache
cd /data/data/com.paperless.scanner.debug/cache

# Liste Verzeichnisse (sollte leer sein oder kein http_cache)
ls -la

# Falls http_cache existiert, lösche es
rm -rf http_cache

# Exit Shell
exit
```

### 2. App starten und Dokumente ansehen

1. Öffne die App
2. Navigiere zur Dokumentenliste
3. **Warte 5 Sekunden** (damit Thumbnails geladen werden)
4. Schließe die App **NICHT**

### 3. Cache-Verzeichnis überprüfen

```bash
adb shell

cd /data/data/com.paperless.scanner.debug/cache

# Check ob http_cache erstellt wurde
ls -la

# Erwartung:
# drwxrwx--- 2 u0_a123 u0_a123 4096 2026-01-30 20:00 http_cache

# Navigiere in Cache-Verzeichnis
cd http_cache

# Liste Cache-Einträge
ls -la

# Erwartung: Mehrere Dateien (Journal + Cache-Einträge)
# -rw------- 1 u0_a123 u0_a123   520 2026-01-30 20:00 journal
# -rw------- 1 u0_a123 u0_a123  1920 2026-01-30 20:00 abc123.0
# -rw------- 1 u0_a123 u0_a123  2048 2026-01-30 20:00 def456.0
# ...

# Prüfe Gesamtgröße
du -sh .

# Erwartung: Wenige KB bis MB (je nach Anzahl Dokumente)
```

### 4. Cache-Hit Test (wichtigster Test!)

Dieser Test verifiziert, dass gecachte Thumbnails **NICHT** erneut vom Server geladen werden.

```bash
# In einem separaten Terminal: Netzwerk-Traffic monitoren
adb shell

# Erfordert Root oder Developer-Build
# Alternative: Wireshark/Charles Proxy mit SSL Interception

# Oder: Paperless Server Logs monitoren
docker logs -f paperless
```

**App-Test:**

1. Öffne Dokumentenliste (Thumbnails laden = Network Requests)
2. Notiere Anzahl der Thumbnail-Requests im Server-Log
3. **Scrolle nach oben** (zurück zu bereits geladenen Thumbnails)
4. **Erwartung:** **KEINE** neuen Thumbnail-Requests im Server-Log!
5. Thumbnails sollten **sofort** erscheinen (aus Cache)

**Flugmodus-Test (Alternative):**

1. Dokumentenliste öffnen, warten bis alle Thumbnails geladen
2. **Flugmodus aktivieren** (Wifi + Mobile Daten aus)
3. Zurück zur App navigieren (z.B. zu Home, dann wieder zu Dokumenten)
4. **Erwartung:** Thumbnails werden **trotzdem angezeigt** (aus Cache)!

### 5. Cache-Control Header Test (Advanced)

Verifiziere dass der Cache-Forcing Interceptor funktioniert:

```bash
# Mit ADB Logcat
adb logcat | grep "OkHttp"

# In der App: Thumbnail laden
# Such nach Header-Logs (nur in DEBUG builds):
# D/OkHttp: Cache-Control: public, max-age=604800
```

---

## Erfolgs-Kriterien

| Test | Erwartetes Verhalten | Status |
|------|---------------------|--------|
| **Cache-Verzeichnis** | `http_cache/` existiert | ⬜ |
| **Cache-Dateien** | Journal + mehrere `.0` Dateien | ⬜ |
| **Cache-Hit** | Keine Server-Requests bei Re-Scroll | ⬜ |
| **Offline-Modus** | Thumbnails trotzdem sichtbar | ⬜ |
| **Cache-Größe** | < 50MB (konfiguriertes Limit) | ⬜ |

---

## Troubleshooting

### Cache-Verzeichnis wird nicht erstellt

**Problem:** `/cache/http_cache` existiert nicht nach App-Start

**Mögliche Ursachen:**
1. OkHttpClient wird nicht benutzt (Coil nutzt eigenen Client?)
2. Keine Thumbnail-Requests (keine Dokumente?)
3. Permissions-Problem (sehr unwahrscheinlich)

**Lösung:**
- Prüfe ob Coil den bereitgestellten OkHttpClient nutzt (siehe CoilModule)
- Stelle sicher dass mindestens 1 Dokument existiert

### Cache-Hit funktioniert nicht

**Problem:** Thumbnails werden immer vom Server geladen

**Mögliche Ursachen:**
1. Cache-Forcing Interceptor überschreibt Header nicht
2. Server sendet `Vary` Header der Caching verhindert
3. OkHttp Cache ist deaktiviert

**Lösung:**
- Prüfe Logcat für "Cache-Control" Logs
- Verifiziere dass `addNetworkInterceptor()` verwendet wird (nicht `addInterceptor()`)

### Cache wird zu groß

**Problem:** Cache überschreitet 50MB

**Lösung:**
- Normales Verhalten - OkHttp räumt automatisch auf bei 50MB
- Falls Cache größer: Bug in OkHttp Cache-Implementation

---

## Performance-Metriken

Nach erfolgreichem Cache-Test sollten folgende Verbesserungen messbar sein:

| Metrik | Ohne Cache | Mit Cache | Verbesserung |
|--------|-----------|-----------|--------------|
| **Thumbnail-Ladezeit** | ~200ms | ~5ms | **40x schneller** |
| **Netzwerk-Requests** | 100% | ~5% | **95% weniger** |
| **Scroll-Performance** | Ruckeln | Smooth | **Deutlich besser** |
| **Datenverbrauch** | Hoch | Niedrig | **Signifikant** |

---

## Nächste Schritte

Nach erfolgreichem Cache-Test:
1. ✅ Task #3 als erledigt markieren
2. ➡️ Weiter mit Task #4: Thumbnail API Endpoint zu PaperlessApi hinzufügen
3. ➡️ Coil ImageLoader konfigurieren (Task #5)
