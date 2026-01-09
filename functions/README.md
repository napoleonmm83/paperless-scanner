# Firebase Cloud Functions - Paperless Scanner Analytics

Automated reporting and analytics functions for AI usage tracking.

## 📋 Functions Overview

### `monthlyAnalyticsReport`
**Schedule:** 1st of each month at 9:00 AM Europe/Berlin
**Purpose:** Generate comprehensive monthly AI usage, cost, and profitability report

**Features:**
- Queries BigQuery for aggregated analytics data
- Calculates costs, revenue, and profit margins
- Identifies top users (anonymized)
- Sends email report via SendGrid
- Logs report to Cloud Functions logs

---

## 🚀 Setup & Deployment

### Prerequisites

1. **Node.js 20+**
   ```bash
   node --version  # Should be 20.x or higher
   ```

2. **Firebase CLI**
   ```bash
   npm install -g firebase-tools
   firebase login
   ```

3. **BigQuery Export** (from ANALYTICS_SETUP.md)
   - Firebase Analytics → BigQuery link must be active
   - Dataset: `analytics_*` in `europe-west3`

4. **SendGrid Account** (for email reports)
   - Sign up at https://sendgrid.com
   - Generate API key with "Mail Send" permission

---

### Installation

1. **Install Dependencies**
   ```bash
   cd functions
   npm install
   ```

2. **Configure Environment Variables**

   Set Firebase config (stored encrypted in Firebase):
   ```bash
   firebase functions:config:set \
     sendgrid.api_key="YOUR_SENDGRID_API_KEY" \
     sendgrid.from_email="analytics@paperless-scanner.app" \
     report.email="marcusmartini83@gmail.com"
   ```

   **Get SendGrid API Key:**
   - https://app.sendgrid.com/settings/api_keys
   - Create new key → Mail Send → Full Access
   - Copy key (only shown once!)

3. **Verify Configuration**
   ```bash
   firebase functions:config:get
   ```

   Expected output:
   ```json
   {
     "sendgrid": {
       "api_key": "SG.xxx...",
       "from_email": "analytics@paperless-scanner.app"
     },
     "report": {
       "email": "marcusmartini83@gmail.com"
     }
   }
   ```

---

### Build & Deploy

1. **Build TypeScript**
   ```bash
   npm run build
   ```

2. **Deploy to Firebase**
   ```bash
   npm run deploy
   # OR
   firebase deploy --only functions
   ```

3. **Verify Deployment**
   ```bash
   firebase functions:list
   ```

   Expected output:
   ```
   ✔ Loaded functions definitions from source.
   ┌───────────────────────────┬─────────────┬─────────────────┐
   │ Function                  │ Region      │ Trigger         │
   ├───────────────────────────┼─────────────┼─────────────────┤
   │ monthlyAnalyticsReport    │ europe-west3│ Scheduled       │
   └───────────────────────────┴─────────────┴─────────────────┘
   ```

---

## 🧪 Testing

### Local Testing with Emulator

1. **Start Emulator**
   ```bash
   npm run serve
   ```

2. **Trigger Function Manually**
   ```bash
   # In another terminal
   npm run shell
   ```

   Then in the shell:
   ```javascript
   monthlyAnalyticsReport()
   ```

### Production Testing

**IMPORTANT:** The function runs on a schedule (1st of month). To test before waiting:

```bash
# Trigger function manually
firebase functions:shell
> monthlyAnalyticsReport()

# OR use gcloud CLI
gcloud functions call monthlyAnalyticsReport \
  --region=europe-west3 \
  --project=paperless-scanner
```

### Dry Run (without sending email)

Temporarily remove SendGrid API key:
```bash
firebase functions:config:unset sendgrid.api_key
firebase deploy --only functions
```

Check Cloud Functions logs:
```bash
firebase functions:log --only monthlyAnalyticsReport
```

---

## 📊 Report Format

### Email Subject
```
📊 AI Usage Report - Januar 2026
```

### Report Content
```
📊 AI Usage Report - Januar 2026
================================

ÜBERSICHT:
- Premium User: 127
- AI Calls gesamt: 3,840
- Ø Calls/User: 30.2

KOSTEN:
- API-Kosten: $3.84
- Ø Kosten/User: $0.03

EINNAHMEN:
- Monatlich: 95 × €1.99 = €189.05
- Jährlich: 32 × €14.99/12 = €39.97
- Gesamt: €229.02

PROFIT:
- Brutto: €229.02
- - Google Fee (15%): €34.35
- - API-Kosten: €3.50
- = Netto: €191.17
- Marge: 83.4% ✅

TOP USERS (anonymisiert):
1. User #a1b2c3d4: 156 Calls ($0.16)
2. User #e5f6g7h8: 134 Calls ($0.13)
3. User #i9j0k1l2: 98 Calls ($0.10)

ALERTS:
- ✅ No critical alerts
```

---

## ⚙️ Configuration Options

### Change Schedule

