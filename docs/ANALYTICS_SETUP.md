# Analytics Dashboard Setup Guide

Komplette Anleitung zum Einrichten des Analytics Dashboards für Kosten/Gewinn-Monitoring der AI-Features.

---

## Übersicht

Nach diesem Setup hast du:
- ✅ Automatische Datensammlung von Firebase Analytics
- ✅ BigQuery Data Warehouse für Analysen
- ✅ Looker Studio Dashboard mit Live-Metriken
- ✅ Kosten/Gewinn-Tracking in Echtzeit

**Geschätzte Setup-Zeit:** 30-45 Minuten
**Kosten:** BigQuery Free Tier (10GB/Monat kostenlos) - ausreichend für dieses Projekt

---

## SCHRITT 1: BigQuery Export aktivieren

### 1.1 Firebase Console öffnen
```
https://console.firebase.google.com
```

1. Wähle Projekt: **paperless-scanner**
2. Klicke auf ⚙️ **Project Settings** (links unten)
3. Tab **Integrations**

### 1.2 BigQuery verknüpfen
1. Finde "BigQuery" Karte
2. Klicke **Link** (oder "Manage" falls schon verknüpft)
3. Wähle **Link to BigQuery**

### 1.3 Konfiguration
```
Dataset Location: europe-west3 (Frankfurt)
Grund: DSGVO-konform, näher an Usern

Export Settings:
☑ Analytics (wichtig!)
☐ Cloud Messaging (optional)
☐ Crashlytics (optional)
```

4. Klicke **Link BigQuery**
5. Warte auf Bestätigung (1-2 Minuten)

### 1.4 Erster Export
⚠️ **WICHTIG:** Der erste Export kann bis zu **24 Stunden** dauern!

**Prüfen ob aktiv:**
```
https://console.cloud.google.com/bigquery
→ Explorer → paperless-scanner → analytics_XXXXX
```

Du solltest sehen:
- `events_YYYYMMDD` Tabellen (eine pro Tag)
- `events_intraday_YYYYMMDD` (Live-Daten)

---

## SCHRITT 2: Looker Studio Dashboard erstellen

### 2.1 Looker Studio öffnen
```
https://lookerstudio.google.com
```

1. Klicke **Create** → **Report**
2. Wähle **Blank Report**

### 2.2 Data Source verbinden
1. Klicke **Add data to report**
2. Wähle **BigQuery**
3. Navigiere zu:
   ```
   paperless-scanner
   → analytics_XXXXX
   → events_* (Wildcard-Tabelle)
   ```
4. Klicke **Add**
5. Klicke **Add to report**

---

## SCHRITT 3: Dashboard Widgets erstellen

### Widget 1: KPIs (Scorecard)

**Titel:** AI Usage - Last 30 Days

**Metrics:**
1. **Total Calls**
   ```sql
   SELECT COUNT(*) as total_calls
   FROM `paperless-scanner.analytics_*.events_*`
   WHERE event_name = 'ai_feature_used'
     AND _TABLE_SUFFIX BETWEEN
       FORMAT_DATE('%Y%m%d', DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY))
       AND FORMAT_DATE('%Y%m%d', CURRENT_DATE())
   ```

2. **Total Cost**
   ```sql
   SELECT SUM(
     (SELECT value.double_value
      FROM UNNEST(event_params)
      WHERE key = 'estimated_cost_usd')
   ) as total_cost
   FROM `paperless-scanner.analytics_*.events_*`
   WHERE event_name = 'ai_feature_used'
     AND _TABLE_SUFFIX BETWEEN
       FORMAT_DATE('%Y%m%d', DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY))
       AND FORMAT_DATE('%Y%m%d', CURRENT_DATE())
   ```

**So hinzufügen in Looker Studio:**
1. Toolbar: **Add a chart** → **Scorecard**
2. Data → **Custom Query**
3. SQL einfügen
4. Style: Font Size = 32, Compact Numbers = On

---

