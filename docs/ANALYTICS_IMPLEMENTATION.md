# Firebase Analytics Implementation Guide

Complete guide for implementing AI usage tracking in the Android app.

---

## 📊 Overview

Firebase Analytics tracks AI feature usage, costs, and user behavior for:
- **Business monitoring** - Monthly profit/cost reports
- **Usage patterns** - Identify popular features
- **Cost optimization** - Track API spending per feature
- **User segmentation** - Free vs Premium behavior

**All data is:**
- ✅ Anonymized (no PII)
- ✅ GDPR-compliant
- ✅ Automatically exported to BigQuery
- ✅ Disabled by default (opt-in)

---

## 🏗️ Architecture

```
┌──────────────────────────────────────────────────┐
│            App Layer (ViewModel/UI)              │
│                                                  │
│  • AI Feature Usage                              │
│  • Premium Subscriptions                         │
│  • User Actions                                  │
└──────────────────┬───────────────────────────────┘
                   │
                   ▼
┌──────────────────────────────────────────────────┐
│         AnalyticsService (Hilt Singleton)        │
│                                                  │
│  • trackAiFeatureUsage()                         │
│  • setSubscriptionStatus()                       │
│  • setAiCallsThisMonth()                         │
└──────────────────┬───────────────────────────────┘
                   │
                   ▼
┌──────────────────────────────────────────────────┐
│            Firebase Analytics SDK                │
│                                                  │
│  Events → BigQuery → Monthly Reports             │
└──────────────────────────────────────────────────┘
```

---

## 🚀 Quick Start

### 1. Inject AnalyticsService

```kotlin
@HiltViewModel
class MyViewModel @Inject constructor(
    private val analyticsService: AnalyticsService
) : ViewModel() {
    // ...
}
```

### 2. Track AI Feature Usage

```kotlin
// When AI feature is used successfully
analyticsService.trackAiFeatureUsage(
    featureType = "analyze_image",
    inputTokens = 1500,
    outputTokens = 200,
    subscriptionType = "monthly"
)
```

### 3. Update User Properties

```kotlin
// When subscription status changes
analyticsService.setSubscriptionStatus("monthly") // or "yearly", "free"

// After each AI call (for usage limits)
analyticsService.setAiCallsThisMonth(currentMonthCount)
```

---

## 📝 AI Feature Types

Use these standardized feature type names:

| Feature Type | Description | Typical Tokens |
|--------------|-------------|----------------|
| `analyze_image` | Image OCR analysis | 1500 in, 200 out |
| `analyze_pdf` | PDF content analysis | 2000 in, 300 out |
| `suggest_tags` | AI-generated tag suggestions | 1000 in, 50 out |
| `generate_title` | Document title generation | 800 in, 20 out |
| `generate_summary` | Document summary | 1200 in, 150 out |

---

## 🔢 Cost Calculation

### Automatic Cost Tracking

The `AiCostCalculator` automatically calculates costs using **Gemini Flash 1.5** pricing:
- **Input:** $0.30 per 1M tokens
- **Output:** $2.50 per 1M tokens

```kotlin
// Cost is calculated automatically
val cost = AiCostCalculator.calculateCost(
    inputTokens = 1500,
    outputTokens = 200
)
// Returns: ~0.00095 USD (~$0.001)
```

### Estimating Costs

```kotlin
// Before making API call
val estimatedCost = AiCostCalculator.calculateInputCost(inputTokens = 1500)
// Returns: ~0.00045 USD

// Get average cost per call
val avgCost = AiCostCalculator.getAverageCostPerCall()
// Returns: ~0.00095 USD (based on 1500 input + 200 output)
```

---

## 📖 Usage Examples

### Example 1: Image Analysis

```kotlin
@HiltViewModel
class UploadViewModel @Inject constructor(
    private val analyticsService: AnalyticsService,
    private val aiService: AiService,
    private val subscriptionManager: SubscriptionManager
) : ViewModel() {

    suspend fun analyzeImage(imageUri: Uri) {
        val subscriptionType = subscriptionManager.getSubscriptionType() // "free", "monthly", "yearly"

        try {
            // Make AI API call
            val response = aiService.analyzeImage(imageUri)

            // Track successful usage
            analyticsService.trackAiFeatureUsage(
                featureType = "analyze_image",
                inputTokens = response.inputTokens,
                outputTokens = response.outputTokens,
                subscriptionType = subscriptionType
            )

            // Update monthly call count
            val newCount = incrementMonthlyCallCount()
            analyticsService.setAiCallsThisMonth(newCount)

        } catch (e: Exception) {
            // Track failed usage
            analyticsService.trackAiFeatureFailure(
                featureType = "analyze_image",
                inputTokens = 1500, // Estimate if request was sent
                subscriptionType = subscriptionType
            )

            analyticsService.logError(e, "Image analysis failed")
        }
    }
}
```

### Example 2: Tag Suggestions

