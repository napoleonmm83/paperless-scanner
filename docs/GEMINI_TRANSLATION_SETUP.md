# Gemini Automatic Translation Setup

## Übersicht

Seit Version 1.4.55 verwendet die Paperless Scanner App **Gemini Automatic Translation** von Google Play Console für alle App-String-Übersetzungen.

## Was ist neu?

### Vorher (Manuell)
- 16 `values-*/strings.xml` Verzeichnisse im Projekt
- Manuelle Übersetzungen für jede Sprache erforderlich
- `check-translations.sh` Skript zur Validierung
- Bei jeder String-Änderung: 16 Dateien aktualisieren

### Jetzt (Gemini)
- **NUR** `values/strings.xml` (Deutsch) im Projekt
- Übersetzungen werden automatisch von Gemini generiert
- Kein manueller Aufwand mehr
- Automatische Integration beim App-Bundle Upload

## Wie funktioniert es?

1. **Entwickler** fügt/ändert Strings in `values/strings.xml` (Deutsch)
2. **Commit & Push** auf `main`
3. **GitHub Actions** baut Release-Bundle
4. **Play Console** empfängt Bundle-Upload
5. **Gemini** generiert automatisch Übersetzungen für 16 Sprachen
6. **Integration** nahtlos ins App-Bundle (keine APK-Größe Erhöhung)
7. **Bereitstellung** an Nutzer in ihrer Sprache

## Einmalige Aktivierung (Play Console)

### Schritt 1: Play Console öffnen
1. Gehe zu [Google Play Console](https://play.google.com/console)
2. Wähle "Paperless Scanner" App aus

### Schritt 2: Gemini Translation aktivieren
1. **Navigation:** Grow users → Translations → App strings
2. **Klicke:** "Get started" Button
3. **Klicke:** "Add languages" Button

### Schritt 3: Sprachen auswählen
Aktiviere **alle 16 Sprachen**:
- English (en)
- French (fr)
- Spanish (es)
- Italian (it)
- Portuguese (pt)
- Dutch (nl)
- Polish (pl)
- Swedish (sv)
- Danish (da)
- Norwegian (no)
- Finnish (fi)
- Czech (cs)
- Hungarian (hu)
- Greek (el)
- Romanian (ro)
- Turkish (tr)

### Schritt 4: Bestätigung
1. **Bestätige** die Sprachauswahl
2. **Fertig!** Gemini ist jetzt aktiv

## Was passiert beim nächsten Release?

Beim nächsten App-Bundle Upload (z.B. Version 1.4.56):

1. **Automatische Generierung:**
   - Gemini analysiert `values/strings.xml` (Deutsch)
   - Generiert Übersetzungen für alle 16 Sprachen
   - Basierend auf neuesten Gemini-Modellen

2. **Automatische Integration:**
   - Übersetzungen werden ins App-Bundle integriert
   - Kein manueller Eingriff erforderlich
   - APK-Größe bleibt unverändert

3. **Preview verfügbar:**
   - Built-in Emulator in Play Console
   - Vor Live-Schaltung prüfbar
   - Einzelne Strings editierbar

## Kontrolle & Anpassungen

### Übersetzungen editieren
1. **Play Console** → Translations → App strings
2. **Sprache auswählen** (z.B. English)
3. **String auswählen** und editieren
4. **Speichern** → Wird in nächstem Bundle verwendet

### Strings von Übersetzung ausschließen
1. **String auswählen** in Play Console
2. **"Translate" Checkbox** deaktivieren
3. **Beispiele:** Brand-Namen, technische Begriffe

### Übersetzungen deaktivieren
1. **Play Console** → Translations → App strings
2. **Sprache auswählen**
3. **"Disable"** klicken

## Wichtige Hinweise

### ✅ DO
- Nur `values/strings.xml` (Deutsch) pflegen
- String-Keys beibehalten (keine Umbenennungen ohne Grund)
- Platzhalter korrekt verwenden (`%1$s`, `%2$d`, etc.)
- Bei neuen Strings: Descriptive Keys verwenden

### ❌ DON'T
- **NIEMALS** `values-*/strings.xml` Verzeichnisse manuell erstellen
- **NIEMALS** Übersetzungen im Projekt-Code pflegen
- Gemini-Übersetzungen überschreiben (nur via Play Console)

## Override-Verhalten

**WICHTIG:** Gemini überschreibt ALLE Übersetzungen für aktivierte Sprachen!

- Wenn Gemini für "English" aktiv ist → alle EN-Übersetzungen kommen von Gemini
- Manuelle `values-en/strings.xml` würden ignoriert/überschrieben werden
- Um eigene Übersetzungen zu nutzen: Sprache NICHT in Gemini aktivieren

## Technische Details

### Konsistenz
- Übersetzungen bleiben konsistent über alle App-Versionen
- Ändern sich nur wenn Source-Text (Deutsch) geändert wird
- Neue Strings werden automatisch erkannt

### Performance
- Keine APK-Größe Erhöhung
- Übersetzungen server-side integriert
- Kein zusätzlicher Download für Nutzer

### Qualität
- Gemini-Modelle state-of-the-art
- Kontextbewusste Übersetzungen
- Natürlich klingende Formulierungen

## Troubleshooting

### Problem: Übersetzungen nicht sichtbar in App
**Lösung:**
1. Prüfe ob Gemini in Play Console aktiviert ist
2. Prüfe ob Bundle hochgeladen wurde (nicht nur APK)
3. Warte auf Verarbeitung (kann wenige Minuten dauern)

### Problem: Falsche Übersetzung für speziellen Begriff
**Lösung:**
1. Play Console → Translations → App strings
2. String suchen und manuell editieren
3. Alternative: String von Übersetzung ausschließen

### Problem: Sprache fehlt
**Lösung:**
1. Play Console → Translations → App strings
2. "Add languages" → Fehlende Sprache hinzufügen
3. Neues Bundle hochladen

## Migration Historie

**Version 1.4.55 (2026-01-25):**
- Alle 16 `values-*/` Verzeichnisse gelöscht
- `scripts/check-translations.sh` entfernt
- CI Workflows aktualisiert (Translation-Checks entfernt)
- `CLAUDE.md` aktualisiert (neuer Gemini-Workflow)
- Gemini Activation Guide erstellt

**Build-Test:** ✅ Erfolgreich (28s)

## Ressourcen

- [Google Play Console: Translate and localize your app](https://support.google.com/googleplay/android-developer/answer/9844778)
- [Android Developers Blog: Gemini Translation](https://android-developers.googleblog.com/2025/10/new-tools-and-programs-to-accelerate.html)
- [Projekt CLAUDE.md](../CLAUDE.md) - Section "🌍 AUTOMATISCHE ÜBERSETZUNG MIT GEMINI"

## Kontakt

Bei Fragen zur Gemini Translation:
- **GitHub Issues:** [paperless-scanner/issues](https://github.com/napoleonmm83/paperless-scanner/issues)
- **Play Console Support:** Google Play Developer Support

---

**Letzte Aktualisierung:** 2026-01-25
