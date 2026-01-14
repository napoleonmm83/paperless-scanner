# GitHub Release Notes Template

**Dieses Template MUSS für JEDES GitHub Release verwendet werden!**

---

## 📱 Paperless Scanner v{VERSION}

**Release Date:** {YYYY-MM-DD}
**Version Code:** {VERSION_CODE}
**Track:** {Internal Testing | Beta | Production}

---

## 🎯 Highlights

<!-- 1-3 Sätze mit den wichtigsten Änderungen dieser Version -->
<!-- Beispiel: "Diese Version bringt automatische OCR-Verbesserung für schlecht gescannte Dokumente und eine neue Settings-Oberfläche für Paperless-GPT Integration." -->

{Kurze Zusammenfassung der wichtigsten Features/Fixes}

---

## ✨ Neue Features

<!-- Liste ALLE neuen Features mit klarer Beschreibung -->
<!-- Format: - **{Feature Titel}** - {Was tut es und WARUM ist es wichtig} -->

- **{Feature Title}** - {Beschreibung was das Feature tut und warum es wichtig ist}
  - {Optional: Sub-Item für technische Details}
  - {Optional: Link zu Issue/PR: #123}

<!-- Beispiele:
- **Paperless-GPT Integration** - Verbindet die App direkt mit Paperless-GPT für sofortige AI-Vorschläge ohne Firebase-Abhängigkeit
  - Konfigurierbar in Settings mit Health-Check
  - Unterstützt sowohl standalone als auch integrierte Deployments
  - Fixes #123

- **Automatische OCR-Verbesserung** - Bei Dokumenten mit niedriger OCR-Qualität (<80%) wird automatisch ein Paperless-GPT OCR-Job gestartet
  - Läuft im Hintergrund, blockiert User nicht
  - Status-Polling alle 2 Sekunden
-->

## 🐛 Fehlerbehebungen

<!-- Liste ALLE Fixes mit klarer Problembeschreibung -->
<!-- Format: - **Fix: {Problem}** - {Was wurde behoben und wie} -->

- **Fix: {Problem Beschreibung}** - {Was wurde behoben und wie}
  - {Optional: Fixes #123}

<!-- Beispiele:
- **Fix: App Crash bei Tag-Löschung** - Tags mit mehr als 100 Dokumenten verursachten einen Crash beim Löschen
  - Fixes #145

- **Fix: Upload-Progress bei großen PDFs** - Progress-Anzeige blieb bei 0% stehen für PDFs >10MB
  - Fixes #156
-->

## 🔧 Verbesserungen

<!-- Liste ALLE Verbesserungen ohne Breaking Changes -->
<!-- Format: - **{Improvement Titel}** - {Was wurde verbessert} -->

- **{Improvement Title}** - {Was wurde verbessert}

<!-- Beispiele:
- **Schnellere App-Startzeit** - Initiale Ladezeit um 30% reduziert durch optimierte Splash-Screen-Logik
- **Bessere Fehler-Meldungen** - Netzwerkfehler zeigen jetzt konkrete Lösungsvorschläge
-->

## 📚 Technische Änderungen

<!-- Optional: Nur wenn relevant für Developer -->
<!-- Format: - {Änderung} -->

- {Architektur-Änderungen}
- {Dependency Updates}
- {Refactorings}

<!-- Beispiele:
- Upgraded Kotlin von 1.9 auf 2.0
- Migrated LabelsViewModel to reactive Flow-based architecture
- Added PaperlessGptRepository with automatic base URL switching
-->

## ⚠️ Breaking Changes

<!-- CRITICAL: Immer prominent anzeigen wenn vorhanden! -->
<!-- Leer lassen wenn keine Breaking Changes -->

<!-- Format: - **{Breaking Change}** - {Was bricht und wie migriert man} -->

<!-- Beispiel:
- **Paperless-ngx API v3 required** - Diese Version benötigt Paperless-ngx v1.17.0 oder höher
  - Migration: Update Paperless-ngx Server vor App-Update
-->

## 🔐 Sicherheit

<!-- Nur wenn relevante Security Fixes vorhanden -->
<!-- Leer lassen wenn keine Security Changes -->

<!-- Format: - {Security Fix} -->

<!-- Beispiel:
- Fixed SQL Injection vulnerability in search query (CVE-2024-XXXX)
- Updated OkHttp to 4.12.0 to patch TLS handshake issue
-->

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
{Kopie aus fastlane/metadata/android/de-DE/changelogs/{VERSION_CODE}.txt}
```

### English (EN)

```
{Kopie aus fastlane/metadata/android/en-US/changelogs/{VERSION_CODE}.txt}
```

---

## 🔗 Links

- [GitHub Repository](https://github.com/napoleonmm83/paperless-scanner)
- [Issue Tracker](https://github.com/napoleonmm83/paperless-scanner/issues)
- [Paperless-ngx](https://github.com/paperless-ngx/paperless-ngx)
- [Dokumentation](https://github.com/napoleonmm83/paperless-scanner/tree/main/docs)
- [Google Play Store](https://play.google.com/store/apps/details?id=com.paperless.scanner)

---

## 🙏 Contributors

<!-- Optional: Liste aller Contributor für dieses Release -->
<!-- Format: @username via #PR -->

<!-- Beispiel:
- @napoleonmm83 - Main development
- @contributor1 - Bug fixes (#145, #156)
- @contributor2 - Translation updates (French)
-->

---

**Vollständige Änderungen:** [`{PREVIOUS_VERSION}...{NEW_VERSION}`](https://github.com/napoleonmm83/paperless-scanner/compare/{PREVIOUS_VERSION}...{NEW_VERSION})

---

## 📸 Screenshots

<!-- Bei UI-Änderungen MÜSSEN Screenshots/GIFs hier eingefügt werden -->
<!-- Format: ![Alt Text](URL) -->

<!-- Beispiel:
### Neue Paperless-GPT Settings

![Settings Screen](https://user-images.githubusercontent.com/.../settings.png)

### OCR Quality Indicator

![OCR Indicator](https://user-images.githubusercontent.com/.../ocr-indicator.gif)
-->

---

## ✅ Release Checklist (vor Veröffentlichung)

Dieses Checklist MUSS vor jedem Release abgearbeitet werden:

- [ ] Version korrekt (Semantic Versioning: MAJOR.MINOR.PATCH)
- [ ] Release Date gesetzt (YYYY-MM-DD)
- [ ] Highlights vorhanden (1-3 Sätze)
- [ ] Alle Features dokumentiert mit "Warum wichtig"
- [ ] Alle Fixes dokumentiert mit klarer Problembeschreibung
- [ ] Breaking Changes prominent markiert (falls vorhanden)
- [ ] Screenshots/GIFs eingefügt (bei UI-Änderungen)
- [ ] Alle Issue/PR Links korrekt (#123)
- [ ] Installation-Anleitung aktuell
- [ ] Changelog DE + EN vollständig
- [ ] Comparison Link generiert (Previous...New)
- [ ] Rechtschreibung geprüft (DE + EN)
- [ ] Markdown-Preview geprüft (korrekte Formatierung)
- [ ] Release Assets hochgeladen (AAB/APK)
- [ ] Track korrekt gesetzt (Internal/Beta/Production)

---

**Template Version:** 1.0
**Last Updated:** 2026-01-14