Edit `functions/src/monthlyReport.ts`:
```typescript
export const monthlyAnalyticsReport = functions.scheduler.onSchedule({
  schedule: "0 9 1 * *", // Change this cron expression
  timeZone: "Europe/Berlin",
  region: "europe-west3",
}, async (event) => {
  // ...
});
```

**Common Schedules:**
- `0 9 1 * *` - 1st of month at 9:00 AM (current)
- `0 9 * * 1` - Every Monday at 9:00 AM (weekly)
- `0 9 * * *` - Every day at 9:00 AM (daily)
- `0 */6 * * *` - Every 6 hours (testing)

**Tools:**
- https://crontab.guru/ - Cron expression builder

### Change Email Recipient

```bash
firebase functions:config:set report.email="new-email@example.com"
firebase deploy --only functions
```

### Change Email Provider

Replace SendGrid with your preferred provider:
1. Edit `functions/src/monthlyReport.ts`
2. Replace SendGrid imports and logic
3. Update environment variables

---

## 🔍 Monitoring

### View Logs

```bash
# Real-time logs
firebase functions:log --only monthlyAnalyticsReport

# View in Firebase Console
# https://console.firebase.google.com/project/paperless-scanner/functions/logs
```

### Check Scheduled Executions

```bash
# Cloud Scheduler jobs
gcloud scheduler jobs list --project=paperless-scanner

# Describe specific job
gcloud scheduler jobs describe \
  firebase-schedule-monthlyAnalyticsReport-europe-west3 \
  --location=europe-west3
```

### Cost Monitoring

Firebase Cloud Functions free tier:
- **2M invocations/month** - More than enough for monthly reports
- **400,000 GB-seconds** compute time
- **200,000 CPU-seconds**

Current function usage: ~1 invocation/month, ~5 seconds runtime
**Cost: $0.00/month** ✅

---

## 🐛 Troubleshooting

### Error: "Permission denied" in BigQuery

**Solution:**
```bash
# Grant Cloud Functions service account BigQuery Data Viewer role
gcloud projects add-iam-policy-binding paperless-scanner \
  --member="serviceAccount:paperless-scanner@appspot.gserviceaccount.com" \
  --role="roles/bigquery.dataViewer"
```

### Error: "SendGrid API key invalid"

**Solution:**
```bash
# Regenerate API key in SendGrid
# Then update config:
firebase functions:config:set sendgrid.api_key="NEW_KEY"
firebase deploy --only functions
```

### Error: "No data returned from BigQuery"

**Causes:**
1. BigQuery export not active → Wait 24h after enabling
2. No AI usage events logged → Check Firebase Analytics
3. Wrong dataset ID → Verify in BigQuery Console

**Debug:**
```bash
# Check BigQuery dataset exists
bq ls --project_id=paperless-scanner

# Query events manually
bq query --project_id=paperless-scanner \
  'SELECT COUNT(*) FROM `paperless-scanner.analytics_*.events_*` WHERE event_name = "ai_feature_used"'
```

### Function not running on schedule

**Solution:**
```bash
# Check Cloud Scheduler job status
gcloud scheduler jobs describe \
  firebase-schedule-monthlyAnalyticsReport-europe-west3 \
  --location=europe-west3

# Manually trigger to test
gcloud scheduler jobs run \
  firebase-schedule-monthlyAnalyticsReport-europe-west3 \
  --location=europe-west3
```

---

## 📚 Resources

- **Firebase Functions Docs:** https://firebase.google.com/docs/functions
- **Cloud Scheduler Docs:** https://cloud.google.com/scheduler/docs
- **BigQuery Node.js Client:** https://cloud.google.com/nodejs/docs/reference/bigquery/latest
- **SendGrid Node.js SDK:** https://github.com/sendgrid/sendgrid-nodejs

---

## 🔒 Security Notes

- **API Keys:** Never commit API keys to git - use Firebase config
- **Email Access:** Report contains anonymized user data only (no PII)
- **BigQuery Access:** Service account has read-only access
- **GDPR Compliance:** All data stored in `europe-west3` (Frankfurt)

---

## 📝 Development

### Local Development

1. **Watch Mode**
   ```bash
   npm run build:watch
   ```

2. **Linting**
   ```bash
   npm run lint
   ```

3. **Type Checking**
   ```bash
   npx tsc --noEmit
   ```

### Adding New Functions

1. Create new file: `functions/src/myNewFunction.ts`
2. Export from `functions/src/index.ts`:
   ```typescript
   export { myNewFunction } from "./myNewFunction";
   ```
3. Build and deploy:
   ```bash
   npm run build
   npm run deploy
   ```

---

## 💰 Cost Estimation

**Monthly Report Function:**
- Invocations: 1/month
- Runtime: ~5 seconds
- Memory: 256 MB
- **Cost: $0.00/month** (within free tier)

**BigQuery Queries:**
- Data scanned: ~10 MB/month (with proper partitioning)
- **Cost: $0.00/month** (10 GB/month free tier)

**SendGrid:**
- Emails sent: 1/month
- **Cost: $0.00/month** (100 emails/day free tier)

**Total: $0.00/month** ✅
