# GitHub Release Notes - Beispiel

**Dieses Beispiel zeigt wie ein vollständig ausgefülltes GitHub Release aussehen sollte.**

---

## 📱 Paperless Scanner v1.5.0

**Release Date:** 2026-01-14
**Version Code:** 10500
**Track:** Internal Testing

---

## 🎯 Highlights

Diese Version bringt die **Paperless-GPT Integration** für direkte AI-Vorschläge ohne Firebase-Abhängigkeit, sowie **automatische OCR-Verbesserung** bei schlecht gescannten Dokumenten. Außerdem wurden wichtige Bugfixes für Tag-Verwaltung und Upload-Performance implementiert.

---

## ✨ Neue Features

- **Paperless-GPT Integration** - Direkte Integration mit Paperless-GPT Server für sofortige AI-Vorschläge
  - Konfigurierbar in Settings mit Health-Check Button
  - Unterstützt sowohl standalone Server als auch integrierte Paperless-ngx Plugin
  - Automatischer Fallback zu anderen AI-Providern bei Nichtverfügbarkeit
  - Fixes #142

- **Automatische OCR-Verbesserung** - Dokumente mit niedriger OCR-Qualität (<80% Confidence) werden automatisch nachbearbeitet
  - Triggert Paperless-GPT OCR-Job im Hintergrund
  - Status-Polling alle 2 Sekunden (max 2 Minuten)
  - User kann während OCR-Verarbeitung normal weiterarbeiten
  - Analytics-Tracking für Erfolgs-/Fehlerrate
  - Fixes #138

- **Tag-Löschung mit Dokumentenanzahl** - Bestätigungs-Dialog zeigt jetzt an, wie viele Dokumente von Tag-Löschung betroffen sind
  - Warnung bei >10 betroffenen Dokumenten
  - Separate Nachrichten für leere vs. verwendete Tags
  - Best Practice: 2-Phase Deletion (Prepare → Confirm)
  - Fixes #156

## 🐛 Fehlerbehebungen

- **Fix: App Crash bei Tag-Löschung** - Tags mit mehr als 100 Dokumenten verursachten ClassCastException beim Löschen
  - Root Cause: MockK relaxed mode gab Object statt List zurück
  - Solution: Explizite `any()` Matcher in Test-Mocks
  - Fixes #145

- **Fix: Upload-Progress bei großen PDFs** - Progress-Anzeige blieb bei 0% stehen für Multi-Page PDFs >10MB
  - Root Cause: Progress-Callbacks wurden nicht korrekt aggregiert
  - Solution: Gewichteter Progress basierend auf Page-Count
  - Fixes #151

- **Fix: OCR Confidence Feld fehlte** - Document API Response enthielt `ocrConfidence` aber Model-Mapping fehlte
  - Added `ocrConfidence: Double?` zu API + Domain Models
  - Updated Mapper in DocumentMapper.kt
  - Fixes #149

## 🔧 Verbesserungen

- **Schnellere Paperless-GPT API Calls** - Timeout von 10s auf 30s erhöht für bessere LLM-Latenz-Toleranz
- **Bessere Fehler-Meldungen bei OCR-Jobs** - Konkrete Error-Messages statt generischer "Job failed"
- **Optimierte Test-Suite** - Reduzierung von 7 Sekunden auf 4 Sekunden durch parallele Mock-Initialisierung

## 📚 Technische Änderungen

- Added `PaperlessGptApi.kt` Retrofit interface mit 3 Endpoints
- Added `PaperlessGptRepository.kt` mit auto-trigger OCR logic
- Added `PaperlessGptBaseUrlInterceptor.kt` für flexible standalone/integrated mode
- Extended `TokenManager.kt` mit 3 neuen Paperless-GPT Preferences
- Added `ocrConfidence` field zu Document models (API + Domain + Mapper)
- Extended `AnalyticsEvent.kt` mit Paperless-GPT OCR tracking events
- Updated `UploadViewModel.kt` mit background OCR auto-trigger nach Upload
- Added 7 comprehensive unit tests für Tag-Deletion flow (2-phase pattern)
- Fixed MockK parameter matchers in `LabelsViewModelTest.kt` (getTags → getTags(any()))

## ⚠️ Breaking Changes

*Keine Breaking Changes in dieser Version*

## 🔐 Sicherheit

*Keine Security-relevanten Änderungen in dieser Version*

---

## 📲 Installation

### Google Play (Empfohlen)

- **Internal Track:** Nur für eingeladene Tester verfügbar
- **Beta Track:** Öffentliche Beta - Join via Google Play Console
- **Production:** Vollständiger Release für alle Nutzer

### Direkter Download (APK/AAB)

1. Lade `app-release.aab` aus den Release Assets herunter
2. Installiere mit `bundletool`:
   ```bash
   bundletool build-apks --bundle=app-release.aab --output=app.apks
   bundletool install-apks --apks=app.apks
   ```

