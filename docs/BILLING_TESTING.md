# Billing & Subscription Testing Guide

Complete guide for testing Premium subscription features before production release.

---

## Table of Contents

1. [Setup Test Environment](#setup-test-environment)
2. [Test Scenarios](#test-scenarios)
3. [Local Testing](#local-testing)
4. [License Testing (Play Console)](#license-testing-play-console)
5. [Internal Testing Track](#internal-testing-track)
6. [Beta Testing](#beta-testing)
7. [Common Issues](#common-issues)

---

## Setup Test Environment

### 1. Configure License Testing in Play Console

1. Open [Google Play Console](https://play.google.com/console)
2. Go to **Settings** → **License testing**
3. Add test Gmail accounts (comma-separated):
   ```
   test1@gmail.com,
   test2@gmail.com,
   your.email@gmail.com
   ```
4. **License response:** Select **RESPOND_NORMALLY**
   - Simulates real purchases without charges
   - Allows testing full purchase flow
5. **Save** changes

### 2. Publish to Internal Testing Track

**Why?** License testing only works with apps distributed through Play Console (not local APK installs).

1. Build release AAB:
   ```bash
   ./gradlew assembleRelease
   ```
2. Upload to **Play Console** → **Testing** → **Internal testing**
3. Add test users (same emails as license testing)
4. Wait 10-15 minutes for rollout
5. Install via Play Store link

---

## Test Scenarios

### Scenario 1: First-Time Purchase (Monthly with Trial)

**Goal:** Verify trial offer appears and purchase flow completes.

**Steps:**
1. Open app → **Settings** → Tap "Premium" card
2. Premium upgrade sheet appears
3. Select **Monthly (€3.99/month)**
4. Verify **"7 days free"** badge is visible
5. Tap **"Jetzt upgraden"** (Upgrade Now)
6. Google Play billing dialog appears
7. Verify:
   - Shows "7-day free trial"
   - Shows "€3.99/month after trial"
   - Payment method displayed (but not charged during trial)
8. Tap **Subscribe**
9. Purchase completes instantly (test mode)

**Expected Result:**
- ✅ Subscription status updates to **Active (Trial)**
- ✅ Premium features unlock immediately
- ✅ Settings screen shows "Premium Active"
- ✅ No actual charge on card (license testing mode)

### Scenario 2: First-Time Purchase (Yearly with Trial)

**Goal:** Verify yearly plan with 14-day trial.

**Steps:**
1. Open app → **Settings** → Tap "Premium" card
2. Select **Yearly (€29.99/year)**
3. Verify **"14 days free"** badge visible
4. Tap **"Jetzt upgraden"**
5. Google Play dialog shows:
   - "14-day free trial"
   - "€29.99/year after trial"
   - "Save 37%" badge
6. Complete purchase

**Expected Result:**
- ✅ Subscription active with 14-day trial
- ✅ Premium features unlock
- ✅ Yearly plan reflected in subscription status

### Scenario 3: Restore Purchases

**Goal:** Test subscription restoration after reinstall.

**Steps:**
1. Ensure active subscription from Scenario 1 or 2
2. **Uninstall app** completely
3. **Reinstall** from Play Store (Internal Testing track)
4. Open app → **Settings**
5. Subscription should **NOT** be active yet (fresh install)
6. Tap "Premium" card → **"Käufe wiederherstellen"** (Restore Purchases)
7. Wait for restoration (2-5 seconds)

**Expected Result:**
- ✅ Subscription restored successfully
- ✅ Premium features unlock
- ✅ Toast message: "1 Käufe wiederhergestellt"
- ✅ Status updates to "Premium Active"

### Scenario 4: Cancel Subscription

**Goal:** Verify app handles canceled subscriptions correctly.

**Steps:**
1. Have active subscription (from Scenario 1/2)
2. Open Google Play Store → **Menu** → **Subscriptions**
3. Find "Premium AI Assistant"
4. Tap **Cancel subscription**
5. Confirm cancellation
6. **DO NOT UNINSTALL APP**
7. Reopen Paperless Scanner app
8. Check subscription status

**Expected Result (During Trial Period):**
- ✅ Subscription shows as "Canceled" but still active until trial end
- ✅ Premium features still work until trial expires
- ✅ After trial: Features lock, status changes to "Expired"

**Expected Result (After Trial, During Paid Period):**
- ✅ Subscription active until end of billing cycle
- ✅ Features work until expiration
- ✅ After expiration: Status changes to "Expired"

### Scenario 5: Upgrade from Monthly to Yearly

**Goal:** Test subscription upgrade flow (pro-rated).

**Steps:**
1. Have active **monthly** subscription
2. Open app → **Settings** → Premium card
3. Tap **"Upgrade zu J\u00e4hrlich"** (if available)
4. OR: Cancel monthly → Wait for expiration → Subscribe to yearly

**Expected Result:**
- ✅ Upgrade processed (pro-rated credit applied)
- ✅ New yearly subscription starts
- ✅ Monthly subscription canceled

**Note:** Downgrade (yearly → monthly) is **not supported** by Google Play. User must cancel yearly and wait for expiration.

### Scenario 6: Payment Failure (Production Only)

**Goal:** Test grace period when payment fails.

⚠️ **Cannot test with license testing!** Only in production with real payments.

**Production Behavior:**
1. Payment fails (expired card, insufficient funds)
2. Google Play retries payment
3. **Grace period activates** (3 days configured)
4. User retains access for 3 days
5. After 3 days: Subscription enters "Account Hold" (access revoked)
6. After account hold: Subscription cancels

**What to test manually in production:**
- BillingManager detects grace period status
- App shows "Payment Issue" banner
- User can update payment method in Play Store

### Scenario 7: Offline Subscription Check

**Goal:** Verify subscription status persists offline.

**Steps:**
1. Have active subscription
2. Enable **Airplane Mode**
3. Kill app (swipe away)
4. Reopen app
5. Navigate to **Settings**

**Expected Result:**
- ✅ Subscription status shows **last known state** (cached)
- ✅ Premium features still accessible (offline)
- ✅ No crash or error when querying subscription

**Note:** BillingClient caches subscription state locally. Refreshes when online.

---

## Local Testing

### Test Without Play Console (Debug Build)

For development purposes, you can test UI without real billing:

**Current Setup (Phase 1 - Debug Mode):**
- Premium features unlocked in `BuildConfig.DEBUG` builds
- No actual billing required
- AI features available for testing

**How to test:**
1. Build debug APK:
   ```bash
   ./gradlew assembleDebug
   ```
2. Install on device:
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```
3. Open app → Settings → Premium section
4. Premium features auto-enabled (debug mode)

**Limitations:**
- Cannot test real purchase flow
- Cannot test trial offers
- Cannot test subscription lifecycle (cancel, renewal, etc.)

**Use Case:**
- UI development
- Feature testing without Play Console setup
- Rapid iteration during development

---

## License Testing (Play Console)

### Setup

**Prerequisites:**
- App uploaded to Play Console (Internal Testing track minimum)
- Test Gmail accounts added to License Testing
- Products activated in Play Console

### How License Testing Works

| Feature | Behavior |
|---------|----------|
| **Purchase Flow** | Full UI, no actual charge |
| **Subscription Status** | Simulates active subscription |
| **Trials** | Trial period is **shortened to 5 minutes** for testing |
| **Renewals** | Not tested (always shows as active) |
| **Cancellations** | Can cancel, but no grace period simulation |
| **Refunds** | Not applicable (no real charge) |

### Test Workflow

1. **Install from Play Store** (Internal Testing track link)
2. **Sign in with test Gmail account** (added to License Testing)
3. **Complete purchase flow**
4. **Wait 5 minutes** for trial to expire (accelerated in test mode)
5. **Verify** subscription status updates

### Verify License Testing Mode

Check logcat for:
```
BillingManager: Billing setup successful
BillingManager: Product details loaded: 2 products
BillingManager: Active subscription found: [paperless_ai_monthly]
```

If no logs appear, check:
- Test account added to License Testing?
- Product IDs match exactly?
- App installed from Play Store (not sideloaded)?

---

## Internal Testing Track

### Purpose

- Test with real Play Store distribution
- Simulate production environment
- Test with license testing accounts
- Get crash reports and analytics

### Setup

1. Build release AAB:
   ```bash
   ./gradlew assembleRelease
   ```
2. Upload to **Play Console** → **Testing** → **Internal testing**
3. Add test users (up to 100 emails)
4. Share testing link with team:
   ```
   https://play.google.com/apps/internaltest/XXXXXXXXXXXXXXX
   ```
5. Testers install via link

### Test Checklist

- [ ] Purchase flow works (both monthly & yearly)
- [ ] Trial offers display correctly (7 days / 14 days)
- [ ] Restore purchases works after reinstall
- [ ] Subscription status updates correctly
- [ ] Premium features unlock after purchase
- [ ] No crashes during billing flow
- [ ] Offline subscription state persists

---

## Beta Testing

### Beta Testing Goals (10-20 Users)

**From Task:** `Premium: Beta Testing mit 10-20 Usern`

#### Recruitment

1. **Reddit** (r/paperlessngx): Post "Beta Testing Program"
2. **GitHub Issues**: Open issue for beta testers
3. **Friends/Family** with Paperless-ngx

#### Beta Program Setup

1. **Play Console** → **Testing** → **Closed testing** → **Create Beta track**
2. Add beta testers (up to 20 emails)
3. **Grant Premium for free**:
   - Option A: Use promo codes (Play Console → **Monetize** → **Promo codes**)
   - Option B: Manual reimbursement (pay back via PayPal)
   - Option C: Extended trial (configure 30-day trial for beta track)

#### Test Scenarios for Beta Users

**Must Test:**
1. Scan invoice → AI suggests tags
2. Scan receipt (poor OCR) → Verify OCR improvement (Paperless-GPT only)
3. Scan letter → Correspondent detected
4. Scan contract → Document type assigned
5. Batch upload 5+ documents → All analyzed

#### Feedback Collection

**Google Form Questions:**
1. How accurate are AI suggestions? (1-5 stars)
2. Does it save you time? (Yes/No)
3. Would you pay €3.99/month? (Yes/No)
4. What's missing?
5. Did you encounter any bugs?

#### Success Criteria

- [ ] 10+ completed feedback forms
- [ ] Average ≥4 stars for accuracy
- [ ] 60%+ willing to pay
- [ ] No critical bugs

**Deliverable:** `docs/BETA_FEEDBACK.md`

---

## Common Issues

### Issue: "Product not found"

**Symptom:** BillingManager cannot find `paperless_ai_monthly` or `paperless_ai_yearly`

**Causes:**
1. Product not activated in Play Console
2. Product ID mismatch (typo in code or Play Console)
3. App not installed from Play Store (sideloaded APK)
4. Google servers not yet synced (wait 2-4 hours after activation)

**Fix:**
```kotlin
// Verify Product IDs in BillingManager.kt
const val PRODUCT_ID_MONTHLY = "paperless_ai_monthly"  // Must match Play Console
const val PRODUCT_ID_YEARLY = "paperless_ai_yearly"   // Must match Play Console
```

Check Play Console:
1. **Monetize** → **Subscriptions**
2. Verify product status: **Active** (not Draft)
3. Verify product ID matches code exactly

### Issue: Trial not showing

**Symptom:** Purchase flow shows full price, no trial mentioned.

**Causes:**
1. Trial offer not set as **Default**
2. Trial offer not **Active**
3. User previously used trial (trial only available once per Google account)

**Fix:**
1. Play Console → Product → **Offers**
2. Find trial offer (`monthly_trial_7d` / `yearly_trial_14d`)
3. Ensure:
   - Status: **Active**
   - Default: **Yes** (checked)

### Issue: Restore doesn't work

**Symptom:** Reinstall app → Tap "Restore Purchases" → No subscription found.

**Causes:**
1. Different Google account used (subscription tied to account)
2. Subscription canceled and expired
3. Not installed from Play Store (license testing requires Play Store install)

**Fix:**
1. Verify same Google account signed in
2. Check subscription in Play Store → **Menu** → **Subscriptions**
3. If subscription shows there, it should restore in app

### Issue: Billing unavailable

**Symptom:** "Billing not available in this device"

**Causes:**
1. No Google Play Services (emulator without Play Store)
2. No payment method on Google account
3. Region not supported (some countries don't support in-app purchases)

**Fix:**
1. Use physical device or Play Store-enabled emulator
2. Add payment method to Google account
3. Test with account in supported region

---

## Debugging Tips

### Enable Billing Logs

Check logcat for BillingManager logs:

```bash
adb logcat -s BillingManager:D
```

**Expected logs:**
```
D/BillingManager: Billing setup successful
D/BillingManager: Product details loaded: 2 products
D/BillingManager: Active subscription found: [paperless_ai_monthly]
D/BillingManager: Purchase successful: [paperless_ai_monthly]
```

**Error logs:**
```
E/BillingManager: Billing setup failed: Service unavailable
E/BillingManager: Failed to load product details: Item not found
E/BillingManager: Purchase error: User canceled
```

### Check Subscription Status

Add debug button in Settings (debug builds only):

```kotlin
if (BuildConfig.DEBUG) {
    Button(onClick = {
        scope.launch {
            val status = billingManager.subscriptionStatus.first()
            Log.d("DEBUG", "Subscription status: $status")
            Toast.makeText(context, "Status: $status", Toast.LENGTH_LONG).show()
        }
    }) {
        Text("Debug: Check Subscription")
    }
}
```

### Verify Product Details

Log product details when loaded:

```kotlin
// In BillingManager.queryProductDetails()
productDetailsCache.forEach { (id, details) ->
    Log.d(TAG, "Product: $id")
    details.subscriptionOfferDetails?.forEach { offer ->
        Log.d(TAG, "  Offer: ${offer.offerToken}")
        Log.d(TAG, "  Price: ${offer.pricingPhases.pricingPhaseList.firstOrNull()?.formattedPrice}")
    }
}
```

---

## Testing Checklist

Before releasing to production:

### Functional Tests
- [ ] Monthly purchase completes successfully
- [ ] Yearly purchase completes successfully
- [ ] Trial offers display (7 days / 14 days)
- [ ] Restore purchases works after reinstall
- [ ] Subscription status updates correctly
- [ ] Premium features unlock after purchase
- [ ] Premium features lock after expiration/cancellation
- [ ] Cancel subscription works
- [ ] Offline mode shows last known status

### UI Tests
- [ ] Premium upgrade sheet displays correctly
- [ ] Pricing accurate (€3.99 / €29.99)
- [ ] Trial badges visible ("7 days free" / "14 days free")
- [ ] Restore button works
- [ ] Premium status shown in Settings

### Error Handling
- [ ] Product not found → Show error message
- [ ] Purchase canceled → No changes, return to previous screen
- [ ] Network offline → Use cached subscription status
- [ ] Billing unavailable → Show "Billing not available" message

### Edge Cases
- [ ] First install (no subscription) → Free features only
- [ ] Expired trial → Features lock, show upgrade prompt
- [ ] Canceled subscription still active → Features work until expiration
- [ ] Multiple restore attempts → Idempotent (no duplicate subscriptions)

---

## Next Steps

After successful testing:

1. ✅ Complete internal testing with team
2. ✅ Run beta program with 10-20 users
3. ✅ Collect feedback via Google Form
4. ✅ Fix critical bugs
5. ✅ Release to production (Closed Testing → Open Testing → Production)

**Related Documentation:**
- `docs/PLAY_CONSOLE_SUBSCRIPTION_SETUP.md` - Play Console setup guide
- `docs/TECHNICAL.md` - Technical architecture
- `docs/BETA_FEEDBACK.md` - Beta testing results (to be created)
