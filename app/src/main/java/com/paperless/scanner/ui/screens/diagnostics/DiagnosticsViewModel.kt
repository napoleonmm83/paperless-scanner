package com.paperless.scanner.ui.screens.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.models.ServerStatusResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException
import javax.inject.Inject

/**
 * Health status of the server system.
 */
enum class HealthStatus {
    GOOD,       // All systems operational
    WARNING,    // Some non-critical issues (low storage, etc.)
    CRITICAL,   // Critical issues (DB error, celery down, etc.)
    UNKNOWN     // Unable to determine (403, network error, etc.)
}

data class DiagnosticsUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val serverStatus: ServerStatusResponse? = null,
    val healthStatus: HealthStatus = HealthStatus.UNKNOWN,
    val isUnavailable: Boolean = false  // True if 403 (no admin permission)
)

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val api: PaperlessApi
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiagnosticsUiState())
    val uiState: StateFlow<DiagnosticsUiState> = _uiState.asStateFlow()

    init {
        loadDiagnostics()
    }

    fun loadDiagnostics() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            try {
                val response = api.getServerStatus()

                if (!response.isSuccessful) {
                    throw retrofit2.HttpException(response)
                }

                val body = response.body() ?: throw Exception("Empty response body")

                // Extract version from x-version header if not in body
                val headerVersion = response.headers()["x-version"]?.takeIf { it.isNotBlank() }
                val version = body.paperlessVersion?.takeIf { it.isNotBlank() } ?: headerVersion

                // Create updated response with version from header
                val serverStatus = body.copy(paperlessVersion = version)

                val health = calculateHealthStatus(serverStatus)

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = null,
                        serverStatus = serverStatus,
                        healthStatus = health,
                        isUnavailable = false
                    )
                }
            } catch (e: HttpException) {
                when (e.code()) {
                    403 -> {
                        // No admin permission - show unavailable state
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = null,
                                isUnavailable = true,
                                healthStatus = HealthStatus.UNKNOWN
                            )
                        }
                    }
                    404 -> {
                        // Old Paperless version without /api/status/
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = "Endpoint not supported by this Paperless version",
                                healthStatus = HealthStatus.UNKNOWN
                            )
                        }
                    }
                    else -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = "HTTP ${e.code()}: ${e.message()}",
                                healthStatus = HealthStatus.UNKNOWN
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Unknown error",
                        healthStatus = HealthStatus.UNKNOWN
                    )
                }
            }
        }
    }

    /**
     * Calculate overall system health from server status response.
     */
    private fun calculateHealthStatus(response: ServerStatusResponse): HealthStatus {
        var hasCritical = false
        var hasWarning = false

        // Check database status
        if (response.database?.status == "ERROR") {
            hasCritical = true
        }

        // Check task system (Redis/Celery)
        if (response.tasks?.redisStatus == "ERROR" || response.tasks?.celeryStatus == "ERROR") {
            hasCritical = true
        }

        // Check storage availability
        response.storage?.let { storage ->
            if (storage.available != null && storage.total != null && storage.total > 0) {
                val availablePercentage = (storage.available.toDouble() / storage.total.toDouble()) * 100
                when {
                    availablePercentage < 5 -> hasCritical = true  // Less than 5% available
                    availablePercentage < 15 -> hasWarning = true  // Less than 15% available
                }
            }
        }

        // Check pending migrations
        if (response.database?.migrationStatus?.unappliedMigrations != null &&
            response.database.migrationStatus.unappliedMigrations!!.isNotEmpty()
        ) {
            hasWarning = true
        }

        return when {
            hasCritical -> HealthStatus.CRITICAL
            hasWarning -> HealthStatus.WARNING
            else -> HealthStatus.GOOD
        }
    }

    /**
     * Format bytes to human-readable string (GB/TB).
     */
    fun formatBytes(bytes: Long): String {
        val absoluteBytes = kotlin.math.abs(bytes)
        val sign = if (bytes < 0) "-" else ""

        val formatted = when {
            absoluteBytes >= 1_000_000_000_000 -> "%.2f TB".format(absoluteBytes / 1_000_000_000_000.0)
            absoluteBytes >= 1_000_000_000 -> "%.2f GB".format(absoluteBytes / 1_000_000_000.0)
            absoluteBytes >= 1_000_000 -> "%.2f MB".format(absoluteBytes / 1_000_000.0)
            else -> "%.2f KB".format(absoluteBytes / 1000.0)
        }

        return "$sign$formatted"
    }

    /**
     * Calculate percentage of used storage.
     */
    fun calculateUsedPercentage(available: Long, total: Long): Int {
        if (total == 0L) return 0
        if (available > total) return 0  // Edge case: available > total (data inconsistency)
        if (available < 0) return 100  // Edge case: negative available (data inconsistency)
        return ((total - available).toDouble() / total.toDouble() * 100).toInt().coerceIn(0, 100)
    }
}
