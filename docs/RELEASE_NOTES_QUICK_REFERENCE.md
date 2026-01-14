# GitHub Release Notes - Quick Reference

**Schnellreferenz für Claude Code beim Erstellen von GitHub Releases**

---

## ⚡ TL;DR Checkliste

Beim Erstellen eines GitHub Releases:

1. ✅ Verwende `docs/RELEASE_NOTES_TEMPLATE.md` als Basis
2. ✅ Schaue dir `docs/RELEASE_NOTES_EXAMPLE.md` für Inspiration an
3. ✅ Fülle ALLE Sektionen aus (nicht nur Changelog kopieren!)
4. ✅ Verlinke ALLE relevanten Issues/PRs (#123)
5. ✅ Bei UI-Änderungen: Screenshots/GIFs einbinden
6. ✅ Breaking Changes FETT hervorheben (falls vorhanden)
7. ✅ Rechtschreibung DE + EN prüfen
8. ✅ Markdown-Preview prüfen

---

## 📋 Pflicht-Sektionen

Jedes Release MUSS haben:

| Sektion | Pflicht? | Beschreibung |
|---------|----------|--------------|
| **Header** | ✅ Ja | Version, Date, Version Code, Track |
| **Highlights** | ✅ Ja | 1-3 Sätze Zusammenfassung |
| **Features** | ⚠️ Wenn vorhanden | Alle neuen Features mit "Warum wichtig" |
| **Fixes** | ⚠️ Wenn vorhanden | Alle Bugfixes mit Problembeschreibung |
| **Improvements** | ⚠️ Wenn vorhanden | Verbesserungen ohne Breaking Changes |
| **Technical Changes** | ⏩ Optional | Nur für Developer relevant |
| **Breaking Changes** | 🚨 KRITISCH | IMMER anzeigen wenn vorhanden! |
| **Security** | ⏩ Optional | Nur bei Security Fixes |
| **Installation** | ✅ Ja | Google Play + Direct Download |
| **Changelog DE+EN** | ✅ Ja | Vollständig aus Fastlane Metadata |
| **Links** | ✅ Ja | Repository, Issues, Docs, Play Store |
| **Contributors** | ⏩ Optional | Wer hat beigetragen |
| **Comparison Link** | ✅ Ja | GitHub Compare View |
| **Screenshots** | ⚠️ Bei UI-Änderungen | PNG/GIF max 2MB |

---

## 🎨 Formatierungs-Regeln

### Überschriften

```markdown
## 📱 Paperless Scanner v1.5.0    ← Titel mit Emoji
### Google Play (Empfohlen)        ← Subheader
```

### Features/Fixes/Improvements

```markdown
- **Feature Titel** - Beschreibung mit "Warum wichtig"
  - Optional: Sub-Item für Details
  - Fixes #123
```

### Breaking Changes (KRITISCH!)

```markdown
## ⚠️ Breaking Changes

- **API v3 required** - Diese Version benötigt Paperless-ngx v1.17.0+
  - Migration: Update Server vor App-Update
```

### Code Blocks

```markdown
```bash
bundletool install-apks --apks=app.apks
`` `
```

### Screenshots

```markdown
![Alt Text](https://user-images.githubusercontent.com/.../screenshot.png)
*Beschreibung was im Screenshot zu sehen ist*
```

---

## 🔍 Wo finde ich die Infos?

### Version & Version Code

```bash
cat version.properties
# VERSION_NAME=1.5.0
# VERSION_CODE=10500
```

### Fastlane Changelog

```
fastlane/metadata/android/de-DE/changelogs/{VERSION_CODE}.txt
fastlane/metadata/android/en-US/changelogs/{VERSION_CODE}.txt
```

### Git Changes seit letztem Release

```bash
# Commits seit letztem Tag
git log v1.4.28..HEAD --oneline

# Files changed
git diff v1.4.28..HEAD --stat

# Comparison URL
https://github.com/napoleonmm83/paperless-scanner/compare/v1.4.28...v1.5.0
```

### Issues/PRs für dieses Release

```bash
# Issues closed zwischen Tags
gh issue list --state closed --search "closed:>2026-01-01"

# PRs merged zwischen Tags
gh pr list --state merged --search "merged:>2026-01-01"
```

---

## 💡 Tipps & Tricks

### Gute Feature-Beschreibungen

❌ **SCHLECHT:**
```markdown
- Paperless-GPT Integration
```

✅ **GUT:**
```markdown
- **Paperless-GPT Integration** - Direkte Integration mit Paperless-GPT Server für sofortige AI-Vorschläge ohne Firebase-Abhängigkeit
  - Konfigurierbar in Settings mit Health-Check
  - Automatischer Fallback zu anderen Providern
  - Fixes #142
```

### Gute Fix-Beschreibungen

❌ **SCHLECHT:**
```markdown
- Fixed crash
```

✅ **GUT:**
```markdown
- **Fix: App Crash bei Tag-Löschung** - Tags mit mehr als 100 Dokumenten verursachten ClassCastException beim Löschen
  - Root Cause: MockK relaxed mode gab Object zurück
  - Fixes #145
```

### Highlights schreiben

Fasse die wichtigsten Änderungen in 1-3 Sätzen zusammen:

```markdown
## 🎯 Highlights

Diese Version bringt die **Paperless-GPT Integration** für direkte AI-Vorschläge
ohne Firebase-Abhängigkeit, sowie **automatische OCR-Verbesserung** bei schlecht
gescannten Dokumenten. Außerdem wurden wichtige Bugfixes für Tag-Verwaltung und
Upload-Performance implementiert.
```

### Screenshots einbinden

1. Screenshot erstellen (PNG, max 2MB)
2. Upload zu GitHub Issue oder Release Assets
3. Copy URL
4. Einbinden mit Alt-Text:

```markdown
![Paperless-GPT Settings Screen](https://user-images.githubusercontent.com/.../settings.png)
*Neue Settings-Sektion für Paperless-GPT Konfiguration*
```

---

## 🚨 Häufige Fehler vermeiden

### ❌ DON'T

1. **Nur Changelog kopieren** - Releases müssen umfassender sein!
2. **Vage Beschreibungen** - "Various fixes" ist NICHT akzeptabel
3. **Fehlende Issue-Links** - IMMER #123 verlinken
4. **Keine Breaking Changes Warnung** - KRITISCH!
5. **Technischer Jargon** - "Refactored ViewModel to Flow-based architecture" → "Schnellere und reaktivere Tag-Verwaltung"
6. **Rechtschreibfehler** - Immer Korrekturlesen!
7. **Kaputtes Markdown** - Preview prüfen!

### ✅ DO

1. **Benutzerfreundliche Sprache** - Erkläre "Was" und "Warum", nicht "Wie"
2. **Konkrete Problembeschreibungen** - Bei Fixes das Problem klar benennen
3. **Issue/PR Verlinkung** - Macht Releases nachvollziehbar
4. **Screenshots bei UI-Änderungen** - Zeigen ist besser als beschreiben
5. **Breaking Changes prominent** - Nutzer MÜSSEN das sehen!
6. **Comparison Link** - Ermöglicht Review des gesamten Diffs

---

## 📊 Beispiel: Von Commits zu Release Notes

### Input: Git Log

```
feat(paperless-gpt): add API integration with health check
fix(tags): resolve crash when deleting tags with >100 docs
fix(upload): correct progress calculation for multi-page PDFs
feat(ocr): implement automatic OCR job trigger for low quality
test(labels): add comprehensive 2-phase deletion tests
docs(claude): add release notes best practice guidelines
```

### Output: Release Notes

```markdown
## ✨ Neue Features

- **Paperless-GPT Integration** - Direkte Integration für AI-Vorschläge
  - Health-Check Button in Settings
  - Fixes #142

- **Automatische OCR-Verbesserung** - Bei niedriger Qualität (<80%)
  - Läuft im Hintergrund
  - Fixes #138

## 🐛 Fehlerbehebungen

- **Fix: Crash bei Tag-Löschung** - Tags mit >100 Dokumenten
  - Fixes #145

- **Fix: Upload-Progress** - Multi-Page PDFs zeigten falschen Progress
  - Fixes #151

## 📚 Technische Änderungen

- Added 7 unit tests for tag deletion (2-phase pattern)
- Updated release documentation with best practices
```

---

## 🔗 Referenzen

- **Template:** `docs/RELEASE_NOTES_TEMPLATE.md`
- **Beispiel:** `docs/RELEASE_NOTES_EXAMPLE.md`
- **Best Practice:** `CLAUDE.md` → "GitHub Release Dokumentation"
- **Fastlane Metadata:** `fastlane/metadata/android/{locale}/changelogs/`

---

**Last Updated:** 2026-01-14
**For:** Claude Code AI Assistant
