package com.paperless.scanner.data.repository

import com.paperless.scanner.data.analytics.AiCostCalculator
import com.paperless.scanner.data.database.dao.AiUsageDao
import com.paperless.scanner.data.database.dao.DailyUsageStats
import com.paperless.scanner.data.database.dao.FeatureUsageStats
import com.paperless.scanner.data.database.entities.AiUsageLog
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for AI usage tracking and analytics.
 *
 * Responsibilities:
 * - Log AI feature usage to local database
 * - Track monthly usage for rate limiting
 * - Provide aggregated statistics
 * - Clean up old data (GDPR compliance)
 *
 * All operations are privacy-preserving and GDPR-compliant.
 */
@Singleton
class AiUsageRepository @Inject constructor(
    private val aiUsageDao: AiUsageDao
) {

    companion object {
        private const val RETENTION_DAYS = 60 // Keep logs for 60 days (GDPR compliant)
        private val MONTH_FORMAT = SimpleDateFormat("yyyy-MM", Locale.US)
    }

    /**
     * Log an AI feature usage.
     *
     * @param featureType Type of AI feature used
     * @param inputTokens Number of input tokens
     * @param outputTokens Number of output tokens
     * @param success Whether the operation succeeded
     * @param subscriptionType User's subscription type
     * @return Log entry ID
     */
    suspend fun logUsage(
        featureType: String,
        inputTokens: Int,
        outputTokens: Int,
        success: Boolean = true,
        subscriptionType: String = "free"
    ): Long {
        val costUsd = AiCostCalculator.calculateCost(inputTokens, outputTokens)
        val currentMonth = getCurrentMonth()

        val log = AiUsageLog(
            timestamp = System.currentTimeMillis(),
            featureType = featureType,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            estimatedCostUsd = costUsd,
            success = success,
            subscriptionMonth = currentMonth,
            subscriptionType = subscriptionType
        )

        return aiUsageDao.insert(log)
    }

    /**
     * Get current month's call count.
     * Used for enforcing usage limits.
     *
     * @return Number of AI calls this month
     */
    suspend fun getCurrentMonthCallCount(): Int {
        return aiUsageDao.getCurrentMonthCallCount()
    }

    /**
     * Observe current month's call count in real-time.
     * Updates automatically when new logs are added.
     *
     * @return Flow of current month call count
     */
    fun observeCurrentMonthCallCount(): Flow<Int> {
        return aiUsageDao.observeCurrentMonthCallCount()
    }

    /**
     * Get monthly call count for a specific month.
     *
     * @param year Year (e.g., 2026)
     * @param month Month (1-12)
     * @return Number of calls in that month
     */
    suspend fun getMonthlyCallCount(year: Int, month: Int): Int {
        val subscriptionMonth = formatMonth(year, month)
        return aiUsageDao.getMonthlyCallCount(subscriptionMonth)
    }

    /**
     * Get current month's total cost.
     *
     * @return Total cost in USD
     */
    suspend fun getCurrentMonthCost(): Double {
        return aiUsageDao.getCurrentMonthCost() ?: 0.0
    }

    /**
     * Get monthly cost for a specific month.
     *
     * @param year Year (e.g., 2026)
     * @param month Month (1-12)
     * @return Total cost in USD
     */
    suspend fun getMonthlyCost(year: Int, month: Int): Double {
        val subscriptionMonth = formatMonth(year, month)
        return aiUsageDao.getMonthlyCost(subscriptionMonth) ?: 0.0
    }

    /**
     * Get usage statistics by feature type for current month.
     *
     * @return List of feature usage stats
     */
    suspend fun getCurrentMonthFeatureStats(): List<FeatureUsageStats> {
        val currentMonth = getCurrentMonth()
        return aiUsageDao.getMonthlyFeatureStats(currentMonth)
    }

    /**
     * Get daily usage statistics for current month.
     * Useful for charts showing usage trends.
     *
     * @return List of daily stats
     */
    suspend fun getCurrentMonthDailyStats(): List<DailyUsageStats> {
        val currentMonth = getCurrentMonth()
        return aiUsageDao.getDailyStats(currentMonth)
    }

    /**
     * Get recent AI usage logs.
     *
     * @param limit Number of logs to retrieve
     * @return List of recent logs
     */
    suspend fun getRecentLogs(limit: Int = 50): List<AiUsageLog> {
        return aiUsageDao.getRecentLogs(limit)
    }

    /**
     * Check if user has any AI usage in current month.
     * Useful for showing "first usage" UI hints.
     *
     * @return True if user has used AI this month
     */
    suspend fun hasCurrentMonthUsage(): Boolean {
        return aiUsageDao.hasCurrentMonthLogs()
    }

    /**
     * Clean up old logs based on retention policy.
     * Should be called periodically (e.g., daily background job).
     *
     * Deletes logs older than 60 days (GDPR compliance).
     *
     * @return Number of deleted logs
     */
    suspend fun cleanupOldLogs(): Int {
        val cutoffTimestamp = System.currentTimeMillis() - (RETENTION_DAYS * 24 * 60 * 60 * 1000L)
        return aiUsageDao.deleteOldLogs(cutoffTimestamp)
    }

    /**
     * Get total number of logs in database.
     * Useful for debugging and storage monitoring.
     *
     * @return Total log count
     */
    suspend fun getTotalLogCount(): Int {
        return aiUsageDao.getTotalLogCount()
    }

    /**
     * Delete all logs for a specific month.
     * Used for testing or manual data cleanup.
     *
     * @param year Year (e.g., 2026)
     * @param month Month (1-12)
     * @return Number of deleted logs
     */
    suspend fun deleteMonthlyLogs(year: Int, month: Int): Int {
        val subscriptionMonth = formatMonth(year, month)
        return aiUsageDao.deleteMonthlyLogs(subscriptionMonth)
    }

    // ==================== Helper Methods ====================

    /**
     * Get current month in "YYYY-MM" format.
     */
    private fun getCurrentMonth(): String {
        return MONTH_FORMAT.format(Date())
    }

    /**
     * Format year and month to "YYYY-MM" format.
     */
    private fun formatMonth(year: Int, month: Int): String {
        return String.format(Locale.US, "%04d-%02d", year, month)
    }

    // ==================== Usage Limit Helpers ====================

    /**
     * Check if user is approaching monthly usage limit.
     *
     * @return UsageLimitStatus enum
     */
    suspend fun checkUsageLimit(): UsageLimitStatus {
        val currentCount = getCurrentMonthCallCount()

        return when {
            currentCount >= 300 -> UsageLimitStatus.HARD_LIMIT_REACHED
            currentCount >= 200 -> UsageLimitStatus.SOFT_LIMIT_200
            currentCount >= 100 -> UsageLimitStatus.SOFT_LIMIT_100
            else -> UsageLimitStatus.WITHIN_LIMITS
        }
    }

    /**
     * Calculate remaining AI calls before hitting hard limit.
     *
     * @return Number of remaining calls (0 if limit reached)
     */
    suspend fun getRemainingCalls(): Int {
        val currentCount = getCurrentMonthCallCount()
        val remaining = 300 - currentCount
        return remaining.coerceAtLeast(0)
    }

    /**
     * Get progress towards hard limit as percentage (0-100).
     *
     * @return Percentage of hard limit used
     */
    suspend fun getLimitUsagePercentage(): Int {
        val currentCount = getCurrentMonthCallCount()
        val percentage = (currentCount.toFloat() / 300 * 100).toInt()
        return percentage.coerceIn(0, 100)
    }
}

/**
 * Enum representing usage limit status.
 */
enum class UsageLimitStatus {
    /** User is within normal usage limits (<100 calls) */
    WITHIN_LIMITS,

    /** Soft limit 1: User has made 100+ calls (show info message) */
    SOFT_LIMIT_100,

    /** Soft limit 2: User has made 200+ calls (show warning) */
    SOFT_LIMIT_200,

    /** Hard limit: User has made 300+ calls (block AI features) */
    HARD_LIMIT_REACHED
}
