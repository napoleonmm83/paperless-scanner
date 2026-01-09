# Privacy Policy for Paperless Scanner

**Last updated: January 9, 2026**

---

## English

### Overview

Paperless Scanner ("the App") is designed with privacy as a core principle. We do not collect, store, or transmit any personal data to external servers.

### Data Collection

**What we DO NOT collect by default:**
- Personal information
- Location data
- Advertising IDs

**Optional Features (with user consent):**

| Feature | Data Collected | Purpose |
|---------|----------------|---------|
| **Analytics** (Optional) | App usage events, anonymized crash reports | Improve app stability and features |
| **AI Features** (Premium Subscription) | Scanned document images, token usage | Provide intelligent document analysis |

**What the App stores locally:**

| Data | Purpose |
|------|---------|
| Server URL | Connect to your Paperless-ngx instance |
| Authentication Token | API access to your server |
| Scanned Images | Temporary storage before upload |
| AI Usage Count | Track monthly AI call limits (Premium only) |
| Subscription Status | Verify Premium features access |

All data is stored securely on your device using Android's encrypted DataStore.

### Permissions

| Permission | Purpose | Data Handling |
|------------|---------|---------------|
| **Camera** | Scan documents | Processed on-device, sent only to YOUR server |
| **Internet** | Upload documents | Data sent only to your configured server URL |

### Data Transmission

The App communicates with:
- **Your Paperless-ngx Server**: Documents you scan are uploaded directly to your self-hosted server
- **Google ML Kit**: Document scanning uses on-device processing (no cloud upload)
- **Firebase Services** (Optional):
  - **Analytics** (opt-in): Anonymized usage events, crash reports
  - **AI Features** (Premium only): Document images for intelligent analysis

### Third-Party Services

**Google ML Kit Document Scanner**
The App uses Google ML Kit for document scanning. All processing happens on-device. Google's privacy policy applies: https://policies.google.com/privacy

**Firebase Analytics (Optional)**
If you consent to analytics, the App collects anonymized usage data via Firebase Analytics to improve the app. You can opt-out anytime in Settings. No personally identifiable information is collected.
- Privacy Policy: https://firebase.google.com/support/privacy
- Data collected: App events, device type, OS version (all anonymized)

**Firebase AI / Google Gemini (Premium Subscription)**
Premium subscribers can use AI-powered document analysis features:
- **What is sent to Google**: Scanned document images, existing tag names
- **What is NOT sent**: Server URLs, credentials, document content from your Paperless server
- **How it's used**: Google Gemini AI analyzes the image to suggest titles, tags, dates, and correspondents
- **Data retention**: Images are **not stored** by Google after analysis (processed in-memory)
- **Opt-in**: AI features are only available with an active Premium subscription
- **Privacy Policy**: https://ai.google.dev/gemini-api/terms

**Important**: AI analysis happens server-side at Google. If you prefer complete privacy, use the free version without AI features.

### Your Rights

Since we don't collect any data, there is nothing to:
- Access
- Correct
- Delete
- Export

Your documents remain entirely under your control on your own Paperless-ngx server.

### Security

- Credentials are stored using Android's encrypted storage
- All network communication uses HTTPS

### Children's Privacy

This app is not intended for use by children under 13 years of age.

### Contact

For questions about this privacy policy, please open an issue on GitHub.

### Changes

We may update this privacy policy from time to time. Changes will be posted in this document with an updated date.

---

## Deutsch

### Überblick

Paperless Scanner ("die App") wurde mit Datenschutz als Kernprinzip entwickelt. Wir erfassen, speichern oder übertragen keine personenbezogenen Daten an externe Server.

### Datenerfassung

**Was wir standardmäßig NICHT erfassen:**
- Persönliche Informationen
- Standortdaten
- Werbe-IDs

**Optionale Funktionen (mit Nutzereinwilligung):**

| Funktion | Erfasste Daten | Zweck |
|----------|----------------|-------|
| **Analytics** (Optional) | App-Nutzungsereignisse, anonymisierte Absturzberichte | Verbesserung der App-Stabilität und Features |
| **AI-Funktionen** (Premium-Abo) | Gescannte Dokumentbilder, Token-Nutzung | Intelligente Dokumentanalyse |

**Was die App lokal speichert:**

| Daten | Zweck |
|-------|-------|
| Server-URL | Verbindung zu Ihrer Paperless-ngx Instanz |
| Authentifizierungs-Token | API-Zugriff auf Ihren Server |
| Gescannte Bilder | Temporäre Speicherung vor Upload |
| AI-Nutzungszähler | Monatliche AI-Aufruf-Limits tracking (nur Premium) |
| Abo-Status | Zugriffsprüfung auf Premium-Features |

