/**
 * Monthly Analytics Report Generator
 *
 * Scheduled Cloud Function that generates a comprehensive monthly report
 * of AI usage, costs, revenue, and profitability metrics.
 *
 * Schedule: Runs on the 1st of each month at 9:00 AM Europe/Berlin
 */

import * as functions from "firebase-functions/v2";
import { BigQuery } from "@google-cloud/bigquery";
import * as sgMail from "@sendgrid/mail";

// Initialize BigQuery client
const bigquery = new BigQuery();

// Configuration
const PROJECT_ID = "paperless-scanner";
const DATASET_ID = "analytics_*"; // Wildcard for Firebase Analytics dataset

// SendGrid configuration (set API key via Firebase config)
const SENDGRID_API_KEY = process.env.SENDGRID_API_KEY || "";
const REPORT_EMAIL_TO = process.env.REPORT_EMAIL || "marcusmartini83@gmail.com";
const REPORT_EMAIL_FROM = process.env.SENDGRID_FROM_EMAIL || "analytics@paperless-scanner.app";

if (SENDGRID_API_KEY) {
  sgMail.setApiKey(SENDGRID_API_KEY);
}

/**
 * Monthly Analytics Report Data Structure
 */
interface MonthlyReportData {
  month: string;
  overview: {
    premiumUsers: number;
    totalCalls: number;
    avgCallsPerUser: number;
  };
  costs: {
    totalApiCostUsd: number;
    avgCostPerUser: number;
  };
  revenue: {
    monthlySubscriptions: number;
    yearlySubscriptions: number;
    monthlyRevenue: number;
    yearlyRevenue: number;
    totalRevenue: number;
  };
  profit: {
    grossRevenue: number;
    googleFee: number;
    apiCosts: number;
    netProfit: number;
    marginPercent: number;
  };
  topUsers: Array<{
    userHash: string;
    calls: number;
    costUsd: number;
  }>;
  alerts: string[];
}

/**
 * Scheduled function that runs monthly to generate analytics report
 */
export const monthlyAnalyticsReport = functions.scheduler.onSchedule({
  schedule: "0 9 1 * *", // Cron: 1st of month at 9:00 AM
  timeZone: "Europe/Berlin",
  region: "europe-west3",
}, async (event) => {
  functions.logger.info("Starting monthly analytics report generation", { date: new Date().toISOString() });

  try {
    // Generate report data
    const reportData = await generateReportData();

    // Format report as text
    const reportText = formatReportText(reportData);

    // Log report for Cloud Functions logs
    functions.logger.info("Monthly Report Generated", { report: reportText });

    // Send email if SendGrid is configured
    if (SENDGRID_API_KEY) {
      await sendEmailReport(reportData, reportText);
      functions.logger.info("Email report sent successfully");
    } else {
      functions.logger.warn("SendGrid API key not configured - email not sent");
    }

    return { success: true, report: reportData };
  } catch (error) {
    functions.logger.error("Error generating monthly report", error);
    throw error;
  }
});

/**
 * Generate report data by querying BigQuery
 */