```kotlin
@HiltViewModel
class TagSuggestionViewModel @Inject constructor(
    private val analyticsService: AnalyticsService,
    private val aiService: AiService
) : ViewModel() {

    suspend fun getSuggestedTags(documentText: String): List<String> {
        val response = aiService.suggestTags(documentText)

        // Track AI usage
        analyticsService.trackAiFeatureUsage(
            featureType = "suggest_tags",
            inputTokens = response.inputTokens,
            outputTokens = response.outputTokens,
            subscriptionType = "monthly"
        )

        return response.tags
    }

    fun onTagAccepted(tag: String) {
        // Track when user accepts a suggestion
        analyticsService.trackEvent(
            AnalyticsEvent.AiSuggestionAccepted(
                featureType = "suggest_tags",
                suggestionCount = 1
            )
        )
    }

    fun onTagRejected() {
        analyticsService.trackEvent(
            AnalyticsEvent.AiSuggestionRejected(
                featureType = "suggest_tags"
            )
        )
    }
}
```

### Example 3: Premium Subscription

```kotlin
@HiltViewModel
class PremiumViewModel @Inject constructor(
    private val analyticsService: AnalyticsService,
    private val billingManager: BillingManager
) : ViewModel() {

    fun showPremiumPrompt(trigger: String) {
        // Track when upgrade prompt is shown
        analyticsService.trackEvent(
            AnalyticsEvent.PremiumPromptShown(trigger = trigger)
        )
    }

    fun onPremiumPromptDismissed(trigger: String) {
        analyticsService.trackEvent(
            AnalyticsEvent.PremiumPromptDismissed(trigger = trigger)
        )
    }

    suspend fun onSubscriptionPurchased(plan: String) {
        // Update user property
        analyticsService.setSubscriptionStatus(plan) // "monthly" or "yearly"

        // Track purchase event
        val priceUsd = when (plan) {
            "monthly" -> 1.99
            "yearly" -> 14.99
            else -> 0.0
        }

        analyticsService.trackEvent(
            AnalyticsEvent.PremiumSubscribed(
                plan = plan,
                priceUsd = priceUsd
            )
        )
    }
}
```

### Example 4: Usage Limits

```kotlin
@HiltViewModel
class AiLimitViewModel @Inject constructor(
    private val analyticsService: AnalyticsService,
    private val usageRepository: AiUsageRepository
) : ViewModel() {

    suspend fun checkUsageLimit(): UsageLimitStatus {
        val monthlyCallCount = usageRepository.getMonthlyCallCount()

        return when {
            monthlyCallCount >= 300 -> {
                // Hard limit reached
                analyticsService.trackEvent(
                    AnalyticsEvent.AiUsageLimitReached(
                        monthlyCallCount = monthlyCallCount
                    )
                )
                UsageLimitStatus.Blocked
            }

            monthlyCallCount >= 200 -> {
                // Soft limit 200
                analyticsService.trackEvent(
                    AnalyticsEvent.AiUsageLimitWarning(
                        currentCalls = monthlyCallCount,
                        limitType = "soft_200"
                    )
                )
                UsageLimitStatus.Warning
            }

            monthlyCallCount >= 100 -> {
                // Soft limit 100
                analyticsService.trackEvent(
                    AnalyticsEvent.AiUsageLimitWarning(
                        currentCalls = monthlyCallCount,
                        limitType = "soft_100"
                    )
                )
                UsageLimitStatus.Info
            }

            else -> UsageLimitStatus.Allowed
        }
    }
}
```

---

## 📊 User Properties

Set these properties for user segmentation:

### Subscription Status

```kotlin
// When user subscribes
analyticsService.setSubscriptionStatus("monthly")

// When subscription expires
analyticsService.setSubscriptionStatus("free")

// When user upgrades to yearly
analyticsService.setSubscriptionStatus("yearly")
```

### AI Usage Tracking

```kotlin
// After each AI call
val currentCount = usageRepository.getMonthlyCallCount()
analyticsService.setAiCallsThisMonth(currentCount)

// This automatically sets:
// - ai_calls_this_month: "45"
// - ai_heavy_user: "false" (or "true" if >100)
```

---

## 🔒 Privacy & GDPR Compliance

### Data Collection Rules

**✅ Allowed:**
- Anonymized usage metrics (call counts, feature types)
- Aggregated costs (token counts, estimated USD)
- Subscription type (free, monthly, yearly)
- Generic timestamps (month, day)

