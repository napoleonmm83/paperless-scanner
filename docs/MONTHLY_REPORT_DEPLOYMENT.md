# Monthly Report Deployment Guide

Quick guide for deploying the automated monthly analytics report.

---

## Prerequisites Checklist

Before deploying, ensure you have:

- [x] Node.js 20+ installed
- [x] Firebase CLI installed (`npm install -g firebase-tools`)
- [x] Firebase Analytics → BigQuery export enabled (see `ANALYTICS_SETUP.md`)
- [x] SendGrid account with API key

---

## Quick Deploy (5 Minutes)

### Step 1: Install Dependencies

```bash
cd functions
npm install
```

### Step 2: Configure SendGrid

1. **Get SendGrid API Key:**
   - Go to https://app.sendgrid.com/settings/api_keys
   - Click "Create API Key"
   - Name: "Paperless Analytics Report"
   - Permissions: "Mail Send" → Full Access
   - Copy the API key (shown only once!)

2. **Set Firebase Config:**
   ```bash
   firebase functions:config:set \
     sendgrid.api_key="YOUR_SENDGRID_API_KEY_HERE" \
     sendgrid.from_email="analytics@paperless-scanner.app" \
     report.email="marcusmartini83@gmail.com"
   ```

3. **Verify Config:**
   ```bash
   firebase functions:config:get
   ```

### Step 3: Build & Deploy

```bash
# Build TypeScript
npm run build

# Deploy to Firebase
firebase deploy --only functions
```

Expected output:
```
✔  Deploy complete!

Project Console: https://console.firebase.google.com/project/paperless-scanner/overview
Functions URL (monthlyAnalyticsReport): https://europe-west3-paperless-scanner.cloudfunctions.net/monthlyAnalyticsReport
```

---

## Verify Deployment

### Check Function is Scheduled

```bash
firebase functions:list
```

Expected output:
```
┌───────────────────────────┬─────────────┬─────────────────┐
│ Function                  │ Region      │ Trigger         │
├───────────────────────────┼─────────────┼─────────────────┤
│ monthlyAnalyticsReport    │ europe-west3│ Scheduled       │
└───────────────────────────┴─────────────┴─────────────────┘
```

### Check Cloud Scheduler Job

```bash
gcloud scheduler jobs list --project=paperless-scanner
```

Expected output:
```
ID: firebase-schedule-monthlyAnalyticsReport-europe-west3
STATE: ENABLED
SCHEDULE: 0 9 1 * *
TIME_ZONE: Europe/Berlin
```

---

## Test the Function

### Option 1: Manual Trigger (Recommended)

```bash
# Trigger the function now (don't wait for 1st of month)
gcloud scheduler jobs run \
  firebase-schedule-monthlyAnalyticsReport-europe-west3 \
  --location=europe-west3 \
  --project=paperless-scanner
```

Check your email in ~10 seconds.

### Option 2: Firebase Console

1. Go to https://console.firebase.google.com/project/paperless-scanner/functions
2. Find `monthlyAnalyticsReport`
3. Click "Logs" to see execution history
4. Use Cloud Scheduler console to trigger manually

### Option 3: Local Emulator

```bash
cd functions
npm run serve

# In another terminal:
npm run shell
> monthlyAnalyticsReport()
```

---

## View Logs

### Firebase Console (Easiest)

https://console.firebase.google.com/project/paperless-scanner/functions/logs

### Command Line

```bash
# Real-time logs
firebase functions:log --only monthlyAnalyticsReport

# Last 50 entries
firebase functions:log --only monthlyAnalyticsReport --limit 50
```

---

## Update Configuration

### Change Email Recipient

```bash
firebase functions:config:set report.email="new-email@example.com"
firebase deploy --only functions
```

### Change Schedule

Edit `functions/src/monthlyReport.ts`:
```typescript
schedule: "0 9 1 * *", // Change this cron expression
```

Common schedules:
- `0 9 1 * *` - 1st of month at 9:00 AM (default)
- `0 9 * * 1` - Every Monday at 9:00 AM
- `0 9 * * *` - Every day at 9:00 AM
- `0 */6 * * *` - Every 6 hours (for testing)

Then redeploy:
```bash
npm run build
firebase deploy --only functions
```

### Rotate SendGrid API Key

```bash
# Get new key from SendGrid
firebase functions:config:set sendgrid.api_key="NEW_KEY_HERE"
firebase deploy --only functions
```

---

## Troubleshooting

### "Permission denied" Error

Grant BigQuery access to Cloud Functions service account:
```bash
gcloud projects add-iam-policy-binding paperless-scanner \
  --member="serviceAccount:paperless-scanner@appspot.gserviceaccount.com" \
  --role="roles/bigquery.dataViewer"
```

### "No data returned" Error

**Cause:** BigQuery export not active yet (takes 24h)

**Solution:** Wait 24h after enabling Firebase Analytics → BigQuery export

**Verify data exists:**
```bash
bq query --project_id=paperless-scanner \
  'SELECT COUNT(*) as event_count FROM `paperless-scanner.analytics_*.events_*` WHERE event_name = "ai_feature_used"'
```

### Email Not Received

**Checklist:**
1. Check SendGrid API key is valid:
   ```bash
   firebase functions:config:get
   ```
2. Check spam folder
3. Verify SendGrid sender domain is verified (for production)
4. Check function logs for errors:
   ```bash
   firebase functions:log --only monthlyAnalyticsReport
   ```

### Function Not Running on Schedule

**Check scheduler job:**
```bash
gcloud scheduler jobs describe \
  firebase-schedule-monthlyAnalyticsReport-europe-west3 \
  --location=europe-west3
```

If STATE is PAUSED:
```bash
gcloud scheduler jobs resume \
  firebase-schedule-monthlyAnalyticsReport-europe-west3 \
  --location=europe-west3
```

---

## Uninstall

To remove the function:

```bash
# Delete function
firebase functions:delete monthlyAnalyticsReport --region=europe-west3

# Clear config (optional)
firebase functions:config:unset sendgrid
firebase functions:config:unset report
```

---

## Cost

**Monthly Report Function:**
- Invocations: 1/month
- Runtime: ~5 seconds
- **Cost: $0.00/month** (within free tier)

**Free Tier Limits:**
- 2M invocations/month
- 400,000 GB-seconds compute time

No credit card required for this usage level.

---

## Next Steps

After successful deployment:

1. ✅ Wait for 1st of month OR trigger manually to test
2. ✅ Verify email arrives correctly
3. ✅ Check report data looks accurate
4. ✅ Bookmark Firebase Functions console for monitoring
5. ✅ Consider setting up Looker Studio dashboard (see `ANALYTICS_SETUP.md`)

---

## Support

- **Firebase Functions:** https://firebase.google.com/docs/functions
- **Cloud Scheduler:** https://cloud.google.com/scheduler/docs
- **SendGrid Docs:** https://docs.sendgrid.com/

For issues, check:
1. Firebase Functions logs
2. Cloud Scheduler job status
3. BigQuery dataset exists and has data