async function generateReportData(): Promise<MonthlyReportData> {
  const currentDate = new Date();
  const lastMonth = new Date(currentDate.getFullYear(), currentDate.getMonth() - 1, 1);
  const monthName = lastMonth.toLocaleDateString("de-DE", { month: "long", year: "numeric" });

  functions.logger.info("Querying BigQuery for analytics data", { month: monthName });

  // Query 1: Overview metrics
  const overviewQuery = `
    SELECT
      COUNT(DISTINCT user_pseudo_id) as premium_users,
      COUNT(*) as total_calls,
      ROUND(AVG(calls_per_user), 1) as avg_calls_per_user
    FROM (
      SELECT
        user_pseudo_id,
        COUNT(*) as calls_per_user
      FROM \`${PROJECT_ID}.${DATASET_ID}.events_*\`
      WHERE event_name = 'ai_feature_used'
        AND _TABLE_SUFFIX BETWEEN
          FORMAT_DATE('%Y%m%d', DATE_TRUNC(DATE_SUB(CURRENT_DATE(), INTERVAL 1 MONTH), MONTH))
          AND FORMAT_DATE('%Y%m%d', LAST_DAY(DATE_SUB(CURRENT_DATE(), INTERVAL 1 MONTH)))
      GROUP BY user_pseudo_id
    )
  `;

  const [overviewRows] = await bigquery.query(overviewQuery);
  const overview = overviewRows[0] || { premium_users: 0, total_calls: 0, avg_calls_per_user: 0 };

  // Query 2: Cost metrics
  const costQuery = `
    SELECT
      ROUND(SUM(
        (SELECT value.double_value
         FROM UNNEST(event_params)
         WHERE key = 'estimated_cost_usd')
      ), 2) as total_api_cost_usd
    FROM \`${PROJECT_ID}.${DATASET_ID}.events_*\`
    WHERE event_name = 'ai_feature_used'
      AND _TABLE_SUFFIX BETWEEN
        FORMAT_DATE('%Y%m%d', DATE_TRUNC(DATE_SUB(CURRENT_DATE(), INTERVAL 1 MONTH), MONTH))
        AND FORMAT_DATE('%Y%m%d', LAST_DAY(DATE_SUB(CURRENT_DATE(), INTERVAL 1 MONTH)))
  `;

  const [costRows] = await bigquery.query(costQuery);
  const totalApiCostUsd = costRows[0]?.total_api_cost_usd || 0;
  const avgCostPerUser = overview.premium_users > 0 ? totalApiCostUsd / overview.premium_users : 0;

  // Query 3: Top users (anonymized)
  const topUsersQuery = `
    SELECT
      SUBSTR(user_pseudo_id, 1, 8) as user_hash,
      COUNT(*) as calls,
      ROUND(SUM(
        (SELECT value.double_value
         FROM UNNEST(event_params)
         WHERE key = 'estimated_cost_usd')
      ), 2) as cost_usd
    FROM \`${PROJECT_ID}.${DATASET_ID}.events_*\`
    WHERE event_name = 'ai_feature_used'
      AND _TABLE_SUFFIX BETWEEN
        FORMAT_DATE('%Y%m%d', DATE_TRUNC(DATE_SUB(CURRENT_DATE(), INTERVAL 1 MONTH), MONTH))
        AND FORMAT_DATE('%Y%m%d', LAST_DAY(DATE_SUB(CURRENT_DATE(), INTERVAL 1 MONTH)))
    GROUP BY user_pseudo_id
    HAVING calls > 50
    ORDER BY calls DESC
    LIMIT 10
  `;

  const [topUsersRows] = await bigquery.query(topUsersQuery);
  const topUsers = topUsersRows.map((row: { user_hash: string; calls: number; cost_usd: number }) => ({
    userHash: row.user_hash,
    calls: Number(row.calls),
    costUsd: Number(row.cost_usd),
  }));

  // Calculate revenue (placeholder - would come from Play Store API in production)
  // For now, using estimated values based on typical conversion rates
  const monthlySubscriptions = Math.round(overview.premium_users * 0.75); // 75% monthly
  const yearlySubscriptions = Math.round(overview.premium_users * 0.25); // 25% yearly

  const monthlyRevenue = monthlySubscriptions * 1.99;
  const yearlyRevenue = yearlySubscriptions * (14.99 / 12);
  const totalRevenue = monthlyRevenue + yearlyRevenue;

  // Calculate profit
  const googleFee = totalRevenue * 0.15; // 15% for first $1M
  const netProfit = totalRevenue - googleFee - totalApiCostUsd;
  const marginPercent = totalRevenue > 0 ? (netProfit / totalRevenue) * 100 : 0;

  // Generate alerts
  const alerts: string[] = [];
  if (marginPercent < 80) {
    alerts.push(`⚠️ Profit margin below 80% (${marginPercent.toFixed(1)}%)`);
  }
  if (avgCostPerUser > 1.00) {
    alerts.push(`⚠️ Average cost per user exceeds $1.00 ($${avgCostPerUser.toFixed(2)})`);
  }
  if (topUsers.some((user) => user.costUsd > 0.99)) {
    alerts.push("⚠️ Some users are costing more than 50% of subscription price");
  }
  if (alerts.length === 0) {
    alerts.push("✅ No critical alerts");
  }

  return {
    month: monthName,
    overview: {
      premiumUsers: Number(overview.premium_users),
      totalCalls: Number(overview.total_calls),
      avgCallsPerUser: Number(overview.avg_calls_per_user),
    },
    costs: {
      totalApiCostUsd,
      avgCostPerUser,
    },
    revenue: {
      monthlySubscriptions,
      yearlySubscriptions,
      monthlyRevenue,
      yearlyRevenue,
      totalRevenue,
    },
    profit: {
      grossRevenue: totalRevenue,
      googleFee,
      apiCosts: totalApiCostUsd,
      netProfit,
      marginPercent,
    },
    topUsers,
    alerts,
  };
}

/**
 * Format report data as human-readable text
 */
function formatReportText(data: MonthlyReportData): string {
  const topUsersText = data.topUsers
    .map((user, index) => `${index + 1}. User #${user.userHash}: ${user.calls} Calls ($${user.costUsd.toFixed(2)})`)
    .join("\n");

  return `
📊 AI Usage Report - ${data.month}
================================

ÜBERSICHT:
- Premium User: ${data.overview.premiumUsers}
- AI Calls gesamt: ${data.overview.totalCalls.toLocaleString("de-DE")}
- Ø Calls/User: ${data.overview.avgCallsPerUser}

KOSTEN:
- API-Kosten: $${data.costs.totalApiCostUsd.toFixed(2)}
- Ø Kosten/User: $${data.costs.avgCostPerUser.toFixed(3)}

EINNAHMEN:
- Monatlich: ${data.revenue.monthlySubscriptions} × €1.99 = €${data.revenue.monthlyRevenue.toFixed(2)}
- Jährlich: ${data.revenue.yearlySubscriptions} × €14.99/12 = €${data.revenue.yearlyRevenue.toFixed(2)}
- Gesamt: €${data.revenue.totalRevenue.toFixed(2)}

PROFIT:
- Brutto: €${data.profit.grossRevenue.toFixed(2)}
- - Google Fee (15%): €${data.profit.googleFee.toFixed(2)}
- - API-Kosten: €${data.profit.apiCosts.toFixed(2)}
- = Netto: €${data.profit.netProfit.toFixed(2)}
- Marge: ${data.profit.marginPercent.toFixed(1)}% ${data.profit.marginPercent >= 80 ? "✅" : "⚠️"}

TOP USERS (anonymisiert):
${topUsersText || "- Keine Heavy User"}

ALERTS:
${data.alerts.map((alert) => `- ${alert}`).join("\n")}

---
Dashboard: https://lookerstudio.google.com/reporting/YOUR_DASHBOARD_ID
BigQuery: https://console.cloud.google.com/bigquery?project=${PROJECT_ID}
`.trim();
}

