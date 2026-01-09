# Analytics Dashboard Konzept

## Übersicht

Business-Monitoring Dashboard für Paperless Scanner Premium Features mit Fokus auf:
- **Profitabilität**: AI-Kosten vs. Abo-Einnahmen
- **Nutzungsverhalten**: Durchschnittliche Calls pro User, Heavy User Identifikation
- **Alerts**: Automatische Benachrichtigungen bei Anomalien

---

## Aktuelle Analytics-Integration

### Bereits implementiert (AnalyticsService.kt):

**✅ Firebase Analytics** - Vollständig integriert
**✅ AI-spezifische Events:**
- `ai_feature_used` - Kosten-Tracking pro Call
- `ai_suggestion_accepted/rejected` - Akzeptanzrate
- `premium_subscribed` - Abo-Abschlüsse
- `ai_usage_limit_warning/reached` - Limit-Tracking

**✅ User Properties:**
- `subscription_status`: "free", "monthly", "yearly"
- `ai_calls_this_month`: Anzahl Calls
- `ai_heavy_user`: "true" wenn >100 Calls/Monat

**✅ Kosten-Kalkulation (AiCostCalculator):**
- Input: $0.30 pro 1M Tokens
- Output: $2.50 pro 1M Tokens
- Durchschnitt: ~$0.001 pro Call

---

## Option A: Firebase + BigQuery + Looker Studio (EMPFOHLEN)

### Vorteile:
✅ **Keine Server-Infrastruktur nötig**
✅ **Kostenlos** bis ~1 GB BigQuery pro Tag
✅ **Native Integration** mit Firebase
✅ **Echtzeit-Dashboards** mit Looker Studio
✅ **Wartungsfrei** - Google verwaltet alles

### Kosten:
- **Firebase Analytics**: Kostenlos (10 Mio Events/Monat)
- **BigQuery Export**: Kostenlos (bis 1 GB/Tag)
- **BigQuery Queries**: $5 pro TB (ca. $0.01/Tag bei 100 Usern)
- **Looker Studio**: Komplett kostenlos
- **Gesamt**: ~$0.30/Monat bei 100 aktiven Usern

### Setup-Aufwand:
⏱️ **2-3 Stunden** einmalig

### Implementierung:

#### 1. BigQuery Export aktivieren (15 Min)

```bash
# Firebase Console: Analytics → BigQuery Linking → Enable

# Schema wird automatisch erstellt:
# - events_YYYYMMDD (tägliche Tabellen)
# - events_intraday_YYYYMMDD (Echtzeit-Updates)
```

**Exported Events:**
```sql
events_YYYYMMDD
├── event_name: STRING
├── event_timestamp: INTEGER
├── user_pseudo_id: STRING (anonymisiert)
├── event_params: ARRAY<STRUCT>
│   ├── key: STRING
│   └── value: STRING|INT|FLOAT
└── user_properties: ARRAY<STRUCT>
    ├── key: STRING ("subscription_status", "ai_calls_this_month")
    └── value: STRING
```

#### 2. BigQuery Queries für Metriken (30 Min)

**A. Aktive Premium User (Heute)**
```sql
WITH latest_properties AS (
  SELECT
    user_pseudo_id,
    ARRAY_AGG(
      STRUCT(up.key AS key, up.value.string_value AS value)
      ORDER BY event_timestamp DESC
      LIMIT 1
    )[OFFSET(0)] AS subscription_status
  FROM `your-project.analytics_XXXXX.events_*`,
  UNNEST(user_properties) AS up
  WHERE
    _TABLE_SUFFIX = FORMAT_DATE('%Y%m%d', CURRENT_DATE())
    AND up.key = 'subscription_status'
  GROUP BY user_pseudo_id
)
SELECT
  COUNT(DISTINCT user_pseudo_id) AS active_premium_users
FROM latest_properties
WHERE subscription_status.value IN ('monthly', 'yearly');
```

**B. AI-Calls und Kosten (Heute/Woche/Monat)**
```sql
SELECT
  DATE(TIMESTAMP_MICROS(event_timestamp)) AS date,
  COUNT(*) AS total_calls,
  SUM(
    (SELECT ep.value.double_value
     FROM UNNEST(event_params) AS ep
     WHERE ep.key = 'estimated_cost_usd')
  ) AS total_cost_usd,
  AVG(
    (SELECT ep.value.double_value
     FROM UNNEST(event_params) AS ep
     WHERE ep.key = 'estimated_cost_usd')
  ) AS avg_cost_per_call
FROM `your-project.analytics_XXXXX.events_*`
WHERE
  event_name = 'ai_feature_used'
  AND _TABLE_SUFFIX BETWEEN
    FORMAT_DATE('%Y%m%d', DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY))
    AND FORMAT_DATE('%Y%m%d', CURRENT_DATE())
GROUP BY date
ORDER BY date DESC;
```