### Widget 2: User Distribution (Pie Chart)

**Titel:** Users by Subscription Type

**Query:**
```sql
SELECT
  (SELECT value.string_value
   FROM UNNEST(user_properties)
   WHERE key = 'subscription_status') as subscription_type,
  COUNT(DISTINCT user_pseudo_id) as users
FROM `paperless-scanner.analytics_*.events_*`
WHERE event_name = 'ai_feature_used'
GROUP BY subscription_type
```

**So hinzufügen:**
1. **Add a chart** → **Pie chart**
2. Data → **Custom Query**
3. SQL einfügen
4. Dimension: subscription_type
5. Metric: users
6. Style: Donut chart = On

---

### Widget 3: Daily Trend (Time Series)

**Titel:** AI Calls & Cost per Day

**Query:**
```sql
SELECT
  PARSE_DATE('%Y%m%d', event_date) as date,
  COUNT(*) as calls,
  SUM(
    (SELECT value.double_value
     FROM UNNEST(event_params)
     WHERE key = 'estimated_cost_usd')
  ) as cost_usd
FROM `paperless-scanner.analytics_*.events_*`
WHERE event_name = 'ai_feature_used'
  AND _TABLE_SUFFIX BETWEEN
    FORMAT_DATE('%Y%m%d', DATE_SUB(CURRENT_DATE(), INTERVAL 90 DAY))
    AND FORMAT_DATE('%Y%m%d', CURRENT_DATE())
GROUP BY date
ORDER BY date
```

**So hinzufügen:**
1. **Add a chart** → **Time series chart**
2. Data → **Custom Query**
3. SQL einfügen
4. Date Range Dimension: date
5. Metric 1: calls (blue, line)
6. Metric 2: cost_usd (red, bar, secondary axis)

---

### Widget 4: Heavy Users (Table)

**Titel:** Top Users by AI Usage (Anonymized)

**Query:**
```sql
SELECT
  SUBSTR(user_pseudo_id, 1, 8) as user_hash,
  COUNT(*) as ai_calls,
  SUM(
    (SELECT value.double_value
     FROM UNNEST(event_params)
     WHERE key = 'estimated_cost_usd')
  ) as total_cost_usd,
  (SELECT value.string_value
   FROM UNNEST(user_properties)
   WHERE key = 'subscription_status') as subscription
FROM `paperless-scanner.analytics_*.events_*`
WHERE event_name = 'ai_feature_used'
  AND _TABLE_SUFFIX BETWEEN
    FORMAT_DATE('%Y%m%d', DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY))
    AND FORMAT_DATE('%Y%m%d', CURRENT_DATE())
GROUP BY user_pseudo_id, subscription
HAVING ai_calls > 50
ORDER BY ai_calls DESC
LIMIT 20
```

**So hinzufügen:**
1. **Add a chart** → **Table**
2. Data → **Custom Query**
3. SQL einfügen
4. Style: Enable sorting, alternating rows

---

### Widget 5: Feature Usage Breakdown

**Titel:** AI Features Usage

**Query:**
```sql
SELECT
  (SELECT value.string_value
   FROM UNNEST(event_params)
   WHERE key = 'feature_type') as feature,
  COUNT(*) as usage_count,
  ROUND(AVG(
    (SELECT value.int_value
     FROM UNNEST(event_params)
     WHERE key = 'input_tokens')
  ), 0) as avg_input_tokens,
  ROUND(SUM(
    (SELECT value.double_value
     FROM UNNEST(event_params)
     WHERE key = 'estimated_cost_usd')
  ), 4) as total_cost
FROM `paperless-scanner.analytics_*.events_*`
WHERE event_name = 'ai_feature_used'
  AND _TABLE_SUFFIX BETWEEN
    FORMAT_DATE('%Y%m%d', DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY))
    AND FORMAT_DATE('%Y%m%d', CURRENT_DATE())
GROUP BY feature
ORDER BY usage_count DESC
```

