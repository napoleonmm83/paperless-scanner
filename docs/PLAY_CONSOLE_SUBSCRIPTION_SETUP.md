# Google Play Console - Subscription Setup Guide

## Prerequisites

- ✅ Google Play Console Developer Account
- ✅ Merchant Account verified (required for in-app purchases)
- ✅ Bank account linked and verified
- ✅ Tax information submitted

If any of these are missing, complete them in Play Console → Settings → Developer account → Payment settings.

---

## Step 1: Navigate to Subscription Products

1. Open [Google Play Console](https://play.google.com/console)
2. Select your app: **Paperless Scanner**
3. Go to **Monetize** → **Products** → **Subscriptions**
4. Click **Create subscription**

---

## Step 2: Create Monthly Subscription

### Basic Details

| Field | Value |
|-------|-------|
| **Product ID** | `paperless_ai_monthly` |
| **Name** | Premium AI Assistant (Monthly) |
| **Description** | Unlock AI-powered document analysis with automatic tag suggestions, title extraction, and correspondent detection. |

### Subscription Settings

| Setting | Value |
|---------|-------|
| **Billing period** | 1 Month |
| **Auto-renewing** | Yes (default) |
| **Grace period** | 3 days |
| **Account hold** | 3 days (optional) |

### Base Plan

1. Click **Add base plan**
2. Select **Auto-renewing**
3. Configure billing period: **Monthly**

### Pricing

| Region | Price | Notes |
|--------|-------|-------|
| **Eurozone (EUR)** | €3.99 | Base price |
| **United States (USD)** | $4.49 | ~€4.00 equivalent |
| **United Kingdom (GBP)** | £3.99 | ~€4.70 equivalent |
| **Other regions** | Auto-converted | Play Console suggests prices |

**Recommendation:** Use Play Console's "Auto-convert" feature for other countries to maintain price parity.

### Free Trial Offer

1. In the **Base plan**, click **Add offer**
2. **Offer type**: Free trial
3. **Offer ID**: `monthly_trial_7d` (custom ID, not user-facing)
4. **Trial duration**: 7 days
5. **Eligibility**: New subscribers only
6. **Phases:**
   - Phase 1: 7 days at €0.00 (Trial)
   - Phase 2: Ongoing at €3.99/month (Regular price)

**Important:** Set offer as **Active** and **Default** so it's automatically applied to new subscribers.

### Backward Compatibility

- **App targeting SDK ≥28?** Yes (Android 9+)
- **Backward compatibility**: Not needed (Min SDK 26)

### Save & Activate

1. Click **Save** (bottom right)
2. Click **Activate** once all details are correct
3. Status should change to **Active**

---

## Step 3: Create Yearly Subscription

Repeat Step 2 with the following changes:

### Basic Details

| Field | Value |
|-------|-------|
| **Product ID** | `paperless_ai_yearly` |
| **Name** | Premium AI Assistant (Yearly) |
| **Description** | Unlock AI-powered document analysis for a full year. Save 37% compared to monthly (€29.99/year vs €47.88/year). |

### Subscription Settings

| Setting | Value |
|---------|-------|
| **Billing period** | 1 Year (12 months) |
| **Auto-renewing** | Yes (default) |
| **Grace period** | 3 days |

### Pricing

| Region | Price | Savings vs Monthly |
|--------|-------|-------------------|
| **Eurozone (EUR)** | €29.99 | 37% (€17.89 saved) |
| **United States (USD)** | $33.99 | ~37% |
| **United Kingdom (GBP)** | £29.99 | ~37% |

### Free Trial Offer

1. **Offer ID**: `yearly_trial_14d`
2. **Trial duration**: 14 days (longer than monthly to incentivize yearly)
3. **Phases:**
   - Phase 1: 14 days at €0.00 (Trial)
   - Phase 2: Ongoing at €29.99/year

**Pro Tip:** 14-day trial for yearly plan has higher conversion rates than 7-day trials.

### Save & Activate

1. Click **Save**
2. Click **Activate**
3. Verify status: **Active**

---

## Step 4: Configure Grace Period & Account Hold

### Grace Period (Recommended)

When a payment fails, give users **3 days** to update their payment method while retaining access.

**Why 3 days?**
- Industry standard
- Balances user experience with revenue protection
- Google recommends 3-7 days

**Configuration:**
1. Go to **Monetize** → **Subscriptions** → Select product
2. Scroll to **Grace period**
3. Set to **3 days**
4. Save

### Account Hold (Optional)

After grace period expires, put account on hold for another **3 days** (access revoked, but subscription not canceled).

**Recommendation:** Enable this to reduce involuntary churn.

---

## Step 5: Configure Subscription Benefits

### Add Benefits Description

In Play Console, add user-facing benefits:

1. Go to **Subscription details** → **Benefits**
2. Add the following:

```
✓ AI-powered tag suggestions
✓ Automatic title extraction
✓ Correspondent detection
✓ Document type classification
✓ Unlimited AI analyses
✓ Priority support
```

These appear in Play Store subscription management UI.

---

## Step 6: Test Subscriptions

### Create Test Accounts

1. Go to **Settings** → **License testing**
2. Add test Gmail accounts (comma-separated):
   ```
   your.test.email@gmail.com,
   another.test@gmail.com
   ```
3. **License response**: Select **RESPOND_NORMALLY** (simulates real purchases)

### Test Purchase Flow

1. Install app on test device using test Gmail account
2. Navigate to **Settings** → **Premium**
3. Click **Upgrade to Premium**
4. Select **Monthly** or **Yearly**
5. Complete purchase flow
6. **Expected result:**
   - Google Play billing dialog appears
   - Shows **Free trial** (7 or 14 days)
   - Shows price after trial
   - No actual charge (test mode)

### Verify in Play Console

1. Go to **Monetize** → **Orders & subscriptions**
2. Filter by test account email
3. Verify test subscription appears
4. Status should be **Trial** (Active)

### Test Scenarios

| Scenario | How to Test | Expected Behavior |
|----------|-------------|------------------|
| **Purchase** | Complete checkout | Subscription activates immediately (Trial starts) |
| **Trial end** | Wait 7/14 days OR use time-manipulation tools | Subscription converts to paid (test cards not charged) |
| **Restore** | Uninstall → Reinstall → Tap "Restore Purchases" | Subscription recognized and reactivated |
| **Cancel** | Cancel via Google Play | Subscription remains active until end of period |
| **Payment failure** | Not testable with license testing | Grace period activates in production |

---

## Step 7: Production Readiness Checklist

Before releasing to production, verify:

- [ ] Both products (`paperless_ai_monthly` & `paperless_ai_yearly`) are **Active**
- [ ] Prices are correct in all major markets (EUR, USD, GBP)
- [ ] Trial offers are **Active** and set as **Default**
- [ ] Grace period configured (3 days recommended)
- [ ] Benefits description added
- [ ] Test purchases completed successfully
- [ ] App code uses correct Product IDs (`paperless_ai_monthly`, `paperless_ai_yearly`)
- [ ] Billing Library dependency up to date (7.1.1+)
- [ ] `BillingManager.initialize()` called in `Application.onCreate()`
- [ ] Privacy Policy and Terms of Service include subscription details
- [ ] Play Store listing mentions Premium features (optional but recommended)

---

## Step 8: Subscription Management URLs

### User-Facing URLs

Provide these links in your app for subscription management:

**Cancel/Manage Subscription:**
```
https://play.google.com/store/account/subscriptions?package=com.paperless.scanner
```

**Subscription Help Center:**
```
https://support.google.com/googleplay/answer/7018481
```

### Deep Link from App

In Settings screen, add a "Manage Subscription" button:

```kotlin
val context = LocalContext.current
val intent = Intent(Intent.ACTION_VIEW).apply {
    data = Uri.parse("https://play.google.com/store/account/subscriptions?package=${context.packageName}&sku=paperless_ai_monthly")
}
context.startActivity(intent)
```

---

## Common Issues & Troubleshooting

### Issue: "Product not found" in app

**Cause:** Product ID mismatch between app and Play Console.

**Fix:**
1. Verify Product IDs in Play Console match code exactly:
   - `paperless_ai_monthly`
   - `paperless_ai_yearly`
2. Check product status is **Active** (not Draft)
3. Wait 2-4 hours after activation for Google's servers to sync

### Issue: "This item isn't available in your country"

**Cause:** Product not available in user's region.

**Fix:**
1. Go to **Monetize** → **Subscriptions** → Select product
2. Click **Pricing** → **Add countries**
3. Add user's country or use "All countries"

### Issue: Trial not showing

**Cause:** Trial offer not set as default.

**Fix:**
1. Go to Product → **Offers**
2. Ensure trial offer is marked **Default**
3. Verify offer status is **Active**

### Issue: "Merchant account not configured"

**Cause:** Bank details or tax info missing.

**Fix:**
1. Go to **Settings** → **Developer account** → **Payment settings**
2. Complete all required fields:
   - Bank account details
   - Tax information
   - Business address
3. Wait for verification (1-3 business days)

---

## Pricing Strategy Notes

### Why €3.99/month?

**Cost Analysis:**
- AI cost per scan: ~€0.001 (Gemini Flash)
- Average user: 30 scans/month = €0.03 AI cost
- Heavy user: 100 scans/month = €0.10 AI cost
- Google Play fee (15%): €0.60
- **Net profit:** €3.39 → 95% margin (even for heavy users)

### Why €29.99/year?

- Monthly equivalent: €2.50/month (37% discount)
- Incentivizes annual commitment
- Reduces churn (lower cancellation rate)
- Improves LTV (Lifetime Value)
- Industry standard: 20-40% discount for annual plans

### Alternative Pricing Tiers (Future)

If you want to add more tiers later:

| Tier | Price | Features |
|------|-------|----------|
| **Free** | €0 | Manual metadata entry only |
| **Premium** | €3.99/mo | AI suggestions (current plan) |
| **Premium Plus** | €7.99/mo | AI + OCR enhancement + Paperless-GPT integration |

---

## Reference Links

- [Google Play Billing Documentation](https://developer.android.com/google/play/billing)
- [Subscription Pricing Best Practices](https://developer.android.com/google/play/billing/subscriptions)
- [Play Console Help](https://support.google.com/googleplay/android-developer/answer/140504)
- [Billing Library Release Notes](https://developer.android.com/google/play/billing/release-notes)

---

## Next Steps

After completing Play Console setup:

1. ✅ Test purchases with license testing accounts
2. ✅ Update app code to handle subscription status
3. ✅ Test restore purchases flow
4. ✅ Add subscription management links to Settings
5. ✅ Update Privacy Policy with subscription terms
6. ✅ Beta test with 10-20 users
7. ✅ Release to production

**Related Documentation:**
- `docs/BILLING_TESTING.md` - Testing guide for subscriptions
- `docs/PRIVACY_POLICY.md` - Privacy policy (update with subscription terms)
- `docs/TERMS_OF_SERVICE.md` - Terms of service (update with refund policy)
