package com.paperless.scanner.data.database.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local database entity for tracking AI feature usage.
 *
 * This table stores anonymized usage data for:
 * - Cost tracking and monthly limits
 * - Usage statistics and aggregations
 * - Offline resilience (syncs to Firebase when online)
 *
 * Privacy: No PII is stored, only feature usage metrics.
 */
@Entity(
    tableName = "ai_usage_logs",
    indices = [
        Index(value = ["subscriptionMonth"]),
        Index(value = ["timestamp"]),
        Index(value = ["featureType"])
    ]
)
data class AiUsageLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /**
     * Timestamp when AI feature was used (milliseconds since epoch)
     */
    val timestamp: Long = System.currentTimeMillis(),

    /**
     * Type of AI feature used:
     * - "analyze_image" - Image OCR analysis
     * - "analyze_pdf" - PDF content analysis
     * - "suggest_tags" - AI-generated tag suggestions
     * - "generate_title" - Document title generation
     * - "generate_summary" - Document summary
     */
    val featureType: String,

    /**
     * Number of input tokens sent to AI API
     */
    val inputTokens: Int,

    /**
     * Number of output tokens received from AI API
     */
    val outputTokens: Int,

    /**
     * Estimated cost in USD (calculated using Gemini Flash pricing)
     */
    val estimatedCostUsd: Double,

    /**
     * Whether the AI operation succeeded
     */
    val success: Boolean = true,

    /**
     * Subscription month in format "YYYY-MM" (e.g., "2026-01")
     * Used for aggregating monthly usage and applying limits
     */
    val subscriptionMonth: String,

    /**
     * User's subscription type at time of usage: "free", "monthly", "yearly"
     * Used for analytics segmentation
     */
    val subscriptionType: String = "free"
)
