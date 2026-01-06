package com.paperless.scanner.util

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Centralized date formatting utility for consistent date display across the app.
 *
 * BEST PRACTICE: Single source of truth for date formatting reduces duplication
 * and ensures consistent date display throughout the application.
 */
object DateFormatter {

    private val INPUT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    private val OUTPUT_DATE_ONLY = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val OUTPUT_DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm")

    /**
     * Formats ISO datetime string to short date format: dd.MM.yyyy
     *
     * @param dateString ISO 8601 datetime string (e.g., "2024-12-01T14:30:00")
     * @return Formatted date string (e.g., "01.12.2024") or first 10 chars on parse error
     */
    fun formatDateShort(dateString: String): String {
        return try {
            val dateTime = LocalDateTime.parse(dateString.take(19), INPUT_FORMATTER)
            dateTime.format(OUTPUT_DATE_ONLY)
        } catch (e: DateTimeParseException) {
            dateString.take(10)
        }
    }

    /**
     * Formats ISO datetime string to date + time format: dd.MM.yyyy, HH:mm
     *
     * @param dateString ISO 8601 datetime string (e.g., "2024-12-01T14:30:00")
     * @return Formatted datetime string (e.g., "01.12.2024, 14:30") or first 10 chars on parse error
     */
    fun formatDateWithTime(dateString: String): String {
        return try {
            val dateTime = LocalDateTime.parse(dateString.take(19), INPUT_FORMATTER)
            dateTime.format(OUTPUT_DATE_TIME)
        } catch (e: DateTimeParseException) {
            dateString.take(10)
        }
    }
}