**So hinzufügen:**
1. **Add a chart** → **Bar chart**
2. Data → **Custom Query**
3. SQL einfügen
4. Dimension: feature
5. Metric: usage_count

---

## SCHRITT 4: Dashboard-Layout optimieren

### 4.1 Anordnung
```
┌─────────────────────────────────────────┐
│  AI Usage Dashboard - Last 30 Days      │
├──────────┬──────────┬───────────────────┤
│ Total    │ Total    │  Avg Cost/        │
│ Calls    │ Cost     │  Call             │
│ [12,340] │ [$3.21]  │  [$0.00026]       │
├──────────┴──────────┴───────────────────┤
│                                          │
│  Daily Trend (Time Series)               │
│  [Calls Line + Cost Bar Chart]           │
│                                          │
├──────────────────┬───────────────────────┤
│ User Distribution│ Feature Breakdown     │
│ [Pie Chart]      │ [Bar Chart]           │
│                  │                       │
├──────────────────┴───────────────────────┤
│ Heavy Users Table (Anonymized)           │
│ [user_hash | calls | cost | subscription]│
└──────────────────────────────────────────┘
```

### 4.2 Farb-Schema
- Primary: `#E1FF8D` (neon-gelb) für wichtige Metriken
- Background: `#0A0A0A` (schwarz) - Theme → Dark Mode
- Charts: Google Standard Colors

---

## SCHRITT 5: Auto-Refresh einrichten

### 5.1 Data Freshness
1. Klicke **Resource** → **Manage added data sources**
2. Wähle deine BigQuery-Quelle
3. **Edit** → **Data Freshness**
4. Wähle: **12 hours** (oder 1 hour für Live-Daten)
5. **Save**

### 5.2 Manueller Refresh
- Shortcut: `Ctrl + Shift + E` (Windows)
- Shortcut: `Cmd + Shift + E` (Mac)

---

## SCHRITT 6: Sharing & Access

### 6.1 Dashboard teilen
1. **Share** (oben rechts)
2. Wähle: **Get link**
3. Permission: **Anyone with the link can view**
4. Klicke **Copy link**

**Link bookmarken:**
```
Chrome → Bookmarks → Add folder "Analytics"
→ Paste Looker Studio Link
```

### 6.2 Mobile Access
Looker Studio App installieren:
- iOS: https://apps.apple.com/app/google-data-studio/id1438167863
- Android: https://play.google.com/store/apps/details?id=com.google.android.apps.westie

---

## SCHRITT 7: Alerts einrichten (Optional)

### 7.1 BigQuery Scheduled Query
```sql
-- Alert: Hohe Kosten pro User
SELECT
  user_pseudo_id,
  SUM(cost) as total_cost
FROM `paperless-scanner.analytics_*.events_*`
WHERE event_name = 'ai_feature_used'
  AND DATE(TIMESTAMP_MICROS(event_timestamp)) = CURRENT_DATE()
GROUP BY user_pseudo_id
HAVING total_cost > 0.50  -- Alert bei >$0.50/Tag/User
```

**Setup:**
1. BigQuery Console → **Scheduled queries**
2. **Create scheduled query**
3. Schedule: **Every day at 20:00**
4. Destination: Email notification

---

## SCHRITT 8: Monthly Email Report (Optional)

### 8.1 Firebase Cloud Function
Erstelle `functions/src/monthlyReport.ts`:

