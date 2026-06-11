# Design: Launch-Promo „50% aufs erste Jahr" für Premium-KI

**Datum:** 2026-06-11
**Status:** Approved (Brainstorm-Session mit User, Design abgenommen)
**Scope:** In-App-Implementierung der Launch-Promo + manuelles Play-Console-Setup + Begleitmaßnahmen (Community/Channels)

---

## 1. Kontext & Research-Grundlage

Die App wird für ~1 CHF auf Google Play verkauft (Kaufpreis bleibt bestehen — User-Entscheidung, u. a. weil „paid → free" bei Play irreversibel ist). Premium-Tier: KI-Analyse (Titel, Tags, Korrespondent, Dokumenttyp, Datum via Gemini) hinter `paperless_ai_monthly` (€3.99, 7d Trial) / `paperless_ai_yearly` (€39.99, 14d Trial).

Deep-Research-Erkenntnisse zur Zielgruppe (Paperless-ngx-/Self-Hosting-Community), verifiziert via adversarialem Review:

1. **Sichtbarkeit ist das Kernproblem, nicht der Preis.** Die App ist NICHT im offiziellen [Related-Projects-Wiki](https://github.com/paperless-ngx/paperless-ngx/wiki/Related-Projects) gelistet — der wichtigsten Discovery-Quelle des Ökosystems (nur 3 Android-Apps gelistet vs. 7 iOS). Selbst kostenlose Clients ohne Community-Präsenz haben minimale Traktion (Paperless Go: „100+" Downloads).
2. **Konkurrenz ist Gratis-OSS:** Paperless Mobile (GPL v3, Play + F-Droid, keine KI-Features, NICHT verwaist — Gegenteil wurde im Review widerlegt), Swift Paperless (iOS, MIT, aktiv: v1.10.0 vom 2026-06-06). Kein offizieller Client existiert oder ist geplant (Core-Team hat explizit abgelehnt, [Discussion #11068](https://github.com/paperless-ngx/paperless-ngx/discussions/11068)).
3. **Die Premium-KI konkurriert mit kostenlosen Server-Tools** ([paperless-ai](https://github.com/clusterzx/paperless-ai), [paperless-gpt](https://github.com/icereed/paperless-gpt)), die im offiziellen Wiki stehen. Differenzierung: **„Zero-Setup-KI direkt beim Scannen"** — kein Docker, kein API-Key, kein Server-Gebastel.
4. **Akzeptierte Monetarisierung in dieser Community:** Bezahlt wird für Convenience und zur Unterstützung, nicht für weggenommene Grundfunktionen (Immich: optionale Lifetime-Lizenzen, null Feature-Gating; Home Assistant Cloud: optionales Convenience-Abo). Abo-Begründung „deckt KI-API-Kosten, finanziert Weiterentwicklung" offen kommunizieren.
5. **Lokale KI ist Differenzierungsmerkmal** (iOS-Konkurrent Archi bewirbt on-device AI als Headline). Die kostenlose lokale `TagMatchingEngine` ist daher ein Marketing-Asset, kein Kannibalisierungsproblem: „Lokale Vorschläge gratis, Cloud-KI optional."
6. **Messaging-Leitplanke:** Druck-Taktiken (tickende Countdowns, Fake-Scarcity) werden abgestraft; ehrliche, transparente Angebote mit Begründung werden akzeptiert.

## 2. Entscheidungen (User-abgenommen)

| Entscheidung | Wert |
|---|---|
| Ziel | Launch-Promo, gekoppelt an Production-Promote der Premium-Version |
| Mechanik | Intro-Rabatt auf Jahresabo: **€19.99 statt €39.99 im ersten Jahr** (−50%), 14d-Trial bleibt davor |
| Fenster | **4 Wochen** ab Production-Promote |
| Dringlichkeit | **Statisches Enddatum** („Offer ends {date}"), KEIN tickender Countdown |
| Kaufpreis App | Bleibt ~1 CHF (keine Free-Umstellung) |
| Architektur | **Ansatz A:** Play-Offer als Quelle der Wahrheit + Firebase Remote Config nur für Enddatum + Kill-Switch |
| Monatsabo | Unverändert, kein Promo |

## 3. Play Console Setup (manuell, kein Code)

Auf dem Base Plan von `paperless_ai_yearly` ein neues Offer anlegen:

- **Offer-Tag:** `launch50`
- **Eligibility:** developer-determined (App entscheidet: sichtbar für alle Nicht-Aktiven, inkl. abgelaufener Abos)
- **Phasen:** 14 Tage kostenlos → 1 Jahr €19.99 (Einmalpreis fürs erste Jahr) → danach regulärer Auto-Renew €39.99
- Bestehendes Default-Offer (14d Trial) bleibt aktiv → greift automatisch wieder nach Deaktivierung von `launch50`
- **Start/Stopp der Promo = Offer aktivieren/deaktivieren.** Kein App-Release nötig.

## 4. Architektur (Code)

### 4.1 `RemoteConfigManager` (neu, `data/config/`)

- Kapselt Firebase Remote Config (Dependency über bestehende Firebase BoM; neue Dependency begründet: zeitgesteuerte Kampagnen ohne Release-Zwang + Kill-Switch).
- Keys: `launch_promo_enabled` (Boolean, Default **false** = Kill-Switch) und `launch_promo_end_epoch_ms` (Long, Default 0).
- Offline / Fetch-Fehler → Defaults → Promo unsichtbar (**fail-closed**).
- Exponiert Werte als `StateFlow`, Hilt `@Singleton`.

### 4.2 `BillingManager`-Erweiterung

- `ProductDetails.subscriptionOfferDetails` auslesen: Offer-Tags + Pricing-Phasen (formatierte, lokalisierte Preise).
- Neue Variante `launchPurchaseFlow(activity, productId, offerToken)` — kauft gezielt das `launch50`-Offer.
- Angezeigte Preise kommen AUSSCHLIESSLICH aus der Billing-API (nie hartcodierte Preis-Strings — Play liefert je Land andere Preise).
- API-Details (Billing 8.3.0) bei Implementierung via Context7 verifizieren.

### 4.3 `LaunchPromoManager` (neu, @Singleton)

Kombiniert vier Signale reaktiv (Flow-`combine`, kein Polling) zu `StateFlow<LaunchPromoState>`:

```kotlin
sealed interface LaunchPromoState {
    data object Hidden : LaunchPromoState
    data class Active(
        val promoPrice: String,      // lokalisiert, aus Billing
        val regularPrice: String,    // lokalisiert, aus Billing
        val endDate: LocalDate,      // aus Remote Config
        val offerToken: String,
    ) : LaunchPromoState
}
```

`Active` nur wenn ALLE gelten, sonst `Hidden`:
1. RC `launch_promo_enabled == true`
2. `now < launch_promo_end_epoch_ms`
3. Billing liefert Offer mit Tag `launch50` auf `paperless_ai_yearly`
4. Nutzer ist nicht Premium-aktiv (`BillingManager.isSubscriptionActive == false`)

## 5. UI (Dark Tech Precision Pro, Strings nur EN in `values/strings.xml`)

- **Home-Banner:** dismissbare Promo-Card (Akzent `#E1FF8D`, 20dp Radius, 1dp Outline, elevation 0): „LAUNCH OFFER — AI yearly plan 50% off until {date}" → öffnet PremiumUpgradeSheet. Dismiss in DataStore persistiert, gilt fürs ganze Promo-Fenster.
- **PremiumUpgradeSheet:** Badge „Launch offer" am Jahres-Plan, durchgestrichener Regulärpreis neben Promo-Preis (beide aus Billing-API), Zeile „Offer ends {date}". CTA nutzt `launch50`-offerToken. Transparenz-Copy ergänzen: Vorschläge laufen über Cloud-KI; lokale Vorschläge bleiben kostenlos.
- **Settings → PremiumSection:** kleines „Launch offer"-Badge solange aktiv.

## 6. Analytics

- `PremiumPromptShown` / `PremiumPromptDismissed` mit neuem Trigger-Wert `launch_promo_banner`.
- `PremiumSubscribed` um Param `offerTag` ergänzen.
- Neues Event `LaunchPromoBannerShown` (Impression).
- GDPR-Verhalten unverändert (nur nach Consent).

## 7. Edge Cases

| Fall | Verhalten |
|---|---|
| Offline / RC-Fetch-Fehler | Defaults → Promo unsichtbar (fail-closed, nie falscher Preis) |
| Uhr-Manipulation | Enddatum nur Anzeige; echtes Gate ist das Offer in Play |
| Promo endet mid-session | Kauf schlägt mit normalem Billing-Fehler fehl; Sheet zeigt danach Regulärpreis |
| Billing nicht Ready / Disconnected | Kein Offer lesbar → `Hidden` |
| Restore / Grace Period / ON_HOLD | Unverändert über bestehende `SubscriptionStatus`-Logik |
| Bereits Premium | `Hidden` (Signal 4) |

## 8. Tests

- Unit-Tests `LaunchPromoManager`: Fake-Billing + Fake-RC, alle Gate-Kombinationen (2⁴-Raster sinnvoll reduziert), Turbine für Flow-Tests (`.coderabbit.yaml`-Konvention).
- `RemoteConfigManager`: Default-/Fehlerverhalten.
- UI-State-Tests PremiumUpgradeSheet: Promo- vs. Normal-Zustand.
- Pipeline wie immer: lokale Release-CI 100% grün → codex review → PR → CodeRabbit.

## 9. Rollout-Reihenfolge

1. Implementieren (Branch + PR, übliche Review-Gates)
2. Internal-Build, Gerätetest Pixel 8 (Promo-Offer via License-Tester sichtbar machen)
3. User: Production-Promote (`fastlane android promote`) + Offer `launch50` aktivieren + RC-Enddatum setzen + `launch_promo_enabled=true`
4. Community-Posts (siehe §10)
5. Nach 4 Wochen: Offer deaktivieren + RC-Kill-Switch — App fällt automatisch auf Regulärpreis zurück

## 10. Begleitmaßnahmen (manuell, Teil der Aktion)

1. **Wiki-Listing beantragen** (Related Projects) — wichtigster Gratis-Kanal, App fehlt dort.
2. **Launch-Posts:** r/paperlessngx + r/selfhosted („I built…"-Post: ehrliche Solo-Dev-Story, lokale Gratis-Features zuerst, Cloud-KI transparent, Promo als Fußnote — nicht als Headline), GitHub Discussions Show-and-tell. Text-Entwürfe werden mitgeliefert.
3. **Play-Listing:** Beschreibung um „Zero-setup AI suggestions" + Privacy-Absatz ergänzen.

**Messaging-Do's:** Transparenz über Datenfluss (was geht an Gemini, was bleibt lokal), Abo-Begründung (API-Kosten, Weiterentwicklung), freie Alternativen anerkennen („wer paperless-ai auf dem Server mag — super; das hier ist für alle ohne Server-Setup").
**Messaging-Don'ts:** Fake-Urgency, tickende Countdowns, Marketing-Superlative, Promo-Spam in Community-Kanälen.

## 11. Out of Scope

- Lifetime-Lizenz-Option (Immich-Modell) — ggf. späteres Follow-up
- Promo auf Monatsabo
- Push-Notifications / FCM
- Win-back-Offers für abgelaufene Abos als eigene Kampagne
- A/B-Testing der Promo-Varianten
