package com.paperless.scanner.util

import android.content.Context
import com.paperless.scanner.R
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Format an ISO date string as a relative time-ago string.
 *
 * Accepts both Paperless API format ("2026-01-03T10:05:00.156005+01:00")
 * and local datetime ("2026-01-03T10:05:00"). Returns localized strings for
 * "just now", "X minutes ago", "X hours ago", "X days ago", or a dd.MM.yyyy
 * date once older than a week.
 */
fun formatTimeAgo(context: Context, dateString: String): String {
    return try {
        val localDateTime = try {
            val zonedDateTime = ZonedDateTime.parse(dateString, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            zonedDateTime.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
        } catch (e: Exception) {
            LocalDateTime.parse(dateString, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        }

        val now = LocalDateTime.now()
        val duration = Duration.between(localDateTime, now)

        val diffMinutes = duration.toMinutes()
        val diffHours = duration.toHours()
        val diffDays = duration.toDays()

        when {
            diffMinutes < 1 -> context.getString(R.string.time_just_now)
            diffMinutes < 60 -> context.getString(R.string.time_minutes_ago, diffMinutes.toInt())
            diffHours < 24 -> context.getString(R.string.time_hours_ago, diffHours.toInt())
            diffDays < 7 -> context.getString(R.string.time_days_ago, diffDays.toInt())
            else -> {
                val outputFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
                localDateTime.format(outputFormatter)
            }
        }
    } catch (e: Exception) {
        context.getString(R.string.time_unknown)
    }
}