**❌ NOT Allowed:**
- User IDs (use Firebase's `user_pseudo_id`)
- Email addresses
- Document content or titles
- Personal information
- IP addresses (Firebase handles this)

### User Consent

```kotlin
// Check consent before enabling analytics
if (userPreferences.hasAnalyticsConsent) {
    analyticsService.setEnabled(true)
} else {
    analyticsService.setEnabled(false)
}

// When user grants consent
fun onAnalyticsConsentGranted() {
    analyticsService.setEnabled(true)
    analyticsService.trackEvent(
        AnalyticsEvent.AnalyticsConsentChanged(granted = true)
    )
}

// When user revokes consent
fun onAnalyticsConsentRevoked() {
    analyticsService.trackEvent(
        AnalyticsEvent.AnalyticsConsentChanged(granted = false)
    )
    analyticsService.setEnabled(false)
}
```

---

## 🧪 Testing

### Local Testing

```kotlin
@Test
fun `test AI cost calculation`() {
    val cost = AiCostCalculator.calculateCost(
        inputTokens = 1500,
        outputTokens = 200
    )

    // Expected: (1500/1M * 0.30) + (200/1M * 2.50)
    //         = 0.00045 + 0.0005
    //         = 0.00095
    assertEquals(0.00095, cost, 0.00001)
}

@Test
fun `test analytics event creation`() {
    val event = AnalyticsEvent.AiFeatureUsed(
        featureType = "analyze_image",
        inputTokens = 1500,
        outputTokens = 200,
        estimatedCostUsd = 0.001,
        subscriptionType = "monthly",
        success = true
    )

    assertEquals("ai_feature_used", event.name)
    assertEquals("analyze_image", event.params["feature_type"])
    assertEquals(1500, event.params["input_tokens"])
}
```

### Debug Logging

Enable verbose logging in `AnalyticsService`:
```
D/AnalyticsService: Event tracked: ai_feature_used with params: {feature_type=analyze_image, input_tokens=1500, ...}
D/AnalyticsService: User property set: subscription_status = monthly
```

### Firebase DebugView

1. Enable debug mode:
   ```bash
   adb shell setprop debug.firebase.analytics.app com.paperless.scanner.debug
   ```

2. Open Firebase Console → Analytics → DebugView
3. See events in real-time
4. Verify parameters and user properties

---

## 📈 BigQuery Schema

Events are exported to BigQuery with this structure:

```sql
SELECT
  event_name,
  event_timestamp,
  user_pseudo_id,
  (SELECT value.string_value FROM UNNEST(event_params) WHERE key = 'feature_type') as feature_type,
  (SELECT value.int_value FROM UNNEST(event_params) WHERE key = 'input_tokens') as input_tokens,
  (SELECT value.int_value FROM UNNEST(event_params) WHERE key = 'output_tokens') as output_tokens,
  (SELECT value.double_value FROM UNNEST(event_params) WHERE key = 'estimated_cost_usd') as cost_usd,
  (SELECT value.string_value FROM UNNEST(user_properties) WHERE key = 'subscription_status') as subscription
FROM `paperless-scanner.analytics_*.events_*`
WHERE event_name = 'ai_feature_used'
  AND _TABLE_SUFFIX BETWEEN '20260101' AND '20260131'
LIMIT 100
```

---

## 🐛 Troubleshooting

### Events Not Showing in Firebase Console

**Check:**
1. Analytics is enabled: `analyticsService.isAnalyticsEnabled()`
2. User granted consent
3. `google-services.json` is present in `app/`
4. Firebase project ID matches
5. Wait 24 hours for data to appear in BigQuery

### Cost Calculations Wrong

**Verify:**
1. Token counts are accurate (check AI response)
2. Using correct pricing constants in `AiCostCalculator`
3. Check for integer overflow (use `Double` not `Int`)

### User Properties Not Set

**Ensure:**
1. Analytics is enabled before setting properties
2. Property names match exactly (case-sensitive)
3. Values are strings (Firebase limitation)
4. Not setting PII (will be filtered out)

---

## 📚 Related Documentation

| Document | Purpose |
|----------|---------|
| **ANALYTICS_SETUP.md** | BigQuery & Looker Studio setup |
| **MONTHLY_REPORT_DEPLOYMENT.md** | Cloud Functions deployment |
| **ANALYTICS_IMPLEMENTATION_SUMMARY.md** | System architecture overview |

---

## ✅ Implementation Checklist

Before deploying AI features with analytics:

- [ ] AnalyticsService injected in all ViewModels
- [ ] `trackAiFeatureUsage()` called after every AI API call
- [ ] `trackAiFeatureFailure()` called on errors
- [ ] Subscription status updated on purchase/expiry
- [ ] Monthly call count tracked and updated
- [ ] User consent check before enabling analytics
- [ ] No PII in event parameters
- [ ] Debug logging tested
- [ ] Firebase DebugView verified
- [ ] Unit tests for cost calculations
- [ ] BigQuery export enabled (wait 24h)
- [ ] Monthly report Cloud Function deployed

---

## 💡 Best Practices

### DO:
- ✅ Track every AI feature usage (success and failure)
- ✅ Update user properties immediately after changes
- ✅ Use standardized feature type names
- ✅ Calculate costs accurately with actual token counts
- ✅ Respect user consent for analytics
- ✅ Test with Firebase DebugView before release

### DON'T:
- ❌ Track PII or document content
- ❌ Hardcode subscription types (use repository/manager)
- ❌ Skip tracking failures (important for error rates)
- ❌ Enable analytics without user consent
- ❌ Use custom event names (stick to predefined)
- ❌ Forget to update monthly call count

---

## 🎯 Success Metrics

**Target Metrics:**
- 100% of AI calls tracked
- <1% event loss rate
- <5% cost calculation variance
- 24-hour BigQuery export delay
- 99.9% analytics uptime

**Monthly Report Metrics:**
- Premium user count
- Total AI calls
- Average calls per user
- Total API costs
- Revenue vs costs
- Profit margin %
