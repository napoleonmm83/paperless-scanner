# Play Store Quick Reference

Schneller Zugriff auf alle Dateien und Texte die du für die Play Store Submission brauchst.

---

## 📱 App Bundle (AAB)

**Pfad:**
```
app/build/outputs/bundle/release/app-release.aab
```

**Wie erstellen:**
```bash
./gradlew bundleRelease
```

---

## 🖼️ Screenshots

**Pfad (8 Dateien):**
```
fastlane/metadata/android/en-US/images/phoneScreenshots/
├── 1_hero_upload.png
├── 2_scan.png
├── 3_ai_suggestions.png
├── 4_documents_list.png
├── 5_settings_applock.png
├── 6_home.png
├── 7_scan_result.png
└── 8_login.png
```

**Deutsche Version (identisch):**
```
fastlane/metadata/android/de-DE/images/phoneScreenshots/
```

---

## 📝 Texte für Play Store

### App-Name
```
Paperless Scanner
```

### Kurzbeschreibung (80 chars max)

**Deutsch:** (68 chars)
```
Nativer Paperless-ngx Scanner mit KI-Vorschlägen. Schnell & privat.
```

**Englisch:** (77 chars)
```
Native Paperless-ngx scanner with AI-powered tag suggestions. Fast & private.
```

### Vollständige Beschreibung

**Deutsch:**
```
Datei: fastlane/metadata/android/de-DE/full_description.txt
Länge: ~2150 chars
```

**Englisch:**
```
Datei: fastlane/metadata/android/en-US/full_description.txt
Länge: ~2050 chars
```

---

## 🎨 App Icon

**512x512 PNG:**

Exportiere aus:
```
app/src/main/res/mipmap-xxxhdpi/ic_launcher.png
```

Oder skaliere auf 512x512 mit:
```bash
# ImageMagick (falls installiert)
convert app/src/main/res/mipmap-xxxhdpi/ic_launcher.png -resize 512x512 icon-512.png
```

---

## 🔗 URLs

### Datenschutzrichtlinie
```
https://github.com/napoleonmm83/paperless-scanner/blob/main/docs/PRIVACY_POLICY.md
```

### Website
```
https://github.com/napoleonmm83/paperless-scanner
```

### Nutzungsbedingungen (optional)
```
https://github.com/napoleonmm83/paperless-scanner/blob/main/docs/TERMS_OF_SERVICE.md
```

---

## 💰 In-App-Produkte (Premium Abos)

### Monatliches Abo

**Produkt-ID:**
```
premium_monthly
```

**Name:**
```
Premium (Monatlich)
```

**Beschreibung:**
```
Unbegrenzte AI-gestützte Tag-Vorschläge, automatische Metadaten-Extraktion, vorrangiger Support.
```

**Preis:**
```
€4.99/Monat
```

**Kostenlose Testversion:**
```
7 Tage (empfohlen)
```

---

### Jährliches Abo

**Produkt-ID:**
```
premium_yearly
```

**Name:**
```
Premium (Jährlich)
```

**Beschreibung:**
```
Unbegrenzte AI-gestützte Tag-Vorschläge, automatische Metadaten-Extraktion, vorrangiger Support. Spare 17% gegenüber monatlicher Abrechnung!
```

**Preis:**
```
€49.99/Jahr
```

**Kostenlose Testversion:**
```
7 Tage (empfohlen)
```

---

## 📋 Release Notes Template

### Deutsch
```
Version X.X.XX:

✨ Neue Features:
- [Feature 1 Beschreibung]
- [Feature 2 Beschreibung]

🐛 Fehlerbehebungen:
- [Fix 1 Beschreibung]
- [Fix 2 Beschreibung]

🔧 Verbesserungen:
- [Improvement 1 Beschreibung]

Vollständige Änderungen: https://github.com/napoleonmm83/paperless-scanner/releases
```

### Englisch
```
Version X.X.XX:

✨ New Features:
- [Feature 1 description]
- [Feature 2 description]

🐛 Bug Fixes:
- [Fix 1 description]
- [Fix 2 description]

🔧 Improvements:
- [Improvement 1 description]

Full changelog: https://github.com/napoleonmm83/paperless-scanner/releases
```

### Erste Version (v1.4.59)

**Deutsch:**
```
Version 1.4.59:

Erste Internal Testing Version mit:
✨ MLKit Document Scanner
📤 Direkter Upload zu Paperless-ngx
🏷️ Tag, Correspondent & Document Type Zuweisung
🤖 AI-gestützte Metadaten-Vorschläge (Premium)
🔒 App-Lock mit biometrischer Entsperrung
🌙 Dark Mode Support
📴 Offline-Modus mit automatischer Synchronisation

Vollständige Änderungen: https://github.com/napoleonmm83/paperless-scanner/releases
```

**Englisch:**
```
Version 1.4.59:

First Internal Testing version featuring:
✨ MLKit Document Scanner
📤 Direct upload to Paperless-ngx
🏷️ Tag, Correspondent & Document Type assignment
🤖 AI-powered metadata suggestions (Premium)
🔒 App-Lock with biometric unlock
🌙 Dark Mode support
📴 Offline mode with automatic sync

Full changelog: https://github.com/napoleonmm83/paperless-scanner/releases
```

