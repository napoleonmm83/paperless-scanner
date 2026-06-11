# Launch-Promo Runbook — 50% aufs erste Jahr Premium-KI

Spec: `docs/superpowers/specs/2026-06-11-launch-promo-design.md`
Plan: `docs/superpowers/plans/2026-06-11-launch-promo-premium.md`

## 1. Play Console: Offer anlegen (einmalig, VOR dem Go-Live)

1. Play Console → Monetarisierung → Produkte → Abos → `paperless_ai_yearly` → Base Plan öffnen.
2. „Angebot hinzufügen" (Offer) auf dem Base Plan:
   - **Offer-ID:** `launch-promo-2026` (frei wählbar)
   - **Offer-Tag:** `launch50`  ← MUSS exakt so heißen (die App matcht auf diesen Tag, `BillingManager.LAUNCH_PROMO_OFFER_TAG`)
   - **Eligibility:** Developer determined
   - **Phasen:**
     1. Free trial — 14 Tage
     2. Single payment — 1 Jahr — 50% Rabatt (€19.99 / CHF-Äquivalent prüfen)
   - Danach greift automatisch der reguläre Base-Plan-Preis (€39.99, Auto-Renew).
3. Offer **NICHT aktivieren** — erst beim Go-Live (Schritt 3).
4. Das bestehende Default-Offer (14d Trial) NICHT anfassen — es greift automatisch wieder nach Promo-Ende.

Hinweis: Ein Offer mit Tag, aber ohne echten Rabatt (Intro-Preis ≥ Regulärpreis) wird von der App ignoriert (fail-closed in `extractLaunchOffer`).

## 2. Firebase Console: Remote-Config-Parameter (einmalig)

Firebase Console → Remote Config → Parameter erstellen:

| Parameter | Typ | Default |
|---|---|---|
| `launch_promo_enabled` | Boolean | `false` |
| `launch_promo_end_epoch_ms` | Number | `0` |

„Veröffentlichen" klicken (Defaults = Promo aus, fail-closed wie in der App).

## 3. Go-Live-Checkliste (am Tag des Production-Promotes)

1. [ ] `fastlane android promote` — Internal-Build mit Promo-Code nach Production.
2. [ ] Play Console: Offer `launch50` **aktivieren**.
3. [ ] Enddatum berechnen: Promote-Tag + 28 Tage, 23:59 lokale Zeit → epoch ms
       (z. B. PowerShell: `[DateTimeOffset]::new(2026,7,9,23,59,0,[TimeSpan]::FromHours(2)).ToUnixTimeMilliseconds()`).
4. [ ] Firebase RC: `launch_promo_end_epoch_ms` = berechneter Wert, `launch_promo_enabled` = `true` → veröffentlichen.
5. [ ] Gerätetest (siehe §5).
6. [ ] Community-Posts absetzen (§6) + Wiki-Listing-Request (§7).

**Propagations-Hinweise:**
- Die App fetcht Remote Config einmal beim App-Start (Mindestintervall 1h). Nutzer mit laufender App sehen die Promo erst nach App-Neustart.
- In einer nie neu gestarteten Langzeit-Session kann der Banner über das Enddatum hinaus sichtbar bleiben (dokumentierte Limitation). Der Geld-Pfad ist davon unberührt: Kauf-Gate ist das Play-Offer selbst; ist es deaktiviert, schlägt der Promo-Kauf kontrolliert fehl bzw. zeigt Google Plays Kaufdialog den autoritativen Preis.

## 4. Promo-Ende-Checkliste (nach 4 Wochen)

1. [ ] Play Console: Offer `launch50` deaktivieren (autoritativer Schalter).
2. [ ] Firebase RC: `launch_promo_enabled` = `false` → veröffentlichen.
3. [ ] App zeigt automatisch wieder Regulärpreise (kein Release nötig; Banner verschwindet spätestens beim nächsten App-Start).
4. [ ] Conversion auswerten: Firebase Analytics `launch_promo_banner_shown` → `premium_prompt_shown{trigger=launch_promo_banner}` → `premium_subscribed{offer_tag=launch50}`.

**Metrik-Caveat:** `premium_subscribed` feuert bei `PurchaseResult.Success`, der auch PENDING-Käufe (langsame Zahlungsmethoden) einschließt; ein nie abgeschlossener PENDING-Kauf wird gezählt, ein später abgeschlossener nicht erneut. Die Conversion-Zahl ist also approximativ — Play Console bleibt die autoritative Umsatzquelle. (Follow-up-Issue beim PR notiert.)

