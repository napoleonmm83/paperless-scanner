# Launch-Promo „50% aufs erste Jahr Premium-KI" — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Zeitlich begrenzte Launch-Promo (50% aufs erste Jahr `paperless_ai_yearly`) in der App anzeigen und kaufbar machen — gesteuert über ein Play-Console-Offer (`launch50`) als Quelle der Wahrheit plus Firebase Remote Config (Enddatum + Kill-Switch), fail-closed.

**Architecture:** Neues `LaunchPromoManager`-Singleton kombiniert vier Gates (RC-Kill-Switch, Enddatum, Play-Offer vorhanden, Nutzer nicht Premium) reaktiv zu `StateFlow<LaunchPromoState>`. `BillingManager` extrahiert das `launch50`-Offer (Token + lokalisierte Preise) aus `ProductDetails` und erhält eine offerToken-bewusste Purchase-Variante. UI: dismissbarer Home-Banner, Promo-Darstellung im `PremiumUpgradeSheet` (alle 5 Call-Sites unverändert dank internem `hiltViewModel()`), Badge in der Settings-`PremiumSection`.

**Tech Stack:** Kotlin 2.0, Jetpack Compose + Material 3, Hilt, Play Billing 8.3.0, Firebase Remote Config (neu, via bestehender BoM 34.6.0), DataStore Preferences, mockk + Turbine + JUnit4.

**Spec:** `docs/superpowers/specs/2026-06-11-launch-promo-design.md` (freigegeben). Branch: `feature/launch-promo-premium`.

---

## Wichtige Projekt-Konventionen (für den Executor)

- **Pre-Commit-Hook** kompiliert Kotlin (main + test) bei jedem `git commit` — jeder Task muss eigenständig kompilieren.
- **CI = RELEASE-Varianten:** `./gradlew testReleaseUnitTest`, `lintRelease`, `assembleRelease`. JDK 21 ist maschinenweit gepinnt (`~/.gradle/gradle.properties`), kein Export nötig.
- **Kein lokales Gradle während eines aktiven Auto-Deploys** (self-hosted Runner = diese Maschine). Vor Gradle-Läufen prüfen: `gh run list --workflow=auto-deploy-internal.yml --limit 1` → kein `in_progress`.
- **Strings nur Englisch** in `app/src/main/res/values/strings.xml` (Gemini übersetzt automatisch). Keine values-de/-en Ordner anfassen.
- **Flow-Tests mit Turbine** (`.coderabbit.yaml`-Mandat).
- Code-Kommentare auf Englisch (Codebase-Konvention), Commits Conventional Commits.

## File-Struktur (was entsteht / was sich ändert)

