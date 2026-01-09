package com.paperless.scanner.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.paperless.scanner.data.database.entities.AiUsageLog
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for AI usage logs.
 *
 * Provides:
 * - Insert operations for logging AI usage
 * - Aggregation queries for usage statistics
 * - Monthly limit tracking
 * - Cost calculations
 */
@Dao
interface AiUsageDao {

    /**
     * Insert a new AI usage log entry
     */
    @Insert
    suspend fun insert(log: AiUsageLog): Long

    /**
     * Get total AI calls for a specific month.
     * Used for enforcing monthly usage limits.
     *
     * @param subscriptionMonth Format: "YYYY-MM"
     * @return Total number of AI calls in the month
     */
    @Query("SELECT COUNT(*) FROM ai_usage_logs WHERE subscriptionMonth = :subscriptionMonth")
    suspend fun getMonthlyCallCount(subscriptionMonth: String): Int

    /**
     * Observe monthly call count in real-time.
     * Useful for UI updates and live limit warnings.
     *
     * @param subscriptionMonth Format: "YYYY-MM"
     * @return Flow of call count that updates on database changes
     */
    @Query("SELECT COUNT(*) FROM ai_usage_logs WHERE subscriptionMonth = :subscriptionMonth")
    fun observeMonthlyCallCount(subscriptionMonth: String): Flow<Int>

    /**
     * Get total AI calls for current month.
     * Convenience method that uses current date.
     */
    @Query("""
        SELECT COUNT(*) FROM ai_usage_logs
        WHERE subscriptionMonth = strftime('%Y-%m', 'now')
    """)
    suspend fun getCurrentMonthCallCount(): Int

    /**
     * Observe current month call count.
     */
    @Query("""
        SELECT COUNT(*) FROM ai_usage_logs
        WHERE subscriptionMonth = strftime('%Y-%m', 'now')
    """)
    fun observeCurrentMonthCallCount(): Flow<Int>

    /**
     * Get total estimated cost for a specific month.
     *
     * @param subscriptionMonth Format: "YYYY-MM"
     * @return Total cost in USD
     */
    @Query("SELECT SUM(estimatedCostUsd) FROM ai_usage_logs WHERE subscriptionMonth = :subscriptionMonth")
    suspend fun getMonthlyCost(subscriptionMonth: String): Double?

    /**
     * Get total estimated cost for current month.
     */
    @Query("""
        SELECT SUM(estimatedCostUsd) FROM ai_usage_logs
        WHERE subscriptionMonth = strftime('%Y-%m', 'now')
    """)
    suspend fun getCurrentMonthCost(): Double?

    /**
     * Get usage statistics by feature type for a specific month.
     *
     * @param subscriptionMonth Format: "YYYY-MM"
     * @return List of feature usage stats
     */
    @Query("""
        SELECT
            featureType,
            COUNT(*) as callCount,
            SUM(estimatedCostUsd) as totalCost,
            AVG(inputTokens) as avgInputTokens,
            AVG(outputTokens) as avgOutputTokens
        FROM ai_usage_logs
        WHERE subscriptionMonth = :subscriptionMonth
        GROUP BY featureType
        ORDER BY callCount DESC
    """)
    suspend fun getMonthlyFeatureStats(subscriptionMonth: String): List<FeatureUsageStats>

    /**
     * Get daily usage statistics for a specific month.
     * Useful for charts and trending analysis.
     *
     * @param subscriptionMonth Format: "YYYY-MM"
     */
    @Query("""
        SELECT
            date(timestamp / 1000, 'unixepoch') as date,
            COUNT(*) as callCount,
            SUM(estimatedCostUsd) as totalCost
        FROM ai_usage_logs
        WHERE subscriptionMonth = :subscriptionMonth
        GROUP BY date
        ORDER BY date ASC
    """)
    suspend fun getDailyStats(subscriptionMonth: String): List<DailyUsageStats>

    /**
     * Get all logs for a specific month.
     * Used for export or detailed analysis.
     *
     * @param subscriptionMonth Format: "YYYY-MM"
     */
    @Query("SELECT * FROM ai_usage_logs WHERE subscriptionMonth = :subscriptionMonth ORDER BY timestamp DESC")
    suspend fun getMonthlyLogs(subscriptionMonth: String): List<AiUsageLog>

    /**
     * Get recent logs (last N entries).
     * Useful for debugging or showing recent activity.
     *
     * @param limit Number of logs to retrieve
     */
    @Query("SELECT * FROM ai_usage_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentLogs(limit: Int = 50): List<AiUsageLog>

    /**
     * Delete logs older than a specific timestamp.
     * Used for cleanup and GDPR compliance (60-day retention).
     *
     * @param beforeTimestamp Milliseconds since epoch
     * @return Number of deleted rows
     */
    @Query("DELETE FROM ai_usage_logs WHERE timestamp < :beforeTimestamp")
    suspend fun deleteOldLogs(beforeTimestamp: Long): Int

    /**
     * Delete all logs for a specific month.
     * Used for testing or manual cleanup.
     *
     * @param subscriptionMonth Format: "YYYY-MM"
     */
    @Query("DELETE FROM ai_usage_logs WHERE subscriptionMonth = :subscriptionMonth")
    suspend fun deleteMonthlyLogs(subscriptionMonth: String): Int

    /**
     * Get total number of logs in database.
     */
    @Query("SELECT COUNT(*) FROM ai_usage_logs")
    suspend fun getTotalLogCount(): Int

    /**
     * Check if any logs exist for current month.
     * Quick check for UI to show "first usage" messages.
     */
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM ai_usage_logs
            WHERE subscriptionMonth = strftime('%Y-%m', 'now')
            LIMIT 1
        )
    """)
    suspend fun hasCurrentMonthLogs(): Boolean
}

/**
 * Data class for feature usage statistics aggregation.
 */
data class FeatureUsageStats(
    val featureType: String,
    val callCount: Int,
    val totalCost: Double,
    val avgInputTokens: Double,
    val avgOutputTokens: Double
)

/**
 * Data class for daily usage statistics aggregation.
 */
data class DailyUsageStats(
    val date: String,
    val callCount: Int,
    val totalCost: Double
)
