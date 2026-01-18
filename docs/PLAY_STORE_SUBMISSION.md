# Play Store Submission Guide

Komplette Schritt-für-Schritt Anleitung für die Veröffentlichung von Paperless Scanner im Google Play Store.

---

## 📋 Voraussetzungen

Bevor du beginnst, stelle sicher dass du hast:

- ✅ Google Play Console Account (25€ einmalige Gebühr)
- ✅ Signiertes Release AAB (`app-release.aab`)
- ✅ 8 Screenshots (bereits fertig in `fastlane/metadata/`)
- ✅ App Descriptions (bereits fertig in `fastlane/metadata/`)
- ✅ App Icon 512x512 PNG

---

## 🚀 Schritt 1: App erstellen in Play Console

### 1.1 Neue App erstellen

1. Gehe zu [Google Play Console](https://play.google.com/console)
2. Klicke auf **"Alle Apps"** → **"App erstellen"**
3. Fülle aus:
   - **App-Name:** `Paperless Scanner`
   - **Standardsprache:** `Deutsch (Deutschland)` oder `English (United States)`
   - **App-Typ:** `App`
   - **Kostenlos/Kostenpflichtig:** `Kostenlos`
4. Akzeptiere die Richtlinien
5. Klicke **"App erstellen"**

---

## 📝 Schritt 2: Store-Eintrag einrichten

### 2.1 Hauptinformationen

Navigiere zu **"Store-Präsenz"** → **"Hauptinformationen"**

#### App-Name
```
Paperless Scanner
```

#### Kurzbeschreibung (80 Zeichen)

**Deutsch:**
```
Nativer Paperless-ngx Scanner mit KI-Vorschlägen. Schnell & privat.
```

**Englisch:**
```
Native Paperless-ngx scanner with AI-powered tag suggestions. Fast & private.
```

#### Vollständige Beschreibung (4000 Zeichen)

**Pfad zu den Dateien:**
- Deutsch: `fastlane/metadata/android/de-DE/full_description.txt`
- Englisch: `fastlane/metadata/android/en-US/full_description.txt`

**Kopiere den gesamten Inhalt** dieser Dateien in die Play Console.

---

### 2.2 App-Symbol

**Anforderungen:**
- Format: PNG
- Größe: 512x512 Pixel
- 32-Bit PNG (mit Alpha-Kanal)
- Maximale Dateigröße: 1 MB

**Pfad:**
```
app/src/main/res/mipmap-xxxhdpi/ic_launcher.png
```

Falls du ein 512x512 PNG brauchst, kannst du das Icon exportieren oder skalieren.

---

### 2.3 Feature-Grafik (Optional aber empfohlen)

**Anforderungen:**
- Format: PNG oder JPEG
- Größe: **1024 x 500 Pixel** (EXAKT)
- Maximale Dateigröße: 1 MB

**Status:** ❌ Noch nicht erstellt

**Überspringen für jetzt** - kann später hinzugefügt werden.

---

### 2.4 Screenshots (Phone)

**Pfad zu deinen Screenshots:**
```
fastlane/metadata/android/de-DE/images/phoneScreenshots/
fastlane/metadata/android/en-US/images/phoneScreenshots/
```

**Lade diese 8 Screenshots hoch (in dieser Reihenfolge):**

1. `1_hero_upload.png` - Upload/Tagging screen
2. `2_scan.png` - Scanner interface
3. `3_ai_suggestions.png` - AI suggestions (Premium)
4. `4_documents_list.png` - Documents overview
5. `5_settings_applock.png` - Settings with App-Lock
6. `6_home.png` - Home dashboard
7. `7_scan_result.png` - Scanned document
8. `8_login.png` - Server login

**Anforderungen:**
- Minimum: 2 Screenshots (du hast 8 ✅)
- Format: PNG oder JPEG
- Mindestgröße: 320px
- Maximalgröße: 3840px

**Wichtig:** Du kannst die Reihenfolge in der Play Console per Drag & Drop ändern!

---

### 2.5 Kategorisierung

**App-Kategorie:**
```
Produktivität
```

**Tags (optional):**
- Dokumentenverwaltung
- Scanner
- Paperless
- Produktivität
- Open Source

---

### 2.6 Kontaktdaten

**E-Mail-Adresse:** (Öffentlich sichtbar)
```
deine-email@example.com
```

**Website:** (Optional)
```
https://github.com/napoleonmm83/paperless-scanner
```

**Telefonnummer:** (Optional, nicht öffentlich)
```
Optional leer lassen
```

---

### 2.7 Datenschutzrichtlinie

**Datenschutzrichtlinien-URL:** (ERFORDERLICH)

**Option 1: GitHub-hosted (Empfohlen)**
```
https://github.com/napoleonmm83/paperless-scanner/blob/main/docs/PRIVACY_POLICY.md
```

**Option 2: GitHub Pages (falls du das einrichtest)**
```
https://napoleonmm83.github.io/paperless-scanner/privacy
```

**Wichtig:** Die URL muss öffentlich zugänglich sein!

---

## 🔒 Schritt 3: Datenschutz & Sicherheit

### 3.1 Datensicherheit-Formular ausfüllen

**"Erfasst deine App Nutzerdaten?"**
```
✅ Ja
```

**Welche Daten werden erfasst?**

#### Standort: NEIN
#### Persönliche Informationen: NEIN
#### Finanzinformationen: NEIN
#### Gesundheit & Fitness: NEIN
#### Nachrichten: NEIN
#### Fotos & Videos: JA ✅

**Details zu Fotos & Videos:**
- **Datentyp:** Fotos
- **Erforderlich/Optional:** Erforderlich
- **Zweck:** App-Funktionalität (Dokumenten-Scannen)
- **Weitergabe an Dritte:** NEIN
- **Verschlüsselung:** NEIN (Fotos werden nicht gespeichert, direkt an Paperless-ngx gesendet)

#### Dateien & Dokumente: JA ✅

**Details:**
- **Datentyp:** Dateien und Dokumente
- **Erforderlich/Optional:** Erforderlich
- **Zweck:** App-Funktionalität
- **Weitergabe an Dritte:** NEIN
- **Verschlüsselung:** NEIN

#### App-Aktivitäten: JA (nur bei Premium mit Analytics) ⚠️

**Falls Analytics NICHT aktiv:**
```
NEIN
```

**Falls Analytics aktiv (opt-in):**
```
JA - Diagnose
```

#### App-Informationen & Leistung: NEIN
#### Geräte- oder andere IDs: NEIN

---

### 3.2 Datenschutzrichtlinie bestätigen

✅ Bestätige dass du eine gültige Datenschutzrichtlinie hast (siehe Schritt 2.7)

---

## 📱 Schritt 4: App-Inhalte

### 4.1 Zielgruppe & Inhalte

**Zielgruppe:**
```
Erwachsene (18+)
```

**Inhaltsrichtlinien:**
- ✅ Keine Gewalt
- ✅ Keine sexuellen Inhalte
- ✅ Keine Hassreden
- ✅ Keine Glücksspiele
- ✅ Keine gefährlichen Aktivitäten

**Inhaltseinstufung:**
Nach Beantwortung des Fragebogens wahrscheinlich: **"Für alle Altersgruppen"**

---

### 4.2 News-App Deklaration

**Ist dies eine News-App?**
```
❌ Nein
```

---

### 4.3 COVID-19-Kontaktnachverfolgung/Statusapps

**Ist dies eine COVID-19-App?**
```
❌ Nein
```

---

### 4.4 Datenschutzerklärung für Google Play

Verwende die gleiche URL wie in Schritt 2.7:
```
https://github.com/napoleonmm83/paperless-scanner/blob/main/docs/PRIVACY_POLICY.md
```

---

## 🏷️ Schritt 5: In-App-Käufe & Abos

### 5.1 In-App-Produkte einrichten

**Du hast Premium-Abos (€4.99/Monat, €49.99/Jahr)**

1. Navigiere zu **"Monetarisierung"** → **"In-App-Produkte"** → **"Abonnements"**
2. Klicke **"Abonnement erstellen"**

#### Monatliches Abo:

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

**Abrechnungszeitraum:**
```
1 Monat
```

**Kostenlose Testversion:** (Optional)
```
7 Tage (empfohlen!)
```

#### Jährliches Abo:

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

**Abrechnungszeitraum:**
```
1 Jahr
```

**Kostenlose Testversion:** (Optional)
```
7 Tage
```

---

## 📦 Schritt 6: Release erstellen

### 6.1 Internal Testing Track (Empfohlen für Start)

1. Navigiere zu **"Release"** → **"Testing"** → **"Internal Testing"**
2. Klicke **"Neues Release erstellen"**

#### App Bundle hochladen

**Pfad zum AAB:**
```
app/build/outputs/bundle/release/app-release.aab
```

**Lade diese Datei hoch** (Drag & Drop oder "Durchsuchen")

**Wichtig:** Das AAB muss signiert sein mit deinem Release-Key!

#### Release-Name

```
1.4.59 (Internal Alpha)
```

#### Release-Hinweise

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

### 6.2 Tester hinzufügen

**E-Mail-Adressen von Testern:**
- Deine eigene E-Mail
- Eventuell Freunde/Beta-Tester

**E-Mail-Liste erstellen:**
1. Gehe zu **"Tester"** Sektion
2. Klicke **"E-Mail-Liste erstellen"**
3. Name: `Internal Testers`
4. Füge E-Mail-Adressen hinzu (Zeile für Zeile)

---

### 6.3 Release überprüfen

**Checkliste vor Release:**

- ✅ AAB hochgeladen
- ✅ Version Code korrekt (z.B. 10459 für v1.4.59)
- ✅ Release-Hinweise ausgefüllt
- ✅ Tester hinzugefügt
- ✅ Alle erforderlichen Informationen ausgefüllt

**Klicke "Änderungen speichern"** → **"Release überprüfen"** → **"Rollout starten"**

---

## ⏰ Schritt 7: Warten auf Überprüfung

### Was passiert jetzt?

1. **Hochladen:** AAB wird verarbeitet (5-10 Minuten)
2. **Überprüfung:** Google prüft deine App (normalerweise 1-3 Tage)
3. **Freigabe:** App wird für Tester verfügbar

### E-Mail-Benachrichtigungen

Du erhältst E-Mails für:
- ✅ Upload erfolgreich
- ⚠️ Probleme gefunden (z.B. Policy-Verstöße)
- ✅ App genehmigt
- 🚀 Release live

---

## 🧪 Schritt 8: Testing Phase

### 8.1 Internal Testing Link

Nach Freigabe erhältst du einen **Opt-in Link**:

```
https://play.google.com/apps/internaltest/...
```

**Teile diesen Link mit deinen Testern!**

### 8.2 Feedback sammeln

**Test-Checkliste:**
- [ ] Login funktioniert
- [ ] Scanner funktioniert
- [ ] Upload funktioniert
- [ ] Tags können zugewiesen werden
- [ ] Premium-Abo kann gekauft werden (Testumgebung!)
- [ ] AI-Vorschläge funktionieren (Premium)
- [ ] App-Lock funktioniert
- [ ] Offline-Modus funktioniert
- [ ] Keine Crashes

**Crashes tracken:** Firebase Crashlytics sollte aktiv sein!

---

## 📈 Schritt 9: Von Internal → Beta → Production

### 9.1 Internal Testing → Closed Beta

**Nach erfolgreichem Internal Testing (empfohlen: 1-2 Wochen):**

1. Navigiere zu **"Closed Testing"**
2. Erstelle neue Beta-Track
3. Wähle gleiche AAB wie bei Internal Testing
4. Füge mehr Tester hinzu (bis zu 100)

**Release-Hinweise:** Gleiche wie bei Internal Testing

### 9.2 Closed Beta → Open Beta (Optional)

**Für größere Community Testing:**

1. Navigiere zu **"Open Testing"**
2. Erstelle Open Beta Track
3. **Jeder** kann jetzt testen (öffentlicher Link)

**Vorsicht:** Öffentlich sichtbar! Nur wenn du bereit bist.

### 9.3 Production Release

**Wenn alles getestet ist und stabil läuft:**

1. Navigiere zu **"Production"**
2. Erstelle Production Release
3. Wähle AAB (kann gleiche wie Beta sein)
4. **Rollout-Prozentsatz:** Start mit 5-10%, dann graduell erhöhen
5. Klicke **"Rollout starten"**

**Gratulation! 🎉 Deine App ist live!**

---

## ⚠️ Häufige Fehler vermeiden

### 1. App Bundle nicht signiert
**Fehler:** `Upload failed: App bundle is not signed`

**Lösung:**
```bash
# Stelle sicher dass signing.properties korrekt ist
./gradlew assembleRelease
```

### 2. Version Code Konflikt
**Fehler:** `Version code X has already been used`

**Lösung:** Erhöhe Version in `version.properties`:
```properties
VERSION_PATCH=60  # Erhöhe um 1
```

### 3. Datenschutzrichtlinie nicht erreichbar
**Fehler:** `Privacy Policy URL is not accessible`

**Lösung:** Stelle sicher dass GitHub-Link öffentlich ist (nicht in privatem Repo!)

### 4. Screenshots zu klein
**Fehler:** `Screenshots must be at least 320px`

**Lösung:** Deine Screenshots sind alle > 320px ✅

### 5. Fehlende Altersfreigabe
**Fehler:** `Content rating questionnaire not completed`

**Lösung:** Gehe zu **"App-Inhalte"** → **"Zielgruppe & Inhalte"** → Fragebogen ausfüllen

---

## 📊 Nach dem Launch

### Analytics & Monitoring

**Google Play Console Metriken:**
- Installationen
- Deinstallationen
- Bewertungen & Rezensionen
- Crashrate (sollte < 1% sein)
- ANR-Rate (sollte < 0.5% sein)

**Firebase Analytics:**
- Aktive Nutzer
- Premium Conversions
- Feature-Nutzung

### Kontinuierliche Updates

**Empfohlener Zyklus:**
1. Bugfixes: Sofort (Hotfix)
2. Features: Alle 2-4 Wochen
3. Major Updates: Alle 2-3 Monate

---

## 🎯 Checkliste: Bist du bereit?

- [ ] Google Play Console Account erstellt (25€ bezahlt)
- [ ] Signiertes AAB vorhanden (`app-release.aab`)
- [ ] Screenshots vorbereitet (8 Stück, beide Locales)
- [ ] Descriptions kopiert (aus fastlane/metadata/)
- [ ] App Icon 512x512 exportiert
- [ ] Datenschutzrichtlinie URL öffentlich erreichbar
- [ ] Firebase Projekt konfiguriert (für Crashlytics)
- [ ] Billing Library getestet (für Premium-Abos)
- [ ] Test-Accounts vorbereitet (für Internal Testing)

---

## 💡 Tipps für erfolgreichen Launch

1. **Start mit Internal Testing** - Nicht direkt Production!
2. **Teste Premium-Abo** in Testumgebung (Google Sandbox)
3. **Sammle Feedback** von mindestens 5-10 Testern
4. **Fix alle Crashes** vor Production Release
5. **Gradual Rollout** in Production (5% → 20% → 50% → 100%)
6. **Monitor Crashlytics** die ersten 24-48 Stunden intensiv
7. **Respond to Reviews** schnell und professionell

---

## 📞 Support & Hilfe

**Probleme beim Upload?**
- [Play Console Help Center](https://support.google.com/googleplay/android-developer)
- [App Bundle FAQ](https://developer.android.com/guide/app-bundle)

**Billing-Probleme?**
- [Google Play Billing Documentation](https://developer.android.com/google/play/billing)

**Policy-Verstöße?**
- [Play Console Policy Center](https://play.google.com/console/about/guides/policycenter/)

---

**Viel Erfolg beim Launch! 🚀**

*Last Updated: 2026-01-18*