| Datei | Aktion | Verantwortung |
|---|---|---|
| `gradle/libs.versions.toml` | Modify | +`firebase-config` (BoM-managed) |
| `app/build.gradle.kts` | Modify | +`implementation(libs.firebase.config)` |
| `app/src/main/java/com/paperless/scanner/data/analytics/AnalyticsEvent.kt` | Modify | `PremiumSubscribed` um `offerTag` erweitert (Preis-Param entfällt — Event wurde bisher nie geloggt), neues `LaunchPromoBannerShown` |
| `app/src/main/java/com/paperless/scanner/data/datastore/TokenManager.kt` | Modify | Dismiss-Flag (Key + Flow + Setter) |
| `app/src/main/java/com/paperless/scanner/data/billing/BillingManager.kt` | Modify | `launchOffer: StateFlow<LaunchOfferDetails?>`, `extractLaunchOffer()`, offerToken-Param in `launchPurchaseFlow` |
| `app/src/main/java/com/paperless/scanner/data/config/RemoteConfigManager.kt` | Create | RC-Wrapper, fail-closed Defaults, `launchPromoConfig: StateFlow<LaunchPromoConfig>` |
| `app/src/main/java/com/paperless/scanner/di/AppModule.kt` | Modify | `@Provides FirebaseRemoteConfig` |
| `app/src/main/java/com/paperless/scanner/PaperlessApp.kt` | Modify | RC-Init in `onCreate`, `launchPromoManager.destroy()` in `onTerminate` (#142-Pattern) |
| `app/src/main/java/com/paperless/scanner/data/billing/LaunchPromoManager.kt` | Create | 4-Gate-Kombination → `StateFlow<LaunchPromoState>`, `promoOfferTokenFor()` |
| `app/src/main/java/com/paperless/scanner/ui/components/promo/LaunchPromoViewModel.kt` | Create | Banner-/Sheet-UI-State, Dismiss, Analytics |
| `app/src/main/java/com/paperless/scanner/ui/components/promo/LaunchPromoBanner.kt` | Create | Stateless Banner-Composable + Preview |
| `app/src/main/java/com/paperless/scanner/ui/screens/home/HomeScreen.kt` | Modify | Banner-Item in LazyColumn |
| `app/src/main/java/com/paperless/scanner/ui/screens/settings/PremiumUpgradeSheet.kt` | Modify | Promo-Preise/Badge/Enddatum am Jahres-Plan, Default-Selektion yearly |
| `app/src/main/java/com/paperless/scanner/ui/screens/settings/sections/PremiumSection.kt` | Modify | „Launch offer"-Badge |
| `app/src/main/java/com/paperless/scanner/ui/screens/settings/SettingsViewModel.kt` | Modify | `launchPromoActive` in UiState, offerToken-Routing, `PremiumSubscribed`-Logging |
| `app/src/main/java/com/paperless/scanner/ui/screens/settings/SettingsScreen.kt` | Modify | Badge-Param durchreichen |
| `app/src/main/res/values/strings.xml` | Modify | 5 neue Promo-Strings |
| `docs/LAUNCH_PROMO_RUNBOOK.md` | Create | Console/RC-Setup, Go-Live-/End-Checkliste, Community-Post-Entwürfe |
| Tests | Create | `AnalyticsEventLaunchPromoTest`, `BillingManagerLaunchOfferTest`, `RemoteConfigManagerTest`, `LaunchPromoManagerTest`, `LaunchPromoViewModelTest` |

**Bewusste Test-Lücken (mit Begründung, nicht stillschweigend):**
- `TokenManager`-Dismiss-Flag: trivialer Spiegel des bestehenden Patterns; Repo hat keine DataStore-Test-Patterns (Memory 16512).
- `SettingsViewModel.launchPurchaseFlow`-Routing: 3 Zeilen Glue über `promoOfferTokenFor()` (getestet in Task 6) + `launchPurchaseFlow` (getestet in Task 4); das `init`-Lade-Geflecht von SettingsViewModel macht einen isolierten Test unverhältnismäßig teuer. Abdeckung via Gerätetest (Runbook) + Review-Gates. Falls codex/CodeRabbit widerspricht: Test nachziehen.
- Compose-UI (Banner/Sheet/Section): Previews + Gerätetest, keine Unit-Tests (Repo-Konvention).

---

### Task 1: Firebase Remote Config Dependency

**Files:**
- Modify: `gradle/libs.versions.toml` (Firebase-Block, nach Zeile 157 `firebase-ai`)
- Modify: `app/build.gradle.kts` (nach Zeile 293 `implementation(libs.firebase.ai)`)

- [ ] **Step 1: Library-Eintrag in libs.versions.toml**

Nach der Zeile `firebase-ai = { group = "com.google.firebase", name = "firebase-ai" }` einfügen:

```toml
firebase-config = { group = "com.google.firebase", name = "firebase-config" }
```

(BoM-managed, daher ohne `version.ref` — wie `firebase-analytics`/`firebase-ai`.)

- [ ] **Step 2: Dependency in app/build.gradle.kts**

Nach `implementation(libs.firebase.ai)` einfügen:

```kotlin
    // Firebase Remote Config (launch promo flags: end date + kill switch)
    implementation(libs.firebase.config)
```

- [ ] **Step 3: Kompilieren**

Run: `./gradlew :app:compileReleaseKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "build: add Firebase Remote Config dependency for launch promo flags"
```

---

### Task 2: Analytics-Events

**Files:**
- Modify: `app/src/main/java/com/paperless/scanner/data/analytics/AnalyticsEvent.kt:224-262`
- Test (Create): `app/src/test/java/com/paperless/scanner/data/analytics/AnalyticsEventLaunchPromoTest.kt`

Hintergrund: `PremiumSubscribed`/`PremiumPromptShown`/`PremiumPromptDismissed` sind definiert, werden aber **nirgends geloggt** (grep-verifiziert) — die Signatur von `PremiumSubscribed` darf daher bruchfrei geändert werden. Der `priceUsd`-Param entfällt (Play Console besitzt die Umsatzdaten; ein lokal erratener USD-Preis wäre Datenmüll), `offerTag` kommt hinzu.

- [ ] **Step 1: Failing Test schreiben**

```kotlin
package com.paperless.scanner.data.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

class AnalyticsEventLaunchPromoTest {

    @Test
    fun `LaunchPromoBannerShown has stable event name and no params`() {
        val event = AnalyticsEvent.LaunchPromoBannerShown
        assertEquals("launch_promo_banner_shown", event.name)
        assertEquals(emptyMap<String, Any>(), event.params)
    }

    @Test
    fun `PremiumSubscribed carries plan and offer tag`() {
        val event = AnalyticsEvent.PremiumSubscribed(plan = "yearly", offerTag = "launch50")
        assertEquals("premium_subscribed", event.name)
        assertEquals(mapOf("plan" to "yearly", "offer_tag" to "launch50"), event.params)
    }

    @Test
    fun `PremiumSubscribed defaults offer tag to none`() {
        val event = AnalyticsEvent.PremiumSubscribed(plan = "monthly")
        assertEquals("none", event.params["offer_tag"])
    }
}
```

- [ ] **Step 2: Test laufen lassen — muss fehlschlagen**

Run: `./gradlew :app:compileReleaseUnitTestKotlin`
Expected: FAIL — `LaunchPromoBannerShown` unresolved / `offerTag` no such parameter

- [ ] **Step 3: AnalyticsEvent.kt anpassen**

`PremiumSubscribed` (Zeilen 223-238) ERSETZEN durch:

```kotlin
    /**
     * User upgraded to Premium subscription
     *
     * @param plan Subscription plan: "monthly" or "yearly"
     * @param offerTag Play Console offer tag used for the purchase (e.g. "launch50"), or "none"
     */
    data class PremiumSubscribed(
        val plan: String,
        val offerTag: String = "none"
    ) : AnalyticsEvent(
        "premium_subscribed",
        mapOf(
            "plan" to plan,
            "offer_tag" to offerTag
        )
    )
```

Im KDoc von `PremiumPromptShown` (Zeile 243) die Trigger-Liste erweitern:

```kotlin
     * @param trigger What triggered the prompt: "ai_feature_locked", "settings", "upload_screen", "launch_promo_banner"
```

Nach `AiUsageLimitReached` (vor dem Paperless-GPT-Abschnitt, Zeile 292) einfügen:

```kotlin
    // ==================== Launch Promo Events ====================

    /** Launch-promo banner impression on the Home screen (logged once per app process) */
    data object LaunchPromoBannerShown : AnalyticsEvent("launch_promo_banner_shown")
```

- [ ] **Step 4: Test laufen lassen — muss bestehen**

Run: `./gradlew :app:testReleaseUnitTest --tests "com.paperless.scanner.data.analytics.AnalyticsEventLaunchPromoTest"`
Expected: PASS (3 Tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/paperless/scanner/data/analytics/AnalyticsEvent.kt app/src/test/java/com/paperless/scanner/data/analytics/AnalyticsEventLaunchPromoTest.kt
git commit -m "feat(analytics): add launch-promo events and offer tag on PremiumSubscribed"
```

---

### Task 3: TokenManager — Banner-Dismiss-Flag

**Files:**
- Modify: `app/src/main/java/com/paperless/scanner/data/datastore/TokenManager.kt` (Key bei ~Z.47, Flow bei ~Z.216ff, Setter bei ~Z.392ff)

Kein Unit-Test (siehe „Bewusste Test-Lücken"). Exakt das bestehende Muster von `aiSuggestionsEnabled` spiegeln.

- [ ] **Step 1: Key im companion object** (neben `AI_DEBUG_MODE_KEY`, ~Z.47):

```kotlin
        private val LAUNCH_PROMO_BANNER_DISMISSED_KEY = booleanPreferencesKey("launch_promo_banner_dismissed")
```

- [ ] **Step 2: Flow** (direkt nach dem `aiNewTagsEnabled`-Flow, ~Z.223):

```kotlin
    /** Whether the user dismissed the launch-promo banner (persists for the whole promo window). */
    val launchPromoBannerDismissed: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[LAUNCH_PROMO_BANNER_DISMISSED_KEY] ?: false
    }
```

- [ ] **Step 3: Setter** (nach `setAiNewTagsEnabled`, ~Z.402):

```kotlin
    suspend fun setLaunchPromoBannerDismissed() {
        context.dataStore.edit { preferences ->
            preferences[LAUNCH_PROMO_BANNER_DISMISSED_KEY] = true
        }
    }
```

- [ ] **Step 4: Kompilieren + Commit**

Run: `./gradlew :app:compileReleaseKotlin` → `BUILD SUCCESSFUL`

```bash
git add app/src/main/java/com/paperless/scanner/data/datastore/TokenManager.kt
git commit -m "feat(datastore): persist launch-promo banner dismissal"
```

---

### Task 4: BillingManager — Launch-Offer-Erkennung + offerToken-Kauf

**Files:**
- Modify: `app/src/main/java/com/paperless/scanner/data/billing/BillingManager.kt`
- Test (Create): `app/src/test/java/com/paperless/scanner/data/billing/BillingManagerLaunchOfferTest.kt`

Eigene Test-Datei (kein Robolectric nötig): `extractLaunchOffer` ist eine pure Funktion über gemockte `ProductDetails`; der BillingManager-Konstruktor speichert nur den Context.

- [ ] **Step 1: Failing Test schreiben**

```kotlin
package com.paperless.scanner.data.billing

import com.android.billingclient.api.ProductDetails
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BillingManagerLaunchOfferTest {

    private val billingManager = BillingManager(mockk(relaxed = true))

    private fun pricingPhase(micros: Long, formatted: String): ProductDetails.PricingPhase = mockk {
        every { priceAmountMicros } returns micros
        every { formattedPrice } returns formatted
    }

    private fun offer(
        tags: List<String>,
        token: String,
        phases: List<ProductDetails.PricingPhase>
    ): ProductDetails.SubscriptionOfferDetails = mockk {
        every { offerTags } returns tags
        every { offerToken } returns token
        every { pricingPhases } returns mockk { every { pricingPhaseList } returns phases }
    }

    private fun productDetails(offers: List<ProductDetails.SubscriptionOfferDetails>?): ProductDetails = mockk {
        every { subscriptionOfferDetails } returns offers
    }

    @Test
    fun `launch50-tagged offer with trial, intro and regular phase is extracted`() {
        val details = productDetails(
            listOf(
                offer(tags = emptyList(), token = "base-token", phases = listOf(pricingPhase(39_990_000, "CHF 39.99"))),
                offer(
                    tags = listOf("launch50"),
                    token = "promo-token",
                    phases = listOf(
                        pricingPhase(0, "Free"),               // 14d trial
                        pricingPhase(19_990_000, "CHF 19.99"), // discounted first year
                        pricingPhase(39_990_000, "CHF 39.99")  // regular renewal
                    )
                )
            )
        )

        assertEquals(
            LaunchOfferDetails(
                offerToken = "promo-token",
                introFormattedPrice = "CHF 19.99",
                regularFormattedPrice = "CHF 39.99"
            ),
            billingManager.extractLaunchOffer(details)
        )
    }

    @Test
    fun `product without launch50 tag yields null`() {
        val details = productDetails(
            listOf(offer(tags = listOf("winback"), token = "x", phases = listOf(pricingPhase(39_990_000, "CHF 39.99"))))
        )
        assertNull(billingManager.extractLaunchOffer(details))
    }

    @Test
    fun `tagged offer without real discount yields null`() {
        val details = productDetails(
            listOf(
                offer(
                    tags = listOf("launch50"),
                    token = "promo-token",
                    phases = listOf(pricingPhase(39_990_000, "CHF 39.99"), pricingPhase(39_990_000, "CHF 39.99"))
                )
            )
        )
        assertNull(billingManager.extractLaunchOffer(details))
    }

    @Test
    fun `null product details yields null`() {
        assertNull(billingManager.extractLaunchOffer(null))
    }
}
```

- [ ] **Step 2: Kompilieren — muss fehlschlagen** (`extractLaunchOffer`/`LaunchOfferDetails` unresolved)

Run: `./gradlew :app:compileReleaseUnitTestKotlin` → FAIL

- [ ] **Step 3: BillingManager.kt erweitern**

(a) Companion (nach `PRODUCT_ID_YEARLY`, Z.84):

```kotlin
        /** Play Console offer tag that marks the time-limited launch promo on the yearly plan. */
        const val LAUNCH_PROMO_OFFER_TAG = "launch50"
```

(b) Felder (nach `productDetailsCache`, Z.109):

```kotlin
    // Launch-promo offer on the yearly plan, re-extracted on every product query.
    // null = Play does not currently serve a discounted launch50 offer (fail-closed).
    private val _launchOffer = MutableStateFlow<LaunchOfferDetails?>(null)
    val launchOffer: StateFlow<LaunchOfferDetails?> = _launchOffer.asStateFlow()
```

(c) In `queryProductDetails()`, OK-Zweig, direkt nach dem `unfetchedProducts`-Block (Z.333):

```kotlin
                    _launchOffer.value = extractLaunchOffer(productDetailsCache[PRODUCT_ID_YEARLY])
```

(d) Neue Methode (nach `queryProductDetails`, vor `queryPurchases`):

```kotlin
    /**
     * Extracts the launch-promo offer (tag [LAUNCH_PROMO_OFFER_TAG]) from the yearly
     * product, if Play currently serves one. The intro price is the first paid pricing
     * phase, the regular price the last one. A tagged offer whose intro price is not
     * actually cheaper carries no real discount and is treated as "no promo" (fail-closed).
     */
    internal fun extractLaunchOffer(productDetails: ProductDetails?): LaunchOfferDetails? {
        val offer = productDetails?.subscriptionOfferDetails
            ?.firstOrNull { LAUNCH_PROMO_OFFER_TAG in it.offerTags }
            ?: return null
        val paidPhases = offer.pricingPhases.pricingPhaseList.filter { it.priceAmountMicros > 0 }
        val intro = paidPhases.firstOrNull() ?: return null
        val regular = paidPhases.last()
        if (intro.priceAmountMicros >= regular.priceAmountMicros) return null
        return LaunchOfferDetails(
            offerToken = offer.offerToken,
            introFormattedPrice = intro.formattedPrice,
            regularFormattedPrice = regular.formattedPrice
        )
    }
```

(e) `launchPurchaseFlow`-Signatur (Z.432) ändern auf:

```kotlin
    suspend fun launchPurchaseFlow(activity: Activity, productId: String, offerToken: String? = null): PurchaseResult {
```

und den Offer-Token-Block (Z.515-523) ERSETZEN durch:

```kotlin
        // Promo path: an explicitly requested offer must still be served by Play right now
        // (it disappears when the Console offer is deactivated — the authoritative gate).
        // Default path: first offer (trial offer when configured as default in Play Console).
        val resolvedOfferToken = if (offerToken != null) {
            productDetails.subscriptionOfferDetails?.firstOrNull { it.offerToken == offerToken }?.offerToken
        } else {
            productDetails.subscriptionOfferDetails?.firstOrNull()?.offerToken
        }
        if (resolvedOfferToken == null) {
            AppLogger.e(TAG, "✗ No subscription offers available!")
            return PurchaseResult.Error(context.getString(R.string.billing_error_no_offers))
        }

        AppLogger.d(TAG, "✓ Offer token found")
        AppLogger.d(TAG, "Offers available: ${productDetails.subscriptionOfferDetails?.size}")
```

und in `ProductDetailsParams` `.setOfferToken(offerToken)` → `.setOfferToken(resolvedOfferToken)`.

(f) Data class am Datei-Ende (vor `SubscriptionInfo`):

```kotlin
/**
 * Launch-promo offer details extracted from the yearly product.
 *
 * Prices are the localized formatted strings from Play Billing — never reformat
 * or hardcode them; Play serves per-country prices.
 */
data class LaunchOfferDetails(
    val offerToken: String,
    val introFormattedPrice: String,
    val regularFormattedPrice: String
)
```

- [ ] **Step 4: Tests laufen lassen**

Run: `./gradlew :app:testReleaseUnitTest --tests "com.paperless.scanner.data.billing.*"`
Expected: PASS — neue 4 Tests UND alle bestehenden Billing-Tests (Signatur-Default-Param ist abwärtskompatibel)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/paperless/scanner/data/billing/BillingManager.kt app/src/test/java/com/paperless/scanner/data/billing/BillingManagerLaunchOfferTest.kt
git commit -m "feat(billing): expose launch50 offer details and offer-token-aware purchase flow"
```

---

### Task 5: RemoteConfigManager + DI + App-Wiring

**Files:**
- Create: `app/src/main/java/com/paperless/scanner/data/config/RemoteConfigManager.kt`
- Modify: `app/src/main/java/com/paperless/scanner/di/AppModule.kt` (object AppModule, ans Ende der @Provides-Liste)
- Modify: `app/src/main/java/com/paperless/scanner/PaperlessApp.kt` (Inject + onCreate)
- Test (Create): `app/src/test/java/com/paperless/scanner/data/config/RemoteConfigManagerTest.kt`

- [ ] **Step 1: Failing Test schreiben**

```kotlin
package com.paperless.scanner.data.config

import com.google.android.gms.tasks.OnCompleteListener
import com.google.android.gms.tasks.Task
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteConfigManagerTest {

    private fun immediateBoolTask(success: Boolean): Task<Boolean> {
        val task = mockk<Task<Boolean>>(relaxed = true)
        every { task.isSuccessful } returns success
        every { task.addOnCompleteListener(any<OnCompleteListener<Boolean>>()) } answers {
            firstArg<OnCompleteListener<Boolean>>().onComplete(task)
            task
        }
        return task
    }

    private fun remoteConfig(fetchSucceeds: Boolean, enabled: Boolean, endEpochMs: Long): FirebaseRemoteConfig =
        mockk(relaxed = true) {
            every { fetchAndActivate() } returns immediateBoolTask(fetchSucceeds)
            every { getBoolean(RemoteConfigManager.KEY_LAUNCH_PROMO_ENABLED) } returns enabled
            every { getLong(RemoteConfigManager.KEY_LAUNCH_PROMO_END_EPOCH_MS) } returns endEpochMs
        }

    @Test
    fun `before initialize the config is fail-closed`() {
        val manager = RemoteConfigManager(mockk(relaxed = true))
        assertEquals(LaunchPromoConfig(enabled = false, endEpochMs = 0L), manager.launchPromoConfig.value)
    }

    @Test
    fun `successful fetch publishes remote values`() {
        val manager = RemoteConfigManager(
            remoteConfig(fetchSucceeds = true, enabled = true, endEpochMs = 1_750_000_000_000)
        )
        manager.initialize()
        assertEquals(
            LaunchPromoConfig(enabled = true, endEpochMs = 1_750_000_000_000),
            manager.launchPromoConfig.value
        )
    }

    @Test
    fun `failed fetch publishes activated or default values instead of hanging`() {
        val manager = RemoteConfigManager(
            remoteConfig(fetchSucceeds = false, enabled = false, endEpochMs = 0L)
        )
        manager.initialize()
        assertEquals(LaunchPromoConfig(enabled = false, endEpochMs = 0L), manager.launchPromoConfig.value)
    }
}
```

- [ ] **Step 2: Kompilieren — FAIL** (Klasse existiert nicht)

- [ ] **Step 3: RemoteConfigManager.kt anlegen**

```kotlin
package com.paperless.scanner.data.config

import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import com.paperless.scanner.util.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Snapshot of the launch-promo flags from Firebase Remote Config. */
data class LaunchPromoConfig(
    val enabled: Boolean,
    val endEpochMs: Long
)

/**
 * Thin wrapper around Firebase Remote Config for the launch promo.
 *
 * Fail-closed by design: until a fetch succeeds (or previously activated values
 * exist), the published config keeps the promo disabled. Listener-based — no
 * coroutine scope to manage or tear down.
 */
@Singleton
class RemoteConfigManager @Inject constructor(
    private val remoteConfig: FirebaseRemoteConfig
) {
    companion object {
        // <=11 chars so AppLogger's "Paperless.{tag}" stays within Android's 23-char tag cap.
        private const val TAG = "RemoteCfg"
        const val KEY_LAUNCH_PROMO_ENABLED = "launch_promo_enabled"
        const val KEY_LAUNCH_PROMO_END_EPOCH_MS = "launch_promo_end_epoch_ms"

        // 1h instead of the 12h default so ending the promo propagates same-day;
        // the authoritative off-switch remains the Play Console offer itself.
        private const val MIN_FETCH_INTERVAL_SECONDS = 3_600L

        // Fail-closed defaults: with no fetched values the promo stays hidden.
        val DEFAULTS: Map<String, Any> = mapOf(
            KEY_LAUNCH_PROMO_ENABLED to false,
            KEY_LAUNCH_PROMO_END_EPOCH_MS to 0L
        )
    }

    private val _launchPromoConfig = MutableStateFlow(LaunchPromoConfig(enabled = false, endEpochMs = 0L))
    val launchPromoConfig: StateFlow<LaunchPromoConfig> = _launchPromoConfig.asStateFlow()

    /** Sets in-app defaults and fetches remote values. Safe to call once from Application.onCreate. */
    fun initialize() {
        remoteConfig.setConfigSettingsAsync(
            FirebaseRemoteConfigSettings.Builder()
                .setMinimumFetchIntervalInSeconds(MIN_FETCH_INTERVAL_SECONDS)
                .build()
        )
        remoteConfig.setDefaultsAsync(DEFAULTS)
        remoteConfig.fetchAndActivate().addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                AppLogger.w(TAG, "Remote Config fetch failed — publishing activated/default values")
            }
            // Previously activated values (or the fail-closed defaults) are still valid reads.
            publishCurrentValues()
        }
    }

    private fun publishCurrentValues() {
        _launchPromoConfig.value = LaunchPromoConfig(
            enabled = remoteConfig.getBoolean(KEY_LAUNCH_PROMO_ENABLED),
            endEpochMs = remoteConfig.getLong(KEY_LAUNCH_PROMO_END_EPOCH_MS)
        )
    }
}
```

- [ ] **Step 4: AppModule-Provider**

In `di/AppModule.kt` (object AppModule) am Ende der @Provides-Liste einfügen + Import `com.google.firebase.remoteconfig.FirebaseRemoteConfig`:

```kotlin
    @Provides
    @Singleton
    fun provideFirebaseRemoteConfig(): FirebaseRemoteConfig = FirebaseRemoteConfig.getInstance()
```

- [ ] **Step 5: PaperlessApp.kt**

Neues Inject-Feld (neben `billingManager`, Z.47-48) + Import `com.paperless.scanner.data.config.RemoteConfigManager`:

```kotlin
    @Inject
    lateinit var remoteConfigManager: RemoteConfigManager
```

In `onCreate()` nach `billingManager.initialize()` (Z.73):

```kotlin
        // Fetch launch-promo flags (fail-closed defaults until the first successful fetch)
        remoteConfigManager.initialize()
```

- [ ] **Step 6: Tests laufen lassen**

Run: `./gradlew :app:testReleaseUnitTest --tests "com.paperless.scanner.data.config.RemoteConfigManagerTest"`
Expected: PASS (3 Tests)

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/paperless/scanner/data/config/ app/src/main/java/com/paperless/scanner/di/AppModule.kt app/src/main/java/com/paperless/scanner/PaperlessApp.kt app/src/test/java/com/paperless/scanner/data/config/
git commit -m "feat(config): add fail-closed RemoteConfigManager for launch promo flags"
```

---

### Task 6: LaunchPromoManager (Kern)

**Files:**
- Create: `app/src/main/java/com/paperless/scanner/data/billing/LaunchPromoManager.kt`
- Modify: `app/src/main/java/com/paperless/scanner/PaperlessApp.kt` (onTerminate)
- Test (Create): `app/src/test/java/com/paperless/scanner/data/billing/LaunchPromoManagerTest.kt`

- [ ] **Step 1: Failing Test schreiben**

```kotlin
package com.paperless.scanner.data.billing

import app.cash.turbine.test
import com.paperless.scanner.data.config.LaunchPromoConfig
import com.paperless.scanner.data.config.RemoteConfigManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LaunchPromoManagerTest {

    private val promoConfigFlow = MutableStateFlow(LaunchPromoConfig(enabled = false, endEpochMs = 0L))
    private val launchOfferFlow = MutableStateFlow<LaunchOfferDetails?>(null)
    private val subscriptionActiveFlow = MutableStateFlow(false)

    private val billingManager = mockk<BillingManager> {
        every { launchOffer } returns launchOfferFlow
        every { isSubscriptionActive } returns subscriptionActiveFlow
    }
    private val remoteConfigManager = mockk<RemoteConfigManager> {
        every { launchPromoConfig } returns promoConfigFlow
    }

    private val offer = LaunchOfferDetails(
        offerToken = "promo-token",
        introFormattedPrice = "CHF 19.99",
        regularFormattedPrice = "CHF 39.99"
    )

    private val now = 1_000_000L

    private val expectedActive = LaunchPromoState.Active(
        promoPrice = "CHF 19.99",
        regularPrice = "CHF 39.99",
        endEpochMs = now + 1,
        offerToken = "promo-token"
    )

    private fun openAllGates() {
        promoConfigFlow.value = LaunchPromoConfig(enabled = true, endEpochMs = now + 1)
        launchOfferFlow.value = offer
        subscriptionActiveFlow.value = false
    }

    private fun kotlinx.coroutines.test.TestScope.manager() =
        LaunchPromoManager(billingManager, remoteConfigManager, clock = { now }, scope = backgroundScope)

    @Test
    fun `all four gates open - state is Active with offer data`() = runTest {
        openAllGates()
        val m = manager()
        advanceUntilIdle()
        assertEquals(expectedActive, m.state.value)
    }

    @Test
    fun `kill switch off - Hidden`() = runTest {
        openAllGates()
        promoConfigFlow.value = promoConfigFlow.value.copy(enabled = false)
        val m = manager()
        advanceUntilIdle()
        assertEquals(LaunchPromoState.Hidden, m.state.value)
    }

    @Test
    fun `end date reached - Hidden`() = runTest {
        openAllGates()
        promoConfigFlow.value = promoConfigFlow.value.copy(endEpochMs = now)
        val m = manager()
        advanceUntilIdle()
        assertEquals(LaunchPromoState.Hidden, m.state.value)
    }

    @Test
    fun `no launch offer served by Play - Hidden`() = runTest {
        openAllGates()
        launchOfferFlow.value = null
        val m = manager()
        advanceUntilIdle()
        assertEquals(LaunchPromoState.Hidden, m.state.value)
    }

    @Test
    fun `subscription already active - Hidden`() = runTest {
        openAllGates()
        subscriptionActiveFlow.value = true
        val m = manager()
        advanceUntilIdle()
        assertEquals(LaunchPromoState.Hidden, m.state.value)
    }

    @Test
    fun `state flips to Active when gates open while collecting`() = runTest {
        val m = manager()
        m.state.test {
            assertEquals(LaunchPromoState.Hidden, awaitItem())
            openAllGates()
            assertEquals(expectedActive, awaitItem())
        }
    }

    @Test
    fun `promoOfferTokenFor yearly returns token when active`() = runTest {
        openAllGates()
        val m = manager()
        advanceUntilIdle()
        assertEquals("promo-token", m.promoOfferTokenFor(BillingManager.PRODUCT_ID_YEARLY))
    }

    @Test
    fun `promoOfferTokenFor monthly returns null even when active`() = runTest {
        openAllGates()
        val m = manager()
        advanceUntilIdle()
        assertNull(m.promoOfferTokenFor(BillingManager.PRODUCT_ID_MONTHLY))
    }

    @Test
    fun `promoOfferTokenFor returns null when hidden`() = runTest {
        val m = manager()
        advanceUntilIdle()
        assertNull(m.promoOfferTokenFor(BillingManager.PRODUCT_ID_YEARLY))
    }
}
```

- [ ] **Step 2: Kompilieren — FAIL** (Klasse existiert nicht)

- [ ] **Step 3: LaunchPromoManager.kt anlegen**

```kotlin
package com.paperless.scanner.data.billing

import com.paperless.scanner.data.config.RemoteConfigManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/** UI-facing launch-promo state. Prices are localized strings straight from Play Billing. */
sealed interface LaunchPromoState {
    data object Hidden : LaunchPromoState
    data class Active(
        val promoPrice: String,
        val regularPrice: String,
        val endEpochMs: Long,
        val offerToken: String
    ) : LaunchPromoState
}

/**
 * Single source of truth for "is the launch promo live for THIS user right now".
 *
 * Active only when ALL four gates hold:
 *  1. Remote Config kill switch on,
 *  2. end date not passed (display gate only — the authoritative gate is the Play offer),
 *  3. Play currently serves the launch50 offer on the yearly plan,
 *  4. the user has no active subscription.
 * Anything else → Hidden (fail-closed).
 *
 * Eagerly shared: SettingsViewModel reads [promoOfferTokenFor] synchronously, so
 * `.value` must be fresh without a collector (WhileSubscribed would pin it).
 *
 * Known limitation: merely passing the end date does not re-emit by itself; the state
 * re-evaluates on the next source emission. Deactivating the Console offer and the
 * kill switch are the real off-switches.
 */
@Singleton
class LaunchPromoManager internal constructor(
    billingManager: BillingManager,
    remoteConfigManager: RemoteConfigManager,
    private val clock: () -> Long,
    private val scope: CoroutineScope
) {
    @Inject
    constructor(
        billingManager: BillingManager,
        remoteConfigManager: RemoteConfigManager
    ) : this(
        billingManager = billingManager,
        remoteConfigManager = remoteConfigManager,
        clock = { System.currentTimeMillis() },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    )

    val state: StateFlow<LaunchPromoState> = combine(
        remoteConfigManager.launchPromoConfig,
        billingManager.launchOffer,
        billingManager.isSubscriptionActive
    ) { config, offer, premiumActive ->
        if (config.enabled && clock() < config.endEpochMs && offer != null && !premiumActive) {
            LaunchPromoState.Active(
                promoPrice = offer.introFormattedPrice,
                regularPrice = offer.regularFormattedPrice,
                endEpochMs = config.endEpochMs,
                offerToken = offer.offerToken
            )
        } else {
            LaunchPromoState.Hidden
        }
    }.stateIn(scope, SharingStarted.Eagerly, LaunchPromoState.Hidden)

    /** Offer token to purchase [productId] with, or null when no promo applies to it. */
    fun promoOfferTokenFor(productId: String): String? {
        val active = state.value as? LaunchPromoState.Active ?: return null
        return if (productId == BillingManager.PRODUCT_ID_YEARLY) active.offerToken else null
    }

    /** Explicit teardown seam (#142 pattern), wired in PaperlessApp.onTerminate. */
    fun destroy() {
        scope.cancel()
    }
}
```

- [ ] **Step 4: PaperlessApp.onTerminate erweitern**

Inject-Feld (neben `remoteConfigManager`) + Import:

```kotlin
    @Inject
    lateinit var launchPromoManager: LaunchPromoManager
```

In `onTerminate()` (Z.88-94), nach `billingManager.destroy()`:

```kotlin
        launchPromoManager.destroy()
```

(Das Injizieren in die Application startet den Eagerly-Combine bewusst beim App-Start.)

- [ ] **Step 5: Tests laufen lassen**

Run: `./gradlew :app:testReleaseUnitTest --tests "com.paperless.scanner.data.billing.LaunchPromoManagerTest"`
Expected: PASS (9 Tests)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/paperless/scanner/data/billing/LaunchPromoManager.kt app/src/main/java/com/paperless/scanner/PaperlessApp.kt app/src/test/java/com/paperless/scanner/data/billing/LaunchPromoManagerTest.kt
git commit -m "feat(billing): add LaunchPromoManager combining RC flags, Play offer and subscription state"
```

---

### Task 7: LaunchPromoViewModel

**Files:**
- Create: `app/src/main/java/com/paperless/scanner/ui/components/promo/LaunchPromoViewModel.kt`
- Test (Create): `app/src/test/java/com/paperless/scanner/ui/components/promo/LaunchPromoViewModelTest.kt`

- [ ] **Step 1: Failing Test schreiben**

Hinweis: `endDateFormatted` NICHT auf einen exakten String asserten (Zeitzonen-Flake) — nur auf „nicht leer".

```kotlin
package com.paperless.scanner.ui.components.promo

import app.cash.turbine.test
import com.paperless.scanner.data.analytics.AnalyticsEvent
import com.paperless.scanner.data.analytics.AnalyticsService
import com.paperless.scanner.data.billing.LaunchPromoManager
import com.paperless.scanner.data.billing.LaunchPromoState
import com.paperless.scanner.data.datastore.TokenManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LaunchPromoViewModelTest {

    private val promoStateFlow = MutableStateFlow<LaunchPromoState>(LaunchPromoState.Hidden)
    private val dismissedFlow = MutableStateFlow(false)

    private val launchPromoManager = mockk<LaunchPromoManager> {
        every { state } returns promoStateFlow
    }
    private val tokenManager = mockk<TokenManager> {
        every { launchPromoBannerDismissed } returns dismissedFlow
        coEvery { setLaunchPromoBannerDismissed() } answers { dismissedFlow.value = true }
    }
    private val analyticsService = mockk<AnalyticsService>(relaxed = true)

    private val activePromo = LaunchPromoState.Active(
        promoPrice = "CHF 19.99",
        regularPrice = "CHF 39.99",
        endEpochMs = 1_750_000_000_000,
        offerToken = "promo-token"
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = LaunchPromoViewModel(launchPromoManager, tokenManager, analyticsService)

    @Test
    fun `banner visible with promo data when active and not dismissed`() = runTest {
        promoStateFlow.value = activePromo
        viewModel().bannerState.test {
            var item = awaitItem()
            if (item is LaunchPromoBannerState.Hidden) item = awaitItem()
            val visible = item as LaunchPromoBannerState.Visible
            assertEquals("CHF 19.99", visible.promoPrice)
            assertEquals("CHF 39.99", visible.regularPrice)
            assertTrue(visible.endDateFormatted.isNotBlank())
        }
    }

    @Test
    fun `banner hidden when previously dismissed`() = runTest {
        promoStateFlow.value = activePromo
        dismissedFlow.value = true
        viewModel().bannerState.test {
            assertEquals(LaunchPromoBannerState.Hidden, awaitItem())
            expectNoEvents()
        }
    }

    @Test
    fun `dismissBanner persists flag, hides banner and logs dismissal`() = runTest {
        promoStateFlow.value = activePromo
        val vm = viewModel()
        vm.bannerState.test {
            var item = awaitItem()
            if (item is LaunchPromoBannerState.Hidden) item = awaitItem()
            assertTrue(item is LaunchPromoBannerState.Visible)
            vm.dismissBanner()
            assertEquals(LaunchPromoBannerState.Hidden, awaitItem())
        }
        coVerify(exactly = 1) { tokenManager.setLaunchPromoBannerDismissed() }
        verify(exactly = 1) {
            analyticsService.trackEvent(AnalyticsEvent.PremiumPromptDismissed(trigger = LAUNCH_PROMO_TRIGGER))
        }
    }

    @Test
    fun `banner impression is logged only once per view model`() = runTest {
        val vm = viewModel()
        vm.onBannerVisible()
        vm.onBannerVisible()
        verify(exactly = 1) { analyticsService.trackEvent(AnalyticsEvent.LaunchPromoBannerShown) }
    }

    @Test
    fun `banner click logs PremiumPromptShown with launch promo trigger`() = runTest {
        viewModel().onBannerClicked()
        verify(exactly = 1) {
            analyticsService.trackEvent(AnalyticsEvent.PremiumPromptShown(trigger = LAUNCH_PROMO_TRIGGER))
        }
    }

    @Test
    fun `sheetPromo maps active promo and ignores dismiss flag`() = runTest {
        promoStateFlow.value = activePromo
        dismissedFlow.value = true
        viewModel().sheetPromo.test {
            var item = awaitItem()
            if (item == null) item = awaitItem()
            assertEquals("CHF 19.99", item!!.promoPrice)
            assertEquals("CHF 39.99", item.regularPrice)
        }
    }
}
```

- [ ] **Step 2: Kompilieren — FAIL**

- [ ] **Step 3: LaunchPromoViewModel.kt anlegen**

```kotlin
package com.paperless.scanner.ui.components.promo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.data.analytics.AnalyticsEvent
import com.paperless.scanner.data.analytics.AnalyticsService
import com.paperless.scanner.data.billing.LaunchPromoManager
import com.paperless.scanner.data.billing.LaunchPromoState
import com.paperless.scanner.data.datastore.TokenManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** Analytics trigger value shared by the banner's impression/click/dismiss events. */
const val LAUNCH_PROMO_TRIGGER = "launch_promo_banner"

sealed interface LaunchPromoBannerState {
    data object Hidden : LaunchPromoBannerState
    data class Visible(
        val promoPrice: String,
        val regularPrice: String,
        val endDateFormatted: String
    ) : LaunchPromoBannerState
}

/** Promo display data for the upgrade sheet (null = no promo live). */
data class LaunchPromoSheetUi(
    val promoPrice: String,
    val regularPrice: String,
    val endDateFormatted: String
)

@HiltViewModel
class LaunchPromoViewModel @Inject constructor(
    launchPromoManager: LaunchPromoManager,
    private val tokenManager: TokenManager,
    private val analyticsService: AnalyticsService
) : ViewModel() {

    private var impressionLogged = false

    /** Banner on Home: promo Active AND not dismissed by the user. */
    val bannerState: StateFlow<LaunchPromoBannerState> = combine(
        launchPromoManager.state,
        tokenManager.launchPromoBannerDismissed
    ) { promo, dismissed ->
        if (promo is LaunchPromoState.Active && !dismissed) {
            LaunchPromoBannerState.Visible(
                promoPrice = promo.promoPrice,
                regularPrice = promo.regularPrice,
                endDateFormatted = formatEndDate(promo.endEpochMs)
            )
        } else {
            LaunchPromoBannerState.Hidden
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LaunchPromoBannerState.Hidden)

    /** Promo display for the upgrade sheet — intentionally ignores the banner dismiss flag. */
    val sheetPromo: StateFlow<LaunchPromoSheetUi?> = launchPromoManager.state
        .map { promo ->
            (promo as? LaunchPromoState.Active)?.let {
                LaunchPromoSheetUi(
                    promoPrice = it.promoPrice,
                    regularPrice = it.regularPrice,
                    endDateFormatted = formatEndDate(it.endEpochMs)
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun onBannerVisible() {
        if (impressionLogged) return
        impressionLogged = true
        analyticsService.trackEvent(AnalyticsEvent.LaunchPromoBannerShown)
    }

    fun onBannerClicked() {
        analyticsService.trackEvent(AnalyticsEvent.PremiumPromptShown(trigger = LAUNCH_PROMO_TRIGGER))
    }

    fun dismissBanner() {
        viewModelScope.launch {
            tokenManager.setLaunchPromoBannerDismissed()
        }
        analyticsService.trackEvent(AnalyticsEvent.PremiumPromptDismissed(trigger = LAUNCH_PROMO_TRIGGER))
    }

    /** Same dd.MM.yyyy format as SettingsViewModel.formatExpiryDate. */
    private fun formatEndDate(endEpochMs: Long): String =
        SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(endEpochMs))
}
```

- [ ] **Step 4: Tests laufen lassen**

Run: `./gradlew :app:testReleaseUnitTest --tests "com.paperless.scanner.ui.components.promo.LaunchPromoViewModelTest"`
Expected: PASS (6 Tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/paperless/scanner/ui/components/promo/ app/src/test/java/com/paperless/scanner/ui/components/promo/
git commit -m "feat(promo): add LaunchPromoViewModel for banner and sheet promo state"
```

---

### Task 8: Strings + Banner-Composable + HomeScreen

**Files:**
- Modify: `app/src/main/res/values/strings.xml` (nach Zeile 697 `premium_trial_14_days`)
- Create: `app/src/main/java/com/paperless/scanner/ui/components/promo/LaunchPromoBanner.kt`
- Modify: `app/src/main/java/com/paperless/scanner/ui/screens/home/HomeScreen.kt`

- [ ] **Step 1: Strings ergänzen**

```xml
    <!-- Launch Promo -->
    <string name="launch_promo_banner_headline">Launch offer</string>
    <string name="launch_promo_banner_body">AI yearly plan %1$s instead of %2$s — until %3$s</string>
    <string name="launch_promo_badge">Launch offer</string>
    <string name="launch_promo_ends">Offer ends %1$s</string>
    <string name="cd_dismiss_promo_banner">Dismiss launch offer banner</string>
    <string name="premium_transparency_note">AI suggestions use cloud AI (Gemini); the subscription covers the API costs. Local rule-based suggestions stay free. Your documents go only to your own server unless you opt in to AI analysis.</string>
```

- [ ] **Step 2: LaunchPromoBanner.kt anlegen**

```kotlin
package com.paperless.scanner.ui.components.promo

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.paperless.scanner.R

/**
 * Dismissible launch-promo banner (Dark Tech Precision Pro: 20dp corners,
 * 1dp PRIMARY border as the promo accent, no elevation).
 */
@Composable
fun LaunchPromoBanner(
    promoPrice: String,
    regularPrice: String,
    endDateFormatted: String,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(width = 1.dp, color = MaterialTheme.colorScheme.primary),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.launch_promo_banner_headline).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.1.em,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.launch_promo_banner_body,
                        promoPrice,
                        regularPrice,
                        endDateFormatted
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.cd_dismiss_promo_banner),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Preview
@Composable
private fun LaunchPromoBannerPreview() {
    MaterialTheme {
        LaunchPromoBanner(
            promoPrice = "CHF 19.99",
            regularPrice = "CHF 39.99",
            endDateFormatted = "09.07.2026",
            onClick = {},
            onDismiss = {}
        )
    }
}
```

- [ ] **Step 3: HomeScreen.kt verdrahten**

(a) Imports ergänzen:

```kotlin
import com.paperless.scanner.ui.components.promo.LaunchPromoBanner
import com.paperless.scanner.ui.components.promo.LaunchPromoBannerState
import com.paperless.scanner.ui.components.promo.LaunchPromoViewModel
```

(b) Signatur: neuen VM-Param ans Ende der bestehenden VM-Liste (nach `trashOverviewViewModel`, Z.64):

```kotlin
    launchPromoViewModel: LaunchPromoViewModel = hiltViewModel(),
```

(c) State sammeln (bei den anderen collectAsState-Zeilen, ~Z.75):

```kotlin
    val launchPromoBanner by launchPromoViewModel.bannerState.collectAsState()
```

(d) Banner-Item in der LazyColumn: NACH dem Item `key = "spacer-after-stats"` (Z.343-345) und VOR dem Processing-Tasks-Block einfügen:

```kotlin
                // Launch promo banner (only for non-premium users while the promo is live)
                (launchPromoBanner as? LaunchPromoBannerState.Visible)?.let { promo ->
                    item(key = "launch-promo") {
                        LaunchedEffect(Unit) { launchPromoViewModel.onBannerVisible() }
                        LaunchPromoBanner(
                            promoPrice = promo.promoPrice,
                            regularPrice = promo.regularPrice,
                            endDateFormatted = promo.endDateFormatted,
                            onClick = {
                                launchPromoViewModel.onBannerClicked()
                                showPremiumUpgradeSheet = true
                            },
                            onDismiss = { launchPromoViewModel.dismissBanner() },
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                    item(key = "spacer-after-promo") {
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
```

- [ ] **Step 4: Kompilieren + Commit**

Run: `./gradlew :app:compileReleaseKotlin` → `BUILD SUCCESSFUL`

```bash
git add app/src/main/res/values/strings.xml app/src/main/java/com/paperless/scanner/ui/components/promo/LaunchPromoBanner.kt app/src/main/java/com/paperless/scanner/ui/screens/home/HomeScreen.kt
git commit -m "feat(home): show dismissible launch-promo banner"
```

---

### Task 9: PremiumUpgradeSheet — Promo-Darstellung

**Files:**
- Modify: `app/src/main/java/com/paperless/scanner/ui/screens/settings/PremiumUpgradeSheet.kt`

Alle 5 Call-Sites bleiben unverändert: der Promo-State kommt über ein internes `hiltViewModel()` mit Default-Param.

- [ ] **Step 1: Imports ergänzen**

```kotlin
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.text.style.TextDecoration
import androidx.hilt.navigation.compose.hiltViewModel
import com.paperless.scanner.ui.components.promo.LaunchPromoViewModel
```

- [ ] **Step 2: Signatur + Promo-State + Plan-Selektion**

Signatur (Z.61-65) ändern auf:

```kotlin
fun PremiumUpgradeSheet(
    onDismiss: () -> Unit,
    onSubscribe: (productId: String) -> Unit,
    onRestore: () -> Unit,
    promoViewModel: LaunchPromoViewModel = hiltViewModel()
) {
```

Die Zeile `var selectedPlan by remember { mutableStateOf("monthly") }` (Z.67) ERSETZEN durch:

```kotlin
    val promo by promoViewModel.sheetPromo.collectAsState()

    // Explicit user selection wins; before any tap a live promo pre-selects yearly.
    var userSelectedPlan by remember { mutableStateOf<String?>(null) }
    val selectedPlan = userSelectedPlan ?: if (promo != null) "yearly" else "monthly"
```

- [ ] **Step 3: Options-Row anpassen** (Z.152-174)

Monthly-`onClick`: `{ selectedPlan = "monthly" }` → `{ userSelectedPlan = "monthly" }`.

Yearly-`SubscriptionOption`-Aufruf ERSETZEN durch:

```kotlin
                SubscriptionOption(
                    title = stringResource(R.string.premium_option_yearly),
                    price = stringResource(R.string.premium_price_yearly, promo?.promoPrice ?: "39.99€"),
                    strikethroughPrice = promo?.let { stringResource(R.string.premium_price_yearly, it.regularPrice) },
                    badge = if (promo != null) {
                        stringResource(R.string.launch_promo_badge)
                    } else {
                        stringResource(R.string.premium_price_yearly_savings)
                    },
                    trial = stringResource(R.string.premium_trial_14_days),
                    selected = selectedPlan == "yearly",
                    onClick = { userSelectedPlan = "yearly" },
                    modifier = Modifier.weight(1f)
                )
```

Direkt NACH der schließenden Klammer der Options-`Row` (vor `Spacer(24.dp)` + Upgrade-Button):

```kotlin
            promo?.let {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.launch_promo_ends, it.endDateFormatted),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
```

- [ ] **Step 4: Transparenz-Hinweis (Spec §5, Community-Erwartung)**

Direkt NACH dem letzten `FeatureItem` (`premium_feature_unlimited`, Z.143-147) und VOR dem `Spacer(32.dp)` einfügen:

```kotlin
            Spacer(modifier = Modifier.height(16.dp))

            // Privacy transparency for the self-hosting audience: cloud AI is opt-in,
            // local suggestions stay free, documents stay on the user's server.
            Text(
                text = stringResource(R.string.premium_transparency_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
```

- [ ] **Step 5: SubscriptionOption um Streichpreis erweitern**

Signatur (Z.264-272): nach `price: String,` einfügen: `strikethroughPrice: String? = null,`

Im Column-Body direkt VOR dem Preis-`Text` (Z.306):

```kotlin
            if (strikethroughPrice != null) {
                Text(
                    text = strikethroughPrice,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    textDecoration = TextDecoration.LineThrough,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
```

- [ ] **Step 6: Kompilieren + Commit**

Run: `./gradlew :app:compileReleaseKotlin` → `BUILD SUCCESSFUL`

```bash
git add app/src/main/java/com/paperless/scanner/ui/screens/settings/PremiumUpgradeSheet.kt
git commit -m "feat(premium): show launch-promo pricing and transparency note in upgrade sheet"
```

---

### Task 10: PremiumSection-Badge + Settings-State

**Files:**
- Modify: `app/src/main/java/com/paperless/scanner/ui/screens/settings/sections/PremiumSection.kt`
- Modify: `app/src/main/java/com/paperless/scanner/ui/screens/settings/SettingsViewModel.kt`
- Modify: `app/src/main/java/com/paperless/scanner/ui/screens/settings/SettingsScreen.kt:79-93`

- [ ] **Step 1: PremiumSection-Param + Badge**

Signatur: nach `premiumExpiryDate: String?,` einfügen: `launchPromoActive: Boolean = false,`

Im Titel-`Row` (nach dem `isPremiumActive`-PRO-Badge-Block, Z.93) einfügen:

```kotlin
                    if (!isPremiumActive && launchPromoActive) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .background(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.launch_promo_badge).uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
```

Im `PremiumSectionInactivePreview` zusätzlich `launchPromoActive = true,` setzen (macht das Badge in der Preview sichtbar).

- [ ] **Step 2: SettingsViewModel**

(a) Imports: `com.paperless.scanner.data.billing.LaunchPromoManager`, `com.paperless.scanner.data.billing.LaunchPromoState`.

(b) `SettingsUiState`: nach `premiumExpiryDate` einfügen: `val launchPromoActive: Boolean = false,`

(c) Konstruktor: nach `premiumFeatureManager` einfügen: `private val launchPromoManager: LaunchPromoManager,`

(d) In `loadSettings()`, nach dem `subscriptionStatus`-Observer-Block (Z.129) einfügen:

```kotlin
            // Observe launch promo state for the PremiumSection badge
            launch {
                launchPromoManager.state.collect { promo ->
                    _uiState.update { it.copy(launchPromoActive = promo is LaunchPromoState.Active) }
                }
            }
```

- [ ] **Step 3: SettingsScreen**

Im `PremiumSection(...)`-Aufruf (Z.79) nach `premiumExpiryDate = uiState.premiumExpiryDate,` einfügen:

```kotlin
            launchPromoActive = uiState.launchPromoActive,
```

- [ ] **Step 4: Kompilieren + Commit**

Run: `./gradlew :app:compileReleaseKotlin` → `BUILD SUCCESSFUL`

```bash
git add app/src/main/java/com/paperless/scanner/ui/screens/settings/sections/PremiumSection.kt app/src/main/java/com/paperless/scanner/ui/screens/settings/SettingsViewModel.kt app/src/main/java/com/paperless/scanner/ui/screens/settings/SettingsScreen.kt
git commit -m "feat(settings): show launch-promo badge in premium section"
```

---

### Task 11: Purchase-Routing + PremiumSubscribed-Logging

**Files:**
- Modify: `app/src/main/java/com/paperless/scanner/ui/screens/settings/SettingsViewModel.kt:255-257`

Kein eigener Unit-Test (siehe „Bewusste Test-Lücken"); `promoOfferTokenFor` (Task 6) und der offerToken-Pfad in `launchPurchaseFlow` (Task 4) sind getestet.

- [ ] **Step 1: `launchPurchaseFlow` ERSETZEN durch**

```kotlin
    /**
     * Launches the Play purchase flow. While the launch promo is active and the user
     * buys the yearly plan, the discounted launch50 offer is purchased instead of the
     * default offer. Logs [AnalyticsEvent.PremiumSubscribed] on success (GDPR-gated
     * inside AnalyticsService).
     */
    suspend fun launchPurchaseFlow(activity: android.app.Activity, productId: String): PurchaseResult {
        val promoOfferToken = launchPromoManager.promoOfferTokenFor(productId)
        val result = billingManager.launchPurchaseFlow(activity, productId, promoOfferToken)
        if (result is PurchaseResult.Success) {
            analyticsService.trackEvent(
                AnalyticsEvent.PremiumSubscribed(
                    plan = if (productId == BillingManager.PRODUCT_ID_MONTHLY) "monthly" else "yearly",
                    offerTag = if (promoOfferToken != null) BillingManager.LAUNCH_PROMO_OFFER_TAG else "none"
                )
            )
        }
        return result
    }
```

- [ ] **Step 2: Kompilieren + voller Testlauf + Commit**

Run: `./gradlew :app:testReleaseUnitTest`
Expected: PASS (kompletter Suite-Lauf, kein Flake)

```bash
git add app/src/main/java/com/paperless/scanner/ui/screens/settings/SettingsViewModel.kt
git commit -m "feat(premium): route yearly purchases through launch50 offer and log subscription event"
```

---

### Task 12: Runbook + Community-Entwürfe

**Files:**
- Create: `docs/LAUNCH_PROMO_RUNBOOK.md`

- [ ] **Step 1: Runbook anlegen** — Inhalt (vollständig übernehmen):

```markdown
# Launch-Promo Runbook — 50% aufs erste Jahr Premium-KI

Spec: docs/superpowers/specs/2026-06-11-launch-promo-design.md

## 1. Play Console: Offer anlegen (einmalig, VOR dem Go-Live)

1. Play Console → Monetarisierung → Produkte → Abos → `paperless_ai_yearly` → Base Plan öffnen.
2. „Angebot hinzufügen" (Offer) auf dem Base Plan:
   - **Offer-ID:** `launch-promo-2026` (frei wählbar)
   - **Offer-Tag:** `launch50`  ← MUSS exakt so heißen (App matcht auf diesen Tag)
   - **Eligibility:** Developer determined
   - **Phasen:**
     1. Free trial — 14 Tage
     2. Single payment — 1 Jahr — 50% Rabatt (€19.99 / CHF-Äquivalent prüfen)
   - Danach greift automatisch der reguläre Base-Plan-Preis (€39.99, Auto-Renew).
3. Offer **NICHT aktivieren** — erst beim Go-Live (Schritt 3).
4. Das bestehende Default-Offer (14d Trial) NICHT anfassen — es greift wieder nach Promo-Ende.

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

## 4. Promo-Ende-Checkliste (nach 4 Wochen)

1. [ ] Play Console: Offer `launch50` deaktivieren (autoritativer Schalter).
2. [ ] Firebase RC: `launch_promo_enabled` = `false` → veröffentlichen.
3. [ ] App zeigt automatisch wieder Regulärpreise (kein Release nötig).
4. [ ] Conversion auswerten: Firebase Analytics `premium_prompt_shown{trigger=launch_promo_banner}` → `premium_subscribed{offer_tag=launch50}`.

## 5. Gerätetest (Pixel 8, License Tester)

Voraussetzung: Test-Konto ist in Play Console → Einstellungen → Lizenztests eingetragen.

1. Internal-Build installieren, mit Lizenztester-Konto angemeldet sein.
2. RC-Werte gesetzt + Offer aktiv → App-Kaltstart.
3. ERWARTET: Home zeigt Banner „LAUNCH OFFER … until <Datum>" mit echten lokalisierten Preisen.
4. Banner-Tap → Sheet: Jahres-Plan vorselektiert, Streichpreis + „Launch offer"-Badge + „Offer ends <Datum>".
5. Settings → Premium-Zeile zeigt „LAUNCH OFFER"-Badge.
6. Kauf im Test-Modus durchziehen → Erfolgsdialog; Banner und Badges verschwinden (Gate 4).
7. Banner-Dismiss-Test (vor Kauf): X antippen → Banner weg, bleibt nach App-Neustart weg; Sheet zeigt Promo weiterhin.
8. Kill-Switch-Test: RC `launch_promo_enabled=false` veröffentlichen → App-Neustart (RC-Cache 1h beachten oder App-Daten löschen) → kein Banner.

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

Regeln: zuerst Sub-Regeln zu Self-Promo prüfen; auf Kommentare schnell und technisch ehrlich antworten; Promo ist Fußnote, nicht Headline.

**GitHub Discussions (paperless-ngx, Show and tell):** gleiche Substanz, kürzer, mit Screenshots.

## 7. Wiki-Listing (Related Projects)

GitHub-Konto → https://github.com/paperless-ngx/paperless-ngx/wiki/Related-Projects → „Edit" (Wiki ist offen editierbar; falls gesperrt: Request via Discussion). Eintrag unter „Android":

> - [Paperless Scanner](https://play.google.com/store/apps/details?id=com.paperless.scanner) by Marcus Martini — Native Android scanner app for Paperless-ngx: MLKit document scanning, offline upload queue, widgets, optional on-device + cloud-AI tag/title suggestions. (Paid app, ~1 CHF)

## 8. Play-Listing-Ergänzung (EN-Basistext)

Kurzbeschreibung ergänzen um: "Zero-setup AI suggestions (optional): titles, tags & correspondents at scan time — no server add-ons, no Docker. Local rule-based suggestions are always free. Documents go only to YOUR server; AI analysis is opt-in."
```

- [ ] **Step 2: Commit**

```bash
git add docs/LAUNCH_PROMO_RUNBOOK.md
git commit -m "docs: add launch promo runbook (console setup, go-live, community drafts)"
```

---

### Task 13: Validierung, Review-Gates, PR

- [ ] **Step 1: Vorbedingung prüfen** — kein aktiver Auto-Deploy:

Run: `gh run list --workflow=auto-deploy-internal.yml --limit 1`
Expected: Status `completed` (sonst warten!)

- [ ] **Step 2: Volle lokale CI**

Run: `./scripts/validate-ci.sh`
Expected: ALLE Phasen grün (testReleaseUnitTest + assembleRelease + lintRelease + String-Checks). Bei Fehlern: STOPP, fixen, erneut.

- [ ] **Step 3: codex review (hartes Gate)**

Via codex-Skill („codex review") — MCP-freies Home + `-c 'sandbox_mode="danger-full-access"'` (Windows), neue Dateien vorher `git add`. Findings fixen → CI erneut grün → erneut codex, bis PASS.

- [ ] **Step 4: Changelogs ERST JETZT erstellen** (versionCode-Drift vermeiden — Memory `feedback_changelog_version_alignment`):

`VERSION_PATCH` in `version.properties` auf origin/main prüfen → nächste Version = PATCH+1 → `versionCode = MAJOR*10000 + MINOR*100 + PATCH`. Dateien `fastlane/metadata/android/de-DE/changelogs/<versionCode>.txt` + `en-US/.../<versionCode>.txt`, je ≤500 Zeichen, z. B.:

DE: `Version 1.5.<X>:\n\n✨ Neu:\n- Launch-Angebot: 50% auf das erste Jahr Premium-KI\n- Angebots-Banner auf dem Startbildschirm\n\n🔧 Verbesserungen:\n- Echte lokalisierte Abo-Preise aus Google Play`
EN: `Version 1.5.<X>:\n\n✨ New:\n- Launch offer: 50% off the first year of Premium AI\n- Offer banner on the home screen\n\n🔧 Improvements:\n- Real localized subscription prices from Google Play`

Mit `wc -m` prüfen (≤500), committen.

- [ ] **Step 5: Push + PR**

```bash
git push -u origin feature/launch-promo-premium
gh pr create --title "feat: launch promo — 50% off first year of premium AI (offer launch50 + Remote Config)" --body "..."
```

PR-Body: Spec + Runbook verlinken, Gate-Architektur kurz erklären, Test-Lücken-Rationale aus diesem Plan zitieren, `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.

- [ ] **Step 6: CodeRabbit-Runde** — alle actionable Findings fixen oder begründet ablehnen (vorher `.coderabbit.yaml` prüfen). Merge erst nach CI-grün + codex-PASS + CodeRabbit clean. **Kein Auto-Merge.**

- [ ] **Step 7: Nach Merge** — Auto-Deploy Internal beobachten (`gh run watch`), danach Gerätetest nach Runbook §5. Production-Promote + Offer-Aktivierung macht der User (Runbook §3).