**C. Kosten-Verteilung (Histogram)**
```sql
WITH user_costs AS (
  SELECT
    user_pseudo_id,
    SUM(
      (SELECT ep.value.double_value
       FROM UNNEST(event_params) AS ep
       WHERE ep.key = 'estimated_cost_usd')
    ) AS monthly_cost_usd
  FROM `your-project.analytics_XXXXX.events_*`
  WHERE
    event_name = 'ai_feature_used'
    AND _TABLE_SUFFIX BETWEEN
      FORMAT_DATE('%Y%m%d', DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY))
      AND FORMAT_DATE('%Y%m%d', CURRENT_DATE())
  GROUP BY user_pseudo_id
)
SELECT
  CASE
    WHEN monthly_cost_usd < 0.01 THEN '$0.00-0.01'
    WHEN monthly_cost_usd < 0.03 THEN '$0.01-0.03'
    WHEN monthly_cost_usd < 0.05 THEN '$0.03-0.05'
    WHEN monthly_cost_usd < 0.10 THEN '$0.05-0.10'
    ELSE '$0.10+'
  END AS cost_bucket,
  COUNT(*) AS user_count
FROM user_costs
GROUP BY cost_bucket
ORDER BY cost_bucket;
```

**D. Heavy User Anteil**
```sql
WITH user_calls AS (
  SELECT
    user_pseudo_id,
    COUNT(*) AS call_count
  FROM `your-project.analytics_XXXXX.events_*`
  WHERE
    event_name = 'ai_feature_used'
    AND _TABLE_SUFFIX BETWEEN
      FORMAT_DATE('%Y%m%d', DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY))
      AND FORMAT_DATE('%Y%m%d', CURRENT_DATE())
  GROUP BY user_pseudo_id
)
SELECT
  COUNT(*) AS total_users,
  COUNT(IF(call_count > 100, 1, NULL)) AS heavy_users,
  ROUND(COUNT(IF(call_count > 100, 1, NULL)) * 100.0 / COUNT(*), 2) AS heavy_user_percentage
FROM user_calls;
```

**E. Profitabilität (Revenue vs Costs)**
```sql
WITH revenue AS (
  SELECT
    SUM(
      CASE
        WHEN (SELECT ep.value.string_value FROM UNNEST(event_params) AS ep WHERE ep.key = 'plan') = 'monthly'
          THEN (SELECT ep.value.double_value FROM UNNEST(event_params) AS ep WHERE ep.key = 'price_usd')
        WHEN (SELECT ep.value.string_value FROM UNNEST(event_params) AS ep WHERE ep.key = 'plan') = 'yearly'
          THEN (SELECT ep.value.double_value FROM UNNEST(event_params) AS ep WHERE ep.key = 'price_usd') / 12
        ELSE 0
      END
    ) AS monthly_revenue_usd
  FROM `your-project.analytics_XXXXX.events_*`
  WHERE
    event_name = 'premium_subscribed'
    AND _TABLE_SUFFIX BETWEEN
      FORMAT_DATE('%Y%m%d', DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY))
      AND FORMAT_DATE('%Y%m%d', CURRENT_DATE())
),
costs AS (
  SELECT
    SUM(
      (SELECT ep.value.double_value
       FROM UNNEST(event_params) AS ep
       WHERE ep.key = 'estimated_cost_usd')
    ) AS monthly_cost_usd
  FROM `your-project.analytics_XXXXX.events_*`
  WHERE
    event_name = 'ai_feature_used'
    AND _TABLE_SUFFIX BETWEEN
      FORMAT_DATE('%Y%m%d', DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY))
      AND FORMAT_DATE('%Y%m%d', CURRENT_DATE())
)
SELECT
  r.monthly_revenue_usd,
  c.monthly_cost_usd,
  (r.monthly_revenue_usd - c.monthly_cost_usd) AS monthly_margin_usd,
  ROUND((r.monthly_revenue_usd - c.monthly_cost_usd) * 100.0 / r.monthly_revenue_usd, 2) AS margin_percentage
FROM revenue r, costs c;
```

#### 3. Looker Studio Dashboard erstellen (1-2 Stunden)

**URL**: https://lookerstudio.google.com/

**Dashboard-Layout:**