---

## 📧 Kontaktdaten

**E-Mail:** (wird öffentlich sichtbar)
```
[Deine E-Mail eintragen]
```

**Support-E-Mail:** (optional, für Reviews)
```
[Support E-Mail falls unterschiedlich]
```

---

## 🏷️ App-Kategorisierung

### Primäre Kategorie
```
Produktivität
```

### Tags (optional)
```
- Dokumentenverwaltung
- Scanner
- Paperless
- Produktivität
- Open Source
- Privacy
```

---

## 🎯 Zielgruppe & Inhalte

### Zielgruppe
```
Erwachsene (18+)
```

### Inhaltseinstufung
```
Für alle Altersgruppen (nach Fragebogen)
```

### Werbung
```
❌ Keine Werbung
```

### In-App-Käufe
```
✅ Ja (Premium-Abonnements)
```

---

## 🔒 Datenschutz-Antworten (Quick Reference)

### Erfasst deine App Nutzerdaten?
```
✅ Ja
```

### Welche Datentypen?

**Fotos & Videos:**
```
✅ Ja
- Zweck: App-Funktionalität (Scannen)
- Erforderlich: Ja
- Weitergabe: Nein
- Verschlüsselung: Nein (werden nicht gespeichert)
```

**Dateien & Dokumente:**
```
✅ Ja
- Zweck: App-Funktionalität
- Erforderlich: Ja
- Weitergabe: Nein
```

**Alle anderen Kategorien:**
```
❌ Nein
(oder: App-Aktivitäten nur wenn Analytics opt-in aktiv)
```

---

## 📊 Version Info

### Aktuelle Version
```
Version Name: 1.4.59
Version Code: 10459
```

### Version Code Berechnung
```kotlin
versionCode = MAJOR * 10000 + MINOR * 100 + PATCH
Beispiel: 1.4.59 → 1 * 10000 + 4 * 100 + 59 = 10459
```

### Nächste Version
```
Version Name: 1.4.60
Version Code: 10460
```

**Update in:** `version.properties`
```properties
VERSION_MAJOR=1
VERSION_MINOR=4
VERSION_PATCH=60
```

---

## 🚀 Fastlane Deployment (Optional)

Falls du Fastlane nutzen möchtest statt manuell:

```bash
# Internal Testing Track
bundle exec fastlane android internal

# Closed Beta Track
bundle exec fastlane android beta

# Production Track
bundle exec fastlane android promote
```

**Konfiguration:** `fastlane/Fastfile`

---

## 📁 Alle Marketing-Dateien

```
paperless-scanner/
├── README.md                           # GitHub README (vollständig)
├── CONTRIBUTING.md                     # Contribution Guide
├── docs/
│   ├── PRIVACY_POLICY.md              # Datenschutzerklärung (DE+EN)
│   ├── TERMS_OF_SERVICE.md            # Nutzungsbedingungen (DE+EN)
│   ├── SUPPORT.md                     # Support-Dokumentation
│   ├── PLAY_STORE_ASSETS.md           # Asset-Tracking
│   ├── PLAY_STORE_SUBMISSION.md       # Vollständige Anleitung
│   ├── PLAY_STORE_CHECKLIST.md        # Abhak-Checkliste
│   └── PLAY_STORE_QUICK_REFERENCE.md  # Diese Datei
└── fastlane/metadata/android/
    ├── de-DE/
    │   ├── title.txt
    │   ├── short_description.txt
    │   ├── full_description.txt
    │   ├── changelogs/
    │   │   └── 10459.txt
    │   └── images/phoneScreenshots/
    │       └── [8 Screenshots]
    └── en-US/
        ├── title.txt
        ├── short_description.txt
        ├── full_description.txt
        ├── changelogs/
        │   └── 10459.txt
        └── images/phoneScreenshots/
            └── [8 Screenshots]
```

---

## 🔍 Wo finde ich was?

| Ich brauche... | Datei/Pfad |
|----------------|------------|
| AAB zum Hochladen | `app/build/outputs/bundle/release/app-release.aab` |
| Screenshots | `fastlane/metadata/android/{locale}/images/phoneScreenshots/` |
| App Beschreibung (DE) | `fastlane/metadata/android/de-DE/full_description.txt` |
| App Beschreibung (EN) | `fastlane/metadata/android/en-US/full_description.txt` |
| Datenschutzrichtlinie URL | `docs/PRIVACY_POLICY.md` → GitHub Link |
| Release Notes | `fastlane/metadata/android/{locale}/changelogs/10459.txt` |
| Vollständige Anleitung | `docs/PLAY_STORE_SUBMISSION.md` |
| Abhak-Checkliste | `docs/PLAY_STORE_CHECKLIST.md` |

---

**Schnellstart:** Lies `PLAY_STORE_SUBMISSION.md` für die vollständige Schritt-für-Schritt-Anleitung!

*Last updated: 2026-01-18*