## 5. Gerätetest (Pixel 8, License Tester)

Voraussetzung: Test-Konto ist in Play Console → Einstellungen → Lizenztests eingetragen.

1. Internal-Build installieren, mit Lizenztester-Konto angemeldet sein.
2. RC-Werte gesetzt + Offer aktiv → App-Kaltstart.
3. ERWARTET: Home zeigt Banner „LAUNCH OFFER … until <Datum>" mit echten lokalisierten Preisen.
4. Banner-Tap → Sheet: Jahres-Plan vorselektiert, Streichpreis (regulär) + Promo-Preis in Neon-Gelb + „Launch offer"-Badge + „Offer ends <Datum>" + Transparenz-Hinweis.
5. Settings → Premium-Zeile zeigt „LAUNCH OFFER"-Badge.
6. Kauf im Test-Modus durchziehen → Google-Play-Dialog muss den **Promo-Preis** zeigen (das ist der eigentliche Routing-Test!); Erfolgsdialog; Banner und Badges verschwinden (Gate 4).
7. Banner-Dismiss-Test (vor Kauf): X antippen → Banner weg, bleibt nach App-Neustart weg; Sheet zeigt Promo weiterhin.
8. Kill-Switch-Test: RC `launch_promo_enabled=false` veröffentlichen → App-Neustart (RC-Cache 1h beachten oder App-Daten löschen) → kein Banner.
9. TalkBack-Stichprobe: Streichpreis wird als „Regular price: …" angesagt.

## 6. Community-Post-Entwürfe

**r/selfhosted + r/paperlessngx (EN) — Titel:** "I built a native Android scanner app for Paperless-ngx (MLKit scanning, offline queue, optional AI tagging)"

> Hi all — solo dev here. I've been building a native Android client for Paperless-ngx
> focused on the scan-to-upload flow: MLKit document scanning, an offline-first upload
> queue, widgets, and biometric/app-lock support. It talks directly to your instance;
> nothing goes through my servers.
>
> Tag/title suggestions come in two flavors: a local, on-device rule engine (free, no
> network) and an optional cloud-AI mode (Gemini) for automatic titles/tags/correspondents
> at scan time — that one's a small subscription because the API calls cost real money.
> If you already run paperless-ai or paperless-gpt server-side: great, you don't need it.
> This is for people who don't want to run another container.
>
> It's on the launch sale right now (50% off the first year of the AI plan), the app
> itself is a one-time ~1 CHF. Happy to answer anything — feedback and feature requests
> very welcome. [Play Store link]

Regeln: zuerst die Self-Promo-Regeln des jeweiligen Subreddits prüfen; auf Kommentare schnell und technisch ehrlich antworten; die Promo bleibt Fußnote, nicht Headline; keine Fake-Urgency.

**GitHub Discussions (paperless-ngx, Show and tell):** gleiche Substanz, kürzer, mit 2–3 Screenshots (Scan-Flow, Banner, Sheet).

## 7. Wiki-Listing (Related Projects)

GitHub-Konto → https://github.com/paperless-ngx/paperless-ngx/wiki/Related-Projects → „Edit" (Wiki ist offen editierbar; falls gesperrt: Request via Discussion). Eintrag unter „Android":

> - [Paperless Scanner](https://play.google.com/store/apps/details?id=com.paperless.scanner) by Marcus Martini — Native Android scanner app for Paperless-ngx: MLKit document scanning, offline upload queue, widgets, optional on-device + cloud-AI tag/title suggestions. (Paid app, ~1 CHF)

## 8. Play-Listing-Ergänzung (EN-Basistext)

Beschreibung ergänzen um: "Zero-setup AI suggestions (optional): titles, tags & correspondents at scan time — no server add-ons, no Docker. Local rule-based suggestions are always free. Documents go only to YOUR server; AI analysis is opt-in."

## 9. Rollback / Kill-Switch

Falls irgendetwas schiefläuft (falsche Preise, Anzeige-Bugs):
1. Firebase RC: `launch_promo_enabled = false` → veröffentlichen (wirkt beim nächsten App-Start, ≤1h RC-Cache).
2. Play Console: Offer `launch50` deaktivieren (wirkt sofort auf Käufe — neue Promo-Käufe schlagen kontrolliert fehl, Default-Offer bleibt kaufbar).
3. Kein App-Release nötig; die App fällt überall fail-closed auf den Regulärzustand zurück.