/**
 * Send email report via SendGrid
 */
async function sendEmailReport(data: MonthlyReportData, reportText: string): Promise<void> {
  const msg = {
    to: REPORT_EMAIL_TO,
    from: REPORT_EMAIL_FROM,
    subject: `📊 AI Usage Report - ${data.month}`,
    text: reportText,
    html: `
      <html>
        <head>
          <style>
            body {
              font-family: 'Courier New', monospace;
              background-color: #0A0A0A;
              color: #E1FF8D;
              padding: 20px;
            }
            .report {
              background-color: #141414;
              border: 1px solid #27272A;
              border-radius: 8px;
              padding: 20px;
              max-width: 800px;
              margin: 0 auto;
            }
            h1 {
              color: #E1FF8D;
              border-bottom: 2px solid #E1FF8D;
              padding-bottom: 10px;
            }
            .section {
              margin: 20px 0;
            }
            .metric {
              margin: 5px 0;
              padding-left: 20px;
            }
            .alert {
              background-color: #1F1F1F;
              border-left: 4px solid #E1FF8D;
              padding: 10px;
              margin: 10px 0;
            }
            .positive {
              color: #4ADE80;
            }
            .warning {
              color: #FFA500;
            }
          </style>
        </head>
        <body>
          <div class="report">
            <h1>📊 AI Usage Report - ${data.month}</h1>

            <div class="section">
              <h2>ÜBERSICHT</h2>
              <div class="metric">Premium User: <strong>${data.overview.premiumUsers}</strong></div>
              <div class="metric">AI Calls gesamt: <strong>${data.overview.totalCalls.toLocaleString("de-DE")}</strong></div>
              <div class="metric">Ø Calls/User: <strong>${data.overview.avgCallsPerUser}</strong></div>
            </div>

            <div class="section">
              <h2>KOSTEN</h2>
              <div class="metric">API-Kosten: <strong>$${data.costs.totalApiCostUsd.toFixed(2)}</strong></div>
              <div class="metric">Ø Kosten/User: <strong>$${data.costs.avgCostPerUser.toFixed(3)}</strong></div>
            </div>

            <div class="section">
              <h2>EINNAHMEN</h2>
              <div class="metric">Monatlich: ${data.revenue.monthlySubscriptions} × €1.99 = <strong>€${data.revenue.monthlyRevenue.toFixed(2)}</strong></div>
              <div class="metric">Jährlich: ${data.revenue.yearlySubscriptions} × €14.99/12 = <strong>€${data.revenue.yearlyRevenue.toFixed(2)}</strong></div>
              <div class="metric">Gesamt: <strong>€${data.revenue.totalRevenue.toFixed(2)}</strong></div>
            </div>

            <div class="section">
              <h2>PROFIT</h2>
              <div class="metric">Brutto: €${data.profit.grossRevenue.toFixed(2)}</div>
              <div class="metric">- Google Fee (15%): €${data.profit.googleFee.toFixed(2)}</div>
              <div class="metric">- API-Kosten: €${data.profit.apiCosts.toFixed(2)}</div>
              <div class="metric">= Netto: <strong class="positive">€${data.profit.netProfit.toFixed(2)}</strong></div>
              <div class="metric">Marge: <strong class="${data.profit.marginPercent >= 80 ? "positive" : "warning"}">${data.profit.marginPercent.toFixed(1)}%</strong></div>
            </div>

            <div class="section">
              <h2>TOP USERS (anonymisiert)</h2>
              ${data.topUsers.map((user, index) => `
                <div class="metric">${index + 1}. User #${user.userHash}: ${user.calls} Calls ($${user.costUsd.toFixed(2)})</div>
              `).join("")}
            </div>

            <div class="section">
              <h2>ALERTS</h2>
              ${data.alerts.map((alert) => `
                <div class="alert">${alert}</div>
              `).join("")}
            </div>

            <div class="section">
              <p>
                <a href="https://console.cloud.google.com/bigquery?project=${PROJECT_ID}" style="color: #E1FF8D;">View in BigQuery</a>
              </p>
            </div>
          </div>
        </body>
      </html>
    `,
  };

  await sgMail.send(msg);
}
