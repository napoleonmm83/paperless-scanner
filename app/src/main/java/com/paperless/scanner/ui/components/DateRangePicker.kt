package com.paperless.scanner.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paperless.scanner.R
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/**
 * Date Range Picker component for document filtering.
 *
 * Features:
 * - Material 3 DateRangePicker dialog
 * - ISO 8601 date format (YYYY-MM-DD)
 * - Quick shortcuts (Today, Last 7 Days, Last 30 Days)
 * - Clear button to reset selection
 * - Dark Tech Precision Pro Style (20dp corners, 1dp border, no elevation)
 *
 * Usage:
 * ```kotlin
 * DateRangePickerField(
 *     label = "Created Date",
 *     startDate = filter.createdDateFrom,
 *     endDate = filter.createdDateTo,
 *     onDateRangeSelected = { start, end ->
 *         updateFilter { it.copy(createdDateFrom = start, createdDateTo = end) }
 *     }
 * )
 * ```
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateRangePickerField(
    label: String,
    startDate: String?,
    endDate: String?,
    onDateRangeSelected: (start: String?, end: String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var showDialog by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Label
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Display selected date range or "Not Set"
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val dateRangeText = if (startDate != null && endDate != null) {
                    formatDateRange(startDate, endDate)
                } else if (startDate != null) {
                    "Ab ${formatDisplayDate(startDate)}"
                } else if (endDate != null) {
                    "Bis ${formatDisplayDate(endDate)}"
                } else {
                    stringResource(R.string.date_range_not_set)
                }

                Text(
                    text = dateRangeText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (startDate != null || endDate != null) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f)
                )

                // Clear button (only if date is set)
                if (startDate != null || endDate != null) {
                    IconButton(onClick = { onDateRangeSelected(null, null) }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.date_range_clear),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Select Date Range button
            OutlinedButton(
                onClick = { showDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Filled.CalendarToday,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.date_range_select))
            }
        }
    }

    // Date Range Picker Dialog
    if (showDialog) {
        DateRangePickerDialog(
            onDismiss = { showDialog = false },
            onConfirm = { start, end ->
                onDateRangeSelected(start, end)
                showDialog = false
            },
            initialStartDate = startDate,
            initialEndDate = endDate
        )
    }
}

/**
 * Date Range Picker Dialog with shortcuts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangePickerDialog(
    onDismiss: () -> Unit,
    onConfirm: (start: String?, end: String?) -> Unit,
    initialStartDate: String?,
    initialEndDate: String?
) {
    // Convert ISO dates to milliseconds for DateRangePicker
    val initialStartMillis = initialStartDate?.let { parseIsoDateToMillis(it) }
    val initialEndMillis = initialEndDate?.let { parseIsoDateToMillis(it) }

    val dateRangePickerState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initialStartMillis,
        initialSelectedEndDateMillis = initialEndMillis
    )

    val confirmEnabled by remember {
        derivedStateOf {
            dateRangePickerState.selectedStartDateMillis != null &&
                    dateRangePickerState.selectedEndDateMillis != null
        }
    }

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = {
                    val startMillis = dateRangePickerState.selectedStartDateMillis
                    val endMillis = dateRangePickerState.selectedEndDateMillis

                    val startDate = startMillis?.let { formatMillisToIsoDate(it) }
                    val endDate = endMillis?.let { formatMillisToIsoDate(it) }

                    onConfirm(startDate, endDate)
                },
                enabled = confirmEnabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(stringResource(R.string.date_range_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.date_range_cancel))
            }
        }
    ) {
        Column {
            // DateRangePicker
            DateRangePicker(
                state = dateRangePickerState,
                modifier = Modifier.weight(1f)
            )

            // Quick Shortcuts
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Today
                OutlinedButton(
                    onClick = {
                        val todayMillis = System.currentTimeMillis()
                        dateRangePickerState.setSelection(todayMillis, todayMillis)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.date_range_today))
                }

                // Last 7 Days
                OutlinedButton(
                    onClick = {
                        val endMillis = System.currentTimeMillis()
                        val startMillis = endMillis - (7L * 24 * 60 * 60 * 1000)
                        dateRangePickerState.setSelection(startMillis, endMillis)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.date_range_last_7_days))
                }

                // Last 30 Days
                OutlinedButton(
                    onClick = {
                        val endMillis = System.currentTimeMillis()
                        val startMillis = endMillis - (30L * 24 * 60 * 60 * 1000)
                        dateRangePickerState.setSelection(startMillis, endMillis)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.date_range_last_30_days))
                }
            }
        }
    }
}

/**
 * Format date range for display.
 * Example: "01.01.2024 - 31.01.2024"
 */
private fun formatDateRange(startDate: String, endDate: String): String {
    val start = formatDisplayDate(startDate)
    val end = formatDisplayDate(endDate)
    return "$start - $end"
}

/**
 * Format ISO date (YYYY-MM-DD) to display format (DD.MM.YYYY).
 */
private fun formatDisplayDate(isoDate: String): String {
    return try {
        val localDate = LocalDate.parse(isoDate, DateTimeFormatter.ISO_DATE)
        localDate.format(DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMAN))
    } catch (e: Exception) {
        isoDate
    }
}

/**
 * Parse ISO date string (YYYY-MM-DD) to milliseconds since epoch.
 */
private fun parseIsoDateToMillis(isoDate: String): Long? {
    return try {
        val localDate = LocalDate.parse(isoDate, DateTimeFormatter.ISO_DATE)
        localDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    } catch (e: Exception) {
        null
    }
}

/**
 * Format milliseconds since epoch to ISO date string (YYYY-MM-DD).
 */
private fun formatMillisToIsoDate(millis: Long): String {
    val instant = Instant.ofEpochMilli(millis)
    val localDate = instant.atZone(ZoneId.systemDefault()).toLocalDate()
    return localDate.format(DateTimeFormatter.ISO_DATE)
}