```
┌─────────────────────────────────────────────────────────────────┐
│ Paperless Scanner - Business Analytics                         │
│ Letzte Aktualisierung: [Echtzeit]                              │
└─────────────────────────────────────────────────────────────────┘

┌──────────────────────┐ ┌──────────────────────┐ ┌─────────────────┐
│ Aktive Premium User  │ │ Gesamt AI-Calls      │ │ Monatl. Marge   │
│      127             │ │      3,842           │ │    €1,847       │
│  ▲ +12 seit gestern  │ │  ▼ -340 seit gestern │ │    92.3%        │
└──────────────────────┘ └──────────────────────┘ └─────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│ AI-Calls & Kosten (Letzte 30 Tage)                              │
│                                                                  │
│    Calls ┤        ╱╲                                             │
│    4000  ┤       ╱  ╲        ╱╲                                  │
│    3000  ┤      ╱    ╲      ╱  ╲                                 │
│    2000  ┤     ╱      ╲    ╱    ╲                                │
│    1000  ┤____╱        ╲__╱      ╲___                            │
│          └─────────────────────────────────────────              │
│              1   5   10  15  20  25  30 (Tage)                  │
│                                                                  │
│    Costs ┤                                                       │
│    $4.0  ┤        ╱╲                                             │
│    $3.0  ┤       ╱  ╲        ╱╲                                  │
│    $2.0  ┤      ╱    ╲      ╱  ╲                                 │
│    $1.0  ┤_____╱      ╲____╱    ╲___                             │
│          └─────────────────────────────────────────              │
└──────────────────────────────────────────────────────────────────┘

┌────────────────────────┐ ┌──────────────────────────────────────┐
│ User Kosten-Verteilung │ │ Heavy User (>100 Calls/Monat)       │
│                        │ │                                      │
│  $0.00-0.01: ████ 45%  │ │  Total Users:       127             │
│  $0.01-0.03: ████ 35%  │ │  Heavy Users:        18             │
│  $0.03-0.05: ██   12%  │ │  Percentage:      14.2%             │
│  $0.05-0.10: █     5%  │ │                                      │
│  $0.10+:     █     3%  │ │  ⚠️ 3 User >$2.50/Monat             │
└────────────────────────┘ └──────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│ Profitabilität (Letzter Monat)                                  │
│                                                                  │
│  Revenue (Subscriptions):    €2,000.00                          │
│  Costs (AI API):                 €153.17                         │
│  ─────────────────────────────────────────                       │
│  Margin:                      €1,846.83  (92.3%)                │
│                                                                  │
│  Status: ✅ PROFITABLE                                           │
└──────────────────────────────────────────────────────────────────┘
```

**Looker Studio Setup:**
1. Data Source: BigQuery → Verbinden mit `analytics_XXXXX`
2. Custom Queries als Data Sources verwenden
3. Scorecard Widgets für KPIs (Premium User, Total Calls, Margin)
4. Time Series Charts für Trends
5. Bar Charts für Histogramme
6. Calculated Fields für Margin %

#### 4. Alerts einrichten (30 Min)

**Option 1: BigQuery Scheduled Queries + Email Notifications**

```sql
-- Scheduled Query: Täglich um 9:00 Uhr

-- Alert 1: User mit Kosten > 50% vom Abo-Preis
WITH high_cost_users AS (
  SELECT
    user_pseudo_id,
    SUM(
      (SELECT ep.value.double_value
       FROM UNNEST(event_params) AS ep
       WHERE ep.key = 'estimated_cost_usd')
    ) AS monthly_cost_usd
  FROM `your-project.analytics_XXXXX.events_*`
  WHERE
    event_name = 'ai_feature_used'
    AND _TABLE_SUFFIX BETWEEN
      FORMAT_DATE('%Y%m%d', DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY))
      AND FORMAT_DATE('%Y%m%d', CURRENT_DATE())
  GROUP BY user_pseudo_id
  HAVING monthly_cost_usd > 2.50  -- 50% von €4.99
)
SELECT
  COUNT(*) AS high_cost_user_count,
  ARRAY_AGG(user_pseudo_id LIMIT 10) AS example_users
FROM high_cost_users;

-- Ergebnis an Email senden wenn count > 0
```

**Option 2: Cloud Functions (Fortgeschritten)**