⚠️ **Hinweis:** Direkt-Downloads sind nur für Entwicklung/Testing. Production Apps sollten über Google Play bezogen werden.

---

## 📝 Changelog (Vollständig)

### Deutsch (DE)

```
Version 1.5.0:

✨ Neue Features:
- Paperless-GPT Integration für direkte AI-Vorschläge
- Automatische OCR-Verbesserung bei schlechten Scans
- Tag-Löschung zeigt betroffene Dokumentenanzahl

🐛 Fehlerbehebungen:
- App Crash bei Tag-Löschung mit >100 Dokumenten behoben
- Upload-Progress für große PDFs korrigiert
- OCR Confidence Feld in Document API hinzugefügt

🔧 Verbesserungen:
- Schnellere Paperless-GPT API Calls (30s Timeout)
- Bessere Fehler-Meldungen bei OCR-Jobs
- Optimierte Test-Suite (4s statt 7s)
```

### English (EN)

```
Version 1.5.0:

✨ New Features:
- Paperless-GPT integration for direct AI suggestions
- Automatic OCR improvement for low-quality scans
- Tag deletion shows affected document count

🐛 Bug Fixes:
- Fixed app crash when deleting tags with >100 documents
- Fixed upload progress for large multi-page PDFs
- Added missing OCR confidence field to Document API

🔧 Improvements:
- Faster Paperless-GPT API calls (30s timeout)
- Better error messages for OCR jobs
- Optimized test suite (4s instead of 7s)
```

---

## 🔗 Links

- [GitHub Repository](https://github.com/napoleonmm83/paperless-scanner)
- [Issue Tracker](https://github.com/napoleonmm83/paperless-scanner/issues)
- [Paperless-ngx](https://github.com/paperless-ngx/paperless-ngx)
- [Paperless-GPT](https://github.com/icereed/paperless-gpt)
- [Dokumentation](https://github.com/napoleonmm83/paperless-scanner/tree/main/docs)
- [Google Play Store](https://play.google.com/store/apps/details?id=com.paperless.scanner)

---

## 🙏 Contributors

- @napoleonmm83 - Main development (Paperless-GPT integration, OCR auto-trigger, bug fixes)
- Claude Sonnet 4.5 - AI-assisted development and code review

---

**Vollständige Änderungen:** [`v1.4.28...v1.5.0`](https://github.com/napoleonmm83/paperless-scanner/compare/v1.4.28...v1.5.0)

---

## 📸 Screenshots

### Paperless-GPT Settings

![Paperless-GPT Settings Screen](https://via.placeholder.com/800x1600/0A0A0A/E1FF8D?text=Settings+Screen)
*Neue Settings-Sektion für Paperless-GPT Konfiguration mit URL, Enable-Toggle und OCR Auto-Trigger*

### OCR Auto-Trigger in Aktion

![OCR Processing Indicator](https://via.placeholder.com/800x400/0A0A0A/E1FF8D?text=OCR+Processing)
*Hintergrund-Verarbeitung läuft während User normal weiterarbeiten kann*

### Tag-Löschung Dialog mit Dokumentenanzahl

![Tag Deletion Dialog](https://via.placeholder.com/600x400/0A0A0A/E1FF8D?text=Delete+Confirmation)
*Dialog zeigt klar an wie viele Dokumente von der Löschung betroffen sind*

---

## 📊 Statistiken

- **Code Changes:** +2,847 / -342 Zeilen
- **Files Changed:** 24 Dateien
- **Commits:** 12 Commits
- **Tests Added:** 7 neue Unit Tests
- **Test Coverage:** 87% → 89%
- **Build Time:** 65 Sekunden (Release)

---

## 🧪 Testing Notes

Diese Version wurde getestet mit:
- ✅ Paperless-ngx v1.17.0 (standalone)
- ✅ Paperless-ngx v1.17.0 + Paperless-GPT v1.2.0 (integrated)
- ✅ Android 8.0 (API 26) - Minimum SDK
- ✅ Android 14.0 (API 34) - Target SDK
- ✅ Verschiedene Bildgrößen (1MB - 50MB)
- ✅ Multi-Page PDFs (1 - 100 Seiten)
- ✅ Offline-Mode (Upload Queue)

Bekannte Limitationen:
- Paperless-GPT OCR benötigt separate Installation
- OCR Auto-Trigger funktioniert nur bei aktiviertem Paperless-GPT
- Maximale OCR Job Wartezeit: 2 Minuten (danach Timeout)

---

## 🔮 Nächste Schritte

Geplant für v1.6.0:
- Settings UI für Paperless-GPT URL Konfiguration
- End-to-End Testing mit echtem Paperless-GPT Server
- OCR Confidence Anzeige in Document Detail Screen
- Batch Upload mit individueller AI-Analyse pro Dokument

---

**Release erstellt am:** 2026-01-14 21:30 UTC
**Deployed to:** Google Play Console Internal Track
**Rollout:** 100% (alle Internal Tester)