Alle Daten werden sicher auf Ihrem Gerät mit Android's verschlüsseltem DataStore gespeichert.

### Berechtigungen

| Berechtigung | Zweck | Datenverarbeitung |
|--------------|-------|-------------------|
| **Kamera** | Dokumente scannen | Verarbeitung auf dem Gerät, nur an IHREN Server gesendet |
| **Internet** | Dokumente hochladen | Daten nur an Ihre konfigurierte Server-URL |

### Datenübertragung

Die App kommuniziert mit:
- **Ihrem Paperless-ngx Server**: Gescannte Dokumente werden direkt auf Ihren selbst gehosteten Server hochgeladen
- **Google ML Kit**: Dokumentenscan nutzt geräteinterne Verarbeitung (kein Cloud-Upload)
- **Firebase-Dienste** (Optional):
  - **Analytics** (Opt-in): Anonymisierte Nutzungsereignisse, Absturzberichte
  - **AI-Funktionen** (nur Premium): Dokumentbilder für intelligente Analyse

### Drittanbieter-Dienste

**Google ML Kit Document Scanner**
Die App nutzt Google ML Kit für den Dokumentenscan. Die gesamte Verarbeitung erfolgt auf dem Gerät. Es gilt Googles Datenschutzerklärung: https://policies.google.com/privacy

**Firebase Analytics (Optional)**
Wenn Sie Analytics zustimmen, sammelt die App anonymisierte Nutzungsdaten über Firebase Analytics zur Verbesserung der App. Sie können jederzeit in den Einstellungen widersprechen. Es werden keine personenbezogenen Daten erfasst.
- Datenschutzerklärung: https://firebase.google.com/support/privacy
- Erfasste Daten: App-Ereignisse, Gerätetyp, OS-Version (alles anonymisiert)

**Firebase AI / Google Gemini (Premium-Abo)**
Premium-Abonnenten können KI-gestützte Dokumentanalyse nutzen:
- **Was an Google gesendet wird**: Gescannte Dokumentbilder, vorhandene Tag-Namen
- **Was NICHT gesendet wird**: Server-URLs, Zugangsdaten, Dokumentinhalte von Ihrem Paperless-Server
- **Verwendung**: Google Gemini AI analysiert das Bild zur Vorschlag von Titeln, Tags, Daten und Korrespondenten
- **Datenspeicherung**: Bilder werden **nicht gespeichert** von Google nach der Analyse (Verarbeitung im Arbeitsspeicher)
- **Opt-in**: AI-Funktionen sind nur mit aktivem Premium-Abo verfügbar
- **Datenschutzerklärung**: https://ai.google.dev/gemini-api/terms

**Wichtig**: AI-Analyse erfolgt serverseitig bei Google. Wenn Sie vollständige Privatsphäre bevorzugen, nutzen Sie die kostenlose Version ohne AI-Funktionen.

### Ihre Rechte

Da wir keine Daten erfassen, gibt es nichts zu:
- Abrufen
- Korrigieren
- Löschen
- Exportieren

Ihre Dokumente verbleiben vollständig unter Ihrer Kontrolle auf Ihrem eigenen Paperless-ngx Server.

### Sicherheit

- Anmeldedaten werden mit Android's verschlüsseltem Speicher gespeichert
- Alle Netzwerkkommunikation nutzt HTTPS

### Datenschutz für Kinder

Diese App ist nicht für die Nutzung durch Kinder unter 13 Jahren bestimmt.

### Kontakt

Bei Fragen zu dieser Datenschutzerklärung öffnen Sie bitte ein Issue auf GitHub.

### Änderungen

Wir können diese Datenschutzerklärung von Zeit zu Zeit aktualisieren. Änderungen werden in diesem Dokument mit aktualisiertem Datum veröffentlicht.

---

## Summary / Zusammenfassung

| | |
|---|---|
| Data collected (default) / Erfasste Daten (Standard) | None / Keine |
| Analytics / Analysen | Optional (opt-in) / Optional (Zustimmung erforderlich) |
| AI Features / AI-Funktionen | Premium subscription only / Nur Premium-Abo |
| Advertising / Werbung | No / Nein |
| Third-party sharing / Weitergabe an Dritte | Only Firebase (opt-in/Premium) / Nur Firebase (Opt-in/Premium) |
| Data transmission / Datenübertragung | Your server + Google (Premium AI) / Ihr Server + Google (Premium AI) |
| AI data retention / AI-Datenspeicherung | Not stored / Nicht gespeichert |