```javascript
// Cloud Function: Trigger bei neuem BigQuery Export
exports.checkAnomalies = functions.pubsub
  .topic('firebase-analytics-export')
  .onPublish(async (message) => {
    // Query BigQuery
    const query = `
      SELECT COUNT(*) as daily_calls
      FROM \`your-project.analytics_XXXXX.events_\${TODAY}\`
      WHERE event_name = 'ai_feature_used'
    `;

    const [rows] = await bigquery.query(query);
    const dailyCalls = rows[0].daily_calls;

    // Alert wenn ungewöhnlich hoch
    const avgCalls = 100; // Baseline
    if (dailyCalls > avgCalls * 2) {
      await sendEmail({
        to: 'admin@example.com',
        subject: '🚨 Unusual AI Usage Spike',
        body: `Daily calls: ${dailyCalls} (2x normal)`
      });
    }
  });
```

---

## Option B: Eigenes Backend (Mehr Kontrolle)

### Vorteile:
✅ **Vollständige Kontrolle** über Daten
✅ **Custom Reports** jederzeit anpassbar
✅ **Eigene Datenbank** (Supabase/Firebase Firestore)
✅ **API-Zugriff** für eigene Tools

### Nachteile:
❌ **Server-Wartung** erforderlich
❌ **Höhere Kosten** (~$20-50/Monat)
❌ **Mehr Implementierungsaufwand** (5-10 Stunden)
❌ **Duplikation** der Firebase Analytics Daten

### Implementierung:

#### 1. Backend Setup (Supabase)

```sql
-- Supabase PostgreSQL Schema
CREATE TABLE ai_usage_logs (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id TEXT NOT NULL,  -- anonymisiert
  feature_type TEXT NOT NULL,
  input_tokens INTEGER NOT NULL,
  output_tokens INTEGER NOT NULL,
  cost_usd DECIMAL(10, 6) NOT NULL,
  subscription_type TEXT NOT NULL,
  success BOOLEAN DEFAULT true,
  created_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE subscription_events (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id TEXT NOT NULL,
  plan TEXT NOT NULL,  -- "monthly" or "yearly"
  price_usd DECIMAL(10, 2) NOT NULL,
  started_at TIMESTAMP DEFAULT NOW()
);

-- Indexes für schnelle Queries
CREATE INDEX idx_ai_usage_created_at ON ai_usage_logs(created_at);
CREATE INDEX idx_ai_usage_user_id ON ai_usage_logs(user_id);
CREATE INDEX idx_subscription_user_id ON subscription_events(user_id);
```

#### 2. Android App Integration

```kotlin
// AnalyticsService.kt - Dual Tracking
fun trackAiFeatureUsage(
    featureType: String,
    inputTokens: Int,
    outputTokens: Int,
    subscriptionType: String
) {
    // Firebase Analytics (existing)
    val costUsd = AiCostCalculator.calculateCost(inputTokens, outputTokens)
    trackEvent(AnalyticsEvent.AiFeatureUsed(...))

    // Eigenes Backend (NEW)
    viewModelScope.launch {
        supabaseClient.from("ai_usage_logs").insert(
            mapOf(
                "user_id" to getCurrentUserId(), // anonymisiert
                "feature_type" to featureType,
                "input_tokens" to inputTokens,
                "output_tokens" to outputTokens,
                "cost_usd" to costUsd,
                "subscription_type" to subscriptionType
            )
        )
    }
}
```

#### 3. Dashboard API

```kotlin
// Supabase Edge Functions (Deno/TypeScript)
import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from 'https://esm.sh/@supabase/supabase-js@2'

serve(async (req) => {
  const supabase = createClient(...)

  // Query: Daily Stats
  const { data, error } = await supabase.rpc('get_daily_stats', {
    start_date: '2026-01-01',
    end_date: '2026-01-31'
  })

  return new Response(JSON.stringify(data), {
    headers: { 'Content-Type': 'application/json' }
  })
})
```

#### 4. Dashboard UI (Next.js)

```typescript
// app/dashboard/page.tsx
export default async function DashboardPage() {
  const stats = await supabase.rpc('get_monthly_stats')

  return (
    <div>
      <h1>Business Analytics</h1>
      <StatsCards data={stats} />
      <UsageChart data={stats.daily_calls} />
      <CostHistogram data={stats.user_costs} />
    </div>
  )
}
```

### Kosten (Option B):
- **Supabase Pro**: $25/Monat (unbegrenzte API-Requests)
- **Vercel/Netlify**: $0-20/Monat (Dashboard Hosting)
- **Gesamt**: ~$25-45/Monat

---

## Empfehlung: Option A (Firebase + BigQuery)

### Begründung:

| Kriterium | Option A | Option B |
|-----------|----------|----------|
| **Setup-Zeit** | 2-3 Stunden | 5-10 Stunden |
| **Kosten** | ~$0.30/Monat | ~$25-45/Monat |
| **Wartung** | Keine | Server-Updates, Backups |
| **Skalierung** | Automatisch | Manuell |
| **Echtzeit** | Ja (BigQuery Streaming) | Ja (Supabase Realtime) |
| **Historische Daten** | Unbegrenzt (BigQuery) | Begrenzt (Speicherkosten) |

**Für ein Startup/Solo-Dev Projekt ist Option A die beste Wahl:**
- ✅ Minimaler Aufwand
- ✅ Nahezu kostenlos
- ✅ Enterprise-Grade Infrastructure (Google)
- ✅ Kein Server-Management

**Option B nur sinnvoll wenn:**
- 📊 Komplexe Custom Reports benötigt werden
- 🔒 Daten on-premise bleiben müssen
- 🛠️ Team für Backend-Entwicklung vorhanden

---

## Nächste Schritte (Option A)

### Phase 1: BigQuery Setup (Sofort)
1. Firebase Console → Analytics → BigQuery Linking → Enable
2. Warten auf ersten Export (~24h)
3. Queries in BigQuery Console testen

### Phase 2: Dashboard bauen (nach 24h)
1. Looker Studio öffnen
2. BigQuery Data Sources verbinden
3. Dashboard nach obigem Template bauen
4. Mit Team teilen

### Phase 3: Alerts einrichten (Optional)
1. Scheduled Queries in BigQuery erstellen
2. Email-Benachrichtigungen konfigurieren
3. Slack-Integration (optional)

### Phase 4: Monitoring (Laufend)
1. Dashboard täglich prüfen
2. Bei Margin <80%: Preise erhöhen oder Features optimieren
3. Bei Heavy Users: Limits anpassen

---

## Metriken-Definitionen

### Aktive Premium User
- **Definition**: Unique Users mit `subscription_status` = "monthly" oder "yearly"
- **Zeitraum**: Letzten 30 Tage aktiv
- **Datenquelle**: BigQuery User Properties

### Total AI-Calls
- **Definition**: Count von `ai_feature_used` Events
- **Zeitraum**: Heute/Woche/Monat
- **Datenquelle**: BigQuery Events

### Geschätzte Kosten
- **Definition**: SUM(`estimated_cost_usd`) aus `ai_feature_used` Events
- **Zeitraum**: Heute/Woche/Monat
- **Datenquelle**: BigQuery Events, berechnet mit AiCostCalculator

### Revenue
- **Definition**: Summe der Abo-Einnahmen (Jahres-Abos auf Monat umgerechnet)
- **Zeitraum**: Letzter Monat
- **Datenquelle**: `premium_subscribed` Events

### Margin
- **Formel**: `(Revenue - Costs) / Revenue * 100`
- **Ziel**: >80% Margin
- **Alert**: Wenn <80%

### Heavy User
- **Definition**: User mit >100 AI-Calls pro Monat
- **Bedeutung**: Überdurchschnittliche Nutzung, potentiell unprofitabel
- **Action**: Limits prüfen, ggf. Premium-Tier mit höherem Preis

---

## Privacy & GDPR

### Anonymisierung:
- ✅ Keine PII in BigQuery (Firebase `user_pseudo_id`)
- ✅ Keine Server-URLs oder IP-Adressen
- ✅ Keine Dokumentinhalte oder Dateinamen

### Data Retention:
- Firebase Analytics: 14 Monate (Standard)
- BigQuery: Unbegrenzt (manuell konfigurierbar)

### GDPR-Konformität:
- User kann Analytics ablehnen (AnalyticsConsentDialog)
- `setAnalyticsCollectionEnabled(false)` stoppt alle Tracking
- Daten-Export auf Anfrage möglich (BigQuery Export)

---

## Support & Dokumentation

### Firebase Analytics:
- Docs: https://firebase.google.com/docs/analytics
- BigQuery Export: https://support.google.com/firebase/answer/9037328

### Looker Studio:
- Docs: https://support.google.com/looker-studio
- Templates: https://lookerstudio.google.com/gallery

### BigQuery:
- SQL Reference: https://cloud.google.com/bigquery/docs/reference/standard-sql
- Pricing: https://cloud.google.com/bigquery/pricing

---

## Zusammenfassung

**Empfehlung**: Option A - Firebase + BigQuery + Looker Studio

**Aufwand**: 2-3 Stunden Setup, dann wartungsfrei

**Kosten**: ~$0.30/Monat bei 100 aktiven Usern

**ROI**: Profitabilitäts-Monitoring spart potentiell hunderte Euro durch frühzeitige Erkennung von Heavy Users und unprofitablen Features.

**Next Action**: BigQuery Export in Firebase Console aktivieren.
