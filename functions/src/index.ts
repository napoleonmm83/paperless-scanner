/**
 * Firebase Cloud Functions for Paperless Scanner Analytics
 *
 * These functions provide automated reporting and analytics for AI usage tracking.
 */

import * as functions from "firebase-functions/v2";
import * as admin from "firebase-admin";

// Initialize Firebase Admin SDK
admin.initializeApp();

// Export all functions
export { monthlyAnalyticsReport } from "./monthlyReport";