```typescript
import * as functions from 'firebase-functions';
import { BigQuery } from '@google-cloud/bigquery';
import * as nodemailer from 'nodemailer';

export const monthlyAnalyticsReport = functions
  .region('europe-west3')
  .pubsub
  .schedule('0 9 1 * *') // 1. jeden Monats um 9:00
  .timeZone('Europe/Berlin')
  .onRun(async (context) => {
    const bigquery = new BigQuery();

    const query = `
      SELECT
        COUNT(DISTINCT user_pseudo_id) as premium_users,
        COUNT(*) as total_calls,
        ROUND(AVG(calls_per_user), 1) as avg_calls_per_user,
        ROUND(SUM(cost), 2) as total_cost_usd
      FROM (
        SELECT
          user_pseudo_id,
          COUNT(*) as calls_per_user,
          SUM((SELECT value.double_value FROM UNNEST(event_params) WHERE key = 'estimated_cost_usd')) as cost
        FROM \`paperless-scanner.analytics_*.events_*\`
        WHERE event_name = 'ai_feature_used'
          AND _TABLE_SUFFIX BETWEEN
            FORMAT_DATE('%Y%m%d', DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY))
            AND FORMAT_DATE('%Y%m%d', CURRENT_DATE())
        GROUP BY user_pseudo_id
      )
    `;

    const [rows] = await bigquery.query(query);
    const data = rows[0];

    // Send Email (Setup mit SendGrid/Mailgun)
    const emailContent = `
      📊 AI Usage Report - ${new Date().toLocaleDateString('de-DE', { month: 'long', year: 'numeric' })}

      📈 Übersicht:
      - Premium User: ${data.premium_users}
      - AI Calls gesamt: ${data.total_calls}
      - Ø Calls/User: ${data.avg_calls_per_user}

      💰 Kosten:
      - API-Kosten: $${data.total_cost_usd}
      - Ø Kosten/User: $${(data.total_cost_usd / data.premium_users).toFixed(2)}

      Dashboard: https://lookerstudio.google.com/reporting/YOUR_DASHBOARD_ID
    `;

    // Email senden (Konfiguration erforderlich)
    console.log(emailContent);
  });
```

### 8.2 Deploy
```bash
cd functions
npm install @google-cloud/bigquery nodemailer
firebase deploy --only functions:monthlyAnalyticsReport
```

---

## Troubleshooting

### Problem: BigQuery Dataset nicht sichtbar
**Lösung:**
```
1. Firebase Console → Analytics → Enable Analytics
2. Warte 24h für ersten Export
3. Prüfe: console.cloud.google.com/bigquery
```

### Problem: "Permission denied" in Looker Studio
**Lösung:**
```
1. BigQuery → Dataset → Permissions
2. Add Principal: your-email@gmail.com
3. Role: BigQuery Data Viewer
```

### Problem: Queries sind zu langsam
**Lösung:**
```
1. Partitionierte Tabellen nutzen (automatisch nach _TABLE_SUFFIX)
2. WHERE-Filter immer auf _TABLE_SUFFIX setzen
3. Clustered Tables für oft genutzte Columns
```

### Problem: Kosten höher als erwartet
**Lösung:**
```
1. BigQuery → Query history
2. Prüfe: Bytes processed
3. Optimiere: SELECT nur benötigte Columns
4. Nutze: Cached results (24h)
```

---

## Erwartete URLs nach Setup

Nach erfolgreichem Setup solltest du folgende Bookmarks haben:

| Beschreibung | URL |
|--------------|-----|
| **Looker Studio Dashboard** | `https://lookerstudio.google.com/reporting/XXXXX` |
| **BigQuery Console** | `https://console.cloud.google.com/bigquery?project=paperless-scanner` |
| **Firebase Analytics** | `https://console.firebase.google.com/project/paperless-scanner/analytics` |
| **Firebase BigQuery Link** | `https://console.firebase.google.com/project/paperless-scanner/settings/integrations/bigquery` |

---

## Next Steps

Nach diesem Setup:
1. ✅ **Warte 24h** für ersten BigQuery Export
2. ✅ Implementiere Analytics Events im App-Code (siehe `docs/ANALYTICS_IMPLEMENTATION.md`)
3. ✅ Teste Dashboard mit Live-Daten
4. ✅ Setup Alerts für kritische Metriken
5. ✅ Optional: Monthly Email Report einrichten

**Support:**
- BigQuery Docs: https://cloud.google.com/bigquery/docs
- Looker Studio Help: https://support.google.com/looker-studio
- Firebase Analytics: https://firebase.google.com/docs/analytics
